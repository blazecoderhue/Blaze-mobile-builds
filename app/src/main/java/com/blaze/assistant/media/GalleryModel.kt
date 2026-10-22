package com.blaze.assistant.media

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * v0.30.0 Gallery: the pure part (no Android), unit tested in GalleryModelTest.
 *
 * One [Entry] per thing the Gallery shows, whether Blaze made it (filesDir/gallery, id "file:<path>") or it sits in
 * shared storage (MediaStore, id = its content:// uri): songs and videos Blaze downloaded to Music/Blaze and
 * Movies/Blaze (or any "Blaze" folder), Download/Blaze, and, when the phone allows it, Screenshots and Camera.
 */
object GalleryModel {
    /** Where an entry came from. Drives the automatic albums. */
    object Src {
        const val BLAZE = "blaze"            // made by Blaze (generated images, beats, terminal output)
        const val MUSIC = "music"            // Music/Blaze, Blaze/Music: songs Blaze downloaded
        const val VIDEOS = "videos"          // Movies/Blaze, Blaze/Videos: videos Blaze downloaded
        const val DOWNLOADS = "downloads"    // Download/Blaze
        const val PICTURES = "pictures"      // Pictures/Blaze (saved copies, Blaze Camera)
        const val SCREENSHOTS = "screenshots"
        const val CAMERA = "camera"
        const val OTHER = "other"
    }

    data class Entry(
        val id: String,          // "file:/abs/path" or "content://media/…"
        val name: String,        // display name with extension
        val title: String,       // what the tile says (Blaze's prompt title, or the name without extension)
        val kind: String,        // image | audio | video
        val src: String,         // one of [Src]
        val time: Long,          // ms since epoch (modified / added)
        val size: Long = 0,      // bytes
        val durationMs: Long = 0,
        val width: Int = 0,
        val height: Int = 0,
        val folder: String = "", // relative path ("Music/Blaze/") or "Blaze gallery"
    ) {
        val isFile get() = id.startsWith("file:")
        val path get() = if (isFile) id.removePrefix("file:") else ""
    }

    // ---------------------------------------------------------------- tabs, sorting, search

    val tabs = listOf("all" to "All", "albums" to "Albums", "images" to "Images", "music" to "Music", "videos" to "Videos")

    fun kindForTab(tab: String): String? = when (tab) { "images" -> "image"; "music" -> "audio"; "videos" -> "video"; else -> null }

    enum class Sort(val label: String) { DATE("Date"), NAME("Name"), SIZE("Size") }

    /** Date and size: biggest/newest first unless [asc]. Name: A→Z unless reversed. Ties fall back to newest, then id. */
    fun sort(list: List<Entry>, by: Sort, asc: Boolean = by == Sort.NAME): List<Entry> {
        val base: Comparator<Entry> = when (by) {
            Sort.DATE -> compareBy { it.time }
            Sort.SIZE -> compareBy { it.size }
            Sort.NAME -> Comparator { a, b -> naturalCompare(a.title.lowercase(Locale.ROOT), b.title.lowercase(Locale.ROOT)) }
        }
        val dir = if (asc) base else base.reversed()
        return list.sortedWith(dir.thenByDescending { it.time }.thenBy { it.id })
    }

    /** "track 2" before "track 10". */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0'); val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb); if (c != 0) return c
                i = ei; j = ej
            } else {
                if (ca != cb) return ca - cb
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /** Every word of [q] must appear in the title or file name (case-insensitive). */
    fun search(list: List<Entry>, q: String): List<Entry> {
        val words = q.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return list
        return list.filter { e -> val hay = (e.title + " " + e.name).lowercase(Locale.ROOT); words.all { it in hay } }
    }

    fun forTab(list: List<Entry>, tab: String): List<Entry> = kindForTab(tab)?.let { k -> list.filter { it.kind == k } } ?: list

    // ---------------------------------------------------------------- sources and automatic albums

    /** Which automatic album a MediaStore row belongs to, from its relative path ("Music/Blaze/", "DCIM/Camera/"). */
    fun sourceOf(relativePath: String, kind: String): String {
        val p = relativePath.replace('\\', '/').trim('/').lowercase(Locale.ROOT)
        val parts = p.split('/')
        return when {
            parts.any { it == "screenshots" || it == "screen recordings" || it == "screenrecorder" } -> Src.SCREENSHOTS
            parts.firstOrNull() == "dcim" && (parts.size == 1 || parts.any { it == "camera" || it == "100andro" || it == "100media" }) -> Src.CAMERA
            parts.firstOrNull() == "download" || parts.firstOrNull() == "downloads" -> if ("blaze" in parts && kind == "audio") Src.MUSIC
                else if ("blaze" in parts && kind == "video") Src.VIDEOS else Src.DOWNLOADS
            "blaze" in parts && kind == "audio" -> Src.MUSIC
            "blaze" in parts && kind == "video" -> Src.VIDEOS
            "blaze" in parts && kind == "image" -> Src.PICTURES
            parts.firstOrNull() == "music" && kind == "audio" -> Src.MUSIC
            else -> Src.OTHER
        }
    }

    data class AutoAlbum(val id: String, val name: String, val items: List<Entry>)

    /**
     * Automatic albums, in a fixed order, only when they have something (Screenshots and Camera only appear once the
     * phone lets Blaze read them). Music holds songs Blaze downloaded and beats it made; Videos the same for video.
     */
    fun autoAlbums(list: List<Entry>): List<AutoAlbum> {
        val newest = list.sortedByDescending { it.time }
        fun a(id: String, name: String, f: (Entry) -> Boolean) = AutoAlbum(id, name, newest.filter(f))
        return listOf(
            a("auto:generated", "Generated images") { it.kind == "image" && (it.src == Src.BLAZE || it.src == Src.PICTURES) },
            a("auto:music", "Music") { it.kind == "audio" && it.src != Src.DOWNLOADS },
            a("auto:videos", "Videos") { it.kind == "video" && it.src in setOf(Src.VIDEOS, Src.BLAZE, Src.PICTURES) },
            a("auto:downloads", "Downloads") { it.src == Src.DOWNLOADS },
            a("auto:screenshots", "Screenshots") { it.src == Src.SCREENSHOTS },
            a("auto:camera", "Camera") { it.src == Src.CAMERA },
        ).filter { it.items.isNotEmpty() }
    }

    /**
     * Blaze's own copy and the copy it saved to the phone (Save → Pictures/Blaze) are the same picture: keep the
     * Blaze one. Two entries match when name and size match.
     */
    fun merge(blaze: List<Entry>, phone: List<Entry>): List<Entry> {
        val mine = blaze.map { it.name.lowercase(Locale.ROOT) to it.size }.toSet()
        val seen = HashSet<String>()
        return (blaze + phone.filter { (it.name.lowercase(Locale.ROOT) to it.size) !in mine }).filter { seen.add(it.id) }
    }

    // ---------------------------------------------------------------- timeline

    /** A grid cell: a day header spanning the row, or one item. */
    sealed class Cell {
        abstract val key: String
        data class Header(val day: Long, val label: String, val count: Int) : Cell() { override val key get() = "h:$day" }
        data class Item(val entry: Entry) : Cell() { override val key get() = entry.id }
    }

    /** Midnight (local) of the day [ms] falls on, as epoch ms. */
    fun dayStart(ms: Long, zone: java.util.TimeZone): Long {
        val c = java.util.Calendar.getInstance(zone)
        c.timeInMillis = ms
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** "Today", "Yesterday", "Mon, 12 Oct" this year, "12 Oct 2025" before. */
    fun dayLabel(day: Long, now: Long, zone: java.util.TimeZone, locale: Locale = Locale.ENGLISH): String {
        val today = dayStart(now, zone)
        val d = dayStart(day, zone)
        if (d == today) return "Today"
        val y = java.util.Calendar.getInstance(zone).apply { timeInMillis = today; add(java.util.Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        if (d == y) return "Yesterday"
        val cd = java.util.Calendar.getInstance(zone).apply { timeInMillis = d }
        val cn = java.util.Calendar.getInstance(zone).apply { timeInMillis = today }
        val fmt = java.text.SimpleDateFormat(if (cd.get(java.util.Calendar.YEAR) == cn.get(java.util.Calendar.YEAR)) "EEE, d MMM" else "d MMM yyyy", locale)
        fmt.timeZone = zone
        return fmt.format(java.util.Date(d))
    }

    /**
     * The Photos-style timeline: items already sorted by date, a header before each new day. Any other sort (name,
     * size) is a flat grid with no headers, because days would repeat.
     */
    fun timeline(sorted: List<Entry>, by: Sort, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): List<Cell> {
        if (by != Sort.DATE) return sorted.map { Cell.Item(it) }
        val out = ArrayList<Cell>(sorted.size + 32)
        var i = 0
        while (i < sorted.size) {
            val day = dayStart(sorted[i].time, zone)
            var j = i
            while (j < sorted.size && dayStart(sorted[j].time, zone) == day) j++
            out += Cell.Header(day, dayLabel(day, now, zone), j - i)
            for (k in i until j) out += Cell.Item(sorted[k])
            i = j
        }
        return out
    }

    // ---------------------------------------------------------------- labels

    fun duration(ms: Long): String {
        if (ms <= 0) return ""
        val s = (ms + 500) / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    fun size(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
    }

    /** A rename keeps the extension and drops characters file systems refuse. Null when nothing usable is left. */
    fun cleanName(input: String, oldName: String): String? {
        val ext = oldName.substringAfterLast('.', "")
        var base = input.trim().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimEnd('.')
        if (ext.isNotEmpty() && base.lowercase(Locale.ROOT).endsWith("." + ext.lowercase(Locale.ROOT))) base = base.dropLast(ext.length + 1).trim()
        if (base.isEmpty() || base == "." || base == "..") return null
        base = base.take(120)
        return if (ext.isEmpty()) base else "$base.$ext"
    }
}

/**
 * Albums the user makes. A plain JSON file (filesDir/gallery_albums.json); items are Gallery [GalleryModel.Entry] ids,
 * so a song in Music/Blaze and a picture Blaze drew can sit in one album. Not thread safe: the UI calls it on main.
 */
class AlbumStore(private val file: File?) {
    data class Album(val id: String, val name: String, val items: List<String>, val created: Long)

    private val list = ArrayList<Album>()
    val albums: List<Album> get() = list.toList()

    init { load() }

    fun load() {
        list.clear()
        val f = file ?: return
        if (!f.exists()) return
        runCatching { fromJson(f.readText()) }.getOrNull()?.let { list.addAll(it) }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp"); tmp.writeText(toJson(list)); if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        }
    }

    fun get(id: String): Album? = list.firstOrNull { it.id == id }

    private fun nameTaken(name: String, except: String? = null) = list.any { it.id != except && it.name.equals(name, ignoreCase = true) }

    /** A clean, unique name: "Trip", then "Trip 2", "Trip 3"… Null for a blank name. */
    fun uniqueName(raw: String, except: String? = null): String? {
        val n = raw.trim().replace(Regex("\\s+"), " ").take(60)
        if (n.isEmpty()) return null
        if (!nameTaken(n, except)) return n
        var i = 2; while (nameTaken("$n $i", except)) i++
        return "$n $i"
    }

    fun create(name: String, items: List<String> = emptyList(), now: Long = System.currentTimeMillis()): Album? {
        val n = uniqueName(name) ?: return null
        var id = "album:$now"; var k = 1; while (get(id) != null) id = "album:$now-${k++}"
        val a = Album(id, n, items.distinct(), now)
        list.add(a); save(); return a
    }

    fun rename(id: String, name: String): Boolean {
        val i = list.indexOfFirst { it.id == id }; if (i < 0) return false
        val n = uniqueName(name, except = id) ?: return false
        list[i] = list[i].copy(name = n); save(); return true
    }

    fun delete(id: String): Boolean = list.removeAll { it.id == id }.also { if (it) save() }

    /** Adds to the end, skipping what is already there. Newest-added shows first in the UI only through sorting. */
    fun add(id: String, items: Collection<String>): Boolean {
        val i = list.indexOfFirst { it.id == id }; if (i < 0) return false
        val cur = list[i].items
        list[i] = list[i].copy(items = cur + items.filter { it !in cur }.distinct()); save(); return true
    }

    fun remove(id: String, items: Collection<String>): Boolean {
        val i = list.indexOfFirst { it.id == id }; if (i < 0) return false
        list[i] = list[i].copy(items = list[i].items.filter { it !in items }); save(); return true
    }

    /** Out of [from] (a user album) into [to]. */
    fun move(from: String, to: String, items: Collection<String>): Boolean {
        if (from == to || get(from) == null || get(to) == null) return false
        add(to, items); remove(from, items); return true
    }

    /** A file was renamed or replaced: every album that held [old] now holds [new] in the same place. */
    fun renameItem(old: String, new: String) {
        var changed = false
        for (i in list.indices) if (old in list[i].items) {
            list[i] = list[i].copy(items = list[i].items.map { if (it == old) new else it }.distinct()); changed = true
        }
        if (changed) save()
    }

    /** Forget deleted items. Call only with a complete list of what exists, or albums lose things they still hold. */
    fun prune(existing: Set<String>) {
        var changed = false
        for (i in list.indices) {
            val keep = list[i].items.filter { it in existing }
            if (keep.size != list[i].items.size) { list[i] = list[i].copy(items = keep); changed = true }
        }
        if (changed) save()
    }

    fun forget(items: Collection<String>) {
        var changed = false
        for (i in list.indices) if (list[i].items.any { it in items }) { list[i] = list[i].copy(items = list[i].items.filter { it !in items }); changed = true }
        if (changed) save()
    }

    companion object {
        fun toJson(albums: List<Album>): String = JSONObject().put("v", 1).put("albums", JSONArray().apply {
            albums.forEach { a -> put(JSONObject().put("id", a.id).put("name", a.name).put("created", a.created).put("items", JSONArray(a.items))) }
        }).toString()

        fun fromJson(s: String): List<Album> {
            val arr = JSONObject(s).optJSONArray("albums") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id"); val name = o.optString("name")
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                val its = o.optJSONArray("items")
                Album(id, name, (0 until (its?.length() ?: 0)).map { its!!.optString(it) }.filter { it.isNotBlank() }.distinct(), o.optLong("created"))
            }
        }
    }
}
