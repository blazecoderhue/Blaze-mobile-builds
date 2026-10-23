package com.blaze.assistant.appstore

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Version strings compared by their numeric parts (CATALOG_SCHEMA.md "Versions"). Pure; StoreCatalogTest. */
object StoreVersions {
    private val num = Regex("\\d+")
    fun parts(v: String): List<Long> = num.findAll(v).mapNotNull { it.value.take(12).toLongOrNull() }.toList()

    /** <0 when [a] is older than [b], 0 same, >0 newer. Missing trailing parts count as 0 ("24" == "24.0"). */
    fun compare(a: String, b: String): Int {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** True when release [tag] is newer than [installed] (unknown either side = false: never invent an update). */
    fun newer(tag: String, installed: String?): Boolean = !installed.isNullOrBlank() && parts(tag).isNotEmpty() && parts(installed).isNotEmpty() && compare(tag, installed) > 0

    /** "v0.29.1" -> "0.29.1", "THUNDERBIRD_24_0" -> "24.0" (what the UI shows for a tag). */
    fun display(tag: String): String {
        val p = parts(tag)
        if (p.isEmpty()) return tag
        val dotted = Regex("\\d+(?:\\.\\d+)+(?:-\\d+)?").find(tag)?.value
        return dotted ?: p.joinToString(".")
    }
}

/** Live search over the catalog: name/tag/summary words, best first. Pure; StoreCatalogTest. */
object StoreSearch {
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()

    fun score(app: StoreApp, q: String): Int {
        val n = norm(q); if (n.isEmpty()) return 1
        val name = norm(app.name)
        return when {
            name == n || app.id == n || app.pkg.lowercase() == q.trim().lowercase() -> 100
            name.startsWith(n) -> 80
            name.split(' ').any { it.startsWith(n) } -> 70
            app.tags.any { norm(it) == n } -> 60
            n.split(' ').all { w -> app.haystack.contains(w) } -> 40
            else -> 0
        }
    }

    fun search(apps: List<StoreApp>, q: String): List<StoreApp> =
        apps.map { it to score(it, q) }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<StoreApp, Int>> { it.second }.thenBy { it.first.name.lowercase() }).map { it.first }

    /** The one app a tool argument means (id, package, or a clear best name match), or null. */
    fun resolve(apps: List<StoreApp>, q: String): StoreApp? {
        val t = q.trim().removeSuffix(" app").trim()
        if (t.isEmpty()) return null
        apps.firstOrNull { it.id == t.lowercase() || it.pkg == t }?.let { return it }
        val ranked = apps.map { it to score(it, t) }.filter { it.second > 0 }.sortedByDescending { it.second }
        val top = ranked.firstOrNull() ?: return null
        if (top.second < 60) return null
        if (ranked.size > 1 && ranked[1].second == top.second) return null   // ambiguous: let the caller list the matches
        return top.first
    }
}

/** A release file resolved from the source host. */
data class ResolvedAsset(val tag: String, val name: String, val url: String, val size: Long, val hostSha256: String?, val publishedAt: String = "")

/**
 * Finds the newest matching release APK on GitHub / GitLab (CATALOG_SCHEMA.md "Source"). [Http] returns the body or
 * throws. Pure; StoreInstallTest.
 */
class ReleaseResolver(private val http: Http, private val abis: List<String>) {
    fun interface Http { fun getJson(url: String): String }
    class NotFound(msg: String) : Exception(msg)

    private val junk = Regex("(?i)\\.(asc|sha256|sha256sum|idsig|sig|txt|json)$")

    fun apiUrl(app: StoreApp): String = when (app.source.type) {
        "gitlab" -> "https://gitlab.com/api/v4/projects/" + URLEncoder.encode(app.source.repo, "UTF-8") + "/releases?per_page=10"
        else -> "https://api.github.com/repos/${app.source.repo}/releases?per_page=10"
    }

    /** The asset name rule for this phone: the first ABI in [abis] with a rule, else the default pattern. */
    fun ruleFor(app: StoreApp): Regex = abis.firstNotNullOfOrNull { app.asset.abis[it] } ?: app.asset.pattern

    fun resolve(app: StoreApp): ResolvedAsset {
        val body = http.getJson(apiUrl(app))
        val list = try { JSONArray(body) } catch (e: Exception) { throw NotFound("The release list from ${app.source.url} couldn't be read.") }
        val rules = listOfNotNull(abis.firstNotNullOfOrNull { app.asset.abis[it] }, app.asset.pattern)
        for (i in 0 until list.length()) {
            val r = list.optJSONObject(i) ?: continue
            val pick = if (app.source.type == "gitlab") gitlab(r, app, rules) else github(r, app, rules)
            if (pick != null) return pick
        }
        throw NotFound("No release of ${app.name} on ${app.source.url} has an APK matching the catalog.")
    }

    private fun github(r: JSONObject, app: StoreApp, rules: List<Regex>): ResolvedAsset? {
        if (r.optBoolean("draft") || (r.optBoolean("prerelease") && !app.asset.prerelease)) return null
        val assets = r.optJSONArray("assets") ?: return null
        val all = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.filter { !junk.containsMatchIn(it.optString("name")) }
        for (rule in rules) {
            val a = all.firstOrNull { rule.matches(it.optString("name")) } ?: continue
            val dig = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()?.takeIf { it.length == 64 }
            return ResolvedAsset(r.optString("tag_name"), a.optString("name"), a.optString("browser_download_url"), a.optLong("size", 0), dig, r.optString("published_at"))
        }
        return null
    }

    private fun gitlab(r: JSONObject, app: StoreApp, rules: List<Regex>): ResolvedAsset? {
        if (r.optBoolean("upcoming_release")) return null
        val links = r.optJSONObject("assets")?.optJSONArray("links") ?: return null
        val all = (0 until links.length()).mapNotNull { links.optJSONObject(it) }.filter { !junk.containsMatchIn(it.optString("name")) }
        for (rule in rules) {
            val l = all.firstOrNull { rule.matches(it.optString("name")) } ?: continue
            val url = l.optString("direct_asset_url").ifBlank { l.optString("url") }
            if (!url.startsWith("https://")) continue
            return ResolvedAsset(r.optString("tag_name"), l.optString("name"), url, 0, null, r.optString("released_at"))
        }
        return null
    }
}

/** Human sizes for cards: "11.5 MB". */
fun storeSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    else -> "${(bytes / 1000).coerceAtLeast(1)} KB"
}
