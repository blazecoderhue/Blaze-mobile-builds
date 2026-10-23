package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.45.19 (HQ on 0.45.17: "sign in with my email and password" showed raw code in the chat instead of the secure form
 * card). The forms the older parsers ([ToolCalls.fromText], [ToolLeak], [TextCalls]) missed, all seen from small models:
 *  - a Python-style call over several lines: `browser_secure_input(\n  fields=[...],\n  title="Sign in"\n)`;
 *  - a Python-style call with a list / object argument on one line (fromText cut `fields=[{..},{..}]` at the first comma);
 *  - a JSON call `{"name": "browser_secure_input", "arguments": {...}}` in the middle of prose, or wrapped in `[ ]`,
 *    or as `{"tool_calls": [...]}`;
 *  - a ```tool / ```tool_call / ```json / ```python block holding any of these (single or multi-line);
 *  - `<tool_call>` / `<function=name>` tags (from [ToolLeak]) anywhere;
 *  - a cut-off (truncated) call of any of these forms.
 *
 * [scan] finds them with balanced, string-aware matching (never a regex over the arguments), [toCalls] turns the ones
 * naming a real tool into real calls (a cut-off browser_secure_input still becomes the form when its fields can be read),
 * and [clean] is the final sanitizer for every chat bubble: raw call text, JSON call payloads, function tags and
 * fenced tool blocks for any known tool never reach the screen, while streaming either (an unfinished one is held back).
 * Text with nothing to hide comes back unchanged, byte for byte. Pure Kotlin; SecureFormRenderTest.
 */
object CallScan {
    /** One call written as text. [args] null = it was cut off / unreadable. [end] is exclusive. */
    data class Hit(val name: String, val args: JSONObject?, val start: Int, val end: Int, val form: String)

    /** Tool names known before the agent has sent a request (the display path has no tool list of its own). */
    val SEED: Set<String> = setOf(
        "browser_secure_input", "browser_open", "browser_click", "browser_type", "browser_show", "browser_read", "browser_back",
        "browser_scroll", "browser_wait", "browser_press", "browser_select", "browser_close", "open_url", "read_screen", "find_tool",
        "load_tools", "ask_user", "web_search", "fetch_page", "memory_save", "memory_search", "recall_memory", "message_contact",
        "blaze_self", "run_shell", "open_app", "type_text", "look_at_screen", "run_plan", "save_file", "open_view", "set_alarm",
        "gmail_search", "gmail_read", "gcal_events", "calendar_events", "generate_image", "open_settings", "search_in_app", "press_button",
    )

    /** Every tool name the agent has registered (the agent adds its full list with [learn]). */
    @Volatile var known: Set<String> = SEED

    fun learn(names: Collection<String>) { if (!known.containsAll(names)) known = known + names }

    private val toolLangs = setOf("tool", "tool_call", "tool_calls", "tool_code", "function", "function_call", "functions", "toolcall")
    private val callLangs = setOf("", "json", "python", "py") + toolLangs
    private val fenceRe = Regex("""```[ \t]*([A-Za-z_\-]*)[^\n`]*\n?([\s\S]*?)(```|$)""")
    private val openTag = Regex("""<\|?(tool_calls?|function_call|tool|function=[A-Za-z_][A-Za-z0-9_.:\-]*)\|?>""", RegexOption.IGNORE_CASE)
    private val pyHead = Regex("""([A-Za-z_][A-Za-z0-9_]*(?:[.:][A-Za-z_][A-Za-z0-9_]*)*)[ \t]*\(""")

    private fun bare(n: String) = n.substringAfterLast('.').substringAfterLast(':').trim()
    private fun isKnown(n: String, known: Collection<String>) = bare(n).let { b -> known.any { it.equals(b, true) } }
    private fun toolish(n: String) = bare(n).let { it.contains('_') && it.length in 3..48 && !it.startsWith("_") }

    // ---------------- balanced matching ----------------

