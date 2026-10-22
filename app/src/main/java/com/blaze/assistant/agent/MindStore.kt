package com.blaze.assistant.agent

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.Sql
import org.json.JSONArray

/**
 * v0.26.3 one memory store: the mind lives in blaze.db, the same file format as Blaze Desktop and Blaze CLI
 * (internal/store schema v1, FTS5). Memories are rows of the shared `memories` table (text, norm, category = kind,
 * created / updated in unix seconds, uses, last_used, pinned), so a blaze.db from the phone opens in Desktop or the
 * CLI and theirs open here.
 *
 * What schema v1 has no column for lives in two side tables that Desktop and the CLI simply don't read:
 *  - `mind_mem`: a memory's subject, related subjects and source turn, keyed by memories.id and only trusted while
 *    its `created` matches the memory's (so a row the CLI removed and reused is never mislabelled). A memory written
 *    by Desktop or the CLI has no side row: its kind comes from its category, its subject from [Mind.guessKind].
 *  - `mind_journal` + `mind_journal_fts`: the one-mind journal, the same columns as Desktop's mind.db journal plus
 *    `kind` (v0.19.0 life logs / chat-app messages), FTS5 external content kept by triggers.
 * Neither changes schema v1 or user_version. Prefixed names so a later shared migration can't collide with them.
 * Mind's own meta keys are kept as "mind:<key>" in the shared meta table.
 *
 * The behaviour is Mind.Db's (dedupe through [Mind.decide], the same search ranking, the same journal rules); only
 * the storage moved. Pure JVM over [Sql]: the phone runs it on the bundled SQLite (AndroidSql), tests on sqlite-jdbc.
 */
class MindStore(val blaze: BlazeDb, val sql: Sql) : Mind.Store {

    override val fts: Boolean get() = true

    private val lock = Any()

    init { for (st in BlazeDb.statements(EXTRA)) sql.exec(st) }

    // ---------------- meta ----------------

    override fun meta(key: String): String = blaze.meta(META + key)
    override fun setMeta(key: String, v: String) { blaze.setMeta(META + key, v) }

    // ---------------- memories ----------------

    private val memSelect = "SELECT m.id, m.category, m.text, m.created, m.updated, x.subject, x.related, x.source_turn, x.created " +
        "FROM memories m LEFT JOIN mind_mem x ON x.id=m.id"

    private fun memOf(r: Sql.Row): Mind.Mem {
        val id = r.long(0); val cat = r.text(1); val text = r.text(2); val created = r.long(3); val updated = r.long(4)
        val side = r.text(5).isNotBlank() && r.long(8) == created
        val guess by lazy { Mind.guessKind(text) }
        val kind = if (cat.isNotBlank()) Mind.normKind(cat) else guess.first
        val subject = if (side) r.text(5) else guess.second
        return Mind.Mem(id, kind, subject, text, if (side) r.text(6) else "", if (side) r.long(7) else 0L, created * 1000, updated * 1000)
    }

    override fun all(): List<Mind.Mem> = sql.query("$memSelect ORDER BY m.created, m.id") { memOf(it) }

    override fun mem(id: Long): Mind.Mem? = sql.query("$memSelect WHERE m.id=?", id) { memOf(it) }.firstOrNull()

    private fun side(id: Long, created: Long, subject: String, related: String, sourceTurn: Long) {
        sql.exec("INSERT INTO mind_mem(id, created, subject, related, source_turn) VALUES(?,?,?,?,?) " +
            "ON CONFLICT(id) DO UPDATE SET created=excluded.created, subject=excluded.subject, related=excluded.related, source_turn=excluded.source_turn",
            id, created, subject, related, sourceTurn)
    }

