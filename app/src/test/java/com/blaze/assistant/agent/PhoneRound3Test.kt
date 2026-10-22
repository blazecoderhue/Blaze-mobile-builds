package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.45.13 round 3 (rpT settings-value 0/5): tap{text:"Dark mode"} + tap{want:"on"} in one step run as one tap with want. */
class PhoneRound3Test {
    private fun call(id: String, name: String, args: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", args))
    private fun reply(vararg c: JSONObject) = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().apply { c.forEach { put(it) } })
    private fun args(r: JSONObject, i: Int) = JSONObject(r.getJSONArray("tool_calls").getJSONObject(i).getJSONObject("function").getString("arguments"))

    @Test fun splitTapFoldsIntoOne() {
        val r = reply(call("a", "tap", """{"text":"Dark mode"}"""), call("b", "tap", """{"want":"on"}"""))
        assertEquals(1, ToolTruth.mergeTaps(r))
        assertEquals(1, r.getJSONArray("tool_calls").length())
        assertEquals("a", r.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("Dark mode", args(r, 0).getString("text")); assertEquals("on", args(r, 0).getString("want"))
    }

    @Test fun wantFirstFoldsIntoLaterTap() {
        val r = reply(call("a", "tap", """{"want":"off"}"""), call("b", "tap", """{"element":2}"""))
        assertEquals(1, ToolTruth.mergeTaps(r))
        assertEquals(2, args(r, 0).getInt("element")); assertEquals("off", args(r, 0).getString("want"))
    }

    @Test fun completeTapsAndOtherToolsUntouched() {
        val r = reply(call("a", "tap", """{"text":"Dark mode","want":"on"}"""), call("b", "read_screen", "{}"))
        assertEquals(0, ToolTruth.mergeTaps(r)); assertEquals(2, r.getJSONArray("tool_calls").length())
        val lone = reply(call("a", "tap", """{"want":"on"}"""), call("b", "scroll", "{}"))
        assertEquals(0, ToolTruth.mergeTaps(lone)); assertEquals(2, lone.getJSONArray("tool_calls").length())
    }

    @Test fun eachWantFindsItsOwnTap() {
        val r = reply(call("a", "tap", """{"text":"Wi-Fi"}"""), call("b", "tap", """{"want":"on"}"""),
            call("c", "tap", """{"text":"Bluetooth"}"""), call("d", "tap", """{"want":"off"}"""))
        assertEquals(2, ToolTruth.mergeTaps(r))
        assertEquals("on", args(r, 0).getString("want")); assertEquals("off", args(r, 1).getString("want"))
    }
}
