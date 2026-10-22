package com.blaze.assistant.mediaapps.store

import com.blaze.assistant.mediaapps.TimeText
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.30.1: Internet Archive (archive.org) sources, no key needed:
 *  - [ArchiveAudioSource]: audio items licensed Creative Commons or public domain (advancedsearch filter on licenseurl).
 *  - [ArchiveFilmsSource]: the Feature Films collection (public-domain films).
 *  - [BlenderMoviesSource]: the Blender open movies (CC BY) as archive.org hosts them.
 * Search: https://archive.org/advancedsearch.php, files: https://archive.org/metadata/{id}, picture:
 * https://archive.org/services/img/{id}, download: https://archive.org/download/{id}/{file}. Parsing is pure (ArchiveParse).
 */
object ArchiveParse {
    fun thumb(id: String) = "https://archive.org/services/img/$id"
    fun fileUrl(id: String, name: String) = "https://archive.org/download/$id/" + name.split('/').joinToString("/") { StoreHttp.enc(it).replace("+", "%20") }

    /** "14:48" / "00:03:12" / "3237.5" / "3 minutes" → seconds (0 when unknown). */
    fun seconds(v: String): Int {
        val s = v.trim()
        if (s.isEmpty()) return 0
        TimeText.parseClock(s).takeIf { it > 0 }?.let { return it }
        s.toDoubleOrNull()?.let { return it.toInt() }
        Regex("""(\d+)\s*min""").find(s)?.let { return it.groupValues[1].toInt() * 60 }
        return 0
    }

    private fun str(o: JSONObject, k: String): String = when (val v = o.opt(k)) {
        is JSONArray -> if (v.length() > 0) v.optString(0) else ""
        null -> ""
        else -> v.toString()
    }

    /** advancedsearch JSON → items. [defaultLicence] labels items without a licence url (Feature Films). */
    fun search(json: String, source: String, page: Int, rows: Int, square: Boolean, defaultLicence: String = "", requireFree: Boolean = true): StorePage {
        val r = JSONObject(json).optJSONObject("response") ?: return StorePage(emptyList(), "")
        val docs = r.optJSONArray("docs") ?: JSONArray()
        val out = ArrayList<StoreItem>()
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val id = str(d, "identifier"); if (id.isEmpty()) continue
            val lic = str(d, "licenseurl")
            if (requireFree && !Licence.isFree(lic)) continue
            val sec = seconds(str(d, "runtime"))
            out += StoreItem(source, id, str(d, "title").ifBlank { id }, str(d, "creator"), sec, if (sec > 0) TimeText.clock(sec * 1000L) else "",
                thumb(id), Licence.label(lic).ifBlank { defaultLicence }, lic, str(d, "downloads").toLongOrNull()?.let { "%,d downloads".format(it) } ?: "", square)
        }
        val found = r.optInt("numFound")
        return StorePage(out, if (page * rows < found) (page + 1).toString() else "")
    }

    private val audioExt = mapOf("mp3" to "audio/mpeg", "ogg" to "audio/ogg", "flac" to "audio/flac", "m4a" to "audio/mp4", "opus" to "audio/ogg")
    private val pref = listOf("vbr mp3", "320kbps mp3", "256kbps mp3", "192kbps mp3", "128kbps mp3", "mp3", "ogg vorbis", "flac", "96kbps mp3", "64kbps mp3")

    /** metadata JSON → the item's playable audio or video files. */
    fun detail(json: String, kind: String, defaultLicence: String = ""): StoreDetail {
        val root = JSONObject(json)
        val md = root.optJSONObject("metadata") ?: JSONObject()
        val id = str(md, "identifier")
        val files = root.optJSONArray("files") ?: JSONArray()
        val out = ArrayList<StoreFormat>()
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name"); val fmt = f.optString("format").lowercase()
            val ext = name.substringAfterLast('.', "").lowercase()
            val size = f.optString("size").toLongOrNull() ?: 0
            val len = seconds(f.optString("length"))
            if (kind == "audio") {
                val mime = audioExt[ext] ?: continue
                if ("spectrogram" in fmt || "image" in fmt) continue
                out += StoreFormat(name, fileUrl(id, name), mime, codecs = fmt, bitrate = if (len > 0 && size > 0) (size * 8 / len).toInt() else 0,
                    contentLength = size, name = f.optString("title").ifBlank { name.substringAfterLast('/').substringBeforeLast('.') })
            } else {
                if (ext != "mp4" && ext != "m4v") continue
                if (!("mpeg4" in fmt || "h.264" in fmt || "mp4" in fmt)) continue
                out += StoreFormat(name, fileUrl(id, name), "video/mp4", codecs = fmt, contentLength = size, height = f.optString("height").toIntOrNull() ?: 0,
                    hasAudio = true, hasVideo = true, name = name.substringAfterLast('/').substringBeforeLast('.'))
            }
        }
        val lic = str(md, "licenseurl")
        val length = seconds(str(md, "runtime")).takeIf { it > 0 }
            ?: files.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.maxOfOrNull { seconds(it.optString("length")) } } ?: 0
        return StoreDetail(id, str(md, "title").ifBlank { id }, str(md, "creator"), length, thumb(id), out,
            Licence.label(lic).ifBlank { defaultLicence }, lic)
    }

    fun audioChoices(d: StoreDetail): List<Choice> {
        fun rank(f: StoreFormat) = pref.indexOf(f.codecs).let { if (it < 0) pref.size else it }
        return d.formats.filter { !it.hasVideo }.sortedWith(compareBy<StoreFormat> { it.key.substringBeforeLast('.') }.thenBy { rank(it) }).take(60).map { f ->
            val fmt = when (f.container) { "mp3" -> "MP3"; "ogg" -> "Ogg"; "flac" -> "FLAC"; "mp4" -> "M4A"; else -> f.container.uppercase() }
            val q = if (f.kbps > 0 && f.container != "flac") " ${f.kbps} kbps" else ""
            Choice("f:" + f.key, f.name.ifBlank { d.title }, listOf(fmt + q, TimeText.size(f.contentLength)).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
                listOf(f.key), when (f.container) { "mpeg" -> "mp3"; "mp4" -> "m4a"; else -> f.container }, f.contentLength, kbps = f.kbps, title = f.name)
        }
    }

    fun videoChoices(d: StoreDetail): List<Choice> =
        d.formats.filter { it.hasVideo }.sortedWith(compareBy<StoreFormat> { it.height }.thenBy { it.contentLength }).map { f ->
            val h = if (f.height > 0) "${f.height}p" else "MP4"
            Choice("f:" + f.key, h + (if (d.formats.count { it.hasVideo } > 1 && f.name != d.title) " \u00b7 " + f.name.take(28) else ""),
                listOf("mp4", TimeText.size(f.contentLength)).filter { it.isNotBlank() }.joinToString(" \u00b7 "), listOf(f.key), "mp4", f.contentLength, height = f.height)
        }

    /** Default film pick: the biggest at or under 720p (archive.org's originals can be several GB). */
    fun defaultVideo(c: List<Choice>): Choice? = c.filter { it.height in 1..720 }.maxByOrNull { it.height } ?: c.minByOrNull { it.bytes }
}

