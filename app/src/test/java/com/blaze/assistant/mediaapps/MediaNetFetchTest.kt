package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.MediaNet.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.45.19 step 3: faster first play, measured on a deterministic simulated network (virtual clock).
 * Time-to-first-play (TTFP) = from the Play tap to the first frame/sample, before (0.45.18 settings) and after.
 */
class MediaNetFetchTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    // ---------------------------------------------------------------- audio: through the real NetReader

    /** 320 kbps song (40 B/ms) on a flaky 800 kbps link (100 B/ms), 300 ms per request. */
    private fun audioTtfp(chunks: MediaNet.Chunks, startBufMs: Long): PlayRun {
        val song = bytes(240_000 * 40)
        val clock = VClock()
        val net = FakeNet(song, clock, bytesPerMs = 100.0, latencyMs = 300)
        return PlayerSim(clock, 40.0, startBufMs, 45_000, song.size.toLong()) { MediaNet.NetReader(net, clock, chunks = chunks) }.run()
    }

    @Test fun audioTimeToFirstPlay() {
        val before = audioTtfp(MediaNet.Chunks.NONE, 1_500)
        val after = audioTtfp(MediaNet.STREAM_CHUNKS, MediaNet.START_BUFFER_MS.toLong())
        println("TTFP audio (320 kbps on 800 kbps, 300 ms RTT): before ${before.firstPlayMs} ms, after ${after.firstPlayMs} ms")
        assertTrue(after.finished && before.finished)
        assertEquals(before.sha, after.sha)
        assertTrue("after ${after.firstPlayMs} < before ${before.firstPlayMs}", after.firstPlayMs < before.firstPlayMs)
        assertTrue(after.firstPlayMs <= 750)
    }

    // ---------------------------------------------------------------- video with its remembered subtitle

    /**
     * One link shared fairly by the flows transferring at that moment (1 ms steps). A flow = latency then bytes.
     * Used for the video case, where the subtitle and the stream's first chunk compete for the link.
     */
    private class Flow(var latency: Double, var bytes: Double, val after: Flow? = null) { var done = -1L; var got = 0.0; var started = after == null }

    private fun videoTtfp(parallel: Boolean, startBufMs: Long, subsWaitMs: Long): Pair<Long, Long> {
        val rate = 375.0                     // 3 Mbps link
        val videoRate = 125.0                // 1 Mbps stream
        val rtt = 300.0
        val subsBytes = 40_960.0             // YouTube timedtext, two requests (track list, then the track)
        val s1 = Flow(rtt, 2_048.0); val s2 = Flow(rtt, subsBytes, after = s1)
        val need = startBufMs * videoRate
        val stream = Flow(rtt, 50_000_000.0, after = if (parallel) null else s2)
        val flows = listOf(s1, s2, stream)
        var t = 0L
        var playAt = -1L
        while (playAt < 0 && t < 60_000) {
            for (f in flows) if (!f.started && f.after != null && f.after.done >= 0) f.started = true
            val moving = flows.filter { it.started && it.done < 0 && it.latency <= 0 }
            val share = if (moving.isEmpty()) 0.0 else rate / moving.size
            for (f in flows) if (f.started && f.done < 0) {
                if (f.latency > 0) f.latency -= 1.0
                else { f.got += share; if (f.got >= f.bytes) f.done = t }
            }
            t++
            val subsReady = s2.done >= 0
            val mayStart = if (parallel) subsReady || t >= subsWaitMs else subsReady
            if (mayStart && stream.got >= need) playAt = t
        }
        return playAt to s2.done
    }

    @Test fun videoTimeToFirstPlay() {
        val (before, subsBefore) = videoTtfp(parallel = false, startBufMs = 1_500, subsWaitMs = 4_000)
        val (after, subsAfter) = videoTtfp(parallel = true, startBufMs = MediaNet.START_BUFFER_MS.toLong(), subsWaitMs = MediaNet.SUBS_WAIT_MS)
        println("TTFP video+subtitle (1 Mbps on 3 Mbps, 300 ms RTT): before $before ms (subtitle at $subsBefore ms), after $after ms (subtitle at $subsAfter ms, added when it lands)")
        assertTrue("after $after < before $before", after < before)
        assertTrue("subtitle no longer gates the start", after < subsAfter || subsAfter <= MediaNet.SUBS_WAIT_MS)
    }

    @Test fun slowSubtitleNoLongerHoldsTheVideo() {
        // A subtitle that never answers: 0.45.18 started after its 4 s timeout; now after SUBS_WAIT_MS.
        assertTrue(MediaNet.SUBS_WAIT_MS <= 1_000 && MediaNet.SUBS_TOTAL_MS == 4_000L)
    }

    // ---------------------------------------------------------------- chunked ranges

    @Test fun chunkedRangesReadEverythingInFewRequests() {
        val data = bytes(9_600_000)
        val clock = VClock()
        val net = FakeNet(data, clock, 100.0)
        val r = MediaNet.NetReader(net, clock, chunks = MediaNet.STREAM_CHUNKS)
        assertEquals(data.size.toLong(), r.open(0, -1))
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32 * 1024)
        while (true) { val n = r.read(buf, 0, buf.size); if (n < 0) break; out.write(buf, 0, n) }
        assertEquals(sha(data), sha(out.toByteArray()))
        assertEquals("256 KB then 4 MB ranges", 4, r.requests)
    }

    @Test fun serverThatIgnoresRangesFallsBackToOneRequest() {
        val data = bytes(2_000_000)
        val clock = VClock()
        val net = FakeNet(data, clock, 100.0, ignoreRange = true)
        val r = MediaNet.NetReader(net, clock, chunks = MediaNet.STREAM_CHUNKS)
        r.open(0, -1)
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32 * 1024)
        while (true) { val n = r.read(buf, 0, buf.size); if (n < 0) break; out.write(buf, 0, n) }
        assertEquals(sha(data), sha(out.toByteArray()))
        assertEquals("one probe + one open-ended request", 2, r.requests)
    }

    @Test fun boundedReadStopsAtItsLength() {
        val data = bytes(1_000_000)
        val clock = VClock()
        val r = MediaNet.NetReader(FakeNet(data, clock, 100.0), clock, chunks = MediaNet.STREAM_CHUNKS)
        assertEquals(300_000L, r.open(500_000, 300_000))
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32 * 1024)
        while (true) { val n = r.read(buf, 0, buf.size); if (n < 0) break; out.write(buf, 0, n) }
        assertEquals(sha(data.copyOfRange(500_000, 800_000)), sha(out.toByteArray()))
    }

    @Test fun productionChunksSurviveDropsMidPlay_5of5() {
        val song = bytes(240_000 * 40)
        var pass = 0
        val lines = ArrayList<String>()
        for (i in 0 until 5) {
            val start = 15_000L + 19_000L * i; val len = 3_000L + 1_000L * i
            val clock = VClock()
            val net = FakeNet(song, clock, 100.0, drops = listOf(start until start + len))
            val run = PlayerSim(clock, 40.0, MediaNet.START_BUFFER_MS.toLong(), 45_000, song.size.toLong()) {
                MediaNet.NetReader(net, clock, chunks = MediaNet.STREAM_CHUNKS) }.run()
            val ok = run.finished && run.sha == sha(song) && run.stallMs == 0L && run.readerErrors == 0 && run.reopens >= 1
            lines += "run ${i + 1}: drop ${start / 1000}s+${len / 1000}s requests=${run.requests} reopens=${run.reopens} stall=${run.stallMs}ms ok=$ok"
            if (ok) pass++
        }
        println("MediaNet drop runs with production chunks (strict):\n" + lines.joinToString("\n"))
        assertEquals(lines.joinToString("\n"), 5, pass)
    }

    // ---------------------------------------------------------------- details fetched ahead

    @Test fun detailsWarmOnlyOnGoodLinks() {
        assertEquals(2, MediaNet.warmCount(Link.WIFI, false, 0))
        assertEquals(1, MediaNet.warmCount(Link.GOOD, false, 0))
        assertEquals(0, MediaNet.warmCount(Link.WEAK, false, 0))
        assertEquals(0, MediaNet.warmCount(Link.WIFI, true, 0))      // Data Saver
        assertEquals(0, MediaNet.warmCount(Link.WIFI, false, 2))     // sources still answering
        var now = 0L
        val c = MediaNet.TtlCache<String>(max = 2, ttlMs = 1_000, now = { now })
        c.put("a", "1"); assertEquals("1", c.get("a"))
        now = 1_001; assertNull(c.get("a"))
        c.put("a", "1"); c.put("b", "2"); c.put("c", "3"); assertNull(c.get("a")); assertEquals(2, c.size())
    }

    @Test fun wiring() {
        val c = src("mediaapps/StreamCache.kt"); val v = src("mediaapps/VideoActivity.kt"); val u = src("mediaapps/MediaUi.kt")
        assertTrue("first-chunk ranges on the network path", "ResumingDataSource(plain.createDataSource(), MediaNet.STREAM_CHUNKS)" in c)
        assertTrue("start buffer", "ReadAheadControl(min, max, MediaNet.START_BUFFER_MS, MediaNet.REBUFFER_MS)" in c)
        assertTrue("first chunk fetched with the subtitle", "StreamCache.warm(this, playUris.first(), it, http)" in v)
        assertTrue("subtitle wait capped", "withTimeoutOrNull(MediaNet.SUBS_WAIT_MS) { fetch.await() }" in v && "attach(late)" in v)
        assertTrue("details warmed and reused", "MediaNet.warmCount(MediaNet.link(ctx), MediaNet.saving(ctx), state.pending)" in u
            && "StreamCache.details.get(detailKey)" in u)
    }
}
