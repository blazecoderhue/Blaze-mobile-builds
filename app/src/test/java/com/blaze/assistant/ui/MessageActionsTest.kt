package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.2: the message action row is icons (with spoken names and long-press labels), not words. */
class MessageActionsTest {
    @Test fun everyFooterActionHasAnIcon() {
        listOf("Copy", "Quote", "Retry", "What I did", "React").forEach { l ->
            val k = MessageActionIcons.kindOf(l)
            assertTrue("$l has an icon", k != null)
            assertEquals(l, k!!.label)
            assertTrue("spoken name for $l", k.spoken.isNotBlank())
        }
        assertNull(MessageActionIcons.kindOf("Something else"))   // unknown labels stay words
    }

    @Test fun touchTargetsStayAccessibleButGlyphsSmall() {
        assertTrue(MessageActionIcons.TOUCH_DP >= 40)
        assertTrue(MessageActionIcons.GLYPH_DP in 12..18)
    }

    @Test fun footerUsesTheIconRow() {
        val src = java.io.File("src/main/java/com/blaze/assistant/ui/Bubbles.kt").readText()
        assertTrue(src.contains("MessageActionIcons.kindOf(a.first)"))
        assertTrue(java.io.File("src/main/java/com/blaze/assistant/ui/MessageActions.kt").readText().contains("onLongClick"))
    }
}
