package com.blaze.assistant.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeySyncMcpTest {
    private fun e(v: String, t: Long) = KeySync.Entry(listOf(v), t)

    @Test fun newerMcpListWinsWhole() {
        val (merged, apply, push) = KeySync.merge(mapOf("mcp" to e("""{"mcpServers":{"a":{}}}""", 10)), mapOf("mcp" to e("""{"mcpServers":{"b":{}}}""", 20)), 99)
        assertEquals(listOf("""{"mcpServers":{"b":{}}}"""), merged["mcp"]!!.k)
        assertTrue(apply.containsKey("mcp")); assertFalse(push)
    }

    @Test fun localNewerIsPushed() {
        val (merged, apply, push) = KeySync.merge(mapOf("mcp" to e("L", 30)), mapOf("mcp" to e("R", 20)), 99)
        assertEquals(listOf("L"), merged["mcp"]!!.k); assertTrue(push); assertFalse(apply.containsKey("mcp"))
    }

    @Test fun remoteOnlyIsApplied() {
        val (_, apply, _) = KeySync.merge(emptyMap(), mapOf("mcp" to e("R", 5)), 99)
        assertTrue(apply.containsKey("mcp"))
    }

    @Test fun unknownEntriesPassThrough() {
        val (merged, apply, _) = KeySync.merge(emptyMap(), mapOf("future" to e("x", 5)), 99)
        assertTrue(merged.containsKey("future")); assertFalse(apply.containsKey("future"))
    }
}
