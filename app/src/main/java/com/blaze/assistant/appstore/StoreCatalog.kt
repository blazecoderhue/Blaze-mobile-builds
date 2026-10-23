package com.blaze.assistant.appstore

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 0.45.19 Store tab: the catalog (store/CATALOG_SCHEMA.md, schema v1). Plain data, parsed with org.json; a bad entry is
 * skipped, never the whole catalog. Pure JVM: StoreCatalogTest.
 */
data class StoreCategory(val id: String, val name: String)

data class StoreSourceRef(val type: String, val repo: String) {
    val url: String get() = if (type == "gitlab") "https://gitlab.com/$repo" else "https://github.com/$repo"
    /** The developer's own release page: a fallback link only, never an install target. */
    val releasesUrl: String get() = if (type == "gitlab") "$url/-/releases" else "$url/releases"
}

/**
 * Where a release file may come from: the official GitHub / GitLab / F-Droid hosts over https, nothing else
 * (user decision 2026-10-23). Redirects to GitHub's own download CDN are followed by the connection.
 */
object StoreHosts {
    private val hosts = setOf("github.com", "gitlab.com", "f-droid.org")
    fun official(url: String): Boolean {
        val u = try { java.net.URI(url) } catch (_: Exception) { return false }
        val h = u.host?.lowercase() ?: return false
        return u.scheme == "https" && u.userInfo == null && (u.port == -1 || u.port == 443) && hosts.any { h == it || h.endsWith(".$it") }
    }
}

data class AssetRule(val pattern: Regex, val abis: Map<String, Regex> = emptyMap(), val prerelease: Boolean = false)

data class KnownRelease(val sha256: String? = null, val byAsset: Map<String, String> = emptyMap(), val size: Long = 0, val versionCode: Long = 0)

data class StoreApp(
    val id: String, val name: String, val pkg: String, val summary: String, val description: String, val icon: String,
    val category: String, val website: String, val license: String, val tags: List<String>, val source: StoreSourceRef,
    val asset: AssetRule, val signers: Set<String>, val releases: Map<String, KnownRelease>, val minSdk: Int,
) {
    /** The expected SHA-256 for [assetName] of release [tag] from the catalog, or null. */
    fun knownSha(tag: String, assetName: String): String? = releases[tag]?.let { r -> r.byAsset[assetName] ?: r.sha256 }?.lowercase()

    /** Words search matches on. */
    val haystack: String by lazy { (listOf(name, id, pkg, summary, category, source.repo) + tags).joinToString(" ").lowercase() }
}

data class Catalog(val schema: Int, val name: String, val updated: String, val categories: List<StoreCategory>, val apps: List<StoreApp>) {
    fun byId(id: String): StoreApp? = apps.firstOrNull { it.id == id }
    fun byPackage(pkg: String): StoreApp? = apps.firstOrNull { it.pkg == pkg }

    /** Categories in catalog order, plus "Other" when some app's category isn't listed; only ones with apps. */
    fun shelf(): List<StoreCategory> {
        val known = categories.filter { c -> apps.any { it.category == c.id } }
        return if (apps.any { a -> categories.none { it.id == a.category } }) known + StoreCategory(OTHER, "Other") else known
    }

    fun inCategory(id: String): List<StoreApp> = when (id) {
        "", ALL -> apps
        OTHER -> apps.filter { a -> categories.none { it.id == a.category } }
        else -> apps.filter { it.category == id }
    }

    companion object {
        const val SCHEMA = 1
        const val ALL = "all"
        const val OTHER = "other"
        val EMPTY = Catalog(SCHEMA, "Blaze Store", "", emptyList(), emptyList())
    }
}

object CatalogParser {
    class Bad(msg: String) : Exception(msg)

    private val idRe = Regex("^[a-z0-9-]{1,40}$")
    private val pkgRe = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val hexRe = Regex("^[0-9a-fA-F]{64}$")
    private val repoRe = Regex("^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+$")
    const val MAX_BYTES = 1_000_000

