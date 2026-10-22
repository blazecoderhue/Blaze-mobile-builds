package com.blaze.assistant.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v0.45.0 recovery for one tool call:
 *  - a transient failure (network down, timeout talking to a service, 429 / 5xx, "try again later") of a SAFE tool is
 *    retried with backoff ([BACKOFF_MS]) before the model ever sees it;
 *  - a risky one-shot tool (OneShot.risky: messages, calls, payments, deletes) is NEVER retried: a second try could
 *    send twice. Its failure goes to the model as is;
 *  - a call that doesn't finish in time is stopped and reported honestly: for a risky tool "couldn't confirm, it may
 *    or may not have gone through, not retried" (unverified, so Blaze never claims it), for others a plain error.
 *  - Stop (cancellation) always goes straight through.
 * Pure Kotlin (coroutines only); RecoveryTest.
 */
object Recovery {
    val BACKOFF_MS = listOf(1_000L, 3_000L)

    private val transientRe = Regex(
        """(?i)\b(timed? ?out|timeout|connection (?:reset|refused|closed|aborted)|unreachable|no (?:internet|connection|network)|network (?:error|is down|unavailable)|""" +
            """unknownhost|unable to resolve host|socket|econn\w*|temporarily|try again (?:later|in a)|rate.?limit\w*|too many requests|service unavailable|bad gateway|gateway timeout|""" +
            """\b(?:429|500|502|503|504)\b|server (?:error|is busy)|busy right now)""")

    /** Is [result] a failure that may well work on a second try? */
    fun transient(result: String): Boolean = !ClaimGuard.succeeded(result) && transientRe.containsMatchIn(result.take(400))

    fun timeoutLine(name: String, risky: Boolean, ms: Long): String = if (risky)
        "Couldn't confirm: $name didn't finish within ${ms / 1000}s and was stopped, so it may or may not have gone through. It was NOT tried again (it could happen twice)."
    else "Error: $name didn't finish within ${ms / 1000}s and was stopped. Try a quicker way or run it as a background job."

    suspend fun run(name: String, risky: Boolean, timeoutMs: Long, sleep: suspend (Long) -> Unit = { delay(it) }, call: suspend () -> String): String {
        var attempt = 0
        while (true) {
            val r = try { withTimeoutOrNull(timeoutMs) { call() } ?: return timeoutLine(name, risky, timeoutMs) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { "Error: ${e.javaClass.simpleName}: ${e.message ?: "failed"}" }
            if (risky || attempt >= BACKOFF_MS.size || !transient(r)) {
                return if (attempt > 0 && !ClaimGuard.succeeded(r)) "$r (tried ${attempt + 1} times)" else r
            }
            sleep(BACKOFF_MS[attempt])
            attempt++
        }
    }
}
