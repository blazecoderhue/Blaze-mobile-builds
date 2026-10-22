package com.blaze.assistant.store

/**
 * blaze.db: the same SQLite store as Blaze Desktop and Blaze CLI (internal/store, schema v1).
 * Memories, plus an index of skills and one of tools, each with an FTS5 table so a turn pulls only the
 * rows it needs. The schema, the normalisation, the FTS query and every ranking below are ports of the
 * Go code; change them together.
 *
 * The SQL runs through [Sql] so the app uses a bundled modern SQLite (FTS5 everywhere, see
 * AndroidSql) while JVM tests use sqlite-jdbc.
 */
class BlazeDb(private val sql: Sql) {

    data class Memory(val id: Long, val text: String, val category: String, val project: String, val chat: String,
                      val created: Long, val updated: Long, val uses: Int, val lastUsed: Long, val pinned: Boolean)

    data class Skill(val name: String, val desc: String, val path: String, val body: String = "", val mtime: Long = 0)
    data class ToolRow(val name: String, val desc: String, val group: String)

    class NewerSchema(v: Int) : Exception("Your memory was saved by a newer version of Blaze (schema v$v, this build knows v${MIGRATIONS.size}). Please update Blaze to use memory.")

    private val lock = Any()

    init { migrate() }

    fun close() = sql.close()

    // ---------------- schema ----------------

    fun version(): Int = sql.query("PRAGMA user_version") { it.long(0).toInt() }.firstOrNull() ?: 0

    private fun migrate() = synchronized(lock) {
        // Desktop's DSN: busy_timeout(5000), synchronous(NORMAL), foreign_keys(ON). Connection settings only; the file is untouched.
        sql.exec("PRAGMA busy_timeout = 5000")
        sql.exec("PRAGMA synchronous = NORMAL")
        sql.exec("PRAGMA foreign_keys = ON")
        var v = version()
        if (v > MIGRATIONS.size) throw NewerSchema(v)
        // WAL only after the version check, so a newer file we refuse is left as it was.
        sql.query("PRAGMA journal_mode=WAL") { it.text(0) }
        while (v < MIGRATIONS.size) {
            val done = sql.tx {
                val now = version()
                if (now >= MIGRATIONS.size) return@tx true
                for (st in statements(MIGRATIONS[now])) sql.exec(st)
                sql.exec("PRAGMA user_version = ${now + 1}")
                false
            }
            v = version()
            if (done) break
        }
        if (v > MIGRATIONS.size) throw NewerSchema(v)
        for (t in listOf("memories", "memories_fts", "skills", "skills_fts", "tools", "tools_fts", "active_tools", "meta")) {
            val n = sql.query("SELECT count(*) FROM sqlite_master WHERE name=?", t) { it.long(0) }.first()
            if (n == 0L) throw IllegalStateException("schema v$v is missing table $t; not touching it")
        }
        sql.exec("DELETE FROM active_tools WHERE at < ?", now() - 30L * 24 * 3600)
    }

    // ---------------- meta ----------------

    fun meta(key: String): String = sql.query("SELECT value FROM meta WHERE key=?", key) { it.text(0) }.firstOrNull() ?: ""

