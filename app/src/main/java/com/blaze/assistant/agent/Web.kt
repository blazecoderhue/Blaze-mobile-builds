package com.blaze.assistant.agent

import com.blaze.assistant.connect.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** Keyless web search (DuckDuckGo HTML) and page reading, so Blaze can look things up without opening a browser. */
object Web {
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    /**
     * v0.32.0 ("it failed to find REA"): DuckDuckGo's HTML endpoint now often answers phones with a bot check, which used
     * to read as "No results" and made Blaze conclude real things don't exist. Now: DuckDuckGo, then Bing (RSS), then
     * GitHub's repo search for code-ish queries; a block is reported as a block, never as "nothing exists".
     */
    suspend fun search(q: String, n: Int = 6): String = withContext(Dispatchers.IO) {
        val notes = ArrayList<String>()
        ddg(q, n, notes)?.let { return@withContext it }
        bing(q, n, notes)?.let { return@withContext it }
        github(q, n, notes, force = false)?.let { return@withContext it }
        if (notes.any { it.contains("blocked") || it.startsWith("Error") })
            "Error: web search is blocked or failing right now (${notes.joinToString("; ")}). This does NOT mean nothing exists. " +
                "Try another wording, or fetch_page a likely URL directly (for example https://github.com/search?q=${URLEncoder.encode(q, "UTF-8")}&type=repositories)."
        else "No results for \"$q\" on DuckDuckGo or Bing. Try different words before concluding it doesn't exist."
    }

    private fun ddg(q: String, n: Int, notes: MutableList<String>): String? {
        val (code, html) = try {
            Http.req("GET", "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"), headers = mapOf("User-Agent" to UA), timeoutMs = 12_000)
        } catch (e: Exception) { notes += "Error: DuckDuckGo ${e.message}"; return null }
        if (code !in 200..299) { notes += "Error: DuckDuckGo HTTP $code"; return null }
        if (blocked(html)) { notes += "DuckDuckGo blocked (bot check)"; return null }
        val blocks = html.split("class=\"result__body").drop(1)
        val out = StringBuilder()
        var i = 0
        for (b in blocks) {
            val link = Regex("class=\"result__a\" href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>").find(b) ?: continue
            var url = link.groupValues[1]
            Regex("uddg=([^&]+)").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.contains("duckduckgo.com/y.js")) continue
            val title = strip(link.groupValues[2])
            val snip = Regex("class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>").find(b)?.groupValues?.get(1)?.let { strip(it) }.orEmpty()
            out.append("${++i}. $title\n   $url\n   $snip\n")
            if (i >= n) break
        }
        if (i == 0) { notes += "DuckDuckGo: no results"; return null }
        return out.toString()
    }

    /** A bot-check / captcha page instead of results. */
    fun blocked(html: String): Boolean = html.contains("anomaly-modal") || html.contains("challenge-form") ||
        html.contains("bots use DuckDuckGo") || (html.contains("captcha", ignoreCase = true) && !html.contains("result__body"))

    private fun bing(q: String, n: Int, notes: MutableList<String>): String? {
        val (code, xml) = try {
            Http.req("GET", "https://www.bing.com/search?format=rss&q=" + URLEncoder.encode(q, "UTF-8"), headers = mapOf("User-Agent" to UA), timeoutMs = 12_000)
        } catch (e: Exception) { notes += "Error: Bing ${e.message}"; return null }
        if (code !in 200..299) { notes += "Error: Bing HTTP $code"; return null }
        val r = parseRss(xml, n)
        if (r == null) notes += "Bing: no results"
        return r
    }

    /** Bing's RSS results as a numbered list (pure; tested). */
    fun parseRss(xml: String, n: Int): String? {
        val out = StringBuilder(); var i = 0
        for (item in xml.split("<item>").drop(1)) {
            fun tag(t: String) = Regex("<$t>([\\s\\S]*?)</$t>").find(item)?.groupValues?.get(1)?.let { strip(it.replace("<![CDATA[", "").replace("]]>", "")) }.orEmpty()
            val url = tag("link"); if (url.isBlank() || url.contains("bing.com/search")) continue
            out.append("${++i}. ${tag("title")}\n   $url\n   ${tag("description")}\n")
            if (i >= n) break
        }
        return if (i == 0) null else out.toString()
    }

    private val codeish = Regex("(?i)\\b(github|repo|repository|library|sdk|npm|pip|package|cli|mcp|skill|open[- ]source|framework|plugin)\\b")

    private fun github(q: String, n: Int, notes: MutableList<String>, force: Boolean): String? {
        if (!force && !codeish.containsMatchIn(q) && notes.none { it.contains("blocked") || it.startsWith("Error") }) return null
        val words = q.replace(Regex("(?i)\\b(github|repo|repository|skill|agent)\\b"), " ").trim().ifBlank { q }
        val (code, body) = try {
            Http.req("GET", "https://api.github.com/search/repositories?per_page=$n&q=" + URLEncoder.encode(words, "UTF-8"),
                headers = mapOf("User-Agent" to UA, "Accept" to "application/vnd.github+json"), timeoutMs = 12_000)
        } catch (e: Exception) { notes += "Error: GitHub ${e.message}"; return null }
        if (code !in 200..299) { notes += "Error: GitHub HTTP $code"; return null }
        val items = try { JSONObject(body).optJSONArray("items") } catch (_: Exception) { null } ?: return null
        if (items.length() == 0) return null
        val out = StringBuilder("GitHub repositories:\n")
        for (k in 0 until minOf(n, items.length())) { val o = items.getJSONObject(k)
            out.append("${k + 1}. ${o.optString("full_name")} (${o.optInt("stargazers_count")} stars)\n   ${o.optString("html_url")}\n   ${o.optString("description").take(200)}\n") }
        return out.toString()
    }

    suspend fun fetch(url: String, max: Int = 9000): String = withContext(Dispatchers.IO) {
        val (code, body) = try { Http.req("GET", url, headers = mapOf("User-Agent" to UA, "Accept" to "text/html,application/json,text/plain"), timeoutMs = 20_000, strict = false) }
            catch (e: Exception) { return@withContext "Error: couldn't open $url (${e.message})" }
        if (code !in 200..299) return@withContext "Error: HTTP $code from $url"
        val t = body.trimStart()
        val text = if (t.startsWith("{") || t.startsWith("[")) t else {
            val titled = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.let { strip(it) }.orEmpty()
            val main = body.replace(Regex("<(script|style|nav|footer|header|svg|noscript)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<(br|p|div|li|h[1-6]|tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            (if (titled.isNotBlank()) "# $titled\n" else "") + strip(main).lines().map { it.trim() }.filter { it.length > 1 }.joinToString("\n")
        }
        if (text.length > max) text.take(max) + "\n…(cut, ${text.length} chars total)" else text
    }

    private fun strip(h: String) = h.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace(Regex("[ \\t]+"), " ").trim()

    val names = setOf("web_search", "fetch_page")
    fun tools(): List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "web_search")
            .put("description", "Search the web (no browser needed). Returns titles, links and snippets. Use for news, facts, prices, how-tos.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("query"))))),
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "fetch_page")
            .put("description", "Read a web page or JSON API as plain text.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("url", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("url")))))
    )
}
