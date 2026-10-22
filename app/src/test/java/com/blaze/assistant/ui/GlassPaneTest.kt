package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.5: the Home glass windows are fixed (no grips) and only lean when you slide beside the page, never on it. */
class GlassPaneTest {
    // The page's box inside a 1080 x 2000 screen.
    private val l = 90f; private val t = 400f; private val r = 990f; private val b = 1700f

    @Test fun onThePageOnlyScrolls() {
        assertFalse(GlassTilt.tiltsFrom(500f, 900f, l, t, r, b))
        assertFalse(GlassTilt.tiltsFrom(l, t, l, t, r, b))
        for (y in 420..1680 step 60) assertFalse("y=$y", GlassTilt.tiltsFrom(540f, y.toFloat(), l, t, r, b))
    }

    @Test fun frameAndBackdropTilt() {
        assertTrue(GlassTilt.tiltsFrom(500f, 350f, l, t, r, b))    // title bar
        assertTrue(GlassTilt.tiltsFrom(60f, 900f, l, t, r, b))     // left rim
        assertTrue(GlassTilt.tiltsFrom(500f, 1900f, l, t, r, b))   // backdrop below the window
        assertTrue(GlassTilt.tiltsFrom(10f, 10f, l, t, r, b))
        // Before the page is measured (an empty box) anywhere tilts.
        assertTrue(GlassTilt.tiltsFrom(500f, 900f, 0f, 0f, 0f, 0f))
    }

    @Test fun tiltIsSlight() {
        assertTrue(GlassTilt.MAX_Y <= 8f && GlassTilt.MAX_X <= 8f)
        assertEquals(GlassTilt.MAX_Y, GlassTilt.tilt(99_999f, 1000f, GlassTilt.MAX_Y), 0.001f)
        assertEquals(-GlassTilt.MAX_X, GlassTilt.tilt(-99_999f, 1000f, GlassTilt.MAX_X), 0.001f)
    }

    private fun src(name: String): String =
        listOf(java.io.File("src/main/java/com/blaze/assistant/ui/$name"), java.io.File("app/src/main/java/com/blaze/assistant/ui/$name"))
            .first { it.exists() }.readText()

    @Test fun windowsAreFixedAndKeepTheLivePageFlat() {
        val pane = src("GlassPane.kt")
        val win = src("GlassWindow.kt")
        val live = src("BrowserLivePreview.kt")
        for (s in listOf(win, live)) {
            assertTrue("uses the fixed pane", s.contains("TiltGlassPane("))
            assertFalse("no grips any more", s.contains("GlassPaneGrip") || s.contains("offset {"))
        }
        assertTrue("page selection still uses the chat pill", win.contains("SelectionPill(") && win.contains("PillWebView("))
        assertTrue("a still picture leans, not the live page", pane.contains("still = takeStill()") && pane.contains("val showLive = appeared && !tilting"))
        assertTrue("finger on the page never tilts", pane.contains("GlassTilt.tiltsFrom("))
    }
}
