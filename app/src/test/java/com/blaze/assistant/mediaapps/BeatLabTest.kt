package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.BeatLab.Dsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BeatLabTest {
    private fun tone(hz: Double, s: Double, rate: Int = 16000) = FloatArray((s * rate).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    @Test fun pitchAndNotes() {
        val f = Dsp.yin(tone(220.0, 0.05), 16000); assertEquals(220.0, f, 3.0)
        assertEquals(0.0, Dsp.yin(FloatArray(800), 16000), 0.0)
        // C4 E4 G4 with gaps
        val gap = FloatArray(1600)
        val hum = tone(261.63, 0.4) + gap + tone(329.63, 0.4) + gap + tone(392.0, 0.4)
        val n = Dsp.notes(hum, 16000)
        assertEquals(listOf(60, 64, 67), n.map { it.midi })
    }

    @Test fun keyChordsWav() {
        val mel = listOf(60, 62, 64, 65, 67, 69, 71, 72).mapIndexed { i, m -> Dsp.Note(m, i * 0.5, 0.5) }
        assertEquals(0, Dsp.key(mel))
        assertEquals(62, Dsp.snap(61, 0))   // C# snaps to a scale tone
        val ch = Dsp.chords(listOf(Dsp.Note(65, 0.0, 1.0), Dsp.Note(69, 1.0, 1.0)), 0, 2.0, 2)
        assertEquals("F", Dsp.chordName(0, ch[0]))
        val pcm = Dsp.render(mel, 0, listOf(0, 3), 120, "pop", 1)
        assertTrue(pcm.size > 22050 * 4)
        val w = Dsp.wav(ShortArray(10), 22050); assertEquals(64, w.size); assertEquals("WAVE", String(w, 8, 4))
    }
}
