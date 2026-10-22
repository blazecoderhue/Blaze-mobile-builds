package com.blaze.assistant.bank

/**
 * v0.45.0 (#15): how Bank mode watches the app in front. It used to ask UsageStats for the last 10 s of events every
 * 1.5 s for as long as Bank mode was on. Now: every 1.5 s only for the first [SETTLE_MS] after the front app changed
 * (so leaving the bank app is still noticed quickly and the "turn off a few seconds after you leave" grace holds), then
 * every 3 s; and each query only reads the events since the previous one. A Front event is reported once per new
 * resume (the state machine treats repeats as no-ops). Pure; FrontPollTest.
 */
class FrontPoll {
    var lastFront: String? = null; private set
    var lastChangeAt = 0L; private set
    var lastQueryEnd = 0L; private set

    /** Wait before the next query. */
    fun interval(now: Long): Long = if (lastChangeAt == 0L || now - lastChangeAt < SETTLE_MS) FAST_MS else SLOW_MS

    /** Start of the next query window (only events since the last query, at most [WINDOW_MS] back). */
    fun since(now: Long): Long = if (lastQueryEnd <= 0L) now - WINDOW_MS else maxOf(now - WINDOW_MS, lastQueryEnd - OVERLAP_MS)

    /**
     * Records one query ending at [now] whose newest resumed app was [newest] at [newestAt] (null = no resume in the
     * window). Returns the package to report as Front, or null when nothing new resumed.
     */
    fun record(now: Long, newest: String?, newestAt: Long): String? {
        val prevEnd = lastQueryEnd
        lastQueryEnd = now
        if (newest == null) return null
        // The overlap re-reads the last half second: an event already reported before is not reported again.
        if (prevEnd > 0L && newestAt < prevEnd - OVERLAP_MS) return null
        if (newestAt <= reportedAt && newest == lastFront) return null
        if (newest != lastFront) lastChangeAt = now
        lastFront = newest; reportedAt = newestAt
        return newest
    }
    private var reportedAt = 0L

    companion object {
        const val FAST_MS = 1_500L
        const val SLOW_MS = 3_000L
        const val SETTLE_MS = 10_000L
        const val WINDOW_MS = 10_000L
        const val OVERLAP_MS = 500L
    }
}
