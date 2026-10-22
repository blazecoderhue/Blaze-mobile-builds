package com.blaze.assistant.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.ChatStore
import com.blaze.assistant.agent.IntentCache
import com.blaze.assistant.agent.Learning
import com.blaze.assistant.agent.Memory
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.agent.Usage
import com.blaze.assistant.device.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Blaze's local dashboard: a tiny web server on the phone (default 127.0.0.1:8787) with a page to
 * chat with Blaze and see analytics. "Share on Wi-Fi" lets a laptop on the same network open it,
 * protected by a token in the link.
 */
object Dashboard {
    const val PORT = 8787
    private var server: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.blaze.assistant.device.CrashLog.guard)
    val running = MutableStateFlow(false)
    private lateinit var app: Context

    /** v0.45.0: tokens from before 0.45 were 72-bit; they are replaced by a 128-bit one once. */
    val token: String get() = Settings.str("dash_token").takeIf { it.length >= 32 } ?: rotate()

    /** v0.45.0: a new secret for the links; every old link (local and Wi-Fi) stops working. */
    fun rotate(): String { val t = DashHttp.newToken(); Settings.setStr("dash_token", t); return t }

    /** Whether the running server is bound to the Wi-Fi (0.0.0.0) rather than this phone only. */
    @Volatile private var boundLan = false
    var lan: Boolean
        get() = Settings.bool("dash_lan", false)
        set(v) = Settings.setBool("dash_lan", v)

    fun localUrl() = "http://127.0.0.1:$PORT/?t=$token"
    fun lanUrl(): String? = wifiIp()?.let { "http://$it:$PORT/?t=$token" }

    private fun wifiIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    @Synchronized fun start(ctx: Context) {
        app = ctx.applicationContext
        if (server != null) return
        val s = try { ServerSocket(PORT, 16, if (lan) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")) }
            catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("dashboard start", e); return }
        server = s; boundLan = lan; running.value = true
        scope.launch {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                scope.launch {
                    try { handle(c) }
                    catch (e: DashHttp.BadRequest) { runCatching { send(c.getOutputStream(), e.code, "text/plain", e.message.orEmpty()) } }
                    catch (e: java.net.SocketTimeoutException) { }
                    catch (e: java.io.IOException) { }
                    catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("dashboard request", e); runCatching { send(c.getOutputStream(), 500, "text/plain", "Blaze dashboard error") } }
                    finally { runCatching { c.close() } }
                }
            }
        }
    }

    /**
     * [expire]: also rotate the token. v0.45.0: a server that was shared on the Wi-Fi expires its link when it stops
     * (or when sharing is switched), so a link seen on the network doesn't keep working the next time.
     */
    @Synchronized fun stop(expire: Boolean = boundLan) {
        runCatching { server?.close() }; server = null; running.value = false
        if (expire) rotate()
        boundLan = false
    }
    fun restart(ctx: Context) { stop(); start(ctx) }

    fun open(ctx: Context) {
        start(ctx)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(localUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun handle(c: Socket) {
        c.soTimeout = 30_000
        val inp = java.io.BufferedInputStream(c.getInputStream())
        val head = DashHttp.readHead(inp) ?: return
        val out = c.getOutputStream()
        // v0.45.0: token first; nothing of the body is read for a caller without the link.
        if (!DashHttp.tokenOk(head.token, token)) return send(out, 401, "text/plain", "Blaze dashboard: open the link from Blaze (Settings → Dashboard).")
        val method = head.method; val path = head.path; val q = head.query
        // Long agent turns (/api/send) may take minutes; the body itself must arrive promptly.
        val body = DashHttp.readBody(inp, head)
        c.soTimeout = 300_000
        val remote = !(c.inetAddress?.isLoopbackAddress ?: true)
        when {
            path == "/" -> send(out, 200, "text/html; charset=utf-8", Page.html)
            path == "/api/stats" -> send(out, 200, "application/json", stats().toString())
            path == "/api/chats" -> send(out, 200, "application/json", JSONArray().apply {
                ChatStore.list().take(60).forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated).put("preview", it.preview)) } }.toString())
            path == "/api/chat" -> {
                val m = Agent.messagesOf(q["id"].orEmpty()) ?: emptyList()
                send(out, 200, "application/json", JSONArray().apply { m.forEach { put(JSONObject().put("role", it.role.name).put("text", it.text).put("meta", it.meta)
                    .put("id", it.gid).apply { if (it.reactions.isNotEmpty()) put("reactions", com.blaze.assistant.agent.Reaction.json(it.reactions)) }) } }.toString())
            }
            path == "/api/send" && method == "POST" -> {
                val o = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val (id, reply) = runBlocking { Agent.askIn(o.optString("chat").ifBlank { null }, o.optString("text"),
                    untrusted = if (remote) com.blaze.assistant.agent.ConfirmGate.DASHBOARD else null) }
                send(out, 200, "application/json", JSONObject().put("chat", id).put("reply", reply).toString())
            }
            // v0.33.0 Desktop Bridge.
            path == "/api/clipboard" && method == "POST" -> {
                val o = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                send(out, 200, "application/json", JSONObject().put("ok", Bridge.setClipboard(app, o.optString("text"))).toString())
            }
            path == "/api/clipboard" -> send(out, 200, "application/json", JSONObject().put("text", Bridge.clipboard(app) ?: JSONObject.NULL).toString())
            path == "/api/files" -> send(out, 200, "application/json", Bridge.files(app).toString())
            path == "/api/file" -> {
                val f = java.io.File(q["path"].orEmpty())
                if (!Bridge.allowed(f, Bridge.roots(app))) return send(out, 404, "text/plain", "Not found")
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: ${Bridge.mime(f.name)}\r\nContent-Length: ${f.length()}\r\n" +
                    "Content-Disposition: attachment; filename=\"${Bridge.safeName(f.name)}\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                f.inputStream().use { it.copyTo(out) }; out.flush()
            }
            path == "/api/upload" && method == "POST" -> {
                val o = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val r = Bridge.upload(o.optString("name"), o.optString("data"))
                send(out, 200, "application/json", (if (!r.startsWith("!")) JSONObject().put("ok", true).put("name", r) else JSONObject().put("ok", false).put("error", r.drop(1))).toString())
            }
            else -> send(out, 404, "text/plain", "Not found")
        }
    }

    private fun stats(): JSONObject {
        val d = DeviceStatus.snap(app)
        val t = Usage.today(); val m = Usage.month()
        return JSONObject()
            .put("version", runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrDefault(""))
            .put("model", Settings.model).put("provider", Settings.provider.name)
            .put("today", JSONObject().put("tokens", t.input + t.output).put("cost", t.cost).put("calls", t.calls))
            .put("month", JSONObject().put("tokens", m.input + m.output).put("cost", m.cost).put("calls", m.calls))
            .put("daily", JSONArray(Usage.daily(14)))
            .put("chats", ChatStore.list().size).put("memories", Memory.facts.value.size).put("skills", Learning.skills.value.size)
            .put("instant", IntentCache.size()).put("jobs", com.blaze.assistant.terminal.Terminal.jobCount())
            .put("device", JSONObject().put("battery", d.battery).put("charging", d.charging).put("temp", d.tempC)
                .put("storageFree", d.freeStorageGb).put("storageTotal", d.totalStorageGb).put("ramFree", d.freeRamMb).put("ramTotal", d.totalRamMb).put("network", d.network))
            .put("warnings", JSONArray(DeviceStatus.warnings(d)))
    }

    private fun send(out: OutputStream, code: Int, type: String, body: String) {
        val b = body.toByteArray()
        out.write("HTTP/1.1 $code ${DashHttp.status(code)}\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }
}
