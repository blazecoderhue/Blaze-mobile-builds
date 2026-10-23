package com.blaze.assistant.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.IOException

/**
 * 0.45.20 delivery status (files/from-site/STEPS_DELIVERY_CONTRACT.md §1): Sending > Sent > Read under the user's own
 * latest bubble. No ticks: a quiet Blaze-style label (ui/DeliveryStatus.kt).
 *
 *  - Sending: from the tap until a 2xx comes back (local only).
 *  - Sent: any 2xx of POST /v1/runs, or the `message_received` event.
 *  - Read: the `message_read` event, or `read != null` in a create / retry answer. NEVER from a timer.
 *  - Not sent: a 4xx other than 408 / 429 (no auto retry; tap to retry with the same client_msg_id).
 *
 * The client_msg_id is made once per bubble (from its stable gid) and kept with it ([Tracker]), so every retry of the
 * same send (offline backoff, reconnect, tap to retry) reuses it and the gateway never makes a second message or run.
 * Pure state machine + send loop; DeliveryTest drives them with a fake gateway and a fake clock.
 */
enum class DeliveryState { SENDING, SENT, READ, NOT_SENT }

object Delivery {
    /** Contract §1 rule 2: 1, 2, 4, 8, 16, 30, 30 … s. */
    val BACKOFF_S = longArrayOf(1, 2, 4, 8, 16, 30)

    fun backoffMs(attempt: Int): Long = BACKOFF_S[attempt.coerceIn(0, BACKOFF_S.size - 1)] * 1000L

    /** The most a Retry-After may hold a send (a broken header can't park the bubble for hours). */
    const val MAX_RETRY_AFTER_MS = 10 * 60_000L

    /** Retry-After as delta-seconds or an HTTP-date; null when absent or unreadable. */
    fun retryAfterMs(v: String?, now: Long): Long? {
        val s = v?.trim().orEmpty()
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { return (it.coerceAtLeast(0) * 1000L).coerceAtMost(MAX_RETRY_AFTER_MS) }
        s.toDoubleOrNull()?.let { return (it.coerceAtLeast(0.0) * 1000).toLong().coerceAtMost(MAX_RETRY_AFTER_MS) }
        return try {
            val f = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            (f.parse(s)!!.time - now).coerceIn(0, MAX_RETRY_AFTER_MS)
        } catch (_: Exception) { null }
    }

    /** What one POST /v1/runs answer means for the bubble. */
    enum class Verdict { OK, RETRY, RETRY_AFTER, REJECTED, OTHER }

    /**
     * 2xx = OK; 408 = RETRY (backoff); 429 = RETRY_AFTER; other 4xx = REJECTED ("Not sent, tap to retry"), except the
     * "no runs on this gateway" codes ([noRuns]) and 413 (the agent folds the chat and asks again), which are OTHER:
     * the caller keeps its old handling (local loop / compaction). 5xx = OTHER too (the caller's fallback).
     */
    fun verdict(code: Int, noRuns: (Int) -> Boolean = { it == 404 || it == 405 || it == 501 }): Verdict = when {
        code in 200..299 -> Verdict.OK
        code == 408 -> Verdict.RETRY
        code == 429 -> Verdict.RETRY_AFTER
        noRuns(code) || code == 413 || code == 401 || code == 403 -> Verdict.OTHER
        code in 400..499 -> Verdict.REJECTED
        else -> Verdict.OTHER
    }

    /** Things that move a bubble's state. */
    enum class Ev { POSTED, RECEIVED, READ, NET_ERROR, REJECTED, STOPPED, USER_RETRY, LOCAL_PICKUP, LOCAL_READ }

    /**
     * The one transition function. Monotone: once Sent it never goes back to Sending, once Read it stays Read.
     * A network error never downgrades anything (offline = it simply stays where it is).
     */
    fun next(s: DeliveryState, e: Ev): DeliveryState = when (e) {
        Ev.POSTED, Ev.RECEIVED, Ev.LOCAL_PICKUP -> if (s == DeliveryState.READ) s else DeliveryState.SENT
        Ev.READ, Ev.LOCAL_READ -> DeliveryState.READ
        Ev.NET_ERROR -> s
        // Rejected (4xx) or stopped before the gateway had it: honestly not sent; tap to retry with the same id.
        Ev.REJECTED, Ev.STOPPED -> if (s == DeliveryState.SENDING) DeliveryState.NOT_SENT else s
        Ev.USER_RETRY -> if (s == DeliveryState.NOT_SENT) DeliveryState.SENDING else s
    }

