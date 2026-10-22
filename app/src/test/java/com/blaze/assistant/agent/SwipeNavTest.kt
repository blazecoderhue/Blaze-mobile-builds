package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeNavTest {
    @Test fun dragFollowsTheFinger() {
        assertEquals(0.5f, SwipeNav.drag(0f, -500f, 1000f), 1e-4f)   // up half the screen from Home
        assertEquals(1f, SwipeNav.drag(0.9f, -500f, 1000f), 1e-4f)   // clamped
        assertEquals(0.75f, SwipeNav.drag(1f, 250f, 1000f), 1e-4f)   // pulled down from Chat
        assertEquals(0.3f, SwipeNav.drag(0.3f, 50f, 0f), 1e-4f)
    }

    @Test fun settleByFlingOrDistance() {
        assertEquals(1f, SwipeNav.settle(0.1f, -1500f, from = 0f))  // quick flick up opens Chat
        assertEquals(0f, SwipeNav.settle(0.9f, 1500f, from = 1f))   // quick flick down shows Home
        assertEquals(0f, SwipeNav.settle(0.2f, -100f, from = 0f))   // short slow drag snaps back
        assertEquals(1f, SwipeNav.settle(0.35f, 0f, from = 0f))
        assertEquals(1f, SwipeNav.settle(0.8f, 100f, from = 1f))
        assertEquals(0f, SwipeNav.settle(0.6f, 0f, from = 1f))
    }

    @Test fun startAndStyle() {
        assertEquals(SwipeNav.HOME, SwipeNav.startSurface(swipe = true, onboarded = true))
        assertEquals(SwipeNav.CHAT, SwipeNav.startSurface(swipe = true, onboarded = false))
        assertEquals(SwipeNav.CHAT, SwipeNav.startSurface(swipe = false, onboarded = true))
        assertEquals(SwipeNav.SWIPE, SwipeNav.styleOf(null)); assertEquals(SwipeNav.CLASSIC, SwipeNav.styleOf("Classic"))
        assertEquals(80f, SwipeNav.handleBottomDp(48f), 1e-4f)
        assertEquals(SwipeNav.CHAT, SwipeNav.surfaceOf(0.7f)); assertEquals(SwipeNav.HOME, SwipeNav.surfaceOf(0.2f))
    }
}
