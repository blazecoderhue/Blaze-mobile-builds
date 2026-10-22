package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.5: unrelated skills are refused; tasks done often are counted and flagged for a skill. */
class SkillGuardTest {
    private val builder = SkillMatch.Cand("android-app-builder", "Build, sign and ship an Android app from a Kotlin project",
        listOf("gradle", "apk", "kotlin"), "1. …", "bundled")
    private val flash = SkillMatch.Cand("study-flashcards", "Make flashcards from notes and quiz the user", listOf("flashcards", "quiz"), "1. …", "bundled")
    private val airtime = SkillMatch.Cand("airtime-topup", "Top up Airtel airtime with the USSD menu", listOf("airtime", "airtel"), "1. …", "learned")
    private val all = listOf(builder, flash, airtime)

    @Test fun unrelatedSkillIsRefused() {
        val r = SkillGuard.gate("android-app-builder", "stop GitHub opening links in the GitHub app", "", all)
        assertNotNull(r); assertTrue(r!!.startsWith("Not loaded"))
        // A reason doesn't unlock a skill that shares nothing with the request.
        assertNotNull(SkillGuard.gate("study-flashcards", "what's the weather in Ndola", "it might help somehow here", all))
    }

    @Test fun relatedOrNamedSkillLoads() {
        assertNull(SkillGuard.gate("study-flashcards", "make me flashcards on photosynthesis and quiz me", "", all))
        assertNull(SkillGuard.gate("airtime-topup", "top up my airtel airtime with K20", "", all))
        assertNull(SkillGuard.gate("android-app-builder", "use the android-app-builder skill", "", all))
        assertNull(SkillGuard.gate("no-such-skill", "anything", "", all))   // the tool reports unknown names itself
    }

    @Test fun oneSharedWordNeedsAReason() {
        // "kotlin" alone: refused once, allowed with a real reason.
        assertEquals(SkillGuard.Verdict.SOFT_REFUSE, SkillGuard.relevant("explain kotlin coroutines to me", builder))
        assertNotNull(SkillGuard.gate("android-app-builder", "explain kotlin coroutines to me", "", all))
        assertNull(SkillGuard.gate("android-app-builder", "explain kotlin coroutines to me", "its kotlin section explains coroutines", all))
    }

    @Test fun strongHitDropsStrayWords() {
        assertFalse(SkillGuard.strongHit("open the app", builder))
        assertTrue(SkillGuard.strongHit("build my kotlin apk with gradle", builder))
    }

    @Test fun frequentTasksAreCountedAndFlagged() {
        var tasks = emptyList<SkillGuard.Freq.Task>()
        val sig = Learn.signature("check my airtel balance", listOf("open_app", "tap", "read_screen"))
        repeat(2) { tasks = SkillGuard.Freq.record(tasks, sig, "check my airtel balance", 1000L + it).first }
        val (after, t) = SkillGuard.Freq.record(tasks, Learn.signature("check airtel balance please", listOf("open_app", "tap", "read_screen")), "check airtel balance please", 2000)
        assertEquals(1, after.size); assertEquals(3, t.count)
        assertTrue(SkillGuard.Freq.needsSkill(t))
        assertTrue(SkillGuard.Freq.promptLine(after).contains("×3"))
        val skilled = SkillGuard.Freq.markSkilled(after, t.sig)
        assertFalse(SkillGuard.Freq.needsSkill(skilled.first()))
        assertEquals("", SkillGuard.Freq.promptLine(skilled))
        assertEquals(skilled, SkillGuard.Freq.fromJson(SkillGuard.Freq.toJson(skilled)))
        // An unrelated task is its own row.
        val (two, _) = SkillGuard.Freq.record(skilled, Learn.signature("send the weekly report email", listOf("gmail_send")), "send the weekly report email", 3000)
        assertEquals(2, two.size)
    }
}
