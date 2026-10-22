package com.blaze.assistant.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Auto (free routing)": spreads turns across the free tiers the user has keys for.
 *
 * v0.23.4: every chat model each provider offers takes part ([ProviderCatalog]), not one default per
 * provider. Providers take turns (round-robin), each offering its next untried model; a model that
 * failed or hit a limit is remembered ([ModelMemory]) and not tried again until its cooldown passes,
 * and hard failures go to the back of their provider's queue. The tried set resets every turn;
 * cooldowns persist. Tool turns use tool-capable models only. When everything is cooling Auto waits
 * once (≤ 25 s), then says when the next model frees up. Pollinations (keyless) is the last resort,
 * for plain chat only.
 */
object FreeRouter {

    data class Health(val fails: Int = 0, val okCount: Int = 0, val coolUntil: Long = 0, val avgMs: Long = 0,
                      val lastError: String = "", val noTools: Boolean = false)

    private val health = ConcurrentHashMap<String, Health>()
    private val _status = MutableStateFlow<Map<String, Health>>(emptyMap())
    val status: StateFlow<Map<String, Health>> = _status

    // ---- Smart OpenRouter free pick ----
    // "openrouter/free" hands each turn to a random free model, sometimes a tiny coding model that
    // can't even read the clock. Instead pick the strongest free models that support tools, once a day.
    private val weak = Regex("""(code|coder|codestral|devstral|mini|nano|tiny|small|lite|micro|guard|embed|vision|-vl|ocr|reward|math|\b[0-9]b\b|\b(1\.5|0\.5|1|2|3|4|7|8|9|12|14)b\b|e2b|e4b|uncensored|roleplay|rp\b)""", RegexOption.IGNORE_CASE)
    private val strong = listOf("deepseek", "qwen3", "kimi", "glm", "gpt-oss", "llama-4", "gemini", "mistral-large", "nemotron-ultra", "hermes", "maverick", "grok")
    @Volatile private var refreshing = false

    /** Best free OpenRouter models (tools-capable), cached; refreshed in the background daily. */
    fun openRouterPicks(): List<String> {
        val saved = Settings.str("or_free_best", "")
        val at = Settings.str("or_free_best_at", "0").toLongOrNull() ?: 0
        if (System.currentTimeMillis() - at > 24 * 3600_000L && !refreshing) {
            refreshing = true
            Thread {
                try {
                    val (code, body) = com.blaze.assistant.connect.Http.req("GET", "https://openrouter.ai/api/v1/models", timeoutMs = 20_000)
                    if (code in 200..299) {
                        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                        val c = (0 until data.length()).map { data.getJSONObject(it) }.filter { m ->
                            val id = m.optString("id")
                            val params = m.optJSONArray("supported_parameters")?.toString() ?: ""
                            id.endsWith(":free") && params.contains("\"tools\"") && !weak.containsMatchIn(id)
                        }.sortedWith(compareByDescending<JSONObject> { m -> strong.indexOfFirst { m.optString("id").contains(it, true) }.let { if (it < 0) 0 else strong.size - it } }
                            .thenByDescending { it.optInt("context_length") })
                        val top = c.take(3).map { it.optString("id") }
                        if (top.isNotEmpty()) { Settings.setStr("or_free_best", top.joinToString(",")); Settings.setStr("or_free_best_at", System.currentTimeMillis().toString()) }
                    }
                } catch (_: Exception) { } finally { refreshing = false }
            }.start()
        }
        return saved.split(',').filter { it.isNotBlank() }
    }

    /** Default free model per provider when the user hasn't picked one. */
    private fun defaultModel(pid: String) = when (pid) {
        "openrouter" -> openRouterPicks().firstOrNull() ?: "openrouter/free"
        "gemini" -> Settings.DEFAULT_MODEL
        else -> Providers.freeDefault[pid] ?: ""
    }

