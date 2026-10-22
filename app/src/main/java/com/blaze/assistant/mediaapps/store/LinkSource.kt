package com.blaze.assistant.mediaapps.store

import com.blaze.assistant.mediaapps.TimeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * v0.32.1 "Download by link" (Music and Video Stores): paste a YouTube link (watch, youtu.be, shorts, music, embed,
 * live) or a direct link to a media file, get the qualities, download it like any Store item.
 *
 * YouTube links go to the YouTube plug-in first when this build has it ([resolve]); when it's missing or fails,
 * Blaze's own small InnerTube player client tries several app clients (ANDROID_VR → ANDROID → IOS), probes the
 * stream url and hands out plain stream urls (no signature JavaScript). Direct links are read once (type and size)
 * and downloaded as they are; a web page with an og:video / og:audio file works too.
 *
 * Registered in StoreSources.all (so downloads can refresh expired links) but hidden from the source chips.
 */
object LinkSource : StoreSource {
    override val id = "link"
    override val name = "Link"
    override val kinds = setOf("audio", "video")

    /** The user-agent of the client whose stream urls were handed out last (stream urls can be tied to it). */
    @Volatile private var lastUa = LinkYt.clients.first().ua
    override val userAgent: String get() = lastUa

    override suspend fun search(kind: String, query: String, next: String): StorePage = StorePage(listOf(resolve(query, kind).first), "")

    /**
     * A pasted link → one Store item and the source that will load it. A YouTube link goes to the YouTube plug-in when
     * this build has it and it can load the video; otherwise (no plug-in, or it failed) to this source's own clients.
     */
    suspend fun resolve(link: String, kind: String): Pair<StoreItem, StoreSource> {
        val url = LinkParse.firstUrl(link) ?: throw StoreError("That doesn't look like a link. Paste a YouTube link or a link to a music or video file.")
        LinkParse.youtubeId(url)?.let { vid ->
            // v0.33.0: links always use Blaze's own clients (the plug-in's streams were refused mid-download).
            // v0.33.0: the real title and channel (oEmbed) instead of "YouTube video".
            val (t, a) = try { LinkYt.oembed(vid) } catch (_: Exception) { "" to "" }
            return StoreItem(YouTubeStoreSource.id, vid, t.ifBlank { "YouTube video" }, a, thumb = "https://i.ytimg.com/vi/$vid/mqdefault.jpg", licence = "YouTube",
                extra = mapOf("url" to url)) to YouTubeStoreSource
        }
        val name = LinkParse.fileTitle(url)
        return StoreItem(id, url, name, (try { URI(url).host } catch (_: Exception) { null }).orEmpty().removePrefix("www."), licence = "From a link",
            extra = mapOf("url" to url)) to this
    }

    override suspend fun detail(item: StoreItem, kind: String): StoreDetail = load(item.id, kind)

    override suspend fun refresh(itemId: String, kind: String): StoreDetail? = try { load(itemId, kind) } catch (_: Exception) { null }

    private suspend fun load(key: String, kind: String): StoreDetail =
        if (key.startsWith("yt:")) youtube(key.removePrefix("yt:"), kind) else direct(key)

    private suspend fun youtube(vid: String, kind: String): StoreDetail {
        val errors = ArrayList<String>()
        val visitor = try { LinkYt.visitorId() } catch (_: Exception) { "" }
        for (cl in LinkYt.clients) {
            try {
                val d = LinkParse.ytDetail(LinkYt.player(cl, vid, visitor), vid)
                val probe = LinkParse.ytChoices(kind, d).firstOrNull()?.keys?.firstOrNull()?.let { k -> d.formats.firstOrNull { it.key == k } }
                    ?: throw StoreError("${cl.name}: no downloadable stream")
                if (!LinkYt.reachable(probe.url, cl.ua, probe.contentLength)) throw StoreError("${cl.name}: stream refused")
                lastUa = cl.ua
                return d
            } catch (e: StoreError) { errors += e.message.orEmpty() }
            catch (e: Exception) { errors += "${cl.name}: ${e.message}" }
        }
        val why = errors.firstOrNull { listOf("bot", "age", "private", "unavailable", "sign in").any { w -> it.contains(w, true) } }
        throw StoreError("YouTube didn't hand out this video" + (why?.let { ": $it" } ?: ". It may be private, age-restricted, live, or blocked on this network.") +
            " Try again in a bit.")
    }

