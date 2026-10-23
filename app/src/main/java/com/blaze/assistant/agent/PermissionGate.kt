package com.blaze.assistant.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * 0.45.20 (mobile item 15, user-approved review fix "ask for Accessibility only when a task first needs it, not in
 * setup"): setup no longer asks for phone control. The first time a task needs it, an in-chat card says why ("Turn on
 * phone control?"), the task waits while the user flips Blaze's switch (Blaze comes back to the front by itself), and the
 * same tool call goes on. "Not now", or no switch within [WAIT_MS], ends the step with an honest result and nothing
 * is tapped or read. Texts, contacts, calendar, mic and camera already ask in-line through Android's own prompt.
 *
 * The decision and the wait are pure ([await] takes its probes): PermissionGateTest drives them without a phone.
 */
object PermissionGate {
    /** Tools that act on or read another app's screen: they need phone control (Blaze's accessibility service). */
    val SCREEN_TOOLS: Set<String> = Orient.acting + Orient.looking + setOf("long_press", "swipe")

    const val WAIT_MS = 180_000L
    const val POLL_MS = 600L

    const val TITLE = "Turn on phone control?"
    const val REASON = "To do this, Blaze needs to see and tap the screen in other apps. Turn on Blaze under Accessibility; " +
        "you'll come straight back here. Blaze still asks before sending, paying or deleting."
    const val ACTION = "Open settings"

    /** Ask only when the tool needs it, the switch is off, and the user hasn't turned phone control off in Blaze. */
    fun shouldAsk(tool: String, serviceOn: Boolean, phoneControlAllowed: Boolean): Boolean =
        tool in SCREEN_TOOLS && !serviceOn && phoneControlAllowed

    enum class Outcome { GRANTED, DECLINED, TIMED_OUT }

    /**
     * Waits until [granted] is true (GRANTED), [answer] completes false (DECLINED), or [waitMs] passes (TIMED_OUT).
     * [answer] completing true just means "they went to turn it on": the wait goes on until the probe says so.
     */
    suspend fun await(granted: () -> Boolean, answer: CompletableDeferred<Boolean>, waitMs: Long = WAIT_MS,
                      pollMs: Long = POLL_MS, now: () -> Long = System::currentTimeMillis): Outcome {
        val end = now() + waitMs
        while (true) {
            if (granted()) return Outcome.GRANTED
            if (answer.isCompleted && answer.getCompleted() == false) return Outcome.DECLINED
            if (now() >= end) return Outcome.TIMED_OUT
            delay(pollMs)
        }
    }

    /** What the model gets when the step can't run: plain, final, no retry. */
    fun notDone(tool: String, o: Outcome): String = when (o) {
        Outcome.GRANTED -> ""
        Outcome.DECLINED -> "Not done: phone control is off and the user chose not to turn it on now, so nothing was tapped or read ($tool). " +
            "Say in one plain line that you couldn't do it without phone control and that they can turn it on any time. Don't retry."
        Outcome.TIMED_OUT -> "Not done: phone control is still off (it wasn't switched on), so nothing was tapped or read ($tool). " +
            "Say in one plain line that you couldn't do it without phone control. Don't retry."
    }
}
