package com.blaze.assistant.agent

import com.blaze.assistant.agent.ToolVerify.Status
import com.blaze.assistant.agent.ToolVerify.Verdict
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.2 faster verification that stays real: one test per rule (a)–(e). */
class VerifyPolicyTest {
    // (a) plain answer turns
    @Test fun plainTurnsHaveNoVerificationStep() {
        assertTrue(VerifyPolicy.plainTurn(emptyList()))
        assertTrue(VerifyPolicy.plainTurn(listOf("web_search", "fetch_page", "memory_search", "find_tool")))
        assertFalse(VerifyPolicy.plainTurn(listOf("web_search", "set_alarm")))
    }

    @Test fun plainTurnStillGetsTheLocalClaimRule() {
        // No action ran, but the reply claims one: the cheap local rule catches it (no model call needed).
        val out = ClaimCheck.review("Alarm set for 7:00.", emptyList(), "set an alarm for 7", setOf("set_alarm"))
        assertFalse(out is ClaimCheck.Outcome.Ok)
    }

    // (b) deterministic on-device postconditions first
    @Test fun probedActionsAreCheckedOnThePhone() {
        assertEquals(VerifyPolicy.Mode.QUICK_THEN_BACKGROUND, VerifyPolicy.mode("open_app", strict = false))
        assertEquals(VerifyPolicy.Mode.QUICK_THEN_BACKGROUND, VerifyPolicy.mode("device_setting", strict = false))
        assertEquals(VerifyPolicy.Mode.WORDS, VerifyPolicy.mode("memory_save", strict = false))
        assertEquals(VerifyPolicy.Mode.WORDS, VerifyPolicy.mode("web_search", strict = false))
    }

    @Test fun conclusiveLocalResultMeansNoModelCheck() {
        val ok = Verdict(Status.VERIFIED, "com.whatsapp is in front")
        val bad = Verdict(Status.FAILED, "nothing answers")
        val unsure = Verdict(Status.UNVERIFIED, "couldn't see which app is in front")
        assertTrue(VerifyPolicy.conclusive(ok)); assertTrue(VerifyPolicy.conclusive(bad)); assertFalse(VerifyPolicy.conclusive(unsure))
        assertFalse(VerifyPolicy.modelCheckAllowed(ok, strict = false))
        assertFalse(VerifyPolicy.modelCheckAllowed(bad, strict = false))
        assertTrue(VerifyPolicy.modelCheckAllowed(unsure, strict = false))
        val tag = VerifyPolicy.tag("Opened WhatsApp (com.whatsapp).", ok, fromProbe = true, strict = false)
        assertTrue(tag.contains("[verified=true") && tag.contains("no screenshot or extra check needed"))
        assertTrue(VerifyPolicy.tag("x", unsure, fromProbe = true, strict = false).contains("One quick check"))
    }

    @Test fun realProbeIsConclusiveWithoutAModel() = runBlocking {
        val p = object : ToolVerify.Probes { override suspend fun foreground() = "com.whatsapp" }
        val v = ToolVerify.check("open_app", JSONObject().put("app", "WhatsApp"), "Opened WhatsApp (com.whatsapp).", p, waitMs = 0) { _, ok -> ok() }
        assertEquals(Status.VERIFIED, v.status)
        assertTrue(VerifyPolicy.conclusive(v))
    }

    // (c) stream now, finish the receipt in parallel, never "done" before the check passes
    @Test fun pendingIsNeverDone() {
        val tag = VerifyPolicy.pendingTag("still checking")
        assertTrue(tag.startsWith("[verified=pending"))
        assertTrue(tag.contains("Don't say it's done"))
        assertTrue(ToolVerify.tagged("Opened.\n$tag"))
        val e = Receipt.Entry("open_app", "app=WhatsApp", Status.UNVERIFIED, "Opened", "still checking", label = "Open WhatsApp", pending = true)
        assertEquals("\u26a0 Checking: Open WhatsApp", Receipt.chip(e))
        assertFalse(Receipt.line(listOf(e)).contains("\u2713"))
        // The final claim rule reads a pending action as not done.
        val out = ClaimCheck.review("Alarm set for 7:00.", listOf(ClaimCheck.Done("set_alarm", Status.UNVERIFIED, "still checking")), "set an alarm for 7", setOf("set_alarm"))
        assertFalse(out is ClaimCheck.Outcome.Ok)
    }

