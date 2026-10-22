package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

/**
 * v0.23.4: every free model each provider offers, not just one default per provider.
 *
 * For each provider in the Auto chain Blaze lists the provider's models (OpenAI-compatible
 * `/models`, Gemini's models API, OpenRouter filtered to `:free` / zero price, Pollinations'
 * public list), keeps chat-capable text models only, marks which can call tools, and orders them:
 * the curated defaults first, then the rest by size/quality. Lists are cached in
 * `filesDir/model_catalog.json` for 24 h and refreshed in the background; offline or after a failed
 * fetch Blaze uses the last cache, then the defaults.
 */
object ProviderCatalog {

    data class Entry(val id: String, val tools: Boolean, val ctx: Int = 0)
    data class Listing(val pid: String, val at: Long, val models: List<Entry>, val keyTag: String = "", val error: String = "")

    const val TTL_MS = 24 * 3600_000L
    private const val RETRY_FAILED_MS = 30 * 60_000L

    @Volatile private var dir: File? = null
    private val mem = ConcurrentHashMap<String, Listing>()
    private val refreshing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val _changed = MutableStateFlow(0L)
    /** Bumps whenever a provider's list changes (Settings › Auto listens to it). */
    val changed: StateFlow<Long> = _changed

    /** Test hook: replaces the network fetch. Returns the raw body or throws. */
    @Volatile internal var fetcher: ((pid: String) -> String)? = null
    /** Test hook: no background refreshes. */
    @Volatile internal var autoRefresh = true

    fun init(filesDir: File) {
        dir = filesDir
        load()
    }

    // ---------------- curated defaults (preferred first) ----------------

