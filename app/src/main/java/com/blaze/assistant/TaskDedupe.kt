package com.blaze.assistant

/**
 * v0.24.7 fix for "two Blaze windows in Recents" (pure, tested in TaskDedupeTest). Given this app's tasks, which
 * older task already holds [main] and should be brought back instead of keeping the new one ([mine])?
 * Null when there is no other Blaze chat task (the browser and the permission helper have their own tasks and are
 * left alone).
 */
object TaskDedupe {
    data class Row(val id: Int, val baseActivity: String?)

    fun keep(mine: Int, rows: List<Row>, main: String): Int? =
        rows.firstOrNull { it.id != mine && it.id > 0 && it.baseActivity == main }?.id
}
