package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 0.45.15 strict replays on the phone route: a4e357ffcdccff09 (overlay-taps #3), a4e356c32bf0ad76 (calendar-event #2). */
class ReplyFixTest {
    private fun call(n: String, a: String) = JSONObject().put("id", n).put("type", "function").put("function", JSONObject().put("name", n).put("arguments", a))

    @Test fun flowToolStandsAlone() {
        val u = "open Do Not Disturb settings and turn it on"
        val r = JSONObject().put("tool_calls", JSONArray().put(call("open_settings_page", "{\"page\":\"dnd\"}"))
            .put(call("run_plan", "{\"steps\":[{\"action\":\"tap\",\"element\":{\"id\":\"17\"}}]}")))
        assertEquals(1, ReplyFix.keepFlowTool(r, "open_settings_page", u))
        assertEquals(1, r.getJSONArray("tool_calls").length())
        assertEquals("open_settings_page", r.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
        // Not a flow (set_alarm has no follow-up), or nothing named: untouched.
        val r2 = JSONObject().put("tool_calls", JSONArray().put(call("set_alarm", "{}")).put(call("read_screen", "{}")))
        assertEquals(0, ReplyFix.keepFlowTool(r2, "set_alarm", "set an alarm for 6:40 am"))
        assertEquals(0, ReplyFix.keepFlowTool(r2, null, u))
    }

    @Test fun tomorrowMeansTomorrow() {
        val wed = LocalDate.of(2026, 10, 21)
        val a = JSONObject().put("title", "QA review").put("start", "2026-10-23 15:00").put("end", "2026-10-23 15:30")
        assertTrue(ReplyFix.fixRelativeDay(a, "add a calendar event QA review tomorrow at 15:00 for 30 minutes", wed))
        assertEquals("2026-10-22 15:00", a.getString("start")); assertEquals("2026-10-22 15:30", a.getString("end"))
        assertFalse(ReplyFix.fixRelativeDay(a, "add a calendar event QA review tomorrow at 15:00", wed))   // already right
        val b = JSONObject().put("start", "2026-10-30 09:00")
        assertFalse(ReplyFix.fixRelativeDay(b, "add a meeting on Friday the 30th at 9", wed))   // no relative day: untouched
        assertEquals(LocalDate.of(2026, 10, 23), ReplyFix.askedDay("the day after tomorrow at noon", wed))
    }
}

/** Review 22 Oct (#4, #6, #7): one cap on re-prompts per turn, malformed calls dropped, no unchecked fast-lane "Done". */
class Review1022Test {
    @Test fun malformedCallsAreDropped() {
        val all = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "tap").put("parameters", JSONObject())))
        fun call(n: String, a: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n).put("arguments", a))
        val r = JSONObject().put("tool_calls", JSONArray().put(call("tap", "{\"text\":\"OK\"}")).put(call("tap", "{broken")).put(call("nope", "{}")))
        assertEquals(2, ToolCalls.dropMalformed(r, all))
        assertEquals(1, r.getJSONArray("tool_calls").length())
        val r2 = JSONObject().put("tool_calls", JSONArray().put(call("nope", "{}")))
        assertEquals(1, ToolCalls.dropMalformed(r2, all)); assertFalse(r2.has("tool_calls"))
    }

    @Test fun oneNudgeBudgetAndNoBlindFastLaneDone() {
        assertEquals(2, RetryGuard.MAX)
        val src = listOf(java.io.File("src/main/java/com/blaze/assistant/agent/Agent.kt"), java.io.File("app/src/main/java/com/blaze/assistant/agent/Agent.kt")).first { it.exists() }.readText()
        // Every re-prompt kind spends the same per-turn budget.
        for (k in listOf("leakNudged", "refusalNudged", "vncNudged", "echoNudged", "emptyNudged", "learnNudged"))
            assertTrue(k, Regex(k + " = true; nudgesLeft--").containsMatchIn(src))
        assertTrue(FastLane.FAIL_STARTS.containsAll(listOf("No app", "Error", "Couldn't")))
        val fl = listOf(java.io.File("src/main/java/com/blaze/assistant/agent/FastLane.kt"), java.io.File("app/src/main/java/com/blaze/assistant/agent/FastLane.kt")).first { it.exists() }.readText()
        assertTrue(fl.contains("\"media_control\" -> null") && fl.contains("\"navigate_to\" -> null") && fl.contains("\"set_alarm\", \"set_timer\" -> null"))
    }
}
