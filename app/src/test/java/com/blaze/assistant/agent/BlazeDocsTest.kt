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
 * v0.28.4 blaze-docs: chunking by heading (300–800 tokens, doc + section path + version), the idempotent reindex in
 * blaze.db next to the memories, bm25 search ("repo" → the exact URL), the tool reference, no secrets in what ships,
 * and the GitHub near-miss fix from Luke's screenshot.
 */
class BlazeDocsTest {
    private val sqls = ArrayList<Sql>()

    @After fun tearDown() { BlazeDocs.use(null); sqls.forEach { try { it.close() } catch (_: Exception) { } } }

    private fun sql(): JdbcSql = JdbcSql().also { sqls += it }

    private fun store(s: Sql = sql()): BlazeDocs.Store {
        val meta = HashMap<String, String>()
        return BlazeDocs.Store(s, { meta[it].orEmpty() }, { k, v -> meta[k] = v })
    }

    private fun first(vararg paths: String): File? = paths.map { File(it) }.firstOrNull { it.isFile }

    /** The docs as they ship (Gradle's generator output), or the repo's own files when run outside Gradle. */
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

    private fun tool(name: String, desc: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc))
    private fun sampleTools() = JSONArray().apply {
        BlazeDocs.tools().forEach { put(it) }
        put(tool("web_search", "Search the web for anything. Returns results with links."))
        put(tool("browser_open", "Open a web page (or search words) in Blaze's own built-in browser, in the background. No screen control needed."))
        put(tool("memory_search", "Search what you know about the user."))
    }

    // ---------------- chunking ----------------

    @Test fun chunksFollowHeadingsAndSizes() {
        val big = (1..400).joinToString(" ") { "Sentence number $it explains one more detail about installing Blaze on a phone." }
        val md = "# Guide\nIntro line.\n\n## Install\n$big\n\n## Tiny one\nShort.\n\n## Tiny two\nAlso short.\n\n```\n# not a heading\n```\n\n### Deep\nDeep text."
        val cs = BlazeDocs.chunk("guide", md, "9.9.9")
        assertTrue(cs.size >= 4)
        cs.forEach { assertTrue("${it.id} has ${BlazeDocs.tokens(it.text)} tokens", BlazeDocs.tokens(it.text) <= BlazeDocs.MAX_TOKENS) }
        assertEquals((1..cs.size).map { "guide#$it" }, cs.map { it.id })
        assertTrue(cs.all { it.doc == "guide" && it.version == "9.9.9" })
        // The long section is split, every part names its heading and section path.
        val install = cs.filter { it.section.contains("Guide > Install") }
        assertTrue(install.size >= 3)
        assertTrue(install.drop(1).all { it.text.startsWith("## Install (continued)") })
        // Small neighbours are merged, keeping every heading in the section path; a fenced "# …" is not a heading.
        val small = cs.last()
        assertTrue(small.section, small.section.contains("Tiny one") && small.section.contains("Tiny two"))
        assertTrue(small.text.contains("# not a heading"))
        assertFalse(cs.any { it.section.contains("not a heading") })
    }

    @Test fun realDocsChunkWithinBudget() {
        val files = docFiles()
        assertTrue("docs found", files.any { it.first == "about.md" })
        val cs = BlazeDocs.chunksOf(files, "test", sampleTools())
        assertTrue(cs.size > 20)
        assertEquals(cs.size, cs.map { it.id }.toSet().size)
        cs.forEach { assertTrue("${it.id}: ${BlazeDocs.tokens(it.text)} tokens", BlazeDocs.tokens(it.text) <= BlazeDocs.MAX_TOKENS) }
        // Most chunks are real sections, not crumbs (only a doc's last piece may be small).
        val docs = cs.groupBy { it.doc }
        val crumbs = docs.values.sumOf { l -> l.dropLast(1).count { BlazeDocs.tokens(it.text) < BlazeDocs.MIN_TOKENS / 3 } }
        assertTrue("crumbs: $crumbs", crumbs <= cs.size / 5)
    }

    // ---------------- storage ----------------

    @Test fun reindexIsIdempotentAndLeavesMemoriesAlone() {
        val s = sql()
        val blaze = BlazeDb(s); val mind = MindStore(blaze, s)
        mind.save("profile", "You", "Lives in Ndola and studies computer science")
        val st = BlazeDocs.Store(s, { blaze.meta(it) }, { k, v -> blaze.setMeta(k, v) })
        val files = docFiles(); val tools = sampleTools()
        assertTrue(BlazeDocs.ensure(st, "h1", "0.0.1", { files }, tools))
        val n = st.count(); val ids = st.ids("about")
        assertTrue(n > 20)
        // Same build: nothing happens. Replace again by hand: the same rows, no duplicates.
        assertFalse(BlazeDocs.ensure(st, "h1", "0.0.1", { error("must not read the docs again") }, tools))
        st.replace(st.signature(), BlazeDocs.chunksOf(files, "0.0.1", tools))
        assertEquals(n, st.count()); assertEquals(ids, st.ids("about"))
        assertEquals(n, s.query("SELECT count(*) FROM blaze_docs_fts") { it.long(0).toInt() }.first())
        // A new build (or another tool list) replaces everything.
        assertTrue(BlazeDocs.ensure(st, "h2", "0.0.2", { listOf("about.md" to "# About\nOnly this.") }, null))
        assertEquals(1, st.count())
        assertEquals("0.0.2", st.read("about#1")!!.version)
        assertTrue(BlazeDocs.ensure(st, "h2", "0.0.2", { listOf("about.md" to "# About\nOnly this.") }, sampleTools()))
        assertTrue(st.count() > 1)
        // Shared schema untouched: v1, memories still there, the signature in meta under a prefixed key.
        assertEquals(1, blaze.version())
        assertEquals(1, mind.all().size)
        assertTrue(blaze.meta(BlazeDocs.META_SIG).startsWith("0.0.2:h2:"))
        for (t in listOf("blaze_docs", "blaze_docs_fts")) assertEquals(1L, s.query("SELECT count(*) FROM sqlite_master WHERE name=?", t) { it.long(0) }.first())
        // Opening again (next launch) keeps the rows.
        assertEquals(st.count(), BlazeDocs.Store(s, { blaze.meta(it) }, { k, v -> blaze.setMeta(k, v) }).count())
    }

    // ---------------- search ----------------

    @Test fun repoQuestionsFindTheExactUrl() {
        val st = store()
        BlazeDocs.ensure(st, "h", "test", { docFiles() }, sampleTools())
        BlazeDocs.use(st)
        for (q in listOf("where is your repo", "take me to blaze-agent repo in github", "where is your source code")) {
            val hits = st.search(q, 5)
            assertTrue(q, hits.isNotEmpty())
            assertTrue("$q → ${hits.first().id}", hits.first().id.startsWith("about#"))
            assertTrue(q, st.read(hits.first().id)!!.text.contains(BlazeDocs.REPO_URL))
            val out = BlazeDocs.search(JSONObject().put("query", q))
            assertTrue(out, out.contains(BlazeDocs.REPO_URL))
            assertTrue(out, out.contains("https://gitlab.com/blazeinchue/Blaze-dekstop"))
            assertFalse(out, out.contains("github.com/LacksonKapapa/"))
        }
        // Limit ≤ 5, ids readable, other questions land in the right doc.
        assertTrue(st.search("settings", 50).size <= 5)
        assertTrue(st.search("how do I update blaze", 3).any { st.read(it.id)!!.text.contains("/update") })
        assertTrue(st.search("what language is blaze written in", 3).any { st.read(it.id)!!.text.contains("Kotlin") })
        assertTrue(st.search("which tool searches blaze docs", 5).any { it.doc == "tools" })
        assertTrue(BlazeDocs.read(JSONObject().put("id", "nope#1")).startsWith("No docs section"))
        assertTrue(BlazeDocs.read(JSONObject().put("id", "about#1")).startsWith("[about#1]"))
    }

    @Test fun versionQuestionsFindTheChangelog() {
        val dir = System.getProperty("blaze.docs.dir")?.let { File(it) }?.takeIf { File(it, "changelog/v0.28.1.md").isFile } ?: return
        val st = store()
        BlazeDocs.ensure(st, "h", "test", { docFiles() }, null)
        assertTrue(dir.path, st.search("what changed in 0.28.1", 3).any { it.doc == "changelog/v0.28.1" })
    }

    @Test fun toolReferenceComesFromTheToolList() {
        val doc = BlazeDocs.toolsDoc(sampleTools()) { if (it.startsWith("browser_")) "browser" else null }
        assertTrue(doc, doc.contains("- ${BlazeDocs.TOOL_SEARCH}: Search Blaze's own docs"))
        assertTrue(doc, doc.contains("## Tools: browser"))
        assertTrue(doc, doc.contains("- web_search: Search the web for anything."))
        assertFalse(doc, doc.contains("Returns results with links"))   // one line only
        assertEquals("Open a web page (or search words) in Blaze's own built-in browser, in the background.",
            BlazeDocs.oneLine("Open a web page (or search words) in Blaze's own built-in browser, in the background. No screen control needed."))
        assertTrue(BlazeDocs.oneLine("x".repeat(400)).length <= 160)
        assertTrue(BlazeDocs.toolsHash(sampleTools()) != BlazeDocs.toolsHash(JSONArray().put(tool("a", "b"))))
    }

    // ---------------- no secrets ----------------

    @Test fun noSecretsShip() {
        val samples = listOf("write to someone@example.com", "token ghp_" + "a".repeat(36), "project blaze-511220", "id " + "0123456789abcdef".repeat(2),
            "key sk-" + "x".repeat(24), "AIza" + "B".repeat(35), "storePassword = \"android\"")
        for (s in samples) { assertTrue(s, BlazeDocs.hasSecret(s)); assertFalse(s, BlazeDocs.hasSecret(BlazeDocs.scrub(s))) }
        assertFalse(BlazeDocs.hasSecret("Blaze v0.28.1 (versionCode 97), minSdk 26, https://gitlab.com/blazeinchue/Blaze-agent"))
        val files = docFiles()
        val cs = BlazeDocs.chunksOf(files, "test", sampleTools())
        for (c in cs) assertFalse("${c.id} has a secret-like string", BlazeDocs.hasSecret(c.text) || BlazeDocs.hasSecret(c.section))
        assertFalse(cs.any { it.text.contains("511220") })
        // The generated files themselves are clean too (scrubbed at build time).
        if (System.getProperty("blaze.docs.dir")?.let { File(it, "manifest.json").isFile } == true)
            for ((n, t) in files) assertFalse("$n has a secret-like string", BlazeDocs.hasSecret(t))
        // about.md carries the build facts (filled in by Gradle when generated).
        val about = files.first { it.first == "about.md" }.second
        assertTrue(about.contains(BlazeDocs.REPO_URL))
        assertTrue(about.contains("https://gitlab.com/blazeinchue/Blaze-cli"))
        assertFalse(about.contains("github.com/"))
        assertTrue(about.contains("https://ai.blaze-app.workers.dev"))
    }

    // ---------------- prompt + routing ----------------

    @Test fun promptLineIsShortAndPinsTheRepo() {
        assertTrue(BlazeDocs.PROMPT_LINE.contains(BlazeDocs.REPO_URL))
        assertTrue(BlazeDocs.PROMPT_LINE.contains(BlazeDocs.TOOL_SEARCH))
        assertTrue(BlazeDocs.PROMPT_LINE.length < 420)
        assertTrue(BlazeDocs.isAboutBlaze("take me to blaze-agent repo in github"))
        assertTrue(BlazeDocs.isAboutBlaze("where is your source code"))
        assertTrue(BlazeDocs.isAboutBlaze("what version are you"))
        assertTrue(BlazeDocs.isAboutBlaze("How do I change Blaze settings?"))
        assertFalse(BlazeDocs.isAboutBlaze("what's the weather in Ndola"))
        assertFalse(BlazeDocs.isAboutBlaze("open my github notifications"))
        assertTrue(BlazeDocs.run(null, BlazeDocs.TOOL_SEARCH, JSONObject().put("query", "repo")).contains(BlazeDocs.REPO_URL))
    }

    @Test fun githubNearMissesOpenTheRealRepo() {
        assertEquals(BlazeDocs.REPO_URL, ToolLeak.fixBlazeUrl("https://github.com/LacksonKapapa/Blaze-Agent"))
        assertEquals(BlazeDocs.REPO_URL, ToolLeak.fixBlazeUrl("github.com/lacksonkapapa/blaze-agent"))
        assertEquals(BlazeDocs.REPO_URL, ToolLeak.fixBlazeUrl("https://github.com/LACKSONKAPAPA/BLAZE-AGENT"))
        assertEquals(BlazeDocs.REPO_URL + "/releases/latest", ToolLeak.fixBlazeUrl("https://github.com/Lackson-Kapapa/Blaze_Agent/releases/latest"))
        assertEquals("https://gitlab.com/blazeinchue/Blaze-dekstop", ToolLeak.fixBlazeUrl("https://github.com/LacksonKapapa/Blaze-Desktop"))
        assertEquals("https://gitlab.com/blazeinchue/Blaze-cli", ToolLeak.fixBlazeUrl("https://www.github.com/lucksonkapapa/blaze-cli.git"))
        // Someone else's repo of the same name, and other URLs, are left alone.
        assertEquals("https://github.com/octocat/blaze-agent", ToolLeak.fixBlazeUrl("https://github.com/octocat/blaze-agent"))
        assertEquals("https://example.com/Blaze-Agent", ToolLeak.fixBlazeUrl("https://example.com/Blaze-Agent"))
        // The leaked github_open alias: any owner or none.
        assertEquals(BlazeDocs.REPO_URL, ToolLeak.githubUrl(JSONObject().put("repo", "blaze-agent")))
        assertEquals(BlazeDocs.REPO_URL, ToolLeak.githubUrl(JSONObject().put("repo", "LacksonKapapa/Blaze-Agent")))
        assertEquals(BlazeDocs.REPO_URL, ToolLeak.githubUrl(JSONObject().put("url", "https://github.com/LacksonKapapa/Blaze-Agent")))
        assertEquals("https://gitlab.com/blazeinchue/Blaze-dekstop", ToolLeak.githubUrl(JSONObject().put("repo", "Blaze-Desktop")))
        assertEquals("https://github.com/octocat/hello", ToolLeak.githubUrl(JSONObject().put("repo", "octocat/hello")))
    }
}
