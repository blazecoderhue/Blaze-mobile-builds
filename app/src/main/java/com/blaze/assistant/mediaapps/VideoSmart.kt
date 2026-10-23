package com.blaze.assistant.mediaapps

import org.json.JSONObject

/**
 * v0.34.0 Blaze Video, the pure rules (VideoSmartTest): what a stream costs in data before it plays, episodes grouped
 * into series, a learned "Skip intro" per series, what plays next, which watched downloads smart downloads clears,
 * and the text for a recap and for AI subtitle translation.
 */
object VideoSmart {
    // ---------------------------------------------------------------- data cost

    /** Bytes a stream of [lengthSec] costs: the file size when known, else bitrate × length. */
    fun bytes(fileBytes: Long, bitrate: Long, lengthSec: Int): Long = if (fileBytes > 0) fileBytes else (bitrate.coerceAtLeast(0) * lengthSec.coerceAtLeast(0) / 8)

    /** "≈ 245 MB to watch", with a mobile-data warning when [metered]. "" when nothing is known. */
    fun costLine(bytes: Long, metered: Boolean, kind: String): String {
        if (bytes <= 0) return ""
        val verb = if (kind == "video") "to watch" else "to listen"
        val mb = bytes / 1_000_000.0
        val size = if (mb >= 1000) "%.1f GB".format(mb / 1000) else if (mb >= 10) "%d MB".format(mb.toLong()) else "%.1f MB".format(mb)
        return "\u2248 $size $verb" + if (metered) " \u00b7 you're on mobile data" else " \u00b7 on Wi-Fi"
    }

    /**
     * The streams to play now. With data saver on mobile data: music takes its smallest stream, video its smallest
     * picture of at least 240p (else the smallest); otherwise the source's own default.
     */
    fun streamFor(kind: String, d: com.blaze.assistant.mediaapps.store.StoreDetail, src: com.blaze.assistant.mediaapps.store.StoreSource, saving: Boolean): List<com.blaze.assistant.mediaapps.store.StoreFormat> {
        if (!saving) return src.streamPick(kind, d)
        val ch = src.choices(kind, d)
        val pick = saverChoice(kind, ch) ?: return src.streamPick(kind, d)
        return pick.keys.mapNotNull { k -> d.formats.firstOrNull { it.key == k } }.ifEmpty { src.streamPick(kind, d) }
    }

    /** 0.45.20 step-down: the best choice at or under [cap] px tall (null when no cap or nothing fits). */
    fun capped(kind: String, d: com.blaze.assistant.mediaapps.store.StoreDetail, src: com.blaze.assistant.mediaapps.store.StoreSource, cap: Int): List<com.blaze.assistant.mediaapps.store.StoreFormat>? {
        if (cap <= 0) return null
        val ch = src.choices(kind, d).filter { it.height in 1..cap }.maxByOrNull { it.height } ?: return null
        return ch.keys.mapNotNull { k -> d.formats.firstOrNull { it.key == k } }.ifEmpty { null }
    }

    fun saverChoice(kind: String, ch: List<com.blaze.assistant.mediaapps.store.Choice>): com.blaze.assistant.mediaapps.store.Choice? {
        val sized = ch.filter { it.bytes > 0 }
        if (kind == "video") {
            val ok = ch.filter { it.height >= 240 }
            return (ok.ifEmpty { ch }).minByOrNull { if (it.height > 0) it.height else Int.MAX_VALUE }
        }
        return sized.minByOrNull { it.bytes } ?: ch.minByOrNull { if (it.kbps > 0) it.kbps else Int.MAX_VALUE }
    }

    /** Big enough to ask first on mobile data (over 150 MB). */
    fun warnFirst(bytes: Long, metered: Boolean): Boolean = metered && bytes > 150_000_000

    // ---------------------------------------------------------------- episodes

    data class Episode(val series: String, val key: String, val season: Int, val episode: Int)

    private val patterns = listOf(
        Regex("""(?i)^(.*?)[\s._\-:|]*\bS(\d{1,2})\s*[._\-]?\s*E(\d{1,3})\b"""),
        Regex("""(?i)^(.*?)[\s._\-:|]*\b(\d{1,2})x(\d{1,3})\b"""),
        Regex("""(?i)^(.*?)[\s._\-:|]*\bSeason\s*(\d{1,2})[\s,._\-:|]*(?:Episode|Ep\.?)\s*(\d{1,3})\b"""),
    )
    private val single = Regex("""(?i)^(.*?)[\s._\-:|(\[]*\b(?:Episode|Ep\.?|Part|Pt\.?|Chapter|#)\s*(\d{1,3})\b""")

