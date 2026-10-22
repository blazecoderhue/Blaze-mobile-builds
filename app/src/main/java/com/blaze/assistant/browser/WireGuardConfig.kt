package com.blaze.assistant.browser

import org.json.JSONArray
import org.json.JSONObject

/** A wg-quick config file (the .conf any WireGuard provider hands out), parsed. Pure Kotlin so it's unit-tested. */
data class WireGuardConfig(
    val privateKey: String,
    val addresses: List<String>,
    val dns: List<String>,
    val mtu: Int,
    val peers: List<Peer>,
) {
    data class Peer(val publicKey: String, val presharedKey: String, val endpointHost: String, val endpointPort: Int,
                    val allowedIps: List<String>, val keepalive: Int)

    /** The JSON line wgproxy reads, with each peer's endpoint host swapped for the IP it resolved to. */
    fun toProxyJson(resolve: (String) -> String): String {
        val ps = JSONArray()
        peers.forEach { p ->
            val ip = resolve(p.endpointHost)
            val ep = if (ip.contains(':')) "[$ip]:${p.endpointPort}" else "$ip:${p.endpointPort}"
            ps.put(JSONObject().put("public_key", p.publicKey).put("preshared_key", p.presharedKey).put("endpoint", ep)
                .put("allowed_ips", JSONArray(p.allowedIps.ifEmpty { listOf("0.0.0.0/0", "::/0") })).put("keepalive", p.keepalive))
        }
        return JSONObject().put("private_key", privateKey).put("addresses", JSONArray(addresses)).put("dns", JSONArray(dns))
            .put("mtu", mtu).put("peers", ps).toString()
    }

    companion object {
        private val KEY = Regex("^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw480]=$")

        /** Parses a wg-quick file. Throws IllegalArgumentException with a plain-words reason when it isn't usable. */
        fun parse(text: String): WireGuardConfig {
            var section = ""
            var priv = ""; val addrs = mutableListOf<String>(); val dns = mutableListOf<String>(); var mtu = 0
            val peers = mutableListOf<Peer>()
            var cur: MutableMap<String, String>? = null
            fun endPeer() {
                val m = cur ?: return; cur = null
                val pub = m["publickey"].orEmpty()
                require(KEY.matches(pub)) { "A [Peer] has no valid PublicKey." }
                val ep = m["endpoint"].orEmpty()
                require(ep.isNotBlank()) { "A [Peer] has no Endpoint." }
                val (host, port) = splitEndpoint(ep)
                val psk = m["presharedkey"].orEmpty()
                require(psk.isEmpty() || KEY.matches(psk)) { "The PresharedKey isn't a valid WireGuard key." }
                peers += Peer(pub, psk, host, port, list(m["allowedips"]), m["persistentkeepalive"]?.toIntOrNull() ?: 0)
            }
            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    endPeer()
                    section = line.drop(1).dropLast(1).trim().lowercase()
                    if (section == "peer") cur = mutableMapOf()
                    continue
                }
                val k = line.substringBefore('=').trim().lowercase()
                val v = line.substringAfter('=', "").trim()
                when (section) {
                    "interface" -> when (k) {
                        "privatekey" -> priv = v
                        "address" -> addrs += list(v)
                        "dns" -> dns += list(v).filter { isIp(it) }   // search domains aren't used
                        "mtu" -> mtu = v.toIntOrNull() ?: 0
                    }
                    "peer" -> cur?.let { m -> m[k] = if (k == "allowedips" && m[k] != null) m[k] + "," + v else v }
                }
            }
            endPeer()
            require(KEY.matches(priv)) { "This doesn't look like a WireGuard config: no valid PrivateKey under [Interface]." }
            require(addrs.isNotEmpty()) { "The config has no Address under [Interface]." }
            require(peers.isNotEmpty()) { "The config has no [Peer]." }
            return WireGuardConfig(priv, addrs, dns, mtu, peers)
        }

        private fun list(s: String?) = s.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        private fun isIp(s: String) = Regex("^[0-9.]+$").matches(s) || (s.contains(':') && Regex("^[0-9a-fA-F:.]+$").matches(s))

        fun splitEndpoint(ep: String): Pair<String, Int> {
            val s = ep.trim()
            val (host, port) = if (s.startsWith("[")) s.substringAfter('[').substringBefore(']') to s.substringAfter("]:", "")
                else s.substringBeforeLast(':') to s.substringAfterLast(':', "")
            val p = port.toIntOrNull()
            require(host.isNotBlank() && p != null && p in 1..65535) { "The Endpoint \u201c$ep\u201d needs a host and port, like vpn.example.com:51820." }
            return host to p
        }

        /**
         * Best guess at a config's country from its file name or the Endpoint host, e.g. Proton's "NL-FREE#1",
         * Mullvad's "mullvad-se-sto-wg-001", "us-nyc.example.net". Null when there's no clear country code;
         * the real exit country is checked once connected anyway.
         */
        fun guessCountry(name: String, endpointHost: String = ""): String? {
            for (s in listOf(name, endpointHost)) {
                val t = s.lowercase().substringAfterLast('/').removeSuffix(".conf").removePrefix("mullvad-").removePrefix("wg-").removePrefix("proton-")
                val m = Regex("^([a-z]{2})(?:[-_#. ]|\\d|$)").find(t) ?: continue
                val code = m.groupValues[1].uppercase().let { if (it == "UK") "GB" else it }
                if (Countries.byCode(code) != null) return code
            }
            return null
        }
    }
}
