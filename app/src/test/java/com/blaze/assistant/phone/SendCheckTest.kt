package com.blaze.assistant.phone

import com.blaze.assistant.agent.ClaimGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendCheckTest {
    private val phone = listOf(
        "Gabriele\u2764\uFE0F" to "+260962010521",
        "Gabriel Bro" to "0768657822",
        "Mary Banda" to "0977123456",
        "Mary Banda" to "+260977123456",
    )

    @Test fun emojiNamesStillMatch() {
        assertTrue(SendCheck.score("Gabriel", "Gabriele\u2764\uFE0F") > 0)
        assertEquals("gabriele", SendCheck.norm("Gabriele\u2764\uFE0F"))
    }

    @Test fun twoGabrielsIsAQuestionNotNotFound() {
        val p = SendCheck.pick("Gabriel", phone)
        assertNull(p.one)
        assertEquals(2, p.choices.size)
        val line = SendCheck.ambiguous("Gabriel", p.choices)
        assertTrue(line.startsWith("Couldn't"))
        assertTrue(line.contains("ask_user"))
        assertTrue(line.contains("Gabriel Bro"))
    }

    @Test fun exactSavedNameWins() {
        assertEquals("Gabriel Bro", SendCheck.pick("gabriel bro", phone).one?.first)
        assertEquals("Gabriele\u2764\uFE0F", SendCheck.pick("Gabriele", phone).one?.first)
    }

    @Test fun sameNumberTwiceIsOnePerson() {
        assertEquals("Mary Banda", SendCheck.pick("Mary", phone).one?.first)
    }

    @Test fun typosMatch() {
        assertTrue(SendCheck.score("Gabrial", "Gabriel Bro") > 0)
        assertEquals(0, SendCheck.score("Tom", "Mary Banda"))
    }

    @Test fun lastMeansNewestImage() {
        val items = listOf("dog_1.png" to 10L, "orange_cat_2.png" to 30L, "sun_3.png" to 20L)
        assertEquals("orange_cat_2.png", SendCheck.imageArg("last", items))
        assertEquals("orange_cat_2.png", SendCheck.imageArg("the image you just made", items))
        assertEquals("sun_3.png", SendCheck.imageArg("sun", items))
        assertNull(SendCheck.imageArg("lion", items))
    }

    @Test fun pressSendOnlyInTheRightChat() {
        assertTrue(SendCheck.chatOpenFor("Gabriele\u2764\uFE0F", "260962010521", listOf("Gabriele \u2764", "online", "Message")))
        assertTrue(SendCheck.chatOpenFor("Gabriel Bro", "0768657822", listOf("+260 76 8657822", "Message")))
        assertFalse(SendCheck.chatOpenFor("Gabriel Bro", "0768657822", listOf("Chats", "Search", "Mary Banda")))
    }

    @Test fun sentOnlyWhenDraftIsGone() {
        assertEquals(SendCheck.Verdict.UNSURE, SendCheck.verdict("hello bro", false, "hello bro", listOf("Gabriel")))
        assertEquals(SendCheck.Verdict.SENT, SendCheck.verdict("hello bro", false, "", listOf("hello bro", "Message")))
        assertEquals(SendCheck.Verdict.UNSURE, SendCheck.verdict("", true, "", listOf("Add a caption...", "Send")))
        assertEquals(SendCheck.Verdict.SENT, SendCheck.verdict("", true, "", listOf("Gabriele", "Message")))
    }

    @Test fun fakeSendClaimIsCaught() {
        val fixed = ClaimGuard.unbackedSend("Message sent to Gabriel.", emptyList())
        assertNotNull(fixed)
        assertTrue(fixed!!.startsWith("I haven't actually sent it"))
        assertNull(ClaimGuard.unbackedSend("Sent orange cat to Gabriele on WhatsApp.", listOf(ClaimGuard.Call("message_contact"))))
        assertNull(ClaimGuard.unbackedSend("I couldn't send it, WhatsApp didn't open.", emptyList()))
        assertTrue(ClaimGuard.asksToSend("send my brother a cat picture on WhatsApp"))
        assertFalse(ClaimGuard.asksToSend("did you send it?"))
    }

    @Test fun unconfirmedSendIsNotSuccess() {
        assertFalse(ClaimGuard.succeeded("Couldn't confirm it was sent: I pressed Send"))
        assertFalse(ClaimGuard.succeeded("Not sent yet: WhatsApp is open"))
        assertFalse(ClaimGuard.succeeded("The user declined. Nothing was sent."))
        assertTrue(ClaimGuard.succeeded("Sent to Gabriele on WhatsApp: the picture (seen leaving the chat)."))
    }
}
