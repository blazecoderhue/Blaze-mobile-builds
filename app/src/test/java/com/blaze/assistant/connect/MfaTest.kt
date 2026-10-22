package com.blaze.assistant.connect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.45.3: sign-in with two-step verification against blaze-auth 0.9.0
 * (shapes from Blaze-dekstop feat/auth-0.9.0 9bd31166, cloud/src/password.ts mfaVerify + mfa.ts).
 */
class MfaTest {
    /** A stand-in for POST /v1/auth/2fa/verify with the server's rules: 5 tries, single use, 5 minutes. */
    private class FakeAuth(val good: String = "123456", val recovery: String = "abcde-12345", var exp: Long = Long.MAX_VALUE) {
        var tries = 0; var used = false; var calls = 0; val seen = mutableListOf<JSONObject>()
        var clock = 0L
        fun post(path: String, b: JSONObject): Pair<Int, JSONObject> {
            calls++; seen += JSONObject(b.toString())
            assertEquals(Mfa.VERIFY_PATH, path)
            if (!b.optString("mfa_token").startsWith("mfa_") || used || clock > exp) return 401 to JSONObject().put("error", "invalid_mfa_token")
            val code = b.optString("code")
            if (code != good && code != recovery) {
                tries++
                if (tries >= Mfa.TRIES) { used = true; return 401 to JSONObject().put("error", "invalid_mfa_token").put("reason", "too_many_tries") }
                return 401 to JSONObject().put("error", "invalid_code").put("tries_left", Mfa.TRIES - tries)
            }
            used = true
            return 200 to JSONObject().put("token", "sess_1").put("user", JSONObject().put("id", "u1").put("email", "a@b.c").put("two_factor", true))
        }
    }

    private val tok = "mfa_" + "x".repeat(43)

    @Test fun parsesMfaRequired() {
        assertEquals(tok, Mfa.tokenOf(401, JSONObject("""{"error":"mfa_required","mfa_token":"$tok"}""")))
        assertNull(Mfa.tokenOf(401, JSONObject("""{"error":"invalid_credentials"}""")))
        assertNull(Mfa.tokenOf(200, JSONObject("""{"error":"mfa_required","mfa_token":"$tok"}""")))
        assertNull(Mfa.tokenOf(401, JSONObject("""{"error":"mfa_required"}""")))
        assertNull(Mfa.tokenOf(401, null))
    }

    @Test fun codeFormats() {
        assertEquals("123456", Mfa.clean(" 123 456 ", false))
        assertNull(Mfa.clean("12345", false)); assertNull(Mfa.clean("12345a", false)); assertNull(Mfa.clean("1234567", false))
        assertEquals("abcde-12345", Mfa.clean("ABCDE-12345", true))
        assertNull(Mfa.clean("abc", true)); assertNull(Mfa.clean("abcde_1234!", true))
    }

    @Test fun rightCodeSignsIn() {
        val srv = FakeAuth(); val p = Mfa.Pending(tok, 0L)
        val r = Mfa.verify(p, "123 456", false, 1_000L, srv::post)
        assertTrue(r is Mfa.Outcome.Signed)
        assertEquals("sess_1", (r as Mfa.Outcome.Signed).body.optString("token"))
        assertEquals(tok, srv.seen[0].optString("mfa_token")); assertEquals("123456", srv.seen[0].optString("code"))
        assertTrue(BlazeAccount.User.from(r.body.optJSONObject("user"))!!.twoFactor)
    }

    @Test fun recoveryCodeSignsIn() {
        val srv = FakeAuth()
        assertTrue(Mfa.verify(Mfa.Pending(tok, 0L), "ABCDE-12345", true, 0L, srv::post) is Mfa.Outcome.Signed)
    }

    @Test fun wrongCodesCountDownThenRestart() {
        val srv = FakeAuth(); var p = Mfa.Pending(tok, 0L)
        for (left in 4 downTo 1) {
            val r = Mfa.verify(p, "000000", false, 1_000L, srv::post)
            assertTrue(r is Mfa.Outcome.Retry)
            p = (r as Mfa.Outcome.Retry).pending
            assertEquals(left, p.triesLeft)
            assertTrue(r.message, r.message.contains(if (left == 1) "1 try left" else "$left tries left"))
        }
        val last = Mfa.verify(p, "000000", false, 1_000L, srv::post)
        assertTrue(last is Mfa.Outcome.Restart)
        assertEquals(5, srv.calls)
    }

    @Test fun badFormatCostsNoTry() {
        val srv = FakeAuth(); val p = Mfa.Pending(tok, 0L)
        assertTrue(Mfa.verify(p, "12", false, 0L, srv::post) is Mfa.Outcome.Error)
        assertEquals(0, srv.calls)
    }

