package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.6 rapid messages: ids, reply markers, bursts and quote chips. */
class RapidMessagesTest {
    private fun m(id: Long, role: Role, text: String = "x", replyTo: List<Long> = emptyList()) =
        ChatMessage(id, role, text, at = 1_000L * id, replyTo = replyTo)

    @Test fun labelsAndUntags() {
        assertEquals("[m7] hi", RapidMessages.label(7, "hi"))
        assertEquals("[m8 \u00b7 sent while you were working] also this", RapidMessages.label(8, "also this", midTurn = true))
        assertEquals("hi", RapidMessages.untag(RapidMessages.label(7, "hi")))
        assertEquals("also this", RapidMessages.untag(RapidMessages.label(8, "also this", true)))
        // the user's own brackets mid-text are kept
        assertEquals("see [m3] above", RapidMessages.untag("see [m3] above"))
        assertTrue(RapidMessages.shouldTag(true, "what's the weather"))
        assertFalse(RapidMessages.shouldTag(false, "agent task"))
        assertFalse(RapidMessages.shouldTag(true, "[Scheduled routine \"x\"] do it"))
        assertTrue(RapidMessages.PROMPT_RULE.contains("[re:m7]"))
        assertTrue(RapidMessages.answeredNote("[m9] time?", "It's 17:40.").contains("It's 17:40."))
        assertEquals("[m9] time?", RapidMessages.answeredNote("[m9] time?", ""))
    }

    @Test fun displayStripsMarkers() {
        assertEquals("Sure, done.", RapidMessages.display("[re:m7] Sure, done."))
        assertEquals("Sure, done.", RapidMessages.display("[re: m7, m8]\nSure, done."))
        assertEquals("A\n\nB", RapidMessages.display("[re:m7] A\n\n[re:m8] B"))
        assertEquals("Echoed id", RapidMessages.display("[m12] Echoed id"))
        assertEquals("Inline gone", RapidMessages.display("Inline [RE:m4]gone"))
        // markdown links and plain brackets stay
        assertEquals("See [the docs](https://x.y) and [1].", RapidMessages.display("See [the docs](https://x.y) and [1]."))
        assertEquals("No markers", RapidMessages.display("No markers"))
    }

    @Test fun streamingHoldsAnUnfinishedMarker() {
        listOf("[", "[r", "[re", "[re:", "[re:m1", "[re:m1,", "[re:m1, m2", "[m1").forEach {
            assertEquals(it, "", RapidMessages.display(it, final = false))
        }
        assertEquals("Hello", RapidMessages.display("[re:m3] Hello", final = false))
        assertEquals("Hello", RapidMessages.display("Hello [re", final = false))
        assertEquals("Hello [x", RapidMessages.display("Hello [x", final = false))
    }

    @Test fun splitsRepliesByMarker() {
        val known = setOf(7L, 8L, 9L)
        assertEquals(listOf(RapidMessages.Part(emptyList(), "Plain answer")), RapidMessages.split("Plain answer", known))
        assertEquals(listOf(RapidMessages.Part(listOf(7L, 8L), "Both at once.")), RapidMessages.split("[re:m7,m8] Both at once.", known))
        val two = RapidMessages.split("[re:m7] The weather is fine.\n\n[re:m9]\nYour alarm is set.", known)
        assertEquals(2, two.size)
        assertEquals(RapidMessages.Part(listOf(7L), "The weather is fine."), two[0])
        assertEquals(RapidMessages.Part(listOf(9L), "Your alarm is set."), two[1])
        // unknown ids are dropped; words before the first marker stay as their own part
        val odd = RapidMessages.split("Okay.\n[re:m7,m42] Yes.", known)
        assertEquals(listOf(RapidMessages.Part(emptyList(), "Okay."), RapidMessages.Part(listOf(7L), "Yes.")), odd)
        // a marker with nothing after it lends its ids to the next part
        assertEquals(listOf(RapidMessages.Part(listOf(7L, 8L), "One reply.")), RapidMessages.split("[re:m7]\n[re:m8] One reply.", known))
        // only a marker: empty text, refs kept (the caller shows its fallback)
        assertEquals(listOf(RapidMessages.Part(listOf(8L), "")), RapidMessages.split("[re:m8]", known))
        // an echoed bare id counts as a marker
        assertEquals(listOf(RapidMessages.Part(listOf(9L), "Hi")), RapidMessages.split("[m9] Hi", known))
    }

