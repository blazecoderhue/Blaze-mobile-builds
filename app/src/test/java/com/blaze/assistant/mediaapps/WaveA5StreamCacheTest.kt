package com.blaze.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave A item 16: stream disk cache sized to the phone, buffer ahead, next-song prefetch, offline replay. */
class WaveA5StreamCacheTest {
    private val MB = StreamCache.MB
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    @Test fun budgetFitsThePhone() {
        assertEquals(128 * MB, StreamCache.budget(3_000, 64_000 * MB))       // 3 GB phone: small
        assertEquals(256 * MB, StreamCache.budget(8_000, 64_000 * MB))
        assertEquals(100 * MB, StreamCache.budget(8_000, 1_000 * MB))        // never over 10 % of free space
        assertEquals(32 * MB, StreamCache.budget(8_000, 50 * MB))            // floor
        assertTrue(StreamCache.bufferMs(3_000).second <= 50_000)             // low RAM: shorter buffer
        assertTrue(StreamCache.bufferMs(8_000).second >= 120_000)
    }

    @Test fun onlyStreamsAreCachedUnderStableKeys() {
        assertTrue(StreamCache.cacheableUrl(("https://rr3.googlevideo.com/videoplayback?expire=1&sig=x")))
        assertFalse(StreamCache.cacheableUrl(("content://media/external/video/1")))
        assertFalse(StreamCache.cacheableUrl(("file:///sdcard/a.mp4")))
        assertEquals("stream:youtube:abc", StreamCache.key("stream:youtube:abc"))
        assertEquals("stream:youtube:abc#1", StreamCache.key("stream:youtube:abc", 1))
        assertEquals(null, StreamCache.key(""))
    }

    @Test fun wiring() {
        val v = src("mediaapps/VideoActivity.kt"); val m = src("mediaapps/MusicService.kt")
        for (s in listOf(v, m)) {
            assertTrue("players read through the cache", "StreamCache.factory(this, http)" in s)
            assertTrue("buffer ahead", "StreamCache.loadControl(this)" in s)
        }
        // 0.45.20: keyed by track, plus the height after a rendition step-down (two renditions never share a key).
        assertTrue("video parts keyed by track", "setCustomCacheKey(if (stream) StreamCache.key(ck) else null)" in v && "val ck = if (heightCap > 0) \"\${t.key}~\${heightCap}p\" else t.key" in v)
        assertTrue("offline replay on refresh failure", "StreamCache.recall(this@VideoActivity, t.key)" in v)
        val c = src("mediaapps/StreamCache.kt")
        assertTrue("offline replay remembers urls per track", "fun remember(" in c && "fun recall(" in c && "StreamCache.remember(this, t.key, playUris)" in v)
        assertTrue("downloads/files bypass the cache", "if (cacheable(spec.uri)) cached else plain" in c)
        assertTrue("next song prefetched", "prefetchNext()" in m && "StreamCache.prefetch(" in m)
    }
}
