package com.blaze.assistant.appstore

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * VirusTotal API v3 hash lookup (GET /api/v3/files/{sha256}, header x-apikey). Only the hash leaves the phone, never
 * the file. The key: the user's own (Store › VirusTotal key, saved on the phone) else BuildConfig.VT_API_KEY (empty in
 * public builds). No key = [VtVerdict.NoKey], shown as "not scanned". Parsing is pure; StoreInstallTest.
 */
object VirusTotal {
    const val BASE = "https://www.virustotal.com/api/v3/files/"

    fun interface Http { fun get(url: String, key: String): Pair<Int, String> }

    fun lookup(sha256: String, key: String?, http: Http = real): VtVerdict {
        if (key.isNullOrBlank()) return VtVerdict.NoKey
        if (!Regex("^[0-9a-fA-F]{64}$").matches(sha256)) return VtVerdict.Error("bad hash")
        val (code, body) = try { http.get(BASE + sha256.lowercase(), key.trim()) } catch (e: Exception) { return VtVerdict.Error(e.message ?: "offline") }
        return parse(code, body)
    }

    fun parse(code: Int, body: String): VtVerdict = when (code) {
        200 -> try {
            val s = JSONObject(body).getJSONObject("data").getJSONObject("attributes").getJSONObject("last_analysis_stats")
            val mal = s.optInt("malicious"); val sus = s.optInt("suspicious"); val harm = s.optInt("harmless"); val und = s.optInt("undetected")
            val total = mal + sus + harm + und
            when {
                mal > 0 -> VtVerdict.Malicious(mal, total)
                sus > 0 -> VtVerdict.Suspicious(sus, total)
                total == 0 -> VtVerdict.Error("no results yet")
                else -> VtVerdict.Clean(harm, und)
            }
        } catch (e: Exception) { VtVerdict.Error("unreadable answer") }
        404 -> VtVerdict.Unknown
        401, 403 -> VtVerdict.Error("the VirusTotal key was refused")
        429 -> VtVerdict.Error("VirusTotal's free quota is used up for now")
        else -> VtVerdict.Error("HTTP $code")
    }

    val real = Http { url, key ->
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("x-apikey", key); c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        code to body
    }
}
