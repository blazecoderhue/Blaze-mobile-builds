package com.blaze.assistant.agent

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.JdbcSql
import com.blaze.assistant.store.Sql
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v0.26.3 one memory store on the JVM (sqlite-jdbc): Mind on blaze.db. Round trip through the tools, the shared
 * schema left as Desktop's, FTS5 ranking, the journal and its logs, and the mind.db → blaze.db copy (idempotent,
 * retried after an interruption, never without a backup).
 */
class MindStoreTest {
    private lateinit var dir: File
    private val sqls = ArrayList<Sql>()

    @Before fun setUp() { dir = Files.createTempDirectory("mindstore").toFile() }
    @After fun tearDown() { Mind.use(null); sqls.forEach { try { it.close() } catch (_: Exception) { } }; dir.deleteRecursively() }

    private fun open(name: String = "blaze.db"): MindStore {
        val s = JdbcSql("jdbc:sqlite:" + File(dir, name).path).also { sqls += it }
        return MindStore(BlazeDb(s), s)
    }

    private fun hist(vararg lines: String) = JSONArray().apply {
        lines.forEachIndexed { i, t -> put(JSONObject().put("role", if (i % 2 == 0) "user" else "assistant").put("content", t)) }
    }

    @Test fun sharedSchemaStaysDesktops() {
        val st = open()
        val v1 = javaClass.getResource("/desktop/store_v1.sql")!!.readText()
        val go = JdbcSql("jdbc:sqlite:" + File(dir, "go.db").path).also { sqls += it }
        go.conn.createStatement().use { it.executeUpdate(v1) }
        fun master(s: Sql, onlyV1: Boolean) = s.query("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name") {
            "${it.text(0)}|${it.text(1)}|${it.text(2)}" }.filter { !onlyV1 || !it.split('|')[1].startsWith("mind_") }
        val blazeSql = sqls.first()
        assertEquals("the v1 tables, indexes and triggers are Desktop's, untouched", master(go, false), master(blazeSql, true))
        assertEquals(1, st.blaze.version())
        assertTrue(master(blazeSql, false).any { it.contains("|mind_journal|") })
        // What Mind saves is a normal blaze.db memory: Desktop / CLI read it with their own code.
        Mind.use(st)
        assertEquals("saved", Mind.save("preference", "food", "No pork, on weekdays").first)
        val row = st.blaze.memories().single()
        assertEquals("No pork, on weekdays", row.text); assertEquals("preference", row.category)
        assertTrue(row.created in (BlazeDb.now() - 5)..(BlazeDb.now() + 1))   // unix seconds, like Go
        // And a memory the CLI wrote shows up in Mind, kind and subject guessed.
        st.blaze.add("My sister Mutale lives in Kitwe")
        val m = Mind.all().first { it.text.startsWith("My sister") }
        assertEquals("person", m.kind); assertEquals("Mutale", m.subject)
    }

    @Test fun roundTripThroughTheTools() {
        val st = open(); Mind.use(st)
        Mind.save("person", "Gloria", "Gloria is the user's cousin in Chingola", related = "Chingola")
        Mind.save("preference", "food", "No pork, on weekdays")
        Mind.save("project", "HomeGlow", "Shopify store selling lamps; launch planned for November")
        val (a, id) = Mind.save("profile", "You", "Lives in Ndola, Zambia")
        assertEquals("saved", a)
        assertEquals("same" to id, Mind.save("profile", "you", "lives in ndola, zambia"))
        assertEquals("same words, other punctuation: one row per norm", "same" to id, Mind.save("fact", "Home", "Lives in Ndola Zambia"))
        assertEquals("updated" to id, Mind.save("profile", "You", "Lives in Lusaka, Zambia"))
        assertEquals("secret", Mind.save("fact", "bank", "My PIN is 4455").first)
        assertEquals(4, st.blaze.countMemories())
        val g = Mind.all().first { it.subject == "Gloria" }
        assertEquals("person", g.kind); assertEquals("Chingola", g.related)
        assertTrue(Mind.run("memory_search", JSONObject().put("query", "cousin")).contains("Gloria"))
        assertTrue("subjects are searchable too", Mind.run("memory_search", JSONObject().put("query", "HomeGlow")).contains("lamps"))
        assertTrue(Mind.run("memory_search", JSONObject().put("query", "Lusaka")).contains("#$id [profile] You: Lives in Lusaka, Zambia"))
        val saved = Mind.run("memory_save", JSONObject().put("kind", "preference").put("subject", "travel").put("text", "Window seats on work trips").put("inferred", true))
        assertTrue(saved, saved.startsWith("Saved #"))
        val upd = saved.removePrefix("Saved #").removeSuffix(".").toLong()
        assertTrue(Mind.all().first { it.id == upd }.text.endsWith(Mind.INFERRED))
        assertTrue(Mind.run("memory_update", JSONObject().put("id", upd).put("text", "Aisle seats on work trips")).startsWith("Updated"))
        assertEquals("Aisle seats on work trips", st.mem(upd)!!.text)
        assertEquals("travel", st.mem(upd)!!.subject)
        assertTrue(Mind.run("memory_update", JSONObject().put("id", 999).put("text", "x y z")).startsWith("No saved line"))
        // The prompt carries only the pinned core and the index.
        val block = Mind.promptBlock()
        assertTrue(block.contains("- [profile] You: Lives in Lusaka, Zambia"))
        assertTrue(block.contains("- person: Gloria (1)"))
        assertFalse("project lines are looked up, not pasted", block.contains("lamps"))
        Mind.delete(upd)
        assertNull(st.mem(upd))
        assertTrue("FTS follows deletes", st.search("aisle", null, 5).isEmpty())
        assertEquals(0, sqls.first().query("SELECT count(*) FROM mind_mem WHERE id=?", upd) { it.long(0) }.first())
    }

