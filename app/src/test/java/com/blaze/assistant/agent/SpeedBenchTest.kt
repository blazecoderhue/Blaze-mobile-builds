package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.41.0 speed: before / after numbers for the pure parts that run on every model call. Prints a line per bench
 * (see the test report's stdout); asserts only the direction so slow CI machines don't fail it.
 */
class SpeedBenchTest {
    /** A typical full-lane request: a ~25k-char system prompt and a tool-heavy recent history (JSON results). */
    private fun request(): JSONArray {
        val sys = buildString { repeat(310) { append("- Rule $it: use \"the tool\" when the user asks; never guess.\n  Example: {\"a\": 1}\n") } }
        val a = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        repeat(10) { i ->
            a.put(JSONObject().put("role", "user").put("content", "put on an action movie $i"))
            a.put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("id", "c$i").put("type", "function")
                .put("function", JSONObject().put("name", "video_ops").put("arguments", "{\"action\":\"search\",\"q\":\"action\"}")))))
            val result = JSONArray().apply { repeat(25) { put(JSONObject().put("title", "Film \"$it\"").put("path", "/storage/emulated/0/Movies/f$it.mp4").put("dur", 6000 + it)) } }.toString()
            a.put(JSONObject().put("role", "tool").put("tool_call_id", "c$i").put("content", result))
        }
        return a
    }

    /** v0.39.0's rule: the whole request serialised to JSON, long before agent. */
    private fun oldTask(messages: JSONArray, tools: JSONArray?): String = when {
        messages.toString().length > Cloud.LONG_CHARS -> "long"
        tools != null && tools.length() > 0 -> "agent"
        else -> "chat"
    }

    private fun timeUs(n: Int, f: () -> Unit): Double {
        repeat(20) { f() }   // warm up the JIT
        val t0 = System.nanoTime()
        repeat(n) { f() }
        return (System.nanoTime() - t0) / 1000.0 / n
    }

    @Test fun taskHintNoLongerSerialisesTheRequest() {
        val req = request()
        val tools = JSONArray().put(JSONObject().put("type", "function"))
        val json = req.toString().length
        val text = Cloud.textChars(req)
        val before = timeUs(200) { oldTask(req, tools) }
        val after = timeUs(200) { Cloud.task(req, tools) }
        println("BENCH task hint: request JSON $json chars, text $text chars; before %.0f µs/call (\"%s\"), after %.0f µs/call (\"%s\")"
            .format(before, oldTask(req, tools), after, Cloud.task(req, tools)))
        assertTrue("after $after µs vs before $before µs", after < before)
        // The JSON escapes alone pushed this under-the-limit request onto the "long" lineup.
        assertTrue(json > Cloud.LONG_CHARS && text < Cloud.LONG_CHARS)
        assertEquals("long", oldTask(req, tools))
        assertEquals("agent", Cloud.task(req, tools))
    }

    @Test fun memoryBlockIsReadOncePerTurn() {
        var reads = 0
        val memo = PromptMemo<String>(20_000)
        // A 6-step task asks for the system prompt (and so the memory block) once per model call.
        repeat(6) { memo.get(7L) { reads++; Thread.sleep(3); "memories" } }
        println("BENCH memory block: 6 model calls -> before 6 database reads, after $reads")
        assertEquals(1, reads)
    }
}
