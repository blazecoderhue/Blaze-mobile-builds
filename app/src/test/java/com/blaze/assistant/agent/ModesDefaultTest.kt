package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ModesDefaultTest {
    @Test fun autopilotIsTheDefault() {
        assertEquals("autopilot", ApprovalDefault.DEFAULT)
        assertEquals("autopilot", ApprovalDefault.of(null))
        assertEquals("autopilot", ApprovalDefault.of(""))
        assertEquals("autopilot", ApprovalDefault.of("garbage"))
        // Someone who chose Auto keeps the confirm cards.
        assertEquals("auto", ApprovalDefault.of("auto"))
        assertEquals("autopilot", ApprovalDefault.of("autopilot"))
    }
}
