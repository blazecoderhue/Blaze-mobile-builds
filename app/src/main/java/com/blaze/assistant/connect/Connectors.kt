package com.blaze.assistant.connect

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Accounts Blaze can use on your behalf. Three sign-in styles:
 *  LOOPBACK – OAuth 2.0 + PKCE in the browser, redirect caught on http://127.0.0.1:43117/callback (Google, Spotify)
 *  DEVICE   – GitHub device flow: type a short code on github.com
 *  TOKEN    – paste an access token (Notion internal integration, Slack app token)
 * One-click (v1.16): when blaze-auth has the provider switched on, Connect needs no developer app:
 * the Worker holds the client secret and hands tokens back through a PKCE-bound one-time code
 * (BlazeAccount.connectTokens), like Blaze Desktop. Your own client id still works and wins when set.
 * Manual setup (own client id / pasted token) was removed from the app; old connections still refresh.
 * Tokens are stored Keystore-encrypted.
 */
object Connectors {
    enum class Kind { LOOPBACK, DEVICE, TOKEN }

    data class Connector(val id: String, val name: String, val kind: Kind, val blurb: String,
                         val authUrl: String = "", val tokenUrl: String = "", val scopes: String = "",
                         val needsSecret: Boolean = false, val help: String, val setupUrl: String)

    const val PORT = 43117
    const val REDIRECT = "http://127.0.0.1:$PORT/callback"

    val all = listOf(
        Connector("google", "Google", Kind.LOOPBACK, "Gmail, Calendar, Drive, Contacts",
            "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
            "https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/contacts.readonly",
            needsSecret = true,
            help = "1. Open Google Cloud Console → APIs & Services, create a project.\n2. Enable Gmail API, Google Calendar API, Google Drive API and People API.\n3. OAuth consent screen: External, add yourself as a test user.\n4. Credentials → Create OAuth client ID → type \"Desktop app\".\n5. Paste the client ID and client secret here, then Connect.",
            setupUrl = "https://console.cloud.google.com/apis/credentials"),
        Connector("github", "GitHub", Kind.DEVICE, "Notifications, issues, pull requests, repos",
            "https://github.com/login/device/code", "https://github.com/login/oauth/access_token", "repo notifications read:user",
            help = "1. github.com → Settings → Developer settings → OAuth Apps → New OAuth App (any homepage/callback URL).\n2. Tick \"Enable Device Flow\".\n3. Paste the Client ID here, then Connect and enter the code Blaze shows.",
            setupUrl = "https://github.com/settings/developers"),
        Connector("spotify", "Spotify", Kind.LOOPBACK, "Play, pause, skip, search, now playing",
            "https://accounts.spotify.com/authorize", "https://accounts.spotify.com/api/token",
            "user-read-playback-state user-modify-playback-state user-read-currently-playing",
            help = "1. developer.spotify.com → Dashboard → Create app.\n2. Redirect URI: $REDIRECT\n3. Tick Web API, save, and paste the Client ID here.",
            setupUrl = "https://developer.spotify.com/dashboard"),
        Connector("notion", "Notion", Kind.TOKEN, "Search pages, add notes",
            help = "1. notion.so/my-integrations → New integration (Internal).\n2. Copy the Internal Integration Secret and paste it here.\n3. In Notion, open the pages Blaze may use → ••• → Connections → add your integration.",
            setupUrl = "https://www.notion.so/my-integrations"),
        Connector("slack", "Slack", Kind.TOKEN, "Read channels, post messages",
            help = "1. api.slack.com/apps → Create New App → From scratch.\n2. OAuth & Permissions → User Token Scopes: channels:read, channels:history, chat:write, users:read.\n3. Install to workspace and paste the User OAuth Token (xoxp-…) here.",
            setupUrl = "https://api.slack.com/apps"),
    )

    fun byId(id: String) = all.first { it.id == id }

    private val _state = MutableStateFlow<Map<String, String>>(emptyMap())
    /** id → status line ("Connected", "Waiting for browser…", an error). */
    val state: StateFlow<Map<String, String>> = _state
    private fun status(id: String, s: String) { _state.value = _state.value + (id to s) }

    fun clientId(id: String) = Settings.str("conn_client_$id")
    fun setClientId(id: String, v: String) = Settings.setStr("conn_client_$id", v.trim())
    fun clientSecret(id: String) = Settings.secret("conn_tok_${id}_secret")
    fun setClientSecret(id: String, v: String) = Settings.setSecret("conn_tok_${id}_secret", v.trim())

    private fun tokens(id: String): JSONObject? = Settings.secret("conn_tok_$id").takeIf { it.isNotBlank() }?.let { try { JSONObject(it) } catch (_: Exception) { null } }
    private fun saveTokens(id: String, o: JSONObject) = Settings.setSecret("conn_tok_$id", o.toString())
    fun connected(id: String) = tokens(id)?.optString("access_token")?.isNotBlank() == true
    fun connectedCount() = all.count { connected(it.id) }
    fun disconnect(id: String) { Settings.setSecret("conn_tok_$id", ""); status(id, "") }