    @Test fun searchRanksWithBm25AndTouches() {
        val st = open(); Mind.use(st)
        val t0 = 1_760_000_000_000L
        st.save("fact", "HomeGlow", "HomeGlow tests run with pnpm test", now = t0)
        val best = st.save("fact", "HomeGlow", "HomeGlow deploys with shopify theme push every friday", now = t0 + 1000).second
        st.save("fact", "Shopify", "Shopify store sells home lighting", now = t0 + 2000)
        val r = st.search("deploy shopify theme", null, 5)
        assertEquals("most words matched first", best, r.first().id)
        // Same number of words matched: bm25 decides (a short row with the term beats a long one), not recency.
        val short = st.save("fact", "Tea", "Rooibos tea", now = t0 + 3000).second
        st.save("fact", "Tea", "Has a long story about rooibos and many other drinks and things over the years", now = t0 + 9000)
        assertEquals(short, st.search("rooibos", null, 5).first().id)
        assertTrue(st.blaze.memory(short)!!.uses >= 1)
        assertEquals(listOf("Tea"), st.search("rooibos", "fact", 5).map { it.subject }.distinct())
        assertTrue(st.search("rooibos", "person", 5).isEmpty())
        st.search("\"quotes\" AND (weird*) NEAR: -x ^y", null, 5)   // never an FTS syntax error
    }

    @Test fun journalAndLogs() {
        val st = open(); Mind.use(st)
        assertEquals(0, st.journal("private-x", hist("secret plan", "ok")))
        assertEquals(2, st.journal("main", hist("My courier in Lusaka is Yango", "Noted.")))
        assertEquals(0, st.journal("main", hist("My courier in Lusaka is Yango", "Noted.")))
        assertEquals("a retry changes the turn in place", 0, st.journal("main", hist("My courier in Lusaka is Yango", "Noted, Yango it is.")))
        assertTrue(Mind.searchChat("Yango", 5).contains("main #0"))
        assertTrue("prefix search like before", Mind.searchChat("Yan", 5).contains("Yango"))
        assertTrue(Mind.searchChat("Yango it is", 5).contains("Noted, Yango it is."))
        assertFalse(Mind.searchChat("Noted.", 5).contains("Noted.\u201d"))
        val id = Mind.searchChat("courier", 5).substringAfter("turn j").substringBefore(' ').toLong()
        assertTrue(Mind.readTurn("j$id")!!.contains("Yango"))
        assertEquals(2, st.unreviewedCount())
        st.markReviewed(listOf(id)); assertEquals(1, st.unreviewedCount())
        // Logs
        assertTrue(MindLogs.append(st, "life_log:2026-10-12", "heard", "Heard nearby: we need milk", 100, MindLogs.LIFE) > 0)
        assertEquals(0L, MindLogs.append(st, "life_log:2026-10-12", "heard", "Heard nearby: we need milk", 100, MindLogs.LIFE))
        assertTrue(MindLogs.append(st, "life_log:2026-10-12", "heard", "Heard nearby: and bread", 130, MindLogs.LIFE) > 0)
        assertEquals(2, MindLogs.count(st, MindLogs.LIFE)); assertEquals(2, MindLogs.count(st, "chat"))
        assertEquals(listOf(0, 1), MindLogs.recent(st, "life_log:2026-10-12", 10).map { it.turn })
        assertEquals(1, MindLogs.since(st, "life_log:2026-10-12", 120).size)
        assertTrue(Mind.searchChat("milk", 5).contains("life_log:2026-10-12"))
        assertEquals(2, MindLogs.deleteKind(st, MindLogs.LIFE))
        assertTrue(Mind.searchChat("milk", 5).startsWith("Nothing"))
        sqls.first().exec("INSERT INTO mind_journal_fts(mind_journal_fts) VALUES('integrity-check')")   // throws if the index drifted
    }

