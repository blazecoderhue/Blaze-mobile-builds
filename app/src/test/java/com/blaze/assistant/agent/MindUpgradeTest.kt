package com.blaze.assistant.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.JdbcSql
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * v0.26.3 on the app's own start-up path (Robolectric's SQLite for the old mind.db, sqlite-jdbc standing in for
 * the bundled SQLite): the first launch backs up mind.db and moves the mind into blaze.db with nothing lost; the next
 * launch goes straight to blaze.db; a blaze.db that can't open, or one from a newer Blaze, leaves Blaze on mind.db
 * with a plain line. Also: the same memories give the same prompt and tool output on both stores.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class MindUpgradeTest {
    private lateinit var ctx: Context
    private val defaultOpen = Mind.openBlaze

    private val blazeFile get() = File(ctx.filesDir, "blaze.db")
    private fun backups() = File(ctx.filesDir, "backup").listFiles()?.filter { it.name.startsWith("mind-") && it.name.endsWith(".db") } ?: emptyList()

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Mind.resetForTests()
        Mind.openBlaze = { JdbcSql("jdbc:sqlite:" + it.path) }
        ctx.deleteDatabase("mind.db")
        listOf("", "-wal", "-shm").forEach { File(blazeFile.path + it).delete() }
        File(ctx.filesDir, "backup").deleteRecursively()
    }

    @After fun tearDown() {
        try { Mind.db()?.close() } catch (_: Throwable) { }
        Mind.resetForTests()
        Mind.openBlaze = defaultOpen
    }

    private val t0 = 1_760_000_000_000L

    /** The same memories and journal, written through the Store API (so it works on either store). */
    private fun fill(d: Mind.Store) {
        d.save("person", "Gloria", "Gloria is the user's cousin in Chingola", "Chingola", 0, t0)
        d.save("preference", "food", "No pork, on weekdays", "", 0, t0 + 1000)
        d.save("profile", "You", "Lives in Ndola, Zambia", "", 0, t0 + 2000)
        d.save("profile", "You", "Studies at Northrise University", "", 0, t0 + 3000)
        d.save("project", "HomeGlow", "Shopify store selling lamps; launch planned for November", "", 0, t0 + 4000)
        d.save("profile", "You", "Lives in Lusaka, Zambia", "", 0, t0 + 5000)   // rewrites the Ndola line
        val h = JSONArray()
        listOf("My courier in Lusaka is Yango", "Noted.", "Book it for Friday", "Booked.").forEachIndexed { i, t ->
            h.put(JSONObject().put("role", if (i % 2 == 0) "user" else "assistant").put("content", t)) }
        d.journal(Mind.MAIN, h, 1_760_000_000L, false)
        d.logAppend("life_log:2026-10-12", "heard", "Heard nearby: we need milk", 100, MindLogs.LIFE)
        d.setMeta("mig_chats", "1")
    }

    private fun waitFor(name: String) {
        val until = System.currentTimeMillis() + 15_000
        while (Mind.storeName != name && System.currentTimeMillis() < until) Thread.sleep(25)
        assertEquals(name, Mind.storeName)
    }

    @Test fun firstLaunchBacksUpAndMovesEverything() {
        NativeSqlite.assume()
        val old = Mind.Db(ctx); old.writableDatabase
        fill(old)
        val oldMems = old.all().map { listOf(it.id, it.kind, it.subject, it.text, it.related) }
        val oldBlock = Mind.block(old.all())
        old.close()

        Mind.init(ctx)
        waitFor("blaze.db")
        assertEquals(1, backups().size)
        assertTrue("mind.db is kept", ctx.getDatabasePath("mind.db").exists())
        assertTrue(blazeFile.exists())
        assertEquals(oldMems, Mind.all().map { listOf(it.id, it.kind, it.subject, it.text, it.related) })
        assertEquals("the same prompt block", oldBlock, Mind.promptBlock())
        assertTrue(Mind.run("memory_search", JSONObject().put("query", "cousin")).contains("Gloria"))
        assertTrue(Mind.searchChat("Yango", 5).contains("main #0"))
        assertTrue(Mind.searchChat("milk", 5).contains("life_log:2026-10-12"))
        assertEquals("1", Mind.db()!!.meta("mig_chats"))
        // remember / recall after the update.
        assertTrue(Memory.add("Has a cat called Mo").startsWith("Remembered") || Mind.all().any { it.text == "Has a cat called Mo" })
        assertTrue(Memory.recall("cat").contains("Mo"))
        // The backup is a readable copy of mind.db.
        val copy = JdbcSql("jdbc:sqlite:" + backups().first().path)
        assertEquals(oldMems.size.toLong(), copy.query("SELECT count(*) FROM memories") { it.long(0) }.first())
        copy.close()

        // Next launch: straight to blaze.db, no second copy or backup.
        Mind.db()!!.close(); Mind.resetForTests()
        Mind.init(ctx)
        assertEquals("blaze.db", Mind.storeName)
        assertEquals(1, backups().size)
        assertTrue(Mind.all().any { it.text == "Has a cat called Mo" })
        // The old file never got the new memory.
        val again = Mind.Db(ctx)
        assertFalse(again.all().any { it.text == "Has a cat called Mo" })
        again.close()
    }

    @Test fun blazeDbThatCantOpenKeepsMindDb() {
        NativeSqlite.assume()
        val old = Mind.Db(ctx); old.writableDatabase; fill(old); old.close()
        Mind.openBlaze = { throw java.io.IOException("no bundled SQLite") }
        Mind.init(ctx)
        assertEquals("mind.db", Mind.storeName)
        assertEquals(Mind.UNAVAILABLE, Mind.notice)
        assertTrue(Mind.run("memory_search", JSONObject().put("query", "Lusaka")).contains("Lives in Lusaka"))
        assertEquals("saved", Mind.save("fact", "You", "Has a cat called Mo").first)
        assertTrue(backups().isEmpty())
    }

    @Test fun newerBlazeDbIsLeftAloneWithAPlainLine() {
        NativeSqlite.assume()
        val old = Mind.Db(ctx); old.writableDatabase; fill(old); old.close()
        JdbcSql("jdbc:sqlite:" + blazeFile.path).apply { exec("CREATE TABLE future(x)"); exec("PRAGMA user_version = 5"); close() }
        val before = blazeFile.readBytes()
        Mind.init(ctx)
        assertEquals("mind.db", Mind.storeName)
        assertEquals(Mind.NEWER, Mind.notice)
        assertTrue(Mind.run("memory_search", JSONObject().put("query", "cousin")).contains("Gloria"))
        assertTrue("blaze.db untouched", before.contentEquals(blazeFile.readBytes()))
    }

    @Test fun freshInstallStartsOnBlazeDb() {
        Mind.init(ctx)
        assertEquals("blaze.db", Mind.storeName)
        assertFalse("no mind.db is made", ctx.getDatabasePath("mind.db").exists())
        assertEquals("saved", Mind.save("profile", "You", "Lives in Kitwe").first)
    }

    @Test fun bothStoresAnswerTheSame() {
        NativeSqlite.assume()
        val old = Mind.Db(ctx, null); old.writableDatabase
        val s = JdbcSql()
        val neu = MindStore(BlazeDb(s), s)
        fill(old); fill(neu)
        fun outputs(d: Mind.Store): List<String> {
            Mind.use(d)
            return listOf(Mind.promptBlock(),
                Mind.run("memory_search", JSONObject().put("query", "cousin")),
                Mind.run("memory_search", JSONObject().put("query", "Lusaka")),
                Mind.run("memory_search", JSONObject().put("query", "food")),
                Mind.run("memory_search", JSONObject().put("query", "nothing like this")),
                Mind.searchChat("Yango", 5), Mind.searchChat("Friday", 5), Mind.readTurn("j1") ?: "")
        }
        assertEquals(outputs(old), outputs(neu))
        Mind.use(null); old.close(); neu.close()
    }
}
