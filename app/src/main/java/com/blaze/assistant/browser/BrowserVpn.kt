package com.blaze.assistant.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The browser's VPN. Your WireGuard configs (one per country) run through wgproxy, a user-space
 * WireGuard tunnel shipped as libwgproxy.so, which serves an HTTP proxy on 127.0.0.1. Only Blaze's
 * browser WebViews use it (ProxyController), so chats with your model don't go through the VPN and
 * Android shows no VPN prompt.
 *
 * v0.25.3: "Blaze Free (Cloudflare WARP)" works with one tap and no imports: a free WARP device is
 * registered from the phone the first time (WarpFree), saved in filesDir/blaze_free.json and reused.
 * Dropped tunnels reconnect with backoff, a health check runs every 45 s, and blocked UDP ports are
 * skipped by trying WARP's other ports.
 *
 * Kill switch: while the VPN is on, the browser never goes direct. If the tunnel drops or fails to
 * start, pages fail to load instead of loading from your real IP, until you pick a working location
 * or switch the VPN off.
 */
object BrowserVpn {
    data class Location(val id: String, val name: String, val country: String?, val conf: String) {
        val label: String get() = Countries.byCode(country)?.name ?: name
        val flag: String get() = Countries.flag(country.orEmpty())
    }

    enum class Status { OFF, CONNECTING, ON, FAILED }

    val locations = mutableStateListOf<Location>()
    var status by mutableStateOf(Status.OFF); private set
    var selected by mutableStateOf<String?>(null); private set
    var exitIp by mutableStateOf(""); private set
    /** Country the tunnel really exits in, from Cloudflare's trace through the tunnel. */
    var exitCountry by mutableStateOf<String?>(null); private set
    var error by mutableStateOf(""); private set
    /** One plain-words line about what the VPN is doing right now (setting up, trying another port, reconnecting in 8 s…). */
    var detail by mutableStateOf(""); private set
    /** Mask location (geolocation, time zone, language, WebRTC) in private tabs, and in every tab while the VPN is on. */
    var maskLocation by mutableStateOf(true); private set

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val mainExec = Executor { main.post(it) }
    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private var port = 0
    /** Bumped when the tunnel changes, so a dying old process doesn't flag the new one as failed. */
    @Volatile private var gen = 0
    /** Called on the main thread when masking or the exit country changes, so tabs re-install their script. */
    var onMaskChanged: (() -> Unit)? = null

    val supported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    val on: Boolean get() = status == Status.ON || status == Status.CONNECTING || status == Status.FAILED
    fun current(): Location? = if (selected == FREE_ID) free else locations.firstOrNull { it.id == selected }

    const val FREE_ID = WarpFree.ID
    /** The built-in free location. Never saved in the locations list, so older versions never see it. */
    val free = Location(FREE_ID, WarpFree.NAME, null, "")
    val isFree: Boolean get() = selected == FREE_ID

    /** What one tap on the switch connects to: the last pick, else the first imported config, else Blaze Free. */
    fun defaultId(): String = current()?.id ?: locations.firstOrNull()?.id ?: FREE_ID

    /** The country pages should see: the tunnel's real exit, else the location's country. Null with the VPN off. */
    fun maskCountry(): Countries.Country? = if (status != Status.ON) null else Countries.byCode(exitCountry ?: current()?.country)

