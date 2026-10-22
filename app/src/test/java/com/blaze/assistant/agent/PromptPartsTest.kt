package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.1 speed: system prompt parts, live-tail snapshot, tool order, screen read reuse, cache usage. */
class PromptPartsTest {
    private fun sample(live: String = "12:00") = PromptParts.build {
        s("persona", "P ")
        slow("connectors", "[conn]")
        s("talk", "T ")
        turn("core", "[core]")
        s("rich", "R ")
        live("time", "\n\nTIME $live ")
        live("memory", "[mem]")
        s("learn", "L ")
        live("aware", "[aw]")
        s("know", "K ")
        turn("skills", "[sk]")
    }

    @Test fun legacyIsTheOldStringAndOrderOnlyMovesParts() {
        val p = sample()
        assertEquals("P [conn]T [core]R \n\nTIME 12:00 [mem]L [aw]K [sk]", PromptParts.legacy(p))
        val a = PromptParts.assemble(p)
        assertEquals("P T R L K [conn][core][sk]\n\nTIME 12:00 [mem][aw]", a)
        // Same parts, same text, nothing added or dropped.
        assertEquals(PromptParts.legacy(p).length, a.length)
        assertEquals(p.map { it.text }.sorted(), PromptParts.ordered(p).map { it.text }.sorted())
        assertEquals(PromptParts.legacy(p), PromptParts.assemble(p, stableOrder = false))
        // Inside a tier the written order is kept.
        assertEquals(listOf("persona", "talk", "rich", "learn", "know"), PromptParts.ordered(p).filter { it.tier == PromptParts.Tier.STATIC }.map { it.name })
    }

    @Test fun stableHeadSurvivesAClockAndScreenChange() {
        val a = PromptParts.assemble(sample("12:00")); val b = PromptParts.assemble(sample("12:07"))
        val la = PromptParts.legacy(sample("12:00")); val lb = PromptParts.legacy(sample("12:07"))
        val head = PromptParts.head(sample())
        assertTrue(PromptParts.commonPrefix(a, b) >= head.length)
        assertTrue(PromptParts.commonPrefix(a, b) > PromptParts.commonPrefix(la, lb))
    }

    @Test fun emptyPartsAreSkippedAndPlaceholdersFilled() {
        val p = PromptParts.build { s("a", ""); s("b", null); live("t", PromptParts.SLOT); live("u", PromptParts.SLOT); s("c", "C") }
        assertEquals(3, p.size)
        val filled = PromptParts.withLive(p, listOf(PromptParts.Part("t", PromptParts.Tier.LIVE, "T"), PromptParts.Part("u", PromptParts.Tier.LIVE, "")))
        assertEquals(listOf("t", "c"), filled.map { it.name })
        assertEquals("CT", PromptParts.assemble(filled))
        assertFalse(PromptParts.assemble(filled).contains(PromptParts.SLOT))
    }

    @Test fun liveSnapKeepsATurnsTailUntilKeyOrTimeChanges() {
        var now = 0L; var builds = 0
        val snap = PromptParts.LiveSnap<String>(300_000) { now }
        repeat(5) { assertEquals("v1", snap.get("chat", listOf(1L, true)) { builds++; "v1" }) }   // a 5-step turn
        assertEquals(1, builds); assertEquals(4, snap.hits)
        assertEquals("v2", snap.get("chat", listOf(1L, false)) { builds++; "v2" })               // awareness switched to full / summary
        assertEquals("other", snap.get("other", listOf(1L, false)) { builds++; "other" })        // another chat has its own slot
        assertEquals("v2", snap.get("chat", listOf(1L, false)) { builds++; "x" })
        now += 300_001
        assertEquals("v3", snap.get("chat", listOf(1L, false)) { builds++; "v3" })              // too old
        assertEquals("v4", snap.get("chat", listOf(2L, false)) { builds++; "v4" })              // next turn
        assertEquals(5, builds)
        snap.drop("chat"); assertEquals("v5", snap.get("chat", listOf(2L, false)) { "v5" })
    }

