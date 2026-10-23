package com.blaze.assistant.mediaapps

import com.blaze.assistant.connect.Entitlements
import com.blaze.assistant.mediaapps.MediaNet.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * 0.45.19 media that survives flaky networks, step 1: stream caching. A deterministic simulated network (virtual
 * clock, [FakeNet]) drops mid-play; the reader rides it out and playback never stalls. Quota and Data Saver gating
 * of prefetch and the cache cap.
 */
class MediaNetTest {
    private val MB = MediaNet.MB
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    // A 4-minute 320 kbps song (40 bytes/ms) over a flaky 800 kbps link with 300 ms per request.
    private val songMs = 240_000L
    private val rate = 40.0
    private val song = bytes((songMs * rate).toInt())

    private fun play(drops: List<LongRange>, graceMs: Long = MediaNet.SHORT_DROP_MS, readAheadMs: Long = 45_000,
                     chunks: MediaNet.Chunks = MediaNet.Chunks.NONE): Pair<PlayRun, FakeNet> {
        val clock = VClock()
        val net = FakeNet(song, clock, bytesPerMs = 100.0, latencyMs = 300, drops = drops)
        val run = PlayerSim(clock, rate, 1_500, readAheadMs, song.size.toLong()) { MediaNet.NetReader(net, clock, graceMs, chunks) }.run()
        return run to net
    }

    // ---------------------------------------------------------------- drops mid-play

    @Test fun shortDropMidPlayNeverStalls_5of5() {
        var pass = 0
        val lines = ArrayList<String>()
        for (i in 0 until 5) {
            val start = 20_000L + 17_000L * i             // a different moment of the song each run
            val len = 3_000L + 1_000L * i                 // 3..7 s without network
            val (run, net) = play(listOf(start until start + len))
            val ok = run.finished && run.sha == sha(song) && run.stallMs == 0L && run.readerErrors == 0 && run.reopens >= 1 && net.resets + net.failedOpens >= 1
            lines += "run ${i + 1}: drop ${start / 1000}s+${len / 1000}s reopens=${run.reopens} resets=${net.resets} failedOpens=${net.failedOpens} stall=${run.stallMs}ms ok=$ok"
            if (ok) pass++
        }
        println("MediaNet drop runs (strict):\n" + lines.joinToString("\n"))
        assertEquals("strict 5/5:\n" + lines.joinToString("\n"), 5, pass)
    }

    @Test fun withoutTheReaderTheSameDropReachesThePlayer() {
        // Grace 0 = the 0.45.18 behaviour: the first reset is a load error.
        val (run, _) = play(listOf(20_000L until 25_000L), graceMs = 0)
        assertTrue("error surfaced", run.readerErrors >= 1)
        assertEquals(sha(song), run.sha)        // the player's own retry still recovers the bytes
    }

    @Test fun longDropPlaysOnFromTheBufferThenResumes() {
        // 20 s without network: longer than the reader's grace, shorter than the 45 s read-ahead.
        val (run, _) = play(listOf(40_000L until 60_000L))
        assertTrue("the reader gave up after its grace and the player retried", run.readerErrors >= 1)
        assertTrue(run.finished); assertEquals(sha(song), run.sha)
        assertEquals("the buffer carried it", 0L, run.stallMs)
    }

    @Test fun dropLongerThanTheBufferStallsOnceAndRecovers() {
        val (run, _) = play(listOf(40_000L until 110_000L), readAheadMs = 30_000)
        assertTrue(run.finished); assertEquals(sha(song), run.sha)
        assertEquals(1, run.stalls)
        assertTrue(run.stallMs in 30_000L..60_000L)
    }

    @Test fun readerReopensAtTheExactByte() {
        val clock = VClock()
        val net = FakeNet(song, clock, 100.0, drops = listOf(1_000L until 1_800L))
        val r = MediaNet.NetReader(net, clock)
        r.open(0, -1)
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (true) { val n = r.read(buf, 0, buf.size); if (n < 0) break; out.write(buf, 0, n) }
        assertEquals(sha(song), sha(out.toByteArray()))
        assertTrue(r.reopens >= 1)
    }

    @Test fun readerGivesUpAfterItsGrace() {
        val clock = VClock()
        val net = FakeNet(song, clock, 100.0, drops = listOf(1_000L until 100_000L))
        val r = MediaNet.NetReader(net, clock, graceMs = 10_000)
        r.open(0, -1)
        val buf = ByteArray(8192)
        try { while (r.read(buf, 0, buf.size) >= 0) {}; fail("should throw") } catch (_: IOException) {}
        assertTrue("gave up near 10 s, at ${clock.now()}", clock.now() in 10_900L..13_500L)
    }

