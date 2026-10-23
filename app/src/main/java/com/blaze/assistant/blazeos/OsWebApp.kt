package com.blaze.assistant.blazeos

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * v0.33.2 BlazeOS web apps and the BlazeOS Browser. Each runs in its own WebView with BlazeOS's own WebView profile
 * ("blazeos": its cookies and storage are apart from Blaze's browser, when the phone's WebView supports profiles), no
 * file or content access, and pages that leave the app open in the BlazeOS Browser instead.
 *
 * A packaged web app is served from https://<app>.apps.blazeos/ straight out of its folder on the BlazeOS disk (so each
 * app has its own origin and storage), with a small JS bridge `window.BlazeOS`: getItem / setItem / removeItem / keys
 * (the app's data folder), notify(title, body) (a BlazeOS banner, after the app's notification permission) and theme()
 * (dark, accent, fontScale as JSON). Open WebViews are kept while their app is open, so leaving an app and coming back
 * keeps its page; Pause or closing the app frees them.
 */
object OsWeb {
    const val PROFILE = "blazeos"
    private val live = HashMap<String, WebView>()

    fun host(manifestId: String) = manifestId.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("") + ".apps.blazeos"
    fun origin(manifestId: String) = "https://" + host(manifestId)

    /** Apps whose first page has drawn (their splash is gone). */
    val loadedApps = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    fun loaded(appId: String) { if (appId !in loadedApps.value) loadedApps.value = loadedApps.value + appId }

    fun release(appId: String) {
        loadedApps.value = loadedApps.value - appId
        live.remove(appId)?.let { try { (it.parent as? ViewGroup)?.removeView(it); it.destroy() } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("OsWebApp: it.parent as ViewGroup.removeViewit it.d", e) } } }
    fun pauseAll() { for (w in live.values) try { w.onPause() } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("OsWebApp: w.onPause", e) } ; try { if (!com.blaze.assistant.browser.phone.PhoneBrowser.active) live.values.firstOrNull()?.pauseTimers() } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("OsWebApp: w.onPause", e) } }
    fun resumeAll() { for (w in live.values) try { w.onResume() } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("OsWebApp: w.onResume", e) } ; try { live.values.firstOrNull()?.resumeTimers() } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("OsWebApp: w.onResume", e) } }
    fun releaseAll() { for (id in live.keys.toList()) release(id) }

    /** Factory reset: BlazeOS's own WebView profile (the BlazeOS Browser's cookies and storage) is emptied. Never Blaze's own. */
    fun wipeProfile() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        try {
            val p = androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            p.cookieManager.removeAllCookies(null)
            p.webStorage.deleteAllData()
        } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("OsWebApp: val p androidx.webkit.ProfileStore.getIn", e) }
    }

    /** Factory reset / uninstall: the app origins' web storage goes too. */
    fun forgetOrigin(manifestId: String) { try { WebStorage.getInstance().deleteOrigin(origin(manifestId)) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("OsWebApp: WebStorage.getInstance.deleteOriginorigi", e) } }

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(ctx: Context, appId: String, setup: (WebView) -> Unit): WebView {
        live[appId]?.let { (it.parent as? ViewGroup)?.removeView(it); return it }
        val w = WebView(ctx.applicationContext)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) { try { WebViewCompat.setProfile(w, PROFILE) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("OsWebApp: WebViewCompat.setProfilew PROFILE", e) } }
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            textZoom = (BlazeOs.prefs.value.fontScale * 100).toInt()
        }
        setup(w)
        live[appId] = w
        return w
    }

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"; "js", "mjs" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"; "webp" -> "image/webp"
        "wasm" -> "application/wasm"; "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/** The bridge an app's page sees as window.BlazeOS. Calls arrive on a WebView thread. */
class OsBridge(private val ctx: Context, private val appId: String, private val label: String) {
    private val file = File(OsDisk.dataDir(ctx, appId), "storage.json")
    private val lock = Any()
    // v0.45.0: storage lives in memory and is written at most once per burst (OsBridgeLimits), not on every setItem.
    private var data: JSONObject? = null
    private val debounce = OsBridgeLimits.WriteDebounce()
    private val notifyRate = OsBridgeLimits.Rate(OsBridgeLimits.NOTIFY_MAX, OsBridgeLimits.NOTIFY_WINDOW_MS)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun read(): JSONObject = data ?: (try { JSONObject(OsDisk.readText(file) ?: "{}") } catch (_: Exception) { JSONObject() }).also { data = it }
    private fun changed() {
        if (!debounce.mark(System.currentTimeMillis())) return
        Thread { try { Thread.sleep(debounce.delay) } catch (_: InterruptedException) { }; flush() }.apply { isDaemon = true }.start()
    }
    /** Writes pending storage now (also called when the app's page goes away). */
    fun flush() {
        synchronized(lock) {
            if (!debounce.pending()) return
            debounce.flushed()
            data?.let { OsDisk.writeText(file, it.toString()) }
        }
    }

