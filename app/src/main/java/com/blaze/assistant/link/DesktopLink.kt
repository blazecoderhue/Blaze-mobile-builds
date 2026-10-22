package com.blaze.assistant.link

import android.net.Uri
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Pairing with Blaze Desktop (Windows, macOS, Linux).
 *
 * Blaze Desktop › Phone shows a QR code: blaze://pair?h=<PC addresses>&p=<port>&fp=<cert SHA-256>&c=<one-time code>&n=<PC name>.
 * We trade the code for a token over HTTPS, pinning the PC's certificate to the fingerprint from the QR code, so nothing
 * else on the Wi-Fi can pose as the PC. After that:
 *  - ask_pc: a Blaze turn runs on the PC (its files, code, programs); its approval requests show up here.
 *  - inbox: while Blaze runs, we long-poll the PC for phone_ask requests from Blaze Desktop and answer them in a chat.
 * Works on the same Wi-Fi, or anywhere when both devices are on Tailscale (the QR carries the PC's Tailscale address).
 */
object DesktopLink {
    private const val TOK = "conn_tok_pc" // conn_tok_ prefix: stored encrypted by Settings
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inbox: Job? = null

    private val _connected = MutableStateFlow(false)
    /** True while the PC answered our last poll. */
    val connected: StateFlow<Boolean> = _connected
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    private val _pairedFlow = MutableStateFlow(false)
    val pairedFlow: StateFlow<Boolean> = _pairedFlow

    val paired: Boolean get() = Settings.secret(TOK).isNotEmpty()
    val pcName: String get() = Settings.str("pc_name", "your PC")
    private val hosts: List<String> get() = Settings.str("pc_hosts").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private val port: Int get() = Settings.str("pc_port", "7377").toIntOrNull() ?: 7377
    private val fp: String get() = Settings.str("pc_fp")

    // ---------- pinned HTTPS ----------

    private fun sha256(c: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }

    private fun pinned(fingerprint: String): Pair<SSLSocketFactory, HostnameVerifier> {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("no client certs")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
                if (!MessageDigest.isEqual(sha256(leaf).toByteArray(), fingerprint.lowercase().toByteArray()))
                    throw CertificateException("This isn't the PC you paired with.")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        // The certificate is pinned, so the host name (an IP) doesn't matter; still check the pin again here.
        val hv = HostnameVerifier { _, session ->
            (session.peerCertificates.firstOrNull() as? X509Certificate)?.let { sha256(it) == fingerprint.lowercase() } == true
        }
        return ctx.socketFactory to hv
    }

    private fun raw(host: String, prt: Int, fingerprint: String, method: String, path: String, body: String?, token: String?, timeoutMs: Int): Pair<Int, String> {
        val (sf, hv) = pinned(fingerprint)
        val h = if (host.contains(':')) "[$host]" else host
        val c = URL("https://$h:$prt$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = sf; c.hostnameVerifier = hv
        c.requestMethod = method
        c.connectTimeout = 4_000; c.readTimeout = timeoutMs
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }

    /** Call the PC, trying the address that worked last first, then the others. */
    private fun call(method: String, path: String, body: String? = null, timeoutMs: Int = 20_000): Pair<Int, String> {
        val good = Settings.str("pc_host_ok")
        val order = (listOf(good) + hosts).filter { it.isNotEmpty() && it in hosts }.distinct()
        var last: Exception = IOException("No address for ${pcName}. Pair again.")
        for (h in order) {
            try {
                val r = raw(h, port, fp, method, path, body, Settings.secret(TOK), timeoutMs)
                if (h != good) Settings.setStr("pc_host_ok", h)
                return r
            } catch (e: Exception) { last = e }
        }
        throw last
    }

    private fun err(body: String) = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()

    private fun unreachable(e: Exception) =
        if (e is javax.net.ssl.SSLException || e.cause is CertificateException) "That isn't the PC you paired with (its security key changed). Pair again from Blaze Desktop › Phone."
        else "Can't reach $pcName. Blaze Desktop must be open, and both devices on the same Wi-Fi (or both on Tailscale)."

    // ---------- pairing ----------

    /** Pair from the QR code / link Blaze Desktop shows. Returns a line for the user. */
    suspend fun pair(link: String, deviceName: String): String = withContext(Dispatchers.IO) {
        val u = runCatching { Uri.parse(link.trim()) }.getOrNull()
        if (u == null || u.scheme != "blaze" || u.host != "pair") return@withContext "That's not a Blaze Desktop pairing code. Open Blaze Desktop › Phone › Show pairing QR code."
        val hs = u.getQueryParameter("h").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val prt = u.getQueryParameter("p")?.toIntOrNull() ?: 7377
        val f = u.getQueryParameter("fp").orEmpty()
        val code = u.getQueryParameter("c").orEmpty()
        val name = u.getQueryParameter("n").orEmpty().ifBlank { "PC" }
        if (hs.isEmpty() || f.length != 64 || code.isEmpty()) return@withContext "The pairing code is incomplete. Show a new QR code on the PC."
        var lastErr = ""
        for (h in hs) {
            try {
                val (c, b) = raw(h, prt, f, "POST", "/v1/pair", JSONObject().put("code", code).put("name", deviceName).toString(), null, 15_000)
                if (c != 200) return@withContext err(b).ifBlank { "The PC said no (HTTP $c)." }
                val o = JSONObject(b)
                Settings.setStr("pc_hosts", hs.joinToString(","))
                Settings.setStr("pc_port", prt.toString())
                Settings.setStr("pc_fp", f.lowercase())
                Settings.setStr("pc_name", o.optString("pc_name", name).ifBlank { name })
                Settings.setStr("pc_device", o.optString("device_id"))
                Settings.setStr("pc_host_ok", h)
                Settings.setSecret(TOK, o.getString("token"))
                _pairedFlow.value = true
                _status.value = ""
                start()
                return@withContext "Paired with $pcName."
            } catch (e: Exception) { lastErr = e.message.orEmpty() }
        }
        "Can't reach the PC at ${hs.joinToString(", ")}. Put both on the same Wi-Fi (or both on Tailscale) and allow Blaze through the PC's firewall. ($lastErr)"
    }

    /** Forget the PC (and tell it, if it's reachable). */
    fun unpair() {
        val wasPaired = paired
        io.launch {
            if (wasPaired) runCatching { call("POST", "/v1/unpair", "{}", 5_000) }
            clearLocal()
        }
        inbox?.cancel(); inbox = null
    }

    private fun clearLocal() {
        Settings.setSecret(TOK, "")
        listOf("pc_hosts", "pc_port", "pc_fp", "pc_name", "pc_device", "pc_host_ok").forEach { Settings.setStr(it, "") }
        _connected.value = false
        _pairedFlow.value = false
    }

    /** Quick check for the settings page. */
    suspend fun ping(): String = withContext(Dispatchers.IO) {
        if (!paired) return@withContext "Not paired."
        try {
            val (c, b) = call("GET", "/v1/ping", null, 6_000)
            if (c == 401) { clearLocal(); return@withContext "$pcName unpaired this phone. Pair again." }
            val o = JSONObject(b)
            "Connected to ${o.optString("pc_name")} · Blaze Desktop ${o.optString("version")} · ${o.optString("os")}"
        } catch (e: Exception) { unreachable(e) }
    }

    // ---------- phone → PC ----------

    /**
     * Run [task] on the PC and wait for the answer. [onStep] gets the PC's progress lines.
     * Approvals the PC needs are asked here with Blaze's normal confirm card.
     */
    suspend fun askPc(task: String, fresh: Boolean, onStep: (String) -> Unit): String {
        if (!paired) return "No PC is paired. The user can pair one in Settings › Blaze Desktop (scan the QR code from Blaze Desktop › Phone)."
        val id = try {
            val (c, b) = withContext(Dispatchers.IO) { call("POST", "/v1/task", JSONObject().put("text", task).put("fresh", fresh).toString()) }
            if (c == 401) { clearLocal(); return "$pcName unpaired this phone. The user needs to pair again." }
            if (c != 200) return "The PC refused the task: ${err(b).ifBlank { "HTTP $c" }}"
            JSONObject(b).getString("id")
        } catch (e: Exception) { return unreachable(e) }
        var seen = 0
        var confirmSeen = ""
        var misses = 0
        val end = System.currentTimeMillis() + 31 * 60_000L
        while (System.currentTimeMillis() < end) {
            val o = try {
                val (c, b) = withContext(Dispatchers.IO) { call("GET", "/v1/task/$id?seen=$seen&confirm=$confirmSeen&wait=25", null, 35_000) }
                if (c != 200) throw IOException(err(b).ifBlank { "HTTP $c" })
                misses = 0
                JSONObject(b)
            } catch (e: Exception) {
                if (++misses >= 4) return "Lost the connection to $pcName while it was working (${e.message}). It may still finish there."
                delay(3_000); continue
            }
            val steps = o.optJSONArray("steps") ?: JSONArray()
            for (i in seen until steps.length()) onStep(steps.optString(i))
            seen = steps.length()
            o.optJSONObject("confirm")?.let { cf ->
                val cid = cf.optString("id")
                if (cid.isNotEmpty() && cid != confirmSeen) {
                    confirmSeen = cid
                    val ok = Agent.confirm("On $pcName: " + cf.optString("title"), cf.optString("detail"), "Allow")
                    runCatching { withContext(Dispatchers.IO) { call("POST", "/v1/confirm", JSONObject().put("task", id).put("id", cid).put("yes", ok).toString()) } }
                }
            }
            when (o.optString("status")) {
                "done" -> return "$pcName answered: " + o.optString("text").ifBlank { "(done, nothing to say)" }
                "error" -> return "$pcName couldn't finish: " + o.optString("text")
            }
        }
        runCatching { withContext(Dispatchers.IO) { call("POST", "/v1/task/$id/stop", "{}", 5_000) } }
        return "$pcName took over 30 minutes, so I stopped it."
    }

    // ---------- PC → phone ----------

    /** Start answering the PC's phone_ask requests (while Blaze is running). Safe to call again. */
    fun start() {
        _pairedFlow.value = paired
        if (inbox?.isActive == true || !paired || !Settings.bool("pc_inbox", true)) return
        inbox = io.launch {
            var backoff = 5_000L
            while (isActive && paired && Settings.bool("pc_inbox", true)) {
                try {
                    val (c, b) = call("GET", "/v1/inbox?wait=25", null, 35_000)
                    if (c == 401) { clearLocal(); _status.value = "The PC unpaired this phone."; break }
                    if (c != 200) throw IOException("HTTP $c")
                    _connected.value = true; _status.value = ""; backoff = 5_000L
                    val reqs = JSONObject(b).optJSONArray("requests") ?: JSONArray()
                    for (i in 0 until reqs.length()) {
                        val r = reqs.getJSONObject(i)
                        val send = PcMessages.parse(r)
                        io.launch { if (send != null) sendFromPc(r.optString("id"), send) else answer(r.optString("id"), r.optString("text")) }
                    }
                } catch (e: Exception) {
                    _connected.value = false
                    _status.value = unreachable(e)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
            _connected.value = false
        }
    }

    fun stopInbox() { inbox?.cancel(); inbox = null; _connected.value = false }

    /** A WhatsApp/Telegram reply drafted on the PC: the confirm card always shows, then Messaging sends it (v0.17.0). */
    private suspend fun sendFromPc(id: String, s: PcMessages.Send) {
        val reply = PcMessages.problem(s)?.let { "Not sent: $it" } ?: try {
            PcMessages.outcome(withContext(Dispatchers.Main) {
                com.blaze.assistant.phone.Messaging.run(com.blaze.assistant.agent.Agent.appContext(), JSONObject().put("to", s.to).put("text", s.text).put("app", s.app), always = true, from = pcName)
            })
        } catch (e: Exception) { "Not sent: the phone hit an error (${e.message})." }
        runCatching { call("POST", "/v1/inbox/$id/reply", JSONObject().put("text", reply).toString(), 15_000) }
    }

    private suspend fun answer(id: String, text: String) {
        val reply = try {
            withContext(Dispatchers.Main) { Agent.ask("From $pcName", "[Request from Blaze on the user's PC \"$pcName\" (paired). Do it with your tools; anything risky asks the user for approval here as usual. " +
                "Then reply in 1-3 short sentences for the PC.]\n$text", visible = true) }
        } catch (e: Exception) { "The phone hit an error: ${e.message}" }
        runCatching { call("POST", "/v1/inbox/$id/reply", JSONObject().put("text", reply).toString(), 15_000) }
    }

    // ---------- tool ----------

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "ask_pc")
        .put("description", "Ask Blaze on the user's paired computer ($pcName) to do something there, in plain words: work with its files and code projects, run programs or commands, install software, check its disk or downloads, open things on it. It works on the PC with its own tools and returns its answer. Not for things on this phone.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("task", JSONObject().put("type", "string").put("description", "The complete request for the PC, e.g. \"Run the tests in my blaze-desktop project and tell me what fails\""))
            .put("new_conversation", JSONObject().put("type", "boolean").put("description", "true to start fresh instead of continuing the last PC conversation")))
            .put("required", JSONArray(listOf("task")))))

    /** Line for the system prompt when a PC is paired. */
    fun promptLine(): String = if (!paired) "" else
        "\nPAIRED PC: \"$pcName\" runs Blaze Desktop. For anything on the computer (its files, code, programs, installs, terminal) use ask_pc with the request in plain words."
}
