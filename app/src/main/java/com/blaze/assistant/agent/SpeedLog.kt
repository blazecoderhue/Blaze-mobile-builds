package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Speed readout (speed plan step 14): every cloud turn logs time to first token, total time,
 * steps, how many tools were sent and how big the prompt was, so each speed change can be
 * measured on whatever phone Blaze runs on.
 */
object SpeedLog {
    data class Entry(val at: Long, val firstMs: Long, val totalMs: Long, val steps: Int, val tools: Int, val promptChars: Int, val route: String,
                     /** v0.41.0: TurnTimer's phase line ("route 3 ms · tools 12 ms · … · total 4.1 s"). */
                     val phases: String = "",
                     /** v0.45.3: Send → the first words on screen (the reply bubble appeared), and the gateway's time to answer headers; -1 = unknown. */
                     val shownMs: Long = -1, val headersMs: Long = -1,
                     /** v0.45.8: Send tap → working state on screen, and → the request on the wire (monotonic); -1 = unknown. */
                     val visibleMs: Long = -1, val wireMs: Long = -1)

    private const val KEY = "speed_log"
    private const val MAX = 50

    fun add(e: Entry) = try {
        val a = try { JSONArray(Settings.str(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        a.put(JSONObject().put("at", e.at).put("first", e.firstMs).put("total", e.totalMs).put("steps", e.steps)
            .put("tools", e.tools).put("chars", e.promptChars).put("route", e.route).put("phases", e.phases)
            .put("shown", e.shownMs).put("hdr", e.headersMs).put("vis", e.visibleMs).put("wire", e.wireMs))
        while (a.length() > MAX) a.remove(0)
        Settings.setStr(KEY, a.toString())
    } catch (_: Exception) { }

    fun all(): List<Entry> = try {
        val a = JSONArray(Settings.str(KEY, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Entry(it.optLong("at"), it.optLong("first"), it.optLong("total"), it.optInt("steps"), it.optInt("tools"), it.optInt("chars"), it.optString("route"), it.optString("phases"),
                it.optLong("shown", -1), it.optLong("hdr", -1), it.optLong("vis", -1), it.optLong("wire", -1))
        }
    } catch (_: Exception) { emptyList() }

    /** One line, e.g. "last 20 turns: first word 1.4 s, done 3.1 s, 18 tools, 9k chars". */
    fun summary(n: Int = 20): String {
        val l = all().takeLast(n)
        if (l.isEmpty()) return "No timed turns yet."
        fun med(xs: List<Long>) = xs.sorted()[xs.size / 2]
        return "last ${l.size} turns (median): first word %.1f s, done %.1f s, %d tools sent, %dk prompt chars".format(
            med(l.map { it.firstMs }) / 1000.0, med(l.map { it.totalMs }) / 1000.0,
            med(l.map { it.tools.toLong() }), med(l.map { it.promptChars.toLong() }) / 1000)
    }

    /** v0.45.3: the first-word line for speed_stats: Send → words on screen (median / slowest of 10), model start → first word, gateway headers. */
    fun firstWordLine(n: Int = 20): String = firstWordLine(all().takeLast(n))

    fun firstWordLine(l: List<Entry>): String {
        val shown = l.map { it.shownMs }.filter { it >= 0 }
        if (shown.isEmpty()) return ""
        fun med(xs: List<Long>) = xs.sorted()[xs.size / 2]
        fun p90(xs: List<Long>) = xs.sorted()[((xs.size - 1) * 9) / 10]
        val hdr = l.map { it.headersMs }.filter { it > 0 }
        return "\nFirst words on screen after Send (last ${shown.size}): median %.1f s, slow ones %.1f s".format(med(shown) / 1000.0, p90(shown) / 1000.0) +
            (if (hdr.isNotEmpty()) "; Blaze Cloud answered (headers) in %.1f s median".format(med(hdr) / 1000.0) else "") + startLine(l)
    }

    /** v0.45.8: "Send → working 16 ms, on the wire 180 ms (median)" for speed_stats; "" before any timed turn. */
    fun startLine(l: List<Entry>): String {
        val vis = l.map { it.visibleMs }.filter { it >= 0 }; val wire = l.map { it.wireMs }.filter { it >= 0 }
        if (vis.isEmpty() && wire.isEmpty()) return ""
        fun med(xs: List<Long>) = xs.sorted()[xs.size / 2]
        return "\nSend \u2192 " + listOfNotNull(vis.takeIf { it.isNotEmpty() }?.let { "working ${med(it)} ms" },
            wire.takeIf { it.isNotEmpty() }?.let { "on the wire ${med(it)} ms" }).joinToString(", ") + " (median of ${maxOf(vis.size, wire.size)})"
    }

    /** v0.41.0: the newest turn's phase line, so "speed_stats" says where the time went. */
    fun lastPhases(): String = all().lastOrNull()?.phases.orEmpty()

    fun clear() = Settings.setStr(KEY, "[]")

    /** v0.45.1: screen reads since start: full tree walks (average time) and reads served from the last walk. */
    fun screenLine(): String = try {
        com.blaze.assistant.phone.BlazeAccessibilityService.instance?.let { s -> screenLine(s.walksDone, s.walksReused, s.walkMsTotal) }.orEmpty()
    } catch (_: Throwable) { "" }

    fun screenLine(walks: Int, reused: Int, ms: Long): String =
        if (walks + reused == 0) "" else "\nScreen reads: $walks tree walks (avg ${if (walks > 0) ms / walks else 0} ms), $reused reused unchanged"
}
