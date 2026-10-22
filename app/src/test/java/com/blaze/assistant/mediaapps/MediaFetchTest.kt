package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.Choice
import com.blaze.assistant.mediaapps.store.StoreItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.0 batch downloads from the chat. */
class MediaFetchTest {
    private fun it(t: String, a: String, sec: Int = 210, id: String = t) = StoreItem("youtube", id, t, a, sec, "3:30")

    @Test fun clearMatchIsConfident() {
        val r = MediaPick.rank("Ed Sheeran Perfect", "audio", listOf(it("Ed Sheeran - Perfect (Official Music Video)", "Ed Sheeran"), it("Perfect - Ed Sheeran (Lyrics)", "7clouds"), it("Ed Sheeran Perfect live at Wembley", "Fan")))
        assertEquals("Ed Sheeran - Perfect (Official Music Video)", r[0].first.title)
        assertTrue(MediaPick.confident(r))
    }

    @Test fun vagueQueryAsks() {
        val r = MediaPick.rank("Sinner", "audio", listOf(it("I'm The Sinner", "Artist A"), it("Sinner", "Artist B", 190), it("Sinners (Trailer)", "Warner")))
        assertFalse(MediaPick.confident(r))
    }

    @Test fun coversAndLongMixesLose() {
        val r = MediaPick.rank("Adele Hello", "audio", listOf(it("Adele - Hello (cover)", "Someone"), it("Adele - Hello", "AdeleVEVO", 367)))
        assertEquals("AdeleVEVO", r[0].first.author)
        assertTrue(MediaPick.score("Adele Hello", "audio", it("Adele Hello 1 hour loop", "x", 3600)) < 0.5)
    }

    @Test fun qualityWords() {
        val a = listOf(Choice("a1", "High", "", listOf("1"), "m4a", 0, kbps = 131), Choice("a2", "Smaller", "", listOf("2"), "m4a", 0, kbps = 50), Choice("o", "Opus", "", listOf("3"), "opus", 0, kbps = 161))
        assertEquals("a1", MediaPick.choose("audio", a, "high")!!.id)
        assertEquals("a2", MediaPick.choose("audio", a, "small")!!.id)
        assertEquals("o", MediaPick.choose("audio", a, "best", "opus")!!.id)
        val v = listOf(1080, 720, 480, 360).map { Choice("v$it", "${it}p", "", listOf("$it"), "mp4", 0, height = it) }
        assertEquals("v720", MediaPick.choose("video", v, "")!!.id)
        assertEquals("v480", MediaPick.choose("video", v, "480p")!!.id)
        assertEquals("v1080", MediaPick.choose("video", v, "best")!!.id)
    }
}
