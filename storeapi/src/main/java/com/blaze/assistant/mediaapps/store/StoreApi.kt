package com.blaze.assistant.mediaapps.store


/**
 * v0.30.1: the Store tabs of Blaze Music and Blaze Video show results from several sources behind one interface,
 * [StoreSource]. Every source turns a search into [StoreItem]s (picture, title, author, length, licence) and an item
 * into downloadable [StoreFormat]s / [Choice]s; the download queue (Downloads.kt) and the screens only ever see these
 * types. Registered in the app's StoreSources.all.
 *
 * This file is the :storeapi module (pure Kotlin, no Android) so a separate source module (:youtube) can implement
 * [StoreSource] without depending on the app.
 *
 * Free / legal sources: Jamendo (Creative Commons music, needs a Jamendo client_id), Internet Archive audio
 * (public-domain / CC only), Internet Archive Feature Films (public domain) and the Blender open movies (CC).
 * YouTube is an optional plug-in module (youtube/, Gradle :youtube) found at runtime; see STATUS.md "YouTube plug-in".
 */
data class StoreItem(
    val source: String,          // StoreSource.id
    val id: String,              // the source's own id (YouTube video id, archive.org identifier, Jamendo track id)
    val title: String,
    val author: String,
    val durationSec: Int = 0,
    val durationText: String = "",
    val thumb: String = "",
    val licence: String = "",    // short label shown on the item: "CC BY 4.0", "Public domain", "YouTube"
    val licenceUrl: String = "",
    val views: String = "",
    val square: Boolean = false, // album-cover style picture (1:1) instead of a 16:9 frame
    val extra: Map<String, String> = emptyMap(),
)

data class StorePage(val items: List<StoreItem>, val next: String)

/** One downloadable file / stream of an item. */
data class StoreFormat(
    val key: String,             // stable within the item (YouTube itag, archive.org file name, "mp32")
    val url: String,
    val mime: String,            // "audio/mp4", "video/mp4", …
    val codecs: String = "",
    val bitrate: Int = 0,        // bits/s
    val contentLength: Long = 0,
    val height: Int = 0,
    val qualityLabel: String = "",
    val hasAudio: Boolean = true,
    val hasVideo: Boolean = false,
    val fps: Int = 0,
    val name: String = "",       // a track title inside an album item
) {
    val container: String get() = mime.substringAfter('/').substringBefore(';').let { if (it == "mpeg") "mp3" else it }
    val muxed: Boolean get() = hasAudio && hasVideo
    val kbps: Int get() = (bitrate + 500) / 1000
}

data class StoreDetail(
    val id: String,
    val title: String,
    val author: String,
    val lengthSec: Int,
    val thumb: String,
    val formats: List<StoreFormat>,
    val licence: String = "",
    val licenceUrl: String = "",
    /** v0.34.0: subtitle / caption tracks the source offers (YouTube captions), side-loaded by the player. */
    val subtitles: List<StoreSubtitle> = emptyList(),
)

/** v0.34.0: one subtitle track a source offers: [url] gives the text (any format Subs.parse reads). */
data class StoreSubtitle(
    val id: String,              // stable within the item ("yt-.en", "yt-a.en", "file-…")
    val url: String,
    val lang: String,            // BCP-47 code, "" when unknown
    val name: String,            // "English", "English (auto-generated)", a file name
    val auto: Boolean = false,   // machine-made (YouTube auto-captions, AI translation)
)

/** One "Download" option: one stream (audio / progressive video / a track) or two (video then audio, merged). */
data class Choice(
    val id: String,
    val label: String,           // "High · AAC 128 kbps", "720p", "Track 3 · MP3"
    val detail: String,          // "m4a · 4.1 MB"
    val keys: List<String>,      // StoreFormat.key of each stream to fetch
    val ext: String,             // final file: m4a | opus | mp3 | ogg | flac | mp4 | webm
    val bytes: Long,
    val height: Int = 0,
    val kbps: Int = 0,
    val title: String = "",      // file title when it isn't the item's (one track of an album)
)

interface StoreSource {
    val id: String
    val name: String
    /** "audio" (Music Store), "video" (Video Store) or both. */
    val kinds: Set<String>
    /** Null when ready; otherwise one line on what's missing (shown instead of results). */
    fun setupNeeded(): String? = null
    /** Whether a blank search lists something (popular / the whole catalogue). */
    val browsable: Boolean get() = false
    /** User-agent for the stream urls this source hands out. */
    val userAgent: String get() = "Blaze/1 (Android)"
    suspend fun search(kind: String, query: String, next: String = ""): StorePage
    suspend fun detail(item: StoreItem, kind: String): StoreDetail
    fun choices(kind: String, d: StoreDetail): List<Choice>
    fun defaultChoice(kind: String, choices: List<Choice>): Choice? = choices.firstOrNull()
    /** Fresh urls for a download whose urls expired (null = this source's urls don't expire). */
    suspend fun refresh(itemId: String, kind: String): StoreDetail? = null
    /** Streams to play right away (Play / Watch): one url, or video + audio to merge while playing. */
    fun streamPick(kind: String, d: StoreDetail): List<StoreFormat> =
        defaultChoice(kind, choices(kind, d))?.keys?.mapNotNull { k -> d.formats.firstOrNull { it.key == k } } ?: emptyList()
}

class StoreError(msg: String, val code: Int = 0) : Exception(msg)

/** Licence urls → the short label shown on an item. Pure (StoreSourcesTest). */
object Licence {
    fun label(url: String): String {
        val u = url.trim().lowercase()
        if (u.isEmpty()) return ""
        if ("publicdomain/zero" in u) return "CC0 (public domain)"
        if ("publicdomain" in u) return "Public domain"
        val m = Regex("""creativecommons\.org/licenses/([a-z\-]+)/(\d(?:\.\d)?)""").find(u)
            ?: return if ("creativecommons" in u) "Creative Commons" else url
        return "CC " + m.groupValues[1].uppercase() + " " + m.groupValues[2]
    }

    /** Only these count as free to download and keep: public domain and Creative Commons. */
    fun isFree(url: String): Boolean { val u = url.lowercase(); return "creativecommons.org" in u || "publicdomain" in u }
}

/** Text helpers sources share (also used by the app's TimeText). Pure. */
object StoreText {
    /** 3725000 ms → "1:02:05", 65000 → "1:05". */
    fun clock(ms: Long): String {
        val t = (ms.coerceAtLeast(0) / 1000)
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** "1:02:05" / "4:13" / "45" → seconds; -1 when it isn't a clock. */
    fun parseClock(s: String): Int {
        val parts = s.trim().split(":")
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return -1
        return parts.fold(0) { acc, p -> acc * 60 + p.toInt() }
    }

    fun size(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
        else -> "%d KB".format(bytes / 1000)
    }
}