    // ---------------- migration ----------------

    /** A mind.db as v0.18.0–v0.26.2 wrote it (the FTS4 tables aren't needed to read it). */
    private class OldDb(f: File) : MindStore.Source {
        val s = JdbcSql("jdbc:sqlite:" + f.path)
        var failAfter = Int.MAX_VALUE
        private var calls = 0
        init {
            s.exec("CREATE TABLE IF NOT EXISTS journal(id INTEGER PRIMARY KEY AUTOINCREMENT, conv TEXT NOT NULL, turn INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, at INTEGER NOT NULL, reviewed INTEGER NOT NULL DEFAULT 0, UNIQUE(conv, turn))")
            s.exec("CREATE TABLE IF NOT EXISTS memories(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, subject TEXT NOT NULL, text TEXT NOT NULL, related TEXT NOT NULL DEFAULT '', source_turn INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, updated INTEGER NOT NULL, mirror INTEGER NOT NULL DEFAULT 0)")
            s.exec("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            try { s.exec("ALTER TABLE journal ADD COLUMN kind TEXT NOT NULL DEFAULT 'chat'") } catch (_: Exception) { }
        }
        override fun metaAll() = s.query("SELECT key, value FROM meta") { it.text(0) to it.text(1) }.toMap()
        override fun oldMemories() = s.query("SELECT id, kind, subject, text, related, source_turn, created, updated FROM memories ORDER BY id") {
            MindStore.OldMem(it.long(0), it.text(1), it.text(2), it.text(3), it.text(4), it.long(5), it.long(6), it.long(7)) }
        override fun oldTurns(afterId: Long, limit: Int): List<MindStore.OldTurn> {
            if (++calls > failAfter) throw IllegalStateException("killed mid-copy")
            return s.query("SELECT id, conv, turn, role, content, at, reviewed, kind FROM journal WHERE id>? ORDER BY id LIMIT ?", afterId, limit) {
                MindStore.OldTurn(it.long(0), it.text(1), it.long(2).toInt(), it.text(3), it.text(4), it.long(5), it.long(6).toInt(), it.text(7)) }
        }
        override fun oldTurnCount() = s.query("SELECT count(*) FROM journal") { it.long(0).toInt() }.first()
    }

    private fun oldDb(turns: Int = 1200): OldDb {
        val o = OldDb(File(dir, "mind.db"))
        val t = 1_760_000_000_000L
        o.s.exec("INSERT INTO memories(id, kind, subject, text, related, source_turn, created, updated) VALUES(3,'person','Gloria','Gloria is the user''s cousin in Chingola','Chingola',7,?,?)", t, t + 5000)
        o.s.exec("INSERT INTO memories(id, kind, subject, text, related, source_turn, created, updated) VALUES(8,'preference','food','No pork, on weekdays','',0,?,?)", t + 1000, t + 1000)
        o.s.exec("INSERT INTO memories(id, kind, subject, text, related, source_turn, created, updated) VALUES(12,'profile','You','Studies at Northrise University','',0,?,?)", t + 2000, t + 9000)
        o.s.exec("INSERT INTO meta(key, value) VALUES('mig_chats','1'),('mig_memory','1'),('archived','[\"c1\"]')")
        for (i in 1..turns) o.s.exec("INSERT INTO journal(conv, turn, role, content, at, reviewed, kind) VALUES(?,?,?,?,?,?,?)",
            if (i % 10 == 0) "life_log:2026-10-12" else "main", i, if (i % 2 == 0) "assistant" else "user",
            if (i == 7) "My courier in Lusaka is Yango" else "turn number $i about things", 1_760_000_000L + i, if (i < 100) 1 else 0, if (i % 10 == 0) "life_log" else "chat")
        return o
    }

    private fun backupTo(o: OldDb): () -> String = {
        val out = File(dir, "backup/mind-1.db"); out.parentFile?.mkdirs(); File(dir, "mind.db").copyTo(out, overwrite = true); out.path
    }

