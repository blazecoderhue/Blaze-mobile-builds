package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCallsTest {
    private fun tool(name: String, vararg params: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name)
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().apply { params.forEach { put(it, JSONObject().put("type", "string")) } })
            .put("required", JSONArray(params.take(1)))))
    private val all = JSONArray().put(tool("browser_show")).put(tool("open_app", "app_name")).put(tool("desktop", "action", "app"))
        .put(tool("set_alarm", "hour", "minute"))

    private fun names(p: Pair<JSONArray, String>?) = p!!.first.let { a -> (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") } }
    private fun args(p: Pair<JSONArray, String>?, i: Int = 0) = JSONObject(p!!.first.getJSONObject(i).getJSONObject("function").getString("arguments"))

    @Test fun bareCallBecomesARealCall() {
        val r = TextCalls.toCalls("browser_show()", all)
        assertEquals(listOf("browser_show"), names(r))
        assertEquals("", r!!.second)
    }

    @Test fun keywordArgsAndKnownNameWithoutUnderscore() {
        val r = TextCalls.toCalls("Starting it now.\ndesktop(action=\"start\")", all)
        assertEquals(listOf("desktop"), names(r))
        assertEquals("start", args(r).getString("action"))
        assertEquals("Starting it now.", r!!.second)
    }

    @Test fun positionalArgGoesToTheFirstRequiredParam() {
        val r = TextCalls.toCalls("`open_app(\"WhatsApp\")`", all)
        assertEquals("WhatsApp", args(r).getString("app_name"))
    }

    @Test fun numbersAndBraceForm() {
        val r = TextCalls.toCalls("set_alarm(hour=7, minute=30)", all)
        assertEquals(7L, args(r).getLong("hour")); assertEquals(30L, args(r).getLong("minute"))
        val b = TextCalls.toCalls("set_alarm {\"hour\": 6, \"minute\": 0}", all)
        assertEquals(6, args(b).getInt("hour"))
    }

    @Test fun bareJsonAndFencedJson() {
        val r = TextCalls.toCalls("{\"name\": \"open_app\", \"arguments\": {\"app_name\": \"Spotify\"}}", all)
        assertEquals("Spotify", args(r).getString("app_name"))
        val f = TextCalls.toCalls("Sure.\n```json\n{\"name\":\"browser_show\",\"arguments\":{}}\n```", all)
        assertEquals(listOf("browser_show"), names(f)); assertEquals("Sure.", f!!.second)
    }

    @Test fun unknownToolIsStrippedNotShown() {
        val t = "linux_desktop_launch(app=\"dillo\")"
        assertNull(TextCalls.toCalls(t, all))
        assertTrue(TextCalls.has(t))
        assertTrue(TextCalls.onlyCalls(t))
        assertEquals("", TextCalls.strip(t))
        assertEquals("Here you go.", TextCalls.strip("Here you go.\nlinux_desktop_launch()"))
    }

    @Test fun codeAndProseAreLeftAlone() {
        val code = "Here's the function:\n```python\ndef add_one(x):\n    return x + 1\n\nadd_one(2)\n```"
        assertFalse(TextCalls.has(code))
        assertEquals(code, TextCalls.strip(code))
        val prose = "Call print(x) to see it, or use f(x) = 2x."
        assertFalse(TextCalls.has(prose))
        assertEquals(prose, TextCalls.strip(prose))
        assertNull(TextCalls.toCalls("The open_app(\"x\") helper is what I'd use.", all))
        assertFalse(TextCalls.has("{\"weather\": \"sunny\"}"))
    }

    @Test fun streamingHidesAnUnfinishedCall() {
        assertEquals("Okay.", TextCalls.strip("Okay.\nbrowser_show(url=\"htt", final = false))
        assertEquals("Okay, sure", TextCalls.strip("Okay, sure", final = false))
    }

    @Test fun parserHandlesNamespacedNames() {
        val r = TextCalls.toCalls("functions.browser_show()", all)
        assertNotNull(r); assertEquals(listOf("browser_show"), names(r))
    }
}
