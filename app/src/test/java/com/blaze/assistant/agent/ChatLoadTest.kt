package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** v0.45.0 P3 #1: the chat list is only built when the one-time chat import still has to run. */
class ChatLoadTest {
    private fun store(flags: MutableMap<String, String>): Mind.Store = Proxy.newProxyInstance(Mind.Store::class.java.classLoader, arrayOf(Mind.Store::class.java)) { _, m, args ->
        when (m.name) {
            "meta" -> flags[args[0] as String] ?: ""
            "setMeta" -> { flags[args[0] as String] = args[1] as String; Unit }
            "journal" -> 1
            "getFts" -> true
            else -> null
        }
    } as Mind.Store

    @Test fun coldStartDoesNotListChatsOnceMigrated() {
        var listed = 0
        val done = store(mutableMapOf("mig_chats" to "1", "mig_memory" to "1"))
        Mind.migrate(done, { listed++; listOf("a") }, { null }, emptyList())
        assertEquals(0, listed)
        val fresh = store(mutableMapOf("mig_memory" to "1"))
        val h = JSONObject().put("history", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
        val r = Mind.migrate(fresh, { listed++; listOf("a", "b") }, { h }, emptyList())
        assertEquals(1, listed)
        assertEquals(2, r.first)
    }
}
