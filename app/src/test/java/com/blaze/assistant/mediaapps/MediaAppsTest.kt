package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.1 Blaze Music / Video: queue, playlists, downloads, quality picks and YouTube parsing. */
class MediaLogicTest {
    @Test fun repeatCycles() {
        assertEquals(Repeat.ALL, Repeat.OFF.next())
        assertEquals(Repeat.ONE, Repeat.ALL.next())
        assertEquals(Repeat.OFF, Repeat.ONE.next())
    }

    @Test fun shuffleStartsWithTappedSongAndKeepsEveryone() {
        val o = QueueLogic.shuffleOrder(10, 4, 42)
        assertEquals(4, o.first())
        assertEquals((0 until 10).toSet(), o.toSet())
        assertEquals(10, o.size)
        assertEquals(o, QueueLogic.shuffleOrder(10, 4, 42))
        assertTrue(QueueLogic.shuffleOrder(0, 0, 1).isEmpty())
        assertEquals(listOf(0), QueueLogic.shuffleOrder(1, 7, 1))
    }

    @Test fun nextAndPrevFollowRepeat() {
        val none = emptyList<Int>()
        assertEquals(1, QueueLogic.nextIndex(0, 3, Repeat.OFF, none))
        assertEquals(-1, QueueLogic.nextIndex(2, 3, Repeat.OFF, none))
        assertEquals(0, QueueLogic.nextIndex(2, 3, Repeat.ALL, none))
        assertEquals(2, QueueLogic.nextIndex(2, 3, Repeat.ONE, none))
        assertEquals(-1, QueueLogic.prevIndex(0, 3, Repeat.OFF, none))
        assertEquals(2, QueueLogic.prevIndex(0, 3, Repeat.ALL, none))
        val order = listOf(2, 0, 1)
        assertEquals(0, QueueLogic.nextIndex(2, 3, Repeat.OFF, order))
        assertEquals(-1, QueueLogic.nextIndex(1, 3, Repeat.OFF, order))
        assertEquals(2, QueueLogic.nextIndex(1, 3, Repeat.ALL, order))
        assertEquals(-1, QueueLogic.nextIndex(5, 3, Repeat.ALL, order))
    }

    @Test fun upNextWrapsOnlyWithRepeatAll() {
        assertEquals(listOf(3, 4), QueueLogic.upNext(2, 5, Repeat.OFF, emptyList()))
        assertEquals(listOf(3, 4, 0, 1), QueueLogic.upNext(2, 5, Repeat.ALL, emptyList()))
        assertEquals(listOf(1), QueueLogic.upNext(0, 3, Repeat.OFF, listOf(2, 0, 1)))
        assertEquals(listOf(0, 1), QueueLogic.playOrder(2, false, listOf(1, 0)))
        assertEquals(listOf(1, 0), QueueLogic.playOrder(2, true, listOf(1, 0)))
    }

