package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.33.0 "[m97,m99] The server appears…" showed the ids. */
class RapidBareTest {
    @Test fun bareListHidden() {
        assertEquals("The server appears to be Flask.", RapidMessages.display("[m97,m99] The server appears to be Flask."))
        assertEquals("Yes it is.", RapidMessages.display("Yes [m97] it is."))
        assertEquals("Hi", RapidMessages.display("Hi\n[m97,", final = false))
        val p = RapidMessages.split("[m97,m99] Flask server.", setOf(97L, 99L))
        assertEquals(1, p.size); assertEquals(listOf(97L, 99L), p[0].refs); assertEquals("Flask server.", p[0].text)
    }
}
