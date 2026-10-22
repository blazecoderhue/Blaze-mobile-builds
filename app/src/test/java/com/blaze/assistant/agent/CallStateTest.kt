package com.blaze.assistant.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** v0.45.0 P3 #3 / #5: per-turn model-call state, and Stop that really ends a blocked request. */
class CallStateTest {
    @Test fun twoTurnsAtOnceNeverShareFlagsUsageOrTiming() = runBlocking {
        val a = CallState(); val b = CallState()
        val ja = async(Dispatchers.IO + a.element()) {
            LlmClient.liteTurn = true
            LlmClient.lastUsage = JSONObject().put("prompt_tokens", 11)
            LlmClient.lastRoute = "A"
            delay(50)
            Triple(LlmClient.liteTurn, LlmClient.lastUsage?.optInt("prompt_tokens"), LlmClient.lastRoute)
        }
        val jb = async(Dispatchers.IO + b.element()) {
            delay(10)
            LlmClient.heavyTurn = true
            LlmClient.lastUsage = JSONObject().put("prompt_tokens", 99)
            LlmClient.lastRoute = "B"
            delay(60)
            Triple(LlmClient.liteTurn, LlmClient.lastUsage?.optInt("prompt_tokens"), LlmClient.lastRoute)
        }
        assertEquals(Triple(true, 11, "A"), ja.await())
        assertEquals(Triple(false, 99, "B"), jb.await())
        assertFalse(a.heavy); assertTrue(b.heavy)
        assertFalse("nothing leaked into the shared state", CallState.GLOBAL.lite || CallState.GLOBAL.heavy)
    }

    @Test fun theTurnModelPinIsPerTurn() = runBlocking {
        val a = CallState(); val b = CallState()
        withContext(a.element()) { TurnModel.current = TurnModel("t-a") }
        withContext(b.element()) { assertEquals(null, TurnModel.current); TurnModel.current = TurnModel("t-b") }
        withContext(a.element()) { assertEquals("t-a", TurnModel.current?.id) }
    }

    @Test fun aForkedCallBeatsItsTurnAndPublishesItsResults() = runBlocking {
        val turn = CallState().also { it.lite = true }
        val call = turn.fork()
        assertTrue(call.lite)
        withContext(Dispatchers.IO + call.element()) {
            LlmClient.lastChunkAt = 12_345L
            LlmClient.lastUsage = JSONObject().put("completion_tokens", 7)
            LlmClient.lastFirstTokenAt = 50
        }
        assertEquals(12_345L, turn.chunkAt.get())        // the turn's watchdog sees the chunk at once
        assertEquals(null, turn.usage)
        call.publish()
        assertEquals(7, turn.usage?.optInt("completion_tokens"))
        assertEquals(50L, turn.firstTokenAt)
        // A sibling call's silence is its own: its chunk time is not the other call's.
        val sib = turn.fork()
        assertEquals(0L, sib.chunkAt.get())
    }

    @Test fun cancellingClosesTheBlockedReadAndEndsAsCancellation() = runBlocking {
        val released = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            LlmClient.cancellableOn({ closed.set(true); released.countDown() }) {
                started.countDown()
                // a socket read that only returns when the connection is closed
                if (!released.await(10, TimeUnit.SECONDS)) error("never closed")
                throw java.io.IOException("Socket closed")
            }
            fail("must not continue after Stop")
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val t0 = System.currentTimeMillis()
        job.cancel()
        withTimeout(3_000) { job.join() }
        assertTrue(closed.get())
        assertTrue("Stop ended the call at once", System.currentTimeMillis() - t0 < 2_000)
    }

    @Test fun aNormalCallIsNeverClosedEarlyAndErrorsStayErrors() = runBlocking {
        val closed = AtomicBoolean(false)
        assertEquals(42, LlmClient.cancellableOn({ closed.set(true) }) { 42 })
        assertFalse(closed.get())
        try { LlmClient.cancellableOn({ closed.set(true) }) { throw LlmClient.LlmException("busy", 503) }; fail() }
        catch (e: LlmClient.LlmException) { assertEquals(503, e.code) }
        catch (e: CancellationException) { fail("a real failure is not a Stop") }
        assertFalse(closed.get())
    }
}
