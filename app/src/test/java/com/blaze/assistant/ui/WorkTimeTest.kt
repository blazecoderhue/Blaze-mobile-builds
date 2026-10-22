package com.blaze.assistant.ui

import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.28.4: Luke's "Worked · 4 steps · 97m 45s" over steps of 0 s, 11 s, 13 s and 9 s. The work card's total is the
 * turn's real run time, frozen when it finishes.
 */
class WorkTimeTest {
    private val t0 = 1_760_000_000_000L          // the prompt (15:54)
    private val later = t0 + 97 * 60_000L         // the retry / wake (17:31)
    private fun step(id: Long, at: Long, startAt: Long = 0L) = ChatMessage(id, Role.STEP, "step $id", at = at, startAt = startAt)

    /** The screenshot: prompt at 15:54, Retry at 17:31, four steps, the reply 33 s later. */
    private val retried = listOf(step(2, later, startAt = later), step(3, later), step(4, later + 11_000), step(5, later + 24_000))
    private val replyAt = later + 33_000

    @Test fun retriedTurnCountsOnlyItsOwnRun() {
        val start = WorkTime.start(retried, prevAt = t0)
        val end = WorkTime.end(retried, live = false, nextAt = replyAt, nextIsReply = true, now = later + 3_600_000)
        assertEquals(later, start)
        assertEquals(33_000L, WorkTime.total(start, end))
    }

    @Test fun finishedTotalIsFrozen() {
        val start = WorkTime.start(retried, t0)
        val a = WorkTime.end(retried, false, replyAt, true, now = replyAt + 1_000)
        val b = WorkTime.end(retried, false, replyAt, true, now = replyAt + 5 * 3_600_000L)   // hours in the background
        assertEquals(a, b)
        assertEquals(33_000L, WorkTime.total(start, b))
    }

    @Test fun liveCardTicksFromTheTurnStart() {
        val start = WorkTime.start(retried, t0)
        assertEquals(5_000L, WorkTime.total(start, WorkTime.end(retried, live = true, nextAt = 0, nextIsReply = false, now = later + 5_000)))
    }

    @Test fun stoppedTurnEndsAtItsLastStepNotTheNextPrompt() {
        val nextPrompt = later + 40 * 60_000L
        val end = WorkTime.end(retried, live = false, nextAt = nextPrompt, nextIsReply = false, now = nextPrompt)
        assertEquals(later + 24_000, end)
        assertEquals(24_000L, WorkTime.total(WorkTime.start(retried, t0), end))
    }

    @Test fun oldChatsWithoutTheStamp() {
        // A normal turn: the prompt just before the first step still counts (the first model call took 4 s).
        val normal = listOf(step(2, t0 + 4_000), step(3, t0 + 9_000))
        assertEquals(t0, WorkTime.start(normal, t0))
        // The bug's shape in a saved chat: the first step is 97 min after the message before it → start at the first step.
        val stale = listOf(step(2, later), step(3, later + 11_000))
        assertEquals(later, WorkTime.start(stale, t0))
        assertEquals(0L, WorkTime.total(0, 5))
        assertEquals(0L, WorkTime.total(10, 5))
    }
}
