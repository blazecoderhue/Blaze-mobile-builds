package com.blaze.assistant.agent

import com.blaze.assistant.ui.RichKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.26.1: "Thinking it through…" / "Thought it through · 4 s" instead of the raw reasoning. */
class ThinkingLabelTest {
    @Test fun labelStates() {
        assertEquals("Thinking it through\u2026", ThinkingLabel.label(live = true, hasThought = true))
        assertEquals("Thought it through", ThinkingLabel.label(live = false, hasThought = true))
        assertEquals("Thought it through \u00b7 4 s", ThinkingLabel.label(live = false, hasThought = true, ms = 4_200))
        assertEquals("Thought it through \u00b7 1 min 5 s", ThinkingLabel.label(live = false, hasThought = true, ms = 65_000))
        assertEquals("Thought it through \u00b7 2 min", ThinkingLabel.label(live = false, hasThought = true, ms = 120_000))
        assertEquals("Thought it through", ThinkingLabel.label(live = false, hasThought = true, ms = 300))   // under a second: no time
        // No reasoning from the model, or the pref off: nothing at all.
        assertNull(ThinkingLabel.label(live = true, hasThought = false))
        assertNull(ThinkingLabel.label(live = false, hasThought = true, show = false))
        // Never the word "reasoning" in what the chat shows.
        for (l in listOf(ThinkingLabel.LIVE, ThinkingLabel.DONE)) assertFalse(l.lowercase().contains("reasoning"))
    }

    @Test fun clockTimesTheReasoning() {
        val c = ThinkingLabel.Clock()
        assertEquals(0L, c.ms())
        c.update("Let me", now = 1_000); c.update("Let me think", now = 3_000); c.update("Let me think about it", now = 5_500)
        assertEquals(4_500L, c.ms())
        c.update("", now = 9_000)   // a new turn starts clean
        assertEquals(0L, c.ms())
    }

    @Test fun reasoningNeverReachesTheAnswer() {
        val p = ReplyParts.split("<think>The user wants X. Secret plan.</think>Here is the answer.", final = true)
        assertEquals("Here is the answer.", p.text)
        assertTrue(p.thinking.contains("Secret plan"))
        // While streaming, an unclosed think block shows nothing in the bubble.
        assertFalse(ReplyParts.split("<think>still thinking about the secret", final = false).text.contains("secret"))
        // Copy works on the answer text only (plus readable visuals).
        assertFalse(RichKit.readable(p.text).contains("Secret"))
    }

    @Test fun chatUiNoLongerDrawsReasoningText() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val chat = File(root, "com/blaze/assistant/ui/ChatScreen.kt").readText()
        assertFalse(chat.contains("ReasoningBlock"))
        assertFalse(chat.contains("Hide reasoning"))
        assertTrue(chat.contains("ThinkingLabel.label"))
    }
}
