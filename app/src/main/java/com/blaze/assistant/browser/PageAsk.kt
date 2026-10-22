package com.blaze.assistant.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * The scoped page chat: what "Ask Blaze about this page" tells the model (pure, tested in PageAskTest).
 *
 * v0.27.1 (Joey: a pasted Google Lens link got "The page text you provided does not include the HTML
 * markup of the `<body>` element…"). The page now reaches the model as what a person sees (title, the readable text,
 * button and link labels, pictures) instead of the agent's element dump, and the rules forbid talking about markup or
 * blaming the user: thin pages, picture pages, sign-in walls and very long pages each get a plain note, and web
 * results are added when the screen alone can't answer.
 */
object PageAsk {
    /** What Blaze could read from the open page. */
    data class Capture(val title: String, val url: String, val text: String, val labels: List<String> = emptyList(),
                       val images: Int = 0, val alts: List<String> = emptyList(), val password: Boolean = false) {
        companion object { val NONE = Capture("", "", "") }
    }

    const val PAGE_BUDGET = 9000

    const val SYSTEM = "You are Blaze, helping the user with the web page they have open in Blaze's browser. Below is what Blaze " +
        "could see on their screen: the page title and address, the words on the page, the labels of its buttons and links, and " +
        "its pictures. Rules:\n" +
        "- Always give a useful answer, and start with it. Plain words, the way a person would describe the page: what it shows, " +
        "says and offers.\n" +
        "- Never mention HTML, markup, tags, code, the DOM, <body> or any element, \"page text\", \"the text you provided\", " +
        "\"the content provided\" or how the page was read. Never say the user gave you too little or did something wrong.\n" +
        "- If only a little of the page could be read (a picture or Google Lens result, a page made of images, a page still " +
        "loading), say in plain words what is visible, use the web results when there are any, and add what you know, marked " +
        "as your own knowledge. Only as a last resort end with one short tip, such as scrolling to the results and asking again.\n" +
        "- If the page asks the user to sign in, say so in one line, answer from what is visible and what you know, and never " +
        "ask for or repeat passwords or codes.\n" +
        "- When the answer isn't on the page, say so in one line and give what you know, clearly marked.\n" +
        "- Be brief: short paragraphs or a few bullets, no headings."

    /** The language rule: the user's own words decide; a link, a chip or an empty question falls back to the phone's language. */
    fun languageRule(phoneLanguage: String): String =
        "- Answer in the language the user writes in. If their message has no words of its own (a link, a button, nothing), " +
            "answer in $phoneLanguage. If the page is in another language, still answer in that language and translate what you quote."

    fun system(phoneLanguage: String = "English"): String = SYSTEM + "\n" + languageRule(phoneLanguage)

    private val signIn = Regex("(?i)\\b(sign ?in|log ?in|signin|login|create (an )?account|continue with (google|apple|facebook|email)|enter your password|" +
        "anmelden|connexion|iniciar sesi[oó]n|entrar)\\b")