    override fun save(kind: String, subject: String, text: String, related: String, sourceTurn: Long, now: Long): Pair<String, Long> = synchronized(lock) {
        val k = Mind.normKind(kind); val s = Mind.squash(subject).ifBlank { "You" }.take(60); val t = Mind.clean(text)
        val sec = now / 1000
        when (val d = Mind.decide(all(), k, s, t)) {
            is Mind.Decision.Same -> "same" to d.id
            is Mind.Decision.Update -> {
                val old = mem(d.id) ?: return@synchronized "same" to d.id
                sql.tx {
                    sql.exec("UPDATE memories SET text=?, norm=?, category=?, updated=? WHERE id=?", t, BlazeDb.norm(t), k, sec, d.id)
                    side(d.id, old.created / 1000, s, related.ifBlank { old.related }, if (sourceTurn > 0) sourceTurn else old.sourceTurn)
                }
                "updated" to d.id
            }
            Mind.Decision.Insert -> {
                val n = BlazeDb.norm(t)
                // blaze.db keeps one row per normalised text (Desktop's Add): the same words with other punctuation are "same".
                sql.query("SELECT id FROM memories WHERE norm=? LIMIT 1", n) { it.long(0) }.firstOrNull()?.let { return@synchronized "same" to it }
                val id = sql.tx {
                    sql.exec("INSERT INTO memories(text, norm, category, created, updated) VALUES(?,?,?,?,?)", t, n, k, sec, sec)
                    val id = sql.query("SELECT last_insert_rowid()") { it.long(0) }.first()
                    side(id, sec, s, related, sourceTurn)
                    id
                }
                "saved" to id
            }
        }
    }

    override fun update(id: Long, text: String, kind: String?, subject: String?, now: Long): Boolean = synchronized(lock) {
        val old = mem(id) ?: return false
        val t = Mind.clean(text).ifBlank { old.text }
        val s = subject?.let { Mind.squash(it).take(60) }?.ifBlank { null } ?: old.subject
        val k = if (!kind.isNullOrBlank()) Mind.normKind(kind) else old.kind
        sql.tx {
            sql.exec("UPDATE memories SET text=?, norm=?, category=?, updated=? WHERE id=?", t, BlazeDb.norm(t), k, now / 1000, id)
            side(id, old.created / 1000, s, old.related, old.sourceTurn)
        }
        true
    }

    override fun delete(id: Long) = synchronized(lock) {
        sql.tx { sql.exec("DELETE FROM memories WHERE id=?", id); sql.exec("DELETE FROM mind_mem WHERE id=?", id) }
    }

    /**
     * memory_search: FTS5 (bm25) over the text plus a plain substring pass over subject, text and related (subjects
     * aren't in the shared FTS table). Ranked like Mind.Db: most query words matched first, then bm25, then newest.
     * The hits are touched (uses + 1, last_used), as Desktop does for the memories it shows the model.
     */
    override fun search(query: String, kind: String?, limit: Int): List<Mind.Mem> {
        val ws = Mind.words(query).toList().take(8)
        val k = kind?.trim()?.lowercase()?.takeIf { it in Mind.KINDS }
        val every = all()
        if (ws.isEmpty()) return every.filter { k == null || it.kind == k }.sortedByDescending { it.updated }.take(limit)
        val rank = HashMap<Long, Int>()
        val fq = BlazeDb.ftsQuery(query, 32)
        if (fq.isNotEmpty()) try {
            sql.query("SELECT f.rowid FROM memories_fts f WHERE memories_fts MATCH ? ORDER BY bm25(memories_fts) LIMIT 200", fq) { it.long(0) }
                .forEachIndexed { i, id -> rank.putIfAbsent(id, i) }
        } catch (_: Exception) { }
        fun hay(m: Mind.Mem) = m.subject + " " + m.text + " " + m.related
        val hits = every.filter { it.id in rank || ws.any { w -> hay(it).contains(w, true) } }
            .filter { k == null || it.kind == k }
            .map { m -> Triple(m, ws.count { w -> hay(m).contains(w, true) }, rank[m.id] ?: Int.MAX_VALUE) }
            .sortedWith(compareByDescending<Triple<Mind.Mem, Int, Int>> { it.second }.thenBy { it.third }.thenByDescending { it.first.updated })
            .take(limit).map { it.first }
        if (hits.isNotEmpty()) try { blaze.touch(hits.map { it.id }) } catch (_: Exception) { }
        return hits
    }

    // ---------------- journal ----------------

    private val synced = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val turnCols = "id, conv, turn, role, content, at"
    private fun turnOf(r: Sql.Row) = Mind.Turn(r.long(0), r.text(1), r.long(2).toInt(), r.text(3), r.text(4), r.long(5))

    private fun maxTurn(conv: String): Int =
        sql.query("SELECT MAX(turn), count(*) FROM mind_journal WHERE conv=?", conv) { if (it.long(1) == 0L) 0 else it.long(0).toInt() + 1 }.first()