    @Test fun refusalsPassStraightThrough() {
        val clock = VClock()
        var opened = 0
        val src = object : MediaNet.RangeSource {
            override fun open(pos: Long, len: Long): MediaNet.Opened { opened++; if (opened > 1) throw IOException("403"); return MediaNet.Opened(100, 100) }
            override fun read(buf: ByteArray, off: Int, len: Int): Int = throw IOException("reset")
            override fun close() {}
        }
        val r = MediaNet.NetReader(src, clock, fatal = { it.message == "403" })
        r.open(0, -1)
        try { r.read(ByteArray(10), 0, 10); fail() } catch (e: IOException) { assertEquals("403", e.message) }
        assertTrue("no waiting out a refusal", clock.now() <= 300)
    }

    @Test fun retryPolicy() {
        assertEquals(-1L, MediaNet.retryDelayMs(httpStatus = true, outOfRange = false, errorCount = 1))
        assertEquals(-1L, MediaNet.retryDelayMs(false, true, 1))
        assertEquals(500L, MediaNet.retryDelayMs(false, false, 1))
        assertEquals(2_000L, MediaNet.retryDelayMs(false, false, 3))
        assertEquals(5_000L, MediaNet.retryDelayMs(false, false, 50))
        assertTrue(MediaNet.LOAD_RETRIES >= 30)
    }

    // ---------------------------------------------------------------- link, read-ahead

    @Test fun linkClassification() {
        assertEquals(Link.NONE, MediaNet.classify(false, true, true, 50_000))
        assertEquals(Link.WIFI, MediaNet.classify(true, true, false, 0))
        assertEquals(Link.WIFI, MediaNet.classify(true, false, true, 0))         // unmetered (e.g. unlimited hotspot)
        assertEquals(Link.GOOD, MediaNet.classify(true, false, false, 5_000))
        assertEquals(Link.WEAK, MediaNet.classify(true, false, false, 900))
    }

    @Test fun readAheadByLink() {
        assertEquals(120_000, MediaNet.readAheadMs(Link.WIFI, false, 120_000))
        assertEquals(60_000, MediaNet.readAheadMs(Link.GOOD, false, 120_000))
        assertEquals(45_000, MediaNet.readAheadMs(Link.WEAK, false, 120_000))
        assertEquals(30_000, MediaNet.readAheadMs(Link.WIFI, true, 120_000))     // Data Saver
        assertEquals(50_000, MediaNet.readAheadMs(Link.WIFI, false, 50_000))    // RAM ceiling
        assertEquals(45_000, MediaNet.readAheadMs(Link.WEAK, false, 50_000))
        for (l in Link.values()) for (s in listOf(true, false)) assertTrue(MediaNet.readAheadMs(l, s, 50_000) >= 30_000)
    }

    // ---------------------------------------------------------------- quota + Data Saver gating

    private fun snap(song: String, video: String, month: String = "2026-10") = Entitlements.parse(
        """{"plan":"basic","downloads":{"song":$song,"video":$video},"month":"$month"}""")!!

    @Test fun prefetchGating() {
        assertNull(MediaNet.noPrefetch(Link.WIFI, false, 10, 3))
        assertNull(MediaNet.noPrefetch(Link.GOOD, false, null, 999))            // server didn't say: allowed
        assertNull(MediaNet.noPrefetch(Link.GOOD, false, Int.MAX_VALUE, 10_000)) // uncapped plan
        assertEquals("data saver", MediaNet.noPrefetch(Link.WIFI, true, 10, 0))
        assertEquals("data saver", MediaNet.noPrefetch(Link.GOOD, true, Int.MAX_VALUE, 0))
        assertEquals("weak signal", MediaNet.noPrefetch(Link.WEAK, false, 10, 0))
        assertEquals("offline", MediaNet.noPrefetch(Link.NONE, false, 10, 0))
        assertEquals("monthly limit", MediaNet.noPrefetch(Link.WIFI, false, 3, 3))
        assertEquals("monthly limit", MediaNet.noPrefetch(Link.WIFI, false, 0, 0))
    }

