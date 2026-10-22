package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle

/**
 * Small Markdown renderer for chat replies: **bold**, *italic*, `code`, ```blocks```, # headings,
 * bullets, numbered lists, > quotes, [links](url), ~~strike~~ and --- rules. Symbols never show raw.
 */
object Md {
    // v0.31.1: groups 9-16 are inline HTML (<b>…</b>, <a href>, <span style="color">, <font color>); see HtmlLite.
    private val inline = Regex("""\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)|(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?![\w])|`([^`]+)`|~~(.+?)~~|\[([^\]]+)]\((https?://[^)\s]+)\)""" +
        """|<(b|strong|i|em|u|ins|s|del|strike|code|kbd|tt|mark|sub|sup|small|big|q|cite|var|samp|abbr)(?:\s[^<>]*)?>(.+?)</\9\s*>""" +
        """|<a\s[^<>]*?href\s*=\s*["']([^"']*)["'][^<>]*>(.+?)</a\s*>""" +
        """|<span\s[^<>]*?style\s*=\s*["'][^"']*?(?<![-\w])color\s*:\s*([^;"']+)[^"']*["'][^<>]*>(.+?)</span\s*>""" +
        """|<font\s[^<>]*?color\s*=\s*["']?([#\w(),. ]+?)["']?(?:\s[^<>]*)?>(.+?)</font\s*>""")
    // v0.31.1 perf: compiled once (they were compiled per line on every streamed frame).
    private val headingRe = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
    private val headingTightRe = Regex("^\\s{0,3}(#{2,6})(?=[^#\\s])(.*)$")
    private val ruleRe = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val bulletRe = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val numRe = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val quoteRe = Regex("^\\s*>\\s?(.*)$")
    private val tidyRes = listOf("**", "__", "~~").map { it to Regex(Regex.escape(it)) }

    /** v0.45.3: while streaming, a last line that is only a list marker ("-", "*", "3.") waits for its words instead of flashing as text. */
    fun dropLoneMarker(s: String): String {
        val i = s.lastIndexOf('\n')
        val last = s.substring(i + 1)
        return if (Regex("""^\s*(?:[-*+•]|\d{1,2}[.)])\s*$""").matches(last) && last.isNotEmpty()) s.substring(0, maxOf(i, 0)) else s
    }

