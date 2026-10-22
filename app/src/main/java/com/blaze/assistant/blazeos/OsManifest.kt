package com.blaze.assistant.blazeos

import org.json.JSONArray
import org.json.JSONObject

/*
 * v0.33.2 BlazeOS app manifests and the App Store catalog. Pure parsing / validation (org.json only), unit tested in
 * OsManifestTest. Format (manifest.json inside an app package, or one entry of the catalog):
 *
 *   {
 *     "id": "com.blazeos.snake",          lowercase letters, digits, dots, dashes, underscores; 2..64 chars
 *     "name": "Snake",                     1..30 chars
 *     "version": "1.0.0", "versionCode": 1,
 *     "icon": { "glyph": "snake", "from": "#34C759", "to": "#0A7D32" }   or "icon": "icon.png" (a file in the package)
 *     "entry": { "type": "web", "path": "index.html" }                     HTML/JS in the package
 *            | { "type": "web", "url": "https://example.org/app/" }       a hosted web app (https only)
 *            | { "type": "builtin", "key": "clock" }                       a Kotlin app built into BlazeOS
 *            | { "type": "linux", "command": "xeyes", "packages": ["xeyes"] }   runs in the Linux desktop (proot)
 *            | { "type": "wasm", "path": "app.wasm" }                      v0.36.0: a native app (WebAssembly in the package,
 *              drawing straight to the screen through BlazeOS's syscalls; OsSyscalls.kt, OsWasmRuntime.kt)
 *              optional "distro_packages": { "ubuntu": ["x11-apps"], "fedora": ["xeyes"] } (v0.33.5: names for the other
 *              Linuxes; without it Alpine's names are mapped by DesktopDistro.mapPackages)
 *     "permissions": ["storage", "notifications", "camera", "mic"],
 *     "description": "…", "author": "…", "category": "Games"
 *   }
 *
 * A catalog adds "package" (where the zip is: "asset:blazeos/apps/snake.zip" now, an https url later) and "size".
 */

data class OsIcon(val glyph: String = "", val from: String = "", val to: String = "", val file: String = "")

data class OsEntry(val type: String, val path: String = "", val url: String = "", val key: String = "", val command: String = "",
                   val packages: List<String> = emptyList(), val distroPackages: Map<String, List<String>> = emptyMap())

data class OsManifestData(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val icon: OsIcon,
    val entry: OsEntry,
    val permissions: List<String> = emptyList(),
    val description: String = "",
    val author: String = "",
    val category: String = "",
    /** Installed websites (v0.33.2 "Install app"): the manifest's colours ("#RRGGBB" or "") and the scope links stay in. */
    val themeColor: String = "",
    val backgroundColor: String = "",
    val scope: String = "",
)

/** One App Store listing: the manifest plus where its package is and how big it is. */
data class OsListing(val manifest: OsManifestData, val pkg: String, val size: Long)

data class OsCatalog(val version: Int, val apps: List<OsListing>, val remote: String = "", val errors: List<String> = emptyList())

object OsManifest {
    const val BUILTIN = "builtin"
    const val WEB = "web"
    const val LINUX = "linux"
    const val WASM = "wasm"
    val types = setOf(BUILTIN, WEB, LINUX, WASM)

    /** Entry types whose code ships in a package zip (installed by unzipping it). */
    fun packaged(e: OsEntry): Boolean = e.type == WASM || e.type == WEB && e.url.isEmpty()

    private val ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val VERSION = Regex("^\\d{1,4}(\\.\\d{1,4}){0,3}$")
    private val COMMAND = Regex("^[A-Za-z0-9 ._/+=:,-]{1,120}$")
    private val PKG = Regex("^[a-z0-9][a-z0-9.+-]{0,63}$")
    /** Fedora has capitals (Thunar, ImageMagick). */
    private val DPKG = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")
    private val DISTRO_ID = Regex("^[a-z][a-z0-9-]{0,31}$")

    /** Either a manifest or the reason it was refused. */
    sealed class Parsed {
        data class Ok(val m: OsManifestData) : Parsed()
        data class Bad(val why: String) : Parsed()
    }

    fun parse(text: String): Parsed = try { parse(JSONObject(text)) } catch (e: Exception) { Parsed.Bad("Not valid JSON") }

    fun parse(o: JSONObject): Parsed {
        val id = o.optString("id").trim()
        if (!ID.matches(id)) return Parsed.Bad("Bad id \"$id\"")
        val name = o.optString("name").trim()
        if (name.isEmpty() || name.length > 30) return Parsed.Bad("Name must be 1 to 30 characters")
        val version = o.optString("version", "1.0").trim()
        if (!VERSION.matches(version)) return Parsed.Bad("Bad version \"$version\"")
        val code = o.optInt("versionCode", 1)
        if (code < 1) return Parsed.Bad("versionCode must be 1 or more")
        val e = o.optJSONObject("entry") ?: return Parsed.Bad("No entry")
        val entry = entry(e) ?: return Parsed.Bad("Bad entry")
        val icon = when (val i = o.opt("icon")) {
            is JSONObject -> OsIcon(i.optString("glyph").trim().lowercase(), color(i.optString("from")), color(i.optString("to")))
            is String -> if (safePath(i.trim())) OsIcon(file = i.trim()) else OsIcon()
            else -> OsIcon()
        }
        val perms = strings(o.optJSONArray("permissions")).map { it.lowercase() }.filter { it in OsPerms.all }.distinct()
        return Parsed.Ok(OsManifestData(id, name, version, code, icon, entry, perms,
            o.optString("description").trim().take(400), o.optString("author").trim().take(60), o.optString("category").trim().take(30),
            color(o.optString("theme_color")), color(o.optString("background_color")), o.optString("scope").trim().takeIf { httpsUrl(it) }.orEmpty()))
    }

