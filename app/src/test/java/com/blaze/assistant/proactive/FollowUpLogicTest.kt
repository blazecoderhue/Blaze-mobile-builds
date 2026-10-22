package com.blaze.assistant.proactive

import com.blaze.assistant.proactive.FollowUpLogic.Item
import com.blaze.assistant.proactive.FollowUpLogic.Status
import com.blaze.assistant.proactive.JarvisLogic.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpLogicTest {
    private val now = 1_800_000_000_000L
    private fun jack(status: Status = Status.ASKED, whenAt: Long = now + 5 * 3600_000L, replied: Boolean = false, nudgedAt: Long = 0) =
        Item("F1", "com.whatsapp", "WhatsApp", "Jack", "Jack Banda", "a meeting about the budget", "tomorrow at 15:00", whenAt, true, status, now - 60_000L,
            replied = replied, nudgedAt = nudgedAt)

    @Test fun cheapPlanCheck() {
        assertTrue(FollowUpLogic.looksLikePlan("Can we meet tomorrow at 3 about the budget?"))
        assertTrue(FollowUpLogic.looksLikePlan("lunch on friday?"))
        assertTrue(FollowUpLogic.looksLikePlan("Call me at 14:30"))
        assertFalse(FollowUpLogic.looksLikePlan("haha nice"))
        assertFalse(FollowUpLogic.looksLikePlan("ok"))
    }

    @Test fun parsesThePlan() {
        val p = FollowUpLogic.parsePlan("{\"plan\":true,\"topic\":\"a meeting about the budget\",\"when\":\"tomorrow at 15:00\",\"when_ms\":1800000000000,\"needs_reply\":true,\"ask\":\"Are you going?\"}")
        assertEquals("a meeting about the budget", p?.topic); assertEquals(1_800_000_000_000L, p?.whenAt); assertEquals("Are you going?", p?.ask)
        assertNull(FollowUpLogic.parsePlan("{\"plan\":false}"))
        assertNull(FollowUpLogic.parsePlan("{\"plan\":true,\"topic\":\"\"}"))
        assertNull(FollowUpLogic.parsePlan("nope"))
        assertEquals("", FollowUpLogic.parsePlan("{\"plan\":true,\"topic\":\"x\",\"ask\":\"no question mark\"}")?.ask)
    }

    @Test fun personalTexts() {
        assertEquals("Jack Banda texted on WhatsApp about a meeting about the budget, tomorrow at 15:00. Are you going?", FollowUpLogic.askText(jack()))
        assertEquals("I see you're in WhatsApp. Jack Banda wanted to talk about a meeting about the budget, tomorrow at 15:00. Maybe get back to Jack?", FollowUpLogic.nudgeText(jack()))
        assertTrue(FollowUpLogic.askText(jack().copy(ask = "Want to reply?")).endsWith("Want to reply?"))
        assertEquals("Jack Banda (WhatsApp): a meeting about the budget, tomorrow at 15:00, going, not replied yet", FollowUpLogic.line(jack(Status.GOING)))
    }

    @Test fun nudgesOnlyWhileWaiting() {
        assertTrue(FollowUpLogic.shouldNudge(jack(), "com.whatsapp", now))
        assertFalse(FollowUpLogic.shouldNudge(jack(), "org.telegram.messenger", now))
        assertFalse(FollowUpLogic.shouldNudge(jack(replied = true), "com.whatsapp", now))
        assertFalse(FollowUpLogic.shouldNudge(jack(nudgedAt = now - 30 * 60_000L), "com.whatsapp", now))
        assertTrue(FollowUpLogic.shouldNudge(jack(nudgedAt = now - 3 * 3600_000L), "com.whatsapp", now))
        assertFalse(FollowUpLogic.shouldNudge(jack(Status.NOT_GOING), "com.whatsapp", now))
        assertFalse(FollowUpLogic.shouldNudge(jack(whenAt = now - 3 * 3600_000L), "com.whatsapp", now))   // long past
        assertFalse(FollowUpLogic.live(jack(whenAt = 0).copy(created = now - 4 * 86_400_000L), now))
    }

    @Test fun reminderTime() {
        assertEquals(now + 3600_000L - 30 * 60_000L, FollowUpLogic.reminderAt(now + 3600_000L, now))
        assertEquals(now + 60_000L, FollowUpLogic.reminderAt(now + 10 * 60_000L, now))
        assertEquals(0L, FollowUpLogic.reminderAt(now - 1, now))
        assertEquals(0L, FollowUpLogic.reminderAt(0, now))
    }

    @Test fun storesAndReadsBack() {
        val list = listOf(jack(), jack(Status.GOING).copy(id = "F2", ask = "Want to reply?"))
        assertEquals(list, FollowUpLogic.fromJson(FollowUpLogic.toJson(list)))
        assertTrue(FollowUpLogic.fromJson("junk").isEmpty())
    }

    @Test fun sendButtons() {
        assertTrue(FollowUpLogic.isSend("Send")); assertTrue(FollowUpLogic.isSend(" send message "))
        assertFalse(FollowUpLogic.isSend("Sender info")); assertFalse(FollowUpLogic.isSend(null))
    }

    @Test fun callOffers() {
        assertTrue(JarvisLogic.callDue(Level.EAGER, now - 4 * 86_400_000L, now, 18))
        assertFalse(JarvisLogic.callDue(Level.EAGER, now - 86_400_000L, now, 18))
        assertFalse(JarvisLogic.callDue(Level.EAGER, 0, now, 10))
        assertFalse(JarvisLogic.callDue(Level.GENTLE, 0, now, 18))
        assertTrue(JarvisLogic.callLine(2, 18).contains("2 things"))
        assertTrue(JarvisLogic.callLine(1, 18).contains("1 thing waiting"))
    }
}
