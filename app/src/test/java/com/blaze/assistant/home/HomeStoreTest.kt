package com.blaze.assistant.home

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.JdbcSql
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v0.29.0: Home widgets + cache in blaze.db: CRUD, ordering, cache kept on failure, idempotent migration, schema v1 untouched. */
class HomeStoreTest {
    private lateinit var dir: File
    private val open = ArrayList<JdbcSql>()

    @Before fun setUp() { dir = Files.createTempDirectory("home").toFile() }
    @After fun tearDown() { open.forEach { try { it.close() } catch (_: Exception) { } }; dir.deleteRecursively() }

    private fun sql(): JdbcSql = JdbcSql("jdbc:sqlite:" + File(dir, "blaze.db").path).also { open += it }

    private fun w(type: String, title: String, cat: String = WidgetTypes.defaultCategory(type)) = Widget("", type, title, cat, "{}")

    @Test fun crudAndOrder() {
        val s = HomeStore(sql())
        val a = s.add(w("weather", "Weather"), now = 1)
        val b = s.add(w("news", "Tech news"), now = 2)
        val c = s.add(w("sports", "Arsenal"), now = 3)
        assertEquals(listOf(a.id, b.id, c.id), s.all().map { it.id })
        assertEquals(listOf(0, 1, 2), s.all().map { it.order })
        assertEquals(3, s.count())

        s.update(b.copy(title = "AI news", size = "large", hidden = true))
        val b2 = s.get(b.id)!!
        assertEquals("AI news", b2.title); assertEquals(WidgetSize.L, b2.size); assertTrue(b2.hidden)

        assertTrue(s.move(c.id, -1))
        assertEquals(listOf(a.id, c.id, b.id), s.all().map { it.id })
        assertFalse("can't move above the top", s.move(a.id, -1))
        s.moveTo(b.id, 0)
        assertEquals(listOf(b.id, a.id, c.id), s.all().map { it.id })
        s.reorder(listOf(c.id, "nope", a.id))
        assertEquals(listOf(c.id, a.id, b.id), s.all().map { it.id })

        s.remove(a.id)
        assertNull(s.get(a.id))
        assertEquals(listOf(0, 1), s.all().map { it.order })
        s.tapped(c.id); s.tapped(c.id)
        assertEquals(2, s.get(c.id)!!.taps)
    }

    @Test fun cacheKeepsLastGoodContentOnFailure() {
        val s = HomeStore(sql())
        val id = s.add(w("prices", "Bitcoin")).id
        assertNull(s.data(id))
        s.saveData(id, WidgetData(headline = "Bitcoin", value = "82,115 USD", change = "▲ 0.3% today"), now = 1_000)
        val ok = s.data(id)!!
        assertEquals("82,115 USD", ok.value); assertEquals(1_000L, ok.fetched); assertEquals("", ok.error)

        s.saveData(id, WidgetData.failed("No connection right now. Showing what I had."), now = 2_000)
        val after = s.data(id)!!
        assertEquals("cached value kept", "82,115 USD", after.value)
        assertEquals("time of the last good fetch kept", 1_000L, after.fetched)
        assertEquals("No connection right now. Showing what I had.", after.error)

        s.saveData(id, WidgetData(headline = "Bitcoin", value = "83,000 USD"), now = 3_000)
        assertEquals("", s.data(id)!!.error); assertEquals(3_000L, s.data(id)!!.fetched)
        s.remove(id)
        assertNull("cache goes with the widget", s.data(id))
    }

    @Test fun firstFailureIsStoredWithoutContent() {
        val s = HomeStore(sql())
        val id = s.add(w("weather", "Weather")).id
        s.saveData(id, WidgetData.failed("Allow location, or type a city, to see the weather.", "location"))
        val d = s.data(id)!!
        assertTrue(d.empty); assertEquals(0L, d.fetched); assertEquals("location", d.action)
    }

    @Test fun migrationIsIdempotentAndLeavesSchemaV1Alone() {
        val sq = sql()
        val db = BlazeDb(sq)
        val before = db.version()
        val s1 = HomeStore(sq)
        s1.add(w("news", "Top stories"))
        // Opening again (next app start) neither fails nor loses anything.
        val s2 = HomeStore(sq)
        val s3 = HomeStore(sq)
        assertEquals(1, s3.count())
        assertEquals(before, db.version())
        assertEquals(1, BlazeDb(sq).version())
        assertEquals(HomeStore.SCHEMA.toString(), s2.meta("schema"))
        // Memories still work next to the Home tables.
        val names = sq.query("SELECT name FROM sqlite_master WHERE type='table'") { it.text(0) }.toSet()
        assertTrue(names.containsAll(setOf("home_widgets", "home_cache", "home_meta", "memories")))
        assertTrue(names.filter { it.startsWith("home") }.all { it.startsWith("home_") })
    }

    @Test fun oldTableWithoutTapsColumnIsUpgraded() {
        val sq = sql()
        sq.exec("CREATE TABLE home_widgets(id TEXT PRIMARY KEY, type TEXT NOT NULL, title TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', config TEXT NOT NULL DEFAULT '{}', size TEXT NOT NULL DEFAULT 'M', ord INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0)")
        sq.exec("INSERT INTO home_widgets(id, type, title) VALUES('w1', 'news', 'Old')")
        val s = HomeStore(sq)
        assertEquals("Old", s.get("w1")!!.title)
        assertEquals(0, s.get("w1")!!.taps)
    }

    @Test fun configRoundTrips() {
        val s = HomeStore(sql())
        val cfg = JSONObject().put("kind", "fx").put("base", "USD").put("symbols", "EUR,ZMW")
        val id = s.add(Widget("", "prices", "USD", "Finance", cfg.toString())).id
        assertEquals("EUR,ZMW", s.get(id)!!.opt("symbols"))
        assertNotNull(HomeStore.newId(5).takeIf { it.startsWith("w") })
    }
}
