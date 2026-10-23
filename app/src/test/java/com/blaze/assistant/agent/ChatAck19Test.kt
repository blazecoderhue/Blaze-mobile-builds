package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.19 chat fix 1 (user rules, phone screenshots 09:20-10:10): no placeholder ack, questions are answered not acked,
 * an ack is one specific line or nothing, a fast task gets only its result, no "ack" label, no "Done ✓" glued to an
 * answer or a "no", Halo's handoff line never shows. Strict 5/5: every scenario runs five times in a row.
 */
class ChatAck19Test {

    private fun fiveTimes(body: (Int) -> Unit) = repeat(5) { body(it) }

    private val placeholder = "Got it, I'm on it. I'll message you here when it's done."

    @Test fun neverThePlaceholder() = fiveTimes {
        assertFalse(Ack.safe(placeholder))
        assertTrue(Ack.isGeneric(placeholder))
        val g = Ack.Gate()
        assertFalse(g.take(placeholder))
        assertFalse(g.acked)
        // Whatever the task, the on-device ack is never a placeholder (it is specific or null).
        for (t in listOf("book a room on automationintesting.online for next weekend, name Test Blaze, email test@example.com, phone 01234567890",
            "open the-Internet.herokuapp.com/login on Blaze phone", "turn on dark mode please", "do the thing", "make it happen", "start",
            "set it up", "cancle booking", "fix this for me please and then also do the other thing I told you about yesterday at the shop")) {
            val a = Ack.local(t)
            if (a != null) { assertFalse("$t -> $a", Ack.isGeneric(a)); assertTrue(a, Ack.safe(a)) }
        }
    }

    @Test fun questionsAreAnsweredNotAcked() = fiveTimes {
        for (q in listOf("did you already book?", "what is Blaze phone doing", "is the alarm set?", "how's the booking going?",
            "Did you send it", "where are you on the booking?", "has it opened yet", "why did it stop?", "booking done?")) {
            assertTrue(q, Ack.isQuestion(q))
            assertFalse(q, Ack.wants(q))
            assertNull(q, Ack.local(q))
        }
        // A request phrased politely as a question is still a task.
        assertEquals("Turning on dark mode.", Ack.local("can you turn on dark mode?"))
        assertEquals("Turning off bluetooth.", Ack.local("Could you please turn off bluetooth?"))
    }

    @Test fun ackIsOneSpecificLine() = fiveTimes {
        assertEquals("Turning on dark mode.", Ack.local("turn on dark mode please"))
        assertEquals("Opening the-Internet.herokuapp.com/login on Blaze phone.", Ack.local("open the-Internet.herokuapp.com/login on Blaze phone"))
        assertEquals("Booking a room on automationintesting.online for next weekend.",
            Ack.local("book a room on automationintesting.online for next weekend, name Test Blaze, email test@example.com, phone 01234567890"))
        assertEquals("Deleting your 7am alarm.", Ack.local("delete my 7am alarm"))   // a delete, never "Setting an alarm"
        assertEquals("Finding you a cheap flight to Lusaka.", Ack.local("find me a cheap flight to Lusaka"))
        assertEquals("Starting a 10-minute timer.", Ack.local("set a timer for 10 minutes"))
        // Nothing specific to say: no ack at all.
        assertNull(Ack.local("cancle booking"))
        assertNull(Ack.local("open " + "x".repeat(Ack.OBJECT_MAX + 5)))
        // One line, short, no label or prefix.
        val a = Ack.local("turn on wifi")!!
        assertFalse(a.contains('\n')); assertFalse(a.startsWith("Got it")); assertTrue(a.length < 90)
    }

    @Test fun fastTasksGetOnlyTheirResult() = fiveTimes { r ->
        val start = 1_000_000L * (r + 1)
        assertEquals(2_000L, Ack.FAST_MS)
        assertEquals(Ack.FAST_MS, Ack.holdMs(start, start))
        assertEquals(1_500L, Ack.holdMs(start, start + 500))
        assertEquals(0L, Ack.holdMs(start, start + 2_000))
        assertEquals(0L, Ack.holdMs(start, start + 60_000))
        // The answer came first (a 300 ms toggle): the gate is closed and no ack follows the result.
        val g = Ack.Gate(at = start)
        g.finished = true
        assertFalse(g.take("Turning on dark mode."))
        assertFalse(g.acked)
    }

    @Test fun noAckLabelAndNoStatusChipInTheActionRow() = fiveTimes {
        assertEquals("", ReplyChips.visible(Ack.META, false))
        assertEquals("", ReplyChips.visible(Ack.META, true))
        assertEquals("", ReplyChips.visible("\u26a0 Couldn't confirm: couldn't read the switch back", false))
        assertEquals("", ReplyChips.visible("\u2713 Phone status", false))
        assertEquals("", ReplyChips.visible("\u2717 Phone act didn't work \u00b7 \u2713 Phone open \u00b7 \u2713 Phone read \u00b7 +1 more", false))
        assertEquals("Memory updated", ReplyChips.visible("\u2713 Phone act \u00b7 Memory updated", false))
        assertEquals("Heads-up \u00b7 14:02", ReplyChips.visible("Heads-up \u00b7 14:02", false))
    }

    @Test fun doneTickNeverOnAnAnswerOrANo() = fiveTimes {
        val no = "No, the booking was not completed. I can see the reservation page is showing the booking form but no confirmation message."
        assertEquals(no, Ack.receipt(no, verified = 1, unproven = 0, rewrote = false))
        assertEquals("The page shows the form.", Ack.receipt("The page shows the form.", 1, 0, false, asked = "did you already book?"))
        assertEquals("I couldn't confirm it.", Ack.receipt("I couldn't confirm it.", 1, 0, false))
        assertEquals("Done \u2713 Dark mode is on.", Ack.receipt("Dark mode is on.", 1, 0, false, asked = "turn on dark mode"))
        // Looking is not doing: status / read / screenshot tools never count as a verified action.
        for (t in listOf("phone_status", "phone_read", "phone_screenshot", "web_search", "fetch_page", "look_at_screen")) assertTrue(t, Ack.isReadOnly(t))
        for (t in listOf("phone_act", "set_alarm", "device_setting", "send_message")) assertFalse(t, Ack.isReadOnly(t))
    }

    @Test fun halosHandoffLineIsNeverShown() = fiveTimes {
        for (h in listOf("Got it \u2014 passing that straight to Blaze.", "Passed to Blaze \u2014 let me know if you need anything else while that runs.",
            "Handing that over to Blaze.", "On it!", "Halo here, I'll handle it."))
            assertTrue(h, Ack.isGeneric(h))
        assertTrue(Duo.isRelay("[[relay]]"))
        assertTrue(Duo.sideSystem(null, "book a room", "").contains("reply with only the tag [[relay]]"))
        assertFalse(Duo.sideSystem(null, "book a room", "").contains("say in one short warm line that you've passed it to Blaze"))
        // A real answer from Halo is not a handoff.
        assertFalse(Ack.isGeneric("The room is \u00a3100 a night with check-in on 31 October."))
    }

    @Test fun ackWithCallTextIsNeverShown() = fiveTimes {
        // showAck drops an ack that carries call text; the scanner behind that guard sees these.
        assertTrue(CallScan.has("<tool_call>{\"name\": \"browser_open\", \"arguments\": {\"url\": \"https://example.com\"}}</tool_call>"))
        assertNotNull(Ack.local("open example.com"))
        assertFalse(CallScan.has(Ack.local("open example.com")!!))
    }
}
