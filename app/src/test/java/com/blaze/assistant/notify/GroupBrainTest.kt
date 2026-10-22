package com.blaze.assistant.notify

import com.blaze.assistant.notify.GroupBrain.Pure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupBrainTest {
    private fun l(i: Int, who: String, t: String) = Pure.Line(i * 60_000L, who, t)
    @Test fun ranksAndCompresses() {
        val log = listOf(l(0, "Mwila", "hi all"), l(1, "Mwila", "<Media omitted>"), l(2, "Mwila", "anyone done the lab?"),
            l(30, "Tutor", "The stats test is on Friday 24th at 10:00 in LT2"), l(31, "Ben", "thanks"), l(60, "Ben", "I owe Mwila K50 for the printing"))
        val c = Pure.compress(log)
        assertEquals("hi all / anyone done the lab?", c[0].text); assertEquals(4, c.size)
        val r = Pure.rank(c, "when is the stats test?")
        assertTrue(r.first().text.contains("Friday 24th"))
        assertEquals(1, Pure.owes(c).size)
        assertTrue(Pure.forMe(listOf(l(1, "Ben", "Chanda can you bring the charger")), listOf("Chanda")).isNotEmpty())
    }
}
