package com.blaze.assistant.mediaapps

import kotlin.math.max
import kotlin.math.min

/**
 * v0.30.1: small pure helpers for the now-playing screen (ExtrasTest): artwork colours and synced lyrics (LRC).
 */
object ArtColors {
    /**
     * A colour that represents a cover: the average of its more colourful pixels (greys and near-black/white count
     * less), as 0xRRGGBB. Falls back to Blaze blue for a blank picture.
     */
    fun dominant(pixels: IntArray): Int {
        var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            val pr = (p shr 16) and 0xff; val pg = (p shr 8) and 0xff; val pb = p and 0xff
            val mx = max(pr, max(pg, pb)); val mn = min(pr, min(pg, pb))
            val sat = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            val light = (mx + mn) / 510.0
            val weight = 0.15 + sat * sat * 3 + (if (light in 0.15..0.85) 0.5 else 0.0)
            r += pr * weight; g += pg * weight; b += pb * weight; w += weight
        }
        if (w <= 0) return 0x4C7DFF
        return ((r / w).toInt() shl 16) or ((g / w).toInt() shl 8) or (b / w).toInt()
    }

    /** The colour scaled to a dark background shade (keeps white text readable): channels × [f]. */
    fun shade(rgb: Int, f: Float = 0.42f): Int {
        val r = (((rgb shr 16) and 0xff) * f).toInt(); val g = (((rgb shr 8) and 0xff) * f).toInt(); val b = ((rgb and 0xff) * f).toInt()
        return (r shl 16) or (g shl 8) or b
    }
}

data class LyricLine(val ms: Long, val text: String)

object Lrc {
    private val tag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** "[01:02.50]Hello" → (62500, "Hello"); lines with several time tags repeat; sorted by time. Plain text → empty. */
    fun parse(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.lines()) {
            val tags = tag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val words = raw.substring(tags.last().range.last + 1).trim()
            for (t in tags) {
                val m = t.groupValues[1].toLong(); val s = t.groupValues[2].toLong()
                val frac = t.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                out += LyricLine(m * 60_000 + s * 1000 + ms, words)
            }
        }
        return out.sortedBy { it.ms }
    }

    /** The line being sung at [posMs] (-1 before the first). */
    fun current(lines: List<LyricLine>, posMs: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) { val mid = (lo + hi) / 2; if (lines[mid].ms <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
        return ans
    }

    /** A title cleaned for a lyrics lookup: "Song (Official Video) [HD]" → "Song"; "Artist - Song" → "Song". */
    fun cleanTitle(title: String, artist: String): String {
        var t = title.replace(Regex("""\s*[(\[][^)\]]*(official|video|audio|lyrics?|hd|4k|remaster(ed)?|visuali[sz]er)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        if (artist.isNotBlank() && t.lowercase().startsWith(artist.lowercase() + " - ")) t = t.substring(artist.length + 3)
        else if (" - " in t) t = t.substringAfter(" - ")
        return t.trim()
    }
}
