package com.blaze.assistant.mediaapps

import java.io.IOException
import java.io.InterruptedIOException

/**
 * 0.45.19 media that survives flaky networks. The pure rules (MediaNetTest); the device reads (link, Data Saver,
 * the plan's limits, the prefetch ledger) are in [MediaNetDevice]:
 * - how far the player reads ahead, by link and Data Saver;
 * - whether the next queued song may be prefetched: Wi-Fi or good signal only, never under Data Saver, and no more
 *   prefetches this month than the plan's song downloads left (GET /v1/entitlements);
 * - how big the stream cache may grow: the phone's budget, capped by what this month's download limits are worth;
 * - [NetReader]: a stream reader that rides out a short drop (re-opens where it stopped instead of failing) and,
 *   from step 3, asks for the first chunk small so playback starts on it.
 */
object MediaNet {
    const val MB = 1024L * 1024L

    enum class Link { WIFI, GOOD, WEAK, NONE }

    /** Downstream estimate at or above this counts as good signal on mobile data. */
    const val GOOD_KBPS = 2_000

    fun classify(connected: Boolean, wifiOrEthernet: Boolean, unmetered: Boolean, downKbps: Int): Link = when {
        !connected -> Link.NONE
        wifiOrEthernet || unmetered -> Link.WIFI
        downKbps >= GOOD_KBPS -> Link.GOOD
        else -> Link.WEAK
    }



    // ---------------------------------------------------------------- read ahead

    /**
     * Seconds of media the player keeps loaded ahead (ms), never above the phone's RAM ceiling [ramMaxMs]:
     * Wi-Fi the full ceiling, good signal 60 s, weak signal 45 s (a drop is likelier, so not less), Data Saver 30 s.
     * Never under 30 s: that is what carries playback through a short drop.
     */
    fun readAheadMs(link: Link, saving: Boolean, ramMaxMs: Int): Int {
        val want = when {
            saving -> 30_000
            link == Link.WIFI || link == Link.NONE -> ramMaxMs   // NONE: keep what's loading, it may come back
            link == Link.GOOD -> 60_000
            else -> 45_000
        }
        return want.coerceAtMost(ramMaxMs).coerceAtLeast(minOf(30_000, ramMaxMs))
    }

    // ---------------------------------------------------------------- quotas


    /** Rough size of one item the quota counts, for turning download counts into a cache cap. */
    const val SONG_ITEM_BYTES = 10 * MB      // ~4 min at 320 kbps
    const val VIDEO_ITEM_BYTES = 80 * MB     // ~10 min at 720p
    /** The cache never shrinks below this: read-ahead and short-drop resume still need a disk to land on. */
    const val CACHE_FLOOR = 32 * MB

    /**
     * The stream cache's size: the phone's [deviceBudget], capped by what this month's download limits are worth.
     * No snapshot (signed out / never fetched) or an uncapped plan: the phone's budget.
     */
    fun cacheCap(deviceBudget: Long, songLeft: Int?, videoLeft: Int?): Long {
        if (songLeft == null && videoLeft == null) return deviceBudget
        fun part(left: Int?, item: Long): Long = when (left) {
            null -> 0L
            Int.MAX_VALUE -> deviceBudget
            else -> left.toLong().coerceAtLeast(0) * item
        }
        val worth = (part(songLeft, SONG_ITEM_BYTES) + part(videoLeft, VIDEO_ITEM_BYTES)).coerceAtLeast(0)
        return minOf(deviceBudget, worth.coerceAtLeast(CACHE_FLOOR)).coerceAtLeast(minOf(CACHE_FLOOR, deviceBudget))
    }


    // ---------------------------------------------------------------- prefetch

    /** Bytes of the next item fetched ahead: ~30 s of a 320 kbps song, more on Wi-Fi; a video's first ~10 s. */
    fun prefetchBytes(link: Link, kind: String): Long = when {
        kind == "video" -> if (link == Link.WIFI) 6 * MB else 3 * MB
        link == Link.WIFI -> 2_500_000L
        else -> 1_500_000L
    }

    /**
     * Why the next item is NOT prefetched, or null when it may be. Wi-Fi or good signal only; never under Data Saver;
     * no more prefetches this month ([prefetchedThisMonth]) than downloads left ([left], null = unknown -> allowed).
     */
    fun noPrefetch(link: Link, saving: Boolean, left: Int?, prefetchedThisMonth: Int): String? = when {
        saving -> "data saver"
        link == Link.NONE -> "offline"
        link == Link.WEAK -> "weak signal"
        left != null && left != Int.MAX_VALUE && prefetchedThisMonth >= left -> "monthly limit"
        else -> null
    }


