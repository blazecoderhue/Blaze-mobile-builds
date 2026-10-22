package com.blaze.assistant.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal OpenAI-compatible chat-completions client (works with Gemini, OpenAI, Groq, OpenRouter...). */
object LlmClient {

    /** [unanswered]: no answer came at all (connect / send / wait for the status failed), so the request may not have arrived. */
    class LlmException(msg: String, val code: Int = 0, val retryAfterSec: Int = 0, val unanswered: Boolean = false) : Exception(msg)

    /** Every key for the user's chosen model ran out: the chat shows a provider/model switcher. */
    class UsedUp(val pid: String, val model: String, msg: String) : Exception(msg)

    /** Which service + model one call goes to. */
    data class Target(val pid: String, val base: String, val key: String, val model: String) {
        val label get() = "${Providers.byId(pid).name.substringBefore(" (")} · $model"
    }

    fun target(pid: String, model: String? = null): Target {
        val p = Providers.byId(pid)
        val base = if (pid == Providers.CUSTOM) Settings.customBase else p.baseUrl
        val m = model?.takeIf { it.isNotBlank() } ?: Settings.modelFor(pid)
        val key = when (pid) { Providers.keyless.id -> ""; Providers.GATEWAY -> Settings.gatewayToken(); else -> KeyRing.active(pid, m) }
        return Target(pid, base, key, m)
    }

    /** MAIN = the user's turn, STEPS = a turn right after a phone action, BACKGROUND = learning/summaries. */
    enum class Lane { MAIN, STEPS, BACKGROUND }

    /** The service + model that answered last (shown as the route chip). */
    var lastRoute: String get() = CallState.current().route; set(v) { CallState.current().route = v }

    /** Rate-limit headers from the latest reply (shown in Settings → Plan & quota). */
    @Volatile var lastRateHeaders: Map<String, String> = emptyMap()

    /** Auth headers for a provider. */
    fun authHeaders(key: String, pid: String = Settings.providerId): Map<String, String> =
        if (key.isBlank()) emptyMap()
        else if (pid == "anthropic") mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01", "Authorization" to "Bearer $key")
        else mapOf("Authorization" to "Bearer $key")

    fun rateHeaders(conn: HttpURLConnection): Map<String, String> =
        conn.headerFields.orEmpty().filterKeys { it != null && (it.lowercase().contains("ratelimit")) }
            .map { (k, v) -> k.lowercase() to (v.firstOrNull() ?: "") }.toMap()

    /** Rate-limit headers of the very last call (empty when it sent none), for the token meter. */
    var lastCallHeaders: Map<String, String> get() = CallState.current().callHeaders; set(v) { CallState.current().callHeaders = v }

    /** v0.44.0: the agent loop is on an action turn (ModelRoute.heavy): tool calls go to the strong lineup. */
    var heavyTurn: Boolean get() = CallState.current().heavy; set(v) { CallState.current().heavy = v }

    /** True for a chat-lane turn (no tools, short prompt): Auto prefers small fast models for it. */
    var liteTurn: Boolean get() = CallState.current().lite; set(v) { CallState.current().lite = v }

    /** Token usage the provider reported for the last call, if any. */
    var lastUsage: JSONObject? get() = CallState.current().usage; set(v) { CallState.current().usage = v }

    /** Set when the provider refused Gemini's include_thoughts option, so we stop sending it. */
    @Volatile private var thoughtsRefused = false

    /**
     * One model turn. With [onText], the reply streams in and [onText] gets the text so far.
     * Returns the assistant message in OpenAI shape (content + optional tool_calls).
     */
    suspend fun complete(messages: JSONArray, tools: JSONArray?, onText: ((String) -> Unit)? = null,
                         onThink: ((String) -> Unit)? = null, lane: Lane = Lane.MAIN): JSONObject {
        // Every request goes out clean (each tool call answered once, valid JSON, no orphans).
        // If the provider still refuses its shape (400 INVALID_ARGUMENT and friends), retry once
        // with the tool turns flattened to plain text, which every provider accepts.
        // v0.25.0: pictures go to the gateway as they are; its vision lineup reads them.
        // v0.31.1 jank: cleaning (a full pass over the history JSON) never runs on the main thread.
        val clean = withContext(kotlinx.coroutines.Dispatchers.Default) { try { ToolCalls.clean(messages) } catch (_: Exception) { messages } }
        return try { completeRaw(clean, tools, onText, onThink, lane) }
        catch (e: LlmException) {
            if (!ToolCalls.isShapeError(e)) throw e
            lastHealed = e.message.orEmpty()
            try { completeRaw(ToolCalls.flatten(clean), tools, onText, onThink, lane).also { lastRoute = "$lastRoute · healed" } }
            catch (e2: LlmException) { throw LlmException(if (e2.code == 400) Cloud.BUSY else e2.message.orEmpty(), e2.code, e2.retryAfterSec) }
        }
    }