abstract class ArchiveSource : StoreSource {
    protected val rows = 30
    abstract fun query(user: String): String
    protected open val sort = "downloads desc"
    protected open val defaultLicence = ""
    protected open val requireFree = true
    protected open val square = false
    override val browsable get() = true

    override suspend fun search(kind: String, query: String, next: String): StorePage {
        val page = next.toIntOrNull() ?: 1
        val url = "https://archive.org/advancedsearch.php?q=" + StoreHttp.enc(query(query.trim())) +
            listOf("identifier", "title", "creator", "licenseurl", "runtime", "downloads").joinToString("") { "&fl%5B%5D=$it" } +
            "&sort%5B%5D=" + StoreHttp.enc(sort) + "&rows=$rows&page=$page&output=json"
        return ArchiveParse.search(StoreHttp.get(url), id, page, rows, square, defaultLicence, requireFree)
    }

    override suspend fun detail(item: StoreItem, kind: String): StoreDetail =
        ArchiveParse.detail(StoreHttp.get("https://archive.org/metadata/" + StoreHttp.enc(item.id)), kind, defaultLicence).let {
            if (it.licence.isBlank()) it.copy(licence = item.licence, licenceUrl = item.licenceUrl) else it
        }

    override fun choices(kind: String, d: StoreDetail) = if (kind == "video") ArchiveParse.videoChoices(d) else ArchiveParse.audioChoices(d)
    override fun defaultChoice(kind: String, choices: List<Choice>) = if (kind == "video") ArchiveParse.defaultVideo(choices) else choices.firstOrNull()

    protected fun words(q: String) = q.replace(Regex("""[():"\\\[\]{}^~*?]"""), " ").replace(Regex("\\s+"), " ").trim()
}

object ArchiveAudioSource : ArchiveSource() {
    override val id = "archive-audio"
    override val name = "Internet Archive"
    override val kinds = setOf("audio")
    override val square = true
    override fun query(user: String): String {
        val w = words(user)
        return (if (w.isEmpty()) "" else "($w) AND ") + "mediatype:audio AND licenseurl:(*creativecommons.org* OR *publicdomain*)"
    }
}

object ArchiveFilmsSource : ArchiveSource() {
    override val id = "archive-films"
    override val name = "Public-domain films"
    override val kinds = setOf("video")
    override val defaultLicence = "Public domain"
    override val requireFree = false   // the Feature Films collection is the public-domain set; most items carry no licence url
    override fun query(user: String): String {
        val w = words(user)
        return (if (w.isEmpty()) "" else "($w) AND ") + "collection:feature_films AND mediatype:movies"
    }
}

object BlenderMoviesSource : ArchiveSource() {
    override val id = "blender"
    override val name = "Blender open movies"
    override val kinds = setOf("video")
    /** The Blender Studio open movies on archive.org (each item carries its CC licence). */
    val ids = listOf("BigBuckBunny_124", "ElephantsDream", "Sintel", "Tears-of-Steel", "CosmosLaundromatFirstCycle",
        "spring_blenderopenmovie", "agent327operationbarbershop", "sprite-fright", "Caminandes1LlamaDrama", "Caminandes2GranDillama", "CaminandesLlamigos")
    override fun query(user: String): String {
        val w = words(user)
        return "identifier:(" + ids.joinToString(" OR ") + ")" + if (w.isEmpty()) "" else " AND ($w)"
    }
}
