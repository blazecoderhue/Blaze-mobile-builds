package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.StoreFormat
import com.blaze.assistant.mediaapps.store.StoreItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.45.20 gaps from Compass's stream/prefetch spec (STREAM_PREFETCH_SPEC.md) that media-net (0.45.19) left open.
 * Pure rules only (StreamSpecTest); the phone side is in StreamCache / MusicService / VideoActivity / MediaUi.
 *
 * - Honest player notes: "Buffering… (weak network)" after 1.5 s of a stall, "Can't reach the source, retrying" with
 *   Retry after 10 s, "Offline: playing from saved" while a stream plays from the disk cache with no network.
 * - Offline queue: skip what isn't on the phone ("Not saved for offline"), never an endless spinner.
 * - Search: "Still working… (slow network)" after 8 s, an error card ("No internet" / "Couldn't reach YouTube") with
 *   Retry and, when there are any, saved results.
 * - Video: after 2 stalls in 60 s, step down one rendition before rebuffering again.
 */
object NetHonesty {
    const val WEAK_AFTER_MS = 1_500L
    const val RETRY_AFTER_MS = 10_000L
    const val SLOW_SEARCH_MS = 8_000L

    enum class Play { NONE, WEAK, RETRYING, OFFLINE }

    /**
     * What the player says. [stalledMs] = how long it has been buffering while it should play (0 = not stalled);
     * [online] = the phone has a network; [stream] = the item comes from the network (a download never needs one);
     * [loadError] = the player gave up on a non-refusal load error (it keeps retrying in the background).
     */
    fun play(stalledMs: Long, online: Boolean, stream: Boolean, loadError: Boolean = false): Play = when {
        !stream -> Play.NONE
        loadError || stalledMs >= RETRY_AFTER_MS -> Play.RETRYING
        stalledMs >= WEAK_AFTER_MS -> Play.WEAK
        !online -> Play.OFFLINE
        else -> Play.NONE
    }

    fun playText(p: Play, online: Boolean = true): String = when (p) {
        Play.NONE -> ""
        Play.WEAK -> if (online) "Buffering\u2026 (weak network)" else "Buffering\u2026 (no connection)"
        Play.RETRYING -> "Can't reach the source, retrying"
        Play.OFFLINE -> "Offline: playing from saved"
    }

    /** Retry shows on the retrying note only. */
    fun retryButton(p: Play): Boolean = p == Play.RETRYING

    const val NOT_SAVED = "Not saved for offline"

    /** Offline, a stream stalled this long has run past what's on disk: move on now instead of retrying for minutes. */
    const val OFFLINE_SKIP_MS = 1_500L
    fun offlineSkipNow(stalledMs: Long, online: Boolean, stream: Boolean): Boolean = !online && stream && stalledMs >= OFFLINE_SKIP_MS

    /**
     * Offline: the next item that can play from the phone, after [from], in queue [order] (indices), where
     * [playable] says whether item i is local or has its start cached. -1 = nothing left that plays offline.
     */
    fun offlineNext(order: List<Int>, from: Int, playable: (Int) -> Boolean): Int {
        val at = order.indexOf(from)
        for (k in (at + 1) until order.size) if (playable(order[k])) return order[k]
        return -1
    }

    // ---------------------------------------------------------------- search

    enum class Search { NONE, SLOW, OFFLINE_ERROR, SOURCE_ERROR }

    /** The Store's state line: loading for 8 s with nothing yet = slow; a search that failed = an error card. */
    fun search(loading: Boolean, elapsedMs: Long, hasResults: Boolean, failed: Boolean, online: Boolean): Search = when {
        failed && !hasResults -> if (online) Search.SOURCE_ERROR else Search.OFFLINE_ERROR
        loading && !hasResults && elapsedMs >= SLOW_SEARCH_MS -> Search.SLOW
        else -> Search.NONE
    }

    fun slowText(): String = "Still working\u2026 (slow network)"