    @Test fun receiptFlipsAfterTheReplyAndSaysWhy() {
        val e = Receipt.Entry("open_app", "", Status.UNVERIFIED, "Opened", "still checking", label = "Open WhatsApp", pending = true)
        val failed = VerifyPolicy.flip(e, Verdict(Status.UNVERIFIED, "com.whatsapp isn't in front"), afterReply = true)
        assertFalse(failed.pending)
        assertTrue(failed.evidence.contains("isn't in front") && failed.evidence.contains("after the reply"))
        assertTrue(Receipt.describe(listOf(failed)).contains("Checked: com.whatsapp isn't in front"))
        val ok = VerifyPolicy.flip(e, Verdict(Status.VERIFIED, "com.whatsapp is in front"), afterReply = false)
        assertEquals("\u2713 Open WhatsApp", Receipt.chip(ok))
        // pending survives a save / load
        val back = Receipt.fromJson(Receipt.toJson(listOf(e)))
        assertTrue(back.single().pending)
        assertFalse(Receipt.fromJson(Receipt.toJson(listOf(ok))).single().pending)
    }

    // (d) a model check that times out is "unverified", never a silent pass
    @Test fun timeoutsAreHonest() {
        assertEquals(Status.UNVERIFIED, VerifyPolicy.timedOut().status)
        assertTrue(VerifyPolicy.timedOut().why.contains("didn't finish"))
        assertFalse(com.blaze.assistant.agent.Playbooks.Review(true, emptyList(), checked = false).checked)
        assertTrue(VerifyPolicy.NOT_REVIEWED.startsWith("(Not self-checked"))
        assertTrue(VerifyPolicy.MODEL_CHECK_MS in 3_000..20_000)
        val src = java.io.File("src/main/java/com/blaze/assistant/agent/Agent.kt").readText()
        assertTrue(src.contains("CallState().also { it.lite = true }"))   // the quick route
        assertTrue(src.contains("Playbooks.Review(true, emptyList(), checked = false)"))
    }

    // risky one-shot actions still need a conclusive check
    @Test fun riskyOneShotActionsAlwaysWaitForTheCheck() {
        listOf("send_sms", "message_contact", "gmail_send", "call_contact", "uninstall_app").forEach { n ->
            if (VerifyPolicy.risky(n)) assertEquals(n, VerifyPolicy.Mode.BLOCKING, VerifyPolicy.mode(n, strict = false))
        }
        assertTrue(VerifyPolicy.risky("send_sms"))
        assertEquals(VerifyPolicy.Mode.BLOCKING, VerifyPolicy.mode("send_sms", strict = false))
        // A send with no "Sent" confirmation is not done, whatever the mode.
        assertEquals(Status.UNVERIFIED, ToolVerify.classify("send_sms", JSONObject(), "Opened the chat with Mum.").status)
    }

    // (e) strict fallback + stats
    @Test fun strictModeIsTheOldPath() {
        assertEquals(VerifyPolicy.Mode.BLOCKING, VerifyPolicy.mode("open_app", strict = true))
        assertTrue(VerifyPolicy.modelCheckAllowed(Verdict(Status.FAILED), strict = true))
        val ok = Verdict(Status.VERIFIED)
        assertEquals(ToolVerify.tag("x", ok), VerifyPolicy.tag("x", ok, fromProbe = true, strict = true))
        assertEquals("verify_strict", VerifyPolicy.PREF_STRICT)
    }

    @Test fun speedStatsLine() {
        val s = VerifyPolicy.Stats()
        assertEquals("", s.line())
        s.local(120); s.local(30); s.model(2400); s.pending = 1; s.savedMs = 1500
        val l = s.line()
        assertTrue(l, l.startsWith("verify 2550 ms"))
        assertTrue(l.contains("local 2/150 ms") && l.contains("model 1/2400 ms") && l.contains("background 1") && l.contains("saved ~1500 ms"))
    }

    @Test fun agentUsesThePolicy() {
        val src = java.io.File("src/main/java/com/blaze/assistant/agent/Agent.kt").readText()
        assertTrue(src.contains("VerifyPolicy.mode(name, strictV)"))
        assertTrue(src.contains("flipReceipt(book, li, di, fin)"))
        assertTrue(src.contains("b.stats.line()"))
    }
}
