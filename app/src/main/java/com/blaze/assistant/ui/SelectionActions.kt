package com.blaze.assistant.ui

import java.net.URLEncoder

/**
 * v0.27.2: what the black text-selection pill offers for the words you selected. Pure Kotlin (SelectionActionsTest),
 * so the rules live in one place and the chat and the page sheet show the same list.
 *
 * Order: Copy, Quote, Select all, then Search (or Open link for a URL), Ask Blaze, Translate, Define (1–3 words
 * only), Read aloud, Share. Quote and Select all only show where the caller allows them.
 */
object SelectionActions {

    enum class Kind { URL, SHORT, LONG }

    enum class Action(val label: String) {
        COPY("Copy"), QUOTE("Quote"), SELECT_ALL("Select all"),
        SEARCH("Search"), OPEN_LINK("Open link"), ASK("Ask Blaze"), TRANSLATE("Translate"),
        DEFINE("Define"), READ_ALOUD("Read aloud"), SHARE("Share")
    }

    /** Longest selection we hand to a prompt or a search (the rest is cut, never the start). */
    const val MAX_PROMPT = 4000
    const val MAX_SEARCH = 300
    /** Define shows for 1 to [DEFINE_MAX_WORDS] words of at most [DEFINE_MAX_CHARS] characters. */
    const val DEFINE_MAX_WORDS = 3
    const val DEFINE_MAX_CHARS = 48

    private val scheme = Regex("^(https?)://[^\\s/$.?#][^\\s]*$", RegexOption.IGNORE_CASE)
    private val bareDomain = Regex("^(www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,24}(:\\d{1,5})?([/?#][^\\s]*)?$", RegexOption.IGNORE_CASE)
    /** File-like words ("notes.txt", "main.kt") are not links. */
    private val fileExt = setOf("txt", "md", "kt", "kts", "java", "py", "js", "ts", "json", "png", "jpg", "jpeg", "gif", "pdf",
        "doc", "docx", "csv", "html", "xml", "zip", "apk", "mp3", "mp4", "sh", "yml", "yaml", "log", "exe")
    private val trimEnds = charArrayOf(' ', '\n', '\t', '\r', '"', '\'', '\u201c', '\u201d', '\u2018', '\u2019', '<', '>', '(', ')', '[', ']')

    /** The selection without surrounding quotes / brackets / whitespace, and a trailing sentence dot or comma. */
    fun clean(text: String): String = text.trim(*trimEnds).trimEnd('.', ',', ';', ':', '!', '?').trim(*trimEnds)

    /** The link if the whole selection is one URL (with or without https://), else null. */
    fun url(text: String): String? {
        val s = clean(text)
        if (s.isEmpty() || s.any { it.isWhitespace() } || s.length > 2000) return null
        if (scheme.matches(s)) return s
        if (s.contains('@')) return null
        if (!bareDomain.matches(s)) return null
        val host = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        val tld = host.substringAfterLast('.').lowercase()
        if (tld in fileExt && !s.startsWith("www.", ignoreCase = true) && !s.contains('/')) return null
        return "https://$s"
    }

    fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    fun kind(text: String): Kind {
        if (url(text) != null) return Kind.URL
        val s = clean(text)
        val w = words(s)
        return if (w.size in 1..DEFINE_MAX_WORDS && s.length <= DEFINE_MAX_CHARS && w.all { word -> word.any { it.isLetter() } }) Kind.SHORT
        else Kind.LONG
    }

    /**
     * The pill for [text]. [allowQuote] / [allowSelectAll] follow the screen (the page sheet has no composer to quote
     * into; Select all only when the toolbar was given one). An empty selection gets only what still makes sense.
     */
    fun forText(text: String, allowQuote: Boolean = true, allowSelectAll: Boolean = true): List<Action> = buildList {
        add(Action.COPY)
        if (allowQuote) add(Action.QUOTE)
        if (allowSelectAll) add(Action.SELECT_ALL)
        if (text.isBlank()) return@buildList
        val k = kind(text)
        add(if (k == Kind.URL) Action.OPEN_LINK else Action.SEARCH)
        add(Action.ASK)
        if (k != Kind.URL) add(Action.TRANSLATE)
        if (k == Kind.SHORT) add(Action.DEFINE)
        if (k != Kind.URL) add(Action.READ_ALOUD)
        add(Action.SHARE)
    }

    /**
     * The pill before we know the words. Compose's selection toolbar never hands the selected text over (only a
     * "copy" callback that also ends the selection), so the list is picked from the selection's box instead:
     * Define shows when the box looks like a few words on one line ([likelyShort]). The exact action is settled on
     * tap with [resolve] (Search on a link opens the link; Define on a long selection explains it).
     */
    fun forUnknown(likelyShort: Boolean, allowQuote: Boolean = true, allowSelectAll: Boolean = true): List<Action> = buildList {
        add(Action.COPY)
        if (allowQuote) add(Action.QUOTE)
        if (allowSelectAll) add(Action.SELECT_ALL)
        add(Action.SEARCH); add(Action.ASK); add(Action.TRANSLATE)
        if (likelyShort) add(Action.DEFINE)
        add(Action.READ_ALOUD); add(Action.SHARE)
    }

    /** A selection box of one line and at most ~3 words wide (in dp). */
    fun likelyShort(widthDp: Float, heightDp: Float): Boolean = heightDp in 0f..34f && widthDp in 0f..200f

    /** Search on a URL opens it; Define on a long selection becomes Ask (explain); everything else is itself. */
    fun resolve(action: Action, text: String): Action = when {
        action == Action.SEARCH && kind(text) == Kind.URL -> Action.OPEN_LINK
        action == Action.DEFINE && kind(text) != Kind.SHORT -> Action.ASK
        else -> action
    }

    /** Where Search / Open link goes in Blaze's browser: the link itself, or a Google search for the words. */
    fun browserUrl(text: String): String {
        url(text)?.let { return it }
        val q = text.trim().replace(Regex("\\s+"), " ").take(MAX_SEARCH)
        return "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
    }

    private fun cap(text: String): String = text.trim().let { if (it.length > MAX_PROMPT) it.take(MAX_PROMPT) + "\u2026" else it }

    fun askPrompt(text: String): String = "Explain: " + cap(text)

    fun translatePrompt(text: String, language: String): String =
        "Translate into ${language.ifBlank { "English" }} (just the translation; if it is already in ${language.ifBlank { "English" }}, say so and give a plain rewording):\n\n" + cap(text)

    fun definePrompt(text: String): String = "Define \u201c" + clean(text) + "\u201d: the meaning in plain words, how it is pronounced, and one example sentence."

    /** Text for Read aloud: no more than the phone's TTS takes in one go. */
    fun speakable(text: String): String = text.trim().take(3900)

    // ---- the first-time nudge ----

    const val NUDGE_KEY = "selection_pill_nudged"

    /** Peek the hidden actions once: only if there are any, the user has never seen it, and motion is on. */
    fun shouldNudge(alreadyNudged: Boolean, motionOn: Boolean, hasMore: Boolean): Boolean = !alreadyNudged && motionOn && hasMore
}
