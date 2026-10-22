package com.blaze.assistant.connect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.45.4: Google / GitHub sign-in coming back as blaze://auth/cb. Server shapes from blaze-auth 0.9.5
 * (Blaze-dekstop main, cloud/src/index.ts start/back/redeem, util.ts appRedirect, mfa.ts requireSecondFactor).
 */
class AuthReturnTest {
    private class MapStore : AuthReturn.Store { var v = ""; override fun get() = v; override fun set(v: String) { this.v = v } }

    // ---------- redirect parsing and the state check ----------

    @Test fun codeWithMatchingState() {
        assertEquals(AuthReturn.Return.Code("abc_DEF-123"),
            AuthReturn.parse("blaze://auth/cb?state=s1&code=abc_DEF-123&provider=google&flow=login", "s1"))
    }

    @Test fun parametersArePercentDecoded() {
        assertEquals(AuthReturn.Return.Code("a b+c"), AuthReturn.parse("blaze://auth/cb?code=a%20b%2Bc&state=s%2F1", "s/1"))
    }

    @Test fun wrongOrMissingStateIsIgnored() {
        assertEquals(AuthReturn.Return.Mismatch, AuthReturn.parse("blaze://auth/cb?code=abc&state=evil", "s1"))
        assertEquals(AuthReturn.Return.Mismatch, AuthReturn.parse("blaze://auth/cb?code=abc", "s1"))
        assertEquals(AuthReturn.Return.Mismatch, AuthReturn.parse("blaze://auth/cb?code=abc&state=s1", ""))
        assertEquals(AuthReturn.Return.Mismatch, AuthReturn.parse(null, "s1"))
    }

    @Test fun onlyTheAuthCallbackCounts() {
        assertTrue(AuthReturn.isAuthLink("blaze://auth/cb?code=1&state=2"))
        assertTrue(AuthReturn.isAuthLink("BLAZE://auth/cb"))
        assertFalse(AuthReturn.isAuthLink("blaze://auth/cbx?code=1&state=s1"))
        assertFalse(AuthReturn.isAuthLink("blaze://other/cb?code=1&state=s1"))
        assertFalse(AuthReturn.isAuthLink("https://evil.example/blaze://auth/cb?code=1&state=s1"))
        assertFalse(AuthReturn.isAuthLink("http://127.0.0.1:33987/cb?code=1&state=s1"))
        assertEquals(AuthReturn.Return.Mismatch, AuthReturn.parse("blaze://other/cb?code=abc&state=s1", "s1"))
    }

    @Test fun errorsAndMissingCode() {
        assertEquals(AuthReturn.Return.Failed("access_denied"), AuthReturn.parse("blaze://auth/cb?error=access_denied&provider=google&state=s1", "s1"))
        assertEquals(AuthReturn.Return.Failed("missing_code"), AuthReturn.parse("blaze://auth/cb?state=s1", "s1"))
        assertEquals(AuthReturn.Return.Failed("not_configured"), AuthReturn.parse("blaze://auth/cb?state=s1&error=not_configured&code=x", "s1"))
    }

    // ---------- the pending verifier survives a process kill ----------

    @Test fun pendingIsSavedAndLoaded() {
        val st = MapStore()
        val p = AuthReturn.Pending("s1", "v".repeat(43), "google", "login", 1_000L)
        AuthReturn.save(st, p)
        assertTrue(st.v.isNotEmpty())
        // A new process: only the store is left.
        assertEquals(p, AuthReturn.load(st, 1_000L + 60_000L))
        assertEquals(AuthReturn.Return.Code("c"), AuthReturn.parse("blaze://auth/cb?code=c&state=s1", AuthReturn.load(st, 2_000L)!!.state))
    }

    @Test fun stalePendingIsDropped() {
        val st = MapStore()
        AuthReturn.save(st, AuthReturn.Pending("s1", "v".repeat(43), "github", "login", 1_000L))
        assertNull(AuthReturn.load(st, 1_000L + AuthReturn.TTL_MS + 1))
        assertEquals("", st.v)   // cleared, not kept around
        AuthReturn.save(st, AuthReturn.Pending("s1", "v".repeat(43), "github", "login", 5_000L))
        assertNull(AuthReturn.load(st, 4_000L))   // the clock went backwards
    }

    @Test fun clearAndBadData() {
        val st = MapStore()
        AuthReturn.save(st, AuthReturn.Pending("s1", "v".repeat(43), "google", "login", 1L))
        AuthReturn.clear(st)
        assertNull(AuthReturn.load(st, 2L))
        assertNull(AuthReturn.Pending.parse("not json"))
        assertNull(AuthReturn.Pending.parse("{\"state\":\"\",\"verifier\":\"x\",\"at\":1}"))
        assertNull(AuthReturn.Pending.parse("{\"state\":\"s\",\"verifier\":\"\",\"at\":1}"))
        assertEquals("login", AuthReturn.Pending.parse("{\"state\":\"s\",\"verifier\":\"v\",\"at\":1}")!!.flow)
    }

