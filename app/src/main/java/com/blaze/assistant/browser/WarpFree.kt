package com.blaze.assistant.browser

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * v0.25.3 "Blaze Free": a free Cloudflare WARP device registered from the phone (the way wgcf does it),
 * turned into an ordinary WireGuard config for wgproxy. Everything here is pure Kotlin (no Android, no
 * network) so it's unit-tested on the JVM: key generation, the registration request, response parsing,
 * the config, the endpoint fallback order, the reconnect backoff and the Cloudflare trace.
 */
object WarpFree {
    const val ID = "blaze-free"
    const val NAME = "Blaze Free (Cloudflare WARP)"
    const val API = "https://api.cloudflareclient.com"

    /** Cloudflare's own WARP peer key, used if a registration answer leaves it out. */
    const val DEFAULT_PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    const val DEFAULT_HOST = "engage.cloudflareclient.com"
    const val DEFAULT_IP = "162.159.192.1"
    /** WARP answers WireGuard on all of these; some networks (e.g. in Zambia) block UDP 2408. */
    val PORTS = listOf(2408, 500, 1701, 4500)
    const val MTU = 1280
    const val KEEPALIVE = 25
    val DNS = listOf("1.1.1.1", "1.0.0.1")

    /** Client API versions to register with, tried in order, each with the headers of the app build it belongs to. */
    data class ApiVariant(val version: String, val userAgent: String, val clientVersion: String) {
        val regUrl: String get() = "$API/$version/reg"
    }
    val API_VARIANTS = listOf(
        ApiVariant("v0a2158", "okhttp/3.12.1", "a-6.10-2158"),
        ApiVariant("v0a5641", "1.1.1.1/6.38.9-5641 (Android 16.0.0)", "a-6.38.9-5641"),
    )

    data class KeyPair(val privateKey: String, val publicKey: String)
    data class Endpoint(val host: String, val port: Int) {
        override fun toString() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
    }

