package com.blaze.assistant.agent

import org.junit.Assume.assumeTrue

/**
 * v0.45.0 (#28): Robolectric's native SQLite runtime has no linux-aarch64 build ("The Robolectric native runtime is not
 * supported on Linux (aarch64)"), so tests that open a framework SQLiteOpenHelper (Mind.Db, LongChat.Index) can't run on
 * ARM Linux boxes. They are SKIPPED there with this reason (never reported as passed) and run normally on x86 CI.
 * The probe opens a real in-memory database, so it never skips where SQLite works.
 */
object NativeSqlite {
    val works: Boolean by lazy {
        try { android.database.sqlite.SQLiteDatabase.create(null).use { it.execSQL("CREATE TABLE t(x)") }; true }
        catch (_: Throwable) { false }
    }
    fun assume() = assumeTrue("Robolectric native SQLite doesn't load on ${System.getProperty("os.name")} ${System.getProperty("os.arch")} (runs on x86 CI)", works)
}
