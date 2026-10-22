package com.blaze.assistant.proactive

import com.blaze.assistant.proactive.Autopilot.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutopilotTest {
    @Test fun places() {
        val p = Geo.parse("Campus@-12.8000,28.2100,200")!!
        assertEquals("Campus", p.name); assertEquals(200.0, p.radiusM, 0.1)
        assertEquals(150.0, Geo.parse("Home@-12.8,28.2")!!.radiusM, 0.1)
        assertNull(Geo.parse("Campus")); assertNull(Geo.parse("x@99,10"))
        val d = Geo.meters(-12.8, 28.21, -12.801, 28.21); assertTrue(d in 100.0..120.0)
        assertTrue(Geo.inside(p, -12.8005, 28.21, 20.0, null))
        assertFalse(Geo.inside(p, -12.81, 28.21, 20.0, true))
        assertTrue(Geo.inside(p, -12.8021, 28.21, 20.0, true))   // 233 m: still inside thanks to hysteresis
        assertFalse(Geo.inside(p, -12.8021, 28.21, 20.0, false))
    }
}
