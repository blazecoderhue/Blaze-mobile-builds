package com.blaze.assistant.ui

/**
 * v0.24.9 rich replies (ChatGPT-style), pure part (RichRepliesTest): turns the Markdown of a Blaze reply into rich
 * blocks on top of [AnswerCards]: step-flow cards, a section image beside its text, image groups (up to 3 side by
 * side, 4 or more as a tilted deck you swipe through with a card flip) and inline diagrams drawn by free, keyless
 * renderers (mermaid.ink for Mermaid, kroki.io for Graphviz / PlantUML / D2; since v0.26.3 Mermaid is drawn on the phone first,
 * see DiagramRender). v0.26.1 adds interactive blocks
 * (```chart, ```choices, ```form, ```tool, ```compare; parsed in [RichKit]) and a prompt that picks formats on its own.
 */
object RichReplies {
    @androidx.compose.runtime.Immutable
    data class Img(val url: String, val alt: String)

    // v0.31.1: compared by value, so a finished block's preview card skips recomposition while the reply streams.
    @androidx.compose.runtime.Immutable
    sealed class Block {
        /** Anything AnswerCards already draws (text, lists, code, tables). */
        @androidx.compose.runtime.Immutable data class Plain(val seg: AnswerCards.Seg) : Block()
        @androidx.compose.runtime.Immutable data class Steps(val steps: List<Step>) : Block()
        @androidx.compose.runtime.Immutable data class Image(val img: Img) : Block()
        /** A section picture beside its paragraph (picture on the left). */
        @androidx.compose.runtime.Immutable data class ImageText(val img: Img, val md: String) : Block()
        @androidx.compose.runtime.Immutable data class Images(val imgs: List<Img>) : Block()
        /** [url] is the web renderer (the fallback); [private] = a private chat: drawn on the phone only (v0.26.3). */
        @androidx.compose.runtime.Immutable data class Diagram(val kind: String, val code: String, val url: String, val private: Boolean = false) : Block()
        // v0.26.1 visual answers (RichKit): interactive blocks drawn natively, offline (so they show in Private too).
        @androidx.compose.runtime.Immutable data class Chart(val chart: RichKit.Chart) : Block()
        @androidx.compose.runtime.Immutable data class Choices(val choices: RichKit.Choices) : Block()
        @androidx.compose.runtime.Immutable data class Form(val form: RichKit.Form) : Block()
        @androidx.compose.runtime.Immutable data class Tool(val tool: RichKit.Tool) : Block()
        @androidx.compose.runtime.Immutable data class Compare(val compare: RichKit.Compare) : Block()
        /** v0.28.1: a table card (```table, or a Markdown pipe table). */
        @androidx.compose.runtime.Immutable data class Table(val table: RichKit.TableCard, val raw: String) : Block()
        /** v0.33.3: the user's installed apps as a tappable grid (```apps, added by list_apps). */
        @androidx.compose.runtime.Immutable data class Apps(val apps: RichKit.AppsCard) : Block()
        /**
         * A visual still streaming in (its fence isn't closed and it can't be drawn yet). Shown as a quiet placeholder
         * while the reply streams; [fallback] once the reply is done (a reply that never closed its fence).
         */
        @androidx.compose.runtime.Immutable data class Pending(val kind: String, val seg: AnswerCards.Seg.Code, val fallback: Block) : Block()
    }

    data class Step(val title: String, val body: String)

    /** How an image group is laid out. */
    enum class Layout { ROW, DECK }
    const val ROW_MAX = 3
    fun layout(n: Int): Layout = if (n <= ROW_MAX) Layout.ROW else Layout.DECK

