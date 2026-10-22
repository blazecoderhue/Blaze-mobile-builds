package com.blaze.assistant.agent

/**
 * v0.45.0 (#12): which chats can leave memory. Every opened chat, background agent, dashboard request and
 * notification reply used to stay in memory with its whole message list and history until it was deleted.
 *
 * A chat leaves memory only when it is not on screen, not working, has nothing waiting, and its newest save has
 * reached the disk ([Cand.saved]); otherwise reloading it from disk could bring back an older copy. Of the chats that
 * may go, the [keep] most recently used stay (quick to come back to), and only those idle for at least [minIdleMs].
 * They reload from disk on demand. Pure; SessionTrimTest.
 */
object SessionTrim {
    data class Cand(val id: String, val current: Boolean, val busy: Boolean, val waiting: Boolean, val saved: Boolean, val lastActive: Long)

    fun evict(cands: List<Cand>, now: Long, keep: Int, minIdleMs: Long): Set<String> {
        val free = cands.filter { !it.current && !it.busy && !it.waiting && it.saved }
            .sortedByDescending { it.lastActive }
        return free.drop(keep.coerceAtLeast(0)).filter { now - it.lastActive >= minIdleMs }.map { it.id }.toSet()
    }

    /** Idle chats kept in memory after a turn. */
    const val KEEP = 3
    /** A chat must be idle this long before it leaves memory after a turn. */
    const val MIN_IDLE_MS = 60_000L
}
