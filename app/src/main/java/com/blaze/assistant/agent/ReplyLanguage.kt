package com.blaze.assistant.agent

/**
 * v0.45.7 Settings › Language: which language Blaze replies in.
 *
 * Auto (the default) matches the user's latest message. A picked language adds one stable line to the SECOND system
 * message (the app context); the shared persona (first message) stays byte-identical. The line only changes when the
 * user changes the setting, so the provider's prompt cache keeps working.
 */
object ReplyLanguage {
    const val PREF = "reply_language"

    /** [code] stored in prefs, [label] shown in Settings (in its own language), [english] used in the prompt line. */
    data class Option(val code: String, val label: String, val english: String, val systemFont: Boolean = false)

    val OPTIONS = listOf(
        Option("auto", "Auto (match me)", ""),
        Option("en", "English", "English"),
        Option("sw", "Kiswahili", "Swahili (Kiswahili)"),
        Option("bem", "ChiBemba", "Bemba (ChiBemba)"),
        Option("ny", "ChiNyanja", "Nyanja / Chichewa (ChiNyanja)", systemFont = true),
        Option("ha", "Hausa", "Hausa", systemFont = true),
        Option("yo", "Yorùbá", "Yoruba (Yorùbá)", systemFont = true),
        Option("am", "አማርኛ", "Amharic (አማርኛ)", systemFont = true),
        Option("fr", "Français", "French"),
        Option("pt", "Português", "Portuguese"),
        Option("ar", "العربية", "Arabic", systemFont = true),
    )

    fun option(code: String?): Option = OPTIONS.firstOrNull { it.code == code } ?: OPTIONS[0]

    /** v0.45.8 (Site): the request header for the pick, `X-Blaze-Reply-Lang: <BCP-47>`; null for Auto (header omitted). */
    const val HEADER = "X-Blaze-Reply-Lang"
    fun header(code: String?): Pair<String, String>? = option(code).code.takeIf { it != "auto" }?.let { HEADER to it }

    /** The saved choice ("auto" when unset or unreadable). */
    fun current(): String = try { option(Settings.str(PREF, "auto")).code } catch (_: Throwable) { "auto" }

    fun set(code: String) { Settings.setStr(PREF, option(code).code) }

    /**
     * The prompt line for [code]. Auto: answer in the language of the user's latest message, with its own spelling,
     * tone marks and script. A picked language: always reply in it.
     */
    fun line(code: String): String {
        val o = option(code)
        return if (o.code == "auto")
            "\nLANGUAGE: reply in the language of the user's latest message (English, Kiswahili, ChiBemba, ChiNyanja/Chichewa, Hausa, Yorùbá, Amharic, French, Portuguese, Arabic or any other), " +
                "with that language's own spelling, tone marks and script (Hausa ɓ ɗ ƙ ƴ, Yorùbá ẹ ọ ṣ with tone accents, Amharic in Ge'ez script). If you can't tell, use English. "
        else
            "\nLANGUAGE: the user chose ${o.english} in Settings. Always reply in ${o.english}, with its own spelling, tone marks and script, even when they write in another language. " +
                "Keep names, code, commands, numbers and quoted text as they are. "
    }

    /** True when the chosen language needs letters the bundled Inter font lacks: the whole UI then uses the system font. */
    fun systemFont(code: String): Boolean = option(code).systemFont
}
