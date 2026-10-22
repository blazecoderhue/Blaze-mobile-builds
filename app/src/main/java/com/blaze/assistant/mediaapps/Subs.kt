package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.StoreSubtitle
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.34.0 subtitles, the pure side (SubsTest). Blaze Video never handed YouTube's caption tracks to the player, so
 * the subtitle menu had nothing to turn on. Now:
 *  - [ytTracks] reads the caption tracks out of a YouTube player reply (manual and auto-generated, with names),
 *  - [parse] reads YouTube's json3 / srv1 XML, WebVTT and SubRip into [Cue]s (auto-caption "rolling" lines cleaned up),
 *  - [toVtt] writes one clean WebVTT file the player side-loads as a subtitle track (Media3 SubtitleConfiguration),
 *  - [pick] / [ordered] / [label] decide which track to turn on and how the menu lists them.
 * The Android side (fetching, files, the player) is in SubsLoader.kt and VideoActivity.kt.
 */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

object Subs {
    // ---------------------------------------------------------------- YouTube caption tracks

    /** The caption tracks of a YouTube player reply (captions.playerCaptionsTracklistRenderer.captionTracks). */
    fun ytTracks(player: JSONObject): List<StoreSubtitle> {
        val arr = player.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks") ?: return emptyList()
        val out = ArrayList<StoreSubtitle>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val url = t.optString("baseUrl")
            if (url.isBlank()) continue
            val lang = t.optString("languageCode")
            val auto = t.optString("kind") == "asr" || t.optString("vssId").startsWith("a.")
            val name = runs(t.optJSONObject("name")).ifBlank { lang }
            val id = "yt-" + (t.optString("vssId").ifBlank { (if (auto) "a." else ".") + lang }).replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (out.none { it.id == id }) out += StoreSubtitle(id, url.replace("\\u0026", "&"), lang, name, auto)
        }
        return out
    }

    fun ytTracks(playerJson: String): List<StoreSubtitle> = try { ytTracks(JSONObject(playerJson)) } catch (_: Exception) { emptyList() }

    private fun runs(o: JSONObject?): String = o?.optString("simpleText")?.takeIf { it.isNotBlank() }
        ?: o?.optJSONArray("runs")?.let { a -> (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()

    /** The caption url asking for [fmt] (json3 | vtt | srv1), any fmt already on it replaced. */
    fun withFormat(url: String, fmt: String): String {
        val base = url.replace(Regex("([?&])fmt=[^&]*&?"), "$1").trimEnd('&', '?')
        return base + (if ('?' in base) "&" else "?") + "fmt=" + fmt
    }

    // ---------------------------------------------------------------- parsing

    /** Any subtitle text: WebVTT, SubRip, YouTube json3 or srv1/srv3 XML. Empty when it's none of them. */
    fun parse(text: String): List<Cue> {
        val t = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trimStart()
        return when {
            t.startsWith("{") -> parseJson3(t)
            t.startsWith("WEBVTT") -> parseVtt(t)
            t.startsWith("<") -> parseXml(t)
            else -> parseSrt(t).ifEmpty { parseVtt("WEBVTT\n\n$t") }
        }
    }

    /** "00:01:02,345", "01:02.345", "1:02:03.4", "62.5" → ms; -1 when it isn't a time. */
    fun clockMs(s: String): Long {
        val t = s.trim().replace(',', '.')
        val m = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?$""").find(t)
        if (m != null) {
            val h = m.groupValues[1].toLongOrNull() ?: 0
            val mi = m.groupValues[2].toLong(); val se = m.groupValues[3].toLong()
            val frac = m.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0
            return ((h * 60 + mi) * 60 + se) * 1000 + frac
        }
        return t.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: -1
    }

    private val arrow = Regex("""^\s*(\S+)\s+-->\s+(\S+)""")

    fun parseSrt(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        for (block in text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))) {
            val lines = block.trim('\n').lines().dropWhile { it.isBlank() }
            val ti = lines.indexOfFirst { arrow.containsMatchIn(it) }
            if (ti < 0 || ti > 1) continue
            val m = arrow.find(lines[ti]) ?: continue
            val a = clockMs(m.groupValues[1]); val b = clockMs(m.groupValues[2])
            val body = clean(lines.drop(ti + 1).joinToString("\n"))
            if (a >= 0 && b > a && body.isNotBlank()) out += Cue(a, b, body)
        }
        return out.sortedBy { it.startMs }
    }

    fun parseVtt(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        for (block in text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))) {
            val lines = block.trim('\n').lines()
            val ti = lines.indexOfFirst { arrow.containsMatchIn(it) }
            if (ti < 0) continue
            if (lines.firstOrNull()?.let { it.startsWith("NOTE") || it.startsWith("STYLE") || it.startsWith("REGION") } == true) continue
            val m = arrow.find(lines[ti]) ?: continue
            val a = clockMs(m.groupValues[1]); val b = clockMs(m.groupValues[2])
            val body = clean(lines.drop(ti + 1).joinToString("\n"))
            if (a >= 0 && b > a && body.isNotBlank()) out += Cue(a, b, body)
        }
        return dedupeRolling(out.sortedBy { it.startMs })
    }

    /** YouTube json3: events[{tStartMs, dDurationMs, segs[{utf8}]}]. Overlapping auto-caption windows are clipped. */
    fun parseJson3(text: String): List<Cue> {
        val ev = try { JSONObject(text).optJSONArray("events") } catch (_: Exception) { null } ?: return emptyList()
        val raw = ArrayList<Cue>()
        for (i in 0 until ev.length()) {
            val e = ev.optJSONObject(i) ?: continue
            val segs = e.optJSONArray("segs") ?: continue
            val body = clean((0 until segs.length()).joinToString("") { segs.optJSONObject(it)?.optString("utf8").orEmpty() })
            val a = e.optLong("tStartMs", -1); val d = e.optLong("dDurationMs", 0)
            if (a >= 0 && d > 0 && body.isNotBlank()) raw += Cue(a, a + d, body)
        }
        return clip(raw.sortedBy { it.startMs })
    }

    /** srv1 (<text start="1.2" dur="3">) and srv3 (<p t="1200" d="3000">) XML. */
    fun parseXml(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        Regex("""<text\b([^>]*)>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
            val at = attrs(m.groupValues[1])
            val a = ((at["start"]?.toDoubleOrNull() ?: return@forEach) * 1000).toLong()
            val d = ((at["dur"]?.toDoubleOrNull() ?: 2.0) * 1000).toLong()
            val body = entities(clean(m.groupValues[2]))
            if (body.isNotBlank()) out += Cue(a, a + d.coerceAtLeast(1), body)
        }
        if (out.isEmpty()) Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach { m ->
            val at = attrs(m.groupValues[1])
            val a = at["t"]?.toLongOrNull() ?: return@forEach
            val d = at["d"]?.toLongOrNull() ?: 2000
            val body = clean(entities(m.groupValues[2].replace(Regex("<[^>]+>"), "")))
            if (body.isNotBlank()) out += Cue(a, a + d.coerceAtLeast(1), body)
        }
        return clip(out.sortedBy { it.startMs })
    }

    private fun attrs(s: String): Map<String, String> = Regex("""(\w+)="([^"]*)"""").findAll(s).associate { it.groupValues[1] to it.groupValues[2] }

    private fun entities(s: String) = s.replace("&amp;#39;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&amp;", "&")

    /** Tags (<c>, <i>, <00:00:01.000>, {\an8}) out, spaces tidied, empty lines dropped. */
    fun clean(s: String): String = s.replace(Regex("""<[^>]*>"""), "").replace(Regex("""\{\\[^}]*\}"""), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /** A cue never runs past the next one's start (auto-captions overlap and would stack two lines deep). */
    fun clip(cues: List<Cue>): List<Cue> = cues.mapIndexedNotNull { i, c ->
        val next = cues.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
        val end = minOf(c.endMs, next)
        if (end > c.startMs) c.copy(endMs = end) else null
    }

    /** YouTube's auto-caption VTT repeats the previous line on top of each new one; keep only what's new. */
    fun dedupeRolling(cues: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>()
        for (c in cues) {
            val prev = out.lastOrNull()
            var body = c.text
            if (prev != null) {
                val pl = prev.text.lines(); val cl = body.lines()
                if (cl.size > 1 && cl.first() == pl.last()) body = cl.drop(1).joinToString("\n")
                if (body == prev.text && c.startMs <= prev.endMs + 50) { out[out.size - 1] = prev.copy(endMs = maxOf(prev.endMs, c.endMs)); continue }
            }
            if (c.endMs - c.startMs < 30 && prev != null && prev.text == body) continue
            if (body.isNotBlank()) out += c.copy(text = body)
        }
        return clip(out)
    }

    // ---------------------------------------------------------------- writing

    fun vttClock(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d.%03d".format(t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, t % 1000)
    }

    fun toVtt(cues: List<Cue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n').append(vttClock(c.startMs)).append(" --> ").append(vttClock(c.endMs)).append('\n')
            append(c.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("\n\n")
        }
    }

    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n').append(vttClock(c.startMs).replace('.', ',')).append(" --> ").append(vttClock(c.endMs).replace('.', ',')).append('\n')
            append(c.text).append("\n\n")
        }
    }

    /** Subtitle files Blaze can load, by name. */
    fun isSubtitleFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("srt", "vtt", "webvtt")

    // ---------------------------------------------------------------- choosing

    /** "English", "English (auto-generated)", or the file's name. */
    fun label(t: StoreSubtitle): String {
        val base = t.name.ifBlank { langName(t.lang) }.ifBlank { "Subtitles" }
        return if (t.auto && !base.contains("auto", true)) "$base (auto-generated)" else base
    }

    fun langName(code: String): String = if (code.isBlank()) "" else try {
        java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { code }
    } catch (_: Exception) { code }

    /** The menu's order: the viewer's language first, then manual before auto-generated, then by name. */
    fun ordered(tracks: List<StoreSubtitle>, prefLang: String): List<StoreSubtitle> =
        tracks.sortedWith(compareBy<StoreSubtitle>({ !sameLang(it.lang, prefLang) }, { it.auto }, { label(it).lowercase() }))

    fun sameLang(a: String, b: String): Boolean = a.isNotBlank() && b.isNotBlank() &&
        a.substringBefore('-').substringBefore('_').equals(b.substringBefore('-').substringBefore('_'), true)

    /**
     * Which track to turn on by itself. [pref] is the saved choice: "" = never chosen (off), "off" = off, else a
     * language code. A manual track in that language beats an auto-generated one. Null = leave subtitles off.
     */
    fun pick(tracks: List<StoreSubtitle>, pref: String): StoreSubtitle? {
        if (pref.isBlank() || pref == "off") return null
        val same = tracks.filter { sameLang(it.lang, pref) }
        return same.firstOrNull { !it.auto } ?: same.firstOrNull()
    }

    /** Tracks worth fetching up front: the one [pick] wants plus the viewer's language, at most [max]. */
    fun prefetch(tracks: List<StoreSubtitle>, pref: String, deviceLang: String, max: Int = 3): List<StoreSubtitle> {
        val want = LinkedHashSet<StoreSubtitle>()
        pick(tracks, pref)?.let { want += it }
        ordered(tracks, deviceLang).filter { sameLang(it.lang, deviceLang) || sameLang(it.lang, "en") }.forEach { want += it }
        return want.take(max)
    }

    // ---------------------------------------------------------------- wave A 25: Translate to, audio names

    /**
     * Languages a YouTube caption track can be machine-translated into (&tlang=). YouTube's own list is longer;
     * these are the ones offered in the menu. Anything else goes through Blaze's AI translation.
     */
    val translateTo: List<Pair<String, String>> = listOf(
        "en" to "English", "fr" to "French", "pt" to "Portuguese", "es" to "Spanish", "sw" to "Swahili", "ny" to "Chichewa (Nyanja)",
        "sn" to "Shona", "zu" to "Zulu", "xh" to "Xhosa", "st" to "Sesotho", "af" to "Afrikaans", "yo" to "Yoruba", "ig" to "Igbo",
        "ha" to "Hausa", "am" to "Amharic", "so" to "Somali", "ar" to "Arabic", "hi" to "Hindi", "bn" to "Bengali", "ur" to "Urdu",
        "ta" to "Tamil", "zh-Hans" to "Chinese (Simplified)", "zh-Hant" to "Chinese (Traditional)", "ja" to "Japanese", "ko" to "Korean",
        "id" to "Indonesian", "ms" to "Malay", "fil" to "Filipino", "vi" to "Vietnamese", "th" to "Thai", "tr" to "Turkish",
        "ru" to "Russian", "uk" to "Ukrainian", "pl" to "Polish", "de" to "German", "nl" to "Dutch", "it" to "Italian",
        "sv" to "Swedish", "da" to "Danish", "fi" to "Finnish", "el" to "Greek", "cs" to "Czech", "ro" to "Romanian", "hu" to "Hungarian",
    )

    fun translateName(code: String): String = translateTo.firstOrNull { sameLang(it.first, code) && (it.first == code || '-' !in it.first) }?.second
        ?: langName(code).ifBlank { code }

    /** True when [t] is a YouTube caption track (its url can take &tlang= for a machine translation). */
    fun canTranslate(t: StoreSubtitle): Boolean = t.url.startsWith("http") && "/api/timedtext" in t.url && "tlang=" !in t.url

    /** The track YouTube translations are made from: a manual one first, else auto-generated. */
    fun translationBase(tracks: List<StoreSubtitle>): StoreSubtitle? =
        tracks.filter { canTranslate(it) }.let { l -> l.firstOrNull { !it.auto } ?: l.firstOrNull() }

    /** [base] machine-translated into [code] by YouTube, as its own track (kept and remembered like any other). */
    fun translated(base: StoreSubtitle, code: String): StoreSubtitle {
        val url = base.url.replace(Regex("([?&])tlang=[^&]*"), "").trimEnd('&', '?')
        return StoreSubtitle("yt-tr-" + code.replace(Regex("[^A-Za-z0-9-]"), "_") + "-" + base.id.removePrefix("yt-").take(20),
            url + (if ('?' in url) "&" else "?") + "tlang=" + code, code, translateName(code) + " (translated)", auto = true)
    }

    /**
     * The track to turn on by itself for a remembered language: one of the video's own ([pick]), else a YouTube
     * translation into it. Null = leave subtitles off.
     */
    fun pickOrTranslate(tracks: List<StoreSubtitle>, pref: String): StoreSubtitle? {
        pick(tracks, pref)?.let { return it }
        if (pref.isBlank() || pref == "off") return null
        val base = translationBase(tracks) ?: return null
        return translated(base, pref)
    }

    /** Caption tracks saved with a download for offline: the remembered language (own or translated) and English, at most [max]. */
    fun forDownload(tracks: List<StoreSubtitle>, pref: String, deviceLang: String, max: Int = 2): List<StoreSubtitle> {
        val out = LinkedHashMap<String, StoreSubtitle>()
        val want = pref.takeIf { it.isNotBlank() && it != "off" } ?: deviceLang
        pickOrTranslate(tracks, want)?.let { out[it.id] = it }
        (pick(tracks, "en") ?: tracks.firstOrNull { sameLang(it.lang, "en") })?.let { out.putIfAbsent(it.id, it) }
        if (out.isEmpty()) translationBase(tracks)?.let { out[it.id] = it }
        return out.values.filter { canTranslate(it) || it.url.contains("tlang=") }.take(max)
    }

    /**
     * A readable audio track name: never "und". The language's name (plus the stream's own label when it adds
     * something), "Original audio" for an unlabelled single track, "Audio 2" for one of several unlabelled ones.
     */
    fun audioName(label: String?, language: String?, index: Int, total: Int): String {
        val code = language.orEmpty().trim()
        val known = code.isNotBlank() && code.lowercase() !in setOf("und", "zxx", "mul", "mis", "unknown")
        val lang = if (known) langName(code) else ""
        val l = label.orEmpty().trim().takeIf { it.isNotBlank() && !it.equals("und", true) && !it.equals(code, true) }
        return when {
            lang.isNotBlank() && l != null && !l.contains(lang, true) -> "$lang \u00b7 $l"
            l != null -> l
            lang.isNotBlank() -> lang
            total <= 1 -> "Original audio"
            else -> "Audio ${index + 1}"
        }
    }

    /** One entry of the subtitle menu: a track the player already has, or one that loads when picked. */
    data class MenuItem(val id: String, val label: String, val lang: String, val loaded: Boolean, val selected: Boolean)

    /**
     * The menu: every text track the player reports right now ([playerTracks], live from onTracksChanged) plus the
     * YouTube / saved tracks not loaded yet. Matched by id so a loaded YouTube track isn't listed twice.
     */
    fun menu(playerTracks: List<MenuItem>, available: List<StoreSubtitle>, prefLang: String): List<MenuItem> {
        val ids = playerTracks.map { it.id }.toSet()
        val extra = ordered(available, prefLang).filter { it.id !in ids }.map { MenuItem(it.id, label(it), it.lang, false, false) }
        return playerTracks + extra
    }

    /** JSON for the saved per-video subtitle list (filesDir/mediaapps/subs/<video>/index.json). */
    fun indexJson(list: List<StoreSubtitle>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("url", it.url).put("lang", it.lang).put("name", it.name).put("auto", it.auto)) }
    }.toString()

    fun indexParse(json: String): List<StoreSubtitle> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { StoreSubtitle(it.optString("id"), it.optString("url"), it.optString("lang"), it.optString("name"), it.optBoolean("auto")) } }
            .filter { it.id.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    /** A safe folder name for a video key (content uri, stream key). */
    fun folderFor(key: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return md.take(10).joinToString("") { "%02x".format(it) }
    }
}
