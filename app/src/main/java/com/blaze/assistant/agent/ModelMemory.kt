package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.23.4: what Auto remembers about each free model ("pid|model"): ok, rate-limited until T, or
 * failed (with a reason and a cooldown). Provider-wide marks ("pid|*") cover a rejected key or a
 * provider-wide daily cap. Kept in `filesDir/model_state.json` (new file; no prefs keys change).
 *
 * Rules: a model that is cooling is never tried; a model whose hard failure has cooled down goes to
 * the back of its provider's queue until it works again; "can't use tools" is remembered for a day
 * and only keeps the model out of tool turns.
 */
object ModelMemory {

    enum class State { OK, LIMITED, FAILED }

    data class Mark(
        val state: State = State.OK,
        val until: Long = 0,           // cooling until (ms)
        val reason: String = "",
        val at: Long = 0,              // when it last failed / was limited
        val okAt: Long = 0,            // when it last answered
        val noToolsUntil: Long = 0,    // can't use tools (bad tool schema / unsupported) until
    )

    /** How long a cooled-down hard failure keeps a model at the back of its provider's queue. */
    const val DEMOTE_MS = 6 * 3600_000L
    const val DECOMMISSIONED_MS = 24 * 3600_000L

    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    private fun now() = clock()

    @Volatile private var dir: File? = null
    private val marks = ConcurrentHashMap<String, Mark>()
    private val _status = MutableStateFlow<Map<String, Mark>>(emptyMap())
    val status: StateFlow<Map<String, Mark>> = _status

    fun init(filesDir: File) { dir = filesDir; load() }

    fun key(pid: String, model: String) = "$pid|$model"
    private fun providerKey(pid: String) = "$pid|*"

    fun mark(pid: String, model: String): Mark = marks[key(pid, model)] ?: Mark()

    /** When [pid]/[model] can be tried again (0 = now). Takes the provider-wide mark into account. */
    fun coolingUntil(pid: String, model: String): Long =
        maxOf(marks[key(pid, model)]?.until ?: 0, marks[providerKey(pid)]?.until ?: 0)

    fun providerCoolingUntil(pid: String): Long = marks[providerKey(pid)]?.until ?: 0

    fun cooling(pid: String, model: String, at: Long = now()) = coolingUntil(pid, model) > at

    fun noTools(pid: String, model: String, at: Long = now()) = (marks[key(pid, model)]?.noToolsUntil ?: 0) > at

    /** A hard failure that has cooled down but hasn't been followed by a success yet. */
    fun demoted(pid: String, model: String, at: Long = now()): Boolean {
        val m = marks[key(pid, model)] ?: return false
        return m.state == State.FAILED && m.okAt < m.at && at - m.at < DEMOTE_MS
    }

    fun ok(pid: String, model: String) = update(key(pid, model)) { it.copy(state = State.OK, until = 0, reason = "", okAt = now()) }

    fun limited(pid: String, model: String, ms: Long, reason: String) =
        update(key(pid, model)) { it.copy(state = State.LIMITED, until = now() + ms, reason = reason.take(140), at = now()) }

    fun failed(pid: String, model: String, ms: Long, reason: String) =
        update(key(pid, model)) { it.copy(state = State.FAILED, until = now() + ms, reason = reason.take(140), at = now()) }

    fun cantUseTools(pid: String, model: String, ms: Long = DECOMMISSIONED_MS) =
        update(key(pid, model)) { it.copy(noToolsUntil = now() + ms, at = now()) }

    /** The whole provider rests (rejected key, provider-wide daily cap). */
    fun providerLimited(pid: String, ms: Long, reason: String) =
        update(providerKey(pid)) { it.copy(state = State.LIMITED, until = now() + ms, reason = reason.take(140), at = now()) }

    fun providerOk(pid: String) { if (marks.remove(providerKey(pid)) != null) changed() }

    fun clear() { marks.clear(); changed() }

    /** Per provider: how many of [models] are usable now, and how many are cooling. */
    fun counts(pid: String, models: List<String>, at: Long = now()): Pair<Int, Int> {
        val cool = models.count { cooling(pid, it, at) }
        return (models.size - cool) to cool
    }

    /** Soonest time any of [models] frees up (0 when none is cooling). */
    fun soonest(pid: String, models: List<String>, at: Long = now()): Long =
        models.map { coolingUntil(pid, it) }.filter { it > at }.minOrNull() ?: 0

    private fun update(k: String, f: (Mark) -> Mark) {
        marks[k] = f(marks[k] ?: Mark())
        changed()
    }

    private fun changed() {
        prune()
        _status.value = HashMap(marks)
        save()
    }

    private fun prune() {
        val t = now()
        marks.entries.removeIf { (_, m) -> m.until < t && m.noToolsUntil < t && (m.state == State.OK || t - m.at > DEMOTE_MS) && t - m.okAt > 7 * 24 * 3600_000L }
    }

    // ---------------- error classification ----------------

    enum class Kind { RATE_LIMIT, PROVIDER_LIMIT, KEY, GONE, NO_TOOLS, BAD_REQUEST, SERVER, TIMEOUT, OFFLINE, TOO_LARGE }
    data class Outcome(val kind: Kind, val coolMs: Long)

