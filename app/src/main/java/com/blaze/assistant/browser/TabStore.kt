package com.blaze.assistant.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.29.7: normal tabs survive closing the browser and Android killing Blaze. Each tab's URL, title, place in
 * the order and its WebView back/forward state (WebView.saveState, kept as base64 when it's small enough) go to
 * `filesDir/browser_tabs.json`, plus which tab was active. Private tabs are never written.
 * The JSON codec is pure so it's tested on the JVM; the WebView parts live in [BrowserEngine].
 */
object TabStore {
    data class Snap(val url: String, val title: String, val state: Map<String, String> = emptyMap())
    data class Saved(val tabs: List<Snap>, val active: Int)

    const val VERSION = 1
    /** Back/forward state bigger than this (base64 chars) is dropped: the tab just reopens its URL. */
    const val MAX_STATE = 400_000
    const val MAX_TOTAL_STATE = 3_000_000
    const val MAX_TABS = 40

    /** Pages worth reopening: web pages and Saved pages (offline copies); "" keeps a blank new tab in its place. */
    fun keepUrl(url: String): Boolean = url.isEmpty() || url == "about:blank" || url.startsWith("http://", true) ||
        url.startsWith("https://", true) || SavedPages.isSavedUrl(url)

    fun encode(s: Saved): String {
        var budget = MAX_TOTAL_STATE
        val tabs = s.tabs.filter { keepUrl(it.url) }.take(MAX_TABS)
        val a = JSONArray()
        tabs.forEach { t ->
            val o = JSONObject().put("u", if (t.url == "about:blank") "" else t.url).put("t", t.title.take(300))
            val size = t.state.entries.sumOf { it.key.length + it.value.length }
            if (t.state.isNotEmpty() && size <= MAX_STATE && size <= budget) {
                budget -= size
                o.put("s", JSONObject().apply { t.state.forEach { (k, v) -> put(k, v) } })
            }
            a.put(o)
        }
        // Keep the active index pointing at the same tab after filtering.
        val activeTab = s.tabs.getOrNull(s.active)
        val active = activeTab?.let { tabs.indexOf(it) }?.takeIf { it >= 0 } ?: (tabs.size - 1).coerceAtLeast(0)
        return JSONObject().put("v", VERSION).put("active", if (tabs.isEmpty()) 0 else active).put("tabs", a).toString()
    }

    fun decode(json: String?): Saved {
        if (json.isNullOrBlank()) return Saved(emptyList(), 0)
        return try {
            val o = JSONObject(json)
            val a = o.optJSONArray("tabs") ?: JSONArray()
            val tabs = (0 until minOf(a.length(), MAX_TABS)).mapNotNull { i ->
                val t = a.optJSONObject(i) ?: return@mapNotNull null
                val url = t.optString("u"); if (!keepUrl(url)) return@mapNotNull null
                val st = t.optJSONObject("s")?.let { s -> s.keys().asSequence().associateWith { s.optString(it) } } ?: emptyMap()
                Snap(url, t.optString("t"), st)
            }
            Saved(tabs, o.optInt("active", 0).coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
        } catch (_: Exception) { Saved(emptyList(), 0) }
    }

    /** Which normal tab to remember as active: the current one if it's normal, else the last normal tab. */
    fun activeIndex(isPrivate: List<Boolean>, current: Int): Int {
        val normals = isPrivate.indices.filter { !isPrivate[it] }
        if (normals.isEmpty()) return 0
        val i = normals.indexOf(current)
        return if (i >= 0) i else normals.size - 1
    }

    // ---------- file ----------
    @Volatile var dir: File? = null
    private fun file() = dir?.let { File(it, "browser_tabs.json") }

    fun write(s: Saved) {
        val f = file() ?: return
        try { val tmp = File(f.path + ".tmp"); tmp.writeText(encode(s)); if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() } } catch (_: Exception) { }
    }

    fun read(): Saved = decode(try { file()?.takeIf { it.exists() }?.readText() } catch (_: Exception) { null })
}
