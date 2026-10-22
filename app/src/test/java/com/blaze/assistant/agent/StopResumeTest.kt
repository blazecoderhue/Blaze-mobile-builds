package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.0 P3 #38 / #5: a clean Stop (tidy history) and Resume with the pinned goal and receipts. */
class StopResumeTest {
    private fun call(id: String, name: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", "{}"))
    private fun shot() = JSONObject().put("role", "user").put("content", JSONArray()
        .put(JSONObject().put("type", "text").put("text", "[screenshot]"))
        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:x"))))

    @Test fun stopMidToolAnswersTheDanglingCallHonestly() {
        val h = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "Text mum I'm late, don't call her"))
            .put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(call("c1", "open_app")).put(call("c2", "message_contact"))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("name", "open_app").put("content", "Opened WhatsApp"))
        val t = StopResume.tidy(h)
        assertEquals(listOf("message_contact"), t.interrupted)
        val last = h.getJSONObject(h.length() - 1)
        assertEquals("tool", last.getString("role")); assertEquals("c2", last.getString("tool_call_id"))
        assertTrue(last.getString("content").startsWith("Interrupted"))
        // Every call is answered exactly once now (a second tidy adds nothing).
        assertEquals(emptyList<String>(), StopResume.tidy(h).interrupted)
        assertEquals(4, h.length())
    }

    @Test fun theUnreadScreenshotIsDropped() {
        val h = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "Open settings"))
            .put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(call("c1", "tap"))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "Tapped"))
            .put(shot())
        val t = StopResume.tidy(h)
        assertTrue(t.droppedShot)
        assertEquals(3, h.length())
        assertTrue(t.interrupted.isEmpty())
    }

    @Test fun aCleanHistoryIsLeftAlone() {
        val h = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")).put(JSONObject().put("role", "assistant").put("content", "Hello"))
        val t = StopResume.tidy(h)
        assertFalse(t.droppedShot); assertTrue(t.interrupted.isEmpty()); assertEquals(2, h.length())
    }

    @Test fun resumeWordsAndTheChip() {
        assertTrue(StopResume.isResume(StopResume.CHIP))
        assertTrue(StopResume.isResume("continue"))
        assertTrue(StopResume.isResume("Resume."))
        assertTrue(StopResume.isResume("carry on"))
        assertTrue(StopResume.isResume("endelea"))
        assertFalse(StopResume.isResume("continue the story about dragons from chapter two"))
        assertFalse(StopResume.isResume("send it to dad instead"))
        // The goal pin treats the chip as a follow-up (it keeps the earlier request).
        assertTrue(GoalPin.isFollowUp(StopResume.CHIP))
        assertEquals("Book the 7pm bus \u2192 ${StopResume.CHIP}", GoalPin.of(StopResume.CHIP, listOf("Book the 7pm bus")).request)
    }

    @Test fun pendingKeepsGoalReceiptsAndInterruptedAndSurvivesSaving() {
        val goal = GoalPin.of("Text mum I'm late, don't call her")
        val done = StopResume.doneLines(listOf(
            Receipt.Entry("open_app", "", ToolVerify.Status.VERIFIED, "Opened", "", label = "Open WhatsApp"),
            Receipt.Entry("web_search", "", ToolVerify.Status.VERIFIED, "x", "", label = "Search")))
        assertEquals(listOf("Open WhatsApp: done and checked"), done)   // look-ups are not "done" actions
        val p = StopResume.pending(goal, done, listOf("message_contact"), at = 5)!!
        val back = StopResume.fromJson(JSONObject(p.toJson().toString()))!!
        assertEquals(p, back)
        val note = StopResume.note(back)
        assertTrue(note.contains("Text mum I'm late"))
        assertTrue(note.contains("don't call her"))
        assertTrue(note.contains("Open WhatsApp: done and checked"))
        assertTrue(note.contains("message_contact"))
        assertNull(StopResume.pending(null, done, emptyList()))
        assertNull(StopResume.fromJson(JSONObject()))
    }
}
