package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPickTest {
    private fun tool(name: String, desc: String = "does $name") = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object")))
    private fun arr(names: Collection<String>) = JSONArray().apply { names.forEach { put(tool(it)) } }
    private fun names(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") }

    private val coreish = ToolPick.ESSENTIAL + ToolPick.CUES.flatMap { it.names }
    private val selected = arr(coreish + listOf("set_alarm", "set_timer", "set_volume"))

    @Test fun plainActionDropsTheOptionalCore() {
        val out = names(ToolPick.pick(selected, "open whatsapp", emptySet(), setOf("device")))
        assertTrue(out.containsAll(listOf("open_app", "find_tool", "load_tools", "set_alarm")))
        assertFalse(out.contains("start_agent")); assertFalse(out.contains("search_chat")); assertFalse(out.contains("blaze_docs_search"))
        assertTrue(out.size < selected.length())
    }

    @Test fun cuesComeBackOnWordsHistoryOrLoad() {
        assertTrue(names(ToolPick.pick(selected, "do this in the background while I wait", emptySet(), emptySet())).contains("start_agent"))
        assertTrue(names(ToolPick.pick(selected, "what did you say earlier about rent", emptySet(), emptySet())).contains("search_chat"))
        assertTrue(names(ToolPick.pick(selected, "cancel my 7am alarm", emptySet(), emptySet())).contains("processes"))
        assertTrue(names(ToolPick.pick(selected, "ok", setOf("check_agent"), emptySet())).contains("list_agents"))
        assertTrue(names(ToolPick.pick(selected, "ok", emptySet(), setOf("history"))).contains("read_turn"))
        assertEquals(setOf("self"), ToolPick.cuesIn("Matching tools (now loaded): blaze_docs_search: search docs"))
    }

    @Test fun routinesKeepEverything() {
        assertEquals(selected.length(), ToolPick.pick(selected, "x", emptySet(), emptySet(), keepAll = true).length())
    }

    @Test fun capRanksByTheRequest() {
        val many = arr(ToolPick.ESSENTIAL + (1..60).map { "extra_tool_$it" } + listOf("set_alarm"))
        val out = names(ToolPick.pick(many, "set an alarm", setOf("extra_tool_59"), emptySet(), max = 25))
        assertEquals(25, out.size)
        assertTrue(out.containsAll(ToolPick.ESSENTIAL)); assertTrue(out.contains("set_alarm")); assertTrue(out.contains("extra_tool_59"))
        // Order is kept as given.
        assertTrue(out.indexOf("set_alarm") > out.indexOf("extra_tool_59"))
    }

    @Test fun loadToolOffersTheCues() {
        val f = ToolPick.loadTool().getJSONObject("function")
        val e = f.getJSONObject("parameters").getJSONObject("properties").getJSONObject("groups").getJSONObject("items").getJSONArray("enum")
        val ids = (0 until e.length()).map { e.getString(it) }
        assertTrue(ids.containsAll(ToolPick.CUES.map { it.id })); assertTrue(ids.contains("device"))
        assertTrue(f.getString("description").contains("agents ("))
    }

    @Test fun sizeLine() {
        val s = ToolPick.sizeLine(arr(listOf("a_b")), arr(listOf("a_b", "c_d")), arr(listOf("a_b", "c_d")))
        assertTrue(s, s.startsWith("tools 1/2 · schemas "))
    }
}
