package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.43.0: the Blaze / Halo / All arc from the header orb. */
class TalkArcMathTest {
    private val e = 0.001f

    @Test fun targetsAndLabels() {
        assertEquals(listOf("blaze", "halo", "all"), TalkArcMath.TARGETS)
        assertEquals(1, TalkArcMath.indexOf("halo")); assertEquals(0, TalkArcMath.indexOf("nobody"))
        assertEquals("all", TalkArcMath.targetAt(9)); assertEquals("blaze", TalkArcMath.targetAt(-1))
        assertTrue(TalkArcMath.label("all").contains("Halo"))
    }

    @Test fun layoutPutsTheFocusedOnTop() {
        // Halo focused: Blaze left, Halo on top, All right.
        assertEquals(135f, TalkArcMath.angle(0, 1f), e); assertEquals(90f, TalkArcMath.angle(1, 1f), e); assertEquals(45f, TalkArcMath.angle(2, 1f), e)
        val (x, y) = TalkArcMath.offset(1, 1f, 100f)
        assertEquals(0f, x, e); assertEquals(-100f, y, e)
        val (lx, _) = TalkArcMath.offset(0, 1f, 100f); val (rx, _) = TalkArcMath.offset(2, 1f, 100f)
        assertTrue(lx < 0f && rx > 0f)
        // every icon stays on the upper half at either end
        for (p in listOf(0f, 2f)) for (i in 0..2) assertTrue(TalkArcMath.angle(i, p) in 0f..180f)
        assertEquals(1.45f, TalkArcMath.scale(1, 1f), e); assertEquals(1f, TalkArcMath.scale(0, 1f), e)
        assertEquals(0.5f, TalkArcMath.focus(1, 1.5f), e)
    }

    @Test fun rotateSwipeAndSnap() {
        // pulling the right-hand icon up over the top (angle 45 -> 90) brings it to the top
        assertEquals(2f, TalkArcMath.rotate(1f, 45f, 90f), e)
        assertEquals(0f, TalkArcMath.rotate(1f, 135f, 90f), e)
        assertEquals(2f, TalkArcMath.rotate(2f, 10f, 170f), e)           // clamped at the end
        assertEquals(1f + 20f / 45f, TalkArcMath.rotate(1f, 170f, -170f), e) // wraps through ±180 the short way
        // a swipe left of one step's arc length brings the next icon up
        val r = 100f; val oneStep = r * 45f * Math.PI.toFloat() / 180f
        assertEquals(2f, TalkArcMath.swipe(1f, -oneStep, r), e)
        assertEquals(0f, TalkArcMath.swipe(0.2f, 500f, r), e)
        assertEquals(1, TalkArcMath.snap(1.4f)); assertEquals(2, TalkArcMath.snap(1.6f)); assertEquals(2, TalkArcMath.snap(9f))
    }

    @Test fun touchAngleAndHit() {
        assertEquals(90f, TalkArcMath.touchAngle(0f, -50f), e)
        assertEquals(180f, TalkArcMath.touchAngle(-50f, 0f), e)
        assertEquals(1, TalkArcMath.hit(3f, -98f, 1f, 100f, 24f))
        val (ax, ay) = TalkArcMath.offset(2, 1f, 100f)
        assertEquals(2, TalkArcMath.hit(ax, ay, 1f, 100f, 24f))
        assertEquals(-1, TalkArcMath.hit(0f, 0f, 1f, 100f, 24f))
    }
}
