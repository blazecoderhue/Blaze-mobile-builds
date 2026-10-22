package com.blaze.assistant.agent

import kotlinx.coroutines.asContextElement
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * v0.45.0 (#3): the per-turn and per-call state LlmClient used to keep in globals (lite / quick / heavy turn flags, the
 * turn's route pin, the last chunk time for the hang watchdog, usage, route, served-by, first-token time).
 *
 * Several things call the model at once (background agents, Halo's side lane, @all's two streams, specialists,
 * routines). With globals, one call's "lite" hint, usage, timing or last-chunk time leaked into another: a hung call was
 * never cut off while another chat streamed, a background agent's tool call could go out tagged as a chat-lane request,
 * and usage was charged to the wrong call.
 *
 * Now each turn (and each guarded or parallel call inside it, via [fork]) runs with its own CallState, carried by the
 * coroutine context ([element]) and read through a ThreadLocal, so LlmClient's old property names keep working and
 * resolve to the state of the coroutine that reads them. Code outside any turn (learning, summaries) shares [GLOBAL],
 * as before. A forked call's results are [publish]ed to its parent when it ends; its chunks beat the parent at once.
 *
 * Pure JVM (kotlinx.coroutines + org.json); CallStateTest.
 */
class CallState(val parent: CallState? = null) {
    @Volatile var lite = false
    @Volatile var quick = false
    @Volatile var heavy = false
    @Volatile var turnModel: TurnModel? = null

    /** Wall-clock time of the latest streamed chunk of a call in this state (or a child's). */
    val chunkAt = AtomicLong(0)
    @Volatile var usage: JSONObject? = null
    @Volatile var route = ""
    @Volatile var servedBy = ""
    @Volatile var servedKey = ""
    @Volatile var firstTokenMs = 0L
    @Volatile var firstTokenAt = 0L
    @Volatile var callStart = 0L
    @Volatile var gotFirst = false
    /** v0.45.3: ms from sending to the response headers (the gateway answered), and to the first visible word (not reasoning). */
    /** v0.45.3: the longest pause between two text chunks of the last streamed reply (stall diagnosis in the speed line). */
    @Volatile var maxGapMs = 0L
    @Volatile var headersMs = 0L
    @Volatile var firstTextMs = 0L
    @Volatile var firstTextAt = 0L
    @Volatile var callHeaders: Map<String, String> = emptyMap()
    /** v0.45.5 "What happened": the gateway's route / timing headers of the last call (TurnTrace.HEADERS). */
    @Volatile var gw: Map<String, String> = emptyMap()
    /** v0.45.5: every X-Request-Id this turn got, and the errors / retries along the way. */
    val requestIds = java.util.concurrent.CopyOnWriteArrayList<String>()
    val errors = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** A chunk arrived: this state and every parent see it (an outer watchdog must not cut a call that is talking). */
    fun beat(at: Long = System.currentTimeMillis()) {
        var s: CallState? = this
        while (s != null) { s.chunkAt.accumulateAndGet(at) { a, b -> maxOf(a, b) }; s = s.parent }
    }

    /** A child for one call: same turn flags and route pin, its own results. */
    fun fork(): CallState = CallState(this).also { it.lite = lite; it.quick = quick; it.heavy = heavy; it.turnModel = turnModel }

    /** Hands this call's results to its parent (the turn reads them after the call, as it read the globals before). */
    fun publish() {
        val p = parent ?: return
        p.usage = usage; p.route = route; p.servedBy = servedBy; p.servedKey = servedKey
        p.firstTokenMs = firstTokenMs; p.firstTokenAt = firstTokenAt; p.callHeaders = callHeaders
        p.maxGapMs = maxGapMs; p.headersMs = headersMs; p.firstTextMs = firstTextMs; p.firstTextAt = firstTextAt
        if (gw.isNotEmpty()) p.gw = gw
        p.requestIds.addAll(requestIds.filter { it !in p.requestIds }); p.errors.addAll(errors)
        p.chunkAt.accumulateAndGet(chunkAt.get()) { a, b -> maxOf(a, b) }
    }

    /** The coroutine-context element that makes this the state of everything run inside it. */
    fun element(): CoroutineContext.Element = TL.asContextElement(this)

    companion object {
        private val TL = ThreadLocal<CallState?>()
        /** Shared by calls outside any turn (and by tests that don't set a state). */
        val GLOBAL = CallState()
        /** The state of the coroutine (thread) reading it. */
        fun current(): CallState = TL.get() ?: GLOBAL
        /** True when the caller runs inside a turn's own state. */
        fun scoped(): Boolean = TL.get() != null
    }
}
