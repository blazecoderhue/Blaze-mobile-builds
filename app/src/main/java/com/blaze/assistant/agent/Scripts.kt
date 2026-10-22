package com.blaze.assistant.agent

import java.text.BreakIterator

/**
 * v0.45.7 local languages: text helpers that never break a letter apart.
 *
 * Yorùbá writes tone marks as combining accents (ẹ́ = e + U+0323 + U+0301), Amharic uses Ge'ez syllables, emoji
 * are surrogate pairs joined by ZWJ. Cutting such text at a fixed char index (`take(n)`, fade spans at n-4…) can
 * split one visible letter into two halves. Everything here works on grapheme clusters instead.
 *
 * [interCovers] is the exact character set of the bundled Inter fonts (res/font, a 228-character Latin subset).
 * Android draws a missing character from a system font anyway (Roboto / Noto, so no tofu), but a reply mixing two
 * fonts letter by letter looks broken, so text that needs a missing letter is drawn wholly in the system font.
 */
object Scripts {
    /** Code point ranges of the bundled Inter subset (all six files share them; read from their cmap tables). */
    private val INTER = intArrayOf(
        0x0020, 0x007E, 0x00A0, 0x00AC, 0x00AE, 0x00FF, 0x0131, 0x0131, 0x0152, 0x0153, 0x02BB, 0x02BC,
        0x02C6, 0x02C6, 0x02DA, 0x02DA, 0x02DC, 0x02DC, 0x0300, 0x0301, 0x0303, 0x0304, 0x0308, 0x0308,
        0x0323, 0x0323, 0x2002, 0x2002, 0x2009, 0x2009, 0x200B, 0x200B, 0x2013, 0x2014, 0x2018, 0x201A,
        0x201C, 0x201E, 0x2022, 0x2022, 0x2026, 0x2026, 0x2032, 0x2033, 0x2039, 0x203A, 0x2044, 0x2044,
        0x20AC, 0x20AC, 0x2122, 0x2122, 0x2191, 0x2191, 0x2193, 0x2193, 0x2212, 0x2212, 0xFEFF, 0xFEFF,
    )

    fun interCovers(cp: Int): Boolean {
        var i = 0
        while (i < INTER.size) { if (cp >= INTER[i] && cp <= INTER[i + 1]) return true; i += 2 }
        return false
    }

    /** Emoji and their joiners/selectors: always drawn by the emoji font, whatever the text font is. */
    fun emojiPart(cp: Int): Boolean = cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0xFE00..0xFE0F ||
        cp == 0x200D || cp == 0x20E3 || cp in 0xE0020..0xE007F || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF || cp == 0x00A9 || cp == 0x00AE

    /** True when [text] has a letter or mark the bundled Inter lacks (Hausa ɓ ɗ ƙ ƴ, Yorùbá ẹ ọ ṣ, Ge'ez, Arabic, ŵ…). */
    fun needsSystemFont(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!interCovers(cp) && !emojiPart(cp) && !Character.isWhitespace(cp) && !Character.isISOControl(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** Every grapheme cluster boundary of [text] (0 and length included). */
    fun boundaries(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf(0)
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = ArrayList<Int>()
        var b = it.first()
        while (b != BreakIterator.DONE) { out += b; b = it.next() }
        return out.toIntArray()
    }

    /** The nearest cluster boundary at or before [index] (never inside a letter, a tone mark sequence or an emoji). */
    fun safeCut(text: String, index: Int): Int {
        if (index <= 0) return 0
        if (index >= text.length) return text.length
        // Fast path: plain ASCII on both sides is always a boundary (except CR LF).
        val a = text[index - 1]; val b = text[index]
        if (a.code < 0x80 && b.code < 0x80 && !(a == '\r' && b == '\n')) return index
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        return if (it.isBoundary(index)) index else it.preceding(index).coerceAtLeast(0)
    }

    /** [text] cut to at most [max] chars on a cluster boundary, with "…" when something was cut. */
    fun clip(text: String, max: Int, ellipsis: String = "\u2026"): String {
        if (text.length <= max) return text
        val room = (max - ellipsis.length).coerceAtLeast(0)
        return text.substring(0, safeCut(text, room)).trimEnd() + ellipsis
    }

    /**
     * The streaming fade's spans: [chars] characters at the end in steps of [step], newest last, each snapped to cluster
     * boundaries so one letter never gets two alphas. Pairs of (start, end), end exclusive, from the end backwards.
     */
    fun fadeSpans(text: String, step: Int, chars: Int): List<Pair<Int, Int>> {
        val n = text.length
        val out = ArrayList<Pair<Int, Int>>()
        var end = n
        var k = 0
        while (k * step < chars && end > 0) {
            val start = safeCut(text, (n - (k + 1) * step).coerceAtLeast(0))
            if (start < end) { out += start to end; end = start }
            k++
        }
        return out
    }
}