    @Test fun chipsOnlyWhenTheQuotedMessageIsntRightAbove() {
        // m1 asked, Blaze answered directly below: no chip
        val direct = listOf(m(1, Role.USER), m(2, Role.STEP), m(3, Role.BLAZE, replyTo = listOf(1)))
        assertTrue(RapidMessages.chipMap(direct).isEmpty())
        // m1, m2 then replies to each: the first is quoted (m2 sits between), the second too (a reply sits between)
        val apart = listOf(m(1, Role.USER), m(2, Role.USER), m(3, Role.BLAZE, replyTo = listOf(1)), m(4, Role.BLAZE, replyTo = listOf(2)))
        assertEquals(mapOf(3L to listOf(1L), 4L to listOf(2L)), RapidMessages.chipMap(apart))
        // one reply to several: quoted, at most MAX_CHIPS
        val many = listOf(m(1, Role.USER), m(2, Role.USER), m(3, Role.USER), m(4, Role.USER), m(5, Role.BLAZE, replyTo = listOf(1, 2, 3, 4)))
        assertEquals(listOf(1L, 2L, 3L), RapidMessages.chipMap(many)[5L])
        // a quoted message that's gone (cleared) or isn't a user message: no chip
        val gone = listOf(m(5, Role.USER), m(6, Role.BLAZE, replyTo = listOf(1)), m(7, Role.BLAZE, replyTo = listOf(6)))
        assertTrue(RapidMessages.chipMap(gone).isEmpty())
        // old chats (no replyTo anywhere)
        assertTrue(RapidMessages.chipMap(listOf(m(1, Role.USER), m(2, Role.BLAZE))).isEmpty())
    }

    @Test fun snippetIsOneShortLine() {
        assertEquals("fix the bug", RapidMessages.snippet("fix the bug"))
        assertEquals("and the header too", RapidMessages.snippet("> quoted bit\n\nand the header\ntoo"))
        val long = RapidMessages.snippet("a".repeat(200))
        assertEquals(RapidMessages.CHIP_CHARS, long.length)
        assertTrue(long.endsWith("\u2026"))
    }

    @Test fun burstWaitsWhileYouType() {
        val t0 = 100_000L
        // not typing: a short grace only
        assertEquals(RapidMessages.GRACE_MS, RapidMessages.burstWait(t0, t0, 0L, false))
        assertEquals(0L, RapidMessages.burstWait(t0 + RapidMessages.GRACE_MS, t0, 0L, false))
        // typing again after the send: wait until you pause for BURST_MS
        assertEquals(RapidMessages.BURST_MS, RapidMessages.burstWait(t0, t0, t0, true))
        assertEquals(RapidMessages.BURST_MS, RapidMessages.burstWait(t0 + 1_000, t0, t0 + 1_000, true))
        assertEquals(0L, RapidMessages.burstWait(t0 + 2_600, t0, t0 + 1_000, true))
        // a keystroke from before the send doesn't count
        assertEquals(RapidMessages.GRACE_MS, RapidMessages.burstWait(t0, t0, t0 - 50, true))
        // never more than BURST_MAX_MS after the last send
        assertEquals(500L, RapidMessages.burstWait(t0 + RapidMessages.BURST_MAX_MS - 500, t0, t0 + RapidMessages.BURST_MAX_MS - 500, true))
        assertEquals(0L, RapidMessages.burstWait(t0 + RapidMessages.BURST_MAX_MS, t0, t0 + RapidMessages.BURST_MAX_MS, true))
    }

    @Test fun routes() {
        // v0.45.8: a typed message starts its turn at once (no burst wait); later ones join that turn at its next step.
        assertEquals(RapidMessages.Route.NOW, RapidMessages.route(busy = false, waiting = false, burst = false))
        assertEquals(RapidMessages.Route.NOW, RapidMessages.route(busy = false, waiting = false, burst = true))
        assertEquals(RapidMessages.Route.JOIN_BURST_NOW, RapidMessages.route(busy = false, waiting = true, burst = true))
        assertEquals(RapidMessages.Route.JOIN_BURST_NOW, RapidMessages.route(busy = false, waiting = true, burst = false))
        // The 0.30.6 routing is kept only for the before/after bench.
        assertEquals(RapidMessages.Route.BURST, RapidMessages.legacyRoute(busy = false, waiting = false, burst = true))
        assertEquals(RapidMessages.Route.BURST, RapidMessages.legacyRoute(busy = false, waiting = true, burst = true))
        assertEquals(RapidMessages.Route.MID_TURN, RapidMessages.route(busy = true, waiting = false, burst = true))
        assertEquals(RapidMessages.Route.MID_TURN, RapidMessages.route(busy = true, waiting = true, burst = false))
    }
}
