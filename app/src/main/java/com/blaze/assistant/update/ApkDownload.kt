package com.blaze.assistant.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * v0.28.1: the update download, made for slow and flaky networks (Blazeinchue's phone on Zambian mobile data got
 * "unexpected end of stream" and the whole update failed).
 *
 *  - **Resumable:** bytes go to `<name>.part`; every try asks for `Range: bytes=<have>-` and appends when the server
 *    answers 206 from that offset (a 200 starts over, a 416 means it's all there).
 *  - **Retries with backoff:** 2, 4, 8, 16, 30, 30… s between tries; a try that moved the download forward resets the
 *    count, so a connection that keeps dropping still finishes. [MAX_FAILS] tries in a row with no progress = give up.
 *  - **Sources:** v0.45.4: the one url from the Blaze site's versions.json (it redirects to the release registry). With
 *    more than one source, after [SWITCH_AFTER] failures in a row the next one carries on from the same partial file.
 *  - **Network drops:** before each try, [Io.waitOnline] blocks until there's a connection again (state "Connection
 *    dropped, I'll keep trying"); mobile data is as good as Wi-Fi.
 *  - **Small buffer, fsynced:** 16 KB reads; the file is synced to disk every [SYNC_EVERY] bytes so a killed app
 *    resumes from what was really written.
 *  - **Verified:** size when known, and the SHA-256 versions.json gives for the file, before installing. A mismatch
 *    deletes the file.
 *
 * Pure JVM (no Android), unit tested in ApkDownloadTest with a fake [Io].
 */
object ApkDownload {
    const val BUFFER = 16 * 1024
    const val SYNC_EVERY = 512L * 1024
    const val SWITCH_AFTER = 2
    const val MAX_FAILS = 10

    /** Wait (ms) before try number [fails] (1-based) after a failure. */
    fun backoffMs(fails: Int): Long = when {
        fails <= 0 -> 0L
        else -> minOf(30_000L, 1_000L shl fails.coerceAtMost(5))
    }

    class Response(val code: Int, val contentLength: Long, val contentRange: String?, val body: InputStream) : AutoCloseable {
        override fun close() { try { body.close() } catch (_: Exception) { } }
    }

    /** Everything that touches the outside world, so the loop is testable. */
    interface Io {
        fun open(url: String, from: Long): Response
        fun online(): Boolean = true
        /** Blocks until [online] (polling); false = cancelled while waiting. */
        fun waitOnline(cancelled: () -> Boolean): Boolean {
            while (!online()) { if (cancelled()) return false; sleep(2_000) }
            return true
        }
        fun sleep(ms: Long) { Thread.sleep(ms) }
    }

    class Cancelled : IOException("Cancelled")
    class GaveUp(msg: String) : IOException(msg)
    class Damaged(msg: String) : IOException(msg)

    /** What the card says while it works. */
    sealed class Event {
        data class Progress(val done: Long, val total: Long) : Event()
        /** A try failed; the next starts in [waitMs] (from [source] index [next]). */
        data class Retrying(val fails: Int, val waitMs: Long, val next: Int) : Event()
        object Offline : Event()
    }

    /** "bytes 100-999/1000" -> 100 */
    fun rangeStart(v: String?): Long? = v?.let { Regex("""bytes\s+(\d+)-\d+/(\d+|\*)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /**
     * Downloads to [dest] from [sources] (in order of preference), resuming [part]. [expected] = size in bytes
     * (0 = unknown), [sha256] = the expected SHA-256 in hex (null = skip). Throws [GaveUp], [Damaged] or [Cancelled].
     */
    fun fetch(
        sources: List<String>, io: Io, part: File, dest: File, expected: Long, sha256: String?,
        cancelled: () -> Boolean = { false }, event: (Event) -> Unit = {},
    ) {
        require(sources.isNotEmpty())
        part.parentFile?.mkdirs()
        if (expected > 0 && part.exists() && part.length() > expected) part.delete()
        var src = 0
        var fails = 0          // failures in a row with no progress
        var onSource = 0       // failures in a row on this source
        var lastError = ""
        while (true) {
            if (cancelled()) throw Cancelled()
            val have = if (part.exists()) part.length() else 0L
            if (expected > 0 && have == expected) break
            if (!io.online()) { event(Event.Offline); if (!io.waitOnline(cancelled)) throw Cancelled() }
            val before = have
            var complete = false
            try {
                complete = tryOnce(sources[src], io, part, have, expected, cancelled, event)
            } catch (e: Cancelled) { throw e }
            catch (e: IOException) { lastError = e.message ?: e.javaClass.simpleName }
            catch (e: RuntimeException) { lastError = e.message ?: e.javaClass.simpleName }
            if (complete) break
            val now = if (part.exists()) part.length() else 0L
            if (now > before) { fails = 0; onSource = 0 } else { fails++; onSource++ }
            if (fails >= MAX_FAILS) throw GaveUp("Couldn't finish the download after several tries ($lastError).")
            if (onSource >= SWITCH_AFTER && sources.size > 1) { src = (src + 1) % sources.size; onSource = 0 }
            val wait = if (now > before) 1_000L else backoffMs(fails)
            event(Event.Retrying(fails, wait, src))
            var waited = 0L
            while (waited < wait) { if (cancelled()) throw Cancelled(); val step = minOf(500L, wait - waited); io.sleep(step); waited += step }
        }
        if (expected > 0 && part.length() != expected) { part.delete(); throw Damaged("The download came out the wrong size.") }
        if (sha256 != null && VersionFeed.sha256(part) != sha256.trim().lowercase()) { part.delete(); dest.delete(); throw Damaged(VersionFeed.SHA_MISMATCH) }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Couldn't save the download")
    }

    /** One request. True = the whole file is there; false = it stopped early (the caller retries). */
    private fun tryOnce(url: String, io: Io, part: File, from: Long, expected: Long, cancelled: () -> Boolean, event: (Event) -> Unit): Boolean {
        io.open(url, from).use { r ->
            val append = when {
                r.code == 206 && from > 0 && rangeStart(r.contentRange) == from -> true
                r.code == 206 && from == 0L -> false
                r.code == 200 -> false
                r.code == 416 && from > 0 -> return expected <= 0 || from == expected
                else -> throw IOException("Server said ${r.code}")
            }
            val start = if (append) from else 0L
            val total = if (expected > 0) expected else if (r.contentLength > 0) start + r.contentLength else -1L
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(BUFFER)
                var done = start
                var synced = start
                var lastTick = -1L
                try {
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = r.body.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done - synced >= SYNC_EVERY) { out.flush(); try { out.fd.sync() } catch (_: Exception) { }; synced = done }
                        if (done - lastTick >= 64 * 1024) { lastTick = done; event(Event.Progress(done, total)) }
                    }
                } finally {
                    out.flush(); try { out.fd.sync() } catch (_: Exception) { }
                }
                event(Event.Progress(done, total))
                return if (expected > 0) done == expected else (total <= 0 || done >= total)
            }
        }
    }

    /**
     * How long the update card stays once nothing is happening, then it goes by itself (null = keep it: working, or
     * waiting on the user for Install / the permission). Failed and Done go after about a minute; a card left over
     * from before a restart (Idle, nothing running) goes too, so no card lingers.
     */
    fun dismissAfterMs(s: Updater.State): Long? = when (s) {
        is Updater.State.Failed, is Updater.State.Done, is Updater.State.Idle -> 60_000L
        is Updater.State.UpToDate -> 8_000L
        else -> null
    }

    /** Plain words for the card while it retries. */
    fun retryLine(e: Event.Retrying): String = when {
        e.waitMs >= 4_000 -> "Connection dropped, I'll keep trying in ${e.waitMs / 1000} s."
        else -> "Connection dropped, I'll keep trying."
    }
    const val OFFLINE = "No connection right now. I'll carry on when you're back online."
}
