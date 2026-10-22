package com.blaze.assistant.terminal

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * v0.25.5: a download that survives a dropped connection (FetchTest, against a local server that cuts off mid-file).
 * It writes to `<dest>.part`, resumes with an HTTP Range request from where it stopped (and starts over cleanly when a
 * server ignores Range), retries with backoff, moves to the next mirror after two failures on one, and checks the
 * SHA-256 before handing over the file. Plain JVM (java.net), no Android.
 */
object Fetch {
    /** v0.33.3: many more tries (a flaky mobile connection drops a dozen times in 260 MB); each resumes, so they are cheap. */
    const val TRIES = 14
    const val CONNECT_TIMEOUT_MS = 25_000
    const val READ_TIMEOUT_MS = 90_000

    /** Seconds to wait before try [attempt] (1-based; the first try doesn't wait): 2, 4, 8, 16, 32, then 60. */
    fun backoffSec(attempt: Int): Int = if (attempt <= 1) 0 else minOf(60, 1 shl minOf(attempt - 1, 10))

    /** v0.33.3: the plain progress line in MB ("Resuming… 12.3 of 48.0 MB", "Downloading… 3.1 MB"). */
    fun mbLine(what: String, got: Long, total: Long, resuming: Boolean): String {
        fun mb(b: Long) = "%.1f".format(java.util.Locale.US, b / 1_000_000.0)
        val verb = if (resuming) "Resuming" else "Downloading"
        val w = if (what.isBlank()) "" else " $what"
        return if (total > 0) "$verb$w\u2026 ${mb(got)} of ${mb(total)} MB" else "$verb$w\u2026 ${mb(got)} MB"
    }

    /** True when an HTTP error means "not on this server" (try the next URL now, no wait, no retry here). */
    fun notHere(code: Int): Boolean = code == 404 || code == 410

    /** Which mirror try [attempt] uses: two tries each, then the next one, wrapping round. */
    fun mirrorIndex(attempt: Int, mirrors: Int): Int = if (mirrors <= 0) 0 else ((attempt - 1).coerceAtLeast(0) / 2) % mirrors

    /** The Range header to send when [have] bytes are already on disk, or null to start from the beginning. */
    fun rangeHeader(have: Long): String? = if (have > 0) "bytes=$have-" else null

    /** Total size from "bytes 100-199/3947906" (null when unknown, "*"). */
    fun totalFrom(contentRange: String?): Long? = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()

    /** Start offset from "bytes 100-199/3947906". */
    fun startFrom(contentRange: String?): Long? = contentRange?.removePrefix("bytes")?.trim()?.substringBefore('-')?.trim()?.toLongOrNull()

    /** True when a 206 reply really continues from [have]; anything else means keeping the old bytes would corrupt the file. */
    fun resumes(code: Int, contentRange: String?, have: Long): Boolean = have > 0 && code == 206 && startFrom(contentRange) == have

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Plain words for a failure, for the status line ("Connection dropped"). */
    fun reason(e: Throwable): String {
        val m = (e.message ?: "").lowercase()
        return when {
            e is StoppedException -> "Stopped"
            e is java.net.UnknownHostException || "unable to resolve" in m -> "Can't reach the download server"
            e is java.net.SocketTimeoutException || "timed out" in m || "timeout" in m -> "The connection went quiet"
            "no space" in m || "enospc" in m -> "The phone is out of space"
            e is ChecksumException -> "The download came through damaged"
            else -> "Connection dropped"
        }
    }

    class ChecksumException(msg: String) : IOException(msg)
    class HttpException(val code: Int) : IOException("HTTP $code")
    /** v0.33.5: the user tapped Stop. The .part file stays, so the next call resumes from it. */
    class StoppedException : IOException("stopped")

    /** A sleep that wakes within half a second of [cancelled] turning true. */
    fun stoppableSleep(cancelled: () -> Boolean): (Long) -> Unit = { ms ->
        var left = ms
        while (left > 0 && !cancelled()) { val d = minOf(left, 500L); Thread.sleep(d); left -= d }
    }

