package com.blaze.assistant.agent

/**
 * v0.45.5 (#5/5b, gateway 0.6.9): POST /v1/warm on app open and when the user starts typing or taps the mic, at most
 * once every [GAP_MS], fire and forget. It loads the gateway's config and the user's token into the edge isolate and
 * pre-opens TLS to the first providers, so the first word comes sooner. Pure throttle (WarmGateTest).
 */
object WarmGate {
    const val GAP_MS = 25_000L
    @Volatile private var last = 0L

    /** True (and remembered) when a warm-up is due at [now]. */
    @Synchronized fun take(now: Long = System.currentTimeMillis()): Boolean {
        if (last != 0L && now - last < GAP_MS && now >= last) return false
        last = now; return true
    }

    fun reset() { last = 0L }

    fun url(base: String): String = base.trimEnd('/') + "/warm"
}
