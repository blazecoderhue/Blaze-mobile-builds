package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.45.9 (user 12:14): every lane that sends tools sends the full registered list, and every lane that sends none hands
 * tool-shaped requests to the full lane. The registry here is the agent's list as far as it builds on the JVM
 * (ToolPickSizeTest's sources + terminal, routines, phone skills, weather, save_file). It is also written to
 * app/build/qa/tools-full-0459.json for tools/halo_replay.py (the replays send it as the request's tools).
 */
class ToolTruthTest {
    internal fun registry(): Pair<JSONArray, List<String>> {
        val (t, missed0) = ToolPickSizeTest().all()
        val missed = missed0.toMutableList()
        fun add(what: String, f: () -> Any) {
            try { when (val v = f()) { is JSONObject -> t.put(v); is List<*> -> v.forEach { t.put(it) } } } catch (e: Throwable) { missed += "$what (${e.javaClass.simpleName})" }
        }
        add("Terminal") { com.blaze.assistant.terminal.Terminal.shellTools() }
        add("Routines") { com.blaze.assistant.routine.Routines.tools() }
        add("Skills") { com.blaze.assistant.phone.Skills.definitions() }
        add("Weather") { GatewayWeather.tool() }
        add("SaveFile") { SaveFile.tool() }
        add("Mind") { Mind.tools() }   // Agent.tools() adds these when Mind is ready (memory_search)
        return ToolTruth.clean(t) to missed
    }

    private val strict = listOf("run ls in the terminal", "open example.com in the Linux browser", "open Settings", "open Design view", "save this file",
        "what's on my calendar today", "remind me at 5 to call mum", "search the web for zesco load shedding", "what do you remember about me")

    @Test fun registryHasEveryMustTool() {
        val (all, missed) = registry()
        val names = ToolTruth.names(all)
        val absent = ToolTruth.MUST - names.toSet()
        assertTrue("missing from the registry: $absent (could not build: $missed)", absent.isEmpty())
        assertEquals("no duplicate names", names.size, names.toSet().size)
        names.forEach { assertTrue(it, Regex("^[a-zA-Z0-9_-]{1,64}$").matches(it)) }
        val dir = File(System.getProperty("user.dir"), "build/qa").apply { mkdirs() }
        File(dir, "tools-full-0459.json").writeText(JSONObject().put("count", names.size).put("missed", JSONArray(missed)).put("tools", all).toString())
    }

    @Test fun agentLaneSendsTheWholeList() {
        val (all, _) = registry()
        assertTrue(ToolTruth.full)
        val sent = ToolTruth.forRequest(all) { JSONArray() }
        assertEquals(all.length(), sent.length())
        // Every strict request, whatever ToolRouter guesses, gets the same full list.
        for (p in strict) assertTrue(p, ToolTruth.names(ToolTruth.forRequest(all) { ToolRouter.selectDefault(all, ToolRouter.guess(p)) }).containsAll(ToolTruth.MUST))
    }

    @Test fun oldSubsetsMissedTools() {
        // The root cause, kept as a regression record: the guessed subset left save_file and other tools out for these.
        val (all, _) = registry()
        val sub = ToolTruth.names(ToolRouter.selectDefault(all, ToolRouter.guess("open example.com in the Linux browser")))
        assertTrue(sub.size < all.length())
    }

    @Test fun cloudRunLaneKeepsEveryName() {
        val (all, _) = registry()
        val adapted = ToolCalls.adapt(all, Providers.GATEWAY, Cloud.MODEL)
        assertEquals(ToolTruth.names(all), ToolTruth.names(adapted))
    }

    @Test fun noToolLanesHandOffToolShapedRequests() {
        // Chat lane (ChatLane.fits) and the @all duo chat send no tools: these go to the full lane.
        for (p in strict) assertTrue(p, ToolTruth.toolShaped(p) || SaveFile.wantsSave(p))
        for (p in strict) assertTrue("@all $p", ToolTruth.toolShaped(Duo.route("@all $p").text))
        // Small talk stays light.
        listOf("hi", "thanks!", "how are you", "good morning").forEach { assertFalse(it, ToolTruth.toolShaped(it)) }
    }

    @Test fun chatLaneSourceChecksToolShape() {
        val src = listOf(File("src/main/java/com/blaze/assistant/agent/ChatLane.kt"), File("app/src/main/java/com/blaze/assistant/agent/ChatLane.kt")).first { it.exists() }.readText()
        assertTrue(src.contains("if (ToolTruth.toolShaped(t) || SaveFile.wantsSave(t)) return false"))
        val agent = listOf(File("src/main/java/com/blaze/assistant/agent/Agent.kt"), File("app/src/main/java/com/blaze/assistant/agent/Agent.kt")).first { it.exists() }.readText()
        assertTrue(agent.contains("Duo.kindFor(route.text).takeUnless { it == Duo.Kind.CHAT && ToolTruth.toolShaped(route.text) }"))
        assertTrue(agent.contains("if (!sched && ToolTruth.toolShaped(text) && !ToolTruth.chatOnly(text))"))
        assertTrue(agent.contains("return ToolTruth.forRequest(allTools) { allTools }"))
    }

    @Test fun refusalCaught() {
        listOf("I don't have the capability to save files directly.", "I can't access your calendar.", "I'm unable to run commands.",
            "As an AI, I can't open apps.", "Open a text editor on your computer and paste it.").forEach { assertTrue(it, ToolTruth.refusal(it)) }
        listOf("Saved to Downloads/Blaze/solar-system.html.", "Here's your schedule for today.", "I can't find that contact; which one?").forEach { assertFalse(it, ToolTruth.refusal(it)) }
        assertTrue(ToolTruth.RULE.contains("I don't have the capability"))
    }

    @Test fun cleanDropsDuplicatesAndFixesParams() {
        fun t(n: String, params: JSONObject? = null) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n).apply { if (params != null) put("parameters", params) })
        val c = ToolTruth.clean(JSONArray().put(t("a")).put(t("a")).put(t("bad name")).put(t("b", JSONObject())))
        assertEquals(listOf("a", "b"), ToolTruth.names(c))
        assertEquals("object", c.getJSONObject(0).getJSONObject("function").getJSONObject("parameters").getString("type"))
        assertTrue(ToolTruth.line(c).startsWith("tools sent 2: a, b"))
        assertEquals("tools sent 0 (no tools in this request)", ToolTruth.line(JSONArray()))
    }

    @Test fun traceKeepsNormalizedAndToolsSent() {
        val tr = TurnTrace.build(mapOf("x-blaze-normalized" to "retried;bad_call;from=groq/x", "x-request-id" to "r1"), "Blaze Cloud", "agent", listOf("r1"),
            100, 50, 900, 1, emptyList(), emptyList(), toolsSent = listOf("save_file", "run_shell"))
        val back = TurnTrace.fromJson(TurnTrace.toJson(tr))!!
        assertEquals("retried;bad_call;from=groq/x", back.normalized)
        assertEquals(listOf("save_file", "run_shell"), back.toolsSent)
        val rows = TurnTrace.rows(back).toMap()
        assertEquals("2: save_file, run_shell", rows["Tools sent"])
        assertEquals("retried;bad_call;from=groq/x", rows["Normalized"])
        assertTrue("x-blaze-normalized" in TurnTrace.HEADERS)
    }

    @Test fun gateAsksNameTheirTool() {
        val all = listOf("create_routine", "list_routines", "memory_search", "memory_save", "remember", "run_shell", "web_search", "create_trigger")
        assertEquals("create_routine", ToolTruth.choiceFor("remind me to call mum at 18:00 today", emptyList(), all))
        assertEquals("create_routine", ToolTruth.choiceFor("@halo set a reminder for 7", emptyList(), all))
        assertEquals("list_routines", ToolTruth.choiceFor("what reminders do I have set?", emptyList(), all))
        assertEquals("memory_save", ToolTruth.choiceFor("remember that my NET exam is on 3 November", emptyList(), all))
        assertEquals("remember", ToolTruth.choiceFor("remember that my NET exam is on 3 November", emptyList(), all - "memory_save"))
        assertEquals("memory_search", ToolTruth.choiceFor("what do you remember about my exams?", emptyList(), all))
        assertEquals(null, ToolTruth.choiceFor("remember when we went to Ndola?", emptyList(), all - "memory_search"))
        assertEquals("run_shell", ToolTruth.choiceFor("run ls in the terminal", emptyList(), all))  // v0.45.13 round 2
        assertEquals(null, ToolTruth.choiceFor("search the web for Zambia news", emptyList(), all))
        assertEquals(null, ToolTruth.choiceFor("hi Blaze", emptyList(), all))
    }

    @Test fun blazeMusicPlayIsMusicOps() {
        val tools = listOf("music_ops", "search_in_app", "run_plan")
        assertEquals("music_ops", ToolTruth.choiceFor("play Calm Down by Rema in Blaze Music", emptyList(), tools))
        assertEquals("music_ops", ToolTruth.choiceFor("put on something chill from my downloads", emptyList(), tools))
        assertEquals("search_in_app", ToolTruth.choiceFor("play Despacito on YouTube", emptyList(), tools))
    }
}