    /**
     * The static system-prompt lines that tell the model to pick the format itself (v0.26.1 visual answers). Kept
     * constant so the provider's cached prefix holds; only added when the rich_replies pref is on.
     */
    const val PROMPT = "\n\nVISUAL ANSWERS: the chat draws some blocks as visuals and small interactive tools. Pick the best format for each answer " +
        "yourself, without being asked, the way a great teacher would. Keep the text short when a visual carries the answer (2-4 sentences around it). " +
        "Plain text is right for simple facts, quick answers, opinions, feelings and chit-chat: never force a visual there, and use at most two visual blocks per answer. " +
        "Choose: how something works or a process -> a ```mermaid flowchart plus 2-3 sentences; a comparison of 2-3 things -> a ```compare block; " +
        "rows of facts with several columns (plans, prices, schedules, specs of 4+ items, results) -> a ```table card; " +
        "numbers, shares or a trend -> a ```chart; anything with an amount the user could change (splitting, tips, loans, savings, unit or recipe scaling) -> a ```tool; " +
        "a choice the user must make next -> a ```choices block; several details you need from the user at once -> a ```form; a how-to -> numbered steps with **bold titles**. " +
        "IN CHAT, INLINE: a diagram, chart or visual the user asks for (\"draw\", \"flowchart\", \"diagram\", \"chart\", \"graph\", \"map out\", \"visualise\") goes straight in your reply as a ```mermaid or ```chart block, drawn in the chat. " +
        "Don't write files, don't open a preview, Design view or preview_server, and don't call use_skill for it. Make a file (or preview / export it) only when the user asks to save, export, download, edit or make a file. " +
        "Interactive blocks are fenced code blocks whose body is ONE valid JSON object (double quotes, no comments, no trailing commas): " +
        "```chart {\"type\":\"bar|line|pie|scatter\",\"title\":\"..\",\"labels\":[\"Jan\",\"Feb\"],\"series\":[{\"name\":\"Sales\",\"values\":[12,19]}],\"unit\":\"K\"} " +
        "(pie: one series; scatter: \"points\":[[x,y],..] with \"xLabel\"/\"yLabel\"). Use real numbers you know or found; if they're estimates, say so in the text. " +
        "```compare {\"title\":\"..\",\"columns\":[\"iPhone 16\",\"Galaxy S25\"],\"rows\":[{\"label\":\"Screen\",\"values\":[\"6.1 in\",\"6.2 in\"]}],\"verdict\":\"one line\"} (2-3 columns, up to 10 rows). " +
        "```table {\"title\":\"..\",\"columns\":[\"Plan\",\"Price\",\"Data\"],\"rows\":[[\"Basic\",\"K50\",\"2 GB\"]]} (a bold title, up to 8 columns; a Markdown pipe table or an HTML <table> is drawn the same way; never put a pipe table inside a ``` fence). " +
        "```tool {\"title\":\"Bill splitter\",\"inputs\":[{\"id\":\"bill\",\"label\":\"Bill\",\"type\":\"number\",\"default\":450,\"prefix\":\"K\"},{\"id\":\"people\",\"label\":\"People\",\"type\":\"slider\",\"min\":1,\"max\":12,\"step\":1,\"default\":3},{\"id\":\"tip\",\"label\":\"Tip\",\"type\":\"slider\",\"min\":0,\"max\":25,\"default\":10,\"suffix\":\"%\"}]," +
        "\"outputs\":[{\"label\":\"Tip\",\"formula\":\"bill*tip/100\",\"prefix\":\"K\"},{\"label\":\"Each pays\",\"formula\":\"bill*(1+tip/100)/people\",\"prefix\":\"K\"}]} " +
        "(input types number, slider, select with \"options\":[{\"label\":\"Monthly\",\"value\":12}]; formulas use input ids, numbers, + - * / ^ ( ), < > <= >= ==, min, max, round(x,2), ceil, floor, abs, sqrt, pow, if(cond,a,b); the last output is the headline; fill defaults from the user's numbers and also state the answer in the text). " +
        "```choices {\"prompt\":\"..\",\"options\":[\"Option A\",\"Option B\"]} (2-5 short options; a tap sends that text as the user's reply). " +
        "```form {\"title\":\"Trip plan\",\"fields\":[{\"id\":\"city\",\"label\":\"City\",\"type\":\"text\"},{\"id\":\"days\",\"label\":\"Days\",\"type\":\"number\",\"default\":3},{\"id\":\"budget\",\"label\":\"Budget\",\"type\":\"select\",\"options\":[\"Low\",\"Mid\",\"High\"]},{\"id\":\"kids\",\"label\":\"With kids\",\"type\":\"toggle\"}],\"submit\":\"Plan it\"} (up to 6 fields). " +
        "Examples: \"how do solar panels work?\" -> 2-3 sentences plus ```mermaid flowchart LR: Sunlight --> Solar cells --> DC power --> Inverter --> AC power --> Home, with the grid / battery as a side branch. " +
        "\"split a K450 bill between 3 with 10% tip\" -> the answer in one line (K165 each) plus a bill-splitter ```tool with those defaults. " +
        "\"compare iPhone 16 vs Galaxy S25\" -> a ```compare block plus a one-line pick. \"Zambia's copper exports over 5 years\" -> a line ```chart. " +
        "\"which laptop should I get?\" with no details -> a ```form or ```choices to narrow it down. \"what's the capital of Zambia?\" or \"thanks!\" -> plain text. " +
        "Other formats the chat draws: a picture for a section: ![alt](https url) on its own line right before that paragraph; several picture lines in a row become a row (up to 3) or a swipeable deck. " +
        "Use only real image URLs you found (Wikimedia Commons, Openverse, the source page); never invent one. " +
        "Diagrams: ```mermaid (flowchart, sequenceDiagram, mindmap, timeline, pie), or ```graphviz / ```plantuml. Keep node labels 1-4 words, no more than ~12 nodes."

