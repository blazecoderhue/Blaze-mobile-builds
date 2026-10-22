package com.blaze.assistant.agent

/**
 * Catches replies that only *look* like work: a tool call or shell command written as text
 * ("$ python app.py", run_shell(command=…), "Action: web_search"), or a claim of having done
 * something ("I ran the tests") when no tool ran this turn. Such a reply is sent back once with
 * [NUDGE] so the model either calls the tool for real or answers in plain language.
 * Pure Kotlin (no Android), so it is unit-tested on the JVM.
 */
object FakeCall {
    // v0.27.x: every use of NUDGE is anchored to the user's latest message (RetryGuard.anchor).

    const val NUDGE = "[Blaze check] Your last reply wrote a command or tool call as text, or said you did something " +
        "that no tool actually did this turn. The user can't run text and never sees your tools. If something needs doing, " +
        "call the tool for real now. Otherwise answer in plain, natural language: what you did and what came of it, " +
        "with no commands, tool names, \"\$ …\" lines or code blocks, unless the user asked for code."

    private val shellFence = Regex("""```\s*(bash|sh|shell|console|zsh|terminal|cmd|powershell|ps1)?\s*\n[\s\S]*?(```|$)""", RegexOption.IGNORE_CASE)
    private val promptLine = Regex("""(?m)^\s*(\$|>>>|PS>|~\$|#)\s+\S""")
    private val agentSyntax = Regex("""(?im)(<\|?(tool_call|function_call|tool)|\[(TOOL_CALL|TOOL|FUNCTION)[\]:]|^\s*(Action|Action Input|Observation|Tool|Tool call|Function call)\s*:)""")
    private val runTalk = Regex("""(?i)\b(i (just )?(ran|executed|run)|i'?ll run|running|executing|output\s*:|result\s*:|returns?\s*:)\b""")
    private val claim = Regex("""(?i)\b(i(?:'ve| have| just)?\s+(ran|run|executed|installed|created|wrote|written|saved|deleted|opened|sent|called|searched|checked|tapped|downloaded|built|pushed|committed|set (up|an alarm|a reminder|a timer))|(the )?(command|tool|script) (ran|executed|returned|output))\b""")
    private val wantsCode = Regex("""(?i)\b(code|command|commands|script|terminal|shell|bash|cli|snippet|syntax|how (do|can|to) (i|you) (install|run|use)|example)\b""")

    /** True when the start of a streamed reply is a tool call in text form, so it shouldn't flash on screen. */
    fun callish(partial: String): Boolean {
        val t = partial.trimStart()
        if (t.isEmpty()) return false
        return t.startsWith("{") || t.startsWith("[{") || t.startsWith("<tool") || t.startsWith("<function") ||
            t.startsWith("```json") || t.startsWith("$ ") || Regex("""^`?[a-z][a-z0-9]*(_[a-z0-9]+)+`?\s*\(""").containsMatchIn(t)
    }

    /**
     * [text] the final reply, [tools] the real tool names, [acted] whether any tool ran this turn,
     * [userText] what the user asked (a request for code makes shell blocks legitimate).
     */
    private val lookVerb = Regex("""(?i)\b(checked|searched|looked)\b""")

    /**
     * [looked] = a read-only tool (memory_search, recall_memory, web_search…) ran this turn: "I checked my memory" /
     * "I searched" is then true and is not sent back (v0.27.x: it used to trigger "Doing it properly this time").
     */
    fun looksFake(text: String, tools: Collection<String>, acted: Boolean, userText: String, looked: Boolean = false): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        // A real tool name used like a call: run_shell(…), "name": "web_search", `file_write`.
        for (n in tools) {
            if (n.length < 4 || !n.contains('_')) continue
            val e = Regex.escape(n)
            if (Regex("""\b$e\s*\(""").containsMatchIn(t) || Regex(""""(name|tool)"\s*:\s*"$e"""").containsMatchIn(t) || t.contains("`$n`")) return true
        }
        if (agentSyntax.containsMatchIn(t)) return true
        val codey = !wantsCode.containsMatchIn(userText)
        val prose = shellFence.replace(t, "").lines().filterNot { promptLine.containsMatchIn(it) }.joinToString(" ").trim()
        val hasShell = shellFence.containsMatchIn(t) || promptLine.containsMatchIn(t)
        if (codey && hasShell && (prose.length < 60 || runTalk.containsMatchIn(t))) return true
        // "I ran / installed / sent …" with nothing actually run.
        if (!acted && claim.findAll(t).any { m -> !(looked && lookVerb.containsMatchIn(m.value)) }) return true
        return false
    }
}
