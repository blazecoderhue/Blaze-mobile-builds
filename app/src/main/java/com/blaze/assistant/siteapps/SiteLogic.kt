package com.blaze.assistant.siteapps

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/*
 * v0.33.2 "Install app" for websites: the pure rules (unit tested in SiteLogicTest). Reading a page's <head> (manifest
 * link, icon links, theme-color, title), parsing a web app manifest, picking the site's real icon in the owner's order
 * (manifest icons, largest and maskable first, then apple-touch-icon, then the largest <link rel=icon>, then
 * /favicon.ico, and only then a generated letter icon), the app's id and scope, and which links stay inside the app.
 * The Android side (fetching, decoding, shortcuts, BlazeOS install) is SiteInstaller.kt; the app window is
 * SiteAppActivity.kt.
 */

/** One icon a site offers. [size] = the largest side it says (0 = unknown, [ANY] = scalable). */
data class IconCandidate(val url: String, val source: String, val size: Int, val maskable: Boolean = false, val type: String = "")

data class WebManifest(
    val name: String = "",
    val shortName: String = "",
    val startUrl: String = "",
    val scope: String = "",
    val display: String = "",
    val themeColor: String = "",
    val backgroundColor: String = "",
    val icons: List<IconCandidate> = emptyList(),
)

data class PageHead(
    val title: String = "",
    val appName: String = "",
    val manifestUrl: String = "",
    val themeColor: String = "",
    /** rel=icon / shortcut icon / apple-touch-icon links, in page order. */
    val icons: List<IconCandidate> = emptyList(),
)

/** An installed site: what the app window needs. */
data class SiteApp(
    val id: String,
    val name: String,
    val startUrl: String,
    /** Links under this prefix stay in the app; others open in the browser. */
    val scope: String,
    val themeColor: String,
    val backgroundColor: String,
    val installedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("startUrl", startUrl).put("scope", scope)
        .put("themeColor", themeColor).put("backgroundColor", backgroundColor).put("installedAt", installedAt)

    companion object {
        fun fromJson(o: JSONObject?): SiteApp? {
            o ?: return null
            val id = o.optString("id"); val start = o.optString("startUrl")
            if (id.isBlank() || !start.startsWith("https://") && !start.startsWith("http://")) return null
            return SiteApp(id, o.optString("name").ifBlank { SiteLogic.hostOf(start) }, start, o.optString("scope").ifBlank { SiteLogic.originOf(start) + "/" },
                SiteLogic.color(o.optString("themeColor")), SiteLogic.color(o.optString("backgroundColor")), o.optLong("installedAt"))
        }

        fun listFromJson(text: String?): List<SiteApp> = try {
            val a = JSONArray(text ?: "[]"); (0 until a.length()).mapNotNull { fromJson(a.optJSONObject(it)) }.distinctBy { it.id }
        } catch (_: Exception) { emptyList() }

        fun listToJson(list: List<SiteApp>): String = JSONArray().apply { for (s in list) put(s.toJson()) }.toString()
    }
}

object SiteLogic {
    const val ANY = 4096
    /** Icons this small aren't worth a home-screen icon if anything bigger exists. */
    const val MIN_GOOD = 96

    // ---------------- urls ----------------

    fun resolve(base: String, ref: String): String = try {
        val r = ref.trim()
        if (r.isEmpty()) "" else URI(base).resolve(r.replace(" ", "%20")).toString()
    } catch (_: Exception) { "" }

    fun originOf(url: String): String = try {
        val u = URI(url); val port = if (u.port > 0) ":${u.port}" else ""
        if (u.scheme == null || u.host == null) "" else "${u.scheme.lowercase()}://${u.host.lowercase()}$port"
    } catch (_: Exception) { "" }

    fun hostOf(url: String): String = try { URI(url).host?.lowercase()?.removePrefix("www.").orEmpty() } catch (_: Exception) { "" }

