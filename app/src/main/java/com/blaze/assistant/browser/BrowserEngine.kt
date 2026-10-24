package com.blaze.assistant.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import kotlin.coroutines.resume

/**
 * Blaze's own browser. Tabs are WebViews that live here, not in the screen, so Blaze can open,
 * read, click and type on pages in the background, and the Browser screen just shows the current tab.
 */
object BrowserEngine {
    class Tab(val id: Int, val ctxWrap: MutableContextWrapper, val isPrivate: Boolean = false) {
        /**
         * Unreleased (browser perf): the WebView is created on first use and can be discarded while the tab is in the
         * background (its back/forward state goes to [pending] and comes back when the tab is shown again). Restored
         * tabs no longer each create a WebView at start, and only [PerfPolicy.Budget.maxLiveWebViews] stay alive.
         */
        internal var webOrNull: WebView? = null
        val web: WebView get() = webOrNull ?: materialize(this)
        val live: Boolean get() = webOrNull != null
        /** When this tab was last on screen (for least-recently-used discarding). */
        @Volatile var lastShown: Long = System.currentTimeMillis()
        internal var captureLate = false
        var title by mutableStateOf(if (isPrivate) "Private tab" else "New tab")
        var icon by mutableStateOf<ImageBitmap?>(null)
        /** A small picture of the page for the tab grid, taken when you leave the tab. */
        var thumb by mutableStateOf<ImageBitmap?>(null)
        internal var mask: ScriptHandler? = null
        internal var maskJs: String? = null
        var url by mutableStateOf("")
        var progress by mutableIntStateOf(100)
        /** v0.29.1 load feed: what the page is pulling right now (see [LoadFeed]). */
        // Unreleased: plain fields, not Compose state. onLoadResource fires for every request (hundreds per page) and
        // nothing on screen reads these, so each write used to be a snapshot-state write on the main thread for nothing.
        @Volatile var pulls = 0
        @Volatile internal var pullingUrl: String = ""
        val pulling: String get() = pullingUrl.let { if (it.isEmpty()) "" else LoadFeed.label(it) }
        @Volatile var loadStart = 0L
        @Volatile var lastPull = 0L
        var canBack by mutableStateOf(false)
        var canForward by mutableStateOf(false)
        @Volatile var loaded: CompletableDeferred<Unit> = CompletableDeferred(Unit)
        /** A JS alert/confirm the page showed while nobody was looking (answered for it, told to Blaze on the next read). */
        @Volatile var lastDialog: String? = null
        /** v0.29.7: a tab restored after a restart that hasn't loaded yet (loads when it's shown). */
        internal var pending: TabStore.Snap? = null
        /** v0.29.7: saved accounts for this page's sign-in form (the "Use a saved password" bar). */
        var fillAccounts by mutableStateOf<List<BrowserLogins.Login>>(emptyList())
    }

    /** v0.29.7: a sign-in the user just sent, waiting for "Save password?" (memory only, never written unless they tap Save). */
    data class LoginOffer(val tabId: Int, val host: String, val site: String, val user: String, val pass: String, val update: Boolean)
    var loginOffer by mutableStateOf<LoginOffer?>(null)
    private var offerWaiting: LoginOffer? = null

    /** Set when a page's renderer died (Android closed it for memory, or it crashed) and the tab was reopened. */
    @Volatile var lastCrash: String? = null