    @JavascriptInterface fun getItem(k: String): String? = synchronized(lock) { read().optString(k, "").ifEmpty { null } }
    @JavascriptInterface fun setItem(k: String, v: String) {
        synchronized(lock) {
            val o = read()
            val key = k.take(200)
            val old = if (o.has(key)) o.opt(key) else null
            if (old == v) return
            o.put(key, v)
            // Keeps one app's storage reasonable (2 MB) so it can't fill the BlazeOS disk.
            if (o.toString().length > 2 * 1024 * 1024) { if (old == null) o.remove(key) else o.put(key, old); return }
            changed()
        }
    }
    @JavascriptInterface fun removeItem(k: String) { synchronized(lock) { val o = read(); if (o.remove(k) != null) changed() } }
    @JavascriptInterface fun keys(): String = synchronized(lock) { org.json.JSONArray(read().keys().asSequence().toList()).toString() }
    @JavascriptInterface fun notify(title: String, body: String) {
        if (!notifyRate.allow(System.currentTimeMillis())) return
        main.post { BlazeOs.scope.launch { if (BlazeOs.request(appId, listOf(OsPerms.NOTIFICATIONS))) BlazeOs.notify(label, title.take(120), body.take(500)) } }
    }
    @JavascriptInterface fun theme(): String {
        val p = BlazeOs.prefs.value
        return JSONObject().put("dark", p.dark).put("accent", p.accent.ifEmpty { "#FF9A3C" }).put("fontScale", p.fontScale.toDouble()).toString()
    }
    @JavascriptInterface fun version(): String = BlazeOs.VERSION
}

