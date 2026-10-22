package com.blaze.assistant.notify

import com.blaze.assistant.agent.Mind
import com.blaze.assistant.agent.MindLogs
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * v0.33.0 Group Brain: Blaze reads a family / class / work group from its chat log (Notification replies › Keep chat logs)
 * and answers "when's the test?" from the history, catches you up on 300 unread messages (who said what, what needs you,
 * dates and plans), and tracks who owes what (contributions, "I'll pay you back", amounts). It hands the model a compact,
 * ranked slice of the log instead of the whole thing. Pure ranking / compression / owes in [Pure] (GroupBrainTest).
 */
object GroupBrain {
    const val TOOL = "group_brain"

    object Pure {
        data class Line(val at: Long, val who: String, val text: String)

        private val stop = setOf("the", "a", "an", "is", "are", "was", "when", "what", "who", "where", "how", "did", "does", "do", "to", "of", "in", "on",
            "for", "and", "or", "it", "i", "we", "you", "me", "my", "our", "about", "say", "said", "anyone", "any", "there", "this", "that", "with", "at")

        fun words(q: String) = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in stop }

        /** The [n] lines most relevant to [q] (word hits, recency as a tie-break), in time order, each with one line of context after. */
        fun rank(lines: List<Line>, q: String, n: Int = 25): List<Line> {
            val w = words(q); if (w.isEmpty()) return lines.takeLast(n)
            val scored = lines.mapIndexed { i, l -> val t = l.text.lowercase(); Triple(i, w.count { t.contains(it) } * 10 + i * 5 / maxOf(1, lines.size), l) }
                .filter { it.second >= 10 }.sortedByDescending { it.second }.take(n)
            val keep = scored.flatMap { listOf(it.first, it.first + 1) }.filter { it < lines.size }.distinct().sorted()
            return keep.map { lines[it] }.take(n + n / 2)
        }

        private val junk = Regex("""^(?:<?media omitted>?|image omitted|sticker|gif|\u200e?this message was deleted|null|\s*)$""", RegexOption.IGNORE_CASE)

        /** Merge consecutive messages from one sender, drop media stubs, clip long ones: ~300 messages fit a small prompt. */
        fun compress(lines: List<Line>, clip: Int = 160): List<Line> {
            val out = ArrayList<Line>()
            for (l in lines) {
                if (junk.matches(l.text.trim())) continue
                val t = l.text.trim().replace(Regex("\\s+"), " ").let { if (it.length > clip) it.take(clip) + "\u2026" else it }
                val last = out.lastOrNull()
                if (last != null && last.who == l.who && l.at - last.at < 10 * 60_000L && last.text.length + t.length < clip * 2) out[out.size - 1] = last.copy(text = last.text + " / " + t)
                else out += Line(l.at, l.who, t)
            }
            return out
        }

        private val oweRe = Regex("""\b(owe[sd]?|pay (?:me|you|back)|paid|refund|contribut\w*|send (?:me|the money)|k\s?\d[\d,.]*|zmw\s?\d|\d[\d,.]*\s?(?:kwacha|zmw)|collect\w*|balance|debt)\b""", RegexOption.IGNORE_CASE)
        fun owes(lines: List<Line>): List<Line> = lines.filter { oweRe.containsMatchIn(it.text) }.takeLast(60)

        /** Lines that mention or ask the user ([me] = names they go by). */
        fun forMe(lines: List<Line>, me: List<String>): List<Line> = lines.filter { l -> l.who != "You" &&
            (me.any { n -> n.length > 1 && l.text.contains(n, true) } || Regex("""@\S+""").containsMatchIn(l.text) && me.any { l.text.contains("@$it", true) }) }
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Group Brain: answer questions from a group chat's history ('when is the test?' in the class group), catch up on many " +
            "unread messages, or list who owes what. Uses the chat logs Blaze keeps from notifications (WhatsApp, Telegram, SMS...).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("ask", "catchup", "owes"))))
            .put("group", JSONObject().put("type", "string").put("description", "group name or id from notif_conversations"))
            .put("question", JSONObject().put("type", "string")).put("hours", JSONObject().put("type", "number").put("description", "catchup window, default 24")))
            .put("required", JSONArray(listOf("action", "group")))))

    private fun lines(group: String, limit: Int): Pair<String, List<Pure.Line>>? {
        val c = Conversations.find(group)
        val d = Mind.db()
        val key = c?.key ?: group.takeIf { it.contains(':') } ?: return null
        val logged = try { d?.let { MindLogs.recent(it, Conversations.journalConv(key), limit) } ?: emptyList() } catch (_: Exception) { emptyList() }
        val l = if (logged.isNotEmpty()) logged.map { Pure.Line(it.at * 1000, if (it.role == "You") "You" else it.role.substringBefore(" (").trim(), it.content) }
            else c?.let { synchronized(it) { it.msgs.toList() } }?.map { Pure.Line(it.at, if (it.self) "You" else it.sender, it.text) } ?: emptyList()
        return (c?.title ?: group) to l
    }

    fun run(a: JSONObject): String {
        val g = a.optString("group").trim()
        val (title, all) = lines(g, 400) ?: return "No chat log for \u201c$g\u201d. Call notif_conversations for names, and make sure Keep chat logs is on (Settings › Notification replies)."
        if (all.isEmpty()) return "Nothing logged for $title yet: logs start from the next message that arrives."
        val df = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
        fun show(l: List<Pure.Line>) = l.joinToString("\n") { "[${df.format(it.at)}] ${it.who}: ${it.text}" }
        return when (a.optString("action")) {
            "ask" -> { val q = a.optString("question"); val hit = Pure.rank(Pure.compress(all, 240), q)
                "From $title (${all.size} messages logged), the lines that look relevant to \u201c$q\u201d:\n${show(hit)}\n\nAnswer from these only; say so if they don't settle it." }
            "owes" -> { val o = Pure.owes(Pure.compress(all, 240))
                if (o.isEmpty()) "No money talk found in $title." else "Money lines in $title (work out who owes whom, totals, and what's still open):\n${show(o)}" }
            else -> { val since = System.currentTimeMillis() - (a.optDouble("hours", 24.0).takeIf { !it.isNaN() } ?: 24.0).times(3_600_000).toLong()
                val w = all.filter { it.at >= since }.ifEmpty { all.takeLast(60) }
                val comp = Pure.compress(w).takeLast(220)
                val who = w.groupingBy { it.who }.eachCount().entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "${it.key} ${it.value}" }
                "Catch-up for $title: ${w.size} messages (most active: $who). Summarise: what happened, decisions, dates/deadlines, anything asked of the user, " +
                    "money, and what they should reply to. Compressed log:\n${show(comp)}" }
        }
    }
}
