package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.28.1: tool calls a model wrote as text ("leaked") instead of calling the tool. Seen on Luke's phone:
 *  - a bubble that only says `[/find_tool need="stock tracking"]`
 *  - `[github_open repo="LacksonKapapa/Blaze-Agent"]`
 *  - a reply starting `[/blazer_save kind=features text="## App ...` (cut off, never closed)
 *
 * [find] parses them (bracketed `[/name k="v" …]` / `[name k=v …]`, `<tool_call>name k="v"</tool_call>`, `<tool_call>{json}`,
 * `<function=name>{json}</function>`, `[TOOL_CALLS][{json}]`), [toCalls] turns the ones that map to a real tool into
 * real calls (exact or near name, else a known alias: save → memory_save, github_open → browser_open with the repo
 * URL, tool search → find_tool), and [strip] removes all of it from what the user sees (while streaming, an
 * unfinished one too). Pure Kotlin, unit tested in ToolLeakTest with exactly the strings above.
 */
object ToolLeak {
    data class Leak(val name: String, val args: JSONObject, val raw: String)

    /** The Blaze agent repo, for `github_open` with no usable repo (or a misspelt Blaze one). */
    const val BLAZE_REPO_URL = "https://gitlab.com/blazeinchue/Blaze-agent"

    /** Asked once per turn when a leak can't be turned into a real call. */
    const val NUDGE = "Your last reply contained tool-call text (like [/name key=\"value\"]) instead of a real tool call, " +
        "so the user would only see code. Call tools only through the native tool-calling API. If no tool fits, answer in plain words. " +
        "Never write tool names, brackets or arguments in your reply."

    /** Static system-prompt rule (in the cached prefix). */
    const val PROMPT_RULE = "TOOL CALLS: call tools only through the native tool-calling API (the tools list), never by writing them in your reply. " +
        "Never put tool names, brackets like [/tool ...] or [tool key=\"value\"], <tool_call> tags or JSON calls in text the user sees: they see that as broken code. " +
        "If you need a tool that isn't loaded, call find_tool or load_tools natively. If no tool fits, answer in plain words."

    /** Shown when nothing could be recovered: plain words, no syntax. */
    const val HONEST = "I meant to run a step for that but it didn't go through, so nothing was done yet. Could you ask me again?"

