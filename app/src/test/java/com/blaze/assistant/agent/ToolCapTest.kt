package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.45.13 phone-control sweep 0: (a) a request never carries more than 128 tools (gateway routes 400 with "'tools':
 * maximum number of items is 128"; the phone registers 136 with PhoneTools) and the turn's own tools always stay;
 * (b) each phone ask names its tool (and the two-step flows their second step); (c) set_alarm's result carries no
 * instruction text that can leak into a reply.
 */
class ToolCapTest {
    private val all: JSONArray by lazy {
        val t = JSONArray(); val r = ToolTruthTest().registry().first; for (i in 0 until r.length()) t.put(r.get(i))
        val p = com.blaze.assistant.phone.PhoneTools.definitions(); for (i in 0 until p.length()) t.put(p.get(i))
        ToolTruth.clean(t)
    }

    private fun ask(user: String, said: List<String> = emptyList()) = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", "rules"))
        said.forEach { put(JSONObject().put("role", "assistant").put("content", it)) }
        put(JSONObject().put("role", "user").put("content", user))
    }

    private fun named(choice: Any): String? = (choice as? JSONObject)?.optJSONObject("function")?.optString("name")

    private val expected = mapOf(
        "set an alarm for 6:40 am called QA wake" to "set_alarm",
        "turn off the QA wake alarm" to "processes",
        "remove the alarm" to "processes",
        "delete my 18:15 alarm" to "processes",
        "add a calendar event QA review tomorrow at 15:00 for 30 minutes" to "add_calendar_event",
        "draft a text to 0977000000 saying QA draft test, don't send it" to "compose_sms",
        "play Despacito by Luis Fonsi on YouTube" to "search_in_app",
        "turn on Wi-Fi, Bluetooth and Do Not Disturb" to "device_setting",
        "set a timer for 2 minutes" to "set_timer",
        "open display settings and turn dark mode on" to "open_settings_page",
        "add a contact QA Tester with number 0977123456" to "add_contact",
        "get directions to Levy Junction Mall in Google Maps" to "open_url",
    )

    // The 0.45.9 strict cases (replay-0459-x8full): their named choices must not change.
    private val old = mapOf(
        "View calendar" to "calendar_events", "open example.com in the Linux browser" to null, "open Settings" to "open_view",   // wave B-4: desktop_task removed
        "open Design view" to "open_view", "remind me to call mum at 18:00 today" to "create_routine", "what reminders do I have set?" to "list_routines",
        "what do you remember about my exams?" to "memory_search", "remember that my NET exam is on 3 November" to "memory_save",
        "@halo hi" to null, "hi Blaze" to null, "anything on for today?" to null, "run ls in the terminal" to "run_shell", // round 2: named run_shell
        "search the web for today's US dollar to kwacha exchange rate" to null, "now list what's inside projects" to null,
        "@halo run ls in the terminal" to "run_shell", "@halo search the web for Zambia news today" to null, "doing good thanks!" to null,
        "so what's on for today specifically?" to null, "well as the assistant shouldn't you be telling me?" to null)

    @Test fun phoneRegistryIsOverTheCap() {
        assertTrue("the phone list must exceed 128 for this test to mean anything: ${all.length()}", all.length() > ToolTruth.MAX_TOOLS)
    }

    @Test fun capIsAtMost128AndKeepsTheNamedTool() {
        val names = ToolTruth.names(all)
        for (u in expected.keys + old.keys + PhoneCases.ALL.map { it.prompt }) {
            val m = ask(u)
            val sent = ToolTruth.cap(m, all)
            val sn = ToolTruth.names(sent)
            assertTrue("$u: ${sn.size}", sn.size <= ToolTruth.MAX_TOOLS)
            assertEquals("$u: no duplicates", sn.size, sn.toSet().size)
            ToolTruth.choiceName(m, names)?.let { assertTrue("$u keeps $it", it in sn) }
            assertEquals("$u: the named choice survives the cap", ToolTruth.choiceName(m, names), ToolTruth.choiceName(m, sn))
            ToolTruth.follow(u).values.forEach { assertTrue("$u keeps follow-up $it", it in sn) }
            (ToolTruth.MUST + ToolTruth.PHONE_CORE).filter { it in names }.forEach { assertTrue("$u keeps $it", it in sn) }
            // original order kept (prompt caching), and the same request gives the same list
            assertEquals(u, names.filter { it in sn.toSet() }, sn)
            assertEquals(u, sn, ToolTruth.names(ToolTruth.cap(ask(u), all)))
        }
    }

    @Test fun capKeepsToolsUsedEarlierInTheChat() {
        val low = ToolTruth.LOW.first { it in ToolTruth.names(all) }
        val m = ask("set a timer for 2 minutes")
        val withUse = JSONArray().apply {
            put(m.get(0))
            put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject().put("id", "c1").put("type", "function")
                .put("function", JSONObject().put("name", low).put("arguments", "{}")))))
            put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "ok"))
            put(m.get(1))
        }
        assertFalse("$low is the first to go without use", low in ToolTruth.names(ToolTruth.cap(m, all)))
        assertTrue("$low stays once used", low in ToolTruth.names(ToolTruth.cap(withUse, all)))
    }

    @Test fun underTheCapNothingChanges() {
        val r = ToolTruthTest().registry().first
        if (r.length() <= ToolTruth.MAX_TOOLS) assertTrue(ToolTruth.cap(ask("open Settings"), r) === r)
    }

    @Test fun phoneAsksNameTheirTool() {
        val names = ToolTruth.names(all)
        for ((u, want) in expected) assertEquals(u, want, named(ToolTruth.choice(ask(u), ToolTruth.cap(ask(u), all))))
        for ((u, want) in old) assertEquals(u, want, ToolTruth.choiceName(ask(u), names))
        // Every strict phone case names a tool, and (except the two-step flows) one of its proof tools.
        for (c in PhoneCases.ALL) {
            val n = ToolTruth.choiceName(ask(c.prompt), names)
            assertTrue(c.id + " named " + n, n != null && (n in c.proof || ToolTruth.follow(c.prompt).isNotEmpty() || n == "open_url"))
        }
        listOf("hi", "what's the time?", "thanks!", "how do alarms work on android?").forEach { assertNull(it, ToolTruth.phoneChoice(it, names)) }
    }

    private fun step(m: JSONArray, tool: String, result: String) = JSONArray().apply {
        for (i in 0 until m.length()) put(m.get(i))
        put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject().put("id", "c$tool").put("type", "function")
            .put("function", JSONObject().put("name", tool).put("arguments", "{}")))))
        put(JSONObject().put("role", "tool").put("tool_call_id", "c$tool").put("content", result))
    }

    @Test fun twoStepFlowsNameTheSecondStepOnce() {
        val names = ToolTruth.names(all)
        val off = step(ask("remove the alarm"), "processes", "A2 · alarm · Demo app alarm · 18:15 · active")
        assertEquals("process", ToolTruth.choiceName(off, names))
        assertNull("only once", ToolTruth.choiceName(step(off, "process", "Deleted"), names))
        val dark = step(ask("open display settings and turn dark mode on"), "open_settings_page", "Opened display settings.")
        assertEquals("tap", ToolTruth.choiceName(dark, names))
        assertNull(ToolTruth.choiceName(step(dark, "tap", "on"), names))
        // other flows stay auto after their first step
        assertNull(ToolTruth.choiceName(step(ask("set a timer for 2 minutes"), "set_timer", "Timer started"), names))
        assertNull(ToolTruth.choiceName(step(ask("open Settings"), "open_view", "ok"), names))
    }

    @Test fun setAlarmResultHasNoInstructionText() {
        val src = listOf(File("src/main/java/com/blaze/assistant/phone/PhoneTools.kt"), File("app/src/main/java/com/blaze/assistant/phone/PhoneTools.kt")).first { it.exists() }.readText()
        assertFalse(src.contains("Say this exact time"))
        assertTrue(src.contains("Alarm set for \${\"%02d:%02d\".format(h, m)}, label \\u201c\$label\\u201d. [ID \$pid]"))
        val py = listOf(File("../tools/halo_replay.py"), File("tools/halo_replay.py")).firstOrNull { it.exists() }?.readText() ?: ""  // replay tools are not in the public build source
        assertFalse("replay fixture", py.contains("Say this exact time"))
    }

    @Test fun unconfirmedActionIsAlwaysSaid() {
        val why = "\u201cDemo app alarm (Blaze A2)\u201d still reads ON in the Clock app"
        val done = listOf(ClaimCheck.Done("processes", ToolVerify.Status.VERIFIED), ClaimCheck.Done("process", ToolVerify.Status.UNVERIFIED, why))
        // replay round 1, runs 2 and 4: the reply only gave the next step
        assertEquals("I couldn't confirm it: $why. Open the Clock app and switch it off.", UnconfirmedEcho.fix("Open the Clock app and switch it off.", done))
        // honest replies and confirmed actions are left alone
        val honest = "I tried to turn off alarm A2 but couldn't confirm it."
        assertEquals(honest, UnconfirmedEcho.fix(honest, done))
        val ok = listOf(ClaimCheck.Done("process", ToolVerify.Status.VERIFIED, "the switch reads OFF"))
        assertEquals("Done, it's off.", UnconfirmedEcho.fix("Done, it's off.", ok))
        // a later verified action settles an earlier unconfirmed tap
        val yt = listOf(ClaimCheck.Done("tap", ToolVerify.Status.UNVERIFIED, "a tap alone"), ClaimCheck.Done("search_in_app", ToolVerify.Status.VERIFIED, "YouTube is playing"))
        assertEquals("Playing.", UnconfirmedEcho.fix("Playing.", yt))
        // a retry of the same tool that worked settles it too
        val retry = listOf(ClaimCheck.Done("set_timer", ToolVerify.Status.UNVERIFIED, "x"), ClaimCheck.Done("set_timer", ToolVerify.Status.VERIFIED, "running"))
        assertNull(UnconfirmedEcho.open(retry))
        val agent = listOf(File("src/main/java/com/blaze/assistant/agent/Agent.kt"), File("app/src/main/java/com/blaze/assistant/agent/Agent.kt")).first { it.exists() }.readText()
        assertTrue(agent.contains("UnconfirmedEcho.fix(textOut, synchronized(turnDone) { turnDone.toList() })"))
    }

    @Test fun wireSitesUseTheCap() {
        fun src(p: String) = listOf(File("src/main/java/com/blaze/assistant/$p"), File("app/src/main/java/com/blaze/assistant/$p")).first { it.exists() }.readText()
        assertTrue(src("agent/LlmClient.kt").contains("val wire = ToolTruth.cap(messages, tools)"))
        assertTrue(src("agent/LlmClient.kt").contains("ToolTruth.choice(messages, wire)"))
        assertTrue(src("agent/CloudRuns.kt").contains("val sent = ToolTruth.cap(messages, tools)"))   // 0.45.15: the named choice is computed on the capped list
    }
}
