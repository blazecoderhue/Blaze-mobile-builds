package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.45.5 (#1, #5b): the per-reply "What happened" sheet. What served the reply (model, provider, route, lane), each
 * tool with short arguments and results, the client's time to first word next to the gateway's own timings
 * (X-Blaze-TTFT-Ms, Server-Timing), errors and retries, and the X-Request-Id that matches the gateway logs (copyable).
 * Stored with the reply as JSON (ChatMessage.trace). Pure Kotlin + org.json (TurnTraceTest).
 */
object TurnTrace {
    data class Tool(val name: String, val args: String, val result: String, val ok: Boolean = true)

    data class Trace(
        val model: String = "", val provider: String = "", val route: String = "", val lane: String = "",
        val requestId: String = "", val requestIds: List<String> = emptyList(),
        val clientTtftMs: Long = 0, val headersMs: Long = 0, val totalMs: Long = 0,
        val gatewayTtftMs: String = "", val gatewayTtfbMs: String = "", val serverTiming: String = "",
        val toolsDropped: Boolean = false, val hedged: Boolean = false, val fastLane: Boolean = false, val fallback: Boolean = false,
        val steps: Int = 1, val tools: List<Tool> = emptyList(), val errors: List<String> = emptyList(),
        /** v0.45.7: the longest pause between two streamed text chunks (the "longest pause" of speed_stats), 0 = none. */
        val maxGapMs: Long = 0,
        /** v0.45.8: from the Send tap (monotonic): working state shown, request on the wire, headers, first token; -1 = unknown. */
        val sendVisibleMs: Long = -1, val sendWireMs: Long = -1, val sendHeadersMs: Long = -1, val sendFirstMs: Long = -1,
        /** v0.45.9: where the time before the wire went (StartTrace.stagesLine). */
        val sendStages: String = "",
        /** v0.45.9: gateway 0.8.2 X-Blaze-Normalized (pass | checked | repaired | leaks | retried;… | unresolved;…). */
        val normalized: String = "",
        /** v0.45.9 (ToolTruth): the tools the last request carried, in the order sent. */
        val toolsSent: List<String> = emptyList(),
    )

    /** Gateway response headers worth keeping (lower-case names). */
    val HEADERS = listOf("x-request-id", "x-blaze-ttft-ms", "x-blaze-ttfb-ms", "server-timing", "x-blaze-provider", "x-blaze-model",
        "x-blaze-hedged", "x-blaze-lane", "x-blaze-fast-lane", "x-blaze-fallback", "x-blaze-tools-dropped", "x-blaze-normalized")

    /** Picks [HEADERS] out of a response's header map (any case). */
    fun pick(h: Map<String?, List<String>>?): Map<String, String> {
        if (h == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in h) { val n = k?.lowercase() ?: continue; if (n in HEADERS) v.firstOrNull()?.let { out[n] = it } }
        return out
    }

    private fun on(v: String?) = v != null && (v == "1" || v.equals("true", true) || v.equals("fast", true))

    fun short(s: String, n: Int): String = s.trim().replace(Regex("\\s+"), " ").let { if (it.length > n) it.take(n - 1) + "\u2026" else it }

    fun build(gw: Map<String, String>, route: String, lane: String, requestIds: List<String>, clientTtftMs: Long, headersMs: Long,
              totalMs: Long, steps: Int, tools: List<Tool>, errors: List<String>, maxGapMs: Long = 0, start: StartTrace.Marks? = null,
              toolsSent: List<String> = emptyList()): Trace = Trace(
        model = gw["x-blaze-model"].orEmpty(), provider = gw["x-blaze-provider"].orEmpty(), route = route, lane = lane,
        requestId = gw["x-request-id"] ?: requestIds.lastOrNull().orEmpty(), requestIds = requestIds.distinct().takeLast(12),
        clientTtftMs = clientTtftMs.coerceAtLeast(0), headersMs = headersMs.coerceAtLeast(0), totalMs = totalMs.coerceAtLeast(0),
        gatewayTtftMs = gw["x-blaze-ttft-ms"].orEmpty(), gatewayTtfbMs = gw["x-blaze-ttfb-ms"].orEmpty(), serverTiming = gw["server-timing"].orEmpty(),
        toolsDropped = on(gw["x-blaze-tools-dropped"]), hedged = on(gw["x-blaze-hedged"]),
        fastLane = on(gw["x-blaze-fast-lane"]) || gw["x-blaze-lane"].equals("fast", true), fallback = on(gw["x-blaze-fallback"]),
        steps = steps, tools = tools.takeLast(20).map { Tool(it.name, short(it.args, 80), short(it.result, 140), it.ok) },
        errors = errors.distinct().takeLast(8).map { short(it, 200) }, maxGapMs = maxGapMs.coerceAtLeast(0),
        sendVisibleMs = start?.visibleMs ?: -1, sendWireMs = start?.wireMs ?: -1, sendHeadersMs = start?.headersMs ?: -1, sendFirstMs = start?.firstMs ?: -1,
        sendStages = start?.let { StartTrace.stagesLine(it) }.orEmpty(), normalized = gw["x-blaze-normalized"].orEmpty(), toolsSent = toolsSent)

    fun toJson(t: Trace): String = JSONObject().put("model", t.model).put("provider", t.provider).put("route", t.route).put("lane", t.lane)
        .put("rid", t.requestId).put("rids", JSONArray(t.requestIds)).put("ttft", t.clientTtftMs).put("hdr", t.headersMs).put("total", t.totalMs)
        .put("gw_ttft", t.gatewayTtftMs).put("gw_ttfb", t.gatewayTtfbMs).put("st", t.serverTiming).put("dropped", t.toolsDropped)
        .put("hedged", t.hedged).put("fast", t.fastLane).put("fallback", t.fallback).put("steps", t.steps)
        .put("tools", JSONArray(t.tools.map { JSONObject().put("n", it.name).put("a", it.args).put("r", it.result).put("ok", it.ok) }))
        .put("errors", JSONArray(t.errors)).put("gap", t.maxGapMs)
        .put("s_vis", t.sendVisibleMs).put("s_wire", t.sendWireMs).put("s_hdr", t.sendHeadersMs).put("s_first", t.sendFirstMs).put("s_stages", t.sendStages)
        .put("norm", t.normalized).put("sent", JSONArray(t.toolsSent)).toString()

    fun fromJson(s: String): Trace? = try {
        if (s.isBlank()) null else JSONObject(s).let { o ->
            fun strs(a: JSONArray?) = a?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
            val tl = o.optJSONArray("tools")
            Trace(o.optString("model"), o.optString("provider"), o.optString("route"), o.optString("lane"), o.optString("rid"), strs(o.optJSONArray("rids")),
                o.optLong("ttft"), o.optLong("hdr"), o.optLong("total"), o.optString("gw_ttft"), o.optString("gw_ttfb"), o.optString("st"),
                o.optBoolean("dropped"), o.optBoolean("hedged"), o.optBoolean("fast"), o.optBoolean("fallback"), o.optInt("steps", 1),
                (0 until (tl?.length() ?: 0)).mapNotNull { i -> tl!!.optJSONObject(i)?.let { Tool(it.optString("n"), it.optString("a"), it.optString("r"), it.optBoolean("ok", true)) } },
                strs(o.optJSONArray("errors")), o.optLong("gap"),
                o.optLong("s_vis", -1), o.optLong("s_wire", -1), o.optLong("s_hdr", -1), o.optLong("s_first", -1), o.optString("s_stages"),
                o.optString("norm"), strs(o.optJSONArray("sent")))
        }
    } catch (_: Exception) { null }

    private fun ms0(v: Long) = if (v < 0) "" else if (v < 1000) "$v ms" else "%.2f s".format(java.util.Locale.US, v / 1000.0)
    private fun ms(v: Long) = if (v <= 0) "" else if (v < 1000) "$v ms" else "%.2f s".format(java.util.Locale.US, v / 1000.0)

    /** The sheet's rows: (label, value); empty values are left out. */
    fun rows(t: Trace): List<Pair<String, String>> = listOf(
        "Model" to listOf(t.model, t.provider).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
        "Route" to listOf(t.route, t.lane, if (t.fastLane) "fast lane" else "", if (t.hedged) "hedged" else "", if (t.fallback) "fallback" else "",
            if (t.toolsDropped) "tools dropped (plain turn)" else "").filter { it.isNotBlank() }.joinToString(" \u00b7 "),
        // v0.45.8: the start of the reply, from the Send tap (targets: working < 100 ms, on the wire < 300 ms).
        "Send \u2192 working" to ms0(t.sendVisibleMs),
        "Send \u2192 on the wire" to ms0(t.sendWireMs),
        "Send \u2192 headers" to ms0(t.sendHeadersMs),
        "Send \u2192 first token" to ms0(t.sendFirstMs),
        "Before the wire" to t.sendStages,
        "First word (phone)" to ms(t.clientTtftMs),
        "Gateway TTFT" to (t.gatewayTtftMs.takeIf { it.isNotBlank() }?.let { "$it ms" } ?: ""),
        "Upstream TTFB" to (t.gatewayTtfbMs.takeIf { it.isNotBlank() }?.let { "$it ms" } ?: ""),
        "Server-Timing" to t.serverTiming,
        "Headers after" to ms(t.headersMs),
        "Longest pause" to ms(t.maxGapMs),
        "Whole reply" to ms(t.totalMs) + if (t.steps > 1) " \u00b7 ${t.steps} steps" else "",
        "Normalized" to t.normalized,
        "Tools sent" to toolsLine(t.toolsSent),
        "Request id" to t.requestId,
    ).filter { it.second.isNotBlank() }

    /** "142: a, b, c…" (every name; the sheet wraps). Empty when the trace has no list (older replies). */
    fun toolsLine(names: List<String>): String = if (names.isEmpty()) "" else "${names.size}: " + names.joinToString(", ")

    /** Plain text for Copy (support / gateway logs). */
    fun copyText(t: Trace): String = buildString {
        rows(t).forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
        if (t.requestIds.size > 1) append("All request ids: ").append(t.requestIds.joinToString(", ")).append('\n')
        t.tools.forEach { append("Tool ").append(it.name).append('(').append(it.args).append(") -> ").append(it.result).append('\n') }
        t.errors.forEach { append("Error: ").append(it).append('\n') }
    }.trim()
}
