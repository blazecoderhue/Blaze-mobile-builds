package com.blaze.assistant.agent

import android.content.Context
import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.Sql
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.28.4 blaze-docs: Blaze's knowledge about itself as an on-demand lookup, not prompt text.
 *
 * At build time Gradle writes assets/blaze-docs/ from the repo (README, CHANGELOG split per version, STATUS, docs/, the
 * hand-written about.md with this build's version / commit / SDK levels, manifest.json; app/build.gradle.kts
 * `BlazeDocsTask`). On the first launch after an update (the manifest hash or the tool list changed) the docs are
 * chunked by heading (~300–800 tokens, each with its doc, section path and version) into blaze.db: table `blaze_docs`
 * + FTS5 `blaze_docs_fts`, replaced whole in one transaction (idempotent). A tool reference generated from the real
 * tool list (name + one line, grouped like ToolRouter) is indexed as the doc `tools`, so it is always in sync.
 *
 * The model reaches it only through [TOOL_SEARCH] (bm25-ranked snippets with ids) and [TOOL_READ] (one chunk). The
 * system prompt carries one short line and the repo URL ([PROMPT_LINE]), whatever the docs hold (PromptBudgetTest).
 *
 * Prefixed names like MindStore's side tables: schema v1 and user_version are untouched, and Desktop / the CLI simply
 * don't read these tables. Pure JVM over [Sql] (BlazeDocsTest on sqlite-jdbc); [init] is the Android glue.
 */
object BlazeDocs {
    const val TOOL_SEARCH = "blaze_docs_search"
    const val TOOL_READ = "blaze_docs_read"
    val toolNames = setOf(TOOL_SEARCH, TOOL_READ)

    const val REPO_URL = ToolLeak.BLAZE_REPO_URL
    const val DIR = "blaze-docs"
    const val MIN_TOKENS = 300
    const val MAX_TOKENS = 800
    const val META_SIG = "blaze_docs:sig"

    /** The only docs text in any prompt: static (it stays in the cached prefix) and the same size whatever is indexed. */
    const val PROMPT_LINE = "\nBLAZE DOCS: questions about Blaze itself (its repo or source code, versions, changelog, features, settings, " +
        "\"how do I … in Blaze\", what it's built with) → call $TOOL_SEARCH first, then $TOOL_READ for the whole section; never guess URLs, " +
        "repo names or owners. Pinned fact: Blaze for Android's source code is $REPO_URL ."

    data class Chunk(val id: String, val doc: String, val section: String, val version: String, val ord: Int, val text: String)
    data class Hit(val id: String, val doc: String, val section: String, val snippet: String)

    // ---------------- secrets ----------------

