package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoCenterTest {
    @Test fun tenSecondCountdown() {
        val o = UndoCenter.Offer("Scheduled", 1_000L, { "x" })
        assertEquals(10, UndoCenter.secondsLeft(o, 1_000L))
        assertEquals(8, UndoCenter.secondsLeft(o, 3_500L))
        assertEquals(0, UndoCenter.secondsLeft(o, 20_000L))
        assertFalse(UndoCenter.expired(o, 10_999L)); assertTrue(UndoCenter.expired(o, 11_000L))
    }

    @Test fun undoRunsOnceAndClears() {
        var n = 0
        UndoCenter.offer("Scheduled \u201cX\u201d", { n++; "Undone" })
        assertEquals("Undone", UndoCenter.undoNow())
        assertNull(UndoCenter.undoNow()); assertEquals(1, n)
    }

    @Test fun onlyUnderAutopilotAndOnlyUndoable() {
        UndoCenter.dismiss()
        UndoCenter.afterTool("create_routine", JSONObject().put("name", "X"), "Created", autopilot = false, before = null)
        assertNull(UndoCenter.current.value)
        UndoCenter.afterTool("send_sms", JSONObject(), "Sent", autopilot = true, before = null)
        assertNull(UndoCenter.current.value)
        assertEquals("Scheduled \u201cMorning brief\u201d", UndoCenter.label("create_routine", "Morning brief"))
    }
}
