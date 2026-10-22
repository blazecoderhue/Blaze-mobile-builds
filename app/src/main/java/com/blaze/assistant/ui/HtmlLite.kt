package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * v0.31.1 HTML in replies, without a WebView. Pure and cheap (a no-op when the text has no tag or entity).
 *
 * [normalize] turns block-level HTML into the Markdown the chat already draws, outside ``` fences only:
 * `<table>` → a pipe table (→ Table preview card + glass window), `<img src>` → an image line (→ Image preview card),
 * `<pre><code class="language-x">` → a ``` fence, `<h1>`–`<h6>` → headings (h5/h6 as h4), `<ul>/<ol>/<li>` → lists,
 * `<blockquote>` → `> ` lines, `<hr>` → a rule, `<br>` / `<p>` / `<div>` → line breaks. Scripts, styles, iframes, objects,
 * forms and comments are removed with their content; nothing is ever executed.
 *
 * Inline tags (`<b> <strong> <i> <em> <u> <ins> <s> <del> <strike> <code> <kbd> <mark> <sub> <sup> <small> <a href>
 * <span style="color:…"> <font color>`) stay in the text and are drawn by [Md]; any other known HTML tag is stripped
 * there ([stripTags]) and its text kept. `&lt;` `&gt;` `&amp;` are decoded only when drawn, so an escaped tag shows as text.
 */
object HtmlLite {
    /** Every HTML element name we recognise (unknown <words> such as List<String> are left alone). */
    val TAGS = setOf(
        "a", "abbr", "address", "article", "aside", "b", "bdi", "bdo", "big", "blockquote", "body", "br", "button", "caption", "center",
        "cite", "code", "col", "colgroup", "data", "dd", "del", "details", "dfn", "div", "dl", "dt", "em", "embed", "fieldset",
        "figcaption", "figure", "font", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hr", "html", "i", "iframe",
        "img", "input", "ins", "kbd", "label", "legend", "li", "link", "main", "mark", "meta", "nav", "noscript", "object", "ol", "option",
        "p", "picture", "pre", "q", "s", "samp", "script", "section", "select", "small", "source", "span", "strike", "strong", "style",
        "sub", "summary", "sup", "svg", "table", "tbody", "td", "template", "textarea", "tfoot", "th", "thead", "time", "title", "tr", "tt",
        "u", "ul", "var", "video", "audio", "wbr",
    )

    private val anyTag = Regex("""</?([a-zA-Z][a-zA-Z0-9]*)\b[^<>]*>""")
    private val entity = Regex("""&(#\d{1,6}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});""")

