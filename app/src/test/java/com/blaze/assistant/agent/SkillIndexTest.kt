package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.26.2 skills by lookup: Desktop's bm25 ranking, only the top matches in the prompt, no name list. */
class SkillIndexTest {
    private fun cand(n: String, d: String, kw: List<String> = emptyList(), on: Boolean = true) = SkillMatch.Cand(n, d, kw, "body", "user", on)
    private val cands = listOf(
        cand("airtime-topup", "Top up airtime and data bundles with USSD", listOf("airtime", "bundle")),
        cand("deep-research", "Research a question on the web with sources"),
        cand("landing-page-builder", "Build a landing page for a store or product", listOf("website")),
        cand("study-flashcards", "Make flashcards from notes and quiz the user", listOf("quiz", "revise")),
        cand("email-drafting", "Draft emails in the user's voice"),
        cand("secret-off", "Top up airtime secretly", on = false),
    ) + (1..40).map { cand("filler-skill-$it", "Unrelated helper number $it for gardening $it") }

    @Test fun nameOutranksDescription() {
        val ix = SkillMatch.indexOf(cands)
        val r = ix.search("airtime", 5)
        assertEquals("airtime-topup", r.first().first.key)
        assertFalse("disabled skills aren't indexed", r.any { it.first.key == "secret-off" })
        assertEquals("study-flashcards", ix.search("quiz me with flashcards on cells", 3).first().first.key)
        assertTrue(ix.search("hello there", 3).isEmpty())
    }

    @Test fun promptHasOnlyTheTopMatchesAndNoNameList() {
        val ix = SkillMatch.indexOf(cands)
        val b = SkillIndex.promptBlock(ix, "build a landing page website for my store")
        assertTrue(b.contains("- landing-page-builder: Build a landing page"))
        assertTrue(b.contains("use_skill / find_skill to search for more"))
        assertTrue(b.lines().count { it.startsWith("- ") } <= SkillIndex.PROMPT_MAX)
        assertFalse("no list of every skill", b.contains("filler-skill-7"))
        assertFalse(b.contains("Also:"))
        val none = SkillIndex.promptBlock(ix, "what's the weather")
        assertFalse(none.contains("- ")); assertTrue(none.contains("find_skill"))
        assertFalse("skills already attached in full aren't listed twice", SkillIndex.promptBlock(ix, "landing page", skip = setOf("landing-page-builder")).contains("- landing-page-builder"))
        assertEquals("", SkillIndex.promptBlock(SkillMatch.indexOf(emptyList()), "x"))
        assertTrue(SkillIndex.findLine(ix, "top up airtime").contains("airtime-topup"))
        assertTrue(SkillIndex.findLine(ix, "zzzz").startsWith("No skill"))
        assertTrue(SkillPacks.names.contains("find_skill"))
    }

    @Test fun genericNameWordsDontPullInAPhoneSkill() {
        val speed = cand("phone-task-speed", "Doing multi-step tasks inside phone apps as fast as possible")
        assertTrue(SkillMatch.match("what sound profile is my phone set to?", listOf(speed)).isEmpty)
    }
}
