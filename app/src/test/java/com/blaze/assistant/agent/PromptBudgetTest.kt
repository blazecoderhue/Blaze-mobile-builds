package com.blaze.assistant.agent

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.JdbcSql
import com.blaze.assistant.store.Sql
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.28.4 audit: the prompt carries a pinned core + a subject index + lookup tools, never the whole memory or the docs.
 * With 500 memories and every doc indexed in blaze.db, the memory/docs part of the system prompt grows by far less
 * than the 6k-token budget compared with an empty store, and none of the docs text or the unpinned memories is in it.
 * Skills: only the matched ones are injected, within SkillMatch's budget; core skills stay within theirs.
 */
class PromptBudgetTest {
    private val sqls = ArrayList<Sql>()

    @After fun tearDown() { Mind.use(null); BlazeDocs.use(null); Projects.use(emptyList()); ProjectDocs.use(null); sqls.forEach { try { it.close() } catch (_: Exception) { } } }

    private fun first(vararg paths: String): File? = paths.map { File(it) }.firstOrNull { it.isFile }

    private fun docFiles(): List<Pair<String, String>> {
        val dir = System.getProperty("blaze.docs.dir")?.let { File(it) }?.takeIf { File(it, "manifest.json").isFile }
        if (dir != null) {
            val names = JSONObject(File(dir, "manifest.json").readText()).getJSONArray("files")
            return (0 until names.length()).map { names.getString(it) }.map { it to File(dir, it).readText() }
        }
        return listOfNotNull(
            first("blaze-docs/about.md", "app/blaze-docs/about.md")?.let { "about.md" to it.readText() },
            first("../README.md", "README.md")?.let { "readme.md" to it.readText() },
            first("../STATUS.md", "STATUS.md")?.let { "status.md" to it.readText() },
            first("../CHANGELOG.md", "CHANGELOG.md")?.let { "changelog/all.md" to it.readText() },
        )
    }

    /** The memory + docs part of the system prompt, exactly as Agent.systemPrompt builds it (Memory.promptBlock + the docs line). */
    private fun dynamicPart(q: String) = Memory.promptBlock(q) + BlazeDocs.PROMPT_LINE

    @Test fun fiveHundredMemoriesAndAllDocsStayOutOfThePrompt() {
        val sql = JdbcSql().also { sqls += it }
        val blaze = BlazeDb(sql); val mind = MindStore(blaze, sql)
        Mind.use(mind)
        val q = "where is your repo and what do you know about my sister"
        val empty = dynamicPart(q)

        val kinds = Mind.KINDS
        val texts = ArrayList<String>()
        for (i in 0 until 500) {
            val k = kinds[i % kinds.size]
            val t = "Note $i for subject$i: prefers option${i * 7} on weekday${i % 5} with detail${i * 13} and place${i * 3}"
            val (r, _) = mind.save(k, "Subject $i", t)
            assertEquals("saved", r)
            texts += t
        }
        assertEquals(500, mind.all().size)
        val docs = BlazeDocs.Store(sql, { blaze.meta(it) }, { k, v -> blaze.setMeta(k, v) })
        val tools = JSONArray().apply { BlazeDocs.tools().forEach { put(it) } }
        assertTrue(BlazeDocs.ensure(docs, "h", "test", { docFiles() }, tools))
        BlazeDocs.use(docs)
        assertTrue(docs.count() > 20)

        val full = dynamicPart(q)
        val growth = BlazeDb.tokens(full) - BlazeDb.tokens(empty)
        assertTrue("grew by $growth tokens", growth < 6_000)
        // Much tighter in practice: the core is capped at CORE_MAX chars, the index at INDEX_PER_KIND subjects per kind.
        assertTrue("grew by $growth tokens", growth < 1_500)
        assertTrue(full.length < Mind.CORE_MAX + 14 * 40 * Mind.KINDS.size + 2_000)
        // Not dumped: only pinned-core lines appear (profile + preference), the index says "+N more", no docs text.
        val shown = texts.count { full.contains(it) }
        assertTrue("$shown memories in the prompt", shown <= Mind.CORE_PROFILE + Mind.CORE_PREFS)
        assertTrue(full.contains("more"))
        assertTrue(full.contains("memory_search"))
        val docLines = docFiles().flatMap { it.second.lines() }.map { it.trim() }.filter { it.length > 60 }
        assertTrue(docLines.size > 50)
        assertFalse("a docs line is in the prompt", docLines.any { full.contains(it) && !BlazeDocs.PROMPT_LINE.contains(it) })
        // The docs line is the same, whatever is indexed.
        assertEquals(BlazeDocs.PROMPT_LINE, full.substring(full.length - BlazeDocs.PROMPT_LINE.length))
        assertTrue(BlazeDocs.PROMPT_LINE.contains(BlazeDocs.REPO_URL))
    }

