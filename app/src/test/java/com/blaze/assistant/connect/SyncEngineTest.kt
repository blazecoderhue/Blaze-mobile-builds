package com.blaze.assistant.connect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Two phones against a fake gateway that follows gateway/src/sync.ts (revs, conflicts, seq cursor, paging). */
class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    class FakeServer(var epoch: String = "e1") : SyncEngine.Api {
        data class Row(val seq: Long, val rev: Long, val deleted: Boolean, val blob: String?, val at: Long)
        val rows = LinkedHashMap<String, Row>()
        var seq = 0L
        var setUp = true
        var check = ""
        var pulls = 0; var pushes = 0; var busyOnce = false
        var maxPushSeen = 0
        override fun meta() = SyncEngine.Meta(setUp, "salt", "pbkdf2-sha256:1000", check, epoch)
        override fun pull(since: Long, limit: Int): SyncEngine.Page {
            pulls++
            val after = rows.entries.filter { it.value.seq > since }.sortedBy { it.value.seq }
            val page = after.take(limit)
            val items = page.map { (k, r) -> SyncEngine.Remote(k.substringBefore('|'), k.substringAfter('|'), r.seq, r.rev, r.deleted, r.blob, r.at) }
            return SyncEngine.Page(items, page.lastOrNull()?.value?.seq ?: since, after.size > page.size, epoch)
        }
        override fun push(items: List<SyncEngine.Out>): SyncEngine.PushResult {
            if (busyOnce) { busyOnce = false; throw SyncEngine.Busy() }
            pushes++; maxPushSeen = maxOf(maxPushSeen, items.size)
            require(items.size <= 200)
            val acc = ArrayList<SyncEngine.Accepted>(); val con = ArrayList<SyncEngine.Remote>(); val rej = ArrayList<SyncEngine.Rejected>()
            for (i in items) {
                val k = i.kind + "|" + i.id
                val r = rows[k]
                if ((r?.rev ?: 0) != i.baseRev) {
                    con += if (r != null) SyncEngine.Remote(i.kind, i.id, r.seq, r.rev, r.deleted, r.blob, r.at) else SyncEngine.Remote(i.kind, i.id, 0, 0, true, null, 0)
                    continue
                }
                if ((i.blob?.length ?: 0) > SyncEngine.ITEM_LIMIT) { rej += SyncEngine.Rejected(i.kind, i.id, "too_large"); continue }
                if (i.blob == null && r == null) { acc += SyncEngine.Accepted(i.kind, i.id, 0); continue }
                seq++
                rows[k] = Row(seq, (r?.rev ?: 0) + 1, i.blob == null, i.blob, 1_000L * seq)
                acc += SyncEngine.Accepted(i.kind, i.id, (r?.rev ?: 0) + 1)
            }
            return SyncEngine.PushResult(acc, con, rej)
        }
    }

    /** A kind kept in memory: id → (t, data). */
    class MapAdapter(override val kind: String = "memory") : SyncEngine.Adapter {
        val items = HashMap<String, Pair<Long, JSONObject>>()
        var applied = 0
        override fun stamps() = items.mapValues { (_, v) -> "${v.first}:${v.second}" }
        override fun read(id: String) = items[id]?.let { SyncMerge.Item(kind, id, it.first, it.second) }
        override fun apply(item: SyncMerge.Item) { applied++; if (item.deleted) items.remove(item.id) else items[item.id] = item.t to item.data!! }
        fun set(id: String, t: Long, text: String) { items[id] = t to JSONObject().put("text", text) }
        fun text(id: String) = items[id]?.second?.optString("text")
    }

    class Phone(root: File, val server: FakeServer, val key: ByteArray) {
        val chatsDir = File(root, "chats").apply { mkdirs() }
        val chats = ChatAdapter({ chatsDir })
        val mem = MapAdapter()
        var stateText: String? = null
        var clock = 1_000_000L
        fun engine() = SyncEngine(server, listOf(chats, mem), key, JsonSyncState({ stateText }, { stateText = it }), { clock })
        fun sync() = engine().run()
        fun writeChat(id: String, vararg msgs: Triple<Long, String, Long>, updated: Long) {
            val o = JSONObject().put("id", id).put("title", "T").put("updated", updated)
                .put("messages", JSONArray().apply { msgs.forEach { (mid, text, at) -> put(JSONObject().put("id", mid).put("role", "USER").put("text", text).put("at", at)) } })
            File(chatsDir, "$id.json").writeText(o.toString())
        }
        fun chatTexts(id: String): List<String> {
            val a = JSONObject(File(chatsDir, "$id.json").readText()).getJSONArray("messages")
            return (0 until a.length()).map { a.getJSONObject(it).getString("text") }
        }
    }

    private lateinit var server: FakeServer
    private lateinit var key: ByteArray
    private lateinit var a: Phone
    private lateinit var b: Phone

    @Before fun setUp() {
        server = FakeServer()
        key = SyncCrypto.deriveKey("passphrase one", SyncCrypto.newSalt(), 1_000)
        server.check = SyncCrypto.makeCheck(key)
        a = Phone(tmp.newFolder("a"), server, key)
        b = Phone(tmp.newFolder("b"), server, key)
    }

    @Test fun anItemMadeOnOnePhoneReachesTheOther() {
        a.mem.set("m1", 10, "Sister is Mary")
        a.writeChat("main", Triple(1L, "hello from A", 100L), updated = 100)
        val r = a.sync()
        assertEquals(2, r.pushed)
        assertEquals(2, server.rows.size)
        // The server only has ciphertext.
        assertTrue(server.rows.values.none { it.blob!!.contains("Mary") || it.blob.contains("hello") })
        b.sync()
        assertEquals("Sister is Mary", b.mem.text("m1"))
        assertEquals(listOf("hello from A"), b.chatTexts("main"))
        // Nothing changed: the next rounds push nothing.
        assertEquals(0, a.sync().pushed); assertEquals(0, b.sync().pushed)
    }

    @Test fun twoPhonesWritingToTheSameChatKeepEveryMessage() {
        a.writeChat("main", Triple(1L, "start", 100L), updated = 100)
        a.sync(); b.sync()
        // Offline on both, then each adds a message with the same local id.
        a.writeChat("main", Triple(1L, "start", 100L), Triple(2L, "A says hi", 200L), updated = 200)
        b.writeChat("main", Triple(1L, "start", 100L), Triple(2L, "B says hello there", 250L), updated = 250)
        a.sync()
        val rb = b.sync()                  // conflict: B merges, pushes the union
        assertTrue(rb.merged + rb.pushed > 0)
        a.sync()
        val want = listOf("start", "A says hi", "B says hello there")
        assertEquals(want, a.chatTexts("main"))
        assertEquals(want, b.chatTexts("main"))
        // Settled: no more pushes either side.
        assertEquals(0, a.sync().pushed); assertEquals(0, b.sync().pushed)
    }

    @Test fun lastWriterWinsForOtherKinds() {
        a.mem.set("m1", 10, "v1"); a.sync(); b.sync()
        a.mem.set("m1", 30, "A's newer edit")
        b.mem.set("m1", 20, "B's older edit")
        b.sync()            // B goes first: server has B's
        a.sync()            // A's push conflicts, A's is newer: kept and pushed on top
        b.sync()
        assertEquals("A's newer edit", a.mem.text("m1"))
        assertEquals("A's newer edit", b.mem.text("m1"))
    }

    @Test fun deletesTravelAsTombstones() {
        a.mem.set("m1", 10, "forget me"); a.mem.set("m2", 10, "keep me")
        a.sync(); b.sync()
        a.mem.items.remove("m1")
        a.sync()
        assertTrue(server.rows["memory|m1"]!!.deleted)
        assertEquals(null, server.rows["memory|m1"]!!.blob)
        b.sync()
        assertFalse(b.mem.items.containsKey("m1"))
        assertEquals("keep me", b.mem.text("m2"))
        // A delete is sent once.
        assertEquals(0, a.sync().pushed)
    }

    @Test fun pullPagesThroughEverything() {
        repeat(450) { a.mem.set("m$it", it.toLong() + 1, "fact $it") }
        a.sync()
        assertTrue(server.pushes >= 3)                 // 200 items per push at most
        assertTrue(server.maxPushSeen <= 200)
        server.pulls = 0
        b.sync()
        assertEquals(450, b.mem.items.size)
        assertTrue(server.pulls >= 3)                  // 200 per page
    }

    @Test fun busyIsRetried() {
        a.mem.set("m1", 1, "x")
        server.busyOnce = true
        assertEquals(1, a.sync().pushed)
    }

    @Test fun anItemTooBigIsSkippedAndTheRestSync() {
        val big = StringBuilder(); val rnd = java.util.Random(1)
        repeat(700_000) { big.append(('a' + rnd.nextInt(26))) }   // random: gzip can't shrink it under the limit
        a.mem.set("huge", 1, big.toString())
        a.mem.set("small", 1, "fine")
        val r = a.sync()
        assertEquals(listOf("memory/huge"), r.skipped)
        assertTrue(server.rows.containsKey("memory|small"))
        assertFalse(server.rows.containsKey("memory|huge"))
        // Not retried every round.
        assertEquals(emptyList<String>(), a.sync().skipped)
    }

    @Test fun aResetWithAnotherPassphraseNeedsTheNewOne() {
        a.mem.set("m1", 1, "x"); a.sync()
        val other = SyncCrypto.deriveKey("passphrase two", SyncCrypto.newSalt(), 1_000)
        server.epoch = "e2"; server.check = SyncCrypto.makeCheck(other); server.rows.clear()
        try { a.sync(); fail("synced with the old key") } catch (_: SyncEngine.WrongKey) { }
    }

    @Test fun aResetWithTheSamePassphraseStartsOver() {
        a.mem.set("m1", 1, "x"); a.sync()
        server.epoch = "e2"; server.rows.clear()
        assertEquals(1, a.sync().pushed)       // everything goes up again against the new copy
        assertTrue(server.rows.containsKey("memory|m1"))
    }

    @Test fun deletedCloudCopyIsReported() {
        server.setUp = false
        try { a.sync(); fail() } catch (_: SyncEngine.NotSetUp) { }
    }

    @Test fun blobsAreBoundToTheirItem() {
        a.mem.set("m1", 1, "mine"); a.mem.set("m2", 1, "other"); a.sync()
        // A server swapping blobs between items: B can't open them and applies nothing.
        val r1 = server.rows["memory|m1"]!!; val r2 = server.rows["memory|m2"]!!
        server.rows["memory|m1"] = r1.copy(blob = r2.blob); server.rows["memory|m2"] = r2.copy(blob = r1.blob)
        val r = b.sync()
        assertEquals(2, r.failedOpen)
        assertTrue(b.mem.items.isEmpty())
    }
}
