package com.blaze.assistant.connect

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Blaze accounts, the same ones Blaze Desktop uses, through blaze-auth (the free Cloudflare
 * Worker in the Blaze-dekstop repo, cloud/). Email + password, or Sign in with Google / GitHub
 * in the browser (PKCE, redirect caught on a random 127.0.0.1 port, one-time code redeemed with
 * our verifier). The session token is Keystore-encrypted ("conn_tok_" prefs are secrets); the
 * signed-in user's name and email are cached for the UI.
 */
object BlazeAccount {
    const val DEFAULT_BASE = "https://blaze-auth.blaze-app.workers.dev"
    private const val SESSION = "conn_tok_blaze_session"
    private const val USER = "blaze_account_user"
    private const val CLIENT = "Blaze Android"

    data class User(val id: String, val email: String, val name: String, val avatarUrl: String,
                    val emailVerified: Boolean, val hasPassword: Boolean, val providers: List<String>,
                    val twoFactor: Boolean = false, val twoFactorApp: Boolean = twoFactor, val twoFactorEmail: Boolean = false) {
        fun json(): JSONObject = JSONObject().put("id", id).put("email", email).put("name", name).put("avatar_url", avatarUrl)
            .put("email_verified", emailVerified).put("has_password", hasPassword).put("providers", JSONArray(providers))
            .put("two_factor", twoFactor).put("two_factor_methods", JSONObject().put("app", twoFactorApp).put("email", twoFactorEmail))
        companion object {
            fun from(o: JSONObject?): User? {
                if (o == null || o.optString("id").isBlank()) return null
                val p = o.optJSONArray("providers")
                return User(o.optString("id"), o.optString("email"), o.optString("name"), o.optString("avatar_url"),
                    o.optBoolean("email_verified"), o.optBoolean("has_password"),
                    (0 until (p?.length() ?: 0)).map { p!!.optString(it) }, o.optBoolean("two_factor"),
                    // 0.9.1: two_factor_methods {app, email}; 0.9.0 had only the app, so two_factor means the app there.
                    o.optJSONObject("two_factor_methods")?.optBoolean("app") ?: o.optBoolean("two_factor"),
                    o.optJSONObject("two_factor_methods")?.optBoolean("email") ?: false)
            }
        }
    }

