package com.blaze.assistant.agent

import com.blaze.assistant.agent.ThinkBudget.Level
import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.33.1 adaptive thinking: short-but-hard thinks, long-but-easy doesn't. */
class ThinkBudgetTest {
    @Test fun levels() {
        for (t in listOf("prove that root 2 is irrational", "why is the sky blue", "solve 3x + 4 = 19", "should I take the job in Lusaka or Kitwe",
            "debug this: ```val x = null!!```", "compare two phones for battery"))
            assertEquals(t, Level.DEEP, ThinkBudget.level(t))
        for (t in listOf("hi", "thanks!", "open whatsapp", "set an alarm for 6", "what's the weather in Ndola", "remind me to call mum",
            "fix the typos: " + "the quick brown fox jumps over teh lazy dog ".repeat(10)))
            assertEquals(t, Level.QUICK, ThinkBudget.level(t))
        assertEquals(Level.NORMAL, ThinkBudget.level("I'm moving to a new flat next month and my landlord keeps sending long messages about the deposit, here they are"))
        assertEquals(Level.NORMAL, ThinkBudget.level("", attachments = 1))
        assertEquals(Level.QUICK, ThinkBudget.level(""))
    }
}
