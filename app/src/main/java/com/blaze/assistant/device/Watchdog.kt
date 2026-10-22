package com.blaze.assistant.device

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 0.45.16 crash + ANR work (user report: random crashes and freezes on a low-end phone).
 *
 * [MainWatch]: a daemon thread pings the main thread every second. When the main thread has not answered for
 * [ANR_MS] it writes "ANR" with the main thread's stack to the local crash log (once per stall), and a stall over
 * [JANK_MS] is written as "STALL" (at most once a minute), so "it froze" can be traced to the line that blocked.
 *
 * [StartGuard]: startup steps run one by one, each guarded: a step that throws is logged and skipped instead of
 * closing the app (before this, ~25 init calls in BlazeApp.onCreate were unguarded, so one bad file or service meant a
 * crash on every launch). When the last starts crashed within a minute of opening, the optional extras (proactive
 * helpers, orb, dashboard, desktop link, router autostart) wait for a calm start: [safeStart].
 */
object MainWatch {
    const val ANR_MS = 5_000L
    const val JANK_MS = 700L
    @Volatile private var started = false
    @Volatile private var answeredAt = 0L

    fun start() {
        if (started) return
        started = true
        val main = Handler(Looper.getMainLooper())
        answeredAt = SystemClock.uptimeMillis()
        Thread({
            var reportedThisStall = false
            var lastStallLog = 0L
            var pingedAt = SystemClock.uptimeMillis()
            while (true) {
                pingedAt = SystemClock.uptimeMillis()
                main.post { answeredAt = SystemClock.uptimeMillis() }
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { return@Thread }
                val now = SystemClock.uptimeMillis()
                val waited = if (answeredAt >= pingedAt) 0L else now - pingedAt
                when (verdict(waited)) {
                    Verdict.ANR -> if (!reportedThisStall) { reportedThisStall = true; CrashLog.write("ANR main thread blocked ${waited} ms at:\n" + mainStack()) }
                    Verdict.STALL -> if (now - lastStallLog > 60_000) { lastStallLog = now; CrashLog.write("STALL main thread ${waited} ms at:\n" + mainStack().take(1500)) }
                    Verdict.OK -> reportedThisStall = false
                }
                // While the main thread is stuck, keep waiting on the same ping instead of queueing more.
                while (answeredAt < pingedAt && SystemClock.uptimeMillis() - pingedAt < 20_000) {
                    try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                    val w = SystemClock.uptimeMillis() - pingedAt
                    if (verdict(w) == Verdict.ANR && !reportedThisStall) { reportedThisStall = true; CrashLog.write("ANR main thread blocked ${w} ms at:\n" + mainStack()) }
                }
            }
        }, "blaze-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    enum class Verdict { OK, STALL, ANR }
    /** Pure (unit-tested): how long the main thread had not answered a ping, as a verdict. */
    fun verdict(waitedMs: Long): Verdict = when {
        waitedMs >= ANR_MS -> Verdict.ANR
        waitedMs >= JANK_MS -> Verdict.STALL
        else -> Verdict.OK
    }

    private fun mainStack(): String = try {
        Looper.getMainLooper().thread.stackTrace.take(25).joinToString("\n") { "  at $it" }
    } catch (_: Throwable) { "  (no stack)" }
}

object StartGuard {
    private const val PREFS = "blaze_start_guard"
    @Volatile var safeStart = false; private set
    @Volatile private var failed = 0

    /** Pure (unit-tested): crash times (ms since boot of each start that crashed early) → open in safe mode. */
    fun shouldSafeStart(earlyCrashesInARow: Int): Boolean = earlyCrashesInARow >= 2

    /**
     * Called first in onCreate. A start counts as "early crash" until [markHealthy] runs (30 s after opening); two in a
     * row turn on [safeStart] for this launch.
     */
    fun begin(ctx: android.content.Context) {
        try {
            val p = ctx.getSharedPreferences(PREFS, 0)
            val pending = p.getBoolean("pending", false)
            val row = if (pending) p.getInt("row", 0) + 1 else 0
            safeStart = shouldSafeStart(row)
            p.edit().putBoolean("pending", true).putInt("row", row).apply()
            if (safeStart) CrashLog.write("SAFE START: the last $row starts ended within 30 s; optional extras wait")
            Handler(Looper.getMainLooper()).postDelayed({ markHealthy(ctx) }, 30_000)
        } catch (e: Throwable) { CrashLog.quiet("StartGuard.begin", e) }
    }

    fun markHealthy(ctx: android.content.Context) {
        try { ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("pending", false).putInt("row", 0).apply() } catch (_: Throwable) { }
    }

    /** One startup step: timed, and a failure is logged and skipped instead of crashing the app. */
    inline fun step(name: String, block: () -> Unit) {
        val t0 = SystemClock.elapsedRealtime()
        try { block() } catch (e: Throwable) {
            if (e is OutOfMemoryError) throw e
            noteFailed()
            CrashLog.write("START STEP FAILED $name: ${(android.util.Log.getStackTraceString(e) ?: e.toString()).take(2500)}")
        }
        val ms = SystemClock.elapsedRealtime() - t0
        if (ms >= 150) CrashLog.write("SLOW START STEP $name ${ms} ms")
    }

    fun noteFailed() { failed++ }
    val failedSteps: Int get() = failed
}

object MemoryTrim {
    /**
     * Pure (unit-tested): RUNNING_LOW (10) / RUNNING_CRITICAL (15) while open, and BACKGROUND (40) and up once in the
     * background. UI_HIDDEN (20) alone keeps them, so coming back to the chat does not re-parse every row.
     */
    fun shouldDropCaches(level: Int): Boolean = level in 10..15 || level >= 40

    fun dropCaches() {
        com.blaze.assistant.ui.ParseMemo.bubble.clear(); com.blaze.assistant.ui.ParseMemo.segs.clear()
        com.blaze.assistant.ui.ParseMemo.blocks.clear(); com.blaze.assistant.ui.ParseMemo.md.clear()
        CrashLog.write("MEMORY trimmed: parse caches dropped")
    }
}
