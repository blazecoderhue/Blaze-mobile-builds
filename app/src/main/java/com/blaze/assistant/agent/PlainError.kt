package com.blaze.assistant.agent

/**
 * Turns raw provider/system errors (JSON blobs, stack messages, URLs) into one or two plain
 * sentences for the chat. The raw text goes to the crash log for debugging.
 */
object PlainError {
    private val codeRe = Regex("\\((\\d{3})\\)")

    /** After automatic compaction and a retry, the request still didn't fit (v0.26.0). */
    const val CONTEXT = "That was too much for this model even after I condensed the older messages. Tap Retry, or send a shorter message (attachments and long pasted text count too)."

    fun of(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "Something went wrong. Tap Retry."
        Cloud.access(403, t)?.let { line -> if ("403" in t || line == t || "waitlisted" in t || "access_rejected" in t) return line }
        val looksRaw = t.contains('{') || t.contains("\"error\"") || t.contains("http", true) || t.length > 220 || t.contains("Exception")
        if (!looksRaw) return t
        try { com.blaze.assistant.device.CrashLog.error("chat error", RuntimeException(t.take(1500))) } catch (_: Throwable) { }
        val who = t.substringBefore(" said (", "").substringBefore(" \u00b7 ").trim().takeIf { it.isNotBlank() && it.length < 40 } ?: "The AI"
        val code = codeRe.find(t)?.groupValues?.get(1)?.toIntOrNull()
        val low = t.lowercase()
        return when {
            code == 429 || "quota" in low || "rate limit" in low || "resource_exhausted" in low ->
                Cloud.BUSY
            code == 401 || code == 403 || "api key" in low && "invalid" in low -> Cloud.EXPIRED
            // v0.25.0: never "that model is gone, open Settings": Blaze Cloud already retried other models quietly.
            code == 404 || "not found" in low -> Cloud.BUSY
            code != null && code in 500..599 || "overloaded" in low || "unavailable" in low -> "$who is having trouble on its side right now. Tap Retry in a moment."
            "timeout" in low || "timed out" in low -> "$who took too long to answer. Tap Retry."
            "unable to resolve host" in low || "failed to connect" in low || "network" in low -> "I can't reach the internet right now. Check your data or Wi-Fi, then tap Retry."
            // v0.26.0: a too-long chat is compacted and retried automatically (AutoCompact); this line only shows when even
            // the compacted request didn't fit, and never asks for a new chat.
            AutoCompact.isContextError(low) -> CONTEXT
            else -> "$who couldn't finish that one. Tap Retry."
        }
    }
}
