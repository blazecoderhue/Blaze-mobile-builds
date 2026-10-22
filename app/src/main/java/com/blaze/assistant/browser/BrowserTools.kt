package com.blaze.assistant.browser

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Tools that let Blaze browse in its own browser, in the background, without touching the screen. */
object BrowserTools {
    val names = setOf("browser_open", "browser_read", "browser_click", "browser_type", "browser_scroll", "browser_back", "browser_tabs", "browser_show", "browser_vpn", SECURE)
    /** v0.20.0: in-chat secure form; handled by the Agent (it needs the chat card). */
    const val SECURE = "browser_secure_input"

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)

    fun definitions(): List<JSONObject> = listOf(
        fn("browser_open", "Open a web page (or search words) in Blaze's own built-in browser, in the background. No screen control needed. " +
            "Use it for sites you must interact with: forms, logins, shopping, booking, reading a full page. Returns the page text and numbered elements.",
            JSONObject().put("url", p("string", "URL or search words")).put("new_tab", p("boolean", "Open in a new tab"))
                .put("show", p("boolean", "Also show the browser to the user"))
                .put("private", p("boolean", "Open in a private tab: no history, cookies wiped when closed, location masked")), listOf("url")),
        fn("browser_read", "Read the current browser page again: text plus numbered links, buttons and fields."),
        fn("browser_click", "Click an element in the browser page by its [number] from browser_read, or by its visible text.",
            JSONObject().put("number", p("integer", "Element number")).put("text", p("string", "Visible text, if no number"))),
        fn("browser_type", "Type into a field in the browser page (by [number] or label), optionally pressing Enter/submitting.",
            JSONObject().put("number", p("integer", "Field number")).put("label", p("string", "Field label/placeholder, if no number"))
                .put("text", p("string", "Text to type")).put("submit", p("boolean", "Press Enter / submit the form")), listOf("text")),
        fn("browser_scroll", "Scroll the browser page.", JSONObject().put("direction", JSONObject().put("type", "string").put("enum", JSONArray(listOf("down", "up"))))),
        fn("browser_back", "Go back one page in the browser."),
        fn("browser_tabs", "List browser tabs, or switch with switch_to.", JSONObject().put("switch_to", p("integer", "Tab number to switch to"))),
        fn(SECURE, "When the page in Blaze's background browser needs something only the user has (email, phone, username + password, API key, " +
            "one-time code), call this instead of asking in chat or showing the browser. The user gets a secure form card in the chat (an 'Action required' " +
            "notification if they're away); the browser fills the page from it and (by default) submits. You never see the values: you get placeholders like " +
            "{{secret:password}} that browser_type can reuse. Saved values for the site are used without asking. Never for card numbers (use browser_show for payment).",
            JSONObject().put("title", p("string", "Short title, e.g. 'Sign in to GitHub'"))
                .put("reason", p("string", "One line on why it's needed"))
                .put("fields", JSONObject().put("type", "array").put("description", "The fields to ask for, in page order")
                    .put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("name", p("string", "Short id, e.g. email, password, code"))
                        .put("label", p("string", "What the user sees, e.g. 'GitHub password'"))
                        .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("email", "phone", "username", "password", "api_key", "otp", "number", "text"))))
                        .put("target", p("string", "The page field's [number] from browser_read, or its label; leave out to find it by kind")))
                        .put("required", JSONArray(listOf("name", "kind")))))
                .put("submit", p("boolean", "Submit the form after filling (default true)"))
                .put("fresh", p("boolean", "Ask even if values are saved for this site (e.g. the saved password was wrong)")), listOf("title", "fields")),
        fn("browser_show", "Show the built-in browser on screen with the current page, so the user can watch or take over (e.g. to pay, solve a captcha, or approve Google's own sign-in). Optional: work stays in the background by default."),
        fn("browser_vpn", "The built-in browser's VPN (only the browser goes through it). Built in and free: Blaze Free (Cloudflare WARP), one tap, no setup, exits near the user " +
            "(hides the real IP but can't pick a country). Plus any per-country WireGuard configs the user imported. " +
            "status: is it on, which country, which IP, which locations exist. connect: with no country (or \"free\") uses Blaze Free when nothing is imported, else the last/first imported one; " +
            "with a country (name or code) uses that imported config, e.g. to see a site as it looks there. disconnect: back to the real connection. " +
            "Countries need an imported WireGuard config (browser ⋯ › VPN & privacy).",
            JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("status", "connect", "disconnect"))))
                .put("country", p("string", "Country name or code for connect, e.g. Germany or DE")), listOf("action")),
    )

    fun describe(name: String, a: JSONObject): String = when (name) {
        "browser_open" -> "Browsing ${com.blaze.assistant.agent.ToolLeak.fixBlazeUrl(a.optString("url")).take(50)}"
        "browser_read" -> "Reading the page"
        "browser_click" -> "Clicking ${a.optString("text").ifBlank { "#" + a.optInt("number") }}"
        "browser_type" -> "Typing in the page"
        "browser_scroll" -> "Scrolling the page"
        "browser_back" -> "Going back"
        "browser_tabs" -> "Checking tabs"
        SECURE -> "Asking you for ${a.optString("title").ifBlank { "a detail" }.take(40)}"
        "browser_vpn" -> when (a.optString("action")) { "connect" -> "Switching the browser VPN to ${a.optString("country")}"; "disconnect" -> "Turning the browser VPN off"; else -> "Checking the browser VPN" }
        else -> "Showing the browser"
    }

    fun show(ctx: Context) = ctx.startActivity(Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = try {
        // v0.30.5: Home's live preview window shows the page while Blaze works in it.
        if (name != "browser_vpn" && name != "browser_tabs") BrowserLive.mark(describe(name, a))
        when (name) {
            // v0.28.4: a guessed Blaze repo URL (wrong owner / case) opens the real repo.
            // v0.33.3: a Play Store page for an installed app launches the app (never "Install" for something installed).
            "browser_open" -> com.blaze.assistant.phone.AppFinder.playId(a.optString("url"))?.takeIf { com.blaze.assistant.phone.InstalledApps.launch(ctx, it) }
                ?.let { "That app ($it) is installed on this phone, so I opened it instead of its Play Store page. Never offer to install it." } ?: BrowserEngine.open(ctx, com.blaze.assistant.agent.ToolLeak.fixBlazeUrl(a.optString("url")), a.optBoolean("new_tab", false), a.optBoolean("private", false)).also { if (a.optBoolean("show")) show(ctx) }
            "browser_read" -> BrowserEngine.read(ctx)
            "browser_click" -> {
                // v0.28.2: a button that pays, sends, posts or deletes always asks first (ConfirmGate: outbound in the user's name).
                val n = if (a.has("number")) a.optInt("number") else null
                val text = a.optString("text").ifBlank { null }
                val label = BrowserEngine.labelOf(n, text).ifBlank { text.orEmpty() }
                val site = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { BrowserEngine.host() }.ifBlank { "this page" }
                if (com.blaze.assistant.phone.Pointer.risky(label) &&
                    !com.blaze.assistant.agent.Agent.confirm("Click \u201c${label.trim().take(40)}\u201d on $site?",
                        "This looks like it pays, sends, posts or deletes something.", "Click", always = true))
                    "The user did NOT approve clicking \"${label.trim().take(40)}\". Don't retry; tell them it's ready for them to finish (browser_show)."
                else BrowserEngine.click(ctx, n, text)
            }
            "browser_type" -> BrowserEngine.type(ctx, if (a.has("number")) a.optInt("number") else null, a.optString("label").ifBlank { null },
                a.optString("text"), a.optBoolean("submit", false))
            "browser_scroll" -> BrowserEngine.scroll(ctx, a.optString("direction", "down") != "up")
            "browser_back" -> BrowserEngine.back(ctx)
            "browser_tabs" -> if (a.has("switch_to")) BrowserEngine.switchTab(a.optInt("switch_to")) else BrowserEngine.tabList()
            "browser_show" -> { show(ctx); "The browser is on screen." }
            "browser_vpn" -> vpn(ctx, a)
            else -> "Unknown browser tool."
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Browser error: ${e.message}" }

    private suspend fun vpn(ctx: Context, a: JSONObject): String {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { BrowserEngine.init(ctx) }
        return when (a.optString("action")) {
            "connect" -> {
                val q = a.optString("country")
                val loc = BrowserVpn.find(q) ?: return "No VPN location for \u201c$q\u201d. Available: Blaze Free (Cloudflare WARP, exits near the user)" +
                    BrowserVpn.locations.joinToString("") { ", " + it.label } + ". Other countries need a WireGuard config imported in the browser (⋯ › VPN & privacy)."
                BrowserVpn.connectNow(loc.id)
            }
            "disconnect" -> { BrowserVpn.disconnect(); "VPN off. The browser uses the real connection again." }
            else -> BrowserVpn.statusText()
        }
    }
}

/**
 * v0.30.5: what Blaze is doing in its background browser, for the Home tab's live preview window. Set on each browser
 * tool call; the preview shows while a chat is working and a browser step happened in the last [WINDOW_MS].
 */
object BrowserLive {
    data class Step(val what: String, val at: Long)
    const val WINDOW_MS = 90_000L
    private val _step = kotlinx.coroutines.flow.MutableStateFlow<Step?>(null)
    val step: kotlinx.coroutines.flow.StateFlow<Step?> = _step
    fun mark(what: String, now: Long = System.currentTimeMillis()) { _step.value = Step(what, now) }
    /** Pure (BrowserLiveTest): show the preview? [busy] = a chat is working right now. */
    fun showing(s: Step?, busy: Boolean, now: Long): Boolean = busy && s != null && now - s.at in 0..WINDOW_MS
}