    @Test fun sizesRankAndLine() {
        val p = sample()
        assertEquals(13, PromptParts.sizes(p)["time"])
        assertTrue(PromptParts.rank(p, 2).startsWith("time"))
        val l = PromptParts.line(p)
        assertTrue(l, l.startsWith("sys ") && l.contains("static") && l.contains("live"))
        val tiers = PromptParts.tierSizes(p)
        assertEquals(p.sumOf { it.text.length }, tiers.values.sum())
    }

    @Test fun cacheUsageShapes() {
        assertEquals(0 to 0, PromptParts.cacheOf(null))
        assertEquals("", PromptParts.cacheLine(JSONObject()))
        val oa = JSONObject().put("prompt_tokens", 15100).put("prompt_tokens_details", JSONObject().put("cached_tokens", 12300))
        assertEquals(15100 to 12300, PromptParts.cacheOf(oa))
        assertEquals("cache 12.3k/15.1k", PromptParts.cacheLine(oa))
        assertEquals(900 to 800, PromptParts.cacheOf(JSONObject().put("prompt_tokens", 900).put("prompt_cache_hit_tokens", 800)))   // DeepSeek
        assertEquals(900 to 700, PromptParts.cacheOf(JSONObject().put("input_tokens", 900).put("cache_read_input_tokens", 700)))   // Anthropic
        assertEquals(500 to 0, PromptParts.cacheOf(JSONObject().put("prompt_tokens", 500)))
    }

    @Test fun ledgerRanksPromptParts() {
        val t = TokenLedger.sectionTokens(sample())
        assertEquals(setOf("persona", "connectors", "talk", "core", "rich", "live: time", "live: memory", "learn", "live: aware", "know", "skills"), t.keys)
        val l = TokenLedger.fold(JSONObject(), "2026-10-20", t)
        val r = TokenLedger.sectionReport(l)
        assertTrue(r, r.startsWith("\nSystem prompt by part") && r.contains("live: time"))
        assertEquals("", TokenLedger.sectionReport(JSONObject()))
    }

    private fun tool(n: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n))

    @Test fun toolsKeepTheirPlaceAcrossSteps() {
        val step1 = JSONArray().apply { listOf("a", "c", "e").forEach { put(tool(it)) } }
        // Step 2: ToolPick now also sends "b" and "d" (canonical order a b c d e) and no longer "e".
        val step2 = JSONArray().apply { listOf("a", "b", "c", "d").forEach { put(tool(it)) } }
        val kept = ToolPick.keepOrder(ToolPick.names(step1), step2)
        assertEquals(listOf("a", "c", "b", "d"), ToolPick.names(kept))
        assertEquals(ToolPick.names(step2).sorted(), ToolPick.names(kept).sorted())   // nothing added or dropped
        assertEquals(ToolPick.names(step2), ToolPick.names(ToolPick.keepOrder(emptyList(), step2)))
        // The serialized prefix of step 1's kept tools is unchanged.
        val s1 = JSONArray().apply { put(tool("a")); put(tool("c")) }.toString().dropLast(1)
        assertTrue(kept.toString().startsWith(s1))
    }

    @Test fun screenReadReuseOnlyWhenNothingCanHaveChanged() {
        assertTrue(ScreenReadCache.reusable(sameWindow = true, eventSince = false, actedSince = false, ageMs = 300))
        assertFalse(ScreenReadCache.reusable(false, false, false, 300))
        assertFalse(ScreenReadCache.reusable(true, true, false, 300))
        assertFalse(ScreenReadCache.reusable(true, false, true, 300))
        assertFalse(ScreenReadCache.reusable(true, false, false, ScreenReadCache.MAX_AGE_MS + 1))
        assertFalse(ScreenReadCache.reusable(true, false, false, -1))
        assertTrue(ScreenReadCache.reusable(true, false, false, ScreenReadCache.MAX_AGE_MS))
    }

    @Test fun screenLine() {
        assertEquals("", SpeedLog.screenLine(0, 0, 0))
        assertEquals("\nScreen reads: 4 tree walks (avg 30 ms), 6 reused unchanged", SpeedLog.screenLine(4, 6, 120))
    }
}
