package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTimerTest {
    @Test fun booksPhasesAndTotals() {
        var now = 1_000L
        val t = TurnTimer { now }
        now += 3; t.mark("route")
        now += 12; t.mark("tools")
        now += 40; t.mark("prep")
        now += 20; t.mark("prompt")
        t.add("first word", 1900)
        now += 4200; t.mark("model")
        now += 9800; t.mark("tool work")
        now += 1500; t.mark("model")
        assertEquals(75, t.prepMs())
        assertEquals(5700, t.get("model"))
        assertEquals(15_575, t.total())
        assertEquals("tool work" to 9800L, t.slowest())
        val line = t.line()
        assertTrue(line, line.startsWith("route 3 ms · tools 12 ms · prep 40 ms · prompt 20 ms · first word 1.9 s · model 5.7 s · tool work 9.8 s"))
        assertTrue(line, line.endsWith("total 15.6 s"))
    }

    @Test fun skipMovesTheMarkWithoutBooking() {
        var now = 0L
        val t = TurnTimer { now }
        now = 500; t.skip()
        now = 600; t.mark("prompt")
        assertEquals(100, t.get("prompt"))
    }
}
