package com.blaze.assistant.agent

import com.blaze.assistant.agent.ConfirmGate.ScreenDecision.ASK
import com.blaze.assistant.agent.ConfirmGate.ScreenDecision.ASK_ONCE
import com.blaze.assistant.agent.ConfirmGate.ScreenDecision.PASS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.45.18: screen actions that web pages or messages could have triggered need the user's OK (prompt injection). */
class ScreenActGateTest {
    private fun d(tool: String, label: String, tainted: Boolean = false, outside: String? = null, ok: Boolean = false) =
        ConfirmGate.screenAct(tool, label, tainted, outside, ok)

    @Test fun cleanTurnPasses() {
        assertEquals(PASS, d("tap", "Wi-Fi"))
        assertEquals(PASS, d("tap", "Send"))            // the tap branch's own risky card still covers this
        assertEquals(PASS, d("type_text", "hello"))
    }

    @Test fun screenOnlyTurnAsksForSendsAndMoney() {
        assertEquals(ASK, d("tap", "Send", tainted = true))
        assertEquals(ASK, d("tap", "Pay now", tainted = true))
        assertEquals(PASS, d("tap", "Allow", tainted = true))
        assertEquals(PASS, d("type_text", "hi", tainted = true))
    }

    @Test fun outsideContentAsksOnceThenOnlyForOutbound() {
        val web = "a web page"
        assertEquals(ASK_ONCE, d("tap", "Wi-Fi", true, web))
        assertEquals(ASK_ONCE, d("type_text", "hello", true, web))
        assertEquals(ASK_ONCE, d("press_button", "enter", true, web))
        assertEquals(ASK_ONCE, d("run_plan", "", true, web))
        assertEquals(PASS, d("tap", "Wi-Fi", true, web, ok = true))
        assertEquals(PASS, d("type_text", "hello", true, web, ok = true))
        // Outbound taps ask every time, even after the turn's OK.
        for (l in listOf("Send", "Reply", "Post", "Share", "Confirm", "Allow", "Install", "Submit", "Delete"))
            assertEquals(l, ASK, d("tap", l, true, "your messages", ok = true))
    }

    @Test fun otherToolsUntouched() {
        for (t in listOf("read_screen", "scroll", "open_app", "message_contact")) assertEquals(t, PASS, d(t, "Send", true, "a web page"))
    }

    @Test fun taintTracksOutsideSource() {
        val t = ConfirmGate.Taint()
        t.mark(ConfirmGate.sourceOf("read_screen"))
        assertEquals("the screen", t.source); assertNull(t.outside)
        t.mark(ConfirmGate.sourceOf("browser_read"))
        assertEquals("the screen", t.source); assertEquals("a web page", t.outside)
        t.screenOk = true; t.clear()
        assertNull(t.source); assertNull(t.outside); assertEquals(false, t.screenOk)
    }
}
