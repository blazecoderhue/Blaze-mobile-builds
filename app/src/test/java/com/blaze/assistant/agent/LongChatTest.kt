package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LongChatTest {
    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    /** user → assistant tool call → tool result → assistant answer, like a real turn. */
    private fun turn(h: JSONArray, n: Int, fact: String = "") {
        h.put(msg("user", "Question $n about the store, please check the listing. $fact"))
        h.put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject().put("id", "c$n")
            .put("type", "function").put("function", JSONObject().put("name", "web_search").put("arguments", "{\"query\":\"lamp $n\"}")))))
        h.put(JSONObject().put("role", "tool").put("tool_call_id", "c$n").put("name", "web_search").put("content", "result ".repeat(300)))
        h.put(msg("assistant", "Answer $n: the listing looks fine, nothing to change. ".repeat(4)))
    }

    @Test fun threeHundredTurnsStayUnderBudget() {
        val h = JSONArray()
        var st = LongChat.State()
        val budget = LongChat.budget(0)
        var folds = 0
        for (n in 0 until 300) {
            turn(h, n, if (n == 5) "The supplier code is ZX-4417." else "")
            if (LongChat.needsCompaction(h, st, budget)) {
                val chunk = LongChat.transcript(h, st.upto, LongChat.tailStart(h))
                val keep = if (chunk.contains("ZX-4417") || st.summary.contains("ZX-4417")) "Facts: supplier code ZX-4417." else ""
                st = LongChat.folded(h, "Goal: run the store. $keep Open threads: none.")
                folds++
            }
            val from = LongChat.sendFrom(h, st)
            val sent = LongChat.tokens(h, from) + LongChat.summaryBlock(st).length / 4
            assertTrue("turn $n sends $sent tokens", sent <= budget + 2_000)
        }
        assertTrue(folds > 5)
        assertTrue("summary keeps the seeded fact", st.summary.contains("ZX-4417"))
        assertTrue(LongChat.summaryBlock(st).contains("search_chat"))
    }

    @Test fun tailNeverStartsOnAToolResult() {
        val h = JSONArray()
        for (n in 0 until 10) turn(h, n)
        val ts = LongChat.tailStart(h)
        assertEquals("user", h.getJSONObject(ts).getString("role"))
        val from = LongChat.sendFrom(h, LongChat.State("note", 6))   // index 6 is a tool result
        assertEquals("user", h.getJSONObject(from).getString("role"))
        assertEquals(0, LongChat.sendFrom(h, LongChat.State("note", 999)))   // history got shorter
    }

    @Test fun shortChatsAreLeftAlone() {
        val h = JSONArray(); turn(h, 1); turn(h, 2)
        assertFalse(LongChat.needsCompaction(h, LongChat.State(), LongChat.budget(0)))
        assertEquals(0, LongChat.tailStart(h))
    }

    @Test fun transcriptClipsToolNoiseAndShowsCalls() {
        val h = JSONArray(); turn(h, 7)
        val t = LongChat.transcript(h, 0, h.length())
        assertTrue(t.contains("[0 user] Question 7"))
        assertTrue(t.contains("[calls web_search"))
        assertTrue(t.contains(" … "))
        assertTrue(t.length < 2_000)
        val req = LongChat.summariseRequest("", h, 0, h.length())
        assertTrue(req.getJSONObject(1).getString("content").contains("(none yet)"))
    }

    @Test fun budgetAndMeter() {
        assertEquals(12_000, LongChat.budget(0))
        assertEquals(12_000, LongChat.budget(128_000))
        assertEquals(4_915, LongChat.budget(8192))
        assertEquals(8192, LongChat.contextWindow("llama3-8b-8192"))
        assertEquals(32 * 1024, LongChat.contextWindow("some-model-32k"))
        assertEquals(128_000, LongChat.contextWindow("meta-llama/llama-3.3-70b-instruct:free"))
        assertEquals(50_000, LongChat.contextWindow("anything", 50_000))
        assertEquals(32_768, LongChat.meterLimit(0))
        assertEquals("in 4.2k · out 600", LongChat.meter(4200, 600))
        assertTrue(LongChat.high(27_000, 32_768))
        assertFalse(LongChat.high(4_000, 32_768))
    }

    @Test fun bigOutputsGoAsideAndReadBack() {
        LongChat.artifactDirOverride = Files.createTempDirectory("art").toFile()
        assertEquals("small", LongChat.aside("small"))
        val big = (1..500).joinToString("\n") { "line $it of the build log" }
        val seen = LongChat.aside(big)
        assertTrue(seen.length < big.length)
        assertTrue(seen.contains("line 1 of"))
        assertTrue(seen.contains("line 500 of"))
        assertFalse(seen.contains("line 250 of"))
        val id = Regex("artifact:([a-f0-9]{12})").find(seen)!!.groupValues[1]
        val part = LongChat.readArtifact(id, 250, 3)
        assertTrue(part.startsWith("250: line 250 of"))
        assertTrue(part.contains("252: line 252"))
        assertTrue(LongChat.readArtifact("artifact:$id", 1, 1).startsWith("1: line 1"))
        assertTrue(LongChat.readArtifact("../../etc/passwd", 1, 1).startsWith("Error"))
        // Few but huge lines: characters instead of lines.
        val wide = "x".repeat(9_000)
        assertTrue(LongChat.aside(wide).contains("more characters"))
    }

    @Test fun ftsQueryIsSafe() {
        assertEquals("lamp* OR prices*", LongChat.ftsQuery("lamp \"prices\""))
        assertEquals("", LongChat.ftsQuery("a ' * -"))
        assertEquals("near* OR nyanja* OR 2026*", LongChat.ftsQuery("NEAR(nyanja) 2026"))
    }

    @Test fun textReadsPartsAndCalls() {
        val m = JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "look"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:x"))))
        assertEquals("look", LongChat.text(m))
        assertTrue(LongChat.tokens(JSONArray().put(m)) >= 800)
    }

    @Test fun readTurnFindsAnExactMessage() {
        val h = JSONArray(); for (n in 0 until 5) turn(h, n)
        val r = LongChat.readTurn("4", "c1") { if (it == "c1") h else null }
        assertTrue(r.startsWith("[user] Question 1"))
        assertTrue(LongChat.readTurn("c2#0", "c1") { if (it == "c2") h else null }.contains("Question 0"))
        assertTrue(LongChat.readTurn("x", "c1") { h }.startsWith("Error"))
    }

    @Test fun projectBriefRoundTripsAndOldProjectsLoad() {
        val old = "[{\"id\":\"p1\",\"name\":\"Old\",\"instructions\":\"be brief\",\"chats\":[\"a\"],\"updated\":1}]"
        val p = Projects.parse(old).single()
        assertEquals("", p.brief)
        val again = Projects.parse(Projects.toJson(listOf(p.copy(brief = "Purpose: ship it"))))
        assertEquals("Purpose: ship it", again.single().brief)
        assertEquals("be brief", again.single().instructions)
    }

    @Test fun toolsAreDeclared() {
        val names = LongChat.tools().map { it.getJSONObject("function").getString("name") }.toSet()
        assertEquals(LongChat.names, names)
        // Plan mode allows the read-only ones (Modes needs Settings at init, so read its source).
        val modes = java.io.File(listOf("src/main", "app/src/main").first { java.io.File(it).isDirectory }, "java/com/blaze/assistant/agent/Modes.kt").readText()
        listOf("search_chat", "read_turn", "read_artifact").forEach { assertTrue(it, modes.contains("\"$it\"")) }
    }
}
