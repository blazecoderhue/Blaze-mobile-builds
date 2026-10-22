package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.45.0 context: the user's goal and constraints stay pinned for the whole task. Long phone tasks fold old steps into
 * a summary (compaction), very long chats send a window, a too-large request is shrunk, and a Cloud run keeps the
 * conversation it was created with. In each case the request that started the task could fall out of what the model
 * sees. Now the task's goal and its constraints ("don't send anything", "under K200", "only WhatsApp", "by 6 pm") go
 * into the system prompt of every step ([block]), which compaction, windowing and shrinking never cut.
 *
 * A short follow-up ("yes", "go on", "continue", "do it") keeps the goal of the request before it. Pure; GoalPinTest.
 */
object GoalPin {
    data class Goal(val request: String, val constraints: List<String>)

    private val ic = setOf(RegexOption.IGNORE_CASE)
    private val followUp = Regex("""^\s*(?:y(?:es|eah|ep)?|ok(?:ay)?|sure|go(?: on| ahead)?|continue|keep going|carry on|do it|proceed|again|try again|retry|next|and\??|then\??|ee|eya|inde|ndiyo|sawa|oui|vas-y|continue[sz]?)\s*[.!?]*\s*$""", ic)
    private val constraintRe = Regex("""\b(?:don'?t|do not|never|only|just|without|must|make sure|before|after|by \d|until|under|below|less than|more than|at most|at least|no more than|budget|max(?:imum)?|min(?:imum)?|exactly|instead of|not (?:the|my|on)|keep|ask me|confirm|ne pas|sans|seulement|usiku|tu|bila|usi\w+|osa\w*|fye)\b""", ic)

    fun isFollowUp(text: String): Boolean = followUp.matches(text.trim()) || text.trim().length <= 3 || StopResume.isResume(text)   // v0.45.0 (#38)

    private fun sentences(t: String) = t.split(Regex("""(?<=[.!?;])\s+|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }

    /** [current] = this turn's request; [earlier] = earlier user requests, newest last. */
    fun of(current: String, earlier: List<String> = emptyList()): Goal {
        val cur = current.trim()
        val base = if (isFollowUp(cur)) earlier.lastOrNull { !isFollowUp(it) }?.trim().orEmpty() else ""
        val request = listOf(base, cur).filter { it.isNotBlank() }.joinToString(" \u2192 ").let { if (it.length > 600) it.take(597) + "\u2026" else it }
        val cons = (sentences(base) + sentences(cur)).filter { constraintRe.containsMatchIn(it) }.distinct().take(6).map { if (it.length > 160) it.take(157) + "\u2026" else it }
        return Goal(request, cons)
    }

    /** The pinned block for the system prompt ("" when there's no request). */
    fun block(g: Goal?): String {
        if (g == null || g.request.isBlank()) return ""
        return "\nCURRENT TASK (pinned; keep to it until it's done, even after earlier steps are summarised): \u201c${g.request}\u201d" +
            (if (g.constraints.isNotEmpty()) " Constraints the user set: " + g.constraints.joinToString(" | ") { "\u201c$it\u201d" } + "." else "")
    }

    /** User request texts in [history] (plain strings, newest last; Blaze's own bracketed notes skipped). */
    fun userTexts(history: JSONArray, max: Int = 4): List<String> {
        val out = ArrayList<String>()
        var i = history.length() - 1
        while (i >= 0 && out.size < max) {
            val m = history.optJSONObject(i)
            if (m?.optString("role") == "user") {
                val c = m.opt("content")
                val t = when (c) {
                    is String -> c
                    is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }
                    else -> ""
                }.trim()
                if (t.isNotEmpty() && !t.startsWith("[")) out.add(0, t)
            }
            i--
        }
        return out
    }
}

/**
 * v0.45.0 context: when a request is too large and has to be shrunk, the messages that are cut are not just dropped:
 * a digest of them (what the user asked, which tools ran and what they returned, first lines only) goes in their place
 * ([digest]), so the model still knows what happened. Pure; GoalPinTest.
 */
object ContextKeep {
    fun digest(dropped: List<JSONObject>, maxChars: Int = 3_000): String {
        if (dropped.isEmpty()) return ""
        val lines = ArrayList<String>()
        for (m in dropped) {
            val role = m.optString("role")
            val c = m.opt("content")
            val text = (c as? String ?: (c as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") } } ?: "")
                .trim().lineSequence().firstOrNull().orEmpty().take(160)
            when (role) {
                "user" -> if (text.isNotBlank()) lines += "User: $text"
                "assistant" -> {
                    val calls = m.optJSONArray("tool_calls")
                    if (calls != null && calls.length() > 0) lines += "You called: " + (0 until calls.length()).joinToString(", ") { calls.optJSONObject(it)?.optJSONObject("function")?.optString("name").orEmpty() }
                    else if (text.isNotBlank()) lines += "You: $text"
                }
                "tool" -> lines += "  \u2192 ${m.optString("name").ifBlank { "result" }}: $text"
            }
        }
        val sb = StringBuilder("[Earlier in this conversation, shortened to fit (first lines only):\n")
        for (l in lines) { if (sb.length + l.length > maxChars) { sb.append("\u2026\n"); break }; sb.append(l).append('\n') }
        return sb.append("]").toString()
    }
}
