package com.blaze.assistant.agent

import com.blaze.assistant.agent.ToolVerify.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v0.45.0 scripted agent-loop tests: a fake model (a script of replies) and fake tools drive [Harness], which runs the
 * same per-step policy as Agent.runLoop's tool / final-reply path, with the real parts: ToolCalls-style missing-tool
 * widening (ToolPick.missing), OneShot, Recovery, ToolVerify (+ probes), the [verified=…] tags, ClaimCheck, NudgePolicy,
 * ActionProof.honest, Receipt and PlanGate. (Agent.runLoop itself still needs the Android session; this pins the order
 * and the outcomes until the ModelPort / ToolPort split, #26 / #35.)
 */
class AgentLoopScriptTest {
    private fun call(id: String, name: String, args: JSONObject = JSONObject()) =
        JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", args.toString()))))
    private fun say(text: String) = JSONObject().put("role", "assistant").put("content", text)
    private fun t(f: (JSONObject) -> String) = f
    private fun toolList(vararg names: String) = JSONArray().apply { names.forEach { put(JSONObject().put("type", "function").put("function", JSONObject().put("name", it))) } }

    class Harness(
        val script: MutableList<JSONObject>,
        val tools: Map<String, (JSONObject) -> String>,
        val sent0: JSONArray,
        val all: JSONArray,
        val probes: ToolVerify.Probes = ToolVerify.NoProbes,
    ) {
        val history = JSONArray()
        val log = mutableListOf<Receipt.Entry>()
        val done = mutableListOf<ClaimCheck.Done>()
        val ran = mutableListOf<String>()
        val oneShot = OneShot()
        var widened = false
        var nudges = 0
        var rewrote = false
        val sentPerStep = mutableListOf<Int>()
        var followups = emptyList<String>()

        suspend fun turn(user: String, maxSteps: Int = 8): String {
            history.put(JSONObject().put("role", "user").put("content", user))
            var proofNudged = false
            for (step in 0 until maxSteps) {
                val sent = if (widened) all else sent0
                sentPerStep += sent.length()
                val reply = script.removeAt(0)
                if (!widened && ToolPick.missing(reply, sent).isNotEmpty()) widened = true
                history.put(reply)
                val calls = reply.optJSONArray("tool_calls")
                if (calls != null && calls.length() > 0) {
                    for (i in 0 until calls.length()) {
                        val fn = calls.getJSONObject(i).getJSONObject("function")
                        val name = fn.getString("name")
                        val args = JSONObject(fn.optString("arguments", "{}"))
                        val tool = tools[name]
                        var result = if (tool == null) "Error: there is no tool called \"$name\"."
                            else oneShot.earlier(name, args) ?: Recovery.run(name, OneShot.risky(name), 5_000, { }) { ran += name; tool(args) }.also { oneShot.record(name, args, it) }
                        val v = ToolVerify.check(name, args, result, probes, 0) { _, ok -> ok() }
                        done += ClaimCheck.Done(name, v.status, v.why); log += Receipt.entry(name, args, result, v)
                        result = ToolVerify.tag(result, v)
                        history.put(JSONObject().put("role", "tool").put("tool_call_id", calls.getJSONObject(i).getString("id")).put("name", name).put("content", result))
                    }
                    continue
                }
                var text = reply.optString("content")
                val sentNames = ToolCalls.names(sent)
                val called = ran.toSet()
                val check = ClaimCheck.review(text, done, user, called)
                if (check is ClaimCheck.Outcome.Rewrite) { text = check.text; rewrote = true }
                val miss = (check as? ClaimCheck.Outcome.Unbacked)?.action
                if (miss != null) {
                    val way = NudgePolicy.proof(miss, sentNames, called)
                    if (way != NudgePolicy.Way.HONEST && !proofNudged) {
                        proofNudged = true; nudges++
                        history.put(JSONObject().put("role", "user").put("content", ActionProof.nudge(miss)))
                        continue
                    }
                    text = ActionProof.honest(text, miss); rewrote = true; followups = listOf(NudgePolicy.TRY_AGAIN)
                }
                return text
            }
            return "Stopped after $maxSteps steps."
        }

        fun keepPlan() = PlanGate.keep(log, nudges > 0, rewrote)
    }

    @Test fun falseSuccessClaimIsNeverShown() = runBlocking {
        // The model says "Sent!" without calling anything, twice.
        val h = Harness(mutableListOf(say("Sent! Mum knows you're running late."), say("Done, message sent to Mum.")),
            mapOf("message_contact" to t { "Sent to Mum on WhatsApp." }), toolList("message_contact", "web_search"), toolList("message_contact", "web_search"))
        val out = h.turn("text mum that I'm running late")
        assertEquals(1, h.nudges)
        assertTrue(out, out.startsWith("I didn't actually send that message"))
        assertFalse(out.contains("message sent", true))
        assertEquals(listOf(NudgePolicy.TRY_AGAIN), h.followups)
        assertFalse(h.keepPlan())
    }

    @Test fun nudgeThatWorksEndsVerified() = runBlocking {
        val h = Harness(mutableListOf(say("Sent!"), call("c1", "message_contact", JSONObject().put("contact", "Mum").put("text", "late")), say("Sent to Mum on WhatsApp.")),
            mapOf("message_contact" to t { "Sent to Mum on WhatsApp: \u201clate\u201d" }), toolList("message_contact"), toolList("message_contact"))
        val out = h.turn("text mum I'm late")
        assertEquals("Sent to Mum on WhatsApp.", out)
        assertEquals(Status.VERIFIED, h.log.single().status)
        assertTrue(Receipt.line(h.log).startsWith("\u2713 Sent to Mum"))
    }

    @Test fun toolFailureMidTaskIsToldPlainly() = runBlocking {
        val h = Harness(mutableListOf(call("c1", "open_app", JSONObject().put("app", "WhatsApp")), call("c2", "message_contact", JSONObject().put("contact", "Ana").put("text", "hi")),
            say("All done! I sent the message to Ana.")),
            mapOf("open_app" to t { "Opened WhatsApp (com.whatsapp)." }, "message_contact" to t { "Couldn't send: Ana isn't in your contacts." }),
            toolList("open_app", "message_contact"), toolList("open_app", "message_contact"))
        val out = h.turn("message Ana hi on whatsapp")
        assertTrue(out, out.contains("didn't work") && out.contains("Ana isn't in your contacts"))
        assertFalse(out.contains("I sent the message"))
        assertTrue(Receipt.line(h.log).startsWith("\u2717"))
        assertFalse(h.keepPlan())
    }

    @Test fun unconfirmedActionSaysCouldntConfirm() = runBlocking {
        val h = Harness(mutableListOf(call("c1", "set_timer", JSONObject().put("seconds", 300)), say("Timer set for 5 minutes!")),
            mapOf("set_timer" to t { "Timer started in Clock." }), toolList("set_timer"), toolList("set_timer"))
        val out = h.turn("set a timer for 5 minutes")
        assertTrue(out, out.contains("couldn't confirm", true))
        assertEquals(Status.UNVERIFIED, h.log.single().status)
    }

    @Test fun missingToolWidensToTheFullSet() = runBlocking {
        val all = toolList("web_search", "set_alarm", "open_app")
        val h = Harness(mutableListOf(call("c1", "set_alarm", JSONObject().put("hour", 7).put("minute", 0)), say("Your alarm is set for 7:00.")),
            mapOf("set_alarm" to t { "Alarm set for 07:00." }), toolList("web_search"), all,
            probes = object : ToolVerify.Probes { override suspend fun nextAlarm() = 7 to 0 })
        val out = h.turn("wake me at 7")
        assertTrue(h.widened)
        assertEquals(listOf(1, 3), h.sentPerStep)
        assertEquals("Your alarm is set for 7:00.", out)
        assertEquals(Status.VERIFIED, h.log.single().status)
        assertTrue(h.keepPlan())
    }

    @Test fun riskyActionNeverRepeatsAndTransientSafeOneRetries() = runBlocking {
        var sends = 0
        var searches = 0
        val args = JSONObject().put("contact", "Mum").put("text", "home soon")
        val h = Harness(mutableListOf(call("c1", "web_search", JSONObject().put("q", "traffic")), call("c2", "message_contact", args), call("c3", "message_contact", args),
            say("Sent to Mum.")),
            mapOf("message_contact" to t { sends++; "Sent to Mum on WhatsApp." }, "web_search" to t { searches++; if (searches == 1) "Error: timed out" else "1. Clear roads" }),
            toolList("message_contact", "web_search"), toolList("message_contact", "web_search"))
        h.turn("check traffic then tell mum I'm home soon")
        assertEquals(1, sends)
        assertEquals(2, searches)
        assertTrue(h.history.toString().contains("Not repeated"))
    }

    @Test fun stopMidStepLeavesNothingRecorded() = runBlocking {
        val args = JSONObject().put("contact", "Mum").put("text", "x")
        val h = Harness(mutableListOf(call("c1", "message_contact", args)),
            mapOf("message_contact" to t { throw CancellationException("Stop") }), toolList("message_contact"), toolList("message_contact"))
        try { h.turn("text mum x"); fail("expected Stop") } catch (_: CancellationException) { }
        assertEquals(0, h.oneShot.size())
        assertTrue(h.log.isEmpty())
    }

    @Test fun cloudRunPhoneStepContinuesTheRun() = runBlocking {
        val kv = HashMap<String, String>()
        var clock = 1_000_000L
        val gw = CloudRunsTest.FakeGateway()
        val store = CloudRuns.RunStore({ kv[it] }, { k, v -> kv[k] = v }, { clock })
        val client = CloudRuns.Client(gw, store, sleep = { clock += it }, now = { clock }, detached = { f -> runBlocking { f() } })
        CloudRuns.owned.clear()
        val c1 = JSONObject().put("id", "c1").put("type", "function").put("function", JSONObject().put("name", "look_at_screen").put("arguments", "{}"))
        gw.brain = { r, res ->
            if (res == null) { r.pending = JSONArray().put(c1); r.emit("tool_calls", JSONObject().put("step", 0).put("calls", r.pending)); r.emit("status", JSONObject().put("status", "needs_device")) }
            else gw.done(r, "The screen shows the Send button.")
        }
        val t = CloudRuns.Turn("main", "turn-p2")
        var described = 0
        t.describe = { _ -> described++; "WhatsApp chat with Mum, Send button bottom right" }
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", "sys")).put(JSONObject().put("role", "user").put("content", "what's on screen"))
        val first = client.complete(t, msgs, null, "agent")
        assertEquals("look_at_screen", first.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
        msgs.put(first)
        msgs.put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "Took a screenshot."))
        msgs.put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "[Screenshot of the phone screen]"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,AAAA")))))
        val second = client.complete(t, msgs, null, "agent")
        assertEquals("The screen shows the Send button.", second.getString("content"))
        assertEquals(1, gw.created)                       // no second run
        assertEquals(1, gw.log.count { it == "POST /runs" })
        assertEquals(1, described)
        assertEquals(1, t.continued); assertEquals(0, t.restarted)
        val sentResult = gw.bodies.map { JSONObject(it) }.first { it.has("results") }.getJSONArray("results").getJSONObject(0).getString("content")
        assertTrue(sentResult.contains("Send button bottom right"))
        assertFalse(gw.bodies.any { it.contains("base64,AAAA") && it.contains("results") })   // the picture is never re-uploaded
    }

    @Test fun cloudRunWithoutDescriberStartsOver() = runBlocking {
        val kv = HashMap<String, String>()
        var clock = 1_000_000L
        val gw = CloudRunsTest.FakeGateway()
        val client = CloudRuns.Client(gw, CloudRuns.RunStore({ kv[it] }, { k, v -> kv[k] = v }, { clock }), sleep = { clock += it }, now = { clock }, detached = { f -> runBlocking { f() } })
        CloudRuns.owned.clear()
        val c1 = JSONObject().put("id", "c1").put("type", "function").put("function", JSONObject().put("name", "look_at_screen").put("arguments", "{}"))
        gw.brain = { r, res -> if (res == null && r.cid.endsWith(":1")) { r.pending = JSONArray().put(c1); r.emit("status", JSONObject().put("status", "needs_device")) } else gw.done(r, "ok") }
        val t = CloudRuns.Turn("main", "turn-p3")
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "x"))
        msgs.put(client.complete(t, msgs, null, "agent"))
        msgs.put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "shot"))
        msgs.put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "u")))))
        client.complete(t, msgs, null, "agent")
        assertEquals(2, gw.created); assertEquals(1, t.restarted)
    }

    @Test fun longContextIsSummarisedAndTheGoalSurvives() {
        val goal = GoalPin.of("continue", listOf("Find me 3 flights to Lusaka under K4000, don't book anything."))
        val m = JSONArray().put(JSONObject().put("role", "system").put("content", "SYS" + GoalPin.block(goal)))
        for (i in 0 until 40) {
            m.put(JSONObject().put("role", "user").put("content", "step $i"))
            m.put(JSONObject().put("role", "assistant").put("content", "Checked site $i, found fare K${3000 + i}"))
        }
        val s = LlmClient.shrink(m, keep = 8)
        val sys = s.getJSONObject(0).getString("content")
        assertTrue(sys.contains("under K4000") && sys.contains("don't book anything"))
        val digest = s.getJSONObject(1).getString("content")
        assertTrue(digest.contains("Checked site 0") || digest.contains("User: step 0"))
        assertTrue(digest.length <= 3_100)
    }
}
