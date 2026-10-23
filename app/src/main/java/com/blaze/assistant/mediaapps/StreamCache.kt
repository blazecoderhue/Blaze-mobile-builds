package com.blaze.assistant.mediaapps

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.util.TreeSet
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

    /** Bytes of the next song fetched ahead: enough for ~30 s at 320 kbps (0.45.19: by link, see [MediaNet.prefetchBytes]). */
    const val PREFETCH_BYTES = 1_500_000L

    /** Only network streams are cached. */
    fun cacheable(uri: Uri?): Boolean = cacheableUrl(uri?.toString().orEmpty())
    fun cacheableUrl(url: String): Boolean = url.substringBefore(':').lowercase() in setOf("http", "https") && "://" in url

    /** The cache key of part [part] of a stream: the Track key (stable), never the signed url (expires). */
    fun key(trackKey: String, part: Int = 0): String? = trackKey.takeIf { it.isNotBlank() }?.let { if (part == 0) it else "$it#$part" }

    @Volatile private var cache: SimpleCache? = null
    @Volatile private var evictor: CappedEvictor? = null
    @Volatile private var deviceBudget = 0L
    private val prefetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "blaze-prefetch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var writer: CacheWriter? = null

    private fun ramMb(ctx: Context): Long = try {
        val mi = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi); mi.totalMem / MB
    } catch (_: Exception) { 0 }

    fun get(ctx: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: run {
            val dir = File(ctx.applicationContext.cacheDir, "media-stream").apply { mkdirs() }
            deviceBudget = budget(ramMb(ctx), dir.usableSpace)
            // 0.45.19: the LRU cap also follows this month's download limits (MediaNet.cacheCap), updated in place.
            val ev = CappedEvictor(MediaNetDevice.cacheCap(deviceBudget, com.blaze.assistant.connect.Entitlements.cached(ctx)))
            evictor = ev
            SimpleCache(dir, ev, StandaloneDatabaseProvider(ctx.applicationContext))
        }.also { cache = it }
    }

    /** Re-reads the plan's limits into the cache cap (shrinking evicts the oldest spans on the next write). */
    fun applyQuota(ctx: Context) {
        get(ctx)
        evictor?.cap = MediaNetDevice.cacheCap(deviceBudget, com.blaze.assistant.connect.Entitlements.cached(ctx))
    }

    /**
     * 0.45.19: buffer ahead by link (Wi-Fi the RAM ceiling, good signal 60 s, weak 45 s, Data Saver 30 s; see
     * [MediaNet.readAheadMs]), re-read every few seconds so walking off Wi-Fi shortens it.
     */
    fun loadControl(ctx: Context): LoadControl {
        val (min, max) = bufferMs(ramMb(ctx))
        val app = ctx.applicationContext
        return ReadAheadControl(min, max, MediaNet.START_BUFFER_MS, MediaNet.REBUFFER_MS) { MediaNet.readAheadMs(MediaNetDevice.link(app), MediaNetDevice.saving(app), max) }
    }

    /** 0.45.19: the players' load-error policy: drops retry for minutes from buffer + cache, refusals fail fast. */
    fun dropPolicy(): LoadErrorHandlingPolicy = DropPolicy()

    /** The player's data source: http(s) through the disk cache, everything else (files, content://) straight. */
    fun factory(ctx: Context, upstream: DataSource.Factory): DataSource.Factory {
        val plain = DefaultDataSource.Factory(ctx, upstream)
        // 0.45.19: network reads ride out a short drop (re-open where they stopped) before the player sees an error.
        // Step 3: a small first range so playback starts on the first answer, then 4 MB ranges.
        val resuming = DataSource.Factory { ResumingDataSource(plain.createDataSource(), MediaNet.STREAM_CHUNKS) }
        val cached = CacheDataSource.Factory().setCache(get(ctx)).setUpstreamDataSourceFactory(resuming)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { Split(cached.createDataSource(), plain.createDataSource()) }
    }

    /** True when the start of [key] is on disk (replay offline). */
    fun hasStart(ctx: Context, key: String, bytes: Long = 256 * 1024): Boolean = try { get(ctx).isCached(key, 0, bytes) } catch (_: Exception) { false }

    /**
     * Fetches the start of [url] into the cache under [key] in the background; a newer call cancels it.
     * 0.45.19: only on Wi-Fi or good signal, never under Data Saver, at most this month's [kind] downloads left
     * ([MediaNet.takePrefetch]); a start already on disk isn't fetched (or counted) again.
     */
    fun prefetch(ctx: Context, url: String, key: String, upstream: DataSource.Factory, kind: String = "song") {
        if (!cacheableUrl(url)) return
        writer?.cancel()
        val app = ctx.applicationContext
        prefetcher.execute {
            try {
                applyQuota(app)
                if (hasStart(app, key)) return@execute
                val bytes = MediaNetDevice.takePrefetch(app, kind)
                if (bytes <= 0) return@execute
                val ds = CacheDataSource.Factory().setCache(get(app)).setUpstreamDataSourceFactory(DefaultDataSource.Factory(app, upstream)).createDataSource()
                val w = CacheWriter(ds, DataSpec.Builder().setUri(url).setKey(key).setPosition(0).setLength(bytes).build(), null, null)
                writer = w
                w.cache()
            } catch (_: Exception) {
            } finally { writer = null }
        }
    }

    private val warmer = Executors.newSingleThreadExecutor { r -> Thread(r, "blaze-warm").apply { isDaemon = true } }

    /**
     * 0.45.19 step 3: fetches the first chunk of the stream about to play into the cache while its metadata
     * (subtitles) loads, so the player starts from disk. Not a prefetch: it's the item being played, so no quota.
     */
    fun warm(ctx: Context, url: String, key: String, upstream: DataSource.Factory, bytes: Long = MediaNet.STREAM_CHUNKS.first) {
        if (!cacheableUrl(url)) return
        val app = ctx.applicationContext
        warmer.execute {
            try {
                if (hasStart(app, key, bytes)) return@execute
                val ds = CacheDataSource.Factory().setCache(get(app)).setUpstreamDataSourceFactory(DefaultDataSource.Factory(app, upstream)).createDataSource()
                CacheWriter(ds, DataSpec.Builder().setUri(url).setKey(key).setPosition(0).setLength(bytes).build(), null, null).cache()
            } catch (_: Exception) { }
        }
    }

    /** 0.45.19 step 3: details fetched ahead for the top results (MediaNet.warmCount), keyed source:id:kind. */
    val details = MediaNet.TtlCache<com.blaze.assistant.mediaapps.store.StoreDetail>()
    fun detailKey(source: String, id: String, kind: String) = "$source:$id:$kind"

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