    /** What Settings › Account draws. */
    data class View(val user: User? = null, val online: Boolean = false, val login: Map<String, Boolean> = emptyMap(),
                    val connectors: Map<String, Boolean> = emptyMap(), val pending: String = "", val message: String = "",
                    /** v0.45.3: a sign-in waiting for its 6-digit / recovery code (blaze-auth 0.9.0 2FA). */
                    val mfa: Mfa.Pending? = null,
                    /** v0.45.3: /v1/providers feature flags (web_login, password_reset, two_factor); missing = off. */
                    val features: Map<String, Boolean> = emptyMap()) {
        val signedIn get() = user != null
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checked = 0L
    @Volatile private var signInJob: Job? = null
    @Volatile private var server: ServerSocket? = null

    /** BLAZE cloud override for local testing (Settings str "blaze_cloud_url"), else the deployed Worker. */
    fun base(): String = Settings.str("blaze_cloud_url").trim().trimEnd('/')
        .takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: DEFAULT_BASE

    fun signedIn() = _view.value.user != null
    /** The session token (Keystore-encrypted at rest), "" when signed out. */
    internal fun sessionToken(): String = Settings.secret(SESSION)
    fun user() = _view.value.user

    /** Call once at app start: shows the cached user straight away, then checks the session and providers. */
    fun start() {
        val cached = Settings.str(USER).takeIf { it.isNotBlank() }?.let { runCatching { User.from(JSONObject(it)) }.getOrNull() }
        // v0.45.4: Android ended Blaze while the browser sign-in was open: keep saying so until blaze://auth/cb lands.
        val waiting = runCatching { AuthReturn.load(pendingStore, System.currentTimeMillis()) }.getOrNull()?.takeIf { it.flow == "login" && cached == null }
        _view.value = _view.value.copy(user = cached, pending = waiting?.provider.orEmpty())
        scope.launch { refreshProviders(true); checkSession(); KeySync.syncIfStale(); SkillSync.syncIfStale() }
    }

    // ---------------- errors ----------------

    class ApiError(val status: Int, val code: String, val body: JSONObject = JSONObject()) : Exception(code)
    /** v0.45.3: the password / browser sign-in was right, but the account has two-step verification on. */
    class MfaRequired(val mfaToken: String, val body: JSONObject = JSONObject()) : Exception("mfa_required")
    class Unreachable(cause: Exception) : Exception(cause.message, cause)
    object NotConfigured : Exception("not_configured")
    object Cancelled : Exception("cancelled")

    fun message(e: Throwable): String = when (e) {
        is NotConfigured -> "That sign-in isn't switched on yet. Use your email instead."
        is Cancelled -> "Sign-in was cancelled."
        is Unreachable -> "Couldn't reach Blaze's sign-in service. Check your internet connection."
        is ApiError -> when (e.code) {
            "invalid_credentials" -> "That email and password don't match."
            "email_taken" -> "There's already a Blaze account with that email. Sign in instead."
            "invalid_email" -> "That doesn't look like an email address."
            "invalid_password" -> "Use a password of at least 8 characters."
            "rate_limited" -> "Too many tries. Wait a few minutes and try again."
            "unauthorized" -> "You're signed out. Sign in again."
            "access_denied" -> "You declined access in the browser. Nothing changed."
            "invalid_code" -> "That code didn't work. Check the time on your phone and try the newest code."
            "invalid_mfa_token" -> Mfa.EXPIRED
            // v0.45.4: browser sign-in outcomes in plain words (never a raw code on the first-run screen).
            "signin_expired" -> "That sign-in took too long or was already used. Tap Continue again."
            "state_mismatch" -> "That sign-in page belonged to an older try. Tap Continue again."
            "missing_code" -> "The sign-in came back without finishing. Tap Continue again."
            "no_session" -> "Signed in, but Blaze didn't get your session. Tap Continue again."
            "mfa_required" -> "This account uses two-step verification. Start the sign-in again to enter your code."
            "current_password_required" -> "Enter your current password too."
            "already_enabled" -> "Two-step verification is already on."
            "not_enabled" -> "Two-step verification is already off."
            "setup_required" -> "Start the set-up again: tap Turn on."
            else -> "Sign-in failed (${e.code})."
        }
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------------- HTTP ----------------

    private fun call(method: String, path: String, bearer: String = "", body: JSONObject? = null): JSONObject {
        val (code, o) = callRaw(method, path, bearer, body)
        if (code >= 400) {
            Mfa.tokenOf(code, o)?.let { throw MfaRequired(it, o) }
            val err = o.optString("error").ifBlank { "http_$code" }
            if (err == "not_configured") throw NotConfigured
            throw ApiError(code, err, o)
        }
        return o
    }

    /** One request: (status, JSON body or {}). Network failures throw [Unreachable]. */
    private fun callRaw(method: String, path: String, bearer: String = "", body: JSONObject? = null): Pair<Int, JSONObject> {
        UrlSafety.check(base() + path)   // v0.28.2: a self-hosted account server on http:// must be on the LAN
        val c = try { UrlSafety.open(base() + path) } catch (e: Exception) { throw Unreachable(e) }
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "Blaze-Android")
            if (bearer.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        } catch (e: java.io.IOException) {
            throw Unreachable(e)
        } finally { c.disconnect() }
    }

    private fun boolMap(o: JSONObject?): Map<String, Boolean> =
        o?.keys()?.asSequence()?.associateWith { o.optBoolean(it) } ?: emptyMap()

    /** Which sign-ins and one-click connectors the Worker has switched on (cached 10 minutes). */
    fun refreshProviders(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - checked < 600_000) return
        checked = System.currentTimeMillis()
        try {
            val p = call("GET", "/v1/providers")
            _view.value = _view.value.copy(online = true, login = boolMap(p.optJSONObject("login")), connectors = boolMap(p.optJSONObject("connectors")),
                features = featureFlags(p))
        } catch (_: Exception) {
            _view.value = _view.value.copy(online = false)
        }
    }

