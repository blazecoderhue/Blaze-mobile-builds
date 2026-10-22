package com.blaze.assistant.connect

import org.json.JSONArray
import org.json.JSONObject

/**
 * The MCP server list in the shape Blaze CLI and Desktop keep in ~/.blaze/mcp.json:
 *
 *   {"mcpServers": {"github": {"url": "https://…/mcp", "headers": {"Authorization": "Bearer …"}}}}
 *
 * Optional per server: "type" ("http" | "streamable-http" | "sse" | "stdio"), "disabled": true, and
 * Blaze's own "toolModes": {"<tool>": "ask" | "always" | "off"}. Anything else in an entry (a desktop's
 * "command", "args", "env") is kept as it is, so a list synced from a PC survives the round trip; stdio
 * servers are listed on the phone but can't run here.
 *
 * Pure Kotlin + org.json so it is unit-tested on the JVM. The network side is [Mcp].
 */
object McpConfig {
    const val HTTP = "http"
    const val SSE = "sse"
    const val STDIO = "stdio"
    const val AUTO = ""

    const val ASK = "ask"
    const val ALWAYS = "always"
    const val OFF = "off"

    enum class Risk { READ, ACTION, WRITE }

    data class Server(
        val name: String,
        val url: String = "",
        val headers: Map<String, String> = emptyMap(),
        /** [HTTP], [SSE], [STDIO] or [AUTO] (Streamable HTTP, falling back to SSE). */
        val transport: String = AUTO,
        val enabled: Boolean = true,
        val toolModes: Map<String, String> = emptyMap(),
        /** The entry as it came in; unknown keys are written back untouched. */
        val raw: JSONObject = JSONObject(),
    ) {
        val id: String get() = slug(name)
        /** Runs on the phone: has a URL and isn't a local (stdio) server. */
        val remote: Boolean get() = transport != STDIO && url.isNotBlank()
    }

    data class Tool(val name: String, val description: String, val schema: JSONObject?, val readOnly: Boolean, val destructive: Boolean) {
        val risk: Risk get() = risk(readOnly, destructive)
    }

    // ---------------- names ----------------

    /** A server's name as it appears in tool names: lowercase letters, digits and single underscores. */
    fun slug(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "server" }

    /**
     * How a server's tool appears to the model: mcp__<server>__<tool> (the CLI/Desktop mcp.json convention).
     * Function names are limited to 64 characters; a longer one keeps a short hash so two tools never collide.
     */
    fun toolName(server: String, tool: String): String {
        val t = tool.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val n = "mcp__${slug(server)}__$t"
        if (n.length <= 64) return n
        val h = Integer.toHexString(n.hashCode()).padStart(8, '0').take(6)
        return n.take(57) + "_" + h
    }

    fun isToolName(name: String) = name.startsWith("mcp__") || name.startsWith("mcp_")

    // ---------------- approval (port of Desktop mcpRisk / effMode / approve) ----------------

    fun risk(readOnly: Boolean, destructive: Boolean): Risk = when {
        destructive -> Risk.WRITE
        readOnly -> Risk.READ
        else -> Risk.ACTION
    }

    fun defaultMode(r: Risk) = if (r == Risk.READ) ALWAYS else ASK

    fun mode(override: String?, r: Risk): String = when (override) { ASK, ALWAYS, OFF -> override; else -> defaultMode(r) }

    /**
     * Whether a call needs the user's tap. "always" runs; "off" never gets here; Ask shows the card. Like
     * Desktop, an Action tool the user left on its default also runs in Autopilot; a Write (destructive) one
     * always asks.
     */
    fun needsApproval(override: String?, r: Risk, autopilot: Boolean): Boolean {
        val m = mode(override, r)
        if (m == ALWAYS) return false
        if (r == Risk.ACTION && (override.isNullOrBlank()) && autopilot) return false
        return true
    }

    // ---------------- mcp.json ----------------

    private fun transportOf(o: JSONObject): String {
        val t = (o.optString("type").ifBlank { o.optString("transport") }).lowercase().replace("_", "-")
        return when {
            t == "sse" -> SSE
            t in setOf("http", "streamable-http", "streamablehttp", "streamable") -> HTTP
            t == "stdio" || (o.has("command") && !o.has("url") && !o.has("serverUrl")) -> STDIO
            else -> AUTO
        }
    }

