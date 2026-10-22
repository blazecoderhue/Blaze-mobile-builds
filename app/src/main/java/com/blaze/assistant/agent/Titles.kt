package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat titles from the dominant theme of the conversation, not its first prompt (port of Blaze
 * Desktop titles.go). After the first full exchange, and again when the chat moves on (every 4 user
 * turns, or after 2 when a long new message lands), one small background call names the chat in at
 * most 6 words and writes the one-line summary search_past_chats uses. It never blocks a turn and
 * fails silently. Chats you renamed by hand (titleSource "manual") are never retitled.
 */
object Titles {
    const val AUTO = "auto"
    const val MANUAL = "manual"
    private const val RETITLE_EVERY = 4

    private val inflight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Whether this point in the chat is worth a title call. [userTurns] counts the user's messages. */
    fun needs(source: String, lastTurns: Int, userTurns: Int, lastUser: String, endsWithAnswer: Boolean): Boolean {
        if (!Settings.autoTitles || source == MANUAL || !endsWithAnswer || userTurns == 0) return false
        if (source != AUTO) return true
        val since = userTurns - lastTurns
        return since >= RETITLE_EVERY || (since >= 2 && lastUser.length > 600)
    }

    data class Result(val title: String, val summary: String)

    /**
     * Asks the background lane for a title and summary. [lines] is the conversation as (isUser, text),
     * oldest first. Returns null on any failure or when only one call per chat may run at a time.
     */
    suspend fun ask(chatId: String, current: String, lines: List<Pair<Boolean, String>>): Result? {
        if (!inflight.add(chatId)) return null
        try {
            val picked = ArrayList<String>(); var size = 0
            for ((isUser, raw) in lines.asReversed()) {
                if (picked.size >= 12) break
                var t = raw.split(Regex("\\s+")).joinToString(" ").trim(); if (t.isEmpty()) continue
                if (t.length > 400) t = t.take(400) + "…"
                val l = (if (isUser) "User: " else "Assistant: ") + t
                if (size + l.length > 3200 && picked.isNotEmpty()) break
                size += l.length; picked.add(0, l)
            }
            if (picked.isEmpty()) return null
            val sys = "You name chat conversations for a list of chats. Reply with exactly two lines and nothing else.\n" +
                "Line 1: a title of 2 to 6 words, in Title Case, naming the dominant theme of the whole conversation (what most of it is about), not just the first message. " +
                "No quotes, no emoji, no trailing punctuation, no words like Chat or Conversation. If the current title still fits the dominant theme, repeat it unchanged.\n" +
                "Line 2: one line, at most 14 words, saying what this chat is about and what was decided."
            val user = "Current title: $current\n\nConversation (latest last):\n" + picked.joinToString("\n")
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", user))
            val raw = LlmClient.complete(msgs, null, null, null, LlmClient.Lane.BACKGROUND).optString("content")
            val body = raw.substringAfterLast("</think>").lines().map { it.trim() }.filter { it.isNotEmpty() }
            val title = clean(body.firstOrNull().orEmpty())
            if (title.isEmpty()) return null
            val summary = body.getOrNull(1).orEmpty().removePrefix("Line 2:").removePrefix("Summary:").trim().take(120)
            return Result(title, summary)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return null }
        finally { inflight.remove(chatId) }
    }

    /** A model reply → a list title: one line, no quotes, emoji or markdown, ≤ 6 words and 60 chars. "" when nothing usable. */
    fun clean(s0: String): String {
        var s = s0.trim()
        for (p in listOf("line 1:", "title:", "new title:", "current title:")) if (s.lowercase().startsWith(p)) s = s.substring(p.length).trim()
        val b = StringBuilder()
        for (ch in s) when {
            ch.isLetterOrDigit() || ch == ' ' -> b.append(ch)
            ch in "-&+/.,:'’#@" -> b.append(ch)
            ch.isWhitespace() -> b.append(' ')
        }
        var out = b.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(6).joinToString(" ").trim(' ', '.', ',', ':', ';', '-', '\'', '’')
        if (out.length > 60) out = out.take(60).trim()
        return if (out.equals("new chat", true)) "" else out
    }
}
