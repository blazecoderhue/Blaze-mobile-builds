package com.blaze.assistant.connect

import org.json.JSONObject

/**
 * v0.45.3: the second sign-in step of blaze-auth 0.9.0 / 0.9.1. When two-step verification is on, a correct
 * password (/v1/auth/login) or a browser sign-in (/oauth/redeem, kind login) answers
 * 401 {error:"mfa_required", mfa_token, methods:["app","email","recovery"] | ["email"], email_hint} (0.9.1; 0.9.0
 * sends no methods, which means an authenticator app). The token lives 5 minutes and allows 5 wrong codes; it is
 * traded for a session with POST /v1/auth/2fa/verify {mfa_token, code} -> {token, user} (an app code, an emailed
 * code or a recovery code). POST /v1/auth/2fa/email {mfa_token} -> {ok, sent_to, sends_left} emails a 6-digit code
 * (valid 10 minutes, at most 3 sends per sign-in: then 429 too_many_codes; 502 mail_failed; 503 not_configured).
 * A wrong code answers 401 {error:"invalid_code", tries_left}; an expired / used-up token 401 invalid_mfa_token.
 * Pure: the HTTP call is passed in, so the flow is unit-tested without a network.
 */
object Mfa {
    const val TTL_MS = 300_000L
    const val TRIES = 5
    const val SENDS = 3
    /** Client-side wait between emailed codes (the server only caps the count). */
    const val RESEND_MS = 30_000L
    const val VERIFY_PATH = "/v1/auth/2fa/verify"
    const val EMAIL_PATH = "/v1/auth/2fa/email"

    /** A sign-in waiting for its second factor. [via] is "email", "google" or "github" (how the first step went). */
    data class Pending(val token: String, val startedAt: Long, val triesLeft: Int = TRIES, val via: String = "email",
                       val methods: List<String> = listOf("app", "recovery"), val emailHint: String = "",
                       val sendsLeft: Int = SENDS, val lastSentAt: Long = -1L, val sentTo: String = "") {
        fun leftMs(now: Long): Long = (startedAt + TTL_MS - now).coerceAtLeast(0L)
        fun expired(now: Long): Boolean = leftMs(now) == 0L || triesLeft <= 0
        val app get() = "app" in methods
        val email get() = "email" in methods
        val recovery get() = "recovery" in methods
        val emailSent get() = lastSentAt >= 0L
        /** No authenticator app on the account: the code can only come by email, so send it straight away. */
        val autoSend get() = !app && email && !emailSent && sendsLeft > 0
        fun resendInMs(now: Long): Long = if (!emailSent) 0L else (lastSentAt + RESEND_MS - now).coerceAtLeast(0L)
        fun canSend(now: Long): Boolean = email && sendsLeft > 0 && resendInMs(now) == 0L && !expired(now)
    }

    /** The mfa_token of a "second step needed" answer, else null. */
    fun tokenOf(status: Int, body: JSONObject?): String? {
        if (status != 401 || body == null || body.optString("error") != "mfa_required") return null
        return body.optString("mfa_token").trim().takeIf { it.isNotEmpty() }
    }

    /** The second-factor ways of an mfa_required body, known ones only, in a fixed order. No list (0.9.0) = app + recovery. */
    fun methodsOf(body: JSONObject?): List<String> {
        val a = body?.optJSONArray("methods") ?: return listOf("app", "recovery")
        val got = (0 until a.length()).map { a.optString(it).trim().lowercase() }.toSet()
        return listOf("app", "email", "recovery").filter { it in got }.ifEmpty { listOf("app", "recovery") }
    }

    /** The pending step from an mfa_required body (null when it isn't one). */
    fun pendingOf(status: Int, body: JSONObject?, now: Long, via: String): Pending? {
        val tok = tokenOf(status, body) ?: return null
        return Pending(tok, now, via = via, methods = methodsOf(body), emailHint = body?.optString("email_hint").orEmpty())
    }

    /** The code as the server wants it, or null when it can't be one: 6 digits, or a recovery code (xxxxx-xxxxx). */
    fun clean(raw: String, recovery: Boolean): String? {
        val s = raw.replace(Regex("\\s"), "")
        return if (recovery) s.lowercase().takeIf { it.length in 8..64 && it.all { c -> c.isLetterOrDigit() || c == '-' } }
        else s.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' } }
    }

    private fun mmss(ms: Long): String { val s = (ms + 999) / 1000; return "%d:%02d".format(s / 60, s % 60) }

    /** "4:05 left · 5 tries left" for the code step. */
    fun stateLine(p: Pending, now: Long): String =
        mmss(p.leftMs(now)) + " left · " + (if (p.triesLeft == 1) "1 try left" else "${p.triesLeft} tries left")

    /** The "Send a new code" state: "", "New code in 0:25 · 2 more", "2 more codes can be sent", "No more codes can be sent". */
    fun sendLine(p: Pending, now: Long): String = when {
        !p.email -> ""
        p.sendsLeft <= 0 -> "No more codes can be sent for this sign-in."
        p.resendInMs(now) > 0 -> "New code in " + mmss(p.resendInMs(now)) + " · " + more(p.sendsLeft)
        else -> more(p.sendsLeft).replaceFirstChar { it.uppercase() } + " can be sent"
    }
    private fun more(n: Int) = if (n == 1) "1 more" else "$n more"

