package com.blaze.assistant.store

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * blaze.db on the JVM (sqlite-jdbc): the schema is Blaze Desktop's internal/store v1 byte for byte, and the Go
 * store tests (store_test.go, shared_test.go) hold for the Kotlin port.
 */
class BlazeDbTest {
    private lateinit var dir: File
    private val open = ArrayList<Sql>()

    @Before fun setUp() { dir = Files.createTempDirectory("blazedb").toFile() }
    @After fun tearDown() { open.forEach { try { it.close() } catch (_: Exception) { } }; dir.deleteRecursively() }

    private fun sql(name: String = "blaze.db"): JdbcSql = JdbcSql("jdbc:sqlite:" + File(dir, name).path).also { open += it }
    private fun db(name: String = "blaze.db") = BlazeDb(sql(name))

    /** The v1 script exactly as it is in Blaze-dekstop internal/store/store.go (copied into test resources). */
    private val desktopV1: String = javaClass.getResource("/desktop/store_v1.sql")!!.readText()

    private fun master(s: Sql): List<String> =
        s.query("SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name") {
            "${it.text(0)}|${it.text(1)}|${it.text(2)}|${it.text(3)}"
        }

    @Test fun migrationTextIsDesktopsVerbatim() {
        assertEquals(1, BlazeDb.MIGRATIONS.size)
        assertEquals(desktopV1, BlazeDb.MIGRATIONS[0])
    }

    @Test fun schemaMatchesDesktop() {
        // What Go does: the whole v1 script in one Exec, then user_version = 1.
        val go = sql("go.db")
        (go as JdbcSql).conn.createStatement().use { it.executeUpdate(desktopV1) }
        go.exec("PRAGMA user_version = 1")
        val kt = sql("kt.db"); BlazeDb(kt)
        val want = master(go); val got = master(kt)
        assertTrue("Desktop's script created nothing", want.size >= 14)
        assertEquals(want, got)
        assertEquals(1L, kt.query("PRAGMA user_version") { it.long(0) }.first())
        for (t in listOf("memories", "memories_fts", "skills", "skills_fts", "tools", "tools_fts", "active_tools", "meta"))
            assertTrue(t, got.any { it.split('|')[1] == t })
        listOf("memories_ai", "memories_ad", "memories_au", "memories_norm").forEach { n -> assertTrue(n, got.any { it.split('|')[1] == n }) }
        // A file Desktop made opens here without a migration, and the other way round.
        BlazeDb(go)
        assertEquals(1L, go.query("PRAGMA user_version") { it.long(0) }.first())
        assertEquals(want, master(go))
    }

    @Test fun reopenRunsNoMigrationTwice() {
        db().close(); open.clear()
        val d = db()
        assertEquals(1, d.version())
        d.add("Uses pnpm, not npm")
        d.close(); open.clear()
        assertEquals(1, db().countMemories())
    }

    @Test fun newerSchemaIsRefusedAndLeftUntouched() {
        val f = File(dir, "new.db")
        JdbcSql("jdbc:sqlite:" + f.path).apply { exec("CREATE TABLE future(x)"); exec("PRAGMA user_version = 7"); close() }
        val before = f.readBytes()
        try { BlazeDb(sql("new.db")); fail("a newer schema must be refused") } catch (e: BlazeDb.NewerSchema) {
            assertTrue(e.message!!, e.message!!.contains("newer version of Blaze"))
            assertTrue(e.message!!, e.message!!.contains("v7"))
        }
        open.forEach { it.close() }; open.clear()
        assertTrue("the file must be left as it was", before.contentEquals(f.readBytes()))
    }

