package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.45.0 P3 #12: idle chats leave memory; busy, waiting, unsaved and on-screen ones never do. */
class SessionTrimTest {
    private fun c(id: String, at: Long, current: Boolean = false, busy: Boolean = false, waiting: Boolean = false, saved: Boolean = true) =
        SessionTrim.Cand(id, current, busy, waiting, saved, at)

    @Test fun keepsTheNewestIdleAndNeverTheProtectedOnes() {
        val now = 1_000_000L
        val cands = listOf(c("main", 0, current = true), c("bg", 1, busy = true), c("q", 2, waiting = true), c("unsaved", 3, saved = false),
            c("a", now - 500_000), c("b", now - 400_000), c("c", now - 300_000), c("d", now - 200_000), c("fresh", now - 1_000))
        assertEquals(setOf("a", "b"), SessionTrim.evict(cands, now, keep = 3, minIdleMs = 60_000))
        // On a chat switch everything idle and saved goes (the old behaviour, now only once saved).
        assertEquals(setOf("a", "b", "c", "d", "fresh"), SessionTrim.evict(cands, now, keep = 0, minIdleMs = 0))
    }

    @Test fun recentlyUsedChatsWaitForTheIdleTime() {
        val now = 100_000L
        val cands = (1..6).map { c("s$it", now - it * 1_000L) }
        assertEquals(emptySet<String>(), SessionTrim.evict(cands, now, keep = 3, minIdleMs = 60_000))
    }
}