    /** Cheap gate: does [s] contain a known tag or an entity? */
    fun hasHtml(s: String): Boolean {
        if (s.indexOf('<') < 0 && s.indexOf('&') < 0) return false
        if (entity.containsMatchIn(s)) return true
        return anyTag.findAll(s).any { it.groupValues[1].lowercase() in TAGS } || Regex("""<(table|script|style|pre|img)\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)
    }

    /** Block HTML → Markdown, outside ``` fences. Returns [src] itself when there's nothing to do. */
    fun normalize(src: String): String {
        if (!hasHtml(src)) return src
        val lines = src.replace("\r", "").split('\n')
        val out = StringBuilder()
        val chunk = StringBuilder()
        var inFence = false
        fun flush() { if (chunk.isNotEmpty()) { out.append(blocks(chunk.toString())); chunk.setLength(0) } }
        lines.forEachIndexed { i, l ->
            val nl = if (i < lines.lastIndex) "\n" else ""
            if (l.trimStart().startsWith("```")) {
                if (!inFence) flush()
                inFence = !inFence
                out.append(l).append(nl)
            } else if (inFence) out.append(l).append(nl)
            else chunk.append(l).append(nl)
        }
        flush()
        return out.toString()
    }

    private val ic = setOf(RegexOption.IGNORE_CASE)
    private val comment = Regex("""<!--[\s\S]*?(-->|$)""")
    private val dangerous = Regex("""<(script|style|iframe|object|embed|noscript|template|svg|textarea|select|button|form|head|title)\b[^>]*>[\s\S]*?(</\1\s*>|$)""", ic)
    private val dangerousSolo = Regex("""<(script|style|iframe|object|embed|link|meta|input|source|base)\b[^>]*>""", ic)
    private val pre = Regex("""<pre\b[^>]*>([\s\S]*?)(</pre\s*>|$)""", ic)
    private val codeOpen = Regex("""<code\b([^>]*)>""", ic)
    private val langAttr = Regex("""class\s*=\s*["']?[^"'>]*?(?:language|lang)-([\w+#.-]+)""", ic)
    private val table = Regex("""<table\b[^>]*>([\s\S]*?)(</table\s*>|$)""", ic)
    private val caption = Regex("""<caption\b[^>]*>([\s\S]*?)</caption\s*>""", ic)
    private val tr = Regex("""<tr\b[^>]*>([\s\S]*?)(?=</tr\s*>|<tr\b|</tbody|</thead|</tfoot|$)""", ic)
    private val cell = Regex("""<(th|td)\b[^>]*>([\s\S]*?)(?=</t[hd]\s*>|<t[hd]\b|</tr|$)""", ic)
    private val img = Regex("""<img\b([^>]*)/?>""", ic)
    // a heading never runs past its line, even when the model forgets </hN>
    private val heading = Regex("""<h([1-6])\b[^>]*>([^\n]*?)(</h\1\s*>|(?=\n)|$)""", ic)
    private val quote = Regex("""<blockquote\b[^>]*>([\s\S]*?)(</blockquote\s*>|$)""", ic)
    private val listTag = Regex("""<(/?)(ul|ol|li)\b[^>]*>""", ic)
    private val br = Regex("""<br\s*/?>""", ic)
    private val hr = Regex("""<hr\b[^>]*>""", ic)
    private val para = Regex("""</?p\b[^>]*>""", ic)
    private val boxy = Regex("""</?(div|section|article|header|footer|main|nav|aside|figure|figcaption|center|details|summary|dl|html|body|thead|tbody|tfoot)\b[^>]*>""", ic)
    private val dtdd = Regex("""<(dt|dd)\b[^>]*>""", ic)
    private val tagName = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)""")

    fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", ic).find(attrs)?.let { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "" }

    private fun blocks(s0: String): String {
        var s = s0
        // tag names to lower case so Md's inline patterns match <B>, <Strong>…
        s = tagName.replace(s) { m -> if (m.groupValues[2].lowercase() in TAGS) "<" + m.groupValues[1] + m.groupValues[2].lowercase() else m.value }
        s = comment.replace(s, "")
        s = dangerous.replace(s, "")
        s = dangerousSolo.replace(s, "")
        s = pre.replace(s) { m ->
            val inner = m.groupValues[1]
            val lang = codeOpen.find(inner)?.let { langAttr.find(it.groupValues[1])?.groupValues?.get(1) }.orEmpty()
            val code = decodeAll(stripAll(br.replace(inner, "\n"))).trim('\n')
            "\n```$lang\n$code\n```\n"
        }
        s = table.replace(s) { m -> "\n\n" + pipeTable(m.groupValues[1]) + "\n\n" }
        s = img.replace(s) { m ->
            val a = m.groupValues[1]
            val src = attr(a, "src").orEmpty().trim()
            val alt = decodeAll(attr(a, "alt").orEmpty()).replace(Regex("[\\[\\]\\n]"), " ").trim()
            if (Regex("""^(https?://|/)\S+$""").matches(src)) "\n![$alt]($src)\n" else alt
        }
        s = heading.replace(s) { m ->
            val n = m.groupValues[1].toInt().coerceAtMost(4)
            "\n\n" + "#".repeat(n) + " " + br.replace(m.groupValues[2], " ").replace('\n', ' ').trim() + "\n\n"
        }
        s = quote.replace(s) { m -> "\n\n" + flow(m.groupValues[1]).trim('\n').lines().joinToString("\n") { "> $it" } + "\n\n" }
        s = flow(s)
        s = decodeSoft(s)
        return s.replace(Regex("\n{3,}"), "\n\n")
    }

    /** Lists, breaks, paragraphs and boxes → lines. */
    private fun flow(s0: String): String {
        var s = s0
        if (listTag.containsMatchIn(s)) {
            val sb = StringBuilder()
            val stack = ArrayList<IntArray>()   // [ordered 0/1, counter]
            var last = 0
            for (m in listTag.findAll(s)) {
                sb.append(s, last, m.range.first)
                last = m.range.last + 1
                val close = m.groupValues[1] == "/"
                when (m.groupValues[2].lowercase()) {
                    "ul", "ol" -> if (close) { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); sb.append('\n') }
                        else { stack += intArrayOf(if (m.groupValues[2].equals("ol", true)) 1 else 0, 0); sb.append('\n') }
                    "li" -> if (!close) {
                        val top = stack.lastOrNull() ?: intArrayOf(0, 0).also { stack += it }
                        top[1]++
                        sb.append('\n').append("  ".repeat((stack.size - 1).coerceAtLeast(0)))
                        sb.append(if (top[0] == 1) "${top[1]}. " else "- ")
                    }
                }
            }
            sb.append(s, last, s.length)
            // whitespace HTML puts between tags would otherwise leave blank or indented lines inside the list
            // "<li>\n  Item" → "- Item" (before trimming, so the marker keeps its space)
            s = sb.toString().replace(Regex("(?m)^([ \\t]*(?:- |\\d+\\. ))[ \\t]*\n\\s*(?=\\S)"), "$1")
                .lines().joinToString("\n") { l -> if (l.isBlank()) "" else l.trimEnd() }
                .replace(Regex("\n(\\s*\n)+(?=\\s*(?:- |\\d+\\. ))"), "\n")
        }
        s = br.replace(s, "\n")
        s = hr.replace(s, "\n\n---\n\n")
        s = para.replace(s, "\n\n")
        s = dtdd.replace(s, "\n")
        s = boxy.replace(s, "\n")
        return s
    }

