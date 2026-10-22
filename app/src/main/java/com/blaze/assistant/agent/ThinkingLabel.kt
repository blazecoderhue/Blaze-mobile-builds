package com.blaze.assistant.agent

/**
 * v0.26.1: the chat no longer shows the model's raw reasoning. While a reasoning model thinks, a calm
 * "Thinking it through…" line shows; afterwards the reply carries a small "Thought it through · 4 s" (the seconds only
 * when they were measured). The reasoning text itself is kept with the chat as before (backward compatible) but is
 * never drawn, copied or quoted. Pure (ThinkingLabelTest).
 */
object ThinkingLabel {
    const val LIVE = "Thinking it through\u2026"
    const val DONE = "Thought it through"

    /** The line to show, or null for none: [hasThought] is whether the model sent any reasoning at all. */
    fun label(live: Boolean, hasThought: Boolean, ms: Long = 0L, show: Boolean = true): String? = when {
        !show || !hasThought -> null
        live -> LIVE
        else -> DONE + duration(ms).let { if (it.isEmpty()) "" else " \u00b7 $it" }
    }

    /** "4 s", "1 min 5 s"; "" under a second (not measured). */
    fun duration(ms: Long): String {
        if (ms < 1000) return ""
        val s = ((ms + 500) / 1000).coerceAtLeast(1)
        return if (s < 60) "$s s" else "${s / 60} min" + (if (s % 60 == 0L) "" else " ${s % 60} s")
    }

    /** Tracks how long a turn's reasoning streamed: first change to the last change. */
    class Clock {
        private var start = 0L; private var last = 0L
        fun update(text: String, now: Long = System.currentTimeMillis()) {
            if (text.isBlank()) { start = 0L; last = 0L; return }
            if (start == 0L) start = now
            last = now
        }
        fun ms(): Long = if (start == 0L) 0L else (last - start).coerceAtLeast(0L)
    }
}
