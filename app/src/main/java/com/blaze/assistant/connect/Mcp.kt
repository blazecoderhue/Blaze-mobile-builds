package com.blaze.assistant.connect

import com.blaze.assistant.agent.Settings
import com.blaze.assistant.connect.McpConfig.Server
import com.blaze.assistant.connect.McpConfig.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Remote MCP servers (Model Context Protocol, JSON-RPC 2.0) over Streamable HTTP, or the older HTTP+SSE
 * transport. stdio servers need a PC, so they are listed but not run. Tools appear to the model as
 * mcp__<server>__<tool> and go through the same approval as Blaze Desktop: read-only tools run, others
 * show the confirm card (Action tools pass in Autopilot), destructive ones always ask, and each tool can
 * be set to Ask / Always / Off.
 *
 * The list is the mcp.json shape Blaze CLI and Desktop use ({"mcpServers": {...}}, see [McpConfig]),
 * stored encrypted (headers hold tokens) and synced through the Blaze account with the model keys
 * (entry "mcp" in the /v1/keys blob, see [KeySync]).
 */
object Mcp {
    private const val PREF = "mcp_config"
    private const val PROTOCOL = "2025-06-18"
    private const val CLIENT_VERSION = "0.9.0"

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Per server name: "12 tools", "Error: …", "Needs a PC (stdio)", "Connecting…". */
    val status: StateFlow<Map<String, String>> = _status
    private val _tools = MutableStateFlow<Map<String, List<Tool>>>(emptyMap())
    /** Per server name: its tools, once listed. */
    val toolLists: StateFlow<Map<String, List<Tool>>> = _tools

    private val conns = ConcurrentHashMap<String, Conn>()

    // ---------------- config ----------------

    fun load() {
        val saved = Settings.secret(PREF)
        var list = try { McpConfig.parse(saved) } catch (_: Exception) { emptyList() }
        if (saved.isBlank()) {
            // Blaze for Android 1.7 – 0.8.0 kept servers in "mcp_servers" with a token per id.
            val legacy = Settings.str("mcp_servers", "")
            if (legacy.isNotBlank()) {
                list = McpConfig.fromLegacy(legacy) { Settings.secret("mcp_tok_$it") }
                Settings.setSecret(PREF, McpConfig.export(list))
                Settings.setStr("mcp_servers_backup", legacy)
                Settings.setStr("mcp_servers", "")
            }
        }
        _servers.value = list
        list.filter { !it.remote }.forEach { setStatus(it.name, if (it.transport == McpConfig.STDIO) "Needs a PC (runs on Blaze Desktop/CLI)" else "No URL") }
    }

    private fun save(l: List<Server>, sync: Boolean = true) {
        val gone = _servers.value.map { it.name }.toSet() - l.map { it.name }.toSet()
        val changed = l.filter { n -> _servers.value.firstOrNull { it.name == n.name }?.let { it.url != n.url || it.headers != n.headers || it.transport != n.transport } ?: false }
        (gone + changed.map { it.name }).forEach { drop(it) }
        _servers.value = l
        Settings.setSecret(PREF, McpConfig.export(l))
        if (sync) KeySync.localChange(KeySync.MCP)
    }

    private fun drop(name: String) {
        conns.remove(name)?.close()
        _tools.value = _tools.value - name
        _status.value = _status.value - name
    }

    fun add(name: String, url: String, token: String, headerLines: String = "", transport: String = McpConfig.AUTO): String {
        val n = name.trim().ifEmpty { return "Give the server a name." }
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "The URL should start with https://"
        val old = _servers.value.firstOrNull { it.name == n }
        val s = Server(n, u, McpConfig.headersFrom(token, headerLines), transport, true, old?.toolModes ?: emptyMap(), old?.raw ?: JSONObject())
        save(_servers.value.filterNot { it.name == n } + s)
        return ""
    }

    fun remove(name: String) = save(_servers.value.filterNot { it.name == name })
    fun toggle(name: String) = save(_servers.value.map { if (it.name == name) it.copy(enabled = !it.enabled) else it })

    fun setToolMode(server: String, tool: String, mode: String) = save(_servers.value.map { s ->
        if (s.name != server) s else s.copy(toolModes = if (mode.isBlank()) s.toolModes - tool else s.toolModes + (tool to mode))
    })

    /** The list as mcp.json text (for copying to a PC). */
    fun exportJson(): String = McpConfig.export(_servers.value, pretty = true)

