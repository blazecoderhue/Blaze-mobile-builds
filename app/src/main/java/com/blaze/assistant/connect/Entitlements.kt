package com.blaze.assistant.connect

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * v0.45.18 usage view: the plan, this month's download limits and what's left, from the gateway's
 * GET /v1/entitlements (Site contract ENTITLEMENTS_CONTRACT.md, gateway 0.8.38). Every number shown comes from the
 * server; nothing here hardcodes a tier's limits. Before each download the worker calls POST /v1/downloads/consume
 * ([consume]) and only starts on a 200.
 *
 * Nothing in the app is gated on `features` yet, so the snapshot's signature is not verified here; the day a feature
 * is gated, gate it only on a payload that passed POST /v1/entitlements/verify.
 */
object Entitlements {
    data class Counter(val limit: Int, val used: Int, val remaining: Int, val unlimited: Boolean)

    data class Snapshot(
        val plan: String,
        val features: List<String>,
        val song: Counter?,
        val video: Counter?,
        /** null with [parallelUncapped] = no server cap; null without it = the server didn't say. */
        val maxParallel: Int?,
        val parallelUncapped: Boolean,
        val month: String,
        val resetAtMs: Long,
        val expMs: Long,
    )

    // ---------------------------------------------------------------- pure (EntitlementsTest)

    fun parse(body: String): Snapshot? = runCatching {
        val o = JSONObject(body)
        if (!o.has("plan") && !o.has("downloads")) return null
        val dl = o.optJSONObject("downloads")
        fun counter(k: String): Counter? = dl?.optJSONObject(k)?.let { c ->
            val unl = c.optBoolean("unlimited", false) || c.optInt("limit", 0) < 0
            Counter(c.optInt("limit", -1), c.optInt("used", 0).coerceAtLeast(0), c.optInt("remaining", -1), unl)
        }
        val feats = o.optJSONArray("features")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        val hasPar = o.has("max_parallel_downloads")
        val par = if (hasPar && !o.isNull("max_parallel_downloads")) o.optInt("max_parallel_downloads").coerceAtLeast(1) else null
        val reset = o.optLong("reset_at_ms", 0L).takeIf { it > 0 }
            ?: runCatching { Instant.parse(o.optString("reset_at")).toEpochMilli() }.getOrDefault(0L)
        Snapshot(o.optString("plan", "basic").ifBlank { "basic" }, feats, counter("song"), counter("video"), par, hasPar && par == null,
            o.optString("month"), reset, o.optLong("exp", 0L))
    }.getOrNull()

    /** Left this month; Int.MAX_VALUE = no cap. */
    fun left(c: Counter): Int = if (c.unlimited) Int.MAX_VALUE else (if (c.remaining >= 0) c.remaining else c.limit - c.used).coerceAtLeast(0)

    /** Wave B 3: how full the month's meter is (used / limit, 0..1); null = no meter (unlimited or no limit given). */
    fun fraction(c: Counter): Float? = if (c.unlimited || c.limit <= 0) null
        else ((c.limit - left(c)).coerceAtLeast(0).toFloat() / c.limit).coerceIn(0f, 1f)

    /** Wave B 3: the meter's caption, "3 of 100 used" (server numbers, never a hardcoded plan table). */
    fun usedLine(c: Counter): String = if (c.unlimited) "Unlimited" else "${fmt((c.limit - left(c)).coerceAtLeast(0))} of ${fmt(c.limit)} used"

    /** "97 of 100 left" / "Unlimited". */
    fun line(c: Counter): String = if (c.unlimited) "Unlimited" else "${fmt(left(c))} of ${fmt(c.limit)} left"

