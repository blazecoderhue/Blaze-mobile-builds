package com.blaze.assistant.agent

import com.blaze.assistant.phone.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Visible pointer: timing and re-aim (v0.21.0); always on (v0.24.4); never touchable, never pauses, Stop chip only (v0.45.14). */
class PointerTest {
    @Test fun glideIsQuickButVisible() {
        assertEquals(120L, Pointer.glideMs(0f, 0f, 2f))
        assertTrue(Pointer.glideMs(200f, 0f, 2f) in 121..419)
        assertEquals(420L, Pointer.glideMs(4000f, 3000f, 2f))
        assertTrue(Pointer.rippleMs(true) > Pointer.rippleMs(false))
    }

    @Test fun targetThatMovedIsAimedAgain() {
        assertFalse(Pointer.moved(100f, 100f, 120f, 110f, 2f))   // ~11 dp
        assertTrue(Pointer.moved(100f, 100f, 100f, 200f, 2f))    // 50 dp
    }

    @Test fun neverBlocksTouchesAndNeverPauses() {
        val W = android.view.WindowManager.LayoutParams::class.java
        fun flag(n: String) = W.getField(n).getInt(null)
        // The pointer window takes no touch at all: Blaze's taps and the user's go to the app under it.
        assertTrue(Pointer.DOT_FLAGS and flag("FLAG_NOT_TOUCHABLE") != 0)
        assertTrue(Pointer.DOT_FLAGS and flag("FLAG_NOT_FOCUSABLE") != 0)
        assertEquals(0, Pointer.DOT_FLAGS and flag("FLAG_WATCH_OUTSIDE_TOUCH"))
        // The Stop chip only takes touches on itself, never outside, and never watches outside touches.
        assertTrue(Pointer.CHIP_FLAGS and flag("FLAG_NOT_TOUCH_MODAL") != 0)
        assertTrue(Pointer.CHIP_FLAGS and flag("FLAG_NOT_FOCUSABLE") != 0)
        assertEquals(0, Pointer.CHIP_FLAGS and flag("FLAG_WATCH_OUTSIDE_TOUCH"))
        assertTrue(Pointer.CHIP_EDGE_DP <= 12 && Pointer.CHIP_UP_DP >= 48)
        // No paused mode is left: no pause / resume / gate / mode / double-tap API on the pointer.
        val names = Pointer::class.java.declaredMethods.map { it.name.lowercase() } + Pointer::class.java.declaredFields.map { it.name.lowercase() }
        for (bad in listOf("pause", "resume", "gate", "mode", "doubletap", "showbar", "showcheck", "clickline", "checkclick"))
            assertTrue(bad, names.filterNot { it.contains("delegate") }.none { it.contains(bad) })
        // The chip steps aside while Blaze's own gesture is in flight.
        assertTrue(Pointer.chipHidden(1000, 1200)); assertFalse(Pointer.chipHidden(1300, 1200))
    }

    @Test fun onlyStopEndsARun() {
        Pointer.takeStop()
        assertFalse(Pointer.takeStop())
        Pointer.stop()
        assertTrue(Pointer.stopped)
        assertTrue(Pointer.takeStop())
        assertFalse(Pointer.takeStop())          // once
        assertTrue(Pointer.risky("Pay now")); assertFalse(Pointer.risky("Sender name"))   // the in-app browser's check stays
    }

    @Test fun alwaysOnAndEasyToGrab() {
        assertTrue(Pointer.enabled)                       // agent taps always show it; show_pointer is ignored
        assertTrue(Pointer.TOUCH_DP >= 56)
        assertTrue(Pointer.MASCOT_DP in 48..56)
        // v0.32.0: the pointer is the Blaze icon (drop in halo) centred on the click point, inside the touch window.
        assertEquals(Pointer.TOUCH_DP / 2f, Pointer.TIP_X_DP, 0.01f)
        assertEquals(Pointer.TOUCH_DP / 2f, Pointer.TIP_Y_DP, 0.01f)
    }

    @Test fun mascotIsLukesFlame() {
        val M = com.blaze.assistant.phone.Mascot
        val b = M.bounds(M.body)
        assertEquals(-1f, b[0], 0.01f); assertEquals(1f, b[2], 0.01f); assertEquals(1f, b[3], 0.01f)   // round body, radius 1
        assertTrue(b[1] < -1.5f)                                                                        // the flame rises above it
        assertTrue(M.TIP[0] > 0f && M.TIP[1] < -1.5f)                                                   // tip at the top right
        assertEquals(M.HEIGHT, 1f - M.TIP[1], 0.01f)
        assertEquals(2, M.EYES.size); assertTrue(M.EYES.all { it[1] < 0f && it[1] > -0.4f })
        assertEquals(M.STOPS.size, M.COLORS.size)
        assertTrue((M.COLORS.first() shr 16 and 0xFF) > 0xF0)          // orange (red-heavy) at the top
        assertTrue((M.COLORS.last() and 0xFF) > 0xF0)                  // blue at the bottom
        // Walking it anchored at the tip puts the tip exactly on the anchor, and squish keeps it there.
        val p = M.at(M.TIP, 100f, 50f, 20f, 1.2f, 0.8f); assertEquals(100f, p[0], 0.001f); assertEquals(50f, p[1], 0.001f)
        var maxY = -1e9f
        M.walk(M.body, 100f, 50f, 20f, move = { _, y -> maxY = maxOf(maxY, y) }, cubic = { _, _, _, _, _, y -> maxY = maxOf(maxY, y) })
        assertEquals(50f + M.HEIGHT * 20f, maxY, 0.6f)
    }

    @Test fun pulseAndSquishStayLight() {
        assertEquals(1f, Pointer.pulseScale(0), 0.001f)
        assertTrue(Pointer.pulseScale(300) in 1.05f..1.08f)
        val (sx0, sy0) = Pointer.squish(-1f); assertEquals(1f, sx0, 0f); assertEquals(1f, sy0, 0f)
        val (sx, sy) = Pointer.squish(0.5f); assertTrue(sx > 1.1f && sy < 0.9f)
        val (sx1, sy1) = Pointer.squish(1f); assertEquals(1f, sx1, 0.01f); assertEquals(1f, sy1, 0.01f)
    }

    @Test fun workBannerSwipes() {
        val t = 100f
        assertEquals(com.blaze.assistant.ui.BannerSwipe.MINIMISE, com.blaze.assistant.ui.bannerSwipe(0f, -150f, t))   // up
        assertEquals(com.blaze.assistant.ui.BannerSwipe.MINIMISE, com.blaze.assistant.ui.bannerSwipe(-200f, 10f, t))  // left
        assertEquals(com.blaze.assistant.ui.BannerSwipe.MINIMISE, com.blaze.assistant.ui.bannerSwipe(220f, 20f, t))   // right
        assertEquals(com.blaze.assistant.ui.BannerSwipe.OPEN, com.blaze.assistant.ui.bannerSwipe(10f, 150f, t))       // down opens Blaze
        assertEquals(com.blaze.assistant.ui.BannerSwipe.NONE, com.blaze.assistant.ui.bannerSwipe(30f, 40f, t))        // a nudge springs back
        assertEquals(com.blaze.assistant.ui.BannerSwipe.NONE, com.blaze.assistant.ui.bannerSwipe(0f, 0f, t))
    }
}