    val tabs = mutableStateListOf<Tab>()
    private var currentIndex by mutableIntStateOf(0)
    /** The tab on screen. Switching to a restored tab loads it (v0.29.7). */
    var current: Int
        get() = currentIndex
        set(v) {
            val prev = tabs.getOrNull(currentIndex)
            currentIndex = v
            val now = tabs.getOrNull(v)
            prev?.takeIf { it !== now }?.let { it.lastShown = System.currentTimeMillis(); background(it) }
            now?.let { it.lastShown = System.currentTimeMillis(); foreground(it); if (it.pending != null) main.post { wake(it) } }
            scheduleSave(); main.post { enforceLiveCap() }
        }
    var desktop by mutableStateOf(false)
    var fullView by mutableStateOf<View?>(null)
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    /** The Browser screen sets this so file pickers on pages work. */
    var filePicker: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null
    private var nextId = 1
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    fun tab(): Tab? = tabs.getOrNull(current)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        BrowserLogins.dir = app.filesDir; TabStore.dir = app.filesDir; SavedPages.root = app.filesDir
        BrowserPerf.hardware(app)
        BrowserData.warm(app)   // bookmarks/history parsed off the main thread before the start page asks
        BrowserVpn.onMaskChanged = { remask() }
        BrowserVpn.init(app)
        main.post { clearPrivateData() }   // leftovers if Blaze was killed with private tabs open
        restoreTabs()
    }

    // ---------- v0.29.7 tabs that survive a restart ----------
    private var restored = false
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val saveTask = Runnable { saveTabsNow() }

    /** Saves the tab list a moment after something changed (page loaded, tab opened / closed / switched). */
    fun scheduleSave() { if (!restored) return; main.removeCallbacks(saveTask); main.postDelayed(saveTask, BrowserPerf.budget().saveDebounceMs) }

    /** Writes the normal tabs (URL, title, order, back/forward state) and flushes cookies so sign-ins survive a kill. */
    fun saveTabsNow() {
        if (!restored || !::app.isInitialized) return
        main.removeCallbacks(saveTask)
        try {
            val snaps = tabs.filter { !it.isPrivate }.map { snapOf(it) }
            val saved = TabStore.Saved(snaps, TabStore.activeIndex(tabs.map { it.isPrivate }, current))
            val budget = BrowserPerf.budget().persistStateBudget
            io.execute { TabStore.write(saved, budget) }   // one writer thread, in order
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.error("Browser tabs not saved", e) }
        flushCookies()
    }

    /** Unreleased: CookieManager.flush blocks and does disk I/O, so it runs on the writer thread, not the main thread. */
    fun flushCookies() { io.execute { try { CookieManager.getInstance().flush() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: CookieManager.getInstance.flush", e) } } }

    @Suppress("DEPRECATION")
    private fun snapOf(t: Tab): TabStore.Snap {
        t.pending?.let { return it.copy(title = t.title) }
        val st = HashMap<String, String>()
        try {
            val b = android.os.Bundle()
            t.web.saveState(b)
            for (k in b.keySet()) when (val v = b.get(k)) {
                is ByteArray -> st[k] = "b:" + java.util.Base64.getEncoder().encodeToString(v)
                is String -> st[k] = "s:$v"
                else -> {}
            }
        } catch (_: Throwable) { st.clear() }
        val url = t.url.takeIf { it.isNotBlank() && it != "about:blank" } ?: ""
        return TabStore.Snap(url, if (url.isEmpty()) "" else t.title, if (url.isEmpty()) emptyMap() else st)
    }

    /** Reopens last session's normal tabs. Only the active one loads now; the rest load when you switch to them. */
    private fun restoreTabs() {
        if (restored) return
        val s = try { TabStore.read() } catch (_: Throwable) { TabStore.Saved(emptyList(), 0) }
        try {
            val made = s.tabs.map { snap -> newTab(app, null, false, restore = snap) }
            restored = true
            made.getOrNull(s.active)?.let { current = tabs.indexOf(it) }
        } catch (e: Throwable) {
            restored = true
            com.blaze.assistant.device.CrashLog.error("Browser tabs not restored", e)
        }
    }

    /** Loads a restored tab: its back/forward list when the WebView accepts it, else just its page. */
    private fun wake(t: Tab) {
        val p = t.pending ?: return
        t.pending = null
        if (p.url.isBlank()) return
        allowFiles(t, p.url)
        if (p.state.isNotEmpty()) {
            try {
                val b = android.os.Bundle()
                p.state.forEach { (k, v) ->
                    if (v.startsWith("b:")) b.putByteArray(k, java.util.Base64.getDecoder().decode(v.substring(2)))
                    else if (v.startsWith("s:")) b.putString(k, v.substring(2))
                }
                val r = t.web.restoreState(b)
                if (r != null && r.size > 0) { t.canBack = t.web.canGoBack(); t.canForward = t.web.canGoForward(); if (t.loaded.isCompleted) t.loaded = CompletableDeferred(); return }
            } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: line", e) }
        }
        load(t, p.url)
    }

    /** Saved pages are file:// archives; only tabs that open one may read files (and only our own folder is ever opened). */
    // v0.45.0: file access follows the page: on for one of our saved pages, off again for anything else
    // (it used to stay on after the tab left a saved page for a website).
    private fun allowFiles(t: Tab, url: String) { val on = fileAccessFor(url); if (t.web.settings.allowFileAccess != on) t.web.settings.allowFileAccess = on }

    private fun fileAccessFor(url: String?): Boolean = SavedPages.fileAccessFor(url)

    /** Back / forward with file access set for the page being returned to (a saved page needs it before it loads). */
    fun back(t: Tab): Boolean { if (!t.web.canGoBack()) return false; historyUrl(t, -1)?.let { allowFiles(t, it) }; t.web.goBack(); return true }
    fun forward(t: Tab): Boolean { if (!t.web.canGoForward()) return false; historyUrl(t, 1)?.let { allowFiles(t, it) }; t.web.goForward(); return true }
    private fun historyUrl(t: Tab, step: Int): String? = try {
        val l = t.web.copyBackForwardList(); l.getItemAtIndex(l.currentIndex + step)?.url
    } catch (_: Throwable) { null }

    // ---------- private tabs ----------
    /** Private tabs get their own WebView profile: separate cookies, storage and cache, wiped when the last one closes. */
    private const val PRIVATE_PROFILE = "blaze-private"
    val privateIsolated: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    fun privateTabs() = tabs.filter { it.isPrivate }
    fun normalTabs() = tabs.filter { !it.isPrivate }

    private fun clearPrivateData() {
        if (!privateIsolated) return
        try {
            val p = ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
            p.cookieManager.removeAllCookies(null)
            p.webStorage.deleteAllData()
        } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: val p ProfileStore.getInstance.getOrCrea", e) }
    }

    // ---------- location masking ----------
    private fun shouldMask(t: Tab) = BrowserVpn.maskLocation && (t.isPrivate || BrowserVpn.on)

    private fun installMask(t: Tab) {
        try { t.mask?.remove() } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: t.mask.remove", e) }
        t.mask = null; t.maskJs = null
        if (!shouldMask(t)) return
        val js = LocationMask.script(BrowserVpn.maskCountry())
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { t.mask = WebViewCompat.addDocumentStartJavaScript(t.web, js, setOf("*")); return } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: t.mask WebViewCompat.addDocumentStartJav", e) }
        }
        t.maskJs = js   // older WebView: injected as each page starts (best effort)
    }

    /** Re-applies masking after the VPN or the setting changed; reloads the visible page so it takes effect. */
    fun remask() {
        tabs.forEach { if (it.live) installMask(it) }   // sleeping tabs get it when their WebView is made
        tab()?.let { if (it.url.startsWith("http")) it.web.reload() }
    }

    /** Takes a small picture of the tab for the tab grid (only works while it's on screen). */
    fun snapshot(t: Tab? = tab()) {
        val w = t?.web ?: return
        if (w.width <= 0 || w.height <= 0 || !w.isAttachedToWindow) return
        try {
            val sc = BrowserPerf.budget().thumbScale
            val b = Bitmap.createBitmap((w.width * sc).toInt().coerceAtLeast(1), (w.height * sc).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val c = Canvas(b); c.scale(sc, sc); c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
            w.draw(c)
            t.thumb = b.asImageBitmap()
            trimThumbs(BrowserPerf.budget().maxThumbs)
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: val sc 0.35f", e) }
    }

    /** v0.33.3: a transparent 84px spacer at the end of a scrollable page (once), so the floating toolbar never hides its end. */
    const val BOTTOM_PAD_JS = "(function(){try{var b=document.body;if(!b||document.getElementById('__blaze_pad'))return;" +
        "if(document.documentElement.scrollHeight<=window.innerHeight+4)return;var d=document.createElement('div');d.id='__blaze_pad';" +
        "d.setAttribute('aria-hidden','true');d.style.cssText='display:block;height:84px;width:1px;clear:both;pointer-events:none;background:transparent';b.appendChild(d);}catch(e){}})()"

    // ---------- tabs ----------
    @SuppressLint("SetJavaScriptEnabled")
    fun newTab(ctx: Context, url: String? = null, isPrivate: Boolean = false, restore: TabStore.Snap? = null): Tab {
        init(ctx)
        if (isPrivate && !privateIsolated) toast("Private tabs share cookies with normal tabs on this phone. Update Android System WebView to keep them apart.")
        val t = Tab(nextId++, MutableContextWrapper(app), isPrivate)
        if (restore != null && restore.url.isNotBlank()) {
            // Unreleased: a restored tab stays a title + saved state until it's shown (no WebView at start).
            t.url = restore.url; t.title = restore.title.ifBlank { display(restore.url) }; t.pending = restore
        } else materialize(t)
        tabs.add(t)
        if (t.pending != null) currentIndex = tabs.lastIndex else current = tabs.lastIndex
        if (!url.isNullOrBlank()) load(t, url)
        return t
    }

    /** Creates the tab's WebView (settings, clients, masking, the sign-in bridge). Called on first use of [Tab.web]. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun materialize(t: Tab): WebView {
        t.webOrNull?.let { return it }
        val isPrivate = t.isPrivate
        val w = WebView(t.ctxWrap)
        t.webOrNull = w
        // The profile must be set before the WebView is used for anything else.
        if (isPrivate && privateIsolated) { try { WebViewCompat.setProfile(w, PRIVATE_PROFILE) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: WebViewCompat.setProfilew PRIVATE_PROFIL", e) } }
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            loadWithOverviewMode = true; useWideViewPort = true
            builtInZoomControls = true; displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            userAgentString = ua(defaultUa(w), desktop)
            if (isPrivate) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        layoutOffscreen(w)
        w.webViewClient = object : WebViewClient() {
            // v0.20.0: a dead page renderer (Android reclaims it on a 3 GB phone, or it crashes) used to take the
            // whole app down with it, and Samsung then switches Blaze's phone control off. Reopen the tab instead.
            override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean {
                val crashed = try { d.didCrash() } catch (_: Throwable) { false }
                com.blaze.assistant.device.CrashLog.write("Browser renderer ${if (crashed) "crashed" else "was closed by Android"} on ${t.url.take(80)}; tab reopened")
                main.post { reopen(t) }
                return true
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                // v0.33.3: intent://, other apps' schemes (market:, mailto:, tel:, hark:…) and cross-site https App Links
                // (link taps, server redirects, JS / meta-refresh redirects) open the app that handles them; a page with
                // no app for it just keeps loading (AppLinks / RedirectRules).
                // An agent driving a background tab stays in the page: only the tab on screen hands https links to apps.
                val handled = AppLinks.handle(app, v, r, t.url.ifBlank { v.url }, appLinks = visible(t))
                if (!handled && r.isForMainFrame) allowFiles(t, r.url?.toString().orEmpty())
                return handled
            }
            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                t.url = url.orEmpty(); t.progress = 5
                // A page that got here without shouldOverrideUrlLoading (redirect, history) never keeps file access it shouldn't.
                if (!fileAccessFor(url)) allowFiles(t, url.orEmpty())
                t.pulls = 0; t.pullingUrl = ""; t.loadStart = System.currentTimeMillis(); t.lastPull = t.loadStart
                if (t.loaded.isCompleted) t.loaded = CompletableDeferred()
                t.maskJs?.let { v.evaluateJavascript(it, null) }
                t.fillAccounts = emptyList()
            }
            override fun onLoadResource(v: WebView, url: String?) {
                if (t.progress >= 100 || url.isNullOrBlank() || url.startsWith("data:")) return
                t.pulls++; t.pullingUrl = url; t.lastPull = System.currentTimeMillis()
            }
            override fun onPageFinished(v: WebView, url: String?) {
                t.url = url.orEmpty(); t.progress = 100; t.canBack = v.canGoBack(); t.canForward = v.canGoForward()
                t.loaded.complete(Unit)
                // v0.33.3: room under the page for the floating toolbar, so the last lines can scroll above it.
                if (url.orEmpty().startsWith("http")) v.evaluateJavascript(BOTTOM_PAD_JS, null)
                if (!t.isPrivate && !url.isNullOrBlank() && url != "about:blank" && !SavedPages.isSavedUrl(url)) BrowserData.addHistory(app, v.title ?: url, url)
                if (!t.isPrivate) {
                    if (t.captureLate) v.evaluateJavascript(BrowserLogins.CAPTURE_JS, null)
                    offerSoon(t, 700)
                    scheduleSave()   // the debounced save also flushes cookies (off the main thread)
                }
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                t.url = url.orEmpty(); t.canBack = v.canGoBack(); t.canForward = v.canGoForward()
                if (!t.isPrivate) { offerSoon(t, 1200); scheduleSave() }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { t.progress = p }
            // In the background there's no Activity to show a page's dialog: answer it here and tell Blaze.
            override fun onJsAlert(v: WebView, url: String?, message: String?, r: android.webkit.JsResult): Boolean {
                if (visible(t)) return false
                t.lastDialog = "alert: " + message.orEmpty().take(300); r.confirm(); return true
            }
            override fun onJsConfirm(v: WebView, url: String?, message: String?, r: android.webkit.JsResult): Boolean {
                if (visible(t)) return false
                t.lastDialog = "confirm (answered Cancel): " + message.orEmpty().take(300); r.cancel(); return true
            }
            override fun onJsPrompt(v: WebView, url: String?, message: String?, def: String?, r: android.webkit.JsPromptResult): Boolean {
                if (visible(t)) return false
                t.lastDialog = "prompt (answered Cancel): " + message.orEmpty().take(300); r.cancel(); return true
            }
            override fun onJsBeforeUnload(v: WebView, url: String?, message: String?, r: android.webkit.JsResult): Boolean {
                if (visible(t)) return false
                r.confirm(); return true
            }
            override fun onReceivedTitle(v: WebView, title: String?) { t.title = title?.ifBlank { null } ?: t.url }
            override fun onReceivedIcon(v: WebView, icon: Bitmap?) { t.icon = icon?.let { smallIcon(it) }?.asImageBitmap() }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean =
                filePicker?.invoke(cb, p) ?: run { cb.onReceiveValue(null); false }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) { fullView = view; fullCallback = callback }
            override fun onHideCustomView() { fullView = null; fullCallback?.onCustomViewHidden(); fullCallback = null }
        }
        w.setDownloadListener { dl, userAgent, disposition, mime, _ -> download(dl, userAgent, disposition, mime) }
        installMask(t)
        // v0.29.7 saved passwords: normal tabs only. Private tabs never get the capture script or the bridge.
        t.captureLate = !isPrivate && installLoginBridge(t)
        applyPerf(t, w)
        if (!visible(t)) background(t)
        return w
    }
    private fun display(u: String) = u.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')

    // ---------- v0.29.7 saved passwords ----------
    /**
     * Listens for the page's sign-in forms (origin-checked WebMessageListener, main frame only). Returns true when the
     * capture script has to be injected after each load (WebView too old for document-start scripts).
     */
    private fun installLoginBridge(t: Tab): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        try {
            WebViewCompat.addWebMessageListener(t.web, BrowserLogins.BRIDGE, setOf("*")) { _, msg, origin, mainFrame, _ ->
                if (mainFrame) onLoginMessage(t, msg.data.orEmpty(), origin)
            }
        } catch (_: Throwable) { return false }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { WebViewCompat.addDocumentStartJavaScript(t.web, BrowserLogins.CAPTURE_JS, setOf("*")); return false } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: WebViewCompat.addDocumentStartJavaScript", e) }
        }
        return true
    }

    private fun onLoginMessage(t: Tab, data: String, origin: Uri) {
        if (t.isPrivate || !tabs.contains(t)) return
        val originUrl = origin.toString()
        if (!com.blaze.assistant.connect.UrlSafety.autofillAllowed("$originUrl/")) return   // https (or the LAN) only
        val host = origin.host?.lowercase()?.removePrefix("www.").orEmpty()
        if (host.isEmpty() || !SiteDomain.sameSite(host, SiteDomain.hostOf(t.url).ifEmpty { host })) return
        val o = try { JSONObject(data) } catch (_: Exception) { return }
        when (o.optString("t")) {
            "form" -> {
                val acc = try { BrowserLogins.forHost(host) } catch (_: Throwable) { emptyList() }
                if (acc.isNotEmpty()) t.fillAccounts = acc
            }
            "submit" -> {
                val user = o.optString("u").trim().take(200); val pass = o.optString("p").take(500)
                if (pass.isEmpty()) return
                SecureInput.hide(pass)   // never echoed to the agent in page text
                if (BrowserLogins.isNever(host)) return
                when (val d = BrowserLogins.decide(host, user, pass)) {
                    is BrowserLogins.Decision.Same -> BrowserLogins.touch(d.id)
                    is BrowserLogins.Decision.Save, is BrowserLogins.Decision.Update -> {
                        offerWaiting = LoginOffer(t.id, host, SiteDomain.registrable(host), user, pass, d is BrowserLogins.Decision.Update)
                        offerSoon(t, 3500)   // shown after the next page (or in 3.5 s on single-page sign-ins)
                    }
                }
            }
        }
    }

    private fun offerSoon(t: Tab, ms: Long) {
        if (offerWaiting?.tabId != t.id) return
        main.postDelayed({ offerWaiting?.takeIf { it.tabId == t.id }?.let { loginOffer = it; offerWaiting = null } }, ms)
    }

    /** "Save" / "Update" on the prompt. */
    fun acceptLoginOffer() {
        val o = loginOffer ?: return; loginOffer = null
        val ok = try { BrowserLogins.save(o.host, o.user, o.pass) != null } catch (_: Throwable) { false }
        toast(if (!ok) "Couldn't save the password." else if (o.update) "Password updated" else "Password saved for ${o.site}")
    }
    fun neverForSite() { val o = loginOffer ?: return; loginOffer = null; BrowserLogins.addNever(o.host); toast("Blaze won't offer to save passwords on ${o.site}.") }
    fun dismissLoginOffer() { loginOffer = null }

    /** Fills a saved account into the page (never submits). https (or LAN) and the same site only. */
    fun fillLogin(t: Tab, l: BrowserLogins.Login) {
        t.fillAccounts = emptyList()
        if (t.isPrivate) return
        if (!com.blaze.assistant.connect.UrlSafety.autofillAllowed(t.url)) { toast("This page isn't secure (http), so Blaze won't fill a password here."); return }
        if (!SiteDomain.sameSite(l.host, SiteDomain.hostOf(t.url))) return
        val pass = l.password
        if (pass.isEmpty()) { toast("This password can't be read on this phone. Save it again."); return }
        SecureInput.hide(pass)
        t.web.evaluateJavascript(BrowserLogins.fillJs(l.user, pass), null)
        try { BrowserLogins.touch(l.id) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: BrowserLogins.touchl.id", e) }
    }

    // ---------- v0.29.7 saved pages ----------
    /** Saves the tab's page as a web archive plus a text copy. [done] gets a message for the user (main thread). */
    fun savePage(t: Tab, done: (String) -> Unit) {
        val dir = SavedPages.dir() ?: return done("Couldn't save this page.")
        val url = t.url; val title = t.title.ifBlank { url }
        if (!url.startsWith("http")) return done("Only web pages can be saved.")
        try { dir.mkdirs() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: dir.mkdirs", e) }
        val id = SavedPages.newId(System.currentTimeMillis(), SavedPages.list().map { it.id })
        val f = File(dir, SavedPages.archiveName(id))
        try {
            t.web.saveWebArchive(f.absolutePath, false) { path ->
                if (path == null || !f.exists() || f.length() == 0L) { f.delete(); done("Couldn't save this page."); return@saveWebArchive }
                t.web.evaluateJavascript("(function(){return document.body?document.body.innerText:''})()") { r ->
                    val body = try { JSONTokener(r ?: "\"\"").nextValue() as? String } catch (_: Exception) { null }.orEmpty()
                    Thread {
                        var hasText = false
                        if (body.isNotBlank()) try {
                            val whenText = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date())
                            File(dir, SavedPages.textName(id)).writeText(SavedPages.textCopy(title, url, whenText, SecureInput.scrub(body))); hasText = true
                        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: val whenText java.text.DateFormat.getDat", e) }
                        SavedPages.add(SavedPages.Page(id, title, url, System.currentTimeMillis(), f.length(), hasText))
                        main.post { done("Saved for offline: ${title.take(60)}") }
                    }.start()
                }
            }
        } catch (e: Throwable) { done("Couldn't save this page: ${e.message}") }
    }

    /** Opens a saved page in a new normal tab, from the file on the phone (works offline). */
    fun openSaved(ctx: Context, p: SavedPages.Page): Boolean {
        val f = SavedPages.archive(p.id)?.takeIf { it.exists() } ?: return false
        val t = newTab(ctx)
        t.web.settings.allowFileAccess = true
        t.title = p.title
        load(t, Uri.fromFile(f).toString())
        return true
    }

    /** True while the Browser screen shows this tab (an Activity is attached to its dialogs). */
    private fun visible(t: Tab) = t.ctxWrap.baseContext !== app && t.webOrNull?.isAttachedToWindow == true

    /**
     * The background browser: a WebView that isn't on screen still needs a size, or pages lay out at 0×0
     * and every element looks invisible to browser_read. Give it the phone's screen size, offscreen.
     */
    fun layoutOffscreen(w: WebView) {
        if (w.isAttachedToWindow) return
        try {
            val dm = app.resources.displayMetrics
            val wp = dm.widthPixels.coerceAtLeast(360); val hp = dm.heightPixels.coerceAtLeast(640)
            w.measure(View.MeasureSpec.makeMeasureSpec(wp, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hp, View.MeasureSpec.EXACTLY))
            w.layout(0, 0, wp, hp)
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: val dm app.resources.displayMetrics", e) }
    }

    // ---------- Unreleased: hardware-aware tab memory ----------
    /** Per-WebView settings from the device budget: pre-raster only where there's memory for it. */
    private fun applyPerf(t: Tab, w: WebView) {
        val b = BrowserPerf.budget()
        if (android.os.Build.VERSION.SDK_INT >= 23) try { w.settings.offscreenPreRaster = b.offscreenPreRaster } catch (_: Throwable) { }
    }

    /** The tab is on screen: full renderer priority, page timers / animations running. */
    private fun foreground(t: Tab) {
        val w = t.webOrNull ?: return
        try { w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true) } catch (_: Throwable) { }
        try { w.onResume() } catch (_: Throwable) { }
    }

    /**
     * The tab left the screen: the page is told it's hidden (it throttles its own timers and animations) and its
     * renderer becomes the first one Android may reclaim, instead of the app. The tab on screen keeps running so
     * Blaze can keep driving it in the background.
     */
    private fun background(t: Tab) {
        val w = t.webOrNull ?: return
        if (t === tab()) return
        try { w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true) } catch (_: Throwable) { }
        try { w.onPause() } catch (_: Throwable) { }
    }

    private fun uses(): List<PerfPolicy.TabUse> { val cur = tab()
        return tabs.map { PerfPolicy.TabUse(it.id, it.live, it === cur, it.progress < 100 || (it === cur && fullView != null), it.lastShown) } }

    /** Discards the least recently shown background WebViews beyond the device's live-WebView budget. */
    fun enforceLiveCap(max: Int = BrowserPerf.budget().maxLiveWebViews) {
        if (!::app.isInitialized) return
        val ids = PerfPolicy.toDiscard(uses(), max).toSet()
        if (ids.isEmpty()) return
        tabs.filter { it.id in ids }.forEach { discard(it) }
    }

    /** Frees a background tab's WebView; its back/forward state is kept in memory and restored when it's shown. */
    private fun discard(t: Tab) {
        val w = t.webOrNull ?: return
        if (t === tab()) return
        val snap = try { snapOf(t) } catch (_: Throwable) { TabStore.Snap(t.url, t.title) }
        t.pending = snap.copy(url = snap.url.ifBlank { t.url.takeIf { u -> u.startsWith("http") || SavedPages.isSavedUrl(u) }.orEmpty() })
        try { t.mask?.remove() } catch (_: Throwable) { }
        t.mask = null; t.maskJs = null
        t.webOrNull = null
        try { (w.parent as? ViewGroup)?.removeView(w); w.stopLoading(); w.destroy() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: discard", e) }
        if (!t.loaded.isCompleted) t.loaded.complete(Unit)
    }

    private fun trimThumbs(keep: Int) {
        val have = tabs.filter { it.thumb != null }.map { it.id }.toSet()
        val drop = PerfPolicy.thumbsToDrop(uses(), have, keep).toSet()
        if (drop.isNotEmpty()) tabs.forEach { if (it.id in drop) it.thumb = null }
    }

    /** Called from the Application's onTrimMemory: sleeps background tabs and drops thumbnails by pressure and device. */
    fun onTrimMemory(level: Int) {
        if (!::app.isInitialized) return
        main.post {
            try {
                val b = BrowserPerf.budget()
                enforceLiveCap(PerfPolicy.keepLiveOnTrim(level, b))
                trimThumbs(PerfPolicy.keepThumbsOnTrim(level, b))
            } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: onTrimMemory", e) }
        }
    }

    /** Favicons can arrive at 192-512 px (up to 1 MB each); the UI draws them at 16-36 dp. */
    private fun smallIcon(b: Bitmap): Bitmap {
        val m = PerfPolicy.ICON_MAX_PX
        if (b.width <= m && b.height <= m) return b
        val k = m.toFloat() / maxOf(b.width, b.height)
        return try { Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true) } catch (_: Throwable) { b }
    }

    /** Replaces a tab whose renderer died with a fresh one on the same page. */
    private fun reopen(t: Tab) {
        val i = tabs.indexOf(t); if (i < 0) return
        val url = t.url.takeIf { it.startsWith("http") }
        val keep = tabs.getOrNull(current)?.takeIf { it !== t }
        try { t.webOrNull?.let { w -> (w.parent as? ViewGroup)?.removeView(w); w.destroy() }; t.webOrNull = null } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BrowserEngine: t.web.parent as ViewGroup.removeViewt.we", e) }
        tabs.removeAt(i)
        val n = newTab(app, url, t.isPrivate)
        tabs.remove(n); tabs.add(i.coerceAtMost(tabs.size), n)
        current = keep?.let { tabs.indexOf(it) }?.takeIf { it >= 0 } ?: tabs.indexOf(n)
        lastCrash = "The page ${url?.take(80) ?: ""} stopped (Android closed it to save memory) and was reopened; anything typed there was lost."
    }

    fun exitFull() { fullCallback?.onCustomViewHidden(); fullView = null; fullCallback = null }

    fun close(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        t.webOrNull?.let { w -> (w.parent as? ViewGroup)?.removeView(w); w.stopLoading(); w.destroy() }
        t.webOrNull = null
        tabs.removeAt(i)
        if (current >= tabs.size) current = (tabs.size - 1).coerceAtLeast(0)
        if (t.isPrivate && tabs.none { it.isPrivate }) clearPrivateData()
        if (loginOffer?.tabId == t.id) loginOffer = null
        if (!t.isPrivate) scheduleSave()
    }

    /** Closes every tab on one side of the tab grid. */
    fun closeAll(isPrivate: Boolean) { for (i in tabs.indices.reversed()) if (tabs[i].isPrivate == isPrivate) close(i) }

    fun closeAll() { while (tabs.isNotEmpty()) close(tabs.lastIndex) }

    /** Point a tab's dialogs (alerts, pickers) at the visible Activity, or back at the app. */
    fun attach(ctx: Context?) { tabs.forEach { it.ctxWrap.baseContext = ctx ?: app } }

    private fun defaultUa(w: WebView) = w.settings.userAgentString
    private fun ua(base: String, desk: Boolean) =
        if (desk) base.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "").replace("; wv", "") else base.replace("; wv", "")

    fun useDesktop(on: Boolean) {
        desktop = on
        tabs.forEach { t -> t.webOrNull?.let { w -> w.settings.userAgentString = ua(WebSettings.getDefaultUserAgent(app), on); w.settings.useWideViewPort = true } }
        tab()?.web?.reload()
    }

    /** Text from the address bar → a URL (or a web search). */
    fun toUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return "about:blank"
        if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(s) || s.startsWith("about:")) return s
        if (!s.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(s)) return "https://$s"
        if (!s.contains(' ') && s.startsWith("localhost")) return "http://$s"
        return "https://www.google.com/search?q=" + Uri.encode(s)
    }

    fun load(t: Tab, input: String) {
        val u = toUrl(input)
        t.pending = null
        if (u.startsWith("file:") && !SavedPages.isSavedUrl(u)) { toast("Blaze only opens its own saved pages from the phone."); return }
        allowFiles(t, u)
        t.url = u; t.loaded = CompletableDeferred()
        t.web.loadUrl(u)
    }

    private fun download(url: String, userAgent: String?, disposition: String?, mime: String?) {
        try {
            if (url.startsWith("blob:") || url.startsWith("data:")) { toast("This download type isn't supported yet."); return }
            val name = URLUtil.guessFileName(url, disposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime).setTitle(name).setDescription("Downloading with Blaze")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            userAgent?.let { req.addRequestHeader("User-Agent", it) }
            app.getSystemService(DownloadManager::class.java).enqueue(req)
            toast("Downloading $name")
        } catch (e: Exception) { toast("Download failed: ${e.message}") }
    }

    private fun toast(s: String) = main.post { Toast.makeText(app, s, Toast.LENGTH_SHORT).show() }

    // ---------- what Blaze uses ----------
    private suspend fun js(t: Tab, code: String): Any? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            t.web.evaluateJavascript(code) { r -> if (cont.isActive) cont.resume(try { JSONTokener(r ?: "null").nextValue() } catch (_: Exception) { r }) }
        }
    }

    private suspend fun waitLoad(t: Tab, ms: Long = 15_000) {
        withTimeoutOrNull(ms) { t.loaded.await() }
        kotlinx.coroutines.delay(500)   // let scripts render
    }

    private suspend fun ensureTab(ctx: Context): Tab = withContext(Dispatchers.Main) { tab() ?: newTab(ctx) }

    suspend fun open(ctx: Context, url: String, newTab: Boolean, isPrivate: Boolean = false): String {
        val t = withContext(Dispatchers.Main) {
            if (newTab || tabs.isEmpty() || tab()?.isPrivate != isPrivate) newTab(ctx, url, isPrivate) else tab()!!.also { load(it, url) }
        }
        waitLoad(t)
        return "Opened ${t.title.take(80)} (${t.url}).\n\n" + read(ctx, 3500)
    }

    /** Page text plus numbered interactive elements Blaze can click or type into. */
    suspend fun read(ctx: Context, max: Int = 7000): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open. Use browser_open first."
        val r = js(t, READ_JS) as? JSONObject ?: return "Couldn't read this page."
        val items = r.optJSONArray("items") ?: JSONArray()
        val sb = StringBuilder()
        sb.append("Page: ").append(r.optString("title")).append(" — ").append(r.optString("url")).append('\n')
        sb.append(r.optString("text").take(max)).append("\n\nElements:\n")
        for (i in 0 until minOf(items.length(), 120)) sb.append(items.getString(i)).append('\n')
        notes(t, r)?.let { sb.append('\n').append(it) }
        return SecureInput.scrub(sb.toString())
    }

    /**
     * v0.27.1: the open page for "Ask Blaze about this page", as a person sees it (title, readable text, button / link /
     * field labels, pictures and their descriptions). Field values are never read; secrets are scrubbed.
     */
    suspend fun capture(ctx: Context, max: Int = 30_000): PageAsk.Capture {
        val t = withContext(Dispatchers.Main) { tab() } ?: return PageAsk.Capture.NONE
        val r = try { js(t, ASK_JS) as? JSONObject } catch (_: Exception) { null }
            ?: return PageAsk.Capture(t.title, t.url, "")
        fun list(k: String) = r.optJSONArray(k)?.let { a -> (0 until a.length()).map { SecureInput.scrub(a.optString(it)) } } ?: emptyList()
        return PageAsk.Capture(title = r.optString("title").ifBlank { t.title }, url = r.optString("url").ifBlank { t.url },
            text = SecureInput.scrub(r.optString("text").take(max)), labels = list("labels"), images = r.optInt("imgs"),
            alts = list("alts"), password = r.optBoolean("pw"))
    }

    /** Things Blaze should know about the page: a dialog it answered, a renderer restart, a site that refuses passwords. */
    private fun notes(t: Tab, r: JSONObject?): String? {
        val out = ArrayList<String>()
        lastCrash?.let { out += it; lastCrash = null }
        t.lastDialog?.let { out += "The page showed a dialog while in the background ($it)."; t.lastDialog = null }
        if (r != null) {
            val items = r.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            SignInHints.check(r.optString("text"), items)?.let { out += it }
        }
        return out.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Notes for the page as it is now (after a click or a submit). */
    private suspend fun notesNow(t: Tab): String = notes(t, js(t, READ_JS) as? JSONObject)?.let { "\n$it" }.orEmpty()

    /** Host of the page in the current tab ("" when none). */
    fun host(): String = tab()?.url?.let { SecureInput.hostOf(it) }.orEmpty()
    fun url(): String = tab()?.url.orEmpty()

    /** True when the current page says this account can't use a password. */
    suspend fun passwordRefused(): String? {
        val t = withContext(Dispatchers.Main) { tab() } ?: return null
        val r = js(t, READ_JS) as? JSONObject ?: return null
        val items = r.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return SignInHints.check(r.optString("text"), items)
    }

    /**
     * Types a value the user gave in a secure form into the page. The value comes from SecureInput,
     * never from the model, and the field is marked so browser_read never shows what's in it.
     */
    suspend fun fillSecret(f: SecureInput.Field, value: String): Boolean {
        val t = withContext(Dispatchers.Main) { tab() } ?: return false
        if (!com.blaze.assistant.connect.UrlSafety.autofillAllowed(t.url)) return false   // v0.28.2: never on plain http (LAN excepted)
        val sel = f.target.toIntOrNull()?.let { "document.querySelector('[data-bz=\"$it\"]')" }
            ?: f.target.takeIf { it.isNotBlank() }?.let { "bzFind(${JSONObject.quote(it)}, true)" }
            ?: "bzFirst(${JSONObject.quote(SecureInput.autoSelector(f.kind))})"
        val r = js(t, """(function(){$FIND_JS
            function bzFirst(s){var l=[].slice.call(document.querySelectorAll(s)).filter(function(e){return bzVis(e)&&!e.disabled&&!e.readOnly&&!e.hasAttribute('data-bz-secret');});return l[0]||null;}
            var e=$sel; if(!e) return 'missing'; e.scrollIntoView({block:'center'}); e.focus();
            var v=${JSONObject.quote(value)};
            var proto = e.tagName=='TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            Object.getOwnPropertyDescriptor(proto,'value').set.call(e, v);
            e.setAttribute('data-bz-secret','1');
            e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
            return 'ok'; })()""")
        return r == "ok"
    }

    /** Submits the form the secure fields are in (its submit button, else requestSubmit, else Enter). */
    suspend fun submitSecret(): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        js(t, """(function(){var l=document.querySelectorAll('[data-bz-secret]'); var e=l[l.length-1]; if(!e) return 'missing';
            var f=e.form; var b=f&&f.querySelector('button[type=submit],input[type=submit],button:not([type])');
            if(b){b.click();return 'ok';} if(f){ if(f.requestSubmit) f.requestSubmit(); else f.submit(); return 'ok'; }
            var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true};
            e.dispatchEvent(new KeyboardEvent('keydown',o)); e.dispatchEvent(new KeyboardEvent('keyup',o)); return 'ok'; })()""")
        kotlinx.coroutines.delay(400)
        if (t.progress < 100) waitLoad(t, 12_000) else kotlinx.coroutines.delay(800)
        return "Submitted. Now on: ${t.title.take(80)} (${t.url})" + notesNow(t)
    }

    /** v0.28.2: what [click] would press (its words), without pressing it; "" when not found. */
    suspend fun labelOf(n: Int?, text: String?): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return ""
        val sel = if (n != null && n >= 0) "document.querySelector('[data-bz=\"$n\"]')" else "bzFind(${JSONObject.quote(text.orEmpty())})"
        return (js(t, "(function(){$FIND_JS var e=$sel; if(!e) return ''; return (e.innerText||e.value||e.getAttribute('aria-label')||'').slice(0,80);})()") as? String).orEmpty()
    }

    suspend fun click(ctx: Context, n: Int?, text: String?): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        val sel = if (n != null && n >= 0) "document.querySelector('[data-bz=\"$n\"]')" else "bzFind(${JSONObject.quote(text.orEmpty())})"
        val r = js(t, "(function(){$FIND_JS var e=$sel; if(!e) return 'missing'; e.scrollIntoView({block:'center'}); e.focus&&e.focus(); e.click(); return 'ok:'+(e.innerText||e.value||e.getAttribute('aria-label')||e.tagName).slice(0,60);})()")
        if (r == "missing") return "No such element. Call browser_read to get fresh numbers."
        kotlinx.coroutines.delay(300)
        if (t.progress < 100) waitLoad(t, 12_000) else kotlinx.coroutines.delay(500)
        return SecureInput.scrub("Clicked ${r.toString().removePrefix("ok:")}. Now on: ${t.title.take(80)} (${t.url})" + notesNow(t))
    }

    suspend fun type(ctx: Context, n: Int?, label: String?, text0: String, submit: Boolean): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        // {{secret:name}} placeholders: swapped for the real value here, at the last moment; the model only ever saw the placeholder.
        val secret = SecureInput.hasPlaceholder(text0)
        if (secret && !com.blaze.assistant.connect.UrlSafety.autofillAllowed(t.url)) return com.blaze.assistant.connect.UrlSafety.AUTOFILL_REFUSED
        val text = if (!secret) text0 else SecureInput.resolve(text0, SecureInput.hostOf(t.url)).let { (v, missing) ->
            v ?: return "No value for ${missing.joinToString { SecureInput.placeholder(it) }} yet. Ask the user with browser_secure_input (it shows a secure form in the chat)." }
        val sel = if (n != null && n >= 0) "document.querySelector('[data-bz=\"$n\"]')"
            else if (!label.isNullOrBlank()) "bzFind(${JSONObject.quote(label)}, true)"
            else "(document.activeElement && /INPUT|TEXTAREA/.test(document.activeElement.tagName) ? document.activeElement : document.querySelector('input[type=search],input[type=text],input:not([type]),textarea'))"
        val r = js(t, """(function(){$FIND_JS var e=$sel; if(!e) return 'missing'; e.scrollIntoView({block:'center'}); e.focus();
            var v=${JSONObject.quote(text)};
            if (e.isContentEditable) { e.innerText = v; } else {
              var proto = e.tagName=='TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto,'value').set; setter.call(e, v); }
            if (${if (secret) "true" else "false"}) e.setAttribute('data-bz-secret','1');
            e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
            if (${if (submit) "true" else "false"}) {
              var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true};
              e.dispatchEvent(new KeyboardEvent('keydown',o)); e.dispatchEvent(new KeyboardEvent('keypress',o)); e.dispatchEvent(new KeyboardEvent('keyup',o));
              if (e.form) { if (e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit(); }
            }
            return 'ok'; })()""")
        if (r == "missing") return "No text field found. Call browser_read to see the fields."
        if (submit) { kotlinx.coroutines.delay(400); if (t.progress < 100) waitLoad(t, 12_000) }
        return SecureInput.scrub(if (submit) "Typed and submitted. Now on: ${t.title.take(80)} (${t.url})" + notesNow(t) else "Typed \u201c${text0.take(60)}\u201d.")
    }

    suspend fun scroll(ctx: Context, down: Boolean): String {
        val t = withContext(Dispatchers.Main) { tab() } ?: return "No page open."
        js(t, "window.scrollBy(0, ${if (down) "" else "-"}window.innerHeight*0.85)")
        kotlinx.coroutines.delay(400)
        return "Scrolled ${if (down) "down" else "up"}."
    }

    suspend fun back(ctx: Context): String = withContext(Dispatchers.Main) {
        val t = tab() ?: return@withContext "No page open."
        if (back(t)) "Went back." else "Nothing to go back to."
    }.also { tab()?.let { waitLoad(it, 8000) } }

    fun tabList(): String = if (tabs.isEmpty()) "No tabs open." else
        tabs.mapIndexed { i, t -> "${if (i == current) "*" else " "}[$i]${if (t.isPrivate) " (private)" else ""} ${t.title.take(60)} — ${t.url}" }.joinToString("\n")

    suspend fun switchTab(i: Int): String = withContext(Dispatchers.Main) {
        if (i in tabs.indices) { current = i; "Switched to ${tabs[i].title}." } else "No tab $i."
    }

    private const val FIND_JS = """
      function bzVis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none';}
      function bzLabel(e){return ((e.innerText||'')+' '+(e.value||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('placeholder')||'')+' '+(e.getAttribute('title')||'')+' '+(e.getAttribute('name')||'')+' '+(e.getAttribute('alt')||'')).replace(/\s+/g,' ').trim().toLowerCase();}
      function bzFind(q,field){q=q.toLowerCase();var sel=field?'input,textarea,[contenteditable=true]':'a,button,input,select,textarea,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[onclick],label';
        var all=[].slice.call(document.querySelectorAll(sel)).filter(bzVis);var best=null,bs=1e9;
        all.forEach(function(e){var l=bzLabel(e);if(!l)return;var i=l.indexOf(q);if(i<0)return;var s=l.length-q.length+i;if(s<bs){bs=s;best=e;}});return best;}
    """

    private const val ASK_JS = """(function(){
      function vis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none';}
      var labels=[];
      [].slice.call(document.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=tab]')).filter(vis).slice(0,150).forEach(function(e){
        var tag=e.tagName.toLowerCase(), field=(tag=='input'||tag=='textarea'||tag=='select');
        var l=(field?'':(e.innerText||''))||e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('name')||'';
        l=l.replace(/\s+/g,' ').trim().slice(0,60); if(!l) return;
        labels.push(field?'Field: '+l:l);
      });
      var imgs=[].slice.call(document.querySelectorAll('img,[role=img]')).filter(function(e){if(!vis(e))return false;var r=e.getBoundingClientRect();return r.width>=60&&r.height>=60;});
      var alts=[]; imgs.forEach(function(e){var a=(e.getAttribute('alt')||e.getAttribute('aria-label')||e.getAttribute('title')||'').replace(/\s+/g,' ').trim(); if(a) alts.push(a.slice(0,100));});
      var pw=[].slice.call(document.querySelectorAll('input[type=password]')).some(vis);
      var text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').trim();
      return {title:document.title,url:location.href,text:text,labels:labels,imgs:imgs.length,alts:alts.slice(0,40),pw:pw};
    })()"""

    private const val READ_JS = """(function(){
      function vis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none';}
      var els=[].slice.call(document.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[onclick],[contenteditable=true]')).filter(vis);
      var items=[];
      els.slice(0,150).forEach(function(e,i){ e.setAttribute('data-bz',i);
        var tag=e.tagName.toLowerCase(), type=e.getAttribute('type')||'';
        var hide = type=='password' || e.hasAttribute('data-bz-secret');
        var label=(e.innerText||(hide?(e.value?'(filled, hidden)':''):e.value)||e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('name')||e.getAttribute('alt')||'').replace(/\s+/g,' ').trim().slice(0,80);
        var kind = tag=='a'?'link':(tag=='input'||tag=='textarea'||e.isContentEditable)?('field'+(type?':'+type:'')):tag=='select'?'select':'button';
        if (tag=='input' && /checkbox|radio/.test(type)) kind=type+(e.checked?' (checked)':'');
        if (!label && kind=='link') return;
        items.push('['+i+'] '+kind+' "'+label+'"'+(tag=='a'&&e.href&&e.href.indexOf('javascript')!=0?' → '+e.href.slice(0,90):''));
      });
      var text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').trim();
      return {title:document.title,url:location.href,text:text,items:items};
    })()"""
}

