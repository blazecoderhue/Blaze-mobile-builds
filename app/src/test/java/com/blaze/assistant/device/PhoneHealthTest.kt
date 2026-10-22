package com.blaze.assistant.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHealthTest {
    @Test fun grades() {
        val ok = PhoneHealth.grade(PhoneHealth.Input(80, false, 31f, 40f, 128f, 3000, 8000, 20f, false, 2, 100))
        assertEquals("healthy", ok.grade); assertTrue(ok.fixes.isEmpty())
        val bad = PhoneHealth.grade(PhoneHealth.Input(10, false, 44f, 2f, 64f, 300, 4000, 400f, false, 15, 3000))
        assertEquals("struggling", bad.grade); assertTrue(bad.fixes.first().startsWith("Storage is almost full"))
    }
}
