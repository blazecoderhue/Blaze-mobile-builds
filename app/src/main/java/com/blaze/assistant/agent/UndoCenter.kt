package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v0.39.0 Undo bar: when Blaze finishes an undoable action on its own (Autopilot), the chat shows "<what> · You have
 * 10 seconds to undo" with an Undo button. Only actions with a real way back register one ([undoFor]); everything
 * else just gets its plain line. Pure parts: [secondsLeft], [label], [undoable] (UndoCenterTest).
 */
object UndoCenter {
    const val WINDOW_MS = 10_000L

    data class Offer(val label: String, val at: Long, val undo: () -> String, val n: Long = System.nanoTime())

    val current = MutableStateFlow<Offer?>(null)

    /** Whole seconds left on the bar (10 … 0). */
    fun secondsLeft(o: Offer, now: Long): Int = (((o.at + WINDOW_MS - now).coerceAtLeast(0) + 999) / 1000).toInt()

    fun expired(o: Offer, now: Long): Boolean = now - o.at >= WINDOW_MS

    /** Tools that have a real way back. */
    val undoable = setOf("create_routine", "schedule_command", "delete_routine")

    /** The headline for a finished action ("Reminder set: Morning brief"). */
    fun label(tool: String, name: String): String = when (tool) {
        "create_routine" -> "Scheduled \u201c${name.take(40)}\u201d"
        "schedule_command" -> "Scheduled \u201c${name.take(40)}\u201d"
        "delete_routine" -> "Removed \u201c${name.take(40)}\u201d"
        else -> "Done"
    }

    fun offer(label: String, undo: () -> String, now: Long = System.currentTimeMillis()) { current.value = Offer(label, now, undo, System.nanoTime()) }

    /** Runs the undo once (the bar goes away) and returns its line for the chat. */
    fun undoNow(): String? {
        val o = current.value ?: return null
        current.value = null
        return try { o.undo() } catch (e: Throwable) { "Couldn't undo: ${e.message}" }
    }

    fun dismiss() { current.value = null }

    /**
     * Called after every tool. Under Autopilot, a finished routine change registers its way back. Never throws.
     * [before] is the routine list as it was before the tool ran (for delete), from [snapshot].
     */
    fun afterTool(tool: String, args: org.json.JSONObject, result: String, autopilot: Boolean, before: List<com.blaze.assistant.routine.Routine>?) {
        if (!autopilot || tool !in undoable || result.startsWith("Error") || result.startsWith("No routine") || result.startsWith("Unknown")) return
        try {
            val R = com.blaze.assistant.routine.Routines
            when (tool) {
                "create_routine", "schedule_command" -> {
                    val name = args.optString("name")
                    val made = R.all.value.lastOrNull { it.name == name.trim() || it.name == name } ?: return
                    offer(label(tool, made.name), { R.delete(made.id); "Undone: \u201c${made.name}\u201d won't run." })
                }
                "delete_routine" -> {
                    val gone = before.orEmpty().firstOrNull { b -> R.all.value.none { it.id == b.id } } ?: return
                    offer(label(tool, gone.name), { R.restore(gone); "Undone: \u201c${gone.name}\u201d is back." })
                }
            }
        } catch (_: Throwable) { }
    }

    fun snapshot(tool: String): List<com.blaze.assistant.routine.Routine>? =
        if (tool == "delete_routine") try { com.blaze.assistant.routine.Routines.all.value } catch (_: Throwable) { null } else null
}
