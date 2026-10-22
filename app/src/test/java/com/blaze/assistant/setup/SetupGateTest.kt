package com.blaze.assistant.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.24.4 dependency gate: upgraded users with missing dependencies get it; complete users don't. */
class SetupGateTest {
    private val all = SetupGate.deps.associate { it.id to true }

    @Test fun upgradedUserWithMissingDependenciesGetsTheGate() {
        // Upgraded from v0.24.1: onboarded long ago, never installed Linux, never paired Desktop.
        val have = all + mapOf("linux" to false, "desktop" to false)
        assertTrue(SetupGate.shouldGate(done = false, have = have, later = emptySet()))
        assertEquals(listOf("linux", "desktop"), SetupGate.pending(have, emptySet()).map { it.id })
        assertEquals(listOf("linux"), SetupGate.blocking(have).map { it.id })
    }

    @Test fun completeUserDoesNot() {
        assertFalse(SetupGate.shouldGate(false, all, emptySet()))
        assertTrue(SetupGate.complete(all, emptySet()))
    }

    @Test fun optionalStepsCanBePutOffRequiredCannot() {
        val have = all + mapOf("desktop" to false, "mic" to false)
        assertFalse(SetupGate.shouldGate(false, have, setOf("desktop", "mic")))
        // "Later" on a required step doesn't count.
        val noLinux = all + mapOf("linux" to false)
        assertTrue(SetupGate.shouldGate(false, noLinux, setOf("linux")))
        assertTrue(SetupGate.dep("linux")!!.required)
        assertTrue(SetupGate.dep("notifications")!!.required)
        assertFalse(SetupGate.dep("desktop")!!.required)
    }

    @Test fun neverRunsAgainOnceDone() {
        val have = all + mapOf("linux" to false)
        assertFalse(SetupGate.shouldGate(done = true, have = have, later = emptySet()))
    }

    @Test fun laterRoundTrips() {
        assertEquals(setOf("desktop", "mic"), SetupGate.parseLater(SetupGate.joinLater(setOf("mic", "desktop"))))
        assertEquals(emptySet<String>(), SetupGate.parseLater(""))
    }
}