    /** The provider error the last self-heal recovered from (diagnostics). */
    @Volatile var lastHealed: String = ""

    /** The model the gateway says answered last ("provider · model"), for the details sheet / diagnostics only. */
    var lastServedBy: String get() = CallState.current().servedBy; set(v) { CallState.current().servedBy = v }
    /** v0.26.2: the gateway route of the last call as "provider/model" (what blaze_avoid names); "" when unknown. */
    var lastServedKey: String get() = CallState.current().servedKey; set(v) { CallState.current().servedKey = v }

    /** The last gateway failure, raw (diagnostics only; people see [Cloud.friendly]). */
    @Volatile var lastCloudError: String = ""

    /**
     * v0.25.0: one path for every lane (chat, the assist panel, Magic Hand, background, vision, code, agent):
     * the Blaze gateway with a task hint. Network trouble, busy models and model-gone / refused-model errors are
     * retried quietly with backoff (the gateway re-routes to its task lineup); a request that outgrew the model
     * is trimmed once. The user's old provider / model / key prefs are ignored (kept on disk, never used or sent).
     */
    private suspend fun completeRaw(messages: JSONArray, tools: JSONArray?, onText: ((String) -> Unit)?,
                         onThink: ((String) -> Unit)?, lane: Lane): JSONObject {
        // v0.33.0 Offline Brain: no internet + the on-device model downloaded = answer on the phone.
        if (lane == Lane.MAIN) OfflineBrain.maybe(messages, onText)?.let { lastRoute = "Offline Brain"; return it }
        val token = Settings.gatewayToken()
        if (token.isBlank()) throw LlmException(Cloud.SIGNED_OUT, 401)
        val t = Target(Providers.GATEWAY, Providers.GATEWAY_BASE, token, Cloud.MODEL)
        var msgs = messages
        // v0.44.0: an action turn asks for the strong (agent) lineup with the weak routes last (ModelRoute).
        val pick = ModelRoute.task(Cloud.task(msgs, tools, liteTurn || (quickTurn && (tools == null || tools.length() == 0))),
            heavyTurn && lane != Lane.BACKGROUND, tools != null && tools.length() > 0, Cloud.textChars(msgs, ModelRoute.AGENT_MAX_CHARS + 1))
        val task = pick.task
        var trimmed = false
        // v0.26.2: Cloud.drive owns the tries; an empty (reasoning-only) reply is a failed try, never an answer.
        return try {
            Cloud.drive(onText != null, call = { a ->
                call(t, if (a.nudge) EmptyReply.withNudge(msgs) else msgs, tools, if (a.stream) onText else null,
                    if (lane == Lane.MAIN) onThink else null, false, task = task, avoid = ModelRoute.avoid(a.avoid, pick.strong), idemKey = a.key)
                    .also { lastRoute = "Blaze Cloud" + (if (lastServedBy.isNotBlank()) " · $lastServedBy" else "") + ModelRoute.label(pick) + if (trimmed) " · trimmed" else "" }
            }, served = { lastServedKey },
                trim = { e -> lastCloudError = e.message.orEmpty().take(300); CallState.current().errors.add(lastCloudError); if (!trimmed && tooLarge(e)) { trimmed = true; msgs = shrink(msgs); true } else false },
                onEmpty = { n -> lastCloudError = "empty reply #$n from ${lastServedBy.ifBlank { "the gateway" }}"; CallState.current().errors.add(lastCloudError)
                    try { com.blaze.assistant.device.CrashLog.write("Blaze Cloud: $lastCloudError (reasoning only / no content)") } catch (_: Throwable) { } })
        } catch (e: LlmException) {
            if (e.code != 401 && e.code != 403) try { com.blaze.assistant.device.CrashLog.write("Blaze Cloud failed: ${lastCloudError}") } catch (_: Throwable) { }
            throw e
        }
    }

