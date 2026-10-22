package com.blaze.assistant.desktop

import com.blaze.assistant.desktop.DesktopNarration.Live
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.25.5: the live desktop card's plain words and when it shows. */
class DesktopNarrationTest {
    private fun say(vararg kv: Pair<String, Any>) = DesktopNarration.status(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } })

    @Test fun actionsInPlainWords() {
        assertEquals("Taking a look", say("action" to "screenshot"))
        assertEquals("Clicking Save", say("action" to "click", "x" to 10, "y" to 20, "label" to "the Save button"))
        assertEquals("Clicking", say("action" to "click", "x" to 10, "y" to 20))
        assertEquals("Double-clicking Home", say("action" to "click", "double" to true, "label" to "Home"))
        assertEquals("Right-clicking", say("action" to "click", "button" to 3))
        assertEquals("Typing into Terminal", say("action" to "type", "text" to "ls -la", "label" to "Terminal"))
        assertEquals("Typing", say("action" to "type", "text" to "secret"))
        assertEquals("Opening Files", say("action" to "run", "app" to "files"))
        assertEquals("Opening Terminal", say("action" to "run", "app" to "xfce4-terminal"))
        assertEquals("Opening the browser", say("action" to "run", "app" to "netsurf"))
        assertEquals("Opening gimp", say("action" to "run", "app" to "/usr/bin/gimp --new"))
        assertEquals("Pressing Ctrl+S", say("action" to "key", "keys" to "ctrl+s"))
        assertEquals("Pressing Enter", say("action" to "key", "keys" to "Return; reboot"))
        assertEquals("Starting the desktop", say("action" to "start"))
        assertEquals("Setting up the desktop", DesktopNarration.status(JSONObject().put("action", "start"), installed = false))
        assertNull(say("action" to "status")); assertNull(say("action" to "open"))
    }

    @Test fun typedTextIsNeverShown() {
        val s = say("action" to "type", "text" to "hunter2 password", "label" to "Password")!!
        assertFalse(s.contains("hunter2"))
    }

    @Test fun labelsStayShortAndPlain() {
        assertEquals("Save", DesktopNarration.clean("\u201cSave\u201d button"))
        assertEquals("File menu", DesktopNarration.clean("the File menu"))
        assertTrue(DesktopNarration.clean("A really very long label that goes on and on").length <= 22)
        assertEquals("rm -rf", DesktopNarration.clean("rm -rf; \$(evil)").trim().take(6))
        assertTrue(say("action" to "click", "label" to "x".repeat(200))!!.length <= 34)
    }

    @Test fun showsWhileActingAndHidesAfter() {
        var s = Live()
        assertFalse(s.show(0))
        s = s.begin("Clicking Save", 1_000, 10, 20)
        assertTrue(s.show(1_000)); assertEquals("Clicking Save", s.pill(5_000)); assertEquals(10, s.x)
        s = s.end(2_000)
        assertTrue(s.show(3_000)); assertEquals("Clicking Save", s.pill(2_500))
        assertEquals(DesktopNarration.THINKING, s.pill(2_000 + DesktopNarration.THINKING_AFTER_MS))
        // Still busy but no desktop action for a long while: it goes.
        assertFalse(s.show(2_000 + DesktopNarration.IDLE_HIDE_MS))
        // The run ends: a few seconds of "Done", then gone.
        s = s.runEnded(4_000)
        assertTrue(s.show(4_000 + DesktopNarration.HIDE_AFTER_MS - 1)); assertEquals("Done", s.pill(4_100))
        assertFalse(s.show(4_000 + DesktopNarration.HIDE_AFTER_MS))
        assertTrue(s.expired(4_000 + DesktopNarration.HIDE_AFTER_MS))
        // The next action keeps the last click ring and comes back.
        s = s.begin("Taking a look", 20_000); assertTrue(s.show(20_000)); assertEquals(10, s.x)
    }

    @Test fun xStopsTheRunsDesktopWork() {
        var s = Live().begin("Typing", 0).stop()
        assertTrue(s.stopped); assertFalse(s.show(1))
        s = s.begin("Clicking", 10)            // ignored while stopped
        assertFalse(s.show(10)); assertTrue(s.stopped)
        s = s.runEnded(20)                     // a new run may use the desktop again
        assertFalse(s.stopped)
        assertTrue(s.begin("Clicking", 30).show(30))
    }
}