/**
 * Bookmarks and history, kept in filesDir/browser.json.
 * Unreleased: reads and writes are off the main thread. The file is parsed once on a background thread ([warm]),
 * kept in memory, and written at most every [WRITE_DELAY_MS] on one writer thread (it used to be re-serialised and
 * rewritten on the main thread after every page load, up to 300 history entries each time).
 */
object BrowserData {
    data class Entry(val title: String, val url: String, val time: Long = System.currentTimeMillis())
    private const val WRITE_DELAY_MS = 2000L
    private fun file(ctx: Context) = File(ctx.filesDir, "browser.json")
    private var cache: JSONObject? = null
    private val lock = Any()
    private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    @Volatile private var writeQueued = false

    /** Parses the file on a background thread so the first start page / history read doesn't touch the disk. */
    fun warm(ctx: Context) { val app = ctx.applicationContext; writer.execute { try { data(app) } catch (_: Throwable) { } } }

    private fun data(ctx: Context): JSONObject = synchronized(lock) {
        cache ?: try { JSONObject(file(ctx).readText()) } catch (_: Exception) {
            JSONObject().put("bookmarks", JSONArray()).put("history", JSONArray()) }.also { cache = it }
    }

    private fun save(ctx: Context) {
        if (writeQueued) return
        writeQueued = true
        val app = ctx.applicationContext
        writer.schedule({
            writeQueued = false
            try {
                val text = synchronized(lock) { data(app).toString() }
                val f = file(app); val tmp = File(f.path + ".tmp")
                tmp.writeText(text); if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
            } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("BrowserData: save", e) }
        }, WRITE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    // 0.45.19-speed: Blaze Phone's site resolver reads history off the main thread while the browser writes it.
    // Reads/writes of the cached JSON share [lock]; read-modify-write callers (toggleBookmark, addHistory) are @Synchronized.
    private fun list(ctx: Context, k: String): List<Entry> = synchronized(lock) {
        val a = data(ctx).optJSONArray(k) ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.optString("t"), it.optString("u"), it.optLong("at")) }
    }
    private fun put(ctx: Context, k: String, l: List<Entry>) {
        val arr = JSONArray().apply { l.forEach { put(JSONObject().put("t", it.title).put("u", it.url).put("at", it.time)) } }
        synchronized(lock) { data(ctx).put(k, arr) }
        save(ctx)
    }

    fun bookmarks(ctx: Context) = list(ctx, "bookmarks")
    fun history(ctx: Context) = list(ctx, "history")
    fun isBookmarked(ctx: Context, url: String) = bookmarks(ctx).any { it.url == url }
    @Synchronized fun toggleBookmark(ctx: Context, title: String, url: String) {
        val b = bookmarks(ctx).toMutableList()
        if (b.removeAll { it.url == url }.not()) b.add(0, Entry(title.ifBlank { url }, url))
        put(ctx, "bookmarks", b)
    }
    /** Called on every page load: the in-memory list is updated now; the file write is batched off the main thread. */
    @Synchronized fun addHistory(ctx: Context, title: String, url: String) {
        val h = history(ctx).filter { it.url != url }.toMutableList()
        h.add(0, Entry(title, url)); put(ctx, "history", h.take(300))
    }
    fun clearHistory(ctx: Context) = put(ctx, "history", emptyList())
}
