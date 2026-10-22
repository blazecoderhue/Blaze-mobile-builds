package com.blaze.assistant.agent

/**
 * v0.24.4: the line under a reply shows only what matters to a person ("Memory updated", "Skill saved",
 * "Heads-up · 14:02"). Technical parts (timings, "instant/on-device", data used, token meters, model ids,
 * "New context") are hidden unless Settings › Advanced › Show technical details (`show_tech_lines`) is on.
 * The stored meta is untouched, so turning it on shows everything again.
 */
object ReplyChips {
    const val PREF = "show_tech_lines"

    private val tech = listOf(
        Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:ms|s|sec|KB|MB|GB|kB|tok|tokens)\\b", RegexOption.IGNORE_CASE),
        Regex("^[\\u26a1\\uD83C\\uDFA8]"),                                  // ⚡ instant / on-device, 🎨 free image
        Regex("\\b(instant|on-device|new context|fresh start|via |model|provider|fallback|retry \\d|lane)\\b", RegexOption.IGNORE_CASE),
        Regex("[\\w.-]+/[\\w.:-]+"),                                          // provider/model ids
        Regex("^usedup:"),
        // v0.25.0: the route line ("\u2601 Free (Google Gemini \u00b7 gemini-3\u2026) \u00b7 2 steps") never shows by default.
        Regex("^[\u2601]"),
        Regex("\\b(blaze cloud|free \\(|gemini|gemma|gpt|llama|qwen|mistral|deepseek|kimi|glm|claude|grok|nemotron|groq|cerebras|openrouter|nvidia|pollinations|keyless|workers-ai|healed|trimmed|tier)", RegexOption.IGNORE_CASE),
        Regex("^\\d+ steps$"),
        Regex("^[^(]*\\)$"),                                                // the tail of a "(provider \u00b7 model)" pair
    )

    /** A reply's model / route line (starts with the cloud mark, or names a provider in brackets). */
    fun isRoute(meta: String): Boolean {
        val t = meta.trim()
        return t.startsWith("\u2601") || t.startsWith("Free (") || t.startsWith("Blaze Cloud") || t.contains("Free (daily cap)")
    }

    fun isTechnical(part: String): Boolean = tech.any { it.containsMatchIn(part.trim()) }

    /** The chip text to show ("" = nothing). */
    fun visible(meta: String, showTech: Boolean): String {
        if (meta.isBlank() || showTech) return meta
        // "Heads-up · 14:02" is one human chip even though it has a dot.
        if (meta.startsWith("Heads-up")) return meta
        // v0.45.0: receipt chips ("✓ Sent to Mum", "⚠ Couldn't confirm: …") always show.
        return meta.split(" \u00b7 ").map { it.trim() }.filter { it.isNotEmpty() && (Receipt.isChip(it) || !isTechnical(it)) }.joinToString(" \u00b7 ")
    }

    /**
     * v0.26.2: the old "Fresh start. Earlier turns are out of my context…" / "Fresh start. Here's where we were" bubble.
     * New context windows are silent now; saved chats that still have one don't show it either. Pure.
     */
    fun isFreshStart(text: String, meta: String): Boolean =
        text.startsWith("Fresh start. ") && (meta.contains("New context") || text.startsWith("Fresh start. Earlier turns are out of my context") || text.startsWith("Fresh start. Here's where we were"))

    fun showTech(): Boolean = try { Settings.bool(PREF, false) } catch (_: Throwable) { false }
}