    /** Why the optional steps model last failed (shown in diagnostics); the main model took over. */
    @Volatile var lastStepsError: String = ""

    /** Wall-clock time of the latest streamed chunk (text, thinking or tool call), for the hang watchdog. */
    var lastChunkAt: Long get() = CallState.current().chunkAt.get(); set(v) { CallState.current().beat(v) }

    /** True when a provider refused the request for being too big for the model or the plan. */
    fun tooLarge(e: Exception): Boolean {
        val code = (e as? LlmException)?.code ?: 0
        val m = e.message.orEmpty()
        return code == 413 || m.contains("too large", true) || m.contains("context length", true) || m.contains("context_length", true) ||
            m.contains("maximum context", true) || m.contains("too many tokens", true) || m.contains("reduce the length", true)
    }

    /** Keeps the system prompt and the recent turns, and cuts very long messages, so a request fits. */
    fun shrink(messages: JSONArray, keep: Int = 10): JSONArray {
        val all = (0 until messages.length()).map { messages.getJSONObject(it) }
        val sys = all.takeWhile { it.optString("role") == "system" }
        val rest = all.drop(sys.size)
        var tail = rest.takeLast(keep)
        // Never start on a tool result whose call was cut off.
        while (tail.isNotEmpty() && tail.first().optString("role") == "tool") tail = tail.drop(1)
        val out = JSONArray()
        // v0.45.0 context: what is cut is summed up in its place (ContextKeep), never silently dropped.
        val cut = rest.dropLast(tail.size)
        val note = ContextKeep.digest(cut)
        val head = if (note.isNotEmpty()) listOf(JSONObject().put("role", "user").put("content", note)) else emptyList()
        (sys + head + tail).forEach { m ->
            val c = JSONObject(m.toString())
            val content = c.opt("content")
            if (content is String && content.length > 6000) c.put("content", content.take(2500) + "\n…[trimmed]…\n" + content.takeLast(2500))
            out.put(c)
        }
        return out
    }

    /** Which provider a key's format belongs to, when the prefix is unmistakable. */
    fun keyOwner(key: String): String? {
        val k = key.trim()
        return when {
            k.startsWith("nvapi-") -> "nvidia"
            k.startsWith("gsk_") -> "groq"
            k.startsWith("sk-or-") -> "openrouter"
            k.startsWith("AIza") -> "gemini"
            k.startsWith("sk-ant-") -> "anthropic"
            k.startsWith("xai-") -> "xai"
            k.startsWith("csk-") -> "cerebras"
            else -> null
        }
    }

    /** Milliseconds from sending the request to the first streamed token (or the whole reply) of the last call. */
    var lastFirstTokenMs: Long get() = CallState.current().firstTokenMs; set(v) { CallState.current().firstTokenMs = v }
    /** Wall-clock time of that first token. */
    var lastFirstTokenAt: Long get() = CallState.current().firstTokenAt; set(v) { CallState.current().firstTokenAt = v }
    /** v0.45.3: ms from sending to the response headers of the last call (0 = unknown). */
    /** v0.45.3: the longest pause between text chunks in the last streamed reply (0 = none or not streamed). */
    var lastMaxGapMs: Long get() = CallState.current().maxGapMs; set(v) { CallState.current().maxGapMs = v }
    var lastHeadersMs: Long get() = CallState.current().headersMs; set(v) { CallState.current().headersMs = v }
    /** v0.45.3: ms from sending to the first visible word (reasoning doesn't count) and its wall-clock time; 0 = none yet. */
    var lastFirstTextMs: Long get() = CallState.current().firstTextMs; set(v) { CallState.current().firstTextMs = v }
    var lastFirstTextAt: Long get() = CallState.current().firstTextAt; set(v) { CallState.current().firstTextAt = v }
    private fun markText() { val st = CallState.current(); if (st.firstTextAt == 0L) { st.firstTextAt = System.currentTimeMillis(); st.firstTextMs = st.firstTextAt - st.callStart } }
    private fun markFirst() { val st = CallState.current(); if (!st.gotFirst) { st.gotFirst = true; StartTrace.first(); st.firstTokenAt = System.currentTimeMillis(); st.firstTokenMs = st.firstTokenAt - st.callStart } }