    /** The label under the bubble (the dots of Sending are drawn by the UI). */
    fun label(s: DeliveryState): String = when (s) {
        DeliveryState.SENDING -> "Sending"
        DeliveryState.SENT -> "Sent"
        DeliveryState.READ -> "Read"
        DeliveryState.NOT_SENT -> "Not sent, tap to retry"
    }

    /** What TalkBack says (politely) when the state changes. */
    fun spoken(s: DeliveryState): String = when (s) {
        DeliveryState.SENDING -> "Sending your message"
        DeliveryState.SENT -> "Message sent"
        DeliveryState.READ -> "Blaze read your message"
        DeliveryState.NOT_SENT -> "Message not sent. Double tap to retry"
    }

    /** Run events that matter for delivery: the event type, or null. Unknown types are ignored (contract). */
    fun eventOf(o: JSONObject): Ev? = when (o.optString("type")) {
        "message_received" -> Ev.RECEIVED
        "message_read" -> Ev.READ
        POSTED -> Ev.POSTED
        REJECTED -> Ev.REJECTED
        STOPPED -> Ev.STOPPED
        else -> null
    }

    /** The create / retry answer's own `received` / `read` fields (contract §1): events to apply, in order. */
    fun fromCreate(o: JSONObject): List<Ev> = buildList {
        if (o.has("received") && !o.isNull("received")) add(Ev.RECEIVED)
        if (o.has("read") && !o.isNull("read")) add(Ev.READ)
    }

    /** Synthetic event the client raises on its own 2xx (Sent) so one hook carries every delivery change. */
    const val POSTED = "_posted"
    const val REJECTED = "_rejected"
    const val STOPPED = "_stopped"

    /** A bubble's client_msg_id: made once from its gid; [gen] > 0 only when an already-delivered message is asked again. */
    fun cidFor(gid: String, gen: Int = 0): String = CloudRuns.safeId("m_" + gid.ifBlank { "x" } + if (gen > 0) ":r$gen" else "")

    /** Thrown when the gateway refused the message (4xx): the bubble says "Not sent, tap to retry"; no error bubble. */
    class NotSent(val code: Int) : Exception("not sent ($code)")

    // ---------------------------------------------------------------- send loop