    /**
     * Known-good free models per provider, best first. Used to order a fetched list (only the ones the
     * provider still lists survive) and as the fallback when nothing has been fetched yet.
     */
    fun defaults(pid: String): List<String> = when (pid) {
        "gemini" -> listOf(Settings.DEFAULT_MODEL, "gemini-flash-latest", "gemini-flash-lite-latest")
        "groq" -> listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct-0905",
            "qwen/qwen3-32b", "meta-llama/llama-4-maverick-17b-128e-instruct", "openai/gpt-oss-20b", "llama-3.1-8b-instant")
        "cerebras" -> listOf("gpt-oss-120b", "qwen-3-235b-a22b-instruct-2507", "zai-glm-4.6", "llama-3.3-70b", "llama3.1-8b")
        "nvidia" -> listOf("meta/llama-3.3-70b-instruct", "moonshotai/kimi-k2.6", "z-ai/glm-5.3", "nvidia/nemotron-3-super-120b-a12b",
            "deepseek-ai/deepseek-v4.1-flash", "openai/gpt-oss-120b", "openai/gpt-oss-20b")
        "mistral" -> listOf("mistral-small-latest", "mistral-medium-latest", "mistral-large-latest", "open-mistral-nemo", "ministral-8b-latest")
        "openrouter" -> (safe { FreeRouter.openRouterPicks() } ?: emptyList()) + "openrouter/free"
        "pollinations" -> listOf("openai-fast", "openai")
        else -> listOfNotNull(Providers.freeDefault[pid], Providers.byId(pid).defaultModel.ifBlank { null })
    }

    private inline fun <T> safe(f: () -> T): T? = try { f() } catch (_: Throwable) { null }

    // ---------------- filtering ----------------

    private val NON_CHAT = listOf(
        "embed", "tts", "whisper", "guard", "rerank", "image", "imagen", "flux", "stable-diffusion", "sdxl", "dall-e",
        "diffusion", "veo", "lyria", "native-audio", "audio", "speech", "transcribe", "parakeet", "canary", "riva-asr",
        "moderation", "safety", "nemoretriever", "retriever", "reward", "clip", "deplot", "kosmos", "fuyu", "neva", "vila",
        "ocr", "parse", "detector", "calibration", "arctic-embed", "bge-", "playai", "orpheus", "prompt-guard", "shieldgemma",
        "aqa", "live", "computer-use", "deep-research", "antigravity", "sora", "realtime", "davinci", "babbage", "robotics",
        "topic-control", "translate", "omni", "banana", "learnlm", "starcoder", "codegemma", "codellama", "-base", "cosmos",
    )

    /** Chat-capable text model? Drops embeddings, TTS, Whisper, guard, image, rerank and the like. */
    fun isChatText(id: String): Boolean {
        val l = id.lowercase()
        return l.isNotBlank() && NON_CHAT.none { l.contains(it) }
    }

    private val KNOWN_TOOLS = Regex(
        """(?i)(llama-?3\.[13]|llama3\.[13]|llama-?4|qwen-?3|qwen2\.5|qwq|gpt-oss|kimi|deepseek-(v[3-9]|chat)|glm-?[4-9]|""" +
            """mistral-(small|medium|large)|mixtral|magistral|ministral|open-mistral-nemo|devstral|command-[ra]|nemotron-(3|4|ultra|super)|""" +
            """llama-3\.1-nemotron|hermes|granite-?[34]|gemini|grok|compound|minimax|seed-oss|jamba|palmyra|openai-fast|^openai$)""")

    /** Tool calling from the known list (used when the provider's API doesn't say). */
    fun knownTools(id: String): Boolean = KNOWN_TOOLS.containsMatchIn(id) && !id.contains("gemma", true)

    /** Parses one provider's models response into chat-capable entries (unordered). */
    fun parse(pid: String, body: String, now: Long = System.currentTimeMillis()): List<Entry> {
        val out = mutableListOf<Entry>()
        val t = body.trim()
        if (pid == "pollinations") {
            val a = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("data") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i)
                val id = o?.optString("name").orEmpty().ifBlank { a.optString(i) }
                if (o != null && (o.optBoolean("audio") || (o.optJSONArray("output_modalities")?.toString()?.let { "\"text\"" !in it } == true))) continue
                if (!isChatText(id)) continue
                out += Entry(id, o?.optBoolean("tools", knownTools(id)) ?: knownTools(id))
            }
            return out.distinctBy { it.id }
        }
        if (pid == "gemini") {
            val a = JSONObject(t).optJSONArray("models") ?: JSONArray()
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                val id = m.optString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (!(id.startsWith("gemini") || id.startsWith("gemma")) || !methods.contains("generateContent") || !isChatText(id)) continue
                if (id.contains("embedding") || id.contains("-tts")) continue
                out += Entry(id, id.startsWith("gemini"), m.optInt("inputTokenLimit"))
            }
            return out.distinctBy { it.id }
        }
        val a = JSONObject(t).optJSONArray("data") ?: JSONArray()
        for (i in 0 until a.length()) {
            val m = a.optJSONObject(i) ?: continue
            val id = m.optString("id").removePrefix("models/")
            if (!isChatText(id)) continue
            if (m.has("active") && !m.optBoolean("active", true)) continue                      // Groq: retired
            var tools = knownTools(id)
            when (pid) {
                "openrouter" -> {
                    val pr = m.optJSONObject("pricing")
                    val free = id.endsWith(":free") || (pr != null && pr.optString("prompt") == "0" && pr.optString("completion") == "0")
                    if (!free) continue
                    val outMods = m.optJSONObject("architecture")?.optJSONArray("output_modalities")?.toString() ?: "[\"text\"]"
                    if (!outMods.contains("\"text\"") || outMods.contains("\"image\"") || outMods.contains("\"audio\"")) continue
                    val exp = m.optString("expiration_date", "")
                    if (exp.isNotBlank() && exp != "null" && expired(exp, now)) continue
                    val params = m.optJSONArray("supported_parameters")
                    if (params != null) tools = params.toString().contains("\"tools\"")
                }
                "mistral" -> {
                    val cap = m.optJSONObject("capabilities")
                    if (cap != null) {
                        if (!cap.optBoolean("completion_chat", true)) continue
                        tools = cap.optBoolean("function_calling", tools)
                    }
                    val dep = m.optString("deprecation", "")
                    if (dep.isNotBlank() && dep != "null" && expired(dep, now)) continue
                }
            }
            val ctx = m.optInt("context_length", m.optInt("context_window", m.optInt("max_context_length")))
            out += Entry(id, tools, ctx)
        }
        return out.distinctBy { it.id }
    }

    private fun expired(date: String, now: Long): Boolean = try {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(date.take(10))
        d != null && d.time < now
    } catch (_: Exception) { false }

    // ---------------- ordering ----------------

    private val strongFamilies = listOf("kimi", "deepseek", "qwen3", "qwen-3", "glm", "gpt-oss", "nemotron-3-ultra", "nemotron-ultra",
        "llama-4", "maverick", "gemini", "mistral-large", "mistral-medium", "nemotron-3-super", "llama-3.3", "llama3.3", "hermes", "grok")
    private val weakWords = Regex("""(?i)(nano|tiny|micro|(?<!ge)mini|small|lite|lightning|instant|-xs\b|code|chatqa|llama2|gemma-2b|recurrent|(?<![0-9.])[1-4]b\b|(?<![0-9.])[78]b\b|e2b|e4b|preview|-exp\b)""")

    /** Rough "billions of parameters" in the id (first number before b; 0 when unknown). */
    fun sizeB(id: String): Double =
        Regex("""(?i)(\d+(?:\.\d+)?)b(?![a-z])""").findAll(id).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull() ?: 0.0

    /** Higher is better: family strength, size, minus "small/coding/preview" words. */
    fun score(id: String): Double {
        val l = id.lowercase()
        var s = 0.0
        val fam = strongFamilies.indexOfFirst { l.contains(it) }
        if (fam >= 0) s += 20.0 + (strongFamilies.size - fam) * 0.2
        val b = sizeB(l)
        s += if (b > 0) ln(b + 1) * 3 else 6.0
        weakWords.findAll(l).forEach { _ -> s -= 6.0 }
        if (l.contains("latest")) s += 1.0
        return s
    }

    /** Curated defaults first (in their order), then the rest by [score], best first. */
    fun order(entries: List<Entry>, preferred: List<String>): List<Entry> {
        val byId = entries.associateBy { it.id }
        val head = preferred.distinct().mapNotNull { byId[it] }
        val rest = entries.filter { e -> head.none { it.id == e.id } }
            .sortedWith(compareByDescending<Entry> { score(it.id) }.thenBy { it.id })
        return head + rest
    }

    // ---------------- lookup ----------------

    /** The last fetched list for [pid] (null when never fetched). */
    fun listing(pid: String): Listing? = mem[pid]

    /**
     * Ordered candidates for [pid]: [pinned] (the user's own chain picks, always kept) and the
     * curated defaults, then everything else the provider lists. Starts a background refresh when
     * the cache is stale. Never blocks on the network.
     */
    fun models(pid: String, pinned: List<String> = emptyList()): List<Entry> {
        val l = mem[pid]
        if (autoRefresh && needsRefresh(pid)) refreshAsync(pid)
        val prefs = pinned + defaults(pid)
        val listed = l?.models.orEmpty()
        if (listed.isEmpty()) {
            // Nothing fetched yet (or offline since install): the user's picks and the defaults.
            return prefs.filter { it.isNotBlank() }.distinct().map { Entry(it, knownTools(it) || it in pinned || it == "openrouter/free") }
        }
        val pinnedEntries = pinned.filter { it.isNotBlank() && listed.none { e -> e.id == it } }.map { Entry(it, true) }
        val ordered = order(listed, prefs)
        // The user's own picks lead even if the provider no longer lists them (a 404 then cools them).
        return (pinned.mapNotNull { p -> ordered.firstOrNull { it.id == p } ?: pinnedEntries.firstOrNull { it.id == p } } + ordered).distinctBy { it.id }
    }

    private fun keyTag(pid: String): String = safe { Providers.keyOf(pid).hashCode().toString() } ?: ""

    fun needsRefresh(pid: String, now: Long = System.currentTimeMillis()): Boolean {
        val l = mem[pid] ?: return true
        if (l.models.isEmpty()) return now - l.at > RETRY_FAILED_MS
        if (pid != "pollinations" && pid != "openrouter" && l.keyTag.isNotBlank() && l.keyTag != keyTag(pid)) return true
        return now - l.at > TTL_MS
    }

    fun refreshAsync(pid: String) {
        if (!refreshing.add(pid)) return
        try { Thread { try { refresh(pid) } finally { refreshing.remove(pid) } }.apply { isDaemon = true }.start() }
        catch (_: Throwable) { refreshing.remove(pid) }
    }

    /** Fetches [pid]'s list now (blocking). On failure the old list stays; the error is recorded. */
    fun refresh(pid: String): Listing {
        val now = System.currentTimeMillis()
        val old = mem[pid]
        val l = try {
            val body = (fetcher ?: ::fetch)(pid)
            val models = parse(pid, body, now)
            if (models.isEmpty()) throw Exception("no chat models listed")
            Listing(pid, now, models, keyTag(pid))
        } catch (e: Exception) {
            // Keep the last good list; remember the failure so we retry in a while, not every turn.
            Listing(pid, if (old?.models.isNullOrEmpty()) now else old!!.at, old?.models.orEmpty(), old?.keyTag.orEmpty(),
                (e.message ?: "fetch failed").take(120))
        }
        mem[pid] = l
        save(); _changed.value = System.currentTimeMillis()
        return l
    }

    /** Refreshes every provider in [pids] (Settings › Auto "Refresh models"). Blocking; call off the main thread. */
    fun refreshAll(pids: List<String>): Map<String, Listing> = pids.distinct().associateWith { refresh(it) }

    private fun fetch(pid: String): String {
        val p = Providers.byId(pid)
        return when (pid) {
            "pollinations" -> get("https://text.pollinations.ai/models", emptyMap())
            "openrouter" -> get("https://openrouter.ai/api/v1/models", emptyMap())   // public list, no key needed
            "gemini" -> {
                val key = Providers.keyOf(pid).ifBlank { throw Exception("no key") }
                // One page of 1000 covers the whole Gemini catalogue.
                get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", mapOf("x-goog-api-key" to key))
            }
            else -> {
                val key = Providers.keyOf(pid).ifBlank { throw Exception("no key") }
                val base = (if (pid == Providers.CUSTOM) Settings.customBase else p.baseUrl).trimEnd('/')
                get("$base/models", LlmClient.authHeaders(key, pid))
            }
        }
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val c = com.blaze.assistant.connect.UrlSafety.open(url)
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw Exception("HTTP $code")
        return text
    }

    @Suppress("unused") private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------- cache file ----------------

    private fun file(): File? = dir?.let { File(it, "model_catalog.json") }

    private fun load() {
        val f = file() ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            o.keys().forEach { pid ->
                val p = o.getJSONObject(pid)
                val a = p.optJSONArray("models") ?: JSONArray()
                mem[pid] = Listing(pid, p.optLong("at"), (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i); Entry(m.getString("id"), m.optBoolean("tools"), m.optInt("ctx")) },
                    p.optString("keyTag"), p.optString("error"))
            }
        } catch (_: Exception) { }
    }

    @Synchronized private fun save() {
        val f = file() ?: return
        try {
            val o = JSONObject()
            mem.values.forEach { l ->
                o.put(l.pid, JSONObject().put("at", l.at).put("keyTag", l.keyTag).put("error", l.error)
                    .put("models", JSONArray(l.models.map { JSONObject().put("id", it.id).put("tools", it.tools).put("ctx", it.ctx) })))
            }
            val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
        } catch (_: Exception) { }
    }

    /** Test helper: puts a list in place without touching the network. */
    internal fun put(pid: String, models: List<Entry>, at: Long = System.currentTimeMillis()) {
        mem[pid] = Listing(pid, at, models, keyTag(pid)); _changed.value = System.currentTimeMillis()
    }

    internal fun clearForTests() { mem.clear(); refreshing.clear(); dir = null; fetcher = null; autoRefresh = false }
}
