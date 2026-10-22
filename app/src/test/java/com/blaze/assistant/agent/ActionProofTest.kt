package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.28.8: Blaze must never say it did something it didn't. Every row is (what the user asked, Blaze's final reply,
 * tools that succeeded this turn, the action that must be flagged or null). The first rows are real failures.
 */
class ActionProofTest {
    private data class Case(val user: String, val reply: String, val ok: List<String>, val flag: String?)

    private val cases = listOf(
        // Real: opened WhatsApp, said sent, nothing was sent.
        Case("look on whatsapp and send Gabriel the cat picture", "Okay. Message sent to Gabriel.", listOf("open_app"), "send"),
        Case("send my brother the orange cat on whatsapp", "Sent orange cat to Gabriele\u2764\uFE0F on WhatsApp.", listOf("generate_image"), "send"),
        Case("send my brother the orange cat on whatsapp", "Sent the picture to Gabriele\u2764\uFE0F on WhatsApp.", listOf("generate_image", "message_contact"), null),
        // Paraphrases of a fake send.
        Case("text mum I'm on my way", "Done! I've texted your mum.", emptyList(), "send"),
        Case("tell Mary I'll be late", "I just messaged Mary that you'll be late.", listOf("find_contact"), "send"),
        Case("reply yes to John", "Your reply has been sent to John.", emptyList(), "send"),
        Case("email the report to Sam", "The email was sent to Sam.", listOf("gmail_search"), "send"),
        Case("whatsapp Peter hi", "I've WhatsApped Peter.", emptyList(), "send"),
        // Honest send answers pass.
        Case("send Gabriel hi", "I couldn't send it: WhatsApp didn't open his chat.", emptyList(), null),
        Case("send Gabriel hi", "Which Gabriel do you mean, Gabriele\u2764\uFE0F or Gabriel Bro?", listOf("message_contact"), null),
        Case("send Gabriel hi", "Want me to send it on WhatsApp?", emptyList(), null),
        Case("did you send it?", "Yes, I sent it earlier.", emptyList(), null),
        // Calls.
        Case("call my brother", "Calling Gabriel now.", emptyList(), "call"),
        Case("call my brother", "I called Gabriel.", listOf("call_number"), null),
        Case("ring the office", "I rang the office for you.", listOf("find_contact"), "call"),
        // Alarms and timers.
        Case("wake me at 6", "Alarm set for 6:00 AM.", emptyList(), "alarm"),
        Case("set a timer for 10 minutes", "I've started a 10 minute timer.", emptyList(), "alarm"),
        Case("set a timer for 10 minutes", "Done, I set a 10 minute timer.", listOf("set_timer"), null),
        Case("wake me at 6", "I'll wake you at 6.", listOf("set_alarm"), null),
        // Reminders and calendar.
        Case("remind me to pay rent on Friday", "I'll remind you on Friday to pay rent.", emptyList(), "reminder"),
        Case("add a meeting tomorrow at 3", "I've added the meeting to your calendar for 3 PM tomorrow.", emptyList(), "reminder"),
        Case("add a meeting tomorrow at 3", "I've added the meeting to your calendar.", listOf("add_calendar_event"), null),
        // Settings.
        Case("turn on wifi", "I've turned on Wi-Fi.", emptyList(), "setting"),
        Case("turn on wifi", "Wi-Fi is now on.", listOf("open_app"), "setting"),
        Case("turn on wifi", "Wi-Fi is now on.", listOf("device_setting"), null),
        Case("mute my phone", "Done, I muted your phone.", emptyList(), "setting"),
        Case("switch to a faster model", "I switched to a faster model.", emptyList(), null),
        // Memory.
        Case("remember that my brother is Gabriele with a heart", "Got it, I'll remember that.", emptyList(), "memory"),
        Case("remember that my brother is Gabriele with a heart", "Saved to memory.", listOf("memory_save"), null),
        Case("save this pdf", "I've saved it to Downloads.", listOf("export_file"), null),
        // Files, opening, installs, music.
        Case("make a pdf of my notes", "I've created the PDF in your Downloads.", emptyList(), "file"),
        Case("open youtube", "I've opened YouTube.", emptyList(), "open"),
        Case("open youtube", "I've opened YouTube.", listOf("open_app"), null),
        Case("install ubuntu", "I've installed Ubuntu for you.", emptyList(), "install"),
        Case("download that video", "I downloaded the video.", emptyList(), "download"),
        Case("save Mike's number 0977000000", "I've added Mike as a contact.", emptyList(), "contact"),
        Case("play some Burna Boy", "Now playing Burna Boy on Spotify.", emptyList(), "music"),
        Case("play some Burna Boy", "Now playing Burna Boy on Spotify.", listOf("spotify_play"), null),
        // Plain answers are never flagged.
        Case("what's the capital of Zambia", "Lusaka is the capital of Zambia.", emptyList(), null),
        Case("how do I turn on wifi", "Swipe down and tap the Wi-Fi tile.", emptyList(), null),
        Case("can you call people?", "Yes, I can call any contact when you ask.", emptyList(), null),
    )

