package com.blaze.assistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallCopilotTest {
    @Test fun helpers() {
        val P = CallCopilot.Pure
        assertEquals("Listening\u2026", P.tail(emptyList()))
        assertEquals("b c \u00b7 d e", P.tail(listOf("a a a a a a a a a", "b c", "d e"), 12))
        assertFalse(P.worth(listOf("hello", "yes")))
        assertTrue(P.worth(listOf("so we meet on friday at ten at the office", "okay bring the documents please")))
        assertTrue(P.summaryPrompt(listOf("x"), 3).contains("(3 min)"))
        assertTrue(P.suggestPrompt(listOf("x")).startsWith("@halo"))
    }
}
