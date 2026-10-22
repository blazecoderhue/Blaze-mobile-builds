package com.blaze.assistant.voice.myvoice

/**
 * Splits a reply into speakable pieces for My voice, so the first piece is short (first audio starts fast)
 * and each later piece is about a sentence (the next one is made while the current one plays).
 * Pure Kotlin: tested on the JVM (SpeechChunkerTest).
 */
object SpeechChunker {
    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "st", "sr", "jr", "vs", "etc", "e.g", "i.e", "eg", "ie", "prof", "inc", "ltd", "no", "approx", "min", "max", "fig",
    )

    /**
     * @param firstMax the first piece is cut at a comma or word boundary when longer than this
     * @param max later pieces longer than this are cut at a comma/semicolon, then at a word boundary
     * @param minLen pieces shorter than this are joined to the next one (fewer, more natural model calls)
     */
    fun chunks(text: String, firstMax: Int = 90, max: Int = 180, minLen: Int = 18): List<String> {
        val clean = text.replace(Regex("[ \\t\\u00A0]+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val sentences = sentences(clean).flatMap { splitLong(it, max) }
        // Join tiny pieces ("Okay." "Sure.") onto the next one.
        val merged = ArrayList<String>()
        var carry = ""
        for (s in sentences) {
            val joined = if (carry.isEmpty()) s else "$carry $s"
            if (joined.length < minLen) { carry = joined; continue }
            merged += joined; carry = ""
        }
        if (carry.isNotEmpty()) { if (merged.isEmpty()) merged += carry else merged[merged.size - 1] = merged.last() + " " + carry }
        // Keep the first piece short so the voice starts quickly.
        if (merged.isNotEmpty() && merged[0].length > firstMax) {
            val first = merged.removeAt(0)
            val (a, b) = cutOnce(first, firstMax)
            merged.add(0, b); merged.add(0, a)
        }
        return merged.map { it.trim() }.filter { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
    }

    /** Sentence split on . ! ? … and line breaks, keeping "Dr. Smith", "e.g. this" and "3.5" together. */
    internal fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r') {
                if (sb.isNotBlank()) out += sb.toString().trim()
                sb.setLength(0); i++; continue
            }
            sb.append(ch)
            if (ch == '.' || ch == '!' || ch == '?' || ch == '…') {
                // swallow closing quotes/brackets and repeated marks
                while (i + 1 < text.length && text[i + 1] in ".!?…\"'”’)]") { i++; sb.append(text[i]) }
                val atEnd = i + 1 >= text.length
                val spaceNext = !atEnd && text[i + 1] == ' '
                if (atEnd || (spaceNext && !isAbbreviation(sb, ch))) {
                    if (sb.isNotBlank()) out += sb.toString().trim()
                    sb.setLength(0)
                }
            }
            i++
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out
    }

    private fun isAbbreviation(sb: StringBuilder, mark: Char): Boolean {
        if (mark != '.') return false
        val s = sb.toString().trimEnd('.', '"', '\'', ')')
        val word = s.substringAfterLast(' ').lowercase()
        if (word in abbreviations) return true
        // Single initials ("J. R. R. Tolkien")
        return word.length == 1 && word[0].isLetter()
    }

    private fun splitLong(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val out = ArrayList<String>()
        var rest = s
        while (rest.length > max) {
            val (a, b) = cutOnce(rest, max)
            out += a; rest = b
        }
        if (rest.isNotBlank()) out += rest
        return out
    }

    /** Cut [s] once, at or before [limit]: prefer a comma/semicolon/colon/dash, else a space. */
    internal fun cutOnce(s: String, limit: Int): Pair<String, String> {
        val window = s.take(limit + 1)
        val minCut = (limit / 4).coerceAtLeast(8)
        var cut = -1
        for (j in window.length - 1 downTo minCut) {
            val c = window[j]
            if ((c == ',' || c == ';' || c == ':' || c == '—' || c == '–') && j + 1 < s.length && s[j + 1] == ' ') { cut = j + 1; break }
        }
        if (cut < 0) cut = window.lastIndexOf(' ').takeIf { it >= minCut } ?: -1
        if (cut < 0) cut = limit.coerceAtMost(s.length)
        return s.substring(0, cut).trim() to s.substring(cut).trim()
    }
}