    /**
     * What a failed call means for Auto. [hintMs] is the provider's own retry hint (Budget), if any.
     * GONE (404 / decommissioned) cools 24 h; KEY (401) and PROVIDER_LIMIT cool the whole provider.
     */
    fun classify(pid: String, code: Int, text: String, hintMs: Long? = null): Outcome {
        val t = text.lowercase()
        fun has(vararg w: String) = w.any { t.contains(it) }
        return when {
            code == 413 || has("too large", "context length", "context_length", "maximum context", "too many tokens", "reduce the length") ->
                Outcome(Kind.TOO_LARGE, 0)
            code == 0 && has("couldn't reach", "unable to resolve", "failed to connect", "network is unreachable", "no address associated") ->
                Outcome(Kind.OFFLINE, 15_000)
            code == 429 || code == 402 || has("rate limit", "rate_limit", "quota", "resource_exhausted", "too many requests") -> {
                val providerWide = has("free-models-per-day", "free-models-per-min", "per-day", "organization", "account") && pid == "openrouter" ||
                    has("credits", "insufficient balance", "payment required") || code == 402
                Outcome(if (providerWide) Kind.PROVIDER_LIMIT else Kind.RATE_LIMIT, (hintMs ?: 60_000L).coerceIn(3_000L, 6 * 3600_000L))
            }
            code == 404 || has("decommissioned", "deprecated", "no longer available", "no longer supported", "model_not_found",
                "not found", "does not exist", "isn't available to your key", "unknown model", "invalid model") ->
                Outcome(Kind.GONE, DECOMMISSIONED_MS)
            code == 401 || has("invalid api key", "incorrect api key", "unauthorized", "authentication") -> Outcome(Kind.KEY, 30 * 60_000L)
            code == 400 && has("tool", "function") -> Outcome(Kind.NO_TOOLS, DECOMMISSIONED_MS)
            code == 403 -> Outcome(Kind.GONE, 6 * 3600_000L)                // not allowed for this key/model
            code in 400..499 -> Outcome(Kind.BAD_REQUEST, 15 * 60_000L)
            code >= 500 -> Outcome(Kind.SERVER, 2 * 60_000L)
            else -> Outcome(Kind.TIMEOUT, 3 * 60_000L)                       // code 0: timed out / stopped mid-reply
        }
    }

    // ---------------- persistence ----------------

    private fun file(): File? = dir?.let { File(it, "model_state.json") }

    private fun load() {
        val f = file() ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            o.keys().forEach { k ->
                val m = o.getJSONObject(k)
                marks[k] = Mark(try { State.valueOf(m.optString("state", "OK")) } catch (_: Exception) { State.OK },
                    m.optLong("until"), m.optString("reason"), m.optLong("at"), m.optLong("okAt"), m.optLong("noToolsUntil"))
            }
            _status.value = HashMap(marks)
        } catch (_: Exception) { }
    }

    @Synchronized private fun save() {
        val f = file() ?: return
        try {
            val o = JSONObject()
            marks.forEach { (k, m) ->
                o.put(k, JSONObject().put("state", m.state.name).put("until", m.until).put("reason", m.reason)
                    .put("at", m.at).put("okAt", m.okAt).put("noToolsUntil", m.noToolsUntil))
            }
            val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
        } catch (_: Exception) { }
    }

    internal fun resetForTests() { marks.clear(); dir = null; clock = { System.currentTimeMillis() }; _status.value = emptyMap() }
}

/**
 * Picks the order Auto tries models in for one turn.
 *
 * Providers take turns (round-robin in chain order), each giving its next untried model, so no
 * single provider is hammered. Within a provider: the model that last answered, then its catalogue
 * order; hard failures that have cooled down go last. Models cooling down are left out (returned
 * separately with when they free up), models already tried this turn are skipped, and a tool turn
 * only gets tool-capable models.
 */
object RoutePlan {

    data class Cand(val pid: String, val model: String, val tools: Boolean)
    data class Plan(val ready: List<Cand>, val cooling: List<Pair<Cand, Long>>)

    private val light = Regex("(?i)(flash|mini|small|8b|9b|12b|scout|instant|lite|haiku|fast|nano|20b)")
    fun isLight(c: Cand) = c.pid == "groq" || c.pid == "cerebras" || light.containsMatchIn(c.model.replace("gemini", ""))

    fun plan(pools: List<Pair<String, List<Cand>>>, needTools: Boolean, tried: Set<String>,
             now: Long = System.currentTimeMillis(), lite: Boolean = false, firstPid: String? = null): Plan {
        val cooling = mutableListOf<Pair<Cand, Long>>()
        val fresh = mutableListOf<List<Cand>>()
        val demoted = mutableListOf<List<Cand>>()
        val order = if (firstPid != null && pools.any { it.first == firstPid }) pools.sortedBy { if (it.first == firstPid) 0 else 1 } else pools
        for ((pid, list) in order) {
            val usable = list.distinctBy { it.model }
                .filter { !needTools || (it.tools && !ModelMemory.noTools(pid, it.model, now)) }
                .filter { ModelMemory.key(pid, it.model) !in tried }
            val (cool, ready) = usable.partition { ModelMemory.cooling(pid, it.model, now) }
            cool.forEach { cooling += it to ModelMemory.coolingUntil(pid, it.model) }
            var (dem, ok) = ready.partition { ModelMemory.demoted(pid, it.model, now) }
            // The model that answered most recently leads its provider (fill-first, like before).
            val last = ok.maxByOrNull { ModelMemory.mark(pid, it.model).okAt }?.takeIf { ModelMemory.mark(pid, it.model).okAt > 0 }
            if (last != null) ok = listOf(last) + (ok - last)
            if (lite) ok = ok.sortedBy { if (isLight(it)) 0 else 1 }       // stable: keeps the order otherwise
            fresh += ok
            demoted += dem.sortedBy { ModelMemory.mark(pid, it.model).at }   // oldest failure first
        }
        return Plan(roundRobin(fresh) + roundRobin(demoted), cooling.sortedBy { it.second })
    }

    fun roundRobin(queues: List<List<Cand>>): List<Cand> {
        val out = mutableListOf<Cand>()
        val max = queues.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) queues.forEach { q -> if (i < q.size) out += q[i] }
        return out
    }
}
