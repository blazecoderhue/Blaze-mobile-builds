package com.blaze.assistant.browser

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Browser performance policy (pure Kotlin, JVM-tested in PerfPolicyTest).
 *
 * The phone's hardware is read once ([BrowserPerf.hardware]) and turned into a continuous score from 0 (very
 * constrained) to 1 (flagship). Every limit the browser uses (live WebViews kept warm, tab thumbnail size, how many
 * thumbnails are kept, off-screen pre-raster, toolbar animation length, how much
 * back/forward state is written to disk, how long saves are debounced, how hard to free memory on onTrimMemory) is
 * scaled from that score. There are no per-device or per-model rules: only RAM, the per-app heap class, CPU cores,
 * Android's media performance class and the low-RAM flag. A coarse [PerfTier] is derived from the same score for the
 * few on/off features and for logs.
 *
 * The user can override it in the browser menu (Browser speed: Auto / Performance / Battery saver), see [SpeedMode].
 */
enum class PerfTier { LOW, MID, HIGH }

enum class SpeedMode(val id: String, val label: String) {
    AUTO("auto", "Auto"), PERFORMANCE("performance", "Performance"), BATTERY("battery", "Battery saver");

    fun next(): SpeedMode = entries[(ordinal + 1) % entries.size]

    companion object {
        const val KEY = "browser_speed_mode"
        fun of(id: String?): SpeedMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

object PerfPolicy {
    /** What the phone reports. [perfClass] is Build.VERSION.MEDIA_PERFORMANCE_CLASS (0 = not declared / before Android 12). */
    data class Hardware(
        val totalRamBytes: Long,
        val lowRamDevice: Boolean,
        val memoryClassMb: Int,
        val cores: Int,
        val sdk: Int,
        val perfClass: Int = 0,
    )

    /** Limits the browser runs with. All derived from [score]; nothing here is device-specific. */
    data class Budget(
        val score: Float,
        val tier: PerfTier,
        /** WebViews kept alive (the tab on screen included); older ones are discarded and restored from saved state. */
        val maxLiveWebViews: Int,
        /** Tab-grid thumbnail scale relative to the page's on-screen size. */
        val thumbScale: Float,
        /** Thumbnails kept in memory (most recently used tabs first). */
        val maxThumbs: Int,
        /** WebSettings.setOffscreenPreRaster on live tabs: instant tab switches, more GPU memory. */
        val offscreenPreRaster: Boolean,
        /** Toolbar slide / fade length in ms (0 = instant). */
        val animMs: Int,
        /** Total back/forward state (base64 chars) written with the tab list. */
        val persistStateBudget: Int,
        /** Debounce for writing the tab list after a change. */
        val saveDebounceMs: Long,
    )

    const val LOW_MAX = 0.34f
    const val MID_MAX = 0.67f
    const val MAX_LIVE = 8
    const val ICON_MAX_PX = 64

    private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * clamp01(t)
    private fun log2(x: Double) = ln(x) / ln(2.0)

    /** 2 GB → 0, 12 GB → 1, logarithmic in between (each doubling of RAM is worth the same). */
    fun ramScore(totalRamBytes: Long): Float {
        val gb = totalRamBytes / (1024.0 * 1024 * 1024)
        if (gb <= 0.0) return 0f
        return clamp01(((log2(gb) - 1.0) / (log2(12.0) - 1.0)).toFloat())
    }

    /** Per-app heap class: 96 MB → 0, 384 MB → 1. */
    fun heapScore(memoryClassMb: Int): Float = clamp01((memoryClassMb - 96) / 288f)

    /** 2 cores → 0, 8+ cores → 1. */
    fun coreScore(cores: Int): Float = clamp01((cores - 2) / 6f)

    /** Media performance class: 0 = unknown (left out of the score), 30 (Android 11 class) → 0.7, 34+ → 1. */
    fun perfClassScore(perfClass: Int): Float? = if (perfClass <= 0) null else clamp01(0.7f + (perfClass - 30) * 0.075f)

    /** The continuous hardware score, 0..1. */
    fun score(hw: Hardware): Float {
        val parts = mutableListOf(ramScore(hw.totalRamBytes) to 0.5f, heapScore(hw.memoryClassMb) to 0.2f, coreScore(hw.cores) to 0.15f)
        perfClassScore(hw.perfClass)?.let { parts += it to 0.15f }
        var s = parts.sumOf { (v, w) -> (v * w).toDouble() }.toFloat() / parts.sumOf { it.second.toDouble() }.toFloat()
        if (hw.sdk in 1 until 29) s -= 0.05f          // older WebView / ART: a little more conservative
        if (hw.lowRamDevice) s = minOf(s, 0.2f)       // Android itself says this phone is memory-constrained
        return clamp01(s)
    }

    /** Applies the user's Browser speed choice. Performance lifts the score (still bounded by RAM in [budget]); Battery saver caps it. */
    fun applyMode(score: Float, mode: SpeedMode): Float = when (mode) {
        SpeedMode.AUTO -> score
        SpeedMode.PERFORMANCE -> clamp01(score + 0.3f)
        SpeedMode.BATTERY -> minOf(score, 0.15f)
    }

    fun tier(score: Float): PerfTier = when {
        score < LOW_MAX -> PerfTier.LOW
        score < MID_MAX -> PerfTier.MID
        else -> PerfTier.HIGH
    }

    /** Hard ceiling on live WebViews from RAM alone (about one per 1.5 GB, at least one), whatever the mode. */
    fun ramLiveCap(totalRamBytes: Long): Int {
        val gb = totalRamBytes / (1024.0 * 1024 * 1024)
        return (gb / 1.5).toInt().coerceIn(1, MAX_LIVE)
    }

    fun budget(hw: Hardware, mode: SpeedMode = SpeedMode.AUTO): Budget = budgetFor(applyMode(score(hw), mode), hw.totalRamBytes)

    fun budgetFor(s0: Float, totalRamBytes: Long = Long.MAX_VALUE): Budget {
        val s = clamp01(s0)
        val t = tier(s)
        val live = lerp(1f, MAX_LIVE.toFloat(), s).roundToInt().coerceIn(1, minOf(MAX_LIVE, ramLiveCap(totalRamBytes)))
        return Budget(
            score = s,
            tier = t,
            maxLiveWebViews = live,
            thumbScale = lerp(0.15f, 0.35f, s),
            maxThumbs = lerp(6f, 40f, s).roundToInt(),
            offscreenPreRaster = s >= 0.75f,
            animMs = if (s < 0.2f) 0 else lerp(140f, 220f, (s - 0.2f) / 0.8f).roundToInt(),
            persistStateBudget = lerp(1_000_000f, TabStore.MAX_TOTAL_STATE.toFloat(), s).roundToInt(),
            saveDebounceMs = lerp(3000f, 1500f, s).roundToInt().toLong(),
        )
    }

    // ---------- onTrimMemory ----------
    // ComponentCallbacks2 levels, copied so this file stays pure.
    const val TRIM_RUNNING_MODERATE = 5
    const val TRIM_RUNNING_LOW = 10
    const val TRIM_RUNNING_CRITICAL = 15
    const val TRIM_UI_HIDDEN = 20
    const val TRIM_BACKGROUND = 40
    const val TRIM_MODERATE = 60

    /** Live WebViews to keep (the tab on screen always counts as one) after Android reports memory pressure [level]. */
    fun keepLiveOnTrim(level: Int, b: Budget): Int {
        val max = b.maxLiveWebViews
        val half = (max / 2).coerceAtLeast(1)
        return when {
            level >= TRIM_MODERATE -> 1
            level >= TRIM_BACKGROUND -> if (b.tier == PerfTier.HIGH) half else 1
            level >= TRIM_UI_HIDDEN -> if (b.tier == PerfTier.LOW) 1 else max
            level >= TRIM_RUNNING_CRITICAL -> 1
            level >= TRIM_RUNNING_LOW -> if (b.tier == PerfTier.HIGH) half else 1
            level >= TRIM_RUNNING_MODERATE -> if (b.tier == PerfTier.LOW) (max - 1).coerceAtLeast(1) else max
            else -> max
        }.coerceIn(1, max)
    }

    /** Thumbnails to keep after memory pressure [level]. */
    fun keepThumbsOnTrim(level: Int, b: Budget): Int = when {
        level >= TRIM_MODERATE || level == TRIM_RUNNING_CRITICAL -> 0
        level >= TRIM_BACKGROUND || level == TRIM_RUNNING_LOW -> b.maxThumbs / 3
        level >= TRIM_UI_HIDDEN && b.tier == PerfTier.LOW -> b.maxThumbs / 3
        else -> b.maxThumbs
    }

    // ---------- which tabs to put to sleep ----------
    /** A tab as the discard policy sees it. [busy] = loading, or otherwise not safe to discard right now. */
    data class TabUse(val id: Int, val live: Boolean, val current: Boolean, val busy: Boolean, val lastShown: Long)

    /**
     * Ids of live tabs to discard so at most [maxLive] stay alive: least recently shown first. The tab on screen and
     * busy tabs are never picked (so the result can leave more than [maxLive] alive when many tabs are busy).
     */
    fun toDiscard(tabs: List<TabUse>, maxLive: Int): List<Int> {
        val live = tabs.filter { it.live }
        val over = live.size - maxLive.coerceAtLeast(1)
        if (over <= 0) return emptyList()
        return live.filter { !it.current && !it.busy }.sortedBy { it.lastShown }.take(over).map { it.id }
    }

    /** Ids of tabs whose thumbnails to drop so at most [keep] remain: least recently shown first. */
    fun thumbsToDrop(tabs: List<TabUse>, withThumb: Set<Int>, keep: Int): List<Int> {
        val have = tabs.filter { it.id in withThumb }
        val over = have.size - keep.coerceAtLeast(0)
        if (over <= 0) return emptyList()
        return have.sortedBy { it.lastShown }.take(over).map { it.id }
    }
}
