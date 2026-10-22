package com.blaze.assistant.phone

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SafeWalkTest {
    @Test fun messages() {
        val m = SafeWalk.message("Chelstone", -15.37, 28.32, 12, update = false)
        assertTrue(m.contains("12 min after")); assertTrue(m.contains("Chelstone")); assertTrue(m.contains("maps.google.com/?q=-15.37000,28.32000"))
        val u = SafeWalk.message("", null, null, 30, update = true)
        assertTrue(u.startsWith("Safe Walk update")); assertTrue(u.contains("unavailable")); assertFalse(u.contains("check on me"))
    }
}
