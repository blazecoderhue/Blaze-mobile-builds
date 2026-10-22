package com.blaze.assistant.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.26.1 visual answers, pure part (RichKitTest): the interactive blocks a reply can carry as fenced code blocks
 * with a JSON body: ```chart, ```choices (or ```buttons), ```form, ```tool and ```compare. Each parser returns null on
 * anything it can't use, and the caller then shows the plain code block instead. Nothing here runs code: tool
 * formulas go through [Expr].
 */
object RichKit {
    /** Fence languages that are interactive blocks (```buttons is an alias of ```choices). */
    val LANGS = mapOf("chart" to "chart", "choices" to "choices", "buttons" to "choices", "form" to "form",
        "tool" to "tool", "calculator" to "tool", "compare" to "compare", "table" to "table", "apps" to "apps")

    const val MAX_POINTS = 60
    const val MAX_SERIES = 4
    const val MAX_SLICES = 8
    const val MAX_CHOICES = 6
    const val MAX_FIELDS = 8
    const val MAX_INPUTS = 6
    const val MAX_OUTPUTS = 5
    const val MAX_COLS = 3
    const val MAX_ROWS = 14

    // ---------- apps (v0.33.3) ----------
    /** One installed app on the card: its label and real package (so a quoted tile tells the agent exactly which app). */
    data class AppTile(val label: String, val pkg: String, val game: Boolean = false)
    data class AppsCard(val title: String, val apps: List<AppTile>)

    /** ```apps {"title":"Your apps (42)","apps":[{"label":"Rally Horizon","pkg":"com.x.rally","game":true}]} */
    fun apps(body: String): AppsCard? {
        val o = json(body) as? JSONObject ?: return null
        val arr = o.optJSONArray("apps") ?: return null
        val list = (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val pkg = a.optString("pkg").ifBlank { a.optString("package") }.trim()
            val label = a.optString("label").ifBlank { a.optString("name") }.trim()
            if (pkg.isEmpty() || !Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(pkg)) null
            else AppTile(label.ifEmpty { pkg }, pkg, a.optBoolean("game"))
        }.distinctBy { it.pkg }
        if (list.isEmpty()) return null
        return AppsCard(o.optString("title").trim(), list.take(300))
    }

    // ---------- chart ----------
    enum class ChartType { BAR, LINE, PIE, SCATTER }
    data class Series(val name: String, val values: List<Double>)
    data class Pt(val x: Double, val y: Double, val label: String = "")
    data class Chart(
        val type: ChartType, val title: String, val labels: List<String>, val series: List<Series>,
        /** Scatter points, one list per series. */
        val points: List<List<Pt>> = emptyList(), val pointNames: List<String> = emptyList(),
        val unit: String = "", val xLabel: String = "", val yLabel: String = "",
    )

    // ---------- choices ----------
    data class Choice(val label: String, val send: String)
    data class Choices(val prompt: String, val options: List<Choice>)

    // ---------- form ----------
    enum class FieldType { TEXT, NUMBER, SELECT, TOGGLE }
    data class Field(val id: String, val label: String, val type: FieldType, val options: List<String> = emptyList(),
                     val default: String = "", val placeholder: String = "", val unit: String = "")
    data class Form(val title: String, val fields: List<Field>, val submit: String, val intro: String = "")

    // ---------- tool ----------
    enum class InputType { SLIDER, NUMBER, SELECT }
    data class Opt(val label: String, val value: Double)
    data class Input(val id: String, val label: String, val type: InputType, val min: Double = 0.0, val max: Double = 100.0,
                     val step: Double = 1.0, val default: Double = 0.0, val options: List<Opt> = emptyList(),
                     val prefix: String = "", val suffix: String = "")
    data class Output(val label: String, val formula: String, val decimals: Int = 2, val prefix: String = "", val suffix: String = "",
                      val big: Boolean = false)
    data class Tool(val title: String, val inputs: List<Input>, val outputs: List<Output>, val note: String = "")

    // ---------- table card (v0.28.1) ----------
    /** A table card: bold [title] (may be blank), muted [headers], wrapping cells; [sticky] keeps the first column in view. */
    data class TableCard(val title: String, val headers: List<String>, val rows: List<List<String>>, val sticky: Boolean = true) {
        val cols: Int get() = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
    }
    const val MAX_TABLE_COLS = 8
    const val MAX_TABLE_ROWS = 60

    /**
     * ```table {"title":"..","columns":["Plan","Price"],"rows":[["Basic","K50"]],"sticky":true}; rows may also be
     * objects keyed by column name. Null when there's nothing to show.
     */
    fun table(body: String): TableCard? {
        val o = json(body) as? JSONObject ?: return null
        val heads = (o.optJSONArray("columns") ?: o.optJSONArray("headers") ?: o.optJSONArray("header")).list()
            .map { clean(it?.toString() ?: "", 60) }.take(MAX_TABLE_COLS)
        val rows = o.optJSONArray("rows").list().mapNotNull { r ->
            when (r) {
                is JSONArray -> r.list().map { clean(it?.toString() ?: "", 300) }
                is JSONObject -> if (heads.isEmpty()) null else heads.map { h -> clean(r.opt(h)?.toString() ?: "", 300) }
                else -> null
            }?.take(MAX_TABLE_COLS)?.takeIf { row -> row.any { it.isNotEmpty() } }
        }.take(MAX_TABLE_ROWS)
        if (rows.isEmpty() || (heads.isEmpty() && rows.first().size < 2)) return null
        return TableCard(clean(o.str("title", "caption"), 80), heads, rows, o.optBoolean("sticky", true))
    }

    /** A Markdown pipe table (AnswerCards' rows: the first is the header) as a card. */
    fun tableFromMd(rows: List<List<String>>, title: String = ""): TableCard? {
        if (rows.isEmpty()) return null
        val body = rows.drop(1).filter { r -> r.any { it.isNotBlank() } }
        return TableCard(title, rows.first(), body, sticky = true)
    }

    /** Plain text for Copy: the title, then the rows with columns separated by two spaces (cells as plain text). */
    fun tablePlain(t: TableCard): String = buildString {
        if (t.title.isNotBlank()) append(t.title).append("\n\n")
        val all = listOfNotNull(t.headers.takeIf { it.isNotEmpty() }) + t.rows
        val w = (0 until t.cols).map { k -> all.maxOf { plainCell(it.getOrElse(k) { "" }).length } }
        all.forEach { r -> append((0 until t.cols).joinToString("  ") { k -> plainCell(r.getOrElse(k) { "" }).padEnd(w[k]) }.trimEnd()).append('\n') }
    }.trimEnd()

    /** Tab-separated values for Copy as TSV (pastes into a spreadsheet as cells). */
    fun tableTsv(t: TableCard): String = (listOfNotNull(t.headers.takeIf { it.isNotEmpty() }) + t.rows)
        .joinToString("\n") { r -> (0 until t.cols).joinToString("\t") { k -> plainCell(r.getOrElse(k) { "" }).replace('\t', ' ').replace('\n', ' ') } }

    /** A cell without Markdown symbols (**bold**, `code`, [text](url) → text). */
    fun plainCell(s: String): String = s.replace(Regex("""\[([^\]]+)]\((?:[^)]+)\)"""), "$1")
        .replace(Regex("""\*\*(.+?)\*\*|__(.+?)__"""), "$1$2").replace(Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)"""), "$1")
        .replace("`", "").replace("<br>", " ").replace("<br/>", " ").trim()

    // ---------- compare ----------
    data class CompareRow(val label: String, val values: List<String>)
    data class Compare(val title: String, val columns: List<String>, val rows: List<CompareRow>, val verdict: String = "", val pick: Int = -1)

    /** Reads a JSON body leniently: tolerates a ```json-style wrapper, smart quotes and trailing commas. */
    fun json(body: String): Any? {
        var t = body.trim()
        if (t.isEmpty()) return null
        t = t.replace('\u201C', '"').replace('\u201D', '"')
        t = t.replace(Regex(""",\s*([}\]])"""), "$1")
        return try {
            when (t.first()) {
                '{' -> JSONObject(t)
                '[' -> JSONArray(t)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun JSONObject.str(vararg keys: String): String {
        for (k in keys) if (has(k) && !isNull(k)) { val v = opt(k); if (v is String || v is Number || v is Boolean) return v.toString().trim() }
        return ""
    }

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> v.trim().replace(",", "").removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    private fun JSONObject.dbl(def: Double, vararg keys: String): Double {
        for (k in keys) num(opt(k))?.let { return it }
        return def
    }

    private fun JSONArray?.list(): List<Any?> = if (this == null) emptyList() else (0 until length()).map { opt(it) }

    private fun clean(s: String, max: Int = 80): String = s.replace(Regex("\\s+"), " ").trim().take(max)

    /** Only simple names are allowed as tool input ids (they appear in formulas). */
    private val idRe = Regex("^[A-Za-z_][A-Za-z0-9_]{0,23}$")

    fun chart(body: String): Chart? {
        val o = json(body) as? JSONObject ?: return null
        val type = when (o.str("type", "kind").lowercase()) {
            "bar", "bars", "column", "columns", "" -> ChartType.BAR
            "line", "lines", "area", "trend" -> ChartType.LINE
            "pie", "donut", "doughnut" -> ChartType.PIE
            "scatter", "points", "dots" -> ChartType.SCATTER
            else -> return null
        }
        val title = clean(o.str("title"), 90)
        val unit = clean(o.str("unit", "units"), 12)
        val xL = clean(o.str("xLabel", "x_label", "xlabel"), 30); val yL = clean(o.str("yLabel", "y_label", "ylabel"), 30)
        if (type == ChartType.SCATTER) {
            val sets = ArrayList<List<Pt>>(); val names = ArrayList<String>()
            fun pts(arr: JSONArray?): List<Pt> = arr.list().mapNotNull { p ->
                when (p) {
                    is JSONArray -> { val x = num(p.opt(0)); val y = num(p.opt(1)); if (x != null && y != null) Pt(x, y, clean(p.optString(2, ""), 30)) else null }
                    is JSONObject -> { val x = num(p.opt("x")); val y = num(p.opt("y")); if (x != null && y != null) Pt(x, y, clean(p.str("label", "name"), 30)) else null }
                    else -> null
                }
            }.take(MAX_POINTS)
            o.optJSONArray("series")?.list()?.forEach { s -> if (s is JSONObject) { val p = pts(s.optJSONArray("points") ?: s.optJSONArray("data")); if (p.isNotEmpty()) { sets += p; names += clean(s.str("name", "label"), 30) } } }
            if (sets.isEmpty()) { val p = pts(o.optJSONArray("points") ?: o.optJSONArray("data")); if (p.isNotEmpty()) { sets += p; names += "" } }
            if (sets.isEmpty()) return null
            return Chart(type, title, emptyList(), emptyList(), sets.take(MAX_SERIES), names.take(MAX_SERIES), unit, xL, yL)
        }
        var labels: List<String> = (o.optJSONArray("labels") ?: o.optJSONArray("x") ?: o.optJSONArray("categories")).list().map { clean(it?.toString() ?: "", 24) }
        val series = ArrayList<Series>()
        o.optJSONArray("series")?.list()?.forEach { s ->
            if (s is JSONObject) {
                val v = (s.optJSONArray("values") ?: s.optJSONArray("data")).list().map { num(it) ?: Double.NaN }
                if (v.any { it.isFinite() }) series += Series(clean(s.str("name", "label"), 30), v)
            }
        }
        // {"data":[{"label":"A","value":3}, ...]} or {"data":{"A":3}} or {"values":[...]} with labels.
        if (series.isEmpty()) {
            val d = o.opt("data")
            when {
                d is JSONArray && d.list().all { it is JSONObject } && d.length() > 0 -> {
                    labels = d.list().map { clean((it as JSONObject).str("label", "name", "x"), 24) }
                    series += Series("", d.list().map { num((it as JSONObject).opt("value") ?: it.opt("y")) ?: Double.NaN })
                }
                d is JSONObject -> { val ks = d.keys().asSequence().toList(); labels = ks.map { clean(it, 24) }; series += Series("", ks.map { num(d.opt(it)) ?: Double.NaN }) }
                else -> {
                    val v = (o.optJSONArray("values") ?: d as? JSONArray).list().map { num(it) ?: Double.NaN }
                    if (v.any { it.isFinite() }) series += Series("", v)
                }
            }
        }
        if (series.isEmpty()) return null
        val n = maxOf(labels.size, series.maxOf { it.values.size }).coerceAtMost(MAX_POINTS)
        if (n == 0) return null
        labels = (0 until n).map { labels.getOrElse(it) { "${it + 1}" } }
        val ss = series.take(if (type == ChartType.PIE) 1 else MAX_SERIES).map { s -> s.copy(values = (0 until n).map { s.values.getOrElse(it) { Double.NaN } }) }
        if (type == ChartType.PIE) {
            val vals = ss[0].values
            if (vals.none { it.isFinite() && it > 0 }) return null
            if (n > MAX_SLICES) {
                // Keep the biggest slices; fold the rest into "Other".
                val order = vals.indices.sortedByDescending { vals[it].takeIf { v -> v.isFinite() } ?: 0.0 }
                val keep = order.take(MAX_SLICES - 1).sorted()
                val other = order.drop(MAX_SLICES - 1).sumOf { vals[it].takeIf { v -> v.isFinite() && v > 0 } ?: 0.0 }
                return Chart(type, title, keep.map { labels[it] } + "Other", listOf(Series(ss[0].name, keep.map { vals[it] } + other)), unit = unit)
            }
        }
        return Chart(type, title, labels, ss, unit = unit, xLabel = xL, yLabel = yL)
    }

    fun choices(body: String): Choices? {
        val j = json(body)
        val (prompt, arr) = when (j) {
            is JSONObject -> clean(j.str("prompt", "title", "question"), 120) to (j.optJSONArray("options") ?: j.optJSONArray("choices") ?: j.optJSONArray("buttons"))
            is JSONArray -> "" to j
            else -> {
                // Plain lines ("- Option") are fine too, but only once the block is closed (see [expandCode]).
                val lines = body.lines().map { it.trim().removePrefix("-").removePrefix("*").removePrefix("•").trim() }.filter { it.isNotBlank() }
                if (lines.isEmpty() || lines.any { it.startsWith("{") || it.startsWith("[") }) return null
                return Choices("", lines.take(MAX_CHOICES).map { Choice(clean(it, 60), clean(it, 300)) })
            }
        }
        val opts = arr.list().mapNotNull { v ->
            when (v) {
                is String -> v.trim().takeIf { it.isNotEmpty() }?.let { Choice(clean(it, 60), clean(it, 300)) }
                is JSONObject -> { val l = clean(v.str("label", "text", "title"), 60); if (l.isEmpty()) null else Choice(l, clean(v.str("send", "message", "value").ifBlank { l }, 300)) }
                else -> null
            }
        }.distinctBy { it.label.lowercase() }.take(MAX_CHOICES)
        return if (opts.isEmpty()) null else Choices(prompt, opts)
    }

    fun form(body: String): Form? {
        val o = json(body) as? JSONObject ?: return null
        val fields = o.optJSONArray("fields").list().mapIndexedNotNull { i, f ->
            if (f !is JSONObject) return@mapIndexedNotNull null
            val label = clean(f.str("label", "name", "id"), 40)
            if (label.isEmpty()) return@mapIndexedNotNull null
            val opts = f.optJSONArray("options").list().mapNotNull { (it as? JSONObject)?.str("label", "value") ?: it?.toString() }.map { clean(it, 40) }.filter { it.isNotEmpty() }.take(8)
            val type = when (f.str("type").lowercase()) {
                "number", "num", "int", "integer", "amount" -> FieldType.NUMBER
                "select", "choice", "dropdown", "radio", "options" -> if (opts.isEmpty()) FieldType.TEXT else FieldType.SELECT
                "toggle", "switch", "checkbox", "bool", "boolean" -> FieldType.TOGGLE
                else -> if (opts.isNotEmpty()) FieldType.SELECT else FieldType.TEXT
            }
            val def = f.opt("default") ?: f.opt("value")
            Field(clean(f.str("id").ifBlank { "f$i" }, 24), label, type, opts,
                default = when (type) {
                    FieldType.TOGGLE -> if (def == true || def?.toString()?.lowercase() in setOf("true", "yes", "on", "1")) "yes" else "no"
                    FieldType.SELECT -> def?.toString()?.takeIf { d -> opts.any { it.equals(d, true) } } ?: opts.first()
                    else -> def?.takeIf { it != JSONObject.NULL }?.toString()?.let { clean(it, 80) } ?: ""
                },
                placeholder = clean(f.str("placeholder", "hint"), 40), unit = clean(f.str("unit", "suffix"), 10))
        }.take(MAX_FIELDS)
        if (fields.isEmpty()) return null
        return Form(clean(o.str("title"), 80), fields, clean(o.str("submit", "button").ifBlank { "Send" }, 24), clean(o.str("intro", "description"), 160))
    }

    /**
     * The message a submitted form sends, compact: "Trip plan: City: Lusaka · Days: 3 · With kids: yes". Blank text
     * fields are left out; a form with nothing filled sends just its title (or the submit label).
     */
    fun formMessage(form: Form, values: Map<String, String>): String {
        val parts = form.fields.mapNotNull { f ->
            val v = (values[f.id] ?: f.default).trim()
            if (v.isEmpty()) null else "${f.label}: $v${if (f.unit.isNotEmpty() && f.type == FieldType.NUMBER) " ${f.unit}" else ""}"
        }
        val head = form.title.ifBlank { form.submit }
        return if (parts.isEmpty()) head else "$head: " + parts.joinToString(" · ")
    }

    /** A number field accepts digits, one dot and a leading minus (pasted commas are dropped). */
    fun numberInput(s: String): String {
        val t = s.replace(",", "").filter { it.isDigit() || it == '.' || it == '-' }
        val neg = t.startsWith("-")
        val digits = t.replace("-", "")
        val dot = digits.indexOf('.')
        val one = if (dot < 0) digits else digits.substring(0, dot + 1) + digits.substring(dot + 1).replace(".", "")
        return (if (neg) "-" else "") + one.take(15)
    }

    fun tool(body: String): Tool? {
        val o = json(body) as? JSONObject ?: return null
        val inputs = o.optJSONArray("inputs").list().mapNotNull { f ->
            if (f !is JSONObject) return@mapNotNull null
            val id = f.str("id", "name")
            if (!idRe.matches(id) || id.lowercase() in Expr.FUNCTIONS || id.lowercase() == "pi") return@mapNotNull null
            val opts = f.optJSONArray("options").list().mapIndexedNotNull { i, v ->
                when (v) {
                    is JSONObject -> { val l = clean(v.str("label", "name"), 30); val n = num(v.opt("value")); if (l.isEmpty()) null else Opt(l, n ?: i.toDouble()) }
                    null -> null
                    else -> clean(v.toString(), 30).takeIf { it.isNotEmpty() }?.let { Opt(it, num(v) ?: i.toDouble()) }
                }
            }.take(8)
            val type = when (f.str("type").lowercase()) {
                "slider", "range" -> InputType.SLIDER
                "select", "choice", "dropdown", "options" -> if (opts.isEmpty()) InputType.NUMBER else InputType.SELECT
                else -> if (opts.isNotEmpty()) InputType.SELECT else InputType.NUMBER
            }
            var min = f.dbl(0.0, "min"); var max = f.dbl(if (type == InputType.SLIDER) 100.0 else 1e12, "max")
            if (max < min) { val t = min; min = max; max = t }
            val step = f.dbl(1.0, "step").let { if (it <= 0.0) 1.0 else it }
            val def = when (type) {
                InputType.SELECT -> num(f.opt("default") ?: f.opt("value"))?.takeIf { d -> opts.any { it.value == d } } ?: opts.first().value
                else -> f.dbl(if (type == InputType.SLIDER) min else 0.0, "default", "value").let { if (type == InputType.SLIDER) it.coerceIn(min, max) else it }
            }
            Input(id, clean(f.str("label").ifBlank { id }, 32), type, min, max, step, def, opts, clean(f.str("prefix"), 6), clean(f.str("suffix", "unit"), 8))
        }.distinctBy { it.id }.take(MAX_INPUTS)
        if (inputs.isEmpty()) return null
        val names = inputs.map { it.id }.toSet()
        val outputs = o.optJSONArray("outputs").list().mapIndexedNotNull { i, f ->
            if (f !is JSONObject) return@mapIndexedNotNull null
            val formula = f.str("formula", "value", "expr").take(Expr.MAX_LEN)
            if (formula.isEmpty() || Expr.check(formula, names) != null) return@mapIndexedNotNull null
            Output(clean(f.str("label", "name").ifBlank { "Result" }, 32), formula, f.dbl(2.0, "decimals").toInt().coerceIn(0, 6),
                clean(f.str("prefix"), 6), clean(f.str("suffix", "unit"), 8), big = f.optBoolean("big", false) || f.optBoolean("main", false))
        }.take(MAX_OUTPUTS)
        if (outputs.isEmpty()) return null
        // The last output is the headline unless one says it's the main one.
        val outs = if (outputs.none { it.big }) outputs.mapIndexed { i, x -> if (i == outputs.size - 1) x.copy(big = true) else x } else outputs
        return Tool(clean(o.str("title").ifBlank { "Calculator" }, 60), inputs, outs, clean(o.str("note"), 160))
    }

    /** Snaps a slider value to its step, inside min..max. */
    fun snap(v: Double, inp: Input): Double {
        if (inp.type != InputType.SLIDER) return v
        val k = Math.round((v - inp.min) / inp.step)
        val s = inp.min + k * inp.step
        return Math.round(s.coerceIn(inp.min, inp.max) * 1e9) / 1e9
    }

    /** Every output's text for these input values (blank when a formula can't be worked out). */
    fun compute(tool: Tool, vals: Map<String, Double>): List<String> =
        tool.outputs.map { Expr.format(Expr.eval(it.formula, vals), it.decimals, it.prefix, it.suffix) }

    fun defaults(tool: Tool): Map<String, Double> = tool.inputs.associate { it.id to it.default }

    /** How an input's value reads beside its label ("K450", "10 %", "3"). */
    fun showInput(inp: Input, v: Double): String {
        if (inp.type == InputType.SELECT) return inp.options.firstOrNull { it.value == v }?.label ?: ""
        val d = if (v == Math.floor(v)) 0 else if (inp.step < 0.1) 2 else 1
        return inp.prefix + Expr.format(v, d) + (if (inp.suffix.isEmpty()) "" else if (inp.suffix == "%") "%" else " " + inp.suffix)
    }

    fun compare(body: String): Compare? {
        val o = json(body) as? JSONObject ?: return null
        val raw = o.optJSONArray("columns") ?: o.optJSONArray("items") ?: o.optJSONArray("options") ?: return null
        // Columns as names with "rows", or as objects {"name": .., "specs"/"values": {label: value}}.
        if (raw.list().all { it is JSONObject }) {
            val cols = raw.list().map { it as JSONObject }.take(MAX_COLS)
            val names = cols.map { clean(it.str("name", "title", "label"), 30) }
            val labels = LinkedHashSet<String>()
            val maps = cols.map { c ->
                val m = c.optJSONObject("values") ?: c.optJSONObject("specs") ?: c.optJSONObject("items") ?: JSONObject()
                m.keys().asSequence().associate { k -> labels += clean(k, 30); clean(k, 30) to clean(m.opt(k)?.toString() ?: "", 80) }
            }
            val rows = labels.take(MAX_ROWS).map { l -> CompareRow(l, maps.map { it[l] ?: "" }) }
            if (names.size < 2 || rows.isEmpty()) return null
            return Compare(clean(o.str("title"), 80), names, rows, clean(o.str("verdict", "summary"), 200), pick(o, names))
        }
        val names = raw.list().map { clean(it?.toString() ?: "", 30) }.take(MAX_COLS)
        if (names.size < 2) return null
        val rows = o.optJSONArray("rows").list().mapNotNull { r ->
            when (r) {
                is JSONObject -> { val l = clean(r.str("label", "name", "feature"), 30); val v = r.optJSONArray("values").list().map { clean(it?.toString() ?: "", 80) }; if (l.isEmpty() || v.isEmpty()) null else CompareRow(l, (0 until names.size).map { v.getOrElse(it) { "" } }) }
                is JSONArray -> { val all = r.list().map { clean(it?.toString() ?: "", 80) }; if (all.size < 2) null else CompareRow(all[0].take(30), (0 until names.size).map { all.getOrElse(it + 1) { "" } }) }
                else -> null
            }
        }.take(MAX_ROWS)
        if (rows.isEmpty()) return null
        return Compare(clean(o.str("title"), 80), names, rows, clean(o.str("verdict", "summary"), 200), pick(o, names))
    }

    private fun pick(o: JSONObject, names: List<String>): Int {
        val p = o.opt("pick") ?: o.opt("winner") ?: return -1
        num(p)?.let { n -> return n.toInt().takeIf { it in names.indices } ?: -1 }
        return names.indexOfFirst { it.equals(p.toString().trim(), ignoreCase = true) }
    }

    // ---------- chart maths ----------
    /** A rounded axis: (min, max, step) covering [lo]..[hi] with about [target] ticks; bar charts start at 0. */
    fun niceAxis(lo0: Double, hi0: Double, target: Int = 4, fromZero: Boolean = true): Triple<Double, Double, Double> {
        var lo = if (fromZero) minOf(0.0, lo0) else lo0
        var hi = if (fromZero) maxOf(0.0, hi0) else hi0
        if (!lo.isFinite() || !hi.isFinite()) return Triple(0.0, 1.0, 0.25)
        if (hi == lo) { hi += if (hi == 0.0) 1.0 else Math.abs(hi) * 0.5; if (!fromZero) lo -= Math.abs(lo) * 0.5 }
        val raw = (hi - lo) / target.coerceAtLeast(1)
        val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
        return Triple(Math.floor(lo / step) * step, Math.ceil(hi / step) * step, step)
    }

    /** v0.26.2: an x-axis category label at most [max] characters, cut with an ellipsis ("Livingstone" → "Livingst…"), never silently. */
    fun axisLabel(l: String, max: Int = 10): String {
        val t = l.trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "\u2026"
    }

    /** Short axis labels: 1200 → 1.2K, 3500000 → 3.5M. */
    fun short(v: Double): String {
        val a = Math.abs(v)
        val (d, s) = when { a >= 1e9 -> 1e9 to "B"; a >= 1e6 -> 1e6 to "M"; a >= 1e4 -> 1e3 to "K"; else -> 1.0 to "" }
        val x = v / d
        val t = if (x == Math.floor(x)) x.toLong().toString() else String.format(java.util.Locale.US, "%.1f", x).trimEnd('0').trimEnd('.')
        return t + s
    }

    /** What a tapped bar / point says: "Mar · Sales: 1,200 K". */
    fun tipText(c: Chart, series: Int, index: Int): String {
        if (c.type == ChartType.SCATTER) {
            val p = c.points.getOrNull(series)?.getOrNull(index) ?: return ""
            val name = c.pointNames.getOrNull(series).orEmpty()
            return listOf(p.label, name).filter { it.isNotBlank() }.joinToString(" · ").let { if (it.isEmpty()) "" else "$it: " } +
                "${c.xLabel.ifBlank { "x" }} ${Expr.format(p.x, dec(p.x))}, ${c.yLabel.ifBlank { "y" }} ${Expr.format(p.y, dec(p.y))}${unitSuffix(c)}"
        }
        val s = c.series.getOrNull(series) ?: return ""
        val v = s.values.getOrNull(index) ?: return ""
        if (!v.isFinite()) return ""
        val label = c.labels.getOrNull(index).orEmpty()
        val pct = if (c.type == ChartType.PIE) { val t = s.values.filter { it.isFinite() && it > 0 }.sum(); if (t > 0) " (${Expr.format(v / t * 100, 0)}%)" else "" } else ""
        return listOf(label, s.name).filter { it.isNotBlank() }.joinToString(" · ") + ": " + Expr.format(v, dec(v)) + unitSuffix(c) + pct
    }

    private fun unitSuffix(c: Chart) = if (c.unit.isEmpty()) "" else if (c.unit == "%") "%" else " " + c.unit
    private fun dec(v: Double) = if (v == Math.floor(v)) 0 else 2

    /** Which bar a tap at [x] (0..width) hits in a chart of [n] groups. */
    fun barIndex(x: Float, width: Float, n: Int): Int = if (n <= 0 || width <= 0f) -1 else (x / (width / n)).toInt().coerceIn(0, n - 1)

    /** Which pie slice a tap at angle [deg] (0 at 12 o'clock, clockwise) falls in. */
    fun sliceAt(deg: Float, values: List<Double>): Int {
        val pos = values.map { if (it.isFinite() && it > 0) it else 0.0 }
        val t = pos.sum(); if (t <= 0) return -1
        val a = ((deg % 360f) + 360f) % 360f
        var acc = 0.0
        pos.forEachIndexed { i, v -> acc += v / t * 360.0; if (a < acc && v > 0) return i }
        return pos.indexOfLast { it > 0 }
    }

    /** The point nearest to a tap, within [radius] px; null if none is that close. [pts] are screen positions. */
    fun nearest(tx: Float, ty: Float, pts: List<Pair<Float, Float>>, radius: Float): Int? {
        var best = -1; var bd = radius * radius
        pts.forEachIndexed { i, (x, y) -> val d = (x - tx) * (x - tx) + (y - ty) * (y - ty); if (d <= bd) { bd = d; best = i } }
        return best.takeIf { it >= 0 }
    }

    // ---------- copy as text ----------
    private val fenceRe = Regex("""(?m)^[ \t]*```[ \t]*([A-Za-z]+)[ \t]*\n([\s\S]*?)\n[ \t]*```[ \t]*$""")

    /**
     * A reply with its interactive blocks turned into readable text, for Copy: a chart becomes its values, a compare
     * card a small table, a tool its results with the starting numbers, choices a list. Unusable blocks stay as they are.
     */
    fun readable(src: String): String = fenceRe.replace(src) { m ->
        val kind = LANGS[m.groupValues[1].lowercase()] ?: return@replace m.value
        val body = m.groupValues[2]
        val out = try {
            when (kind) {
                "chart" -> chart(body)?.let { c ->
                    val head = if (c.title.isNotBlank()) c.title + "\n" else ""
                    head + if (c.type == ChartType.SCATTER) c.points.flatten().joinToString("\n") { "- ${it.label.ifBlank { Expr.format(it.x, dec(it.x)) }}: ${Expr.format(it.y, dec(it.y))}" }
                    else c.labels.indices.joinToString("\n") { i -> "- " + c.labels[i] + ": " + c.series.joinToString(", ") { s ->
                        (if (s.name.isNotBlank() && c.series.size > 1) s.name + " " else "") + Expr.format(s.values[i], dec(s.values[i])) + unitSuffix(c) } }
                }
                "compare" -> compare(body)?.let { c ->
                    (if (c.title.isNotBlank()) c.title + "\n\n" else "") + "| | " + c.columns.joinToString(" | ") + " |\n|" + "---|".repeat(c.columns.size + 1) + "\n" +
                        c.rows.joinToString("\n") { r -> "| ${r.label} | " + r.values.joinToString(" | ") + " |" } + (if (c.verdict.isNotBlank()) "\n\n" + c.verdict else "")
                }
                "tool" -> tool(body)?.let { t ->
                    val d = defaults(t)
                    t.title + " (" + t.inputs.joinToString(", ") { "${it.label} ${showInput(it, d[it.id] ?: it.default)}" } + ")\n" +
                        t.outputs.zip(compute(t, d)).joinToString("\n") { (o, v) -> "- ${o.label}: ${v.ifEmpty { "\u2014" }}" }
                }
                "table" -> table(body)?.let { tablePlain(it) }
                "apps" -> apps(body)?.let { c -> (c.title.ifBlank { "Apps" }) + "\n" + c.apps.joinToString("\n") { "- ${it.label} (${it.pkg})" } }
                "choices" -> choices(body)?.let { c -> (if (c.prompt.isNotBlank()) c.prompt + "\n" else "") + c.options.joinToString("\n") { "- ${it.label}" } }
                "form" -> form(body)?.let { f -> (f.title.ifBlank { f.submit }) + "\n" + f.fields.joinToString("\n") { "- ${it.label}:" } }
                else -> null
            }
        } catch (_: Exception) { null }
        out ?: m.value
    }
}