    /** "No internet" / "Couldn't reach YouTube" / "Couldn't reach the Store" (all sources at once). */
    fun errorTitle(s: Search, sourceName: String?): String = when (s) {
        Search.OFFLINE_ERROR -> "No internet"
        Search.SOURCE_ERROR -> "Couldn't reach " + (sourceName?.takeIf { it.isNotBlank() } ?: "the Store")
        else -> ""
    }

    // ---------------------------------------------------------------- video rendition step-down

    /** Counts stalls in a sliding [windowMs]; [stall] returns true when this one is the [limit]th (time to step down). */
    class StallWindow(private val windowMs: Long = 60_000L, private val limit: Int = 2) {
        private val at = ArrayDeque<Long>()
        fun stall(now: Long): Boolean {
            at.addLast(now)
            while (at.isNotEmpty() && now - at.first() > windowMs) at.removeFirst()
            if (at.size >= limit) { at.clear(); return true }
            return false
        }
        fun reset() = at.clear()
        val count: Int get() = at.size
    }

    /** The next lower picture height under [current] among [heights] (>= 144), or null when it is already the lowest. */
    fun stepDown(current: Int, heights: List<Int>): Int? = heights.filter { it in 144 until current }.maxOrNull()
}

/**
 * 0.45.20: an expired signed stream url (HTTP 403 / 410 mid-play) is re-resolved through its source and the read goes
 * on at the same byte, with no player error and no restart. Only the same rendition is accepted (same itag, or the same
 * byte size), so the bytes already in the disk cache under the stable key stay valid.
 */
object UrlRenew {
    const val MAX_PER_OPEN = 2
    /** One source refresh per stream at most this often when it keeps failing. */
    const val FAIL_BACKOFF_MS = 30_000L
    /** A renewed url is reused for opens that still carry the old one (the MediaItem keeps the old url). */
    const val MEMO_MS = 3 * 60 * 60_000L

    fun expired(code: Int): Boolean = code == 403 || code == 410

    fun param(url: String, name: String): String? {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return null
        return q.split('&').firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")?.takeIf { it.isNotEmpty() }
    }

    private fun lastSegment(url: String) = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')

    /**
     * The fresh url of the same rendition as [oldUrl] among [fresh] formats: same itag (YouTube), else the one format
     * with the same byte size [total] (or clen), else the same file name (archive.org). Null = no safe match.
     */
    fun sameRendition(oldUrl: String, total: Long, fresh: List<StoreFormat>): String? {
        val itag = param(oldUrl, "itag")
        if (itag != null) return fresh.firstOrNull { it.key == itag || param(it.url, "itag") == itag }?.url
        val size = total.takeIf { it > 0 } ?: param(oldUrl, "clen")?.toLongOrNull()
        if (size != null) fresh.filter { it.contentLength == size || param(it.url, "clen")?.toLongOrNull() == size }.singleOrNull()?.let { return it.url }
        val name = lastSegment(oldUrl)
        if (name.contains('.')) fresh.filter { lastSegment(it.url) == name }.singleOrNull()?.let { return it.url }
        return null
    }

    /** "stream:<source>:<id>" or "stream:<source>:<id>#<part>" (StreamCache.key of a Store stream). */
    data class Key(val source: String, val id: String, val part: Int)

    fun parseKey(cacheKey: String): Key? {
        if (!cacheKey.startsWith("stream:")) return null
        val base = cacheKey.substringBefore('#')
        val part = cacheKey.substringAfter('#', "0").toIntOrNull() ?: 0
        val bits = base.split(":", limit = 3)
        if (bits.size < 3 || bits[1].isBlank() || bits[2].isBlank()) return null
        return Key(bits[1], bits[2], part)
    }

    /** Which kind to ask the source for first: a second part (the audio of a merged video) or a video mime = video. */
    fun kinds(oldUrl: String, part: Int): List<String> {
        val mime = param(oldUrl, "mime")?.replace("%2F", "/")?.lowercase().orEmpty()
        return if (part > 0 || mime.startsWith("video")) listOf("video", "audio") else listOf("audio", "video")
    }