    /** Confirms a saved session is still good (signed out elsewhere, account deleted…). */
    fun checkSession() {
        val tok = Settings.secret(SESSION)
        if (tok.isBlank()) { if (_view.value.user != null) setSession(null, null); return }
        try {
            User.from(call("GET", "/v1/me", tok).optJSONObject("user"))?.let { setSession(tok, it) }
        } catch (e: ApiError) {
            if (e.status == 401) setSession(null, null)
        } catch (_: Exception) { /* offline: keep the cached user */ }
    }

    /** v0.45.3: blaze-auth 0.9.0 flags; an older server has none, so every new feature stays hidden. */
    internal fun featureFlags(p: JSONObject): Map<String, Boolean> {
        val f = listOf("web_login", "password_reset", "two_factor").associateWith { p.optBoolean(it, false) }.toMutableMap()
        // v0.45.4: blaze-auth 0.9.5 takes blaze://auth/cb as the browser sign-in redirect.
        f["app_redirect"] = AuthReturn.serverAllows(p)
        // 0.9.1: two_factor_methods {app, email: mailer configured}; 0.9.0 = app only.
        val m = p.optJSONObject("two_factor_methods")
        f["two_factor_app"] = f["two_factor"] == true && (m?.optBoolean("app", true) ?: true)
        f["two_factor_email"] = f["two_factor"] == true && (m?.optBoolean("email", false) ?: false)
        return f
    }

    private fun setSession(token: String?, user: User?) {
        if (token == null || user == null) {
            Settings.setSecret(SESSION, ""); Settings.setStr(USER, "")
            _view.value = _view.value.copy(user = null)
            return
        }
        val fresh = Settings.secret(SESSION) != token
        Settings.setSecret(SESSION, token); Settings.setStr(USER, user.json().toString())
        _view.value = _view.value.copy(user = user)
        if (fresh) { KeySync.syncSoon(); SkillSync.syncSoon() }   // just signed in: bring this account's model keys here, and ours there
    }

    private fun takeSession(o: JSONObject) {
        val tok = o.optString("token"); val u = User.from(o.optJSONObject("user"))
        if (tok.isBlank() || u == null) throw ApiError(502, "no_session")
        setSession(tok, u)
    }

    // ---------------- email + password ----------------

    /** Signs in, or creates an account when [signUp]. Returns "" on success, else a plain sentence. */
    suspend fun email(signUp: Boolean, name: String, email: String, password: String): String = withContext(Dispatchers.IO) {
        val e = email.trim()
        if (e.isEmpty() || password.isEmpty()) return@withContext "Enter your email and password."
        if (signUp && password.length < 8) return@withContext "Use a password of at least 8 characters."
        try {
            val body = JSONObject().put("email", e).put("password", password).put("client", CLIENT)
            if (signUp) body.put("name", name.trim())
            takeSession(call("POST", if (signUp) "/v1/auth/signup" else "/v1/auth/login", body = body))
            ""
        } catch (m: MfaRequired) {
            startMfa(m, "email"); ""
        } catch (x: Exception) { message(x) }
    }

    // ---------------- two-step verification (v0.45.3, blaze-auth 0.9.0) ----------------

    private fun startMfa(m: MfaRequired, via: String, ready: Mfa.Pending? = null) {
        val now = System.currentTimeMillis()
        val p = ready ?: Mfa.pendingOf(401, m.body, now, via) ?: Mfa.Pending(m.mfaToken, now, via = via)
        _view.value = _view.value.copy(mfa = p, pending = "", message = "")
        // 0.9.1: no authenticator app on the account, so the code can only come by email: send it now.
        if (p.autoSend) scope.launch { sendMfaEmail() }
    }

