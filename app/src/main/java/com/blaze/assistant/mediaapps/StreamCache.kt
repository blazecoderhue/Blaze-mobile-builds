package com.blaze.assistant.mediaapps

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import java.io.File
import java.util.concurrent.Executors

/**
 * Wave A item 16: streams keep what they've played on disk (an LRU cache sized to the phone), buffer well ahead so a
 * flaky network doesn't stall, prefetch the start of the next song, and replay from disk when the network is gone.
 * Downloads and phone files never pass through the cache (they're already local).
 */
@OptIn(UnstableApi::class)
object StreamCache {
    const val MB = 1024L * 1024L

    /** Disk budget: 256 MB, 128 MB on a 3 GB phone, never more than 10 % of free space, never under 32 MB. */
    fun budget(totalRamMb: Long, freeDiskBytes: Long): Long {
        val want = if (totalRamMb in 1..3584) 128 * MB else 256 * MB
        return want.coerceAtMost(freeDiskBytes / 10).coerceAtLeast(32 * MB)
    }

    /** Buffer ahead (ms): min / max. Longer on bigger phones; a 3 GB phone keeps RAM low. */
    fun bufferMs(totalRamMb: Long): Pair<Int, Int> = if (totalRamMb in 1..3584) 20_000 to 50_000 else 30_000 to 120_000

    /** Bytes of the next song fetched ahead: enough for ~30 s at 320 kbps. */
    const val PREFETCH_BYTES = 1_500_000L

    /** Only network streams are cached. */
    fun cacheable(uri: Uri?): Boolean = cacheableUrl(uri?.toString().orEmpty())
    fun cacheableUrl(url: String): Boolean = url.substringBefore(':').lowercase() in setOf("http", "https") && "://" in url

    /** The cache key of part [part] of a stream: the Track key (stable), never the signed url (expires). */
    fun key(trackKey: String, part: Int = 0): String? = trackKey.takeIf { it.isNotBlank() }?.let { if (part == 0) it else "$it#$part" }

    @Volatile private var cache: SimpleCache? = null
    private val prefetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "blaze-prefetch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var writer: CacheWriter? = null

    private fun ramMb(ctx: Context): Long = try {
        val mi = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi); mi.totalMem / MB
    } catch (_: Exception) { 0 }

    fun get(ctx: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: run {
            val dir = File(ctx.applicationContext.cacheDir, "media-stream").apply { mkdirs() }
            SimpleCache(dir, LeastRecentlyUsedCacheEvictor(budget(ramMb(ctx), dir.usableSpace)), StandaloneDatabaseProvider(ctx.applicationContext))
        }.also { cache = it }
    }

    fun loadControl(ctx: Context): LoadControl {
        val (min, max) = bufferMs(ramMb(ctx))
        return DefaultLoadControl.Builder().setBufferDurationsMs(min, max, 1_500, 3_000).build()
    }

    /** The player's data source: http(s) through the disk cache, everything else (files, content://) straight. */
    fun factory(ctx: Context, upstream: DataSource.Factory): DataSource.Factory {
        val plain = DefaultDataSource.Factory(ctx, upstream)
        val cached = CacheDataSource.Factory().setCache(get(ctx)).setUpstreamDataSourceFactory(plain)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { Split(cached.createDataSource(), plain.createDataSource()) }
    }

    /** True when the start of [key] is on disk (replay offline). */
    fun hasStart(ctx: Context, key: String, bytes: Long = 256 * 1024): Boolean = try { get(ctx).isCached(key, 0, bytes) } catch (_: Exception) { false }

    /** Fetches the first [bytes] of [url] into the cache under [key] in the background; a newer call cancels it. */
    fun prefetch(ctx: Context, url: String, key: String, upstream: DataSource.Factory, bytes: Long = PREFETCH_BYTES) {
        if (!cacheableUrl(url)) return
        writer?.cancel()
        val app = ctx.applicationContext
        prefetcher.execute {
            try {
                val ds = CacheDataSource.Factory().setCache(get(app)).setUpstreamDataSourceFactory(DefaultDataSource.Factory(app, upstream)).createDataSource()
                val w = CacheWriter(ds, DataSpec.Builder().setUri(url).setKey(key).setPosition(0).setLength(bytes).build(), null, null)
                writer = w
                w.cache()
            } catch (_: Exception) {
            } finally { writer = null }
        }
    }

    // Offline replay: the last stream urls per Track key (the cache key), so a stream played before can start again
    // from disk when the source can't be reached.
    private const val PREFS = "blaze_stream_cache"
    fun remember(ctx: Context, trackKey: String, urls: List<String>) {
        if (urls.isEmpty()) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(trackKey, urls.joinToString("\n")).apply()
    }
    fun recall(ctx: Context, trackKey: String): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(trackKey, null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    /** Picks the cached or the direct source per request, by scheme. */
    private class Split(private val cached: DataSource, private val plain: DataSource) : DataSource {
        private var cur: DataSource? = null
        override fun addTransferListener(l: TransferListener) { cached.addTransferListener(l); plain.addTransferListener(l) }
        override fun open(spec: DataSpec): Long { val d = if (cacheable(spec.uri)) cached else plain; cur = d; return d.open(spec) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = cur!!.read(buffer, offset, length)
        override fun getUri(): Uri? = cur?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = cur?.responseHeaders ?: emptyMap()
        override fun close() { try { cur?.close() } finally { cur = null } }
    }
}
