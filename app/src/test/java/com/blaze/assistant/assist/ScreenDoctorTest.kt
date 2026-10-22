package com.blaze.assistant.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDoctorTest {
    @Test fun tidyAndPrompt() {
        assertEquals("Error 403 | Try again", ScreenDoctor.tidy(listOf("Error  403", "Error 403", "x", "Try again")))
        assertTrue(ScreenDoctor.tidy(List(500) { "line $it" }, 100).length <= 100)
        val p = ScreenDoctor.prompt("Error 403", "com.example")
        assertTrue(p.startsWith("[Screen Doctor] What's this")); assertTrue(p.contains("Screen text: Error 403")); assertTrue(p.contains("com.example"))
    }
}