    /** [live] = a reply still streaming: a tag being typed at the very end ("<str") is hidden until it closes. */
    fun render(src: String, accent: Color, codeBg: Color, live: Boolean = false): AnnotatedString = buildAnnotatedString {
        val lines = src.replace("\r", "").let { if (live) dropLoneMarker(HtmlLite.dropPartialTag(it)) else it }.split('\n')
        var inCode = false
        lines.forEachIndexed { i, raw ->
            if (raw.trimStart().startsWith("```")) { inCode = !inCode; return@forEachIndexed }
            if (inCode) {
                withStyle(SpanStyle(fontFamily = Type.Mono, fontSize = 13.sp, background = codeBg)) { append(raw) }
                if (i < lines.lastIndex) append('\n'); return@forEachIndexed
            }
            var line = tidy(raw)
            val heading = headingRe.find(line) ?: headingTightRe.find(line)
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = if (level <= 2) 17.sp else 16.sp)) {
                        inlines(heading.groupValues[2].trimEnd('#', ' '), accent, codeBg) }
                }
                ruleRe.matches(line) -> append("──────────")
                else -> {
                    val bullet = bulletRe.find(line)
                    val num = numRe.find(line)
                    val quote = quoteRe.find(line)
                    when {
                        bullet != null -> { append("  ".repeat(bullet.groupValues[1].length / 2) + "•  "); inlines(bullet.groupValues[2], accent, codeBg) }
                        num != null -> { append("  ".repeat(num.groupValues[1].length / 2) + num.groupValues[2] + ".  "); inlines(num.groupValues[3], accent, codeBg) }
                        quote != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("│ "); inlines(quote.groupValues[1], accent, codeBg) }
                        else -> inlines(line, accent, codeBg)
                    }
                }
            }
            if (i < lines.lastIndex) append('\n')
        }
    }

    private fun AnnotatedString.Builder.plain(s: String) { append(HtmlLite.decodeDisplay(HtmlLite.stripTags(s))) }

    private fun AnnotatedString.Builder.inlines(s: String, accent: Color, codeBg: Color) {
        var last = 0
        val dark = accent.isUnspecified() || accent.luminance() > 0.5f
        for (m in inline.findAll(s)) {
            plain(s.substring(last, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inlines(g[1], accent, codeBg) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inlines(g[2], accent, codeBg) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inlines(g[3], accent, codeBg) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inlines(g[4], accent, codeBg) }
                g[5].isNotEmpty() -> withStyle(SpanStyle(fontFamily = Type.Mono, fontSize = 13.sp, background = codeBg)) { append(" " + g[5] + " ") }
                g[6].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[6]) }
                g[8].isNotEmpty() -> {
                    pushStringAnnotation("URL", g[8])
                    withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(g[7]) }
                    pop()
                }
                g[9].isNotEmpty() -> {
                    val inner = g[10]
                    when (g[9]) {
                        "code", "kbd", "tt", "samp" -> withStyle(SpanStyle(fontFamily = Type.Mono, fontSize = 13.sp, background = codeBg)) {
                            append(" " + HtmlLite.decodeDisplay(HtmlLite.stripTags(inner)) + " ") }
                        else -> withStyle(htmlStyle(g[9], accent, dark)) { inlines(inner, accent, codeBg) }
                    }
                }
                g[11].isNotEmpty() || g[12].isNotEmpty() -> {
                    val href = HtmlLite.decodeAll(g[11]).trim()
                    // only real web / mail links; javascript:, data: and the rest show as plain text
                    if (Regex("^(https?://|mailto:)\\S+$", RegexOption.IGNORE_CASE).matches(href)) {
                        pushStringAnnotation("URL", href)
                        withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { inlines(g[12], accent, codeBg) }
                        pop()
                    } else inlines(g[12], accent, codeBg)
                }
                g[14].isNotEmpty() -> { val t = HtmlLite.tint(g[13], dark); if (t != null) withStyle(SpanStyle(color = t, fontWeight = FontWeight.Medium)) { inlines(g[14], accent, codeBg) } else inlines(g[14], accent, codeBg) }
                g[16].isNotEmpty() -> { val t = HtmlLite.tint(g[15], dark); if (t != null) withStyle(SpanStyle(color = t, fontWeight = FontWeight.Medium)) { inlines(g[16], accent, codeBg) } else inlines(g[16], accent, codeBg) }
                else -> plain(m.value)
            }
            last = m.range.last + 1
        }
        plain(s.substring(last))
    }

    private fun Color.isUnspecified() = this == Color.Unspecified

    /** Inline HTML tag → style (monochrome-friendly: <mark> is a soft white wash, not yellow). */
    private fun htmlStyle(tag: String, accent: Color, dark: Boolean): SpanStyle = when (tag) {
        "b", "strong" -> SpanStyle(fontWeight = FontWeight.SemiBold)
        "i", "em", "cite", "var", "q" -> SpanStyle(fontStyle = FontStyle.Italic)
        "u", "ins", "abbr" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "s", "del", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "mark" -> SpanStyle(background = (if (dark) Color.White else Color.Black).copy(alpha = 0.16f), fontWeight = FontWeight.Medium)
        "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 11.sp)
        "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 11.sp)
        "small" -> SpanStyle(fontSize = 12.sp)
        "big" -> SpanStyle(fontSize = 17.sp)
        else -> SpanStyle()
    }

    /**
     * v0.28.1: a marker left open (a reply still streaming "**Offline mo", or a stray "**") is hidden instead of
     * shown raw: an odd number of `**` / `__` / `` ` `` / `~~` drops the last unmatched one.
     */
    fun tidy(line: String): String {
        var t = line
        for ((m, re) in tidyRes) {
            val n = re.findAll(t).count()
            if (n % 2 == 1) { val i = t.lastIndexOf(m); t = t.removeRange(i, i + m.length) }
        }
        if (t.count { it == '`' } % 2 == 1) { val i = t.lastIndexOf('`'); t = t.removeRange(i, i + 1) }
        return t
    }

    /** One line or a short label (step lines, card titles) with its inline markdown rendered. */
    fun inline(src: String, accent: Color, codeBg: Color): AnnotatedString = render(src.replace('\n', ' '), accent, codeBg)

    /** Plain text for copy, speech and notifications. */
    fun plain(src: String): String = render(src, Color.Unspecified, Color.Unspecified).text
        .replace("──────────", "").replace("│ ", "")
}