    @Test fun expiredStepNeverCallsAndRestarts() {
        val srv = FakeAuth(); val p = Mfa.Pending(tok, 0L)
        assertFalse(p.expired(Mfa.TTL_MS - 1)); assertTrue(p.expired(Mfa.TTL_MS))
        assertTrue(Mfa.verify(p, "123456", false, Mfa.TTL_MS + 1, srv::post) is Mfa.Outcome.Restart)
        assertEquals(0, srv.calls)
        // the server's own expiry (clock skew) also ends the step
        val srv2 = FakeAuth(exp = 10).also { it.clock = 11 }
        assertTrue(Mfa.verify(p, "123456", false, 1_000L, srv2::post) is Mfa.Outcome.Restart)
    }

    @Test fun tokenIsSingleUse() {
        val srv = FakeAuth(); val p = Mfa.Pending(tok, 0L)
        assertTrue(Mfa.verify(p, "123456", false, 0L, srv::post) is Mfa.Outcome.Signed)
        assertTrue(Mfa.verify(p, "123456", false, 0L, srv::post) is Mfa.Outcome.Restart)
    }

    @Test fun offlineAndRateLimitKeepTheStep() {
        val p = Mfa.Pending(tok, 0L)
        val off = Mfa.verify(p, "123456", false, 0L) { _, _ -> throw java.io.IOException("offline") }
        assertTrue(off is Mfa.Outcome.Error)
        val rl = Mfa.verify(p, "123456", false, 0L) { _, _ -> 429 to JSONObject().put("error", "rate_limited") }
        assertTrue(rl is Mfa.Outcome.Error && rl.message.contains("Too many tries"))
    }

    @Test fun stateLineShowsTimeAndTries() {
        val p = Mfa.Pending(tok, 0L)
        assertEquals("5:00 left · 5 tries left", Mfa.stateLine(p, 0L))
        assertEquals("0:01 left · 1 try left", Mfa.stateLine(p.copy(triesLeft = 1), Mfa.TTL_MS - 500))
    }

    @Test fun providerFlagsAndUser() {
        val live = JSONObject("""{"version":"0.9.0","login":{"email":true},"connectors":{},"web_login":true,"password_reset":true,"two_factor":true}""")
        assertEquals(mapOf("web_login" to true, "password_reset" to true, "two_factor" to true, "app_redirect" to false,
            "two_factor_app" to true, "two_factor_email" to false), BlazeAccount.featureFlags(live))
        val v091 = JSONObject("""{"version":"0.9.1","two_factor":true,"two_factor_methods":{"app":true,"email":true}}""")
        assertTrue(BlazeAccount.featureFlags(v091)["two_factor_email"]!!)
        val old = JSONObject("""{"login":{"email":true},"connectors":{}}""")
        assertTrue(BlazeAccount.featureFlags(old).values.none { it })
        val u = BlazeAccount.User.from(JSONObject("""{"id":"u1","email":"a@b.c","has_password":true,"two_factor":true,"providers":["google"]}"""))!!
        assertTrue(u.twoFactor && u.hasPassword)
        assertTrue(BlazeAccount.User.from(u.json())!!.twoFactor)
        assertFalse(BlazeAccount.User.from(JSONObject("""{"id":"u2"}"""))!!.twoFactor)
        // 0.9.0 user: two_factor alone means the app; 0.9.1 names the methods
        assertTrue(u.twoFactorApp && !u.twoFactorEmail)
        val e = BlazeAccount.User.from(JSONObject("""{"id":"u3","two_factor":true,"two_factor_methods":{"app":false,"email":true}}"""))!!
        assertTrue(e.twoFactor && e.twoFactorEmail && !e.twoFactorApp)
        val back = BlazeAccount.User.from(e.json())!!
        assertTrue(back.twoFactorEmail && !back.twoFactorApp)
    }

    // ---------------- 0.9.1: emailed codes ----------------

    /** POST /v1/auth/2fa/email as 0.9.1 answers it: 3 sends per sign-in, then 429 too_many_codes. */
    private class FakeMail { var sends = 0; var calls = 0
        fun post(path: String, b: JSONObject): Pair<Int, JSONObject> {
            calls++; assertEquals(Mfa.EMAIL_PATH, path); assertTrue(b.optString("mfa_token").startsWith("mfa_"))
            if (sends >= Mfa.SENDS) return 429 to JSONObject().put("error", "too_many_codes")
            sends++
            return 200 to JSONObject().put("ok", true).put("sent_to", "l**e@example.com").put("sends_left", Mfa.SENDS - sends)
        }
    }

