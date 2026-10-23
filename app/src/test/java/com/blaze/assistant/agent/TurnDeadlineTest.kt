package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.45.9: no turn can run 56 minutes again. */
class TurnDeadlineTest {
    @Test fun normalTurnsEndInFiveMinutes() {
        assertEquals(5 * 60_000L, TurnDeadline.turnMs(autopilot = true, apMinutes = 45, longWork = false))
        assertEquals(5 * 60_000L, TurnDeadline.turnMs(autopilot = false, apMinutes = 45, longWork = true))
        assertEquals(45 * 60_000L, TurnDeadline.turnMs(autopilot = true, apMinutes = 45, longWork = true))
    }

    @Test fun stepBudgetIsCapped() {
        assertEquals(TurnDeadline.STEP_MS, TurnDeadline.stepBudget(10_000_000, 0))
        assertEquals(20_000L, TurnDeadline.stepBudget(120_000, 100_000))
        assertEquals(5_000L, TurnDeadline.stepBudget(100_000, 200_000))
        assertTrue(TurnDeadline.CLOUD_STEP_MS < TurnDeadline.STEP_MS)
    }

    @Test fun agentUsesTheDeadline() {
        val src = listOf(File("src/main/java/com/blaze/assistant/agent/Agent.kt"), File("app/src/main/java/com/blaze/assistant/agent/Agent.kt")).first { it.exists() }.readText()
        assertTrue(src.contains("if (System.currentTimeMillis() > hardEnd())"))
        // 0.45.20: the Cloud step limit starts once the message reached the gateway (offline sends keep retrying).
        assertTrue(src.contains("CloudRuns.timedAfterSent(ct, minOf(budget, TurnDeadline.CLOUD_STEP_MS))"))
        assertTrue(src.contains("withTimeoutOrNull(left) { LlmClient.complete(r, tools, onText, onThink, lane) }"))
    }
}
