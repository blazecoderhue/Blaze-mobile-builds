package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v0.25.0: the Undo bar swipes away left, right or down; a small drag springs back. */
class SwipeAwayTest {
    @Test fun directions() {
        assertEquals(SwipeAway.Dir.RIGHT, SwipeAway.decide(200f, 10f, 500f, 50f))
        assertEquals(SwipeAway.Dir.LEFT, SwipeAway.decide(-200f, 10f, 500f, 50f))
        assertEquals(SwipeAway.Dir.DOWN, SwipeAway.decide(5f, 40f, 500f, 50f))
        assertNull(SwipeAway.decide(40f, 5f, 500f, 50f))
        assertNull(SwipeAway.decide(0f, -80f, 500f, 50f))            // up never dismisses
        assertEquals(SwipeAway.Dir.LEFT, SwipeAway.decide(-20f, 0f, 500f, 50f, vx = -2000f))   // a flick
        val (tx, _) = SwipeAway.target(SwipeAway.Dir.RIGHT, 500f, 50f); assert(tx > 500f)
    }

    @Test fun brainStatus() {
        assertEquals(BrainStatus.Dot.BAD, BrainStatus.of(false, true).first)
        assertEquals(BrainStatus.Dot.OK to "Connected", BrainStatus.of(true, true))
        assertEquals(BrainStatus.Dot.WAIT, BrainStatus.of(true, null).first)
    }
}
