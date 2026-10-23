package com.blaze.assistant.browser.phone

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.blaze.assistant.browser.BrowserLogins
import com.blaze.assistant.browser.SecureInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import com.blaze.assistant.browser.phone.PhoneDriver.ActResult
import com.blaze.assistant.browser.phone.PhoneDriver.FocusInfo
import com.blaze.assistant.browser.phone.PhoneDriver.Target

/**
 * 0.45.19 Blaze Phone browser: the engine. One Blaze-owned WebView on Blaze's own off-screen display ([PhoneSurface]),
 * in a WebView profile per site ([PhonePolicy.sessionFor]) so sign-ins persist across runs and never mix with the
 * user's own browser or its private tabs. Input is real: MotionEvents and KeyEvents dispatched to the WebView (the page
 * sees trusted events); page scripts only read, or act as a fallback when a touch can't land.
 *
 * The user can watch ([live]), take control (the real WebView moves to [PhoneBrowserActivity], interactive) and hand
 * back (Blaze resumes with a fresh read). Everything is released after [PhonePolicy.IDLE_MS] without use, or when the
 * user finishes a session they opened themselves.
 */
object PhoneBrowser {
    data class Live(
        val mode: PhonePolicy.Mode = PhonePolicy.Mode.OFF,
        val url: String = "",
        val title: String = "",
        val step: String = "",
        val reason: String? = null,
        val frameNo: Long = 0,
        val at: Long = 0,
        val surfaceOk: Boolean = false,
        val lowRam: Boolean = false,
        /** An agent turn is waiting on Hand back. */
        val agentWaiting: Boolean = false,
    )

    private val _live = MutableStateFlow(Live())
    /** Bumped when a new WebView is made (another site's session, a renderer restart): the full screen re-hosts it. */
    private val _webGen = MutableStateFlow(0)
    val webGen: StateFlow<Int> = _webGen
    @Volatile private var watchers = 0
    val live: StateFlow<Live> = _live
    /** The newest live frame (a reused buffer: draw it now, don't keep it). Updated with [Live.frameNo]. */
    @Volatile var frame: Bitmap? = null
        private set
    @Volatile var frameW = 0
        private set
    @Volatile var frameH = 0
        private set

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var wrap: MutableContextWrapper? = null
    private var session: PhonePolicy.Session? = null
    private var surface: PhoneSurface? = null
    private var cfg: PhonePolicy.Config? = null
    @Volatile private var loaded = CompletableDeferred(Unit)
    @Volatile private var lastActive = 0L
    @Volatile private var lastDialog: String? = null
    @Volatile private var lastCrash: String? = null
    @Volatile private var handBack: CompletableDeferred<Unit>? = null

    val active: Boolean get() = _live.value.mode != PhonePolicy.Mode.OFF
    val multiProfile: Boolean get() = try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (_: Throwable) { false }

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    private fun prefs() = app.getSharedPreferences("blaze_phone", Context.MODE_PRIVATE)
    fun sites(): List<String> = if (!::app.isInitialized) emptyList() else PhonePolicy.Sites.decode(prefs().getString("sites", null))
    private fun rememberSite(site: String) { if (site.isNotBlank()) prefs().edit().putString("sites", PhonePolicy.Sites.add(prefs().getString("sites", null), site)).apply() }

    fun config(): PhonePolicy.Config = cfg ?: run {
        val am = app.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { try { am.getMemoryInfo(it) } catch (_: Throwable) { } }
        PhonePolicy.config(mi.totalMem, try { am.isLowRamDevice } catch (_: Throwable) { false })
    }.also { cfg = it }

    private fun set(f: (Live) -> Live) { _live.value = f(_live.value) }
    fun step(what: String) { set { it.copy(step = what, at = System.currentTimeMillis()) } }
    private fun touch() { lastActive = System.currentTimeMillis(); surface?.actedAt = lastActive; scheduleIdle() }

