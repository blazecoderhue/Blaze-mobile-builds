package com.blaze.assistant.blazeos

/**
 * v0.45.0: limits for window.BlazeOS. addJavascriptInterface is visible to every frame of the page (ads, iframes of a
 * hosted app), and the bridge can't tell frames apart, so the bridge itself is made cheap to abuse:
 *  - storage writes are coalesced ([WriteDebounce]): many setItem calls in a burst become one file write;
 *  - notification requests are rate-limited ([Rate]): a frame can't spam prompts or notifications.
 * Pure (clock passed in), unit-tested in OsBridgeLimitsTest.
 */
object OsBridgeLimits {
    const val WRITE_DELAY_MS = 750L
    /** At most this many notify() calls per window per app; the rest are dropped. */
    const val NOTIFY_MAX = 5
    const val NOTIFY_WINDOW_MS = 60_000L

    /** Sliding-window counter. */
    class Rate(private val max: Int, private val windowMs: Long) {
        private val times = ArrayDeque<Long>()
        @Synchronized fun allow(now: Long): Boolean {
            while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
            if (times.size >= max) return false
            times.addLast(now); return true
        }
    }

    /** Says when the pending write is due; a new change inside the delay pushes nothing earlier, so a burst = 1 write. */
    class WriteDebounce(private val delayMs: Long = WRITE_DELAY_MS) {
        private var dirtySince = -1L
        /** Marks dirty; true when the caller must schedule a flush (none pending yet). */
        @Synchronized fun mark(now: Long): Boolean { if (dirtySince >= 0) return false; dirtySince = now; return true }
        @Synchronized fun due(now: Long): Boolean = dirtySince >= 0 && now - dirtySince >= delayMs
        @Synchronized fun flushed() { dirtySince = -1L }
        @Synchronized fun pending(): Boolean = dirtySince >= 0
        val delay: Long get() = delayMs
    }
}