    /** A registered WARP device: everything needed to rebuild its WireGuard config, saved in filesDir. */
    data class Account(
        val privateKey: String,
        val publicKey: String,
        val deviceId: String,
        val token: String,
        val peerPublicKey: String,
        val endpoint: Endpoint,
        val v4: String,
        val v6: String,
        val clientId: String = "",
        val created: Long = 0,
        val lastGood: Endpoint? = null,
    ) {
        fun toJson(): String = JSONObject()
            .put("private_key", privateKey).put("public_key", publicKey).put("id", deviceId).put("token", token)
            .put("peer_public_key", peerPublicKey).put("endpoint", endpoint.toString())
            .put("v4", v4).put("v6", v6).put("client_id", clientId).put("created", created)
            .put("last_good", lastGood?.toString().orEmpty())
            .put("conf", toConf())   // the plain wg-quick file too, for anyone looking
            .toString()

        /** The wg-quick text, for the default endpoint (or [ep]). */
        fun toConf(ep: Endpoint = endpoint): String = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = " + addresses().joinToString(", "))
            appendLine("DNS = " + DNS.joinToString(", "))
            appendLine("MTU = $MTU")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $peerPublicKey")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = $ep")
            appendLine("PersistentKeepalive = $KEEPALIVE")
        }

        fun addresses(): List<String> = listOfNotNull(v4.takeIf { it.isNotBlank() }?.let { withPrefix(it, 32) },
            v6.takeIf { it.isNotBlank() }?.let { withPrefix(it, 128) })

        /** The config wgproxy runs, pointed at one endpoint. */
        fun config(ep: Endpoint = endpoint): WireGuardConfig = WireGuardConfig(
            privateKey, addresses(), DNS, MTU,
            listOf(WireGuardConfig.Peer(peerPublicKey, "", ep.host, ep.port, listOf("0.0.0.0/0", "::/0"), KEEPALIVE)),
        )

        companion object {
            fun fromJson(s: String): Account {
                val o = JSONObject(s)
                fun ep(v: String): Endpoint? = v.takeIf { it.isNotBlank() }?.let { runCatching { WireGuardConfig.splitEndpoint(it) }.getOrNull() }
                    ?.let { Endpoint(it.first, it.second) }
                val a = Account(
                    o.getString("private_key"), o.optString("public_key"), o.optString("id"), o.optString("token"),
                    o.optString("peer_public_key").ifBlank { DEFAULT_PEER_KEY },
                    ep(o.optString("endpoint")) ?: Endpoint(DEFAULT_HOST, PORTS[0]),
                    o.optString("v4"), o.optString("v6"), o.optString("client_id"), o.optLong("created"), ep(o.optString("last_good")),
                )
                require(isKey(a.privateKey)) { "The saved Blaze Free key is damaged." }
                require(a.addresses().isNotEmpty()) { "The saved Blaze Free config has no address." }
                return a
            }
        }
    }

    // ---- keys ----

    fun isKey(s: String) = Regex("^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw480]=$").matches(s)

    /** A fresh WireGuard key pair, made on the phone; the private key never leaves it. */
    fun newKeyPair(rnd: SecureRandom = SecureRandom()): KeyPair {
        val k = ByteArray(32).also { rnd.nextBytes(it) }
        val priv = X25519.clamp(k)
        return KeyPair(b64(priv), b64(X25519.publicKey(priv)))
    }

    fun publicKeyOf(privateKey: String): String = b64(X25519.publicKey(Base64.getDecoder().decode(privateKey)))

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    // ---- registration ----

    /** The JSON body of POST /reg. [tos] is an ISO-8601 time, e.g. 2026-10-14T09:49:00.000Z. */
    fun registrationBody(publicKey: String, tos: String): String = JSONObject()
        .put("key", publicKey)
        .put("install_id", "").put("fcm_token", "")
        .put("tos", tos)
        .put("type", "Android").put("model", "PC").put("locale", "en_US")
        .put("warp_enabled", true)
        .put("serial_number", "").put("os_version", "16.0.0")
        .put("key_type", "curve25519").put("tunnel_type", "wireguard")
        .toString()

    /** Turns Cloudflare's answer into an [Account]. Throws IllegalArgumentException in plain words. */
    fun parseRegistration(text: String, keys: KeyPair, now: Long = 0): Account {
        val root = try { JSONObject(text) } catch (_: Exception) { throw IllegalArgumentException("Cloudflare sent an answer Blaze couldn't read.") }
        errorIn(root)?.let { throw IllegalArgumentException(it) }
        val r = root.optJSONObject("result") ?: root
        val cfg = r.optJSONObject("config") ?: throw IllegalArgumentException("Cloudflare didn't send a VPN config.")
        val peer = cfg.optJSONArray("peers")?.optJSONObject(0)
        val peerKey = peer?.optString("public_key").orEmpty().ifBlank { DEFAULT_PEER_KEY }
        require(isKey(peerKey)) { "Cloudflare sent a VPN key Blaze couldn't use." }
        val epObj = peer?.optJSONObject("endpoint")
        val endpoint = epObj?.let { endpointOf(it) } ?: Endpoint(DEFAULT_HOST, PORTS[0])
        val addrs = cfg.optJSONObject("interface")?.optJSONObject("addresses")
        val v4 = addrs?.optString("v4").orEmpty().substringBefore('/')
        val v6 = addrs?.optString("v6").orEmpty().substringBefore('/')
        require(v4.isNotBlank() || v6.isNotBlank()) { "Cloudflare didn't give this phone a VPN address." }
        return Account(keys.privateKey, keys.publicKey, r.optString("id"), r.optString("token"), peerKey, endpoint, v4, v6,
            cfg.optString("client_id"), now)
    }

    /** The peer's IPv4 address from the answer (the "v4" field without its :0 port), if any. */
    fun peerIp(text: String): String? = runCatching {
        val r = JSONObject(text).let { it.optJSONObject("result") ?: it }
        r.getJSONObject("config").getJSONArray("peers").getJSONObject(0).getJSONObject("endpoint").optString("v4")
            .substringBeforeLast(':').takeIf { Regex("^[0-9.]+$").matches(it) }
    }.getOrNull()

    private fun endpointOf(o: JSONObject): Endpoint? {
        val ports = o.optJSONArray("ports")
        val port0 = if (ports != null && ports.length() > 0) ports.optInt(0, PORTS[0]) else PORTS[0]
        val host = o.optString("host")
        if (host.isNotBlank()) return runCatching { WireGuardConfig.splitEndpoint(host).let { Endpoint(it.first, it.second) } }
            .getOrElse { Endpoint(host.substringBefore(':'), port0) }
        val v4 = o.optString("v4").substringBeforeLast(':')
        return if (v4.isNotBlank()) Endpoint(v4, port0) else null
    }

    /** Cloudflare's error answer ({"success":false,"errors":[{"message":…}]}) in plain words, or null. */
    fun errorIn(o: JSONObject): String? {
        val errs: JSONArray? = o.optJSONArray("errors")
        if (o.optBoolean("success", true) && (errs == null || errs.length() == 0)) return null
        val msg = errs?.optJSONObject(0)?.optString("message").orEmpty()
        return "Cloudflare refused the free sign-up" + if (msg.isNotBlank()) ": $msg." else "."
    }

    /** A plain-words line for a failed HTTP answer from the client API. */
    fun httpError(code: Int, body: String): String {
        val fromBody = runCatching { errorIn(JSONObject(body)) }.getOrNull()
        return when {
            fromBody != null -> fromBody
            code == 429 -> "Cloudflare is limiting sign-ups from this network right now."
            code in 500..599 -> "Cloudflare's free service is having trouble ($code)."
            else -> "Cloudflare refused the free sign-up ($code)."
        }
    }

    // ---- connecting ----

    /**
     * Endpoints to try, in order, without repeats: the last one that worked, the one Cloudflare named
     * (engage.cloudflareclient.com:2408), then the peer IP on each of [PORTS], then Cloudflare's well-known IP on each.
     */
    fun candidates(a: Account, peerIp: String? = null): List<Endpoint> {
        val out = LinkedHashSet<Endpoint>()
        a.lastGood?.let { out += it }
        out += a.endpoint
        val ips = listOfNotNull(peerIp?.takeIf { it.isNotBlank() }, DEFAULT_IP).distinct()
        for (ip in ips) for (p in PORTS) out += Endpoint(ip, p)
        return out.toList()
    }

    /** Wait before reconnect try n (1-based): 2, 4, 8, 16, 32, then 60 s. */
    fun backoffMs(attempt: Int): Long {
        val n = attempt.coerceAtLeast(1)
        return if (n >= 6) 60_000L else 2_000L shl (n - 1)
    }

    /** Auto-reconnect gives up (and asks for a tap) after this many tries in a row. */
    const val MAX_RETRIES = 8

    /** What https://www.cloudflare.com/cdn-cgi/trace says about the connection. */
    data class Trace(val ip: String, val country: String?, val warp: String) {
        val warpOn: Boolean get() = warp == "on" || warp == "plus"
    }

    fun parseTrace(text: String): Trace {
        val m = text.lines().filter { it.contains('=') }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        return Trace(m["ip"].orEmpty(), m["loc"]?.takeIf { it.length == 2 && it != "XX" && it != "T1" }, m["warp"].orEmpty())
    }

    /** Plain-words line while trying [ep], the [index]th candidate. */
    fun tryingLine(index: Int, ep: Endpoint): String =
        if (index == 0) "Connecting to Blaze Free…" else "That route is blocked here. Trying port ${ep.port}…"

    private fun withPrefix(ip: String, bits: Int) = if (ip.contains('/')) ip else "$ip/$bits"
}

/** Curve25519 Diffie-Hellman (RFC 7748), just enough to make a WireGuard public key. Not constant-time; it runs once per sign-up. */
object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    fun clamp(k: ByteArray): ByteArray {
        require(k.size == 32) { "A key is 32 bytes." }
        val c = k.copyOf()
        c[0] = (c[0].toInt() and 248).toByte()
        c[31] = ((c[31].toInt() and 127) or 64).toByte()
        return c
    }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE)

    fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(u.size == 32) { "A point is 32 bytes." }
        val k = decode(clamp(scalar))
        val uu = u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = decode(uu).mod(P)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO; var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) { val a = x2; x2 = x3; x3 = a; val b = z2; z2 = z3; z3 = b }
            swap = kt
            val a = x2.add(z2).mod(P); val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P); val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P); val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P); val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) { x2 = x3; z2 = z3 }
        return encode(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }

    private fun decode(b: ByteArray) = BigInteger(1, b.reversedArray())

    private fun encode(x: BigInteger): ByteArray {
        val be = x.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }
}
