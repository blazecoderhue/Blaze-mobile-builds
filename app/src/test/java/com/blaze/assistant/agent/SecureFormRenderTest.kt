package com.blaze.assistant.agent

import com.blaze.assistant.browser.SecureInput
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 0.45.19: "sign in with my email and password" must end on the secure form card, never raw code in a bubble.
 * Every scenario runs 5 times (strict 5/5, deterministic): the cloud tool_calls event, each text-call form a model
 * wrote (Python single / multi-line, JSON, mid-prose JSON, tags, fenced blocks), cut-off payloads (no raw text, an
 * honest line), reconnect / re-sent deltas (no doubled or re-leaked text) and the chat-credentials guard (CredAsk).
 */
class SecureFormRenderTest {
    private fun tool(name: String, vararg params: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name)
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().apply { params.forEach { put(it, JSONObject().put("type", "string")) } })
            .put("required", JSONArray(params.take(1)))))

    private val tools = JSONArray().put(tool("browser_secure_input", "fields", "title", "reason", "submit")).put(tool("browser_open", "url"))
        .put(tool("browser_type", "number", "text")).put(tool("ask_user", "question")).put(tool("web_search", "query")).put(tool("tap", "text"))
    private val names = ToolCalls.names(tools)

    /** Raw call text that must never be in a bubble. */
    private val raw = listOf("browser_secure", "fields", "\"name\"", "'name'", "arguments", "<tool", "<function", "tool_call", "kind")

    private fun assertClean(what: String, shown: String) {
        for (r in raw) assertFalse("$what shows raw `$r`: <<$shown>>", shown.contains(r))
    }

    private val FIELDS = """[{"name": "email", "kind": "email"}, {"name": "password", "kind": "password"}]"""
    private val ARGS = """{"fields": $FIELDS, "title": "Sign in"}"""

    /** Each text form seen (or reasonable) for browser_secure_input, with the words the user should still see. */
    private val variants: List<Pair<String, String>> = listOf(
        """browser_secure_input(fields=$FIELDS, title="Sign in")""" to "",
        "Sure, let me get those securely.\nbrowser_secure_input(\n    fields=[\n        {\"name\": \"email\", \"kind\": \"email\"},\n        {\"name\": \"password\", \"kind\": \"password\"}\n    ],\n    title=\"Sign in to example.com\"\n)" to "Sure, let me get those securely.",
        "browser_secure_input(fields=[{'name': 'email', 'kind': 'email'}, {'name': 'password', 'kind': 'password'}], submit=True)" to "",
        "`browser_secure_input(fields=$FIELDS)`" to "",
        """{"name": "browser_secure_input", "arguments": $ARGS}""" to "",
        """I'll open a secure form for that. {"name":"browser_secure_input","arguments":$ARGS} Fill it in and I'll carry on.""" to "I'll open a secure form for that.  Fill it in and I'll carry on.",
        """{"function": {"name": "browser_secure_input", "arguments": ${JSONObject.quote(ARGS)}}}""" to "",
        """[{"name": "browser_secure_input", "arguments": $ARGS}]""" to "",
        """{"tool_calls": [{"type": "function", "function": {"name": "browser_secure_input", "arguments": ${JSONObject.quote(ARGS)}}}]}""" to "",
        """<tool_call>{"name": "browser_secure_input", "arguments": $ARGS}</tool_call>""" to "",
        """Opening a secure form. <function=browser_secure_input>$ARGS</function>""" to "Opening a secure form.",
        "```json\n{\"name\": \"browser_secure_input\", \"arguments\": $ARGS}\n```" to "",
        "One moment.\n```tool\nbrowser_secure_input(\n  fields=$FIELDS,\n  title=\"Sign in\"\n)\n```" to "One moment.",
        "```python\nbrowser_secure_input(\n    fields=$FIELDS\n)\n```" to "",
        "Here you go: browser_secure_input(fields=$FIELDS) and I'll fill the page." to "Here you go:  and I'll fill the page.",
    )

    private fun five(block: () -> Unit) = repeat(5) { block() }

    private fun secureCall(p: Pair<JSONArray, String>?, what: String): JSONObject {
        assertNotNull("$what: no real call", p)
        assertEquals("$what: one call", 1, p!!.first.length())
        val f = p.first.getJSONObject(0).getJSONObject("function")
        assertEquals(what, "browser_secure_input", f.getString("name"))
        return JSONObject(f.getString("arguments"))
    }

    private fun kinds(args: JSONObject) = SecureInput.parseFields(args.optJSONArray("fields")).map { it.kind }

    @Before fun setUp() { CallScan.known = CallScan.SEED }

    // ---------------- cloud ----------------

    @Test fun cloudToolCallsEventReachesTheCard() = five {
        val gw = CloudRunsTest.FakeGateway()
        val call = JSONObject().put("id", "c1").put("type", "function")
            .put("function", JSONObject().put("name", "browser_secure_input").put("arguments", ARGS))
        gw.brain = { r, res ->
            if (res == null) {
                r.emit("status", JSONObject().put("status", "running"))
                r.emit("delta", JSONObject().put("step", 0).put("text", "\n"))
                r.pending = JSONArray().put(call)
                r.emit("tool_calls", JSONObject().put("step", 0).put("calls", r.pending))
                r.emit("status", JSONObject().put("status", "needs_device"))
            } else gw.done(r, "Signed in.")
        }
        val kv = HashMap<String, String>(); var clock = 1_000_000L
        val texts = mutableListOf<String>()
        val client = CloudRuns.Client(gw, CloudRuns.RunStore({ kv[it] }, { k, v -> kv[k] = v }, { clock }), sleep = { clock += it }, now = { clock }, detached = { f -> runBlocking { f() } })
        val ev = object : CloudRuns.Events { override fun text(t: String) { texts += t }; override fun status(line: String) {} }
        CloudRuns.owned.clear()
        val m = runBlocking { client.complete(CloudRuns.Turn("main", "t-sf"), JSONArray().put(JSONObject().put("role", "user").put("content", "sign in with my email and password")), null, "agent", ev) }
        val calls = m.getJSONArray("tool_calls")
        assertEquals("browser_secure_input", calls.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals(listOf(SecureInput.Kind.EMAIL, SecureInput.Kind.PASSWORD), kinds(JSONObject(calls.getJSONObject(0).getJSONObject("function").getString("arguments"))))
        // Nothing the run streamed shows code.
        texts.forEach { assertClean("cloud stream", ReplyParts.split(it).text) }
        assertClean("cloud narration", ReplyParts.split((m.opt("content") as? String).orEmpty(), final = true).text)
    }

    // ---------------- text calls ----------------

    @Test fun everyTextFormBecomesTheRealCall() = five {
        for ((v, words) in variants) {
            val p = CallScan.toCalls(v, tools)
            val a = secureCall(p, v)
            assertEquals(v, listOf(SecureInput.Kind.EMAIL, SecureInput.Kind.PASSWORD), kinds(a))
            assertEquals(v, words.replace(Regex("\\s+"), " ").trim(), p!!.second.replace(Regex("\\s+"), " ").trim())
            assertClean("left words of $v", p.second)
        }
    }

    @Test fun everyTextFormIsHiddenInTheBubble() = five {
        for ((v, words) in variants) {
            val b = ToolLeak.bubbleText(v)
            assertClean("bubble", b)
            if (words.isBlank()) assertEquals(v, ToolLeak.STORED_EMPTY, b)
            else assertEquals(v, words.replace(Regex("\\s+"), " ").trim(), b.replace(Regex("\\s+"), " ").trim())
            assertClean("final reply", ReplyParts.split(v, final = true).text)
        }
    }

    @Test fun neverFlashesWhileStreaming() = five {
        for ((v, _) in variants) for (n in 1..v.length) {
            val p = v.substring(0, n)
            assertClean("stream prefix of <<$v>> at $n", ReplyParts.split(p).text)
            assertClean("bubble prefix at $n", ToolLeak.bubbleText(CallScan.clean(p, final = false)))
        }
    }

    @Test fun anyKnownToolPayloadIsSanitized() = five {
        CallScan.learn(names)
        for (s in listOf("""Searching now {"name": "web_search", "arguments": {"query": "weather"}}""",
                "```tool_call\n{\"name\": \"browser_open\", \"arguments\": {\"url\": \"https://x.com\"}}\n```",
                "<tool_call>{\"name\": \"ask_user\", \"arguments\": {\"question\": \"Which?\"}}</tool_call>",
                "browser_open(\n  url=\"https://example.com/login\"\n)")) {
            val b = ToolLeak.bubbleText(s)
            assertFalse(b, b.contains("arguments") || b.contains("browser_open(") || b.contains("<tool") || b.contains("url="))
        }
        // Ordinary text and code the user asked for stay byte for byte.
        for (s in listOf("Hi there", "Use `snake_case(x)` in Python.", "```kotlin\nfun main() { println(\"hi\") }\n```",
                "Remember (this) for later.", "The JSON is {\"a\": 1}.", "Your email inbox has 3 new messages.")) {
            assertEquals(s, ToolLeak.bubbleText(s)); assertEquals(s, ToolLeak.forDisplay(s))
        }
    }

    // ---------------- malformed / truncated ----------------

    @Test fun cutOffCallWithReadableFieldsStillOpensTheForm() = five {
        val v = "Let me get that.\nbrowser_secure_input(fields=[{\"name\":\"email\",\"kind\":\"email\"},{\"name\":\"password\",\"kind\":\"passw"
        val a = secureCall(CallScan.toCalls(v, tools), v)
        assertEquals(listOf(SecureInput.Kind.EMAIL, SecureInput.Kind.PASSWORD), kinds(a))
        assertEquals("Let me get that.", ToolLeak.bubbleText(v))
    }

    @Test fun unreadablePayloadShowsNoCodeAndAnHonestLine() = five {
        for (v in listOf("{\"name\":\"browser_secure_input\",\"argu", "```tool\nbrowser_secure_input(((\n```", "<tool_call>{\"name\": \"browser_secure_input\", \"argum",
                "browser_secure_input(")) {
            assertNull(v, CallScan.toCalls(v, tools))
            assertTrue(v, CallScan.has(v, names))
            assertEquals(v, "", CallScan.clean(v, true, names, loose = true))
            assertEquals(v, ToolLeak.STORED_EMPTY, ToolLeak.bubbleText(v))
            assertClean("final $v", ReplyParts.split(v, final = true).text)
        }
        // The gatekeeper's last resort is plain words.
        assertClean("honest", ToolLeak.HONEST)
    }

    @Test fun stringFieldsStillOpenTheForm() = five {
        val a = CallScan.secureArgs(JSONObject().put("fields", "email, password"))!!
        assertEquals(listOf(SecureInput.Kind.EMAIL, SecureInput.Kind.PASSWORD), kinds(a))
        val b = CallScan.secureArgs(JSONObject().put("fields", FIELDS))!!
        assertEquals(listOf(SecureInput.Kind.EMAIL, SecureInput.Kind.PASSWORD), kinds(b))
    }

    // ---------------- reconnect / replay ----------------

    private fun d(seq: Int, text: String, step: Int = 0) = JSONObject().put("seq", seq).put("type", "delta").put("step", step).put("text", text)

    @Test fun resentSeqsNeverDoubleText() = five {
        val r = CloudRuns.Reducer()
        val pieces = listOf("Sure. ", "{\"name\":\"browser_secure_input\",", "\"arguments\":$ARGS}", " Fill it in.")
        pieces.forEachIndexed { i, s -> assertTrue(r.apply(d(i + 1, s))) }
        val once = r.text
        // The reconnect re-sends 2..4 (Last-Event-ID lost): nothing applies twice.
        pieces.forEachIndexed { i, s -> if (i >= 1) assertFalse(r.apply(d(i + 1, s))) }
        assertEquals(once, r.text)
        assertEquals("Sure. Fill it in.", ReplyParts.split(r.text, final = true).text.replace(Regex("\\s+"), " "))
    }

    @Test fun resetDropsPartialTextWithOrWithoutStep() = five {
        val r = CloudRuns.Reducer()
        r.apply(d(1, "browser_secure_input(\n  fields=[{\"name\": \"em"))
        r.apply(JSONObject().put("seq", 2).put("type", "reset").put("step", 0))
        assertEquals("", r.text)
        r.apply(d(3, "Opening the secure form."))
        assertEquals("Opening the secure form.", r.text)
        r.apply(d(4, " {\"name\": \"browser_"))
        r.apply(JSONObject().put("seq", 5).put("type", "reset"))   // no step: all partial text goes
        assertEquals("", r.text)
        r.apply(d(6, "Done."))
        assertEquals("Done.", r.text)
    }

    @Test fun reconnectMidCallNeverLeaks() = five {
        val gw = CloudRunsTest.FakeGateway()
        val parts = listOf("One moment. ", "browser_secure_input(\n", "  fields=$FIELDS,\n", "  title=\"Sign in\"\n)")
        gw.brain = { r, _ ->
            r.emit("status", JSONObject().put("status", "running"))
            parts.forEach { r.emit("delta", JSONObject().put("step", 0).put("text", it)) }
            r.emit("done", JSONObject().put("reply", parts.joinToString("")))
            r.emit("status", JSONObject().put("status", "done"))
        }
        gw.cutAfter = 4
        val kv = HashMap<String, String>(); var clock = 1_000_000L
        val texts = mutableListOf<String>()
        val client = CloudRuns.Client(gw, CloudRuns.RunStore({ kv[it] }, { k, v -> kv[k] = v }, { clock }), sleep = { clock += it }, now = { clock }, detached = { f -> runBlocking { f() } })
        val ev = object : CloudRuns.Events { override fun text(t: String) { texts += t }; override fun status(line: String) {} }
        CloudRuns.owned.clear()
        val m = runBlocking { client.complete(CloudRuns.Turn("main", "t-rc"), JSONArray().put(JSONObject().put("role", "user").put("content", "sign in")), null, "agent", ev) }
        assertEquals(parts.joinToString(""), texts.last())   // each piece once
        texts.forEach { assertClean("streamed after reconnect", ReplyParts.split(it).text) }
        val a = secureCall(CallScan.toCalls(m.getString("content"), tools), "reply after reconnect")
        assertEquals(listOf(SecureInput.Kind.EMAIL, SecureInput.Kind.PASSWORD), kinds(a))
    }

    // ---------------- chat-credentials guard ----------------

    private val host = "the-internet.herokuapp.com"

    @Test fun chatCredentialAsksBecomeTheSecureForm() = five {
        val seen = listOf(
            "I can help you log into the-internet.herokuapp.com. I'll need your username and password to complete the login. Could you please provide those credentials?" to SecureInput.Kind.USERNAME,
            "Sure — drop your username and password here and I'll fill them in." to SecureInput.Kind.USERNAME,
            "I need your email and password for that site. What are they?" to SecureInput.Kind.EMAIL,
            "Need your email and password for that login. Send 'em over and I'll fill 'em in." to SecureInput.Kind.EMAIL,
            "What's your password?" to SecureInput.Kind.EMAIL,
        )
        for ((reply, first) in seen) {
            val c = CredAsk.decide(reply, host, 0, false, names, "sign in with my email and password")
            assertNotNull(reply, c)
            assertEquals("browser_secure_input", c!!.getJSONObject("function").getString("name"))
            val k = kinds(JSONObject(c.getJSONObject("function").getString("arguments")))
            assertEquals(reply, listOf(first, SecureInput.Kind.PASSWORD), k)
        }
        // A PIN ask gets one masked PIN field.
        val pin = CredAsk.decide("Please send me your PIN for the app.", host, 0, false, names)!!
        assertEquals(listOf(SecureInput.Kind.PASSWORD), kinds(JSONObject(pin.getJSONObject("function").getString("arguments"))))
    }

    @Test fun guardRespectsLimitsAndNeverAsksForCards() = five {
        val ask = "Could you please provide your username and password?"
        assertNull("no page open", CredAsk.decide(ask, "", 0, false, names))
        assertNull("3 asks already", CredAsk.decide(ask, host, 3, false, names))
        assertNull("cancelled", CredAsk.decide(ask, host, 99, false, names))
        assertNull("once per turn", CredAsk.decide(ask, host, 0, true, names))
        assertNull("no secure tool", CredAsk.decide(ask, host, 0, false, setOf("browser_open")))
        for (s in listOf("What's your card number and CVV?", "I've signed you in. Want me to check your email inbox next?",
                "Enter your details in the secure form above.", "Never share your password in chat.", "Done: you're signed in.",
                "I sent your email draft to Sam.")) assertNull(s, CredAsk.decide(s, host, 0, false, names))
    }
}
