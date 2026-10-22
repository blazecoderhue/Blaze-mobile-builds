package com.blaze.assistant.bank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v0.45.0 P3 #15: Bank mode polls less and reads only new usage events. */
class FrontPollTest {
    @Test fun fastRightAfterAChangeThenSlow() {
        val p = FrontPoll()
        assertEquals(FrontPoll.FAST_MS, p.interval(0))
        assertEquals("bank", p.record(1_000, "bank", 900))
        assertEquals(FrontPoll.FAST_MS, p.interval(5_000))
        assertEquals(FrontPoll.SLOW_MS, p.interval(12_000))
        // Leaving the bank app speeds polling up again (the auto-off grace stays a few seconds).
        assertEquals("home", p.record(20_000, "home", 19_500))
        assertEquals(FrontPoll.FAST_MS, p.interval(21_000))
    }

    @Test fun queriesOnlyTheNewEventsAndReportsEachResumeOnce() {
        val p = FrontPoll()
        assertEquals(0L, p.since(10_000))                       // first query: the last 10 s
        assertEquals("bank", p.record(10_000, "bank", 9_000))
        assertEquals(9_500L, p.since(13_000))                  // then from the previous end (minus a small overlap)
        assertNull(p.record(13_000, "bank", 9_000))             // the same resume, seen again in the overlap → not again
        assertNull(p.record(16_000, null, 0))
        assertEquals("bank", p.record(19_000, "bank", 18_000))  // a new resume of the same app is reported (idempotent upstream)
        assertEquals(20_000L, p.since(30_000))                 // never more than 10 s back
    }
}