    /** Where the code went, for "We sent a code to l**e@example.com". */
    fun sentTo(p: Pending): String = p.sentTo.ifBlank { p.emailHint }.ifBlank { "your email" }

    sealed class Outcome {
        /** Signed in: the {token, user} body. */
        data class Signed(val body: JSONObject) : Outcome()
        /** The step stays with a new state (wrong code, code emailed, send limit reached): show [message]. */
        data class Retry(val pending: Pending, val message: String) : Outcome()
        /** The step is over (expired, too many tries, token unknown): back to sign-in with [message]. */
        data class Restart(val message: String) : Outcome()
        /** Nothing was spent (bad format, offline, rate limit): show [message], the step stays as it was. */
        data class Error(val message: String) : Outcome()
    }

    const val EXPIRED = "The sign-in code step ran out (5 minutes or 5 tries). Sign in again."
    private const val OFFLINE = "Couldn't reach Blaze's sign-in service. Check your internet connection."

    /** Emails a sign-in code. Never sends while the wait or the limit says no. */
    fun sendEmail(p: Pending, now: Long, post: (path: String, body: JSONObject) -> Pair<Int, JSONObject>): Outcome {
        if (p.expired(now)) return Outcome.Restart(EXPIRED)
        if (!p.email) return Outcome.Error("This account can't get codes by email.")
        if (p.sendsLeft <= 0) return Outcome.Error("No more codes can be sent for this sign-in. Use the last one, or sign in again.")
        if (p.resendInMs(now) > 0) return Outcome.Error("Wait " + mmss(p.resendInMs(now)) + " before asking for a new code.")
        val (status, body) = try { post(EMAIL_PATH, JSONObject().put("mfa_token", p.token)) } catch (_: Exception) { return Outcome.Error(OFFLINE) }
        if (status in 200..299) {
            val to = body.optString("sent_to").ifBlank { p.emailHint }
            val left = if (body.has("sends_left")) body.optInt("sends_left", p.sendsLeft - 1) else p.sendsLeft - 1
            val q = p.copy(sendsLeft = left.coerceAtLeast(0), lastSentAt = now, sentTo = to)
            return Outcome.Retry(q, "We sent a code to " + sentTo(q) + ". It works for 10 minutes.")
        }
        return when (body.optString("error")) {
            "too_many_codes" -> Outcome.Retry(p.copy(sendsLeft = 0), "No more codes can be sent for this sign-in. Use the last one, or sign in again.")
            "invalid_mfa_token" -> Outcome.Restart(EXPIRED)
            "mail_failed" -> Outcome.Error("The email couldn't be sent. Try again in a moment" + (if (p.app) ", or use your authenticator app." else "."))
            "not_configured" -> Outcome.Error("Codes by email aren't switched on yet" + (if (p.app) ". Use your authenticator app." else "."))
            "rate_limited" -> Outcome.Error("Too many tries. Wait a few minutes and try again.")
            else -> Outcome.Error("Couldn't send the code (" + body.optString("error").ifBlank { "http_$status" } + ").")
        }
    }

    /**
     * One verify try. [post] does POST path+body and returns (status, parsed body); it may throw
     * (offline). The step's own clock is checked first, so an expired step never calls the server.
     */
    fun verify(p: Pending, raw: String, recovery: Boolean, now: Long,
               post: (path: String, body: JSONObject) -> Pair<Int, JSONObject>): Outcome {
        if (p.expired(now)) return Outcome.Restart(EXPIRED)
        val code = clean(raw, recovery) ?: return Outcome.Error(
            if (recovery) "Enter one of your recovery codes, like abcde-12345."
            else if (p.emailSent && !p.app) "Enter the 6-digit code from the email." else "Enter the 6-digit code.")
        val (status, body) = try {
            post(VERIFY_PATH, JSONObject().put("mfa_token", p.token).put("code", code))
        } catch (_: Exception) {
            return Outcome.Error(OFFLINE)
        }
        if (status in 200..299) {
            return if (body.optString("token").isNotBlank() && body.optJSONObject("user") != null) Outcome.Signed(body)
            else Outcome.Error("Sign-in failed (no_session).")
        }
        return when (body.optString("error")) {
            "invalid_code" -> {
                val left = if (body.has("tries_left")) body.optInt("tries_left", p.triesLeft - 1) else p.triesLeft - 1
                if (left <= 0) Outcome.Restart(EXPIRED)
                else Outcome.Retry(p.copy(triesLeft = left),
                    (if (recovery) "That recovery code didn't work." else "That code didn't work.") +
                        " " + (if (left == 1) "1 try left." else "$left tries left."))
            }
            "invalid_mfa_token" -> Outcome.Restart(EXPIRED)
            "rate_limited" -> Outcome.Error("Too many tries. Wait a few minutes and try again.")
            else -> Outcome.Error("Sign-in failed (" + body.optString("error").ifBlank { "http_$status" } + ").")
        }
    }
}
