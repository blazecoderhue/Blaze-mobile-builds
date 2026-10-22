package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/** v0.24.0 messaging thread: grouping, corners, spacing and timestamps. */
class BubblesTest {
    private val ME = Side.ME
    private val B = Side.BLAZE

    @Test fun groupsRunsFromOneSide() {
        val p = Bubbles.positions(listOf(ME, ME, B, B, B, ME, B), List(7) { 1_000L * it })
        assertEquals(listOf(GroupPos.FIRST, GroupPos.LAST, GroupPos.FIRST, GroupPos.MIDDLE, GroupPos.LAST, GroupPos.SINGLE, GroupPos.SINGLE), p)
    }

    @Test fun longPauseSplitsAGroup() {
        val t = listOf(0L, 60_000L, 60_000L + Bubbles.GAP_MS)
        assertEquals(listOf(GroupPos.FIRST, GroupPos.LAST, GroupPos.SINGLE), Bubbles.positions(listOf(B, B, B), t))
        // unknown times (old chats) never split
        assertEquals(listOf(GroupPos.FIRST, GroupPos.LAST), Bubbles.positions(listOf(ME, ME), listOf(0L, 99_999_999L)))
        assertEquals(emptyList<GroupPos>(), Bubbles.positions(emptyList(), emptyList()))
    }

    @Test fun cornersAreAsymmetricAndPointAtTheSender() {
        // (topStart, topEnd, bottomEnd, bottomStart)
        val me = Bubbles.corners(ME, GroupPos.SINGLE)
        assertEquals(Bubbles.TAIL, me[1]); assertEquals(Bubbles.BIG, me[0]); assertEquals(Bubbles.BIG, me[3])
        val blaze = Bubbles.corners(B, GroupPos.SINGLE)
        assertEquals(Bubbles.TAIL, blaze[0]); assertEquals(Bubbles.BIG, blaze[1])
        val mid = Bubbles.corners(ME, GroupPos.MIDDLE)
        assertEquals(Bubbles.JOIN, mid[1]); assertEquals(Bubbles.JOIN, mid[2])
        assertNotEquals(Bubbles.corners(B, GroupPos.FIRST), Bubbles.corners(B, GroupPos.LAST))
        GroupPos.values().forEach { pos -> assertTrue(Bubbles.corners(ME, pos).toSet().size > 1) } // never a plain uniform box
    }

    @Test fun tightInsideGroupsLooserBetween() {
        assertEquals(0f, Bubbles.gapAbove(GroupPos.FIRST, first = true).value)
        assertTrue(Bubbles.gapAbove(GroupPos.MIDDLE, false) < Bubbles.gapAbove(GroupPos.FIRST, false))
        assertEquals(Bubbles.gapAbove(GroupPos.SINGLE, false), Bubbles.gapAbove(GroupPos.FIRST, false))
        assertEquals(Bubbles.gapAbove(GroupPos.LAST, false), Bubbles.gapAbove(GroupPos.MIDDLE, false))
    }

    @Test fun quietTimestamps() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 13, 21, 0, 0) }.timeInMillis
        fun at(day: Int, h: Int, m: Int) = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, day, h, m, 0) }.timeInMillis
        assertEquals("", Bubbles.time(0, now))
        assertEquals("14:02", Bubbles.time(at(13, 14, 2), now, Locale.UK))
        assertEquals("Yesterday 09:05", Bubbles.time(at(12, 9, 5), now, Locale.UK))
        assertTrue(Bubbles.time(at(9, 8, 0), now, Locale.UK).endsWith(" 08:00"))
        assertEquals("1 Oct 08:00", Bubbles.time(at(1, 8, 0), now, Locale.UK))
    }
}