    /** A stable id for a site app: host + first path part of its start url ("site.github.com", "site.example.org.app"). */
    fun idFor(startUrl: String): String {
        val host = hostOf(startUrl).ifEmpty { "site" }
        val first = try { URI(startUrl).path.orEmpty().trim('/').substringBefore('/') } catch (_: Exception) { "" }
        val raw = "site." + host + if (first.isNotEmpty() && first.length <= 24) ".$first" else ""
        return raw.lowercase().map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '-' }.joinToString("").take(64)
    }

    /**
     * The app's scope: the manifest's scope when it's on the start url's origin, else the start url's origin (the whole
     * site). Always ends with "/".
     */
    fun scopeFor(startUrl: String, manifestScope: String): String {
        val o = originOf(startUrl)
        val s = manifestScope.trim()
        val scoped = if (s.isNotEmpty() && originOf(s) == o) s else "$o/"
        return if (scoped.endsWith("/")) scoped else scoped.substringBeforeLast('/') + "/"
    }

    /** True when [url] belongs to the app (same origin, under its scope); other links open in the browser. */
    fun inScope(url: String, scope: String): Boolean {
        val o = originOf(url)
        if (o.isEmpty() || o != originOf(scope)) return false
        val path = try { URI(url).rawPath.orEmpty().ifEmpty { "/" } } catch (_: Exception) { return false }
        val sp = try { URI(scope).rawPath.orEmpty().ifEmpty { "/" } } catch (_: Exception) { "/" }
        return path.startsWith(sp) || (path + "/") == sp
    }

    // ---------------- colours ----------------

    private val NAMED = mapOf("black" to "#000000", "white" to "#FFFFFF", "red" to "#FF0000", "green" to "#008000", "blue" to "#0000FF",
        "navy" to "#000080", "gray" to "#808080", "grey" to "#808080", "orange" to "#FFA500", "purple" to "#800080", "teal" to "#008080")

    /** "#abc", "#aabbcc", "#aabbccdd" (alpha dropped), "rgb(1,2,3)" or a few names → "#RRGGBB"; anything else → "". */
    fun color(s: String?): String {
        val t = s?.trim()?.lowercase().orEmpty()
        NAMED[t]?.let { return it }
        Regex("^#([0-9a-f]{3})$").find(t)?.let { m -> return "#" + m.groupValues[1].map { "$it$it" }.joinToString("").uppercase() }
        Regex("^#([0-9a-f]{6})([0-9a-f]{2})?$").find(t)?.let { m -> return "#" + m.groupValues[1].uppercase() }
        Regex("^rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})").find(t)?.let { m ->
            val (r, g, b) = m.destructured
            return "#" + listOf(r, g, b).joinToString("") { it.toInt().coerceIn(0, 255).toString(16).padStart(2, '0') }.uppercase()
        }
        return ""
    }

    /** True when white status-bar icons read better on [hex] than dark ones. */
    fun isDark(hex: String): Boolean {
        val v = hex.removePrefix("#").toLongOrNull(16) ?: return true
        val r = (v shr 16) and 0xFF; val g = (v shr 8) and 0xFF; val b = v and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) < 150
    }

    // ---------------- head + manifest ----------------

    private fun attr(tag: String, name: String): String {
        val m = Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag) ?: return ""
        return htmlDecode(m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] })
    }

    private fun htmlDecode(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /** Largest side from a sizes attribute ("16x16 32x32", "any"); 0 when missing. */
    fun sizeOf(sizes: String): Int {
        val t = sizes.trim().lowercase()
        if (t.split(Regex("\\s+")).any { it == "any" }) return ANY
        return Regex("(\\d+)\\s*x\\s*(\\d+)").findAll(t).maxOfOrNull { maxOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: 0
    }

    /** Reads the parts of a page's <head> that matter for installing it. */
    fun parseHead(html: String, pageUrl: String): PageHead {
        val head = html.take(400_000)
        val base = Regex("""<base\s[^>]*>""", RegexOption.IGNORE_CASE).find(head)?.value?.let { attr(it, "href") }?.takeIf { it.isNotBlank() }
            ?.let { resolve(pageUrl, it) }?.ifEmpty { null } ?: pageUrl
        var manifest = ""; var theme = ""; var appName = ""
        val icons = ArrayList<IconCandidate>()
        for (m in Regex("""<(link|meta)\s[^>]*>""", RegexOption.IGNORE_CASE).findAll(head)) {
            val tag = m.value
            if (m.groupValues[1].equals("meta", true)) {
                val n = attr(tag, "name").lowercase()
                if (n == "theme-color" && theme.isEmpty()) theme = color(attr(tag, "content"))
                if ((n == "application-name" || n == "apple-mobile-web-app-title") && appName.isEmpty()) appName = attr(tag, "content").trim()
                continue
            }
            val rel = attr(tag, "rel").lowercase().split(Regex("\\s+")).toSet()
            val href = attr(tag, "href")
            if (href.isBlank()) continue
            val url = resolve(base, href)
            if (url.isEmpty() || url.startsWith("data:")) continue
            val type = attr(tag, "type").lowercase()
            when {
                "manifest" in rel -> if (manifest.isEmpty()) manifest = url
                "apple-touch-icon" in rel || "apple-touch-icon-precomposed" in rel ->
                    icons += IconCandidate(url, APPLE, sizeOf(attr(tag, "sizes")).let { if (it == 0) 180 else it }, type = type)
                "icon" in rel -> icons += IconCandidate(url, LINK, sizeOf(attr(tag, "sizes")), type = type)
            }
        }
        val title = Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.let { htmlDecode(it).trim() }.orEmpty()
        return PageHead(title, appName, manifest, theme, icons)
    }

    const val MANIFEST = "manifest"
    const val APPLE = "apple-touch-icon"
    const val LINK = "icon"
    const val FAVICON = "favicon"

    /** Parses a web app manifest (resolving its urls against the manifest's own url). */
    fun parseManifest(text: String, manifestUrl: String): WebManifest? {
        val o = try { JSONObject(text) } catch (_: Exception) { return null }
        val icons = ArrayList<IconCandidate>()
        val arr = o.optJSONArray("icons") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val ic = arr.optJSONObject(i) ?: continue
            val src = ic.optString("src").trim(); if (src.isEmpty()) continue
            val url = resolve(manifestUrl, src); if (url.isEmpty() || url.startsWith("data:")) continue
            val purpose = ic.optString("purpose").lowercase().split(Regex("\\s+")).toSet()
            // A monochrome-only icon is a glyph for themed icons, not the app icon.
            if (purpose.isNotEmpty() && purpose != setOf("") && "any" !in purpose && "maskable" !in purpose) continue
            icons += IconCandidate(url, MANIFEST, sizeOf(ic.optString("sizes")), maskable = "maskable" in purpose, type = ic.optString("type").lowercase())
        }
        return WebManifest(o.optString("name").trim(), o.optString("short_name").trim(),
            o.optString("start_url").trim().takeIf { it.isNotEmpty() }?.let { resolve(manifestUrl, it) }.orEmpty(),
            o.optString("scope").trim().takeIf { it.isNotEmpty() }?.let { resolve(manifestUrl, it) }.orEmpty(),
            o.optString("display").trim().lowercase(), color(o.optString("theme_color")), color(o.optString("background_color")), icons)
    }

    /** True for an icon Android's BitmapFactory can decode (not SVG). */
    fun raster(c: IconCandidate): Boolean = !c.type.contains("svg") && !c.url.substringBefore('?').lowercase().endsWith(".svg")

    /**
     * Every icon to try, best first, in the owner's order: the manifest's icons (largest first, maskable before plain
     * at the same size), then apple-touch-icons (largest first), then <link rel=icon> (largest first), then
     * origin/favicon.ico. SVGs are skipped (they can't be decoded into a launcher bitmap); repeats are dropped.
     * The caller downloads them in this order and uses the first that decodes; none → the letter icon.
     */
    fun iconOrder(manifest: WebManifest?, head: PageHead, pageUrl: String): List<IconCandidate> {
        val m = manifest?.icons.orEmpty().filter(::raster)
            .sortedWith(compareByDescending<IconCandidate> { if (it.size == ANY) 0 else it.size }.thenByDescending { it.maskable })
        val apple = head.icons.filter { it.source == APPLE && raster(it) }.sortedByDescending { it.size }
        val links = head.icons.filter { it.source == LINK && raster(it) }.sortedByDescending { it.size }
        val fav = originOf(pageUrl).takeIf { it.isNotEmpty() }?.let { listOf(IconCandidate("$it/favicon.ico", FAVICON, 0)) }.orEmpty()
        val seen = HashSet<String>()
        return (m + apple + links + fav).filter { seen.add(it.url) }
    }

    /** The name the app gets: manifest short_name (if short), name, the page's app name, its title, then the host. */
    fun nameFor(manifest: WebManifest?, head: PageHead, pageUrl: String): String {
        val short = manifest?.shortName.orEmpty()
        val n = listOf(short.takeIf { it.length in 1..14 }, manifest?.name, short, head.appName, head.title.substringBefore(" | ").substringBefore(" - ").trim())
            .firstOrNull { !it.isNullOrBlank() } ?: hostOf(pageUrl)
        return n.trim().take(30).ifEmpty { "App" }
    }

    /** Builds the app from what was read. The start url is the manifest's when it's on the same origin, else the page. */
    fun appFor(manifest: WebManifest?, head: PageHead, pageUrl: String, now: Long): SiteApp {
        val start = manifest?.startUrl?.takeIf { it.isNotEmpty() && originOf(it) == originOf(pageUrl) } ?: pageUrl
        val theme = manifest?.themeColor?.ifEmpty { null } ?: head.themeColor
        val bg = manifest?.backgroundColor?.ifEmpty { null } ?: theme.ifEmpty { "#FFFFFF" }
        return SiteApp(idFor(start), nameFor(manifest, head, pageUrl), start, scopeFor(start, manifest?.scope.orEmpty()), theme, bg, now)
    }

    // ---------------- letter icon ----------------

    /** The letter for the fallback icon: the name's first letter or digit, uppercase. */
    fun letter(name: String): String = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

    private val palette = listOf("#E8590C", "#1971C2", "#2F9E44", "#9C36B5", "#C2255C", "#0C8599", "#5F3DC4", "#E67700")

    /** The fallback icon's colour: the theme colour, else one picked from the host so it stays the same. */
    fun letterColor(host: String, theme: String): String = theme.ifEmpty { palette[(host.hashCode() and 0x7fffffff) % palette.size] }
}
