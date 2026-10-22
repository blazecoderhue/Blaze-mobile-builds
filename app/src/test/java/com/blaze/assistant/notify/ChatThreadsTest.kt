package com.blaze.assistant.notify

import com.blaze.assistant.notify.ChatThreads.Line
import com.blaze.assistant.notify.ChatThreads.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.6 item 11 (P1): a grouped WhatsApp chat ("Cyp home", 19:24) mixed up who asked whom. */
class ChatThreadsTest {
    private val me = listOf("Luke")
    private val t0 = 1_760_000_000_000L

    private fun cyp() = listOf(
        Line(t0, "Stefano", "Deborah, are you free Thursday at 9:00?"),
        Line(t0 + 60_000, "Debi", "yes"),
        Line(t0 + 120_000, "Elena", "Pronto"),
    )

    @Test fun cypHomeQuestionForSomeoneElseNeverAsksTheUser() {
        val a = ChatThreads.lastAsk(cyp(), group = true, me = me)!!
        assertEquals("Stefano", a.from)
        assertEquals(Target.OTHER, a.target)
        assertEquals("Deborah", a.to)
        assertTrue("Debi is Deborah, so it's answered", a.answered)
        assertFalse(ChatThreads.shouldAskUser(a))
        val d = ChatThreads.decide("Cyp home", true, cyp(), me)
        assertFalse(d.askUser)
        assertTrue(d.summary, d.summary.startsWith("In Cyp home: "))
        assertTrue(d.summary, d.summary.contains("Stefano asked Deborah"))
        assertTrue(d.summary, d.summary.contains("Debi: \u201cyes\u201d") && d.summary.contains("Elena: \u201cPronto\u201d"))
        assertFalse("never auto-sends into a group", ChatThreads.canAutoSend(group = true, autoOn = true))
    }

    @Test fun cypHomeSpeakersSplitFromLegacyTitles() {
        assertEquals(Triple("Cyp home", "Elena", "Pronto"), ChatThreads.splitLegacy("Cyp home: Elena", "Pronto"))
        assertEquals(Triple("Cyp home", "Debi", "yes"), ChatThreads.splitLegacy("Debi @ Cyp home", "yes"))
        assertEquals("Cyp home", ChatThreads.splitLegacy("Cyp home (3 messages)", "Stefano: Thursday?").first)
        assertEquals("Stefano", ChatThreads.splitLegacy("Cyp home (3 messages)", "Stefano: Thursday?").second)
        assertTrue(ChatThreads.samePerson("Deborah", "Debi"))
        assertFalse(ChatThreads.samePerson("Stefano", "Elena"))
    }

    @Test fun groupQuestionToTheUserOrEveryoneAsks() {
        val toMe = listOf(Line(t0, "Stefano", "Luke, can you bring the keys?"))
        assertEquals(Target.USER, ChatThreads.lastAsk(toMe, true, me)!!.target)
        assertTrue(ChatThreads.decide("Cyp home", true, toMe, me).askUser)
        val all = listOf(Line(t0, "Elena", "Is everyone home tonight?"))
        assertEquals(Target.GROUP, ChatThreads.lastAsk(all, true, me)!!.target)
        val answered = all + Line(t0 + 1, "Luke", "yes", self = true)
        assertFalse(ChatThreads.decide("Cyp home", true, answered, me).askUser)
    }

    @Test fun oneToOneChatIsAlwaysForTheUser() {
        val lines = listOf(Line(t0, "Gloria", "Are you coming tonight?"))
        val a = ChatThreads.lastAsk(lines, group = false, me = me)!!
        assertEquals(Target.USER, a.target)
        assertTrue(ChatThreads.shouldAskUser(a))
        val d = ChatThreads.decide("Gloria", false, lines, me)
        assertTrue(d.askUser)
        assertTrue(d.summary, d.summary.startsWith("From Gloria: "))
        assertTrue(ChatThreads.canAutoSend(group = false, autoOn = true))
        assertFalse(ChatThreads.canAutoSend(group = false, autoOn = false))
    }

    @Test fun bufferDropsRepeatedHistory() {
        val b = ChatThreads.Buffer()
        assertEquals(3, b.add(cyp()).size)
        assertEquals(0, b.add(cyp()).size)
        assertEquals(1, b.add(cyp() + Line(t0 + 180_000, "Stefano", "Perfect")).size)
        assertEquals(4, b.size)
    }
}
