package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.44.0: the one parser for tool calls a model wrote as plain text in the main agent loop. Smaller models answer
 * with `browser_show()`, `open_app("WhatsApp")`, `desktop(action="start")`, `` `set_alarm(hour=7, minute=0)` ``,
 * `name {json}` or a bare `{"name": …, "arguments": …}` instead of calling the tool. [ToolLeak] already handles the
 * bracket / tag forms (`[/name k=v]`, `<tool_call>…`, `[TOOL_CALLS]…`); this file adds the function-call forms.
 *
 * What the loop does with it: a call to a tool that exists becomes a real call ([toCalls]); function syntax for a tool
 * that doesn't exist is taken out ([strip]) and the model is asked once more for a plain answer ([has]). The user never
 * sees function syntax, while streaming either ([strip] with final = false hides an unfinished call line).
 *
 * Kept deliberately small and self-contained: the Halo lane (DuoText on feat/bank-mode) parses the same forms, so the
 * two can be folded into this one file. Only a line that is nothing but a call counts, never a call inside a sentence,
 * and code in a fenced block (```python …```) is left alone. Pure Kotlin; TextCallsTest.
 */
object TextCalls {
    data class Call(val name: String, val args: JSONObject, val raw: String)

    private const val ID = """[A-Za-z_][A-Za-z0-9_.:]*"""
    // name(...) alone on a line, optional backticks and a trailing semicolon.
    private val pyLine = Regex("""(?m)^[ \t]*`{0,3}[ \t]*($ID)[ \t]*\(([^\n]*)\)[ \t]*;?[ \t]*`{0,3}[ \t]*$""")
    // name {json} alone on a line.
    private val braceLine = Regex("""(?m)^[ \t]*`?($ID)[ \t]*(\{[^\n]*\})[ \t]*`?[ \t]*$""")
    // ```lang\n…``` blocks (the language decides whether the body may hold calls).
    private val fence = Regex("""```([A-Za-z_\-]*)[ \t]*\n?([\s\S]*?)```""")
    private val kv = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*[=:]\s*("(?:[^"\\]|\\.)*"|'[^']*'|\[[^\]]*\]|\{[^}]*\}|[^,)]+)""")
    // While streaming: a line that starts like a call and isn't closed yet.
    private val openTail = Regex("""(?:^|\n)[ \t]*`?[A-Za-z]+_[A-Za-z0-9_]*[ \t]*[({][^\n]*$""")
    private val callLangs = setOf("", "json", "tool", "tool_call", "tool_code", "python", "py")

    /** A name that looks like a tool rather than a word: snake_case, or one of [known]. */
    fun toolish(name: String, known: Collection<String> = emptySet()): Boolean {
        val n = name.substringAfterLast('.').substringAfterLast(':')
        return (n.contains('_') && n.length in 3..48 && !n.startsWith("__")) || known.any { it.equals(n, true) }
    }

    private fun value(v: String): Any {
        val t = v.trim()
        if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))))
            return t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\n", "\n")
        if (t.startsWith("[") || t.startsWith("{")) ToolCalls.parseArgs(if (t.startsWith("[")) "{\"v\":$t}" else t)?.let { o -> return if (t.startsWith("[")) o.opt("v") ?: t else o }
        return when (t) { "true", "True" -> true; "false", "False" -> false; "None", "null" -> JSONObject.NULL; else -> t.toLongOrNull() ?: t.toDoubleOrNull() ?: t }
    }

    /** `key="v", n=2` or `{"key": "v"}`; a lone positional value comes back under "_0". */
    fun parseArgs(s: String): JSONObject {
        val t = s.trim()
        if (t.isEmpty()) return JSONObject()
        if (t.startsWith("{")) return ToolCalls.parseArgs(t) ?: JSONObject()
        val o = JSONObject()
        kv.findAll(t).forEach { m -> o.put(m.groupValues[1], value(m.groupValues[2])) }
        if (o.length() == 0) o.put("_0", value(t))
        return o
    }

    /** Text ranges that are code to leave alone (fenced blocks in a language that isn't a call format). */
    private fun codeRanges(text: String): List<IntRange> = fence.findAll(text)
        .filter { m -> m.groupValues[1].lowercase() !in callLangs || !onlyCallLines(m.groupValues[2]) }
        .map { it.range }.toList()

    private fun onlyCallLines(body: String): Boolean {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        val joined = lines.joinToString("\n")
        if (joined.startsWith("{") && jsonCall(joined) != null) return true
        return lines.all { l -> pyLine.matchEntire(l)?.let { toolish(it.groupValues[1]) } == true || braceLine.matchEntire(l)?.let { toolish(it.groupValues[1]) } == true }
    }

    /** `{"name": "x", "arguments": {…}}` (also tool / function.name / parameters / args). */
    private fun jsonCall(s: String): Call? {
        val o = ToolCalls.parseArgs(s) ?: return null
        val f = o.optJSONObject("function")
        val n = (f?.optString("name") ?: o.optString("name").ifBlank { o.optString("tool").ifBlank { o.optString("tool_name") } }).trim()
        if (n.isEmpty() || n.contains(' ')) return null
        val hasArgs = (f ?: o).let { it.has("arguments") || it.has("args") || it.has("parameters") || it.has("input") }
        if (!hasArgs && f == null && !o.has("tool")) return null
        val a = f?.opt("arguments") ?: o.opt("arguments") ?: o.opt("args") ?: o.opt("parameters") ?: o.opt("input")
        return Call(n, ToolCalls.parseArgs(a) ?: JSONObject(), s)
    }

    /** Every function-style call in [text], in order. [known] widens "tool-ish" to tool names without an underscore. */
    fun find(text: String, known: Collection<String> = emptySet()): List<Call> {
        if (text.isBlank() || (!text.contains('(') && !text.contains('{'))) return emptyList()
        val skip = codeRanges(text)
        fun free(r: IntRange) = skip.none { it.first <= r.first && r.last <= it.last }
        val out = ArrayList<Pair<Int, Call>>()
        for (m in pyLine.findAll(text)) if (free(m.range) && toolish(m.groupValues[1], known))
            out += m.range.first to Call(m.groupValues[1], parseArgs(m.groupValues[2]), m.value.trim('\n'))
        for (m in braceLine.findAll(text)) if (free(m.range) && toolish(m.groupValues[1], known) && out.none { it.first == m.range.first })
            out += m.range.first to Call(m.groupValues[1], parseArgs(m.groupValues[2]), m.value.trim('\n'))
        // A bare JSON call: the whole reply, or a fenced json / tool block.
        val whole = text.trim()
        if (whole.startsWith("{") && whole.endsWith("}")) jsonCall(whole)?.let { out += 0 to it.copy(raw = whole) }
        for (m in fence.findAll(text)) if (m.groupValues[1].lowercase() in callLangs) {
            val b = m.groupValues[2].trim()
            if (b.startsWith("{")) jsonCall(b)?.let { c -> out.removeAll { it.first in m.range }; out += m.range.first to c.copy(raw = m.value) }
            else if (onlyCallLines(b)) {
                // The fence itself goes with its calls.
                val inner = out.filter { it.first in m.range }
                if (inner.isNotEmpty()) { out.removeAll { it.first in m.range }; inner.forEachIndexed { i, (_, c) -> out += m.range.first + i to (if (i == 0) c.copy(raw = m.value) else c.copy(raw = "")) } }
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** Is there function syntax the user must not see? */
    fun has(text: String, known: Collection<String> = emptySet()): Boolean = find(text, known).isNotEmpty()

    /** Nothing but calls (no words for the user). */
    fun onlyCalls(text: String, known: Collection<String> = emptySet()): Boolean {
        val c = find(text, known)
        return c.isNotEmpty() && strip(text, known = known).isBlank()
    }

    /** [text] without call lines (and, while streaming, without an unfinished one at the end). */
    fun strip(text: String, final: Boolean = true, known: Collection<String> = emptySet()): String {
        if (text.isEmpty() || (!text.contains('(') && !text.contains('{'))) return text
        var t = text
        for (c in find(t, known)) if (c.raw.isNotEmpty()) t = t.replace(c.raw, "")
        if (!final) openTail.find(t)?.let { m -> if (codeRanges(t).none { m.range.first in it } && t.count { it == '`' } % 6 == 0) t = t.substring(0, m.range.first) }
        return if (t == text) text else t.replace(Regex("""[ \t]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** The first required (else the first) parameter of [name] in [all], for a positional `open_app("WhatsApp")`. */
    private fun firstParam(all: JSONArray, name: String): String? {
        for (i in 0 until all.length()) {
            val f = all.optJSONObject(i)?.optJSONObject("function") ?: continue
            if (f.optString("name") != name) continue
            val p = f.optJSONObject("parameters") ?: return null
            p.optJSONArray("required")?.optString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            return p.optJSONObject("properties")?.keys()?.asSequence()?.firstOrNull()
        }
        return null
    }

    /** Real calls for the text calls that name a tool in [all], and the text left over (every call line stripped). */
    fun toCalls(text: String, all: JSONArray): Pair<JSONArray, String>? {
        val known = ToolCalls.names(all)
        val found = find(text, known)
        if (found.isEmpty()) return null
        val calls = JSONArray()
        for (c in found) {
            val n = ToolCalls.resolveName(c.name, known) ?: continue
            val a = c.args
            if (a.length() == 1 && a.has("_0")) firstParam(all, n)?.let { p -> val v = a.opt("_0"); a.remove("_0"); a.put(p, v) }
            a.remove("_0")
            calls.put(JSONObject().put("id", "textcall_${calls.length()}").put("type", "function")
                .put("function", JSONObject().put("name", n).put("arguments", a.toString())))
        }
        if (calls.length() == 0) return null
        return calls to strip(text, known = known)
    }

    // ---------------- 0.45.15: block calls (device /phoneqa 21 Oct 22:40, codestral on the "run" route) ----------------

    /**
     * The forms codestral wrote on the phone instead of calling the tool:
     *  - a tool name alone on a line, then `key: value`, `key=value` or `- key: value` lines
     *    ("open_settings\npage: dnd\ntap\ntext: \"Do Not Disturb\"\nwant: \"on\"");
     *  - a tool name with its pairs on the same line ("tap text=\"Dark mode\" want=\"on\"");
     *  - a tool name alone on a line, then a JSON object ("run_plan\n{\"steps\": [...]}");
     *  - a bare `{"steps": [...]}` (whole reply or a ```json block): that is run_plan's argument.
     * Only names in [known] count. A call missing a required parameter (a bare "tap") is dropped; [blockHas] still sees
     * the syntax so the loop asks once more instead of showing it.
     */
    data class Block(val name: String, val args: JSONObject, val lines: IntRange)

    private val pairLine = Regex("""^\s*(?:[-*]\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*[:=]\s*(.*?)\s*,?\s*$""")
    private val inlinePair = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*[:=]\s*("(?:[^"\\]|\\.)*"|'[^']*'|[^\s"']+)""")

    private fun blockValue(v: String): Any? {
        val t = v.trim().trimEnd(',')
        if (t.isEmpty() || t.equals("null", true) || t.equals("none", true)) return null
        return value(t)
    }

    private fun stepsJson(t: String): JSONObject? {
        val o = ToolCalls.parseArgs(t.trim()) ?: return null
        val steps = o.optJSONArray("steps") ?: return null
        if (steps.length() == 0 || (0 until steps.length()).none { steps.optJSONObject(it)?.has("do") == true }) return null
        return o
    }

    /** Block-form calls in [text], in order (see [Block]). */
    fun blocks(text: String, known: Collection<String>): List<Block> {
        if (text.isBlank()) return emptyList()
        val names = known.associateBy { it.lowercase() }
        val out = ArrayList<Block>()
        // A bare steps object: the whole reply, or a fenced json block.
        val whole = text.trim()
        if ("run_plan" in known) {
            if (whole.startsWith("{")) stepsJson(whole)?.let { return listOf(Block("run_plan", it, 0..0)) }
            for (m in fence.findAll(text)) if (m.groupValues[1].lowercase() in callLangs) stepsJson(m.groupValues[2])?.let { out += Block("run_plan", it, -1..-1) }
            if (out.isNotEmpty()) return out
        }
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i].trim().trim('`')
            val head = raw.substringBefore(' ').substringBefore('\t')
            val name = names[head.lowercase()]
            if (name == null) { i++; continue }
            val start = i
            val args = JSONObject()
            val restOfLine = raw.substring(head.length).trim()
            if (restOfLine.startsWith("{")) {
                ToolCalls.parseArgs(restOfLine)?.let { o -> o.keys().forEach { k -> args.put(k, o.get(k)) } }
            } else if (restOfLine.isNotEmpty()) {
                val pairs = inlinePair.findAll(restOfLine).toList()
                // "tap element 17 and tap …" or a sentence: not a call line.
                if (pairs.isEmpty()) { i++; continue }
                pairs.forEach { m -> if (!args.has(m.groupValues[1])) blockValue(m.groupValues[2])?.let { args.put(m.groupValues[1], it) } }
            }
            i++
            // A JSON object on the next line(s).
            if (args.length() == 0 && i < lines.size && lines[i].trim().startsWith("{")) {
                var j = i; val sb = StringBuilder()
                while (j < lines.size) { sb.append(lines[j]).append('\n'); if (ToolCalls.parseArgs(sb.toString().trim()) != null) break; j++ }
                ToolCalls.parseArgs(sb.toString().trim())?.let { o -> o.keys().forEach { k -> args.put(k, o.get(k)) }; i = j + 1 }
            }
            // key: value lines (blank lines inside the block are fine).
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) { if (i + 1 < lines.size && pairLine.matches(lines[i + 1]) && names[lines[i + 1].trim().substringBefore(' ').lowercase()] == null) { i++; continue } else break }
                val m = pairLine.matchEntire(l) ?: break
                if (names[l.trim().substringBefore(' ').lowercase()] != null && !l.contains(':') && !l.contains('=')) break
                val k = m.groupValues[1]
                if (!args.has(k)) blockValue(m.groupValues[2])?.let { args.put(k, it) }
                i++
            }
            // A bare word ("tap", "wait") on a line is prose unless it carries arguments.
            if (args.length() == 0 && !name.contains('_')) continue
            out += Block(name, args, start until i)
        }
        return out
    }

    /** Required parameters of [name] in [all]. */
    private fun required(all: JSONArray, name: String): List<String> {
        for (i in 0 until all.length()) {
            val f = all.optJSONObject(i)?.optJSONObject("function") ?: continue
            if (f.optString("name") != name) continue
            val r = f.optJSONObject("parameters")?.optJSONArray("required") ?: return emptyList()
            return (0 until r.length()).map { r.optString(it) }
        }
        return emptyList()
    }

    /** True when [b] can run: its required parameters are there (tap needs text or element, whatever its schema says). */
    private fun runnable(b: Block, all: JSONArray): Boolean {
        if (b.name == "tap" && !b.args.has("text") && !b.args.has("element") && !b.args.has("x")) return false
        return required(all, b.name).all { b.args.has(it) }
    }

    /** Is there block-form call syntax for a known tool (shown to the user it reads as broken code)? */
    fun blockHas(text: String, known: Collection<String>): Boolean = blocks(text, known).isNotEmpty()

    /** Real calls for the runnable block calls, and the words left for the user (every block line taken out). */
    fun blockCalls(text: String, all: JSONArray): Pair<JSONArray, String>? {
        val known = ToolCalls.names(all)
        val found = blocks(text, known)
        if (found.isEmpty()) return null
        val ok = found.filter { runnable(it, all) }
        if (ok.isEmpty()) return null
        val calls = JSONArray()
        for (b in ok) calls.put(JSONObject().put("id", "blockcall_${calls.length()}").put("type", "function")
            .put("function", JSONObject().put("name", b.name).put("arguments", b.args.toString())))
        return calls to blockStrip(text, known)
    }

    /** [text] without its block calls (and without a fenced steps block). */
    fun blockStrip(text: String, known: Collection<String>): String {
        val found = blocks(text, known)
        if (found.isEmpty()) return text
        if (found.any { it.lines == 0..0 }) return ""
        var t = text
        if (found.any { it.lines == -1..-1 }) t = fence.replace(t) { m -> if (stepsJson(m.groupValues[2]) != null) "" else m.value }
        val drop = found.filter { it.lines.first >= 0 }.flatMap { it.lines.toList() }.toSet()
        if (drop.isNotEmpty()) t = t.lines().filterIndexed { i, _ -> i !in drop }.joinToString("\n")
        return t.replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** Sent once when the reply was function syntax for no real tool. */
    const val NUDGE = "Your last reply was function-call text (like name(...)) instead of a real tool call, so the user would only see code. " +
        "Call tools only through the native tool-calling API. If no tool fits, answer in plain words. Never write function names, brackets or JSON in your reply."
}