    /** The chain as saved ("pid|model" entries), or a default built from the keys that are set. */
    fun entries(): List<Pair<String, String>> {
        val saved = try {
            val a = JSONArray(Settings.freeChain)
            (0 until a.length()).map { a.getString(it).split('|', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } } }
        } catch (_: Exception) { emptyList() }.filter { it.first != "github" }   // GitHub Models retired Jul 2026
        val keyed = Providers.freeOrder.filter { Providers.keyOf(it).isNotBlank() }
            .map { it to Settings.modelFor(it).ifBlank { defaultModel(it) } }
            .filter { it.second.isNotBlank() }
        if (saved.isEmpty()) return keyed
        // A free key added after the chain was saved joins at the end, unless the user removed it.
        val removed = Settings.str("free_chain_removed", "").split(',').filter { it.isNotBlank() }.toSet()
        return saved + keyed.filter { k -> saved.none { it.first == k.first } && k.first !in removed }
    }

    /** The user removed [pid] from the chain: don't auto-add it back. */
    fun removed(pid: String) {
        val r = Settings.str("free_chain_removed", "").split(',').filter { it.isNotBlank() }.toMutableSet()
        r += pid; Settings.setStr("free_chain_removed", r.joinToString(","))
    }

    /** A key was just added for [pid] (sign-in or key catcher): let it join the chain again. */
    fun added(pid: String) {
        val r = Settings.str("free_chain_removed", "").split(',').filter { it.isNotBlank() && it != pid }
        Settings.setStr("free_chain_removed", r.joinToString(","))
    }

    fun save(list: List<Pair<String, String>>) {
        Settings.freeChain = JSONArray(list.map { "${it.first}|${it.second}" }).toString()
    }

    /** One-tap "free stack": every free-tier provider with a key, in the default order. */
    fun presetFreeStack() { Settings.freeChain = ""; Settings.setStr("free_chain_removed", ""); save(entries()) }

    // ---------------- v0.23.4: every model per provider ----------------

    /** At most this many models are called in one turn (cooling ones are skipped for free). */
    const val MAX_TRIES = 10
    /** A turn stops trying new models after this long (not counting the one short wait). */
    const val TURN_BUDGET_MS = 150_000L
    /** Auto waits quietly once for a model that frees up within this long. */
    const val WAIT_MAX_MS = 25_000L

    /** The chain's providers, in order, each with its full model list (the user's picks first). */
    fun pools(): List<Pair<String, List<RoutePlan.Cand>>> {
        val e = entries()
        return e.map { it.first }.distinct().map { pid ->
            val listed = ProviderCatalog.listing(pid)?.models.orEmpty()
            // "openrouter/free" hands each turn to a random free model and tiny coding models can't run Blaze:
            // the catalogue orders OpenRouter's free models instead (they stay in the list, further down).
            val pinned = e.filter { it.first == pid }.map { it.second }
                .filter { it.isNotBlank() && !(pid == "openrouter" && (it == "openrouter/free" || weak.containsMatchIn(it))) }
                // A built-in default the provider no longer lists (e.g. a retired NIM model) is dropped, not pinned.
                .filter { m -> listed.isEmpty() || listed.any { it.id == m } || !isDefault(pid, m) }
            pid to ProviderCatalog.models(pid, pinned).map { RoutePlan.Cand(pid, it.id, it.tools || it.id in pinned) }
        }
    }

    private fun isDefault(pid: String, m: String) =
        m == Providers.freeDefault[pid] || m == Providers.byId(pid).defaultModel || m in ProviderCatalog.defaults(pid)

    private fun keyOf(c: RoutePlan.Cand) = ModelMemory.key(c.pid, c.model)
    private fun nameOf(pid: String) = if (pid == Providers.keyless.id) "Keyless" else Providers.byId(pid).name.substringBefore(" (")

    /** The first couple of models a turn would try, for connection pre-warming. */
    fun warmTargets(): List<LlmClient.Target> = try {
        RoutePlan.plan(pools(), true, emptySet()).ready.take(2).map { LlmClient.target(it.pid, it.model) }
    } catch (_: Exception) { emptyList() }

    private fun mark(t: LlmClient.Target, f: (Health) -> Health) {
        val k = t.pid + "|" + t.model
        health[k] = f(health[k] ?: Health()); _status.value = HashMap(health)
    }

    suspend fun complete(messages: JSONArray, tools: JSONArray?, onText: ((String) -> Unit)?,
                         onThink: ((String) -> Unit)?, lane: LlmClient.Lane, waited: Boolean = false,
                         carried: Set<String> = emptySet()): JSONObject {
        val needTools = tools != null && tools.length() > 0
        var pools = pools().filter { it.second.isNotEmpty() }
        // Token meter: what this request costs, so a model without room is skipped before sending.
        val tokens = Budget.estimate(messages.toString().length + (tools?.toString()?.length ?: 0))
        val tried = carried.toMutableSet()          // this turn only; cooldowns persist in ModelMemory
        val hard = carried.toMutableSet()           // tried and failed for a reason waiting won't fix
        val skipPids = mutableSetOf<String>()       // unreachable / rejected key / provider-wide cap
        val offline = mutableSetOf<String>()
        val failures = mutableListOf<String>()
        val resting = mutableListOf<Triple<String, Long, String>>()   // name, ms until ready, why
        var tooLarge = 0; var otherErrors = 0
        var attempts = 0
        var fetchedMissing = false
        var lastErr: Exception? = null
        val started = System.currentTimeMillis()
        val firstPid = if (LlmClient.quickTurn && Settings.bool("fastest_first", true)) "groq" else null

        while (attempts < MAX_TRIES && System.currentTimeMillis() - started < TURN_BUDGET_MS) {
            val plan = RoutePlan.plan(pools.filter { it.first !in skipPids }, needTools, tried,
                lite = LlmClient.liteTurn, firstPid = firstPid)
            val c = plan.ready.firstOrNull()
            if (c == null) {
                // Out of candidates: if a provider's list was never fetched (first run, was offline), fetch it now once.
                val missing = pools.map { it.first }.filter { it !in skipPids && ProviderCatalog.listing(it)?.models.isNullOrEmpty() }
                if (!fetchedMissing && missing.isNotEmpty()) {
                    fetchedMissing = true
                    withContext(Dispatchers.IO) { missing.forEach { ProviderCatalog.refresh(it) } }
                    pools = pools().filter { it.second.isNotEmpty() }
                    continue
                }
                break
            }
            val k = keyOf(c)
            tried += k
            val t = LlmClient.target(c.pid, c.model)
            if (t.key.isBlank() || t.base.isBlank()) { hard += k; continue }
            val name = nameOf(c.pid)
            val blocked = Budget.wait(k, tokens)
            if (blocked != null) {
                resting += Triple("$name · ${c.model}", blocked.first, blocked.second)
                if (blocked.second.startsWith("request bigger")) tooLarge++
                continue
            }
            attempts++
            val t0 = System.currentTimeMillis()
            try {
                Budget.spend(k, tokens)
                val msg = LlmClient.call(t, messages, tools, onText, if (lane == LlmClient.Lane.MAIN) onThink else null, false, timeoutMs = 45_000)
                Budget.headers(k, LlmClient.lastCallHeaders)
                LlmClient.lastUsage?.optInt("completion_tokens")?.takeIf { it > 0 }?.let { Budget.spend(k, it) }
                val ms = System.currentTimeMillis() - t0
                ModelMemory.ok(c.pid, c.model); ModelMemory.providerOk(c.pid)
                mark(t) { h -> h.copy(fails = 0, okCount = h.okCount + 1, coolUntil = 0, lastError = "",
                    avgMs = if (h.avgMs == 0L) ms else (h.avgMs * 3 + ms) / 4) }
                LlmClient.lastRoute = "$name · ${c.model}"
                return msg
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                lastErr = e
                val code = (e as? LlmClient.LlmException)?.code ?: 0
                val text = e.message.orEmpty()
                Budget.headers(k, LlmClient.lastCallHeaders)
                val short = text.removePrefix("${Providers.byId(c.pid).name} · ${c.model}: ").take(110)
                val rateLike = code == 429 || code == 402 || Regex("(?i)rate.?limit|quota|resource_exhausted|too many requests").containsMatchIn(text)
                if (rateLike) Budget.limited(k, text, (e as? LlmClient.LlmException)?.retryAfterSec ?: 0)
                val hint = if (rateLike) Budget.wait(k, 0)?.first else null
                val o = ModelMemory.classify(c.pid, code, text, hint)
                mark(t) { h -> h.copy(fails = h.fails + 1, coolUntil = System.currentTimeMillis() + o.coolMs, lastError = text.take(120)) }
                when (o.kind) {
                    ModelMemory.Kind.TOO_LARGE -> { tooLarge++; failures += "$name: request too big for its plan" }
                    ModelMemory.Kind.OFFLINE -> {
                        otherErrors++; offline += c.pid; skipPids += c.pid
                        if (offline.size >= 2) throw LlmClient.LlmException(
                            "I can't reach any AI service right now. Check your data or Wi-Fi, then ask again.", 0)
                    }
                    ModelMemory.Kind.RATE_LIMIT -> {
                        ModelMemory.limited(c.pid, c.model, o.coolMs, Budget.wait(k, 0)?.second ?: "rate limit")
                        resting += Triple("$name · ${c.model}", o.coolMs, "rate limit")
                    }
                    ModelMemory.Kind.PROVIDER_LIMIT -> {
                        ModelMemory.providerLimited(c.pid, o.coolMs, "provider limit: $short")
                        skipPids += c.pid
                        resting += Triple(name, o.coolMs, "daily limit")
                    }
                    ModelMemory.Kind.KEY -> {
                        otherErrors++; hard += k; skipPids += c.pid
                        ModelMemory.providerLimited(c.pid, o.coolMs, "key rejected")
                        failures += "$name: key rejected"
                    }
                    ModelMemory.Kind.NO_TOOLS -> { otherErrors++; hard += k; ModelMemory.cantUseTools(c.pid, c.model) }
                    ModelMemory.Kind.GONE -> { otherErrors++; hard += k; ModelMemory.failed(c.pid, c.model, o.coolMs, "not available: $short") }
                    else -> {
                        otherErrors++; hard += k
                        ModelMemory.failed(c.pid, c.model, o.coolMs, short)
                        failures += "$name · ${c.model}: $short"
                    }
                }
            }
        }

        // Every model said the request was too big: trim the conversation once and go round again.
        if (tooLarge > 0 && otherErrors == 0) {
            val small = LlmClient.shrink(messages, 6)
            if (small.length() < messages.length()) return complete(small, tools, onText, onThink, lane, waited, hard)
        }

        // Keyless last resort: plain chat turns, or any turn when no key is set at all.
        if (!needTools || pools.isEmpty()) {
            for (m in ProviderCatalog.models(Providers.keyless.id).map { it.id }.take(2)) {
                try {
                    val msg = LlmClient.call(LlmClient.target(Providers.keyless.id, m), messages, null, onText, null, false, timeoutMs = 45_000)
                    LlmClient.lastRoute = "Keyless · $m"
                    return msg
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { lastErr = e }
            }
        }
        if (pools.isEmpty()) throw LlmClient.LlmException("Auto (free) has nothing to route to yet. Tap Retry in a moment.")

        // Nothing ready: wait quietly once for the soonest model that frees up within ~25 s.
        val now = System.currentTimeMillis()
        val cool = RoutePlan.plan(pools.filter { it.first !in skipPids || ModelMemory.providerCoolingUntil(it.first) > now }, needTools, hard).cooling
        val nextCool = cool.firstOrNull()?.let { (c, until) -> Triple("${nameOf(c.pid)} · ${c.model}", until - now, "") }
        val soonest = (resting.filter { it.second > 0 } + listOfNotNull(nextCool)).minByOrNull { it.second }
        val leftReady = RoutePlan.plan(pools.filter { it.first !in skipPids }, needTools, tried).ready.isNotEmpty()
        if (!waited && !leftReady && soonest != null && soonest.second <= WAIT_MAX_MS) {
            restingNote = "${soonest.first.substringBefore(" · ")} is cooling down, back in ${(soonest.second / 1000).coerceAtLeast(1)}s"
            try { kotlinx.coroutines.delay(soonest.second + 300) } finally { restingNote = "" }
            return complete(messages, tools, onText, onThink, lane, true, hard)
        }
        val code0 = if (soonest != null && failures.isEmpty()) 429 else (lastErr as? LlmClient.LlmException)?.code ?: 0
        val ra = ((soonest?.second ?: 0L) / 1000).toInt()
        lastFailures = (resting.distinctBy { it.first }.sortedBy { it.second }.map { "${it.first} back in ${fmt(it.second)} (${it.third})" } + failures.distinct()).take(12)
        throw LlmClient.LlmException(friendly(attempts, leftReady, soonest?.let { it.first to it.second }, failures.isNotEmpty()), code0, ra)
    }

    /** One provider's line in Settings › Auto: how many models it has, how many are cooling. */
    data class PoolSummary(val pid: String, val name: String, val models: Int, val cooling: Int, val toolModels: Int,
                           val fetchedAt: Long, val error: String, val nextFreeMs: Long)

    /** Per provider in the chain (and the keyless last resort): model counts and cooldowns. */
    fun summary(now: Long = System.currentTimeMillis()): List<PoolSummary> {
        val ps = try { pools() } catch (_: Exception) { emptyList() }
        val keyless = Providers.keyless.id to ProviderCatalog.models(Providers.keyless.id).map { RoutePlan.Cand(Providers.keyless.id, it.id, false) }
        return (ps + keyless).map { (pid, list) ->
            val ids = list.map { it.model }
            val (_, cool) = ModelMemory.counts(pid, ids, now)
            val l = ProviderCatalog.listing(pid)
            val soon = ModelMemory.soonest(pid, ids, now)
            PoolSummary(pid, nameOf(pid), ids.size, cool, list.count { it.tools && !ModelMemory.noTools(pid, it.model, now) },
                l?.at ?: 0, l?.error.orEmpty(), if (soon > 0) soon - now else 0)
        }
    }

    /** What the last failed Auto turn ran into, model by model (diagnostics). */
    @Volatile var lastFailures: List<String> = emptyList()

    /** The message Luke sees when no free model could answer: short, plain, says when the next one frees up. */
    fun friendly(attempts: Int, moreLeft: Boolean, soonest: Pair<String, Long>?, hadErrors: Boolean): String = when {
        moreLeft -> "I tried $attempts free models and none answered this time. Say \u201ctry again\u201d and I'll move on to the next ones."
        soonest != null -> "All your free models are busy right now. The next one (${soonest.first.take(48)}) frees up in ${fmt(soonest.second)}. " +
            "Ask again then."
        hadErrors -> "None of your free models could answer just now. Say \u201ctry again\u201d in a minute."
        else -> "All your free models are resting right now. Ask again in a minute."
    }

    /** Shown while Auto waits for a cooling model (empty otherwise). */
    @Volatile var restingNote: String = ""

    fun fmt(ms: Long): String = when {
        ms >= 3600_000 -> "${ms / 3600_000}h ${(ms % 3600_000) / 60_000}m"
        ms >= 90_000 -> "${(ms + 59_999) / 60_000} min"
        else -> "${(ms + 999) / 1000}s"
    }
}
