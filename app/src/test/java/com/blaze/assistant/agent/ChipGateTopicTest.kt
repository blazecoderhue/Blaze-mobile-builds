package com.blaze.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.45.16: an explicit topic switch keeps the earlier topic's names out of this turn's chips (design suite chat-hard-switch). */
class ChipGateTopicTest {
    private val earlier = listOf("help me plan a 3 day trip to Livingstone",
        "Day 1: Victoria Falls rainforest walk and a sunset cruise on the Zambezi. Want me to price a lodge?")

    @Test fun topicSwitchDropsStaleNamesFromChips() {
        val st = ChipGate.staleTerms("ok totally different thing. explain how photosynthesis works in two sentences", earlier)
        assertTrue("livingstone" in st && "zambezi" in st && "victoria" in st)
        assertFalse("want" in st)
        val st2 = ChipGate.staleTerms("ok totally different thing. explain how photosynthesis works", earlier, listOf(earlier[0]))
        assertTrue("trip" in st2 && "help" !in st2)
        try {
            ChipGate.stale = st
            assertFalse(ChipGate.allowed("check Livingstone weather"))
            assertTrue(ChipGate.allowed("show a diagram"))
        } finally { ChipGate.stale = emptySet() }
    }

    @Test fun noSwitchKeepsEverything() {
        assertTrue(ChipGate.staleTerms("what about Day 2 in Livingstone?", earlier).isEmpty())
        assertTrue(ChipGate.allowed("check Livingstone weather"))
    }
}