    /** What must never be in the docs (also scrubbed at build time). The same patterns as BlazeDocsTask. */
    val SECRET_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""") to "[email]",
        Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\b|\bgithub_pat_[A-Za-z0-9_]{20,}\b""") to "[token]",
        Regex("""\bsk-[A-Za-z0-9_\-]{16,}\b|\bAIza[0-9A-Za-z_\-]{30,}\b|\bxox[abprs]-[A-Za-z0-9\-]{10,}\b""") to "[key]",
        Regex("""\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\b""") to "[token]",
        Regex("""\b\d{6,}-[a-z0-9]{20,}\.apps\.googleusercontent\.com\b""") to "[client-id]",
        Regex("""\b[a-z][a-z0-9\-]{2,24}-\d{6}\b""") to "[project-id]",
        Regex("""\b[0-9a-fA-F]{32,}\b""") to "[id]",
        Regex("""(?i)\b(store|key)Password\s*=\s*"[^"\[]+"""") to "$1Password = \"[redacted]\"",
    )

    fun scrub(s: String): String { var t = s; for ((re, rep) in SECRET_PATTERNS) t = re.replace(t, rep); return t }

    fun hasSecret(s: String): Boolean = SECRET_PATTERNS.any { it.first.containsMatchIn(s) }

    // ---------------- chunking ----------------

    fun tokens(s: String): Int = BlazeDb.tokens(s)

    private data class Section(val path: List<String>, val lines: MutableList<String>)

    /** Splits Markdown by headings (#…####, not inside ``` fences): each section with its heading path. */
    private fun sections(md: String): List<Section> {
        val out = ArrayList<Section>()
        val stack = ArrayList<Pair<Int, String>>()
        var cur = Section(emptyList(), ArrayList())
        var fence = false
        val head = Regex("^(#{1,4})\\s+(.+?)\\s*#*\\s*$")
        for (line in md.replace("\r\n", "\n").lines()) {
            if (line.trimStart().startsWith("```")) fence = !fence
            val m = if (fence) null else head.find(line)
            if (m != null) {
                if (cur.lines.any { it.isNotBlank() } || cur.path.isNotEmpty()) out += cur
                val lvl = m.groupValues[1].length
                while (stack.isNotEmpty() && stack.last().first >= lvl) stack.removeAt(stack.size - 1)
                stack += lvl to m.groupValues[2].trim()
                cur = Section(stack.map { it.second }, arrayListOf(line))
            } else cur.lines += line
        }
        if (cur.lines.any { it.isNotBlank() }) out += cur
        return out
    }

    /** Cuts one too-long block at paragraph, then line, then sentence, then character boundaries, each ≤ [maxTok]. */
    private fun split(text: String, maxTok: Int): List<String> {
        if (tokens(text) <= maxTok) return listOf(text)
        val maxChars = maxTok * 4
        val out = ArrayList<String>(); val cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out += cur.toString().trim('\n'); cur.setLength(0) }
        fun pieces(t: String): List<String> = when {
            t.contains("\n\n") -> t.split("\n\n").map { it + "\n\n" }
            t.contains('\n') -> t.split('\n').map { it + "\n" }
            Regex("(?<=[.!?])\\s+").containsMatchIn(t) -> t.split(Regex("(?<=[.!?])\\s+")).map { "$it " }
            else -> t.chunked(maxChars)
        }
        for (p in pieces(text)) {
            if (p.toByteArray().size > maxChars) { flush(); split(p.trimEnd(), maxTok).forEach { out += it }; continue }
            if ((cur.toString() + p).toByteArray().size > maxChars) flush()
            cur.append(p)
        }
        flush()
        return out.filter { it.isNotBlank() }
    }

    /**
     * One doc → chunks of about [minTok]–[maxTok] tokens: a section is one chunk, a long one is split (each part keeps
     * its heading), and small neighbours are merged until they reach [minTok] (the first one's section path names it).
     */
    fun chunk(doc: String, md: String, version: String, minTok: Int = MIN_TOKENS, maxTok: Int = MAX_TOKENS, scrubSecrets: Boolean = true): List<Chunk> {
        val parts = ArrayList<Pair<String, String>>()   // section path, text
        // v0.28.5: project docs are the user's own text, so ProjectDocs chunks them without scrubbing.
        for (s in sections(if (scrubSecrets) scrub(md) else md)) {
            val path = s.path.joinToString(" > ")
            val text = s.lines.joinToString("\n").trim()
            if (text.isEmpty()) continue
            val head = s.lines.firstOrNull()?.takeIf { it.startsWith("#") }.orEmpty()
            // Room for the "(continued)" heading each later part gets, so no chunk passes maxTok.
            split(text, (maxTok - tokens(head) - 4).coerceAtLeast(maxTok / 2)).forEachIndexed { i, t ->
                parts += path to (if (i > 0 && head.isNotEmpty() && !t.startsWith("#")) "$head (continued)\n$t" else t)
            }
        }
        val merged = ArrayList<Pair<String, String>>()
        for (p in parts) {
            val last = merged.lastOrNull()
            if (last != null && tokens(last.second) < minTok && tokens(last.second) + tokens(p.second) + 1 <= maxTok) {
                // The merged chunk keeps every heading in its section path, so bm25's section weight still sees them.
                val leaf = p.first.substringAfterLast(" > ")
                val path = if (leaf.isBlank() || last.first.endsWith(leaf)) last.first else (if (last.first.isBlank()) p.first else "${last.first} · $leaf")
                merged[merged.size - 1] = path to (last.second + "\n\n" + p.second)
            }
            else merged += p
        }
        return merged.mapIndexed { i, (path, text) -> Chunk("$doc#${i + 1}", doc, path, version, i, text) }
    }

    /** The tool reference from the real tool list: `## group` then `- name: one line`, core tools first. */
    fun toolsDoc(tools: JSONArray, groupOf: (String) -> String? = { ToolRouter.groupOf(it) }): String {
        val rows = (0 until tools.length()).mapNotNull { tools.optJSONObject(it)?.optJSONObject("function") }
            .map { it.optString("name") to oneLine(it.optString("description")) }.filter { it.first.isNotBlank() }.distinctBy { it.first }
        val byGroup = rows.groupBy { (n, _) -> try { groupOf(n) } catch (_: Throwable) { null } ?: "core" }
        val order = listOf("core") + ToolRouter.groups.map { it.id }
        val sb = StringBuilder("# Blaze tools (generated from this build's tool list, ${rows.size} tools)\n\n" +
            "Core tools are always available; the others load by group (load_tools / find_tool).\n")
        for (g in order + (byGroup.keys - order.toSet()).sorted()) {
            val l = byGroup[g] ?: continue
            val about = ToolRouter.groups.firstOrNull { it.id == g }?.about ?: "always available"
            sb.append("\n## Tools: ").append(g).append(" (").append(about).append(")\n")
            for ((n, d) in l.sortedBy { it.first }) sb.append("- ").append(n).append(": ").append(d).append('\n')
        }
        return sb.toString()
    }

    /** First sentence of a description, at most 160 characters. */
    fun oneLine(d: String): String {
        val t = d.replace(Regex("\\s+"), " ").trim()
        val end = Regex("(?<=[a-z0-9)\"'])[.!?](\\s|$)").find(t)?.range?.first?.plus(1) ?: t.length
        val s = t.substring(0, end)
        return if (s.length > 160) s.take(157).trimEnd() + "…" else s
    }

    /** sha1 of the tool names + descriptions (a changed tool list reindexes the tools doc). */
    fun toolsHash(tools: JSONArray?): String {
        if (tools == null) return ""
        val rows = (0 until tools.length()).mapNotNull { tools.optJSONObject(it)?.optJSONObject("function") }
            .map { BlazeDb.ToolRow(it.optString("name"), it.optString("description"), "") }
        return BlazeDb.toolsHash(rows).take(16)
    }

    // ---------------- the store ----------------

    const val TABLES = """CREATE TABLE IF NOT EXISTS blaze_docs(id TEXT PRIMARY KEY, doc TEXT NOT NULL, section TEXT NOT NULL DEFAULT '', version TEXT NOT NULL DEFAULT '', ord INTEGER NOT NULL DEFAULT 0, text TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS blaze_docs_doc ON blaze_docs(doc, ord);
CREATE VIRTUAL TABLE IF NOT EXISTS blaze_docs_fts USING fts5(id UNINDEXED, doc, section, text, tokenize='porter unicode61');"""

    /** blaze_docs + blaze_docs_fts on [sql]; the signature lives in the shared meta table ([getMeta] / [setMeta]). */
    class Store(private val sql: Sql, private val getMeta: (String) -> String, private val setMeta: (String, String) -> Unit) {
        private val lock = Any()

        init { for (st in BlazeDb.statements(TABLES)) sql.exec(st) }

        fun signature(): String = getMeta(META_SIG)

        fun count(): Int = sql.query("SELECT count(*) FROM blaze_docs") { it.long(0).toInt() }.first()

        /** Replaces every chunk (one transaction) and records [sig]. Running it twice leaves the same rows. */
        fun replace(sig: String, chunks: List<Chunk>) = synchronized(lock) {
            sql.tx {
                sql.exec("DELETE FROM blaze_docs"); sql.exec("DELETE FROM blaze_docs_fts")
                for (c in chunks.distinctBy { it.id }) {
                    sql.exec("INSERT INTO blaze_docs(id, doc, section, version, ord, text) VALUES(?,?,?,?,?,?)", c.id, c.doc, c.section, c.version, c.ord, c.text)
                    sql.exec("INSERT INTO blaze_docs_fts(id, doc, section, text) VALUES(?,?,?,?)", c.id, docTerms(c.doc), c.section, c.text)
                }
            }
            setMeta(META_SIG, sig)
        }

        /** Best chunks for [query] (bm25: section ×6, doc ×2, text ×1; the about doc is preferred), with snippets. */
        fun search(query: String, limit: Int): List<Hit> {
            val fq = BlazeDb.ftsQuery(expand(query), 32); if (fq.isEmpty()) return emptyList()
            return try {
                sql.query("SELECT id, doc, section, snippet(blaze_docs_fts, 3, '', '', '…', 48) FROM blaze_docs_fts f WHERE blaze_docs_fts MATCH ? " +
                    "ORDER BY bm25(blaze_docs_fts, 0.0, 2.0, 6.0, 1.0) * (CASE WHEN f.id LIKE 'about#%' THEN 2.0 WHEN f.id LIKE 'tools#%' THEN 1.2 ELSE 1.0 END) LIMIT ?",
                    fq, limit.coerceIn(1, 5)) { Hit(it.text(0), "", it.text(2), it.text(3)) }
                    .map { h -> h.copy(doc = h.id.substringBeforeLast('#')) }
            } catch (_: Exception) { emptyList() }
        }

        fun read(id: String): Chunk? = sql.query("SELECT id, doc, section, version, ord, text FROM blaze_docs WHERE id=?", id.trim()) {
            Chunk(it.text(0), it.text(1), it.text(2), it.text(3), it.long(4).toInt(), it.text(5))
        }.firstOrNull()

        fun ids(doc: String): List<String> = sql.query("SELECT id FROM blaze_docs WHERE doc=? ORDER BY ord", doc) { it.text(0) }
    }

    private val verRe = Regex("\\bv?(\\d+)\\.(\\d+)\\.(\\d+)\\b")

    /** The doc name as search words, plus a squashed version ("changelog/v0.28.1" → "changelog v0.28.1 v0281") so "0.28.1" finds its entry. */
    fun docTerms(doc: String): String = doc.replace('/', ' ').replace('-', ' ') + (verRe.find(doc)?.let { " v" + it.groupValues.drop(1).joinToString("") } ?: "")

    private val repoRe = Regex("\\b(repo|repos|repository|repositories|github|source|sourcecode|code base|codebase|open source)\\b")

    /** Words people use for the same thing ("source code" → repo, "what version" → changelog). */
    fun expand(q: String): String {
        val l = q.lowercase(); val extra = StringBuilder()
        if (repoRe.containsMatchIn(l)) extra.append(" repo github source")
        verRe.findAll(l).forEach { extra.append(" v").append(it.groupValues.drop(1).joinToString("")) }
        if (Regex("\\b(version|versions|release|releases|changelog|changed|new|latest)\\b").containsMatchIn(l)) extra.append(" version release changelog")
        if (Regex("\\b(update|upgrade|updating)\\b").containsMatchIn(l)) extra.append(" update")
        if (Regex("\\b(language|written|built|stack|kotlin|framework)\\b").containsMatchIn(l)) extra.append(" kotlin stack")
        if (Regex("\\b(setting|settings|toggle|option|options)\\b").containsMatchIn(l)) extra.append(" settings")
        return q + extra
    }

    /** Every doc file → chunks (about first), plus the tools doc. */
    fun chunksOf(files: List<Pair<String, String>>, version: String, tools: JSONArray?): List<Chunk> {
        val out = ArrayList<Chunk>()
        for ((name, md) in files) out += chunk(docName(name), md, version)
        if (tools != null && tools.length() > 0) out += chunk("tools", toolsDoc(tools), version)
        return out
    }

    /** "changelog/v0.28.1.md" → "changelog/v0.28.1", "about.md" → "about". */
    fun docName(file: String): String = file.removeSuffix(".md").trim('/')

    /**
     * Indexes when the signature changed (new build, other docs, other tools); otherwise does nothing.
     * Returns true when it (re)indexed.
     */
    fun ensure(store: Store, manifestHash: String, version: String, files: () -> List<Pair<String, String>>, tools: JSONArray?): Boolean {
        val sig = "$version:$manifestHash:${toolsHash(tools)}"
        if (store.signature() == sig && store.count() > 0) return false
        store.replace(sig, chunksOf(files(), version, tools))
        return true
    }

    // ---------------- tools ----------------

    fun tools(): List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL_SEARCH)
            .put("description", "Search Blaze's own docs (built into this app): its GitHub repos and exact URLs, versions and changelog, features, settings, " +
                "how to do things in Blaze, how to update, tech stack, privacy, limits, and every tool. Use it first for any question about Blaze itself; never guess URLs.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("query", JSONObject().put("type", "string").put("description", "What to look up, e.g. 'repo url', 'what changed in 0.28.1', 'how to update'"))
                .put("limit", JSONObject().put("type", "integer").put("description", "1-5, default 3")))
                .put("required", JSONArray(listOf("query"))))),
        JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL_READ)
            .put("description", "Read one section of Blaze's docs in full, by the id $TOOL_SEARCH gave (e.g. about#2).")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("id", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("id"))))),
    )

    /** The exact repo URLs, put on top of any repo / source-code search (the fact that was guessed wrong before). */
    const val REPOS_LINE = "Blaze repos (exact): Android (this app) $REPO_URL · Desktop https://gitlab.com/blazeinchue/Blaze-dekstop · CLI https://gitlab.com/blazeinchue/Blaze-cli"

    fun formatHits(q: String, hits: List<Hit>): String =
        (if (repoRe.containsMatchIn(q.lowercase())) "$REPOS_LINE\n" else "") +
        if (hits.isEmpty()) "Nothing in Blaze's docs matches \u201c$q\u201d. Try other words; for live setup state use about_blaze. Blaze for Android's repo is $REPO_URL ."
        else hits.joinToString("\n") { "[${it.id}] ${it.section.ifBlank { it.doc }}: ${it.snippet.replace(Regex("\\s+"), " ").trim()}" } +
            "\n$TOOL_READ {id} returns a whole section."

    fun formatChunk(c: Chunk?, id: String): String = c?.let { "[${it.id}] ${it.section.ifBlank { it.doc }} (Blaze v${it.version})\n${it.text}" }
        ?: "No docs section \u201c$id\u201d. Use $TOOL_SEARCH for ids."

    /** Runs a docs tool against [s] (null: not ready yet). */
    fun run(s: Store?, name: String, args: JSONObject): String {
        val st = s ?: return "Blaze's docs are still loading. Blaze for Android's repo is $REPO_URL ."
        return when (name) {
            TOOL_SEARCH -> { val q = args.optString("query").ifBlank { args.optString("q") }; formatHits(q, st.search(q, args.optInt("limit", 3).coerceIn(1, 5))) }
            TOOL_READ -> args.optString("id").let { formatChunk(st.read(it), it) }
            else -> "Unknown tool"
        }
    }

    /** "where is your repo", "what version are you", "how do I … in Blaze": a question about Blaze itself (a per-turn hint). */
    fun isAboutBlaze(text: String): Boolean {
        val l = text.lowercase()
        if (Regex("\\bblaze\\b").containsMatchIn(l) && Regex("\\b(repo|repository|github|source|version|changelog|update|release|setting|settings|feature|features|built|language|stack|how do i|how to|where)\\b").containsMatchIn(l)) return true
        return Regex("\\b(your (repo|repository|source code|code|github|version|changelog|settings?)|what version are you|are you open source|who (made|built|created) you|what (language|stack) are you)\\b").containsMatchIn(l)
    }

    const val TURN_HINT = "\n[This is about Blaze itself: call $TOOL_SEARCH first and answer from what it returns; never guess URLs or repo names.]"

    // ---------------- Android ----------------

    @Volatile var store: Store? = null
        private set
    @Volatile private var lastTools = ""

    /** For tests. */
    fun use(s: Store?) { store = s }

    /**
     * Opens the docs store in blaze.db (or, while blaze.db isn't the memory store yet, in memory) and indexes when the
     * build, the docs or the tool list changed. Call off the main thread.
     */
    fun init(ctx: Context, tools: JSONArray?) {
        try {
            val app = ctx.applicationContext
            val s = store ?: open().also { store = it }
            val manifest = JSONObject(app.assets.open("$DIR/manifest.json").bufferedReader().use { it.readText() })
            val names = manifest.optJSONArray("files") ?: JSONArray()
            val version = manifest.optString("version")
            ensure(s, manifest.optString("hash"), version, {
                (0 until names.length()).mapNotNull { i -> val n = names.optString(i)
                    try { n to app.assets.open("$DIR/$n").bufferedReader().use { it.readText() } } catch (_: Exception) { null } }
            }, tools)
            lastTools = toolsHash(tools)
            docsFiles = { (0 until names.length()).mapNotNull { i -> val n = names.optString(i)
                try { n to app.assets.open("$DIR/$n").bufferedReader().use { it.readText() } } catch (_: Exception) { null } } }
            manifestHash = manifest.optString("hash"); docsVersion = version
        } catch (_: Throwable) { }
    }

    @Volatile private var docsFiles: (() -> List<Pair<String, String>>)? = null
    @Volatile private var manifestHash = ""
    @Volatile private var docsVersion = ""

    /** The tool list this turn (Agent.tools): when it changed since indexing (a connector, a setting), reindex off-thread. */
    fun noteTools(tools: JSONArray) {
        val h = toolsHash(tools)
        val s = store ?: return
        val f = docsFiles ?: return
        if (h == lastTools) return
        lastTools = h
        Thread({ try { ensure(s, manifestHash, docsVersion, f, tools) } catch (_: Throwable) { } }, "blaze-docs-tools").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun open(): Store {
        (Mind.db() as? MindStore)?.let { m -> return Store(m.sql, { m.blaze.meta(it) }, { k, v -> m.blaze.setMeta(k, v) }) }
        // blaze.db isn't the memory store right now (still copying, or it couldn't open): keep the docs in memory this run.
        val mem = HashMap<String, String>()
        return Store(Mind.openBlaze(java.io.File(":memory:")), { mem[it].orEmpty() }, { k, v -> mem[k] = v })
    }

    fun search(args: JSONObject): String = run(store, TOOL_SEARCH, args)
    fun read(args: JSONObject): String = run(store, TOOL_READ, args)
}
