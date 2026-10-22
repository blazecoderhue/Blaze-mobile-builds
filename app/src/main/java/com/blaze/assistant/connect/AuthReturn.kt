package com.blaze.assistant.connect

import org.json.JSONObject

/**
 * v0.45.4: browser sign-in (Google / GitHub) coming back to the app as blaze://auth/cb?code=..&state=..
 * (blaze-auth 0.9.5 accepts that exact redirect and advertises features.app_redirect in /v1/providers).
 *
 * Why: until 0.45.3 the app listened on 127.0.0.1 and redeemed the one-time code while the browser was still in front.
 * The browser showed "You're signed in", but the redeem ran with Blaze in the background and its outcome (an error, or
 * nothing at all when Android had frozen or closed Blaze) never reached the first-run screen. Now the browser opens the
 * app itself, and the redeem runs with Blaze in front, from the saved [Pending] if Android ended Blaze meanwhile.
 *
 * Pure (no Android types), so the whole flow is unit-tested: link parsing and the state check, the saved pending
 * sign-in, and what a redeem answer means (a session, the two-step code screen, or a plain error).
 */
object AuthReturn {
    const val REDIRECT = "blaze://auth/cb"
    /** The server keeps its sign-in state 10 minutes; the app keeps its half as long. */
    const val TTL_MS = 600_000L

    /** A browser sign-in waiting for blaze://auth/cb. Saved (encrypted) on disk so it survives Android ending Blaze. */
    data class Pending(val state: String, val verifier: String, val provider: String, val flow: String, val at: Long) {
        fun json(): String = JSONObject().put("state", state).put("verifier", verifier).put("provider", provider)
            .put("flow", flow).put("at", at).toString()
        fun fresh(now: Long): Boolean = now - at in 0..TTL_MS
        companion object {
            fun parse(s: String?): Pending? {
                if (s.isNullOrBlank()) return null
                return runCatching {
                    val o = JSONObject(s)
                    Pending(o.optString("state"), o.optString("verifier"), o.optString("provider"), o.optString("flow", "login"), o.optLong("at", -1L))
                }.getOrNull()?.takeIf { it.state.isNotEmpty() && it.verifier.isNotEmpty() && it.at >= 0 }
            }
        }
    }

    /** Where the pending sign-in is kept (the app: an encrypted setting; tests: a map). */
    interface Store { fun get(): String; fun set(v: String) }

    fun save(store: Store, p: Pending) = store.set(p.json())
    fun clear(store: Store) = store.set("")
    /** The saved sign-in if it's still fresh; a stale one is dropped. */
    fun load(store: Store, now: Long): Pending? {
        val p = Pending.parse(store.get()) ?: return null
        if (!p.fresh(now)) { clear(store); return null }
        return p
    }

    sealed class Return {
        data class Code(val code: String) : Return()
        data class Failed(val error: String) : Return()
        /** Not ours: another link, a stale tab or a forged state. Ignored, the pending sign-in stays. */
        object Mismatch : Return()
    }

    /** True for blaze://auth/cb links (any query). */
    fun isAuthLink(url: String?): Boolean {
        val u = url?.trim() ?: return false
        if (!u.regionMatches(0, REDIRECT, 0, REDIRECT.length, ignoreCase = true)) return false
        val rest = u.substring(REDIRECT.length)
        return rest.isEmpty() || rest[0] == '?' || rest[0] == '/' || rest[0] == '#'
    }

    /** The query parameters of [url], percent-decoded. */
    fun query(url: String): Map<String, String> {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in q.split('&')) {
            if (part.isEmpty()) continue
            val k = part.substringBefore('='); val v = part.substringAfter('=', "")
            val dk = runCatching { java.net.URLDecoder.decode(k, "UTF-8") }.getOrDefault(k)
            if (dk !in out) out[dk] = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
        return out
    }

    /** What a returned link means for the sign-in that expects [expectState]. */
    fun parse(url: String?, expectState: String): Return {
        if (url == null || expectState.isEmpty() || !isAuthLink(url)) return Return.Mismatch
        val q = query(url)
        if (q["state"] != expectState) return Return.Mismatch
        val e = q["error"].orEmpty(); val c = q["code"].orEmpty()
        return if (e.isNotEmpty() || c.isEmpty()) Return.Failed(e.ifEmpty { "missing_code" }.take(64)) else Return.Code(c)
    }

    /** What a /oauth/redeem answer means. */
    sealed class Redeemed {
        data class Session(val body: JSONObject) : Redeemed()
        data class Tokens(val body: JSONObject) : Redeemed()
        /** 401 mfa_required: the account has two-step verification; go to the code screen. */
        data class TwoStep(val pending: Mfa.Pending) : Redeemed()
        data class Failed(val status: Int, val code: String) : Redeemed()
    }

    /**
     * blaze-auth /oauth/redeem: 200 {kind:"session", provider, token, user} for a login, 200 {kind:"tokens", ...} for a
     * connector, 401 {error:"mfa_required", mfa_token, methods, email_hint} when two-step is on, else {error}.
     */
    fun redeemed(status: Int, body: JSONObject?, now: Long, via: String): Redeemed {
        val b = body ?: JSONObject()
        Mfa.pendingOf(status, b, now, via)?.let { return Redeemed.TwoStep(it) }
        if (status !in 200..299) return Redeemed.Failed(status, b.optString("error").ifBlank { "http_$status" })
        return when (b.optString("kind")) {
            "session" -> if (b.optString("token").isNotBlank() && b.optJSONObject("user")?.optString("id").orEmpty().isNotBlank())
                Redeemed.Session(b) else Redeemed.Failed(502, "no_session")
            "tokens" -> Redeemed.Tokens(b)
            else -> Redeemed.Failed(502, "no_session")
        }
    }

    /** The server takes blaze://auth/cb (0.9.5: features.app_redirect, or app_redirect = the exact URL / true). */
    fun serverAllows(providers: JSONObject?): Boolean {
        val p = providers ?: return false
        if (p.optJSONObject("features")?.optBoolean("app_redirect", false) == true) return true
        return when (val v = p.opt("app_redirect")) { is Boolean -> v; is String -> v == REDIRECT; else -> false }
    }
}
