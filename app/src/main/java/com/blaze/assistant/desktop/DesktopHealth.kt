package com.blaze.assistant.desktop

import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * v0.43.0: is the Linux desktop really up? Only one answer counts: port 5901 on 127.0.0.1 accepts a connection and
 * greets with an RFB (VNC) banner, the same thing the viewer needs. Before this, "running" came from a check inside
 * the Linux that matched its own command line and a stale socket file, so Blaze said the desktop was running while
 * nothing listened on the port (the viewer then failed with ECONNREFUSED).
 *
 * Pure parts (DesktopHealthTest): the banner check, the wait-with-retries loop (clock and sleep injected), the plain
 * words for a failed connection, the log tail and its diagnosis, and the tool results the agent reads.
 */
object DesktopHealth {
    enum class Probe { UP, REFUSED, TIMEOUT, NOT_VNC }

    /** "RFB 003.008\n" (any 3.x the viewer accepts). */
    fun isRfbBanner(b: ByteArray): Boolean = b.size >= 12 && Rfb.version(String(b, 0, 12, Charsets.ISO_8859_1)) != null

    /** Reads up to 12 bytes; a server that closes early gives fewer. */
    fun readBanner(inp: InputStream): ByteArray {
        val buf = ByteArray(12); var n = 0
        while (n < 12) { val k = inp.read(buf, n, 12 - n); if (k <= 0) break; n += k }
        return buf.copyOf(n)
    }