    /** Progress: bytes so far, total (or -1), and an optional retry line ("Connection dropped. Retrying (2 of 6)…"). */
    fun interface Progress { fun on(got: Long, total: Long, retry: String?) }

    /**
     * Downloads the first of [urls] that works into [dest], checking [sha256] (lowercase hex; blank skips the check).
     * Throws the last error when every try fails; partial bytes stay in `<dest>.part` for the next call to resume.
     */
    fun download(urls: List<String>, dest: File, sha256: String, tries: Int = TRIES, progress: Progress = Progress { _, _, _ -> },
                 sleep: (Long) -> Unit = { Thread.sleep(it) }, open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
                 cancelled: () -> Boolean = { false }): File {
        require(urls.isNotEmpty())
        val part = File(dest.path + ".part")
        dest.parentFile?.mkdirs()
        if (dest.exists() && (sha256.isBlank() || sha256(dest) == sha256)) return dest
        var last: Throwable? = null
        // v0.33.3: a URL that answers 404 / 410 is skipped at once (a package is in main OR community); others get two
        // tries each, then the next mirror, wrapping round. Every try resumes from the .part file.
        var idx = 0; var failsHere = 0; val missing = HashSet<Int>()
        fun advance() { var k = 0; do { idx = (idx + 1) % urls.size; k++ } while (idx in missing && k <= urls.size); failsHere = 0 }
        for (attempt in 1..tries) {
            if (cancelled()) throw StoppedException()
            val url = urls[idx]
            val prev = last
            if (attempt > 1 && prev != null && !(prev is HttpException && notHere(prev.code))) {
                val wait = backoffSec(attempt)
                progress.on(part.length(), -1, "${reason(last!!)}. Retrying ($attempt of $tries)\u2026")
                if (wait > 0) sleep(wait * 1000L)   // a caller with a Stop passes a sleep that wakes early (Fetch.stoppableSleep)
                if (cancelled()) throw StoppedException()
            }
            try {
                once(url, part, progress, open, cancelled)
                if (sha256.isNotBlank() && sha256(part) != sha256) { part.delete(); throw ChecksumException("checksum mismatch") }
                dest.delete()
                if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
                return dest
            } catch (e: StoppedException) { throw e
            } catch (e: HttpException) {
                last = e
                if (e.code == 416) part.delete()   // what we have is bigger than the file: start over
                if (notHere(e.code)) {
                    missing += idx
                    if (missing.size >= urls.size) throw e   // nowhere has it: no point retrying
                    advance(); continue
                }
                failsHere++
            } catch (e: Exception) { if (cancelled()) throw StoppedException(); last = e; failsHere++ }
            if (failsHere >= 2 && urls.size - missing.size > 1) advance()
        }
        throw last ?: IOException("download failed")
    }

    private fun once(url: String, part: File, progress: Progress, open: (String) -> HttpURLConnection, cancelled: () -> Boolean = { false }) {
        val have = if (part.exists()) part.length() else 0L
        val c = open(url)
        c.connectTimeout = CONNECT_TIMEOUT_MS; c.readTimeout = READ_TIMEOUT_MS
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept-Encoding", "identity")
        rangeHeader(have)?.let { c.setRequestProperty("Range", it) }
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpException(code)
            val resume = resumes(code, c.getHeaderField("Content-Range"), have)
            val len = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val total = if (resume) totalFrom(c.getHeaderField("Content-Range")) ?: (if (len >= 0) have + len else -1L) else len
            var got = if (resume) have else 0L
            RandomAccessFile(part, "rw").use { out ->
                if (!resume) out.setLength(0)
                out.seek(got)
                c.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw StoppedException()
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        progress.on(got, total, null)
                    }
                }
            }
            if (total > 0 && got < total) throw IOException("Connection dropped at $got of $total bytes")
            if (total > 0 && got > total) { part.delete(); throw IOException("Got more than the file's size; starting over") }
        } finally { c.disconnect() }
    }
}
