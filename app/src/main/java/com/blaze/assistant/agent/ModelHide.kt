package com.blaze.assistant.agent

/**
 * 0.45.19 chat fixes (user rule for every client): never show which model or provider replied. Blaze is Blaze and Halo is
 * Halo. No model or provider ids in chat footers, the "What happened" sheet, settings, errors or fallback notices.
 * The details stay available to developers only behind [DevFlags.modelInfo] (off by default, debug builds only).
 *
 * Pure parts ([mentions], [scrub]) are tested by HideModels19Test; UiProviderNamesTest greps the UI-facing sources.
 */
object ModelHide {
    /** Provider and model-family words that must never reach the user (lower case, matched as words or id parts). */
    val NAMES = listOf(
        "mistral", "ministral", "mixtral", "pixtral", "codestral", "groq", "cerebras", "sambanova", "cohere", "command-r", "gemini", "gemma",
        "openrouter", "nvidia", "nemotron", "z.ai", "zhipu", "glm", "kilo-", "kilocode", "llama", "qwen", "deepseek", "openai", "gpt",
        "claude", "anthropic", "grok", "kimi", "moonshot", "pollinations", "workers-ai",
    )

    private val nameAlt = NAMES.joinToString("|") { Regex.escape(it) }
    /** A word that is a provider / model name, or an id containing one ("llama-3.3-70b", "auto/best-free" is not). */
    private val word = Regex("(?<![\\w.])[\\w.:-]*(?:$nameAlt)[\\w.:-]*(?![\\w])", RegexOption.IGNORE_CASE)
    private val id = Regex("(?<![\\w.])[\\w.-]+/[\\w.:-]+(?![\\w])")
    private val paren = Regex("\\s*\\(([^()]*)\\)")

    /** True when [text] names a provider or model. Pure. */
    fun mentions(text: String): Boolean = word.containsMatchIn(text)

    /**
     * [text] with provider / model names taken out: "(Groq · llama-3.3-70b)" and "openrouter/free" go, a bare name left in a
     * sentence becomes "Blaze" ("Groq is having trouble" -> "Blaze is having trouble"). Text naming none comes back unchanged.
     */
    fun scrub(text: String): String {
        if (!mentions(text)) return text
        var t = paren.replace(text) { m -> if (mentions(m.groupValues[1])) "" else m.value }
        t = id.replace(t) { m -> if (mentions(m.value)) "" else m.value }
        t = word.replace(t, "Blaze")
        t = t.replace(Regex("\\bBlaze(\\s*[\u00b7,/-]?\\s*Blaze)+\\b"), "Blaze")
            .replace(Regex("(\\s*\u00b7\\s*)+$"), "").replace(Regex("^(\\s*\u00b7\\s*)+"), "")
            .replace(Regex("\\s*\u00b7\\s*(\u00b7\\s*)+"), " \u00b7 ")
            .replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\\s+([.,!?])"), "$1")
        return t.trim()
    }
}

/** Developer-only switches. Every one is off by default and only a debug build can turn it on. */
object DevFlags {
    /** Show the model / provider / route in reply footers and the "What happened" sheet. */
    const val MODEL_INFO = "dev_model_info"

    /** Pure rule: [debugBuild] and the developer turned [MODEL_INFO] on. */
    fun modelInfo(debugBuild: Boolean, pref: Boolean): Boolean = debugBuild && pref

    fun modelInfo(): Boolean = try {
        modelInfo(com.blaze.assistant.BuildConfig.DEBUG, Settings.bool(MODEL_INFO, false))
    } catch (_: Throwable) { false }
}