    private val imgLine = Regex("""^\s*!\[([^\]]*)]\(\s*<?((?:https?://|/)[^\s)>]+)>?(?:\s+"[^"]*")?\s*\)\s*$""")
    private val stepRe = Regex("""^\s*(\d+)[.)]\s+\*\*(.+?)\*\*\s*[:.\u2014\u2013-]?\s*(.*)$""")
    private val diagramLangs = mapOf(
        "mermaid" to "mermaid", "graphviz" to "graphviz", "dot" to "graphviz", "plantuml" to "plantuml", "puml" to "plantuml", "d2" to "d2")

    fun image(line: String): Img? = imgLine.matchEntire(line)?.let { Img(it.groupValues[2], it.groupValues[1].trim()) }

    /**
     * Splits AnswerCards' segments into rich blocks. Code blocks in a diagram language become diagrams. v0.26.3: also in
     * Private, since Mermaid is drawn on the phone (nothing leaves it); other kinds show a plain line there ([DiagramRender.plan]).
     */
    fun expand(segs: List<AnswerCards.Seg>, private: Boolean = false): List<Block> = segs.flatMap { s ->
        when (s) {
            is AnswerCards.Seg.Text -> text(s.md)
            is AnswerCards.Seg.Lists -> text(s.md, list = s)
            // v0.28.1: a Markdown pipe table is drawn as a table card too.
            is AnswerCards.Seg.Table -> RichKit.tableFromMd(s.rows)?.let { listOf(Block.Table(it, s.raw)) } ?: listOf(Block.Plain(s))
            is AnswerCards.Seg.Code -> {
                val lang = s.lang.trim().lowercase()
                val kind = diagramLangs[lang]
                if (kind != null && s.code.isNotBlank()) {
                    // v0.26.1: a diagram is drawn once its fence closes (no broken half-diagram fetches mid-stream).
                    val d = Block.Diagram(kind, s.code, diagramUrl(kind, s.code), private)
                    listOf(if (open(s)) Block.Pending("diagram", s, d) else d)
                }
                else RichKit.LANGS[lang]?.let { listOf(interactive(it, s)) } ?: listOf(Block.Plain(s))
            }
            else -> listOf(Block.Plain(s))
        }
    }

    /** Is this code block still streaming in (no closing fence yet)? */
    fun open(s: AnswerCards.Seg.Code): Boolean {
        val lines = s.raw.trimEnd().lines()
        return lines.size < 2 || !lines.last().trim().startsWith("```")
    }

    /**
     * An interactive block, or the fallback: a block whose JSON can't be used shows as the plain code block once it's
     * closed, and as a quiet "Drawing a chart…" placeholder while it's still streaming in.
     */
    fun interactive(kind: String, s: AnswerCards.Seg.Code): Block {
        val b = try {
            when (kind) {
                "chart" -> RichKit.chart(s.code)?.let { Block.Chart(it) }
                "choices" -> (if (open(s) && RichKit.json(s.code) == null) null else RichKit.choices(s.code))?.let { Block.Choices(it) }
                "form" -> RichKit.form(s.code)?.let { Block.Form(it) }
                "tool" -> RichKit.tool(s.code)?.let { Block.Tool(it) }
                "compare" -> RichKit.compare(s.code)?.let { Block.Compare(it) }
                "table" -> RichKit.table(s.code)?.let { Block.Table(it, s.raw) }
                "apps" -> RichKit.apps(s.code)?.let { Block.Apps(it) }
                else -> null
            }
        } catch (_: Exception) { null } catch (_: StackOverflowError) { null }
        return b ?: if (open(s)) Block.Pending(kind, s, Block.Plain(s)) else Block.Plain(s)
    }

    /** The placeholder line for a block still streaming in. */
    fun pendingLabel(kind: String): String = when (kind) {
        "chart" -> "Drawing a chart\u2026"
        "compare" -> "Lining up the comparison\u2026"
        "table" -> "Setting out the table\u2026"
        "tool" -> "Building a calculator\u2026"
        "form" -> "Making a quick form\u2026"
        "diagram" -> "Drawing a diagram\u2026"
        "apps" -> "Laying out your apps\u2026"
        else -> "Adding options\u2026"
    }

