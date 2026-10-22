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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.28.5 brief cap: the brief never passes 600 tokens (editor cut, update_brief squeeze + top part, migration), nothing
 * is lost (the whole text and earlier briefs become notes), and a project's full instructions / brief / notes are chunked
 * into blaze.db (`project_docs` + FTS5) and reached with project_docs_search / project_docs_read, per project.
 */
class ProjectDocsTest {
    private val sqls = ArrayList<Sql>()

    @After fun tearDown() { Projects.use(emptyList()); ProjectDocs.use(null); sqls.forEach { try { it.close() } catch (_: Exception) { } } }

    private fun store(): ProjectDocs.Store {
        val s = JdbcSql().also { sqls += it }; val meta = HashMap<String, String>()
        return ProjectDocs.Store(s, { meta[it].orEmpty() }, { k, v -> meta[k] = v })
    }

    private fun long(word: String, chars: Int = 20_000): String {
        val sb = StringBuilder("Purpose: ship the $word app.\nRules: never force-push main.\n")
        var i = 0
        while (sb.length < chars) { sb.append("Detail $i about $word: module$i keeps option${i * 7} and note${i * 3} for later.\n"); i++ }
        return sb.append("Tail marker quokkaharbour for $word.").toString()
    }

    @Test fun fitKeepsTheTopWithinTheCap() {
        val f = Projects.fit(long("alpha"))
        assertTrue(f.trimmed)
        assertTrue("${BlazeDb.tokens(f.text)} tokens", BlazeDb.tokens(f.text) <= Projects.BRIEF_TOKENS)
        assertTrue(f.text.startsWith("Purpose: ship the alpha app.\nRules: never force-push main."))
        assertFalse(f.text.contains("quokkaharbour"))
        // Whitespace alone never costs words.
        val spaced = (1..200).joinToString("\n\n\n") { "rule $it    keep   it" }
        val g = Projects.fit(spaced)
        if (BlazeDb.tokens(Projects.squeeze(spaced)) <= Projects.BRIEF_TOKENS) { assertFalse(g.trimmed); assertTrue(g.text.contains("rule 200 keep it")) }
        // A short brief is kept exactly.
        assertEquals("Purpose: x", Projects.fit("  Purpose: x \n").text)
        // One giant line and multi-byte text still fit.
        assertTrue(BlazeDb.tokens(Projects.fit("é".repeat(5_000)).text) <= Projects.BRIEF_TOKENS)
        assertTrue(BlazeDb.tokens(Projects.fit("x".repeat(9_000)).text) <= Projects.BRIEF_TOKENS)
    }

    @Test fun editorCutNeverPassesTheCap() {
        assertEquals("short", Projects.cutTokens("short", Projects.BRIEF_TOKENS))
        val cut = Projects.cutTokens("😀".repeat(2_000), Projects.BRIEF_TOKENS)
        assertTrue(BlazeDb.tokens(cut) <= Projects.BRIEF_TOKENS)
        assertEquals(0, cut.length % 2)   // never half a surrogate pair
        Projects.use(listOf(Project("p1", "One", chats = listOf("c1"))))
        Projects.setBrief("p1", long("beta"))
        assertTrue(BlazeDb.tokens(Projects.get("p1")!!.brief) <= Projects.BRIEF_TOKENS)
    }

    @Test fun updateBriefCompressesAndKeepsEverythingAsNotes() {
        Projects.use(listOf(Project("p1", "One", chats = listOf("c1"), brief = "Purpose: old brief text")))
        val full = long("gamma")
        val reply = Projects.updateBrief("p1", full, now = 1_000L)
        val p = Projects.get("p1")!!
        assertTrue(BlazeDb.tokens(p.brief) <= Projects.BRIEF_TOKENS)
        assertTrue(p.brief.startsWith("Purpose: ship the gamma app."))
        assertTrue(reply, reply.contains("cap is 600"))
        assertTrue(p.notes.any { it.text == "Purpose: old brief text" })
        assertTrue(p.notes.any { it.text == full.trim() })
        // A brief that fits: saved as is, the one it replaced kept.
        val r2 = Projects.updateBrief("p1", "Purpose: new\nRules: short", now = 2_000L)
        assertTrue(r2, r2.contains("updated"))
        assertEquals("Purpose: new\nRules: short", Projects.get("p1")!!.brief)
        assertTrue(Projects.get("p1")!!.notes.size >= 3)
    }

