package com.blaze.assistant.phone

import com.blaze.assistant.phone.MagicHandBox.Handle
import com.blaze.assistant.phone.MagicHandLogic.Box
import com.blaze.assistant.phone.MagicHandState.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.26.0: the grab box moves, resizes, stays on screen, never gets too small, and its handles are hit right. */
class MagicHandBoxTest {
    private val screen = MagicHandBox.bounds(1080, 2340, topInset = 80, bottomInset = 120)   // usable: y 80..2220
    private val box = Box(200, 600, 600, 900)
    private val reach = 44
    private val min = 80

    @Test fun boundsLeaveOutTheSystemBars() {
        assertEquals(MagicHandBox.Bounds(0, 80, 1080, 2220), screen)
    }

    @Test fun cornersEdgesInsideAndOutside() {
        assertEquals(Handle.TOP_LEFT, MagicHandBox.hit(box, 200, 600, reach))
        assertEquals(Handle.TOP_LEFT, MagicHandBox.hit(box, 170, 575, reach))          // just outside the corner still counts
        assertEquals(Handle.TOP_RIGHT, MagicHandBox.hit(box, 610, 590, reach))
        assertEquals(Handle.BOTTOM_LEFT, MagicHandBox.hit(box, 205, 895, reach))
        assertEquals(Handle.BOTTOM_RIGHT, MagicHandBox.hit(box, 640, 940, reach))
        assertEquals(Handle.LEFT, MagicHandBox.hit(box, 190, 750, reach))
        assertEquals(Handle.RIGHT, MagicHandBox.hit(box, 620, 750, reach))
        assertEquals(Handle.TOP, MagicHandBox.hit(box, 400, 610, reach))
        assertEquals(Handle.BOTTOM, MagicHandBox.hit(box, 400, 920, reach))
        assertEquals(Handle.MOVE, MagicHandBox.hit(box, 400, 750, reach))
        assertEquals(Handle.NONE, MagicHandBox.hit(box, 100, 750, reach))
        assertEquals(Handle.NONE, MagicHandBox.hit(box, 400, 1000, reach))
    }

    @Test fun smallBoxCanStillBeMoved() {
        val small = Box(500, 500, 580, 580)   // 80 px, smaller than 3 × reach
        assertEquals(Handle.MOVE, MagicHandBox.hit(small, 540, 540, reach))
        assertEquals(Handle.TOP_LEFT, MagicHandBox.hit(small, 498, 498, reach))
        assertEquals(Handle.BOTTOM_RIGHT, MagicHandBox.hit(small, 585, 585, reach))
    }

    @Test fun moveKeepsSizeAndStaysOnScreen() {
        val moved = MagicHandBox.drag(box, Handle.MOVE, 100, -50, screen, min)
        assertEquals(Box(300, 550, 700, 850), moved)
        val farLeftUp = MagicHandBox.drag(box, Handle.MOVE, -5000, -5000, screen, min)
        assertEquals(Box(0, 80, 400, 380), farLeftUp)                 // under the status bar is off limits
        val farRightDown = MagicHandBox.drag(box, Handle.MOVE, 5000, 5000, screen, min)
        assertEquals(Box(680, 1920, 1080, 2220), farRightDown)        // never on the navigation bar
        assertEquals(box.w, farRightDown.w); assertEquals(box.h, farRightDown.h)
    }

    @Test fun resizeMovesOnlyTheGrabbedSides() {
        assertEquals(Box(150, 560, 600, 900), MagicHandBox.drag(box, Handle.TOP_LEFT, -50, -40, screen, min))
        assertEquals(Box(200, 600, 700, 900), MagicHandBox.drag(box, Handle.RIGHT, 100, 999, screen, min))   // dy ignored
        assertEquals(Box(200, 600, 600, 1000), MagicHandBox.drag(box, Handle.BOTTOM, 999, 100, screen, min)) // dx ignored
        assertEquals(Box(200, 600, 650, 950), MagicHandBox.drag(box, Handle.BOTTOM_RIGHT, 50, 50, screen, min))
    }

