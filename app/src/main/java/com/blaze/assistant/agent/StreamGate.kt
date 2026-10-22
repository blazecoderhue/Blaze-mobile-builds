package com.blaze.assistant.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * v0.31.0 token streaming: conflates a fast stream of "text so far" snapshots (any thread) into at most one [apply] per
 * display frame on [scope] (the agent's main-thread scope). Only the newest snapshot is applied; older ones in between
 * are dropped (each snapshot already holds the whole text so far, so nothing is lost).
 *
 * [flush] applies whatever is waiting right now (call it on the main thread when the model call returns, so the final
 * text lands before the reply is finalised and never after it). [close] drops anything still queued.
 */
class StreamGate(private val scope: CoroutineScope, private val apply: (String) -> Unit) {
    private val latest = AtomicReference<String?>(null)
    private val scheduled = AtomicBoolean(false)
    @Volatile private var closed = false

    fun offer(text: String) {
        if (closed) return
        latest.set(text)
        if (scheduled.compareAndSet(false, true)) scope.launch {
            // 0.45.16 smoothness (low-end phones): at most one bubble update every [MIN_GAP_MS]. Each update re-splits and
            // re-renders the whole reply so far; at 60 a second that was most of a low-end frame budget while streaming.
            val wait = gapLeft(lastApplied, (System.nanoTime() / 1_000_000L))
            if (wait > 0) kotlinx.coroutines.delay(wait)
            try { awaitFrame() } catch (_: IllegalStateException) { /* no Choreographer (tests): apply now */ }
            scheduled.set(false)
            val v = latest.getAndSet(null) ?: return@launch
            if (!closed) { lastApplied = (System.nanoTime() / 1_000_000L); apply(v) }
        }
    }

    @Volatile private var lastApplied = 0L

    companion object {
        const val MIN_GAP_MS = 50L
        /** Pure (unit-tested): how long to wait before the next update, given the last one. */
        fun gapLeft(lastAppliedMs: Long, nowMs: Long): Long = if (lastAppliedMs <= 0L) 0L else (lastAppliedMs + MIN_GAP_MS - nowMs).coerceIn(0L, MIN_GAP_MS)
    }

    fun flush() {
        val v = latest.getAndSet(null) ?: return
        if (!closed) apply(v)
    }

    fun close() { closed = true; latest.set(null) }

    /** v0.45.0 (#5): true after [close] (Stop): callers drop late callbacks too. */
    val isClosed: Boolean get() = closed
}