    /** Pastes an mcp.json (adds/replaces by name). Returns a plain sentence. */
    fun importJson(text: String, replace: Boolean = false): String = try {
        val inc = McpConfig.parse(text)
        if (inc.isEmpty()) "No servers in that JSON." else {
            save(McpConfig.merge(_servers.value, inc, replace))
            load()
            "Added ${inc.size} server${if (inc.size == 1) "" else "s"}: ${inc.joinToString { it.name }}."
        }
    } catch (e: Exception) { e.message ?: "Couldn't read that JSON." }

    /** KeySync: the account's copy won; take it without syncing back. */
    internal fun applySynced(text: String) {
        val l = try { McpConfig.parse(text) } catch (_: Exception) { return }
        save(l, sync = false); load()
    }

    internal fun syncedText(): String = if (_servers.value.isEmpty()) "" else McpConfig.export(_servers.value)

    private fun setStatus(name: String, s: String) { _status.value = _status.value + (name to s) }

    // ---------------- connections ----------------

    private class SessionGone : Exception("session expired")
    private class NotStreamable(code: Int) : Exception("HTTP $code")

    private interface Conn {
        fun request(method: String, params: JSONObject?): JSONObject?
        fun notify(method: String)
        fun close()
    }

    private val ids = AtomicLong(1)

    private fun httpOpen(url: String, method: String, headers: Map<String, String>, readMs: Int): HttpURLConnection =
        UrlSafety.open(url).apply {
            requestMethod = method; connectTimeout = 15_000; readTimeout = readMs
            setRequestProperty("User-Agent", "Blaze-Android")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun refused(code: Int, body: String) =
        Exception("the server refused the request (HTTP $code). Check the token or headers. ${body.take(200)}".trim())

    private fun envelope(id: Long?, method: String, params: JSONObject?) = JSONObject().put("jsonrpc", "2.0").put("method", method).apply {
        if (id != null) put("id", id)
        if (params != null) put("params", params)
    }

    private fun result(o: JSONObject?): JSONObject? {
        if (o == null) throw Exception("the server closed the stream without answering")
        o.optJSONObject("error")?.let { throw Exception("MCP error ${it.optInt("code")}: ${it.optString("message")}") }
        return o.optJSONObject("result") ?: JSONObject()
    }

    /** Streamable HTTP: one POST per message; the reply is JSON or an SSE stream. */
    private class HttpConn(val url: String, val headers: Map<String, String>) : Conn {
        @Volatile var session: String? = null
        @Volatile var proto: String? = null

        private fun post(body: JSONObject, wantId: Long?): JSONObject? {
            val c = httpOpen(url, "POST", headers, 120_000)
            val sent = session
            try {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "application/json, text/event-stream")
                sent?.let { c.setRequestProperty("Mcp-Session-Id", it) }
                proto?.let { c.setRequestProperty("MCP-Protocol-Version", it) }
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                c.getHeaderField("Mcp-Session-Id")?.let { session = it }
                if (code >= 300) {
                    val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    when {
                        code == 404 && sent != null -> { session = null; proto = null; throw SessionGone() }
                        code == 401 || code == 403 -> throw refused(code, err)
                        body.optString("method") == "initialize" && code in setOf(400, 404, 405) -> throw NotStreamable(code)
                        else -> throw Exception("HTTP $code ${err.take(200)}".trim())
                    }
                }
                if (body.optString("method") == "initialize") proto = PROTOCOL
                if (wantId == null) return null
                return if (c.contentType.orEmpty().startsWith("text/event-stream")) {
                    var found: JSONObject? = null
                    c.inputStream.bufferedReader().use { r ->
                        McpConfig.readSse(r.lineSequence()) { _, data ->
                            val o = try { JSONObject(data) } catch (_: Exception) { null }
                            if (o != null && McpConfig.isReplyTo(o, wantId)) { found = o; false } else true
                        }
                    }
                    found
                } else McpConfig.pickReply(c.inputStream.bufferedReader().use { it.readText() }, wantId)
            } finally { c.disconnect() }
        }

        override fun request(method: String, params: JSONObject?): JSONObject? {
            val id = ids.getAndIncrement()
            return result(post(envelope(id, method, params), id))
        }
        override fun notify(method: String) { try { post(envelope(null, method, null), null) } catch (_: Exception) { } }
        override fun close() {
            val s = session ?: return
            Thread {
                try { httpOpen(url, "DELETE", headers, 3_000).apply { setRequestProperty("Mcp-Session-Id", s) }.let { it.responseCode; it.disconnect() } } catch (_: Exception) { }
            }.start()
        }
    }

