package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.28.1: the fanned picture stack and the 3D-flip viewer (the maths; the frames are in ShotsTest). */
class ImageStackTest {
    @Test fun stackShowsTopAndUpToTwoBehind() {
        assertEquals(1, StackMath.layers(1)); assertEquals(2, StackMath.layers(2)); assertEquals(3, StackMath.layers(5))
        assertEquals(listOf(4, 0, 1), (0..2).map { StackMath.at(4, it, 5) })
    }

    @Test fun swipeWrapsAround() {
        assertEquals(1, StackMath.next(0, 1, 3)); assertEquals(2, StackMath.next(0, -1, 3)); assertEquals(0, StackMath.next(2, 1, 3))
    }

    @Test fun behindCardsFanLeftAndRightSmallerAndDimmer() {
        val top = StackMath.layer(0, 0f); val one = StackMath.layer(1, 0f); val two = StackMath.layer(2, 0f)
        assertEquals(0f, top.rotation, 0f); assertEquals(1f, top.scale, 0f); assertEquals(0f, top.dim, 0f)
        assertTrue(one.rotation < 0 && two.rotation > 0)
        assertTrue(one.scale < 1f && two.scale < one.scale)
        assertTrue(one.dim > 0f && two.dim > one.dim)
        // As the top leaves, the next card grows into its place.
        val moving = StackMath.layer(1, -1f)
        assertEquals(0f, moving.rotation, 0.001f); assertEquals(1f, moving.scale, 0.001f)
    }

    @Test fun flipThresholds() {
        assertEquals(1, StackMath.flips(-0.4f, 0f)); assertEquals(-1, StackMath.flips(0.4f, 0f))
        assertEquals(0, StackMath.flips(0.1f, 100f)); assertEquals(1, StackMath.flips(-0.05f, -2000f))
    }

    @Test fun positionLabel() {
        assertNull(StackMath.label(1, 5)); assertEquals("2/6", StackMath.label(1, 6))
    }

    @Test fun pagesTiltOnYAndSlide() {
        val cur = FlipMath.page(0, -0.5f)
        assertEquals(-15f, cur.rotationY, 0.001f); assertEquals(-0.5f, cur.shift, 0.001f)
        val next = FlipMath.page(1, -0.5f)
        assertEquals(15f, next.rotationY, 0.001f); assertEquals(0.5f, next.shift, 0.001f)
        assertEquals(30f, FlipMath.page(1, 0f).rotationY, 0.001f)
        assertTrue(FlipMath.MAX_DEG in 25f..35f)
        assertEquals(0f, FlipMath.page(0, 0f).rotationY, 0f)
    }

    @Test fun removeAnimationsIsAPlainSlide() {
        val p = FlipMath.page(0, -0.5f, motion = false)
        assertEquals(0f, p.rotationY, 0f); assertEquals(-0.5f, p.shift, 0f); assertEquals(1f, p.alpha, 0f)
    }
}