    override fun journal(conv: String, h: JSONArray, at: Long, full: Boolean): Int = synchronized(lock) {
        if (PrivateChat.isPrivateId(conv)) return 0   // v0.24.4: private chats never reach the journal
        val n = h.length(); if (n == 0) return 0
        val known = if (full) 0 else (synced[conv] ?: maxTurn(conv))
        val from = if (full) 0 else (minOf(known, n) - 24).coerceAtLeast(0)
        var added = 0
        sql.tx {
            for (i in from until n) {
                val m = h.optJSONObject(i) ?: continue
                val text = LongChat.text(m).trim().take(8000)
                if (text.isBlank()) continue
                val role = m.optString("role").ifBlank { "user" }
                val old = sql.query("SELECT id, content FROM mind_journal WHERE conv=? AND turn=?", conv, i) { it.long(0) to it.text(1) }.firstOrNull()
                if (old == null) {
                    sql.exec("INSERT INTO mind_journal(conv, turn, role, content, at, reviewed, kind) VALUES(?,?,?,?,?,0,'chat')", conv, i, role, text, at)
                    added++
                } else if (old.second != text) {
                    // A retry changed that turn.
                    sql.exec("UPDATE mind_journal SET content=?, role=?, reviewed=0 WHERE id=?", text, role, old.first)
                }
            }
        }
        synced[conv] = n
        added
    }

    fun count(where: String = "1=1"): Int = sql.query("SELECT count(*) FROM mind_journal WHERE $where") { it.long(0).toInt() }.first()
    override fun unreviewedCount(): Int = count("reviewed=0")

    override fun unreviewed(limit: Int): List<Mind.Turn> =
        sql.query("SELECT $turnCols FROM mind_journal WHERE reviewed=0 ORDER BY id LIMIT ?", limit) { turnOf(it) }

    override fun markReviewed(ids: List<Long>) {
        if (ids.isEmpty()) return
        synchronized(lock) { sql.tx { ids.forEach { sql.exec("UPDATE mind_journal SET reviewed=1 WHERE id=?", it) } } }
    }

    override fun turn(id: Long): Mind.Turn? = sql.query("SELECT $turnCols FROM mind_journal WHERE id=?", id) { turnOf(it) }.firstOrNull()

    override fun searchJournal(query: String, conv: String?, limit: Int): List<Mind.Hit> {
        val ws = Mind.words(query).toList().take(8); if (ws.isEmpty()) return emptyList()
        val rows = ArrayList<Mind.Turn>()
        val q = journalQuery(query)
        val c = if (conv != null) " AND j.conv=?" else ""
        try {
            if (q.isEmpty()) throw IllegalStateException("no terms")
            val args: Array<Any?> = if (conv != null) arrayOf(q, conv) else arrayOf(q)
            rows += sql.query("SELECT j.id, j.conv, j.turn, j.role, j.content, j.at FROM mind_journal_fts f JOIN mind_journal j ON j.id=f.rowid " +
                "WHERE mind_journal_fts MATCH ?$c ORDER BY j.id DESC LIMIT 200", *args) { turnOf(it) }
        } catch (_: Exception) {
            rows.clear()
            val where = ws.joinToString(" OR ") { "content LIKE ?" }
            val args: List<Any?> = ws.map { "%$it%" } + listOfNotNull(conv)
            rows += sql.query("SELECT $turnCols FROM mind_journal j WHERE ($where)$c ORDER BY id DESC LIMIT 200", *args.toTypedArray()) { turnOf(it) }
        }
        return rows.map { it to ws.count { w -> it.content.contains(w, true) } }
            .sortedWith(compareByDescending<Pair<Mind.Turn, Int>> { it.second }.thenByDescending { it.first.id })
            .take(limit).map { (t, _) -> Mind.Hit(t.id, t.conv, t.turn, t.role, Mind.snippet(t.content, ws)) }
    }

    // ---------------- logs (MindLogs) ----------------

    override fun logAppend(conv: String, role: String, content: String, at: Long, kind: String): Long = synchronized(lock) {
        val text = content.trim().take(8000)
        if (text.isBlank()) return 0
        val dup = sql.query("SELECT id FROM mind_journal WHERE conv=? AND at=? AND role=? AND content=? LIMIT 1", conv, at, role, text) { it.long(0) }.isNotEmpty()
        if (dup) return 0
        sql.tx {
            sql.exec("INSERT OR IGNORE INTO mind_journal(conv, turn, role, content, at, reviewed, kind) VALUES(?,?,?,?,?,0,?)", conv, maxTurn(conv), role, text, at, kind)
            if (sql.query("SELECT changes()") { it.long(0) }.first() > 0) sql.query("SELECT last_insert_rowid()") { it.long(0) }.first() else 0L
        }
    }

