package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * v0.43.0: tool calls written as plain text in a Halo x Blaze message. Seen on a phone in @all: Halo's whole reply was
 * the literal text `browser_show()`. The @all chat lane gives the models no tools, so a model that had seen tools
 * earlier in the conversation "called" one by writing it, and the app showed that as the answer.
 *
 * [find] spots the Python-style form (`name()`, `name(key="v", n=2)`, `` `name(...)` ``, `name {json}`, a bare JSON
 * `{"name": …, "arguments": …}`) on a line of its own, plus everything [ToolLeak] already knows (`[/name k=v]`,
 * `<tool_call>…`, `[TOOL_CALLS]…`). [decide] says what to do with a finished message: run the calls that are allowed
 * here, show the rest of the text, ask the model once more for a plain answer, or fall back to a plain line. The user
 * never sees function syntax. Pure Kotlin; DuoTextTest.
 */
object DuoText {
    data class Call(val name: String, val args: JSONObject, val raw: String)

    sealed class Decision {
        /** Nothing to hide, or the text left after taking the calls out. */
        data class Show(val text: String) : Decision()
        /** Calls that map to tools allowed on this lane; [rest] is any plain text around them. */
        data class Run(val calls: List<Call>, val rest: String) : Decision()
        /** Only function syntax and no usable tool: ask once more for a plain answer ([NUDGE]). */
        object Retry : Decision()
        /** Still only syntax after the retry: show [fallback] instead. */
        data class Fallback(val text: String) : Decision()
    }

    /** Sent as a user line when a duo message was only function syntax. */
    const val NUDGE = "Your last message was only function-call text (like name(...)), which the user would see as broken code. " +
        "No tools run in this chat. Answer the user in plain words now. If something needs checking live (is it running, did it open), " +
        "say you can't check it from here and that @blaze can check it. Never write function names or brackets."

    /** The plain line when even the retry came back as syntax. */
    fun fallback(who: String): String = "I can't check that from here, so I won't guess. Ask @blaze and it will look properly."

    private const val ID = """[A-Za-z_][A-Za-z0-9_.]*"""
    // name(...) alone on a line, optional backticks / trailing semicolon.
    private val pyLine = Regex("""(?m)^[ \t]*`{0,3}[ \t]*($ID)[ \t]*\(([^\n]*)\)[ \t]*;?[ \t]*`{0,3}[ \t]*$""")
    // name {json} alone on a line.
    private val braceLine = Regex("""(?m)^[ \t]*`?($ID)[ \t]*(\{[^\n]*\})[ \t]*`?[ \t]*$""")
    private val kv = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*[=:]\s*("(?:[^"\\]|\\.)*"|'[^']*'|[^,)]+)""")

    /** A name that looks like a tool, not a word in a sentence: snake_case, or one of [known]. */
    private fun toolish(name: String, known: Collection<String>): Boolean =
        name.contains('_') || known.any { it.equals(name, true) }

    private fun value(v: String): Any {
        val t = v.trim()
        if (t.length >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) return t.substring(1, t.length - 1)
        return when (t) { "true", "True" -> true; "false", "False" -> false; else -> t.toLongOrNull() ?: t.toDoubleOrNull() ?: t }
    }

    fun parseArgs(s: String): JSONObject {
        val t = s.trim()
        if (t.isEmpty()) return JSONObject()
        if (t.startsWith("{")) return try { JSONObject(t) } catch (_: Exception) { JSONObject() }
        val o = JSONObject()
        kv.findAll(t).forEach { m -> o.put(m.groupValues[1], value(m.groupValues[2])) }
        return o
    }

    private val fence = Regex("```[\\s\\S]*?(```|$)")

    /** Code blocks the user asked for: a `my_func()` line inside one is code, not a leaked call. */
    private fun inFence(text: String, at: Int): Boolean = fence.findAll(text).any { at in it.range && !it.value.trimEnd('`').trim().let { b -> pyLine.matches(b.removePrefix("```").trim()) } }

    /** Every text-form call in [text], in order of appearance. [known] = the tool names this app has (any lane). */
    fun find(text: String, known: Collection<String> = emptyList()): List<Call> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Pair<Int, Call>>()
        for (m in pyLine.findAll(text)) {
            val n = m.groupValues[1]
            if (!toolish(n, known) || inFence(text, m.range.first)) continue
            out += m.range.first to Call(n, parseArgs(m.groupValues[2]), m.value)
        }
        for (m in braceLine.findAll(text)) {
            val n = m.groupValues[1]
            if (!toolish(n, known) || out.any { it.first == m.range.first } || inFence(text, m.range.first)) continue
            out += m.range.first to Call(n, parseArgs(m.groupValues[2]), m.value)
        }
        val t = text.trim()
        if (t.startsWith("{") && t.endsWith("}")) try {
            val o = JSONObject(t)
            val f = o.optJSONObject("function")
            val n = (f?.optString("name") ?: o.optString("name").ifBlank { o.optString("tool") }).trim()
            if (n.isNotEmpty()) {
                val a = f?.opt("arguments") ?: o.opt("arguments") ?: o.opt("args") ?: o.opt("parameters")
                out += 0 to Call(n, (a as? JSONObject) ?: (a as? String)?.let { parseArgs(it) } ?: JSONObject(), text)
            }
        } catch (_: Exception) { }
        for (l in ToolLeak.find(text)) out += text.indexOf(l.raw).coerceAtLeast(0) to Call(l.name, l.args, l.raw)
        return out.sortedBy { it.first }.map { it.second }.distinctBy { it.raw }
    }

    // While streaming: a last line that is starting to look like `some_tool(` is held back until it's finished.
    private val partialLine = Regex("""(?:^|\n)[ \t]*`{0,3}[ \t]*[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]*)+[ \t]*(?:\([^\n]*)?$""")

    /** [text] without any text-form call (while streaming, without one that's still being written at the end). */
    fun strip(text: String, final: Boolean = true, known: Collection<String> = emptyList()): String {
        if (text.isEmpty()) return text
        var t = text
        for (c in find(t, known)) t = t.replace(c.raw, "")
        t = ToolLeak.strip(t, final)
        if (!final) partialLine.find(t)?.let { t = t.substring(0, it.range.first) }
        return t.replace(Regex("""[ \t]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /** True when [text] had calls and nothing worth reading is left without them. */
    fun onlyCalls(text: String, known: Collection<String> = emptyList()): Boolean =
        find(text, known).isNotEmpty() && !meaningful(strip(text, true, known))

    private fun meaningful(s: String): Boolean = s.count { it.isLetterOrDigit() } >= 2

    /**
     * What to do with a finished message [text] (control tags already removed). [allowed] = tools that may run on this
     * lane (empty in the @all chat); [known] = every tool name, so a real tool written as text is recognised even
     * where it can't run; [retried] = the nudge was already sent once.
     */
    fun decide(text: String, allowed: Set<String>, known: Collection<String> = allowed, retried: Boolean, who: String = "Halo"): Decision {
        val calls = find(text, known + allowed)
        if (calls.isEmpty()) return Decision.Show(text)
        val rest = strip(text, true, known + allowed)
        val runnable = calls.mapNotNull { c -> allowed.firstOrNull { it.equals(c.name, true) }?.let { c.copy(name = it) } }
        if (runnable.isNotEmpty()) return Decision.Run(runnable, rest)
        if (meaningful(rest)) return Decision.Show(rest)
        return if (!retried) Decision.Retry else Decision.Fallback(fallback(who))
    }
}
