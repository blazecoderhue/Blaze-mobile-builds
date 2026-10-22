package com.blaze.assistant.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** search_chat over the SQLite FTS4 index (Robolectric's real SQLite). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class LongChatIndexTest {
    @Test fun searchFindsTurnFiveAndReindexesAfterRetry() {
        val idx = try { LongChat.Index(ApplicationProvider.getApplicationContext()).also { it.writableDatabase } } catch (_: Throwable) { null }
        assumeTrue("no FTS4 in this SQLite", idx != null)
        val h = JSONArray()
        for (n in 0 until 40) h.put(JSONObject().put("role", if (n % 2 == 0) "user" else "assistant").put("content",
            if (n == 5) "The courier for Lusaka is Yango, booked for Friday." else "Turn $n is about the store layout."))
        idx!!.sync("c1", h)
        assertEquals(40, idx.indexed("c1"))
        val hits = idx.search("courier lusaka", "c1", 5)
        assertTrue(hits.isNotEmpty())
        assertEquals(5, hits.first().turn)
        assertTrue(hits.first().snippet.contains("[courier]", ignoreCase = true) || hits.first().snippet.contains("courier", true))
        assertTrue(idx.search("courier", "other-chat", 5).isEmpty())
        // A retry made the chat shorter: it is indexed again from scratch.
        val shorter = JSONArray(); for (n in 0 until 3) shorter.put(h.get(n))
        idx.sync("c1", shorter)
        assertEquals(3, idx.indexed("c1"))
        assertTrue(idx.search("courier", "c1", 5).isEmpty())
        idx.forget("c1")
        assertEquals(0, idx.indexed("c1"))
    }
}