    /** Emails a sign-in code for the pending step; the outcome is also put in [View.message] for the code step. */
    suspend fun sendMfaEmail(): String = withContext(Dispatchers.IO) {
        val p = _view.value.mfa ?: return@withContext Mfa.EXPIRED
        val msg = when (val r = Mfa.sendEmail(p, System.currentTimeMillis()) { path, b -> callRaw("POST", path, body = b) }) {
            is Mfa.Outcome.Retry -> { if (_view.value.mfa?.token == p.token) _view.value = _view.value.copy(mfa = r.pending); r.message }
            is Mfa.Outcome.Restart -> { cancelMfa(r.message); return@withContext r.message }
            is Mfa.Outcome.Error -> r.message
            is Mfa.Outcome.Signed -> ""
        }
        if (_view.value.mfa?.token == p.token) _view.value = _view.value.copy(message = msg)
        msg
    }

    /** Leaves the code step (Back, or the 5 minutes ran out on screen). */
    fun cancelMfa(message: String = "") { _view.value = _view.value.copy(mfa = null, message = message) }

    /** Sends the code of the pending sign-in. "" when signed in; otherwise a plain sentence (the step may have ended). */
    suspend fun verifyMfa(code: String, recovery: Boolean): String = withContext(Dispatchers.IO) {
        val p = _view.value.mfa ?: return@withContext Mfa.EXPIRED
        when (val r = Mfa.verify(p, code, recovery, System.currentTimeMillis()) { path, b -> callRaw("POST", path, body = b.put("client", CLIENT)) }) {
            is Mfa.Outcome.Signed -> {
                try { takeSession(r.body) } catch (x: Exception) { return@withContext message(x) }
                _view.value = _view.value.copy(mfa = null, message = "Signed in as " + (_view.value.user?.email ?: ""))
                ""
            }
            is Mfa.Outcome.Retry -> { if (_view.value.mfa?.token == p.token) _view.value = _view.value.copy(mfa = r.pending); r.message }
            is Mfa.Outcome.Restart -> { cancelMfa(r.message); r.message }
            is Mfa.Outcome.Error -> r.message
        }
    }

    // ---------------- password + 2FA settings (v0.45.3) ----------------

    private fun authed(): String = Settings.secret(SESSION).ifBlank { throw ApiError(401, "unauthorized") }

    private fun takeUser(o: JSONObject) {
        User.from(o.optJSONObject("user"))?.let { u -> Settings.setStr(USER, u.json().toString()); _view.value = _view.value.copy(user = u) }
    }

    /** Sets (no password yet) or changes the password. "" on success. */
    suspend fun setPassword(password: String, current: String): String = withContext(Dispatchers.IO) {
        if (password.length < 8) return@withContext "Use a password of at least 8 characters."
        try {
            val b = JSONObject().put("password", password)
            if (current.isNotEmpty()) b.put("current_password", current)
            takeUser(call("POST", "/v1/auth/password/set", authed(), b)); ""
        } catch (x: Exception) { message(x) }
    }

    /** Asks for a reset email. The server answers 200 whether or not the address has an account. */
    suspend fun forgotPassword(email: String): String = withContext(Dispatchers.IO) {
        val e = email.trim()
        if (e.isEmpty()) return@withContext "Enter your email first."
        try {
            call("POST", "/v1/auth/password/forgot", body = JSONObject().put("email", e))
            "If $e has a Blaze account, a link to set a new password is on its way. It works for 1 hour."
        } catch (x: ApiError) { if (x.status == 503) "Password reset isn't switched on yet." else message(x) }
        catch (x: Exception) { message(x) }
    }

