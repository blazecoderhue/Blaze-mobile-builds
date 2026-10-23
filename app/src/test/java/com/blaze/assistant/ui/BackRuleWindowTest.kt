package com.blaze.assistant.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * 0.45.19 fix 7, strict 5/5 with real key events through a real window: BackRule's interceptor takes Back before the
 * screen's own handlers (Compose BackHandler / OnBackPressedCallback, which a nested sheet or page registers), acts
 * once per press, and leaves every other key (and Back when the screen keeps its own) alone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class BackRuleWindowTest {
    private fun press(a: ComponentActivity, code: Int = KeyEvent.KEYCODE_BACK): Pair<Boolean, Boolean> {
        val cb = a.window.callback
        val d = cb.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        val u = cb.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return d to u
    }

    @Test fun backGoesToChatBeforeTheScreensOwnHandler() {
        var pass = 0
        for (run in 1..5) {
            val a = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            var nested = 0; var toChat = 0; var takes = true
            a.onBackPressedDispatcher.addCallback(a, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { nested++ } })
            assertTrue(BackRule.wrapWith(a, { takes }, { toChat++ }))
            assertFalse("wrapped once", BackRule.wrapWith(a, { takes }, { toChat++ }))
            val (d, u) = press(a)
            assertTrue(d); assertTrue(u)
            assertEquals("run $run: one press, one trip to the chat", 1, toChat)
            assertEquals("run $run: the nested handler never saw it", 0, nested)
            // A screen that keeps its own Back (the chat itself): the key reaches its handler as before.
            takes = false
            press(a)
            assertEquals(1, toChat)
            assertEquals("run $run: own handler", 1, nested)
            // Other keys always pass through untouched.
            takes = true
            press(a, KeyEvent.KEYCODE_ENTER)
            assertEquals(1, toChat)
            // A held Back acts once, on release.
            val cb = a.window.callback
            cb.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            cb.dispatchKeyEvent(KeyEvent.changeTimeRepeat(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), 0L, 1))
            assertEquals(1, toChat)
            cb.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            assertEquals("run $run: held Back", 2, toChat)
            assertEquals(1, nested)
            pass++
        }
        assertEquals("strict 5/5", 5, pass)
    }
}