    /** `<table>` inner HTML → a Markdown pipe table (caption as a bold line above). Streaming-safe (open tags close at the end). */
    fun pipeTable(inner: String): String {
        val cap = caption.find(inner)?.groupValues?.get(1)?.let { inlineCell(it) }.orEmpty()
        val body = caption.replace(inner, "")
        val rows = tr.findAll(body).map { r -> cell.findAll(r.groupValues[1]).map { c -> inlineCell(c.groupValues[2]) }.toList() }
            .filter { it.isNotEmpty() }.toList()
        if (rows.isEmpty()) return if (cap.isNotBlank()) "**$cap**" else ""
        val n = rows.maxOf { it.size }
        fun line(r: List<String>) = "| " + (0 until n).joinToString(" | ") { r.getOrElse(it) { "" } } + " |"
        val sb = StringBuilder()
        if (cap.isNotBlank()) sb.append("**").append(cap).append("**\n\n")
        sb.append(line(rows[0])).append('\n').append("|" + " --- |".repeat(n))
        rows.drop(1).forEach { sb.append('\n').append(line(it)) }
        return sb.toString()
    }

    private fun inlineCell(h: String): String =
        flow(br.replace(h, " ")).replace(Regex("\\s+"), " ").trim().replace("|", "\\|")

    /** Every tag removed (for code inside <pre>). */
    fun stripAll(s: String): String = anyTag.replace(s, "")

    /** Known HTML tags removed, their text kept (Md calls this on plain runs). */
    fun stripTags(s: String): String {
        if (s.indexOf('<') < 0) return s
        return anyTag.replace(s) { m -> if (m.groupValues[1].lowercase() in TAGS) "" else m.value }
    }