/** LRU eviction with a cap that can change while the cache is open (the plan's limits move during the month). */
@OptIn(UnstableApi::class)
class CappedEvictor(cap: Long) : CacheEvictor {
    @Volatile var cap: Long = cap
    private val lru = TreeSet<CacheSpan> { a, b -> if (a.lastTouchTimestamp != b.lastTouchTimestamp) a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp) else a.compareTo(b) }
    private var size = 0L
    val bytes: Long get() = size
    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {}
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) { if (length != C.LENGTH_UNSET.toLong()) evict(cache, length) }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) { lru.add(span); size += span.length; evict(cache, 0) }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) { if (lru.remove(span)) size -= span.length }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) { onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan) }
    private fun evict(cache: Cache, need: Long) { while (size + need > cap && lru.isNotEmpty()) cache.removeSpan(lru.first()) }
}

/** [DefaultLoadControl] that stops loading at the link's read-ahead ([cap], ms, re-read every 5 s). */
@OptIn(UnstableApi::class)
class ReadAheadControl(min: Int, max: Int, play: Int, rebuffer: Int, private val cap: () -> Int) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE), min, max, play, rebuffer, DEFAULT_TARGET_BUFFER_BYTES,
    DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS, DEFAULT_BACK_BUFFER_DURATION_MS, DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME) {
    @Volatile private var capUs = max * 1000L
    @Volatile private var at = Long.MIN_VALUE / 2
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val go = super.shouldContinueLoading(parameters)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - at >= 5_000) { at = now; capUs = runCatching { cap() }.getOrDefault((capUs / 1000).toInt()) * 1000L }
        return go && parameters.bufferedDurationUs < capUs
    }
}

