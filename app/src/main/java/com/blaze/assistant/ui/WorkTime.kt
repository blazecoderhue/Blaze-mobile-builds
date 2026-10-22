package com.blaze.assistant.ui

import com.blaze.assistant.agent.ChatMessage

/**
 * v0.28.4: how long a turn's work took, for the work card ("Worked · 4 steps · 33s").
 *
 * Luke's screenshot showed "Worked · 4 steps · 97m 45s" over steps of 0–13 s: the card timed a run of steps from the
 * message before it (the user's prompt), so a Retry or a background agent's wake 97 minutes later counted the whole
 * gap. Now the run starts at the turn's real start, stamped on its first step ([ChatMessage.startAt]), and ends at the
 * message that closed it (the reply) or, when nothing closed it, its last step. A finished card never reads the clock,
 * so its total is frozen at finish however long the app sits in the background. Pure (WorkTimeTest).
 */
object WorkTime {
    /** Old chats have no turn stamp: the previous message counts only when it is this close before the first step. */
    const val LEGACY_GAP_MS = 2 * 60_000L

    /** When the run of [steps] started; [prevAt] = the message before it (0 = none). */
    fun start(steps: List<ChatMessage>, prevAt: Long): Long {
        val first = steps.firstOrNull() ?: return prevAt
        if (first.startAt > 0) return first.startAt
        if (first.at <= 0) return prevAt
        return if (prevAt > 0 && first.at >= prevAt && first.at - prevAt <= LEGACY_GAP_MS) prevAt else first.at
    }

    /**
     * When the run ended. Live: [now]. Finished: the reply that followed it ([nextAt], when [nextIsReply]) or its
     * last step; never a later prompt (a turn that was stopped would otherwise run until you next wrote).
     */
    fun end(steps: List<ChatMessage>, live: Boolean, nextAt: Long, nextIsReply: Boolean, now: Long): Long {
        if (live) return now
        val last = steps.lastOrNull()?.at ?: 0L
        return if (nextIsReply && nextAt >= last && nextAt > 0) nextAt else last
    }

    /** The total in ms (0 when unknown). */
    fun total(start: Long, end: Long): Long = if (start > 0 && end >= start) end - start else 0L
}
