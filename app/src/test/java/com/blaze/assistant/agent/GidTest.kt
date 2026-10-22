package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.0 P5 (#37, R2): global message ids, migration of old chats, reactions on the message. */
class GidTest {
    @Test fun newIdsAreUniqueOrderedAndValid() {
        val ids = (1..5000).map { Gid.next(1_000L) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith(Gid.device + "-") && Gid.valid(it) && !Gid.isLegacy(it) })
        // Every bubble made gets its own id; copy() keeps it.
        val a = ChatMessage(1, Role.BLAZE, "part one"); val b = ChatMessage(2, Role.BLAZE, "part two")
        assertNotEquals(a.gid, b.gid)
        assertEquals(a.gid, a.copy(text = "edited", meta = "chip").gid)
    }

    @Test fun deviceIdIsKeptOnceMade() {
        var saved = ""
        Gid.init({ saved }, { saved = it })
        assertEquals(Gid.device, saved)
        Gid.init({ "abcd1234" }, { throw AssertionError("must not rewrite") })
        assertEquals("abcd1234", Gid.device)
        assertTrue(Gid.next().startsWith("abcd1234-"))
    }

    @Test fun legacyMigrationIsDeterministicAndIdempotent() {
        fun old() = JSONArray()
            .put(JSONObject().put("id", 1).put("role", "USER").put("text", "hi").put("at", 100))
            .put(JSONObject().put("id", 2).put("role", "STEP").put("text", "Stopped."))
            .put(JSONObject().put("id", 3).put("role", "STEP").put("text", "Stopped."))
            .put(JSONObject().put("id", 4).put("role", "BLAZE").put("text", "new").put("gid", "zzzz0000-abc"))
        val a = old(); val b = old()
        assertEquals(3, Gid.fill(a))
        Gid.fill(b)
        // Same ids on two phones, from the content.
        assertEquals((0 until 4).map { a.getJSONObject(it).getString("gid") }, (0 until 4).map { b.getJSONObject(it).getString("gid") })
        // Two identical old messages are told apart by order; an explicit id is kept.
        assertNotEquals(a.getJSONObject(1).getString("gid"), a.getJSONObject(2).getString("gid"))
        assertEquals("zzzz0000-abc", a.getJSONObject(3).getString("gid"))
        assertTrue(Gid.isLegacy(a.getJSONObject(0).getString("gid")))
        // Again: nothing changes.
        val before = a.toString()
        assertEquals(0, Gid.fill(a))
        assertEquals(before, a.toString())
    }

    @Test fun loadingAnOldChatAssignsIdsAndSavingKeepsThem() {
        val file = """{"id":"main","title":"T","messages":[{"id":1,"role":"USER","text":"hi","at":100},{"id":2,"role":"BLAZE","text":"hello","at":110,"mark":3}]}"""
        val p1 = ChatJson.parse(JSONObject(file)); val p2 = ChatJson.parse(JSONObject(file))
        assertEquals(p1.msgs.map { it.gid }, p2.msgs.map { it.gid })      // every load, every phone: the same ids
        assertTrue(p1.msgs.all { Gid.isLegacy(it.gid) })
        assertEquals(3, p1.marks[2L])
        // Saved (with a reaction added) and loaded again: same ids, the reaction kept, nothing else changed.
        val msgs = p1.msgs.map { if (it.id == 2L) it.copy(reactions = listOf(Reaction("👍", "user", 7))) else it }
        val saved = ChatJson.file(JSONObject().put("id", "main"), ChatJson.messages(msgs, p1.marks), JSONArray())
        val p3 = ChatJson.parse(JSONObject(saved))
        assertEquals(p1.msgs.map { it.gid }, p3.msgs.map { it.gid })
        assertEquals(listOf("👍"), p3.msgs[1].reactions.map { it.emoji })
        assertEquals(msgs, p3.msgs)
    }

    @Test fun duplicateExplicitIdsAreSplit() {
        val a = JSONArray().put(JSONObject().put("role", "USER").put("text", "x").put("gid", "dup-1"))
            .put(JSONObject().put("role", "USER").put("text", "y").put("gid", "dup-1"))
        Gid.fill(a)
        assertNotEquals(a.getJSONObject(0).getString("gid"), a.getJSONObject(1).getString("gid"))
    }

    @Test fun reactionsAndIdsSurviveSaveAndLoad() {
        val m = ChatMessage(7, Role.BLAZE, "Done", at = 50, reactions = listOf(Reaction("👍", "user", 60), Reaction("❤️", "halo", 61)))
        val o = JSONObject(ChatJson.message(m))
        assertEquals(m.gid, o.getString("gid"))
        val back = Reaction.list(o.getJSONArray("reactions"))
        assertEquals(m.reactions, back)
        // A message without reactions writes none (old readers see the same file as before plus "gid").
        assertFalse(JSONObject(ChatJson.message(ChatMessage(8, Role.USER, "q"))).has("reactions"))
    }

    @Test fun toggleRules() {
        var l = Reaction.toggle(emptyList(), "👍", now = 1)
        assertEquals(listOf("👍"), l.map { it.emoji })
        l = Reaction.toggle(l, "👎", now = 2)                 // 👍 and 👎 replace each other
        assertEquals(listOf("👎"), l.map { it.emoji })
        l = Reaction.toggle(l, "❤️", now = 3)
        assertEquals(listOf("👎", "❤️"), l.map { it.emoji })
        l = Reaction.toggle(l, "👎", now = 4)                 // tapping again takes it back
        assertEquals(listOf("❤️"), l.map { it.emoji })
        l = Reaction.toggle(l, "❤️", by = "halo", now = 5)    // someone else's reaction is separate
        assertEquals(2, l.size)
        // Broken entries are dropped on load, duplicates once.
        val a = JSONArray().put(JSONObject().put("e", "👍").put("by", "user").put("at", 1)).put(JSONObject().put("e", "👍").put("by", "user").put("at", 2)).put(JSONObject())
        assertEquals(1, Reaction.list(a).size)
    }

    @Test fun mergeInKeepsMemoryAndAddsSyncedMessages() {
        val u = ChatMessage(1, Role.USER, "hello", at = 100, gid = "aaaa0000-1")
        val r = ChatMessage(2, Role.BLAZE, "Hi th", at = 110, gid = "aaaa0000-2")
        val unsaved = ChatMessage(3, Role.USER, "typed just now", at = 300, gid = "aaaa0000-3")
        val cur = listOf(u, r, unsaved)
        // The file (written by sync) has the finished reply, a message from the other phone at 200, a 👍, but not the unsaved one.
        val file = listOf(u.copy(id = 11), r.copy(id = 12, text = "Hi there!", reactions = listOf(Reaction("👍", "user", 5))),
            ChatMessage(13, Role.USER, "from my tablet", at = 200, gid = "bbbb0000-1"))
        var next = 50L
        val res = SyncMergeIn.merge(cur, file, 0, 0, busy = false, pendingReact = emptySet()) { next++ }!!
        assertEquals(listOf("hello", "Hi there!", "from my tablet", "typed just now"), res.msgs.map { it.text })
        assertEquals(listOf(1L, 2L, 50L, 3L), res.msgs.map { it.id })   // local ids kept, new one fresh
        assertEquals("👍", res.msgs[1].reactions.single().emoji)
        // Busy: the turn owns its bubbles (no text change), reactions and new messages still come in.
        val busy = SyncMergeIn.merge(cur, file, 0, 0, busy = true, pendingReact = emptySet()) { next++ }!!
        assertEquals("Hi th", busy.msgs[1].text)
        assertEquals(4, busy.msgs.size)
        // A reaction changed here and not saved yet is not undone by the file.
        val mine = cur.map { if (it.id == 2L) it.copy(reactions = listOf(Reaction("👎", "user", 9))) else it }
        val keep = SyncMergeIn.merge(mine, file, 0, 0, busy = true, pendingReact = setOf("aaaa0000-2")) { next++ }!!
        assertEquals("👎", keep.msgs[1].reactions.single().emoji)
        // A newer /clear from the other phone hides what came before it; the same file twice changes nothing.
        val cleared = SyncMergeIn.merge(cur, file, 0, 150, busy = false, pendingReact = emptySet()) { next++ }!!
        assertEquals(listOf("from my tablet", "typed just now"), cleared.msgs.map { it.text })
        assertEquals(null, SyncMergeIn.merge(res.msgs, file, 0, 0, busy = false, pendingReact = emptySet()) { next++ })
    }
}