    @Test fun everyCase() {
        val wrong = cases.mapNotNull { c ->
            val got = ActionProof.unbacked(c.reply, c.ok, c.user)?.id
            if (got != c.flag) "\"${c.reply}\" (asked \"${c.user}\", ok=${c.ok}): expected ${c.flag}, got $got" else null
        }
        assertTrue("Wrong verdicts:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun honestReplacesOnlyTheClaim() {
        val a = ActionProof.ACTIONS.first { it.id == "send" }
        val out = ActionProof.honest("Message sent to Gabriel. He usually replies fast in the evening.", a)
        assertTrue(out.startsWith("I didn't actually send that message"))
        assertTrue(out.contains("He usually replies fast"))
        assertFalse(out.contains("Message sent"))
    }

    @Test fun nudgeDemandsARealTool() {
        val n = ActionProof.nudge(ActionProof.ACTIONS.first { it.id == "alarm" })
        assertTrue(n.contains("NOT happened")); assertTrue(n.contains("set_alarm"))
    }

    @Test fun failedToolResultsNeverCountAsDone() {
        for (r in listOf("Couldn't send: WhatsApp isn't installed.", "Couldn't confirm it was sent", "Not sent yet: phone control is off",
            "The user declined. Nothing was sent.", "The user didn't allow contacts.", "Error: timeout", "Permission denied: CALL_PHONE",
            "No contact matches \"Bob\".", "I couldn't find one contact called Gabriel.", "Need who to message."))
            assertFalse(r, ClaimGuard.succeeded(r))
        for (r in listOf("Sent to Gabriele on WhatsApp.", "Calling Gabriel.", "Alarm set for 06:00.", "Saved to memory."))
            assertTrue(r, ClaimGuard.succeeded(r))
    }

    /** Every tool named as proof must really exist, or a typo would make that action unprovable (or always flagged). */
    @Test fun proofToolsExist() {
        val src = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val missing = ActionProof.ACTIONS.flatMap { it.tools }.distinct().filter { "\"$it\"" !in src }
        assertTrue("Unknown proof tools: $missing", missing.isEmpty())
    }

    /** message_contact may only say "Sent to" from the notification reply or after the on-screen check says SENT. */
    @Test fun messagingOnlyClaimsAfterTheCheck() {
        val src = File("src/main/java/com/blaze/assistant/phone/Messaging.kt").readText()
        val returns = Regex("""return "((?:[^"\\]|\\.)*)"""").findAll(src).map { it.range.first to it.groupValues[1] }.toList()
        val verdictAt = src.indexOf("SendCheck.Verdict.SENT")
        assertTrue(verdictAt > 0)
        for ((at, text) in returns) {
            if (text.startsWith("Sent to")) {
                val viaNotification = src.substring(maxOf(0, at - 120), at).contains("sendReply")
                assertTrue("Unverified 'Sent to' at $at", viaNotification || at > verdictAt)
            } else if (!text.startsWith("\$")) {
                assertFalse("A failure result reads like success: $text", ClaimGuard.succeeded(text))
            }
        }
        assertEquals(1, returns.count { it.second.startsWith("Sent to") && it.first > verdictAt })
    }
}
