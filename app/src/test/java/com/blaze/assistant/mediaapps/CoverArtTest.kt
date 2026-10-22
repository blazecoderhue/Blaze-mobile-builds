package com.blaze.assistant.mediaapps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverArtTest {
    @Test fun prompts() {
        val p = CoverArt.prompt("my_beat_03.mp3", "Blaze", "audio")
        assertTrue(p.startsWith("Album cover for the song \"my beat 03\"")); assertFalse(p.contains("by Blaze")); assertTrue(p.contains("lo-fi"))
        val v = CoverArt.prompt("Calculus lesson 4 (Official Video)", "Prof", "video")
        assertTrue(v.contains("thumbnail")); assertFalse(v.contains("Official")); assertTrue(v.contains("No text"))
        assertTrue(CoverArt.prompt("Song", "X", "audio", "minimal black and gold").contains("minimal black and gold"))
    }
}