    // ------------------------------------------------------------------ lifecycle
    /** Makes (or reuses) the WebView for [url]'s site on Blaze's screen. Main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensure(url: String): WebView {
        val multi = multiProfile
        val cur = web
        if (cur != null && !PhonePolicy.needsNewWebView(session, url, multi)) return cur
        if (cur != null) dropWebView()
        val s = PhonePolicy.sessionFor(url, multi)
        val c = config()
        val w0 = MutableContextWrapper(app)
        val w = WebView(w0)
        // The profile has to be set before the WebView does anything else.
        if (s.profile != null) try {
            ProfileStore.getInstance().getOrCreateProfile(s.profile)
            WebViewCompat.setProfile(w, s.profile)
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("PhoneBrowser.setProfile", e) }
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            loadWithOverviewMode = true; useWideViewPort = true
            builtInZoomControls = true; displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true   // Blaze Phone browses, it doesn't play media
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false; allowContentAccess = false
            setSupportMultipleWindows(false)
            userAgentString = userAgentString.replace("; wv", "")
        }
        try { cookies(s).setAcceptThirdPartyCookies(w, true) } catch (_: Throwable) { }
        w.webViewClient = Client()
        w.webChromeClient = Chrome()
        web = w; wrap = w0; session = s
        _webGen.value = _webGen.value + 1
        rememberSite(s.site)
        val sf = surface ?: PhoneSurface(app, c).also { surface = it }
        sf.watching = watchers
        sf.onFrame = { b, fw, fh -> frame = b; frameW = fw; frameH = fh; set { l -> l.copy(frameNo = l.frameNo + 1) } }
        val ok = sf.start(w)
        if (!ok) { surface = null; offscreen(w) }
        set { it.copy(mode = if (it.mode == PhonePolicy.Mode.OFF) PhonePolicy.Mode.AGENT else it.mode, surfaceOk = ok, lowRam = c.lowRam) }
        return w
    }

    /** No display on this phone: lay the WebView out at the phone's size, offscreen (pages still work, no frames). */
    private fun offscreen(w: WebView) {
        val dm = app.resources.displayMetrics
        val wp = dm.widthPixels.coerceAtLeast(360); val hp = dm.heightPixels.coerceAtLeast(640)
        w.measure(android.view.View.MeasureSpec.makeMeasureSpec(wp, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(hp, android.view.View.MeasureSpec.EXACTLY))
        w.layout(0, 0, wp, hp)
    }

    private fun cookies(s: PhonePolicy.Session?): CookieManager =
        s?.profile?.let { p -> try { ProfileStore.getInstance().getProfile(p)?.cookieManager } catch (_: Throwable) { null } } ?: CookieManager.getInstance()

    private fun flush() { try { cookies(session).flush() } catch (_: Throwable) { } }

    private fun dropWebView() {
        val w = web ?: return
        flush()
        try { (w.parent as? ViewGroup)?.removeView(w); w.stopLoading(); w.destroy() } catch (_: Throwable) { }
        web = null; wrap = null; session = null
    }

    /** Frees everything (WebView, display, buffers). Sessions stay on disk. Main thread. */
    fun release() {
        main.removeCallbacks(idleCheck)
        dropWebView()
        surface?.release(); surface = null
        frame = null
        handBack?.complete(Unit); handBack = null
        _live.value = Live()
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            val l = _live.value
            if (PhonePolicy.shouldRelease(l.mode, lastActive, System.currentTimeMillis())) release()
            else if (l.mode != PhonePolicy.Mode.OFF) main.postDelayed(this, 30_000)
        }
    }
    private fun scheduleIdle() { main.removeCallbacks(idleCheck); main.postDelayed(idleCheck, 30_000) }

