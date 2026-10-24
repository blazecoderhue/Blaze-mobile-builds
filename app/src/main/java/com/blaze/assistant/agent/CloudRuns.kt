package com.blaze.assistant.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException

/**
 * v0.42.0 Cloud runs (gateway 0.5.0, `/v1/runs`). Each message goes to the gateway ONCE, as a run with a client
 * message id; the gateway runs the model loop and keeps the reply, so a dropped connection or a closed app no longer
 * loses the answer. The phone follows the run's numbered events (SSE, resumed with Last-Event-ID), runs the tool calls
 * that need the phone with the agent's own executor (same approval rules) and posts the results back.
 *
 * How it sits in the agent: [Turn] wraps one user turn. The agent loop calls [Client.complete] where it used to call
 * LlmClient.complete: the first call creates the run and returns at `done` (the reply) or `needs_device` (an assistant
 * message with tool_calls, exactly what the local loop would get). After the agent ran the tools, its next call sees
 * the tool results at the end of the messages and posts them to the same run instead of starting over.
 *
 * Falls back to the local loop ([Fallback]) when the gateway is older (404 / 405: no runs for [OFF_MS]), unreachable
 * before a run exists, refuses the request (413, 5xx, 503 not_configured) or a run fails on the gateway's side.
 *
 * The pure parts (SSE parsing, the event reducer, plain lines, decisions, tool-result matching) are tested on the JVM
 * (CloudRunsTest), and the client runs against a fake gateway through [Http].
 */
object CloudRuns {
    const val PREF = "cloud_runs"
    /** After a 404 / 405 on POST /v1/runs (a gateway older than 0.5.0): local loop for this long. */
    const val OFF_MS = 6 * 3600_000L
    /** How long one turn keeps waiting for the connection to come back before it says the reply is safe. */
    const val MAX_OFFLINE_MS = 10 * 60_000L
    /** A run the store has kept longer than this is forgotten (the gateway deletes it after 24 h anyway). */
    const val KEEP_MS = 24 * 3600_000L

    // Plain lines (the quiet working line in Simple mode, the step card's title in Advanced).
    const val SENDING = "Sending\u2026"
    const val QUEUED = "Sent. Waiting for Blaze\u2026"
    const val RUNNING = "Blaze is thinking\u2026"
    const val ON_PHONE = "Doing that on your phone\u2026"
    const val OFFLINE = "Waiting for internet \u2014 your reply is safe\u2026"
    const val SAFE = "Connection lost. Your reply is safe and will show up here when you're back online."
    const val BUSY_ALL = "Every model is busy right now. Tap Retry in a moment."
    const val EXPIRED = "This reply timed out. Tap Retry to send it again."
    const val NO_REPLY = "No answer came back. Tap Retry to try again."
    const val STOPPED = "Stopped."
    const val CUT_SHORT = "I was doing something on your phone when Blaze closed, so that task stopped. Send it again and I'll finish it."

    fun waiting(ms: Long): String = "Busy right now, trying again in ${maxOf(1L, (ms + 999) / 1000)}s\u2026"

    /** The plain line for a run status event, or null when the status needs no line of its own. */
    fun line(status: String, retryInMs: Long = 0): String? = when (status) {
        "queued" -> QUEUED
        "running" -> RUNNING
        "waiting" -> waiting(retryInMs)
        "needs_device" -> ON_PHONE
        else -> null
    }

    /** A frame's data as a run event; the SSE event name fills a missing "type", and a non-JSON `ack` is its text. */
    fun frameEvent(f: Frame): JSONObject {
        val o = try { JSONObject(f.data) } catch (e: org.json.JSONException) {
            if (f.event == "ack" && f.data.isNotBlank()) JSONObject().put("text", f.data.trim()) else throw e
        }
        if (!o.has("type") && f.event.isNotBlank() && f.event != "message") o.put("type", f.event)
        return o
    }

    /** The text of an `ack` event: `text` (or `message`), trimmed; "" when absent. */
    fun ackText(ev: JSONObject): String {
        // Gateway 0.8.38 contract (files/from-site/RUNS_ACK_CONTRACT.md): {ack:{intent,line,details}} on the 202 / `run`
        // frame and as event #2; older drafts used text / message. A null ack (greeting, plain question) = "".
        ev.optJSONObject("ack")?.let { a -> return a.optString("line").trim() }
        return ev.optString("line").ifBlank { ev.optString("text") }.ifBlank { ev.optString("message") }.trim()
    }

    /** The user's IANA zone, sent on every create (contract §1: exact times are never assumed). */
    @Volatile var tz: () -> String = { try { java.util.TimeZone.getDefault().id } catch (_: Throwable) { "" } }

    /** Thrown when this step should go the old way (LlmClient.complete). Never shown to anyone. */
    class Fallback(val why: String) : Exception(why)

    /** What a failed run means for the person: an error line (an exception to show), or null = do it locally. */
    fun failure(code: String): LlmClient.LlmException? = when (code) {
        "quota_exceeded" -> LlmClient.LlmException(Cloud.friendly(429, "quota_exceeded"), 429)
        "models_busy" -> LlmClient.LlmException(BUSY_ALL, 503)
        "expired" -> LlmClient.LlmException(EXPIRED, 408)
        "no_reply" -> LlmClient.LlmException(NO_REPLY, EmptyReply.CODE)
        // The agent folds older turns on 413 (AutoCompact) and asks again: a new run with the shorter request.
        "too_large" -> LlmClient.LlmException("This conversation is too large (too_large)", 413)
        else -> null   // model_rejected, too_many_steps, internal_error, not_configured…: the local loop copes
    }

    /** True when a create answer means the gateway has no Cloud runs at all (older than 0.5.0). */
    fun noRuns(code: Int): Boolean = code == 404 || code == 405 || code == 501