    /** Paste-a-token connectors. */
    fun saveToken(id: String, token: String) {
        if (token.isBlank()) { disconnect(id); return }
        saveTokens(id, JSONObject().put("access_token", token.trim())); status(id, "Connected")
    }

    // ---------- Browser sign-in (PKCE + loopback) ----------

    private fun rand(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /**
     * Connect is OAuth through blaze-auth only, like Blaze Desktop (v1.16): no developer apps or
     * pasted tokens. Accounts connected the old way keep working until you disconnect them.
     */
    fun available(id: String) = BlazeAccount.oneClick(id)

    suspend fun connect(ctx: Context, id: String): String {
        val c = byId(id)
        if (available(id)) return oneClick(ctx, c).also { status(id, it) }
        withContext(Dispatchers.IO) { BlazeAccount.refreshProviders(true) }
        if (available(id)) return oneClick(ctx, c).also { status(id, it) }
        return (if (!BlazeAccount.view.value.online) "Couldn't reach Blaze's sign-in service. Check your internet connection."
                else "One-tap Connect for ${c.name} is coming soon.").also { status(id, it) }
    }

    /** The pre-1.16 developer-app flows, kept only so old connections can still refresh. */
    @Suppress("unused")
    private suspend fun legacyConnect(ctx: Context, id: String): String {
        val c = byId(id)
        if (clientId(id).isBlank()) return "Paste your client ID first.".also { status(id, it) }
        return try {
            when (c.kind) {
                Kind.LOOPBACK -> loopback(ctx, c)
                Kind.DEVICE -> deviceFlow(ctx, c)
                Kind.TOKEN -> "Paste the token instead."
            }.also { status(id, it) }
        } catch (e: Exception) { ("Couldn't connect: " + (e.message ?: e.javaClass.simpleName)).also { status(id, it) } }
    }

    private suspend fun oneClick(ctx: Context, c: Connector): String = try {
        status(c.id, "Waiting for you to sign in in the browser…")
        // GitHub "workflow" lets Blaze push the Actions file of an app it builds (android-app-builder);
        // only Workers from 0.8.0 accept it, so older ones keep getting the old scopes.
        val scopes = if (c.kind == Kind.TOKEN) "" else if (c.id == "github" && BlazeAccount.workerAtLeast("0.8.0")) c.scopes + " workflow" else scopesFor(c)
        val t = BlazeAccount.connectTokens(ctx, c.id, scopes)
        val exp = t.optLong("expires_in", 0)
        if (exp > 0) t.put("expires_at", System.currentTimeMillis() + exp * 1000)
        saveTokens(c.id, t.put("via", "blaze"))
        withContext(Dispatchers.Main) {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        }
        "Connected" + (t.optString("account").takeIf { it.isNotBlank() }?.let { " as $it" } ?: "")
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { BlazeAccount.message(e) }

    /** Google asks for Classroom only once the user switched it on (v0.16.0); nothing changes for anyone else. */
    fun scopesFor(c: Connector): String = if (c.id == "google" && Classroom.enabled) c.scopes + " " + Classroom.SCOPES else c.scopes

    private suspend fun loopback(ctx: Context, c: Connector): String = withContext(Dispatchers.IO) {
        val verifier = b64(rand(48))
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val st = b64(rand(16))
        val url = c.authUrl + "?" + Http.form("client_id" to clientId(c.id), "redirect_uri" to REDIRECT, "response_type" to "code",
            "scope" to scopesFor(c), "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to st) +
            (if (c.id == "google") "&access_type=offline&prompt=consent" else "")
        ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 300_000
            status(c.id, "Waiting for you to sign in in the browser…")
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val code = withTimeoutOrNull(300_000) {
                server.accept().use { sock ->
                    val line = sock.getInputStream().bufferedReader().readLine().orEmpty()   // GET /callback?code=…&state=… HTTP/1.1
                    val q = Uri.parse("http://x" + line.split(" ").getOrElse(1) { "" })
                    val ok = q.getQueryParameter("state") == st && q.getQueryParameter("code") != null
                    val html = "<html><body style='font-family:sans-serif;padding:40px'><h2>${if (ok) "Connected to ${c.name}" else "Sign-in didn't finish"}</h2><p>You can go back to Blaze.</p></body></html>"
                    sock.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html").toByteArray())
                    if (ok) q.getQueryParameter("code") else null
                }
            } ?: return@withContext "Sign-in didn't finish."
            val form = mutableListOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT,
                "client_id" to clientId(c.id), "code_verifier" to verifier)
            if (c.needsSecret) form += "client_secret" to clientSecret(c.id)
            val (status, body) = Http.req("POST", c.tokenUrl, Http.form(*form.toTypedArray()), mapOf("Accept" to "application/json"), "application/x-www-form-urlencoded")
            if (status !in 200..299) return@withContext "Token exchange failed ($status): ${body.take(160)}"
            val t = JSONObject(body).put("expires_at", System.currentTimeMillis() + JSONObject(body).optLong("expires_in", 3600) * 1000)
            saveTokens(c.id, t)
            withContext(Dispatchers.Main) {
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            }
            "Connected"
        }
    }

    /** GitHub device flow: shows a code, opens github.com/login/device, then polls for the token. */
    private suspend fun deviceFlow(ctx: Context, c: Connector): String = withContext(Dispatchers.IO) {
        val (s1, b1) = Http.req("POST", c.authUrl, Http.form("client_id" to clientId(c.id), "scope" to c.scopes),
            mapOf("Accept" to "application/json"), "application/x-www-form-urlencoded")
        if (s1 !in 200..299) return@withContext "GitHub said ($s1): ${b1.take(160)}"
        val d = JSONObject(b1)
        val userCode = d.getString("user_code"); var interval = d.optInt("interval", 5)
        status(c.id, "Enter code $userCode on GitHub (copied)")
        withContext(Dispatchers.Main) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("GitHub code", userCode))
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.optString("verification_uri", "https://github.com/login/device"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val until = System.currentTimeMillis() + d.optInt("expires_in", 900) * 1000L
        while (System.currentTimeMillis() < until) {
            kotlinx.coroutines.delay(interval * 1000L)
            val (_, b) = Http.req("POST", c.tokenUrl, Http.form("client_id" to clientId(c.id), "device_code" to d.getString("device_code"),
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"), mapOf("Accept" to "application/json"), "application/x-www-form-urlencoded")
            val o = JSONObject(b)
            when (o.optString("error")) {
                "" -> { saveTokens(c.id, o); return@withContext "Connected" }
                "authorization_pending" -> {}
                "slow_down" -> interval += 5
                else -> return@withContext "GitHub: " + o.optString("error_description", o.optString("error"))
            }
        }
        "The code expired. Try again."
    }

    /** A valid access token, refreshing it when it's about to expire. */
    fun access(id: String): String? {
        val t = tokens(id) ?: return null
        val exp = t.optLong("expires_at", 0)
        if (exp > 0 && exp < System.currentTimeMillis() + 60_000 && t.optString("refresh_token").isNotBlank() && t.optString("via") == "blaze") {
            val n = BlazeAccount.refreshTokens(id, t.getString("refresh_token")) ?: return null
            for (k in n.keys()) t.put(k, n.get(k))
            t.put("expires_at", System.currentTimeMillis() + n.optLong("expires_in", 3600) * 1000)
            saveTokens(id, t)
            return t.optString("access_token")
        }
        if (exp > 0 && exp < System.currentTimeMillis() + 60_000 && t.optString("refresh_token").isNotBlank()) {
            val c = byId(id)
            val form = mutableListOf("grant_type" to "refresh_token", "refresh_token" to t.getString("refresh_token"), "client_id" to clientId(id))
            if (c.needsSecret) form += "client_secret" to clientSecret(id)
            val (s, b) = Http.req("POST", c.tokenUrl, Http.form(*form.toTypedArray()), mapOf("Accept" to "application/json"), "application/x-www-form-urlencoded")
            if (s in 200..299) {
                val n = JSONObject(b)
                for (k in n.keys()) t.put(k, n.get(k))
                t.put("expires_at", System.currentTimeMillis() + n.optLong("expires_in", 3600) * 1000)
                saveTokens(id, t)
            } else return null
        }
        return t.optString("access_token")
    }

    // ---------- Tools ----------

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun s(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun i(d: String) = JSONObject().put("type", "integer").put("description", d)

    private val defs: Map<String, List<JSONObject>> = mapOf(
        "google" to listOf(
            fn("gmail_search", "Search the user's Gmail (Gmail search syntax, e.g. 'from:bank newer_than:7d is:unread').", JSONObject().put("query", s("Gmail query")).put("max", i("Max results, default 8")), listOf("query")),
            fn("gmail_read", "Read one Gmail message by id (from gmail_search).", JSONObject().put("id", s("Message id")), listOf("id")),
            fn("gmail_send", "Send an email from the user's Gmail. The user approves first.", JSONObject().put("to", s("Email address")).put("subject", s("Subject")).put("body", s("Plain-text body")), listOf("to", "subject", "body")),
            fn("gcal_events", "List events on the user's Google Calendar for the next N days.", JSONObject().put("days", i("Days ahead, default 1"))),
            fn("gcal_create", "Create a Google Calendar event.", JSONObject().put("title", s("Title")).put("start", s("yyyy-MM-dd HH:mm local")).put("end", s("yyyy-MM-dd HH:mm, optional")).put("location", s("Optional")), listOf("title", "start")),
            fn("drive_search", "Search the user's Google Drive files by name or content.", JSONObject().put("query", s("Words")), listOf("query")),
            fn("gcontacts_search", "Search the user's Google Contacts.", JSONObject().put("query", s("Name, email or number")), listOf("query")),
        ) + Classroom.tools { n, d, p, r -> fn(n, d, p, r) },
        "github" to listOf(
            fn("github_notifications", "List the user's unread GitHub notifications."),
            fn("github_my_prs", "List the user's open pull requests and ones awaiting their review."),
            fn("github_issues", "List open issues in a repo.", JSONObject().put("repo", s("owner/name")), listOf("repo")),
            fn("github_create_issue", "Open an issue in a repo. The user approves first.", JSONObject().put("repo", s("owner/name")).put("title", s("Title")).put("body", s("Body")), listOf("repo", "title")),
            fn("github_my_repos", "List ALL of the user's own repositories, private ones included, newest activity first. Use this (not search) for 'my repos'."),
            fn("github_search_repos", "Search public GitHub repositories (not for the user's own list: use github_my_repos).", JSONObject().put("query", s("Search words")), listOf("query")),
            fn("github_create_repo", "Create a new repository on the user's GitHub (private unless they want it public). The user approves first. Use for a new app or project.",
                JSONObject().put("name", s("Repo name, e.g. habit-tracker")).put("description", s("One line")).put("public", JSONObject().put("type", "boolean").put("description", "true only if the user asked for public")), listOf("name")),
            fn("github_ci", "Status of the latest GitHub Actions run in a repo (or a branch). Waits up to wait_seconds for a running build to finish. On failure it returns the failing step and the compiler errors.",
                JSONObject().put("repo", s("owner/name")).put("branch", s("Optional, default the repo's default branch")).put("wait_seconds", i("0-150, default 120")), listOf("repo")),
            fn("github_install_apk", "Download the APK from a repo's latest GitHub release and open Android's installer for it (the user taps Install).",
                JSONObject().put("repo", s("owner/name")), listOf("repo")),
        ),
        "spotify" to listOf(
            fn("spotify_now_playing", "What's playing on the user's Spotify right now."),
            fn("spotify_play", "Search Spotify and play the top track, album or playlist on the user's active device. Empty query resumes.", JSONObject().put("query", s("What to play")).put("type", JSONObject().put("type", "string").put("enum", JSONArray(listOf("track", "album", "playlist", "artist"))))),
            fn("spotify_control", "Pause, skip or go back on Spotify.", JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("pause", "next", "previous")))), listOf("action")),
        ),
        "notion" to listOf(
            fn("notion_search", "Search the user's Notion pages shared with Blaze.", JSONObject().put("query", s("Words")), listOf("query")),
            fn("notion_add_page", "Create a Notion page under a parent page (found by title).", JSONObject().put("parent", s("Parent page title")).put("title", s("New page title")).put("content", s("Text content")), listOf("parent", "title")),
        ),
        "slack" to listOf(
            fn("slack_channels", "List Slack channels the user is in."),
            fn("slack_read", "Read the latest messages in a Slack channel.", JSONObject().put("channel", s("Channel name or id")).put("limit", i("Default 15")), listOf("channel")),
            fn("slack_post", "Post a message to a Slack channel. The user approves first.", JSONObject().put("channel", s("Channel name or id")).put("text", s("Message")), listOf("channel", "text")),
        ),
    )

    private val owner: Map<String, String> = defs.flatMap { (id, l) -> l.map { it.getJSONObject("function").getString("name") to id } }.toMap()

    fun handles(name: String) = name in owner || name == "share_to_app"

    fun tools(): List<JSONObject> = defs.filterKeys { connected(it) }.values.flatten()
        .filter { Classroom.enabled || it.getJSONObject("function").getString("name") !in Classroom.toolNames } +
        fn("share_to_app", "Share text (a link, note or message draft) into another app via Android's share sheet, e.g. WhatsApp, Telegram, Gmail, Notes.",
            JSONObject().put("app", s("App name")).put("text", s("What to share")), listOf("app", "text"))

    /** Short summary for the system prompt. */
    fun promptBlock(): String {
        val on = all.filter { connected(it.id) }
        return if (on.isEmpty()) "" else "\nConnected accounts you can use with tools: " + on.joinToString(", ") { "${it.name} (${it.blurb})" } + ".\n"
    }

    fun describe(name: String): String = when {
        name.startsWith("gmail_") -> "Checking Gmail"
        name.startsWith("gcal_") -> "Checking Google Calendar"
        name.startsWith("drive_") -> "Searching Drive"
        name.startsWith("gcontacts") -> "Searching contacts"
        name == "classroom_sync" -> "Syncing Google Classroom"
        name.startsWith("classroom_") -> "Checking Google Classroom"
        name.startsWith("github_") -> "Checking GitHub"
        name.startsWith("spotify_") -> "Spotify"
        name.startsWith("notion_") -> "Checking Notion"
        name.startsWith("slack_") -> "Checking Slack"
        name == "share_to_app" -> "Sharing"
        else -> name
    }

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = withContext(Dispatchers.IO) {
        if (name == "share_to_app") return@withContext share(ctx, a.optString("app"), a.optString("text"))
        val id = owner[name] ?: return@withContext "Unknown tool"
        val tok = access(id) ?: return@withContext "${byId(id).name} isn't connected (or the sign-in expired). Ask the user to reconnect in Settings → Connections."
        try {
            when (id) {
                "google" -> google(name, a, tok)
                "github" -> github(name, a, tok, ctx)
                "spotify" -> spotify(name, a, tok)
                "notion" -> notion(name, a, tok)
                "slack" -> slack(name, a, tok)
                else -> "Unknown tool"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    /** Used by the process registry to delete a Google Calendar event Blaze created. */
    suspend fun gcalDelete(eventId: String): String = withContext(Dispatchers.IO) {
        val tok = access("google") ?: return@withContext "Couldn't delete: Google isn't connected."
        try {
            val (c, b) = Http.req("DELETE", "https://www.googleapis.com/calendar/v3/calendars/primary/events/" + Http.enc(eventId), null, bearer(tok))
            if (c in 200..299 || c == 404 || c == 410) "Deleted it from Google Calendar." else "Couldn't delete ($c) ${b.take(120)}"
        } catch (e: Exception) { "Couldn't delete: ${e.message}" }
    }

    private fun bearer(t: String) = mapOf("Authorization" to "Bearer $t", "Accept" to "application/json")
    private fun get(url: String, t: String, extra: Map<String, String> = emptyMap()): JSONObject {
        val (c, b) = Http.req("GET", url, null, bearer(t) + extra)
        if (c !in 200..299) throw Exception("($c) ${b.take(200)}")
        return if (b.trimStart().startsWith("[")) JSONObject().put("items", JSONArray(b)) else if (b.isBlank()) JSONObject() else JSONObject(b)
    }
    private fun send(method: String, url: String, body: JSONObject, t: String, extra: Map<String, String> = emptyMap()): JSONObject {
        val (c, b) = Http.req(method, url, body.toString(), bearer(t) + extra)
        if (c !in 200..299) throw Exception("($c) ${b.take(200)}")
        return if (b.isBlank()) JSONObject() else try { JSONObject(b) } catch (_: Exception) { JSONObject() }
    }

    private val local = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private fun rfc(d: Date) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(d)

    private suspend fun google(name: String, a: JSONObject, t: String): String {
        val g = "https://gmail.googleapis.com/gmail/v1/users/me"
        return when (name) {
            "gmail_search" -> {
                val l = get("$g/messages?maxResults=${a.optInt("max", 8).coerceIn(1, 20)}&q=" + Http.enc(a.optString("query")), t).optJSONArray("messages") ?: return "No emails matched."
                (0 until l.length()).joinToString("\n") { k ->
                    val id = l.getJSONObject(k).getString("id")
                    val m = get("$g/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date", t)
                    val h = m.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
                    fun hd(n: String) = (0 until h.length()).map { h.getJSONObject(it) }.firstOrNull { it.optString("name") == n }?.optString("value").orEmpty()
                    "[$id] ${hd("Date").take(22)} · ${hd("From").take(50)} · ${hd("Subject").take(80)}\n   ${m.optString("snippet").take(160)}"
                }
            }
            "gmail_read" -> {
                val m = get("$g/messages/${a.optString("id")}?format=full", t)
                fun body(p: JSONObject?): String {
                    if (p == null) return ""
                    if (p.optString("mimeType") == "text/plain") p.optJSONObject("body")?.optString("data")?.takeIf { it.isNotBlank() }?.let {
                        return String(Base64.decode(it, Base64.URL_SAFE)) }
                    val parts = p.optJSONArray("parts") ?: return ""
                    for (i in 0 until parts.length()) body(parts.getJSONObject(i)).takeIf { it.isNotBlank() }?.let { return it }
                    return ""
                }
                body(m.optJSONObject("payload")).ifBlank { m.optString("snippet") }.take(6000)
            }
            "gmail_send" -> {
                if (!Agent.confirm("Email ${a.optString("to")}?", "${a.optString("subject")}\n\n${a.optString("body").take(300)}", "Send", always = true))
                    return "The user declined. The email was NOT sent."
                val raw = "To: ${a.optString("to")}\r\nSubject: ${a.optString("subject")}\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n${a.optString("body")}"
                send("POST", "$g/messages/send", JSONObject().put("raw", b64(raw.toByteArray())), t)
                "Email sent to ${a.optString("to")}."
            }
            "gcal_events" -> {
                val now = Date(); val end = Date(now.time + a.optInt("days", 1).coerceIn(1, 31) * 86_400_000L)
                val l = get("https://www.googleapis.com/calendar/v3/calendars/primary/events?singleEvents=true&orderBy=startTime&maxResults=30&timeMin=" + Http.enc(rfc(now)) + "&timeMax=" + Http.enc(rfc(end)), t).optJSONArray("items") ?: JSONArray()
                if (l.length() == 0) "Nothing on Google Calendar in that range." else (0 until l.length()).joinToString("\n") { k ->
                    val e = l.getJSONObject(k); val st = e.optJSONObject("start")
                    "${st?.optString("dateTime")?.ifBlank { null } ?: st?.optString("date")} · ${e.optString("summary")}${e.optString("location").takeIf { it.isNotBlank() }?.let { " @ $it" } ?: ""}"
                }
            }
            "gcal_create" -> {
                val st = local.parse(a.optString("start")) ?: return "Start must be yyyy-MM-dd HH:mm."
                val en = a.optString("end").takeIf { it.isNotBlank() }?.let { local.parse(it) } ?: Date(st.time + 3_600_000)
                val tz = TimeZone.getDefault().id
                val e = send("POST", "https://www.googleapis.com/calendar/v3/calendars/primary/events", JSONObject().put("summary", a.optString("title"))
                    .put("location", a.optString("location")).put("start", JSONObject().put("dateTime", rfc(st)).put("timeZone", tz))
                    .put("end", JSONObject().put("dateTime", rfc(en)).put("timeZone", tz)), t)
                val pid = com.blaze.assistant.agent.Processes.register("gevent", a.optString("title"), e.optString("id"), st.time,
                    JSONObject().put("location", a.optString("location")).put("link", e.optString("htmlLink")))
                "Added \"${a.optString("title")}\" to Google Calendar. [ID $pid] ${e.optString("htmlLink")}"
            }
            "drive_search" -> {
                val q = a.optString("query").replace("'", "\\'")
                val l = get("https://www.googleapis.com/drive/v3/files?pageSize=10&fields=files(name,mimeType,webViewLink,modifiedTime)&q=" +
                    Http.enc("(name contains '$q' or fullText contains '$q') and trashed=false"), t).optJSONArray("files") ?: JSONArray()
                if (l.length() == 0) "No Drive files matched." else (0 until l.length()).joinToString("\n") { k ->
                    val f = l.getJSONObject(k); "${f.optString("name")} (${f.optString("modifiedTime").take(10)}) ${f.optString("webViewLink")}" }
            }
            "classroom_courses", "classroom_coursework", "classroom_sync" -> classroom(name, a, t)
            "gcontacts_search" -> {
                val l = get("https://people.googleapis.com/v1/people:searchContacts?readMask=names,emailAddresses,phoneNumbers&query=" + Http.enc(a.optString("query")), t).optJSONArray("results") ?: JSONArray()
                if (l.length() == 0) "No Google contact matched." else (0 until l.length()).joinToString("\n") { k ->
                    val p = l.getJSONObject(k).getJSONObject("person")
                    fun first(arr: String, f: String) = p.optJSONArray(arr)?.optJSONObject(0)?.optString(f).orEmpty()
                    "${first("names", "displayName")} · ${first("phoneNumbers", "value")} · ${first("emailAddresses", "value")}" }
            }
            else -> "Unknown tool"
        }
    }

    private fun classroom(name: String, a: JSONObject, t: String): String {
        if (!Classroom.enabled) return Classroom.NEEDS_SCOPE
        val g: (String) -> JSONObject = { u -> get(u, t) }
        return try {
            when (name) {
                "classroom_courses" -> Classroom.courses(g).let { l -> if (l.isEmpty()) "No active Google Classroom courses." else l.joinToString("\n") { "- ${it.second} [id ${it.first}]" } }
                "classroom_coursework" -> Classroom.work(g, a.optString("course_id"), a.optInt("days", 21).coerceIn(1, 180), a.optBoolean("include_past"))
                    .let { l -> if (l.isEmpty()) "No assignments due in that window." else l.take(40).joinToString("\n") { Classroom.line(it) } }
                else -> {
                    val l = Classroom.work(g, "", a.optInt("days", 30).coerceIn(1, 180), false).filter { !Classroom.done(it) }
                    val have = com.blaze.assistant.routine.Routines.all.value.map { it.name }.toSet()
                    val added = ArrayList<String>(); var kept = 0
                    for (w in l) {
                        val at = Classroom.reminderAt(w.due) ?: continue
                        if (Classroom.reminderName(w) in have) { kept++; continue }
                        com.blaze.assistant.routine.Routines.addAt(Classroom.reminderName(w), Classroom.reminderPrompt(w), at)
                        added += w.title
                    }
                    if (l.isEmpty()) "Nothing due in Classroom in that window that isn't turned in."
                    else "Classroom sync: ${added.size} new reminder${if (added.size == 1) "" else "s"} in Schedule (24 h before each is due)" +
                        (if (kept > 0) ", $kept already there" else "") + "." + (if (added.isNotEmpty()) " Added: ${added.joinToString("; ").take(600)}" else "")
                }
            }
        } catch (e: Exception) {
            if ((e.message ?: "").let { it.contains("(403)") || it.contains("(401)") || it.contains("insufficient", true) }) Classroom.NEEDS_SCOPE else "Classroom error: ${e.message}"
        }
    }

    private suspend fun github(name: String, a: JSONObject, t: String, ctx: Context): String {
        val api = "https://api.github.com"
        val h = mapOf("Accept" to "application/vnd.github+json")
        return when (name) {
            "github_notifications" -> {
                val l = get("$api/notifications?per_page=20", t, h).optJSONArray("items") ?: JSONArray()
                if (l.length() == 0) "No unread GitHub notifications." else (0 until l.length()).joinToString("\n") { k ->
                    val n = l.getJSONObject(k); "${n.optJSONObject("repository")?.optString("full_name")} · ${n.optString("reason")} · ${n.optJSONObject("subject")?.optString("title")}" }
            }
            "github_my_prs" -> {
                val me = get("$api/user", t, h).optString("login")
                fun q(qs: String) = get("$api/search/issues?per_page=15&q=" + Http.enc(qs), t, h).optJSONArray("items") ?: JSONArray()
                val mine = q("is:pr is:open author:$me"); val review = q("is:pr is:open review-requested:$me")
                fun fmt(l: JSONArray) = (0 until l.length()).joinToString("\n") { k -> val p = l.getJSONObject(k); "  #${p.optInt("number")} ${p.optString("title")} (${p.optString("repository_url").substringAfter("repos/")})" }.ifEmpty { "  none" }
                "Your open PRs:\n${fmt(mine)}\nWaiting for your review:\n${fmt(review)}"
            }
            "github_issues" -> {
                val l = get("$api/repos/${a.optString("repo")}/issues?state=open&per_page=20", t, h).optJSONArray("items") ?: JSONArray()
                if (l.length() == 0) "No open issues." else (0 until l.length()).joinToString("\n") { k -> val i = l.getJSONObject(k); "#${i.optInt("number")} ${i.optString("title")}" }
            }
            "github_create_issue" -> {
                if (!Agent.confirm("Open an issue in ${a.optString("repo")}?", a.optString("title"), "Create", always = true)) return "The user declined."
                val r = send("POST", "$api/repos/${a.optString("repo")}/issues", JSONObject().put("title", a.optString("title")).put("body", a.optString("body")), t, h)
                "Created issue #${r.optInt("number")}: ${r.optString("html_url")}"
            }
            "github_my_repos" -> {
                val all = mutableListOf<JSONObject>()
                for (page in 1..5) {
                    val l = get("$api/user/repos?per_page=100&page=$page&sort=pushed&affiliation=owner,collaborator,organization_member", t, h).optJSONArray("items") ?: JSONArray()
                    for (k in 0 until l.length()) all += l.getJSONObject(k)
                    if (l.length() < 100) break
                }
                if (all.isEmpty()) "No repositories on this GitHub account."
                else "${all.size} repos (${all.count { it.optBoolean("private") }} private), most recently pushed first:\n" + all.take(60).joinToString("\n") { r ->
                    "${r.optString("full_name")}${if (r.optBoolean("private")) " · private" else ""} · pushed ${r.optString("pushed_at").take(10)}" +
                        r.optString("description").takeIf { it.isNotBlank() && it != "null" }?.let { " · ${it.take(60)}" }.orEmpty()
                }
            }
            "github_search_repos" -> {
                val l = get("$api/search/repositories?per_page=8&q=" + Http.enc(a.optString("query")), t, h).optJSONArray("items") ?: JSONArray()
                (0 until l.length()).joinToString("\n") { k -> val r = l.getJSONObject(k); "${r.optString("full_name")} ★${r.optInt("stargazers_count")} · ${r.optString("description").take(90)}" }.ifEmpty { "Nothing found." }
            }
            "github_create_repo" -> GitHubApps.createRepo(a, t)
            "github_ci" -> GitHubApps.ci(a, t)
            "github_install_apk" -> GitHubApps.installApk(ctx, a, t)
            else -> "Unknown tool"
        }
    }

    private suspend fun spotify(name: String, a: JSONObject, t: String): String {
        val api = "https://api.spotify.com/v1"
        return when (name) {
            "spotify_now_playing" -> {
                val (c, b) = Http.req("GET", "$api/me/player/currently-playing", null, bearer(t))
                if (c == 204 || b.isBlank()) return "Nothing is playing on Spotify."
                val o = JSONObject(b).optJSONObject("item") ?: return "Nothing is playing."
                "${o.optString("name")} — ${o.optJSONArray("artists")?.optJSONObject(0)?.optString("name")}${if (JSONObject(b).optBoolean("is_playing")) "" else " (paused)"}"
            }
            "spotify_play" -> {
                val q = a.optString("query")
                if (q.isBlank()) { Http.req("PUT", "$api/me/player/play", "{}", bearer(t)); return "Resumed." }
                val type = a.optString("type").ifBlank { "track" }
                val r = get("$api/search?limit=1&type=$type&q=" + Http.enc(q), t).optJSONObject(type + "s")?.optJSONArray("items")?.optJSONObject(0)
                    ?: return "Couldn't find \"$q\" on Spotify."
                val body = if (type == "track") JSONObject().put("uris", JSONArray().put(r.getString("uri"))) else JSONObject().put("context_uri", r.getString("uri"))
                val (c, b) = Http.req("PUT", "$api/me/player/play", body.toString(), bearer(t))
                if (c == 404) "Spotify has no active device. Open Spotify on the phone and try again."
                else if (c !in 200..299) "Spotify said ($c): ${b.take(150)}" else "Playing ${r.optString("name")}."
            }
            "spotify_control" -> {
                val (m, p) = when (a.optString("action")) { "pause" -> "PUT" to "pause"; "previous" -> "POST" to "previous"; else -> "POST" to "next" }
                val (c, b) = Http.req(m, "$api/me/player/$p", "", bearer(t))
                if (c in 200..299) "Done." else "Spotify said ($c): ${b.take(150)}"
            }
            else -> "Unknown tool"
        }
    }

    private suspend fun notion(name: String, a: JSONObject, t: String): String {
        val h = mapOf("Notion-Version" to "2022-06-28")
        fun title(p: JSONObject): String {
            val props = p.optJSONObject("properties") ?: return "Untitled"
            for (k in props.keys()) { val v = props.getJSONObject(k); if (v.optString("type") == "title")
                return v.optJSONArray("title")?.let { arr -> (0 until arr.length()).joinToString("") { arr.getJSONObject(it).optString("plain_text") } } ?: "Untitled" }
            return "Untitled"
        }
        return when (name) {
            "notion_search" -> {
                val l = send("POST", "https://api.notion.com/v1/search", JSONObject().put("query", a.optString("query")).put("page_size", 10), t, h).optJSONArray("results") ?: JSONArray()
                if (l.length() == 0) "Nothing in Notion matched (pages must be shared with the integration)." else
                    (0 until l.length()).joinToString("\n") { k -> val p = l.getJSONObject(k); "${title(p)} · ${p.optString("url")}" }
            }
            "notion_add_page" -> {
                val parent = send("POST", "https://api.notion.com/v1/search", JSONObject().put("query", a.optString("parent"))
                    .put("filter", JSONObject().put("property", "object").put("value", "page")).put("page_size", 1), t, h)
                    .optJSONArray("results")?.optJSONObject(0) ?: return "No Notion page called \"${a.optString("parent")}\" is shared with Blaze."
                val txt = a.optString("content").take(1900)
                val body = JSONObject().put("parent", JSONObject().put("page_id", parent.getString("id")))
                    .put("properties", JSONObject().put("title", JSONObject().put("title", JSONArray().put(JSONObject().put("text", JSONObject().put("content", a.optString("title")))))))
                if (txt.isNotBlank()) body.put("children", JSONArray().put(JSONObject().put("object", "block").put("type", "paragraph")
                    .put("paragraph", JSONObject().put("rich_text", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", txt)))))))
                val r = send("POST", "https://api.notion.com/v1/pages", body, t, h)
                "Created \"${a.optString("title")}\" in Notion: ${r.optString("url")}"
            }
            else -> "Unknown tool"
        }
    }

    private suspend fun slack(name: String, a: JSONObject, t: String): String {
        val api = "https://slack.com/api"
        fun channels(): List<JSONObject> { val l = get("$api/conversations.list?limit=200&exclude_archived=true&types=public_channel,private_channel", t).optJSONArray("channels") ?: JSONArray()
            return (0 until l.length()).map { l.getJSONObject(it) } }
        fun chanId(c: String) = if (c.matches(Regex("[CGD][A-Z0-9]{6,}"))) c else channels().firstOrNull { it.optString("name") == c.removePrefix("#") }?.optString("id")
        return when (name) {
            "slack_channels" -> channels().filter { it.optBoolean("is_member") }.joinToString(", ") { "#" + it.optString("name") }.ifEmpty { "You're not in any channels." }
            "slack_read" -> {
                val id = chanId(a.optString("channel")) ?: return "No channel ${a.optString("channel")}."
                val o = get("$api/conversations.history?limit=${a.optInt("limit", 15).coerceIn(1, 50)}&channel=$id", t)
                if (!o.optBoolean("ok")) return "Slack: ${o.optString("error")}"
                val l = o.optJSONArray("messages") ?: JSONArray()
                (l.length() - 1 downTo 0).joinToString("\n") { k -> val m = l.getJSONObject(k); "${m.optString("user")}: ${m.optString("text").take(200)}" }
            }
            "slack_post" -> {
                val id = chanId(a.optString("channel")) ?: return "No channel ${a.optString("channel")}."
                if (!Agent.confirm("Post in ${a.optString("channel")}?", a.optString("text").take(300), "Post", always = true)) return "The user declined. Nothing was posted."
                val o = send("POST", "$api/chat.postMessage", JSONObject().put("channel", id).put("text", a.optString("text")), t)
                if (o.optBoolean("ok")) "Posted." else "Slack: ${o.optString("error")}"
            }
            else -> "Unknown tool"
        }
    }

    private suspend fun share(ctx: Context, app: String, text: String): String = withContext(Dispatchers.Main) {
        val pm = ctx.packageManager
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val targets = pm.queryIntentActivities(send, 0)
        val hit = targets.firstOrNull { it.loadLabel(pm).toString().contains(app, true) || it.activityInfo.packageName.contains(app.replace(" ", ""), true) }
        if (hit == null) { ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@withContext "Couldn't find $app, so the share sheet is open." }
        ctx.startActivity(send.setClassName(hit.activityInfo.packageName, hit.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Shared into ${hit.loadLabel(pm)}. The user may need to pick a chat and press send."
    }
}
