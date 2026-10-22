package com.blaze.assistant.home

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.Sql

/**
 * v0.29.0: Home board widgets and their cached content in blaze.db. Prefixed tables (`home_widgets`, `home_cache`,
 * `home_meta`) next to the memories; schema v1 and `user_version` are untouched. Every statement is idempotent
 * (CREATE … IF NOT EXISTS, columns added only when missing), so opening it again is always safe.
 */
class HomeStore(private val sql: Sql) {
    private val lock = Any()

    init { migrate() }

    private fun migrate() {
        for (st in BlazeDb.statements(TABLES)) sql.exec(st)
        // Later columns are added here with addColumn(...) so old installs catch up without a schema bump.
        addColumn("home_widgets", "taps", "INTEGER NOT NULL DEFAULT 0")
        setMeta("schema", SCHEMA.toString())
    }

    private fun columns(table: String): Set<String> = sql.query("PRAGMA table_info($table)") { it.text(1) }.toSet()

    private fun addColumn(table: String, col: String, def: String) {
        if (col !in columns(table)) sql.exec("ALTER TABLE $table ADD COLUMN $col $def")
    }

    fun meta(k: String): String = sql.query("SELECT v FROM home_meta WHERE k=?", k) { it.text(0) }.firstOrNull().orEmpty()
    fun setMeta(k: String, v: String) = sql.exec("INSERT INTO home_meta(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", k, v)

    private fun row(r: Sql.Row) = Widget(r.text(0), r.text(1), r.text(2), r.text(3), r.text(4), r.text(5), r.long(6).toInt(),
        r.long(7) != 0L, r.long(8), r.long(9), r.long(10).toInt())

    private val cols = "id, type, title, category, config, size, ord, hidden, created, updated, taps"

    /** Every widget, in board order (hidden ones too). */
    fun all(): List<Widget> = sql.query("SELECT $cols FROM home_widgets ORDER BY ord, created") { row(it) }

    fun get(id: String): Widget? = sql.query("SELECT $cols FROM home_widgets WHERE id=?", id) { row(it) }.firstOrNull()

    fun count(): Int = sql.query("SELECT count(*) FROM home_widgets") { it.long(0).toInt() }.first()

    /** Adds [w] at the end of the board (or at [w].order when ≥ 0 and [atEnd] is false). Returns the stored widget. */
    fun add(w: Widget, now: Long = System.currentTimeMillis(), atEnd: Boolean = true): Widget = synchronized(lock) {
        val id = w.id.ifBlank { newId(now) }
        val ord = sql.query("SELECT coalesce(max(ord), -1) FROM home_widgets") { it.long(0).toInt() }.first() + 1
        val s = w.copy(id = id, size = WidgetSize.norm(w.size), order = ord, created = if (w.created > 0) w.created else now, updated = now)
        sql.exec("INSERT OR REPLACE INTO home_widgets($cols) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            s.id, s.type, s.title, s.category, s.config, s.size, s.order, s.hidden, s.created, s.updated, s.taps)
        if (!atEnd) moveTo(id, w.order)
        get(id) ?: s
    }

    fun update(w: Widget, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        sql.exec("UPDATE home_widgets SET type=?, title=?, category=?, config=?, size=?, hidden=?, updated=? WHERE id=?",
            w.type, w.title, w.category, w.config, WidgetSize.norm(w.size), w.hidden, now, w.id)
    }

    fun remove(id: String) = synchronized(lock) {
        sql.tx {
            sql.exec("DELETE FROM home_widgets WHERE id=?", id)
            sql.exec("DELETE FROM home_cache WHERE id=?", id)
        }
        renumber(all().map { it.id })
    }

    fun tapped(id: String) = sql.exec("UPDATE home_widgets SET taps = taps + 1 WHERE id=?", id)

    /** Writes the board order exactly as [ids] (unknown ids ignored; widgets missing from [ids] keep their place after). */
    fun reorder(ids: List<String>) = synchronized(lock) {
        val known = all().map { it.id }
        val first = ids.filter { it in known }.distinct()
        renumber(first + known.filter { it !in first })
    }

    private fun renumber(ids: List<String>) = sql.tx { ids.forEachIndexed { i, id -> sql.exec("UPDATE home_widgets SET ord=? WHERE id=?", i, id) } }

    /** Moves one widget by [delta] places (−1 up, +1 down). False when it can't move. */
    fun move(id: String, delta: Int): Boolean = synchronized(lock) {
        val ids = all().map { it.id }.toMutableList()
        val i = ids.indexOf(id); if (i < 0) return false
        val j = (i + delta).coerceIn(0, ids.size - 1); if (j == i) return false
        ids.removeAt(i); ids.add(j, id)
        renumber(ids); true
    }

    /** Puts [id] at position [to] (0-based, clamped). */
    fun moveTo(id: String, to: Int) = synchronized(lock) {
        val ids = all().map { it.id }.toMutableList()
        if (!ids.remove(id)) return
        ids.add(to.coerceIn(0, ids.size), id)
        renumber(ids)
    }

    // ---------------- cache ----------------

    fun data(id: String): WidgetData? = sql.query("SELECT data, fetched, error FROM home_cache WHERE id=?", id) {
        WidgetData.parse(it.text(0), it.long(1), it.text(2))
    }.firstOrNull()

    fun allData(): Map<String, WidgetData> = sql.query("SELECT id, data, fetched, error FROM home_cache") {
        it.text(0) to WidgetData.parse(it.text(1), it.long(2), it.text(3))
    }.toMap()

    /**
     * Saves a refresh result. A failure keeps the last good content (and its time) and only records the error line, so
     * the card still shows cached data with a stale tag.
     */
    fun saveData(id: String, d: WidgetData, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        try { com.blaze.assistant.agent.TurnContextSource.invalidate() } catch (_: Throwable) { }   // v0.45.9: weather feeds the [context] block
        val old = data(id)
        if (d.error.isNotBlank() && old != null && !old.empty) {
            sql.exec("UPDATE home_cache SET error=? WHERE id=?", d.error, id)
            // Keep the button the failure asks for (e.g. "Allow location") without throwing the content away.
            if (d.action.isNotBlank()) sql.exec("UPDATE home_cache SET data=? WHERE id=?", old.copy(action = d.action).toJson(), id)
        } else {
            val fetched = if (d.error.isBlank()) now else 0L
            sql.exec("INSERT INTO home_cache(id, data, fetched, error) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data, fetched=excluded.fetched, error=excluded.error",
                id, d.toJson(), fetched, d.error)
        }
    }

    fun clearError(id: String) = sql.exec("UPDATE home_cache SET error='' WHERE id=?", id)

    companion object {
        const val SCHEMA = 1
        const val TABLES = """CREATE TABLE IF NOT EXISTS home_widgets(id TEXT PRIMARY KEY, type TEXT NOT NULL, title TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', config TEXT NOT NULL DEFAULT '{}', size TEXT NOT NULL DEFAULT 'M', ord INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS home_widgets_ord ON home_widgets(ord);
CREATE TABLE IF NOT EXISTS home_cache(id TEXT PRIMARY KEY, data TEXT NOT NULL DEFAULT '{}', fetched INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS home_meta(k TEXT PRIMARY KEY, v TEXT NOT NULL DEFAULT '');"""

        private val rnd = java.security.SecureRandom()
        fun newId(now: Long): String = "w" + java.lang.Long.toString(now, 36).takeLast(6) + Integer.toString(rnd.nextInt(36 * 36 * 36), 36).padStart(3, '0')
    }
}