    /** Prefetch ledger: "month|kind" -> count, only the current month kept. */
    fun bump(ledger: Map<String, Int>, month: String, kind: String): Map<String, Int> {
        val k = "$month|$kind"
        return ledger.filterKeys { it.startsWith("$month|") } + (k to (ledger[k] ?: 0) + 1)
    }
    fun count(ledger: Map<String, Int>, month: String, kind: String): Int = ledger["$month|$kind"] ?: 0


    // ---------------------------------------------------------------- step 3: faster first play

    /**
     * Range sizes for network streams: a small first range so the first bytes (and playback) come on the first
     * request's answer, then 4 MB ranges (some CDNs pace open-ended whole-file requests).
     */
    val STREAM_CHUNKS = Chunks(256 * 1024L, 4 * MB)
    /** Media buffered before playback starts (0.45.18: 1.5 s); after a stall it still waits for 3 s. */
    const val START_BUFFER_MS = 1_000
    const val REBUFFER_MS = 3_000
    /** A video waits at most this long for its remembered subtitle (0.45.18: up to 4 s); a later one is added when it lands. */
    const val SUBS_WAIT_MS = 700L
    /** The remembered subtitle keeps loading in the background this long in total. */
    const val SUBS_TOTAL_MS = 4_000L

    /**
     * How many of the top results get their details (stream links, length, subtitles) fetched ahead while the user
     * looks at the grid, so Preview -> Play doesn't wait: Wi-Fi 2, good signal 1, else none; never under Data Saver
     * or while sources are still answering.
     */
    fun warmCount(link: Link, saving: Boolean, pending: Int): Int = when {
        saving || pending > 0 -> 0
        link == Link.WIFI -> 2
        link == Link.GOOD -> 1
        else -> 0
    }