    /** Parses [text]. Throws [Bad] when it isn't a usable catalog (bad JSON, newer schema, no apps array). */
    fun parse(text: String): Catalog {
        if (text.length > MAX_BYTES) throw Bad("catalog too big")
        val o = try { JSONObject(text) } catch (e: Exception) { throw Bad("not JSON: ${e.message}") }
        val schema = o.optInt("schema", 0)
        if (schema < 1) throw Bad("no schema")
        if (schema > Catalog.SCHEMA) throw Bad("schema $schema is newer than this Blaze (knows ${Catalog.SCHEMA})")
        val arr = o.optJSONArray("apps") ?: throw Bad("no apps")
        val cats = o.optJSONArray("categories")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .mapNotNull { c -> c.optString("id").takeIf { it.isNotBlank() }?.let { StoreCategory(it, c.optString("name").ifBlank { it }) } } }.orEmpty()
        val seen = HashSet<String>()
        val apps = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { app(it) }.filter { seen.add(it.id) }
        return Catalog(schema, o.optString("name").ifBlank { "Blaze Store" }, o.optString("updated"), cats, apps)
    }

    /** One app, or null when a required field is missing or malformed. */
    fun app(a: JSONObject): StoreApp? = try {
        val id = a.optString("id"); val pkg = a.optString("package"); val name = a.optString("name").trim()
        val src = a.optJSONObject("source"); val asset = a.optJSONObject("asset")
        val type = src?.optString("type").orEmpty(); val repo = src?.optString("repo").orEmpty()
        val pattern = asset?.optString("pattern").orEmpty()
        if (!idRe.matches(id) || !pkgRe.matches(pkg) || name.isEmpty() || a.optString("summary").isBlank() || a.optString("category").isBlank()
            || type !in setOf("github", "gitlab") || !repoRe.matches(repo) || pattern.isBlank()) null
        else {
            val abis = asset!!.optJSONObject("abis")?.let { m -> m.keys().asSequence().associateWith { Regex(m.getString(it)) } }.orEmpty()
            val rel = a.optJSONObject("releases")?.let { m -> m.keys().asSequence().associateWith { k ->
                val r = m.getJSONObject(k)
                KnownRelease(r.optString("sha256").takeIf { hexRe.matches(it) }?.lowercase(),
                    r.optJSONObject("sha256_by_asset")?.let { b -> b.keys().asSequence().associateWith { b.getString(it).lowercase() }.filterValues { hexRe.matches(it) } }.orEmpty(),
                    r.optLong("size", 0), r.optLong("version_code", 0))
            } }.orEmpty()
            StoreApp(id, name, pkg, a.optString("summary").trim(), a.optString("description").trim(), a.optString("icon").takeIf { it.startsWith("https://") }.orEmpty(),
                a.optString("category"), a.optString("website").takeIf { it.startsWith("https://") }.orEmpty(), a.optString("license"),
                strings(a.optJSONArray("tags")), StoreSourceRef(type, repo), AssetRule(Regex(pattern), abis, asset.optBoolean("prerelease", false)),
                strings(a.optJSONArray("signer_sha256")).filter { hexRe.matches(it) }.map { it.lowercase() }.toSet(), rel, a.optInt("min_sdk", 0))
        }
    } catch (e: Exception) { null }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
}

/**
 * The on-phone copy with its ETag, and the fetch rules: 304 keeps the cache; an error or a bad body keeps the cache;
 * only a 200 with a valid body replaces it. [Http] is the network, so the rules are unit tested.
 */
class CatalogCache(private val dir: File, private val seed: () -> String?) {
    data class Response(val code: Int, val body: String?, val etag: String?)
    fun interface Http { fun get(url: String, etag: String?): Response }

    enum class Outcome { UPDATED, NOT_MODIFIED, OFFLINE, BAD_BODY, HTTP_ERROR }
    data class Result(val catalog: Catalog, val outcome: Outcome, val detail: String = "")

    private val body get() = File(dir, "catalog.json")
    private val tag get() = File(dir, "catalog.etag")
    private val stamp get() = File(dir, "catalog.checked")

    val etag: String? get() = try { tag.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null } } catch (_: Exception) { null }
    /** When the last fetch answered (200 or 304), 0 = never. */
    val checkedAt: Long get() = try { stamp.takeIf { it.isFile }?.readText()?.trim()?.toLong() ?: 0L } catch (_: Exception) { 0L }

    /** The cached catalog, else the bundled seed, else empty. Never throws. */
    fun load(): Catalog {
        try { if (body.isFile) return CatalogParser.parse(body.readText()) } catch (_: Exception) { }
        try { seed()?.let { return CatalogParser.parse(it) } } catch (_: Exception) { }
        return Catalog.EMPTY
    }

    fun refresh(url: String, http: Http, now: Long = System.currentTimeMillis()): Result {
        val r = try { http.get(url, if (body.isFile) etag else null) } catch (e: Exception) { return Result(load(), Outcome.OFFLINE, e.message ?: "offline") }
        return when {
            r.code == 304 && body.isFile -> { mark(now); Result(load(), Outcome.NOT_MODIFIED) }
            r.code == 200 && r.body != null -> {
                val parsed = try { CatalogParser.parse(r.body) } catch (e: CatalogParser.Bad) { return Result(load(), Outcome.BAD_BODY, e.message ?: "") }
                if (parsed.apps.isEmpty()) return Result(load(), Outcome.BAD_BODY, "no usable apps")
                dir.mkdirs()
                val tmp = File(dir, "catalog.json.tmp"); tmp.writeText(r.body)
                if (!tmp.renameTo(body)) { body.delete(); tmp.renameTo(body) }
                if (r.etag.isNullOrBlank()) tag.delete() else tag.writeText(r.etag)
                mark(now)
                Result(parsed, Outcome.UPDATED)
            }
            else -> Result(load(), Outcome.HTTP_ERROR, "HTTP ${r.code}")
        }
    }

    private fun mark(now: Long) { try { dir.mkdirs(); stamp.writeText(now.toString()) } catch (_: Exception) { } }

    companion object {
        const val URL = "https://blazeinchue.pages.dev/store/catalog.json"
        const val STALE_MS = 6L * 3600_000
    }
}
