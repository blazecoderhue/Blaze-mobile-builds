package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloStudioTest {
    @Test fun describes() {
        assertEquals("", HaloStudio.describe(HaloStudio.DEFAULT))
        val p = HaloStudio.P("Kopala Halo", humour = 9, formality = 1, langs = mapOf("Bemba" to 30), boundaries = "never swear")
        val d = HaloStudio.describe(p)
        assertTrue(d.contains("funny")); assertTrue(d.contains("casual")); assertTrue(d.contains("Bemba in about 30%")); assertTrue(d.contains("never swear"))
        assertEquals(p, HaloStudio.fromJson(HaloStudio.toJson(p)))
    }
}
