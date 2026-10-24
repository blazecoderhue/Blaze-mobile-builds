package com.blaze.assistant.browser

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Reads the phone's hardware once and keeps the browser's [PerfPolicy.Budget]. The user's Browser speed choice
 * (Settings key [SpeedMode.KEY]) is applied on top; changing it recomputes the budget without re-reading hardware.
 */
object BrowserPerf {
    @Volatile private var hw: PerfPolicy.Hardware? = null
    @Volatile private var cached: PerfPolicy.Budget? = null

    fun hardware(ctx: Context): PerfPolicy.Hardware = hw ?: detect(ctx.applicationContext).also { hw = it }

    private fun detect(ctx: Context): PerfPolicy.Hardware {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        try { am?.getMemoryInfo(mi) } catch (_: Throwable) { }
        val perfClass = if (Build.VERSION.SDK_INT >= 31) try { Build.VERSION.MEDIA_PERFORMANCE_CLASS } catch (_: Throwable) { 0 } else 0
        return PerfPolicy.Hardware(
            totalRamBytes = mi.totalMem,
            lowRamDevice = try { am?.isLowRamDevice == true } catch (_: Throwable) { false },
            memoryClassMb = try { am?.memoryClass ?: 128 } catch (_: Throwable) { 128 },
            cores = Runtime.getRuntime().availableProcessors(),
            sdk = Build.VERSION.SDK_INT,
            perfClass = perfClass,
        )
    }

    var mode: SpeedMode
        get() = SpeedMode.of(runCatching { com.blaze.assistant.agent.Settings.str(SpeedMode.KEY, SpeedMode.AUTO.id) }.getOrNull())
        set(v) { runCatching { com.blaze.assistant.agent.Settings.setStr(SpeedMode.KEY, v.id) }; cached = null }

    /** The current budget. Before [hardware] has been read (no Context yet) a middle profile is used. */
    fun budget(): PerfPolicy.Budget = cached ?: (hw?.let { PerfPolicy.budget(it, mode) } ?: PerfPolicy.budgetFor(0.5f)).also { if (hw != null) cached = it }

    fun budget(ctx: Context): PerfPolicy.Budget { hardware(ctx); return budget() }

    /** One line for logs / crash reports. */
    fun describe(): String { val h = hw; val b = budget()
        return "browser perf ${b.tier} score=${"%.2f".format(b.score)} mode=${mode.id} live=${b.maxLiveWebViews} thumbs=${b.maxThumbs}@${"%.2f".format(b.thumbScale)}" +
            (h?.let { " ram=${it.totalRamBytes / (1024 * 1024)}MB heap=${it.memoryClassMb}MB cores=${it.cores} sdk=${it.sdk} pc=${it.perfClass} lowRam=${it.lowRamDevice}" } ?: "") }
}
