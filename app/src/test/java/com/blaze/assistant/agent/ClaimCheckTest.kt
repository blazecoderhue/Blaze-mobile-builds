package com.blaze.assistant.agent

import com.blaze.assistant.agent.ClaimCheck.Done
import com.blaze.assistant.agent.ClaimCheck.Outcome
import com.blaze.assistant.agent.ToolVerify.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimCheckTest {
    @Test fun theFailedStartCaseIsRewritten() {
        val ask = "start the preview server and run it"
        val reply = "The server is running and the site is up. Have fun browsing!"
        val done = listOf(Done("preview_server", Status.FAILED, "nothing answers on port 8080"))
        val o = ClaimCheck.review(reply, done, ask)
        assertTrue(o is Outcome.Rewrite)
        val t = (o as Outcome.Rewrite).text
        assertTrue(t, t.startsWith("I tried to get that running, but it didn't work: nothing answers on port 8080"))
        assertFalse(t.contains("is running and the site"))
        assertTrue(t.contains("Have fun"))
    }

    @Test fun verifiedClaimsStand() {
        val o = ClaimCheck.review("The server is running.", listOf(Done("preview_server", Status.VERIFIED)), "start the server")
        assertEquals(Outcome.Ok, o)
        assertEquals(Outcome.Ok, ClaimCheck.review("Alarm set for 7:00.", listOf(Done("set_alarm", Status.VERIFIED)), "set an alarm for 7"))
    }

    @Test fun theNewestCallDecides() {
        val done = listOf(Done("run_shell", Status.VERIFIED), Done("run_shell", Status.UNVERIFIED, "the server didn't show up as a running process"))
        val o = ClaimCheck.review("The server is running now.", done, "run the server")
        assertTrue(o is Outcome.Rewrite)
        assertTrue((o as Outcome.Rewrite).text.contains("couldn't confirm it worked (the server didn't show up"))
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
        assertEquals(Outcome.Ok, ClaimCheck.review("The server isn't running yet. Want me to start it?", emptyList(), "is the server running?"))
        assertEquals(Outcome.Ok, ClaimCheck.review("Paris is the capital of France.", emptyList(), "what is the capital of france"))
        assertEquals(Outcome.Ok, ClaimCheck.review("I tried to start it but couldn't confirm it.", listOf(Done("preview_server", Status.UNVERIFIED)), "start the server"))
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
