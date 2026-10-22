package com.blaze.assistant.voice.myvoice

import com.blaze.assistant.voice.myvoice.AudioClip.Verdict
import com.blaze.assistant.voice.myvoice.MyVoiceSetup.Inputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class MyVoiceSetupTest {
    @Test fun downloadedAndGoodRecordingCanTest() {
        val s = MyVoiceSetup.step3(Inputs(packReady = true, recorded = Verdict.OK))
        assertTrue(s.canTest)
        assertTrue(MyVoiceSetup.step2Done(Inputs(packReady = true, recorded = Verdict.OK)))
    }

    @Test fun downloadedButShortRecordingSaysWhatIsMissing() {
        val s = MyVoiceSetup.step3(Inputs(packReady = true, recorded = Verdict.TOO_SHORT))
        assertFalse(s.canTest)
        assertFalse("must not blame the download", s.hint.contains("Download", ignoreCase = true))
        assertTrue(s.hint, s.hint.contains("too short"))
    }

    @Test fun savedRecordingSurvivesLeavingSettings() {
        // A good recording was saved, Settings was closed before "Use this voice": nothing on screen now.
        val i = Inputs(packReady = true, recorded = null, candidateSaved = true)
        assertTrue(MyVoiceSetup.step3(i).canTest)
        assertTrue(MyVoiceSetup.step2Done(i))
    }

    @Test fun existingVoiceCanBeTestedAgain() {
        assertTrue(MyVoiceSetup.step3(Inputs(packReady = true, hasVoice = true)).canTest)
    }

    @Test fun freshBadRecordingWinsOverOldVoice() {
        val s = MyVoiceSetup.step3(Inputs(packReady = true, recorded = Verdict.TOO_QUIET, hasVoice = true, candidateSaved = true))
        assertFalse(s.canTest)
        assertTrue(s.hint.contains("quiet"))
    }

    @Test fun noModelSaysDownload() {
        val s = MyVoiceSetup.step3(Inputs(packReady = false, recorded = Verdict.OK))
        assertFalse(s.canTest)
        assertTrue(s.hint, s.hint.startsWith("Download the voice model first"))
        assertTrue(MyVoiceSetup.step3(Inputs(packReady = false, downloading = true, recorded = Verdict.OK)).hint.startsWith("Waiting"))
        assertEquals("Download the voice model and record your voice first.", MyVoiceSetup.step3(Inputs(packReady = false)).hint)
    }

    @Test fun noRecordingSaysRecord() {
        val s = MyVoiceSetup.step3(Inputs(packReady = true))
        assertFalse(s.canTest)
        assertEquals("Record your voice first (step 2).", s.hint)
        assertFalse(MyVoiceSetup.step3(Inputs(packReady = true, recording = true, hasVoice = true)).canTest)
    }

    // ---- the "too short" check on speech-like audio ----

    private val sr = 24000

    /** Speech-ish audio: ~4.5 syllables a second, a short breath every ~3 s, room noise, quiet lead-in / out. */
    private fun read(sec: Float, level: Float, noise: Float = 0.002f, seed: Int = 1): FloatArray {
        val r = Random(seed)
        val lead = (0.8f * sr).toInt()
        val n = (sec * sr).toInt()
        return FloatArray(lead * 2 + n) { k ->
            val hiss = (r.nextFloat() * 2 - 1) * noise * 1.7f
            val i = k - lead
            if (i < 0 || i >= n) return@FloatArray hiss
            val t = i / sr.toFloat()
            val env = max(0.0, sin(2 * PI * 4.5 * t)).pow(1.5)
            val phrase = if (t % 3.2f < 2.8f) 1.0 else 0.0
            val car = sin(2 * PI * 140 * t) + 0.5 * sin(2 * PI * 280 * t)
            (level * env * phrase * (0.6 + 0.4 * r.nextFloat()) * car / 1.3).toFloat() + hiss
        }
    }

    @Test fun normalReadsOfTenToFifteenSecondsAreAccepted() {
        for (level in listOf(0.07f, 0.1f, 0.3f)) for (sec in listOf(10f, 12f, 15f)) {
            val p = AudioClip.prepareReference(read(sec, level), sr)
            assertEquals("level $level, $sec s, speech ${p.speechSec}", Verdict.OK, p.verdict)
            assertTrue(p.seconds <= AudioClip.MAX_REFERENCE_SEC + 0.01f)
        }
    }

    @Test fun speechTimeTracksTheRead() {
        val s = AudioClip.activeSpeechSeconds(read(12f, 0.1f), sr)
        assertTrue("got $s", s in 10f..13.5f)
    }

    @Test fun reallyShortReadIsStillTooShort() {
        assertEquals(Verdict.TOO_SHORT, AudioClip.prepareReference(read(3f, 0.1f), sr).verdict)
    }

    @Test fun steadyNoiseIsNotSpeech() {
        val r = Random(3)
        val hum = FloatArray(sr * 12) { (0.2 * sin(2 * PI * 100 * it / sr)).toFloat() + (r.nextFloat() - 0.5f) * 0.01f }
        assertEquals(0f, AudioClip.activeSpeechSeconds(hum, sr), 0f)
        assertEquals(Verdict.TOO_NOISY, AudioClip.prepareReference(hum, sr).verdict)
    }
}