    fun setMeta(key: String, value: String) = synchronized(lock) {
        sql.exec("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value)
    }

    // ---------------- memories ----------------

    private fun rows(q: String, vararg a: Any?): List<Memory> = sql.query(q, *a) { r ->
        Memory(r.long(0), r.text(1), r.text(2), r.text(3), r.text(4), r.long(5), r.long(6), r.long(7).toInt(), r.long(8), r.long(9) != 0L)
    }

    fun memories(): List<Memory> = rows("SELECT $MEM_COLS FROM memories ORDER BY created, id")
    fun memory(id: Long): Memory? = rows("SELECT $MEM_COLS FROM memories WHERE id=?", id).firstOrNull()
    fun countMemories(): Int = sql.query("SELECT count(*) FROM memories") { it.long(0).toInt() }.first()

    /**
     * Saves a fact, deduplicating: identical text (ignoring case, punctuation and spacing) is kept once,
     * and a near-identical one (≥ 85% shared words) is replaced by the newer wording, keeping its id.
     * Returns (id, [ADDED] | [EXISTS] | [UPDATED]).
     */
    fun add(text0: String, project: String = "", chat: String = "", at: Long = 0): Pair<Long, String> = synchronized(lock) {
        val text = clean(text0); val n = norm(text)
        sql.query("SELECT id FROM memories WHERE norm=? LIMIT 1", n) { it.long(0) }.firstOrNull()?.let { id ->
            sql.exec("UPDATE memories SET updated=? WHERE id=?", now(), id)
            return id to EXISTS
        }
        val q = ftsQuery(text, 24)
        if (q.isNotEmpty()) {
            val cands = try { sql.query("SELECT m.id, m.norm FROM memories_fts f JOIN memories m ON m.id=f.rowid WHERE memories_fts MATCH ? ORDER BY bm25(memories_fts) LIMIT 5", q) { it.long(0) to it.text(1) } } catch (_: Exception) { emptyList() }
            var best = 0L; var bs = 0.0
            for ((cid, cn) in cands) { val s = similar(n, cn); if (s > bs) { best = cid; bs = s } }
            if (bs >= NEAR) {
                sql.exec("UPDATE memories SET text=?, norm=?, updated=? WHERE id=?", text, n, now(), best)
                return best to UPDATED
            }
        }
        val created = if (at > 0) at else now()
        // Single statement: a row with the same norm written by Desktop/CLI in the meantime is not duplicated.
        sql.exec("INSERT INTO memories(text, norm, project, chat, created, updated) SELECT ?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM memories WHERE norm=?)",
            text, n, project, chat, created, now(), n)
        val id = sql.query("SELECT id FROM memories WHERE norm=? LIMIT 1", n) { it.long(0) }.first()
        id to ADDED
    }

    fun edit(id: Long, text0: String): Boolean = synchronized(lock) {
        val text = clean(text0)
        sql.exec("UPDATE memories SET text=?, norm=?, updated=? WHERE id=?", text, norm(text), now(), id)
        changes() > 0
    }

    fun delete(id: Long): Boolean = synchronized(lock) {
        sql.exec("DELETE FROM memories WHERE id=?", id)
        changes() > 0
    }

    private fun changes(): Long = sql.query("SELECT changes()") { it.long(0) }.first()

    /** Removes every memory whose text contains [s] (case-insensitive); returns what it removed. */
    fun forgetMatching(s0: String): List<Memory> {
        val s = clean(s0).lowercase(); if (s.isEmpty()) return emptyList()
        return memories().filter { it.text.lowercase().contains(s) && delete(it.id) }
    }

    fun setPinned(id: Long, on: Boolean) = synchronized(lock) { sql.exec("UPDATE memories SET pinned=? WHERE id=?", if (on) 1 else 0, id) }
    fun setCategory(id: Long, cat: String) = synchronized(lock) { sql.exec("UPDATE memories SET category=? WHERE id=?", cat.trim(), id) }

    /** The memories that best match [q] (BM25), best first. */
    fun search(q: String, limit: Int): List<Memory> {
        val fq = ftsQuery(q, 32); if (fq.isEmpty()) return emptyList()
        return try {
            rows("SELECT m.id, m.text, m.category, m.project, m.chat, m.created, m.updated, m.uses, m.last_used, m.pinned " +
                "FROM memories_fts f JOIN memories m ON m.id=f.rowid WHERE memories_fts MATCH ? ORDER BY bm25(memories_fts), m.uses DESC LIMIT ?", fq, limit)
        } catch (_: Exception) { emptyList() }
    }

    /** Records that memories were shown to the model. */
    fun touch(ids: List<Long>) {
        if (ids.isEmpty()) return
        synchronized(lock) { val t = now(); for (id in ids) sql.exec("UPDATE memories SET uses=uses+1, last_used=? WHERE id=?", t, id) }
    }

    /**
     * The memories for one turn: every pinned one, then the best matches for [q] (up to [max]), topped up
     * with the most used/recent ones when q matches little, all within a token [budget]. Picked rows are touched.
     */
    fun forPrompt(q: String, max: Int, budget: Int): List<Memory> {
        val seen = HashSet<Long>(); val out = mutableListOf<Memory>(); var used = 0
        fun add(ms: List<Memory>, limit: Int) {
            for (m in ms) {
                if (limit >= 0 && out.size >= limit) return
                if (m.id in seen) continue
                val t = tokens(m.text) + 2
                if (used + t > budget) continue
                seen += m.id; used += t; out += m
            }
        }
        add(rows("SELECT $MEM_COLS FROM memories WHERE pinned=1 ORDER BY created, id"), -1)
        val pinned = out.size
        add(search(q, max * 2), pinned + max)
        if (out.size - pinned < 3) add(rows("SELECT $MEM_COLS FROM memories ORDER BY uses DESC, updated DESC LIMIT 8"), pinned + 3)
        touch(out.map { it.id })
        return out
    }

    /**
     * Makes the memories exactly [texts] (Go ReplaceAll, the Settings textarea): lines already saved keep their row,
     * new ones are added, missing ones are removed.
     */
    fun replaceAll(texts: List<String>) {
        val want = texts.map { norm(it) }.filter { it.isNotEmpty() }.toSet()
        val have = HashSet<String>()
        for (m in memories()) {
            val n = norm(m.text)
            if (n !in want || n in have) { delete(m.id); continue }
            have += n
        }
        for (t in texts) {
            val n = norm(t)
            if (n.isNotEmpty() && have.add(n)) synchronized(lock) {
                val now = now()
                sql.exec("INSERT INTO memories(text, norm, created, updated) VALUES(?,?,?,?)", clean(t), n, now, now)
            }
        }
    }

    /** Memories about the same thing as [text] without being the same fact (overlap in [lo], NEAR), best first. */
    fun related(text: String, lo: Double, limit: Int, skip: Long): List<Memory> {
        val n = norm(text)
        return search(text, 12).filter { it.id != skip }.map { it to similar(n, norm(it.text)) }
            .filter { it.second >= lo && it.second < NEAR }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    // ---------------- skills index ----------------

    fun replaceSkills(sk: List<Skill>) = synchronized(lock) {
        sql.tx {
            sql.exec("DELETE FROM skills"); sql.exec("DELETE FROM skills_fts")
            for (s in sk) {
                sql.exec("INSERT OR REPLACE INTO skills(name, desc, path, body, mtime) VALUES(?,?,?,?,?)", s.name, s.desc, s.path, s.body, s.mtime)
                sql.exec("INSERT INTO skills_fts(path, name, desc, body) VALUES(?,?,?,?)", s.path, s.name + " " + s.name.replace("-", " "), s.desc, s.body)
            }
        }
    }

    /** path -> mtime for every indexed skill (Go SkillMtimes). */
    fun skillMtimes(): Map<String, Long> = sql.query("SELECT path, mtime FROM skills") { it.text(0) to it.long(1) }.toMap()

    /** The indexed text of a skill (Go SkillBody). */
    fun skillBody(path: String): String = sql.query("SELECT body FROM skills WHERE path=?", path) { it.text(0) }.firstOrNull() ?: ""

    fun skills(): List<Skill> = sql.query("SELECT name, desc, path, mtime FROM skills ORDER BY name") { Skill(it.text(0), it.text(1), it.text(2), "", it.long(3)) }

    fun searchSkills(q: String, limit: Int): List<Skill> {
        val fq = ftsQuery(q, 32); if (fq.isEmpty()) return emptyList()
        return try {
            sql.query("SELECT s.name, s.desc, s.path, s.mtime FROM skills_fts f JOIN skills s ON s.path=f.path " +
                "WHERE skills_fts MATCH ? ORDER BY bm25(skills_fts, 0, 10.0, 5.0, 1.0) LIMIT ?", fq, limit) { Skill(it.text(0), it.text(1), it.text(2), "", it.long(3)) }
        } catch (_: Exception) { emptyList() }
    }

    // ---------------- tools index ----------------

    /** Rebuilds the tools index when the set changed (hash in meta "tools_hash"). */
    fun syncTools(ts: List<ToolRow>) = synchronized(lock) {
        val sum = toolsHash(ts)
        if (meta("tools_hash") == sum) return
        sql.tx {
            sql.exec("DELETE FROM tools"); sql.exec("DELETE FROM tools_fts")
            for (t in ts) {
                sql.exec("INSERT OR REPLACE INTO tools(name, desc, grp) VALUES(?,?,?)", t.name, t.desc, t.group)
                sql.exec("INSERT INTO tools_fts(key, name, desc, grp) VALUES(?,?,?,?)", t.name, t.name + " " + t.name.replace("_", " "), t.desc, t.group)
            }
            sql.exec("INSERT INTO meta(key,value) VALUES('tools_hash',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", sum)
        }
    }

    fun searchTools(q: String, limit: Int): List<String> {
        val fq = ftsQuery(q, 32); if (fq.isEmpty()) return emptyList()
        return try { sql.query("SELECT key FROM tools_fts WHERE tools_fts MATCH ? ORDER BY bm25(tools_fts, 0, 10.0, 3.0, 2.0) LIMIT ?", fq, limit) { it.text(0) } }
        catch (_: Exception) { emptyList() }
    }

    fun activate(conv: String, vararg names: String) = synchronized(lock) {
        val t = now()
        for (n in names) sql.exec("INSERT INTO active_tools(conv, name, at) VALUES(?,?,?) ON CONFLICT(conv, name) DO UPDATE SET at=excluded.at", conv, n, t)
    }

    fun active(conv: String): List<String> = sql.query("SELECT name FROM active_tools WHERE conv=? ORDER BY name", conv) { it.text(0) }

    companion object {
        const val ADDED = "added"
        const val EXISTS = "exists"
        const val UPDATED = "updated"
        const val NEAR = 0.85

        private const val MEM_COLS = "id, text, category, project, chat, created, updated, uses, last_used, pinned"

        /** Desktop internal/store/store.go migrations, verbatim. */
        val MIGRATIONS = listOf(
            // v1
            """CREATE TABLE memories(
		id INTEGER PRIMARY KEY,
		text TEXT NOT NULL,
		norm TEXT NOT NULL,
		category TEXT NOT NULL DEFAULT '',
		project TEXT NOT NULL DEFAULT '',
		chat TEXT NOT NULL DEFAULT '',
		created INTEGER NOT NULL,
		updated INTEGER NOT NULL,
		uses INTEGER NOT NULL DEFAULT 0,
		last_used INTEGER NOT NULL DEFAULT 0,
		pinned INTEGER NOT NULL DEFAULT 0
	);
	CREATE INDEX memories_norm ON memories(norm);
	CREATE VIRTUAL TABLE memories_fts USING fts5(text, content='memories', content_rowid='id', tokenize='porter unicode61');
	CREATE TRIGGER memories_ai AFTER INSERT ON memories BEGIN
		INSERT INTO memories_fts(rowid, text) VALUES (new.id, new.text);
	END;
	CREATE TRIGGER memories_ad AFTER DELETE ON memories BEGIN
		INSERT INTO memories_fts(memories_fts, rowid, text) VALUES ('delete', old.id, old.text);
	END;
	CREATE TRIGGER memories_au AFTER UPDATE OF text ON memories BEGIN
		INSERT INTO memories_fts(memories_fts, rowid, text) VALUES ('delete', old.id, old.text);
		INSERT INTO memories_fts(rowid, text) VALUES (new.id, new.text);
	END;
	CREATE TABLE skills(name TEXT NOT NULL, desc TEXT NOT NULL DEFAULT '', path TEXT PRIMARY KEY, body TEXT NOT NULL DEFAULT '', mtime INTEGER NOT NULL DEFAULT 0);
	CREATE VIRTUAL TABLE skills_fts USING fts5(path UNINDEXED, name, desc, body, tokenize='porter unicode61');
	CREATE TABLE tools(name TEXT PRIMARY KEY, desc TEXT NOT NULL DEFAULT '', grp TEXT NOT NULL DEFAULT '');
	CREATE VIRTUAL TABLE tools_fts USING fts5(key UNINDEXED, name, desc, grp, tokenize='porter unicode61');
	CREATE TABLE active_tools(conv TEXT NOT NULL, name TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(conv, name));
	CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);""",
        )

        /** Splits a migration into statements (a trigger's BEGIN … END keeps its inner semicolons). */
        fun statements(script: String): List<String> {
            val out = mutableListOf<String>(); val cur = StringBuilder()
            val begin = Regex("\\bBEGIN\\b"); val end = Regex("\\bEND\\b")
            for (piece in script.split(';')) {
                cur.append(piece)
                if (begin.findAll(cur).count() > end.findAll(cur).count()) { cur.append(';'); continue }
                val s = cur.toString().trim(); if (s.isNotEmpty()) out += s
                cur.clear()
            }
            return out
        }

        fun now(): Long = System.currentTimeMillis() / 1000

        /** Go SyncTools' sum: sha1 over name NUL desc NUL group SOH, sorted by name, hex. */
        fun toolsHash(ts: List<ToolRow>): String {
            val h = java.security.MessageDigest.getInstance("SHA-1")
            for (t in ts.sortedBy { it.name }) h.update((t.name + "\u0000" + t.desc + "\u0000" + t.group + "\u0001").toByteArray())
            return h.digest().joinToString("") { "%02x".format(it) }
        }

        /** Go strings.Fields + join: single spaces. */
        fun clean(s: String): String = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

        /** The comparison form of a text: lower case, letters and digits only, single spaces (Go Norm). */
        fun norm(s: String): String {
            val b = StringBuilder(); var gap = false
            s.lowercase().codePoints().forEach { cp ->
                if (Character.isLetter(cp) || Character.isDigit(cp)) { if (gap && b.isNotEmpty()) b.append(' '); gap = false; b.appendCodePoint(cp) }
                else gap = true
            }
            return b.toString()
        }

        private val STOP = ("a an and are as at be but by can could did do does for from had has have he her his how i if in into is it its just me my no not of on or our she so than that the their them then there these they this to too up us was we were what when where which who why will with would you your yes ok okay please thanks thank hi hello hey can't don't i'm it's let lets make want need get got use using also very really some any all about")
            .split(' ').toSet()

        /** Free text → a safe FTS5 query: the newest distinct non-stopword terms, quoted, OR-ed. */
        fun ftsQuery(q: String, max: Int): String {
            val words = norm(q).split(' ').filter { it.isNotEmpty() }
            val seen = HashSet<String>(); val terms = mutableListOf<String>()
            for (i in words.indices.reversed()) {
                if (terms.size >= max) break
                val w = words[i]
                if (w.toByteArray().size < 2 || w in STOP || w in seen) continue
                seen += w; terms += "\"$w\""
            }
            return terms.joinToString(" OR ")
        }

        /** ≈ 4 bytes per token (Go Tokens). */
        fun tokens(s: String): Int = (s.toByteArray().size + 3) / 4

        /** Jaccard overlap of two texts' word sets. */
        fun similar(a: String, b: String): Double {
            val wa = a.split(' ').filter { it.isNotEmpty() }.toSet(); val wb = b.split(' ').filter { it.isNotEmpty() }.toSet()
            if (wa.isEmpty() || wb.isEmpty()) return 0.0
            val inter = wa.count { it in wb }
            return inter.toDouble() / (wa.size + wb.size - inter)
        }
    }
}

/** The few SQL calls blaze.db needs. Arguments: String, Long/Int, Double, Boolean, null. */
interface Sql {
    /** Runs one statement to the end (rows it returns, e.g. from a PRAGMA, are skipped). */
    fun exec(sql: String, vararg args: Any?)
    fun <T> query(sql: String, vararg args: Any?, row: (Row) -> T): List<T>
    /** BEGIN IMMEDIATE … COMMIT (rolled back on an exception). */
    fun <T> tx(block: () -> T): T
    fun close()

    interface Row { fun long(i: Int): Long; fun text(i: Int): String }
}
