package com.blaze.assistant.phone

import com.blaze.assistant.phone.MagicHandSafety.Verdict
import com.blaze.assistant.phone.MagicHandSafety.Win
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.25.0: Magic Hand can never lock the phone (low-end phone report): overlay flags, teach filter, escape hatches, watchdog. */
class MagicHandSafetyTest {
    private val S = MagicHandSafety

    @Test fun flagValuesMatchAndroid() {
        // Same numbers as android.view.WindowManager.LayoutParams (documented constants).
        assertEquals(0x8, S.FLAG_NOT_FOCUSABLE); assertEquals(0x10, S.FLAG_NOT_TOUCHABLE); assertEquals(0x20, S.FLAG_NOT_TOUCH_MODAL)
        assertEquals(0x40000, S.FLAG_WATCH_OUTSIDE_TOUCH)
    }

    @Test fun noWindowSwallowsOtherTouches() {
        for (w in Win.values()) for (typing in listOf(false, true)) {
            val f = S.flags(w, typing)
            assertFalse("$w typing=$typing", S.blocksOthers(f))
            assertTrue("$w must be NOT_TOUCH_MODAL", f and S.FLAG_NOT_TOUCH_MODAL != 0)
        }
        assertTrue(S.flags(Win.HIGHLIGHT) and S.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(S.flags(Win.HIGHLIGHT) and S.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(S.flags(Win.HAND) and S.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(S.flags(Win.DOCK) and S.FLAG_NOT_FOCUSABLE != 0)          // only the name field ever takes the keyboard
        assertTrue(S.flags(Win.DOCK, typing = true) and S.FLAG_NOT_FOCUSABLE == 0)
        // The v0.24.7 bug: focusable dock without NOT_TOUCH_MODAL = every touch on screen captured.
        assertTrue(S.blocksOthers(0))
    }

    @Test fun handStaysOnScreenAboveTheNavBar() {
        val size = 168; val w = 720; val h = 1600; val nav = 126
        val (x0, y0) = S.startSpot(size, w, h, nav)
        assertTrue(x0 in 200..400); assertTrue(y0 in 300..700)
        assertEquals(0 to 0, S.clamp(-500, -500, size, w, h, nav))
        assertEquals((w - size) to (h - nav - size), S.clamp(5000, 5000, size, w, h, nav))
        assertEquals(0 to 60, S.clamp(0, 0, size, w, h, nav, topInset = 60))
    }

    @Test fun teachIgnoresSystemAppsAndBlaze() {
        val own = "com.blaze.assistant"
        listOf("com.android.systemui", "android", "com.samsung.android.app.smartcapture", "com.sec.android.app.launcher",
            "com.samsung.android.honeyboard", "com.google.android.inputmethod.latin", own, "com.example.screenshot.pro", "")
            .forEach { assertTrue(it, S.ignoreForTeach(it, own)) }
        assertTrue(S.ignoreForTeach("com.nova.home", own, launchers = setOf("com.nova.home")))
        assertTrue(S.ignoreForTeach("com.fancy.ime", own, imes = setOf("com.fancy.ime")))
        listOf("com.whatsapp", "com.android.chrome", "com.android.settings").forEach { assertFalse(it, S.ignoreForTeach(it, own)) }
    }

    @Test fun humanLabelsOnly() {
        assertEquals("", S.humanLabel("MainUiViewForStandAlone\$7"))
        assertEquals("", S.humanLabel("FrameLayout")); assertEquals("", S.humanLabel("android.widget.Button"))
        assertEquals("", S.humanLabel("ChatListFragment"))
        assertEquals("Send", S.humanLabel(" Send ")); assertEquals("New chat", S.humanLabel("New chat"))
        assertEquals("WhatsApp", S.humanLabel("WhatsApp")); assertEquals("Wi-Fi", S.humanLabel("Wi-Fi"))
        val r = MagicHandTeach.Recorder("com.blaze.assistant")
        r.window("com.whatsapp", "WhatsApp", "Chats")
        r.window("com.whatsapp", "WhatsApp", "MainUiViewForStandAlone\$7")   // dropped: not a screen title
        r.click("com.whatsapp", "WhatsApp", "Send", "com.whatsapp:id/send", "android.widget.ImageButton")
        assertEquals(2, r.size)
        val line = MagicHandTeach.line(r.steps[1])
        assertFalse(line, line.contains("ImageButton")); assertTrue(line.contains("\u201cSend\u201d"))
    }

    @Test fun teachStepLimit() {
        val r = MagicHandTeach.Recorder("x")
        repeat(100) { r.click("com.app", "App", "Button $it", "", "") }
        assertEquals(MagicHandTeach.MAX_STEPS, r.size)
        val u = MagicHandState.teachSteps(MagicHandState.teach(MagicHandState.start(false)), r.steps.map(MagicHandTeach::line))
        assertEquals(MagicHandState.TEACH_FULL, u.note)
    }

    @Test fun teachIntroComesFirst() {
        val u = MagicHandState.intro(MagicHandState.start(false), firstTime = true)
        assertEquals(MagicHandState.Panel.TEACH_INTRO, u.panel)
        assertEquals(MagicHandSafety.TEACH_INTRO, u.intro)
        assertEquals(MagicHandLogic.Mode.POINT, u.mode)   // nothing recorded until Start
        assertEquals(MagicHandState.DockButton.CLOSE, MagicHandState.dockButtons(u).last())
        assertEquals(MagicHandState.TEACH_SHORT, MagicHandState.intro(MagicHandState.start(false), firstTime = false).intro)
    }

    @Test fun backTwiceExits() {
        val b = MagicHandSafety.BackTwice()
        assertFalse(b.press(1_000)); assertTrue(b.press(1_400))
        assertFalse(b.press(5_000)); assertFalse(b.press(6_000)); assertTrue(b.press(6_300))
    }

    @Test fun watchdog() {
        assertEquals(Verdict.OK, S.watchdog(10_000, 9_500, 9_000))
        assertEquals(Verdict.STALLED, S.watchdog(10_000, 6_900, 9_000))
        assertEquals(Verdict.OK, S.watchdog(10_000, 7_000, 9_000))
        assertEquals(Verdict.IDLE, S.watchdog(700_000, 699_900, 0))
        assertEquals(Verdict.OK, S.watchdog(599_000, 598_900, 0))
    }
}