    @Test fun moveReorders() {
        assertEquals(listOf("b", "c", "a"), QueueLogic.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), QueueLogic.move(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b"), QueueLogic.move(listOf("a", "b"), 0, 5))
    }

    @Test fun playlistOps() {
        var l = PlaylistOps.create(emptyList(), "  Road   trip ", "p1")
        assertEquals("Road trip", l[0].name)
        l = PlaylistOps.create(l, "road trip", "p2")
        assertEquals("road trip 2", l[1].name)
        l = PlaylistOps.create(l, "", "p3")
        assertEquals("New playlist", l[2].name)
        l = PlaylistOps.rename(l, "p3", "Gym")
        assertEquals("Gym", l[2].name)
        assertEquals(l, PlaylistOps.rename(l, "p3", "   "))
        l = PlaylistOps.add(l, "p1", listOf("a", "b", "a", "c"))
        l = PlaylistOps.add(l, "p1", listOf("b", "d"))
        assertEquals(listOf("a", "b", "c", "d"), l[0].keys)
        l = PlaylistOps.move(l, "p1", 3, 0)
        assertEquals(listOf("d", "a", "b", "c"), l[0].keys)
        l = PlaylistOps.removeAt(l, "p1", 1)
        assertEquals(listOf("d", "b", "c"), l[0].keys)
        l = PlaylistOps.removeAt(l, "p1", 9)
        assertEquals(3, l[0].keys.size)
        l = PlaylistOps.forget(l, "b")
        assertEquals(listOf("d", "c"), l[0].keys)
        l = PlaylistOps.delete(l, "p2")
        assertEquals(listOf("p1", "p3"), l.map { it.id })
    }

    @Test fun recentsAndResume() {
        var r = Recents.push(emptyList(), "a")
        r = Recents.push(r, "b"); r = Recents.push(r, "a")
        assertEquals(listOf("a", "b"), r)
        assertEquals(3, (1..10).fold(emptyList<String>()) { acc, i -> Recents.push(acc, "k$i", 3) }.size)
        assertEquals(0, ResumeLogic.resumeAt(3_000, 100_000))
        assertEquals(50_000, ResumeLogic.resumeAt(50_000, 100_000))
        assertEquals(0, ResumeLogic.resumeAt(95_000, 100_000))
        assertFalse(ResumeLogic.keep(99_000, 100_000))
    }

    @Test fun timeAndSpeeds() {
        assertEquals("1:02:05", TimeText.clock(3_725_000))
        assertEquals("1:05", TimeText.clock(65_000))
        assertEquals(3725, TimeText.parseClock("1:02:05"))
        assertEquals(-1, TimeText.parseClock("LIVE"))
        assertEquals(-1, TimeText.parseClock(""))
        assertEquals("1.5×", Speeds.label(1.5f))
        assertEquals("1×", Speeds.label(1f))
        assertEquals(1.25f, Speeds.next(1f))
        assertEquals(0.5f, Speeds.next(2f))
        assertEquals(0, SleepTimer.left(0, 5))
        assertEquals(10, SleepTimer.left(15, 5))
    }

    @Test fun libraryGroupsAndSearch() {
        val t = listOf(Track("1", "u1", "Blue Song", "Ann"), Track("2", "u2", "Red", "bob", album = "Colours"), Track("3", "u3", "Green", ""))
        assertEquals(listOf("Ann", "bob", "Unknown artist"), LibraryGroups.artists(t).map { it.first })
        assertEquals(listOf("Ann", "Colours", "Singles"), LibraryGroups.albums(t).map { it.first })
        assertEquals(listOf("1"), LibraryGroups.search(t, "blue ann").map { it.key })
        assertEquals(3, LibraryGroups.search(t, "  ").size)
    }
}

class DownloadLogicTest {
    private fun job(vararg parts: DlPart) = DlJob("j", "abcdefghijk", "video", "T", "C", 60, "", "720p", "v136+140", "mp4", parts.toList(), createdAt = 1)

    @Test fun backoffGrowsAndCaps() {
        assertEquals(2000, DlLogic.backoffMs(0))
        assertEquals(4000, DlLogic.backoffMs(1))
        assertEquals(16000, DlLogic.backoffMs(3))
        assertEquals(300_000, DlLogic.backoffMs(30))
    }

    @Test fun transientErrorsRetryThenFail() {
        var j = job(DlPart("136", "u", 100))
        j = DlLogic.onError(j, "timeout", true, 1000)
        assertEquals(DlStatus.QUEUED, j.status); assertEquals(1, j.attempts); assertEquals(3000, j.nextTryAt)
        repeat(DlLogic.MAX_ATTEMPTS) { j = DlLogic.onError(j, "timeout", true, 1000) }
        assertEquals(DlStatus.FAILED, j.status)
        assertEquals(DlStatus.FAILED, DlLogic.onError(job(), "no space", false, 0).status)
    }

    @Test fun progressResetsAttemptsAndCounts() {
        var j = DlLogic.onError(job(DlPart("136", "u", 100), DlPart("140", "u", 50)), "drop", true, 0)
        j = DlLogic.onProgress(j, 0, 100)
        j = DlLogic.onProgress(j, 1, 25)
        assertEquals(0, j.attempts)
        assertEquals(DlStatus.RUNNING, j.status)
        assertEquals(125, j.done); assertEquals(150, j.total)
        assertEquals(125f / 150f, j.progress, 0.0001f)
        j = DlLogic.onProgress(j, 1, 10, 80)
        assertEquals(180, j.total)
    }

    @Test fun rangesAndStatusCodes() {
        assertEquals("bytes=0-${DlLogic.CHUNK - 1}", DlLogic.range(0, 100_000_000))
        assertEquals("bytes=90-99", DlLogic.range(90, 100))
        assertEquals("bytes=5-14", DlLogic.range(5, 0, 10))
        assertEquals(5000, DlLogic.totalFromContentRange("bytes 100-199/5000"))
        assertEquals(-1, DlLogic.totalFromContentRange("bytes 100-199/*"))
        assertEquals(DlLogic.Resume.APPEND, DlLogic.onStatus(206))
        assertEquals(DlLogic.Resume.RESTART, DlLogic.onStatus(200))
        assertEquals(DlLogic.Resume.COMPLETE, DlLogic.onStatus(416))
        assertEquals(DlLogic.Resume.RETRY, DlLogic.onStatus(403))
        assertEquals(DlLogic.Resume.RETRY, DlLogic.onStatus(503))
        assertEquals(DlLogic.Resume.FAIL, DlLogic.onStatus(404))
    }

