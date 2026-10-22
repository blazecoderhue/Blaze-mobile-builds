package com.blaze.assistant.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.27.0: one motion spec for the app, and nothing moves with the phone's "Remove animations" on. */
class MotionTokensTest {
    @After fun tearDown() = MotionTokens.setEnabledForTests(true)

    @Test fun durationsAreTheThreeSteps() {
        assertEquals(150, MotionTokens.SHORT)
        assertEquals(220, MotionTokens.MEDIUM)
        assertEquals(320, MotionTokens.LONG)
        assertTrue(MotionTokens.PRESS_SCALE in 0.9f..0.99f)
    }

    @Test fun removeAnimationsMeansScaleZero() {
        assertFalse(MotionTokens.motionOn(0f))
        assertFalse(MotionTokens.motionOn(Float.NaN))
        assertTrue(MotionTokens.motionOn(1f))
        assertTrue(MotionTokens.motionOn(0.5f))
        assertTrue(MotionTokens.motionOn(10f))
    }

    @Test fun specsSnapWhenMotionIsOff() {
        MotionTokens.setEnabledForTests(true)
        assertTrue(MotionTokens.medium<Float>() is TweenSpec)
        assertEquals(220, (MotionTokens.medium<Float>() as TweenSpec).durationMillis)
        assertEquals(150L, MotionTokens.ms(MotionTokens.SHORT))
        assertNotEquals(EnterTransition.None, MotionTokens.enterUp())

        MotionTokens.setEnabledForTests(false)
        assertTrue(MotionTokens.fast<Float>() is SnapSpec)
        assertTrue(MotionTokens.medium<Float>() is SnapSpec)
        assertTrue(MotionTokens.slow<Float>() is SnapSpec)
        assertTrue(MotionTokens.drag<Float>() is SnapSpec)
        assertEquals(0L, MotionTokens.ms(MotionTokens.LONG))
        assertEquals(EnterTransition.None, MotionTokens.enterUp())
        assertEquals(EnterTransition.None, MotionTokens.expand())
        assertEquals(ExitTransition.None, MotionTokens.collapse())
        assertEquals(ExitTransition.None, MotionTokens.exitDown())
    }

    @Test fun onlyNewMessagesAnimateAndOnlyOnce() {
        val a = AppearOnce(listOf("m1", "m2"))
        assertFalse("history opened with the chat", a.fresh("m1"))
        assertTrue("a new reply", a.fresh("m3"))
        assertFalse("scrolled away and back", a.fresh("m3"))
    }
}
