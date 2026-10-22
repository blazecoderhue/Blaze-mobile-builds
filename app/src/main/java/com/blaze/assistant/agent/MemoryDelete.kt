package com.blaze.assistant.agent

import org.json.JSONArray

/**
 * v0.45.3: deleting saved memories by their #ids (the memory_delete tool), pure part (MemoryDeleteTest). One confirm
 * card covers the whole list (deletes keep their card in Autopilot too).
 */
object MemoryDelete {
    const val MAX = 60

    /** The ids in a memory_delete call ([1, "#2", "3, #4"] or "#5 #6"), distinct, in order, at most [MAX]. */
    fun idsOf(v: Any?): List<Long> {
        val raw = when (v) {
            is JSONArray -> (0 until v.length()).joinToString(" ") { v.opt(it)?.toString().orEmpty() }
            null -> ""
            else -> v.toString()
        }
        return Regex("""#?(\d{1,9})""").findAll(raw).mapNotNull { it.groupValues[1].toLongOrNull() }.distinct().take(MAX).toList()
    }

    /** The confirm card for deleting [lines] ("#12 [fact] You: …"): title and a short detail (first lines, then "and N more"). */
    fun card(lines: List<String>): Pair<String, String> {
        val n = lines.size
        val title = if (n == 1) "Delete 1 saved memory?" else "Delete $n saved memories?"
        val shown = lines.take(8).joinToString("\n") { "\u2022 " + it.take(90) }
        return title to (shown + if (n > 8) "\n\u2026and ${n - 8} more" else "")
    }

    /** True for a forget query that is really a list of #ids ("#12", "12, 13"). */
    fun isIdQuery(q: String): Boolean = Regex("""^\s*#?\d{1,9}(?:\s*[, ]\s*#?\d{1,9})*\s*$""").matches(q)
}