    // ---------- what /oauth/redeem answers ----------

    private fun user() = JSONObject().put("id", "u1").put("email", "a@b.c").put("name", "A").put("providers", JSONArray().put("google"))

    @Test fun sessionOnRedeem() {
        val b = JSONObject().put("kind", "session").put("provider", "google").put("token", "t1").put("user", user())
        val r = AuthReturn.redeemed(200, b, 0L, "google")
        assertTrue(r is AuthReturn.Redeemed.Session)
        assertEquals("t1", (r as AuthReturn.Redeemed.Session).body.optString("token"))
    }

    @Test fun twoStepOnRedeemGoesToTheCodeScreen() {
        // blaze-auth 0.9.1+: 401 {error:"mfa_required", mfa_token, methods, email_hint} from signInOrMfa in redeem().
        val b = JSONObject().put("error", "mfa_required").put("mfa_token", "mfa_" + "x".repeat(30))
            .put("methods", JSONArray().put("app").put("email").put("recovery")).put("email_hint", "a***@b.c")
        val r = AuthReturn.redeemed(401, b, 10L, "github")
        assertTrue(r is AuthReturn.Redeemed.TwoStep)
        val p = (r as AuthReturn.Redeemed.TwoStep).pending
        assertEquals("mfa_" + "x".repeat(30), p.token)
        assertEquals("github", p.via)
        assertEquals(listOf("app", "email", "recovery"), p.methods)
        assertEquals("a***@b.c", p.emailHint)
        assertFalse(p.autoSend)
    }

    @Test fun emailOnlyTwoStepSendsTheCodeByItself() {
        val b = JSONObject().put("error", "mfa_required").put("mfa_token", "mfa_" + "y".repeat(30)).put("methods", JSONArray().put("email").put("recovery"))
        val p = (AuthReturn.redeemed(401, b, 0L, "google") as AuthReturn.Redeemed.TwoStep).pending
        assertTrue(p.autoSend)
    }

    @Test fun failuresAreNeverSilent() {
        assertEquals(AuthReturn.Redeemed.Failed(400, "invalid_code"), AuthReturn.redeemed(400, JSONObject().put("error", "invalid_code"), 0L, "google"))
        assertEquals(AuthReturn.Redeemed.Failed(401, "mfa_required"), AuthReturn.redeemed(401, JSONObject().put("error", "mfa_required"), 0L, "google"))   // no token
        assertEquals(AuthReturn.Redeemed.Failed(500, "http_500"), AuthReturn.redeemed(500, null, 0L, "google"))
        assertEquals(AuthReturn.Redeemed.Failed(502, "no_session"), AuthReturn.redeemed(200, JSONObject().put("kind", "session").put("token", "t"), 0L, "google"))
        assertEquals(AuthReturn.Redeemed.Failed(502, "no_session"), AuthReturn.redeemed(200, JSONObject(), 0L, "google"))
        assertTrue(AuthReturn.redeemed(200, JSONObject().put("kind", "tokens").put("tokens", JSONObject()), 0L, "google") is AuthReturn.Redeemed.Tokens)
    }

    // ---------- does the server take blaze://auth/cb? (else the 127.0.0.1 fallback) ----------

    @Test fun serverFlag() {
        // Live /v1/providers on blaze-auth 0.9.5 (2026-10-20).
        val live = JSONObject("{\"version\":\"0.9.5\",\"login\":{\"email\":true,\"google\":true,\"github\":true},\"features\":{\"app_redirect\":true},\"app_redirect\":\"blaze://auth/cb\"}")
        assertTrue(AuthReturn.serverAllows(live))
        assertTrue(AuthReturn.serverAllows(JSONObject().put("app_redirect", "blaze://auth/cb")))
        assertTrue(AuthReturn.serverAllows(JSONObject().put("app_redirect", true)))
        assertFalse(AuthReturn.serverAllows(JSONObject().put("app_redirect", "blaze://other")))
        assertFalse(AuthReturn.serverAllows(JSONObject().put("version", "0.9.3")))
        assertFalse(AuthReturn.serverAllows(null))
        assertEquals(true, BlazeAccount.featureFlags(live)["app_redirect"])
        assertEquals(false, BlazeAccount.featureFlags(JSONObject())["app_redirect"])
    }

    @Test fun browserOutcomesInPlainWords() {
        for (c in listOf("signin_expired", "state_mismatch", "missing_code", "no_session", "mfa_required")) {
            val m = BlazeAccount.message(BlazeAccount.ApiError(400, c))
            assertFalse(m, m.contains(c))
        }
        assertTrue(BlazeAccount.OPEN_APP.contains("package=com.blaze.assistant;"))
        assertFalse(BlazeAccount.OPEN_APP.contains("code") || BlazeAccount.OPEN_APP.contains("state"))
    }
}