    override fun logRecent(conv: String, limit: Int): List<Mind.Turn> =
        sql.query("SELECT $turnCols FROM mind_journal WHERE conv=? ORDER BY turn DESC LIMIT ?", conv, limit) { turnOf(it) }.reversed()

    override fun logSince(conv: String, sinceSec: Long): List<Mind.Turn> =
        sql.query("SELECT $turnCols FROM mind_journal WHERE conv=? AND at>=? ORDER BY turn", conv, sinceSec) { turnOf(it) }

    override fun logCount(kind: String): Int = sql.query("SELECT count(*) FROM mind_journal WHERE kind=?", kind) { it.long(0).toInt() }.first()

    override fun logDeleteKind(kind: String): Int = synchronized(lock) {
        sql.tx {
            val n = logCount(kind)
            sql.exec("DELETE FROM mind_journal WHERE kind=?", kind)   // mind_journal_ad keeps the FTS index in step
            n
        }
    }

    override fun close() = blaze.close()

    // ---------------- migration from mind.db (v0.26.3) ----------------

    /** One memory as mind.db stores it (times in ms since v0.18.0; seconds are accepted too). */
    data class OldMem(val id: Long, val kind: String, val subject: String, val text: String, val related: String,
                      val sourceTurn: Long, val created: Long, val updated: Long)

    /** One journal row as mind.db stores it. */
    data class OldTurn(val id: Long, val conv: String, val turn: Int, val role: String, val content: String,
                       val at: Long, val reviewed: Int, val kind: String)

    /** What the migration reads from the old mind.db (Mind.Db on the phone; a plain SQLite file in tests). */
    interface Source {
        fun metaAll(): Map<String, String>
        fun oldMemories(): List<OldMem>
        /** Journal rows with id > [afterId], oldest first, at most [limit]. */
        fun oldTurns(afterId: Long, limit: Int): List<OldTurn>
        fun oldTurnCount(): Int
    }

    data class Imported(val memories: Int, val turns: Int, val skipped: Boolean = false)

    fun imported(): Boolean = meta(IMPORT_DONE) == "1"

    /**
     * Copies everything in mind.db into blaze.db: meta keys, every memory (same id, kind as category, subject,
     * related and source turn in mind_mem, times in seconds) and every journal row (same id, so "j123" references
     * and memories' source turns stay valid). [backup] runs first and must succeed (it returns the backup's path).
     *
     * Idempotent and safe across a crash: each pass replaces rows by id (delete + insert, so the FTS triggers stay in
     * step), the journal goes in batches of [batch] rows each in its own transaction, and "imported" is set only after
     * the counts check out. A pass that dies half way is simply run again on the next launch. mind.db is never changed.
     */
    fun importFrom(src: Source, backup: () -> String, batch: Int = 500, onProgress: (Int, Int) -> Unit = { _, _ -> }): Imported = synchronized(lock) {
        if (imported()) return Imported(0, 0, skipped = true)
        val b = meta(IMPORT_BACKUP).takeIf { it.isNotBlank() && java.io.File(it).isFile } ?: backup().also { setMeta(IMPORT_BACKUP, it) }
        if (b.isBlank()) throw java.io.IOException("mind.db backup failed")
        setMeta(IMPORT_STARTED, BlazeDb.now().toString())
        val oldMeta = src.metaAll()
        sql.tx { for ((k, v) in oldMeta) sql.exec("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", META + k, v) }
        val mems = src.oldMemories()
        sql.tx {
            for (m in mems) {
                val text = BlazeDb.clean(m.text)
                if (text.isEmpty()) continue
                val c = sec(m.created).takeIf { it > 0 } ?: BlazeDb.now()
                val u = sec(m.updated).takeIf { it > 0 } ?: c
                sql.exec("DELETE FROM memories WHERE id=?", m.id)
                sql.exec("INSERT INTO memories(id, text, norm, category, created, updated) VALUES(?,?,?,?,?,?)",
                    m.id, text, BlazeDb.norm(text), Mind.normKind(m.kind), c, u)
                side(m.id, c, Mind.squash(m.subject).ifBlank { "You" }.take(60), m.related, m.sourceTurn)
            }
        }
        val total = src.oldTurnCount()
        var after = 0L; var copied = 0
        while (true) {
            val rows = src.oldTurns(after, batch)
            if (rows.isEmpty()) break
            sql.tx {
                for (t in rows) {
                    sql.exec("DELETE FROM mind_journal WHERE id=? OR (conv=? AND turn=?)", t.id, t.conv, t.turn)
                    sql.exec("INSERT INTO mind_journal(id, conv, turn, role, content, at, reviewed, kind) VALUES(?,?,?,?,?,?,?,?)",
                        t.id, t.conv, t.turn, t.role, t.content, sec(t.at), t.reviewed, t.kind.ifBlank { "chat" })
                }
                sql.exec("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", META + IMPORT_JOURNAL_AT, rows.last().id.toString())
            }
            after = rows.last().id; copied += rows.size
            onProgress(copied, total)
        }
        // Check before saying done: every old memory and journal row is here.
        val haveMems = mems.count { m -> m.text.isNotBlank() && mem(m.id) != null }
        val wantMems = mems.count { it.text.isNotBlank() }
        if (haveMems != wantMems) throw IllegalStateException("memories copied $haveMems of $wantMems")
        if (copied < total) throw IllegalStateException("journal copied $copied of $total")
        synced.clear()
        setMeta(IMPORT_AT, BlazeDb.now().toString())
        setMeta(IMPORT_DONE, "1")
        Imported(wantMems, copied)
    }