    /**
     * v0.28.5: a project with a 20k-char brief and 20k-char instructions adds at most ~700 tokens to the prompt (the top
     * of each within 600, a header and one lookup line), none of the tail text, and the tail is found by project_docs_search.
     */
    @Test fun aHugeProjectBriefAndInstructionsStayOutOfThePrompt() {
        fun big(word: String): String {
            val sb = StringBuilder("Purpose: the $word project.\nRules: be brief.\n")
            var i = 0
            while (sb.length < 20_000) { sb.append("Line $i of $word: keep setting$i at value${i * 11} and file$i.kt untouched.\n"); i++ }
            return sb.append("Last line of $word: platypusreef$word.").toString()
        }
        val brief = big("brief"); val ins = big("ins")
        assertTrue(brief.length >= 20_000 && ins.length >= 20_000)
        // As stored before v0.28.5 (unmigrated) and after the migration: both pin within the budget.
        val raw = Project("p1", "Big", instructions = ins, chats = listOf("c1"), brief = brief)
        for (p in listOf(raw, Projects.migrate(listOf(raw), 1L).single())) {
            Projects.use(listOf(p))
            val none = Projects.promptLine("other-chat")
            assertEquals("", none)
            val block = Projects.promptLine("c1")
            val t = BlazeDb.tokens(block)
            assertTrue("project adds $t tokens", t <= 700)
            assertTrue(block.contains("Purpose: the ins project."))
            assertTrue(block.contains("Purpose: the brief project."))
            assertTrue(block.contains(Projects.LOOKUP_LINE))
            assertFalse(block.contains("platypusreef"))
            val tail = (brief.lines() + ins.lines()).takeLast(40)
            assertFalse(tail.any { it.length > 20 && block.contains(it) })
        }
        // An empty project costs one short line.
        assertTrue(BlazeDb.tokens(Projects.pinned(Project("p2", "Small"))) < 10)
        // The tail is still reachable.
        val sql = JdbcSql().also { sqls += it }; val meta = HashMap<String, String>()
        val docs = ProjectDocs.Store(sql, { meta[it].orEmpty() }, { k, v -> meta[k] = v })
        val p = Projects.migrate(listOf(raw), 1L).single()
        assertTrue(docs.ensure(p))
        assertTrue(docs.search(p.id, "platypusreefins", 3).isNotEmpty())
        assertTrue(docs.search(p.id, "platypusreefbrief", 3).isNotEmpty())   // the full brief lives on as a note
    }

    @Test fun skillsAreLookedUpNotPasted() {
        val root = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }
        val cands = root.listFiles()!!.filter { File(it, "SKILL.md").isFile }.mapNotNull { SkillMatch.fromDir(it, "bundled", true) }
        assertTrue(cands.size > 20)
        val all = cands.sumOf { it.body.length }
        // The broadest request still brings at most MAX_FULL skills, within the budget (plus their headers).
        val broad = "build a website app with code and research a design plan, email, data analysis, flashcards, debugging, review, github repo"
        val block = SkillMatch.block(SkillMatch.match(broad, cands))
        assertTrue("${block.length} chars", block.length <= SkillMatch.BUDGET_CHARS + SkillMatch.MAX_FULL * 120 + 400)
        assertTrue("all skills are $all chars; one turn gets ${block.length}", block.length * 5 < all)
        // The always-on lookup line is a few short descriptions, not the library.
        val lookup = SkillIndex.promptBlock(SkillMatch.indexOf(cands), broad)
        assertTrue("${lookup.length} chars", lookup.length < 1_200)
        // Core skills' pinned rules stay within their budget.
        val core = CoreSkills.block(CoreSkills.NAMES.associateWith { File(root, "$it/SKILL.md").readText() })
        assertTrue("${core.length} chars", core.length <= CoreSkills.BUDGET_CHARS)
    }
}
