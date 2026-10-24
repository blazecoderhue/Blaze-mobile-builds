package com.blaze.assistant.agent

/** Built-in AI services. All speak the OpenAI chat-completions format at [baseUrl]. */
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyHint: String,
    val keyPage: String,
    val quotaPage: String,
)

object Providers {
    const val CUSTOM = "custom"
    const val AUTO = "auto"
    /** v0.24.6: Blaze Gateway, one endpoint holding Blaze's own free-tier keys; models are managed on its dashboard. */
    const val GATEWAY = "gateway"
    /** v0.25.0: the gateway's own name (the old blaze-gateway.blaze-app.workers.dev address still answers too). */
    const val GATEWAY_BASE = "https://ai.blaze-app.workers.dev/v1/"
    /**
     * 0.45.23: the gateway Blaze talks to. Always [GATEWAY_BASE] in a release build. Only a DEBUG build made for the
     * emulator clean-install CI job (-Pblaze.testBase=http://10.0.2.2:8787) points at that job's local mock gateway, so no
     * test ever reaches Blaze Cloud or a paid model.
     */
    val gatewayBase: String get() = testBase()?.let { "$it/v1/" } ?: GATEWAY_BASE
    fun testBase(): String? = com.blaze.assistant.BuildConfig.TEST_BASE.trim().trimEnd('/')
        .takeIf { com.blaze.assistant.BuildConfig.DEBUG && it.isNotEmpty() }
    /** Keyless public endpoint used as the very last resort (chat only, no tools). */
    val keyless = Provider("pollinations", "Pollinations (keyless)", "https://text.pollinations.ai/openai/",
        "openai-fast", "", "", "")

    val all = listOf(
        Provider(AUTO, "Auto (free routing)", "", "", "", "", ""),
        // Runs on this phone (agent/BlazeRouter.kt); its key is set when it starts.
        Provider("blazerouter", "BlazeRouter (on this phone)", "http://127.0.0.1:20128/v1/", "auto/best-free", "set automatically", "", ""),
        // Signs in with your Blaze account (no key to paste); until you sign in, Auto (free) answers instead.
        Provider(GATEWAY, "Blaze Gateway", gatewayBase, "blaze-auto", "your Blaze account", "", ""),
        Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/",
            "gemini-3.8-flash", "AIza…", "https://aistudio.google.com/apikey",
            "https://aistudio.google.com/rate-limit?timeRange=last-28-days"),
        Provider("openai", "ChatGPT (OpenAI)", "https://api.openai.com/v1/",
            "", "sk-…", "https://platform.openai.com/api-keys", "https://platform.openai.com/settings/organization/limits"),
        Provider("anthropic", "Claude (Anthropic)", "https://api.anthropic.com/v1/",
            "", "sk-ant-…", "https://console.anthropic.com/settings/keys", "https://console.anthropic.com/settings/limits"),
        Provider("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1/",
            "", "nvapi-…", "https://build.nvidia.com/settings/api-keys", "https://build.nvidia.com/"),
        Provider("moonshot", "Kimi (Moonshot)", "https://api.moonshot.ai/v1/",
            "", "sk-…", "https://platform.moonshot.ai/console/api-keys", "https://platform.moonshot.ai/console/limits"),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1/",
            "deepseek-chat", "sk-…", "https://platform.deepseek.com/api_keys", "https://platform.deepseek.com/usage"),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1/",
            "", "gsk_…", "https://console.groq.com/keys", "https://console.groq.com/settings/limits"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/",
            "", "sk-or-…", "https://openrouter.ai/settings/keys", "https://openrouter.ai/settings/credits"),
        Provider("mistral", "Mistral", "https://api.mistral.ai/v1/",
            "", "…", "https://console.mistral.ai/api-keys", "https://admin.mistral.ai/plateforme/limits"),
        Provider("cerebras", "Cerebras", "https://api.cerebras.ai/v1/",
            "", "csk-…", "https://cloud.cerebras.ai/platform", "https://cloud.cerebras.ai/platform"),
        Provider("github", "GitHub Models (retired Jul 2026)", "https://models.github.ai/inference/",
            "", "github_pat_…", "https://github.com/settings/personal-access-tokens/new", "https://github.com/marketplace/models"),
        Provider("xai", "Grok (xAI)", "https://api.x.ai/v1/",
            "", "xai-…", "https://console.x.ai/", "https://console.x.ai/"),
        Provider(CUSTOM, "Custom (OpenAI-compatible)", "", "", "Your key", "", ""),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: if (id == keyless.id) keyless else all[1]

    /** Providers with a real free tier, in the default fill-first order for Auto. */
    val freeOrder = listOf("gemini", "groq", "cerebras", "nvidia", "openrouter", "mistral", "deepseek", "moonshot")

    /** Free-tier model Auto uses for a provider when the user hasn't picked one. A 404 cools it for 30 min. */
    val freeDefault = mapOf(
        "groq" to "llama-3.3-70b-versatile",
        "cerebras" to "gpt-oss-120b",
        "nvidia" to "meta/llama-3.3-70b-instruct",
        "mistral" to "mistral-small-latest",
    )

    /** The key Blaze would use for [pid]. (GitHub Models was retired on 30 Jul 2026, so no GitHub fallback.) */
    fun keyOf(pid: String): String = KeyRing.active(pid, Settings.modelFor(pid))
}
