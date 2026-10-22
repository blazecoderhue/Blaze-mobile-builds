package com.blaze.assistant.proactive

import com.blaze.assistant.proactive.HabitFilter.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.27.0: habit offers only for apps the user opens on purpose. */
class HabitFilterTest {
    private val self = "com.blaze.assistant"
    private val kbs = setOf("com.google.android.inputmethod.latin", "com.touchtype.swiftkey", "com.example.fancykeys")
    private val homes = setOf("com.sec.android.app.launcher", "com.example.myhome")

    private fun v(pkg: String, launchable: Boolean? = true, overlay: Boolean = false, dwell: Long = 60_000) =
        HabitFilter.verdict(pkg, self, kbs, homes, launchable, overlay, dwell)

    @Test fun aRealAppOpenedForAWhileCounts() {
        assertEquals(Why.OK, v("com.whatsapp"))
        assertEquals(Why.OK, v("com.spotify.music", dwell = HabitFilter.MIN_DWELL_MS))
    }

    @Test fun keyboardsNeverCount() {
        assertEquals(Why.KEYBOARD, v("com.google.android.inputmethod.latin"))
        assertEquals(Why.KEYBOARD, v("com.example.fancykeys"))   // any installed keyboard, whatever its name
        assertEquals(Why.KEYBOARD, v("COM.TOUCHTYPE.SWIFTKEY"))
        // A keyboard the package manager didn't list is still caught by its name.
        assertEquals(Why.SYSTEM, HabitFilter.verdict("com.samsung.android.honeyboard", self, emptySet(), homes, true, false, 60_000))
    }

    @Test fun homeSystemAndSelfNeverCount() {
        assertEquals(Why.HOME, v("com.example.myhome"))
        assertEquals(Why.HOME, v("com.sec.android.app.launcher"))
        assertEquals(Why.SYSTEM, v("com.android.systemui"))
        assertEquals(Why.SYSTEM, v("com.android.settings"))
        assertEquals(Why.SYSTEM, v("com.google.android.permissioncontroller"))
        assertEquals(Why.SYSTEM, v("com.android.packageinstaller"))
        assertEquals(Why.SYSTEM, v("android"))
        assertEquals(Why.SELF, v(self))
        assertEquals(Why.BLANK, v(" "))
    }

    @Test fun noLauncherIconNoOffer() {
        assertEquals(Why.NOT_LAUNCHABLE, v("com.example.backgroundthing", launchable = false))
        assertEquals("unknown = allowed", Why.OK, v("com.example.app", launchable = null))
    }

    @Test fun overlaysAndBriefVisitsDontCount() {
        assertEquals(Why.OVERLAY, v("com.facebook.orca", overlay = true))
        assertEquals(Why.BRIEF, v("com.whatsapp", dwell = 1_500))
        assertEquals(Why.BRIEF, v("com.whatsapp", dwell = HabitFilter.MIN_DWELL_MS - 1))
    }

    @Test fun windowKinds() {
        val w = 1080; val h = 2400
        assertFalse(HabitFilter.isOverlay(HabitFilter.WINDOW_APPLICATION, w, h, w, h))
        assertTrue("keyboard window", HabitFilter.isOverlay(2, w, 900, w, h))
        assertTrue("system window", HabitFilter.isOverlay(3, w, h, w, h))
        assertTrue("accessibility overlay", HabitFilter.isOverlay(4, w, h, w, h))
        assertTrue("chat head", HabitFilter.isOverlay(HabitFilter.WINDOW_APPLICATION, 200, 200, w, h))
        assertFalse("unknown window", HabitFilter.isOverlay(-1, 0, 0, w, h))
        assertFalse("split screen half still counts", HabitFilter.isOverlay(HabitFilter.WINDOW_APPLICATION, w, h / 2 + 10, w, h))
    }

    @Test fun passingWindows() {
        assertTrue(HabitFilter.passes("com.android.systemui", kbs))
        assertTrue(HabitFilter.passes("com.example.fancykeys", kbs))
        assertTrue(HabitFilter.passes("com.samsung.android.honeyboard", emptySet()))
        assertFalse(HabitFilter.passes("com.whatsapp", kbs))
        assertFalse("going home ends the visit", HabitFilter.passes("com.sec.android.app.launcher", kbs))
    }

    @Test fun humanLabels() {
        assertEquals("WhatsApp", HabitFilter.label("WhatsApp", "com.whatsapp"))
        assertEquals("Y.Music", HabitFilter.label("Y.Music", "com.yandex.music"))
        assertEquals("Whatsapp", HabitFilter.label("com.whatsapp", "com.whatsapp"))
        assertEquals("Music", HabitFilter.label(null, "com.example.music"))
        assertEquals("My app", HabitFilter.label("", "com.example.my_app"))
    }

    @Test fun visitsEndWhenAnotherAppComesUpNotWhenAKeyboardDoes() {
        val vs = HabitFilter.Visits()
        assertNull(vs.onWindow("com.whatsapp", 1_000, passing = false))
        assertNull("keyboard shows", vs.onWindow("com.google.android.inputmethod.latin", 3_000, passing = true))
        assertNull("shade pulled down", vs.onWindow("com.android.systemui", 5_000, passing = true))
        assertNull("same app, other screen", vs.onWindow("com.whatsapp", 6_000, passing = false))
        val ended = vs.onWindow("com.sec.android.app.launcher", 31_000, passing = false)!!
        assertEquals("com.whatsapp", ended.pkg)
        assertEquals(1_000L, ended.start)
        assertEquals(30_000L, ended.dwellMs)
        assertEquals("com.sec.android.app.launcher", vs.current)
        // A quick look at another app ends the launcher visit; the quick one itself is brief when it ends.
        vs.onWindow("com.instagram.android", 32_000, passing = false)
        val quick = vs.onWindow("com.whatsapp", 33_000, passing = false)!!
        assertEquals(Why.BRIEF, v(quick.pkg, dwell = quick.dwellMs))
    }
}