    @Test fun migrationMovesOldLongBriefsIntoNotes() {
        val big = long("delta", 9_000)
        val list = listOf(Project("a", "A", brief = big), Project("b", "B", brief = "Purpose: fine"))
        val out = Projects.migrate(list, 5L)
        assertTrue(BlazeDb.tokens(out[0].brief) <= Projects.BRIEF_TOKENS)
        assertEquals(big, out[0].notes.single().text)
        assertEquals(list[1], out[1])
        assertEquals(out, Projects.migrate(out, 6L))   // idempotent
        // JSON: notes survive a round trip; JSON from v0.28.4 (no notes) still parses.
        assertEquals(out, Projects.parse(Projects.toJson(out)))
        val old = JSONArray().put(JSONObject().put("id", "x").put("name", "X").put("instructions", "i").put("brief", "b").put("chats", JSONArray()))
        val parsed = Projects.parse(old.toString()).single()
        assertEquals("b", parsed.brief); assertTrue(parsed.notes.isEmpty())
    }

    @Test fun docsAreChunkedSearchedAndReadPerProject() {
        val st = store()
        val a = Project("a", "A", instructions = long("alpha"), brief = "Purpose: alpha brief", notes = listOf(ProjectNote(1L, "Earlier brief", "Old plan mentions wombatcove.")))
        val b = Project("b", "B", instructions = "Other project with quokkaharbour too.")
        assertTrue(st.ensure(a)); assertTrue(st.ensure(b))
        assertFalse(st.ensure(a))   // nothing changed: no reindex
        val chunks = ProjectDocs.chunksOf(a)
        assertTrue(chunks.size > 5)
        assertTrue(chunks.all { BlazeDb.tokens(it.text) <= ProjectDocs.MAX_TOKENS + 20 })
        assertEquals(chunks.size, st.count("a"))

        val hits = st.search("a", "quokkaharbour", 4)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.id.startsWith("instructions#") })
        val full = st.read("a", hits.first().id)
        assertNotNull(full); assertTrue(full!!.text.contains("quokkaharbour"))
        assertEquals("note1#1", st.search("a", "wombatcove", 4).first().id)
        // Other projects' docs never show up.
        assertTrue(st.search("b", "wombatcove", 4).isEmpty())
        assertTrue(st.search("b", "quokkaharbour", 4).all { st.read("b", it.id)!!.project == "b" })
        // A change reindexes; the old text is gone.
        assertTrue(st.ensure(a.copy(instructions = "Now about narwhalbay only.")))
        assertTrue(st.search("a", "quokkaharbour", 4).isEmpty())
        assertTrue(st.search("a", "narwhalbay", 4).isNotEmpty())
        st.drop("a"); assertEquals(0, st.count("a"))
    }

    @Test fun toolsWorkThroughRunAndOnlyInProjects() {
        val st = store()
        val p = Project("a", "A", instructions = long("echo"))
        val out = ProjectDocs.run(st, p, ProjectDocs.TOOL_SEARCH, JSONObject().put("query", "quokkaharbour"))
        assertTrue(out, out.contains("[instructions#"))
        val id = Regex("\\[(instructions#\\d+)]").find(out)!!.groupValues[1]
        assertTrue(ProjectDocs.run(st, p, ProjectDocs.TOOL_READ, JSONObject().put("id", id)).contains("quokkaharbour"))
        assertTrue(ProjectDocs.run(st, p, ProjectDocs.TOOL_SEARCH, JSONObject().put("query", "")).contains("Instructions"))
        assertTrue(ProjectDocs.run(st, null, ProjectDocs.TOOL_SEARCH, JSONObject().put("query", "x")).contains("isn't in a project"))
        // No database: plain matching still answers.
        assertTrue(ProjectDocs.run(null, p, ProjectDocs.TOOL_SEARCH, JSONObject().put("query", "quokkaharbour")).contains("quokkaharbour"))

        val all = JSONArray().apply { ProjectDocs.tools().forEach { put(it) }; put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "web_search"))) }
        val stripped = ProjectDocs.strip(all)
        assertEquals(1, stripped.length())
        assertEquals("web_search", stripped.getJSONObject(0).getJSONObject("function").getString("name"))
    }
}
