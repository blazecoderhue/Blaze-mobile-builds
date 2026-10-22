package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.26.2 non-blocking dispatch: slots, one specialist per target, scope line, results as they land, browser lease. */
class DispatchTest {
    private val N = Dispatch.Kind.NETWORK; private val P = Dispatch.Kind.PHONE; private val G = Dispatch.Kind.GENERAL

    @Test fun networkSpecialistsRunThreeAtOnceEvenOnLowEndPhones() {
        // low-end phone: general cap 1.
        assertTrue(Dispatch.canRun(N, listOf(N, N), 1)); assertFalse(Dispatch.canRun(N, listOf(N, N, N), 1))
        assertTrue("network work doesn't take the general slot", Dispatch.canRun(N, listOf(G), 1))
        assertTrue(Dispatch.canRun(G, listOf(N, N, N), 1)); assertFalse(Dispatch.canRun(G, listOf(G), 1))
        // Phone specialists take turns (the phone lock) and share the general slot.
        assertFalse(Dispatch.canRun(P, listOf(P), 2)); assertFalse(Dispatch.canRun(P, listOf(G), 1)); assertTrue(Dispatch.canRun(P, listOf(N), 1))
        // A queued general agent doesn't block network specialists behind it.
        assertEquals(1, Dispatch.pick(listOf(G, N, N), listOf(G), 1))
        assertEquals(-1, Dispatch.pick(listOf(P, G), listOf(G), 1))
        assertEquals(G, Dispatch.kind(null)); assertEquals(P, Dispatch.kind(setOf("phone", "comms"))); assertEquals(N, Dispatch.kind(setOf("web", "browser")))
    }

    @Test fun oneSpecialistPerTarget() {
        val b = listOf(Dispatch.Brief("shopper", "Price of Galaxy A15 128 GB at Game Zambia, delivered to Ndola"),
            Dispatch.Brief("shopper", "Price of Galaxy A15 128 GB at Game Zambia delivered to Ndola"),
            Dispatch.Brief("shopper", "Price of Galaxy A15 128 GB at Jumia Zambia, delivered to Ndola, new only"),
            Dispatch.Brief("researcher", ""))
        val live = listOf(Dispatch.Brief("shopper", "Price of Galaxy A15 128 GB at Jumia Zambia, delivered to Ndola, new only"))
        val p = Dispatch.plan(b, live)
        assertEquals(1, p.accepted.size)
        assertEquals(3, p.rejected.size)
        assertTrue(p.rejected.any { it.second.contains("same target") }); assertTrue(p.rejected.any { it.second.contains("message_agent") })
        assertTrue(p.rejected.any { it.second == "empty brief" })
        assertEquals(6, Dispatch.plan((1..8).map { Dispatch.Brief("researcher", "Topic number $it about distinct subject $it alpha$it") }, emptyList()).accepted.size)
    }

    @Test fun scopeLineAndImmediateReturn() {
        assertEquals("", Dispatch.scopeLine("", listOf("Researcher")))
        assertEquals("Sending Researcher and Shopper ×2 to work on this at the same time. Results land here as each one finishes.",
            Dispatch.scopeLine("", listOf("Researcher", "Shopper", "Shopper")))
        assertEquals("Sending Researcher and Shopper to compare solar kits.", Dispatch.scopeLine("Sending Researcher and Shopper to compare solar kits.\nmore", listOf("Researcher", "Shopper")))
        val out = Dispatch.startedLine(listOf("B3" to "Researcher", "B4" to "Shopper"), emptyList(), 1, scopeShown = true)
        assertTrue(out.startsWith("Started B3 (Researcher), B4 (Shopper)."))
        assertTrue(out.contains("Don't wait")); assertTrue(out.contains("don't repeat it")); assertTrue(out.contains("1 of them wait"))
        assertEquals("Price of Galaxy A15 at Game…", Dispatch.title("Goal: price of Galaxy A15 at Game Zambia, delivered"))
    }

    @Test fun eachResultPostsAsItLands() {
        val m = Dispatch.resultMessage("B3", "Shopper", "A15 at Game", "done", 4, 30, "K3,499 at Game", othersLive = 1)
        assertTrue(m.startsWith(Dispatch.RESULT_PREFIX)); assertTrue(m.contains("K3,499")); assertTrue(m.contains("short follow-up"))
        assertTrue(m.contains("Don't wait for them"))
        assertTrue(Dispatch.resultMessage("B4", "Shopper", "t", "done", 1, 1, "x", 0).contains("last one"))
        assertTrue(Dispatch.resultMessage("B4", "Shopper", "t", "failed", 1, 1, "boom", 0).contains("could not finish"))
        // The finished card drops to the bottom of the chat (under anything said since).
        assertEquals(listOf("a", "c", "B3!"), Dispatch.moveToEnd(listOf("a", "B3", "c"), { it == "B3" }, { "$it!" }))
        assertEquals(listOf("a"), Dispatch.moveToEnd(listOf("a"), { it == "zz" }))
    }

    @Test fun browserLease() {
        val l = Dispatch.Lease()
        assertNull(l.take("B1")); assertNull(l.take("B1"))
        assertTrue(l.take("B2")!!.contains("B1"))
        assertNull("a finished holder doesn't block", l.take("B2") { it != "B1" })
        l.release("B2"); assertNull(l.take("B3"))
    }
}
