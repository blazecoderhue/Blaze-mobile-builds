package com.blaze.assistant.connect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private fun note(id: String, t: Long, text: String?) = SyncMerge.Item("memory", id, t, text?.let { JSONObject().put("text", it) })

    private fun msg(id: Long, role: String, text: String, at: Long) = JSONObject().put("id", id).put("role", role).put("text", text).put("at", at)
    private fun chat(updated: Long, vararg m: JSONObject, title: String = "Blaze") =
        JSONObject().put("id", "main").put("title", title).put("updated", updated).put("messages", JSONArray().apply { m.forEach { put(it) } })
    private fun texts(c: JSONObject): List<String> { val a = c.getJSONArray("messages"); return (0 until a.length()).map { a.getJSONObject(it).getString("text") } }
    private fun ids(c: JSONObject): List<Long> { val a = c.getJSONArray("messages"); return (0 until a.length()).map { a.getJSONObject(it).getLong("id") } }

    @Test fun lastWriterWins() {
        assertEquals(SyncMerge.Outcome.TakeRemote, SyncMerge.resolve(note("m", 10, "old"), note("m", 20, "new")))
        assertEquals(SyncMerge.Outcome.KeepLocal, SyncMerge.resolve(note("m", 30, "mine"), note("m", 20, "theirs")))
    }

    @Test fun tiesSettleTheSameWayOnBothPhones() {
        val a = note("m", 10, "aaa"); val b = note("m", 10, "bbb")
        val onA = SyncMerge.resolve(a, b); val onB = SyncMerge.resolve(b, a)
        // A keeps its own exactly when B takes A's: both end with the same copy.
        assertTrue((onA == SyncMerge.Outcome.KeepLocal) == (onB == SyncMerge.Outcome.TakeRemote))
    }

    @Test fun tombstonesFollowTheSameRule() {
        assertEquals(SyncMerge.Outcome.TakeRemote, SyncMerge.resolve(note("m", 10, "here"), note("m", 20, null)))
        assertEquals(SyncMerge.Outcome.KeepLocal, SyncMerge.resolve(note("m", 30, "edited after"), note("m", 20, null)))
        // A deleted chat against a newer copy: the copy wins (chats only merge when both exist).
        val del = SyncMerge.Item("chat", "c", 10, null)
        val live = SyncMerge.Item("chat", "c", 20, chat(20, msg(1, "USER", "hi", 5)))
        assertEquals(SyncMerge.Outcome.TakeRemote, SyncMerge.resolve(del, live))
    }

    @Test fun envelopeRoundTrip() {
        val i = note("m1", 42, "Lives in Ndola")
        val back = SyncMerge.Item.parse("memory", "m1", i.envelope())
        assertEquals(42L, back.t); assertEquals("Lives in Ndola", back.data!!.getString("text"))
        val tomb = SyncMerge.Item.parse("memory", "m1", note("m1", 7, null).envelope())
        assertTrue(tomb.deleted)
        try { SyncMerge.Item.parse("memory", "other", i.envelope()); throw AssertionError("mismatch accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun chatMessagesAreAUnion() {
        // Both phones started from the same two messages, then each added its own turn with the same local ids.
        val base = arrayOf(msg(1, "USER", "hello", 100), msg(2, "BLAZE", "hi there", 110))
        val a = chat(300, *base, msg(3, "USER", "from phone A", 200), msg(4, "BLAZE", "answer A", 210))
        val b = chat(250, *base, msg(3, "USER", "from phone B", 220), msg(4, "BLAZE", "answer B", 230))
        val m = SyncMerge.mergeChat(a, b)
        assertEquals(listOf("hello", "hi there", "from phone A", "answer A", "from phone B", "answer B"), texts(m))
        assertEquals(ids(m).size, ids(m).toSet().size)   // colliding ids got fresh ones
        assertEquals(300L, m.getLong("updated"))
        // Same result whichever phone merges.
        assertEquals(texts(m), texts(SyncMerge.mergeChat(b, a)))
    }

    @Test fun chatKeepsTheFullerCopyOfAMessage() {
        val a = chat(10, msg(1, "BLAZE", "Half a rep", 100))
        val b = chat(20, msg(1, "BLAZE", "Half a reply that finished streaming.", 100))
        assertEquals(listOf("Half a reply that finished streaming."), texts(SyncMerge.mergeChat(a, b)))
    }

    @Test fun chatFieldsComeFromTheNewerCopyAndMissingOnesStay() {
        val older = chat(10, msg(1, "USER", "x", 1), title = "Old").put("history", JSONArray().put("h1"))
        val newer = chat(20, msg(2, "USER", "y", 2), title = "New")   // a trimmed copy: no model history
        val m = SyncMerge.mergeChat(older, newer)
        assertEquals("New", m.getString("title"))
        assertEquals(1, m.getJSONArray("history").length())
    }

    @Test fun clearOnOnePhoneHidesOlderMessagesOnBoth() {
        val a = chat(20, msg(1, "USER", "old", 100), msg(2, "USER", "after clear", 300)).put(SyncMerge.CLEARED, 200L)
        val b = chat(10, msg(1, "USER", "old", 100), msg(3, "BLAZE", "also old", 150))
        val m = SyncMerge.mergeChat(b, a)
        assertEquals(listOf("after clear"), texts(m))
        assertEquals(200L, m.getLong(SyncMerge.CLEARED))
    }

    @Test fun resolveChatsMerges() {
        val local = SyncMerge.Item("chat", "main", 20, chat(20, msg(1, "USER", "a", 1)))
        val remote = SyncMerge.Item("chat", "main", 30, chat(30, msg(1, "USER", "b", 2)))
        val o = SyncMerge.resolve(local, remote)
        assertTrue(o is SyncMerge.Outcome.Merged)
        assertEquals(listOf("a", "b"), texts((o as SyncMerge.Outcome.Merged).item.data!!))
        // Remote already has everything local has: just take it.
        val sup = SyncMerge.Item("chat", "main", 30, chat(30, msg(1, "USER", "a", 1), msg(2, "USER", "b", 2)))
        assertEquals(SyncMerge.Outcome.TakeRemote, SyncMerge.resolve(local, sup))
    }

    @Test fun trimDropsHistoryThenOldMessages() {
        val msgs = (1..100).map { msg(it.toLong(), "USER", "message $it " + "z".repeat(50), it * 10L) }.toTypedArray()
        val c = chat(5, *msgs).put("history", JSONArray().apply { repeat(50) { put("h".repeat(100)) } })
        val noHist = SyncMerge.trimChat(c) { !it.has("history") }
        assertNotNull(noHist); assertEquals(100, noHist!!.getJSONArray("messages").length())
        val small = SyncMerge.trimChat(c) { it.toString().length < 3000 }!!
        val kept = texts(small)
        assertTrue(kept.size in 1..99)
        assertTrue(kept.last().startsWith("message 100"))   // the newest stay
        assertNull(SyncMerge.trimChat(c) { false })
    }
}