    @Test fun expiryAndQueueOrder() {
        assertTrue(DlLogic.urlExpired(1000, 900_000))
        assertFalse(DlLogic.urlExpired(10_000, 900_000))
        assertFalse(DlLogic.urlExpired(0, 900_000))
        val a = job().copy(id = "a", createdAt = 5)
        val b = job().copy(id = "b", createdAt = 3, nextTryAt = 100)
        val c = job().copy(id = "c", createdAt = 1, status = DlStatus.PAUSED)
        assertEquals("a", DlLogic.next(listOf(a, b, c), 50)?.id)
        assertEquals("b", DlLogic.next(listOf(a, b, c), 100)?.id)
        assertEquals(100, DlLogic.nextWake(listOf(a, b, c), 50))
        assertNull(DlLogic.next(listOf(c), 0))
    }

    @Test fun pauseResumeSpaceAndNames() {
        val j = job(DlPart("1", "u", 100, 40))
        assertEquals(DlStatus.PAUSED, DlLogic.pause(j).status)
        assertEquals(DlStatus.QUEUED, DlLogic.resume(DlLogic.pause(j)).status)
        assertEquals(DlStatus.DONE, DlLogic.resume(j.copy(status = DlStatus.DONE)).status)
        assertEquals(60 + 100 + DlLogic.SPACE_MARGIN, DlLogic.spaceNeeded(j))
        assertFalse(DlLogic.hasSpace(1000, j))
        assertEquals("AC-DC- Back in Black.m4a", DlLogic.fileName("AC/DC: Back in Black", "m4a"))
        assertEquals("Blaze.mp4", DlLogic.fileName("...", "mp4"))
    }

    @Test fun expiryFromUrl() {
        assertEquals(1760000000L, DlLogic.expiryOf("https://rr1.googlevideo.com/videoplayback?expire=1760000000&itag=18"))
        assertEquals(0L, DlLogic.expiryOf("https://archive.org/download/x/y.mp3"))
    }

    @Test fun oldJsonWithItagStillReads() {
        val j = DlLogic.fromJson(org.json.JSONObject("""{"id":"a","st":"PAUSED","parts":[{"itag":140,"url":"u","total":10,"done":5}]}"""))
        assertEquals("140", j.parts[0].key); assertEquals("youtube", j.source); assertEquals(DlStatus.PAUSED, j.status)
    }

    @Test fun jsonRoundTrip() {
        val j = job(DlPart("136", "https://x?expire=1", 100, 40, "mp4"), DlPart("140", "https://y", 50)).copy(status = DlStatus.PAUSED, error = "e", outUri = "content://1", source = "archive-audio", licence = "CC BY 4.0", fileTitle = "Track 1")
        val back = DlLogic.listFromJson(DlLogic.listToJson(listOf(j)))
        assertEquals(listOf(j), back)
        assertTrue(DlLogic.listFromJson("not json").isEmpty())
    }
}

class ExtrasTest {
    @Test fun artColours() {
        val red = IntArray(64) { 0xFFE02020.toInt() }
        val c = ArtColors.dominant(red)
        assertTrue(((c shr 16) and 0xff) > 200 && (c and 0xff) < 60)
        // Colourful pixels outweigh grey ones.
        val mixed = IntArray(100) { if (it < 30) 0xFF2050F0.toInt() else 0xFF808080.toInt() }
        val m = ArtColors.dominant(mixed)
        assertTrue((m and 0xff) > ((m shr 16) and 0xff))
        assertEquals(0x4C7DFF, ArtColors.dominant(IntArray(4)))   // fully transparent
        assertEquals(0x402010, ArtColors.shade(0x804020, 0.5f))
    }

    @Test fun lrcParsing() {
        val l = Lrc.parse("[ar:Someone]\n[00:12.50]Hello\n[00:05]Intro\n[01:02.123][01:30.00]Chorus\nplain line")
        assertEquals(listOf(5000L, 12500L, 62123L, 90000L), l.map { it.ms })
        assertEquals(listOf("Intro", "Hello", "Chorus", "Chorus"), l.map { it.text })
        assertEquals(-1, Lrc.current(l, 1000))
        assertEquals(1, Lrc.current(l, 20_000))
        assertEquals(3, Lrc.current(l, 999_999))
        assertTrue(Lrc.parse("no tags here").isEmpty())
    }

    @Test fun lyricsTitles() {
        assertEquals("Song", Lrc.cleanTitle("Song (Official Video) [HD]", "Ann"))
        assertEquals("Song", Lrc.cleanTitle("Ann - Song (Lyrics)", "Ann"))
        assertEquals("Song (Live at X)", Lrc.cleanTitle("Song (Live at X)", ""))
        val r = Lyrics.parse("""{"plainLyrics":"a\nb","syncedLyrics":"[00:01.00]a\n[00:02.00]b","instrumental":false}""")
        assertEquals(2, r.synced.size); assertFalse(r.empty)
        assertTrue(Lyrics.parse("""{"plainLyrics":null,"syncedLyrics":null}""").empty)
    }
}
