package com.blaze.assistant.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AwarenessTest {
    private val t0 = 1_800_000_000_000L

    @Before fun setUp() = Awareness.reset()
    @After fun tearDown() = Awareness.reset()

    @Test fun clockAndAgo() {
        assertEquals("1:23", Awareness.clock(83_000))
        assertEquals("1:02:03", Awareness.clock(3_723_000))
        assertEquals("0:00", Awareness.clock(-5))
        assertEquals("just now", Awareness.ago(t0, t0 - 10_000))
        assertEquals("4 min ago", Awareness.ago(t0, t0 - 4 * 60_000))
        assertEquals("2 h ago", Awareness.ago(t0, t0 - 2 * 3600_000))
        assertEquals("yesterday", Awareness.ago(t0, t0 - 30 * 3600_000))
        assertEquals("3 days ago", Awareness.ago(t0, t0 - 3 * 86_400_000L))
    }

    @Test fun playingPositionMovesOnAndStopsAtTheEnd() {
        val n = Awareness.Now("music", "Playing", "Song", "Blaze Music", "by X", 60_000, 200_000, true, t0)
        assertEquals(90_000, Awareness.position(n, t0 + 30_000))
        assertEquals(200_000, Awareness.position(n, t0 + 10 * 60_000))
        val paused = n.copy(playing = false)
        assertEquals(60_000, Awareness.position(paused, t0 + 30_000))
        assertEquals(-1, Awareness.position(n.copy(posMs = -1), t0))
    }

    @Test fun lineReadsNaturally() {
        val song = Awareness.Now("music", "Playing", "Blinding Lights", "Blaze Music", "by The Weeknd", 72_000, 200_000, true, t0)
        assertEquals("Playing \"Blinding Lights\" by The Weeknd in Blaze Music, 1:12 of 3:20", Awareness.line(song, t0))
        val film = Awareness.Now("video", "Paused", "Dune", "Blaze Video", "", 2_462_000, 9_312_000, false, t0)
        assertEquals("Paused \"Dune\" in Blaze Video, 41:02 of 2:35:12 (5 min ago)", Awareness.line(film, t0 + 5 * 60_000))
        val screen = Awareness.Now("screen", "On screen", "Chat", "Blaze", at = t0)
        assertEquals("On screen \"Chat\" in Blaze", Awareness.line(screen, t0 + 5 * 60_000))
    }

    @Test fun promptPutsPlayingMediaFirstAndRecentAfter() {
        val now = t0 + 60_000
        val nows = listOf(
            Awareness.Now("screen", "On screen", "Chat", "Blaze", at = t0 + 50_000),
            Awareness.Now("media:spotify", "Playing", "Song", "Spotify", "by X", 10_000, 180_000, true, t0),
        )
        val ev = listOf(Awareness.Event("watched", "watched \"Dune\" in Blaze Video, stopped at 41:02 of 2:35:12", t0 - 20 * 60_000))
        val s = Awareness.format(nows, ev, now)
        assertTrue(s.startsWith("\nWHAT'S GOING ON"))
        assertTrue(s.indexOf("Spotify") < s.indexOf("Chat"))
        assertTrue(s.contains("Recently: watched \"Dune\" in Blaze Video, stopped at 41:02 of 2:35:12 (21 min ago)"))
        assertEquals("", Awareness.format(emptyList(), emptyList(), now))
        // Events older than three days are dropped.
        assertEquals("", Awareness.format(emptyList(), listOf(Awareness.Event("x", "old", now - 4 * 86_400_000L)), now))
    }

    @Test fun eventsDedupeAndSurviveARestart() {
        val dir = createTempDir()
        Awareness.init(dir)
        Awareness.did("listened", "played \"A\" in Blaze Music", t0)
        Awareness.did("watched", "watched \"Dune\"", t0 + 1000)
        Awareness.did("listened", "played \"A\" in Blaze Music", t0 + 2000)   // same thing within 10 min: moves up, not twice
        assertEquals(listOf("played \"A\" in Blaze Music", "watched \"Dune\""), Awareness.recent().map { it.text })
        Awareness.reset()
        assertTrue(Awareness.recent().isEmpty())
        Awareness.init(dir)
        // Loaded entries are judged against the real clock; these test times are in the future, so they're kept.
        assertEquals(2, Awareness.recent().size)
        dir.deleteRecursively()
    }

    @Test fun loadIgnoresJunkAndOldEntries() {
        assertTrue(Awareness.load("not json", t0).isEmpty())
        assertTrue(Awareness.load(null, t0).isEmpty())
        val json = Awareness.toJson(listOf(Awareness.Event("a", "fresh", t0 - 1000), Awareness.Event("b", "stale", t0 - 5 * 86_400_000L), Awareness.Event("c", "", t0)))
        assertEquals(listOf("fresh"), Awareness.load(json, t0).map { it.text })
    }

    @Test fun readersAddLiveItemsAndNeverBreakThePrompt() {
        Awareness.set(Awareness.Now("music", "Playing", "Song", "Blaze Music", posMs = 0, durMs = 1000, playing = true))
        Awareness.readers += { listOf(Awareness.Now("media:x", "Playing", "song", "Other")) }   // same title: dropped
        Awareness.readers += { throw IllegalStateException("boom") }
        Awareness.readers += { listOf(Awareness.Now("blazeos", "Open", "Snake", "BlazeOS")) }
        val titles = Awareness.nows().map { it.title }
        assertEquals(listOf("Song", "Snake"), titles)
        assertFalse(Awareness.promptBlock().isBlank())
        Awareness.clear("music")
        assertEquals(listOf("song", "Snake"), Awareness.nows().map { it.title })
    }

    @Test fun reportListsFeatures() {
        val r = Awareness.report(listOf(Triple("Learn", "learn", "flashcards")))
        assertTrue(r.contains("## Live now") && r.contains("Blaze Music") && r.contains("- Learn (/learn): flashcards"))
        assertEquals(Awareness.TOOL, Awareness.tool().getJSONObject("function").getString("name"))
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(): File = kotlin.io.createTempDir("awareness")
}
