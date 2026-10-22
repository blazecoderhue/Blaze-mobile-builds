package com.blaze.assistant.agent

import com.blaze.assistant.ui.DesignStatus
import com.blaze.assistant.ui.DesignStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.6: Design never says "Done" unless it rendered (user 21:34-21:40). */
class DesignTruthTest {
    private fun st(s: State, d: String = "") = DesignStatus.Status(s, d, 1L)

    @Test fun doneOnlyWhenRendered() {
        val r = "Done. Your 3D rocket is open in the Design view \u2014 tap Open in Design to orbit and zoom."
        assertEquals(r, DesignTruth.ground(r, st(State.RENDERED, "drew on screen (r170)")))
        val pending = DesignTruth.ground(r, null)
        assertFalse(pending, pending.startsWith("Done"))
        assertTrue(pending, pending.startsWith("Saved; it's still opening"))
        val failed = DesignTruth.ground(r, st(State.FAILED, "The 3D scene (r170) rendered but nothing was visible"))
        assertTrue(failed, failed.startsWith("It didn't render: The 3D scene (r170) rendered but nothing was visible."))
        assertFalse(failed, failed.contains("Done."))
        val intro = DesignTruth.ground("Done. Your 3-second animated intro is ready in Design. Tap the canvas to skip ahead.", null)
        assertFalse(intro, intro.contains("is ready"))
    }

    @Test fun motionAsksGetTheHonestStatus() {
        assertTrue(DesignTruth.isMotionAsk("Motion design: Make it loop smoothly"))
        assertFalse(DesignTruth.isMotionAsk("make a 3d rocket"))
        val g = DesignTruth.ground("Made it loop smoothly.", st(State.RENDERED, "page loaded"), motion = true)
        assertTrue(g, g.endsWith("The Motion timeline isn't built yet; your intro is on the Page tab."))
        assertEquals("The Motion timeline isn't built yet; your model is on the 3D tab.", DesignTruth.motionStatus(true))
    }

    @Test fun toolLineSaysWhatTheViewProved() {
        assertTrue(DesignTruth.toolLine(st(State.RENDERED, "page loaded")).contains("rendered (page loaded)"))
        assertTrue(DesignTruth.toolLine(st(State.FAILED, "WebGL is off")).contains("FAILED: WebGL is off"))
        assertTrue(DesignTruth.toolLine(null).contains("Do NOT say done"))
    }

    @Test fun statusIsPerFileAndFresh() {
        DesignStatus.clear()
        DesignStatus.pending("/h/designs/rocket/index.html", 100)
        assertEquals(State.PENDING, DesignStatus.of("file:///h/designs/rocket/index.html")!!.state)
        DesignStatus.rendered("/h/designs/rocket/index.html", "drew", 200)
        assertNull(DesignStatus.since("/h/designs/rocket/index.html", 300))
        assertEquals(State.RENDERED, DesignStatus.since("/h/designs/rocket/index.html", 150)!!.state)
        DesignStatus.clear()
    }
}
