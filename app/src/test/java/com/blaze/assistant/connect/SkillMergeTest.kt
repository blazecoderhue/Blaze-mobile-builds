package com.blaze.assistant.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMergeTest {
    private fun r(t: Long, body: String?) = SkillMerge.Rec(t, if (body == null) emptyMap() else mapOf("SKILL.md" to body))

    @Test fun newOnEitherSide() {
        val res = SkillMerge.merge(mapOf("a" to r(5, "A")), mapOf("b" to r(7, "B")))
        assertTrue(res.push)
        assertEquals(setOf("a", "b"), res.merged.keys)
        assertEquals(setOf("b"), res.apply.keys)
    }

    @Test fun newerWins() {
        val local = mapOf("x" to r(10, "mine"), "y" to r(3, "old"))
        val remote = mapOf("x" to r(8, "theirs"), "y" to r(9, "new"))
        val res = SkillMerge.merge(local, remote)
        assertEquals("mine", res.merged["x"]!!.files["SKILL.md"])
        assertEquals("new", res.apply["y"]!!.files["SKILL.md"])
        assertTrue(res.push)
    }

    @Test fun deletionsTravel() {
        val res = SkillMerge.merge(mapOf("x" to r(5, "A")), mapOf("x" to r(9, null)))
        assertTrue(res.apply["x"]!!.deleted)
        val res2 = SkillMerge.merge(mapOf("x" to r(12, null)), mapOf("x" to r(9, "A")))
        assertTrue(res2.merged["x"]!!.deleted && res2.push)
        // A remote deletion of something this phone never had changes nothing here.
        assertTrue(SkillMerge.merge(emptyMap(), mapOf("z" to r(1, null))).apply.isEmpty())
    }

    @Test fun sameContentNoTraffic() {
        val res = SkillMerge.merge(mapOf("x" to r(5, "A")), mapOf("x" to r(5, "A")))
        assertFalse(res.push); assertTrue(res.apply.isEmpty())
    }

    @Test fun unsafeNamesAndPaths() {
        assertFalse(SkillMerge.safeName("../etc")); assertFalse(SkillMerge.safeName(".sync")); assertTrue(SkillMerge.safeName("my-skill_2"))
        assertFalse(SkillMerge.safePath("../x")); assertFalse(SkillMerge.safePath("/abs")); assertFalse(SkillMerge.safePath("a//b"))
        assertTrue(SkillMerge.safePath("assets/template.html"))
    }
}
