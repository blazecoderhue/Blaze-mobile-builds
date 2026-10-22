package com.blaze.assistant.browser

import java.net.URLDecoder

/**
 * v0.27.1: what the user actually means in "Ask Blaze about this page" (pure, tested in PageQuestionTest).
 *
 * People paste things that aren't questions: a giant Google / Lens results link, nothing at all, one word. Each of
 * those is turned into a real request for the model ([Intent.prompt]) and a compact bubble for the chat
 * ([Intent.shown] plus a link chip), so the user always gets an answer and never sees a 2,000-character URL echoed.
 */
object PageQuestion {
    enum class Kind { ASK, EMPTY, WORD, GREETING, SEARCH_LINK, LENS_LINK, THIS_PAGE_LINK, LINK }

    /**
     * @param shown what the user's bubble says (never a long raw URL)
     * @param prompt what the model is asked
     * @param link the pasted link, shown as a compact chip ([chip]) instead of text
     * @param search words to run through web search for this question (a pasted search link's query)
     * @param fetch a pasted ordinary link to read
     */
    data class Intent(val kind: Kind, val shown: String, val prompt: String, val link: String? = null,
                      val search: String? = null, val fetch: String? = null)

    const val SUMMARISE = "Summarise this page"

    private val urlRx = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"']+)""")
    private val greetings = setOf("hi", "hey", "hello", "hallo", "hiya", "yo", "sup", "thanks", "thank", "thx", "ty", "ok", "okay", "cool", "nice")
    private val wordCommands = mapOf(
        "summary" to SUMMARISE, "summarise" to SUMMARISE, "summarize" to SUMMARISE, "tldr" to SUMMARISE, "tl;dr" to SUMMARISE,
        "summarise this" to SUMMARISE, "summarize this" to SUMMARISE,
        "explain" to "Explain this page simply", "simplify" to "Explain this page simply", "eli5" to "Explain this page simply",
        "translate" to "Translate the main text of this page into my language",
        "keypoints" to "What are the key points?", "points" to "What are the key points?",
    )

    /** Search engines: host part → the query parameters to try, in order. */
    private val engines = listOf(
        "google." to listOf("q", "oq", "as_q"), "bing.com" to listOf("q"), "duckduckgo.com" to listOf("q"),
        "search.yahoo." to listOf("p", "q"), "yandex." to listOf("text"), "baidu.com" to listOf("wd", "word"),
        "ecosia.org" to listOf("q"), "search.brave.com" to listOf("q"), "startpage.com" to listOf("query", "q"),
        "qwant.com" to listOf("q"), "youtube.com/results" to listOf("search_query"),
    )

    /** The one link in [text] when the message is (mostly) that link, else null. "Mostly" = at most 40 other characters. */
    fun mostlyLink(text: String): String? {
        val t = text.trim()
        val m = urlRx.findAll(t).toList()
        if (m.size != 1) return null
        val u = m[0].value.trimEnd('.', ',', ')', ']', '!', '?', ';')
        val rest = t.replace(m[0].value, "").trim()
        return if (rest.length <= 40) u else null
    }

    fun parse(raw: String, pageUrl: String = ""): Intent {
        val t = raw.trim()
        val bare = t.trim('?', '!', '.', ' ', '\n')
        if (bare.isEmpty()) return Intent(Kind.EMPTY, SUMMARISE, "Summarise this page: what it is, and the main things it says or shows.")
        mostlyLink(t)?.let { return forLink(it, t, pageUrl) }
        val lower = bare.lowercase()
        wordCommands[lower]?.let { return Intent(Kind.ASK, t, it) }
        val words = lower.split(Regex("\\s+"))
        if (words.size == 1 || (words.size <= 2 && words.all { it in greetings })) {
            if (words.all { it in greetings })
                return Intent(Kind.GREETING, t, "$t (Reply in one friendly line, then in one short line say what this page is and offer to summarise it or answer a question about it.)")
            if (!t.contains('?'))
                return Intent(Kind.WORD, t, "What does \u201c$bare\u201d mean here? If it appears on this page, say what it refers to on the page; " +
                    "if not, say what it usually means and how it relates to this page, if at all.")
        }
        return Intent(Kind.ASK, t, t)
    }

    private fun forLink(url: String, typed: String, pageUrl: String): Intent {
        val extra = typed.replace(url, "").trim().trim(':', '-', ' ')
        val ask = if (extra.isNotEmpty() && extra.trim('?', '!', '.').isNotEmpty()) " The user also wrote: \u201c$extra\u201d." else ""
        val shown = extra
        val full = if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url
        val q = searchQuery(full)
        if (isLens(full)) {
            val p = "I pasted a Google Lens link (a search by picture${if (q != null) " for \u201c$q\u201d" else ""}). Tell me what this Lens / " +
                "image search shows: what the picture seems to be and the main matches or results, from what is visible on the page" +
                (if (q != null) " and the web results" else "") + ".$ask"
            return Intent(Kind.LENS_LINK, shown, p, link = full, search = q)
        }
        if (q != null) {
            val p = "I pasted a ${engineName(full)} search link for \u201c$q\u201d. What does this search show? Answer \u201c$q\u201d directly, " +
                "using the page and the web results.$ask"
            return Intent(Kind.SEARCH_LINK, shown, p, link = full, search = q)
        }
        if (isSearchPage(full)) {
            return Intent(Kind.LENS_LINK, shown, "I pasted a ${engineName(full)} results link. Tell me what these results show, from what is visible on the page.$ask", link = full)
        }
        if (samePage(full, pageUrl)) {
            return Intent(Kind.THIS_PAGE_LINK, shown, "I pasted the link to this page. What does this page show? Give me the main points.$ask", link = full)
        }
        return Intent(Kind.LINK, shown, "I pasted a link to ${host(full).ifEmpty { "another page" }}: ${compact(full)}. " +
            "What is on it? Summarise it, and say how it relates to the page I have open if it does.$ask", link = full, fetch = full)
    }

    /** Query parameters of [url], decoded ("a+b" → "a b"). */
    fun params(url: String): Map<String, String> {
        val q = url.substringAfter('?', "").substringBefore('#')
        val frag = url.substringAfter('#', "")
        val out = LinkedHashMap<String, String>()
        (q.split('&') + if (frag.contains('=')) frag.split('&') else emptyList()).forEach { kv ->
            if (kv.isEmpty()) return@forEach
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            val dv = try { URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { v }
            if (k.isNotEmpty() && k !in out) out[k] = dv
        }
        return out
    }

    fun host(url: String): String =
        Regex("^(?i)(?:[a-z][a-z0-9+.-]*://)?(?:www\\.)?([^/:?#]+)").find(url.trim())?.groupValues?.get(1)?.lowercase().orEmpty()

    private fun hostAndPath(url: String): String = url.trim().replace(Regex("^(?i)[a-z][a-z0-9+.-]*://(www\\.)?"), "").substringBefore('?').substringBefore('#').lowercase()

    /** The search words in a search engine link, or null (not a search link, or a picture search without words). */
    fun searchQuery(url: String): String? {
        val hp = hostAndPath(url)
        val keys = engines.firstOrNull { (h, _) -> if (h.contains('/')) hp.startsWith(h) || hp.contains(h) else host(url).contains(h) }?.second ?: return null
        if (host(url).contains("google.") && !isSearchPage(url) && !host(url).startsWith("lens.")) return null
        val p = params(url)
        return keys.firstNotNullOfOrNull { k -> p[k]?.trim()?.takeIf { it.isNotEmpty() } }?.take(200)
    }

    /** A results page of a search engine (with or without words). */
    fun isSearchPage(url: String): Boolean {
        val h = host(url); val path = hostAndPath(url).substringAfter('/', "")
        return when {
            h.startsWith("lens.google") -> true
            h.contains("google.") -> path.startsWith("search") || path.startsWith("webhp") || path.startsWith("imgres")
            h.contains("bing.com") -> path.startsWith("search") || path.startsWith("images")
            h.contains("duckduckgo.com") -> params(url).containsKey("q")
            h.contains("youtube.com") -> path.startsWith("results")
            else -> engines.any { (e, _) -> h.contains(e.substringBefore('/')) }
        }
    }

    /** A Google Lens / search-by-picture link: lens.google.com, or a Google search with udm=26, lns_* or source=lns.*. */
    fun isLens(url: String): Boolean {
        val h = host(url)
        if (h.startsWith("lens.google")) return true
        if (!h.contains("google.")) return h.contains("bing.com") && hostAndPath(url).contains("images/search") && params(url)["view"] == "detailv2"
        val p = params(url)
        return p["udm"] == "26" || p.keys.any { it.startsWith("lns_") } || p["source"].orEmpty().startsWith("lns") ||
            p.containsKey("vsrid") || p["tbs"].orEmpty().startsWith("sbi:")
    }

    private fun engineName(url: String): String {
        val h = host(url)
        return when {
            h.contains("google.") -> "Google"; h.contains("bing.") -> "Bing"; h.contains("duckduckgo") -> "DuckDuckGo"
            h.contains("yahoo") -> "Yahoo"; h.contains("yandex") -> "Yandex"; h.contains("baidu") -> "Baidu"
            h.contains("ecosia") -> "Ecosia"; h.contains("brave") -> "Brave"; h.contains("youtube") -> "YouTube"
            else -> h.substringBefore('.').replaceFirstChar { it.uppercase() }
        }
    }

    /** Same page: same host and path (query and fragment ignored, so a re-pasted search link of this page counts). */
    fun samePage(a: String, b: String): Boolean = b.isNotBlank() && hostAndPath(a).trimEnd('/') == hostAndPath(b).trimEnd('/')

    /** A short, readable form of a link for prompts: host + path, cut to [max] characters. */
    fun compact(url: String, max: Int = 120): String {
        val hp = url.trim().replace(Regex("^(?i)[a-z][a-z0-9+.-]*://(www\\.)?"), "").substringBefore('#')
        return if (hp.length <= max) hp else hp.take(max - 1) + "\u2026"
    }

    /** An address for the model: host + path, the query only when it is short (search and Lens pages never), cut to 160. */
    fun shortAddress(url: String): String {
        val hp = url.trim().replace(Regex("^(?i)[a-z][a-z0-9+.-]*://(www\\.)?"), "").substringBefore('#')
        val path = hp.substringBefore('?')
        val query = hp.substringAfter('?', "")
        val keep = query.isNotEmpty() && query.length <= 40 && !isSearchPage(url) && !isLens(url)
        val out = if (keep) "$path?$query" else path
        return if (out.length <= 160) out else out.take(159) + "\u2026"
    }

    /** The label on the link chip in the user's bubble: "Google Lens", "Google search · cheap flights", "nytimes.com/…". */
    fun chip(url: String): String {
        val q = searchQuery(url)
        return when {
            isLens(url) -> "Google Lens" + (q?.let { " \u00b7 " + it.take(40) } ?: " result")
            q != null -> "${engineName(url)} search \u00b7 ${q.take(40)}"
            isSearchPage(url) -> "${engineName(url)} results"
            else -> compact(url, 42)
        }
    }

    /** The search words behind a results page title ("cheap flights - Google Search" → "cheap flights"), or null. */
    fun queryFromTitle(title: String): String? {
        val m = Regex("^(.+?)\\s+[-\u2013\u2014|]\\s+(Google Search|Google-Suche|Recherche Google|Búsqueda de Google|Bing|DuckDuckGo|Yahoo Search.*|Brave Search|Ecosia)\\s*$", RegexOption.IGNORE_CASE).find(title.trim())
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Google", true) }
    }
}
