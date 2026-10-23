package com.blaze.assistant.mediaapps

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Wave B 18: how many downloads run at once. The Site quota API (GET /v1/entitlements, max_parallel_downloads:
 * basic 1, standard 2, pro 4, lifetime null = no cap) is the source of truth, cached for offline use; the phone then
 * caps it by what it can carry (2 on a ~3 GB phone) and backs off under memory or thermal pressure.
 * Pure rules here (WaveB1DownloadsTest); the fetch is [DlQuota].
 */
sealed class Entitled {
    object Unknown : Entitled()                 // never fetched (offline first run)
    data class Capped(val n: Int, val tier: String = "") : Entitled()
    object Uncapped : Entitled()                // lifetime: the client tunes, up to [DlConcurrency.AUTO_MAX]
}

object DlConcurrency {
    const val DEFAULT = 3            // HQ: 3 at a time when the plan is unknown
    const val SMALL_PHONE = 2        // ~3 GB phones
    const val AUTO_MAX = 6           // lifetime ceiling
    const val SMALL_RAM_MB = 3_300L  // totalMem of a "3 GB" phone reads ~2.7–2.9 GB

    /** thermal: PowerManager.THERMAL_STATUS_* (0 none … 3 severe+). */
    fun limit(e: Entitled, totalRamMb: Long, lowRamDevice: Boolean, lowMemoryNow: Boolean, thermal: Int): Int {
        val small = lowRamDevice || (totalRamMb in 1..SMALL_RAM_MB)
        var n = when (e) {
            is Entitled.Capped -> e.n.coerceAtLeast(1)
            Entitled.Uncapped -> when { small -> SMALL_PHONE; totalRamMb in 1..6_000L -> 4; else -> AUTO_MAX }
            Entitled.Unknown -> DEFAULT
        }
        if (small) n = minOf(n, SMALL_PHONE)
        if (thermal >= 3) n = 1 else if (thermal == 2) n = minOf(n, 2)
        if (lowMemoryNow) n = (n / 2).coerceAtLeast(1)
        return n.coerceIn(1, AUTO_MAX)
    }

    /** Jobs to start now: oldest ready ones not already running, up to [limit] running in total. */
    fun toStart(jobs: List<DlJob>, now: Long, running: Set<String>, limit: Int): List<DlJob> {
        val room = limit - running.size
        if (room <= 0) return emptyList()
        return jobs.filter { (it.status == DlStatus.QUEUED || it.status == DlStatus.RUNNING) && it.nextTryAt <= now && it.id !in running }
            .sortedBy { it.createdAt }.take(room)
    }

    /** Parses /v1/entitlements (top level or under "entitlements"/"limits"): null = lifetime, a number = cap. */
    fun parse(body: String): Entitled = runCatching {
        val root = JSONObject(body)
        val o = listOf(root, root.optJSONObject("entitlements"), root.optJSONObject("limits")).firstOrNull { it != null && it.has("max_parallel_downloads") }
            ?: return Entitled.Unknown
        val tier = o.optString("tier", root.optString("tier", root.optString("plan", "")))
        if (o.isNull("max_parallel_downloads")) Entitled.Uncapped
        else Entitled.Capped(o.getInt("max_parallel_downloads").coerceIn(1, AUTO_MAX), tier)
    }.getOrDefault(Entitled.Unknown)

    fun toCache(e: Entitled): String = when (e) { is Entitled.Capped -> "${e.n}|${e.tier}"; Entitled.Uncapped -> "null"; Entitled.Unknown -> "" }
    fun fromCache(s: String): Entitled = when {
        s == "null" -> Entitled.Uncapped
        s.isBlank() -> Entitled.Unknown
        else -> s.substringBefore('|').toIntOrNull()?.let { Entitled.Capped(it, s.substringAfter('|', "")) } ?: Entitled.Unknown
    }

    /**
     * A soft, non-nagging upgrade line: only when the plan's cap is what holds two or more downloads back, and never
     * on the top plans.
     */
    fun nudge(e: Entitled, limit: Int, waiting: Int): String? {
        if (e !is Entitled.Capped || e.n >= 4 || waiting < 2 || limit < e.n) return null
        return if (e.n == 1) "Downloading one at a time on your plan \u00b7 bigger plans run several at once"
        else "Downloading ${e.n} at a time on your plan"
    }
}

/** Wave B 18: fetches and caches the plan's download cap. */
object DlQuota {
    private const val PREFS = "media_dl"
    private const val KEY = "ent"
    private const val AT = "ent_at"
    private const val TTL = 30 * 60_000L

    fun cached(ctx: Context): Entitled = DlConcurrency.fromCache(ctx.getSharedPreferences(PREFS, 0).getString(KEY, "") ?: "")