@Composable
fun OsWebApp(inst: OsInstalled) {
    val m = inst.manifest
    val appId = inst.appId
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler {
        val w = web
        if (w == null) BlazeOs.home()
        else w.evaluateJavascript("(window.blazeosBack && window.blazeosBack()) ? 'y' : 'n'") { r ->
            if (r?.contains("y") != true) { if (w.canGoBack()) w.goBack() else BlazeOs.home() }
        }
    }
    AndroidView(modifier = Modifier.fillMaxSize().background(osBg()).imePadding(), factory = { ctx ->
        OsWeb.obtain(ctx, appId) { w ->
            val dir = OsDisk.appDir(ctx, m.id)
            val base = OsWeb.origin(m.id)
            val hosted = m.entry.url.isNotEmpty()
            val hostedHost = if (hosted) Uri.parse(m.entry.url).host.orEmpty() else ""
            w.addJavascriptInterface(OsBridge(ctx, appId, m.name), "BlazeOS")
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                    val u = req.url
                    if (hosted || u.host != OsWeb.host(m.id)) return null
                    val path = u.path.orEmpty().trimStart('/').ifEmpty { m.entry.path }
                    if (!OsManifest.safePath(path)) return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)
                    val f = File(dir, path)
                    if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator) || !f.isFile)
                        return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), null)
                    return WebResourceResponse(OsWeb.mime(f.name), "utf-8", 200, "OK",
                        mapOf("Cache-Control" to "no-cache", "Content-Security-Policy" to "frame-ancestors 'none'"), f.inputStream())
                }
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val h = req.url.host.orEmpty()
                    val inside = if (hosted && m.scope.isNotEmpty()) com.blaze.assistant.siteapps.SiteLogic.inScope(req.url.toString(), m.scope)
                        else if (hosted) h == hostedHost else h == OsWeb.host(m.id)
                    if (inside) return false
                    // v0.33.3: intent:// and other apps' schemes go to the app that handles them (no loop, page kept otherwise).
                    val sc = req.url.scheme.orEmpty().lowercase()
                    if (sc != "http" && sc != "https") { com.blaze.assistant.browser.AppLinks.handle(view.context, view, req); return true }
                    // Leaving the app: open in the BlazeOS Browser.
                    if (req.url.scheme == "https" || req.url.scheme == "http") { OsBrowser.pending.value = req.url.toString(); BlazeOs.launch("blaze:browser") }
                    return true
                }
            }
            w.setBackgroundColor(parseHex(m.backgroundColor, if (BlazeOs.prefs.value.dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()))
            w.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, p: Int) { if (p >= 80) OsWeb.loaded(appId) }
                override fun onPermissionRequest(request: PermissionRequest) {
                    val perms = request.resources.mapNotNull {
                        when (it) { PermissionRequest.RESOURCE_VIDEO_CAPTURE -> OsPerms.CAMERA; PermissionRequest.RESOURCE_AUDIO_CAPTURE -> OsPerms.MIC; else -> null }
                    }
                    if (perms.isEmpty()) { request.deny(); return }
                    BlazeOs.scope.launch { if (BlazeOs.request(appId, perms)) request.grant(request.resources) else request.deny() }
                }
            }
            w.loadUrl(if (hosted) m.entry.url else "$base/${m.entry.path}")
        }.also { web = it }
    }, onRelease = { w -> (w.parent as? ViewGroup)?.removeView(w) })
    // A splash with the icon and name on the app's background colour until its first page has loaded (max 6 s).
    val loaded by OsWeb.loadedApps.collectAsState()
    var timedOut by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(appId) { kotlinx.coroutines.delay(6000); timedOut = true }
    if (appId !in loaded && !timedOut) {
        val look = LocalOsLook.current
        val bg = androidx.compose.ui.graphics.Color(parseHex(m.backgroundColor, if (look.dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()))
        Column(Modifier.fillMaxSize().background(bg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            OsAppIcon(OsApp(appId, m.name, m.entry.type, true), 96.dp)
            Spacer(Modifier.height(14.dp))
            Text(m.name, style = osText(look, 20f, androidx.compose.ui.text.font.FontWeight.SemiBold,
                if (com.blaze.assistant.siteapps.SiteLogic.isDark(m.backgroundColor.ifEmpty { if (look.dark) "#000000" else "#F2F2F7" })) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(0xFF111114)))
        }
    }
}

fun parseHex(hex: String, def: Int): Int = OsIcons.parseColor(hex)?.toInt() ?: def

/** What the BlazeOS Browser should open next (a link out of a web app). */
object OsBrowser { val pending = kotlinx.coroutines.flow.MutableStateFlow("") }

/** BlazeOS's own browser: nothing signed in, its own WebView profile, DuckDuckGo for searches. */
@Composable
fun OsBrowserApp() {
    val look = LocalOsLook.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun go(t: String) {
        val s = t.trim(); if (s.isEmpty()) return
        val url = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(s)) s
            else if (!s.contains(' ') && s.contains('.')) "https://$s"
            else "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(s, "UTF-8")
        web?.loadUrl(url)
    }
    BackHandler { val w = web; if (w != null && w.canGoBack()) w.goBack() else BlazeOs.home() }
    Column(Modifier.fillMaxSize().background(osBg()).imePadding()) {
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
            OsWeb.obtain(ctx, "blaze:browser") { w ->
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) { address = url.orEmpty(); title = view.title.orEmpty() }
                    // v0.33.3: intent://, market:, mailto:, tel:… and cross-site https App Links open the app that handles them.
                    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean =
                        com.blaze.assistant.browser.AppLinks.handle(view.context, view, req, address.ifBlank { view.url })
                }
                w.loadUrl(OsBrowser.pending.value.ifEmpty { "https://duckduckgo.com/" })
                OsBrowser.pending.value = ""
            }.also { web = it; address = it.url.orEmpty()
                val p = OsBrowser.pending.value; if (p.isNotEmpty()) { it.loadUrl(p); OsBrowser.pending.value = "" } }
        }, onRelease = { w -> (w.parent as? ViewGroup)?.removeView(w) })
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ArrowBackIosNew, "Back", tint = look.accent, modifier = Modifier.size(34.dp).clip(CircleShape).clickable { web?.let { if (it.canGoBack()) it.goBack() } }.padding(8.dp))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(look.ink.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 11.dp)) {
                if (address.isEmpty()) Text("Search or enter website", style = osText(look, 15f, color = look.sub))
                BasicTextField(address, { address = it }, singleLine = true, textStyle = osText(look, 15f), cursorBrush = SolidColor(look.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go(address) }), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.Refresh, "Reload", tint = look.accent, modifier = Modifier.size(34.dp).clip(CircleShape).clickable { web?.reload() }.padding(7.dp))
            // Add to Home screen: installs the site as a BlazeOS app with its real icon.
            Icon(Icons.Rounded.AddToHomeScreen, "Add to Home screen", tint = look.accent, modifier = Modifier.size(34.dp).clip(CircleShape).clickable {
                val u = web?.url.orEmpty()
                if (installing) return@clickable
                installing = true
                scope.launch {
                    val (ins, why) = com.blaze.assistant.siteapps.SiteInstaller.inspect(u)
                    val err = if (ins == null) why else com.blaze.assistant.siteapps.SiteInstaller.installInBlazeOs(ins)
                    installing = false
                    if (err != null) BlazeOs.notify("Browser", "Couldn't add it", err)
                }
            }.padding(7.dp))
        }
        Spacer(Modifier.height(2.dp))
    }
    @Suppress("UNUSED_VARIABLE") val t = title
}
