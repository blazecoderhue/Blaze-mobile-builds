package com.blaze.assistant.voice.myvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioClipTest {
    private val sr = 24000
    private fun silence(sec: Float, level: Float = 0.001f) = FloatArray((sec * sr).toInt()) { if (it % 2 == 0) level else -level }
    private fun tone(sec: Float, amp: Float = 0.3f) = FloatArray((sec * sr).toInt()) { (amp * sin(2 * PI * 220 * it / sr)).toFloat() }

    @Test fun trimsLeadingAndTrailingSilence() {
        val x = AudioClip.concat(silence(1f), tone(2f), silence(1.5f))
        val y = AudioClip.trimSilence(x, sr)
        val sec = y.size / sr.toFloat()
        assertTrue("got $sec", sec in 2.0f..2.5f)
    }

    @Test fun nothingHeard() {
        assertEquals(0, AudioClip.trimSilence(FloatArray(sr * 2), sr).size)
        assertEquals(AudioClip.Verdict.NOTHING_HEARD, AudioClip.prepareReference(FloatArray(sr * 3), sr).verdict)
    }

    @Test fun shortensLongPauses() {
        val x = AudioClip.concat(tone(1f), silence(2f), tone(1f))
        val y = AudioClip.shortenPauses(x, sr, maxPauseMs = 300)
        val sec = y.size / sr.toFloat()
        assertTrue("got $sec", sec in 2.2f..2.45f)
    }

    @Test fun normalisesWithCappedGain() {
        val y = AudioClip.normalize(tone(1f, 0.1f))
        assertEquals(0.89f, y.maxOf { abs(it) }, 0.01f)
        val quiet = AudioClip.normalize(tone(1f, 0.01f), maxGain = 12f)
        assertEquals(0.12f, quiet.maxOf { abs(it) }, 0.01f)
    }

    @Test fun prepareCapsAtTenSecondsAndJudges() {
        val speechy = AudioClip.concat(*Array(12) { AudioClip.concat(tone(1.2f), silence(0.8f)) })
        val p = AudioClip.prepareReference(speechy, sr)
        assertTrue(p.seconds <= 10.01f)
        assertEquals(AudioClip.Verdict.OK, p.verdict)
        assertEquals(AudioClip.Verdict.TOO_SHORT, AudioClip.prepareReference(AudioClip.concat(silence(1f), tone(2f), silence(1f)), sr).verdict)
        val loud = FloatArray(sr * 8) { if ((it / 40) % 2 == 0) 1f else -1f }
        assertEquals(AudioClip.Verdict.TOO_LOUD, AudioClip.prepareReference(loud, sr).verdict)
        assertEquals(AudioClip.Verdict.TOO_QUIET, AudioClip.prepareReference(AudioClip.concat(silence(0.5f, 0.0005f), tone(8f, 0.02f)), sr).verdict)
    }

    @Test fun wavRoundTrip() {
        val x = tone(0.5f)
        val bytes = AudioClip.wavBytes(x, 16000)
        assertEquals(44 + x.size * 2, bytes.size)
        val (y, rate) = AudioClip.readWav(bytes)!!
        assertEquals(16000, rate)
        assertEquals(x.size, y.size)
        for (i in x.indices step 97) assertEquals(x[i], y[i], 1f / 16000)
        assertEquals(null, AudioClip.readWav(ByteArray(10)))
    }

    @Test fun meter() {
        assertEquals(0f, AudioClip.meterLevel(ShortArray(100)), 0f)
        val loud = ShortArray(1000) { if (it % 2 == 0) 16000 else -16000 }
        assertTrue(AudioClip.meterLevel(loud) > 0.8f)
        val soft = ShortArray(1000) { if (it % 2 == 0) 100 else -100 }
        assertTrue(AudioClip.meterLevel(soft) < 0.4f)
    }
}
