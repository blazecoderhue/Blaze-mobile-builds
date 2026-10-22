package com.blaze.assistant.agent

/**
 * v0.45.1 speed: the system prompt as named parts with a stability tier, so provider prompt caching can hit.
 *
 * Providers (OpenAI-compatible, Gemini, Anthropic, Groq, DeepSeek…) reuse the work for the longest byte-identical prefix
 * of a request. The old prompt mixed per-turn blocks (core skills, views, terminal) and per-call data into the middle,
 * so two turns shared only the first part, and inside one phone task every step changed the system message (clock,
 * screen awareness), which also threw away the cache for the whole history after it.
 *
 * Now:
 *  - [assemble] orders the parts STATIC → SLOW → TURN → LIVE (stable sort: inside a tier the old order is kept, and
 *    no text is added, dropped or changed), so the longest possible prefix is identical turn after turn.
 *  - [LiveSnap] keeps a turn's LIVE tail (clock, device line, awareness, setup, …) for the steps of that turn, so the
 *    system message stays byte-identical from step to step and the history behind it stays cached. A new turn, a
 *    change of what the tail is built from (its key: memory block, full/summary awareness, the request) or [LiveSnap.ttlMs]
 *    rebuilds it. Tool results always carry the fresh screen, so the snapshot never decides whether an action worked.
 *  - [sizes] / [rank] give each part's size, for the speed log and "where do my tokens go" (#16).
 *
 * Pure Kotlin (PromptPartsTest, PromptCacheBenchTest).
 */
object PromptParts {
    enum class Tier { STATIC, SLOW, TURN, LIVE }

    data class Part(val name: String, val tier: Tier, val text: String)

    /** Collects parts in the order the prompt was always written in (so the old string is [legacy]). */
    class Builder {
        val parts = ArrayList<Part>()
        fun add(name: String, tier: Tier, text: String?) { if (!text.isNullOrEmpty()) parts += Part(name, tier, text) }
        fun s(name: String, text: String?) = add(name, Tier.STATIC, text)
        fun slow(name: String, text: String?) = add(name, Tier.SLOW, text)
        fun turn(name: String, text: String?) = add(name, Tier.TURN, text)
        fun live(name: String, text: String?) = add(name, Tier.LIVE, text)
    }

    /** Placeholder text of a LIVE part whose text is made later ([withLive]). */
    const val SLOT = "\u0000live\u0000"

    /** Each placeholder part replaced by the [live] part of the same name (empty ones dropped); other parts unchanged. */
    fun withLive(parts: List<Part>, live: List<Part>): List<Part> {
        val byName = live.associateBy { it.name }
        return parts.mapNotNull { p -> if (p.text == SLOT) byName[p.name]?.takeIf { it.text.isNotEmpty() } else p }
    }

    fun build(block: Builder.() -> Unit): List<Part> = Builder().apply(block).parts

    /** The prompt exactly as before v0.45.1 (parts in written order). */
    fun legacy(parts: List<Part>): String = parts.joinToString("") { it.text }

    /** Stable parts first; inside a tier the written order is kept. */
    fun ordered(parts: List<Part>): List<Part> = parts.withIndex().sortedWith(compareBy({ it.value.tier.ordinal }, { it.index })).map { it.value }

    fun assemble(parts: List<Part>, stableOrder: Boolean = true): String = if (stableOrder) ordered(parts).joinToString("") { it.text } else legacy(parts)

    /** The non-LIVE text (what can be cached across turns of the same kind). */
    fun head(parts: List<Part>): String = ordered(parts).filter { it.tier != Tier.LIVE }.joinToString("") { it.text }

    /** The LIVE text, in written order. */
    fun tail(parts: List<Part>): String = parts.filter { it.tier == Tier.LIVE }.joinToString("") { it.text }

    /** Characters per part name (a name used twice is summed). */
    fun sizes(parts: List<Part>): Map<String, Int> = LinkedHashMap<String, Int>().apply { parts.forEach { put(it.name, (get(it.name) ?: 0) + it.text.length) } }

    /** Characters per tier. */
    fun tierSizes(parts: List<Part>): Map<Tier, Int> = Tier.values().associateWith { t -> parts.filter { it.tier == t }.sumOf { it.text.length } }

    /** Biggest parts first: "rich 9.1k, core 8.0k, control 4.2k". */
    fun rank(parts: List<Part>, n: Int = 6): String = sizes(parts).entries.sortedByDescending { it.value }.take(n)
        .joinToString(", ") { "${it.key} %.1fk".format(it.value / 1000.0) }

    /** One speed-log line: "sys 41.2k (static 30.1k · slow 0.4k · turn 6.0k · live 4.7k)". */
    fun line(parts: List<Part>): String {
        val t = tierSizes(parts)
        return "sys %.1fk (static %.1fk · slow %.1fk · turn %.1fk · live %.1fk)".format(parts.sumOf { it.text.length } / 1000.0,
            (t[Tier.STATIC] ?: 0) / 1000.0, (t[Tier.SLOW] ?: 0) / 1000.0, (t[Tier.TURN] ?: 0) / 1000.0, (t[Tier.LIVE] ?: 0) / 1000.0)
    }

    /** Length of the common prefix of two strings (what a prefix cache could reuse). */
    fun commonPrefix(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    /**
     * One turn's LIVE tail, kept for the steps of that turn. [get] returns the kept text while [key] is the same and it is
     * younger than [ttlMs]; otherwise it builds a new one. Thread-safe; one slot per chat id.
     */
    class LiveSnap<T>(private val ttlMs: Long = 5 * 60_000L, private val clock: () -> Long = System::currentTimeMillis) {
        private class Slot<T>(val key: Any?, val at: Long, val value: T)
        private val slots = java.util.concurrent.ConcurrentHashMap<String, Slot<T>>()
        @Volatile var hits = 0; private set
        @Volatile var builds = 0; private set

        fun get(chat: String, key: Any?, make: () -> T): T {
            val now = clock()
            slots[chat]?.let { s -> if (s.key == key && now - s.at < ttlMs) { hits++; return s.value } }
            val v = make()
            builds++
            slots[chat] = Slot(key, now, v)
            return v
        }

        fun drop(chat: String) { slots.remove(chat) }
        fun clear() { slots.clear() }
    }

    /** (prompt tokens, cached prompt tokens) from a provider's usage block (OpenAI / Gemini / DeepSeek shapes); (0, 0) when absent. */
    fun cacheOf(usage: org.json.JSONObject?): Pair<Int, Int> {
        if (usage == null) return 0 to 0
        val prompt = usage.optInt("prompt_tokens", usage.optInt("input_tokens", 0))
        val cached = usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0)?.takeIf { it > 0 }
            ?: usage.optInt("prompt_cache_hit_tokens", 0).takeIf { it > 0 }
            ?: usage.optInt("cache_read_input_tokens", 0).takeIf { it > 0 }
            ?: usage.optInt("cached_tokens", 0)
        return prompt to cached
    }

    /** "cache 12.3k/15.1k" for the speed log; "" when the provider sent no usage. */
    fun cacheLine(usage: org.json.JSONObject?): String {
        val (p, c) = cacheOf(usage)
        return if (p <= 0) "" else "cache %.1fk/%.1fk".format(c / 1000.0, p / 1000.0)
    }
}