/** Load errors: an HTTP refusal fails at once (the source is asked again); a drop retries every 1-5 s for minutes. */
@OptIn(UnstableApi::class)
class DropPolicy : DefaultLoadErrorHandlingPolicy(MediaNet.LOAD_RETRIES) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) return C.TIME_UNSET
        val e = loadErrorInfo.exception
        val d = MediaNet.retryDelayMs(e is HttpDataSource.InvalidResponseCodeException, DataSourceException.isCausedByPositionOutOfRange(e), loadErrorInfo.errorCount)
        return if (d < 0) C.TIME_UNSET else d
    }
}

/**
 * A network [DataSource] that rides out drops ([MediaNet.NetReader]): a failed read re-opens at the byte it stopped at
 * (a Range request) for up to [MediaNet.SHORT_DROP_MS] before the error reaches the player. Range requests come from
 * [chunks] (step 3: a small first chunk so playback starts on it).
 */
@OptIn(UnstableApi::class)
class ResumingDataSource(
    private val up: DataSource,
    chunks: MediaNet.Chunks = MediaNet.Chunks.NONE,
    clock: MediaNet.Clock = MediaNet.Clock.REAL,
    graceMs: Long = MediaNet.SHORT_DROP_MS,
) : DataSource {
    private var spec: DataSpec? = null
    private val reader = MediaNet.NetReader(object : MediaNet.RangeSource {
        override fun open(pos: Long, len: Long): MediaNet.Opened {
            val s = spec!!
            val n = up.open(s.subrange(pos - s.position, if (len < 0) C.LENGTH_UNSET.toLong() else len))
            val cr = header(up.responseHeaders, "Content-Range")
            return MediaNet.Opened(if (n == C.LENGTH_UNSET.toLong()) -1 else n, totalOf(cr), ranged = cr != null || (pos == 0L && len < 0))
        }
        override fun read(buf: ByteArray, off: Int, len: Int): Int = up.read(buf, off, len).let { if (it == C.RESULT_END_OF_INPUT) -1 else it }
        override fun close() = up.close()
    }, clock, graceMs, chunks,
        fatal = { it is HttpDataSource.InvalidResponseCodeException && it.responseCode != 416 },
        eof = { DataSourceException.isCausedByPositionOutOfRange(it) || (it is HttpDataSource.InvalidResponseCodeException && it.responseCode == 416) })

    val requests: Int get() = reader.requests
    val reopens: Int get() = reader.reopens

    override fun addTransferListener(transferListener: TransferListener) = up.addTransferListener(transferListener)
    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        val n = reader.open(dataSpec.position, dataSpec.length)
        return if (n < 0) C.LENGTH_UNSET.toLong() else n
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = reader.read(buffer, offset, length).let { if (it < 0) C.RESULT_END_OF_INPUT else it }
    override fun getUri(): Uri? = up.uri ?: spec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = up.responseHeaders
    override fun close() { try { reader.close() } finally { spec = null } }

    companion object {
        fun header(h: Map<String, List<String>>, name: String): String? =
            h.entries.firstOrNull { it.key != null && it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        /** "bytes 0-262143/9876543" -> 9876543; "*" or missing -> -1. */
        fun totalOf(contentRange: String?): Long = contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1L
    }
}
