package com.blaze.assistant.ui

/**
 * v0.31.1 table detection (pure, unit-testable). Models often wrap a Markdown pipe table in a ``` fence, write it without
 * the outer pipes, or draw an ASCII / box-drawing grid. All of those become a real table (rows of cells: header first)
 * so the reply shows the Table preview card + floating glass window instead of a "Code · 6 lines" box.
 */
object TableSniff {
    /** Fence languages whose body may be a table (```table with a JSON body stays a ```table card, see [RichKit.table]). */
    val FENCE_LANGS = setOf("", "md", "markdown", "table", "text", "txt", "plain", "plaintext", "gfm")

    /** A separator row with at least one pipe between columns: `---|:---:|---`, `| --- | --- |`, `|-|-|`. */
    private val sepMulti = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)+\|?\s*$""")
    /** A one-column separator, only after a header that starts with a pipe: `|---|`. */
    private val sepSingle = Regex("""^\s*\|\s*:?-{2,}:?\s*\|?\s*$""")

    /** Splits a pipe row into trimmed cells: outer pipes optional, `\|` is a literal pipe inside a cell. */
    fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length - 1)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (ch == '\\' && i + 1 < t.length && t[i + 1] == '|') { cur.append('|'); i += 2; continue }
            if (ch == '|') { out += cur.toString().trim(); cur.setLength(0) } else cur.append(ch)
            i++
        }
        out += cur.toString().trim()
        return out
    }

    /** Has a real (unescaped) pipe and at least two cells. */
    fun isPipeRow(line: String): Boolean {
        if (line.isBlank() || line.trimStart().startsWith("```")) return false
        if (!Regex("""(?<!\\)\|""").containsMatchIn(line)) return false
        return cells(line).size >= 2 || line.trimStart().startsWith("|")
    }

    fun isSep(line: String, header: String): Boolean =
        sepMulti.matches(line) || (header.trimStart().startsWith("|") && sepSingle.matches(line))

    /** A pipe table starts at [i]: a header row and a separator right under it. */
    fun pipeStart(lines: List<String>, i: Int): Boolean =
        i + 1 < lines.size && isPipeRow(lines[i]) && isSep(lines[i + 1], lines[i])

    /** Rows (header first) of the pipe table starting at [i], and the index after its last line. */
    fun pipeAt(lines: List<String>, i: Int): Pair<List<List<String>>, Int> {
        val rows = mutableListOf(cells(lines[i]))
        var j = i + 2
        while (j < lines.size && isPipeRow(lines[j])) { rows += cells(lines[j]); j++ }
        return rows to j
    }

    // ---------- ASCII / box-drawing grids ----------
    private const val BORDER_CHARS = "+-=:|─━┄┈┌┐└┘├┤┬┴┼═╔╗╚╝╠╣╦╩╬╞╡╪╤╧╒╕╘╛╓╖╙╜╟╢╫╥╨│║ "

    fun isBorder(line: String): Boolean {
        val t = line.trim()
        if (t.length < 3) return false
        if (!(t[0] == '+' || t[0] in "┌├└╔╠╚╞╒╘╓╟╙")) return false
        if (t.none { it == '-' || it == '─' || it == '=' || it == '═' || it == '━' }) return false
        return t.all { it in BORDER_CHARS }
    }

    fun isBoxRow(line: String): Boolean { val t = line.trim(); return t.length >= 3 && (t[0] == '|' || t[0] == '│' || t[0] == '║') }

    private fun boxCells(line: String): List<String> {
        val t = line.trim().replace('│', '|').replace('║', '|')
        return cells(t)
    }

    /** An ASCII grid starts at [i]: a border line followed by a cell row. */
    fun asciiStart(lines: List<String>, i: Int): Boolean = i + 1 < lines.size && isBorder(lines[i]) && isBoxRow(lines[i + 1])

    /** Rows (header first) of the ASCII grid starting at [i] (null if it isn't one), and the index after it. */
    fun asciiAt(lines: List<String>, i: Int): Pair<List<List<String>>, Int>? {
        var j = i
        val groups = ArrayList<MutableList<List<String>>>()
        var cur: MutableList<List<String>>? = null
        while (j < lines.size && (isBorder(lines[j]) || isBoxRow(lines[j]))) {
            if (isBorder(lines[j])) { cur?.let { if (it.isNotEmpty()) groups += it }; cur = mutableListOf() }
            else { if (cur == null) cur = mutableListOf(); cur.add(boxCells(lines[j])) }
            j++
        }
        cur?.let { if (it.isNotEmpty()) groups += it }
        if (groups.isEmpty()) return null
        fun merge(g: List<List<String>>): List<String> {
            val n = g.maxOf { it.size }
            return (0 until n).map { k -> g.joinToString(" ") { it.getOrElse(k) { "" } }.replace(Regex("\\s+"), " ").trim() }
        }
        val rows: List<List<String>> = when {
            // no border under the header: every line is a row
            groups.size == 1 -> groups[0]
            // header, then one body block: each body line is a row
            groups.size == 2 -> listOf(merge(groups[0])) + groups[1]
            // a border between every row: lines inside a block are one wrapped row
            else -> groups.map { merge(it) }
        }
        if (rows.isEmpty() || rows.maxOf { it.size } < 2) return null
        return rows to j
    }

    /**
     * The body of a fence (its lines without the ``` lines) as table rows, or null when it isn't only a table. Blank lines
     * around it are ignored; anything else in the fence keeps it a code block.
     */
    fun fenced(body: List<String>): List<List<String>>? {
        val ls = body.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (ls.size < 2) return null
        if (pipeStart(ls, 0)) {
            val (rows, end) = pipeAt(ls, 0)
            return if (end == ls.size) rows else null
        }
        if (isBorder(ls[0]) || isBoxRow(ls[0])) {
            // a grid without a top border still counts when a border follows the first row
            val start = if (isBorder(ls[0])) 0 else if (ls.size > 1 && isBorder(ls[1])) 0 else return null
            val r = asciiAt(ls, start) ?: return null
            return if (r.second == ls.size) r.first else null
        }
        return null
    }
}