    private const val NAME = """[A-Za-z_][A-Za-z0-9_.:\-]*"""
    private const val VALUE = """"(?:[^"\\]|\\.)*(?:"|$)|'[^']*(?:'|$)|[^\s\]"']+"""
    private const val PAIR = """[A-Za-z_][A-Za-z0-9_]*\s*=\s*(?:$VALUE)"""
    // [/name args…]  (slash form: args optional)
    private val slashRe = Regex("""\[/\s*($NAME)((?:\s+(?:$PAIR))*)\s*/?\s*(?:\]|$)""")
    // [name k=v …]  (no slash: at least one key=value, so [x], [1], [link] and markdown are never touched)
    private val pairRe = Regex("""\[\s*($NAME)((?:\s+(?:$PAIR))+)\s*/?\s*(?:\]|$)""")
    private val pairInner = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*=\s*($VALUE)""")
    private val tagRe = Regex("""<(tool_call|function_call|tool)>\s*([\s\S]*?)(?:</\1>|$)""", RegexOption.IGNORE_CASE)
    private val fnTagRe = Regex("""<function=($NAME)>\s*([\s\S]*?)(?:</function>|$)""", RegexOption.IGNORE_CASE)
    private val mistralRe = Regex("""\[TOOL_CALLS\]\s*(\[[\s\S]*?\]|\{[\s\S]*?\})\s*$""")

    private fun unquote(v: String): Any {
        val t = v.trim()
        if (t.startsWith("\"")) {
            val body = t.substring(1).let { if (it.endsWith("\"") && !it.endsWith("\\\"")) it.dropLast(1) else it }
            return body.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
        }
        if (t.startsWith("'")) return t.substring(1).removeSuffix("'")
        return when (t) { "true" -> true; "false" -> false; else -> t.toLongOrNull() ?: t.toDoubleOrNull() ?: t }
    }

    private fun pairs(s: String): JSONObject {
        val o = JSONObject()
        pairInner.findAll(s).forEach { m -> o.put(m.groupValues[1], unquote(m.groupValues[2])) }
        return o
    }

    private fun fromJson(o: JSONObject, raw: String): Leak? {
        val f = o.optJSONObject("function")
        val n = (f?.optString("name") ?: o.optString("name").ifBlank { o.optString("tool").ifBlank { o.optString("tool_name") } }).trim()
        if (n.isEmpty()) return null
        val a = f?.opt("arguments") ?: o.opt("arguments") ?: o.opt("args") ?: o.opt("parameters") ?: o.opt("input")
        return Leak(n, ToolCalls.parseArgs(a) ?: JSONObject(), raw)
    }

    /** Every leaked call in [text], in order. */
    fun find(text: String): List<Leak> {
        if (text.isBlank() || (!text.contains('[') && !text.contains('<'))) return emptyList()
        val out = ArrayList<Pair<Int, Leak>>()
        val taken = ArrayList<IntRange>()
        fun add(r: IntRange, l: Leak) { if (taken.none { it.first <= r.last && r.first <= it.last }) { taken += r; out += r.first to l } }
        for (m in tagRe.findAll(text)) {
            val body = m.groupValues[2].trim()
            val objs = ToolCalls.splitObjects(body).mapNotNull { ToolCalls.parseArgs(it) }.mapNotNull { fromJson(it, m.value) }
            if (objs.isNotEmpty()) objs.forEach { add(m.range, it) }
            else Regex("""^/?($NAME)([\s\S]*)$""").find(body)?.let { b -> add(m.range, Leak(b.groupValues[1], pairs(b.groupValues[2]), m.value)) }
        }
        for (m in fnTagRe.findAll(text)) add(m.range, Leak(m.groupValues[1], ToolCalls.parseArgs(m.groupValues[2]) ?: pairs(m.groupValues[2]), m.value))
        mistralRe.find(text)?.let { m ->
            val body = m.groupValues[1].trim()
            val objs = if (body.startsWith("[")) try { val a = JSONArray(body); (0 until a.length()).mapNotNull { a.optJSONObject(it) } } catch (_: Exception) { emptyList() }
                else listOfNotNull(ToolCalls.parseArgs(body))
            objs.mapNotNull { fromJson(it, m.value) }.forEach { add(m.range, it) }
        }
        for (m in slashRe.findAll(text)) add(m.range, Leak(m.groupValues[1], pairs(m.groupValues[2]), m.value))
        for (m in pairRe.findAll(text)) add(m.range, Leak(m.groupValues[1], pairs(m.groupValues[2]), m.value))
        return out.sortedBy { it.first }.map { it.second }
    }

    fun has(text: String): Boolean = find(text).isNotEmpty() || unfinished(text) >= 0

    // While streaming: "[/" + a name, "[name_with_underscore " or "<tool_call" with no end yet hides the rest.
    private val openRe = Regex("""\[/\s*[A-Za-z_]?[A-Za-z0-9_.:\-]*[^\]]*$|\[[A-Za-z]+_[A-Za-z0-9_]*(?:\s[^\]]*)?$|<(?:tool_call|function_call|function=)[^>]*>?[^<]*$|<t(?:o(?:o(?:l(?:_(?:c(?:a(?:l(?:l)?)?)?)?)?)?)?)?$|\[TOOL_CALLS[\s\S]*$""")

    /** Index where an unfinished leaked call starts at the end of [text], or -1. */
    fun unfinished(text: String): Int {
        val m = openRe.find(text) ?: return -1
        // "[x" / "[1" / "[link" alone aren't calls; "[/" alone is the start of one.
        val s = m.value
        if (s.startsWith("[") && !s.startsWith("[/") && !s.startsWith("[TOOL_CALLS") && !s.contains('_')) return -1
        return m.range.first
    }

    /** [text] without any leaked call (and, while streaming, without an unfinished one at the end). */
    fun strip(text: String, final: Boolean = true): String {
        if (text.isEmpty() || (!text.contains('[') && !text.contains('<'))) return text
        var t = text
        for (l in find(t)) t = t.replace(l.raw, "")
        if (!final) unfinished(t).takeIf { it >= 0 }?.let { t = t.substring(0, it) }
        return t.replace(Regex("""[ \t]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** v0.28.3: shown instead of an old saved reply that was nothing but leaked tool syntax. */
    const val STORED_EMPTY = "That step didn't run, so nothing was done. Tap Retry to try it again."

    /**
     * v0.28.3: what the screen shows for a saved reply or chat preview. Replies saved before v0.28.1 can still hold raw
     * leaked calls (Luke's `[github_open repo="LacksonKapapa/Blaze-Agent"]` at 15:55, shown again after the update),
     * so they're stripped when drawn too. Text with no leak comes back unchanged (byte for byte).
     */
    fun forDisplay(text: String): String {
        if (text.isEmpty()) return text
        // 0.45.19: the final sanitizer (CallScan) too: multi-line Python calls, JSON call payloads mid-prose and fenced
        // tool blocks for any known tool. Text with nothing to hide still comes back byte for byte.
        val t = if ((!text.contains('[') && !text.contains('<')) || find(text).isEmpty()) text else strip(text, final = true)
        return try { CallScan.clean(t, final = true) } catch (_: Throwable) { t }
    }

    /** [forDisplay] for a reply bubble: a reply that was only a leaked call becomes [STORED_EMPTY] instead of an empty bubble. */
    fun bubbleText(text: String): String {
        // v0.43.0: a saved reply that is only a Python-style call (`browser_show()`) shows the plain line too.
        if (text.isNotBlank() && text.length < 400 && DuoText.onlyCalls(text)) return STORED_EMPTY
        val t = forDisplay(text)
        // 0.45.19: a reply that was nothing but call text (even cut off or unreadable) is the plain line, never code.
        return if (t.isBlank() && text.isNotBlank()) STORED_EMPTY else t
    }

    // ---------- mapping to real tools ----------

    private fun key(n: String) = n.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun call(i: Int, name: String, args: JSONObject): JSONObject =
        JSONObject().put("id", "leak_$i").put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))

    /** The Blaze repos by squashed name (any case, dashes or none, "desktop" misspelt the repo's way too). v0.28.4. */
    val BLAZE_REPOS = mapOf(
        "blazeagent" to BLAZE_REPO_URL,
        "blazeandroid" to BLAZE_REPO_URL,
        "blazedekstop" to "https://gitlab.com/blazeinchue/Blaze-dekstop",
        "blazedesktop" to "https://gitlab.com/blazeinchue/Blaze-dekstop",
        "blazecli" to "https://gitlab.com/blazeinchue/Blaze-cli",
    )

    /** A guessed owner for Blaze's repos ("LacksonKapapa", "luckson-kapapa", any "kapapa" spelling in any case, "blaze", "blazeinchue"). */
    fun blazeOwner(owner: String): Boolean {
        val k = key(owner)
        return k.contains("kapapa") || k == "blazeinchue" || k.startsWith("lackson") || k.startsWith("luckson") || k == "blaze" || k == "blazeapp" || k == "luke"
    }

    /** A GitHub URL for `github_open`-style args: url, else repo ("owner/name"; a Blaze-agent spelling → the real repo). */
    fun githubUrl(args: JSONObject): String {
        args.optString("url").trim().takeIf { it.startsWith("http") }?.let { return fixBlazeUrl(it) }
        val repo = args.optString("repo").ifBlank { args.optString("repository").ifBlank { args.optString("name") } }.trim().trim('/')
            .removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("github.com/").removeSuffix(".git")
        if (repo.isBlank()) return BLAZE_REPO_URL
        // Any owner (or none) with a Blaze repo name: "LacksonKapapa/Blaze-Agent", "blaze-agent", "BLAZE-DESKTOP".
        BLAZE_REPOS[key(repo.substringAfterLast('/'))]?.let { return it }
        return if (Regex("""^[A-Za-z0-9_.\-]+/[A-Za-z0-9_.\-]+$""").matches(repo)) "https://github.com/$repo" else BLAZE_REPO_URL
    }

    private val ghUrl = Regex("""^(?:https?://)?(?:www\.)?github\.com/([^/?#\s]+)/([^/?#\s]+)(.*)$""", RegexOption.IGNORE_CASE)

    /**
     * v0.28.4 (Luke's screenshot: Blaze opened github.com/LacksonKapapa/Blaze-Agent, said it doesn't exist and sent him to
     * a stranger's profile): a github.com URL to a Blaze repo under a guessed owner or the wrong case becomes the real
     * one, keeping the rest of the path (/releases, /tree/apk …). Other URLs are returned unchanged.
     */
    fun fixBlazeUrl(url: String): String {
        val m = ghUrl.find(url.trim()) ?: return url
        val owner = m.groupValues[1]; val repo = m.groupValues[2].removeSuffix(".git")
        val real = BLAZE_REPOS[key(repo)] ?: return url
        if (!blazeOwner(owner)) return url
        return real + m.groupValues[3]
    }

    /** The real tool for [l], or null. Exact / near names first, then aliases for common near-misses. */
    fun map(l: Leak, known: Collection<String>): Pair<String, JSONObject>? {
        ToolCalls.resolveName(l.name, known)?.let { return it to l.args }
        val k = key(l.name.substringAfterLast('.').substringAfterLast(':'))
        val text = l.args.optString("text").ifBlank { l.args.optString("content").ifBlank { l.args.optString("value").ifBlank { l.args.optString("note") } } }
        return when {
            k.startsWith("github") && (k.contains("open") || k.contains("view") || k.contains("show") || k.contains("browse") || l.args.has("repo")) &&
                "browser_open" in known -> "browser_open" to JSONObject().put("url", githubUrl(l.args)).put("show", true)
            (k.contains("findtool") || k.contains("searchtool") || k.contains("toolsearch") || k.contains("findtools") || k == "tools") && "find_tool" in known ->
                "find_tool" to JSONObject().put("need", l.args.optString("need").ifBlank { l.args.optString("query").ifBlank { l.args.optString("q").ifBlank { text } } })
            (k.endsWith("save") || k.startsWith("save") || k.contains("remember") || k.contains("memorize")) && text.isNotBlank() -> when {
                "memory_save" in known -> "memory_save" to JSONObject().put("kind", saveKind(l.args.optString("kind")))
                    .put("subject", l.args.optString("subject").ifBlank { l.args.optString("kind").ifBlank { "You" } }).put("text", text)
                "remember" in known -> "remember" to JSONObject().put("text", text)
                else -> null
            }
            (k == "open" || k == "openurl" || k == "browse" || k == "openlink") && l.args.optString("url").isNotBlank() && "browser_open" in known ->
                "browser_open" to JSONObject().put("url", l.args.optString("url")).put("show", true)
            else -> null
        }
    }

    private val kinds = setOf("profile", "preference", "person", "project", "decision", "fact")   // = Mind.KINDS
    private fun saveKind(k: String): String = k.lowercase().takeIf { it in kinds } ?: if (k.isNotBlank()) "project" else "fact"

    /** Real calls for the leaks in [text] that map to a tool in [all], and the text left over (all leaks stripped). */
    fun toCalls(text: String, all: JSONArray): Pair<JSONArray, String>? {
        val leaks = find(text)
        if (leaks.isEmpty()) return null
        val known = ToolCalls.names(all)
        val calls = JSONArray()
        for (l in leaks) map(l, known)?.let { (n, a) -> calls.put(call(calls.length(), n, a)) }
        if (calls.length() == 0) return null
        return calls to strip(text)
    }
}