    /** A tag still being typed at the very end of a streaming reply ("…<str", "</b"): hidden until it closes. */
    fun dropPartialTag(s: String): String {
        val m = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)?([^<>]*)$""").find(s) ?: return s
        val name = m.groupValues[2].lowercase()
        if (name.isEmpty() && m.value.length > 2) return s
        if (name.isNotEmpty() && TAGS.none { it.startsWith(name) }) return s
        return s.substring(0, m.range.first)
    }

    private val named = mapOf("nbsp" to " ", "quot" to "\"", "apos" to "'", "#39" to "'", "ndash" to "\u2013", "mdash" to "\u2014",
        "hellip" to "\u2026", "copy" to "\u00a9", "reg" to "\u00ae", "trade" to "\u2122", "times" to "\u00d7", "divide" to "\u00f7",
        "middot" to "\u00b7", "bull" to "\u2022", "rarr" to "\u2192", "larr" to "\u2190", "deg" to "\u00b0", "euro" to "\u20ac",
        "pound" to "\u00a3", "laquo" to "\u00ab", "raquo" to "\u00bb", "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201c", "rdquo" to "\u201d")

    private fun num(code: String): String? = try {
        val v = if (code.startsWith("#x") || code.startsWith("#X")) code.substring(2).toInt(16) else code.substring(1).toInt()
        if (v == 0 || v > 0x10FFFF) null else String(Character.toChars(v))
    } catch (_: Exception) { null }

    /** Entities except &lt; &gt; &amp; (and their numeric forms), which stay until [decodeDisplay] so they never become tags. */
    fun decodeSoft(s: String): String = if (s.indexOf('&') < 0) s else entity.replace(s) { m ->
        val k = m.groupValues[1]
        when {
            k.startsWith("#") -> num(k)?.takeIf { it != "<" && it != ">" && it != "&" } ?: m.value
            else -> named[k.lowercase()] ?: m.value
        }
    }

    fun decodeAll(s: String): String = if (s.indexOf('&') < 0) s else entity.replace(s) { m ->
        val k = m.groupValues[1]
        when (k.lowercase()) { "lt" -> "<"; "gt" -> ">"; "amp" -> "&"; else -> if (k.startsWith("#")) num(k) ?: m.value else named[k.lowercase()] ?: m.value }
    }

    /** Last step before text is drawn. */
    fun decodeDisplay(s: String): String = if (s.indexOf('&') < 0) s else s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&#60;", "<").replace("&#62;", ">").replace("&amp;", "&").replace("&#38;", "&")

    // ---------- colours ----------
    private val names = mapOf("red" to 0xFFE5484D, "green" to 0xFF30A46C, "blue" to 0xFF3E63DD, "orange" to 0xFFF76B15, "yellow" to 0xFFFFC53D,
        "purple" to 0xFF8E4EC6, "violet" to 0xFF8E4EC6, "pink" to 0xFFD6409F, "teal" to 0xFF12A594, "cyan" to 0xFF00A2C7, "gray" to 0xFF8B8D98,
        "grey" to 0xFF8B8D98, "white" to 0xFFFFFFFF, "black" to 0xFF000000, "brown" to 0xFFAD7F58, "gold" to 0xFFFFC53D, "lime" to 0xFF99D52A,
        "navy" to 0xFF2A3A8C, "maroon" to 0xFF8C2A2A, "silver" to 0xFFC0C0C0)

    fun parseColor(v: String): Color? {
        val t = v.trim().lowercase()
        names[t]?.let { return Color(it) }
        Regex("""^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$""").find(t)?.let { m ->
            val h = m.groupValues[1]
            val full = when (h.length) { 3 -> h.map { "$it$it" }.joinToString(""); 8 -> h.substring(0, 6); else -> h }
            return Color(0xFF000000 or full.toLong(16))
        }
        Regex("""^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})""").find(t)?.let { m ->
            val (r, g, b) = m.groupValues.drop(1).map { it.toInt().coerceIn(0, 255) }
            return Color(r, g, b)
        }
        return null
    }

    /**
     * A colour from the model mapped onto the monochrome glass look: heavily desaturated towards the text grey and kept
     * readable on the ground ([dark] = dark theme). Null = draw in the normal text colour.
     */
    fun tint(v: String, dark: Boolean): Color? {
        val c = parseColor(v) ?: return null
        val base = if (dark) Color(0xFFE4E4E8) else Color(0xFF2A2A2E)
        var out = lerp(c, base, 0.45f)
        // keep contrast: push away from the ground until it reads
        repeat(4) {
            val l = out.luminance()
            if (dark && l < 0.38f) out = lerp(out, Color.White, 0.35f)
            if (!dark && l > 0.35f) out = lerp(out, Color.Black, 0.35f)
        }
        return out
    }
}
