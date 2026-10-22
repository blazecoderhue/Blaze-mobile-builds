package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.43.0: dropping one suggestion chip on another asks for both. */
class ChipMergeTest {
    @Test fun joinsWithAnd() {
        assertEquals("set gentle wake tone", ChipMerge.text(listOf("set gentle wake tone")))
        assertEquals("set gentle wake tone and schedule bedtime reminder", ChipMerge.text(listOf("set gentle wake tone", "schedule bedtime reminder")))
        assertEquals("a, b and c", ChipMerge.text(listOf("a", "b", "c")))
        assertEquals("a and b", ChipMerge.text(listOf(" a. ", "A", "b!")))
        assertEquals("", ChipMerge.text(listOf(" ", "")))
    }

    @Test fun mergeKeepsRowOrderAndPlace() {
        val row = ChipMerge.start(listOf("set gentle wake tone", "schedule bedtime reminder", "dim lights"))
        // dragging the first onto the second, or the second onto the first, reads the same
        val a = ChipMerge.merge(row, 0, 1); val b = ChipMerge.merge(row, 1, 0)
        assertEquals("set gentle wake tone and schedule bedtime reminder", ChipMerge.text(a[0]))
        assertEquals(ChipMerge.text(a[0]), ChipMerge.text(b[0]))
        assertEquals(listOf(listOf("dim lights")), a.drop(1))
        // a combined chip takes a third
        val c = ChipMerge.merge(a, 1, 0)
        assertEquals(1, c.size)
        assertEquals("set gentle wake tone, schedule bedtime reminder and dim lights", ChipMerge.text(c[0]))
        // on itself or out of range: nothing changes
        assertEquals(row, ChipMerge.merge(row, 1, 1)); assertEquals(row, ChipMerge.merge(row, 0, 7))
    }

    @Test fun splitRestores() {
        val row = ChipMerge.start(listOf("a", "b", "c"))
        val merged = ChipMerge.merge(row, 0, 2) // [b], [a, c]
        assertEquals(listOf(listOf("b"), listOf("a"), listOf("c")), ChipMerge.split(merged, 1))
        assertEquals(row, ChipMerge.split(row, 0)) // a plain chip just snaps back
    }

    @Test fun targetUnderTheDraggedChip() {
        val boxes = listOf(ChipMerge.Box(0f, 0f, 100f, 40f), ChipMerge.Box(110f, 0f, 220f, 40f), null, ChipMerge.Box(230f, 0f, 300f, 40f))
        assertEquals(1, ChipMerge.target(boxes, 0, 150f, 20f))
        assertEquals(-1, ChipMerge.target(boxes, 0, 50f, 20f))      // over itself
        assertEquals(-1, ChipMerge.target(boxes, 0, 150f, 90f))     // dragged away
        assertEquals(3, ChipMerge.target(boxes, 0, 226f, 20f, slop = 6f))
    }
}