    /** v0.34.0: just the caption tracks of a YouTube video (for downloaded videos played from the library). */
    suspend fun ytCaptions(vid: String): List<StoreSubtitle> {
        val visitor = try { LinkYt.visitorId() } catch (_: Exception) { "" }
        for (cl in LinkYt.clients) {
            val tracks = try { com.blaze.assistant.mediaapps.Subs.ytTracks(LinkYt.player(cl, vid, visitor)) } catch (_: Exception) { emptyList() }
            if (tracks.isNotEmpty()) return tracks
        }
        return emptyList()
    }

    private suspend fun direct(url: String): StoreDetail = withContext(Dispatchers.IO) {
        val head = LinkYt.peek(url)
        var target = url; var mime = head.mime; var len = head.length
        if (mime.startsWith("text/html")) {
            target = LinkParse.mediaInPage(LinkYt.text(url), url)
                ?: throw StoreError("That page has no music or video file in it. Paste the file's own link, or a YouTube link.")
            val h2 = LinkYt.peek(target); mime = h2.mime; len = h2.length
        }
        val m = if (mime.startsWith("audio/") || mime.startsWith("video/")) mime.substringBefore(';').trim() else LinkParse.extMime(target)
            ?: throw StoreError("That link isn't a music or video file (it's ${mime.ifBlank { "unknown" }}).")
        val f = StoreFormat("file", target, m, contentLength = len, hasAudio = true, hasVideo = m.startsWith("video/"), name = LinkParse.fileTitle(target))
        StoreDetail(url, LinkParse.fileTitle(target), (try { URI(target).host } catch (_: Exception) { null }).orEmpty().removePrefix("www."), 0, "", listOf(f), "From a link")
    }

    override fun choices(kind: String, d: StoreDetail): List<Choice> =
        if (d.formats.none { it.key == "file" }) LinkParse.ytChoices(kind, d) else d.formats.map { f ->
            val ext = LinkParse.extOf(f.url) ?: f.container.let { if (it == "mp4" && !f.hasVideo) "m4a" else it }
            Choice("file", if (f.hasVideo) "Video file" else "Audio file", listOf(ext, TimeText.size(f.contentLength)).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
                listOf(f.key), ext, f.contentLength)
        }

    override fun defaultChoice(kind: String, choices: List<Choice>): Choice? =
        if (kind == "video") choices.firstOrNull { it.height in 1..720 } ?: choices.firstOrNull() else choices.firstOrNull()
}

/** Pure parsing for links (LinkSourceTest). */
object LinkParse {
    fun firstUrl(text: String): String? {
        val t = text.trim()
        val m = Regex("""https?://[^\s<>"']+""").find(t)?.value
            ?: Regex("""(?:www\.|m\.|music\.)?(?:youtube\.com|youtu\.be)/[^\s<>"']+""").find(t)?.value?.let { "https://$it" }
        return m?.trimEnd('.', ',', ')', ']', '!', '?')
    }

