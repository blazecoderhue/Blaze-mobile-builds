package com.blaze.assistant.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE

/**
 * [Sql] on the phone: AndroidX's bundled SQLite (androidx.sqlite:sqlite-bundled), a modern SQLite compiled into the
 * app with FTS5, so blaze.db behaves the same on every Android version (the system SQLite on older phones has no
 * FTS5). One connection, used by one thread at a time (every call holds this object's monitor; a transaction holds
 * it from BEGIN to COMMIT).
 */
class AndroidSql(path: String) : Sql {
    private val conn: SQLiteConnection = BundledSQLiteDriver().open(path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX)

    private fun bind(st: SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val n = i + 1
            when (a) {
                null -> st.bindNull(n)
                is String -> st.bindText(n, a)
                is Long -> st.bindLong(n, a)
                is Int -> st.bindLong(n, a.toLong())
                is Boolean -> st.bindLong(n, if (a) 1L else 0L)
                is Double -> st.bindDouble(n, a)
                is Float -> st.bindDouble(n, a.toDouble())
                else -> st.bindText(n, a.toString())
            }
        }
    }

    @Synchronized override fun exec(sql: String, vararg args: Any?) {
        val st = conn.prepare(sql)
        try { bind(st, args); while (st.step()) { } } finally { st.close() }
    }

    @Synchronized override fun <T> query(sql: String, vararg args: Any?, row: (Sql.Row) -> T): List<T> {
        val st = conn.prepare(sql)
        try {
            bind(st, args)
            val r = object : Sql.Row {
                override fun long(i: Int): Long = if (st.isNull(i)) 0L else st.getLong(i)
                override fun text(i: Int): String = if (st.isNull(i)) "" else st.getText(i)
            }
            val out = ArrayList<T>()
            while (st.step()) out += row(r)
            return out
        } finally { st.close() }
    }

    @Synchronized override fun <T> tx(block: () -> T): T {
        exec("BEGIN IMMEDIATE")
        val v = try { block() } catch (e: Throwable) { try { exec("ROLLBACK") } catch (_: Throwable) { }; throw e }
        exec("COMMIT")
        return v
    }

    @Synchronized override fun close() = conn.close()
}
