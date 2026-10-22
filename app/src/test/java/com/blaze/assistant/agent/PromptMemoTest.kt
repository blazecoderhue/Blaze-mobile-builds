package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptMemoTest {
    @Test fun reusesUntilKeyChangesOrTimePasses() {
        var now = 0L
        var builds = 0
        val m = PromptMemo<String>(20_000) { now }
        repeat(6) { m.get(1L) { builds++; "block" } }   // a 6-step turn
        assertEquals(1, builds)
        m.get(2L) { builds++; "new" }                    // memory changed
        assertEquals(2, builds)
        now += 20_001
        assertEquals("again", m.get(2L) { builds++; "again" })
        assertEquals(3, builds)
        m.clear(); m.get(2L) { builds++; "x" }
        assertEquals(4, builds)
    }
}
