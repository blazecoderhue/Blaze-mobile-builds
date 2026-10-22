package com.blaze.assistant.store

import java.sql.Connection
import java.sql.PreparedStatement

/**
 * [Sql] for JVM tests: sqlite-jdbc (a modern SQLite with FTS5, like the phone's bundled one).
 *
 * Connects through sqlite-jdbc's own driver, not java.sql.DriverManager: DriverManager only hands out a driver
 * registered from the caller's class loader, and in a Robolectric test (its own sandbox class loader) the driver had
 * been registered from the plain test class loader by the JVM tests that ran first, so `JdbcSql()` failed there with
 * "No suitable driver found for jdbc:sqlite::memory:" (MindUpgradeTest.bothStoresAnswerTheSame, v0.26.3 CI).
 */
class JdbcSql(url: String = "jdbc:sqlite::memory:") : Sql {
    val conn: Connection = org.sqlite.JDBC().connect(url, java.util.Properties())
        ?: throw java.sql.SQLException("Not a SQLite URL: $url")

    private fun prep(sql: String, args: Array<out Any?>): PreparedStatement {
        val st = conn.prepareStatement(sql)
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.setNull(i + 1, java.sql.Types.NULL)
                is String -> st.setString(i + 1, a)
                is Long -> st.setLong(i + 1, a)
                is Int -> st.setLong(i + 1, a.toLong())
                is Boolean -> st.setLong(i + 1, if (a) 1L else 0L)
                is Double -> st.setDouble(i + 1, a)
                else -> st.setString(i + 1, a.toString())
            }
        }
        return st
    }

    @Synchronized override fun exec(sql: String, vararg args: Any?) { prep(sql, args).use { it.execute() } }

    @Synchronized override fun <T> query(sql: String, vararg args: Any?, row: (Sql.Row) -> T): List<T> = prep(sql, args).use { st ->
        st.executeQuery().use { rs ->
            val r = object : Sql.Row {
                override fun long(i: Int): Long = rs.getLong(i + 1)
                override fun text(i: Int): String = rs.getString(i + 1) ?: ""
            }
            val out = ArrayList<T>()
            while (rs.next()) out += row(r)
            out
        }
    }

    @Synchronized override fun <T> tx(block: () -> T): T {
        exec("BEGIN IMMEDIATE")
        val v = try { block() } catch (e: Throwable) { try { exec("ROLLBACK") } catch (_: Throwable) { }; throw e }
        exec("COMMIT")
        return v
    }

    @Synchronized override fun close() = conn.close()
}