    private val warmed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Pre-warm (speed plan step 10): resolve DNS and open the TLS connection to the AI service
     * while the user is still talking, so the real request skips that handshake. Kept-alive
     * connections are reused by HttpURLConnection's pool. At most once a minute per host.
     */
    fun prewarm() {
        // v0.45.9: build the [context] block in the background too (cached ~60 s), so the send path never waits for it.
        try { TurnContextSource.warm() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("prewarm context", e) }
        // v0.45.5 (#5/5b): signed in, POST /v1/warm (gateway 0.6.9) at most every 25 s: it pre-opens TLS on the shared
        // keep-alive pool and loads config + token into the edge isolate. Fire and forget.
        val tok = try { Settings.gatewayToken() } catch (_: Throwable) { "" }
        if (tok.isNotBlank()) {
            if (!WarmGate.take()) return
            Thread {
                try {
                    val c = com.blaze.assistant.connect.UrlSafety.open(WarmGate.url(Providers.GATEWAY_BASE))
                    c.requestMethod = "POST"; c.connectTimeout = 5000; c.readTimeout = 5000; c.doOutput = true
                    c.setRequestProperty("Authorization", "Bearer $tok"); c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write("{}".toByteArray()) }
                    val code = c.responseCode
                    (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() }
                } catch (_: Exception) { }
            }.start()
            return
        }
        val bases = try {
            listOf(Providers.GATEWAY_BASE)
        } catch (_: Exception) { emptyList() }
        for (b in bases.map { it.trimEnd('/') }.filter { it.startsWith("http") }.distinct().take(2)) {
            val host = try { URL(b).host } catch (_: Exception) { continue }
            val now = System.currentTimeMillis()
            if (now - (warmed[host] ?: 0L) < 60_000) continue
            warmed[host] = now
            Thread {
                try {
                    val c = com.blaze.assistant.connect.UrlSafety.open(b)
                    c.requestMethod = "HEAD"; c.connectTimeout = 5000; c.readTimeout = 5000
                    // v0.45.1: whatever the status, the (empty) body is closed so the connection goes back to the pool
                    // for the real request instead of being dropped with an unread error stream.
                    val code = c.responseCode
                    (if (code in 200..399) c.inputStream else c.errorStream)?.use { it.readBytes() }
                } catch (_: Exception) { }
            }.start()
        }
    }

    /** True for short, simple turns: ask reasoning models to think briefly so answers come fast. */
    var quickTurn: Boolean get() = CallState.current().quick; set(v) { CallState.current().quick = v }

