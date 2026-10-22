package com.blaze.assistant.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** v0.24.4 private chat: nothing is persisted (chat file, journal, long-chat index) and nothing is learned. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class PrivateChatTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun hist() = JSONArray().put(JSONObject().put("role", "user").put("content", "my secret plan"))
        .put(JSONObject().put("role", "assistant").put("content", "got it"))

    @Test fun ids() {
        assertTrue(PrivateChat.isPrivateId("private-abc"))
        assertFalse(PrivateChat.isPrivateId("main"))
        assertFalse(PrivateChat.isPrivateId(null))
        assertFalse(PrivateChat.isPrivateId("abcprivate-"))
    }

    @Test fun chatStoreNeverWritesAPrivateChat() {
        ChatStore.init(ctx)
        val dir = File(ctx.filesDir, "chats")
        dir.listFiles()?.forEach { it.delete() }
        ChatStore.save(JSONObject().put("id", "private-x1").put("title", "Private").put("messages", JSONArray()))
        assertTrue(ChatStore.list().none { it.id == "private-x1" })
        assertEquals(null, ChatStore.load("private-x1"))
        assertEquals(0, dir.listFiles()?.size ?: 0)
        // A normal chat still saves.
        ChatStore.save(JSONObject().put("id", "c1").put("title", "Normal").put("messages", JSONArray()))
        assertTrue(ChatStore.list().any { it.id == "c1" })
        ChatStore.delete("c1")
    }

    @Test fun sessionPersistWritesNothing() {
        ChatStore.init(ctx)
        File(ctx.filesDir, "chats").listFiles()?.forEach { it.delete() }
        val s = Agent.Session(PrivateChat.PREFIX + "t1")
        assertTrue(s.isPrivate)
        s.add(Role.USER, "my secret plan")
        s.history.put(JSONObject().put("role", "user").put("content", "my secret plan"))
        s.add(Role.BLAZE, "got it")
        s.persist(force = true)
        s.learnAfterTurn()   // returns before titles, memories or skills
        Thread.sleep(300)
        assertEquals(0, File(ctx.filesDir, "chats").listFiles()?.size ?: 0)
        assertTrue(ChatStore.list().isEmpty())
    }

    @Test fun journalAndIndexSkipPrivateChats() {
        NativeSqlite.assume()
        val db = Mind.Db(ctx, null)
        assertEquals(0, db.journal("private-j1", hist()))
        assertEquals(0, db.count())
        assertTrue(db.journal("main", hist()) > 0)
        db.close()
        val idx = try { LongChat.Index(ctx).also { it.writableDatabase } } catch (_: Throwable) { null } ?: return
        idx.sync("private-j1", hist())
        assertEquals(0, idx.indexed("private-j1"))
    }

    @Test fun memoryAndSkillToolsAreRemoved() {
        val all = JSONArray()
        listOf("remember", "forget", "memory_save", "memory_update", "skill_manage", "write_skill", "web_search", "memory_search", "search_chat").forEach {
            all.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", it)))
        }
        val names = PrivateChat.filterTools(all).let { a -> (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") } }
        assertEquals(listOf("web_search", "memory_search", "search_chat"), names)
        assertTrue(PrivateChat.blocked("remember"))
        assertFalse(PrivateChat.blocked("web_search"))
    }
}
