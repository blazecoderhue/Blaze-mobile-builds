package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.3: "@halo yes, clean them up" ran 9 minutes: forget "#12" matched nothing, again and again. */
class LoopGuardTest {
    @Test fun theSameNoOpCallStopsAfterTwo() {
        val g = LoopGuard()
        val a = JSONObject().put("fact", "#12")
        repeat(2) { assertNull(g.check("forget", a)); g.record("forget", a, "Nothing matched \"#12\".") }
        assertNotNull(g.check("forget", a))
    }

    @Test fun aToolThatChangesNothingIsBlocked() {
        val g = LoopGuard()
        for (id in listOf("#2", "#12", "#26")) { assertNull(g.check("forget", JSONObject().put("fact", id))); g.record("forget", JSONObject().put("fact", id), "Nothing matched \"$id\".") }
        val n = g.check("forget", JSONObject().put("fact", "#99"))
        assertNotNull(n); assertTrue(n!!.contains("changed nothing"))
    }

    @Test fun progressResetsAndNewAnswersAreFine() {
        val g = LoopGuard()
        val a = JSONObject().put("query", "all")
        g.record("memory_search", a, "79 lines"); g.record("memory_search", a, "70 lines"); g.record("memory_search", a, "65 lines")
        assertNull(g.check("memory_search", a))
        g.record("forget", JSONObject().put("fact", "x"), "Nothing matched"); g.record("forget", JSONObject().put("fact", "y"), "Forgot 1 item(s).")
        assertNull(g.check("forget", JSONObject().put("fact", "z")))
    }

    @Test fun screenToolsAreExempt() {
        val g = LoopGuard(); val a = JSONObject()
        repeat(5) { g.record("read_screen", a, "same screen") }
        assertNull(g.check("read_screen", a))
    }

    @Test fun memoryDeleteIds() {
        assertEquals(listOf(2L, 12L, 26L), MemoryDelete.idsOf(JSONArray().put(2).put("#12").put("26")))
        assertEquals(listOf(17L, 24L, 72L), MemoryDelete.idsOf("#17, #24 #72 #17"))
        assertEquals(emptyList<Long>(), MemoryDelete.idsOf(null))
        assertEquals(MemoryDelete.MAX, MemoryDelete.idsOf((1..200).joinToString(",")).size)
        assertTrue(MemoryDelete.isIdQuery("#12")); assertTrue(MemoryDelete.isIdQuery("12, #13"))
        assertTrue(!MemoryDelete.isIdQuery("my sister Mary"))
        val (t, d) = MemoryDelete.card((1..10).map { "#$it [fact] You: line $it" })
        assertEquals("Delete 10 saved memories?", t); assertTrue(d.contains("and 2 more"))
        assertEquals("Delete 1 saved memory?", MemoryDelete.card(listOf("#1 x")).first)
    }

    @Test fun memoryDeleteIsAToolAndOneShot() {
        assertTrue("memory_delete" in Mind.toolNames)
        assertTrue(Mind.tools().any { it.getJSONObject("function").getString("name") == "memory_delete" })
        assertTrue(OneShot.risky("memory_delete"))
        assertTrue(PrivateChat.blocked("memory_delete"))
        assertTrue(LoopGuard.noEffect("Nothing deleted: none of #9 is saved"))
    }
}
