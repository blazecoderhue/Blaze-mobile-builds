package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v0.45.0 P3 #1 / #7: the chat list without parsing every chat, and saves that never fail silently. */
class ChatIndexTest {
    private val dir: File = Files.createTempDirectory("chats").toFile()

    @After fun tearDown() { ChatIndex.renameHook = null; dir.deleteRecursively() }

    private fun chat(id: String, title: String, updated: Long, pinned: Boolean = false, turns: Int = 50) = JSONObject()
        .put("id", id).put("title", title).put("updated", updated).put("pinned", pinned).put("preview", "p-$id")
        .put("history", JSONArray().apply { repeat(turns) { put(JSONObject().put("role", "user").put("content", "x".repeat(200))) } })

    private fun writeRaw(o: JSONObject) = File(dir, o.getString("id") + ".json").writeText(o.toString())

    @Test fun listReadsEachFileOnceThenOnlyStats() {
        for (i in 1..5) writeRaw(chat("c$i", "Chat $i", 1000L + i))
        val idx = ChatIndex(dir)
        val first = idx.list()
        assertEquals(listOf("c5", "c4", "c3", "c2", "c1"), first.map { it.id })
        assertEquals(5, idx.parses)
        idx.list(); idx.list()
        assertEquals("unchanged files are never parsed again", 5, idx.parses)
    }

    @Test fun saveUpdatesOneRowWithoutReparsingOthers() {
        for (i in 1..20) writeRaw(chat("c$i", "Chat $i", 1000L + i))
        val idx = ChatIndex(dir)
        idx.list()
        val p0 = idx.parses
        repeat(10) { n -> assertTrue(idx.save(chat("main", "Blaze", 5000L + n))) }
        val l = idx.list()
        assertEquals(p0, idx.parses)   // the saved header is taken from the object, nothing is read back
        assertEquals("main", l.first().id)
        assertEquals(21, l.size)
    }

    @Test fun coldStartUsesTheIndexFile() {
        for (i in 1..8) writeRaw(chat("c$i", "Chat $i", 1000L + i))
        ChatIndex(dir).list()
        assertTrue(File(dir, ChatIndex.INDEX).isFile)
        val cold = ChatIndex(dir)          // a new process
        assertEquals(8, cold.list().size)
        assertEquals("headers come from the index, no chat is parsed", 0, cold.parses)
        assertFalse("the index is never listed as a chat", cold.list().any { it.id.startsWith(".") })
    }

    @Test fun anOutsideWriteIsPickedUpAndDeletesDisappear() {
        writeRaw(chat("a", "Old title", 1000)); writeRaw(chat("b", "B", 900))
        val idx = ChatIndex(dir)
        assertEquals("Old title", idx.list().first { it.id == "a" }.title)
        // Cloud sync (or an older build) rewrites the file behind the index: a new stamp → read again.
        val f = File(dir, "a.json")
        f.writeText(chat("a", "Synced title, longer", 2000).toString()); f.setLastModified(f.lastModified() + 5000)
        assertEquals("Synced title, longer", idx.list().first { it.id == "a" }.title)
        File(dir, "b.json").delete()
        assertEquals(listOf("a"), idx.list().map { it.id })
        assertEquals(listOf("a"), ChatIndex(dir).list().map { it.id })
    }

    @Test fun aBrokenIndexIsRebuiltAndPinnedComesFirst() {
        writeRaw(chat("x", "X", 1000, pinned = true)); writeRaw(chat("y", "Y", 3000))
        File(dir, ChatIndex.INDEX).writeText("{not json")
        val l = ChatIndex(dir).list()
        assertEquals(listOf("x", "y"), l.map { it.id })
    }

    @Test fun renameFailureFallsBackToMove() {
        ChatIndex.renameHook = { _, _ -> false }
        val idx = ChatIndex(dir)
        assertTrue(idx.save(chat("m", "M", 1)))
        assertEquals("M", JSONObject(File(dir, "m.json").readText()).getString("title"))
        assertFalse(File(dir, "m.json.tmp").exists())
    }

    @Test fun aFailedWriteReportsFalseAndKeepsTheOldFile() {
        val idx = ChatIndex(dir)
        assertTrue(idx.save(chat("k", "Kept", 1)))
        // The tmp path is a directory: the write cannot happen (like a full disk).
        File(dir, "k.json.tmp").mkdirs()
        assertFalse(idx.save(chat("k", "Lost", 2)))
        assertEquals("Kept", JSONObject(File(dir, "k.json").readText()).getString("title"))
        assertEquals("Kept", idx.list().first { it.id == "k" }.title)
    }
}