    // ------------------------------------------------------------------ clients
    private class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
            val s = r.url?.scheme?.lowercase().orEmpty()
            // Blaze Phone stays in the page: no intent://, market:, tel: or other apps opened from here.
            return s != "http" && s != "https" && s != "about" && s != "data" && s != "blob"
        }
        override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
            if (loaded.isCompleted) loaded = CompletableDeferred()
            _live.value = _live.value.copy(url = url.orEmpty())
        }
        override fun onPageFinished(v: WebView, url: String?) {
            loaded.complete(Unit)
            _live.value = _live.value.copy(url = url.orEmpty(), title = v.title.orEmpty())
            try { CookieManager.getInstance().flush() } catch (_: Throwable) { }
            flush()
        }
        override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean {
            val url = _live.value.url
            lastCrash = "The page renderer stopped (Android closed it to save memory) on ${url.take(80)}; Blaze Phone reopened it and anything typed there was lost."
            com.blaze.assistant.device.CrashLog.write("Blaze Phone renderer gone on ${url.take(80)}")
            main.post {
                if (web === v) { web = null; try { (v.parent as? ViewGroup)?.removeView(v); v.destroy() } catch (_: Throwable) { }; session = null
                    if (url.startsWith("http")) ensure(url).loadUrl(url) }
            }
            return true
        }
    }

    private class Chrome : WebChromeClient() {
        private fun userDriving() = _live.value.mode == PhonePolicy.Mode.USER && wrap?.baseContext !== app
        override fun onJsAlert(v: WebView, url: String?, message: String?, r: android.webkit.JsResult): Boolean {
            if (userDriving()) return false
            lastDialog = "alert (answered OK): " + message.orEmpty().take(300); r.confirm(); return true
        }
        override fun onJsConfirm(v: WebView, url: String?, message: String?, r: android.webkit.JsResult): Boolean {
            if (userDriving()) return false
            lastDialog = "confirm (answered Cancel; ask the user, then use phone_handoff if they want it OK'd): " + message.orEmpty().take(300); r.cancel(); return true
        }
        override fun onJsPrompt(v: WebView, url: String?, message: String?, def: String?, r: android.webkit.JsPromptResult): Boolean {
            if (userDriving()) return false
            lastDialog = "prompt (answered Cancel): " + message.orEmpty().take(300); r.cancel(); return true
        }
        override fun onJsBeforeUnload(v: WebView, url: String?, message: String?, r: android.webkit.JsResult): Boolean {
            if (userDriving()) return false
            r.confirm(); return true
        }
        override fun onReceivedTitle(v: WebView, title: String?) { _live.value = _live.value.copy(title = title.orEmpty()) }
    }

    // ------------------------------------------------------------------ helpers
    private suspend fun js(code: String): Any? = withContext(Dispatchers.Main) {
        val w = web ?: return@withContext null
        withTimeoutOrNull(8000) {
            suspendCancellableCoroutine<Any?> { cont ->
                w.evaluateJavascript(code) { r -> if (cont.isActive) cont.resume(try { JSONTokener(r ?: "null").nextValue() } catch (_: Exception) { r }) }
            }
        }
    }

    internal suspend fun settle(ms: Long = 12_000) {
        delay(250)
        withTimeoutOrNull(ms) { loaded.await() }
        delay(350)   // let scripts render
    }

    /** While the user drives, Blaze waits for Hand back (up to [ms]); true when it may act. */
    private suspend fun waitForUser(ms: Long = 10 * 60_000L): Boolean {
        if (_live.value.mode != PhonePolicy.Mode.USER && _live.value.mode != PhonePolicy.Mode.WAITING) return true
        val d = handBack ?: CompletableDeferred<Unit>().also { handBack = it }
        set { it.copy(agentWaiting = true) }
        val r = withTimeoutOrNull(ms) { d.await() } != null
        set { it.copy(agentWaiting = false) }
        return r && _live.value.mode == PhonePolicy.Mode.AGENT
    }

    private fun stillUser() = "The user is in control of Blaze Phone and hasn't handed back yet. Don't act; tell them you'll continue when they tap Hand back."

    // ------------------------------------------------------------------ what the tools call
    suspend fun open(ctx: Context, url0: String): String {
        init(ctx)
        if (!waitForUser()) return stillUser()
        val url = com.blaze.assistant.browser.BrowserEngine.toUrl(url0)
        if (!url.startsWith("https://") && !url.startsWith("http://")) return "Blaze Phone opens web pages only (https)."
        withContext(Dispatchers.Main) {
            val w = ensure(url)
            if (loaded.isCompleted) loaded = CompletableDeferred()
            w.loadUrl(url)
            touch()
        }
        settle(15_000)
        return "Opened on Blaze Phone.\n" + readText()
    }

    suspend fun read(): PhonePolicy.Snapshot? {
        if (web == null) return null
        val o = js(PhoneScripts.SNAPSHOT) as? JSONObject ?: return null
        touch()
        val s = PhonePolicy.parseSnapshot(o) { SecureInput.scrub(it) }
        val d = lastDialog; lastDialog = null
        return if (d != null) s.copy(dialog = d) else s
    }

    suspend fun readText(): String {
        val s = read() ?: return if (web == null) "Blaze Phone has no page open. Use phone_open first." else "Couldn't read this page (still loading?). Try phone_wait, then phone_read."
        val notes = buildList { lastCrash?.let { add(it); lastCrash = null } }
        return PhonePolicy.render(s) + if (notes.isEmpty()) "" else "\n\n" + notes.joinToString("\n")
    }

    /** JPEG data URL of Blaze's screen, or null (no display on this phone). */
    suspend fun screenshot(): String? {
        val sf = surface ?: return null
        touch()
        val b = sf.screenshot() ?: return null
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    }

    /** Finds a target: an element id ("e12"), or words on the page. Scrolled into view. */
    suspend fun locate(target: String, field: Boolean = false): Target? {
        val t = target.trim().removePrefix("[").removeSuffix("]")
        val id = if (Regex("^e\\d+$").matches(t)) t else if (t == ":focus") (js(PhoneScripts.FOCUSED_ID) as? String) ?: return null
            else (js(PhoneScripts.find(t, field)) as? String) ?: return null
        val o = js(PhoneScripts.rect(id)) as? JSONObject ?: return null
        delay(60)
        return Target(id, o.optInt("x"), o.optInt("y"), o.optInt("w"), o.optInt("h"), SecureInput.scrub(o.optString("label")), o.optString("role"),
            o.optString("tag"), o.optString("type"), o.optBoolean("card"), o.optBoolean("sens"), o.optBoolean("editable"), o.optBoolean("disabled"),
            o.optBoolean("covered"), o.optString("coveredBy"))
    }

    suspend fun focusInfo(): FocusInfo {
        val o = js(PhoneScripts.FOCUS) as? JSONObject ?: return FocusInfo("", false, false, false)
        return FocusInfo(o.optString("submit"), o.optBoolean("card"), o.optBoolean("inForm"), o.optBoolean("editable"))
    }

    suspend fun pageHasCard(): Boolean = focusInfo().card

    /** A real tap at (x, y) in the WebView's pixels. */
    suspend fun tapAt(x: Float, y: Float) = withContext(Dispatchers.Main) {
        val w = web ?: return@withContext
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        w.dispatchTouchEvent(down); down.recycle()
        delay(70)
        val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        w.dispatchTouchEvent(up); up.recycle()
        touch()
    }

    /** A real drag (finger scroll) of [dy] pixels. */
    private suspend fun drag(dy: Float) = withContext(Dispatchers.Main) {
        val w = web ?: return@withContext
        val x = w.width / 2f; val y0 = w.height / 2f + dy / 2; val y1 = w.height / 2f - dy / 2
        val t = SystemClock.uptimeMillis()
        fun ev(a: Int, y: Float) = MotionEvent.obtain(t, SystemClock.uptimeMillis(), a, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        ev(MotionEvent.ACTION_DOWN, y0).also { w.dispatchTouchEvent(it); it.recycle() }
        val steps = 10
        for (i in 1..steps) { delay(16); ev(MotionEvent.ACTION_MOVE, y0 + (y1 - y0) * i / steps).also { w.dispatchTouchEvent(it); it.recycle() } }
        delay(60)   // a held stop: a scroll, not a fling
        ev(MotionEvent.ACTION_UP, y1).also { w.dispatchTouchEvent(it); it.recycle() }
        touch()
    }

    private fun keyCode(k: String): Int = when (k.lowercase()) {
        "enter" -> KeyEvent.KEYCODE_ENTER; "tab" -> KeyEvent.KEYCODE_TAB; "escape" -> KeyEvent.KEYCODE_ESCAPE
        "backspace" -> KeyEvent.KEYCODE_DEL; "space" -> KeyEvent.KEYCODE_SPACE; "up" -> KeyEvent.KEYCODE_DPAD_UP
        "down" -> KeyEvent.KEYCODE_DPAD_DOWN; "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "pageup" -> KeyEvent.KEYCODE_PAGE_UP; "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN; else -> KeyEvent.KEYCODE_UNKNOWN
    }

    /** A real key press (down + up) to the page. */
    suspend fun key(k: String, meta: Int = 0) = withContext(Dispatchers.Main) {
        val w = web ?: return@withContext
        val code = keyCode(k); if (code == KeyEvent.KEYCODE_UNKNOWN) return@withContext
        val t = SystemClock.uptimeMillis()
        w.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
        w.dispatchKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
        touch()
    }

    /** Types [text] as real key events into the focused field. False when a character has no key (then JS fills). */
    private suspend fun keys(text: String, clear: Boolean): Boolean = withContext(Dispatchers.Main) {
        val w = web ?: return@withContext false
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = map.getEvents(text.toCharArray()) ?: return@withContext false
        if (clear) {
            val t = SystemClock.uptimeMillis()
            for (a in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                w.dispatchKeyEvent(KeyEvent(t, t, a, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
            for (a in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) w.dispatchKeyEvent(KeyEvent(t, t, a, KeyEvent.KEYCODE_DEL, 0))
        }
        events.forEachIndexed { i, e ->
            w.dispatchKeyEvent(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0))
            if (i % 2 == 1) delay(6)
        }
        touch()
        true
    }

    suspend fun click(t: Target): ActResult {
        if (t.disabled) return ActResult(false, "\u201c${t.label.take(40)}\u201d is disabled right now (a required field may be empty). phone_read to see what's missing.")
        val real = t.w > 0 && t.h > 0 && !t.covered
        if (real) tapAt(t.x.toFloat(), t.y.toFloat()) else js(PhoneScripts.click(t.id))
        settle(10_000)
        return ActResult(true, "Clicked \u201c${t.label.take(60)}\u201d" + (if (!real) " (by script: ${if (t.covered) "covered by \u201c${t.coveredBy}\u201d" else "no size"})" else "") + ".")
    }

    suspend fun type(t: Target, text0: String, clear: Boolean, submit: Boolean): ActResult {
        val url = _live.value.url
        val secret = SecureInput.hasPlaceholder(text0)
        if (secret && !com.blaze.assistant.connect.UrlSafety.autofillAllowed(url)) return ActResult(false, com.blaze.assistant.connect.UrlSafety.AUTOFILL_REFUSED)
        val text = if (!secret) text0 else SecureInput.resolve(text0, SecureInput.hostOf(url)).first?.also { SecureInput.hide(it) }
            ?: return ActResult(false, "No saved value for that placeholder on this site. Ask the user to take control (phone_handoff) so they type it themselves.")
        if (t.w > 0 && t.h > 0 && !t.covered) tapAt(t.x.toFloat(), t.y.toFloat()) else js("(function(){var e=document.querySelector('[data-bzp=\"${t.id}\"]');if(e)e.focus();})()")
        delay(120)
        val typedByKeys = keys(text, clear)
        delay(80)
        // Check what landed; frameworks that swallow key events get the value set by script instead.
        val check = js("(function(){var e=document.querySelector('[data-bzp=\"${t.id}\"]');if(!e)return -1;var v=e.isContentEditable?e.innerText:(e.value||'');return v.length;})()")
        val len = (check as? Number)?.toInt() ?: -1
        val good = typedByKeys && (len < 0 || (if (clear) len == text.length else len >= text.length))
        if (!good) js(PhoneScripts.type(t.id, text, secret || t.sens, clear))
        else if (secret || t.sens) js(PhoneScripts.markSecret(t.id))
        touch()
        var msg = if (secret || t.sens) "Typed into \u201c${t.label.take(40)}\u201d (value hidden)" else "Typed \u201c${text0.take(60)}\u201d into \u201c${t.label.take(40)}\u201d"
        if (!good) msg += " (by script)"
        if (submit) { key("enter"); settle(12_000); msg += " and pressed Enter" }
        return ActResult(true, SecureInput.scrub("$msg."))
    }

    suspend fun select(t: Target, option: String): ActResult {
        val r = js(PhoneScripts.select(t.id, option))?.toString().orEmpty()
        touch()
        return when {
            r.startsWith("ok:") -> { settle(4000); ActResult(true, "Selected \u201c${r.removePrefix("ok:").take(60)}\u201d in \u201c${t.label.take(40)}\u201d.") }
            r.startsWith("nooption:") -> ActResult(false, "No option \u201c${option.take(40)}\u201d. Options: ${r.removePrefix("nooption:").take(300)}")
            r == "notselect" -> ActResult(false, "\u201c${t.label.take(40)}\u201d isn't a dropdown; click it, then click the option.")
            else -> ActResult(false, "That element is gone. phone_read for fresh ids.")
        }
    }

    suspend fun scroll(down: Boolean): ActResult {
        val before = (js("String(Math.round(window.scrollY))") as? String)?.toIntOrNull()
        val h = withContext(Dispatchers.Main) { web?.height ?: 0 }
        drag((if (down) 1 else -1) * h * 0.6f)
        delay(350)
        val after = (js("String(Math.round(window.scrollY))") as? String)?.toIntOrNull()
        if (before != null && before == after) js(PhoneScripts.scrollBy(down))
        return ActResult(true, "Scrolled ${if (down) "down" else "up"}.")
    }

    suspend fun navigate(what: String): ActResult {
        val r = withContext(Dispatchers.Main) {
            val w = web ?: return@withContext "No page open."
            if (loaded.isCompleted) loaded = CompletableDeferred()
            when (what) {
                "back" -> if (w.canGoBack()) { w.goBack(); "Went back." } else { loaded.complete(Unit); "Nothing to go back to." }
                "forward" -> if (w.canGoForward()) { w.goForward(); "Went forward." } else { loaded.complete(Unit); "Nothing to go forward to." }
                else -> { w.reload(); "Reloaded." }
            }
        }
        touch(); settle(10_000)
        return ActResult(true, r)
    }

    /** Fills the saved login for this site (BrowserLogins, the browser's password store) without submitting. */
    suspend fun fillLogin(user: String?): ActResult {
        val url = _live.value.url
        if (!com.blaze.assistant.connect.UrlSafety.autofillAllowed(url)) return ActResult(false, com.blaze.assistant.connect.UrlSafety.AUTOFILL_REFUSED)
        val host = SecureInput.hostOf(url)
        val list = try { BrowserLogins.forHost(host) } catch (_: Throwable) { emptyList() }
        val l = list.firstOrNull { user.isNullOrBlank() || it.user.equals(user, true) } ?: list.firstOrNull()
            ?: return ActResult(false, "No saved login for $host. Call phone_handoff so the user signs in themselves (never ask for a password in chat). Codes (2FA) can be asked in chat with ask_user and typed with phone_act.")
        val pass = l.password
        if (pass.isEmpty()) return ActResult(false, "The saved password for $host can't be read on this phone. Call phone_handoff so the user signs in.")
        SecureInput.hide(pass)
        js(BrowserLogins.fillJs(l.user, pass))
        js("(function(){[].slice.call(document.querySelectorAll('input[type=password]')).forEach(function(e){e.setAttribute('data-bz-secret','1');});})()")
        try { BrowserLogins.touch(l.id) } catch (_: Throwable) { }
        touch()
        return ActResult(true, "Filled the saved login for ${l.user.take(60)} on $host (not submitted; click the sign-in button next).")
    }

    /** Waits until [selector] is visible / [text] is on the page / the page loaded (or the opposite with [gone]). */
    suspend fun waitFor(selector: String?, text: String?, gone: Boolean, ms: Long): String {
        if (web == null) return "No page open."
        val until = System.currentTimeMillis() + ms
        if (selector.isNullOrBlank() && text.isNullOrBlank()) { withTimeoutOrNull(ms) { loaded.await() }; return if (loaded.isCompleted) "Page loaded." else "Still loading after ${ms / 1000}s." }
        while (System.currentTimeMillis() < until) {
            when (js(PhoneScripts.waitCheck(selector, text, gone))) {
                "yes" -> { touch(); return "Condition met: ${describe(selector, text, gone)}." }
                "badselector" -> return "That CSS selector isn't valid."
            }
            delay(300)
        }
        return "Timed out after ${ms / 1000}s waiting for ${describe(selector, text, gone)}."
    }

    private fun describe(sel: String?, text: String?, gone: Boolean) =
        (if (!sel.isNullOrBlank()) "selector $sel" else "text \u201c${text.orEmpty().take(40)}\u201d") + if (gone) " gone" else ""

    // ------------------------------------------------------------------ hand-off
    /**
     * Blaze asks the user to take control (captcha, 2FA, a card form, their own password). Waits for Hand back (up to
     * [ms]); then Blaze continues with a fresh read. [notify]: post the "Action required" notification.
     */
    suspend fun handoff(reason: String, ms: Long = 10 * 60_000L, notify: (String, String) -> Unit): String {
        if (web == null) return "Blaze Phone has no page open."
        set { it.copy(mode = PhonePolicy.Mode.WAITING, reason = reason.take(160)) }
        notify(reason, SecureInput.hostOf(_live.value.url).ifBlank { "Blaze Phone" })
        val ok = waitForUser(ms)
        if (!ok) {
            if (_live.value.mode == PhonePolicy.Mode.WAITING) set { it.copy(mode = PhonePolicy.Mode.AGENT, reason = null) }
            return "The user didn't take over within ${ms / 60_000} minutes. Stop here and tell them what's waiting on Blaze Phone (${reason.take(80)})."
        }
        return "The user handed back. Fresh read:\n" + readText()
    }

    /** "Take control": the real WebView goes to the full-screen activity; Blaze pauses. Main thread. */
    fun takeControl(activityCtx: Context): WebView? {
        val w = web ?: return null
        wrap?.baseContext = activityCtx   // dialogs and pickers show in the activity
        set { it.copy(mode = PhonePolicy.Mode.USER) }
        touch()
        return w
    }

    /**
     * "Hand back" (an agent is waiting) or "Done" (the user browsed on their own): the WebView goes back to Blaze's
     * screen, Blaze resumes; with nobody waiting the session is saved and everything released. Main thread.
     */
    fun handBackNow() {
        wrap?.baseContext = app
        val waiting = handBack
        if (waiting == null || waiting.isCompleted) {
            flush(); release(); return
        }
        web?.let { w -> surface?.put(w) ?: offscreen(w) }
        set { it.copy(mode = PhonePolicy.Mode.AGENT, reason = null) }
        touch()
        handBack = null
        waiting.complete(Unit)
    }

    /** The user opened Blaze Phone themselves (nothing running): make sure a page is up. Main thread. */
    fun openForUser(ctx: Context, url: String = "https://www.google.com/") {
        init(ctx)
        if (web == null) { ensure(url).loadUrl(url); touch() }
    }

    fun userLoad(input: String) {
        val url = com.blaze.assistant.browser.BrowserEngine.toUrl(input)
        if (!url.startsWith("http")) return
        val w = web ?: return
        // Another site gets its own session; the user stays in control on the new WebView.
        if (PhonePolicy.needsNewWebView(session, url, multiProfile)) {
            val mode = _live.value.mode
            val n = ensure(url); n.loadUrl(url); set { it.copy(mode = mode) }; return
        }
        w.loadUrl(url); touch()
    }

    /** The WebView the user drives (after [takeControl]), for the activity to host. */
    fun webView(): WebView? = web

    // ------------------------------------------------------------------ status / settings
    fun status(): String {
        val l = _live.value
        if (l.mode == PhonePolicy.Mode.OFF) return "Blaze Phone is idle (nothing open). Sessions kept for: ${sites().take(12).joinToString().ifBlank { "no sites yet" }}."
        val c = config()
        val mode = when (l.mode) { PhonePolicy.Mode.AGENT -> "Blaze is driving"; PhonePolicy.Mode.WAITING -> "waiting for the user to take control (${l.reason})"
            PhonePolicy.Mode.USER -> "the user is in control"; else -> "idle" }
        return "Blaze Phone: $mode. Page: ${l.title.take(80)} \u2014 ${l.url.take(160)}. Session: ${session?.site ?: "?"} (" +
            (if (session?.isolated == true) "own profile" else "shared with the browser: this phone's WebView has no profiles") + "). " +
            "Screen: ${if (l.surfaceOk) "live" else "offscreen (no live view on this phone)"}, ${if (c.lowRam) "low-RAM mode" else "normal mode"}. " + memoryLine()
    }

    fun memoryLine(): String {
        val rt = Runtime.getRuntime()
        val heap = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        val native = android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576
        val sfc = (surface?.bytesHeld() ?: 0L) / 1_048_576
        return "RAM: app heap ${heap} MB, native ${native} MB, Blaze Phone screen buffers ${sfc} MB."
    }

    /** Settings › Blaze Phone › Sign out: forgets one site's cookies and storage. Main thread. */
    fun signOut(site: String): Boolean {
        if (!::app.isInitialized) return false
        if (session?.site == site) release()
        var ok = false
        if (multiProfile) {
            val name = PhonePolicy.profileName(site)
            ok = try { ProfileStore.getInstance().deleteProfile(name) } catch (_: Throwable) {
                try { ProfileStore.getInstance().getProfile(name)?.let { it.cookieManager.removeAllCookies(null); it.webStorage.deleteAllData(); true } ?: false } catch (_: Throwable) { false }
            }
        } else ok = expireCookies(site)
        prefs().edit().putString("sites", PhonePolicy.Sites.remove(prefs().getString("sites", null), site)).apply()
        return ok
    }

    /** Settings › Blaze Phone › Clear Blaze Phone data: every Blaze Phone session. Main thread. */
    fun clearAll(): Int {
        if (!::app.isInitialized) return 0
        release()
        var n = 0
        if (multiProfile) {
            try {
                val ps = ProfileStore.getInstance()
                ps.allProfileNames.filter { it.startsWith(PhonePolicy.PROFILE_PREFIX) || it == PhonePolicy.SHARED_PROFILE }.forEach { name ->
                    if (try { ps.deleteProfile(name) } catch (_: Throwable) { false }) n++
                }
            } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("PhoneBrowser.clearAll", e) }
        } else sites().forEach { if (expireCookies(it)) n++ }
        prefs().edit().remove("sites").apply()
        return n
    }

    /** No profiles on this phone: expire the site's cookies one by one (the user's other sites are untouched). */
    private fun expireCookies(site: String): Boolean {
        val cm = CookieManager.getInstance()
        var any = false
        for (u in listOf("https://$site", "https://www.$site")) {
            val raw = cm.getCookie(u) ?: continue
            raw.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.forEach { name ->
                cm.setCookie(u, "$name=; Max-Age=0; Path=/"); cm.setCookie(u, "$name=; Max-Age=0; Path=/; Domain=.$site"); any = true
            }
            try { WebStorage.getInstance().deleteOrigin(u) } catch (_: Throwable) { }
        }
        cm.flush()
        return any
    }

    /** Live-view watchers (the chat tile, the full screen): frames are only made while someone watches. */
    fun watch(on: Boolean) { watchers = (watchers + if (on) 1 else -1).coerceAtLeast(0); surface?.watching = watchers }

    fun hasPage(): Boolean = web != null
    fun site(): String = session?.site.orEmpty()
    suspend fun mayAct(): Boolean = waitForUser()

    /** The real [PhoneDriver]: this engine. [notify] posts "Action required" for a hand-off when Blaze isn't on screen. */
    class Driver(private val ctx: Context, private val notify: (String, String) -> Unit) : PhoneDriver {
        init { PhoneBrowser.init(ctx) }
        override fun hasPage() = PhoneBrowser.hasPage()
        override fun mode() = live.value.mode
        override fun site() = PhoneBrowser.site()
        override suspend fun open(url: String) = PhoneBrowser.open(ctx, url)
        override suspend fun snapshot() = PhoneBrowser.read()
        override suspend fun locate(target: String, field: Boolean) = PhoneBrowser.locate(target, field)
        override suspend fun focusInfo() = PhoneBrowser.focusInfo()
        override suspend fun click(t: Target) = PhoneBrowser.click(t)
        override suspend fun type(t: Target, text: String, clear: Boolean, submit: Boolean) = PhoneBrowser.type(t, text, clear, submit)
        override suspend fun select(t: Target, option: String) = PhoneBrowser.select(t, option)
        override suspend fun key(k: String) { if (k.equals("back", true)) PhoneBrowser.navigate("back") else { PhoneBrowser.key(k); if (k.equals("enter", true)) PhoneBrowser.settle(10_000) } }
        override suspend fun scroll(down: Boolean) = PhoneBrowser.scroll(down)
        override suspend fun navigate(what: String) = PhoneBrowser.navigate(what)
        override suspend fun fillLogin(user: String?) = PhoneBrowser.fillLogin(user)
        override suspend fun screenshot() = PhoneBrowser.screenshot()
        override suspend fun waitFor(selector: String?, text: String?, gone: Boolean, ms: Long) = PhoneBrowser.waitFor(selector, text, gone, ms)
        override suspend fun handoff(reason: String) = PhoneBrowser.handoff(reason, notify = notify)
        override suspend fun awaitAgentTurn() = PhoneBrowser.mayAct()
        override fun status() = PhoneBrowser.status()
        override fun step(what: String) = PhoneBrowser.step(what)
    }
}
