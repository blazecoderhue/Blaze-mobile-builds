package com.blaze.assistant.connect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigTest {
    private val sample = """
        {"mcpServers": {
          "GitHub": {"url": "https://api.example.com/mcp", "headers": {"Authorization": "Bearer abc"}},
          "old-sse": {"url": "https://x.example.com/sse", "type": "sse", "disabled": true},
          "files": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]}
        }}
    """.trimIndent()

    @Test fun parsesTheSharedShape() {
        val l = McpConfig.parse(sample)
        assertEquals(setOf("GitHub", "old-sse", "files"), l.map { it.name }.toSet())
        val gh = l.first { it.name == "GitHub" }
        assertEquals("https://api.example.com/mcp", gh.url)
        assertEquals("Bearer abc", gh.headers["Authorization"])
        assertEquals(McpConfig.AUTO, gh.transport)
        assertTrue(gh.enabled && gh.remote)
        val sse = l.first { it.name == "old-sse" }; val files = l.first { it.name == "files" }
        assertEquals(McpConfig.SSE, sse.transport)
        assertFalse(sse.enabled)
        assertEquals(McpConfig.STDIO, files.transport)
        assertFalse(files.remote)
    }

    @Test fun roundTripKeepsDesktopOnlyFields() {
        val again = McpConfig.parse(McpConfig.export(McpConfig.parse(sample)))
        val files = again.first { it.name == "files" }
        assertEquals("npx", files.raw.getString("command"))
        assertEquals(3, files.raw.getJSONArray("args").length())
        assertTrue(again.first { it.name == "old-sse" }.raw.getBoolean("disabled"))
        assertEquals("Bearer abc", again.first { it.name == "GitHub" }.headers["Authorization"])
    }

    @Test fun acceptsABareMapAndRejectsJunk() {
        assertEquals(1, McpConfig.parse("""{"a": {"url": "https://a.example/mcp"}}""").size)
        assertEquals(0, McpConfig.parse("  ").size)
        try { McpConfig.parse("nope"); throw AssertionError("should fail") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("JSON")) }
    }

    @Test fun toolNamesUseDoubleUnderscoresAndFit64() {
        assertEquals("mcp__github__create_issue", McpConfig.toolName("GitHub", "create_issue"))
        assertEquals("mcp__my_server__a_b", McpConfig.toolName("My Server!", "a.b"))
        val long = McpConfig.toolName("a-very-long-server-name-indeed", "and_an_even_longer_tool_name_that_overflows_the_limit")
        assertTrue(long.length <= 64)
        val other = McpConfig.toolName("a-very-long-server-name-indeed", "and_an_even_longer_tool_name_that_overflows_the_limit2")
        assertTrue(long != other)
        assertTrue(McpConfig.isToolName(long) && McpConfig.isToolName("mcp_legacy_tool") && !McpConfig.isToolName("web_search"))
    }

    @Test fun approvalFollowsDesktop() {
        val read = McpConfig.risk(readOnly = true, destructive = false)
        val act = McpConfig.risk(readOnly = false, destructive = false)
        val write = McpConfig.risk(readOnly = true, destructive = true)
        assertEquals(McpConfig.Risk.READ, read); assertEquals(McpConfig.Risk.ACTION, act); assertEquals(McpConfig.Risk.WRITE, write)
        assertFalse(McpConfig.needsApproval(null, read, false))
        assertTrue(McpConfig.needsApproval(null, act, false))
        assertFalse(McpConfig.needsApproval(null, act, true))          // Autopilot, default mode
        assertTrue(McpConfig.needsApproval(McpConfig.ASK, act, true))  // user asked to be asked
        assertTrue(McpConfig.needsApproval(null, write, true))         // destructive always asks
        assertFalse(McpConfig.needsApproval(McpConfig.ALWAYS, write, false))
        assertTrue(McpConfig.needsApproval(McpConfig.ASK, read, false))
        assertEquals(McpConfig.OFF, McpConfig.mode(McpConfig.OFF, read))
        assertEquals(McpConfig.ALWAYS, McpConfig.mode("bogus", read))
    }

    @Test fun toolModesSurviveExport() {
        val s = McpConfig.parse(sample).first { it.name == "GitHub" }.copy(toolModes = mapOf("delete_repo" to McpConfig.OFF))
        val back = McpConfig.parse(McpConfig.export(listOf(s)))[0]
        assertEquals(McpConfig.OFF, back.toolModes["delete_repo"])
    }

    @Test fun headersFromTokenAndLines() {
        assertEquals(mapOf("Authorization" to "Bearer t0k"), McpConfig.headersFrom("t0k", ""))
        assertEquals(mapOf("Authorization" to "Basic xyz"), McpConfig.headersFrom("Basic xyz", ""))
        val h = McpConfig.headersFrom("t", "X-Api-Key: k1\nAuthorization: Token z")
        assertEquals("k1", h["X-Api-Key"]); assertEquals("Token z", h["Authorization"])
    }

    @Test fun legacyMigration() {
        val l = McpConfig.fromLegacy("""[{"id":"zap","name":"Zapier","url":"https://z.example/mcp","enabled":false},{"id":"x","name":"x","url":""}]""") { if (it == "zap") "s3" else "" }
        assertEquals(1, l.size)
        assertEquals("Zapier", l[0].name); assertEquals("Bearer s3", l[0].headers["Authorization"]); assertFalse(l[0].enabled)
    }

    @Test fun sseParsing() {
        val ev = mutableListOf<Pair<String, String>>()
        McpConfig.readSse(sequenceOf(": comment", "event: endpoint", "data: /messages?s=1", "", "data: {\"a\":", "data: 1}", "", "data: tail")) { e, d -> ev += e to d; true }
        assertEquals(listOf("endpoint" to "/messages?s=1", "" to "{\"a\":\n1}", "" to "tail"), ev)
        var n = 0
        McpConfig.readSse(sequenceOf("data: 1", "", "data: 2", "")) { _, _ -> n++; false }
        assertEquals(1, n)
    }

    @Test fun repliesSingleAndBatch() {
        assertTrue(McpConfig.isReplyTo(JSONObject("""{"jsonrpc":"2.0","id":7,"result":{}}"""), 7))
        assertTrue(McpConfig.isReplyTo(JSONObject("""{"jsonrpc":"2.0","id":"7","result":{}}"""), 7))
        assertFalse(McpConfig.isReplyTo(JSONObject("""{"jsonrpc":"2.0","id":7,"method":"ping"}"""), 7))
        val b = McpConfig.pickReply("""[{"jsonrpc":"2.0","id":1,"result":{"x":1}},{"jsonrpc":"2.0","id":2,"result":{"x":2}}]""", 2)
        assertEquals(2, b!!.getJSONObject("result").getInt("x"))
        assertNull(McpConfig.pickReply("[]", 2))
    }

    @Test fun toolsAndResults() {
        val t = McpConfig.tools(JSONObject("""{"tools":[{"name":"get","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}},{"name":"rm","annotations":{"destructiveHint":true}},{"name":""}]}"""))
        assertEquals(2, t.size)
        assertEquals(McpConfig.Risk.READ, t[0].risk); assertEquals(McpConfig.Risk.WRITE, t[1].risk)
        assertEquals("hi\n[resource file://a]\nbody", McpConfig.flatten(JSONObject("""{"content":[{"type":"text","text":"hi"},{"type":"resource","resource":{"uri":"file://a","text":"body"}}]}""")))
        assertEquals("Tool error: bad", McpConfig.flatten(JSONObject("""{"isError":true,"content":[{"type":"text","text":"bad"}]}""")))
        assertEquals("{\"n\":1}", McpConfig.flatten(JSONObject("""{"content":[],"structuredContent":{"n":1}}""")))
        assertEquals("Done.", McpConfig.flatten(JSONObject("{}")))
    }

    @Test fun mergeAddsOrReplaces() {
        val cur = McpConfig.parse(sample)
        val inc = McpConfig.parse("""{"mcpServers":{"GitHub":{"url":"https://new.example/mcp"},"new":{"url":"https://n.example/mcp"}}}""")
        val m = McpConfig.merge(cur, inc, replace = false)
        assertEquals(4, m.size); assertEquals("https://new.example/mcp", m.first { it.name == "GitHub" }.url)
        assertEquals(2, McpConfig.merge(cur, inc, replace = true).size)
    }
}
