package com.blaze.assistant.phone

import com.blaze.assistant.phone.MagicHandLogic.Box
import com.blaze.assistant.phone.MagicHandState.GrabAction
import com.blaze.assistant.phone.MagicHandState.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.28.1: the instant grab card, queued actions and the OCR line filter. */
class MagicHandOcrTest {
    private val region = Box(100, 100, 500, 300)

    @Test fun linesMostlyOutsideTheBoxAreDropped() {
        val lines = listOf(
            MagicHandOcr.Line(Box(110, 60, 480, 104), "ervAnood lot Hark handlA"),   // cut by the top edge: 4 of 44 px inside
            MagicHandOcr.Line(Box(110, 120, 480, 150), "Galaxy A15 128GB"),
            MagicHandOcr.Line(Box(110, 160, 300, 190), "K1,299"),
            MagicHandOcr.Line(Box(450, 200, 700, 230), "half outside right"),        // 50 of 250 px wide inside
            MagicHandOcr.Line(Box(110, 280, 480, 320), "In stock"),                  // 20 of 40 px: 50 %, dropped
        )
        assertEquals("Galaxy A15 128GB\nK1,299", MagicHandOcr.keep(lines, region))
    }

    @Test fun mostlyInsideIsKept() {
        val l = MagicHandOcr.Line(Box(110, 270, 480, 310), "Free delivery")  // 30 of 40 px = 75 %
        assertEquals("Free delivery", MagicHandOcr.keep(listOf(l), region))
    }

    @Test fun insideFraction() {
        assertEquals(1f, MagicHandOcr.insideFraction(Box(120, 120, 140, 140), region), 0.001f)
        assertEquals(0f, MagicHandOcr.insideFraction(Box(0, 0, 50, 50), region), 0.001f)
        assertEquals(0.5f, MagicHandOcr.insideFraction(Box(80, 120, 120, 140), region), 0.001f)
        assertEquals(0f, MagicHandOcr.insideFraction(Box(10, 10, 10, 10), region), 0.001f)
    }

    @Test fun sameRowIsReadLeftToRight() {
        val lines = listOf(
            MagicHandOcr.Line(Box(300, 122, 480, 148), "K1,299"),
            MagicHandOcr.Line(Box(110, 120, 280, 150), "Price"),
            MagicHandOcr.Line(Box(110, 200, 280, 230), "Next"),
        )
        assertEquals("Price\nK1,299\nNext", MagicHandOcr.keep(lines, region))
    }

    @Test fun cropHasAMarginAndStaysOnScreen() {
        assertEquals(Box(84, 84, 516, 316), MagicHandOcr.cropFor(region, 16, 720, 1600))
        assertEquals(Box(0, 0, 720, 1600), MagicHandOcr.cropFor(Box(5, 5, 715, 1595), 16, 720, 1600))
        assertEquals(Box(16, 16, 416, 216), MagicHandOcr.regionIn(Box(84, 84, 516, 316), region))
        assertEquals(Box(8, 8, 208, 108), MagicHandOcr.regionIn(Box(84, 84, 516, 316), region, 0.5f))
    }

    @Test fun bigCropsAreDownscaledSmallOnesNot() {
        assertEquals(1f, MagicHandOcr.scaleFor(720, 400), 0.0001f)
        assertEquals(0.5f, MagicHandOcr.scaleFor(2560, 1000), 0.0001f)
        assertEquals(1f, MagicHandOcr.scaleFor(0, 0), 0.0001f)
    }

    @Test fun cardOpensAtOnceWithShimmerAndUsableActions() {
        val u = MagicHandState.reading(MagicHandState.start(false).copy(grabbed = "old text"), "Chrome")
        assertEquals(Panel.GRAB, u.panel)
        assertEquals("", u.grabbed)          // nothing from the previous grab
        assertTrue(u.grabBusy)                // shimmer
        assertEquals("Chrome", u.grabSource)
        assertTrue(MagicHandState.grabActionsEnabled(u))
    }

    @Test fun actionTappedWhileReadingWaitsForTheText() {
        val u = MagicHandState.reading(MagicHandState.start(false), "Chrome")
        val (q, run) = MagicHandState.tapGrab(u, GrabAction.COPY)
        assertNull(run)
        assertEquals(GrabAction.COPY, q.queued)
        val done = MagicHandState.grabbed(q, "Galaxy A15", "Chrome")
        assertEquals(GrabAction.COPY, done.queued)
        assertFalse(done.grabBusy)
        val (after, now) = MagicHandState.tapGrab(done, done.queued!!)
        assertEquals(GrabAction.COPY, now)
        assertNull(after.queued)
    }

    @Test fun emptyReadDropsTheQueue() {
        val u = MagicHandState.tapGrab(MagicHandState.reading(MagicHandState.start(false), "X"), GrabAction.SAVE).first
        val r = MagicHandState.grabbed(u, "", "X")
        assertNull(r.queued)
        assertEquals(Panel.STATUS, r.panel)
    }

    @Test fun privateNeverOpensTheCard() {
        val u = MagicHandState.reading(MagicHandState.start(true), "X")
        assertEquals(Panel.STATUS, u.panel)
        assertFalse(u.grabBusy)
    }
}
