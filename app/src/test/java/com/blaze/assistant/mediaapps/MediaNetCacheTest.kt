package com.blaze.assistant.mediaapps

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 0.45.19 step 1 on the real media3 pieces: SimpleCache + CacheDataSource over [ResumingDataSource] over a fake
 * flaky HTTP source (virtual clock: no real waiting). A drop mid-read is ridden out; a stream read once replays
 * from disk with the network gone; the cap shrinks with the plan; a refusal is not waited out.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class MediaNetCacheTest {
    private val dirs = ArrayList<java.io.File>()
    private val caches = ArrayList<SimpleCache>()

    @Suppress("DEPRECATION")
    private fun cache(cap: Long, ev: CappedEvictor = CappedEvictor(cap)): Pair<SimpleCache, CappedEvictor> {
        val d = java.nio.file.Files.createTempDirectory("medianet").toFile(); dirs += d
        return SimpleCache(d, ev).also { caches += it } to ev
    }

    @After fun clean() { caches.forEach { runCatching { it.release() } }; dirs.forEach { it.deleteRecursively() } }

    /** A media3 HTTP-ish source: Range by DataSpec position/length, Content-Range header, drops on a virtual clock. */
    class FlakyHttp(val data: ByteArray, val clock: VClock, val drops: List<LongRange> = emptyList(), var offline: Boolean = false,
                    val refuse: Boolean = false) : DataSource {
        var opens = 0; var resets = 0
        private var pos = 0L; private var end = 0L; private var spec: DataSpec? = null
        private fun down() = offline || drops.any { clock.now() in it }
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            clock.advance(100.0)
            if (refuse) throw HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), dataSpec, ByteArray(0))
            if (down()) throw HttpDataSource.HttpDataSourceException("no route", dataSpec, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            opens++; spec = dataSpec
            pos = dataSpec.position
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) data.size.toLong() else minOf(data.size.toLong(), pos + dataSpec.length)
            return end - pos
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= end) return C.RESULT_END_OF_INPUT
            val k = minOf(length.toLong(), 8192L, end - pos).toInt()
            clock.advance(k / 50.0)
            if (down()) { resets++; throw HttpDataSource.HttpDataSourceException("reset", spec!!, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_READ) }
            System.arraycopy(data, pos.toInt(), buffer, offset, k); pos += k
            return k
        }
        override fun getUri(): Uri? = spec?.uri
        override fun getResponseHeaders(): Map<String, List<String>> =
            spec?.let { mapOf("Content-Range" to listOf("bytes ${it.position}-${end - 1}/${data.size}")) } ?: emptyMap()
        override fun close() { spec = null }
    }

    private fun readAll(ds: DataSource, key: String): ByteArray {
        ds.open(DataSpec.Builder().setUri(Uri.parse("https://cdn.example/song.mp3?sig=1")).setKey(key).build())
        val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
        try { while (true) { val n = ds.read(buf, 0, buf.size); if (n == C.RESULT_END_OF_INPUT) break; out.write(buf, 0, n) } } finally { ds.close() }
        return out.toByteArray()
    }

    private fun cached(c: SimpleCache, up: DataSource) = CacheDataSource(c, up, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    @Test fun dropMidReadIsRiddenOut() {
        val data = bytes(3 * 1024 * 1024)
        val clock = VClock()
        val http = FlakyHttp(data, clock, drops = listOf(10_000L until 14_000L))
        val res = ResumingDataSource(http, clock = clock)
        val (c, _) = cache(64 * MediaNet.MB)
        assertEquals(sha(data), sha(readAll(cached(c, res), "stream:jamendo:1")))
        assertTrue("re-opened at least once (resets=${http.resets})", http.resets >= 1 && res.reopens >= 1)
    }

    @Test fun replaysFromDiskWithTheNetworkGone() {
        val data = bytes(2 * 1024 * 1024)
        val clock = VClock()
        val http = FlakyHttp(data, clock)
        val (c, _) = cache(64 * MediaNet.MB)
        assertEquals(sha(data), sha(readAll(cached(c, ResumingDataSource(http, clock = clock)), "stream:yt:abc")))
        http.offline = true
        val opensBefore = http.opens
        assertEquals(sha(data), sha(readAll(cached(c, ResumingDataSource(http, clock = clock)), "stream:yt:abc")))
        assertEquals("served from disk, no network", opensBefore, http.opens)
    }

    @Test fun capShrinksWithThePlan() {
        val (c, ev) = cache(64 * MediaNet.MB)
        val clock = VClock()
        for (i in 0 until 4) readAll(cached(c, ResumingDataSource(FlakyHttp(bytes(4 * 1024 * 1024, i), clock), clock = clock)), "stream:k:$i")
        assertTrue(ev.bytes >= 15 * MediaNet.MB)
        ev.cap = 6 * MediaNet.MB           // the month's limits ran low
        readAll(cached(c, ResumingDataSource(FlakyHttp(bytes(1024 * 1024, 9), clock), clock = clock)), "stream:k:9")
        assertTrue("evicted down to the cap (${ev.bytes})", ev.bytes <= 6 * MediaNet.MB)
        assertTrue("the newest stream kept", c.isCached("stream:k:9", 0, 1024 * 1024))
    }

    @Test fun refusalIsNotWaitedOut() {
        val clock = VClock()
        val res = ResumingDataSource(FlakyHttp(bytes(1024), clock, refuse = true), clock = clock)
        try { readAll(res, "stream:x:1"); fail() } catch (e: IOException) { assertTrue(e is HttpDataSource.InvalidResponseCodeException) }
        assertTrue(clock.now() < 1_000)
    }

    @Test fun contentRangeTotal() {
        assertEquals(9_876_543L, ResumingDataSource.totalOf("bytes 0-262143/9876543"))
        assertEquals(-1L, ResumingDataSource.totalOf("bytes 0-10/*"))
        assertEquals(-1L, ResumingDataSource.totalOf(null))
        assertEquals("x", ResumingDataSource.header(mapOf("content-range" to listOf("x")), "Content-Range"))
    }
}
