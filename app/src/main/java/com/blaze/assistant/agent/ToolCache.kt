package com.blaze.assistant.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.33.1 content-hash tool cache (idea #29) + speculative prefetch (idea #2).
 *
 *  - Identical read-only lookups (web_search, fetch_page) are answered from a small on-phone cache instead of the
 *    network: the key is a SHA-256 of the tool name and its normalised arguments, so "Weather  Ndola" and
 *    "weather ndola" share one entry. It's per phone (filesDir/tool_cache.json), so per user; private chats never read
 *    or write it.
 *  - Freshness first: time-sensitive searches (news, today, live, price, weather, score…) keep 5 minutes, other searches
 *    30, pages 2 hours. Errors, blocks and empty results are never stored. "refresh" / "check again" in the request
 *    skips the cache. A cached answer tells the model how old it is.
 *  - Speculation: after a web search on Wi-Fi (never on metered data or Low-data mode), the top two result pages are
 *    fetched in the background, so the fetch_page that usually follows is instant. A fetch that's already in flight is
 *    awaited, never started twice.
 *
 * The rules are pure (ToolCacheTest); [Store] takes a clock.
 */
object ToolCache {
    const val MAX_ENTRIES = 200
    const val SEARCH_TTL = 30 * 60_000L
    const val FRESH_TTL = 5 * 60_000L
    const val PAGE_TTL = 2 * 3600_000L
    const val FRESH_PAGE_TTL = 10 * 60_000L
    val tools = setOf("web_search", "fetch_page")

    private val freshWords = Regex("(?i)\\b(news|today|tonight|now|live|latest|current|breaking|score|scores|price|prices|rate|rates|exchange|weather|forecast|stock|stocks|traffic|update|updates|happening|this week|yesterday)\\b")
    private val freshUrl = Regex("(?i)(/news|/live|/api/|weather|score|finance|/feed|rss|status)")
    private val refresh = Regex("(?i)\\b(refresh|reload|check again|look again|search again|try again|re-?check|up to date|fresh)\\b")

    /** The normalised arguments a key is made from. */
    fun canonical(tool: String, args: JSONObject): String = when (tool) {
        "web_search" -> args.optString("query").lowercase().replace(Regex("\\s+"), " ").trim()
        "fetch_page" -> args.optString("url").trim().substringBefore('#').removeSuffix("/")
        else -> args.toString()
    }

    fun key(tool: String, args: JSONObject): String = sha256(tool + "\n" + canonical(tool, args))

    fun keyForUrl(url: String): String = key("fetch_page", JSONObject().put("url", url))

    /** How long a result may be reused; 0 = never cache. */
    fun ttlMs(tool: String, args: JSONObject): Long = when (tool) {
        "web_search" -> if (args.optString("query").isBlank()) 0 else if (freshWords.containsMatchIn(args.optString("query"))) FRESH_TTL else SEARCH_TTL
        "fetch_page" -> args.optString("url").let { u -> if (!u.startsWith("http")) 0 else if (freshUrl.containsMatchIn(u)) FRESH_PAGE_TTL else PAGE_TTL }
        else -> 0
    }

    /** The user asked for a fresh look. */
    fun bypass(userText: String): Boolean = refresh.containsMatchIn(userText)

    /** Only real answers are kept: no errors, blocks, "no results" or near-empty pages. */
    fun storable(result: String): Boolean {
        val t = result.trimStart()
        return t.length >= 40 && !t.startsWith("Error") && !t.startsWith("No results") && !t.startsWith("Couldn't")
    }

    /** What the model sees in front of a cached result. */
    fun note(ageMs: Long): String {
        val m = ageMs / 60_000
        return "[Cached result from ${if (m < 1) "under a minute" else "$m min"} ago. If freshness matters, say so or search again with other words.]\n"
    }

    /** The result urls of a web_search answer, in order (DuckDuckGo / Bing / GitHub lists all put the url on its own line). */
    fun resultUrls(searchResult: String, n: Int = 2): List<String> =
        searchResult.lineSequence().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }
            .filterNot { it.contains("duckduckgo.com") || it.contains("bing.com/") || it.endsWith(".pdf", true) }
            .distinct().take(n).toList()

    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    data class Entry(val at: Long, val value: String)

    /** An LRU of entries with a clock (pure; tested). */
    class Store(private val max: Int = MAX_ENTRIES, private val clock: () -> Long = System::currentTimeMillis) {
        private val map = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > max
        }
        var hits = 0; private set
        var misses = 0; private set

        @Synchronized fun get(key: String, ttl: Long): Entry? {
            val e = map[key]
            if (e == null || ttl <= 0 || clock() - e.at > ttl) { if (e != null && ttl > 0 && clock() - e.at > ttl * 4) map.remove(key); misses++; return null }
            hits++
            return e
        }
        @Synchronized fun put(key: String, value: String) { map[key] = Entry(clock(), value) }
        @Synchronized fun has(key: String, ttl: Long): Boolean = map[key]?.let { clock() - it.at <= ttl } == true
        @Synchronized fun size() = map.size
        @Synchronized fun clear() { map.clear(); hits = 0; misses = 0 }
        @Synchronized fun toJson(): JSONObject = JSONObject().apply { map.forEach { (k, e) -> put(k, JSONObject().put("at", e.at).put("v", e.value)) } }
        @Synchronized fun load(o: JSONObject) {
            o.keys().asSequence().toList().mapNotNull { k -> o.optJSONObject(k)?.let { k to Entry(it.optLong("at"), it.optString("v")) } }
                .sortedBy { it.second.at }.forEach { (k, e) -> map[k] = e }
        }
    }

    // ---------------- Android side ----------------

    private val store = Store()
    private val inflight = ConcurrentHashMap<String, Deferred<String>>()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    @Volatile private var dirty = false
    @Volatile private var loaded = false

    val enabled get() = try { Settings.bool("tool_cache", true) } catch (_: Throwable) { true }
    val speculate get() = try { Settings.bool("speculative_fetch", true) } catch (_: Throwable) { true }

    fun init(filesDir: File) {
        file = File(filesDir, "tool_cache.json")
        bg.async { try { file?.takeIf { it.isFile }?.let { store.load(JSONObject(it.readText())) } } catch (_: Exception) { }; loaded = true }
    }

    private fun save() {
        if (dirty) return
        dirty = true
        bg.async {
            kotlinx.coroutines.delay(3_000)
            dirty = false
            try { file?.let { f -> val tmp = File(f.path + ".tmp"); tmp.writeText(store.toJson().toString()); tmp.renameTo(f) } } catch (_: Exception) { }
        }
    }

    /**
     * Runs [fetch] or answers from the cache. [private] (a private chat) skips the cache entirely; an identical
     * lookup already running (a speculative prefetch) is awaited instead of started again.
     */
    suspend fun cached(tool: String, args: JSONObject, userText: String, private: Boolean, fetch: suspend () -> String): String {
        if (private || !enabled || tool !in tools) return fetch()
        val ttl = ttlMs(tool, args)
        if (ttl <= 0) return fetch()
        val k = key(tool, args)
        if (!bypass(userText)) {
            store.get(k, ttl)?.let { e -> TokenLedger.cacheHit(); return note(System.currentTimeMillis() - e.at) + e.value }
            inflight[k]?.let { d -> val r = try { d.await() } catch (_: Exception) { "" }; if (storable(r)) { TokenLedger.cacheHit(); return r } }
        }
        val r = fetch()
        if (storable(r)) { store.put(k, r); save() }
        return r
    }

    /** After a search: fetch its top pages in the background (Wi-Fi only), into the cache. */
    fun prefetch(searchResult: String, unmetered: Boolean, lowData: Boolean, private: Boolean, fetch: suspend (String) -> String) {
        if (private || lowData || !unmetered || !enabled || !speculate) return
        for (u in resultUrls(searchResult)) {
            val k = keyForUrl(u)
            if (store.has(k, ttlMs("fetch_page", JSONObject().put("url", u))) || inflight.containsKey(k)) continue
            val d = bg.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val r = try { fetch(u) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }
                if (storable(r)) { store.put(k, r); save() }
                inflight.remove(k)
                r
            }
            inflight[k] = d
            d.start()
        }
    }

    fun stats(): String = "Tool cache: ${store.size()} saved lookups, ${store.hits} reused, ${store.misses} fetched fresh since start" +
        (if (!enabled) " (off)" else "") + (if (!speculate) ", prefetch off" else ", prefetch on Wi-Fi")

    fun clear() { store.clear(); try { file?.delete() } catch (_: Exception) { } }
}
