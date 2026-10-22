package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The tool-call gatekeeper. Models (especially free ones) send tool calls with glued names,
 * broken JSON, wrong types, made-up names, or as plain text. Everything here runs locally
 * between the model and the tools, so a sloppy call is repaired instead of crashing the turn
 * or being echoed back to the provider (which answers 400 INVALID_ARGUMENT).
 *
 *  - [normalize]  fixes the calls in one assistant reply against the known tools
 *  - [fromText]   turns a call the model wrote as text into a real tool call
 *  - [clean]      makes a message list safe to send (every call answered, valid JSON, no orphans)
 *  - [flatten]    last resort after a 400: tool turns become plain text every provider accepts
 */
object ToolCalls {

    // ---------- JSON helpers ----------

    /** True when [s] is one complete JSON value (used by the stream reader to split glued calls). */
    fun completeJson(s: String?): Boolean {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return false
        return try { val tok = JSONTokener(t); tok.nextValue(); !tok.more() } catch (_: Exception) { false }
    }

    /** Top-level JSON objects found back to back in [s] ("{}{\"q\":1}" -> ["{}", "{\"q\":1}"]). */
    fun splitObjects(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0; var start = -1; var inStr = false; var esc = false
        for ((i, c) in s.withIndex()) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { if (depth > 0) { depth--; if (depth == 0 && start >= 0) { out += s.substring(start, i + 1); start = -1 } } }
            }
        }
        return out
    }

    /** Best-effort parse of model-written arguments. Null only when nothing usable is there. */
    fun parseArgs(raw: Any?): JSONObject? {
        if (raw is JSONObject) return raw
        var s = (raw as? String)?.trim().orEmpty()
        if (s.isEmpty() || s == "null") return JSONObject()
        s = s.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        tryObj(s)?.let { return it }
        // A JSON string that itself holds JSON: "\"{\\\"q\\\":1}\""
        if (s.startsWith("\"")) try { (JSONTokener(s).nextValue() as? String)?.let { inner -> tryObj(inner)?.let { return it } } } catch (_: Exception) { }
        var r = s
        // Trailing commas, single quotes, Python literals, unquoted keys.
        r = r.replace(Regex(",\\s*([}\\]])"), "$1")
        if (!r.contains('"') && r.contains('\'')) r = r.replace('\'', '"')
        r = r.replace(Regex("(?<=[:\\[,\\s])True\\b"), "true").replace(Regex("(?<=[:\\[,\\s])False\\b"), "false").replace(Regex("(?<=[:\\[,\\s])None\\b"), "null")
        r = r.replace(Regex("([{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:"), "$1\"$2\":")
        tryObj(r)?.let { return it }
        // Unclosed braces/brackets (a cut-off stream).
        val open = r.count { it == '{' } - r.count { it == '}' }
        val openSq = r.count { it == '[' } - r.count { it == ']' }
        if (open > 0 || openSq > 0) tryObj(r + "]".repeat(openSq.coerceAtLeast(0)) + "}".repeat(open.coerceAtLeast(0)))?.let { return it }
        // Several objects glued together: keep the first.
        splitObjects(r).firstNotNullOfOrNull { tryObj(it) }?.let { return it }
        return null
    }

    private fun tryObj(s: String): JSONObject? = try {
        val v = JSONTokener(s).nextValue(); v as? JSONObject
    } catch (_: Exception) { null }

    // ---------- names ----------

    private fun key(n: String) = n.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** "functions.gmail_search", "default_api:gmail_search", "GmailSearch", "gmail-serach" -> "gmail_search". */
    fun resolveName(raw: String, known: Collection<String>): String? {
        if (raw in known) return raw
        val stripped = raw.trim().substringAfterLast('.').substringAfterLast(':').substringAfterLast('/').trim('"', '\'', '`', ' ')
        if (stripped in known) return stripped
        val k = key(stripped)
        if (k.isEmpty()) return null
        known.firstOrNull { key(it) == k }?.let { return it }
        // Small typos only, and only when exactly one tool is that close.
        val near = known.map { it to lev(key(it), k) }.filter { it.second <= (if (k.length > 8) 2 else 1) }
        val best = near.minByOrNull { it.second } ?: return null
        return best.first.takeIf { near.count { it.second == best.second } == 1 }
    }

    /** A name made of several known tool names glued together, split back apart (null if it isn't one). */
    fun splitGlued(raw: String, known: Collection<String>): List<String>? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val byLen = known.sortedByDescending { it.length }
        // best[i] = a split of s.substring(0, i)
        val best = arrayOfNulls<List<String>>(s.length + 1)
        best[0] = emptyList()
        for (i in 0 until s.length) {
            val prev = best[i] ?: continue
            for (n in byLen) if (s.startsWith(n, i) && best[i + n.length] == null) best[i + n.length] = prev + n
        }
        return best[s.length]?.takeIf { it.size > 1 }
    }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    // ---------- schemas ----------

    fun names(tools: JSONArray): Set<String> =
        (0 until tools.length()).mapNotNull { tools.optJSONObject(it)?.optJSONObject("function")?.optString("name")?.takeIf { n -> n.isNotBlank() } }.toSet()

    private fun params(tools: JSONArray, name: String): JSONObject? {
        for (i in 0 until tools.length()) {
            val f = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
            if (f.optString("name") == name) return f.optJSONObject("parameters")
        }
        return null
    }

    /** Fixes argument types to match the schema ("5" -> 5, "true" -> true, "a" -> ["a"], wrong-case enum). */
    fun coerce(args: JSONObject, schema: JSONObject?): JSONObject {
        val props = schema?.optJSONObject("properties") ?: return args
        val out = JSONObject()
        val propKeys = props.keys().asSequence().toList()
        for (k in args.keys().asSequence().toList()) {
            // Argument names with a different case/separator ("Query" -> "query").
            val name = if (props.has(k)) k else propKeys.firstOrNull { key(it) == key(k) } ?: k
            val p = props.optJSONObject(name)
            out.put(name, if (p == null) args.opt(k) else fix(args.opt(k), p))
        }
        return out
    }

    private fun fix(v: Any?, p: JSONObject): Any? {
        val type = p.optString("type")
        p.optJSONArray("enum")?.let { e ->
            val opts = (0 until e.length()).map { e.opt(it).toString() }
            val sv = v?.toString().orEmpty()
            if (sv !in opts) opts.firstOrNull { it.equals(sv, true) || key(it) == key(sv) }?.let { return it }
        }
        return when (type) {
            "integer" -> when (v) { is Number -> v.toLong(); is String -> v.trim().toDoubleOrNull()?.toLong() ?: v; else -> v }
            "number" -> when (v) { is String -> v.trim().toDoubleOrNull() ?: v; else -> v }
            "boolean" -> when (v) { is String -> when (v.trim().lowercase()) { "true", "yes", "on", "1" -> true; "false", "no", "off", "0" -> false; else -> v }; is Number -> v.toInt() != 0; else -> v }
            "string" -> when (v) { null, JSONObject.NULL -> v; is String -> v; is JSONArray -> (0 until v.length()).joinToString(", ") { v.opt(it).toString() }; else -> v.toString() }
            "array" -> when (v) {
                is JSONArray -> { val items = p.optJSONObject("items"); if (items == null) v else JSONArray().apply { for (i in 0 until v.length()) put(fix(v.opt(i), items)) } }
                is String -> (try { JSONTokener(v.trim()).nextValue() as? JSONArray } catch (_: Exception) { null })
                    ?: JSONArray(v.split(',', '|').map { it.trim() }.filter { it.isNotEmpty() })
                null, JSONObject.NULL -> v
                else -> JSONArray().put(v)
            }
            "object" -> if (v is String) parseArgs(v) ?: v else v
            else -> v
        }
    }

    // ---------- normalize one reply ----------

    data class Report(val repaired: List<String>, val unknown: Set<String>)

    /**
     * Repairs the tool_calls of an assistant reply in place, against [all] tools (loaded or not).
     * Glued calls are split, names resolved, arguments parsed + coerced and re-serialized, ids
     * made unique. Calls naming no real tool keep their name and are listed in [Report.unknown]
     * so the agent answers them with an error instead of running anything.
     */
    fun normalize(reply: JSONObject, all: JSONArray): Report {
        val calls = reply.optJSONArray("tool_calls") ?: return Report(emptyList(), emptySet())
        val known = names(all)
        val repaired = ArrayList<String>()
        val unknown = HashSet<String>()
        val out = JSONArray()
        val ids = HashSet<String>()
        fun add(src: JSONObject, name: String, args: JSONObject, n: Int) {
            var id = src.optString("id", "").ifBlank { "call_${System.nanoTime() % 100000}_$n" }
            if (!ids.add(id)) { id = "${id}_$n"; ids.add(id) }
            val c = JSONObject().put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", args.toString()))
            src.optJSONObject("extra_content")?.let { c.put("extra_content", it) }
            out.put(c)
        }
        for (i in 0 until calls.length()) {
            val c = calls.optJSONObject(i) ?: continue
            val fn = c.optJSONObject("function") ?: JSONObject()
            val rawName = fn.optString("name").ifBlank { c.optString("name") }
            val rawArgs = fn.opt("arguments") ?: c.opt("arguments")
            if (rawName.isBlank()) { repaired += "dropped a call with no name"; continue }
            val name = resolveName(rawName, known)
            if (name != null) {
                val parsed = parseArgs(rawArgs)
                if (name != rawName) repaired += "$rawName → $name"
                if (parsed == null) repaired += "$name: unreadable arguments"
                val args = coerce(parsed ?: JSONObject(), params(all, name))
                if (parsed != null && args.toString() != parsed.toString()) repaired += "$name: argument types"
                add(c, name, args, i)
                continue
            }
            val parts = splitGlued(rawName, known)
            if (parts != null) {
                // "device_statusrecent_notificationsgmail_search" + "{}{}{\"query\":..}"
                val objs = (rawArgs as? String)?.let { splitObjects(it) } ?: emptyList()
                repaired += "split $rawName into ${parts.joinToString(", ")}"
                parts.forEachIndexed { j, n ->
                    val a = if (objs.size == parts.size) parseArgs(objs[j]) else if (j == 0) parseArgs(objs.firstOrNull()) else null
                    add(JSONObject().put("id", c.optString("id").let { if (it.isBlank()) "" else "${it}_$j" })
                        .apply { if (j == 0) c.optJSONObject("extra_content")?.let { put("extra_content", it) } },
                        n, coerce(a ?: JSONObject(), params(all, n)), i * 10 + j)
                }
                continue
            }
            unknown += rawName
            add(c, rawName, parseArgs(rawArgs) ?: JSONObject(), i)
        }
        if (out.length() == 0) { reply.remove("tool_calls"); if (reply.isNull("content")) reply.put("content", "") }
        else reply.put("tool_calls", out)
        return Report(repaired, unknown)
    }

    /**
     * 0.45.15 (review 22 Oct #6): keeps only calls to a tool in [all] whose arguments are a JSON object; the rest are
     * dropped (none left: tool_calls removed). Count dropped.
     */
    fun dropMalformed(reply: JSONObject, all: JSONArray): Int {
        val calls = reply.optJSONArray("tool_calls") ?: return 0
        val known = names(all).toSet()
        val keep = JSONArray(); var dropped = 0
        for (i in 0 until calls.length()) {
            val c = calls.optJSONObject(i); val f = c?.optJSONObject("function")
            val ok = f != null && f.optString("name") in known && (f.opt("arguments").let { a -> a is JSONObject || (a is String && (a.isBlank() || try { JSONObject(a); true } catch (_: Exception) { false })) })
            if (ok) keep.put(c) else dropped++
        }
        if (keep.length() == 0) reply.remove("tool_calls") else reply.put("tool_calls", keep)
        return dropped
    }

    // ---------- calls written as text ----------

    private val tagRe = Regex("<(tool_call|function_call|tool|function)[^>]*>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
    private val fenceRe = Regex("```(?:json|tool_call|tool)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    private val pyRe = Regex("^\\s*`?([A-Za-z_][A-Za-z0-9_.:]*)\\((.*)\\)`?\\s*$", RegexOption.MULTILINE)

    /**
     * When a model writes a tool call into its text instead of using the API (common on small
     * free models), pull it out. Returns the calls and the text left over, or null if the text
     * holds no call to a real tool.
     */
    fun fromText(text: String, all: JSONArray): Pair<JSONArray, String>? {
        if (text.isBlank()) return null
        val known = names(all)
        val calls = JSONArray()
        var rest = text
        fun fromObj(o: JSONObject): Boolean {
            val f = o.optJSONObject("function")
            val n = (f?.optString("name") ?: o.optString("name").ifBlank { o.optString("tool").ifBlank { o.optString("tool_name") } }).trim()
            val name = resolveName(n, known) ?: return false
            val a = f?.opt("arguments") ?: o.opt("arguments") ?: o.opt("args") ?: o.opt("parameters") ?: o.opt("input")
            calls.put(JSONObject().put("id", "text_${calls.length()}").put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", (parseArgs(a) ?: JSONObject()).toString())))
            return true
        }
        for (m in tagRe.findAll(text)) {
            val body = m.groupValues[2]
            if (splitObjects(body).mapNotNull { parseArgs(it) }.map { fromObj(it) }.any { it }) rest = rest.replace(m.value, "")
        }
        if (calls.length() == 0) for (m in fenceRe.findAll(text)) {
            val objs = splitObjects(m.groupValues[1]).mapNotNull { parseArgs(it) }
            if (objs.map { fromObj(it) }.any { it }) rest = rest.replace(m.value, "")
        }
        if (calls.length() == 0) {
            val t = text.trim()
            if (t.startsWith("{") && t.endsWith("}")) parseArgs(t)?.let { if (fromObj(it)) rest = "" }
        }
        if (calls.length() == 0) for (m in pyRe.findAll(text)) {
            val name = resolveName(m.groupValues[1], known) ?: continue
            if (key(m.groupValues[1]) != key(name)) continue   // only exact tool names for this loose form
            val args = JSONObject()
            Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|[^,]+)").findAll(m.groupValues[2]).forEach { a ->
                val v = a.groupValues[2].trim()
                args.put(a.groupValues[1], when {
                    v.startsWith("\"") || v.startsWith("'") -> v.substring(1, v.length - 1)
                    v == "true" || v == "True" -> true; v == "false" || v == "False" -> false
                    else -> v.toLongOrNull() ?: v.toDoubleOrNull() ?: v
                })
            }
            calls.put(JSONObject().put("id", "text_${calls.length()}").put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", args.toString())))
            rest = rest.replace(m.value, "")
        }
        return if (calls.length() > 0) calls to rest.trim() else null
    }

    // ---------- history hygiene ----------

    /**
     * A copy of [msgs] every provider accepts: private keys dropped, arguments valid JSON strings,
     * every tool_call answered by exactly one tool message right after it, no orphan tool
     * messages, no empty assistant turns.
     */
    fun clean(msgs: JSONArray): JSONArray {
        val out = JSONArray()
        var i = 0
        while (i < msgs.length()) {
            val m = JSONObject(msgs.optJSONObject(i)?.toString() ?: "{}")
            m.keys().asSequence().toList().filter { it.startsWith("_") }.forEach { m.remove(it) }
            val role = m.optString("role")
            if (role == "tool") {   // a tool message not right after its call
                out.put(JSONObject().put("role", "user").put("content", "[Tool result${m.optString("name").let { if (it.isBlank()) "" else " from $it" }}]\n" + m.optString("content")))
                i++; continue
            }
            val calls = if (role == "assistant") m.optJSONArray("tool_calls") else null
            if (calls == null || calls.length() == 0) {
                if (role == "assistant") {
                    m.remove("tool_calls")
                    if (m.isNull("content") || (m.opt("content") as? String)?.isBlank() == true) m.put("content", "…")
                }
                out.put(m); i++; continue
            }
            val ids = ArrayList<String>()
            val fixed = JSONArray()
            for (j in 0 until calls.length()) {
                val c = JSONObject(calls.optJSONObject(j)?.toString() ?: continue)
                val f = c.optJSONObject("function") ?: continue
                if (f.optString("name").isBlank()) continue
                val a = f.opt("arguments")
                f.put("arguments", (if (a is JSONObject) a else parseArgs(a) ?: JSONObject()).toString())
                var id = c.optString("id").ifBlank { "call_${i}_$j" }
                while (id in ids) id += "x"
                c.put("id", id); c.put("type", "function")
                ids += id; fixed.put(c)
            }
            if (fixed.length() == 0) { m.remove("tool_calls"); if (m.isNull("content")) m.put("content", "…"); out.put(m); i++; continue }
            m.put("tool_calls", fixed)
            out.put(m)
            // Collect the tool replies that follow, then emit one per call in call order.
            val replies = LinkedHashMap<String, JSONObject>()
            val loose = ArrayList<JSONObject>()
            var k = i + 1
            while (k < msgs.length() && msgs.optJSONObject(k)?.optString("role") == "tool") {
                val t = JSONObject(msgs.getJSONObject(k).toString())
                t.keys().asSequence().toList().filter { it.startsWith("_") }.forEach { t.remove(it) }
                val tid = t.optString("tool_call_id")
                if (tid in ids && tid !in replies) replies[tid] = t else loose += t
                k++
            }
            for ((j, id) in ids.withIndex()) {
                val t = replies[id] ?: loose.removeFirstOrNull()?.put("tool_call_id", id)
                    ?: JSONObject().put("role", "tool").put("tool_call_id", id).put("content", "(no result)")
                if (!t.has("name")) t.put("name", fixed.getJSONObject(j).getJSONObject("function").getString("name"))
                if (t.optString("content").isEmpty()) t.put("content", "(empty)")
                out.put(t)
            }
            loose.forEach { t -> out.put(JSONObject().put("role", "user").put("content", "[Tool result]\n" + t.optString("content"))) }
            i = k
        }
        return out
    }

    /**
     * Last resort when a provider still refuses the conversation: every tool turn becomes plain
     * text (assistant says what it called, the result comes back as a user note). No ids, no
     * signatures, nothing a strict API can reject. The model keeps all the information.
     */
    fun flatten(msgs: JSONArray): JSONArray {
        val src = clean(msgs)
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val m = src.getJSONObject(i)
            when (m.optString("role")) {
                "assistant" -> {
                    val calls = m.optJSONArray("tool_calls")
                    if (calls == null) out.put(m) else {
                        val said = (m.opt("content") as? String).orEmpty().trim()
                        val list = (0 until calls.length()).joinToString("; ") {
                            val f = calls.getJSONObject(it).getJSONObject("function"); "${f.optString("name")}(${f.optString("arguments").take(300)})"
                        }
                        out.put(JSONObject().put("role", "assistant").put("content", listOf(said, "[I called: $list]").filter { it.isNotBlank() }.joinToString("\n")))
                    }
                }
                "tool" -> out.put(JSONObject().put("role", "user").put("content", "[Result of ${m.optString("name", "tool")}]\n" + m.optString("content").take(8000)))
                else -> out.put(m)
            }
        }
        // Merge back-to-back user notes so strict providers that want alternating roles accept it.
        val merged = JSONArray()
        for (i in 0 until out.length()) {
            val m = out.getJSONObject(i)
            val prev = if (merged.length() > 0) merged.getJSONObject(merged.length() - 1) else null
            if (prev != null && prev.optString("role") == "user" && m.optString("role") == "user" && prev.opt("content") is String && m.opt("content") is String)
                prev.put("content", prev.getString("content") + "\n\n" + m.getString("content"))
            else merged.put(m)
        }
        return merged
    }

    /** True for a provider refusing the request's shape (worth one cleaned retry). */
    fun isShapeError(e: Exception): Boolean {
        val code = (e as? LlmClient.LlmException)?.code ?: 0
        val m = e.message.orEmpty()
        return code == 400 || code == 422 || m.contains("INVALID_ARGUMENT", true) || m.contains("invalid argument", true) ||
            m.contains("tool_call", true) || m.contains("function call", true) || m.contains("thought_signature", true) ||
            m.contains("tool_use", true) || m.contains("must be followed", true)
    }

    /** A plain-English version of a provider error, for the chat. */
    fun friendly(e: Exception): String {
        val m = e.message.orEmpty()
        val who = m.substringBefore(" said (", "").ifBlank { m.substringBefore(":", "the AI") }.take(60)
        return when {
            isShapeError(e) -> "$who refused the request even after I cleaned it up. Tap Retry, or say it a different way if it keeps happening."
            else -> m
        }
    }

    /** Shortens tool descriptions for small models: long text costs tokens and confuses them. */
    fun slim(tools: JSONArray, maxDesc: Int = 420, maxParam: Int = 160): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val t = JSONObject(tools.optJSONObject(i)?.toString() ?: continue)
            val f = t.optJSONObject("function")
            if (f == null) { out.put(t); continue }
            f.optString("description").takeIf { it.length > maxDesc }?.let { f.put("description", it.take(maxDesc).substringBeforeLast(". ") + ".") }
            f.optJSONObject("parameters")?.optJSONObject("properties")?.let { p ->
                p.keys().forEach { k -> p.optJSONObject(k)?.let { q -> q.optString("description").takeIf { it.length > maxParam }?.let { q.put("description", it.take(maxParam)) } } }
            }
            out.put(t)
        }
        return out
    }

    // ---------- provider adapters ----------

    /**
     * The tool list as this provider likes it. Gemini rejects some JSON-schema keywords
     * (additionalProperties, $schema, examples...) with INVALID_ARGUMENT, and wants enum values
     * as strings. Free / small-model routes get shorter descriptions.
     */
    fun adapt(tools: JSONArray, pid: String, model: String): JSONArray {
        val small = pid == Providers.keyless.id || model.endsWith(":free") || pid == "groq" ||
            Regex("(?i)(mini|small|tiny|nano|\\b[1-9]b\\b|lite|flash-8b)").containsMatchIn(model)
        val base = if (small && Settings.bool("tool_slim", true)) slim(tools) else tools
        if (pid != "gemini") return base
        val out = JSONArray()
        for (i in 0 until base.length()) out.put(scrub(JSONObject(base.getJSONObject(i).toString())))
        return out
    }

    private val geminiDrop = setOf("additionalProperties", "\$schema", "examples", "default", "\$ref", "\$defs", "definitions", "pattern", "format", "minLength", "maxLength", "const")

    /** [props] = this object is a "properties" map, whose keys are argument names, not keywords. */
    private fun scrub(o: JSONObject, props: Boolean = false): JSONObject {
        for (k in o.keys().asSequence().toList()) {
            if (!props && k in geminiDrop) { o.remove(k); continue }
            when (val v = o.opt(k)) {
                is JSONObject -> scrub(v, !props && k == "properties")
                is JSONArray -> {
                    if (k == "enum") o.put(k, JSONArray((0 until v.length()).map { v.opt(it).toString() }).also { if (o.optString("type") != "string") o.put("type", "string") })
                    else for (j in 0 until v.length()) (v.opt(j) as? JSONObject)?.let { scrub(it) }
                }
            }
        }
        return o
    }
}