    /** Client ids the gateway accepts: 4–128 of A-Z a-z 0-9 . _ : - */
    fun safeId(s: String): String {
        val t = s.replace(Regex("[^A-Za-z0-9._:-]"), "_").take(110)
        return if (t.length < 4) t.padEnd(4, '_') else t
    }

    /**
     * The tool results for every id in [pending] when they are the newest messages (only tool results after the
     * assistant's call), else null: anything newer (a nudge, a message you sent meanwhile, a screenshot) means the
     * run's own copy of the conversation is out of date and a new run is needed.
     */
    fun resultsFor(messages: JSONArray, pending: List<String>): JSONArray? = resultsBefore(messages, messages.length(), pending)

    private fun resultsBefore(messages: JSONArray, end: Int, pending: List<String>): JSONArray? {
        if (pending.isEmpty()) return null
        val found = HashMap<String, String>()
        var i = end - 1
        while (i >= 0) {
            val m = messages.optJSONObject(i) ?: break
            if (m.optString("role") != "tool") break
            val c = m.opt("content") as? String ?: return null
            found.putIfAbsent(m.optString("tool_call_id"), c)
            i--
        }
        if (!pending.all { it in found }) return null
        return JSONArray().apply { pending.forEach { put(JSONObject().put("tool_call_id", it).put("content", found[it])) } }
    }

    /** v0.45.0 (#6): most notes that may ride along with one set of tool results; more means the run is really out of date. */
    const val MAX_NOTES = 4

    /**
     * v0.45.0 (#6): the tool results for [pending] plus what the phone added after them in the same step: screenshots
     * ([shots], the user messages with an image) and short notes (claim / proof / leak nudges, "[The user adds, while
     * you work: …]"), folded into the last result's content so the run continues with them instead of being cancelled
     * and re-created with the whole conversation. The gateway's tool_results take text only, so a screenshot is turned
     * into words by the caller (Turn.describe) before it is sent. null = something else is newer (an assistant
     * message, a missing result, too many notes): a new run is needed.
     */
    data class Continue(val results: JSONArray, val shots: List<JSONObject>, val notes: Int)

