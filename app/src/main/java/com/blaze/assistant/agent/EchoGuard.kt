package com.blaze.assistant.agent

import org.json.JSONArray

/**
 * v0.45.5 (#1, the 18:42 wrong reply): "Hi! Introduce yourself" came back 13 minutes later as a heading "Current Blaze UI",
 * a quote "Chat (pull down from the top for Home)" and a table of recent apps: the model copied the Awareness block /
 * the blaze_now result into the chat instead of answering. A tool result or the context Blaze sends with the request is
 * never the answer. [echoes] spots a reply that is mostly a copy of those sources (or carries their markers); the agent
 * asks the model once more for a real answer and otherwise shows [HONEST]. Asking about Blaze's state ("what's going on",
 * "what's playing") is exempt. Pure Kotlin (EchoGuardTest).
 */
object EchoGuard {
    const val NUDGE = "[Blaze] Your last reply repeated Blaze's own context or a tool result instead of answering. " +
        "Answer the user's latest message directly, in your own words, in one or two sentences. Don't copy context, tables or tool output."
    const val HONEST = "Sorry, that reply went wrong (it repeated my own status notes). Ask me again and I'll answer properly."

    /** Context-reading tools whose output is for Blaze, not the user (unless the user asked for it). */
    val CONTEXT_TOOLS = setOf("blaze_now", "device_status", "processes", "about_blaze", "self_test", "blaze_docs_read")

    private val markers = listOf("WHAT'S GOING ON (live", "## Live now", "## Blaze apps", "## Views (open_view)", "pull down from the top for Home",
        "swipe up for Chat)", "Current Blaze UI", "CURRENT LOCAL TIME:", "Phone now:", "Tracked now:", "(Details: blaze_now)")

    private val askState = Regex("\\b(what'?s (?:going on|happening|playing|on (?:my|the) screen|open|running)|blaze_now|live now|status report|" +
        "what (?:are you|is blaze) (?:doing|running)|recent apps?|what did (?:i|you) (?:just )?(?:do|open)|show (?:me )?(?:the )?(?:status|context|prompt))\\b", RegexOption.IGNORE_CASE)

    /** The user asked about Blaze's state, so quoting it is the answer. */
    fun asked(userText: String): Boolean = askState.containsMatchIn(userText)

    private fun grams(s: String, n: Int = 4): List<String> {
        val w = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (w.size < n) return emptyList()
        return (0..w.size - n).map { i -> w.subList(i, i + n).joinToString(" ") }
    }

    /** Share (0..1) of [reply]'s word 4-grams found in [source]. */
    fun overlap(reply: String, source: String): Double {
        val g = grams(reply); if (g.isEmpty()) return 0.0
        val src = grams(source).toHashSet(); if (src.isEmpty()) return 0.0
        return g.count { it in src }.toDouble() / g.size
    }

    fun hasMarker(reply: String): Boolean = markers.any { reply.contains(it, ignoreCase = true) }

    /**
     * True when [reply] is a copy of Blaze's context: a marker, or at least [min] of its 4-grams from the [context]
     * (system prompt / awareness) or from a context tool's result ([toolTexts]). Short replies (under 8 grams) only count
     * on a marker.
     */
    fun echoes(reply: String, userText: String, context: List<String>, toolTexts: List<String>, min: Double = 0.6): Boolean {
        val r = reply.trim(); if (r.isEmpty() || asked(userText)) return false
        if (hasMarker(r)) return true
        if (grams(r).size < 8) return false
        return (context + toolTexts).any { it.isNotBlank() && overlap(r, it) >= min }
    }

    /** Results of context tools since the latest user message in [history] (role "tool", matched to their call's name). */
    fun contextToolTexts(history: JSONArray): List<String> {
        var start = 0
        for (i in history.length() - 1 downTo 0) if (history.optJSONObject(i)?.optString("role") == "user") { start = i; break }
        val names = HashMap<String, String>()
        val out = ArrayList<String>()
        for (i in start until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            m.optJSONArray("tool_calls")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { c ->
                names[c.optString("id")] = c.optJSONObject("function")?.optString("name").orEmpty() } }
            if (m.optString("role") == "tool" && names[m.optString("tool_call_id")] in CONTEXT_TOOLS) out += m.optString("content")
        }
        return out
    }
}
