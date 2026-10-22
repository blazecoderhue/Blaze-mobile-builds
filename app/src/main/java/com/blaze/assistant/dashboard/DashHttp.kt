package com.blaze.assistant.dashboard

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * v0.45.0: the dashboard's HTTP parsing as pure code (unit-tested in DashHttpTest).
 *
 *  - The request line and headers are read as bytes with hard caps (8 KB per line, 64 headers).
 *  - The token is checked from the query or the x-blaze-token header BEFORE any body is read, so a device on the
 *    Wi-Fi without the link can't make the phone allocate anything for a body.
 *  - The body is capped ([maxBody]) and read as BYTES (Content-Length counts bytes), then decoded as UTF-8, so a
 *    message with accents or emoji no longer waits for characters that never arrive.
 *  - Tokens are compared in constant time.
 */
object DashHttp {
    const val MAX_LINE = 8 * 1024
    const val MAX_HEADERS = 64
    /** Normal API bodies (chat messages, clipboard text). */
    const val MAX_BODY = 1 shl 20
    /** /api/upload carries a base64 file of up to Bridge.MAX_UPLOAD (12 MB) -> about 16 MB of text. */
    const val MAX_UPLOAD_BODY = 17 * (1 shl 20)

    class BadRequest(val code: Int, msg: String) : Exception(msg)

    data class Head(val method: String, val target: String, val headers: Map<String, String>) {
        val path: String get() = target.substringBefore('?')
        val query: Map<String, String> get() = parseQuery(target.substringAfter('?', ""))
        /** The token the caller sent: the ?t= query (the link) or the x-blaze-token header (the page's fetches). */
        val token: String? get() = headers["x-blaze-token"]?.takeIf { it.isNotBlank() } ?: query["t"]
        val contentLength: Long get() = headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
    }

    /** One header line as ISO-8859-1 (HTTP header bytes), without CR/LF; null at end of stream. */
    fun readLine(inp: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) break
            if (b.size() >= MAX_LINE) throw BadRequest(431, "header line too long")
            b.write(c)
        }
        return b.toString("ISO-8859-1").removeSuffix("\r")
    }

    fun readHead(inp: InputStream): Head? {
        val first = readLine(inp) ?: return null
        val parts = first.split(' ')
        val headers = HashMap<String, String>()
        var n = 0
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
            if (++n > MAX_HEADERS) throw BadRequest(431, "too many headers")
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        return Head(parts.getOrNull(0)?.ifBlank { null } ?: "GET", parts.getOrNull(1)?.ifBlank { null } ?: "/", headers)
    }

    /** The body cap for [path]. */
    fun maxBody(path: String): Int = if (path == "/api/upload") MAX_UPLOAD_BODY else MAX_BODY

    /** Reads exactly Content-Length bytes (refusing more than [max]) and decodes them as UTF-8. */
    fun readBody(inp: InputStream, head: Head, max: Int = maxBody(head.path)): String {
        val len = head.contentLength
        if (len < 0) throw BadRequest(400, "bad content-length")
        if (len > max) throw BadRequest(413, "body too large")
        if (len == 0L) return ""
        val buf = ByteArray(len.toInt())
        try { DataInputStream(inp).readFully(buf) } catch (e: java.io.EOFException) { throw BadRequest(400, "body shorter than content-length") }
        return String(buf, Charsets.UTF_8)
    }

    fun tokenOk(given: String?, expected: String): Boolean {
        if (given == null || expected.isEmpty()) return false
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    fun parseQuery(q: String): Map<String, String> = q.split('&').mapNotNull { kv ->
        val p = kv.split('=', limit = 2)
        if (p.size != 2) null else (dec(p[0]) to dec(p[1]))
    }.toMap()

    /** Like Uri.decode: %XX only, a literal "+" stays "+". */
    private fun dec(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { s }

    /** 128-bit random token as hex. */
    fun newToken(rnd: java.security.SecureRandom = java.security.SecureRandom()): String =
        ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun status(code: Int): String = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"; else -> "Error"
    }
}
