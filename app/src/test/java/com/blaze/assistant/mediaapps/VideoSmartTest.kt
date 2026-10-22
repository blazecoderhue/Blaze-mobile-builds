package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.34.0 Blaze Video: data cost, episodes, skip intro, up next, smart downloads, recap and translation text. */
class VideoSmartTest {
    private fun v(title: String, key: String = title) = Track(key, "content://$key", title, kind = "video")

    @Test fun costLine() {
        assertEquals(245_000_000L, VideoSmart.bytes(245_000_000, 0, 0))
        assertEquals(75_000_000L, VideoSmart.bytes(0, 1_000_000, 600))
        assertEquals("\u2248 245 MB to watch \u00b7 you're on mobile data", VideoSmart.costLine(245_000_000, true, "video"))
        assertEquals("\u2248 1.2 GB to watch \u00b7 on Wi-Fi", VideoSmart.costLine(1_200_000_000, false, "video"))
        assertEquals("\u2248 4.5 MB to listen \u00b7 on Wi-Fi", VideoSmart.costLine(4_500_000, false, "audio"))
        assertEquals("", VideoSmart.costLine(0, true, "video"))
        assertTrue(VideoSmart.warnFirst(200_000_000, true)); assertFalse(VideoSmart.warnFirst(200_000_000, false))
    }

    @Test fun episodesParse() {
        assertEquals(VideoSmart.Episode("Dark", "dark", 1, 2), VideoSmart.episode("Dark S01E02 - Lies"))
        assertEquals(VideoSmart.Episode("The Office", "the office", 3, 14), VideoSmart.episode("The.Office.3x14.720p"))
        assertEquals(VideoSmart.Episode("Kids Show", "kids show", 2, 5), VideoSmart.episode("Kids Show - Season 2 Episode 5"))
        assertEquals(VideoSmart.Episode("Learn Kotlin", "learn kotlin", 1, 7), VideoSmart.episode("Learn Kotlin | Episode 7: Lambdas"))
        assertEquals(VideoSmart.Episode("Big Buck Bunny", "big buck bunny", 1, 2), VideoSmart.episode("Big Buck Bunny (Part 2)"))
        assertNull(VideoSmart.episode("Sintel"))
        assertNull(VideoSmart.episode("Episode 4"))   // no series name
    }

    @Test fun seriesAndNext() {
        val vids = listOf(v("Dark S01E03"), v("Sintel"), v("Dark S01E01"), v("Dark S02E01"), v("Dark S01E02"), v("Solo Show Ep 1"))
        val s = VideoSmart.series(vids)
        assertEquals(1, s.size)
        assertEquals(listOf("Dark S01E01", "Dark S01E02", "Dark S01E03", "Dark S02E01"), s[0].second.map { it.title })
        assertEquals("Dark S01E03", VideoSmart.next(v("Dark S01E02"), vids)?.title)
        assertEquals("Dark S02E01", VideoSmart.next(v("Dark S01E03"), vids)?.title)
        assertNull(VideoSmart.next(v("Dark S02E01"), vids))
        assertNull(VideoSmart.next(v("Sintel"), vids))
    }

    @Test fun introIsLearnedFromEarlyJumps() {
        assertEquals(95_000L, VideoSmart.learnIntro(0, 10_000, 95_000))
        assertEquals(90_000L, VideoSmart.learnIntro(95_000, 5_000, 85_000))
        assertEquals(95_000L, VideoSmart.learnIntro(95_000, 600_000, 700_000))    // late jump: ignored
        assertEquals(95_000L, VideoSmart.learnIntro(95_000, 10_000, 15_000))      // too small
        assertTrue(VideoSmart.showSkip(10_000, 95_000)); assertFalse(VideoSmart.showSkip(93_000, 95_000)); assertFalse(VideoSmart.showSkip(10_000, 0))
        assertEquals(mapOf("dark" to 95_000L), VideoSmart.introParse(VideoSmart.introJson(mapOf("dark" to 95_000L))))
        assertTrue(VideoSmart.introParse("x").isEmpty())
    }

    @Test fun countdownAndSmartDownloads() {
        assertEquals(5, VideoSmart.countdown(0, 0)); assertEquals(2, VideoSmart.countdown(0, 3_500)); assertEquals(-1, VideoSmart.countdown(0, 5_000))
        val ep = v("Dark S01E01", "a").copy(source = "download"); val film = v("Sintel", "b").copy(source = "download")
        val gal = v("Dark S01E02", "c").copy(source = "gallery")
        val day = 24 * 3_600_000L
        val fin = mapOf("a" to 0L + 1, "b" to 1L, "c" to 1L)
        assertEquals(listOf(ep), VideoSmart.toClear(listOf(ep, film, gal), fin, day + 2))
        assertTrue(VideoSmart.toClear(listOf(ep), fin, day - 10).isEmpty())
        assertTrue(VideoSmart.toClear(listOf(ep), emptyMap(), day * 9).isEmpty())
    }

    @Test fun transcriptStopsAtPositionAndTrims() {
        val cues = listOf(Cue(0, 1000, "Hello"), Cue(1000, 2000, "Hello"), Cue(65_000, 66_000, "Two\nlines"), Cue(200_000, 201_000, "Later"))
        assertEquals("[0:00] Hello\n[1:05] Two lines\n", VideoSmart.transcript(cues, 100_000))
        val long = (0 until 500).map { Cue(it * 1000L, it * 1000L + 900, "line number $it") }
        val t = VideoSmart.transcript(long, 1_000_000, 200)
        assertTrue(t.length <= 200); assertTrue(t.startsWith("[")); assertTrue(t.trimEnd().endsWith("line number 499"))
        assertTrue(VideoSmart.recapPrompt("Film", t).contains("\u201cFilm\u201d"))
    }

    @Test fun translationBatchesAndParsing() {
        val cues = (1..10).map { Cue(it * 1000L, it * 1000L + 500, "line $it") }
        val b = VideoSmart.batches(cues, 40)
        assertTrue(b.size > 1); assertEquals(cues, b.flatten())
        val p = VideoSmart.translatePrompt(cues.take(2), "French")
        assertTrue(p.contains("into French")); assertTrue(p.contains("1|line 1\n2|line 2\n"))
        val out = VideoSmart.applyTranslation(cues.take(3), "1|ligne un\n2) ligne deux\nnoise")
        assertEquals(listOf("ligne un", "ligne deux", "line 3"), out.map { it.text })
        assertEquals(cues.take(3).map { it.startMs }, out.map { it.startMs })
        assertEquals(2.0 / 3, VideoSmart.translatedShare(cues.take(3), out), 1e-9)
    }
}
