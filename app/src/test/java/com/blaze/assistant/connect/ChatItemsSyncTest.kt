package com.blaze.assistant.connect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** v0.45.0 P5 (#37): chats as per-message / per-reaction items, two phones against the fake gateway. */
class ChatItemsSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    class Phone(root: File, val server: SyncEngineTest.FakeServer, val key: ByteArray) {
        val dir = File(root, "chats").apply { mkdirs() }
        val split = ChatSplit { dir }
        var stateText: String? = null
        var applied = ArrayList<String>()
        private var mtime = 1_000_000_000L
        fun state() = JsonSyncState({ stateText }, { stateText = it })
        fun sync(): SyncEngine.Report {
            val st = state()
            val ads = listOf(ChatHeadAdapter(split) { applied += it }, MessageAdapter(split) { id -> st.get("chat", id)?.deleted == true }, ReactionAdapter(split))
            // Like CloudSync: between rounds only the stamps are kept, so every scenario also runs on released chats.
            return try { SyncEngine(server, ads, key, st, { 5_000_000L }).run() } finally { split.release() }
        }
        fun file(id: String) = File(dir, "$id.json")
        fun read(id: String) = JSONObject(file(id).readText())
        /** Writes the chat the way the app does (whole file), with a new mtime. */
        fun write(id: String, o: JSONObject) { file(id).writeText(o.toString()); file(id).setLastModified(mtime); mtime += 1000 }
        fun msgs(id: String): JSONArray = read(id).getJSONArray("messages")
        fun texts(id: String) = msgs(id).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("text") } }
        fun edit(id: String, f: (JSONObject) -> Unit) { val o = read(id); f(o); o.put("updated", o.optLong("updated") + 1); write(id, o) }
        fun add(id: String, role: String, text: String, at: Long, gid: String? = null) = edit(id) { o ->
            val a = o.getJSONArray("messages")
            val mx = (0 until a.length()).maxOfOrNull { a.getJSONObject(it).optLong("id") } ?: 0
            a.put(JSONObject().put("id", mx + 1).put("role", role).put("text", text).put("at", at).apply { gid?.let { put("gid", it) } })
        }
        fun react(id: String, index: Int, emoji: String?, at: Long = 77) = edit(id) { o ->
            val m = o.getJSONArray("messages").getJSONObject(index)
            if (emoji == null) m.remove("reactions") else m.put("reactions", JSONArray().put(JSONObject().put("e", emoji).put("by", "user").put("at", at)))
        }
    }

    private lateinit var server: SyncEngineTest.FakeServer
    private lateinit var key: ByteArray
    private lateinit var a: Phone
    private lateinit var b: Phone

    @Before fun setUp() {
        server = SyncEngineTest.FakeServer()
        key = SyncCrypto.deriveKey("passphrase one", SyncCrypto.newSalt(), 1_000)
        server.check = SyncCrypto.makeCheck(key)
        a = Phone(tmp.newFolder("a"), server, key)
        b = Phone(tmp.newFolder("b"), server, key)
    }

    private fun oldChat(vararg m: Triple<String, String, Long>) = JSONObject().put("id", "main").put("title", "Blaze").put("updated", 10)
        .put("messages", JSONArray().apply { m.forEachIndexed { i, (role, text, at) -> put(JSONObject().put("id", i + 1).put("role", role).put("text", text).put("at", at)) } })

    private fun kinds() = server.rows.keys.groupingBy { it.substringBefore('|') }.eachCount()

    @Test fun oldChatsMigrateAndRoundTripAsMessages() {
        a.write("main", oldChat(Triple("USER", "hello", 100), Triple("BLAZE", "hi there", 110), Triple("USER", "how are you", 120)))
        val r = a.sync()
        assertEquals(4, r.pushed)                       // the chat item + one item per message
        assertEquals(mapOf("chat" to 1, "msg" to 3), kinds())
        assertTrue(server.rows.values.none { it.blob!!.contains("hello") })
        b.sync()
        assertEquals(listOf("hello", "hi there", "how are you"), b.texts("main"))
        // The same ids on both phones, derived from the old messages (no migration write was needed on A).
        val ga = (0 until 3).map { JSONObject(a.split.get("main")!!.o.toString()).getJSONArray("messages").getJSONObject(it).getString("gid") }
        val gb = (0 until 3).map { b.msgs("main").getJSONObject(it).getString("gid") }
        assertEquals(ga, gb)
        assertEquals(0, a.sync().pushed); assertEquals(0, b.sync().pushed)
    }

    @Test fun aNewMessageIsOneSmallItemAndAReactionIsOneTinyItem() {
        a.write("main", oldChat(Triple("USER", "hello", 100), Triple("BLAZE", "hi there", 110)))
        a.sync(); b.sync()
        a.add("main", "USER", "one more", 200, gid = "devA0000-1")
        assertEquals(2, a.sync().pushed)               // that message + the chat item (its newest messages changed)
        b.sync()
        assertEquals(listOf("hello", "hi there", "one more"), b.texts("main"))
        // A 👍 on B: only the reaction item travels; no message, no chat item.
        b.react("main", 1, "👍")
        val rb = b.sync()
        assertEquals(1, rb.pushed)
        assertEquals(1, kinds()["react"])
        a.sync()
        val ra = a.msgs("main").getJSONObject(1).getJSONArray("reactions").getJSONObject(0)
        assertEquals("👍", ra.getString("e")); assertEquals("user", ra.getString("by"))
        // Taking it back is a delete of that item only.
        b.react("main", 1, null)
        assertEquals(1, b.sync().pushed)
        a.sync()
        assertFalse(a.msgs("main").getJSONObject(1).has("reactions"))
        assertEquals(0, a.sync().pushed); assertEquals(0, b.sync().pushed)
    }

    @Test fun offlineOnBothPhonesKeepsEveryMessageInOrder() {
        a.write("main", oldChat(Triple("USER", "start", 100)))
        a.sync(); b.sync()
        a.add("main", "USER", "A says hi", 200, gid = "devA0000-1")
        b.add("main", "USER", "B says hello", 250, gid = "devB0000-1")
        b.add("main", "BLAZE", "B's reply", 260, gid = "devB0000-2")
        a.sync(); b.sync(); a.sync()
        val want = listOf("start", "A says hi", "B says hello", "B's reply")
        assertEquals(want, a.texts("main")); assertEquals(want, b.texts("main"))
        // Local ids stay unique on each phone.
        val ids = (0 until 4).map { a.msgs("main").getJSONObject(it).getLong("id") }
        assertEquals(4, ids.toSet().size)
        assertEquals(0, a.sync().pushed); assertEquals(0, b.sync().pushed)
    }

    @Test fun twoBubblesOfOneReplyAtTheSameMomentStaySeparate() {
        a.write("main", oldChat(Triple("USER", "two things", 100)))
        a.add("main", "BLAZE", "Answer one", 300, gid = "devA0000-1")
        a.add("main", "BLAZE", "Answer two", 300, gid = "devA0000-2")
        a.sync(); b.sync()
        assertEquals(listOf("two things", "Answer one", "Answer two"), b.texts("main"))
    }

    @Test fun theSameMessageChangedOnBothPhonesSettlesOnTheFullerCopy() {
        a.write("main", oldChat(Triple("USER", "q", 100)))
        a.add("main", "BLAZE", "Half a rep", 200, gid = "devA0000-1")
        a.sync(); b.sync()
        a.edit("main") { it.getJSONArray("messages").getJSONObject(1).put("text", "Half a reply that finished.") }
        b.edit("main") { it.getJSONArray("messages").getJSONObject(1).put("meta", "chip") }
        a.sync(); b.sync(); a.sync(); b.sync()
        assertEquals("Half a reply that finished.", a.msgs("main").getJSONObject(1).getString("text"))
        assertEquals("Half a reply that finished.", b.msgs("main").getJSONObject(1).getString("text"))
        assertEquals(0, a.sync().pushed); assertEquals(0, b.sync().pushed)
    }

    @Test fun aMessageThatLeftOnePhoneIsNeverDeletedElsewhere() {
        a.write("main", oldChat(Triple("USER", "keep me", 100), Triple("BLAZE", "and me", 110)))
        a.sync(); b.sync()
        a.edit("main") { o -> o.put("messages", JSONArray().put(o.getJSONArray("messages").get(1))) }   // trimmed / overwritten on A
        a.sync(); b.sync()
        assertEquals(listOf("keep me", "and me"), b.texts("main"))
    }

    @Test fun clearOnOnePhoneHidesOlderMessagesOnTheOther() {
        a.write("main", oldChat(Triple("USER", "old", 100)))
        a.sync(); b.sync()
        a.edit("main") { o -> o.put("messages", JSONArray()).put(SyncMerge.CLEARED, 150L) }
        a.sync(); b.sync()
        assertEquals(emptyList<String>(), b.texts("main"))
        // A late message from before the clear doesn't come back.
        assertEquals(null, MessageAdapter.put(b.read("main"), "x-1", JSONObject().put("role", "USER").put("text", "late").put("at", 120)))
    }

    @Test fun deletedChatsTravelAndLateMessagesDontReviveThem() {
        a.write("c1", oldChat(Triple("USER", "bye", 100)).put("id", "c1"))
        a.sync(); b.sync()
        assertTrue(b.file("c1").exists())
        a.file("c1").delete()
        a.sync(); b.sync()
        assertFalse(b.file("c1").exists())
    }

    @Test fun olderBlazeWholeChatsStillMergeBothWays() {
        // Phone C runs an older Blaze: it pushes whole chats with the old adapter.
        val cDir = tmp.newFolder("c")
        val cChats = ChatAdapter({ cDir })
        var cState: String? = null
        fun cSync() = SyncEngine(server, listOf(cChats), key, JsonSyncState({ cState }, { cState = it }), { 5_000_000L }).run()
        File(cDir, "main.json").writeText(oldChat(Triple("USER", "from the old phone", 100), Triple("BLAZE", "old reply", 110)).toString())
        cSync()
        a.sync()
        assertEquals(listOf("from the old phone", "old reply"), a.texts("main"))
        // A adds a message; the old phone gets it from the chat item's newest messages and keeps everything it had.
        a.add("main", "USER", "new phone says hi", 200, gid = "devA0000-9")
        a.sync()
        cSync()
        val arr = JSONObject(File(cDir, "main.json").readText()).getJSONArray("messages")
        assertEquals(listOf("from the old phone", "old reply", "new phone says hi"), (0 until arr.length()).map { arr.getJSONObject(it).getString("text") })
        // And a new phone joining later gets the whole conversation from the message items.
        b.sync()
        assertEquals(listOf("from the old phone", "old reply", "new phone says hi"), b.texts("main"))
    }

    @Test fun anAppSaveDuringARoundIsNotLost() {
        a.write("main", oldChat(Triple("USER", "one", 100)))
        val sp = a.split
        sp.get("main")
        // Sync puts a message from the other phone into its parsed copy …
        assertTrue(sp.edit("main", create = false) { o -> MessageAdapter.put(o, "devB0000-1", JSONObject().put("role", "USER").put("text", "from B").put("at", 150)) })
        // … and the app saves the chat with a new message before the round writes.
        a.add("main", "BLAZE", "app reply", 200, gid = "devA0000-1")
        sp.flush()
        assertEquals(listOf("one", "from B", "app reply"), a.texts("main"))
    }

    @Test fun itemIdsAreSafe() {
        assertEquals("main:devA0000-1", ChatItems.msgId("main", "devA0000-1"))
        assertEquals(null, ChatItems.msgId("main", "bad id"))
        val rid = ChatItems.reactId("main", "devA0000-1", "user", "❤️")!!
        assertTrue(SyncKinds.ID_RE.matches(rid))
        assertEquals(listOf("main", "devA0000-1", "user", "❤️"), ChatItems.splitReactId(rid))
        assertEquals("main" to "devA0000-1", ChatItems.splitMsgId("main:devA0000-1"))
        // A message's stamp doesn't depend on which phone wrote it (local id, empty fields, reactions).
        val m1 = JSONObject().put("id", 3).put("role", "USER").put("text", "x").put("files", JSONArray()).put("at", 5).put("gid", "g-1")
        val m2 = JSONObject().put("gid", "g-1").put("at", 5).put("text", "x").put("role", "USER").put("id", 99)
            .put("reactions", JSONArray().put(JSONObject().put("e", "👍")))
        assertEquals(ChatItems.normMsg(m1).toString(), ChatItems.normMsg(m2).toString())
    }
}