    /** Words that could be read from the page body. */
    fun words(c: Capture): Int = c.text.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }

    /** Little readable text: a Lens / image result, a page still loading, a page drawn on a canvas. */
    fun thin(c: Capture): Boolean = words(c) < 40

    fun mostlyPictures(c: Capture): Boolean = c.images >= 4 && words(c) < 150 + c.images * 5

    /** A sign-in wall: a password box, or a short page saying "sign in" with an email / phone / username box. */
    fun signInWall(c: Capture): Boolean = c.password || (words(c) < 150 && signIn.containsMatchIn(c.text + " " + c.labels.joinToString(" ")) &&
        c.labels.any { it.startsWith("Field: ") && Regex("(?i)e-?mail|phone|user|login|account").containsMatchIn(it) })

    /** Long pages keep the start (most of the budget) and the end, cut at a line break, with a plain marker between. */
    fun trimText(text: String, budget: Int): String {
        val t = text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (t.length <= budget) return t
        val headLen = (budget * 0.8).toInt()
        val tailLen = budget - headLen - 60
        var head = t.take(headLen)
        head.lastIndexOf('\n').takeIf { it > headLen / 2 }?.let { head = head.take(it) }
        var tail = t.takeLast(tailLen)
        tail.indexOf('\n').takeIf { it in 0 until tailLen / 2 }?.let { tail = tail.substring(it + 1) }
        return head.trimEnd() + "\n\n[\u2026 a long middle part of the page is skipped here \u2026]\n\n" + tail.trimStart()
    }

    /** The page as the model sees it: no indices, no element kinds, no raw element dump. */
    fun pageBlock(c: Capture, budget: Int = PAGE_BUDGET): String {
        if (c == Capture.NONE || (c.url.isBlank() && c.text.isBlank() && c.title.isBlank())) return "Blaze couldn't read this page at all (it may still be loading)."
        val sb = StringBuilder()
        if (c.title.isNotBlank()) sb.append("Title: ").append(c.title.trim().take(200)).append('\n')
        if (c.url.isNotBlank()) {
            val q = PageQuestion.searchQuery(c.url)
            sb.append("Address: ").append(PageQuestion.shortAddress(c.url))
            when {
                PageQuestion.isLens(c.url) -> sb.append(" (a Google Lens / search-by-picture result").append(q?.let { " for \u201c$it\u201d" } ?: "").append(')')
                q != null -> sb.append(" (search results for \u201c").append(q).append("\u201d)")
            }
            sb.append('\n')
        }
        val labels = c.labels.map { it.replace(Regex("\\s+"), " ").trim().take(60) }.filter { it.length > 1 }.distinct().take(60)
        val alts = c.alts.map { it.replace(Regex("\\s+"), " ").trim().take(100) }.filter { it.length > 2 }.distinct().take(25)
        val textBudget = (budget - sb.length - labels.sumOf { it.length + 3 } - alts.sumOf { it.length + 2 } - 400).coerceAtLeast(1500)
        val text = trimText(c.text, textBudget)
        sb.append("\nWords on the page:\n").append(text.ifBlank { "(none could be read)" }).append('\n')
        if (labels.isNotEmpty()) sb.append("\nButtons and links: ").append(labels.joinToString(" \u00b7 ")).append('\n')
        if (c.images > 0 || alts.isNotEmpty()) {
            sb.append("\nPictures: ").append(if (c.images > 0) "${c.images} on the page" else "some")
            if (alts.isNotEmpty()) sb.append("; described as: ").append(alts.joinToString("; "))
            sb.append('\n')
        }
        notes(c).takeIf { it.isNotEmpty() }?.let { sb.append("\nNote for Blaze: ").append(it.joinToString(" ")).append('\n') }
        return sb.toString().take(budget + 200)
    }

    fun notes(c: Capture): List<String> {
        val out = ArrayList<String>()
        if (thin(c)) out += "Only a little of this page could be read (often a picture result or a page still loading); describe what is visible and use the web results and your own knowledge."
        if (mostlyPictures(c)) out += "This page is mostly pictures; use their descriptions and the title."
        if (signInWall(c)) out += "The page is asking the user to sign in, so what is behind it can't be seen."
        if (c.text.length > PAGE_BUDGET) out += "This is a long page; only its start and end are included."
        return out
    }

    /** Words worth a web search for this question, or null: a pasted search link, or a thin results page's own query. */
    fun searchFor(q: PageQuestion.Intent, c: Capture): String? {
        q.search?.let { return it }
        if (q.kind == PageQuestion.Kind.LINK) return null
        val onSearchPage = c.url.isNotBlank() && (PageQuestion.isSearchPage(c.url) || PageQuestion.isLens(c.url))
        if (!(thin(c) || (onSearchPage && q.kind == PageQuestion.Kind.LENS_LINK))) return null
        return PageQuestion.searchQuery(c.url) ?: PageQuestion.queryFromTitle(c.title)
    }

    /**
     * The request: system rules, the page, any web results, then the conversation. [history] is (fromUser, text) with the
     * user's turns already rewritten into real requests ([PageQuestion.Intent.prompt]).
     */
    fun messages(page: Capture, history: List<Pair<Boolean, String>>, web: String? = null, phoneLanguage: String = "English"): JSONArray {
        val sys = StringBuilder(system(phoneLanguage)).append("\n\nPAGE ON THE USER'S SCREEN:\n").append(pageBlock(page))
        web?.takeIf { it.isNotBlank() }?.let { sys.append("\n\n").append(it.take(5000)) }
        val a = JSONArray().put(JSONObject().put("role", "system").put("content", sys.toString()))
        history.forEach { (me, t) -> a.put(JSONObject().put("role", if (me) "user" else "assistant").put("content", t)) }
        return a
    }

    val chips = listOf(PageQuestion.SUMMARISE, "Explain it simply", "What are the key points?")
}
