package com.blaze.assistant.agent

import com.blaze.assistant.agent.ToolVerify.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.14 (user, live 0.45.13 21:38): the pointer overlay paused every action. Strict case: taps go through under it, never "paused". */
class PointerOverlayTest {
    @Test fun overlayCaseIsStrict() {
        val c = PhoneCases.byId("overlay-taps")!!
        assertEquals(PhoneCases.Verifier.OVERLAY_TAP, c.verifier)
        assertEquals(setOf("tap"), c.proof)
        assertEquals("open_settings_page", ToolTruth.phoneChoice(c.prompt, listOf("open_settings_page", "device_setting", "tap")))
        assertEquals("tap", ToolTruth.follow(c.prompt)["open_settings"]); assertEquals("tap", ToolTruth.follow(c.prompt)["open_settings_page"])
        assertTrue(PhoneCases.judge(c, listOf("open_settings_page" to Status.VERIFIED, "tap" to Status.VERIFIED), "Do Not Disturb is on.").pass)
        assertFalse(PhoneCases.judge(c, listOf("tap" to Status.VERIFIED), "Paused. Do Not Disturb is on.").pass)
        assertFalse(PhoneCases.judge(c, listOf("tap" to Status.UNVERIFIED), "Do Not Disturb is on.").pass)
    }

    @Test fun pausedIsSpotted() {
        assertTrue(PhoneCases.pausedIn("Paused before tapping [3]"))
        assertTrue(PhoneCases.pausedIn("step: Paused: you have the pointer"))
        assertFalse(PhoneCases.pausedIn("Tapped \u201cDo not disturb\u201d. Checked on the phone: the switch now reads on."))
        // The tap loop no longer treats a "Paused before" line as a known result shape; Stop is the only stop.
        assertTrue(TapLoop.failed("Stopped before tapping [2]: the user tapped Stop. Nothing was tapped."))
    }
}
