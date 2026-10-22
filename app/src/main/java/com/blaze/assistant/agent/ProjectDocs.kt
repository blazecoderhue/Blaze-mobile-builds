package com.blaze.assistant.agent

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.Sql
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.28.5 project docs: a project's full instructions, full brief and older notes as an on-demand lookup, not prompt text.
 *
 * The prompt pins only [Projects.pinned] (name + the top of the instructions and brief, ≤ ~600 tokens, + one lookup line).
 * Everything is chunked (BlazeDocs.chunk: by heading, ~150–600 tokens, the user's text unscrubbed) into blaze.db: table
 * `project_docs` + FTS5 `project_docs_fts`, keyed by project, the same style as BlazeDocs / MindStore's side tables
 * (prefixed names; schema v1 and user_version untouched). The project list in prefs (`projects_json`, now with `notes`)
 * stays the source of truth, so nothing is lost if blaze.db can't open: the index is rebuilt from it whenever a
 * project's signature (sha1 of its texts) changes ([ensure], called lazily before every search / read).
 *
 * Tools: [TOOL_SEARCH] / [TOOL_READ], offered in project chats only (Agent strips them elsewhere). Pure JVM over [Sql]
 * (ProjectDocsTest, PromptBudgetTest on sqlite-jdbc).
 */
object ProjectDocs {
    const val TOOL_SEARCH = "project_docs_search"
    const val TOOL_READ = "project_docs_read"
    val toolNames = setOf(TOOL_SEARCH, TOOL_READ)
    const val MIN_TOKENS = 150
    const val MAX_TOKENS = 600
    private const val SIG = "project_docs:sig:"

    data class Chunk(val id: String, val project: String, val kind: String, val title: String, val ord: Int, val text: String)
    data class Hit(val id: String, val title: String, val snippet: String)

    const val TABLES = """CREATE TABLE IF NOT EXISTS project_docs(pid TEXT NOT NULL, id TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL DEFAULT '', ord INTEGER NOT NULL DEFAULT 0, text TEXT NOT NULL, PRIMARY KEY(pid, id));
CREATE VIRTUAL TABLE IF NOT EXISTS project_docs_fts USING fts5(pid UNINDEXED, id UNINDEXED, kind, title, text, tokenize='porter unicode61');"""

    /** A project's docs as (doc id, kind, title, text): instructions, brief, then each note (newest last). */
    fun docsOf(p: Project): List<List<String>> = buildList {
        if (p.instructions.isNotBlank()) add(listOf("instructions", "instructions", "Instructions", p.instructions))
        if (p.brief.isNotBlank()) add(listOf("brief", "brief", "Brief (current)", p.brief))
        p.notes.forEachIndexed { i, n -> add(listOf("note${i + 1}", "note", n.title.ifBlank { "Note ${i + 1}" }, n.text)) }
    }

    /** Every chunk of [p]: ids like `instructions#3`, `brief#1`, `note2#1`. */
    fun chunksOf(p: Project): List<Chunk> = docsOf(p).flatMap { (doc, kind, title, text) ->
        BlazeDocs.chunk(doc, text, "", MIN_TOKENS, MAX_TOKENS, scrubSecrets = false).mapIndexed { i, c ->
            Chunk(c.id, p.id, kind, if (c.section.isBlank()) title else "$title > ${c.section}", i, c.text)
        }
    }

    fun signature(p: Project): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        docsOf(p).forEach { d -> d.forEach { md.update(it.toByteArray()); md.update(0) }; md.update(1) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(20)
    }

    class Store(private val sql: Sql, private val getMeta: (String) -> String, private val setMeta: (String, String) -> Unit) {
        private val lock = Any()
        init { for (st in BlazeDb.statements(TABLES)) sql.exec(st) }

        fun count(pid: String): Int = sql.query("SELECT count(*) FROM project_docs WHERE pid=?", pid) { it.long(0).toInt() }.first()

        /** Reindexes [p] when its texts changed since the last time. Returns true when it did. Idempotent. */
        fun ensure(p: Project): Boolean = synchronized(lock) {
            val sig = signature(p)
            if (getMeta(SIG + p.id) == sig) return false
            val chunks = chunksOf(p)
            sql.tx {
                sql.exec("DELETE FROM project_docs WHERE pid=?", p.id); sql.exec("DELETE FROM project_docs_fts WHERE pid=?", p.id)
                for (c in chunks.distinctBy { it.id }) {
                    sql.exec("INSERT INTO project_docs(pid, id, kind, title, ord, text) VALUES(?,?,?,?,?,?)", c.project, c.id, c.kind, c.title, c.ord, c.text)
                    sql.exec("INSERT INTO project_docs_fts(pid, id, kind, title, text) VALUES(?,?,?,?,?)", c.project, c.id, c.kind, c.title, c.text)
                }
            }
            setMeta(SIG + p.id, sig)
            true
        }

        /** Drops a deleted project's chunks. */
        fun drop(pid: String) = synchronized(lock) {
            sql.tx { sql.exec("DELETE FROM project_docs WHERE pid=?", pid); sql.exec("DELETE FROM project_docs_fts WHERE pid=?", pid) }
            setMeta(SIG + pid, "")
        }

        /** Best chunks of project [pid] for [query] (bm25: title ×4, kind ×2, text ×1). */
        fun search(pid: String, query: String, limit: Int): List<Hit> {
            val fq = BlazeDb.ftsQuery(query, 32); if (fq.isEmpty()) return emptyList()
            return try {
                sql.query("SELECT id, title, snippet(project_docs_fts, 4, '', '', '…', 40) FROM project_docs_fts WHERE project_docs_fts MATCH ? AND pid = ? " +
                    "ORDER BY bm25(project_docs_fts, 0.0, 0.0, 2.0, 4.0, 1.0) LIMIT ?", fq, pid, limit.coerceIn(1, 8)) { Hit(it.text(0), it.text(1), it.text(2)) }
            } catch (_: Exception) { emptyList() }
        }

        fun read(pid: String, id: String): Chunk? = sql.query("SELECT id, pid, kind, title, ord, text FROM project_docs WHERE pid=? AND id=?", pid, id.trim()) {
            Chunk(it.text(0), it.text(1), it.text(2), it.text(3), it.long(4).toInt(), it.text(5))
        }.firstOrNull()

        /** Every chunk id + title of [pid], in order (what a blank search lists). */
        fun list(pid: String): List<Pair<String, String>> = sql.query("SELECT id, title FROM project_docs WHERE pid=? ORDER BY CASE kind WHEN 'instructions' THEN 0 WHEN 'brief' THEN 1 ELSE 2 END, id", pid) { it.text(0) to it.text(1) }
    }

    fun formatHits(q: String, hits: List<Hit>, all: List<Pair<String, String>>): String = when {
        all.isEmpty() -> "This project has no instructions, brief or notes yet."
        q.isBlank() -> "This project's sections: " + all.take(40).joinToString("; ") { "[${it.first}] ${it.second}" } + ". $TOOL_READ {id} reads one."
        hits.isEmpty() -> "Nothing in this project's docs matches \u201c$q\u201d. Sections: " + all.take(30).joinToString("; ") { "[${it.first}] ${it.second}" } + ". $TOOL_READ {id} reads one."
        else -> hits.joinToString("\n") { "[${it.id}] ${it.title}: ${it.snippet.replace(Regex("\\s+"), " ").trim()}" } + "\n$TOOL_READ {id} returns the whole section."
    }

    /** Runs a project-docs tool for project [p] against [s] (indexing it first if it changed). */
    fun run(s: Store?, p: Project?, name: String, args: JSONObject): String {
        if (p == null) return "This chat isn't in a project, so there are no project docs."
        val st = s ?: return fallback(p, name, args)
        try { st.ensure(p) } catch (_: Exception) { return fallback(p, name, args) }
        return when (name) {
            TOOL_SEARCH -> { val q = args.optString("query").ifBlank { args.optString("q") }
                formatHits(q, if (q.isBlank()) emptyList() else st.search(p.id, q, args.optInt("limit", 4)), st.list(p.id)) }
            TOOL_READ -> { val id = args.optString("id"); st.read(p.id, id)?.let { "[${it.id}] ${it.title}\n${it.text}" } ?: "No section \u201c$id\u201d in this project. Use $TOOL_SEARCH for ids." }
            else -> "Unknown tool"
        }
    }

    /** No database: plain word matching over the same chunks, so the tools still work. */
    private fun fallback(p: Project, name: String, args: JSONObject): String {
        val chunks = chunksOf(p)
        return when (name) {
            TOOL_SEARCH -> {
                val q = args.optString("query"); val words = q.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
                val hits = chunks.map { c -> c to words.count { c.text.lowercase().contains(it) } }.filter { it.second > 0 }.sortedByDescending { it.second }
                    .take(args.optInt("limit", 4).coerceIn(1, 8)).map { (c, _) -> Hit(c.id, c.title, around(c.text, words)) }
                formatHits(q, hits, chunks.map { it.id to it.title })
            }
            TOOL_READ -> chunks.firstOrNull { it.id == args.optString("id").trim() }?.let { "[${it.id}] ${it.title}\n${it.text}" } ?: "No section \u201c${args.optString("id")}\u201d in this project."
            else -> "Unknown tool"
        }
    }

    /** ~240 characters of [text] around the first of [words] it contains. */
    private fun around(text: String, words: List<String>): String {
        val flat = text.replace(Regex("\\s+"), " ")
        val at = words.map { flat.lowercase().indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val from = (at - 100).coerceAtLeast(0)
        return (if (from > 0) "…" else "") + flat.substring(from, minOf(flat.length, from + 240))
    }

    fun tools(): List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL_SEARCH)
            .put("description", "Search this project's full instructions, full brief and older notes (only their top part is pinned in the prompt). " +
                "Use it before asking the user for project details. A blank query lists every section.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("query", JSONObject().put("type", "string").put("description", "What to look up, e.g. 'deadline', 'tone rules', 'file layout'"))
                .put("limit", JSONObject().put("type", "integer").put("description", "1-8, default 4")))
                .put("required", JSONArray(listOf("query"))))),
        JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL_READ)
            .put("description", "Read one section of this project's docs in full, by the id $TOOL_SEARCH gave (e.g. instructions#2, note3#1).")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("id", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("id"))))),
    )

    /** The tool list without the project-docs tools (every chat that isn't in a project). */
    fun strip(all: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until all.length()) {
            val t = all.optJSONObject(i) ?: continue
            val n = t.optJSONObject("function")?.optString("name") ?: t.optString("name")
            if (n !in toolNames) put(t)
        }
    }

    // ---------------- Android ----------------

    @Volatile private var store: Store? = null
    @Volatile private var persistent = false

    /** For tests. */
    fun use(s: Store?) { store = s; persistent = s != null }

    /** blaze.db when it's the memory store; otherwise an in-memory index rebuilt from prefs (prefs stay the source of truth). */
    @Synchronized fun store(): Store? {
        if (persistent) return store
        (Mind.db() as? MindStore)?.let { m ->
            return try { Store(m.sql, { m.blaze.meta(it) }, { k, v -> m.blaze.setMeta(k, v) }).also { store = it; persistent = true } } catch (_: Throwable) { store }
        }
        if (store == null) store = try {
            val mem = HashMap<String, String>()
            Store(Mind.openBlaze(java.io.File(":memory:")), { mem[it].orEmpty() }, { k, v -> mem[k] = v })
        } catch (_: Throwable) { null }
        return store
    }

    /** Indexes every project that changed (start-up, off the main thread). */
    fun init() { try { val s = store() ?: return; Projects.all.value.forEach { p -> try { s.ensure(p) } catch (_: Throwable) { } } } catch (_: Throwable) { } }

    fun search(chatId: String, args: JSONObject): String = run(store(), Projects.of(chatId), TOOL_SEARCH, args)
    fun read(chatId: String, args: JSONObject): String = run(store(), Projects.of(chatId), TOOL_READ, args)
    fun dropProject(pid: String) { try { store()?.drop(pid) } catch (_: Throwable) { } }
}