    /** Index just after the bracket that closes the one at [open], or -1 when it is never closed. Quotes are honoured. */
    fun close(s: String, open: Int): Int {
        val stack = ArrayList<Char>()
        var q = '\u0000'; var esc = false
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (q != '\u0000') {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == q) q = '\u0000' else if (c == '\n' && q == '\'') q = '\u0000'
                i++; continue
            }
            when (c) {
                '"', '\'' -> q = c
                '(', '[', '{' -> stack += c
                ')', ']', '}' -> {
                    if (stack.isEmpty()) return -1
                    val o = stack.removeAt(stack.size - 1)
                    if ((o == '(' && c != ')') || (o == '[' && c != ']') || (o == '{' && c != '}')) return -1
                    if (stack.isEmpty()) return i + 1
                }
            }
            i++
        }
        return -1
    }

    /** Top-level pieces of [s] split at [sep] (outside quotes and brackets). */
    private fun splitTop(s: String, sep: Char = ','): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder()
        var depth = 0; var q = '\u0000'; var esc = false
        for (c in s) {
            if (q != '\u0000') { sb.append(c); if (esc) esc = false else if (c == '\\') esc = true else if (c == q) q = '\u0000'; continue }
            when (c) {
                '"', '\'' -> { q = c; sb.append(c) }
                '(', '[', '{' -> { depth++; sb.append(c) }
                ')', ']', '}' -> { depth--; sb.append(c) }
                else -> if (c == sep && depth == 0) { out += sb.toString(); sb.setLength(0) } else sb.append(c)
            }
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }

    // ---------------- Python literals ----------------

    /** A Python / JSON-ish literal as JSON text: 'x' -> "x", True/False/None -> true/false/null, key=value inside dict(...) left alone. */
    private fun pyToJson(v: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '"' || c == '\'') {
                val triple = v.startsWith("$c$c$c", i)
                val qlen = if (triple) 3 else 1
                var j = i + qlen; val body = StringBuilder()
                while (j < v.length) {
                    if (v[j] == '\\' && j + 1 < v.length) { body.append(v[j]).append(v[j + 1]); j += 2; continue }
                    if (v.startsWith(c.toString().repeat(qlen), j)) break
                    body.append(v[j]); j++
                }
                val raw = body.toString().let { if (c == '\'') it.replace("\\'", "'") else it }
                sb.append(JSONObject.quote(unescape(raw)))
                i = minOf(v.length, j + qlen); continue
            }
            if (c.isLetter() || c == '_') {
                var j = i; while (j < v.length && (v[j].isLetterOrDigit() || v[j] == '_')) j++
                val w = v.substring(i, j)
                sb.append(when (w) { "True" -> "true"; "False" -> "false"; "None" -> "null"; else -> w })
                i = j; continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun unescape(s: String): String = if (!s.contains('\\')) s else
        s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")

    private fun pyValue(v: String): Any {
        val t = v.trim().trimEnd(',')
        if (t.isEmpty()) return ""
        val j = pyToJson(t)
        try { val o = JSONObject("{\"v\":$j}"); return o.opt("v") ?: t } catch (_: Exception) { }
        ToolCalls.parseArgs("{\"v\":$j}")?.opt("v")?.let { return it }
        return t.trim('"', '\'')
    }

    /** `key="v", fields=[{...}], n=2` (also `key: v`); positionals as _0, _1. Null when the text isn't call arguments. */
    fun pyArgs(inner: String): JSONObject? {
        val t = inner.trim()
        if (t.isEmpty()) return JSONObject()
        if (t.startsWith("{") && splitTop(t).size == 1) return ToolCalls.parseArgs(t)
        val o = JSONObject(); var pos = 0
        for (p in splitTop(t)) {
            val piece = p.trim()
            if (piece.isEmpty()) continue
            val m = Regex("""^\**([A-Za-z_][A-Za-z0-9_]*)\s*[=:]\s*([\s\S]*)$""").find(piece)
            if (m != null && !piece.startsWith("\"") && !piece.startsWith("'")) o.put(m.groupValues[1], pyValue(m.groupValues[2]))
            else o.put("_" + pos++, pyValue(piece))
        }
        return o
    }

    // ---------------- JSON call objects ----------------

    /** The calls a JSON object stands for: {name, arguments}, {function:{name, arguments}}, {tool, ...}, {tool_calls:[...]}. */
    private fun jsonCalls(o: JSONObject): List<Pair<String, JSONObject?>> {
        o.optJSONArray("tool_calls")?.let { a -> return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.flatMap { jsonCalls(it) } }
        val f = o.optJSONObject("function")
        val n = (f?.optString("name")?.takeIf { it.isNotBlank() } ?: o.optString("name").ifBlank { o.optString("tool").ifBlank { o.optString("tool_name") } }).trim()
        if (n.isEmpty() || n.contains(' ')) return emptyList()
        val holder = f ?: o
        val hasArgs = listOf("arguments", "args", "parameters", "input").any { holder.has(it) }
        if (!hasArgs && f == null && !o.has("tool") && !o.has("tool_name")) return emptyList()
        val a = holder.opt("arguments") ?: holder.opt("args") ?: holder.opt("parameters") ?: holder.opt("input")
        return listOf(n to (ToolCalls.parseArgs(a) ?: JSONObject()))
    }

    private val jsonTail = Regex("""["']?(?:name|tool|tool_name|function|tool_calls)["']?\s*:""")

    // ---------------- scanning ----------------

    /**
     * Every call written as text in [text], in order. [known] = real tool names; [loose] also counts an unknown
     * snake_case name at the start of a line (the agent's gatekeeper), else only known names (what a bubble hides).
     */
    fun scan(text: String, known: Collection<String> = this.known, loose: Boolean = false): List<Hit> {
        if (text.isBlank() || (text.indexOf('(') < 0 && text.indexOf('{') < 0 && text.indexOf('<') < 0 && text.indexOf("```") < 0 && text.indexOf("[TOOL_CALLS") < 0)) return emptyList()
        val hits = ArrayList<Hit>()
        val skip = ArrayList<IntRange>()   // code to leave alone (fences in a language that isn't a call format)
        fun taken(i: Int) = hits.any { i >= it.start && i < it.end } || skip.any { i in it }

        // 1. Fenced blocks.
        for (m in fenceRe.findAll(text)) {
            val lang = m.groupValues[1].lowercase()
            val bodyStart = m.groups[2]!!.range.first.takeIf { it >= 0 } ?: (m.range.last + 1)
            val body = m.groupValues[2]
            val closed = m.groupValues[3] == "```"
            if (lang !in callLangs) { skip += m.range; continue }
            val inner = scan(body, known, loose = loose || lang in toolLangs).filter { it.form != "fence" }
            val rest = removeRanges(body, inner.map { it.start until it.end }).trim()
            if (inner.isNotEmpty() && rest.isEmpty()) {
                inner.forEachIndexed { i, h -> hits += Hit(h.name, h.args, if (i == 0) m.range.first else m.range.last + 1, if (i == 0) m.range.last + 1 else m.range.last + 1, "fence") }
            } else if (lang in toolLangs) {
                // A tool block we can't read: never shown; an honest line replaces it if nothing else is left.
                hits += Hit(inner.firstOrNull()?.name ?: "", null, m.range.first, m.range.last + 1, "fence")
            } else if (inner.isNotEmpty() && inner.all { isKnown(it.name, known) }) {
                inner.forEach { h -> hits += h.copy(start = bodyStart + h.start, end = bodyStart + h.end) }
            } else if (!closed && body.trimStart().let { it.startsWith("{") && jsonTail.containsMatchIn(it) }) {
                hits += Hit("", null, m.range.first, text.length, "fence")
            } else skip += m.range
        }

        // 2. Tags and [TOOL_CALLS] (ToolLeak's parser), anywhere.
        var from = 0
        for (l in ToolLeak.find(text)) {
            if (!(l.raw.startsWith("<") || l.raw.startsWith("[TOOL_CALLS"))) continue
            val at = text.indexOf(l.raw, from).takeIf { it >= 0 } ?: continue
            from = at + l.raw.length
            if (taken(at)) continue
            val cut = !Regex("""</[A-Za-z_]+>\s*$""").containsMatchIn(l.raw) && !l.raw.startsWith("[TOOL_CALLS")
            hits += Hit(l.name, if (cut && l.args.length() == 0) null else l.args, at, at + l.raw.length, "tag")
        }

        // 2b. An opening tag ToolLeak couldn't read (cut off, or a body that isn't JSON yet): to its closing tag or the end.
        for (m in openTag.findAll(text)) {
            if (taken(m.range.first)) continue
            val tag = m.groupValues[1]
            val closeName = if (tag.startsWith("function=")) "function" else tag
            val endTag = Regex("""</\|?${Regex.escape(closeName)}\|?>""", RegexOption.IGNORE_CASE).find(text, m.range.last + 1)
            val bodyEnd = endTag?.range?.first ?: text.length
            val body = text.substring(m.range.last + 1, bodyEnd)
            val inner = ToolCalls.splitObjects(body).mapNotNull { ToolCalls.parseArgs(it) }.flatMap { jsonCalls(it) }.firstOrNull()
            val name = if (tag.startsWith("function=")) tag.removePrefix("function=") else inner?.first
                ?: Regex("""["'](?:name|tool)["']\s*:\s*["']([A-Za-z_][A-Za-z0-9_.:]*)""").find(body)?.groupValues?.get(1).orEmpty()
            val args = if (endTag == null) null else if (tag.startsWith("function=")) ToolCalls.parseArgs(body.trim()) else inner?.second
            hits += Hit(bare(name), args, m.range.first, endTag?.let { it.range.last + 1 } ?: text.length, "tag")
        }

        // 3. Python-style calls (one line or several), at a line start or (known names) mid-prose.
        for (m in pyHead.findAll(text)) {
            val s = m.range.first
            if (taken(s)) continue
            val name = m.groupValues[1]
            if (s > 0 && (text[s - 1].isLetterOrDigit() || text[s - 1] == '_' || text[s - 1] == '.')) continue
            val lineHead = text.substring(text.lastIndexOf('\n', s - 1) + 1, s)
            val atLine = lineHead.all { it == ' ' || it == '\t' || it == '`' || it == '-' || it == '*' || it == '>' }
            // Mid-prose only when the bracket follows the name directly ("web_search (the tool)" is prose).
            if (!atLine && (m.value.contains(' ') || m.value.contains('\t'))) continue
            val open = m.range.last
            val e = close(text, open)
            // A known name without an underscore ("tap", "desktop") only as an exact, argument-shaped line call, so prose
            // like "Remember (this) …" is never touched.
            val plainOk = !toolish(name) && atLine && known.any { it == bare(name) } &&
                (e < 0 || text.substring(open + 1, e - 1).trim().let { it.isEmpty() || it.contains('=') || it.startsWith("\"") || it.startsWith("'") || it.startsWith("{") })
            val ok = (isKnown(name, known) && toolish(name)) || plainOk || (loose && atLine && toolish(name))
            if (!ok) continue
            var start = s; while (start > 0 && text[start - 1] == '`') start--
            if (e < 0) {
                // Cut off: everything from here on is the unfinished call.
                val args = pyArgs(text.substring(open + 1))?.takeIf { it.length() > 0 && complete(text.substring(open + 1)) }
                hits += Hit(bare(name), if (args != null) args else partialArgs(text.substring(open + 1)), start, text.length, "py-cut")
                break
            }
            var end = e
            while (end < text.length && (text[end] == ';' || text[end] == '`')) end++
            hits += Hit(bare(name), pyArgs(text.substring(open + 1, e - 1)), start, end, "py")
        }

        // 4. JSON call objects anywhere (also `[ {...} ]` and {"tool_calls": [...]}).
        var i = text.indexOf('{')
        while (i >= 0) {
            if (taken(i)) { i = text.indexOf('{', i + 1); continue }
            val e = close(text, i)
            if (e < 0) {
                val tail = text.substring(i)
                if (jsonTail.containsMatchIn(tail.take(200)) && Regex("""^\{\s*["']""").containsMatchIn(tail)) {
                    val n = Regex("""["'](?:name|tool|tool_name)["']\s*:\s*["']([A-Za-z_][A-Za-z0-9_.:]*)""").find(tail)?.groupValues?.get(1).orEmpty()
                    var st = i; while (st > 0 && text[st - 1].isWhitespace() && text.substring(text.lastIndexOf('\n', st - 1) + 1, st).isBlank()) st--
                    if (st > 0 && text[st - 1] == '[') st--
                    hits += Hit(bare(n), partialArgs(tail), if (st < i && text.getOrNull(st) == '[') st else i, text.length, "json-cut")
                    break
                }
                i = text.indexOf('{', i + 1); continue
            }
            val obj = ToolCalls.parseArgs(text.substring(i, e))
            val calls = obj?.let { jsonCalls(it) }.orEmpty().filter { (n, _) -> isKnown(n, known) || (loose || text.substring(i, e).contains("\"arguments\"")) && toolish(n) }
            if (calls.isEmpty()) { i = text.indexOf('{', i + 1); continue }
            var st = i; var en = e
            // [ {...} ] around it
            val before = text.substring(0, st).trimEnd()
            if (before.endsWith("[")) { val after = text.substring(en).trimStart(); if (after.startsWith("]")) { st = before.length - 1; en = text.length - after.length + 1 } }
            while (st > 0 && text[st - 1] == '`') st--
            while (en < text.length && text[en] == '`') en++
            calls.forEachIndexed { k, (n, a) -> hits += Hit(bare(n), a, if (k == 0) st else en, en, "json") }
            i = text.indexOf('{', en)
        }
        return hits.sortedBy { it.start }
    }

    /** True when every bracket in [s] is closed (a call whose `)` alone is missing still has readable args). */
    private fun complete(s: String): Boolean = s.count { it == '[' } == s.count { it == ']' } && s.count { it == '{' } == s.count { it == '}' }

    /** What can be read from a cut-off call: the fields a secure form needs, by name. Null when nothing is readable. */
    fun partialArgs(s: String): JSONObject? {
        val low = s.lowercase()
        val f = JSONArray()
        if (Regex("""\bemail\b|e-mail""").containsMatchIn(low)) f.put(JSONObject().put("name", "email").put("kind", "email"))
        else if (Regex("""\buser(?:name|_name| name)?\b|\blogin\b""").containsMatchIn(low)) f.put(JSONObject().put("name", "username").put("kind", "username"))
        if (Regex("""password|passcode|\bpin\b""").containsMatchIn(low)) f.put(JSONObject().put("name", "password").put("kind", "password"))
        if (f.length() == 0) return null
        return JSONObject().put("fields", f)
    }

    private fun removeRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val sb = StringBuilder(); var at = 0
        for (r in ranges.sortedBy { it.first }) {
            if (r.first > at) sb.append(text, at, r.first)
            at = maxOf(at, r.last + 1)
        }
        if (at < text.length) sb.append(text, at, text.length)
        return sb.toString()
    }

    // ---------------- what the user sees ----------------

    /** While streaming: where an unfinished call / payload / tool fence starts at the end of [text], or -1. */
    fun unfinished(text: String, known: Collection<String> = this.known): Int {
        // An open fence in a call language whose body is (or is becoming) a call.
        val opens = Regex("```").findAll(text).count()
        if (opens % 2 == 1) {
            val at = text.lastIndexOf("```")
            val after = text.substring(at + 3)
            val lang = Regex("""^[ \t]*([A-Za-z_\-]*)""").find(after)?.groupValues?.get(1)?.lowercase().orEmpty()
            val body = after.substringAfter('\n', "").trimStart()
            if (lang in toolLangs || (lang in callLangs && (body.isEmpty() || body.startsWith("{") || body.startsWith("[") || pyHead.find(body)?.range?.first == 0))) return at
        }
        // The last line: a snake_case name that is becoming a call (`browser_secure_in`, `browser_secure_input(`).
        val ls = text.lastIndexOf('\n') + 1
        val last = text.substring(ls)
        Regex("""^[ \t]*`{0,3}[ \t]*([A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]*)+)[ \t]*$""").find(last)?.let { return ls + it.range.first }
        // A known tool name being typed mid-prose (`… browser_secure_inp`).
        Regex("""([A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]*)$""").find(text)?.let { m ->
            if (known.any { k -> k.startsWith(m.value, true) && k.contains('_') }) return m.range.first
        }
        // A tag being typed: `<tool_c`, `<function`, `<|tool`.
        Regex("""<\|?([A-Za-z_=]*)$""").find(text)?.let { m ->
            val w = m.groupValues[1].lowercase()
            if (w.isNotEmpty() && listOf("tool_call", "tool_calls", "function=", "function_call", "tool").any { it.startsWith(w) || w.startsWith(it) }) return m.range.first
        }
        // A JSON object that starts like a call payload and isn't closed yet (the outermost one).
        var i = text.indexOf('{')
        while (i >= 0) {
            if (close(text, i) < 0 && Regex("""^\{\s*(["']|$)""").containsMatchIn(text.substring(i))) {
                var st = i; val before = text.substring(0, st).trimEnd(); if (before.endsWith("[")) st = before.length - 1
                return st
            }
            i = text.indexOf('{', i + 1)
        }
        return -1
    }

    /**
     * [text] with every call written as text taken out (known tools; [loose] adds unknown snake_case line calls), plus
     * [ToolLeak] syntax; while streaming ([final] false) an unfinished one at the end is held back.
     * Text with nothing to hide comes back unchanged.
     */
    fun clean(text: String, final: Boolean = true, known: Collection<String> = this.known, loose: Boolean = false): String {
        if (text.isEmpty()) return text
        val hits = scan(text, known, loose)
        var t = if (hits.isEmpty()) text else removeRanges(text, hits.map { it.start until it.end })
        if (!final) unfinished(t, known).takeIf { it >= 0 }?.let { t = t.substring(0, it) }
        if (t == text) {
            val a = ToolLeak.strip(t, final)
            if (a == text || a == text.trim()) return text
            t = a
        } else t = ToolLeak.strip(t, final)
        return t.replace(Regex("""[ \t]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** Is there call syntax in [text] the user must not see? */
    fun has(text: String, known: Collection<String> = this.known, loose: Boolean = false): Boolean = scan(text, known, loose).isNotEmpty()

    /**
     * Real calls for the text calls in [text] that name a tool in [all], and the words left for the user (every call,
     * readable or not, taken out). A cut-off browser_secure_input whose fields can be read still becomes the form.
     */
    fun toCalls(text: String, all: JSONArray): Pair<JSONArray, String>? {
        val known = ToolCalls.names(all)
        if (known.isEmpty()) return null
        val hits = scan(text, known, loose = true)
        if (hits.isEmpty()) return null
        val calls = JSONArray()
        for (h in hits) {
            val n = ToolCalls.resolveName(h.name, known) ?: continue
            var a = h.args ?: (if (n == SECURE) partialArgs(text.substring(h.start)) else null) ?: continue
            a = JSONObject(a.toString())
            if (n == SECURE) a = secureArgs(a, text.substring(h.start, h.end)) ?: continue
            // A lone positional value goes to the first parameter.
            if (a.has("_0") && a.length() == 1) firstParam(all, n)?.let { p -> a.put(p, a.opt("_0")) }
            a.keys().asSequence().toList().filter { it.startsWith("_") && it.drop(1).all { c -> c.isDigit() } }.forEach { a.remove(it) }
            calls.put(JSONObject().put("id", "scan_${calls.length()}").put("type", "function")
                .put("function", JSONObject().put("name", n).put("arguments", a.toString())))
        }
        if (calls.length() == 0) return null
        return calls to clean(text, true, known, loose = true)
    }

    const val SECURE = "browser_secure_input"

    /** browser_secure_input args that will open the form: fields as a JSON array (a JSON string or a word list too). */
    fun secureArgs(a: JSONObject, raw: String = ""): JSONObject? {
        val f = a.opt("fields") ?: a.opt("_0")
        val arr: JSONArray? = when (f) {
            is JSONArray -> f
            is String -> runCatching { JSONArray(f) }.getOrNull()
                ?: f.split(',', ' ', '+', '/').map { it.trim().trim('"', '\'') }.filter { it.isNotBlank() && it != "and" }.takeIf { it.isNotEmpty() }?.let { JSONArray(it) }
            is JSONObject -> JSONArray().put(f)
            else -> null
        }
        val out = JSONObject(a.toString()); out.remove("_0")
        val fields = arr?.takeIf { it.length() > 0 } ?: partialArgs(raw)?.optJSONArray("fields") ?: return null
        return out.put("fields", fields)
    }

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
}
