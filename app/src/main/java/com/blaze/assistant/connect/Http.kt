package com.blaze.assistant.connect

import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny HTTP helper for connectors: returns (status, body).
 * v0.28.2: [strict] (the default, for connector / model / gateway calls) refuses http:// to a public host;
 * with strict = false (reading a public page) plain http is allowed, but never with credentials.
 */
object Http {
    fun req(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap(),
             contentType: String = "application/json", timeoutMs: Int = 30_000, strict: Boolean = true): Pair<Int, String> {
        if (strict) UrlSafety.check(url) else UrlSafety.checkSecret(url, headers)
        val c = URL(url).openConnection() as HttpURLConnection
        if (UrlSafety.isPlainHttp(url) && strict) c.instanceFollowRedirects = false
        c.requestMethod = method
        c.connectTimeout = 15_000; c.readTimeout = timeoutMs
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }

    fun form(vararg kv: Pair<String, String>) = kv.joinToString("&") { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
    fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
