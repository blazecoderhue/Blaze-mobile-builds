package com.blaze.assistant.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.29.7 "Save page for offline": the page as a web archive (WebView.saveWebArchive, .mht: HTML, pictures and
 * styles in one file) plus a readable text copy, in `filesDir/saved_pages/`, listed in the browser's Saved pages.
 * They open offline in a tab. The index is pure JSON so it's tested on the JVM.
 */
object SavedPages {
    data class Page(val id: String, val title: String, val url: String, val saved: Long, val bytes: Long, val hasText: Boolean)

    const val DIR = "saved_pages"

    @Volatile var root: File? = null
    fun dir(): File? = root?.let { File(it, DIR) }
    private fun indexFile() = dir()?.let { File(it, "index.json") }
    private val lock = Any()

    // ---------- pure ----------
    fun encode(pages: List<Page>): String = JSONObject().put("v", 1).put("pages", JSONArray().apply {
        pages.forEach { put(JSONObject().put("id", it.id).put("t", it.title).put("u", it.url).put("at", it.saved).put("b", it.bytes).put("txt", it.hasText)) }
    }).toString()

    fun decode(json: String?): List<Page> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONObject(json).optJSONArray("pages") ?: return emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { validId(it.optString("id")) }.map {
                Page(it.optString("id"), it.optString("t"), it.optString("u"), it.optLong("at"), it.optLong("b"), it.optBoolean("txt"))
            }.sortedByDescending { it.saved }
        } catch (_: Exception) { emptyList() }
    }

    /** Ids are generated here (time + counter, [a-z0-9]); anything else is refused so a bad index can't point outside the folder. */
    fun validId(id: String) = id.matches(Regex("^[a-z0-9]{4,40}$"))

    fun newId(now: Long, existing: Collection<String>): String {
        var n = 0; var id: String
        do { id = java.lang.Long.toString(now, 36) + (if (n == 0) "" else java.lang.Integer.toString(n, 36)); n++ } while (id in existing)
        return id
    }

    fun archiveName(id: String) = "$id.mht"
    fun textName(id: String) = "$id.txt"

    /** True for a file:// URL of one of our archives ("/…/saved_pages/abc.mht"). */
    fun isSavedUrl(url: String): Boolean = !url.contains("/../") && !url.contains("/./") && !url.contains('%') &&
        Regex("^file:///.*/$DIR/[a-z0-9]{4,40}\\.mht$").matches(url)

    /** v0.45.0, pure: may a browser tab showing [url] read files (only Blaze's own saved-page archives). */
    fun fileAccessFor(url: String?): Boolean = !url.isNullOrBlank() && isSavedUrl(url)

    fun idOfUrl(url: String): String? = if (isSavedUrl(url)) url.substringAfterLast('/').removeSuffix(".mht") else null

    /** "12 KB", "3.4 MB". */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    /** Text copy: title, address, when, then the page's readable text. */
    fun textCopy(title: String, url: String, whenText: String, body: String): String =
        "$title\n$url\nSaved $whenText\n\n" + body.replace(Regex("\n{3,}"), "\n\n").trim() + "\n"

    // ---------- files ----------
    fun list(): List<Page> = synchronized(lock) { decode(try { indexFile()?.takeIf { it.exists() }?.readText() } catch (_: Exception) { null }) }
        .filter { p -> dir()?.let { File(it, archiveName(p.id)).exists() } ?: false }

    private fun writeIndex(pages: List<Page>) = synchronized(lock) {
        try { indexFile()?.let { it.parentFile?.mkdirs(); it.writeText(encode(pages)) } } catch (_: Exception) { }
    }

    fun add(p: Page) = synchronized(lock) { writeIndex(listOf(p) + list().filter { it.id != p.id }) }

    fun delete(id: String) = synchronized(lock) {
        if (!validId(id)) return@synchronized
        dir()?.let { File(it, archiveName(id)).delete(); File(it, textName(id)).delete() }
        writeIndex(list().filter { it.id != id })
    }

    fun archive(id: String): File? = if (validId(id)) dir()?.let { File(it, archiveName(id)) } else null
    fun text(id: String): String? = if (!validId(id)) null else try { dir()?.let { File(it, textName(id)) }?.takeIf { it.exists() }?.readText() } catch (_: Exception) { null }
}