    @Test fun migrationCopiesEverythingOnceAndKeepsIds() {
        val o = oldDb(); val st = open(); Mind.use(st)
        var progress = 0
        val r = st.importFrom(o, backupTo(o), onProgress = { n, _ -> progress = n })
        assertEquals(3, r.memories); assertEquals(1200, r.turns); assertEquals(1200, progress)
        assertTrue(st.imported())
        assertTrue(File(dir, "backup/mind-1.db").isFile)
        val g = st.mem(3)!!
        assertEquals(listOf("person", "Gloria", "Gloria is the user's cousin in Chingola", "Chingola"), listOf(g.kind, g.subject, g.text, g.related))
        assertEquals(7L, g.sourceTurn)
        assertEquals("ms → unix seconds in blaze.db", 1_760_000_000L, st.blaze.memory(3)!!.created)
        assertEquals(1_760_000_005L, st.blaze.memory(3)!!.updated)
        assertEquals(1_760_000_005_000L, g.updated)
        assertEquals("meta flags carried over (no second chat import)", "1", st.meta("mig_chats"))
        assertEquals(setOf("c1"), Mind.archived())
        assertTrue(Mind.searchChat("Yango", 5).contains("turn j7 "))
        assertEquals("reviewed flags kept", 1200 - 99, st.unreviewedCount())
        assertEquals(120, MindLogs.count(st, MindLogs.LIFE))
        assertTrue(Mind.promptBlock().contains("- [profile] You: Studies at Northrise University"))
        // Idempotent: a second run copies nothing and makes no second backup.
        var backups = 0
        assertTrue(st.importFrom(o, { backups++; "x" }).skipped)
        assertEquals(0, backups)
        assertEquals(3, st.blaze.countMemories())
        assertEquals(1200, st.count())
        // New memories don't collide with the copied ids.
        assertTrue(Mind.save("fact", "You", "Has a cat called Mo").second > 12)
        // mind.db is left as it was.
        assertEquals(3, o.oldMemories().size); assertEquals(1200, o.oldTurnCount())
    }

    @Test fun interruptedMigrationRetriesCleanly() {
        val o = oldDb(); val st = open()
        o.failAfter = 1   // the process dies after the first journal batch is in
        try { st.importFrom(o, backupTo(o), batch = 500); fail() } catch (e: IllegalStateException) { assertEquals("killed mid-copy", e.message) }
        assertFalse("not marked done", st.imported())
        assertEquals(500, st.count())
        // mind.db changed in between (the app kept using it): the retry picks that up too.
        o.s.exec("UPDATE memories SET text='No pork or beef, on weekdays' WHERE id=8")
        o.s.exec("INSERT INTO journal(conv, turn, role, content, at) VALUES('main', 5000, 'user', 'late turn about Kariba', 1)")
        o.failAfter = Int.MAX_VALUE
        // A reopened file (new launch) carries on.
        sqls.forEach { it.close() }; sqls.clear()
        val again = open()
        var backups = 0
        val r = again.importFrom(o, { backups++; "y" })
        assertEquals("the first backup is reused", 0, backups)
        assertEquals(1201, r.turns)
        assertTrue(again.imported())
        assertEquals(1201, again.count())
        assertEquals("No pork or beef, on weekdays", again.mem(8)!!.text)
        Mind.use(again)
        assertTrue(Mind.searchChat("Kariba", 5).contains("late turn"))
        assertTrue("no duplicate rows in the FTS index", Mind.searchChat("Yango", 20).lines().count { it.startsWith("turn j") } == 1)
        // FTS5 external-content index matches its table.
        sqls.last().exec("INSERT INTO mind_journal_fts(mind_journal_fts) VALUES('integrity-check')")
        sqls.last().exec("INSERT INTO memories_fts(memories_fts) VALUES('integrity-check')")
    }

    @Test fun noBackupNoCopy() {
        val o = oldDb(10); val st = open()
        try { st.importFrom(o, { throw java.io.IOException("disk full") }); fail() } catch (e: java.io.IOException) { }
        assertFalse(st.imported())
        assertEquals(0, st.blaze.countMemories())
    }

    @Test fun freshInstallNeedsNoCopy() {
        val st = open()
        st.markFresh()
        assertTrue(st.imported())
        assertTrue(st.importFrom(oldDb(1), { "z" }).skipped)
    }

    @Test fun newerSchemaFromDesktopIsRefused() {
        val f = File(dir, "newer.db")
        JdbcSql("jdbc:sqlite:" + f.path).apply { exec("PRAGMA user_version = 2"); close() }
        try { open("newer.db"); fail() } catch (e: BlazeDb.NewerSchema) { assertTrue(e.message!!.startsWith("Your memory was saved by a newer version of Blaze")) }
        assertNotNull(Mind.NEWER)
    }

    @Test fun journalQueryIsSafe() {
        assertEquals("\"yango\"* OR \"lusaka\"*", MindStore.journalQuery("Yango, Lusaka!"))
        assertEquals("", MindStore.journalQuery("\" * ( ) :"))
        assertEquals(1_700_000_000L, MindStore.sec(1_700_000_000_000L))
        assertEquals(1_700_000_000L, MindStore.sec(1_700_000_000L))
    }
}
