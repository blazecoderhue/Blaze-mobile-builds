package com.blaze.assistant.agent

import com.blaze.assistant.agent.ClaimCheck.Done
import com.blaze.assistant.agent.ClaimCheck.Outcome
import com.blaze.assistant.agent.ToolVerify.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimCheckTest {
    @Test fun theDesktopCaseIsRewritten() {
        val ask = "start the linux desktop and open dillo"
        val reply = "The Linux desktop is running and Dillo is open. Have fun browsing!"
        val done = listOf(Done("desktop", Status.FAILED, "the desktop is NOT running: nothing answers on port 5901"))
        val o = ClaimCheck.review(reply, done, ask)
        assertTrue(o is Outcome.Rewrite)
        val t = (o as Outcome.Rewrite).text
        assertTrue(t, t.startsWith("I tried to get that running, but it didn't work: the desktop is NOT running"))
        assertFalse(t.contains("is running and Dillo"))
        assertTrue(t.contains("Have fun"))
    }

    @Test fun verifiedClaimsStand() {
        val o = ClaimCheck.review("The desktop is running.", listOf(Done("desktop", Status.VERIFIED)), "start the desktop")
        assertEquals(Outcome.Ok, o)
        assertEquals(Outcome.Ok, ClaimCheck.review("Alarm set for 7:00.", listOf(Done("set_alarm", Status.VERIFIED)), "set an alarm for 7"))
    }

    @Test fun theNewestCallDecides() {
        val done = listOf(Done("desktop", Status.VERIFIED), Done("desktop", Status.UNVERIFIED, "dillo didn't show up as a running process"))
        val o = ClaimCheck.review("Dillo is running now.", done, "open dillo in the desktop")
        assertTrue(o is Outcome.Rewrite)
        assertTrue((o as Outcome.Rewrite).text.contains("couldn't confirm it worked (dillo didn't show up"))
        val fixed = listOf(Done("open_app", Status.UNVERIFIED), Done("open_app", Status.VERIFIED))
        assertEquals(Outcome.Ok, ClaimCheck.review("I opened WhatsApp.", fixed, "open whatsapp"))
    }

    @Test fun unconfirmedSettingAndTimer() {
        val o = ClaimCheck.review("Done, I turned on Wi-Fi.", listOf(Done("device_setting", Status.UNVERIFIED, "only the settings screen was opened; the setting itself was not changed")), "turn on wifi")
        assertTrue(o is Outcome.Rewrite)
        assertEquals("I tried to change that setting, but I couldn't confirm it worked (only the settings screen was opened; the setting itself was not changed).", (o as Outcome.Rewrite).text)
        val t = ClaimCheck.review("Timer is running for 10 minutes.", listOf(Done("set_timer", Status.UNVERIFIED, "the timer was handed to the Clock app; nothing read it back")), "set a timer for 10 minutes")
        assertTrue(t is Outcome.Rewrite)
    }

    @Test fun noToolAtAllGoesToTheReprompt() {
        val o = ClaimCheck.review("Message sent!", emptyList(), "text mum I'm late")
        assertTrue(o is Outcome.Unbacked)
        assertEquals("send", (o as Outcome.Unbacked).action.id)
    }

    @Test fun honestRepliesAndUnrelatedTalkPass() {
        assertEquals(Outcome.Ok, ClaimCheck.review("The desktop isn't running yet. Want me to repair it?", emptyList(), "is the desktop running?"))
        assertEquals(Outcome.Ok, ClaimCheck.review("Paris is the capital of France.", emptyList(), "what is the capital of france"))
        assertEquals(Outcome.Ok, ClaimCheck.review("I tried to start it but couldn't confirm it.", listOf(Done("desktop", Status.UNVERIFIED)), "start the desktop"))
        assertEquals(Outcome.Ok, ClaimCheck.review("", emptyList(), "open whatsapp"))
    }

    @Test fun layoutIsKept() {
        val reply = "Here's what happened:\n\n- Alarm set for 7:00.\n- Your notes are below."
        val o = ClaimCheck.review(reply, listOf(Done("set_alarm", Status.UNVERIFIED, "the Clock app didn't list an alarm at 07:00 as the next one")), "set an alarm for 7 and show my notes")
        val t = (o as Outcome.Rewrite).text
        assertTrue(t, t.contains("\n\n- I tried to set that alarm or timer, but I couldn't confirm it worked"))
        assertTrue(t.contains("\n- Your notes are below."))
    }
}
