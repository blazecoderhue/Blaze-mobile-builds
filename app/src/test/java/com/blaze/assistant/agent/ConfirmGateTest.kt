package com.blaze.assistant.agent

import com.blaze.assistant.agent.ConfirmGate.Decision.AUTOPILOT_OK
import com.blaze.assistant.agent.ConfirmGate.Decision.SHOW
import com.blaze.assistant.agent.ConfirmGate.Decision.STANDING_OK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v0.28.2: the confirm card's decision table (prompt-injection hardening). */
class ConfirmGateTest {
    private fun d(always: Boolean = false, destructive: Boolean = false, pre: Boolean = false, auto: Boolean = false, tainted: Boolean = false) =
        ConfirmGate.decide(always, destructive, pre, auto, tainted)

    @Test fun decisionTable() {
        // Every combination of the five inputs, against the rule written out by hand.
        for (bits in 0 until 32) {
            val always = bits and 1 != 0; val destructive = bits and 2 != 0; val pre = bits and 4 != 0
            val auto = bits and 8 != 0; val tainted = bits and 16 != 0
            val want = when {
                always -> SHOW
                tainted && (destructive || pre) -> SHOW
                pre -> STANDING_OK
                auto && !destructive -> AUTOPILOT_OK
                else -> SHOW
            }
            assertEquals("bits=$bits", want, d(always, destructive, pre, auto, tainted))
        }
    }

    @Test fun outboundAlwaysAsks() {
        // send_sms / call / WhatsApp / email / post / pay / delete / install / settings: always = true.
        assertEquals(SHOW, d(always = true, pre = true))
        assertEquals(SHOW, d(always = true, auto = true))
        assertEquals(SHOW, d(always = true, pre = true, auto = true))
    }

    @Test fun taintRemovesStandingOkAndAutopilotForRiskyActions() {
        assertEquals(STANDING_OK, d(pre = true))
        assertEquals(SHOW, d(pre = true, tainted = true))
        assertEquals(SHOW, d(destructive = true, auto = true, tainted = true))
        // Harmless actions in Autopilot still pass after reading a page (e.g. opening an app).
        assertEquals(AUTOPILOT_OK, d(auto = true, tainted = true))
        assertEquals(SHOW, d(destructive = true, auto = true))
        assertEquals(SHOW, d())
    }

    @Test fun cardSaysWhatWasRead() {
        assertEquals("“hi”", ConfirmGate.detail("“hi”", null))
        assertEquals("Requested after reading a web page.\n“hi”", ConfirmGate.detail("“hi”", "a web page"))
        assertEquals("Requested after reading your messages.", ConfirmGate.detail("", "your messages"))
    }

    @Test fun untrustedSources() {
        assertEquals("a web page", ConfirmGate.sourceOf("browser_read"))
        assertEquals("a web page", ConfirmGate.sourceOf("browser_open"))
        assertEquals("a fetched link", ConfirmGate.sourceOf("fetch_page"))
        assertEquals("a fetched link", ConfirmGate.sourceOf("http_request"))
        assertEquals("web search results", ConfirmGate.sourceOf("web_search"))
        assertEquals("notifications", ConfirmGate.sourceOf("recent_notifications"))
        assertEquals("your messages", ConfirmGate.sourceOf("read_messages"))
        assertEquals("your email", ConfirmGate.sourceOf("gmail_read"))
        assertEquals("the screen", ConfirmGate.sourceOf("read_screen"))
        assertEquals("an MCP tool's answer", ConfirmGate.sourceOf("mcp__notes__read"))
        assertNull(ConfirmGate.sourceOf("send_sms"))
        assertNull(ConfirmGate.sourceOf("memory_search"))
        assertNull(ConfirmGate.sourceOf("set_timer"))
    }

    @Test fun taintKeepsTheFirstSourceUntilCleared() {
        val t = ConfirmGate.Taint()
        assertNull(t.source)
        t.mark(null); assertNull(t.source)
        t.mark("a web page"); t.mark("your messages")
        assertEquals("a web page", t.source)
        t.clear(); assertNull(t.source)
        t.mark(ConfirmGate.SHARED_FILE); assertEquals("a shared file", t.source)
    }
}