    @Test fun quotaFromEntitlements() {
        val s = snap("""{"limit":100,"used":97,"remaining":3}""", """{"limit":10,"used":10,"remaining":0}""")
        assertEquals(3, MediaNet.left(s, "song")); assertEquals(0, MediaNet.left(s, "video"))
        val u = snap("""{"limit":-1,"unlimited":true}""", """{"limit":-1,"unlimited":true}""")
        assertEquals(Int.MAX_VALUE, MediaNet.left(u, "song"))
        assertNull(MediaNet.left(null, "song"))
        // 3 songs left: the 4th prefetch this month is refused.
        var ledger = emptyMap<String, Int>()
        repeat(3) { assertNull(MediaNet.noPrefetch(Link.WIFI, false, MediaNet.left(s, "song"), MediaNet.count(ledger, "2026-10", "song"))); ledger = MediaNet.bump(ledger, "2026-10", "song") }
        assertEquals("monthly limit", MediaNet.noPrefetch(Link.WIFI, false, MediaNet.left(s, "song"), MediaNet.count(ledger, "2026-10", "song")))
        // A new month starts the count again and drops the old month.
        ledger = MediaNet.bump(ledger, "2026-11", "song")
        assertEquals(1, MediaNet.count(ledger, "2026-11", "song")); assertEquals(0, MediaNet.count(ledger, "2026-10", "song"))
        assertEquals("2026-10", MediaNet.month(s, 0)); assertEquals("1970-01", MediaNet.month(null, 0))
    }

    @Test fun cacheCapFollowsTheMonthlyLimits() {
        val dev = 256 * MB
        assertEquals(dev, MediaNet.cacheCap(dev, null as Entitlements.Snapshot?))                     // signed out
        assertEquals(dev, MediaNet.cacheCap(dev, snap("""{"limit":-1,"unlimited":true}""", """{"limit":-1,"unlimited":true}""")))
        // 3 songs + 1 video left: 3 x 10 MB + 80 MB.
        assertEquals(110 * MB, MediaNet.cacheCap(dev, snap("""{"limit":100,"used":97,"remaining":3}""", """{"limit":10,"used":9,"remaining":1}""")))
        // Nothing left: the floor, so read-ahead and resume still work.
        assertEquals(MediaNet.CACHE_FLOOR, MediaNet.cacheCap(dev, snap("""{"limit":100,"used":100,"remaining":0}""", """{"limit":10,"used":10,"remaining":0}""")))
        // Plenty left: the phone's own budget.
        assertEquals(dev, MediaNet.cacheCap(dev, snap("""{"limit":1000,"used":0,"remaining":1000}""", """{"limit":100,"used":0,"remaining":100}""")))
        assertEquals(64 * MB, MediaNet.cacheCap(64 * MB, 0, Int.MAX_VALUE))      // videos uncapped: device budget
    }

    @Test fun prefetchSizes() {
        assertTrue(MediaNet.prefetchBytes(Link.WIFI, "song") > MediaNet.prefetchBytes(Link.GOOD, "song"))
        assertTrue(MediaNet.prefetchBytes(Link.GOOD, "song") >= 1_000_000)
        assertTrue(MediaNet.prefetchBytes(Link.WIFI, "video") > MediaNet.prefetchBytes(Link.WIFI, "song"))
    }

    @Test fun wiring() {
        val c = src("mediaapps/StreamCache.kt"); val m = src("mediaapps/MusicService.kt"); val v = src("mediaapps/VideoActivity.kt")
        val n = src("mediaapps/MediaNet.kt")
        assertTrue("cache reads go through the resuming source", "ResumingDataSource(plain.createDataSource())" in c)
        assertTrue("cache cap follows the plan", "CappedEvictor(MediaNet.cacheCap(" in c && "fun applyQuota(" in c)
        assertTrue("read-ahead by link", "ReadAheadControl(" in c && "MediaNet.readAheadMs(MediaNet.link(app), MediaNet.saving(app), max)" in c)
        assertTrue("prefetch gated", "MediaNet.takePrefetch(app, kind)" in c && "if (hasStart(app, key)) return@execute" in c)
        assertTrue("gating reads the plan and data saver", "Entitlements.cached(ctx)" in n && "noPrefetch(link, saving(ctx)" in n)
        assertTrue("system Data Saver counts too", "RESTRICT_BACKGROUND_STATUS_ENABLED" in n)
        assertTrue("music player drop policy", "setLoadErrorHandlingPolicy(StreamCache.dropPolicy())" in m)
        assertEquals("video players drop policy", 3, Regex("setLoadErrorHandlingPolicy\\(StreamCache\\.dropPolicy\\(\\)\\)").findAll(v).count())
    }
}
