package com.blaze.store.platform

import com.blaze.store.data.HttpError
import com.blaze.store.data.Net
import com.blaze.store.data.VtStats
import com.blaze.store.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Outcome of the pre-install VirusTotal check. Only [CLEAN] lets an install go ahead. */
enum class VtGate { CLEAN, FLAGGED, NO_KEY, PENDING, ERROR }

data class VtCheck(val gate: VtGate, val sha256: String? = null, val stats: VtStats? = null, val message: String = "", val permalink: String? = null) {
    val allowsInstall get() = gate == VtGate.CLEAN
}

/** Transport for [VtGuard]: the default talks to VirusTotal v3; unit tests swap in a fake. Each call returns (http status, body). */
interface VtApi {
    suspend fun report(sha256: String, key: String): Pair<Int, String>
    suspend fun upload(file: File, key: String): Pair<Int, String>
    suspend fun analysis(id: String, key: String): Pair<Int, String>
}

/**
 * Blaze Store checks every APK with VirusTotal before it installs it: the UI installer, background auto-updates and the Blaze agent API
 * all go through [check]. The file's SHA-256 is looked up first; only when VirusTotal has never seen it is the APK uploaded and analysed.
 */
object VtGuard {
    const val MALICIOUS_BLOCK = 3
    const val COMBINED_BLOCK = 5
    const val NO_KEY_MESSAGE = "Add your free VirusTotal API key in Blaze Store (any app page, VirusTotal) so it can check apps before installing them."

    /** Flagged when 3+ engines call it malicious, or 5+ call it malicious or suspicious. A lone heuristic hit stays clean. */
    fun decide(s: VtStats) = if (s.malicious >= MALICIOUS_BLOCK || s.malicious + s.suspicious >= COMBINED_BLOCK) VtGate.FLAGGED else VtGate.CLEAN

    fun link(hash: String) = "https://www.virustotal.com/gui/file/$hash"

    fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
        f.inputStream().use { s -> while (true) { val n = s.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parse(b: String): JsonObject? = runCatching { json.parseToJsonElement(b).jsonObject }.getOrNull()
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun stats(o: JsonObject) = VtStats(o.int("harmless"), o.int("malicious"), o.int("suspicious"), o.int("undetected"), o.int("timeout"))

    fun apiMessage(status: Int) = when {
        status == 401 || status == 403 -> "VirusTotal rejected the API key. Check it in Blaze Store."
        status == 429 -> "VirusTotal quota reached (free keys allow 4 lookups a minute). Try again shortly."
        status >= 500 -> "VirusTotal is having trouble right now. Try again later."
        else -> "VirusTotal check failed ($status)."
    }

    private fun verdict(hash: String, s: VtStats): VtCheck {
        val g = decide(s)
        val msg = if (g == VtGate.FLAGGED) "VirusTotal flagged this APK: ${s.malicious} malicious, ${s.suspicious} suspicious. It was not installed." else "VirusTotal: no threat found."
        return VtCheck(g, hash, s, msg, link(hash))
    }

    suspend fun check(file: File, key: String, api: VtApi = HttpVtApi, polls: Int = 18, pollDelayMs: Long = 10_000): VtCheck {
        val k = key.trim()
        if (k.isEmpty()) return VtCheck(VtGate.NO_KEY, message = NO_KEY_MESSAGE)
        if (!file.exists() || file.length() == 0L) return VtCheck(VtGate.ERROR, message = "The APK to check is missing.")
        val hash = withContext(Dispatchers.IO) { sha256(file) }
        return try {
            val (code, body) = api.report(hash, k)
            when {
                code in 200..299 -> verdict(hash, stats(parse(body)?.obj("data")?.obj("attributes")?.obj("last_analysis_stats") ?: JsonObject(emptyMap())))
                code == 404 -> {
                    val (uc, ub) = api.upload(file, k)
                    if (uc !in 200..299) return VtCheck(VtGate.ERROR, hash, message = apiMessage(uc))
                    val id = parse(ub)?.obj("data")?.str("id") ?: return VtCheck(VtGate.ERROR, hash, message = "VirusTotal did not return an analysis id.")
                    repeat(polls) { i ->
                        delay(if (i < 3) minOf(pollDelayMs, 5_000) else pollDelayMs)
                        val (ac, ab) = api.analysis(id, k)
                        if (ac !in 200..299) return VtCheck(VtGate.ERROR, hash, message = apiMessage(ac))
                        val a = parse(ab)?.obj("data")?.obj("attributes")
                        if (a?.str("status") == "completed") return verdict(hash, stats(a.obj("stats")))
                    }
                    VtCheck(VtGate.PENDING, hash, message = "VirusTotal is still analysing this APK. Try again in a minute.", permalink = link(hash))
                }
                else -> VtCheck(VtGate.ERROR, hash, message = apiMessage(code))
            }
        } catch (e: Exception) {
            VtCheck(VtGate.ERROR, hash, message = e.message ?: "VirusTotal check failed.")
        }
    }
}

/** VirusTotal v3 over the store's OkHttp client. */
object HttpVtApi : VtApi {
    private const val VT = "https://www.virustotal.com/api/v3"
    private val slow by lazy { Net.client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(0, TimeUnit.MILLISECONDS).build() }

    private suspend fun get(url: String, key: String): Pair<Int, String> =
        try { 200 to Net.text(url, mapOf("x-apikey" to key, "Accept" to "application/json"), c = slow) } catch (e: HttpError) { e.code to e.body }

    override suspend fun report(sha256: String, key: String) = get("$VT/files/$sha256", key)
    override suspend fun analysis(id: String, key: String) = get("$VT/analyses/$id", key)

    override suspend fun upload(file: File, key: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        var url = "$VT/files"
        if (file.length() >= 32L * 1024 * 1024) {
            val (c, b) = get("$VT/files/upload_url", key)
            if (c !in 200..299) return@withContext c to b
            url = runCatching { json.parseToJsonElement(b).jsonObject["data"]!!.jsonPrimitive.content }.getOrNull() ?: return@withContext 502 to b
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("application/vnd.android.package-archive".toMediaType())).build()
        slow.newCall(Request.Builder().url(url).post(body).header("x-apikey", key).header("Accept", "application/json").build()).execute().use { it.code to it.body.string() }
    }
}
