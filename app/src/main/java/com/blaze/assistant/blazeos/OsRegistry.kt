package com.blaze.assistant.blazeos

import org.json.JSONArray
import org.json.JSONObject

/*
 * v0.33.2 BlazeOS installed-apps registry and per-app permissions: pure rules + JSON, unit tested in OsRegistryTest.
 * The registry lives on the BlazeOS disk as system/registry.json, the permissions as system/permissions.json
 * (OsDisk.kt reads and writes them).
 */

/** One installed app: its manifest, when it was installed, and how much of the disk its package takes. */
data class OsInstalled(val manifest: OsManifestData, val installedAt: Long, val size: Long, val preinstalled: Boolean = false) {
    val id: String get() = manifest.id
    val appId: String get() = OsApps.pkgId(manifest.id)
}

data class OsRegistry(val apps: List<OsInstalled> = emptyList()) {
    fun get(id: String): OsInstalled? = apps.firstOrNull { it.id == id }
    fun has(id: String) = get(id) != null
    val used: Long get() = apps.sumOf { it.size }

    /** What installing [m] would be: a fresh install, an update, the same version again, or an older one. */
    fun kindOf(m: OsManifestData): String {
        val cur = get(m.id) ?: return INSTALL
        return when {
            m.versionCode > cur.manifest.versionCode -> UPDATE
            m.versionCode == cur.manifest.versionCode -> SAME
            else -> OLDER
        }
    }

    sealed class Result {
        data class Ok(val reg: OsRegistry) : Result()
        data class Refused(val why: String) : Result()
    }

    /**
     * Adds (or updates) an app of [size] bytes. Refused when it would not fit in [quota] with [diskUsed] already on the
     * disk (an update only needs the difference), or when it is an older version than the one installed.
     */
    fun install(m: OsManifestData, size: Long, quota: Long, diskUsed: Long, now: Long, preinstalled: Boolean = false): Result {
        val cur = get(m.id)
        if (cur != null && m.versionCode < cur.manifest.versionCode) return Result.Refused("A newer version of ${m.name} is already installed")
        val need = size - (cur?.size ?: 0L)
        if (need > 0 && diskUsed + need > quota) return Result.Refused("Not enough space on the BlazeOS disk (needs ${OsResources.size(need)}, " +
            "${OsResources.size((quota - diskUsed).coerceAtLeast(0))} free)")
        val entry = OsInstalled(m, cur?.installedAt ?: now, size, preinstalled || cur?.preinstalled == true)
        return Result.Ok(OsRegistry(apps.filter { it.id != m.id } + entry))
    }

    fun uninstall(id: String): OsRegistry = OsRegistry(apps.filter { it.id != id })

    fun toJson(): String = JSONObject().put("version", 1).put("apps", JSONArray().apply {
        for (a in apps) put(JSONObject().put("manifest", OsManifest.toJson(a.manifest)).put("installedAt", a.installedAt)
            .put("size", a.size).put("preinstalled", a.preinstalled))
    }).toString()

    companion object {
        const val INSTALL = "install"
        const val UPDATE = "update"
        const val SAME = "same"
        const val OLDER = "older"

        /** Reads registry.json; unreadable entries are dropped (their files are cleaned up by the next disk scan). */
        fun fromJson(text: String?): OsRegistry {
            if (text.isNullOrBlank()) return OsRegistry()
            return try {
                val arr = JSONObject(text).optJSONArray("apps") ?: JSONArray()
                OsRegistry((0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val m = (OsManifest.parse(o.optJSONObject("manifest") ?: JSONObject()) as? OsManifest.Parsed.Ok)?.m ?: return@mapNotNull null
                    OsInstalled(m, o.optLong("installedAt"), o.optLong("size").coerceAtLeast(0), o.optBoolean("preinstalled"))
                }.distinctBy { it.id })
            } catch (_: Exception) { OsRegistry() }
        }
    }
}

/** BlazeOS permissions an app can ask for. Each app has its own answers; the host phone's permission is asked after. */
object OsPerms {
    const val CAMERA = "camera"
    const val MIC = "mic"
    const val STORAGE = "storage"
    const val NOTIFICATIONS = "notifications"
    val all = listOf(CAMERA, MIC, STORAGE, NOTIFICATIONS)

    fun label(p: String) = when (p) { CAMERA -> "Camera"; MIC -> "Microphone"; STORAGE -> "Files and photos"; NOTIFICATIONS -> "Notifications"; else -> p }
    fun question(app: String, p: String) = when (p) {
        CAMERA -> "Allow $app to take pictures and record video?"
        MIC -> "Allow $app to record audio?"
        STORAGE -> "Allow $app to see the files and photos on BlazeOS?"
        NOTIFICATIONS -> "Allow $app to send you notifications?"
        else -> "Allow $app to use $p?"
    }

    /** The host (Android) permission behind a BlazeOS one; null = nothing to ask the host (BlazeOS's own disk / banners). */
    fun hostPermission(p: String): String? = when (p) { CAMERA -> "android.permission.CAMERA"; MIC -> "android.permission.RECORD_AUDIO"; else -> null }

    /** What the built-in apps need before they open. */
    fun forBuiltin(key: String): List<String> = when (key) { "camera" -> listOf(CAMERA); "photos", "files" -> listOf(STORAGE); else -> emptyList() }
}

/** An app's answer: "allow" / "deny", or none yet (ask). "Only this time" is never stored. */
data class OsGrants(val map: Map<String, Map<String, String>> = emptyMap()) {
    enum class Decision { ALLOWED, DENIED, ASK }

    fun decide(app: String, perm: String): Decision = when (map[app]?.get(perm)) {
        ALLOW -> Decision.ALLOWED
        DENY -> Decision.DENIED
        else -> Decision.ASK
    }

    fun set(app: String, perm: String, allow: Boolean): OsGrants {
        if (perm !in OsPerms.all) return this
        val m = map.toMutableMap()
        m[app] = (m[app].orEmpty()) + (perm to if (allow) ALLOW else DENY)
        return OsGrants(m)
    }

    /** Back to "ask" (Settings › Apps › Reset permissions). */
    fun reset(app: String, perm: String): OsGrants {
        val cur = map[app] ?: return this
        val left = cur - perm
        return OsGrants(if (left.isEmpty()) map - app else map + (app to left))
    }

    /** Uninstall forgets the app's answers. */
    fun forget(app: String): OsGrants = OsGrants(map - app)

    fun toJson(): String = JSONObject().apply { for ((a, ps) in map) put(a, JSONObject().apply { for ((p, v) in ps) put(p, v) }) }.toString()

    companion object {
        const val ALLOW = "allow"
        const val DENY = "deny"

        fun fromJson(text: String?): OsGrants {
            if (text.isNullOrBlank()) return OsGrants()
            return try {
                val o = JSONObject(text)
                val out = HashMap<String, Map<String, String>>()
                for (a in o.keys()) {
                    val ps = o.optJSONObject(a) ?: continue
                    val m = HashMap<String, String>()
                    for (p in ps.keys()) { val v = ps.optString(p); if (p in OsPerms.all && (v == ALLOW || v == DENY)) m[p] = v }
                    if (m.isNotEmpty()) out[a] = m
                }
                OsGrants(out)
            } catch (_: Exception) { OsGrants() }
        }
    }
}
