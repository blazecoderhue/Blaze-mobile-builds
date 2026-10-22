package com.blaze.assistant.voice.myvoice

import com.blaze.assistant.voice.myvoice.VoiceRoute.Reason
import com.blaze.assistant.voice.myvoice.VoiceRoute.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRouteTest {
    private val now = 10_000_000_000L

    @Test fun picksVoice() {
        assertEquals(Route.SYSTEM to Reason.NOT_CHOSEN, VoiceRoute.pick(false, true, true, false, 0, false, now))
        assertEquals(Route.SYSTEM to Reason.NOT_SET_UP, VoiceRoute.pick(true, false, true, false, 0, false, now))
        assertEquals(Route.SYSTEM to Reason.NOT_SET_UP, VoiceRoute.pick(true, true, false, false, 0, false, now))
        assertEquals(Route.SYSTEM to Reason.FAILED, VoiceRoute.pick(true, true, true, true, 0, false, now))
        assertEquals(Route.MINE to Reason.NONE, VoiceRoute.pick(true, true, true, false, 0, false, now))
    }

    @Test fun slowCoolsDownThenRetries() {
        assertEquals(Route.SYSTEM to Reason.SLOW, VoiceRoute.pick(true, true, true, false, now - 1000, false, now))
        assertEquals(Route.MINE to Reason.NONE, VoiceRoute.pick(true, true, true, false, now - VoiceRoute.SLOW_COOLDOWN_MS - 1, false, now))
        assertEquals(Route.MINE to Reason.NONE, VoiceRoute.pick(true, true, true, false, now - 1000, true, now))
    }

    @Test fun tellsOnce() {
        val (t1, s1) = VoiceRoute.tellOnce(Reason.SLOW, "")
        assertTrue(t1!!.contains("too slow"))
        val (t2, s2) = VoiceRoute.tellOnce(Reason.SLOW, s1)
        assertNull(t2); assertEquals(s1, s2)
        val (t3, s3) = VoiceRoute.tellOnce(Reason.FAILED, s2)
        assertTrue(t3!!.isNotBlank()); assertEquals("FAILED,SLOW", s3)
        assertNull(VoiceRoute.tellOnce(Reason.NOT_CHOSEN, "").first)
    }

    @Test fun lowEndDefaults() {
        val lowEndRam = 3L * 1024 * 1024 * 1024
        assertTrue(VoiceRoute.lowEnd(lowEndRam, 8))
        assertEquals(3, VoiceRoute.numSteps("auto", true))
        assertEquals(2, VoiceRoute.numSteps("fast", false))
        assertEquals(3, VoiceRoute.numSteps("auto", false))
        assertEquals(5, VoiceRoute.numSteps("best", true))
        assertEquals(4, VoiceRoute.threads(8))
    }

    @Test fun speedWatch() {
        val w = SpeedWatch(keepWhenSlow = false)
        assertEquals(SpeedWatch.Verdict.KEEP, w.onPiece(3000, 2000))  // first piece may be slow (warm-up)
        assertEquals(SpeedWatch.Verdict.KEEP, w.onPiece(1500, 2000))
        assertEquals(SpeedWatch.Verdict.KEEP, w.onPiece(1800, 2000))
        assertEquals(SpeedWatch.Verdict.SLOW, w.onPiece(3000, 2000))
        val hopeless = SpeedWatch(keepWhenSlow = false)
        assertEquals(SpeedWatch.Verdict.SLOW, hopeless.onPiece(9000, 2000))
        val keep = SpeedWatch(keepWhenSlow = true)
        assertEquals(SpeedWatch.Verdict.KEEP, keep.onPiece(9000, 2000))
        assertEquals(4.5f, keep.lastRtf, 0.01f)
        assertTrue(SpeedWatch.describe(0.5f).startsWith("Fast"))
        assertTrue(SpeedWatch.describe(1.5f).contains("pause"))
    }
}
