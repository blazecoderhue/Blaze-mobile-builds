package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.45.0 (#11, the safe part): a chat file is still one JSON file in the same format, but each message's JSON is made
 * once and reused by later saves (messages are immutable; a changed message is a new object). The main chat saved after
 * every turn no longer re-serialises thousands of old messages each time. The model history is still written whole
 * (its entries can change in place). Storing messages as rows is left for later (#11 L).
 *
 * The text is byte-for-byte what JSONObject would make for the same fields (ChatJsonTest parses both).
 */
object ChatJson {
    private val cache = java.util.WeakHashMap<ChatMessage, String>()

    /** One message as saved (without its history mark). */
    fun message(m: ChatMessage): String = synchronized(cache) { cache[m] } ?: build(m).also { s -> synchronized(cache) { cache[m] = s } }

    private fun build(m: ChatMessage): String = JSONObject().put("id", m.id).put("role", m.role.name).put("text", m.text)
        .put("files", JSONArray(m.files)).apply {
            if (m.thinking.isNotEmpty()) put("thinking", m.thinking.take(20000))
            if (m.followups.isNotEmpty()) put("followups", JSONArray(m.followups))
            if (m.meta.isNotEmpty()) put("meta", m.meta)
            if (m.at > 0) put("at", m.at)
            if (m.media.isNotEmpty()) put("media", JSONArray(m.media))
            if (m.thinkMs > 0) put("think_ms", m.thinkMs)
            if (m.startAt > 0) put("start_at", m.startAt)
            if (m.replyTo.isNotEmpty()) put("reply_to", JSONArray(m.replyTo))
            if (m.speaker.isNotEmpty()) put("speaker", m.speaker)
            if (m.receipt.isNotEmpty()) put("receipt", m.receipt)
            if (m.trace.isNotEmpty()) put("trace", m.trace)   // v0.45.5 "What happened"
            if (m.gid.isNotEmpty()) put("gid", m.gid)   // v0.45.0 (#37)
            if (m.reactions.isNotEmpty()) put("reactions", Reaction.json(m.reactions))   // v0.45.0 reactions
        }.toString()

    /** The "messages" array, with each message's history mark (marks change without the message changing). */
    fun messages(msgs: List<ChatMessage>, marks: Map<Long, Int>): String {
        val sb = StringBuilder(msgs.size * 160 + 2).append('[')
        msgs.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            val s = message(m)
            val mk = marks[m.id]
            if (mk == null) sb.append(s) else sb.append(s, 0, s.length - 1).append(",\"mark\":").append(mk).append('}')
        }
        return sb.append(']').toString()
    }

    /** The whole file: [head]'s fields, then "messages" and "history". */
    fun file(head: JSONObject, messagesJson: String, history: JSONArray): String {
        val h = head.toString()
        return StringBuilder(h.length + messagesJson.length + 64).append(h, 0, h.length - 1)
            .append(if (head.length() > 0) "," else "").append("\"messages\":").append(messagesJson)
            .append(",\"history\":").append(history.toString()).append('}').toString()
    }

    /**
     * v0.45.0 (#2; P5 #26 moved here from Agent): pure parse of a chat file. Media paths are not checked with
     * File.exists here (a missing file is skipped when its row is drawn). Messages saved before global ids get their
     * deterministic ids here (Gid.fill: idempotent, the same on every phone); they are written on the next save.
     */
    internal fun parse(o: JSONObject): ParsedChat {
        val list = ArrayList<ChatMessage>()
        val marks = HashMap<Long, Int>()
        val ms = o.optJSONArray("messages") ?: JSONArray()
        Gid.fill(ms)
        var maxId = 0L
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val mid = m.getLong("id"); maxId = maxOf(maxId, mid)
            val files = m.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val fu = m.optJSONArray("followups")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val media = m.optJSONArray("media")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            // v0.30.6: "reply_to" is optional (chats saved before it load with none).
            val replyTo = m.optJSONArray("reply_to")?.let { a -> (0 until a.length()).map { a.optLong(it) } }?.filter { it > 0 } ?: emptyList()
            list += ChatMessage(mid, Role.valueOf(m.getString("role")), m.getString("text"), files, m.optString("thinking"), fu, m.optString("meta"), media,
                at = m.optLong("at"), thinkMs = m.optLong("think_ms"), startAt = m.optLong("start_at"), replyTo = replyTo, speaker = m.optString("speaker"),
                receipt = m.optString("receipt"), gid = m.getString("gid"), reactions = Reaction.list(m.optJSONArray("reactions")), trace = m.optString("trace"))
            if (m.has("mark")) marks[mid] = m.getInt("mark")
        }
        return ParsedChat(o, list, marks, maxId)
    }
}

/** v0.45.0 (#2): a chat file turned into messages, with no Session touched (so it can be built off the main thread). */
internal class ParsedChat(val o: JSONObject, val msgs: List<ChatMessage>, val marks: Map<Long, Int>, val maxId: Long)
