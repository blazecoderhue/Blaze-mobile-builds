package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineBrainTest {
    @Test fun prompt() {
        val m = JSONArray().put(JSONObject().put("role", "system").put("content", "huge system prompt"))
            .put(JSONObject().put("role", "assistant").put("content", "old"))
            .put(JSONObject().put("role", "user").put("content", "[m7] remind me to buy bread"))
            .put(JSONObject().put("role", "tool").put("content", "x"))
            .put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "and milk"))))
        val p = OfflineBrain.prompt(m)
        assertTrue(p.startsWith("<|im_start|>system\n")); assertTrue(p.endsWith("<|im_start|>assistant\n"))
        assertTrue(p.contains("<|im_start|>user\nremind me to buy bread<|im_end|>")); assertTrue(p.contains("and milk"))
        assertFalse(p.contains("huge system prompt")); assertFalse(p.contains("[m7]")); assertFalse(p.contains("\nold"))
        val long = JSONArray(); repeat(50) { long.put(JSONObject().put("role", "user").put("content", "x".repeat(500))) }
        assertTrue(OfflineBrain.prompt(long, 3000).length < 4500)
    }
}
