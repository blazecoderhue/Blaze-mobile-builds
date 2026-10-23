package com.blaze.assistant.terminal

import java.net.HttpURLConnection
import java.net.URL

/**
 * v0.33.5: one Stop for every part of a Linux download. [stop] closes the download that is in flight (its
 * .part file stays, so the next Start resumes with an HTTP Range request), kills the running install process (PRoot
 * runs with --kill-on-exit, so apk / apt / pacman / dnf go with it) and wakes any retry wait.
 */
class StopToken {
    @Volatile var stopped = false; private set
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var proc: Process? = null

    /** Opens a download connection this token can close. */
    fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).also { conn = it; if (stopped) runCatching { it.disconnect() } }

    /** The install process now running (null when it ended). */
    fun track(p: Process?) { proc = p; if (stopped && p != null) kill(p) }

    val sleep: (Long) -> Unit get() = Fetch.stoppableSleep { stopped }
    val cancelled: () -> Boolean get() = { stopped }

    fun stop() {
        stopped = true
        conn?.let { c -> Thread { runCatching { c.disconnect() } }.apply { isDaemon = true }.start() }
        proc?.let { kill(it) }
    }

    fun reset() { stopped = false; conn = null; proc = null }

    private fun kill(p: Process) {
        Thread {
            runCatching { p.destroy() }
            runCatching { if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
        }.apply { isDaemon = true; name = "blaze-install-stop" }.start()
    }
}
