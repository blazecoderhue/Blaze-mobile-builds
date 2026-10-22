package com.blaze.assistant.agent

/**
 * v0.27.x: "show me your memories" answers from the memory table, fast, in chat.
 *
 * Joey typed "show me your memories" and Blaze spent 1m40s checking memory, being re-prompted twice and then
 * reopening an old solar-panel diagram in Design. A request to see what Blaze remembers is now recognised
 * before any model call ([isListRequest]) and answered from the saved lines, grouped by kind ([format]),
 * with a short note on how to change or delete them. No Design, no tool loop.
 * Pure Kotlin, unit-tested in IntentHonestyTest.
 */
object MemoryIntent {

    private val ic = setOf(RegexOption.IGNORE_CASE)

    private val asks = listOf(
        // "show me your memories", "list my memories", "can I see what's in your memory"
        Regex("""\b(?:show|list|see|view|read|display|give|tell|open|print|dump)\b.{0,24}\bmemor(?:y|ies)\b""", ic),
        // "what are your memories", "what's in your memory"
        Regex("""\bwhat(?:'s| is| are| do you have| have you got)\b.{0,16}\b(?:your|my|in your|in my|saved|stored)\s+memor(?:y|ies)\b""", ic),
        // "what do you know about me", "what do you remember about me", "what have you saved about me"
        Regex("""\bwhat (?:do|did) you (?:know|remember|have saved|have stored|have) (?:about|on) me\b""", ic),
        Regex("""\bwhat (?:have|has) (?:you|blaze) (?:saved|remembered|learned|learnt|stored|kept|got) (?:about|on) me\b""", ic),
        Regex("""\bwhat do you know of me\b|\beverything you (?:know|remember) about me\b""", ic),
        // a bare "memories?" / "my memories" / "your memory"
        Regex("""^\s*(?:(?:your|my|all|blaze'?s)\s+)?memor(?:y|ies)\s*[?.!]*\s*$""", ic),
    )

    // Phone storage and RAM, photo "memories", and requests to save or delete are something else.
    private val notThis = Regex("""\b(?:ram|storage|space|free|full|usage|used|card|sd|leak|gb|mb|photos?|pictures?|gallery|videos?|""" +
        """memories (?:from|of|in|with)|remember (?:that|this|to)|forget|delete|remove|erase|clear|wipe|save|add)\b""", ic)

    /** Is [text] a request to see what Blaze remembers about the user? */
    fun isListRequest(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("/") || t.length > 120) return false
        if (notThis.containsMatchIn(t)) return false
        return asks.any { it.containsMatchIn(t) }
    }

    /** Group headers, in the order shown. */
    val HEADERS = linkedMapOf(
        "profile" to "About you",
        "preference" to "Preferences",
        "person" to "People",
        "project" to "Projects",
        "decision" to "Decisions",
        "fact" to "Other things",
    )

    const val MAX_LINES = 60
    const val MANAGE = "To change or delete one, say \u201cforget \u2026\u201d with a few of its words, or type /memory to manage them all."
    const val EMPTY = "I don't have anything saved about you yet. Tell me something to keep, like \u201cremember I take my coffee black\u201d, and it'll show up here."

    private fun lineFor(m: Mind.Mem): String {
        val text = m.text.trim().removeSuffix(".")
        val subj = m.subject.trim()
        val plainSubj = subj.isEmpty() || subj.lowercase() in setOf("you", "user", "the user", "me", "general")
        return if (plainSubj || text.lowercase().startsWith(subj.lowercase())) text else "$subj: $text"
    }

    /** The reply for a list request from the memory table. */
    fun format(mems: List<Mind.Mem>): String {
        if (mems.isEmpty()) return EMPTY
        val groups = mems.sortedByDescending { maxOf(it.updated, it.created) }
            .groupBy { if (it.kind in HEADERS) it.kind else "fact" }
        val out = StringBuilder("Here's what I remember about you:\n")
        var shown = 0
        for ((kind, header) in HEADERS) {
            val g = groups[kind] ?: continue
            if (shown >= MAX_LINES) break
            out.append("\n**").append(header).append("**\n")
            for (m in g) {
                if (shown >= MAX_LINES) break
                out.append("- ").append(lineFor(m)).append('\n'); shown++
            }
        }
        val more = mems.size - shown
        if (more > 0) out.append("\n\u2026and $more more.\n")
        out.append('\n').append(MANAGE)
        return out.toString().trim()
    }

    /** The reply when only the old plain list is available (memory store couldn't open). */
    fun formatPlain(facts: List<String>): String {
        val f = facts.map { it.trim() }.filter { it.isNotEmpty() }
        if (f.isEmpty()) return EMPTY
        val shown = f.takeLast(MAX_LINES).reversed()
        return ("Here's what I remember about you:\n\n" + shown.joinToString("\n") { "- ${it.removeSuffix(".")}" } +
            (if (f.size > shown.size) "\n\n\u2026and ${f.size - shown.size} more." else "") + "\n\n" + MANAGE).trim()
    }

    /** Queries that mean "everything" when the model calls memory_search / recall_memory for a list request. */
    fun meansAll(query: String): Boolean = query.trim().lowercase().trim('*', '.', '?', '!', ' ') in
        setOf("", "all", "everything", "any", "me", "user", "the user", "about me", "memories", "memory", "all memories", "list", "list all")
}
