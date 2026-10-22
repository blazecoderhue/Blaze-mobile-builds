package com.blaze.assistant.agent

/**
 * v0.45.8 the start of a reply, timed on the monotonic clock (System.nanoTime, never the wall clock), from the Send tap:
 *
 * - **tap**: Send pressed (the chat screen's send, or Agent.send for voice and other callers);
 * - **visible**: the first frame drawn with the working state (orb / work card);
 * - **wire**: the chat request's body starts going out (the connection is open, the first byte is being written);
 * - **headers**: the gateway answered with headers;
 * - **first**: the first streamed token.
 *
 * Each mark is taken once per tap. The newest finished set rides on the reply's "What happened" sheet (TurnTrace) and in
 * the speed log (speed_stats). Pure Kotlin with an injectable clock (StartTraceTest, SendPathTest).
 */
object StartTrace {
    class Marks(val tap: Long) {
        @Volatile var visible: Long = -1
        @Volatile var wire: Long = -1
        @Volatile var headers: Long = -1
        @Volatile var first: Long = -1
        private fun ms(t: Long) = if (t < 0) -1L else ((t - tap) / 1_000_000L).coerceAtLeast(0)
        val visibleMs: Long get() = ms(visible)
        val wireMs: Long get() = ms(wire)
        val headersMs: Long get() = ms(headers)
        val firstMs: Long get() = ms(first)
        /** v0.45.9: named stages between tap and wire (first time each was reached), in order. */
        val stages = java.util.Collections.synchronizedMap(LinkedHashMap<String, Long>())
        fun stageMs(name: String): Long = stages[name]?.let { ms(it) } ?: -1L
    }

    /**
     * v0.45.9: finer marks between Send and the wire, so a phone's "on the wire 4.84 s" says where the time went:
     * turn (the agent loop started), route (tool groups picked), tools (tool list built: MCP, connectors), prompt (system
     * prompt + [context] block + request JSON), lane (chat lane / agent / cloud run chosen), token (session token read),
     * connect (TCP + TLS done, when the lane opens the connection itself). Each is taken once per tap, only before the wire.
     */
    fun stage(name: String) { cur?.let { if (it.wire < 0 && !it.stages.containsKey(name)) it.stages[name] = clock() } }

    /** "turn 3 ms · route 5 ms · tools 4.61 s · prompt 4.70 s · token 4.70 s" (ms since the tap, cumulative). */
    fun stagesLine(m: Marks): String = synchronized(m.stages) { m.stages.keys.toList() }.joinToString(" \u00b7 ") { "$it ${f(m.stageMs(it))}" }

    /** Targets (user, 2026-10-21): working state under 100 ms, request on the wire under 300 ms. */
    const val VISIBLE_TARGET_MS = 100L
    const val WIRE_TARGET_MS = 300L

    @Volatile var clock: () -> Long = { System.nanoTime() }
    @Volatile var cur: Marks? = null
        private set

    /** Send was tapped. A second tap within [SAME_TAP_MS] (the chat screen, then Agent.send) keeps the first mark. */
    fun tap(force: Boolean = false) {
        val now = clock()
        val c = cur
        if (!force && c != null && c.wire < 0 && now - c.tap < SAME_TAP_NS) return
        cur = Marks(now)
    }
    private const val SAME_TAP_NS = 2_000_000_000L

    fun visible() { cur?.let { if (it.visible < 0) it.visible = clock() } }
    fun wire() { cur?.let { if (it.wire < 0) it.wire = clock() } }
    fun headers() { cur?.let { if (it.wire >= 0 && it.headers < 0) it.headers = clock() } }
    fun first() { cur?.let { if (it.wire >= 0 && it.first < 0) it.first = clock() } }

    /** The marks of the turn that just ended (null when nothing was timed). */
    fun take(): Marks? = cur?.takeIf { it.wire >= 0 || it.visible >= 0 }

    fun reset() { cur = null }

    private fun f(v: Long) = if (v < 0) "" else if (v < 1000) "$v ms" else "%.2f s".format(java.util.Locale.US, v / 1000.0)

    /** "Send → working 16 ms · on the wire 182 ms · headers 640 ms · first word 1.21 s" (unknown parts left out). */
    fun line(m: Marks): String = listOf("working" to m.visibleMs, "on the wire" to m.wireMs, "headers" to m.headersMs, "first word" to m.firstMs)
        .filter { it.second >= 0 }.joinToString(" \u00b7 ", prefix = "Send \u2192 ") { "${it.first} ${f(it.second)}" }

    fun json(m: Marks): org.json.JSONObject = org.json.JSONObject().put("vis", m.visibleMs).put("wire", m.wireMs).put("hdr", m.headersMs).put("first", m.firstMs)
        .put("stages", stagesLine(m))
}
