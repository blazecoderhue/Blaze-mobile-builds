package com.blaze.assistant.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finds which chat models the saved key can actually use.
 * 1. Lists the models the key can see (Gemini native list, or /models on other OpenAI-compatible services).
 * 2. Keeps chat models only (drops TTS, image, video, live, embedding, music...).
 * 3. Plan detection: sends each candidate a 1-word test. Models the key's plan blocks
 *    (404 not available, 403, or a quota of 0 on the free tier) are dropped.
 */
object ModelCatalog {

    data class Model(val id: String, val name: String, val note: String)
    data class Result(val plan: String, val models: List<Model>, val error: String? = null)

    private val NON_CHAT = listOf(
        "tts", "image", "imagen", "veo", "lyria", "live", "embedding", "embed", "transcribe",
        "robotics", "native-audio", "audio", "computer-use", "omni", "banana", "aqa",
        "deep-research", "antigravity", "learnlm", "whisper", "dall-e", "moderation", "davinci",
        "babbage", "realtime", "sora", "rerank", "guard", "nemoretriever", "ocr", "speech"
    )

    private fun isChat(id: String) = NON_CHAT.none { id.lowercase().contains(it) }

    private fun isGoogle(base: String) = base.contains("generativelanguage.googleapis.com")

    // ---- cache ----
    fun cached(): Result? {
        val raw = Settings.modelCache
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            if (o.optString("keyTag") != keyTag()) return null
            val arr = o.getJSONArray("models")
            Result(o.optString("plan"), (0 until arr.length()).map {
                val m = arr.getJSONObject(it); Model(m.getString("id"), m.getString("name"), m.optString("note"))
            })
        } catch (_: Exception) { null }
    }

    private fun save(r: Result) {
        val arr = JSONArray()
        r.models.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("note", it.note)) }
        Settings.modelCache = JSONObject().put("keyTag", keyTag()).put("plan", r.plan).put("models", arr).toString()
    }

    /** Tells cached results apart per key + service without storing the key itself. */
    private fun keyTag() = (Settings.apiKey + "|" + Settings.baseUrl).hashCode().toString()

    // ---- detection ----
    suspend fun detect(): Result = withContext(Dispatchers.IO) {
        val key = Settings.apiKey
        if (key.isBlank()) return@withContext Result("", emptyList(), "Add your API key first.")
        val base = Settings.baseUrl.trimEnd('/')
        if (base.isBlank()) return@withContext Result("", emptyList(), "Add the service address first.")
        val pid = Settings.providerId

        var listed: List<Model> = try {
            if (isGoogle(base)) listGemini(key) else listOpenAi(base, key)
        } catch (e: Exception) {
            return@withContext Result("", emptyList(), e.message ?: "Couldn't reach the AI service.")
        }
        if (listed.isEmpty()) return@withContext Result("", emptyList(), "This key can't see any chat models.")

        // Big catalogues: the list is already scoped to the key, so test-calling every model would burn quota.
        if (!isGoogle(base) && listed.size > 20) {
            var plan = "Key verified"
            if (pid == "openrouter") {
                val info = Quota.openRouterKey(key)
                val free = info?.optBoolean("is_free_tier", false) == true
                val remaining = info?.opt("limit_remaining")
                plan = if (free) "Free tier" else "Paid credits"
                if (free || remaining == 0 || remaining == 0.0) {
                    listed = listed.filter { it.id.endsWith(":free") }
                    plan = "Free models only"
                }
            }
            val sorted = listed.sortedWith(compareBy({ rank(it.id) }, { it.id }))
            val r = Result(plan, sorted)
            save(r)
            return@withContext r
        }

        val probes = coroutineScope {
            val gate = Semaphore(4)
            listed.map { m -> async { gate.withPermit { m to probe(base, key, m.id) } } }.awaitAll()
        }

        var sawFreeTier = false
        var proWorked = false
        val ok = mutableListOf<Model>()
        for ((m, p) in probes) {
            if (p.freeTier) sawFreeTier = true
            when (p.status) {
                Probe.OK -> { ok += m; if (m.id.contains("pro")) proWorked = true }
                Probe.BUSY -> ok += m.copy(note = "rate limited right now")
                Probe.BLOCKED -> {}
            }
        }
        val plan = when {
            !isGoogle(base) -> if (ok.size == listed.size) "All models allowed" else "Limited (${ok.size} of ${listed.size})"
            sawFreeTier && !proWorked -> "Free tier"
            proWorked || !sawFreeTier && ok.size == listed.size -> "Paid (billing on)"
            else -> "Free tier"
        }
        val sorted = ok.sortedWith(compareBy({ rank(it.id) }, { -version(it.id) }, { it.id }))
        val r = if (sorted.isEmpty()) Result(plan, emptyList(), "Your key's plan doesn't allow any chat model right now.")
                else Result(plan, sorted)
        if (sorted.isNotEmpty()) save(r)
        r
    }

    /** Stable before preview, then newest version first. */
    private fun rank(id: String) = when {
        id.contains("latest") -> 2
        id.contains("preview") || id.contains("exp") -> 1
        else -> 0
    }
    private fun version(id: String): Double =
        Regex("""(\d+(?:\.\d+)?)""").find(id)?.value?.toDoubleOrNull() ?: 0.0

    /** Chat models one provider offers to your key (for the "switch brain" picker). */
    suspend fun modelsFor(pid: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val p = Providers.byId(pid); val key = KeyRing.all(pid).firstOrNull() ?: return@withContext emptyList()
            val base = if (pid == Providers.CUSTOM) Settings.customBase else p.baseUrl
            (if (isGoogle(base)) listGemini(key) else listOpenAi(base.trimEnd('/'), key)).map { it.id }.filter { isChat(it) }.distinct().take(40)
        } catch (_: Exception) { emptyList() }
    }

    private fun listGemini(key: String): List<Model> {
        val out = mutableListOf<Model>()
        var token = ""
        do {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000" +
                (if (token.isNotEmpty()) "&pageToken=" + URLEncoder.encode(token, "UTF-8") else "")
            val (code, text) = http("GET", url, null, mapOf("x-goog-api-key" to key))
            if (code !in 200..299) throw Exception(errMsg(code, text))
            val o = JSONObject(text)
            val arr = o.optJSONArray("models") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val id = m.getString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (!id.startsWith("gemini") || id.contains("gemma") || !methods.contains("generateContent") || !isChat(id)) continue
                out += Model(id, m.optString("displayName", id), "")
            }
            token = o.optString("nextPageToken", "")
        } while (token.isNotEmpty())
        return out.distinctBy { it.id }
    }

    private fun listOpenAi(base: String, key: String): List<Model> {
        val (code, text) = http("GET", "$base/models?limit=1000", null, LlmClient.authHeaders(key))
        if (code !in 200..299) throw Exception(errMsg(code, text))
        val arr = JSONObject(text).optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { o ->
                val id = o.getString("id").removePrefix("models/")
                Model(id, o.optString("display_name", "").ifBlank { o.optString("name", "").ifBlank { id } }, "")
            }
            .filter { isChat(it.id) }.distinctBy { it.id }
    }

    private enum class Probe { OK, BUSY, BLOCKED }
    private data class ProbeResult(val status: Probe, val freeTier: Boolean)

    private fun probe(base: String, key: String, id: String): ProbeResult {
        val body = JSONObject().put("model", id).put("max_tokens", 16)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Say ok")))
        return try {
            val (code, text) = http("POST", "$base/chat/completions", body.toString(), LlmClient.authHeaders(key))
            val low = text.lowercase()
            val free = low.contains("free_tier")
            when {
                code in 200..299 -> ProbeResult(Probe.OK, free)
                code == 429 && (low.contains("limit: 0") || low.contains("\"limit\": 0") || low.contains("quota exceeded for metric") && low.contains("limit: 0")) ->
                    ProbeResult(Probe.BLOCKED, free)
                code == 429 -> ProbeResult(Probe.BUSY, free)
                // 400 can be a parameter complaint while the model itself is usable
                code == 400 && !low.contains("not found") && !low.contains("not supported") && !low.contains("not available") ->
                    ProbeResult(Probe.OK, free)
                else -> ProbeResult(Probe.BLOCKED, free)
            }
        } catch (_: Exception) { ProbeResult(Probe.BLOCKED, false) }
    }

    internal fun http(method: String, url: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val conn = com.blaze.assistant.connect.UrlSafety.open(url)
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 45_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    private fun errMsg(code: Int, text: String): String {
        val m = try {
            val o = JSONObject(text); val e = o.opt("error")
            when (e) {
                is JSONObject -> e.optString("message", text)
                is JSONArray -> e.optJSONObject(0)?.optJSONObject("error")?.optString("message") ?: text
                else -> text
            }
        } catch (_: Exception) { text }
        return when (code) {
            400, 401, 403 -> "That key was rejected. Check it in Settings. ($code)"
            else -> "The AI service said ($code): ${m.take(200)}"
        }
    }
}