    /** One real connection to the desktop's port. Never throws. */
    fun probe(port: Int = DesktopPlan.PORT, connectMs: Int = 1500, readMs: Int = 2500): Probe = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), connectMs)
            s.soTimeout = readMs
            if (isRfbBanner(readBanner(s.getInputStream()))) Probe.UP else Probe.NOT_VNC
        }
    } catch (_: ConnectException) { Probe.REFUSED } catch (_: SocketTimeoutException) { Probe.TIMEOUT } catch (_: Exception) { Probe.REFUSED }

    data class Wait(val ok: Boolean, val attempts: Int, val waitedMs: Long, val last: Probe?, val gaveUpEarly: Boolean)

    /**
     * Checks [check] every [intervalMs] until it says [Probe.UP], [timeoutMs] passes, or [stop] says there's no point
     * (the start job already exited). Clock and sleep are injected for tests.
     */
    suspend fun waitUntilUp(
        timeoutMs: Long, intervalMs: Long,
        check: suspend () -> Probe,
        stop: suspend () -> Boolean = { false },
        now: () -> Long = System::currentTimeMillis,
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ): Wait {
        val t0 = now(); var n = 0; var last: Probe? = null
        while (true) {
            n++
            last = check()
            if (last == Probe.UP) return Wait(true, n, now() - t0, last, false)
            if (stop()) {
                // One last look: the job can exit right after the port came up (it never should, but don't lie either way).
                last = check(); n++
                return Wait(last == Probe.UP, n, now() - t0, last, last != Probe.UP)
            }
            if (now() - t0 + intervalMs > timeoutMs) return Wait(false, n, now() - t0, last, false)
            sleep(intervalMs)
        }
    }

    /** The viewer's retry rule while the desktop starts: wait [delays] in turn while time is left in [windowMs]. */
    fun retryDelay(attempt: Int, elapsedMs: Long, windowMs: Long = 30_000): Long? {
        if (elapsedMs >= windowMs) return null
        val d = listOf(500L, 800L, 1200L, 1800L, 2500L)[attempt.coerceIn(0, 4)]
        return d.coerceAtMost(windowMs - elapsedMs)
    }

    /** Errors worth retrying while the desktop is still starting (nothing listening yet, or the connect timed out). */
    fun retryable(raw: String): Boolean = Regex("(?i)ECONNREFUSED|connection refused|failed to connect|timed out|timeout|ECONNRESET|connection reset").containsMatchIn(raw)

    const val NOT_RUNNING = "The desktop isn't answering. It may have closed or not finished starting."
    const val AUTH = "The desktop asked for a different password. Tap Repair to restart it cleanly."
    const val CLOSED = "The desktop closed the connection."

    /** Plain words for the viewer instead of "failed to connect to /127.0.0.1 (port 5901) ... ECONNREFUSED". */
    fun plainError(raw: String): String = when {
        raw.isBlank() -> ""
        retryable(raw) -> NOT_RUNNING
        Regex("(?i)password|authentication|sign-in|auth").containsMatchIn(raw) -> AUTH
        Regex("(?i)not a vnc").containsMatchIn(raw) -> "Something else is using the desktop's port. Tap Repair."
        Regex("(?i)closed the connection|EOF|broken pipe").containsMatchIn(raw) -> CLOSED
        else -> NOT_RUNNING
    }

    /** The useful last lines of a Terminal.run result ("exit 0 · cwd /root" header, blanks and routine noise dropped). */
    fun logTail(out: String, max: Int = 8): List<String> = out.lines()
        .filterIndexed { i, l -> !(i == 0 && l.startsWith("exit ")) }
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("---") && !it.startsWith("Copyright") && !it.startsWith("See https") &&
            !it.startsWith("Underlying X server") && !it.startsWith("Xvnc TigerVNC") && !it.matches(Regex("\\(EE\\)\\s*")) && "mieq: warning" !in it }
        .takeLast(max)

    /** A plain hint for a known Xvnc failure, or null. */
    fun diagnose(log: String): String? = when {
        Regex("(?i)password file is missing|PasswordFile|could not read password").containsMatchIn(log) -> "The desktop's password file wasn't written."
        Regex("(?i)Xvnc is not installed|not found").containsMatchIn(log) && "Xvnc" in log -> "The desktop's display server (Xvnc) is missing; Repair re-links it, or reinstall the desktop."
        Regex("(?i)Address (already )?in use|failed to bind").containsMatchIn(log) -> "Something else is already using the desktop's port 5901."
        Regex("(?i)Server is already active|already in use for display|\\.X1-lock").containsMatchIn(log) -> "An old desktop lock was left behind; Repair clears it."
        Regex("(?i)could not open default font|font path").containsMatchIn(log) -> "The desktop is missing its basic fonts."
        Regex("(?i)Bad system call|SIGSYS").containsMatchIn(log) -> "Android blocked something the display server needs."
        Regex("(?i)Killed|signal 9|out of memory").containsMatchIn(log) -> "Android closed the display server, probably to save memory."
        else -> null
    }

    /** What the user sees when the start failed (status line + error). */
    fun failLine(log: List<String>): String {
        val hint = diagnose(log.joinToString("\n"))
        return "The desktop didn't start." + (if (hint != null) " $hint" else "") + " Tap Repair to fix it and try again."
    }

    /** The agent's tool result after a verified start: the port answered. */
    fun verifiedResult(ms: Long): String =
        "VERIFIED: the Linux desktop is running. Port 5901 accepted a VNC connection ${"%.1f".format(java.util.Locale.US, ms / 1000.0)} s after start. The user can watch it in Blaze (View desktop); never suggest a VNC client."

    /** The agent's tool result after a failed start. The model must not say the desktop is running. */
    fun failedResult(log: List<String>): String =
        "FAILED: the Linux desktop did NOT start (nothing accepted a connection on port 5901). Do not tell the user it is running or that any app " +
            "in it was opened. Tell them plainly it didn't start and that the Repair button on the Desktop page (or desktop action=repair) can fix it." +
            (diagnose(log.joinToString("\n"))?.let { " Likely cause: $it" } ?: "") +
            (if (log.isNotEmpty()) "\nLast lines of /tmp/xvnc.log:\n" + log.joinToString("\n") else "\n(/tmp/xvnc.log was empty)")

    /** True only for a result that may be reported to the user as "running". */
    fun saysRunning(result: String): Boolean = result.startsWith("VERIFIED:") || result.startsWith("The desktop is running")
}
