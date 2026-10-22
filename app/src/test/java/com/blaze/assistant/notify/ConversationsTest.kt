package com.blaze.assistant.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Notification replies (v0.19.0): OTP and bank filtering, conversation keys, MessagingStyle dedupe, the rolling log. */
class ConversationsTest {
    @Before fun reset() = Conversations.reset()

    @Test fun codesAndBankMessagesAreSensitive() {
        listOf(
            "Your WhatsApp code: 123-456. Don't share this code with others",
            "G-482913 is your Google verification code.",
            "Your OTP is 849302",
            "Use 5521 to sign in to your account",
            "Your PIN is 4471",
            "Zanaco: Your account was debited ZMW 450.00. Available balance ZMW 1,203.55",
            "You have received K250.00 from JOHN BANDA. Airtel Money",
            "MTN MoMo: Cash out of K100 successful. Txn ID 88123",
            "Card ending 4421 was used at SHOPRITE",
        ).forEach { assertTrue(it, Sensitive.isSensitive(it)) }
        listOf(
            "See you at 1500 at the cafe",
            "Lecture moved to room 204 tomorrow",
            "Are you coming to church on Sunday?",
            "Haha the code review went fine",
            "Call me when you get to Ndola",
            "",
        ).forEach { assertFalse(it, Sensitive.isSensitive(it)) }
        assertTrue(Sensitive.isSensitive("884213", title = "Your verification code"))
    }

    @Test fun keysAndTitles() {
        assertEquals("Gloria", Conversations.cleanTitle("Gloria (3 messages)", false))
        assertEquals("Family", Conversations.cleanTitle("Family: Mutale", true))
        assertEquals("Ba: Mum", Conversations.cleanTitle("Ba: Mum", false))
        assertEquals("wa:gloria-gozza", Conversations.key("com.whatsapp", "Gloria Gozza"))
        assertEquals("sms:mum", Conversations.key("com.google.android.apps.messaging", "Mum"))
        assertEquals("tg:cs-year-2", Conversations.key("org.telegram.messenger", "CS Year 2!"))
        assertEquals("notif:wa:gloria-gozza", Conversations.journalConv("wa:gloria-gozza"))
        assertTrue(Conversations.isMessaging("com.whatsapp", null, false))
        assertTrue(Conversations.isMessaging("com.example.chat", null, true))
        assertFalse(Conversations.isMessaging("com.example.shop", "promo", false))
    }

    @Test fun messagingStyleHistoryIsDedupedAndCodesNeverStored() {
        val t = 1_760_000_000_000L
        val m1 = Conversations.Msg(t, "Gloria", "Are you free Friday?")
        val m2 = Conversations.Msg(t + 60_000, "Gloria", "For the group project")
        val code = Conversations.Msg(t + 90_000, "Gloria", "Your WhatsApp code: 123-456")
        assertEquals(2, Conversations.add("com.whatsapp", "WhatsApp", "Gloria", false, listOf(m1, m2, code), null))
        // The next post repeats the history plus one new message (and the user's own reply).
        val m3 = Conversations.Msg(t + 120_000, "You", "Yes, after 2", self = true)
        assertEquals(1, Conversations.add("com.whatsapp", "WhatsApp", "Gloria", false, listOf(m1, m2, m3), null))
        assertEquals(0, Conversations.add("com.whatsapp", "WhatsApp", "Gloria", false, listOf(m1, m2, m3), null))
        val c = Conversations.find("gloria")!!
        assertEquals("wa:gloria", c.key)
        assertEquals(listOf("Are you free Friday?", "For the group project", "Yes, after 2"), c.msgs.map { it.text })
        assertTrue(c.msgs.none { it.text.contains("123") })
        assertEquals(c, Conversations.find("wa:gloria"))
        assertEquals(c, Conversations.find("Glo"))
    }

    @Test fun rollingLogKeepsTheNewest() {
        val t = 1_760_000_000_000L
        val msgs = (1..60).map { Conversations.Msg(t + it * 1000L, "Mutale", "message $it") }
        Conversations.add("org.telegram.messenger", "Telegram", "Mutale", false, msgs, null)
        val c = Conversations.find("mutale")!!
        assertEquals(Conversations.KEEP_IN_MEMORY, c.msgs.size)
        assertEquals("message 60", c.msgs.last().text)
        assertEquals("message 21", c.msgs.first().text)
    }

    @Test fun freshAndRender() {
        val a = Conversations.Msg(1000, "A", "hi"); val b = Conversations.Msg(2000, "A", "hi")
        assertEquals(listOf(a, b), Conversations.fresh(emptySet(), listOf(a, b, a)))
        assertEquals(listOf(b), Conversations.fresh(setOf(a.sig), listOf(a, b)))
        val f = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals("00:00 A: hi\n00:00 You: ok", Conversations.render(listOf(a, Conversations.Msg(3000, "You", "ok", true)), f))
        assertTrue(Conversations.thin(3)); assertFalse(Conversations.thin(4))
        assertEquals(setOf("wa:gloria", "sms:mum"), Conversations.autoKeys("[\"wa:gloria\",\"sms:mum\"]"))
        assertEquals(emptySet<String>(), Conversations.autoKeys("not json"))
    }
}