    /** Paragraph-level pass over a text segment: image runs, image + paragraph, step lists, the rest as text. */
    fun text(md: String, list: AnswerCards.Seg.Lists? = null): List<Block> {
        val lines = md.split('\n')
        if (lines.none { image(it) != null } && lines.none { stepRe.matches(it) }) return listOf(Block.Plain(list ?: AnswerCards.Seg.Text(md)))
        val out = ArrayList<Block>()
        val buf = StringBuilder()
        fun flush() { val t = buf.toString().trim('\n'); if (t.isNotBlank()) out += Block.Plain(AnswerCards.Seg.Text(t)); buf.clear() }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val img = image(l)
            if (img != null) {
                flush()
                val run = arrayListOf(img)
                var j = i + 1
                while (j < lines.size) {
                    val n = image(lines[j])
                    if (n != null) { run += n; j++ }
                    else if (lines[j].isBlank() && j + 1 < lines.size && image(lines[j + 1]) != null) j++
                    else break
                }
                if (run.size == 1) {
                    // A lone picture right before a paragraph sits beside it.
                    var k = j
                    while (k < lines.size && lines[k].isBlank()) k++
                    val para = StringBuilder()
                    while (k < lines.size && lines[k].isNotBlank() && image(lines[k]) == null && !stepRe.matches(lines[k]) && !lines[k].trimStart().startsWith("#")) {
                        para.append(lines[k]).append('\n'); k++
                    }
                    if (para.isNotBlank()) { out += Block.ImageText(img, para.toString().trim()); i = k; continue }
                    out += Block.Image(img)
                } else out += Block.Images(run)
                i = j; continue
            }
            if (stepRe.matches(l)) {
                val steps = ArrayList<Step>()
                var j = i
                while (j < lines.size) {
                    val m = stepRe.matchEntire(lines[j])
                    if (m != null) { steps += Step(m.groupValues[2].trim(), m.groupValues[3].trim()); j++; continue }
                    // Indented continuation lines belong to the step above.
                    if (steps.isNotEmpty() && lines[j].startsWith("  ") && lines[j].isNotBlank()) {
                        val last = steps.removeAt(steps.size - 1)
                        steps += last.copy(body = (last.body + " " + lines[j].trim()).trim()); j++; continue
                    }
                    break
                }
                if (steps.size >= 2) { flush(); out += Block.Steps(steps); i = j; continue }
            }
            buf.append(l).append('\n'); i++
        }
        flush()
        return out
    }

    // ---------- diagrams: the web renderers (v0.26.3: the fallback; Mermaid is drawn on the phone first) ----------
    fun diagramUrl(kind: String, code: String): String = when (kind) {
        "mermaid" -> "https://mermaid.ink/img/" + b64url(code.trim().toByteArray()) + "?type=png&bgColor=!white"
        else -> "https://kroki.io/$kind/png/" + b64url(deflate(code.trim().toByteArray()))
    }

    private fun b64url(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun deflate(b: ByteArray): ByteArray {
        val d = java.util.zip.Deflater(9)
        d.setInput(b); d.finish()
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    // ---------- the deck ----------
    /** Each card under the top one tilts a little, alternating sides; the top card is straight. Degrees. */
    fun tilt(depth: Int): Float = if (depth <= 0) 0f else (if (depth % 2 == 1) -1f else 1f) * minOf(2.5f + depth * 1.5f, 7f)
    /** How far each card under the top one peeks out (dp, down and sideways). */
    fun peek(depth: Int): Float = minOf(depth, 3) * 7f
    /** Cards drawn under the top one (the rest are hidden). */
    const val DECK_SHOWN = 3
    /** A swipe of at least this fraction of the card width turns the card. */
    const val SWIPE_FRACTION = 0.22f
    const val FLIP_MS = 420

    fun next(index: Int, n: Int, dir: Int): Int = if (n <= 0) 0 else ((index + dir) % n + n) % n

    /** Did a horizontal drag of [dx] px on a card [widthPx] wide turn it? -1 back, +1 forward, 0 no. */
    fun swipeDir(dx: Float, widthPx: Float): Int = when {
        widthPx <= 0f -> 0
        dx <= -SWIPE_FRACTION * widthPx -> 1
        dx >= SWIPE_FRACTION * widthPx -> -1
        else -> 0
    }

    /**
     * Card flip over t = 0..1: the old card turns 0 → 90° (t ≤ 0.5), then the new one -90 → 0°. Returns (angle,
     * showingNew). [dir] = +1 turns to the left.
     */
    fun flip(t: Float, dir: Int): Pair<Float, Boolean> {
        val k = t.coerceIn(0f, 1f); val s = if (dir >= 0) -1f else 1f
        return if (k <= 0.5f) (s * 180f * k) to false else (s * (180f * k - 180f)) to true
    }

    /** Android's "Remove animations" (animator duration scale 0) turns the flip into an instant swap. */
    fun animationsOff(animatorScale: Float): Boolean = animatorScale <= 0f

    /** "2 / 5" for the deck's counter and TalkBack. */
    fun counter(index: Int, n: Int): String = "${index + 1} / $n"
}