    /**
     * The renew bookkeeping, with the source call injected ([resolve]: source, id, kind -> fresh formats, or null).
     * Thread-safe; [renew] blocks while the source answers (the player's loader thread, never the main thread).
     */
    class Renewer(private val resolve: (String, String, String) -> List<StoreFormat>?, private val now: () -> Long = System::currentTimeMillis) {
        private val memo = object : LinkedHashMap<String, Pair<String, Long>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Long>>?) = size > 64
        }
        private val failedAt = HashMap<String, Long>()
        var calls = 0; private set

        fun renew(cacheKey: String, oldUrl: String, total: Long): String? {
            val k = parseKey(cacheKey) ?: return null
            synchronized(this) {
                memo[oldUrl]?.let { (u, at) -> if (now() - at < MEMO_MS && u != oldUrl) return u }
                failedAt[cacheKey]?.let { if (now() - it < FAIL_BACKOFF_MS) return null }
            }
            for (kind in kinds(oldUrl, k.part)) {
                synchronized(this) { calls++ }
                val fresh = runCatching { resolve(k.source, k.id, kind) }.getOrNull() ?: continue
                val u = sameRendition(oldUrl, total, fresh) ?: continue
                synchronized(this) { memo[oldUrl] = u to now(); failedAt.remove(cacheKey) }
                return u
            }
            synchronized(this) { failedAt[cacheKey] = now() }
            return null
        }
    }
}

/**
 * 0.45.20: the last [MAX] search result pages per Store on disk, so a repeated search works offline ("Show saved
 * results"). Encoding is pure (StreamSpecTest); MediaUi reads/writes it through MediaPrefs.
 */
object SavedSearches {
    const val MAX = 20
    const val ITEMS = 30

    data class Page(val key: String, val at: Long, val items: List<StoreItem>)

    fun itemJson(i: StoreItem): JSONObject = JSONObject().put("s", i.source).put("i", i.id).put("t", i.title).put("a", i.author)
        .put("d", i.durationSec).put("dt", i.durationText).put("th", i.thumb).put("l", i.licence).put("lu", i.licenceUrl)
        .put("v", i.views).put("sq", i.square)
        .apply { if (i.extra.isNotEmpty()) put("x", JSONObject(i.extra as Map<*, *>)) }

    fun itemOf(o: JSONObject): StoreItem? {
        val src = o.optString("s"); val id = o.optString("i")
        if (src.isBlank() || id.isBlank()) return null
        val x = o.optJSONObject("x")?.let { e -> e.keys().asSequence().associateWith { e.optString(it) } }.orEmpty()
        return StoreItem(src, id, o.optString("t"), o.optString("a"), o.optInt("d"), o.optString("dt"), o.optString("th"),
            o.optString("l"), o.optString("lu"), o.optString("v"), o.optBoolean("sq"), x)
    }

    fun encode(pages: List<Page>): String = JSONArray().also { a ->
        pages.forEach { p -> a.put(JSONObject().put("k", p.key).put("at", p.at).put("items", JSONArray().also { ia -> p.items.forEach { ia.put(itemJson(it)) } })) }
    }.toString()

    fun decode(s: String): List<Page> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { n ->
            val o = a.optJSONObject(n) ?: return@mapNotNull null
            val items = o.optJSONArray("items")?.let { ia -> (0 until ia.length()).mapNotNull { ia.optJSONObject(it)?.let(::itemOf) } }.orEmpty()
            if (items.isEmpty()) null else Page(o.optString("k"), o.optLong("at"), items)
        }
    }.getOrDefault(emptyList())

    /** Newest first, one page per key, at most [MAX] pages of at most [ITEMS] results. */
    fun put(pages: List<Page>, key: String, items: List<StoreItem>, at: Long): List<Page> {
        if (items.isEmpty() || key.isBlank()) return pages
        return (listOf(Page(key, at, items.take(ITEMS))) + pages.filter { it.key != key }).take(MAX)
    }

    fun find(pages: List<Page>, key: String): Page? = pages.firstOrNull { it.key == key }
}