    /** "Resets Nov 1" on the phone's clock (00:00 UTC on the 1st is 02:00 in Lusaka, still the 1st). */
    fun resetLine(ms: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        if (ms <= 0) "" else "Resets " + DateTimeFormatter.ofPattern("MMM d", locale).format(Instant.ofEpochMilli(ms).atZone(zone))

    fun parallelLine(s: Snapshot): String = when {
        s.maxParallel != null -> if (s.maxParallel == 1) "1 download at a time" else "${s.maxParallel} downloads at a time"
        s.parallelUncapped -> "No limit on downloads at a time"
        else -> ""
    }

    /** Near a limit: at most 10% (and at most 5) left, or none. Unlimited never is. */
    fun near(c: Counter?): Boolean = c != null && !c.unlimited && c.limit > 0 && left(c) <= maxOf(1, minOf(5, c.limit / 10))

    /** The soft nudge, only when a counter is near its limit; empty otherwise. Never shown on an uncapped plan. */
    fun nudge(s: Snapshot): String {
        val songs = near(s.song); val videos = near(s.video)
        if (!songs && !videos) return ""
        val what = when { songs && videos -> "song and video downloads"; songs -> "song downloads"; else -> "video downloads" }
        val none = (songs && left(s.song!!) == 0) || (videos && left(s.video!!) == 0)
        return (if (none) "You've used this month's $what." else "You're close to this month's $what.") + " A bigger plan gives you more."
    }

    /** The small line on a download sheet: "9 video downloads left this month". */
    fun sheetLine(s: Snapshot?, kind: String): String {
        val c = (if (kind == "video") s?.video else s?.song) ?: return ""
        if (c.unlimited) return ""
        val n = left(c)
        val noun = if (kind == "video") "video download" else "song download"
        return if (n == 0) "No ${noun}s left this month. ${resetLine(s!!.resetAtMs)}".trim()
        else "${fmt(n)} ${noun}${if (n == 1) "" else "s"} left this month"
    }

    fun kindOf(jobKind: String): String = if (jobKind == "video") "video" else "song"

    fun stale(s: Snapshot?, fetchedAt: Long, now: Long): Boolean =
        s == null || (s.expMs > 0 && now >= s.expMs) || now - fetchedAt >= 15 * 60_000L

    private fun fmt(n: Int) = String.format(Locale.US, "%,d", n)

    /** What the worker does with a POST /v1/downloads/consume answer. */
    sealed class Gate {
        object Ok : Gate()
        object Skip : Gate()                          // signed out: nothing to count against
        data class Blocked(val message: String) : Gate()
        data class Offline(val why: String) : Gate()  // try again later
    }

    fun gateFor(code: Int, body: String): Gate = when {
        code in 200..299 -> Gate.Ok
        code == 401 -> Gate.Skip
        code == 429 -> Gate.Blocked(runCatching { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
            .ifBlank { "You've used this month's downloads on your plan." })
        code in 400..499 -> Gate.Blocked(runCatching { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
            .ifBlank { "Blaze couldn't start this download ($code)." })
        else -> Gate.Offline("Couldn't reach Blaze to check your download limit")
    }

    // ---------------------------------------------------------------- state + network

    private const val PREFS = "blaze_entitlements"
    private val _snap = MutableStateFlow<Snapshot?>(null)
    val snap: StateFlow<Snapshot?> = _snap
    @Volatile private var fetchedAt = 0L

    fun cached(ctx: Context): Snapshot? {
        _snap.value?.let { return it }
        val p = ctx.applicationContext.getSharedPreferences(PREFS, 0)
        val s = parse(p.getString("body", "").orEmpty())
        if (s != null && p.getString("who", "") == who()) { fetchedAt = p.getLong("at", 0); _snap.value = s }
        return _snap.value
    }

    private fun who(): String = runCatching { com.blaze.assistant.agent.Settings.gatewayToken().takeLast(10) }.getOrDefault("")

    private fun call(method: String, path: String, body: String?): Pair<Int, String> {
        val c = UrlSafety.open(com.blaze.assistant.agent.Providers.GATEWAY_BASE.trimEnd('/') + path)
        c.requestMethod = method; c.connectTimeout = 8_000; c.readTimeout = 10_000
        c.setRequestProperty("Authorization", "Bearer " + com.blaze.assistant.agent.Settings.gatewayToken())
        try {
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }).orEmpty()
            return code to text
        } finally { c.disconnect() }
    }

    private fun signedIn() = runCatching { com.blaze.assistant.agent.Settings.gatewayToken().isNotBlank() }.getOrDefault(false)

    /** Call on IO. Refreshes when [force] or the snapshot is stale; returns what we have (cache when offline). */
    fun refresh(ctx: Context, force: Boolean = false): Snapshot? {
        val app = ctx.applicationContext
        if (!signedIn()) { _snap.value = null; return null }
        val have = cached(app)
        if (!force && !stale(have, fetchedAt, System.currentTimeMillis())) return have
        val got = runCatching { call("GET", "/entitlements", null) }.getOrNull()
        if (got != null && got.first == 401) { _snap.value = null; return null }
        val s = got?.takeIf { it.first in 200..299 }?.let { parse(it.second) } ?: return have
        fetchedAt = System.currentTimeMillis()
        _snap.value = s
        app.getSharedPreferences(PREFS, 0).edit().putString("body", got.second).putLong("at", fetchedAt).putString("who", who()).apply()
        return s
    }

    /** Call on IO, right before a download starts. Refreshes the snapshot after a counted download. */
    fun consume(ctx: Context, jobKind: String, ref: String): Gate {
        if (!signedIn()) return Gate.Skip
        val body = JSONObject().put("kind", kindOf(jobKind)).apply { if (ref.isNotBlank()) put("ref", ref.take(200)) }.toString()
        val g = runCatching { call("POST", "/downloads/consume", body) }.map { (code, text) -> gateFor(code, text) }
            .getOrElse { Gate.Offline("Couldn't reach Blaze to check your download limit") }
        if (g is Gate.Ok || g is Gate.Blocked) runCatching { refresh(ctx, force = true) }
        return g
    }
}
