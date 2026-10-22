package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.1 token ledger: a request split into its parts, folded per day, reported biggest first. */
class TokenLedgerTest {
    private fun msg(role: String, content: Any) = JSONObject().put("role", role).put("content", content)
    private fun tool(n: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n).put("description", "d".repeat(396)))

    @Test fun breakdownByPart() {
        val req = JSONArray()
            .put(msg("system", "s".repeat(4000)))
            .put(msg("user", "hello there"))
            .put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("id", "1"))))
            .put(msg("tool", "x".repeat(800)).put("name", "fetch_page"))
            .put(msg("tool", "WhatsApp (com.whatsapp). Elements, use the [number]:\n[1] Chats").put("name", "read_screen"))
            .put(msg("user", JSONArray().put(JSONObject().put("type", "text").put("text", "look")).put(JSONObject().put("type", "image_url"))))
        val tools = JSONArray().put(tool("web_search")).put(tool("send_sms"))
        val b = TokenLedger.breakdown(req, tools) { if (it == "send_sms") "messages" else null }
        assertEquals(1000, b["system prompt"])
        assertEquals(200, b["results: fetch_page"])
        assertTrue((b["screen readouts"] ?: 0) > 0)
        assertEquals(TokenLedger.IMAGE_TOKENS, b["images"])
        assertTrue((b["schemas: messages"] ?: 0) > 100)
        assertTrue((b["schemas: core"] ?: 0) > 100)
        assertTrue((b["tool calls"] ?: 0) > 0)
    }

    @Test fun foldKeepsSevenDaysAndReports() {
        val l = JSONObject()
        for (d in 1..9) TokenLedger.fold(l, "2026-10-%02d".format(d), mapOf("system prompt" to 1000, "results: fetch_page" to 300))
        assertEquals(TokenLedger.DAYS, l.length())
        assertTrue(!l.has("2026-10-01") && l.has("2026-10-09"))
        val (calls, lines) = TokenLedger.top(l)
        assertEquals(7L, calls)
        assertEquals("system prompt", lines[0].part)
        assertEquals(1000L, lines[0].perCall)
        val r = TokenLedger.report(l, 3)
        assertTrue(r, r.contains("1. system prompt: 77%") && r.contains("→") && r.contains("3 lookups"))
        assertTrue(TokenLedger.report(JSONObject(), 0).startsWith("No model calls"))
    }
}
