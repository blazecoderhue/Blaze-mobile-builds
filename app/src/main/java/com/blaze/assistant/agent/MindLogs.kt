package com.blaze.assistant.agent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Journal entries that aren't chat turns (v0.19.0): chat-app messages read from notifications (kind "notif",
 * conv "notif:<app>:<conversation>") and life-log transcripts (kind "life_log", conv "life_log:<yyyy-MM-dd>" or
 * "brainstorm:<start ms>"). They sit in the same journal table, so search_chat finds them and the idle review
 * turns them into memories.
 *
 * The journal gets a `kind` column ('chat' for every existing row). It is added in place with ALTER TABLE, without
 * bumping the database version, so an older Blaze still opens mind.db and simply ignores the column.
 *
 * v0.26.3: the journal is in blaze.db ([MindStore], mind_journal, which has `kind` from the start); these calls go
 * through [Mind.Store]. The *Old functions are the mind.db versions, used by [Mind.Db].
 */
object MindLogs {
    const val NOTIF = "notif"
    const val LIFE = "life_log"

    @Volatile private var checked: Mind.Db? = null

    /** Adds the kind column once per database (idempotent). */
    fun ensureKind(d: Mind.Db) {
        if (checked === d) return
        synchronized(d) {
            if (checked === d) return
            val db = d.writableDatabase
            val has = db.rawQuery("PRAGMA table_info(journal)", null).use { c ->
                var found = false
                val i = c.getColumnIndex("name")
                while (c.moveToNext()) if (c.getString(if (i >= 0) i else 1) == "kind") found = true
                found
            }
            if (!has) db.execSQL("ALTER TABLE journal ADD COLUMN kind TEXT NOT NULL DEFAULT 'chat'")
            db.execSQL("CREATE INDEX IF NOT EXISTS journal_kind ON journal(kind, at)")
            checked = d
        }
    }

    /** Conversation class for the idle review: "heard" (life logs), "notif" (chat apps) or "chat". */
    fun classOf(conv: String): String = when {
        conv.startsWith("life_log:") || conv.startsWith("brainstorm:") -> "heard"
        conv.startsWith("notif:") -> "notif"
        else -> "chat"
    }

    /**
     * Appends one entry to [conv]. [at] is in seconds. The identical entry (same conv, time, role and text) is
     * added only once, so re-posted notifications and retries add nothing. Returns the new id, or 0 when skipped.
     */
    fun append(d: Mind.Store, conv: String, role: String, content: String, at: Long, kind: String): Long = d.logAppend(conv, role, content, at, kind)

    /** The newest [limit] entries of [conv], oldest first. */
    fun recent(d: Mind.Store, conv: String, limit: Int): List<Mind.Turn> = d.logRecent(conv, limit)

    /** Entries of [conv] since [sinceSec], oldest first. */
    fun since(d: Mind.Store, conv: String, sinceSec: Long): List<Mind.Turn> = d.logSince(conv, sinceSec)

    fun count(d: Mind.Store, kind: String): Int = d.logCount(kind)

    /** Deletes every entry of [kind] (and its search index rows). Memories already learned from them stay. */
    fun deleteKind(d: Mind.Store, kind: String): Int = d.logDeleteKind(kind)

    // ---------------- mind.db (Mind.Db) ----------------

    internal fun appendOld(d: Mind.Db, conv: String, role: String, content: String, at: Long, kind: String): Long {
        val text = content.trim().take(8000)
        if (text.isBlank()) return 0
        ensureKind(d)
        return synchronized(d) {
            val db = d.writableDatabase
            val dup = db.rawQuery("SELECT id FROM journal WHERE conv=? AND at=? AND role=? AND content=? LIMIT 1",
                arrayOf(conv, at.toString(), role, text)).use { it.moveToFirst() }
            if (dup) return 0
            val turn = db.rawQuery("SELECT MAX(turn) FROM journal WHERE conv=?", arrayOf(conv)).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) + 1 else 0 }
            val id = db.insertWithOnConflict("journal", null, ContentValues().apply {
                put("conv", conv); put("turn", turn); put("role", role); put("content", text); put("at", at); put("reviewed", 0); put("kind", kind)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (id > 0 && d.fts) try { db.insert("journal_fts", null, ContentValues().apply { put("docid", id); put("content", text) }) } catch (_: Exception) { }
            if (id > 0) id else 0L
        }
    }

    internal fun recentOld(d: Mind.Db, conv: String, limit: Int): List<Mind.Turn> =
        d.readableDatabase.rawQuery("SELECT id, conv, turn, role, content, at FROM journal WHERE conv=? ORDER BY turn DESC LIMIT ?",
            arrayOf(conv, limit.toString())).use { c ->
            val out = ArrayList<Mind.Turn>()
            while (c.moveToNext()) out += Mind.Turn(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getLong(5))
            out.reversed()
        }

    internal fun sinceOld(d: Mind.Db, conv: String, sinceSec: Long): List<Mind.Turn> =
        d.readableDatabase.rawQuery("SELECT id, conv, turn, role, content, at FROM journal WHERE conv=? AND at>=? ORDER BY turn",
            arrayOf(conv, sinceSec.toString())).use { c ->
            val out = ArrayList<Mind.Turn>()
            while (c.moveToNext()) out += Mind.Turn(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getLong(5))
            out
        }

    internal fun countOld(d: Mind.Db, kind: String): Int {
        ensureKind(d)
        return d.readableDatabase.rawQuery("SELECT count(*) FROM journal WHERE kind=?", arrayOf(kind)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    internal fun deleteKindOld(d: Mind.Db, kind: String): Int {
        ensureKind(d)
        return synchronized(d) {
            val db = d.writableDatabase
            db.beginTransaction()
            try {
                if (d.fts) try { db.execSQL("DELETE FROM journal_fts WHERE docid IN (SELECT id FROM journal WHERE kind=?)", arrayOf<Any>(kind)) } catch (_: Exception) { }
                val n = db.delete("journal", "kind=?", arrayOf(kind))
                db.setTransactionSuccessful()
                n
            } finally { db.endTransaction() }
        }
    }
}
