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

    /**
     * The chip text to show ("" = nothing). 0.45.19 chat fixes: never the ack label ("ack"), never the action receipt
     * chips ("✓ Phone status", "⚠ Couldn't confirm …", "✗ Phone act didn't work", "+1 more": "What I did" holds them), and
     * never a model, provider or route ([ModelHide]) unless the developer flag [dev] is on ([DevFlags.modelInfo]).
     * Timings / data stay behind Show technical details ([showTech]).
     */
    fun visible(meta: String, showTech: Boolean, dev: Boolean = false): String {
        if (meta.isBlank()) return ""
        if (dev) return meta
        if (meta.trim() == Ack.META) return ""
        if (meta.startsWith("usedup:")) return ""
        // "Heads-up · 14:02" is one human chip even though it has a dot.
        if (meta.startsWith("Heads-up")) return meta
        return meta.split(" \u00b7 ").map { it.trim() }.filter { p ->
            p.isNotEmpty() && p != Ack.META && !Receipt.isChip(p) && !ModelHide.mentions(p) && !isRouteWord(p) && (showTech || !isTechnical(p))
        }.joinToString(" \u00b7 ")
    }

    /** Route / lane words that only make sense with a model next to them ("Blaze Cloud", "cloud run", "fallback"). */
    private fun isRouteWord(p: String): Boolean =
        Regex("^run$|\\b(blaze cloud|cloud run|via |model|provider|fallback|lane|keyless|free \\(|tier)\\b|^[\u2601]|[\\w.-]+/[\\w.:-]+|\\)$", RegexOption.IGNORE_CASE).containsMatchIn(p)

    /**
     * v0.26.2: the old "Fresh start. Earlier turns are out of my context…" / "Fresh start. Here's where we were" bubble.
     * New context windows are silent now; saved chats that still have one don't show it either. Pure.
     */
    fun isFreshStart(text: String, meta: String): Boolean =
        text.startsWith("Fresh start. ") && (meta.contains("New context") || text.startsWith("Fresh start. Earlier turns are out of my context") || text.startsWith("Fresh start. Here's where we were"))

    fun showTech(): Boolean = try { Settings.bool(PREF, false) } catch (_: Throwable) { false }

    /** The chip for [meta] with this phone's settings (Show technical details, the developer flag). */
    fun shown(meta: String): String = visible(meta, showTech(), DevFlags.modelInfo())
}