    /** The older HTTP+SSE transport: GET an event stream, wait for "endpoint", POST there; replies come on the stream. */
    private class SseConn(val url: String, val headers: Map<String, String>) : Conn {
        private val waiting = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
        @Volatile private var dead: Exception? = null
        @Volatile private var stream: HttpURLConnection? = null
        private val endpoint = CompletableFuture<String>()

        fun start(): SseConn {
            val c = httpOpen(url, "GET", headers, 0).apply { setRequestProperty("Accept", "text/event-stream") }
            stream = c
            val code = c.responseCode
            if (code == 401 || code == 403) throw refused(code, c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
            if (code >= 300) throw Exception("HTTP $code from the SSE stream")
            Thread {
                try {
                    c.inputStream.bufferedReader().use { r ->
                        McpConfig.readSse(r.lineSequence()) { event, data ->
                            if (event == "endpoint") endpoint.complete(URL(URL(url), data.trim()).toString()) else deliver(data)
                            true
                        }
                    }
                    fail(Exception("the server closed the event stream"))
                } catch (e: Exception) { fail(e) }
            }.apply { isDaemon = true }.start()
            try { endpoint.get(15, TimeUnit.SECONDS) } catch (_: Exception) { close(); throw Exception("the SSE server never sent its endpoint event") }
            return this
        }

        private fun deliver(data: String) {
            val o = try { JSONObject(data) } catch (_: Exception) { return }
            if (o.has("method")) {
                // A request from the server: answer pings, politely refuse the rest.
                if (o.has("id")) {
                    val reply = JSONObject().put("jsonrpc", "2.0").put("id", o.get("id"))
                    if (o.optString("method") == "ping") reply.put("result", JSONObject())
                    else reply.put("error", JSONObject().put("code", -32601).put("message", "Blaze doesn't support ${o.optString("method")}"))
                    Thread { try { send(reply) } catch (_: Exception) { } }.start()
                }
                return
            }
            val id = o.opt("id")?.toString()?.trim('"')?.toLongOrNull() ?: return
            waiting.remove(id)?.complete(o)
        }

        private fun fail(e: Exception) {
            if (dead == null) dead = e
            waiting.values.forEach { it.completeExceptionally(e) }; waiting.clear()
        }

        private fun send(body: JSONObject) {
            val c = httpOpen(endpoint.getNow(null) ?: throw Exception("not connected"), "POST", headers, 30_000)
            try {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code >= 300) throw Exception("HTTP $code from the server")
            } finally { c.disconnect() }
        }

        val alive get() = dead == null

