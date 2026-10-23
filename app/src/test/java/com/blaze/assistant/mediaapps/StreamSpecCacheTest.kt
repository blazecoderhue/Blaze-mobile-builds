package com.blaze.assistant.mediaapps

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * 0.45.20 spec test 4 (cache cap) on the real media3 SimpleCache + [CappedEvictor] + [ResumingDataSource]: streams are
 * written past the cap; the size never ends above it, and the oldest entries that weren't played again go first
 * (a stream played again is touched and survives). 5 cap/order variants, strict 5/5. Plus an expired url mid-read
 * on the real CacheDataSource path: renewed at the same byte, bytes identical, nothing reaches the reader.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class StreamSpecCacheTest {
    private val dirs = ArrayList<java.io.File>()
    private val caches = ArrayList<SimpleCache>()
    private val MB = MediaNet.MB

    @Suppress("DEPRECATION")
    private fun cache(cap: Long): Pair<SimpleCache, CappedEvictor> {
        val d = java.nio.file.Files.createTempDirectory("streamspec").toFile(); dirs += d
        val ev = CappedEvictor(cap)
        return SimpleCache(d, ev).also { caches += it } to ev
    }

    @After fun clean() { caches.forEach { runCatching { it.release() } }; dirs.forEach { it.deleteRecursively() } }

    private fun readAll(ds: DataSource, key: String, url: String = "https://cdn.example/a.m4a?sig=1"): ByteArray {
        ds.open(DataSpec.Builder().setUri(Uri.parse(url)).setKey(key).build())
        val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
        try { while (true) { val n = ds.read(buf, 0, buf.size); if (n == C.RESULT_END_OF_INPUT) break; out.write(buf, 0, n) } } finally { ds.close() }
        return out.toByteArray()
    }

    private fun cached(c: SimpleCache, up: DataSource) = CacheDataSource(c, up, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    @Test fun t4_cacheStaysUnderCapOldestUnplayedEvictedFirst_5of5() {
        val lines = ArrayList<String>(); var pass = 0
        // (cap MB, streams, size MB each, which stream is played again before the rest are written)
        val variants = listOf(Triple(12L, 6, 3), Triple(18L, 8, 3), Triple(16L, 6, 4), Triple(24L, 8, 4), Triple(8L, 6, 2))
        for ((run, v) in variants.withIndex()) {
            val (capMb, count, sizeMb) = v
            val (c, ev) = cache(capMb * MB)
            val clock = VClock()
            val replay = 1                                   // stream 1 is played again after stream 2 is written
            for (i in 0 until count) {
                readAll(cached(c, ResumingDataSource(MediaNetCacheTest.FlakyHttp(bytes((sizeMb * MB).toInt(), 10 * run + i), clock), clock = clock)), "stream:s:$i")
                Thread.sleep(15)                             // distinct last-touch times (ms clock)
                if (i == 2) { readAll(cached(c, ResumingDataSource(MediaNetCacheTest.FlakyHttp(bytes((sizeMb * MB).toInt(), 10 * run + replay), clock), clock = clock)), "stream:s:$replay"); Thread.sleep(15) }
            }
            val kept = (0 until count).filter { c.isCached("stream:s:$it", 0, sizeMb * MB) }
            val gone = (0 until count) - kept.toSet()
            val newest = count - 1
            // Every evicted stream is older than every kept one that wasn't played again (replay aside), and the replayed one is kept.
            val orderOk = gone.isNotEmpty() && gone.all { g -> kept.filter { it != replay }.all { k -> g < k } }
            val ok = ev.bytes <= capMb * MB && c.cacheSpace <= capMb * MB && replay in kept && newest in kept && orderOk
            lines += "run ${run + 1}: cap=${capMb}MB wrote ${count}x${sizeMb}MB size=${ev.bytes / MB}MB kept=$kept evicted=$gone ok=$ok"
            if (ok) pass++
        }
        println("Spec 4 cache cap (strict):\n" + lines.joinToString("\n"))
        assertEquals("strict 5/5:\n" + lines.joinToString("\n"), 5, pass)
    }

    /** A media3 source whose original url answers 403 for any open at or past [expireFrom] bytes. */
    class ExpiringHttp(val data: ByteArray, val clock: VClock, val expireFrom: Long) : DataSource {
        var refused = 0
        private val inner = MediaNetCacheTest.FlakyHttp(data, clock)
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            if ("sig=old" in dataSpec.uri.toString() && dataSpec.position >= expireFrom) {
                refused++
                throw androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), dataSpec, ByteArray(0))
            }
            return inner.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)
        override fun getUri(): Uri? = inner.uri
        override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders
        override fun close() = inner.close()
    }

    @Test fun expiredUrlRenewedAtTheSameByteThroughTheRealCache() {
        val data = bytes(6 * 1024 * 1024, 77)
        val clock = VClock()
        val http = ExpiringHttp(data, clock, expireFrom = 3L * 1024 * 1024)
        val asked = ArrayList<Triple<String, String, Long>>()
        // 256 KB then 1 MB ranges, so a request starts past the expiry point.
        val res = ResumingDataSource(http, MediaNet.Chunks(256 * 1024L, 1024 * 1024L), clock = clock,
            renewer = { k, u, t -> asked += Triple(k, u, t); u.replace("sig=old", "sig=new") })
        val (c, _) = cache(64 * MB)
        val got = readAll(cached(c, res), "stream:youtube:abc", "https://rr1.googlevideo.com/videoplayback?itag=140&sig=old")
        assertEquals(sha(data), sha(got))
        assertEquals(1, http.refused); assertEquals(1, res.renewals)
        assertEquals("renewed with the stable cache key", "stream:youtube:abc", asked.single().first)
        assertTrue(c.isCached("stream:youtube:abc", 0, data.size.toLong()))
    }
}
