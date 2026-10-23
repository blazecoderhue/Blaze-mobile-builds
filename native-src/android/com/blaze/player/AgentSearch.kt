// Blaze Player (Apache-2.0). Added by Blazeinchue.
package com.blaze.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** search(licensed sources): the user's local library and Internet Archive PD/CC feature films. */
object AgentSearch {
    const val ARCHIVE_FILTER =
        "collection:(feature_films) AND mediatype:(movies) AND " +
            "(licenseurl:*creativecommons.org* OR licenseurl:*publicdomain*) AND NOT licenseurl:*-nc*"
    private const val LIB_PREFS = "blaze_player_agent"
    private const val LIB_KEY = "library"

    fun setLibrary(ctx: Context, json: String) {
        ctx.applicationContext.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)
            .edit().putString(LIB_KEY, json).apply()
    }

    fun sanitize(q: String): String =
        q.replace(Regex("[()\\[\\]{}:\"\\\\^~*?!+&|]"), " ")
            .replace(Regex("\\b(AND|OR|NOT|TO)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim().take(120)

    fun isAllowedLicense(url: String?): Boolean {
        val u = url?.lowercase() ?: return false
        if (!(u.contains("creativecommons.org") || u.contains("publicdomain"))) return false
        return !Regex("[/-]nc[/-]|-nc$|by-nc").containsMatchIn(u)
    }

    fun searchLocal(ctx: Context, query: String, limit: Int): JSONArray {
        val out = JSONArray()
        val raw = ctx.applicationContext.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)
            .getString(LIB_KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val q = query.trim().lowercase()
        for (i in 0 until arr.length()) {
            if (out.length() >= limit) break
            val o = arr.optJSONObject(i) ?: continue
            if (q.isEmpty() || o.optString("title").lowercase().contains(q)) out.put(o)
        }
        return out
    }

    fun searchArchive(query: String, limit: Int): JSONArray {
        val q = sanitize(query)
        val full = if (q.isEmpty()) ARCHIVE_FILTER else "($q) AND $ARCHIVE_FILTER"
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val url = "https://archive.org/advancedsearch.php?q=${enc(full)}" +
            listOf("identifier", "title", "year", "licenseurl", "creator").joinToString("") { "&fl[]=${enc(it)}" } +
            "&sort[]=${enc("downloads desc")}&rows=${limit.coerceIn(1, 100)}&page=1&output=json"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) throw RuntimeException("Internet Archive HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val docs = JSONObject(body).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until docs.length()) {
                val d = docs.optJSONObject(i) ?: continue
                val ident = d.optString("identifier")
                if (ident.isEmpty() || !isAllowedLicense(d.optString("licenseurl"))) continue
                out.put(JSONObject().apply {
                    put("id", "archive:$ident")
                    put("source", "archive")
                    put("title", d.opt("title")?.let { if (it is JSONArray) it.optString(0) else it.toString() } ?: ident)
                    d.opt("year")?.let { put("year", it.toString()) }
                    put("poster", "https://archive.org/services/img/$ident")
                    put("licenseUrl", d.optString("licenseurl"))
                    put("pageUrl", "https://archive.org/details/$ident")
                })
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking; call off the main thread. */
    fun search(ctx: Context, query: String, sources: List<String>, limit: Int): JSONArray {
        val out = JSONArray()
        if ("local" in sources) {
            val l = searchLocal(ctx, query, limit)
            for (i in 0 until l.length()) out.put(l.get(i))
        }
        if ("archive" in sources && out.length() < limit) {
            val a = searchArchive(query, limit - out.length())
            for (i in 0 until a.length()) out.put(a.get(i))
        }
        return out
    }
}