    /** An episode marker in a title ("Show S01E02", "Show 1x02", "Show - Episode 3", "Show Part 2"), or null. */
    fun episode(title: String): Episode? {
        val t = title.replace('_', ' ').trim().let { if (' ' !in it && it.count { c -> c == '.' } >= 2) it.replace('.', ' ') else it }
        for (p in patterns) p.find(t)?.let { m ->
            val s = cleanSeries(m.groupValues[1]); if (s.isBlank()) return null
            return Episode(s, s.lowercase(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        single.find(t)?.let { m ->
            val s = cleanSeries(m.groupValues[1]); if (s.isBlank()) return null
            return Episode(s, s.lowercase(), 1, m.groupValues[2].toInt())
        }
        return null
    }

    private fun cleanSeries(s: String) = s.replace(Regex("""[\s._\-:|(\[]+$"""), "").replace(Regex("\\s+"), " ").trim()

    /** Series with at least two episodes, each sorted by season then episode; series by name. */
    fun series(videos: List<Track>): List<Pair<String, List<Track>>> {
        val by = LinkedHashMap<String, MutableList<Pair<Episode, Track>>>()
        for (v in videos) { val e = episode(v.title) ?: continue; by.getOrPut(e.key) { ArrayList() } += e to v }
        return by.values.filter { it.size >= 2 }
            .map { l -> l.first().first.series to l.sortedWith(compareBy({ it.first.season }, { it.first.episode })).map { it.second } }
            .sortedBy { it.first.lowercase() }
    }

    /** What plays after [cur]: the next episode of its series, else nothing (no surprise picks). */
    fun next(cur: Track, videos: List<Track>): Track? {
        val e = episode(cur.title) ?: return null
        val eps = series(videos).firstOrNull { it.first.lowercase() == e.key }?.second ?: return null
        val i = eps.indexOfFirst { it.key == cur.key }
        return if (i in 0 until eps.size - 1) eps[i + 1] else null
    }

    // ---------------------------------------------------------------- skip intro

    /**
     * Learns where a series' intro ends from a jump the viewer makes early in an episode: from the first 3 minutes,
     * 20 s to 150 s forward, landing before 5 minutes. Averages with what it knew. Returns the new intro end or [known].
     */
    fun learnIntro(known: Long, fromMs: Long, toMs: Long): Long {
        val jump = toMs - fromMs
        if (fromMs > 180_000 || jump !in 20_000..150_000 || toMs > 300_000) return known
        return if (known <= 0) toMs else (known + toMs) / 2
    }

    /** Show "Skip intro" from 2 s in until 3 s before the intro's end. */
    fun showSkip(posMs: Long, introEnd: Long): Boolean = introEnd > 0 && posMs in 2_000 until (introEnd - 3_000)

    fun introJson(m: Map<String, Long>): String = JSONObject(m as Map<*, *>).toString()
    fun introParse(s: String): Map<String, Long> = try { val o = JSONObject(s); o.keys().asSequence().associateWith { o.optLong(it) } } catch (_: Exception) { emptyMap() }

    // ---------------------------------------------------------------- autoplay + smart downloads

    /** Seconds left on the "Up next" countdown, or -1 when it's done. */
    fun countdown(startedAt: Long, now: Long, seconds: Int = 5): Int {
        val left = seconds - ((now - startedAt) / 1000).toInt()
        return if (left <= 0) -1 else left
    }

    /**
     * Smart downloads: downloaded episodes the viewer finished more than [graceMs] ago are cleared to free space; a
     * finished film or anything liked stays. [finished] = key → when it was watched to the end.
     */
    fun toClear(downloads: List<Track>, finished: Map<String, Long>, now: Long, graceMs: Long = 24 * 3_600_000L): List<Track> =
        downloads.filter { d -> d.kind == "video" && d.source == "download" && episode(d.title) != null &&
            (finished[d.key] ?: 0L).let { at -> at > 0 && now - at >= graceMs } }

    // ---------------------------------------------------------------- recap + translation

    /** What was said up to [posMs] (the last [maxChars] of it), for a recap. */
    fun transcript(cues: List<Cue>, posMs: Long, maxChars: Int = 12_000): String {
        val sb = StringBuilder()
        var last = ""
        for (c in cues) {
            if (c.startMs > posMs) break
            val line = c.text.replace('\n', ' ')
            if (line == last) continue
            last = line
            sb.append('[').append(StoreClock.mmss(c.startMs)).append("] ").append(line).append('\n')
        }
        return if (sb.length <= maxChars) sb.toString() else sb.substring(sb.length - maxChars).substringAfter('\n')
    }

    fun recapPrompt(title: String, transcript: String): String =
        "Here is what has been said so far in the video \u201c$title\u201d (timestamps in brackets). Write a short recap for someone " +
            "coming back to it: 3 to 6 bullet points of what happened, in order, then one line on where it left off. Plain words, " +
            "no spoilers beyond this point, no preamble.\n\n$transcript"

    /** Cues in batches of at most [maxChars] characters of text (one model call each). */
    fun batches(cues: List<Cue>, maxChars: Int = 3_000): List<List<Cue>> {
        val out = ArrayList<List<Cue>>(); var cur = ArrayList<Cue>(); var n = 0
        for (c in cues) {
            if (cur.isNotEmpty() && n + c.text.length > maxChars) { out += cur; cur = ArrayList(); n = 0 }
            cur += c; n += c.text.length + 6
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    fun translatePrompt(batch: List<Cue>, language: String): String = buildString {
        append("Translate these subtitle lines into ").append(language).append(". Keep each line's number and the format ")
        append("\"number|translation\", one per line, same count, nothing else. Keep names, keep it short enough to read as subtitles.\n\n")
        batch.forEachIndexed { i, c -> append(i + 1).append('|').append(c.text.replace('\n', ' ')).append('\n') }
    }

    /** The model's "n|text" lines back onto [batch]; a line it skipped keeps the original text. */
    fun applyTranslation(batch: List<Cue>, reply: String): List<Cue> {
        val map = HashMap<Int, String>()
        for (line in reply.lines()) {
            val m = Regex("""^\s*(\d+)\s*[|:.)\-]\s*(.+)$""").find(line) ?: continue
            map[m.groupValues[1].toInt()] = m.groupValues[2].trim()
        }
        return batch.mapIndexed { i, c -> map[i + 1]?.takeIf { it.isNotBlank() }?.let { c.copy(text = it) } ?: c }
    }

    /** How much of a translation came back (to tell a failed one from a good one). */
    fun translatedShare(before: List<Cue>, after: List<Cue>): Double =
        if (before.isEmpty()) 0.0 else before.indices.count { before[it].text != after.getOrNull(it)?.text }.toDouble() / before.size
}

/** mm:ss / h:mm:ss for transcripts. */
object StoreClock {
    fun mmss(ms: Long): String {
        val t = ms.coerceAtLeast(0) / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }
}
