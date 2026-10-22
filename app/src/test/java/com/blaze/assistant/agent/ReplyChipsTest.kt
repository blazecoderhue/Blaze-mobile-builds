package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.24.4: technical lines under replies are hidden by default. */
class ReplyChipsTest {
    @Test fun hidesTechnicalParts() {
        assertEquals("", ReplyChips.visible("\u26a1 instant \u00b7 12 ms", false))
        assertEquals("", ReplyChips.visible("\uD83C\uDFA8 free image \u00b7 3.1 s", false))
        assertEquals("Memory updated", ReplyChips.visible("Memory updated \u00b7 1.2 MB", false))
        assertEquals("", ReplyChips.visible("New context", false))
        assertEquals("Skill saved", ReplyChips.visible("google/gemini-2.5-flash \u00b7 Skill saved", false))
        assertEquals("Heads-up \u00b7 14:02", ReplyChips.visible("Heads-up \u00b7 14:02", false))
    }

    @Test fun hidesModelAndTier() {
        // v0.25.0: the test phone screenshot's line, old stored replies, and the new route line all show nothing by default.
        assertEquals("", ReplyChips.visible("\u2601 Free (Google Gemini \u00b7 gemini-3.5-flash) \u00b7 first word 1.2 s \u00b7 2.3 s", false))
        assertEquals("", ReplyChips.visible("\u2601 Google Gemini \u00b7 gemini-3.5-flash \u00b7 2.3 s \u00b7 3 steps", false))
        assertEquals("", ReplyChips.visible("\u2601 Blaze Cloud / groq / llama-3.3-70b-versatile \u00b7 2.3 s", false))
        assertEquals("Memory updated", ReplyChips.visible("\u2601 Blaze Cloud / gemini / gemini-flash-latest \u00b7 1.0 s \u00b7 Memory updated", false))
    }

    @Test fun showsEverythingWhenAsked() {
        assertEquals("\u26a1 instant \u00b7 12 ms", ReplyChips.visible("\u26a1 instant \u00b7 12 ms", true))
    }
}
