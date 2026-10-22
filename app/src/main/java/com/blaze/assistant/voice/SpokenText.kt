package com.blaze.assistant.voice

/**
 * v0.41.0: what Blaze may say out loud. Only the reply the user sees is spoken, as plain words: never the work steps
 * ("Looking at your series"), tool or status lines, markdown symbols, links, code or table pipes. Pure (SpokenTextTest).
 */
object SpokenText {
    /** Streamed sentences start being spoken only when the round surely is the reply, not narration before a tool. */
    const val STREAM_MIN_CHARS = 180

    private val think = Regex("""(?s)<think>.*?(</think>|$)""")
    private val fence = Regex("""(?s)```.*?(```|$)""")
    private val image = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val url = Regex("""https?://\S+|www\.\S+""")
    private val reMark = Regex("""\[re:[^\]]*]""", RegexOption.IGNORE_CASE)
    private val heading = Regex("""(?m)^\s{0,3}#{1,6}\s*""")
    private val quote = Regex("""(?m)^\s*>\s?""")
    private val bullet = Regex("""(?m)^\s*([-*+•·]|\d{1,2}[.)])\s+""")
    private val rule = Regex("""(?m)^\s*([-*_=─━]\s*){3,}$""")
    private val tableSep = Regex("""(?m)^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
    private val emphasis = Regex("""(\*\*|__|~~|\*|_|`)(?=\S)(.+?)(?<=\S)\1""")
    private val symbols = Regex("""[*_`#>|~•]+""")
    /** Lines that are a tool call written as text, or a status / step line, never reply text. */
    private val statusLine = Regex(
        """(?im)^\s*(TOOLS?\s*[:\[{].*|\{\s*"(name|tool|function)"\s*:.*|\[(tool|step|status)[^\]]*].*|\(step\).*|⚡.*|☁.*|✓\s.*|→\s.*)$""")

    /** The final reply text, cleaned for a speech engine. Blank when there is nothing to say. */
    fun forSpeech(text: String, max: Int = 3900): String {
        var t = text.replace("\r", "")
        t = think.replace(t, " ")
        t = fence.replace(t, " ")
        t = statusLine.replace(t, "")
        t = reMark.replace(t, "")
        t = image.replace(t, " ")
        t = link.replace(t, "$1")
        t = url.replace(t, "")
        t = tableSep.replace(t, "")
        t = rule.replace(t, "")
        t = heading.replace(t, "")
        t = quote.replace(t, "")
        t = bullet.replace(t, "")
        repeat(2) { t = emphasis.replace(t) { it.groupValues[2] } }
        // Table rows: cells become a short pause.
        t = t.lines().joinToString("\n") { l -> if (l.count { it == '|' } >= 2) l.trim().trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") else l }
        t = symbols.replace(t, " ")
        t = t.replace(Regex("""[ \t]+"""), " ").replace(Regex(""" *\n *"""), "\n").replace(Regex("""\n{2,}"""), "\n").trim()
        // A line without an ending gets one, so the voice pauses between list items / lines.
        t = t.lines().filter { it.isNotBlank() }.joinToString(" ") { l -> if (l.last() in ".!?:;,…") l else "$l." }
        return if (t.length <= max) t else t.take(max).substringBeforeLast(' ')
    }

    /**
     * Whether sentences of the round being streamed may be spoken before it finishes. A round that ends in a tool
     * call becomes a work step, so only the no-tools lane, or text clearly longer than a one-line "Let me check…",
     * starts early. Everything else is spoken whole when the reply is final.
     */
    fun streamOk(roundText: String, noTools: Boolean): Boolean =
        noTools || roundText.trim().length >= STREAM_MIN_CHARS
}