    private fun file() = File(app.filesDir, "browser_vpn.json")

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        try {
            val o = JSONObject(file().readText())
            maskLocation = o.optBoolean("mask", true)
            val a = o.optJSONArray("locations") ?: JSONArray()
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                locations += Location(it.getString("id"), it.optString("name"), it.optString("country").ifBlank { null }, it.getString("conf"))
            }
            // The VPN comes back on after a restart if it was on, so the browser never silently goes direct.
            val sel = o.optString("selected").ifBlank { null }
            if (o.optBoolean("enabled") && sel != null && (sel == FREE_ID || locations.any { it.id == sel })) {
                selected = sel; status = Status.CONNECTING
                if (supported) ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder().addProxyRule("127.0.0.1:1").build(), mainExec) { }
                connect(sel)
            }
        } catch (_: Exception) { }
    }

    private fun save(enabled: Boolean = on) {
        try {
            val a = JSONArray()
            locations.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("country", it.country.orEmpty()).put("conf", it.conf)) }
            file().writeText(JSONObject().put("enabled", enabled).put("selected", selected.orEmpty()).put("mask", maskLocation).put("locations", a).toString())
        } catch (_: Exception) { }
    }

    fun setMask(v: Boolean) { maskLocation = v; save(); onMaskChanged?.invoke() }

    /** Adds a config from text. Returns the new location, or throws with a plain-words reason. */
    fun add(name: String, text: String): Location {
        val cfg = WireGuardConfig.parse(text)
        val country = WireGuardConfig.guessCountry(name, cfg.peers.first().endpointHost)
        val clean = name.substringAfterLast('/').removeSuffix(".conf").ifBlank { cfg.peers.first().endpointHost }
        val loc = Location("wg" + System.currentTimeMillis().toString(36) + locations.size, clean, country, text)
        locations += loc; save()
        return loc
    }

    /** Imports .conf files, or a .zip of them (what Proton and Mullvad download). Returns (added, problems). */
    fun import(name: String, bytes: ByteArray): Pair<List<Location>, List<String>> {
        val added = mutableListOf<Location>(); val bad = mutableListOf<String>()
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.endsWith(".conf", true)) {
                        val t = z.readBytes().toString(Charsets.UTF_8)
                        try { added += add(e.name, t) } catch (x: Exception) { bad += "${e.name.substringAfterLast('/')}: ${x.message}" }
                    }
                    e = z.nextEntry
                }
            }
            if (added.isEmpty() && bad.isEmpty()) bad += "$name has no .conf files in it."
        } else try { added += add(name, bytes.toString(Charsets.UTF_8)) } catch (x: Exception) { bad += "$name: ${x.message}" }
        return added to bad
    }

    fun rename(id: String, country: String?) {
        val i = locations.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        locations[i] = locations[i].copy(country = country); save()
    }

    fun remove(id: String) {
        if (selected == id) disconnect()
        locations.removeAll { it.id == id }; save()
    }

    /** Find a location by country name/code or location name; "free"/"warp"/blank mean Blaze Free when nothing else fits. */
    fun find(q: String): Location? {
        val t = q.trim()
        if (t.isEmpty() || Regex("(?i)^(blaze ?free|free|warp|cloudflare|any|auto|default|on)$").matches(t) || t.contains("warp", true))
            return if (t.isEmpty() && locations.isNotEmpty()) (current() ?: locations.first()) else free
        val c = Countries.find(q)
        return locations.firstOrNull { c != null && it.country == c.code }
            ?: locations.firstOrNull { it.name.equals(q, true) } ?: locations.firstOrNull { it.name.contains(q, true) }
    }

    fun connect(id: String) { scope.launch { connectNow(id) } }

    /** Starts (or switches) the tunnel and waits until it's up and checked. Returns a plain-words result. */
    suspend fun connectNow(id: String): String {
        cancelJobs()
        return lock.withLock { connectLocked(id, 0) }
    }

    /** [attempt] 0 is a tap or a tool call; 1+ are automatic reconnects. Call with [lock] held. */
    private suspend fun connectLocked(id: String, attempt: Int): String {
        val loc = (if (id == FREE_ID) free else locations.firstOrNull { it.id == id }) ?: return "No such VPN location."
        if (!supported) { error = "This phone's Android System WebView is too old for the browser VPN. Update it in the Play Store."; return error }
        val g = ++gen
        stopProc()
        withContext(Dispatchers.Main) {
            selected = id; status = Status.CONNECTING; error = ""; exitIp = ""; exitCountry = null
            detail = if (attempt > 0) "Reconnecting (try $attempt of ${WarpFree.MAX_RETRIES})…" else if (id == FREE_ID) "Connecting to Blaze Free…" else ""
        }
        setProxy(1)   // kill switch while connecting: nothing goes direct
        save(true)
        try {
            val t = if (id == FREE_ID) connectFree(g) else {
                startTunnel(WireGuardConfig.parse(loc.conf), id, g)
                trace(port, 12_000)
            }
            setProxy(port)
            withContext(Dispatchers.Main) { exitIp = t.ip; exitCountry = t.country; status = Status.ON; detail = "" }
            // The exit check is the truth about the country; fix the label if the guess was wrong or missing.
            if (id != FREE_ID && t.country != null && t.country != loc.country) rename(loc.id, t.country)
            withContext(Dispatchers.Main) { onMaskChanged?.invoke() }
            startHealth(id, g)
            val c = Countries.byCode(t.country)
            return if (id == FREE_ID) "VPN on (Blaze Free, Cloudflare WARP): browsing from ${c?.name ?: t.country ?: "a nearby country"} (${t.ip.ifBlank { "IP hidden" }})."
                else "VPN on: browsing from ${c?.name ?: t.country ?: loc.label} (${t.ip.ifBlank { "IP hidden" }})."
        } catch (e: CancellationException) {
            if (gen != g) return "The VPN was switched to another location or turned off."
            throw e
        } catch (e: Exception) {
            if (gen == g) {
                stopProc()
                val why = when {
                    e is FreeFailed -> e.message.orEmpty()
                    e is java.net.SocketTimeoutException || e.message?.contains("timed out", true) == true || e.message?.contains("tunnel", true) == true ->
                        "The VPN server didn't answer. The config may be expired or revoked, or this network blocks WireGuard."
                    else -> e.message ?: "The VPN couldn't connect."
                }
                val next = attempt + 1
                val more = next <= WarpFree.MAX_RETRIES
                withContext(Dispatchers.Main) {
                    status = Status.FAILED; error = why
                    detail = if (more) "Trying again in ${WarpFree.backoffMs(next) / 1000} s." else "Tap the switch or a location to try again."
                    onMaskChanged?.invoke()
                }
                setProxy(1)
                if (more) scheduleReconnect(id, next, g)
            }
            return "VPN failed: $error The browser stays blocked (kill switch) until you pick another location or turn the VPN off."
        }
    }

    private class FreeFailed(msg: String) : Exception(msg)

    /** Blaze Free: register once (or reuse), then try each WARP endpoint/port until the trace says warp=on. */
    private suspend fun connectFree(g: Int): WarpFree.Trace {
        var fresh = false
        var acct: WarpFree.Account = loadFree() ?: run {
            withContext(Dispatchers.Main) { detail = "Setting up Blaze Free (first time only)…" }
            fresh = true
            registerFree()
        }
        var last: Exception? = null
        for (round in 0..1) {
            val cands = WarpFree.candidates(acct, freePeerIp).let { if (round == 0) it else it.take(4) }
            for ((i, ep) in cands.withIndex()) {
                if (gen != g) throw CancellationException("superseded")
                withContext(Dispatchers.Main) { detail = WarpFree.tryingLine(i, ep) }
                try {
                    startTunnel(acct.config(ep), FREE_ID, g)
                    val t = trace(port, 8_000)
                    if (!t.warpOn) throw IllegalStateException("Cloudflare says this connection isn't going through WARP.")
                    if (acct.lastGood != ep) { acct = acct.copy(lastGood = ep); saveFree(acct) }
                    return t
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    last = e
                    if (gen != g) throw CancellationException("superseded")
                    stopProc()
                }
            }
            // Every route failed. The device may have been dropped by Cloudflare: sign up again once and retry.
            if (round == 0 && !fresh) {
                withContext(Dispatchers.Main) { detail = "Refreshing Blaze Free…" }
                acct = try { registerFree() } catch (e: Exception) { last = e; break }
                fresh = true
            } else break
        }
        throw FreeFailed(if (last is FreeFailed) last.message.orEmpty() else
            "Blaze Free couldn't get through on this network (tried ports ${WarpFree.PORTS.joinToString()}). It may block VPNs; try Wi-Fi or mobile data.")
    }

    private fun freeFile() = File(app.filesDir, "blaze_free.json")
    @Volatile private var freePeerIp: String? = null

    private fun loadFree(): WarpFree.Account? = try {
        val f = freeFile(); if (!f.exists()) null else WarpFree.Account.fromJson(f.readText())
    } catch (_: Exception) { null }

    private fun saveFree(a: WarpFree.Account) {
        try { freeFile().writeText(a.toJson()) } catch (_: Exception) { }
    }

    /** Registers a new free WARP device with Cloudflare's public client API, keys made on the phone. */
    private fun registerFree(): WarpFree.Account {
        val keys = WarpFree.newKeyPair()
        val tos = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        val body = WarpFree.registrationBody(keys.publicKey, tos).toByteArray()
        var why = "Couldn't reach Cloudflare to set up Blaze Free. Check the internet connection."
        for (v in WarpFree.API_VARIANTS) {
            try {
                val c = URL(v.regUrl).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 15_000
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("User-Agent", v.userAgent)
                c.setRequestProperty("CF-Client-Version", v.clientVersion)
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                c.setRequestProperty("Connection", "Keep-Alive")
                try {
                    c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) { why = WarpFree.httpError(code, text); continue }
                    val a = WarpFree.parseRegistration(text, keys, System.currentTimeMillis())
                    freePeerIp = WarpFree.peerIp(text)
                    saveFree(a)
                    return a
                } finally { c.disconnect() }
            } catch (e: IllegalArgumentException) { why = e.message ?: why } catch (_: Exception) { }
        }
        throw FreeFailed(why)
    }

    /** Starts wgproxy with [cfg]; sets [port]. Watches the process so a dropped tunnel reconnects on its own. */
    private fun startTunnel(cfg: WireGuardConfig, id: String, g: Int) {
        val json = cfg.toProxyJson { host -> resolve(host) }
        val bin = File(app.applicationInfo.nativeLibraryDir, "libwgproxy.so")
        if (!bin.exists()) throw IllegalStateException("The VPN engine is missing from this build.")
        val p = ProcessBuilder(bin.absolutePath).redirectErrorStream(true).start()
        proc = p; stdin = p.outputStream
        p.outputStream.write((json + "\n").toByteArray()); p.outputStream.flush()
        val out = BufferedReader(InputStreamReader(p.inputStream))
        val first = withTimeoutLine(out, 10_000) ?: throw IllegalStateException("The VPN engine didn't start.")
        if (first.startsWith("ERROR")) throw IllegalArgumentException(first.removePrefix("ERROR").trim())
        port = first.removePrefix("READY").trim().toIntOrNull() ?: throw IllegalStateException("The VPN engine said: $first")
        Thread {
            try { while (out.readLine() != null) { } } catch (_: Exception) { }
            // Only a tunnel that was up counts as a drop; failures while connecting are handled by the connect loop.
            if (gen == g && proc === p && status == Status.ON) dropped(id, g, "The VPN connection dropped.")
        }.apply { isDaemon = true }.start()
    }

    private var retryJob: Job? = null
    private var healthJob: Job? = null

    private fun cancelJobs() { retryJob?.cancel(); retryJob = null; healthJob?.cancel(); healthJob = null }

    /** The tunnel was up and broke: block pages (kill switch) and reconnect with backoff. */
    private fun dropped(id: String, g: Int, why: String) {
        scope.launch {
            lock.withLock {
                if (gen != g || status == Status.OFF) return@withLock
                stopProc()
                setProxy(1)
                withContext(Dispatchers.Main) {
                    status = Status.FAILED; error = why
                    detail = "Reconnecting in ${WarpFree.backoffMs(1) / 1000} s. Pages are paused so nothing leaks."
                    onMaskChanged?.invoke()
                }
                scheduleReconnect(id, 1, g)
            }
        }
    }

    private fun scheduleReconnect(id: String, attempt: Int, g: Int) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(WarpFree.backoffMs(attempt))
            lock.withLock { if (gen == g && status != Status.OFF && selected == id) connectLocked(id, attempt) }
        }
    }

    /** Health check: every 45 s ask Cloudflare's trace through the tunnel; two misses in a row count as a drop. */
    private fun startHealth(id: String, g: Int) {
        healthJob?.cancel()
        healthJob = scope.launch {
            var misses = 0
            while (gen == g && status == Status.ON) {
                delay(45_000)
                if (gen != g || status != Status.ON) break
                val ok = try { val t = trace(port, 10_000); id != FREE_ID || t.warpOn } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                misses = if (ok) 0 else misses + 1
                if (misses >= 2) { dropped(id, g, "The VPN stopped answering."); break }
            }
        }
    }

    /** Turns the VPN off: the browser goes direct again. Safe to call from any thread. */
    fun disconnect() {
        gen++
        cancelJobs()
        main.post {
            status = Status.OFF; exitIp = ""; exitCountry = null; error = ""; detail = ""
            if (supported) ProxyController.getInstance().clearProxyOverride(mainExec) { }
            onMaskChanged?.invoke()
        }
        scope.launch { lock.withLock { stopProc() }; save(false) }
    }

    private fun stopProc() {
        try { stdin?.close() } catch (_: Exception) { }
        proc?.let { p -> try { if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroy() } catch (_: Exception) { p.destroy() } }
        proc = null; stdin = null; port = 0
    }

    /** Port 1 is the kill switch: nothing listens there, so pages fail instead of going direct. */
    private suspend fun setProxy(port: Int) = withContext(Dispatchers.Main) {
        if (!supported) return@withContext
        val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
        ProxyController.getInstance().setProxyOverride(cfg, mainExec) { }
    }

    private fun resolve(host: String): String {
        if (Regex("^[0-9.]+$").matches(host) || host.contains(':')) return host
        val all = InetAddress.getAllByName(host)
        return (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress ?: host
    }

    private fun withTimeoutLine(r: BufferedReader, ms: Long): String? {
        var line: String? = null
        val t = Thread { line = try { r.readLine() } catch (_: Exception) { null } }
        t.start(); t.join(ms)
        return line
    }

    /** Asks Cloudflare, through the tunnel, which IP and country the browser now appears from (and whether it's WARP). */
    private fun trace(port: Int, timeoutMs: Int): WarpFree.Trace {
        val c = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as java.net.HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        try {
            return WarpFree.parseTrace(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    fun statusText(): String = when (status) {
        Status.OFF -> "VPN off. Locations: ${WarpFree.NAME} (free, one tap, exits near the user)" +
            (if (locations.isEmpty()) "; no imported countries (the user can import WireGuard configs in the browser: ⋯ › VPN & privacy)." else ", " + locations.joinToString { it.label } + ".")
        Status.CONNECTING -> "VPN connecting to ${current()?.label}… ${detail}".trim()
        Status.ON -> "VPN on${if (isFree) " (Blaze Free)" else ""}: ${Countries.byCode(exitCountry)?.name ?: current()?.label} · $exitIp. Location masking ${if (maskLocation) "on" else "off"}."
        Status.FAILED -> "VPN not connected ($error) ${detail} Browser blocked by the kill switch.".replace("  ", " ")
    }

    /** The plain-words status line the VPN sheet shows. */
    fun statusLine(): String {
        val country = Countries.byCode(exitCountry ?: current()?.country)
        return when (status) {
            Status.OFF -> if (locations.isEmpty()) "Off. Turn on for Blaze Free: one tap, no setup." else "Off. Sites see your real IP."
            Status.CONNECTING -> detail.ifBlank { "Connecting to ${current()?.label ?: "the VPN"}…" }
            Status.ON -> ("${country?.flag ?: ""} ${country?.name ?: current()?.label} · $exitIp" + if (isFree) " · Blaze Free" else "").trim()
            Status.FAILED -> "Not connected: $error ${detail.ifBlank { "Pages stay blocked so nothing leaks." }}"
        }
    }
}
