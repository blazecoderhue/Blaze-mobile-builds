package com.blaze.assistant.update

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * v0.45.4: where updates come from. One file on the Blaze site, nothing else:
 * https://blazeinchue.pages.dev/versions.json = {schema:1, android:{version, url, sha256, notes}, desktop…, cli…}.
 * The APK is downloaded only from android.url (it redirects to the release registry) and must match android.sha256.
 * Pure JVM (no Android), tested in VersionFeedTest.
 */
object VersionFeed {
    const val URL = "https://blazeinchue.pages.dev/versions.json"
    const val SCHEMA = 1
    const val DAY_MS = 24 * 3600_000L

    data class Android(val version: String, val url: String, val sha256: String, val notes: String)

    /** The android entry, or null when the file is not something we understand (wrong schema, missing fields, bad hash). */
    fun parse(json: String): Android? = try {
        val o = JSONObject(json)
        if (o.optInt("schema", -1) != SCHEMA) null
        else o.optJSONObject("android")?.let { a ->
            val v = clean(a.optString("version"))
            val url = a.optString("url").trim()
            val sha = a.optString("sha256").trim().lowercase()
            if (parts(v).isEmpty() || !url.startsWith("https://") || !Regex("^[0-9a-f]{64}$").matches(sha)) null
            else Android(v, url, sha, a.optString("notes").trim())
        }
    } catch (_: Exception) { null }

    /** "v0.45.4" → "0.45.4" (a leading v in any case is ignored). */
    fun clean(v: String): String = v.trim().removePrefix("v").removePrefix("V")

    private fun parts(v: String): List<Int> {
        val core = clean(v).substringBefore('-').substringBefore('+')
        if (!Regex("""^\d+(\.\d+)*$""").matches(core)) return emptyList()
        return core.split('.').map { it.toIntOrNull() ?: 0 }
    }

    /** Semver-style compare on the numeric core (missing parts count as 0; a leading v is ignored). */
    fun compare(a: String, b: String): Int {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun newer(candidate: String, installed: String): Boolean = parts(candidate).isNotEmpty() && compare(candidate, installed) > 0

    /** The only place the APK is downloaded from. */
    fun sources(a: Android): List<String> = listOf(a.url)

    /** Every host the update path talks to (for the no-GitHub check). */
    fun hosts(a: Android?): List<String> = (listOf(URL) + (a?.let { sources(it) } ?: emptyList())).mapNotNull {
        try { URI(it).host?.lowercase() } catch (_: Exception) { null }
    }

    /** True when a quiet check is due: never checked, a day has passed, or the clock went backwards. */
    fun due(now: Long, last: Long): Boolean = last <= 0L || now < last || now - last >= DAY_MS

    /** The banner line, or null when there is nothing to show (not newer, or this version was dismissed). */
    fun banner(a: Android?, installed: String, dismissed: String): String? {
        if (a == null || !newer(a.version, installed)) return null
        if (dismissed.isNotBlank() && compare(dismissed, a.version) == 0) return null
        return "Blaze ${a.version} is available"
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp -> val b = ByteArray(64 * 1024); while (true) { val n = inp.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    const val SHA_MISMATCH = "The downloaded file didn't match the Blaze site's fingerprint, so I deleted it and didn't install it. Tap Retry to download it again."
}