    /** A small TTL + LRU cache (details fetched ahead; stream links expire, so 10 min). [now] injectable for tests. */
    class TtlCache<T>(private val max: Int = 24, private val ttlMs: Long = 10 * 60_000L, private val now: () -> Long = System::currentTimeMillis) {
        private val map = object : LinkedHashMap<String, Pair<T, Long>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<T, Long>>?) = size > max
        }
        @Synchronized fun put(key: String, v: T) { map[key] = v to now() }
        @Synchronized fun get(key: String): T? {
            val (v, at) = map[key] ?: return null
            if (now() - at > ttlMs) { map.remove(key); return null }
            return v
        }
        @Synchronized fun size() = map.size
    }

    // ---------------------------------------------------------------- load errors

    /** How long a stream rides out a dropped connection inside one read before the player hears about it. */
    const val SHORT_DROP_MS = 10_000L
    /** Back-off between re-opens during a drop. */
    fun backoffMs(attempt: Int): Long = when (attempt) { 0 -> 250L; 1 -> 500L; 2 -> 1_000L; else -> 2_000L }

    /**
     * The player's retry delay after a load error that got past [NetReader]: a refused stream (HTTP status) fails at
     * once so the source can be asked again; anything else (no network, reset) retries every 1-5 s for minutes,
     * playing on from the buffer and the disk cache meanwhile.
     */
    fun retryDelayMs(httpStatus: Boolean, outOfRange: Boolean, errorCount: Int): Long =
        if (httpStatus || outOfRange) -1L else minOf((errorCount - 1).coerceAtLeast(0) * 1_000L, 5_000L).coerceAtLeast(500L)
    const val LOAD_RETRIES = 60

    // ---------------------------------------------------------------- the reader

    interface Clock {
        fun now(): Long
        fun sleep(ms: Long)
        companion object {
            val REAL = object : Clock {
                override fun now() = System.nanoTime() / 1_000_000L
                override fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); throw InterruptedIOException("interrupted")
                }
            }
        }
    }

    /** One range request at a time (an HTTP GET with a Range header). */
    interface RangeSource {
        /** Opens [pos, pos+len) (len -1 = to the end). [Opened.length] -1 = unknown. */
        fun open(pos: Long, len: Long): Opened
        /** -1 = this range ended. */
        fun read(buf: ByteArray, off: Int, len: Int): Int
        fun close()
    }

    /** [total] is the whole stream's size when the server said (-1 otherwise); [ranged] false = it ignored the range. */
    data class Opened(val length: Long, val total: Long = -1, val ranged: Boolean = true)

    /** Range sizes: the first request, then each next one (0 = one open-ended request, no chunking). */
    data class Chunks(val first: Long, val next: Long) {
        companion object { val NONE = Chunks(0, 0) }
        fun size(index: Int): Long = if (first <= 0) -1 else if (index == 0) first else next
    }

    /**
     * Reads a stream through [src], riding out drops: a failed read or a closed connection re-opens at the byte it
     * stopped at, backing off, for up to [graceMs]; only then does the error reach the player. [fatal] errors (an HTTP
     * status) pass straight through; [eof] errors (416 past the end) end the stream.
     */
    class NetReader(
        private val src: RangeSource,
        private val clock: Clock = Clock.REAL,
        private val graceMs: Long = SHORT_DROP_MS,
        private val chunks: Chunks = Chunks.NONE,
        private val fatal: (IOException) -> Boolean = { false },
        private val eof: (IOException) -> Boolean = { false },
    ) {
        private var pos = 0L
        private var end = -1L          // exclusive; -1 = to the stream's end
        private var total = -1L
        private var left = 0L          // bytes left in the open range; -1 = open-ended
        private var opened = false
        private var ended = false
        private var chunk = 0
        private var chunking = true
        var requests = 0; private set
        var reopens = 0; private set
        val position: Long get() = pos

        /** Opens at [start] for [length] bytes (-1 = to the end); returns the length if known, else -1. */
        fun open(start: Long, length: Long): Long {
            pos = start; end = if (length >= 0) start + length else -1; total = -1; chunk = 0; chunking = chunks.first > 0
            ended = false; requests = 0; reopens = 0
            openAt()
            return when { end >= 0 -> end - start; total >= 0 -> total - start; !chunking && left >= 0 -> left; else -> -1 }
        }

        private fun done() = ended || (end in 0..pos) || (total in 0..pos)

        private fun openAt() {
            var len = if (chunking) chunks.size(chunk) else -1L
            if (end >= 0) len = if (len < 0) end - pos else minOf(len, end - pos)
            val o = src.open(pos, len)
            requests++; opened = true
            if (o.total >= 0) total = o.total
            if (chunking && !o.ranged) {
                // The server ignored the range (sent everything): one open-ended request from here, no more chunks.
                src.close(); chunking = false
                val o2 = src.open(pos, if (end >= 0) end - pos else -1)
                requests++
                if (o2.total >= 0) total = o2.total
                left = o2.length
            } else left = o.length
            chunk++
        }

        private fun closeQuietly() { opened = false; try { src.close() } catch (_: IOException) { } }

        fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                if (done()) return -1
                if (!opened) {
                    try { openAt() } catch (e: IOException) {
                        if (eof(e)) { ended = true; return -1 }
                        recover(e); continue
                    }
                }
                if (left == 0L) {
                    closeQuietly()
                    if (!chunking) return -1          // an exact, open-ended range finished
                    continue                          // next chunk
                }
                val want = if (left > 0) minOf(len.toLong(), left).toInt() else len
                val r = try { src.read(buf, off, want) } catch (e: IOException) { recover(e); continue }
                if (r < 0) {
                    closeQuietly()
                    if (left < 0) return -1           // open-ended range ended: the stream did
                    recover(IOException("connection closed with $left bytes to go")); continue
                }
                pos += r
                if (left > 0) left -= r
                return r
            }
        }

        /** A drop: re-open at [pos], backing off, until it works or [graceMs] has passed. */
        private fun recover(first: IOException) {
            if (fatal(first)) { closeQuietly(); throw first }
            closeQuietly()
            val deadline = clock.now() + graceMs
            var e = first
            var attempt = 0
            while (true) {
                val now = clock.now()
                if (now >= deadline) throw e
                clock.sleep(minOf(backoffMs(attempt++), deadline - now))
                try {
                    openAt(); reopens++; return
                } catch (e2: IOException) {
                    if (eof(e2)) { ended = true; return }
                    if (fatal(e2)) { closeQuietly(); throw e2 }
                    closeQuietly(); e = e2
                }
            }
        }

        fun close() { closeQuietly() }
    }
}
