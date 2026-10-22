package com.blaze.assistant.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** mind.db on Robolectric's real SQLite: migration idempotency, recall, dedupe, journal search, idle review. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class MindDbTest {
    private lateinit var db: Mind.Db

    @Before fun open() {
        NativeSqlite.assume()
        db = Mind.Db(ApplicationProvider.getApplicationContext(), null)   // in-memory
        db.writableDatabase
        Mind.use(db)
    }

    @After fun close() { Mind.use(null); if (::db.isInitialized) db.close() }

    private fun chat(vararg lines: String): JSONObject {
        val h = JSONArray()
        lines.forEachIndexed { i, t -> h.put(JSONObject().put("role", if (i % 2 == 0) "user" else "assistant").put("content", t)) }
        return JSONObject().put("history", h).put("updated", 1_700_000_000_000L)
    }

    @Test fun migrationIsIdempotentAndLosesNothing() {
        val chats = mapOf(
            "c1" to chat("My courier in Lusaka is Yango", "Noted.", "Book it for Friday", "Booked."),
            "c2" to chat("I study at Northrise University", "Great."),
        )
        val facts = listOf("Sister is Mary, lives in Ndola", "Prefers short answers")
        val first = Mind.migrate(db, chats.keys.toList(), { chats[it] }, facts)
        assertEquals(6, first.first)
        assertEquals(2, first.second)
        assertEquals(6, db.count())
        assertEquals(6, db.unreviewedCount())
        assertEquals(setOf("c1", "c2"), Mind.archived())
        // A second run (or a crash halfway and a retry) adds nothing.
        db.setMeta("mig_chats", ""); db.setMeta("mig_memory", "")
        val again = Mind.migrate(db, chats.keys.toList(), { chats[it] }, facts)
        assertEquals(0, again.first)
        assertEquals(0, again.second)
        assertEquals(6, db.count())
        assertEquals(2, db.all().size)
        // Old chats are in the journal, word for word.
        val hits = db.searchJournal("courier Lusaka", null, 5)
        assertTrue(hits.isNotEmpty())
        assertEquals("c1", hits.first().conv)
        assertEquals(0, hits.first().turn)
        assertTrue(Mind.searchChat("Yango", 5).contains("c1"))
        val id = hits.first().id
        assertTrue(Mind.readTurn("j$id")!!.contains("Yango"))
    }

    @Test fun recallFindsBySubjectAndWords() {
        Mind.save("person", "Gloria", "Gloria is the user's cousin in Chingola")
        Mind.save("preference", "food", "No pork, on weekdays")
        Mind.save("project", "HomeGlow", "Shopify store selling lamps; launch planned for November")
        assertEquals("Gloria", db.search("gloria")[0].subject)
        assertEquals("HomeGlow", db.search("lamps store")[0].subject)
        assertEquals(1, db.search("pork", "preference").size)
        assertTrue(db.search("pork", "person").isEmpty())
        assertTrue(Mind.run("memory_search", JSONObject().put("query", "cousin")).contains("Gloria"))
    }

    @Test fun dedupeNewestWinsAndSecretsAreRefused() {
        val (a, id) = Mind.save("profile", "You", "Lives in Ndola, Zambia")
        assertEquals("saved", a)
        assertEquals("same" to id, Mind.save("profile", "you", "lives in ndola, zambia"))
        assertEquals("updated" to id, Mind.save("profile", "You", "Lives in Lusaka, Zambia"))
        assertEquals(1, db.all().size)
        assertEquals("Lives in Lusaka, Zambia", db.mem(id)!!.text)
        assertTrue(db.search("Ndola").isEmpty())
        assertEquals("Lusaka", db.search("Lusaka")[0].text.split(" ")[2].removeSuffix(","))
        assertEquals("secret", Mind.save("fact", "bank", "My PIN is 4455").first)
        assertTrue(Mind.run("memory_save", JSONObject().put("kind", "preference").put("subject", "travel")
            .put("text", "Window seats on work trips").put("inferred", true)).startsWith("Saved"))
        assertTrue(db.search("window").first().text.endsWith("(inferred)"))
        val upd = db.search("window").first().id
        assertTrue(Mind.run("memory_update", JSONObject().put("id", upd).put("text", "Aisle seats on work trips")).startsWith("Updated"))
        assertEquals("Aisle seats on work trips", db.mem(upd)!!.text)
    }

    @Test fun journalUpdatesARetriedTurn() {
        val h = chat("What's the plan?", "Plan A").getJSONArray("history")
        Mind.journal("main", h)
        assertEquals(2, db.count())
        h.put(1, JSONObject().put("role", "assistant").put("content", "Plan B"))
        Mind.journal("main", h)
        assertEquals(2, db.count())
        assertTrue(db.searchJournal("Plan B", "main", 5).isNotEmpty())
    }

    @Test fun idleReviewMarksTurnsAndSaves() = runBlocking {
        val chats = mapOf("c1" to chat("I'm Luke and I study computer science at Northrise", "Nice to meet you, Luke."))
        Mind.migrate(db, listOf("c1"), { chats[it] }, emptyList())
        assertEquals(2, db.unreviewedCount())
        // A failed model call leaves the turns queued.
        assertEquals(0, MindReview.review(3) { null })
        assertEquals(2, db.unreviewedCount())
        val n = MindReview.review(3) { "[{\"kind\":\"profile\",\"subject\":\"You\",\"text\":\"Studies computer science at Northrise University\"}]" }
        assertEquals(2, n)
        assertEquals(0, db.unreviewedCount())
        assertEquals(1, db.search("Northrise").size)
        // Nothing left: the next pass does nothing.
        assertEquals(0, MindReview.review(3) { "[]" })
    }
}