    fun continuation(messages: JSONArray, pending: List<String>): Continue? {
        if (pending.isEmpty()) return null
        val notes = ArrayList<String>()
        val shots = ArrayList<JSONObject>()
        var end = messages.length()
        while (end > 0) {
            val m = messages.optJSONObject(end - 1) ?: return null
            if (m.optString("role") != "user") break
            when (val c = m.opt("content")) {
                is String -> notes.add(0, c.trim())
                is JSONArray -> {
                    val parts = (0 until c.length()).mapNotNull { c.optJSONObject(it) }
                    if (parts.any { it.optString("type") == "image_url" }) shots.add(0, m)
                    else notes.add(0, parts.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }.trim())
                }
                else -> return null
            }
            end--
        }
        if (notes.size > MAX_NOTES || shots.size > 2) return null
        val res = resultsBefore(messages, end, pending) ?: return null
        val kept = notes.filter { it.isNotBlank() }
        if (kept.isNotEmpty()) addNote(res, kept.joinToString("\n") { "[Added after these results: ${it.take(4_000)}]" })
        return Continue(res, shots, kept.size)
    }

    /** Appends [note] to the content of the last result in [results] (in place). */
    fun addNote(results: JSONArray, note: String): JSONArray {
        val last = results.optJSONObject(results.length() - 1) ?: return results
        last.put("content", last.optString("content").trimEnd() + "\n\n" + note)
        return results
    }

    /** The text a screenshot is sent as inside a run's tool results. */
    fun shotNote(description: String): String = "[Screenshot of the phone screen, described for you: ${description.trim().take(6_000)}]"

    /** v0.45.0 (#6): the one-off vision request that turns a screenshot message into words for a run. */
    fun describeRequest(shot: JSONObject, ask: String): JSONArray {
        val c = shot.optJSONArray("content") ?: JSONArray()
        val img = (0 until c.length()).mapNotNull { c.optJSONObject(it) }.firstOrNull { it.optString("type") == "image_url" }
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text",
            "Describe this phone screenshot for an assistant that can't see it: the app, every readable text, the buttons and fields " +
                "(with where they are: top / middle / bottom, left / right), and anything relevant to this request: \"${ask.take(500)}\". " +
                "Plain text, no guessing, under 250 words."))
        if (img != null) parts.put(img)
        return JSONArray().put(JSONObject().put("role", "user").put("content", parts))
    }

    /** POST /v1/runs body. */
    fun createBody(clientId: String, messages: JSONArray, tools: JSONArray?, task: String): JSONObject = JSONObject()
        .put("client_msg_id", clientId).put("messages", messages).put("task", task).apply {
            tz().takeIf { it.isNotBlank() }?.let { put("tz", it) }
            // v0.45.5 (#1, gateway 0.6.9): schemas are dropped on a plain turn, so a greeting can't come back as a tool call.
            // 0.45.15 (device /phoneqa 21 Oct 22:40, 10/55): a run sent tool_choice "auto" + blaze_tools "auto" every time, so
            // the named tool (ToolTruth.choice) never reached the phone's real route and codestral wrote its calls as text.
            // Gateway 0.8.19+ applies a run's tool_choice to its first model step only; blaze_tools only when nothing is named.
            if (tools != null && tools.length() > 0) {
                val sent = ToolTruth.cap(messages, tools)
                put("tools", sent)
                val choice = ToolTruth.choice(messages, sent)
                put("tool_choice", choice)
                if (choice == "auto") put("blaze_tools", "auto")
            }
        }

    // ---------------- SSE ----------------

    /** One server-sent event. [id] = the run event's seq (null for `run` / `end`). */
    data class Frame(val id: Long?, val event: String, val data: String)

    /** Line-by-line SSE reader: a blank line ends a frame; `:` lines (pings) are skipped. */
    class Sse {
        private var id: Long? = null
        private var event = ""
        private val data = StringBuilder()
        private var any = false

        fun line(raw: String): Frame? {
            val l = raw.removeSuffix("\r")
            if (l.isEmpty()) {
                if (!any) return null
                val f = Frame(id, event.ifEmpty { "message" }, data.toString())
                id = null; event = ""; data.setLength(0); any = false
                return f
            }
            if (l.startsWith(":")) return null
            val k = l.substringBefore(':')
            var v = if (':' in l) l.substringAfter(':') else ""
            if (v.startsWith(" ")) v = v.substring(1)
            when (k) {
                "id" -> { id = v.trim().toLongOrNull(); any = true }
                "event" -> { event = v.trim(); any = true }
                "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(v); any = true }
            }
            return null
        }
    }

    // ---------------- events → state ----------------

    /**
     * Applies run events in order, once each (seq ≤ [lastSeq] is a repeat and is ignored). Text is kept per model step:
     * `reset{step}` drops that step's partial text (the gateway started the step over on another invocation).
     */
    class Reducer(var lastSeq: Long = 0) {
        var status = ""
        var retryInMs = 0L
        private val texts = java.util.TreeMap<Int, StringBuilder>()
        var calls: JSONArray? = null
        var callsStep = -1
        var reply: String? = null
        var provider = ""
        var model = ""
        var errorCode = ""
        var errorMessage = ""
        /** Contract §3 `failed{reason, try}`: the honest words for the person. */
        var failReason = ""
        var failTry = ""
        /** The run's `ack` text (one per run; later ones are ignored). */
        var ack: String? = null

        /** A new model call of the agent starts: the earlier steps' text and calls are done with. */
        fun window() { texts.clear(); calls = null; callsStep = -1; reply = null }

        /** The text this call has streamed so far (every step in the window). */
        val text: String get() = texts.values.joinToString("") { it.toString() }

        fun textOf(step: Int): String = texts[step]?.toString().orEmpty()

        /** True when [ev] was new and applied. */
        fun apply(ev: JSONObject): Boolean {
            val seq = ev.optLong("seq", -1)
            if (seq >= 0) { if (seq <= lastSeq) return false; lastSeq = seq }
            when (ev.optString("type")) {
                "status" -> { status = ev.optString("status"); retryInMs = ev.optLong("retry_in_ms", 0) }
                "delta" -> texts.getOrPut(ev.optInt("step")) { StringBuilder() }.append(ev.optString("text"))
                // 0.45.19: a reset with no step drops all partial text (never a re-sent copy left beside the new one).
                "reset" -> if (ev.has("step")) texts.remove(ev.optInt("step")) else texts.clear()
                "tool_calls" -> if (ev.optString("where", "device") == "device") { calls = ev.optJSONArray("calls"); callsStep = ev.optInt("step", -1) }
                "done" -> { reply = ev.optString("reply"); provider = ev.optString("provider"); model = ev.optString("model") }
                "error" -> { errorCode = ev.optString("code"); errorMessage = ev.optString("message") }
                "failed" -> { errorCode = ev.optString("code").ifBlank { errorCode }; failReason = ev.optString("reason"); failTry = ev.optString("try") }
                "ack" -> if (ack == null) ack = ackText(ev)
            }
            return true
        }
    }

    // ---------------- persisted runs ----------------

    /**
     * Runs this phone started that haven't been picked up yet: {run id, client id, chat, last seq, status, pending tool
     * call ids}. Kept in one preference (JSON), so a reply survives an app restart. [get]/[put] are the storage.
     */
    class RunStore(private val kvGet: (String) -> String?, private val kvPut: (String, String) -> Unit, private val now: () -> Long = { System.currentTimeMillis() }) {
        data class Entry(val run: String, val cid: String, val chat: String, val seq: Long, val status: String,
                         val pending: List<String> = emptyList(), val at: Long = 0)

        private val lock = Any()

        private fun read(): MutableList<Entry> = try {
            val a = JSONArray(kvGet(KEY) ?: "[]")
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
                Entry(o.optString("run"), o.optString("cid"), o.optString("chat"), o.optLong("seq"), o.optString("status"),
                    o.optJSONArray("pending")?.let { p -> (0 until p.length()).map { p.optString(it) } } ?: emptyList(), o.optLong("at"))
            }.filter { it.run.isNotBlank() && now() - it.at < KEEP_MS }.toMutableList()
        } catch (_: Exception) { mutableListOf() }

        private fun write(list: List<Entry>) {
            kvPut(KEY, JSONArray().apply {
                list.forEach { e -> put(JSONObject().put("run", e.run).put("cid", e.cid).put("chat", e.chat).put("seq", e.seq)
                    .put("status", e.status).put("pending", JSONArray(e.pending)).put("at", e.at)) }
            }.toString())
        }

        fun all(): List<Entry> = synchronized(lock) { read() }
        fun get(run: String): Entry? = all().firstOrNull { it.run == run }

        fun save(e: Entry) = synchronized(lock) {
            val l = read(); l.removeAll { it.run == e.run }
            l.add(e.copy(at = if (e.at == 0L) now() else e.at)); write(l)
        }

        fun update(run: String, f: (Entry) -> Entry) = synchronized(lock) {
            val l = read(); val i = l.indexOfFirst { it.run == run }
            if (i >= 0) { l[i] = f(l[i]); write(l) }
        }

        fun remove(run: String) = synchronized(lock) { val l = read(); if (l.removeAll { it.run == run }) write(l) }

        /** The gateway answered 404 / 405 to POST /v1/runs: no Cloud runs for [OFF_MS]. */
        fun runsOff() = kvPut(OFF_KEY, now().toString())
        fun isOff(): Boolean = (kvGet(OFF_KEY)?.toLongOrNull() ?: 0L).let { it > 0 && now() - it < OFF_MS }

        companion object {
            const val KEY = "cloud_runs_v1"
            const val OFF_KEY = "cloud_runs_off_at"
        }
    }

    // ---------------- network ----------------

    /** One HTTP answer: status, content type and the body as a reader. Closing it stops a stream. */
    class Response(val code: Int, val contentType: String, val reader: BufferedReader, private val onClose: () -> Unit = {},
                   /** 0.45.20: the few headers the client reads (Retry-After), lower-case names. */
                   val headers: Map<String, String> = emptyMap()) : java.io.Closeable {
        fun text(): String = try { reader.use { it.readText() } } catch (_: IOException) { "" } finally { onClose() }
        override fun close() { try { reader.close() } catch (_: Exception) { }; try { onClose() } catch (_: Exception) { } }
    }

    /** The gateway, as seen by the client. [path] is relative to the gateway's /v1 base, e.g. "/runs". */
    interface Http {
        @Throws(IOException::class)
        fun send(method: String, path: String, body: String?, headers: Map<String, String>): Response
    }

    /** The real gateway: HTTPS with the Blaze sign-in. */
    class GatewayHttp(private val base: String = Providers.gatewayBase, private val token: () -> String = { Settings.gatewayToken() }) : Http {
        override fun send(method: String, path: String, body: String?, headers: Map<String, String>): Response {
            val c = com.blaze.assistant.connect.UrlSafety.open(base.trimEnd('/') + path)
            c.requestMethod = method
            c.connectTimeout = 15_000
            // The gateway pings a stream every 15 s and ends it after ~25 s.
            c.readTimeout = 40_000
            if (method == "POST" && path == "/runs") StartTrace.stage("token (run)")
            c.setRequestProperty("Authorization", "Bearer ${token()}")
            if (method == "POST" && path == "/runs") StartTrace.stage("headers set (run)")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            // v0.45.8 (Site): chat requests (a new run, results for it) carry the reply-language pick; none for Auto.
            if (method == "POST" && path.startsWith("/runs")) ReplyLanguage.header(ReplyLanguage.current())?.let { (k, v) -> c.setRequestProperty(k, v) }
            try {
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    // v0.45.8: a new run (POST /runs) is the chat request: time it from Send.
                    val chat = method == "POST" && path == "/runs"
                    val bytes = body.toByteArray()
                    if (chat) StartTrace.stage("body ${bytes.size / 1024} KB (run)")   // v0.45.9
                    c.outputStream.use { if (chat) StartTrace.wire(); it.write(bytes) }
                }
                val code = c.responseCode
                if (method == "POST" && path == "/runs") StartTrace.headers()
                // v0.45.5: request id + route / timing headers for the "What happened" sheet.
                try { val g = TurnTrace.pick(c.headerFields); if (g.isNotEmpty()) CallState.current().let { st ->
                    st.gw = st.gw + g; g["x-request-id"]?.let { if (it !in st.requestIds) st.requestIds.add(it) } } } catch (_: Throwable) { }
                val s = (if (code in 200..299) c.inputStream else c.errorStream) ?: java.io.ByteArrayInputStream(ByteArray(0))
                val hs = c.getHeaderField("Retry-After")?.let { mapOf("retry-after" to it) } ?: emptyMap()
                return Response(code, c.contentType.orEmpty(), s.bufferedReader(), onClose = { c.disconnect() }, headers = hs)
            } catch (e: IOException) { c.disconnect(); throw e }
        }
    }

    private val SSE = mapOf("Accept" to "text/event-stream")

    // ---------------- one turn ----------------

    /** One user turn's runs: [base] makes the client message ids (one per run the turn starts: base:1, base:2…). */
    class Turn(val chat: String, base: String) {
        val base = safeId(base)
        private var n = 0
        /** The run this turn is on (null between runs). */
        @Volatile var runId: String? = null
        val red = Reducer()
        /** A run's text is on screen: speech waits for the final reply (a reset could take words back). */
        @Volatile var streaming = false
        /** Something sent this turn the old way: the rest of the turn stays local. */
        @Volatile var off = false
        /** This turn already passed a gateway ack on (one per turn, across its runs). */
        @Volatile var acked = false
        /** The client id of the run being made now (kept for an idempotent resend). */
        var cid = ""
        /** 0.45.20: the bubble's own client_msg_id (Delivery.Tracker), used for this turn's first run so a resend reuses it. */
        @Volatile var firstCid: String? = null
        /** 0.45.20: the user bubbles this turn answers (their gids), for the delivery label. */
        @Volatile var gids: List<String> = emptyList()
        /** 0.45.20: how long the first create waited for the network (the turn's clock starts after it). */
        @Volatile var waitedMs = 0L
        /** 0.45.20: completes once the gateway has the message (a 2xx), so step time limits start then, not while offline. */
        val sent = kotlinx.coroutines.CompletableDeferred<Unit>()
        fun nextId(): String {
            val f = firstCid
            cid = if (n == 0 && !f.isNullOrBlank()) { n = 1; f } else "$base:${++n}"
            return cid
        }
        /**
         * v0.45.0 (#6): turns a screenshot message into words with one local vision call, so a phone step that took a
         * screenshot continues the run instead of starting a new one. null result (or no describer) = a new run.
         */
        @Volatile var describe: (suspend (JSONObject) -> String?)? = null
        /** v0.45.0 (#6): how many steps continued this turn's run, and how many had to start a new one (speed log). */
        @Volatile var continued = 0
        @Volatile var restarted = 0
    }

    /** What a run left from an earlier app session ended as. */
    sealed class Resumed {
        data class Reply(val text: String) : Resumed()
        /** It was waiting for the phone (its tools need the turn that died with the app): cancelled. */
        object Interrupted : Resumed()
        data class Failed(val line: String) : Resumed()
        /** No connection now: try again later. */
        object Later : Resumed()
    }

    /** Live callbacks of one model call. All optional. */
    interface Events {
        fun text(t: String) {}
        fun status(line: String) {}
        /** Something arrived (or the client is still waiting on purpose): keeps the agent's silence watchdog quiet. */
        fun beat() {}
        /** The gateway's instant acknowledgment (`ack{text}`, gateway contract from Site): shown once, never a "done". */
        fun ack(text: String) {}
        /** 0.45.20: every applied run event (and the client's own `_posted`): delivery label and step pill read these. */
        fun raw(ev: JSONObject) {}
    }

    /** Runs owned by a live turn (the restart / reconnect pick-up leaves them alone). */
    val owned: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    class Client(
        private val http: Http,
        val store: RunStore,
        private val sleep: suspend (Long) -> Unit = { delay(it) },
        private val now: () -> Long = { System.currentTimeMillis() },
        /** Fire-and-forget work (cancel / delete): detached from the turn so a Stop still reaches the gateway. */
        private val detached: (suspend () -> Unit) -> Unit = { f -> CoroutineScope(Dispatchers.IO).launch { try { f() } catch (_: Throwable) { } } },
    ) {
        var maxOfflineMs = MAX_OFFLINE_MS

        /**
         * One model call of the agent, as part of [t]'s run: a new run, or the tool results of the run waiting for the
         * phone. Returns the assistant message (content, tool_calls when the phone has work). Throws [Fallback] (do it
         * locally) or LlmClient.LlmException (a line for the person).
         */
        suspend fun complete(t: Turn, messages: JSONArray, tools: JSONArray?, task: String, ev: Events = object : Events {}): JSONObject {
            t.red.window()
            if (t.runId != null) t.sent.complete(Unit)
            try {
                val cur = t.runId?.let { store.get(it) }
                if (cur != null && cur.status == "needs_device") {
                    val k = continuation(messages, cur.pending)
                    var res = k?.results
                    // A screenshot can't ride in text-only results: described in words, or a new run after all.
                    if (k != null && k.shots.isNotEmpty()) {
                        val words = t.describe?.let { d -> try { d(k.shots.last()) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
                        res = if (words.isNullOrBlank()) null else addNote(k.results, shotNote(words))
                    }
                    if (res != null) {
                        t.continued++
                        val fc: Any = try { tools?.takeIf { it.length() > 0 }?.let { ToolTruth.choice(messages, ToolTruth.cap(messages, it)) } ?: "auto" } catch (_: Throwable) { "auto" }
                        return result(t, sendResults(t, cur, res, ev, fc), ev)
                    }
                }
                if (t.runId != null) t.restarted++
                t.runId?.let { cancel(it) }
                t.runId = null
                return create(t, messages, tools, task, ev)
            } catch (e: CancellationException) {
                // Stop: the run stops on the gateway too.
                t.runId?.let { cancel(it) }
                t.runId = null
                throw e
            }
        }

        /** Stops run [id] on the gateway (best effort) and forgets it. */
        fun cancel(id: String) {
            store.remove(id); owned.remove(id)
            detached { http.send("POST", "/runs/$id/cancel", "{}", emptyMap()).close() }
        }

        private fun forget(id: String, delete: Boolean) {
            store.remove(id); owned.remove(id)
            if (delete) detached { http.send("DELETE", "/runs/$id", null, emptyMap()).close() }
        }

        private suspend fun <T> io(f: () -> T): T = withContext(Dispatchers.IO) { f() }

        private suspend fun pause(ms: Long, ev: Events) {
            var left = ms
            while (left > 0) { val s = minOf(1_000L, left); sleep(s); left -= s; ev.beat() }
        }

        private suspend fun create(t: Turn, messages: JSONArray, tools: JSONArray?, task: String, ev: Events): JSONObject {
            ev.status(SENDING)
            val cid = t.nextId()
            val body = createBody(cid, messages, tools, task).toString()
            // 0.45.20 (contract §1): offline keeps the bubble at Sending and retries 1, 2, 4, 8, 16, 30 … s (at once when the
            // network comes back), 429 waits Retry-After, another 4xx is "Not sent". Every attempt is the same body and
            // client id: if an earlier one did land, the gateway answers with that run (existing:true), never a second one.
            val t0 = now()
            val r = try {
                Delivery.deliver(
                    post = { io { http.send("POST", "/runs", body, SSE + (TurnModel.current?.headers() ?: emptyMap())) } },
                    codeOf = { it.code }, retryAfterOf = { it.headers["retry-after"] }, close = { it.close() },
                    wait = { ms -> Delivery.waitOrWake(ms, sleep, { ev.beat() }) }, now = now,
                    onEvent = { e -> when (e) {
                        Delivery.Ev.NET_ERROR -> ev.status(OFFLINE)
                        Delivery.Ev.POSTED -> { if (!t.sent.isCompleted) t.waitedMs = now() - t0; t.sent.complete(Unit); ev.raw(JSONObject().put("type", Delivery.POSTED)) }
                        Delivery.Ev.REJECTED -> ev.raw(JSONObject().put("type", Delivery.REJECTED))
                        else -> Unit
                    } })
            } catch (e: CancellationException) { ev.raw(JSONObject().put("type", Delivery.STOPPED)); throw e }
            if (r.code !in 200..299) {
                r.close()
                if (noRuns(r.code)) store.runsOff()
                throw Fallback("create ${r.code}")
            }
            return result(t, follow(t, r, ev, body), ev)
        }

        private fun register(t: Turn, id: String) {
            t.runId = id
            owned.add(id)
            store.save(RunStore.Entry(id, t.cid, t.chat, t.red.lastSeq, t.red.status.ifEmpty { "queued" }))
        }

        private suspend fun sendResults(t: Turn, cur: RunStore.Entry, res: JSONArray, ev: Events, choice: Any = "auto"): String {
            ev.status(RUNNING)
            // 0.45.15: the follow-up tool (open_settings → tap, processes → process) rides with the results; gateway
            // 0.8.19+ applies it to the next model step only.
            val body = JSONObject().put("results", res).apply { if (choice != "auto") put("tool_choice", choice) }.toString()
            var offlineSince = 0L
            var k = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val r = try { io { http.send("POST", "/runs/${cur.run}/tool_results", body, SSE) } } catch (e: IOException) {
                    if (offlineSince == 0L) offlineSince = now()
                    ev.status(OFFLINE)
                    if (now() - offlineSince > maxOfflineMs) throw LlmClient.LlmException(SAFE, 0)
                    pause(minOf(15_000L, 1_000L shl minOf(k++, 4)), ev)
                    continue   // a repeat of results that did land is fine (already:true)
                }
                if (offlineSince != 0L) ev.status(RUNNING)
                when {
                    r.code in 200..299 -> {
                        store.update(cur.run) { it.copy(status = "queued", pending = emptyList()) }
                        return follow(t, r, ev, null)
                    }
                    r.code == 404 -> { r.close(); forget(cur.run, false); t.runId = null; throw LlmClient.LlmException(EXPIRED, 408) }
                    r.code == 409 -> { r.close(); return follow(t, null, ev, null) }   // it moved on: read where it is
                    else -> { r.close(); cancel(cur.run); t.runId = null; throw Fallback("tool_results ${r.code}") }
                }
            }
        }

        /**
         * Reads [first] and then reconnects (Last-Event-ID) until the run ends or waits for the phone; returns that
         * status. While the connection is down it says so, keeps the watchdog quiet and retries with backoff; after
         * [maxOfflineMs] it gives up with [SAFE] (the run stays saved and is picked up later).
         */
        private suspend fun follow(t: Turn, first: Response?, ev: Events, createBody: String?): String {
            var resp = first
            var offlineSince = 0L
            var k = 0
            var fails = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (resp == null) {
                    val id = t.runId
                    resp = try {
                        if (id == null) {
                            if (createBody == null) throw Fallback("no run")
                            io { http.send("POST", "/runs", createBody, SSE) }   // same client id: the same run
                        } else io { http.send("GET", "/runs/$id/stream", null, SSE + ("Last-Event-ID" to t.red.lastSeq.toString())) }
                    } catch (e: IOException) {
                        if (offlineSince == 0L) offlineSince = now()
                        ev.status(OFFLINE)
                        if (now() - offlineSince > maxOfflineMs) {
                            if (t.runId == null) throw Fallback("unreachable")
                            throw LlmClient.LlmException(SAFE, 0)
                        }
                        pause(minOf(15_000L, 1_000L shl minOf(k++, 4)), ev)
                        continue
                    }
                    if (offlineSince != 0L) { offlineSince = 0L; k = 0; ev.status(line(t.red.status, t.red.retryInMs) ?: RUNNING) }
                }
                val r = resp!!
                resp = null
                if (r.code !in 200..299) {
                    r.close()
                    val id = t.runId ?: throw Fallback("create ${r.code}")
                    if (r.code == 404) { forget(id, false); t.runId = null; throw LlmClient.LlmException(EXPIRED, 408) }
                    // The stream refused (5xx…): read the events as JSON instead.
                    poll(t, id, ev)?.let { return it }
                    if (++fails > 20) throw LlmClient.LlmException(Cloud.BUSY, 503)
                    pause(1_500, ev)
                    continue
                }
                val before = t.red.lastSeq
                val end = try { read(t, r, ev) } catch (e: IOException) { null }
                if (end != null) return end
                // Cut (or the gateway's ~25 s stream ended): reconnect; a stream that brought nothing waits a moment first.
                if (t.red.lastSeq == before) pause(1_000, ev)
            }
        }

        /** GET /runs/:id?after= (JSON). Returns the status when the run ended or waits for the phone, else null. */
        private suspend fun poll(t: Turn, id: String, ev: Events): String? {
            for (page in 0 until 20) {
                val o = view(id, t.red.lastSeq) ?: return null
                o.optJSONArray("events")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { apply(t, it, ev) } }
                if (o.optBoolean("more")) continue
                val st = o.optString("status")
                return if (st in listOf("done", "failed", "cancelled", "needs_device")) st else null
            }
            return null
        }

        private suspend fun view(id: String, after: Long = Long.MAX_VALUE / 2): JSONObject? = try {
            val r = io { http.send("GET", "/runs/$id?after=$after", null, emptyMap()) }
            if (r.code !in 200..299) { r.close(); null } else JSONObject(io { r.text() })
        } catch (_: IOException) { null } catch (_: org.json.JSONException) { null }

        private var savedAt = 0L

        private fun apply(t: Turn, o: JSONObject, ev: Events) {
            if (!t.red.apply(o)) return
            val type = o.optString("type")
            try { ev.raw(o) } catch (_: Throwable) { }
            when (type) {
                "delta", "reset" -> { t.streaming = true; ev.text(t.red.text) }
                "status" -> line(t.red.status, t.red.retryInMs)?.let { ev.status(it) }
                "ack" -> t.red.ack?.takeIf { it.isNotBlank() && !t.acked }?.let { t.acked = true; ev.ack(it) }
            }
            val id = t.runId ?: return
            // Every event but a text piece is saved at once; text pieces at most once a second.
            if (type != "delta" || now() - savedAt > 1_000) {
                savedAt = now()
                store.update(id) { it.copy(seq = t.red.lastSeq, status = if (type == "status") t.red.status else it.status) }
            }
        }

        /** Contract §1: the 202 / `run` frame carries the ack before any model ran; shown at once, once per turn. */
        private fun headAck(t: Turn, o: JSONObject, ev: Events) {
            // 0.45.20: the create / retry answer's `received` / `read` (contract §1) move the delivery label too.
            Delivery.fromCreate(o).forEach { d -> ev.raw(JSONObject().put("type", if (d == Delivery.Ev.READ) "message_read" else "message_received")) }
            if (t.acked) return
            val a = ackText(o)
            if (a.isNotBlank()) { t.acked = true; ev.ack(a) }
        }

        /** Reads one SSE answer. The end status, or null when it stopped without one (reconnect). */
        private suspend fun read(t: Turn, r: Response, ev: Events): String? {
            if (!r.contentType.contains("event-stream", true)) {
                // A plain JSON answer (202 {run_id}): note the run, then follow its stream.
                val o = try { JSONObject(io { r.text() }) } catch (_: Exception) { JSONObject() }
                o.optString("run_id").takeIf { it.isNotBlank() && t.runId == null }?.let { register(t, it) }
                headAck(t, o, ev)
                return null
            }
            return coroutineScope {
                val d = async(Dispatchers.IO) {
                    val sse = Sse()
                    var end: String? = null
                    while (end == null) {
                        val l = r.reader.readLine() ?: break
                        ev.beat()
                        val f = sse.line(l) ?: continue
                        when (f.event) {
                            "run" -> {
                                val o = try { JSONObject(f.data) } catch (_: Exception) { continue }
                                o.optString("run_id").takeIf { it.isNotBlank() && t.runId == null }?.let { register(t, it) }
                                headAck(t, o, ev)
                                // Results that had already landed: this stream starts at the run's newest event; read from ours.
                                if (o.optBoolean("already")) break
                            }
                            "end" -> end = try { JSONObject(f.data).optString("status") } catch (_: Exception) { "" }.ifEmpty { "done" }
                            // An `event: ack` frame may carry no "type" (or plain text): the frame's name is its type.
                            else -> try { apply(t, frameEvent(f), ev) } catch (_: org.json.JSONException) { }
                        }
                    }
                    end
                }
                try { d.await() } catch (e: CancellationException) { r.close(); throw e } finally { if (d.isCompleted) r.close() }
            }
        }

        /** The agent's answer for the status the run ended on. */
        private suspend fun result(t: Turn, status: String, ev: Events): JSONObject {
            val id = t.runId ?: throw Fallback("no run id")
            when (status) {
                "done" -> {
                    val reply = t.red.reply ?: view(id)?.optString("reply")
                    t.runId = null
                    if (reply == null) { forget(id, true); throw Fallback("no reply") }
                    forget(id, true)
                    if (t.red.provider.isNotBlank()) {
                        LlmClient.lastServedBy = listOf(t.red.provider, t.red.model).filter { it.isNotBlank() }.joinToString(" · ")
                        LlmClient.lastServedKey = if (t.red.model.isNotBlank()) "${t.red.provider}/${t.red.model}" else ""
                    }
                    return JSONObject().put("role", "assistant").put("content", reply)
                }
                "needs_device" -> {
                    var calls = t.red.calls
                    var said = if (t.red.callsStep >= 0) t.red.textOf(t.red.callsStep) else t.red.text
                    if (calls == null || calls.length() == 0) { calls = view(id)?.optJSONArray("pending_tool_calls"); said = t.red.text }
                    if (calls == null || calls.length() == 0) { cancel(id); t.runId = null; throw Fallback("no calls") }
                    val ids = (0 until calls.length()).map { calls.optJSONObject(it)?.optString("id").orEmpty() }.filter { it.isNotBlank() }
                    store.update(id) { it.copy(status = "needs_device", pending = ids, seq = t.red.lastSeq) }
                    ev.status(ON_PHONE)
                    return JSONObject().put("role", "assistant").put("content", if (said.isBlank()) JSONObject.NULL else said).put("tool_calls", calls)
                }
                "failed" -> {
                    var code = t.red.errorCode
                    if (code.isBlank()) code = view(id)?.optJSONObject("error")?.optString("code").orEmpty()
                    forget(id, true); t.runId = null
                    val known = failure(code)
                    // Contract §3: a busy / quota failure says it in the gateway's honest words, with what to do next.
                    if (known != null && t.red.failReason.isNotBlank())
                        throw LlmClient.LlmException(listOf(t.red.failReason, t.red.failTry).filter { it.isNotBlank() }.joinToString(" "), known.code)
                    throw known ?: Fallback("failed $code")
                }
                "cancelled" -> { forget(id, true); t.runId = null; throw LlmClient.LlmException(STOPPED, 0) }
                else -> { cancel(id); t.runId = null; throw Fallback("status $status") }
            }
        }

        /**
         * A run left from an earlier app session (the app was closed or the connection dropped): follows it once to its
         * end with no live text. Runs that were waiting for the phone are cancelled (their turn is gone).
         */
        suspend fun resume(e: RunStore.Entry): Resumed {
            val t = Turn(e.chat, e.cid)
            t.cid = e.cid
            t.runId = e.run
            t.red.lastSeq = e.seq
            if (e.status == "needs_device") { cancel(e.run); return Resumed.Interrupted }
            val keep = maxOfflineMs
            return try {
                maxOfflineMs = 0
                owned.add(e.run)
                val st = follow(t, null, object : Events {}, null)
                if (st == "needs_device") { cancel(e.run); return Resumed.Interrupted }
                Resumed.Reply((result(t, st, object : Events {}).opt("content") as? String).orEmpty())
            } catch (x: LlmClient.LlmException) {
                if (x.message == SAFE) Resumed.Later else Resumed.Failed(x.message.orEmpty())
            } catch (x: Fallback) {
                forget(e.run, true)
                Resumed.Failed(NO_REPLY)
            } finally {
                maxOfflineMs = keep
                owned.remove(e.run)
            }
        }
    }

    // ---------------- the app's side ----------------

    @Volatile private var client: Client? = null

    /** The app's client (Settings-backed store, the real gateway). */
    fun client(): Client = client ?: synchronized(this) {
        client ?: Client(GatewayHttp(), RunStore({ k -> Settings.str(k, "").ifEmpty { null } }, { k, v -> Settings.setStr(k, v) })).also { client = it }
    }

    /**
     * True when this turn can go as a Cloud run: switched on (pref, default on), signed in, the gateway hasn't said it
     * is too old lately, online, and a normal chat (not private, not a background agent / specialist / Halo turn,
     * not a scheduled routine). Everything else keeps the local loop.
     */
    fun eligible(mainChat: Boolean, private: Boolean, online: Boolean): Boolean = try {
        mainChat && !private && online && Settings.bool(PREF, true) && Settings.gatewayToken().isNotBlank() && !client().store.isOff()
    } catch (_: Throwable) { false }

    /**
     * One agent model call through [t] (see [Client.complete]) with the same request clean-up and task hint as
     * LlmClient. Null = do this call locally (the rest of the turn too).
     */
    suspend fun step(t: Turn, messages: JSONArray, tools: JSONArray?, onText: ((String) -> Unit)?, onStatus: (String) -> Unit,
                     onAck: (String) -> Unit = {}, onRaw: (JSONObject) -> Unit = {}): JSONObject? {
        if (t.off) return null
        val clean = withContext(Dispatchers.Default) { try { ToolCalls.clean(messages) } catch (_: Exception) { messages } }
        val sendTools = tools?.takeIf { it.length() > 0 }?.let { try { ToolCalls.adapt(it, Providers.GATEWAY, Cloud.MODEL) } catch (_: Exception) { it } }
        // v0.44.0: an action turn's run is created on the strong (agent) lineup (ModelRoute); a run keeps its task.
        val task = ModelRoute.task(Cloud.task(clean, tools, LlmClient.liteTurn || (LlmClient.quickTurn && (tools == null || tools.length() == 0))),
            LlmClient.heavyTurn, tools != null && tools.length() > 0, Cloud.textChars(clean, ModelRoute.AGENT_MAX_CHARS + 1)).task
        val start = System.currentTimeMillis()
        var first = false
        LlmClient.lastFirstTokenMs = 0; LlmClient.lastFirstTokenAt = 0; LlmClient.lastFirstTextMs = 0; LlmClient.lastFirstTextAt = 0; LlmClient.lastHeadersMs = 0
        LlmClient.lastUsage = null   // a run's token count stays on the gateway (its quota); never the last local call's
        return try {
            client().complete(t, clean, sendTools, task, object : Events {
                override fun text(tx: String) {
                    if (!first && tx.isNotEmpty()) { first = true; StartTrace.first(); LlmClient.lastFirstTokenAt = System.currentTimeMillis(); LlmClient.lastFirstTokenMs = LlmClient.lastFirstTokenAt - start
                        LlmClient.lastFirstTextAt = LlmClient.lastFirstTokenAt; LlmClient.lastFirstTextMs = LlmClient.lastFirstTokenMs }
                    onText?.invoke(tx)
                }
                override fun status(line: String) = onStatus(line)
                override fun ack(text: String) = onAck(text)
                override fun raw(ev: JSONObject) = onRaw(ev)
                override fun beat() { LlmClient.lastChunkAt = System.currentTimeMillis() }
            }).also { LlmClient.lastRoute = "Blaze Cloud · run" + (if (LlmClient.lastServedBy.isNotBlank()) " · ${LlmClient.lastServedBy}" else "") }
        } catch (f: Fallback) {
            t.off = true
            LlmClient.lastCloudError = "cloud run: ${f.why}"
            null
        }
    }

    /**
     * 0.45.20: runs [block] with a time limit of [ms] that only starts once [t]'s message reached the gateway
     * ([Turn.sent]): while the phone is offline the bubble stays at Sending and the send keeps retrying instead of being
     * cut off by the step limit. null = the limit hit (like withTimeoutOrNull). Stop still cancels at once.
     */
    suspend fun <T> timedAfterSent(t: Turn, ms: Long, block: suspend () -> T): T? = coroutineScope {
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val work = async { block() }
        val guard = launch { t.sent.await(); delay(ms); timedOut.set(true); work.cancel() }
        try { work.await() } catch (e: CancellationException) {
            if (timedOut.get() && currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) null else throw e
        } finally { guard.cancel() }
    }

    /**
     * The agent's turn ended: a run still waiting for the phone is stopped (nobody will send its tool results); a run
     * still answering (the connection was lost) stays saved, and the pick-up delivers its reply into the chat later.
     */
    fun endTurn(t: Turn) {
        val id = t.runId ?: return
        t.runId = null
        val c = client()
        val e = c.store.get(id)
        if (e == null || e.status == "needs_device") c.cancel(id) else owned.remove(id)
    }
}
