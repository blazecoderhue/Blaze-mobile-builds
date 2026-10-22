package com.blaze.assistant.agent

/**
 * v0.45.9 (user, 12:12: "save this file" took 56 minutes, 11:15 → 12:11 CAT). What in the code let one turn run that long:
 *  - A normal chat turn goes to the gateway as a Cloud run (CloudRuns). CloudRuns.follow reconnects to the run's stream
 *    in a `while (true)` loop for as long as the gateway keeps the run alive (queued / retrying on its side), and every
 *    reconnect and keep-alive beat counts as activity, so the 100 s silence watchdog (Agent.idleGuard) never fired.
 *  - The only turn clock was Autopilot's (Modes.apMinutes, 45 min by default, and Autopilot is the default), and it was
 *    checked only between model steps, never during one, so a single stuck step could outlive it.
 * Now every model step has a hard wall-clock cap ([STEP_MS]; a Cloud run gets [CLOUD_STEP_MS] and is then cancelled on
 * the gateway and the step is sent directly once), and every turn has a hard end ([turnMs]), checked during the step too.
 * Pure Kotlin; TurnDeadlineTest.
 */
object TurnDeadline {
    /** A Cloud run step: after this the run is cancelled and the same step goes direct. */
    const val CLOUD_STEP_MS = 75_000L
    /** Any one model step, whatever lane, retries included. */
    const val STEP_MS = 150_000L
    /** A normal turn (not Autopilot long work). */
    const val TURN_MS = 5 * 60_000L

    /** The hard turn length: Autopilot keeps its own budget (long work), every other turn [TURN_MS]. */
    fun turnMs(autopilot: Boolean, apMinutes: Int, longWork: Boolean): Long =
        if (autopilot && longWork) apMinutes.coerceIn(5, 240) * 60_000L else TURN_MS

    /** What one step may still take at [now] for a turn ending at [end] (never below 5 s, so the last step can answer). */
    fun stepBudget(end: Long, now: Long, cap: Long = STEP_MS): Long = minOf(cap, maxOf(5_000L, end - now))

    fun line(ms: Long) = "This reply hit its ${ms / 60_000} minute limit, so I stopped it rather than keep you waiting. Send it again and I'll answer directly."
    const val CLOUD_SLOW = "The cloud run was slow, answering directly"
}