    /** The 11-character video id of any YouTube link, or null. */
    fun youtubeId(url: String): String? {
        val u = try { URI(url) } catch (_: Exception) { return null }
        val host = u.host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.").removePrefix("music.")
        val path = u.path.orEmpty()
        val ok = { s: String? -> s?.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) } }
        return when (host) {
            "youtu.be" -> ok(path.trim('/').substringBefore('/'))
            "youtube.com", "youtube-nocookie.com" -> {
                val q = u.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
                ok(q["v"]) ?: Regex("^/(?:shorts|embed|live|v|e)/([^/?#]+)").find(path)?.groupValues?.get(1)?.let(ok)
            }
            else -> null
        }
    }

    /** Where the stream probe reads: ~1.5 MB in for files over 2 MB (unknown size counts as big), else the start. */
    fun probeAt(length: Long): Long = if (length in 1..2_000_000) 0 else 1_500_000

    private val exts = mapOf("mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg", "oga" to "audio/ogg",
        "opus" to "audio/ogg", "flac" to "audio/flac", "wav" to "audio/wav", "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm",
        "mkv" to "video/x-matroska", "mov" to "video/quicktime", "3gp" to "video/3gpp")

    fun extOf(url: String): String? = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it in exts }
    fun extMime(url: String): String? = extOf(url)?.let { exts[it] }

    fun fileTitle(url: String): String {
        val last = try { java.net.URLDecoder.decode(url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/'), "UTF-8") } catch (_: Exception) { "" }
        val base = (if ('.' in last) last.substringBeforeLast('.') else last).replace('_', ' ').replace('-', ' ').trim()
        return base.ifBlank { "Download" }.take(80)
    }

    /** og:video / og:audio / <video|audio|source src> in a page, made absolute. */
    fun mediaInPage(html: String, base: String): String? {
        val pats = listOf(
            Regex("""<meta[^>]+property=["']og:(?:video|audio)(?::(?:secure_)?url)?["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:(?:video|audio)(?::(?:secure_)?url)?["']""", RegexOption.IGNORE_CASE),
            Regex("""<(?:video|audio|source)[^>]+src=["']([^"']+\.(?:mp4|m4a|mp3|webm|ogg|opus|flac|wav|m4v)[^"']*)["']""", RegexOption.IGNORE_CASE))
        for (p in pats) p.find(html)?.groupValues?.get(1)?.let { s ->
            val abs = try { URI(base).resolve(s.replace("&amp;", "&")).toString() } catch (_: Exception) { s }
            if (youtubeId(abs) == null) return abs
        }
        return null
    }

    // ---------------------------------------------------------------- YouTube player JSON

    /** v0.33.0: InnerTube search JSON → items (videos only, no live) + the next-page token. Pure (LinkSourceTest). */
    fun ytSearch(json: String, source: String): StorePage {
        val items = ArrayList<StoreItem>(); var next = ""
        fun runs(o: JSONObject?): String = o?.optString("simpleText")?.takeIf { it.isNotBlank() }
            ?: o?.optJSONArray("runs")?.let { a -> (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> {
                    val v = x.optJSONObject("videoRenderer")
                    if (v != null) {
                        val id = v.optString("videoId")
                        val len = runs(v.optJSONObject("lengthText"))
                        val live = v.optJSONArray("badges")?.toString()?.contains("LIVE", true) == true || len.isBlank()
                        if (id.isNotBlank() && !live && items.none { it.id == id }) {
                            items += StoreItem(source, id, runs(v.optJSONObject("title")),
                                runs(v.optJSONObject("ownerText")).ifBlank { runs(v.optJSONObject("longBylineText")) },
                                StoreText.parseClock(len).coerceAtLeast(0), len, "https://i.ytimg.com/vi/$id/mqdefault.jpg", "YouTube",
                                views = runs(v.optJSONObject("shortViewCountText")).ifBlank { runs(v.optJSONObject("viewCountText")) })
                        }
                        return
                    }
                    x.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }?.let { next = it }
                    x.keys().forEach { walk(x.opt(it)) }
                }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(try { JSONObject(json) } catch (_: Exception) { JSONObject() })
        return StorePage(items, next)
    }

    fun ytDetail(json: String, vid: String): StoreDetail {
        val root = try { JSONObject(json) } catch (_: Exception) { throw StoreError("YouTube answered with something that isn't a player reply") }
        val ps = root.optJSONObject("playabilityStatus")
        val st = ps?.optString("status").orEmpty()
        if (st != "OK") throw StoreError(ps?.optString("reason")?.ifBlank { null } ?: "YouTube says this video can't be played ($st)")
        val vd = root.optJSONObject("videoDetails") ?: JSONObject()
        val sd = root.optJSONObject("streamingData") ?: throw StoreError("YouTube gave no streams for this video")
        val out = ArrayList<StoreFormat>()
        for (arr in listOf(sd.optJSONArray("formats"), sd.optJSONArray("adaptiveFormats"))) {
            if (arr == null) continue
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val url = f.optString("url"); if (url.isBlank()) continue          // ciphered streams are skipped
                val mt = f.optString("mimeType")
                val mime = mt.substringBefore(';').trim()
                val codecs = Regex("codecs=\"([^\"]+)\"").find(mt)?.groupValues?.get(1).orEmpty()
                val video = mime.startsWith("video/")
                val audio = mime.startsWith("audio/") || (video && codecs.contains(','))
                out += StoreFormat(f.optInt("itag").toString(), url, mime, codecs, f.optInt("bitrate"), f.optString("contentLength").toLongOrNull() ?: 0,
                    f.optInt("height"), f.optString("qualityLabel"), audio, video, f.optInt("fps"))
            }
        }
        if (out.isEmpty()) throw StoreError("YouTube only offered protected streams for this video")
        val thumbs = vd.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: JSONArray()
        val thumb = if (thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty() else "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
        // v0.34.0: the caption tracks go with the streams, so Blaze Video can side-load them as subtitles.
        return StoreDetail("yt:$vid", vd.optString("title").ifBlank { "YouTube video" }, vd.optString("author"), vd.optString("lengthSeconds").toIntOrNull() ?: 0,
            thumb, out, "YouTube", subtitles = com.blaze.assistant.mediaapps.Subs.ytTracks(root))
    }

    /** Music: AAC (m4a) qualities then Opus, best first. Video: H.264 heights ≤ 1080p (video + best AAC, merged) and progressive files, best first. */
    fun ytChoices(kind: String, d: StoreDetail): List<Choice> {
        val len = d.lengthSec.toLong()
        val est = { f: StoreFormat -> if (f.contentLength > 0) f.contentLength else f.bitrate.toLong() * len / 8 }
        val aac = d.formats.filter { it.hasAudio && !it.hasVideo && it.mime == "audio/mp4" }.sortedByDescending { it.bitrate }
        if (kind != "video") {
            val opus = d.formats.filter { it.hasAudio && !it.hasVideo && it.mime == "audio/webm" }.sortedByDescending { it.bitrate }
            val a = aac.mapIndexed { i, f -> Choice("a" + f.key, (if (i == 0) "High" else "Smaller") + " \u00b7 AAC ${f.kbps} kbps", "m4a \u00b7 " + TimeText.size(est(f)),
                listOf(f.key), "m4a", est(f), kbps = f.kbps) }.distinctBy { it.kbps }
            val o = opus.take(1).map { Choice("o" + it.key, "Opus ${it.kbps} kbps", "opus \u00b7 " + TimeText.size(est(it)), listOf(it.key), "opus", est(it), kbps = it.kbps) }
            if (a.isEmpty() && o.isEmpty())   // no audio-only stream: take the sound out of the progressive file
                return d.formats.filter { it.muxed && it.mime == "video/mp4" }.take(1).map { Choice("p" + it.key, "Audio \u00b7 from the video", "m4a", listOf(it.key), "m4a", est(it)) }
            return a + o
        }
        val bestA = aac.firstOrNull()
        val out = ArrayList<Choice>()
        val avc = d.formats.filter { it.hasVideo && !it.hasAudio && it.mime == "video/mp4" && it.codecs.startsWith("avc1") && it.height in 1..1080 }
            .groupBy { it.height }.map { (_, v) -> v.maxBy { it.bitrate } }.sortedByDescending { it.height }
        if (bestA != null) for (v in avc) {
            val b = est(v) + est(bestA)
            out += Choice("v" + v.key, v.qualityLabel.ifBlank { "${v.height}p" }, "mp4 \u00b7 " + TimeText.size(b), listOf(v.key, bestA.key), "mp4", b, height = v.height)
        }
        d.formats.filter { it.muxed && it.mime == "video/mp4" }.sortedByDescending { it.height }.forEach { p ->
            if (out.none { it.height == p.height }) out += Choice("p" + p.key, p.qualityLabel.ifBlank { "${p.height}p" } + " \u00b7 one file",
                "mp4 \u00b7 " + TimeText.size(est(p)), listOf(p.key), "mp4", est(p), height = p.height)
        }
        return out.sortedByDescending { it.height }
    }
}

/** The network side of [LinkSource]: InnerTube clients and small HTTP helpers. */
object LinkYt {
    data class Client(val name: String, val id: Int, val version: String, val ua: String, val extra: JSONObject.() -> Unit)

    // v0.32.2: VISIONOS first: in 2026 it's the one app client whose plain https streams still download without a
    // PO token (yt-dlp's JS-less default). ANDROID_VR / ANDROID / IOS hand out urls that answer the first bytes and
    // then 403 ("The server refused the download"), so the probe now reads past the first ~1.5 MB.
    val clients = listOf(
        Client("VISIONOS", 101, "1.02", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15") {
            put("deviceMake", "Apple"); put("deviceModel", "RealityDevice17,1"); put("osName", "visionOS"); put("osVersion", "26.5.23O471") },
        Client("ANDROID_VR", 28, "1.65.10", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip") {
            put("deviceMake", "Oculus"); put("deviceModel", "Quest 3"); put("androidSdkVersion", 32); put("osName", "Android"); put("osVersion", "12L") },
        Client("ANDROID", 3, "21.26.364", "com.google.android.youtube/21.26.364 (Linux; U; Android 14) gzip") {
            put("androidSdkVersion", 34); put("osName", "Android"); put("osVersion", "14") },
        Client("IOS", 5, "21.26.4", "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)") {
            put("deviceMake", "Apple"); put("deviceModel", "iPhone16,2"); put("osName", "iPhone"); put("osVersion", "18.3.2.22D82") },
    )

    private fun ctx(c: Client) = JSONObject().put("client", JSONObject().put("clientName", c.name).put("clientVersion", c.version)
        .put("hl", "en").put("gl", "US").apply(c.extra))

    suspend fun visitorId(): String = withContext(Dispatchers.IO) {
        val c = clients.first()
        val body = post("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", JSONObject().put("context", ctx(c)).toString(), c, "")
        JSONObject(body).optJSONObject("responseContext")?.optString("visitorData").orEmpty()
    }

    /** v0.33.0: the WEB client, only for search (streams always come from [clients]). */
    val web = Client("WEB", 1, "2.20260114.08.00", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36") { }

    /** One page of YouTube search results (raw InnerTube JSON), videos only; [continuation] for the next page. */
    suspend fun search(query: String, continuation: String = ""): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("context", ctx(web))
        if (continuation.isNotBlank()) body.put("continuation", continuation) else body.put("query", query).put("params", "EgIQAQ==")
        post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body.toString(), web, "")
    }

    /** v0.34.0 playlist import: one page of a playlist (WEB browse "VL<id>", or a continuation), raw InnerTube JSON. */
    suspend fun playlist(listId: String, continuation: String = ""): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("context", ctx(web))
        if (continuation.isNotBlank()) body.put("continuation", continuation) else body.put("browseId", "VL$listId")
        post("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body.toString(), web, "")
    }

    /** Title and channel of a video (public oEmbed, no client needed). */
    suspend fun oembed(vid: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val o = JSONObject(text("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode("https://www.youtube.com/watch?v=$vid", "UTF-8")))
        o.optString("title") to o.optString("author_name")
    }

    suspend fun player(c: Client, vid: String, visitor: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("context", ctx(c)).put("videoId", vid).put("contentCheckOk", true).put("racyCheckOk", true)
        post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body.toString(), c, visitor)
    }

    private fun post(url: String, body: String, c: Client, visitor: String): String {
        val h = URL(url).openConnection() as HttpURLConnection
        try {
            h.requestMethod = "POST"; h.doOutput = true; h.connectTimeout = 15_000; h.readTimeout = 20_000
            h.setRequestProperty("Content-Type", "application/json")
            h.setRequestProperty("User-Agent", c.ua)
            h.setRequestProperty("X-YouTube-Client-Name", c.id.toString())
            h.setRequestProperty("X-YouTube-Client-Version", c.version)
            h.setRequestProperty("Origin", "https://www.youtube.com")
            if (visitor.isNotBlank()) h.setRequestProperty("X-Goog-Visitor-Id", visitor)
            h.outputStream.use { it.write(body.toByteArray()) }
            val code = h.responseCode
            val text = (if (code in 200..299) h.inputStream else h.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw StoreError("${c.name}: YouTube answered $code" + if (text.contains("Sorry", true)) " (bot check)" else "")
            return text
        } finally { h.disconnect() }
    }

    /**
     * True when a stream url really downloads. v0.32.2: reads 1 KB at ~1.5 MB in (or the start of a small file):
     * urls without a PO token answer the first bytes and refuse later ranges, which is what broke downloads.
     */
    suspend fun reachable(url: String, ua: String, length: Long = 0): Boolean = withContext(Dispatchers.IO) {
        try {
            val h = URL(url).openConnection() as HttpURLConnection
            try {
                h.connectTimeout = 10_000; h.readTimeout = 10_000
                val at = LinkParse.probeAt(length)
                h.setRequestProperty("User-Agent", ua); h.setRequestProperty("Range", "bytes=$at-${at + 1023}")
                h.responseCode in 200..299 && (h.inputStream.use { it.read(ByteArray(1024)) } > 0)
            } finally { h.disconnect() }
        } catch (_: Exception) { false }
    }

    data class Peek(val mime: String, val length: Long)

    private const val WEB_UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    fun peek(url: String): Peek {
        val h = URL(url).openConnection() as HttpURLConnection
        try {
            h.instanceFollowRedirects = true; h.connectTimeout = 15_000; h.readTimeout = 15_000
            h.setRequestProperty("User-Agent", WEB_UA); h.setRequestProperty("Range", "bytes=0-0")
            val code = h.responseCode
            if (code !in 200..299) throw StoreError("The link answered $code")
            val total = h.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: h.contentLengthLong.takeIf { code == 200 } ?: 0L
            return Peek(h.contentType.orEmpty().lowercase(), total.coerceAtLeast(0))
        } finally { h.disconnect() }
    }

    fun text(url: String): String {
        val h = URL(url).openConnection() as HttpURLConnection
        try {
            h.connectTimeout = 15_000; h.readTimeout = 20_000; h.setRequestProperty("User-Agent", WEB_UA)
            return h.inputStream.bufferedReader().use { it.readText().take(600_000) }
        } finally { h.disconnect() }
    }
}
