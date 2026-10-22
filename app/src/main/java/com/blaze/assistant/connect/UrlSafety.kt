package com.blaze.assistant.connect

/**
 * Blaze allows plain HTTP so it can reach the on-device runner, OAuth loopback and gateways or MCP servers
 * on the user's own network (see res/xml/network_security_config.xml). Android's XML can't express "LAN
 * only", so the rule is enforced here, in code (v0.28.2): model / gateway / MCP / connector / account calls
 * refuse http:// to a public host ([check], [open]), and no request ever sends credentials (Authorization,
 * API keys, tokens) over http:// to a public host ([Http.req] with strict = false still refuses those).
 * The self-check also warns about saved http:// addresses.
 */
object UrlSafety {
    /** Thrown when a plain-http request to a public host is refused. The message says what to do. */
    class CleartextBlocked(val host: String, msg: String) : java.io.IOException(msg)

    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun host(url: String): String {
        val rest = url.trim().substringAfter("://", "")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        return if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']').lowercase()
        else authority.substringBefore(':').lowercase()
    }

    /**
     * Loopback (localhost, 127/8, ::1; the emulator's 10.0.2.2 is in 10/8), private LAN (10/8, 172.16/12,
     * 192.168/16), link-local, CGNAT / Tailscale (100.64/10), IPv6 ULA / link-local, *.local / *.lan /
     * *.home.arpa / *.ts.net, and single-label LAN names ("desktop", "my-pc": public DNS never answers those).
     */
    fun isLocalHost(h0: String): Boolean {
        val h = h0.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        if (!h.contains('.') && !h.contains(':') && Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$").matches(h) && !h.all { it.isDigit() }) return true
        if (h == "localhost" || h.endsWith(".localhost") || h == "::1") return true
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".ts.net")) return true
        if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return h.contains(':')
        val m = ipv4.matchEntire(h) ?: return false
        val (a, b) = m.destructured.toList().map { it.toInt() }.let { it[0] to it[1] }
        return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
            (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }

    /** True for an http:// URL whose host is on the public internet. */
    fun isRiskyCleartext(url: String): Boolean {
        val u = url.trim()
        if (!u.regionMatches(0, "http://", 0, 7, ignoreCase = true)) return false
        return !isLocalHost(host(u))
    }

    /** Blaze's browser: an http:// page shows "Not secure" in the address bar. */
    fun notSecure(pageUrl: String): Boolean = isPlainHttp(pageUrl)

    /** Blaze's browser: secure forms and saved logins only fill https pages (or http on this phone / the LAN). */
    fun autofillAllowed(pageUrl: String): Boolean {
        val u = pageUrl.trim()
        return u.regionMatches(0, "https://", 0, 8, ignoreCase = true) || (isPlainHttp(u) && isPrivateHost(host(u)))
    }

    const val AUTOFILL_REFUSED = "This page isn't secure (plain http://), so I won't fill in logins or secure details here: they'd travel unencrypted. " +
        "Open the https:// version of the page, or let the user type it themselves in the live browser (browser_show)."

    /** Same as [isLocalHost]: hosts that may be reached over plain http. */
    fun isPrivateHost(h: String): Boolean = isLocalHost(h)

    fun isPlainHttp(url: String): Boolean = url.trim().regionMatches(0, "http://", 0, 7, ignoreCase = true)

    /** The plain-words error for a refused http:// URL, or null when [url] is fine (https, or http to a private host). */
    fun problem(url: String): String? {
        if (!isRiskyCleartext(url)) return null
        val h = host(url)
        val https = "https://" + url.trim().substring(7)
        return "Blocked an unencrypted connection to $h: it's on the public internet and the address starts with http://, " +
            "so keys and chats would travel in the clear. Use $https instead (or a local, LAN or Tailscale address)."
    }

    /** Refuses http:// to a public host (model, gateway, MCP, connector and account calls). */
    fun check(url: String) { problem(url)?.let { throw CleartextBlocked(host(url), it) } }

    private val secretHeader = Regex("(?i)^(authorization|proxy-authorization|cookie|x-api-key|api-key|apikey|x-goog-api-key|x-auth-token|x-access-token)$|token|secret|api[-_]?key|password")
    private val secretQuery = Regex("(?i)[?&](key|api_key|apikey|access_token|token|auth|password|secret|client_secret)=")

    /** True when the request carries credentials: an auth / key / token header, or a key / token in the query. */
    fun carriesSecret(url: String, headers: Map<String, String>): Boolean =
        headers.any { (k, v) -> v.isNotBlank() && secretHeader.containsMatchIn(k.trim()) } || secretQuery.containsMatchIn(url)

    /**
     * Refuses credentials over http:// to a public host even where plain http is otherwise allowed
     * (reading a public http:// page is fine; sending a key to it is not).
     */
    fun checkSecret(url: String, headers: Map<String, String>) {
        if (carriesSecret(url, headers)) check(url)
    }

    /**
     * Opens a connection for a model / gateway / MCP / connector call: refuses http:// to a public host, and
     * for http:// (private hosts) doesn't follow redirects, so a LAN server can't bounce the request (and its
     * headers) to the internet in the clear.
     */
    fun open(url: String): java.net.HttpURLConnection {
        check(url)
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        if (isPlainHttp(url)) c.instanceFollowRedirects = false
        return c
    }
}