    @Test fun damagedV1IsRefused() {
        sql("bad.db").apply { exec("CREATE TABLE memories(x)"); exec("PRAGMA user_version = 1") }
        try { db("bad.db"); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("missing table")) }
    }

    @Test fun normAndQueryLikeGo() {
        assertEquals("uses pnpm not npm", BlazeDb.norm("  Uses PNPM, not   npm. "))
        assertEquals("café 2026 ndola", BlazeDb.norm("Café—2026 (Ndola)!"))
        // Newest words first, stopwords and 1-byte words out, duplicates once, every term quoted.
        assertEquals("\"theme\" OR \"homeglow\" OR \"deploy\"", BlazeDb.ftsQuery("how do I deploy the homeglow theme?", 32))
        assertEquals("", BlazeDb.ftsQuery("the and of a", 32))
        assertEquals(2, BlazeDb.tokens("abcdefg"))
        assertEquals(1.0, BlazeDb.similar("a b c", "c b a"), 1e-9)
        // Go: sha1 of name NUL desc NUL group SOH, sorted by name.
        assertEquals("7d68cb19a6d6933b7e73345bcee46930226a97e2", BlazeDb.toolsHash(listOf(BlazeDb.ToolRow("a", "b", "c"))))
        assertEquals(BlazeDb.toolsHash(listOf(BlazeDb.ToolRow("b", "", ""), BlazeDb.ToolRow("a", "", ""))),
            BlazeDb.toolsHash(listOf(BlazeDb.ToolRow("a", "", ""), BlazeDb.ToolRow("b", "", ""))))
    }

    @Test fun searchRanking() {
        val d = db()
        for (f in listOf("Uses pnpm, not npm", "HomeGlow deploys with shopify theme push", "HomeGlow tests run with pnpm test",
            "Prefers short answers without emoji", "Lives in Lusaka, Zambia", "Shopify store HomeGlow sells home lighting"))
            assertEquals(f, BlazeDb.ADDED, d.add(f).second)
        val r = d.search("how do I deploy the homeglow theme?", 3)
        assertEquals("porter: deploy ~ deploys, bm25 best first", "HomeGlow deploys with shopify theme push", r.first().text)
        assertEquals(1, d.search("emoji", 5).size)
        assertTrue(d.search("the and of", 5).isEmpty())
        d.search("\"quotes\" AND (weird*) NEAR: -x ^y", 5)   // FTS syntax is quoted away, never an error
    }

    @Test fun dedupeAndForget() {
        val d = db()
        val (id, st) = d.add("Uses pnpm, not npm"); assertEquals(BlazeDb.ADDED, st)
        assertEquals(id to BlazeDb.EXISTS, d.add("  uses PNPM not npm. "))
        d.add("Favourite editor is Neovim with LazyVim config")
        val (id3, st3) = d.add("Favourite editor is Neovim with the LazyVim config")
        assertEquals(BlazeDb.UPDATED, st3)
        assertTrue(d.memory(id3)!!.text.contains("the LazyVim"))
        assertEquals(BlazeDb.ADDED, d.add("Favourite drink is coffee").second)
        assertEquals(3, d.countMemories())
        assertEquals(2, d.forgetMatching("favourite").size)
        assertTrue(d.delete(id)); assertEquals(0, d.countMemories())
        assertFalse("deleting twice finds nothing", d.delete(id))
        assertTrue("the FTS index follows deletes", d.search("pnpm", 5).isEmpty())
    }

    @Test fun forPromptPinnedThenMatch() {
        val d = db()
        val pid = d.add("Name is Sam").first; d.setPinned(pid, true)
        for (i in 0 until 50) d.add("Filler fact number " + "x".repeat(i % 7 + 1) + " about topic" + ('a' + i % 26) + ('a' + i / 26))
        d.add("HomeGlow deploys with shopify theme push")
        val got = d.forPrompt("deploy homeglow", 8, 600)
        assertEquals(pid, got[0].id)
        assertEquals("HomeGlow deploys with shopify theme push", got[1].text)
        assertTrue(got.sumOf { BlazeDb.tokens(it.text) + 2 } <= 600)
        assertTrue("picked rows are touched", d.memory(pid)!!.uses >= 1)
    }

    @Test fun skillsAndToolsIndex() {
        val d = db()
        d.replaceSkills(listOf(BlazeDb.Skill("deploy-shopify", "Push a Shopify theme", "/s/a/SKILL.md", "steps"),
            BlazeDb.Skill("write-email", "Draft an email in the user's voice", "/s/b/SKILL.md", "shopify mentioned once")))
        assertEquals("deploy-shopify", d.searchSkills("shopify deploy", 5).first().name)
        assertEquals(2, d.skillMtimes().size)
        assertEquals("steps", d.skillBody("/s/a/SKILL.md"))
        d.syncTools(listOf(BlazeDb.ToolRow("web_search", "Search the web", "web"), BlazeDb.ToolRow("memory_save", "Save a memory", "memory")))
        val h = d.meta("tools_hash"); assertEquals(40, h.length)
        d.syncTools(listOf(BlazeDb.ToolRow("memory_save", "Save a memory", "memory"), BlazeDb.ToolRow("web_search", "Search the web", "web")))
        assertEquals("same set, same hash, no rebuild", h, d.meta("tools_hash"))
        assertEquals("web_search", d.searchTools("search the web", 3).first())
        d.activate("c1", "web_search"); assertEquals(listOf("web_search"), d.active("c1"))
    }

    @Test fun replaceAllKeepsRows() {
        val d = db()
        val keep = d.add("Likes tea").first; d.add("Old fact")
        d.replaceAll(listOf("likes tea", "New fact", "New fact"))
        assertEquals(listOf("Likes tea", "New fact"), d.memories().map { it.text })
        assertNotNull(d.memory(keep))
    }
}