    /** A fresh install (no mind.db): nothing to copy. */
    fun markFresh() { if (!imported()) { setMeta(IMPORT_AT, BlazeDb.now().toString()); setMeta(IMPORT_DONE, "1") } }

    companion object {
        const val META = "mind:"
        const val IMPORT_DONE = "android_import_done"
        const val IMPORT_BACKUP = "android_import_backup"
        const val IMPORT_STARTED = "android_import_started"
        const val IMPORT_JOURNAL_AT = "android_import_journal_at"
        const val IMPORT_AT = "android_import_at"

        /** Times in ms (mind.db since v0.18.0) or seconds → unix seconds. */
        fun sec(t: Long): Long = if (t > 100_000_000_000L) t / 1000 else t

        /** search_chat's FTS5 query: each word as a quoted prefix ("word"*), OR-ed (Mind.Db's LongChat.ftsQuery, in FTS5 form). */
        fun journalQuery(q: String): String = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.distinct().take(8)
            .joinToString(" OR ") { "\"$it\"*" }

        /** The Android side tables (not part of schema v1; Desktop and the CLI ignore them). */
        val EXTRA = """CREATE TABLE IF NOT EXISTS mind_mem(id INTEGER PRIMARY KEY, created INTEGER NOT NULL, subject TEXT NOT NULL DEFAULT '', related TEXT NOT NULL DEFAULT '', source_turn INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS mind_journal(
  id INTEGER PRIMARY KEY,
  conv TEXT NOT NULL,
  turn INTEGER NOT NULL,
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  at INTEGER NOT NULL,
  reviewed INTEGER NOT NULL DEFAULT 0,
  kind TEXT NOT NULL DEFAULT 'chat',
  UNIQUE(conv, turn)
);
CREATE INDEX IF NOT EXISTS mind_journal_unreviewed ON mind_journal(reviewed, id);
CREATE INDEX IF NOT EXISTS mind_journal_kind ON mind_journal(kind, at);
CREATE VIRTUAL TABLE IF NOT EXISTS mind_journal_fts USING fts5(content, content='mind_journal', content_rowid='id', tokenize='porter unicode61');
CREATE TRIGGER IF NOT EXISTS mind_journal_ai AFTER INSERT ON mind_journal BEGIN
  INSERT INTO mind_journal_fts(rowid, content) VALUES (new.id, new.content);
END;
CREATE TRIGGER IF NOT EXISTS mind_journal_ad AFTER DELETE ON mind_journal BEGIN
  INSERT INTO mind_journal_fts(mind_journal_fts, rowid, content) VALUES ('delete', old.id, old.content);
END;
CREATE TRIGGER IF NOT EXISTS mind_journal_au AFTER UPDATE OF content ON mind_journal BEGIN
  INSERT INTO mind_journal_fts(mind_journal_fts, rowid, content) VALUES ('delete', old.id, old.content);
  INSERT INTO mind_journal_fts(rowid, content) VALUES (new.id, new.content);
END;"""
    }
}