    /** Network call (call on IO). Signed out = the free plan's 1. Falls back to the cache when offline. */
    fun current(ctx: Context, force: Boolean = false): Entitled {
        val p = ctx.getSharedPreferences(PREFS, 0)
        val token = runCatching { com.blaze.assistant.agent.Settings.gatewayToken() }.getOrDefault("")
        if (token.isBlank()) return Entitled.Capped(1, "basic")
        if (!force && System.currentTimeMillis() - p.getLong(AT, 0) < TTL) cached(ctx).let { if (it != Entitled.Unknown) return it }
        // 0.45.20: one fetch for the plan's numbers. Entitlements (0.45.18, GET /v1/entitlements) already holds
        // max_parallel_downloads next to the monthly counters, so the queue reads it there instead of a second call.
        val got = runCatching { com.blaze.assistant.connect.Entitlements.refresh(ctx, force) }.getOrNull()?.let { fromSnapshot(it) } ?: Entitled.Unknown
        if (got != Entitled.Unknown) { p.edit().putString(KEY, DlConcurrency.toCache(got)).putLong(AT, System.currentTimeMillis()).apply(); return got }
        return cached(ctx)
    }

    /** The parallel cap in an Entitlements snapshot: a number, no cap (lifetime), or Unknown when the server didn't say. */
    fun fromSnapshot(s: com.blaze.assistant.connect.Entitlements.Snapshot): Entitled = when {
        s.maxParallel != null -> Entitled.Capped(s.maxParallel.coerceIn(1, DlConcurrency.AUTO_MAX), s.plan)
        s.parallelUncapped -> Entitled.Uncapped
        else -> Entitled.Unknown
    }

    /** The limit right now on this phone. */
    fun limit(ctx: Context, e: Entitled): Int {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
        val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching { ctx.getSystemService(PowerManager::class.java).currentThermalStatus }.getOrDefault(0) else 0
        return DlConcurrency.limit(e, mi.totalMem / (1024 * 1024), am?.isLowRamDevice == true, mi.lowMemory, thermal)
    }
}

/**
 * Wave B 19: verification. Each part is hashed (SHA-256) as its bytes arrive; a resumed part hashes what is already
 * on disk once, then carries on. A part is good when its length matches what the server said; the finished file's
 * hash and length are re-checked after the atomic move into private storage.
 */
class PartHasher {
    private val md = MessageDigest.getInstance("SHA-256")
    var bytes = 0L; private set
    fun update(b: ByteArray, n: Int) { md.update(b, 0, n); bytes += n }
    fun hex(): String = (md.clone() as MessageDigest).digest().joinToString("") { "%02x".format(it) }
    companion object {
        /** A hasher already fed with the first [len] bytes of [f] (a resumed .part file). */
        fun resume(f: File, len: Long): PartHasher = PartHasher().also { h ->
            if (len > 0 && f.isFile) FileInputStream(f).use { inp ->
                val buf = ByteArray(64 * 1024); var left = len
                while (left > 0) { val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; h.update(buf, n); left -= n }
            }
        }
        fun ofFile(f: File): String = resume(f, f.length()).hex()
    }
}

object DlVerify {
    /** Indices of parts whose file length doesn't match the expected total (unknown totals pass when non-empty). */
    fun badParts(parts: List<DlPart>, lengths: List<Long>): List<Int> = parts.indices.filter { i ->
        val l = lengths.getOrElse(i) { -1L }
        if (parts[i].total > 0) l != parts[i].total else l <= 0
    }

    /** The line the Downloads list shows for a job (never "100%" forever: verifying and saving have their own words). */
    fun statusLine(j: DlJob, size: (Long) -> String = { "$it B" }): String = when (j.status) {
        DlStatus.RUNNING -> when (j.phase) {
            PHASE_VERIFY -> "Verifying\u2026"
            PHASE_SAVE -> "Saving\u2026"
            else -> "${(j.progress * 100).toInt()}% \u00b7 ${size(j.done)} of ${size(j.total)}"
        }
        DlStatus.QUEUED -> if (j.attempts > 0) "Retrying soon \u00b7 ${j.error}" else "Waiting \u00b7 ${j.quality}"
        DlStatus.PAUSED -> "Paused \u00b7 ${(j.progress * 100).toInt()}%"
        DlStatus.FAILED -> "Failed \u00b7 ${j.error}"
        DlStatus.DONE -> if (j.verified) "Saved \u2713 \u00b7 verified" else "Saved"
        else -> j.quality
    }

    const val PHASE_VERIFY = "verifying"
    const val PHASE_SAVE = "saving"
}