    @Test fun resizeNeverGoesBelowMinimumOrFlips() {
        val crushed = MagicHandBox.drag(box, Handle.BOTTOM_RIGHT, -1000, -1000, screen, min)
        assertEquals(Box(200, 600, 280, 680), crushed)                // the opposite corner stays, min size kept
        val pastLeft = MagicHandBox.drag(box, Handle.LEFT, 900, 0, screen, min)
        assertEquals(Box(520, 600, 600, 900), pastLeft)
        val pastTop = MagicHandBox.drag(box, Handle.TOP, 0, 900, screen, min)
        assertEquals(Box(200, 820, 600, 900), pastTop)
    }

    @Test fun resizeStopsAtScreenEdges() {
        assertEquals(Box(0, 80, 600, 900), MagicHandBox.drag(box, Handle.TOP_LEFT, -5000, -5000, screen, min))
        assertEquals(Box(200, 600, 1080, 2220), MagicHandBox.drag(box, Handle.BOTTOM_RIGHT, 5000, 5000, screen, min))
    }

    @Test fun fitGrowsTinyBoxesAndShiftsThemOnScreen() {
        assertEquals(Box(10, 100, 90, 180), MagicHandBox.fit(Box(10, 100, 30, 120), screen, min))
        assertEquals(Box(1000, 2140, 1080, 2220), MagicHandBox.fit(Box(1070, 2300, 1080, 2310), screen, min))
        // A minimum bigger than the screen is capped by it.
        val tiny = MagicHandBox.Bounds(0, 0, 50, 50)
        assertEquals(Box(0, 0, 50, 50), MagicHandBox.drag(Box(0, 0, 50, 50), Handle.MOVE, 10, 10, tiny, 200))
    }

    @Test fun onlyRealChangesReadAgain() {
        assertFalse(MagicHandBox.changed(box, Box(202, 601, 599, 898), 3))
        assertTrue(MagicHandBox.changed(box, Box(210, 600, 600, 900), 3))
    }

    @Test fun touchWindowWrapsTheBoxAndItsHandles() {
        assertEquals(Box(150, 550, 650, 950), MagicHandBox.window(box, 50))
    }

    @Test fun windowFlagsNeverBlockTheRestOfThePhone() {
        val active = MagicHandSafety.flags(MagicHandSafety.Win.BOX, active = true)
        val idle = MagicHandSafety.flags(MagicHandSafety.Win.BOX, active = false)
        assertFalse(MagicHandSafety.blocksOthers(active)); assertFalse(MagicHandSafety.blocksOthers(idle))
        assertTrue(active and MagicHandSafety.FLAG_NOT_TOUCH_MODAL != 0)
        assertTrue(active and MagicHandSafety.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(active and MagicHandSafety.FLAG_NOT_TOUCHABLE == 0)
        assertTrue(idle and MagicHandSafety.FLAG_NOT_TOUCHABLE != 0)
    }

    @Test fun regrabKeepsTheCardOpen() {
        val g = MagicHandState.grabbed(MagicHandState.start(false), "Hello world", "Chrome")
        val busy = MagicHandState.regrabbing(g)
        assertTrue(busy.grabBusy); assertEquals(Panel.GRAB, busy.panel)
        val done = MagicHandState.regrabbed(busy, "  New text ", "")
        assertEquals("New text", done.grabbed); assertEquals("Chrome", done.grabSource); assertFalse(done.grabBusy)
        val empty = MagicHandState.regrabbed(busy, "", "Chrome")
        assertEquals(Panel.GRAB, empty.panel); assertEquals("", empty.grabbed)   // nothing read: card stays, says so
        // A read that lands after the card closed changes nothing.
        val closed = MagicHandState.back(busy)
        assertFalse(closed.grabBusy)
        assertEquals(closed, MagicHandState.regrabbed(closed, "late", "Chrome"))
    }

    @Test fun longPressSelectsTextInsteadOfClosing() {
        val s = MagicHandState.start(false)
        assertTrue(MagicHandState.longPressCloses(s))                                         // status: escape hatch kept
        assertFalse(MagicHandState.longPressCloses(MagicHandState.grabbed(s, "Some text", "Chrome")))
        assertFalse(MagicHandState.longPressCloses(MagicHandState.answered(MagicHandState.asking(s, "q"), "An answer")))
        assertFalse(MagicHandState.longPressCloses(MagicHandState.review(s, listOf("Tap Send"), "Send")))
    }
}