    @Test fun parsesMethods() {
        val both = JSONObject("""{"error":"mfa_required","mfa_token":"$tok","methods":["app","email","recovery"],"email_hint":"l**e@example.com"}""")
        val p = Mfa.pendingOf(401, both, 0L, "email")!!
        assertEquals(listOf("app", "email", "recovery"), p.methods); assertEquals("l**e@example.com", p.emailHint)
        assertTrue(p.app && p.email && p.recovery); assertFalse(p.autoSend)
        val mailOnly = Mfa.pendingOf(401, JSONObject("""{"error":"mfa_required","mfa_token":"$tok","methods":["email"],"email_hint":"a**b@c.d"}"""), 0L, "google")!!
        assertEquals(listOf("email"), mailOnly.methods); assertFalse(mailOnly.app); assertFalse(mailOnly.recovery)
        // 0.9.0 sends no list: authenticator app + recovery codes
        assertEquals(listOf("app", "recovery"), Mfa.methodsOf(JSONObject("""{"error":"mfa_required","mfa_token":"$tok"}""")))
        // unknown entries (sms) are dropped; order is fixed
        assertEquals(listOf("app", "email"), Mfa.methodsOf(JSONObject("""{"methods":["sms","email","app"]}""")))
        assertNull(Mfa.pendingOf(401, JSONObject("""{"error":"invalid_credentials"}"""), 0L, "email"))
    }

    @Test fun noAppMeansAutoSend() {
        val p = Mfa.Pending(tok, 0L, methods = listOf("email"), emailHint = "l**e@example.com")
        assertTrue(p.autoSend)
        val srv = FakeMail()
        val r = Mfa.sendEmail(p, 1_000L, srv::post)
        assertTrue(r is Mfa.Outcome.Retry)
        val q = (r as Mfa.Outcome.Retry).pending
        assertEquals("We sent a code to l**e@example.com. It works for 10 minutes.", r.message)
        assertTrue(q.emailSent); assertFalse(q.autoSend); assertEquals(2, q.sendsLeft)
        // the emailed code goes through the same verify call
        val v = Mfa.verify(q, "654321", false, 2_000L) { path, b -> assertEquals(Mfa.VERIFY_PATH, path); assertEquals("654321", b.optString("code"))
            200 to JSONObject().put("token", "s").put("user", JSONObject().put("id", "u")) }
        assertTrue(v is Mfa.Outcome.Signed)
        // with the app on the account nothing is sent by itself
        assertFalse(Mfa.Pending(tok, 0L, methods = listOf("app", "email")).autoSend)
    }

    @Test fun resendWaitsAndStopsAtTheLimit() {
        val srv = FakeMail()
        var p = Mfa.Pending(tok, 0L, methods = listOf("app", "email", "recovery"))
        assertEquals("3 more can be sent", Mfa.sendLine(p, 0L))
        p = (Mfa.sendEmail(p, 0L, srv::post) as Mfa.Outcome.Retry).pending
        // too soon: no call
        assertFalse(p.canSend(10_000L))
        assertEquals("New code in 0:20 · 2 more", Mfa.sendLine(p, 10_000L))
        assertTrue(Mfa.sendEmail(p, 10_000L, srv::post) is Mfa.Outcome.Error); assertEquals(1, srv.calls)
        p = (Mfa.sendEmail(p, Mfa.RESEND_MS, srv::post) as Mfa.Outcome.Retry).pending
        p = (Mfa.sendEmail(p, 2 * Mfa.RESEND_MS, srv::post) as Mfa.Outcome.Retry).pending
        assertEquals(0, p.sendsLeft); assertFalse(p.canSend(10 * Mfa.RESEND_MS))
        assertEquals("No more codes can be sent for this sign-in.", Mfa.sendLine(p, 3 * Mfa.RESEND_MS))
        assertTrue(Mfa.sendEmail(p, 4 * Mfa.RESEND_MS, srv::post) is Mfa.Outcome.Error); assertEquals(3, srv.calls)
        // the server's own limit (e.g. sends from another screen) also ends sending
        val other = FakeMail().also { it.sends = 3 }
        val r = Mfa.sendEmail(Mfa.Pending(tok, 0L, methods = listOf("email")), 0L, other::post)
        assertTrue(r is Mfa.Outcome.Retry && r.pending.sendsLeft == 0)
    }

    @Test fun sendFailures() {
        val p = Mfa.Pending(tok, 0L, methods = listOf("app", "email"))
        assertTrue(Mfa.sendEmail(p, 0L) { _, _ -> 502 to JSONObject().put("error", "mail_failed") } is Mfa.Outcome.Error)
        assertTrue(Mfa.sendEmail(p, 0L) { _, _ -> 401 to JSONObject().put("error", "invalid_mfa_token") } is Mfa.Outcome.Restart)
        assertTrue(Mfa.sendEmail(p, Mfa.TTL_MS) { _, _ -> error("must not call") } is Mfa.Outcome.Restart)
        assertTrue(Mfa.sendEmail(Mfa.Pending(tok, 0L), 0L) { _, _ -> error("must not call") } is Mfa.Outcome.Error)   // 0.9.0: no email
    }
}