        override fun request(method: String, params: JSONObject?): JSONObject? {
            dead?.let { throw it }
            val id = ids.getAndIncrement()
            val f = CompletableFuture<JSONObject>(); waiting[id] = f
            try { send(envelope(id, method, params)) } catch (e: Exception) { waiting.remove(id); throw e }
            return try { result(f.get(120, TimeUnit.SECONDS)) }
            catch (e: java.util.concurrent.TimeoutException) { waiting.remove(id); throw Exception("the server didn't answer in 2 minutes") }
            catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception) ?: e }
        }
        override fun notify(method: String) { try { send(envelope(null, method, null)) } catch (_: Exception) { } }
        override fun close() { fail(Exception("closed")); try { stream?.disconnect() } catch (_: Exception) { } }
    }

    private fun initialize(c: Conn) {
        c.request("initialize", JSONObject().put("protocolVersion", PROTOCOL).put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Blaze Android").put("version", CLIENT_VERSION)))
        c.notify("notifications/initialized")
    }

    private fun looksSse(url: String) = try { URL(url).path.trimEnd('/').endsWith("/sse") } catch (_: Exception) { false }

    /** A live, initialized connection (Streamable HTTP first; SSE when asked for or when HTTP isn't offered). */
    private fun connect(s: Server): Conn {
        conns[s.name]?.let { if (it !is SseConn || it.alive) return it else conns.remove(s.name) }
        val conn: Conn = if (s.transport == McpConfig.SSE || (s.transport == McpConfig.AUTO && looksSse(s.url))) {
            SseConn(s.url, s.headers).start().also { initialize(it) }
        } else try {
            HttpConn(s.url, s.headers).also { initialize(it) }
        } catch (e: NotStreamable) {
            if (s.transport == McpConfig.HTTP) throw Exception("the server doesn't speak Streamable HTTP (${e.message})")
            SseConn(s.url, s.headers).start().also { initialize(it) }
        }
        conns[s.name] = conn
        return conn
    }

    private fun <T> withConn(s: Server, block: (Conn) -> T): T = try { block(connect(s)) } catch (e: SessionGone) {
        conns.remove(s.name); block(connect(s))
    }

    /** Connects and lists a server's tools. Returns the status line. */
    suspend fun refresh(s: Server): String = withContext(Dispatchers.IO) {
        if (!s.remote) return@withContext (if (s.transport == McpConfig.STDIO) "Needs a PC (runs on Blaze Desktop/CLI)" else "No URL").also { setStatus(s.name, it) }
        setStatus(s.name, "Connecting…")
        try {
            conns.remove(s.name)?.close()
            val all = mutableListOf<Tool>()
            withConn(s) { c ->
                var cursor = ""
                for (page in 0 until 20) {
                    val r = c.request("tools/list", JSONObject().apply { if (cursor.isNotEmpty()) put("cursor", cursor) })
                    all += McpConfig.tools(r)
                    cursor = r?.optString("nextCursor").orEmpty()
                    if (cursor.isEmpty()) break
                }
            }
            _tools.value = _tools.value + (s.name to all)
            "${all.size} tool${if (all.size == 1) "" else "s"}".also { setStatus(s.name, it) }
        } catch (e: Exception) { ("Error: " + (e.message ?: e.javaClass.simpleName)).also { setStatus(s.name, it) } }
    }

    /** Tools of enabled servers (minus ones turned off), in OpenAI format. Lists servers not yet listed (quickly). */
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    /**
     * v0.45.9: never a network call on the send path. Before, a server whose tool list wasn't loaded yet (first Send after
     * launch) was refreshed here, up to 8 s per server, before the chat request could go out. Now the refresh runs in the
     * background and this turn goes out with the tools already known; the next turn has the server's tools.
     */
    suspend fun tools(): List<JSONObject> {
        val on = _servers.value.filter { it.enabled && it.remote }
        for (s in on) if (_tools.value[s.name] == null && _status.value[s.name]?.startsWith("Error") != true && refreshing.add(s.name))
            bg.launch { try { withTimeoutOrNull(15_000) { refresh(s) } } catch (_: Throwable) { } finally { refreshing.remove(s.name) } }
        return on.flatMap { s -> _tools.value[s.name].orEmpty().filter { McpConfig.mode(s.toolModes[it.name], it.risk) != McpConfig.OFF }.map { t ->
            JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", McpConfig.toolName(s.name, t.name))
                .put("description", ("[${s.name}] " + t.description).take(900))
                .put("parameters", t.schema ?: JSONObject().put("type", "object").put("properties", JSONObject())))
        } }
    }

    fun handles(name: String) = McpConfig.isToolName(name)

    /** An MCP tool the model called, with what the approval check needs. */
    data class Found(val server: Server, val tool: Tool, val override: String?) {
        val mode: String get() = McpConfig.mode(override, tool.risk)
    }

    fun find(name: String): Found? {
        for (s in _servers.value.filter { it.enabled && it.remote }) {
            val t = _tools.value[s.name]?.firstOrNull { McpConfig.toolName(s.name, it.name) == name } ?: continue
            return Found(s, t, s.toolModes[t.name])
        }
        return null
    }

    fun hasConfig() = _servers.value.isNotEmpty()

    /** For Plan mode: true when the tool declares it only reads (and isn't turned off). */
    fun readOnly(name: String): Boolean = find(name)?.let { it.tool.risk == McpConfig.Risk.READ && it.mode != McpConfig.OFF } ?: false

    /** Runs the tool (approval is the caller's job, see Agent.mcpCall). */
    suspend fun call(f: Found, args: JSONObject): String = withContext(Dispatchers.IO) {
        try {
            McpConfig.flatten(withConn(f.server) { it.request("tools/call", JSONObject().put("name", f.tool.name).put("arguments", args)) }).take(12_000)
        } catch (e: Exception) {
            conns.remove(f.server.name)?.close()
            "MCP error from ${f.server.name}: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