    private fun strMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (k in o.keys()) m[k] = o.optString(k)
        return m
    }

    fun parseEntry(name: String, o: JSONObject): Server = Server(
        name = name,
        url = o.optString("url").ifBlank { o.optString("serverUrl") }.trim(),
        headers = strMap(o.optJSONObject("headers")),
        transport = transportOf(o),
        enabled = !o.optBoolean("disabled", false) && o.optBoolean("enabled", true),
        toolModes = strMap(o.optJSONObject("toolModes")).filterValues { it in setOf(ASK, ALWAYS, OFF) },
        raw = JSONObject(o.toString()),
    )

    /** Reads {"mcpServers": {...}} (or a bare {name: {...}} map). Bad input gives an exception with a plain message. */
    fun parse(text: String): List<Server> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val root = try { JSONObject(t) } catch (e: Exception) { throw IllegalArgumentException("That isn't valid JSON: ${e.message?.take(80)}") }
        val map = root.optJSONObject("mcpServers") ?: root.optJSONObject("servers")
            ?: if (root.keys().asSequence().all { root.opt(it) is JSONObject }) root else throw IllegalArgumentException("Expected {\"mcpServers\": {…}}")
        return map.keys().asSequence().toList().mapNotNull { k -> map.optJSONObject(k)?.let { parseEntry(k, it) } }
    }

    fun entry(s: Server): JSONObject {
        val o = JSONObject(s.raw.toString())
        o.remove("serverUrl"); o.remove("enabled"); o.remove("transport")
        if (s.url.isNotBlank()) o.put("url", s.url) else o.remove("url")
        if (s.headers.isNotEmpty()) o.put("headers", JSONObject(s.headers as Map<*, *>)) else o.remove("headers")
        when (s.transport) {
            HTTP -> o.put("type", "http")
            SSE -> o.put("type", "sse")
            STDIO -> o.put("type", "stdio")
            else -> if (o.optString("type").lowercase() in setOf("http", "sse", "streamable-http")) o.remove("type")
        }
        if (!s.enabled) o.put("disabled", true) else o.remove("disabled")
        if (s.toolModes.isNotEmpty()) o.put("toolModes", JSONObject(s.toolModes as Map<*, *>)) else o.remove("toolModes")
        return o
    }

    /** The list as mcp.json text (stable order). */
    fun export(list: List<Server>, pretty: Boolean = false): String {
        val m = JSONObject()
        for (s in list) m.put(s.name, entry(s))
        val root = JSONObject().put("mcpServers", m)
        return if (pretty) root.toString(2) else root.toString()
    }

    /** Adds or replaces servers by name; [replace] drops the ones not in [incoming]. */
    fun merge(current: List<Server>, incoming: List<Server>, replace: Boolean): List<Server> {
        if (replace) return incoming
        val out = current.associateBy { it.name }.toMutableMap()
        for (s in incoming) out[s.name] = s
        return out.values.toList()
    }

    /** Servers saved by Blaze for Android 1.7 – 0.8.0 (pref "mcp_servers" + a bearer token per id). */
    fun fromLegacy(serversJson: String, token: (String) -> String): List<Server> = try {
        val a = JSONArray(serversJson)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val tok = token(o.optString("id")).trim()
            Server(name = o.optString("name").ifBlank { o.optString("id") }, url = o.optString("url").trim(),
                headers = if (tok.isNotEmpty()) mapOf("Authorization" to "Bearer $tok") else emptyMap(),
                enabled = o.optBoolean("enabled", true))
        }.filter { it.url.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    /** "Key: value" lines (as typed in Settings) → headers. A bare token becomes a Bearer Authorization header. */
    fun headersFrom(token: String, lines: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (l in lines.lines()) {
            val i = l.indexOf(':'); if (i <= 0) continue
            val k = l.substring(0, i).trim(); val v = l.substring(i + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) m[k] = v
        }
        val t = token.trim()
        if (t.isNotEmpty() && m.keys.none { it.equals("Authorization", true) })
            m["Authorization"] = if (t.contains(' ')) t else "Bearer $t"
        return m
    }

    // ---------------- wire helpers ----------------

    /** True when [o] is the reply (not a request) to JSON-RPC id [id]. */
    fun isReplyTo(o: JSONObject, id: Long): Boolean =
        !o.has("method") && o.has("id") && o.get("id").toString().trim('"') == id.toString()

    /** Finds the reply to [id] in a JSON-RPC body: a single message or a batch. */
    fun pickReply(body: String, id: Long): JSONObject? {
        val t = body.trim()
        if (t.startsWith("[")) {
            val a = JSONArray(t)
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (isReplyTo(it, id)) return it }
            return null
        }
        if (!t.startsWith("{")) return null
        return JSONObject(t)
    }

    /**
     * Server-Sent Events: calls [fn](event, data) for each event until it returns false. Multi-line data
     * is joined with newlines; comment lines (":") are skipped; a final event without a blank line counts.
     */
    fun readSse(lines: Sequence<String>, fn: (String, String) -> Boolean) {
        var event = ""; val data = mutableListOf<String>()
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) {
                if (data.isNotEmpty() && !fn(event, data.joinToString("\n"))) return
                event = ""; data.clear(); continue
            }
            if (line.startsWith(":")) continue
            val i = line.indexOf(':')
            val k = if (i < 0) line else line.substring(0, i)
            val v = if (i < 0) "" else line.substring(i + 1).removePrefix(" ")
            when (k) { "event" -> event = v; "data" -> data += v }
        }
        if (data.isNotEmpty()) fn(event, data.joinToString("\n"))
    }

    /** Tools from a tools/list result. */
    fun tools(result: JSONObject?): List<Tool> {
        val a = result?.optJSONArray("tools") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("name").isNotBlank() }.map { t ->
            val ann = t.optJSONObject("annotations")
            Tool(t.optString("name"), t.optString("description"), t.optJSONObject("inputSchema"),
                readOnly = ann?.optBoolean("readOnlyHint", false) == true, destructive = ann?.optBoolean("destructiveHint", false) == true)
        }
    }

    /** A tools/call result flattened to text, like Desktop's callTool. */
    fun flatten(r: JSONObject?): String {
        val content = r?.optJSONArray("content") ?: JSONArray()
        val b = StringBuilder()
        for (i in 0 until content.length()) {
            val x = content.optJSONObject(i) ?: continue
            when (x.optString("type")) {
                "text" -> b.append(x.optString("text"))
                "resource" -> x.optJSONObject("resource")?.let { b.append("[resource ${it.optString("uri")}]\n").append(it.optString("text")) }
                else -> b.append("[${x.optString("type")} ${x.optString("mimeType")} content]")
            }
            b.append('\n')
        }
        var out = b.toString().trim()
        if (out.isEmpty()) out = r?.optJSONObject("structuredContent")?.toString().orEmpty()
        if (out.isEmpty()) out = "Done."
        return if (r?.optBoolean("isError") == true) "Tool error: $out" else out
    }
}