    internal suspend fun call(t: Target, messages: JSONArray, tools: JSONArray?, onText: ((String) -> Unit)?,
                              onThink: ((String) -> Unit)?, thoughts: Boolean, timeoutMs: Int = 120_000, task: String? = null,
                              avoid: List<String> = emptyList(), idemKey: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val key = t.key
        if (key.isBlank() && t.pid != Providers.keyless.id)
            throw LlmException("No API key for ${Providers.byId(t.pid).name} yet. Switch Settings to Advanced and paste your key on the AI page.", 401)
        val base = t.base.trimEnd('/')
        if (base.isBlank()) throw LlmException("Add your service address in Settings.")
        if (t.model.isBlank()) throw LlmException("Pick a model on the AI page (Settings, Advanced).", 404)
        val owner = keyOwner(key)
        if (owner != null && owner != t.pid && t.pid != Providers.CUSTOM && t.pid != Providers.keyless.id)
            throw LlmException("The key saved under ${Providers.byId(t.pid).name} is actually a ${Providers.byId(owner).name} key. Switch Settings to Advanced and paste the right key for each service on the AI page.", 401)
        // v0.28.2: http:// only to this phone, the LAN or Tailscale; never keys or chats in the clear over the internet.
        com.blaze.assistant.connect.UrlSafety.problem(base)?.let { throw LlmException(it, 0) }
        val conn = com.blaze.assistant.connect.UrlSafety.open("$base/chat/completions")
        conn.requestMethod = "POST"
        // A free-chain call (timeoutMs < 120 s) gives up quickly so the next model gets a turn.
        val chained = timeoutMs < 120_000
        conn.connectTimeout = if (chained) 8_000 else 30_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        // Streaming: the read timeout is the longest wait for the next chunk, including the first.
        conn.readTimeout = if (chained && onText != null) minOf(timeoutMs, if (t.pid == "nvidia") 40_000 else 25_000) else timeoutMs
        CallState.current().let { st -> st.callStart = System.currentTimeMillis(); st.gotFirst = false; st.firstTokenMs = 0; st.firstTokenAt = 0; st.headersMs = 0; st.firstTextMs = 0; st.firstTextAt = 0 }
        if (t.pid == Providers.GATEWAY) { lastServedBy = ""; lastServedKey = "" }
        authHeaders(key, t.pid).forEach { (k, v) -> conn.setRequestProperty(k, v) }
        // v0.42.0: a retry of a request that may have arrived gets the kept answer, not a second call (gateway 0.5.0 idem.ts).
        if (t.pid == Providers.GATEWAY && idemKey.isNotBlank()) conn.setRequestProperty("Idempotency-Key", idemKey)
        // v0.45.0 (#17): every step of a turn names the turn and the route that served its first step (TurnModel).
        val turnModel = if (t.pid == Providers.GATEWAY) TurnModel.current else null
        turnModel?.headers()?.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        // v0.45.8 (Site): the reply-language picker as a header (BCP-47); none for Auto.
        if (t.pid == Providers.GATEWAY) ReplyLanguage.header(ReplyLanguage.current())?.let { (k, v) -> conn.setRequestProperty(k, v) }

        val body = JSONObject().apply {
            put("model", t.model)
            put("messages", messages)
            if (onText != null) put("stream", true)
            val quick = quickTurn && Settings.bool("quick_think", true)
            if (quick && t.pid == "openrouter") put("reasoning", JSONObject().put("effort", "low"))
            if (quick && t.pid == "gemini" && !thoughts) put("reasoning_effort", "low")
            if (thoughts) put("extra_body", JSONObject().put("google",
                JSONObject().put("thinking_config", JSONObject().put("include_thoughts", true).apply { if (quick) put("thinking_budget", 512) })))
            // OpenRouter: if this free model is busy, fall through to the next-best free ones server-side.
            if (t.pid == "openrouter" && t.model.endsWith(":free")) {
                val alts = FreeRouter.openRouterPicks().filter { it != t.model }.take(2)
                if (alts.isNotEmpty()) put("models", JSONArray(listOf(t.model) + alts))
            }
            if (t.pid == Providers.GATEWAY) Cloud.tag(this, task ?: Cloud.task(messages, tools, liteTurn))
            // v0.45.3: a hint for short / plain turns that the first visible word matters more than long reasoning. The
            // gateway strips every blaze_* field before a provider sees it, so this is safe today; a gateway that reads it can
            // map it to each provider's own setting (e.g. reasoning_effort "low" for gpt-oss). Not sent on action turns.
            if (t.pid == Providers.GATEWAY && (liteTurn || quick) && !heavyTurn) put("blaze_reasoning", "low")
            // v0.26.2: routes that just came back empty or broke mid-stream; the gateway tries its next model.
            if (t.pid == Providers.GATEWAY && avoid.isNotEmpty()) put("blaze_avoid", JSONArray(avoid))
            if (tools != null && tools.length() > 0) {
                // v0.45.13: never more than 128 tools (some routes 400 "'tools': maximum number of items is 128"); the turn's
                // own and the named tool always stay (ToolTruth.cap).
                val wire = ToolTruth.cap(messages, tools)
                put("tools", try { ToolCalls.adapt(wire, t.pid, t.model) } catch (_: Exception) { wire })
                val choice = if (t.pid == Providers.GATEWAY) ToolTruth.choice(messages, wire) else "auto"
                put("tool_choice", choice)   // v0.45.9
                // v0.45.5 (#1, gateway 0.6.9): the gateway drops the schemas on a plain turn (no app / phone / file / code
                // intent), so "hi, introduce yourself" can't come back as a tool call (X-Blaze-Tools-Dropped: 1).
                // v0.45.9: not when the app named the tool. Live 0.8.7 dropped the schemas (and the named tool_choice) on
                // "remember that my NET exam is on 3 November" and "what do you remember about my exams?" 10 of 10.
                if (t.pid == Providers.GATEWAY && choice == "auto") put("blaze_tools", "auto")
            }
            // v0.45.5 (#10): reasoning models (gpt-oss) need room to reason and still answer; short turns get a floor of
            // 1024 (≥ SharedPersona.MIN_MAX_TOKENS). Longer turns keep the provider's own limit (no cap, as before).
            if (t.pid == Providers.GATEWAY && (liteTurn || quick) && !heavyTurn) put("max_tokens", maxOf(1024, SharedPersona.MIN_MAX_TOKENS))
        }
        val who = Providers.byId(t.pid).name
        // v0.45.0 (#5): Stop really ends the request: cancelling this coroutine closes the socket (cancellable), so a blocked
        // read returns at once and nothing more streams into the chat; the call ends as a CancellationException, never retried.
        cancellable(conn) {
        val code = try {
            val bytes = body.toString().toByteArray()
            StartTrace.stage("body ${bytes.size / 1024} KB")   // v0.45.9: request JSON ready (size tells the tool-schema cost)
            // v0.45.8: "on the wire" = the connection is open and the first byte is being written.
            conn.outputStream.use { StartTrace.wire(); it.write(bytes) }
            conn.responseCode.also { StartTrace.headers() }
        } catch (e: java.net.SocketTimeoutException) {
            conn.disconnect(); throw LlmException("$who · ${t.model} didn't start answering in ${conn.readTimeout / 1000}s.", 0, unanswered = true)
        } catch (e: java.io.IOException) {
            conn.disconnect(); throw LlmException("$who: couldn't reach the service (${e.message?.take(80)}).", 0, unanswered = true)
        }
        CallState.current().let { st -> st.headersMs = System.currentTimeMillis() - st.callStart }
        if (onText == null || code !in 200..299) markFirst()
        val rh = rateHeaders(conn)
        lastCallHeaders = rh
        rh.takeIf { it.isNotEmpty() }?.let { lastRateHeaders = it }
        if (t.pid == Providers.GATEWAY) lastServedBy = listOfNotNull(conn.getHeaderField("X-Blaze-Provider"), conn.getHeaderField("X-Blaze-Model"))
            .filter { it.isNotBlank() }.joinToString(" · ")
        if (t.pid == Providers.GATEWAY) lastServedKey = listOfNotNull(conn.getHeaderField("X-Blaze-Provider"), conn.getHeaderField("X-Blaze-Model"))
            .filter { it.isNotBlank() }.takeIf { it.size == 2 }?.joinToString("/").orEmpty()
        if (code in 200..299) turnModel?.served(lastServedKey)
        // v0.45.5 (#1, #5b): request id + route / timing headers for the "What happened" sheet.
        if (t.pid == Providers.GATEWAY) try {
            val g = TurnTrace.pick(conn.headerFields)
            CallState.current().let { st -> st.gw = g; g["x-request-id"]?.let { if (it !in st.requestIds) st.requestIds.add(it) }
                if (code !in 200..299) st.errors.add("HTTP $code" + (g["x-request-id"]?.let { " ($it)" } ?: "")) }
        } catch (_: Throwable) { }
        lastUsage = null
        if (code in 200..299 && onText != null &&
            (conn.contentType ?: "").contains("event-stream", ignoreCase = true)) {
            try { return@cancellable readStream(conn, onText, onThink) }
            catch (e: LlmException) { throw LlmException("${Providers.byId(t.pid).name} · ${t.model}: ${e.message}", e.code, e.retryAfterSec) }
            catch (e: java.net.SocketTimeoutException) { throw LlmException("${Providers.byId(t.pid).name} · ${t.model} stopped sending (timed out mid-reply).", 0) }
            finally { conn.disconnect() }
        }
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) {
            val msg = try {
                val o = if (text.trimStart().startsWith("[")) JSONArray(text).getJSONObject(0) else JSONObject(text)
                val e = o.opt("error")
                when (e) {
                    is JSONObject -> e.optString("message", text)
                    is JSONArray -> e.optJSONObject(0)?.optJSONObject("error")?.optString("message") ?: text
                    else -> text
                }
            } catch (_: Exception) { text }
            val retry = conn.getHeaderField("retry-after")?.trim()?.toIntOrNull() ?: 0
            if (code == 404 || msg.contains("no longer available", true) || msg.contains("not found", true))
                throw LlmException("$who: the model “${t.model}” isn't available to your key. Pick another one on the AI page (Settings, Advanced).", 404)
            // Gateway 0.3.0: signed in but not approved yet / rejected. Not an expired sign-in.
            val errCode = try { JSONObject(text).optJSONObject("error")?.optString("code").orEmpty() } catch (_: Exception) { "" }
            Cloud.access(code, "$errCode $msg")?.let { throw LlmException(it, code) }
            if (code == 401 || code == 403)
                throw LlmException("$who rejected its key ($code). Check the $who key on the AI page (Settings, Advanced): ${msg.take(160)}", code)
            // v0.42.0: keep the gateway's error code ("in_progress" on 409 is worth waiting for, see Cloud.retryable).
            throw LlmException("$who · ${t.model} said ($code): ${msg.take(300)}" + (if (errCode.isNotBlank() && errCode !in msg) " [$errCode]" else ""), code, retry)
        }
        // v0.28.9: an empty or broken 200 body showed the raw "End of input at character 0 of" error. Treat it as a
        // provider hiccup (503) so the normal retry / next-model path takes over.
        val json = try { JSONObject(text) } catch (_: Exception) { null }
        val m = json?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw LlmException("$who · ${t.model} sent an empty reply. Trying again.", 503)
        lastUsage = json.optJSONObject("usage")
        val r = m.optString("reasoning_content").ifBlank { m.optString("reasoning") }
        m.remove("reasoning_content"); m.remove("reasoning"); m.remove("reasoning_details")
        if (r.isNotBlank() && r != "null") m.put("_reasoning", r)
        m
        }
    }

    /**
     * v0.45.0 (#5): runs the blocking HTTP exchange [block] so that cancelling the calling coroutine (Stop, a watchdog,
     * a lost race) disconnects [conn] at once instead of waiting for the socket. Whatever the closed socket throws is
     * reported as the CancellationException it really is, so nothing retries it or shows it as an error.
     */
    internal suspend fun <T> cancellable(conn: HttpURLConnection, block: () -> T): T = cancellableOn({ try { conn.disconnect() } catch (_: Throwable) { } }, block)

    /** [cancellable] with any closer (tests use a fake blocking read). */
    internal suspend fun <T> cancellableOn(close: () -> Unit, block: () -> T): T = kotlinx.coroutines.coroutineScope {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { kotlinx.coroutines.awaitCancellation() } finally { if (!done.get()) close() }
        }
        try { block() }
        catch (e: Throwable) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            if (!isActive) throw kotlin.coroutines.cancellation.CancellationException("Stopped").apply { initCause(e) }
            throw e
        } finally { done.set(true); watcher.cancel() }
    }

    /** Reads an SSE chat stream, stitching text and tool-call fragments back together. */
    private fun readStream(conn: HttpURLConnection, onText: (String) -> Unit, onThink: ((String) -> Unit)?): JSONObject =
        readSse(conn.inputStream, onText, onThink)

    /**
     * Reads an SSE chat stream from [input]. v0.45.3 (Halo stall): every text chunk is handed to [onText] the moment it
     * is read. Before, a chunk that came less than 16 ms after the previous hand-off was skipped and only shown with
     * the NEXT chunk, so the end of every burst sat invisible until the model sent more (seen as a reply that froze
     * mid-list for seconds, then jumped). The agent's StreamGate already keeps it to one screen update per frame.
     */
    internal fun readSse(input: java.io.InputStream, onText: (String) -> Unit, onThink: ((String) -> Unit)?): JSONObject {
        val content = StringBuilder()
        val think = StringBuilder()
        val calls = ArrayList<JSONObject>()
        val slotOf = HashMap<Int, Int>()
        var emitted = 0
        var lastText = 0L
        CallState.current().maxGapMs = 0
        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                lastChunkAt = System.currentTimeMillis()
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = try { JSONObject(data) } catch (_: Exception) { continue }
                obj.optJSONObject("usage")?.let { lastUsage = it }
                obj.optJSONObject("error")?.let { throw LlmException("The AI service said: " + it.optString("message").take(300)) }
                val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val rp = listOf("reasoning_content", "reasoning").firstNotNullOfOrNull { k ->
                    delta.optString(k, "").takeIf { delta.has(k) && !delta.isNull(k) && it.isNotEmpty() } }
                if (rp != null) { markFirst(); think.append(rp); onThink?.invoke(think.toString()) }
                val piece = delta.optString("content", "").takeIf { delta.has("content") && !delta.isNull("content") }
                if (!piece.isNullOrEmpty()) {
                    markFirst(); markText()
                    content.append(piece)
                    onText(content.toString()); emitted = content.length
                    val now = System.currentTimeMillis()
                    if (lastText > 0) CallState.current().let { st -> if (now - lastText > st.maxGapMs) st.maxGapMs = now - lastText }
                    lastText = now
                }
                val tc = delta.optJSONArray("tool_calls") ?: continue
                markFirst()
                for (i in 0 until tc.length()) {
                    val d = tc.getJSONObject(i)
                    // Providers disagree on "index": Gemini sends each parallel call whole, in its own
                    // chunk, often all with index 0. So a chunk that brings a NEW id, or a name for a
                    // call that already has a finished name + arguments, starts a new call instead of
                    // being glued onto the last one (that glue produced "device_statusgmail_search").
                    val idx = if (d.has("index") && !d.isNull("index")) d.optInt("index", i) else i
                    val newId = d.optString("id", "").takeIf { d.has("id") && !d.isNull("id") && it.isNotBlank() }
                    val f = d.optJSONObject("function")
                    val newName = f?.optString("name", "")?.takeIf { f.has("name") && !f.isNull("name") && it.isNotBlank() }
                    var slot = slotOf[idx]
                    val cur = slot?.let { calls[it] }
                    if (cur != null) {
                        val curId = cur.optString("id", "")
                        val curFn = cur.getJSONObject("function")
                        val idChanged = newId != null && curId.isNotBlank() && newId != curId
                        val nameRestart = newName != null && curFn.optString("name").isNotBlank() &&
                            ToolCalls.completeJson(curFn.optString("arguments"))
                        if (idChanged || nameRestart) slot = null
                    }
                    if (slot == null) {
                        slot = calls.size
                        calls.add(JSONObject().put("type", "function").put("function", JSONObject().put("name", "").put("arguments", "")))
                        slotOf[idx] = slot
                    }
                    val acc = calls[slot]
                    if (newId != null) acc.put("id", newId)
                    f?.let { fn ->
                        val af = acc.getJSONObject("function")
                        if (newName != null) af.put("name", af.getString("name") + newName)
                        if (fn.has("arguments") && !fn.isNull("arguments")) {
                            val a = fn.opt("arguments")
                            af.put("arguments", af.getString("arguments") + (if (a is JSONObject) a.toString() else a.toString()))
                        }
                    }
                    // Keep each call's own extra fields (Gemini 3 thought signatures must be echoed back).
                    d.optJSONObject("extra_content")?.let { acc.put("extra_content", it) }
                }
            }
        }
        if (content.length != emitted) onText(content.toString())
        val msg = JSONObject().put("role", "assistant").put("content", if (content.isEmpty() && calls.isNotEmpty()) JSONObject.NULL else content.toString())
        if (think.isNotEmpty()) msg.put("_reasoning", think.toString())
        if (calls.isNotEmpty()) {
            val arr = JSONArray()
            val seen = HashSet<String>()
            calls.forEachIndexed { i, c ->
                if (c.getJSONObject("function").optString("name").isBlank()) return@forEachIndexed
                var id = c.optString("id", "").ifBlank { "call_$i" }
                if (!seen.add(id)) { id = "${id}_$i"; seen.add(id) }
                c.put("id", id); arr.put(c)
            }
            if (arr.length() > 0) msg.put("tool_calls", arr)
            else if (msg.isNull("content")) msg.put("content", "")
        }
        return msg
    }
}
