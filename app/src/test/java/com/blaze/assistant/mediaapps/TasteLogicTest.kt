package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** v0.30.5: Store ideas and the taste line learned from plays and downloads. */
class TasteLogicTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private fun e(t: String, a: String, w: Int = 1, at: Long = now, k: String = "audio") = TasteEvent(k, t, a, w, at)

    @Test fun noHistoryGivesStarters() {
        val a = TasteLogic.ideas(emptyList(), "audio", now)
        assertEquals(TasteLogic.starterAudio.take(12), a)
        assertEquals(TasteLogic.starterVideo.first(), TasteLogic.ideas(emptyList(), "video", now).first())
    }

    @Test fun favouriteArtistsComeFirst() {
        val ev = listOf(e("I'm The Sinner Official Lyric Video", "Jared Benjamin"), e("Grace (Lyric Video)", "Jared Benjamin - Topic"),
            e("Something", "Other Artist"))
        val ideas = TasteLogic.ideas(ev, "audio", now)
        assertEquals("Jared Benjamin mix", ideas.first())
        assertTrue("Other Artist mix" in ideas)
        assertTrue(ideas.size <= 12)
    }

    @Test fun downloadsWeighMoreAndOldPlaysFade() {
        val ev = listOf(e("a", "Old Fav", 1, now - 200 * day), e("b", "Old Fav", 1, now - 200 * day), e("c", "New One", 3))
        assertEquals("New One", TasteLogic.topAuthors(ev, "audio", now).first())
    }

    @Test fun stylesNeedTwoItemsAndSkipNoiseAndNames() {
        val ev = listOf(e("Amapiano Mix 2026 Official Video", "DJ Kay"), e("Best Amapiano Live", "Someone"), e("Gospel praise", "Choir"))
        val st = TasteLogic.topStyles(ev, "audio", now)
        assertEquals(listOf("amapiano"), st)
        assertFalse("official" in TasteLogic.words("Official Music Video HD"))
    }

    @Test fun kindsStaySeparate() {
        val ev = listOf(e("Match highlights", "Sky Sports", k = "video"))
        assertTrue(TasteLogic.topAuthors(ev, "audio", now).isEmpty())
        assertEquals("Sky Sports", TasteLogic.ideas(ev, "video", now).first())
    }

    @Test fun summaryNeedsEnoughAndJsonRoundTrips() {
        assertEquals("", TasteLogic.summary(listOf(e("x", "A")), now))
        val ev = (1..6).map { e("Song $it", "Artist A") }
        assertTrue(TasteLogic.summary(ev, now).contains("Artist A"))
        assertEquals(ev, TasteLogic.fromJson(TasteLogic.toJson(ev)))
        assertEquals(TasteLogic.MAX_EVENTS, (1..500).fold(emptyList<TasteEvent>()) { l, i -> TasteLogic.push(l, e("t$i", "a")) }.size)
    }
}