    private fun entry(e: JSONObject): OsEntry? {
        val type = e.optString("type").trim().lowercase()
        return when (type) {
            WEB -> {
                val path = e.optString("path").trim()
                val url = e.optString("url").trim()
                when {
                    url.isNotEmpty() -> if (httpsUrl(url)) OsEntry(WEB, url = url) else null
                    path.isEmpty() -> OsEntry(WEB, path = "index.html")
                    safePath(path) -> OsEntry(WEB, path = path)
                    else -> null
                }
            }
            WASM -> e.optString("path").trim().ifEmpty { "app.wasm" }.takeIf { safePath(it) && it.endsWith(".wasm") }?.let { OsEntry(WASM, path = it) }
            BUILTIN -> e.optString("key").trim().lowercase().takeIf { it in OsApps.builtinKeys }?.let { OsEntry(BUILTIN, key = it) }
            LINUX -> {
                val cmd = e.optString("command").trim()
                val pk = strings(e.optJSONArray("packages"))
                val dp = e.optJSONObject("distro_packages")?.let { o ->
                    o.keys().asSequence().filter { DISTRO_ID.matches(it) }.associateWith { k -> strings(o.optJSONArray(k)).filter { DPKG.matches(it) } }
                        .filterValues { it.isNotEmpty() }
                } ?: emptyMap()
                if (!COMMAND.matches(cmd) || pk.any { !PKG.matches(it) }) null else OsEntry(LINUX, command = cmd, packages = pk, distroPackages = dp)
            }
            else -> null
        }
    }

    /** A relative path inside a package: no "..", not absolute, no backslashes, no scheme. */
    fun safePath(p: String): Boolean = p.isNotEmpty() && p.length <= 200 && !p.startsWith("/") && !p.contains('\\') &&
        !p.contains(':') && p.split('/').none { it == ".." || it == "." || it.isEmpty() }

    fun httpsUrl(u: String): Boolean = Regex("^https://[A-Za-z0-9.-]+(:\\d{1,5})?(/[^\\s]*)?$").matches(u)

    private fun color(s: String?): String = OsIcons.parseColor(s)?.let { OsIcons.hex(it) } ?: ""

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
    }

    fun toJson(m: OsManifestData): JSONObject = JSONObject()
        .put("id", m.id).put("name", m.name).put("version", m.version).put("versionCode", m.versionCode)
        .put("icon", if (m.icon.file.isNotEmpty()) m.icon.file as Any else JSONObject().put("glyph", m.icon.glyph).put("from", m.icon.from).put("to", m.icon.to))
        .put("entry", JSONObject().put("type", m.entry.type).apply {
            if (m.entry.path.isNotEmpty()) put("path", m.entry.path)
            if (m.entry.url.isNotEmpty()) put("url", m.entry.url)
            if (m.entry.key.isNotEmpty()) put("key", m.entry.key)
            if (m.entry.command.isNotEmpty()) put("command", m.entry.command)
            if (m.entry.packages.isNotEmpty()) put("packages", JSONArray(m.entry.packages))
            if (m.entry.distroPackages.isNotEmpty()) put("distro_packages", JSONObject().apply { m.entry.distroPackages.forEach { (k, v) -> put(k, JSONArray(v)) } })
        })
        .put("permissions", JSONArray(m.permissions))
        .put("description", m.description).put("author", m.author).put("category", m.category)
        .apply { if (m.themeColor.isNotEmpty()) put("theme_color", m.themeColor); if (m.backgroundColor.isNotEmpty()) put("background_color", m.backgroundColor)
            if (m.scope.isNotEmpty()) put("scope", m.scope) }

    /** -1, 0, 1 by dotted numbers ("1.10" > "1.9"). */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d.coerceIn(-1, 1)
        }
        return 0
    }

    /** The App Store catalog. Bad entries are skipped (and listed in [OsCatalog.errors]); duplicate ids keep the first. */
    fun catalog(text: String): OsCatalog {
        val o = try { JSONObject(text) } catch (_: Exception) { return OsCatalog(0, emptyList(), errors = listOf("Not valid JSON")) }
        val arr = o.optJSONArray("apps") ?: JSONArray()
        val out = ArrayList<OsListing>(); val errs = ArrayList<String>(); val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            when (val p = parse(a)) {
                is Parsed.Bad -> errs += "#$i: ${p.why}"
                is Parsed.Ok -> {
                    val pkg = a.optString("package").trim()
                    val needsPkg = packaged(p.m.entry)
                    val pkgOk = pkg.isEmpty() && !needsPkg || pkg.startsWith("asset:") && safePath(pkg.removePrefix("asset:")) || httpsUrl(pkg)
                    if (!pkgOk) errs += "#$i: bad package"
                    else if (!seen.add(p.m.id)) errs += "#$i: duplicate ${p.m.id}"
                    else out += OsListing(p.m, pkg, a.optLong("size", 0L).coerceAtLeast(0L))
                }
            }
        }
        val remote = o.optString("remote").trim().takeIf { httpsUrl(it) }.orEmpty()
        return OsCatalog(o.optInt("version", 1), out, remote, errs)
    }
}
