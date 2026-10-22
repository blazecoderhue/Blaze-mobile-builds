package com.blaze.assistant.agent

import com.blaze.assistant.agent.SimpleMode.Work
import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.41.0: Simple mode shows a quiet line while working and nothing once done; Advanced keeps the step card. */
class SimpleWorkTest {
    @Test fun simpleHidesTheCard() {
        assertEquals(Work.HINT, SimpleMode.work(simple = true, showSteps = false, live = true))
        assertEquals(Work.NONE, SimpleMode.work(simple = true, showSteps = false, live = false))
    }

    @Test fun advancedOrShowStepsKeepsTheCard() {
        assertEquals(Work.CARD, SimpleMode.work(simple = false, showSteps = false, live = true))
        assertEquals(Work.CARD, SimpleMode.work(simple = false, showSteps = false, live = false))
        assertEquals(Work.CARD, SimpleMode.work(simple = true, showSteps = true, live = false))
    }

    @Test fun hintIsOneShortLine() {
        assertEquals("Looking at your series\u2026", SimpleMode.hint("", "Looking at your series."))
        assertEquals("Working on it\u2026", SimpleMode.hint("", ""))
        assertEquals("Sending Jack the location\u2026", SimpleMode.hint("Sending Jack the location\u2026\nmore", "old"))
        val long = SimpleMode.hint("Reading every single message in the family group chat to find the address you asked about", "")
        assert(long.length <= 49) { long }
        assert(!long.contains('\n'))
    }
}
