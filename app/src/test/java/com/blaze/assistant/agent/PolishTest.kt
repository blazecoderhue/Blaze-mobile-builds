package com.blaze.assistant.agent

import com.blaze.assistant.ui.Bubbles
import com.blaze.assistant.ui.RichKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.26.2 polish: no "Fresh start" bubble, one swipe row of chips, chart labels with an ellipsis, the design check. */
class PolishTest {
    @Test fun freshStartBubbleIsHidden() {
        assertTrue(ReplyChips.isFreshStart("Fresh start. Earlier turns are out of my context, not deleted: I'll look them up when I need them.", "New context"))
        assertTrue(ReplyChips.isFreshStart("Fresh start. Here's where we were:\n\nnote", ""))
        assertFalse(ReplyChips.isFreshStart("Fresh start sounds good to me!", ""))
        assertFalse(ReplyChips.isFreshStart("Here you go", "New context"))
    }

    @Test fun chipRowIsOneCompactSwipeRow() {
        val r = Bubbles.chipRow(listOf(" Compare prices ", "compare prices", "", "Book the cheapest one for me tomorrow morning please at the lodge", "a", "b", "c", "d"))
        assertEquals(Bubbles.CHIPS_MAX, r.size)
        assertEquals("Compare prices", r[0])
        assertTrue(r[1].endsWith("\u2026") && r[1].length == Bubbles.CHIP_CHARS)
        assertEquals(emptyList<String>(), Bubbles.chipRow(listOf(" ", "")))
    }

    @Test fun chartLabelsGetAnEllipsisNotASilentCut() {
        assertEquals("Livingsto\u2026", RichKit.axisLabel("Livingstone"))
        assertEquals("Livingston", RichKit.axisLabel("Livingston"))
        assertEquals("Ndola", RichKit.axisLabel(" Ndola "))
    }

    @Test fun designCheckCatchesBlankTextAndArrows() {
        val bare = "<html><body><p>Start -> Plan -> Build -> Ship</p></body></html>"
        val p = DesignLint.check(bare)
        assertTrue(p.any { it.contains("no styling") }); assertTrue(p.any { it.contains("text arrows") })
        assertTrue(DesignLint.check("<html><body><pre>flowchart TD\nA-->B</pre><style>body{color:#111}</style></body></html>").any { it.contains("Mermaid code") })
        val good = "<html><head><style>:root{--ink:#1d2433;--accent:#e4572e}body{font:16px/1.5 sans-serif;color:var(--ink)}</style></head><body>" +
            "<header><h1>Solar for Ndola homes</h1></header><section><div class=card><h2>Save K400 a month</h2><p>Real numbers from three installers.</p></div>" +
            "<svg viewBox='0 0 10 10'><rect width='10' height='10'/></svg></section><footer><button>Get a quote</button></footer></body></html>"
        assertEquals(emptyList<String>(), DesignLint.check(good))
        assertEquals(emptyList<String>(), DesignLint.check(bare, "3d"))
        assertTrue(DesignLint.note(bare).startsWith("\n[Design check]"))
        assertEquals("", DesignLint.note(good))
    }
}