    /** First step of turning 2FA on: (secret, otpauth URI) for the authenticator app. */
    suspend fun mfaSetup(): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching { call("POST", "/v1/auth/2fa/setup", authed(), JSONObject()).let { it.optString("secret") to it.optString("otpauth_uri") } }
            .recoverCatching { throw Exception(message(it)) }
    }

    /** Confirms the first code; returns the recovery codes (shown once). */
    suspend fun mfaEnable(code: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val c = Mfa.clean(code, false) ?: throw Exception("Enter the 6-digit code from your authenticator app.")
            val o = call("POST", "/v1/auth/2fa/enable", authed(), JSONObject().put("code", c))
            takeUser(o)
            val a = o.optJSONArray("recovery_codes")
            (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
        }.recoverCatching { throw Exception(if (it is ApiError || it is Unreachable) message(it) else it.message) }
    }

    /** 0.9.1: emails a code that confirms turning emailed sign-in codes on ([purpose] "enable") or off ("disable"). Ok = where it went. */
    suspend fun emailCodeSend(purpose: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { call("POST", "/v1/auth/2fa/email/send", authed(), JSONObject().put("purpose", if (purpose == "disable") "disable" else "enable")).optString("sent_to") }
            .recoverCatching { throw Exception(if (it is ApiError && it.code == "mail_failed") "The email couldn't be sent. Try again in a moment."
                else if (it is ApiError && it.status == 503) "Codes by email aren't switched on yet." else message(it)) }
    }

    /** 0.9.1: turns emailed sign-in codes on / off with the emailed code (off also takes an app code). "" on success. */
    suspend fun emailCodeSet(on: Boolean, code: String): String = withContext(Dispatchers.IO) {
        val c = Mfa.clean(code, false) ?: return@withContext "Enter the 6-digit code."
        try { takeUser(call("POST", if (on) "/v1/auth/2fa/email/enable" else "/v1/auth/2fa/email/disable", authed(), JSONObject().put("code", c))); "" }
        catch (x: Exception) { message(x) }
    }

    /** Turns 2FA off with a current code or a recovery code. "" on success. */
    suspend fun mfaDisable(code: String): String = withContext(Dispatchers.IO) {
        val c = code.replace(Regex("\\s"), "")
        if (c.isEmpty()) return@withContext "Enter a code from your authenticator app or a recovery code."
        try { takeUser(call("POST", "/v1/auth/2fa/disable", authed(), JSONObject().put("code", c))); "" } catch (x: Exception) { message(x) }
    }

    // ---------------- Sign in with Google / GitHub ----------------

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun rand(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    /** Starts the browser sign-in. The outcome lands in [view] (user, or message). Returns "" when the browser opened. */
    fun signInWith(ctx: Context, provider: String): String {
        if (provider != "google" && provider != "github") return "Blaze can't sign in with $provider."
        val v = _view.value
        if (v.login[provider] != true) {
            return if (!v.online) "Couldn't reach Blaze's sign-in service. Check your internet connection."
            else "Sign in with ${title(provider)} isn't switched on yet. Use your email instead."
        }
        cancel()
        _view.value = _view.value.copy(pending = provider, message = "")
        val app = ctx.applicationContext
        signInJob = scope.launch {
            val msg = try {
                // v0.45.4: the redeem's answer decides: a session, the two-step code screen, or a plain error.
                when (val r = AuthReturn.redeemed(200, authorize(app, "/v1/auth/$provider/start", provider = provider), System.currentTimeMillis(), provider)) {
                    is AuthReturn.Redeemed.Session -> { takeSession(r.body); bringToFront(app); "Signed in as " + (_view.value.user?.email ?: "") }
                    is AuthReturn.Redeemed.TwoStep -> { startMfa(MfaRequired(r.pending.token, JSONObject()), provider, r.pending); bringToFront(app); return@launch }
                    is AuthReturn.Redeemed.Tokens -> throw ApiError(502, "no_session")
                    is AuthReturn.Redeemed.Failed -> throw ApiError(r.status, r.code)
                }
            } catch (m: MfaRequired) {
                startMfa(m, provider); bringToFront(app); return@launch
            } catch (e: Exception) {
                if (!isActive) return@launch   // superseded or cancelled: say nothing
                message(browserError(e))
            }
            _view.value = _view.value.copy(pending = "", message = msg)
        }
        return ""
    }

    /** Stops a browser sign-in in progress. */
    fun cancel() {
        signInJob?.cancel(); signInJob = null
        runCatching { server?.close() }; server = null
        waiter?.cancel(); waiter = null; waitState = ""
        AuthReturn.clear(pendingStore)
        if (_view.value.pending.isNotEmpty()) _view.value = _view.value.copy(pending = "")
    }

    /**
     * Listen on 127.0.0.1:<random>, open the Worker's start URL, wait up to 10 minutes for the
     * redirect back with our state, then redeem the one-time code with the PKCE verifier.
     */
    internal suspend fun authorize(ctx: Context, startPath: String, scopes: String = "", provider: String = ""): JSONObject {
        val verifier = b64(rand(32))
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = b64(rand(24))
        // v0.45.4: the browser comes back to the app (blaze://auth/cb) when the server takes it; 127.0.0.1 stays the fallback.
        if (_view.value.features["app_redirect"] == true) return authorizeApp(ctx, startPath, scopes, provider, verifier, challenge, state)
        val srv = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).also { server = it }
        try {
            srv.soTimeout = 1_000
            val redirect = "http://127.0.0.1:${srv.localPort}/cb"
            val url = base() + startPath + "?" + Http.form("state" to state, "code_challenge" to challenge,
                "code_challenge_method" to "S256", "redirect" to redirect) + (if (scopes.isNotBlank()) "&scope=" + Http.enc(scopes) else "")
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val until = System.currentTimeMillis() + 600_000
            var code: String? = null
            var err: String? = null
            while (code == null && err == null) {
                if (!kotlin.coroutines.coroutineContext.isActive || srv.isClosed) throw Cancelled
                if (System.currentTimeMillis() > until) throw Cancelled
                val sock = try { srv.accept() } catch (_: SocketTimeoutException) { continue } catch (_: java.net.SocketException) { throw Cancelled }
                sock.use { s ->
                    s.soTimeout = 5_000   // browsers open speculative connections that never send a request
                    val line = runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
                    val target = line.split(" ").getOrElse(1) { "" }
                    if (!target.startsWith("/cb")) { reply(s, 404, page(false, "Not found", "")); return@use }
                    val q = Uri.parse("http://x$target")
                    if (q.getQueryParameter("state") != state) {
                        reply(s, 200, page(false, "Sign-in didn't finish", "That link doesn't match the sign-in Blaze started. Start again from Blaze."))
                        return@use
                    }
                    val e = q.getQueryParameter("error").orEmpty()
                    val c = q.getQueryParameter("code").orEmpty()
                    when {
                        e.isNotEmpty() || c.isEmpty() -> {
                            err = e.ifEmpty { "missing_code" }
                            reply(s, 200, page(false, "Sign-in didn't finish",
                                if (err == "access_denied") "You declined access. Nothing changed; you can close this tab."
                                else "The sign-in came back with an error ($err). Go back to Blaze and try again."))
                        }
                        else -> { code = c; reply(s, 200, page(true, "You're signed in", "Go back to Blaze to finish.", openApp = true)) }
                    }
                }
            }
            err?.let { if (it == "not_configured") throw NotConfigured else throw ApiError(400, it) }
            return redeemRaw(code!!, verifier)
        } finally {
            runCatching { srv.close() }
            if (server === srv) server = null
        }
    }

    private fun reply(s: java.net.Socket, status: Int, html: String) {
        val b = html.toByteArray()
        runCatching {
            s.getOutputStream().write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray() + b)
            s.getOutputStream().flush()
        }
    }

    private fun page(ok: Boolean, title: String, msg: String, openApp: Boolean = false): String {
        fun esc(x: String) = x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val mark = if (ok) "#1F4BD8" else "#111110"
        return "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Blaze</title>" +
            "<body style=\"font:15px/1.5 system-ui,sans-serif;background:#FBFBFA;color:#111110;display:grid;place-items:center;min-height:100vh;margin:0\">" +
            "<div style=\"max-width:440px;margin:16px;padding:32px;border:1px solid rgba(17,17,16,.18);background:#fff;box-shadow:inset 3px 0 0 $mark\">" +
            "<div style=\"font:500 11px monospace;letter-spacing:.12em;text-transform:uppercase;color:$mark\">Blaze</div>" +
            "<h2 style=\"margin:8px 0 6px;font-weight:600\">${esc(title)}</h2><p style=\"margin:0;color:rgba(17,17,16,.6)\">${esc(msg)}</p>" +
            (if (openApp) "<a href=\"$OPEN_APP\" style=\"display:inline-block;margin-top:18px;padding:12px 20px;background:$mark;color:#fff;text-decoration:none;font-weight:600\">Open Blaze</a>" else "") +
            "</div></body>"
    }

    /** Chrome / Samsung Internet open Blaze from this link (the running app, nothing in it). */
    internal const val OPEN_APP = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=com.blaze.assistant;end"

    // ---------------- app redirect (v0.45.4): blaze://auth/cb ----------------

    private const val PENDING = "conn_tok_blaze_auth_pending"   // "conn_tok_": Keystore-encrypted, never synced or backed up
    internal val pendingStore = object : AuthReturn.Store {
        override fun get() = Settings.secret(PENDING)
        override fun set(v: String) = Settings.setSecret(PENDING, v)
    }
    @Volatile private var waiter: kotlinx.coroutines.CompletableDeferred<String>? = null
    @Volatile private var waitState: String = ""

    /** POST /oauth/redeem: the raw answer (a 401 mfa_required comes back as a body, not an exception). */
    private fun redeemRaw(code: String, verifier: String): JSONObject {
        val (status, o) = callRaw("POST", "/oauth/redeem", body = JSONObject().put("code", code).put("code_verifier", verifier).put("client", CLIENT))
        if (status in 200..299) return o
        Mfa.tokenOf(status, o)?.let { throw MfaRequired(it, o) }
        val err = o.optString("error").ifBlank { "http_$status" }
        if (err == "not_configured") throw NotConfigured
        throw ApiError(status, err, o)
    }

    /** A redeem that failed on its one-time code means the sign-in expired (not "check the time on your phone"). */
    private fun browserError(e: Exception): Exception =
        if (e is ApiError && (e.code == "invalid_code" || e.code == "invalid_code_verifier")) ApiError(e.status, "signin_expired", e.body) else e

    private suspend fun authorizeApp(ctx: Context, startPath: String, scopes: String, provider: String,
                                     verifier: String, challenge: String, state: String): JSONObject {
        val flow = if (startPath.startsWith("/v1/auth/")) "login" else "connect"
        val w = kotlinx.coroutines.CompletableDeferred<String>()
        waiter = w; waitState = state
        AuthReturn.save(pendingStore, AuthReturn.Pending(state, verifier, provider, flow, System.currentTimeMillis()))
        try {
            val url = base() + startPath + "?" + Http.form("state" to state, "code_challenge" to challenge,
                "code_challenge_method" to "S256", "redirect" to AuthReturn.REDIRECT) + (if (scopes.isNotBlank()) "&scope=" + Http.enc(scopes) else "")
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val link = kotlinx.coroutines.withTimeoutOrNull(AuthReturn.TTL_MS) { w.await() } ?: throw Cancelled
            return when (val r = AuthReturn.parse(link, state)) {
                is AuthReturn.Return.Code -> redeemRaw(r.code, verifier)
                is AuthReturn.Return.Failed -> if (r.error == "not_configured") throw NotConfigured else throw ApiError(400, r.error)
                AuthReturn.Return.Mismatch -> throw ApiError(400, "state_mismatch")
            }
        } finally {
            if (waiter === w) { waiter = null; waitState = "" }
            AuthReturn.clear(pendingStore)
        }
    }

    /**
     * MainActivity hands every blaze://auth/cb link here. The sign-in still waiting takes it. If Android ended Blaze
     * while the browser was open, the saved sign-in is finished from here (login only; a connector's caller is gone).
     */
    fun onAppRedirect(ctx: Context, link: String) {
        val w = waiter
        if (w != null && AuthReturn.query(link)["state"] == waitState) { w.complete(link); return }
        val p = AuthReturn.load(pendingStore, System.currentTimeMillis()) ?: return
        if (p.flow != "login") { AuthReturn.clear(pendingStore); return }
        val r = AuthReturn.parse(link, p.state)
        if (r == AuthReturn.Return.Mismatch) return   // someone else's link: keep waiting for ours
        AuthReturn.clear(pendingStore)
        val via = p.provider.ifBlank { "browser" }
        _view.value = _view.value.copy(pending = p.provider, message = "")
        signInJob = scope.launch {
            val msg = try {
                when (r) {
                    is AuthReturn.Return.Code -> when (val d = AuthReturn.redeemed(200, redeemRaw(r.code, p.verifier), System.currentTimeMillis(), via)) {
                        is AuthReturn.Redeemed.Session -> { takeSession(d.body); "Signed in as " + (_view.value.user?.email ?: "") }
                        is AuthReturn.Redeemed.TwoStep -> { startMfa(MfaRequired(d.pending.token, JSONObject()), via, d.pending); return@launch }
                        is AuthReturn.Redeemed.Tokens -> throw ApiError(502, "no_session")
                        is AuthReturn.Redeemed.Failed -> throw ApiError(d.status, d.code)
                    }
                    is AuthReturn.Return.Failed -> throw if (r.error == "not_configured") NotConfigured else ApiError(400, r.error)
                    AuthReturn.Return.Mismatch -> return@launch
                }
            } catch (m: MfaRequired) {
                startMfa(m, via); return@launch
            } catch (e: Exception) { message(browserError(e)) }
            _view.value = _view.value.copy(pending = "", message = msg)
        }
    }

    private suspend fun bringToFront(app: Context) = withContext(Dispatchers.Main) {
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
            app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    // ---------------- sign out / delete ----------------

    /** Ends the session here and at the service (best effort when offline). */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val tok = Settings.secret(SESSION)
        if (tok.isNotBlank()) runCatching { call("POST", "/v1/logout", tok, JSONObject()) }
        setSession(null, null)
        _view.value = _view.value.copy(message = "")
    }

    /** Deletes the Blaze account. Connected accounts and keys on this phone stay. Returns "" or a plain sentence. */
    suspend fun delete(): String = withContext(Dispatchers.IO) {
        val tok = Settings.secret(SESSION)
        if (tok.isNotBlank()) try {
            call("POST", "/v1/account/delete", tok, JSONObject().put("confirm", "delete"))
        } catch (e: ApiError) {
            if (e.status != 401) return@withContext message(e)
        } catch (e: Exception) { return@withContext message(e) }
        setSession(null, null)
        ""
    }

    // ---------------- one-click connectors (the Worker holds the client secrets) ----------------

    /** True when the Worker can connect [id] with one click. */
    fun oneClick(id: String) = _view.value.connectors[id] == true

    /** Runs the browser Connect for [id]; returns the provider tokens (access_token, refresh_token, expires_in…). */
    suspend fun connectTokens(ctx: Context, id: String, scopes: String): JSONObject = withContext(Dispatchers.IO) {
        val r = authorize(ctx, "/oauth/${Uri.encode(id)}/start", scopes)
        val t = r.optJSONObject("tokens")
        if (r.optString("kind") != "tokens" || t == null || t.optString("access_token").isBlank()) throw ApiError(502, "no_token")
        t
    }

    /** Swaps a refresh token for new tokens through the Worker, or null when it refuses. */
    fun refreshTokens(id: String, refreshToken: String): JSONObject? = try {
        call("POST", "/oauth/${Uri.encode(id)}/refresh", body = JSONObject().put("refresh_token", refreshToken))
            .optJSONObject("tokens")?.takeIf { it.optString("access_token").isNotBlank() }
    } catch (_: Exception) { null }

    /** The Worker's version ("0.8.0"), or "" when it can't be reached. */
    fun workerVersion(): String = try { call("GET", "/health").optString("version") } catch (_: Exception) { "" }

    /** True when the Worker is at least [v] (dotted numbers). */
    fun workerAtLeast(v: String): Boolean {
        val have = workerVersion().split('.').map { it.toIntOrNull() ?: 0 }
        if (have.isEmpty() || have.all { it == 0 }) return false
        val want = v.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(have.size, want.size)) {
            val a = have.getOrElse(i) { 0 }; val b = want.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }

    fun clearMessage() { _view.value = _view.value.copy(message = "") }

    fun title(p: String) = when (p) { "github" -> "GitHub"; "google" -> "Google"; else -> p }
}
