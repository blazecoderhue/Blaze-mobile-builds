package com.blaze.assistant.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Token meter for the free chain. Free tiers cap tokens (and requests) per minute and per day,
 * so instead of sending and getting a 429, we remember what each provider+model told us
 * (rate-limit headers, usage, 429 retry hints) and what we've sent in the last minute, and skip
 * a model that has no room for this request *before* calling it.
 */
object Budget {

    private data class Meter(
        val sent: ArrayDeque<Pair<Long, Int>> = ArrayDeque(),   // (time, tokens) in the last minute
        @Volatile var tpm: Int = 0,                              // tokens per minute, from headers
        @Volatile var rpm: Int = 0,                              // requests per minute, from headers
        @Volatile var remTokens: Int = -1, @Volatile var remTokensAt: Long = 0, @Volatile var tokensResetMs: Long = 0,
        @Volatile var remReq: Int = -1, @Volatile var remReqAt: Long = 0, @Volatile var reqResetMs: Long = 0,
        @Volatile var blockedUntil: Long = 0, @Volatile var why: String = "",
    )

    private val meters = ConcurrentHashMap<String, Meter>()
    private fun m(key: String) = meters.getOrPut(key) { Meter() }

    /** Rough token count of a request: ~4 characters per token. */
    fun estimate(chars: Int) = chars / 4 + 50

    /** Remembers a request that went out (call before or right after sending). */
    fun spend(key: String, tokens: Int) {
        val mt = m(key)
        synchronized(mt.sent) {
            mt.sent.addLast(System.currentTimeMillis() to tokens)
            trim(mt)
        }
    }

    private fun trim(mt: Meter) {
        val cut = System.currentTimeMillis() - 60_000
        while (mt.sent.isNotEmpty() && mt.sent.first().first < cut) mt.sent.removeFirst()
    }

    /** Reads x-ratelimit-* headers (Groq, OpenRouter, Cerebras, Mistral, OpenAI style). */
    fun headers(key: String, h: Map<String, String>) {
        if (h.isEmpty()) return
        val mt = m(key)
        val now = System.currentTimeMillis()
        fun num(vararg names: String) = names.firstNotNullOfOrNull { n -> h[n]?.trim()?.toDoubleOrNull()?.toInt() }
        fun dur(vararg names: String) = names.firstNotNullOfOrNull { n -> h[n]?.let { parseDuration(it) } }
        num("x-ratelimit-limit-tokens", "x-ratelimit-limit-tokens-minute", "x-ratelimit-tokens-limit")?.let { mt.tpm = it }
        num("x-ratelimit-limit-requests", "x-ratelimit-limit-requests-minute")?.let { mt.rpm = it }
        num("x-ratelimit-remaining-tokens", "x-ratelimit-remaining-tokens-minute", "x-ratelimit-tokens-remaining")?.let { mt.remTokens = it; mt.remTokensAt = now }
        num("x-ratelimit-remaining-requests", "x-ratelimit-remaining-requests-minute", "x-ratelimit-remaining")?.let { mt.remReq = it; mt.remReqAt = now }
        dur("x-ratelimit-reset-tokens", "x-ratelimit-reset-tokens-minute")?.let { mt.tokensResetMs = it }
        dur("x-ratelimit-reset-requests", "x-ratelimit-reset-requests-minute", "x-ratelimit-reset")?.let { mt.reqResetMs = it }
    }

    /** A 429 or quota error: block until the provider's own hint, or a sensible default. */
    fun limited(key: String, message: String, retryAfterSec: Int) {
        val mt = m(key)
        val hint = retryAfterSec.takeIf { it > 0 }?.times(1000L) ?: hintFrom(message)
        val daily = Regex("(?i)(per ?day|daily|RPD|TPD|quota exceeded|exceeded your current quota|free_tier_requests)").containsMatchIn(message) && hint == null
        val ms = hint ?: if (daily) 30 * 60_000L else 60_000L
        mt.blockedUntil = System.currentTimeMillis() + ms.coerceIn(3_000L, 6 * 3600_000L)
        mt.why = if (daily) "daily quota" else "rate limit"
    }

    /** "try again in 12.5s", "retryDelay": "40s", "Please retry in 1m3s", "reset after 20 seconds". */
    fun hintFrom(msg: String): Long? {
        Regex("(?i)retry ?delay\"?\\s*:\\s*\"?([0-9.]+)s").find(msg)?.let { return (it.groupValues[1].toDouble() * 1000).toLong() }
        Regex("(?i)(?:try again|retry|reset|available) (?:in|after) ((?:[0-9.]+\\s*(?:h|m|s|ms|seconds?|minutes?|hours?)\\s*)+)").find(msg)?.let { return parseDuration(it.groupValues[1]) }
        return null
    }

    /** "1m2.5s", "7.66s", "120ms", "40 seconds", "2 minutes" -> milliseconds. */
    fun parseDuration(s: String): Long? {
        val t = s.trim().lowercase()
        t.toDoubleOrNull()?.let { return (it * 1000).toLong() }   // plain seconds
        var total = 0.0; var any = false
        Regex("([0-9.]+)\\s*(ms|milliseconds?|h|hours?|m|minutes?|min|s|seconds?|sec)").findAll(t).forEach { g ->
            val v = g.groupValues[1].toDoubleOrNull() ?: return@forEach
            any = true
            total += when (g.groupValues[2]) {
                "ms", "millisecond", "milliseconds" -> v
                "h", "hour", "hours" -> v * 3600_000
                "m", "min", "minute", "minutes" -> v * 60_000
                else -> v * 1000
            }
        }
        return if (any) total.toLong() else null
    }

    /**
     * Null when [key] can take a request of [tokens] now; otherwise how long until it can,
     * in ms, and why (shown as "Groq cooling down 40s").
     */
    fun wait(key: String, tokens: Int): Pair<Long, String>? {
        val mt = meters[key] ?: return null
        val now = System.currentTimeMillis()
        if (mt.blockedUntil > now) return (mt.blockedUntil - now) to mt.why
        // Provider said how many tokens are left in this window.
        if (mt.remTokens >= 0 && mt.tokensResetMs > 0 && now < mt.remTokensAt + mt.tokensResetMs) {
            val usedSince = synchronized(mt.sent) { trim(mt); mt.sent.filter { it.first > mt.remTokensAt }.sumOf { it.second } }
            if (mt.remTokens - usedSince < tokens) return (mt.remTokensAt + mt.tokensResetMs - now) to "token limit"
        }
        if (mt.remReq == 0 && mt.reqResetMs > 0 && now < mt.remReqAt + mt.reqResetMs) return (mt.remReqAt + mt.reqResetMs - now) to "request limit"
        // Our own count against the known per-minute caps.
        synchronized(mt.sent) {
            trim(mt)
            if (mt.tpm > 0 && tokens > mt.tpm) return 60_000L to "request bigger than its per-minute limit"
            if (mt.tpm > 0 && mt.sent.sumOf { it.second } + tokens > mt.tpm)
                return (mt.sent.first().first + 60_000 - now).coerceAtLeast(1000) to "token limit"
            if (mt.rpm > 0 && mt.sent.size >= mt.rpm) return (mt.sent.first().first + 60_000 - now).coerceAtLeast(1000) to "request limit"
        }
        return null
    }

    /** The provider's per-minute token cap when it told us one (0 = unknown). */
    fun tpm(key: String) = meters[key]?.tpm ?: 0

    fun clear(key: String) { meters[key]?.blockedUntil = 0 }
}