    /**
     * Posts until the gateway answers, the way the contract wants: a network error keeps the bubble at Sending and waits
     * 1, 2, 4, 8, 16, 30, 30 … s ([backoffMs]) or less when [wait] returns early (connectivity came back); 408 backs
     * off the same way; 429 waits Retry-After; another 4xx throws [NotSent]. Every attempt is the SAME request (the same
     * client_msg_id), so whatever lands, the gateway keeps one message and one run. Returns the 2xx answer, or any
     * answer the caller handles itself ([Verdict.OTHER]). Cancellation (Stop) ends it.
     */
    suspend fun <R> deliver(
        post: suspend () -> R,
        codeOf: (R) -> Int,
        retryAfterOf: (R) -> String?,
        close: (R) -> Unit,
        wait: suspend (Long) -> Unit,
        now: () -> Long,
        onEvent: (Ev) -> Unit,
        noRuns: (Int) -> Boolean = { it == 404 || it == 405 || it == 501 },
    ): R {
        var k = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val r = try { post() } catch (e: CancellationException) { throw e } catch (e: IOException) {
                onEvent(Ev.NET_ERROR)
                wait(backoffMs(k++))
                continue
            }
            val code = codeOf(r)
            when (verdict(code, noRuns)) {
                Verdict.OK -> { onEvent(Ev.POSTED); return r }
                Verdict.RETRY -> { close(r); wait(backoffMs(k++)) }
                Verdict.RETRY_AFTER -> { val ra = retryAfterMs(retryAfterOf(r), now()); close(r); wait(ra ?: backoffMs(k++)) }
                Verdict.REJECTED -> { close(r); onEvent(Ev.REJECTED); throw NotSent(code) }
                Verdict.OTHER -> return r
            }
        }
    }

    // ---------------------------------------------------------------- the app's record

    /**
     * Each bubble's state and client_msg_id, by the bubble's gid. Kept in one preference (JSON, newest [KEEP]) so a
     * restart still shows the right label and a tap-to-retry after a restart reuses the same id.
     */
    class Tracker(private val kvGet: (String) -> String?, private val kvPut: (String, String) -> Unit) {
        data class Entry(val state: DeliveryState, val cid: String, val gen: Int = 0)

        private val _states = MutableStateFlow<Map<String, DeliveryState>>(emptyMap())
        /** gid -> state, for the UI. */
        val states: StateFlow<Map<String, DeliveryState>> = _states.asStateFlow()
        private val lock = Any()
        private var loaded = false
        private val map = LinkedHashMap<String, Entry>()

        private fun load() {
            if (loaded) return
            loaded = true
            try {
                val o = JSONObject(kvGet(KEY) ?: "{}")
                o.keys().forEach { g ->
                    val e = o.optJSONObject(g) ?: return@forEach
                    val st = try { DeliveryState.valueOf(e.optString("s")) } catch (_: Exception) { DeliveryState.SENT }
                    map[g] = Entry(st, e.optString("c"), e.optInt("g"))
                }
            } catch (_: Exception) { }
            _states.value = map.mapValues { it.value.state }
        }

        private fun save() {
            while (map.size > KEEP) map.remove(map.keys.first())
            val o = JSONObject()
            map.forEach { (g, e) -> o.put(g, JSONObject().put("s", e.state.name).put("c", e.cid).put("g", e.gen)) }
            try { kvPut(KEY, o.toString()) } catch (_: Throwable) { }
            _states.value = map.mapValues { it.value.state }
        }

        fun state(gid: String): DeliveryState? = synchronized(lock) { load(); map[gid]?.state }

        /**
         * The client_msg_id for sending [gid] now. A bubble that never reached the server (Sending / Not sent) keeps
         * its id (a retry of the same send); one that was already delivered gets a new generation (asked again).
         */
        fun cidForSend(gid: String): String = synchronized(lock) {
            load()
            val e = map[gid]
            val out = when {
                e == null -> Entry(DeliveryState.SENDING, cidFor(gid))
                e.state == DeliveryState.SENDING || e.state == DeliveryState.NOT_SENT -> e.copy(state = DeliveryState.SENDING, cid = e.cid.ifBlank { cidFor(gid, e.gen) })
                else -> (e.gen + 1).let { g -> Entry(DeliveryState.SENDING, cidFor(gid, g), g) }
            }
            map.remove(gid); map[gid] = out; save()
            out.cid
        }

        /** Applies [ev] to every bubble in [gids]. */
        fun apply(gids: Collection<String>, ev: Delivery.Ev) = synchronized(lock) {
            load()
            var changed = false
            gids.forEach { g ->
                val e = map[g] ?: Entry(DeliveryState.SENDING, cidFor(g))
                val n = next(e.state, ev)
                if (n != e.state || g !in map) { map[g] = e.copy(state = n); changed = true }
            }
            if (changed) save()
        }

        /** Marks [gid] as sending (a fresh bubble, before any network). */
        fun sending(gid: String) = synchronized(lock) {
            load()
            if (gid !in map) { map[gid] = Entry(DeliveryState.SENDING, cidFor(gid)); save() }
        }

        companion object {
            const val KEY = "delivery_v1"
            const val KEEP = 200
        }
    }

    @Volatile private var tracker: Tracker? = null

    /** The app's tracker (Settings-backed). Tests make their own [Tracker]. */
    fun tracker(): Tracker = tracker ?: synchronized(this) {
        tracker ?: Tracker({ k -> try { Settings.str(k, "").ifEmpty { null } } catch (_: Throwable) { null } },
            { k, v -> try { Settings.setStr(k, v) } catch (_: Throwable) { } }).also { tracker = it }
    }

    /** Connectivity came back: every send waiting out its backoff tries again at once (contract §1 rule 2). */
    val wake = MutableStateFlow(0L)
    fun reconnected() { wake.value = wake.value + 1 }

    /**
     * Waits [ms] in one-second slices (calling [beat] so the turn's silence watchdog stays quiet), or less when [wake]
     * changes meanwhile.
     */
    suspend fun waitOrWake(ms: Long, sleep: suspend (Long) -> Unit, beat: () -> Unit, wakeNow: () -> Long = { wake.value }) {
        val start = wakeNow()
        var left = ms
        while (left > 0) {
            if (wakeNow() != start) return
            val s = minOf(1_000L, left); sleep(s); left -= s; beat()
        }
    }
}
