package com.blaze.assistant.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.3: an optional look-up that is slow never holds the first model request beyond its budget. */
class PreBudgetTest {
    @Test fun readyValueIsUsed() = runBlocking {
        val d = CompletableDeferred("note"); val late = ArrayList<String>()
        assertEquals("note", PreBudget.within(d, 10, "", "orient") { late += it }); assertTrue(late.isEmpty())
    }

    @Test fun slowLookupIsDroppedAtTheBudget() = runBlocking {
        val late = ArrayList<String>()
        val d = async { delay(5_000); "late note" }
        val t0 = System.currentTimeMillis()
        val r = PreBudget.within(d, 100, "", "orient") { late += it }
        assertEquals("", r); assertEquals(listOf("orient"), late)
        assertTrue(System.currentTimeMillis() - t0 < 1_500); assertTrue(d.isCancelled)
    }

    @Test fun firstWordLine() {
        val l = listOf(SpeedLog.Entry(0, 900, 3000, 1, 10, 1000, "r", "", shownMs = 1200, headersMs = 300),
            SpeedLog.Entry(0, 900, 3000, 1, 10, 1000, "r", "", shownMs = 2400, headersMs = 500),
            SpeedLog.Entry(0, 900, 3000, 1, 10, 1000, "r", ""))
        val s = SpeedLog.firstWordLine(l)
        assertTrue(s, s.contains("First words on screen after Send (last 2)")); assertTrue(s.contains("headers"))
        assertEquals("", SpeedLog.firstWordLine(emptyList()))
    }
}
