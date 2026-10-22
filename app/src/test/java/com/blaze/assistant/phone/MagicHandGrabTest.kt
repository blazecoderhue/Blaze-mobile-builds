package com.blaze.assistant.phone

import com.blaze.assistant.phone.MagicHandLogic.Box
import com.blaze.assistant.phone.MagicHandLogic.P
import com.blaze.assistant.phone.MagicHandState.DockButton
import com.blaze.assistant.phone.MagicHandState.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** v0.27.0: Grab made obvious: a Grab button, a live box while drawing, snap to text, Undo, first-time tips. */
class MagicHandGrabTest {
    private val bounds = MagicHandBox.bounds(1080, 2400, topInset = 80, bottomInset = 120)

    private fun circle(cx: Float, cy: Float, r: Float, turns: Double, n: Int = 40) =
        (0..n).map { i -> val a = 2 * Math.PI * turns * i / n; P(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), i * 16L) }

    @Test fun liveBoxFollowsTheDrawnShape() {
        assertNull("a tap shows nothing", MagicHandBox.liveBox(listOf(P(10f, 10f), P(12f, 11f)), 48))
        val drag = listOf(P(100f, 300f), P(250f, 380f), P(400f, 500f))
        assertEquals(Box(100, 300, 400, 500), MagicHandBox.liveBox(drag, 48))
        val c = MagicHandBox.liveBox(circle(500f, 1000f, 150f, 1.0), 48)!!
        assertTrue(c.l in 348..352 && c.r in 648..652 && c.t in 848..852 && c.b in 1148..1152)
    }

    @Test fun liveBoxOnlyWhenTheHandIsCirclingNotPointing() {
        val d = 2.75f
        assertFalse("a straight drag is pointing", MagicHandLogic.looping((0..20).map { P(100f + it * 30, 500f + it * 5) }, d))
        assertFalse("a quarter turn", MagicHandLogic.looping(circle(500f, 1000f, 200f, 0.25), d))
        assertTrue("three quarters round", MagicHandLogic.looping(circle(500f, 1000f, 200f, 0.75), d))
        assertTrue("a full circle", MagicHandLogic.looping(circle(500f, 1000f, 200f, 1.0), d))
        assertFalse("too small to be a circle", MagicHandLogic.looping(circle(500f, 1000f, 30f, 1.0), d))
    }

    @Test fun snapsToTheTextUnderTheDrawing() {
        val drawn = Box(100, 500, 700, 700)
        val line1 = Box(120, 520, 650, 570)
        val line2 = Box(120, 580, 690, 640)
        val far = Box(100, 1500, 700, 1560)
        val s = MagicHandBox.snap(drawn, listOf(line1, line2, far), pad = 10, bounds = bounds, minSize = 40)
        assertEquals(Box(110, 510, 700, 650), s)
        assertTrue(MagicHandBox.snapped(drawn, s, 4))
    }

    @Test fun snapTakesABlockMostlyInsideButNotAWholePage() {
        val drawn = Box(100, 500, 500, 700)
        // A word poking out a little to the right still counts (most of it is inside); the screen-sized container doesn't.
        val word = Box(400, 600, 560, 650)
        val page = Box(0, 80, 1080, 2280)
        val s = MagicHandBox.snap(drawn, listOf(word, page), pad = 0, bounds = bounds, minSize = 40)
        assertEquals(Box(400, 600, 560, 650), s)
    }

    @Test fun snapNeverGrowsTooFar() {
        val drawn = Box(400, 1000, 600, 1100)
        val longLine = Box(100, 1020, 1000, 1080)   // centre inside, but far wider than the drawing
        val s = MagicHandBox.snap(drawn, listOf(longLine), pad = 0, bounds = bounds, minSize = 40)
        assertEquals(300, s.l); assertEquals(700, s.r)   // at most half the drawing's width out on each side
        assertEquals(1020, s.t); assertEquals(1080, s.b)
    }

    @Test fun noTextUnderItKeepsTheDrawing() {
        val drawn = Box(100, 500, 400, 800)
        assertEquals(drawn, MagicHandBox.snap(drawn, emptyList(), 10, bounds, 40))
        assertEquals(drawn, MagicHandBox.snap(drawn, listOf(Box(800, 1500, 900, 1550)), 10, bounds, 40))
        assertFalse(MagicHandBox.snapped(drawn, drawn, 4))
    }

    @Test fun undoGoesBackStepByStep() {
        val h = MagicHandBox.History(cap = 3)
        assertFalse(h.canUndo); assertNull(h.undo())
        val a = Box(0, 0, 100, 100); val b = Box(10, 10, 110, 110); val c = Box(20, 20, 120, 120); val d = Box(30, 30, 130, 130)
        h.push(a); h.push(a); h.push(b)
        assertEquals(b, h.undo()); assertEquals(a, h.undo()); assertNull(h.undo())
        h.push(a); h.push(b); h.push(c); h.push(d)   // over the cap: the oldest goes
        assertEquals(d, h.undo()); assertEquals(c, h.undo()); assertEquals(b, h.undo()); assertNull(h.undo())
        h.push(a); h.clear(); assertFalse(h.canUndo)
    }

    @Test fun handlesAreBigEnoughToTouch() {
        assertTrue("48 dp target", MagicHandBox.REACH_DP * 2 >= 48)
        assertTrue(MagicHandBox.PAD_DP >= MagicHandBox.REACH_DP)
        assertTrue(MagicHandBox.showTip(0)); assertTrue(MagicHandBox.showTip(MagicHandBox.TIPS_TIMES - 1))
        assertFalse(MagicHandBox.showTip(MagicHandBox.TIPS_TIMES))
    }

    @Test fun grabButtonAndMode() {
        val u = MagicHandState.start(false)
        val btns = MagicHandState.dockButtons(u)
        assertEquals(DockButton.GRAB, btns.first())
        assertEquals(DockButton.CLOSE, btns.last())
        assertFalse("no Grab in Private", DockButton.GRAB in MagicHandState.dockButtons(MagicHandState.start(true)))
        assertFalse("no Grab while teaching", DockButton.GRAB in MagicHandState.dockButtons(MagicHandState.teach(u)))

        val on = MagicHandState.grabMode(u, true, firstTimes = true)
        assertTrue(on.grabMode)
        assertEquals(MagicHandState.GRAB_MODE_HINT, MagicHandState.statusLine(on))
        assertEquals("Circle or drag over what you want", MagicHandState.GRAB_MODE_HINT)
        assertEquals(MagicHandState.GRAB_MODE_SHORT, MagicHandState.statusLine(MagicHandState.grabMode(u, true, firstTimes = false)))
        val off = MagicHandState.grabMode(on, false)
        assertFalse(off.grabMode); assertEquals(MagicHandState.HELLO, MagicHandState.statusLine(off))

        val priv = MagicHandState.grabMode(MagicHandState.start(true), true)
        assertFalse(priv.grabMode)
        assertEquals(MagicHandGuard.PRIVATE_NOTE, priv.note)
    }

    @Test fun aGrabEndsGrabModeAndOpensTheCard() {
        val on = MagicHandState.grabMode(MagicHandState.start(false), true)
        val g = MagicHandState.grabbed(on, "Total K1,299", "Jumia")
        assertEquals(Panel.GRAB, g.panel); assertFalse(g.grabMode)
        val empty = MagicHandState.grabbed(on, " ", "Jumia")
        assertEquals(Panel.STATUS, empty.panel); assertFalse(empty.grabMode)
        // "Grab again" from the card: back to drawing, the card (and its Undo) gone.
        val again = MagicHandState.grabMode(g.copy(canUndo = true, coach = true), true)
        assertEquals(Panel.STATUS, again.panel); assertTrue(again.grabMode); assertFalse(again.canUndo); assertFalse(again.coach)
        // The actions stay: Copy, Translate, Search, Save, Carry.
        assertEquals(listOf("Copy", "Translate", "Search", "Save", "Carry"), MagicHandState.grabActions(false).map { it.label })
    }
}
