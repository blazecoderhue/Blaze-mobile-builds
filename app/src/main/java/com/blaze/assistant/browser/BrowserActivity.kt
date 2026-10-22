package com.blaze.assistant.browser

import android.app.Activity
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.blaze.assistant.ui.Glass
import com.blaze.assistant.ui.GlassIconButton
import com.blaze.assistant.ui.MascotGlyph
import com.blaze.assistant.ui.BrandProgress
import com.blaze.assistant.ui.BlazeTheme
import com.blaze.assistant.ui.Label
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.Type
import com.blaze.assistant.ui.hairline
import com.blaze.assistant.ui.hairlineBottom
import com.blaze.assistant.ui.hairlineTop

/** The Browser screen: shows BrowserEngine's current tab with an address bar, tabs, bookmarks, history and downloads. */
class BrowserActivity : androidx.fragment.app.FragmentActivity() {
    private var pending: ValueCallback<Array<Uri>>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pending?.onReceiveValue(if (r.resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data) else null)
        pending = null
    }
    /** VPN configs: .conf files or a provider's .zip, several at once. */
    private val importer = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val added = mutableListOf<String>(); val bad = mutableListOf<String>()
        uris.forEach { u ->
            try {
                val name = contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "config.conf"
                val bytes = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: ByteArray(0)
                if (bytes.size > 5_000_000) { bad += "$name is too big to be a WireGuard config."; return@forEach }
                val (a, b) = BrowserVpn.import(name, bytes)
                added += a.map { "${it.flag} ${it.label}" }; bad += b
            } catch (e: Exception) { bad += e.message ?: "Couldn't read a file." }
        }
        val msg = (if (added.isNotEmpty()) "Added ${added.size}: ${added.take(6).joinToString()}${if (added.size > 6) "…" else ""}." else "") +
            (if (bad.isNotEmpty()) " Skipped ${bad.size}: ${bad.first()}" else "")
        Toast.makeText(this, msg.trim(), Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT))
        BrowserEngine.init(this)
        BrowserEngine.filePicker = { cb, p ->
            pending?.onReceiveValue(null); pending = cb
            try { picker.launch(p.createIntent()); true } catch (_: Exception) { pending = null; cb.onReceiveValue(null); false }
        }
        BrowserUi.authenticate = { reason, ok -> authenticate(reason, ok) }
        handle(intent)
        if (BrowserEngine.tabs.isEmpty()) BrowserEngine.newTab(this)
        setContent { BlazeTheme { BrowserScreen(onClose = { finish() }, onImport = { try { importer.launch(arrayOf("*/*")) } catch (_: Exception) { } },
            onPopOut = { w, h -> popOut(w, h) }) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    /** v0.24.6: the page pointer's Pop out: the video under the pointer plays in a small window over other apps. */
    fun popOut(w: Float, h: Float): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        return try {
            val r = if (w > 0f && h > 0f) (w / h).coerceIn(0.42f, 2.38f) else 16f / 9f
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000).toInt(), 1000)).build())
        } catch (_: Exception) { false }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        BrowserUi.pip.value = isInPictureInPictureMode
    }

    private fun handle(i: Intent?) {
        val u = i?.dataString ?: i?.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("https?://\\S+").find(it)?.value } ?: return
        BrowserEngine.newTab(this, u)
    }

    override fun onResume() { super.onResume(); BrowserEngine.attach(this); BrowserEngine.tab()?.web?.onResume() }
    override fun onPause() { BrowserEngine.attach(null); BrowserEngine.saveTabsNow(); super.onPause() }
    override fun onStop() { BrowserEngine.saveTabsNow(); super.onStop() }

    /** v0.29.7: fingerprint / face / PIN before a saved password is shown, copied or edited. No screen lock = no reveal. */
    private fun authenticate(reason: String, ok: () -> Unit) {
        val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (androidx.biometric.BiometricManager.from(this).canAuthenticate(auth) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Set a screen lock (PIN, pattern or fingerprint) in Android settings to see saved passwords.", Toast.LENGTH_LONG).show(); return }
        try {
            androidx.biometric.BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(this), object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: androidx.biometric.BiometricPrompt.AuthenticationResult) { ok() }
            }).authenticate(androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle("Saved passwords").setSubtitle(reason).setAllowedAuthenticators(auth).build())
        } catch (e: Exception) { Toast.makeText(this, "Couldn't ask for your screen lock: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        BrowserUi.authenticate = null
        BrowserEngine.attach(null); BrowserEngine.filePicker = null
        // Back to the background browser: the tabs keep running offscreen at screen size.
        BrowserEngine.tabs.forEach { (it.web.parent as? ViewGroup)?.removeView(it.web); BrowserEngine.layoutOffscreen(it.web) }
        super.onDestroy()
    }
}

/** Browser screen state shared with the activity. */
object BrowserUi {
    val pip = androidx.compose.runtime.mutableStateOf(false)
    /** The summoned page pointer (v0.24.6). */
    val pointer = androidx.compose.runtime.mutableStateOf(false)
    /** v0.29.7: device unlock for Saved passwords (set while the browser is open). */
    @Volatile var authenticate: ((String, () -> Unit) -> Unit)? = null
    @Volatile var unlockedUntil = 0L
}

private enum class Panel { NONE, TABS, MENU, BOOKMARKS, HISTORY, PRIVACY, PASSWORDS, SAVED_PAGES, INSTALL_SITE, SITE_APPS }

/**
 * The Browser (v0.24.6 look): rounded dark Blaze theme with a pill address bar, rounded tab chips, round icon
 * buttons and a brand-coloured progress shimmer. The mascot button summons the page pointer (long-press too);
 * the sparkle button opens "Ask Blaze about this page".
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun BrowserScreen(onClose: () -> Unit, onImport: () -> Unit, onPopOut: (Float, Float) -> Boolean = { _, _ -> false },
                           previewPointer: PagePointer.Hit? = null, previewMenu: Boolean = false, previewAsk: List<Pair<Boolean, String>>? = null,
                           previewPage: (@Composable () -> Unit)? = null, previewTip: androidx.compose.ui.geometry.Offset? = null) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val tab = BrowserEngine.tab()
    var panel by remember { mutableStateOf(Panel.NONE) }
    var editing by remember { mutableStateOf(false) }
    var bar by remember(tab?.id, tab?.url, editing) { mutableStateOf(if (editing) tab?.url.orEmpty().let { if (it == "about:blank") "" else it } else display(tab?.url.orEmpty())) }
    var tick by remember { mutableIntStateOf(0) }   // re-read bookmarks after a change
    var pointer by BrowserUi.pointer
    if (previewPointer != null) pointer = true
    var askOpen by remember { mutableStateOf(previewAsk != null) }
    var askFirst by remember { mutableStateOf<String?>(null) }
    val pip by BrowserUi.pip
    var reading by remember { mutableStateOf<SavedPages.Page?>(null) }
    var installUrl by remember { mutableStateOf("") }
    val priv = tab?.isPrivate == true
    val bg = if (priv) Glass.privateBg else Glass.bg

    BackHandler {
        when {
            BrowserEngine.fullView != null -> BrowserEngine.exitFull()
            reading != null -> reading = null
            askOpen -> askOpen = false
            pointer -> pointer = false
            panel != Panel.NONE -> panel = Panel.NONE
            tab?.web?.canGoBack() == true -> BrowserEngine.back(tab)
            else -> onClose()
        }
    }

    BrowserEngine.fullView?.let { v ->
        AndroidView(factory = { FrameLayout(it) }, update = { f -> if (v.parent !== f) { (v.parent as? ViewGroup)?.removeView(v); f.removeAllViews(); f.addView(v) } },
            modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
        return
    }

    reading?.let { p -> SavedTextReader(p, onClose = { reading = null }); return }

    if (panel == Panel.TABS) {
        Box(Modifier.fillMaxSize().background(if (priv) androidx.compose.ui.graphics.Color(0xFF202124) else LocalBlaze.current.paper).systemBarsPadding()) {
            TabGrid(startPrivate = priv, onDone = { panel = Panel.NONE })
        }
        return
    }

    // Picture-in-picture: only the page (the video was scrolled to the middle and started).
    if (pip && tab != null) {
        key(tab.id) { WebHost(tab) }
        return
    }

    val host = remember(tab?.id) {
        PointerHost(
            openUrl = { u, mode ->
                when (mode) {
                    OpenMode.SAME -> BrowserEngine.tab()?.let { BrowserEngine.load(it, u) }
                    OpenMode.NEW -> BrowserEngine.newTab(ctx, u, isPrivate = priv)
                    OpenMode.BACKGROUND -> { val keep = BrowserEngine.current; BrowserEngine.newTab(ctx, u, isPrivate = priv); BrowserEngine.current = keep }
                }
            },
            ask = { q -> askFirst = q; askOpen = true },
            pip = { h, x, y ->
                BrowserEngine.tab()?.web?.evaluateJavascript(PagePointer.focusVideoJs(x, y), null)
                pointer = false
                if (!onPopOut(h.w, h.h)) android.widget.Toast.makeText(ctx, "This phone doesn't allow picture-in-picture for Blaze.", android.widget.Toast.LENGTH_SHORT).show()
            },
            isPrivate = priv)
    }

    // v0.33.3: the floating toolbar hides on scroll down and comes back on scroll up (or a tap on the page top).
    var barShown by remember { mutableStateOf(true) }
    LaunchedEffect(tab?.id, tab?.url) { barShown = true }
    // v0.34.1: where the floating toolbar is docked (bottom, top, left or right of the page), kept across launches.
    var dock by remember { mutableStateOf(ToolbarDock.load()) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().imePadding()) {
        // Address row: close, privacy, the address pill, reload, ask.
        Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Rounded.Close, "Close browser", size = 38.dp, onClick = onClose)
            Spacer(Modifier.width(6.dp))
            val vpn = BrowserVpn.status
            Box(Modifier.size(38.dp).clip(CircleShape).background(Glass.raised).clickable { panel = if (panel == Panel.PRIVACY) Panel.NONE else Panel.PRIVACY }
                .semantics { contentDescription = "VPN and privacy" }, contentAlignment = Alignment.Center) {
                when (vpn) {
                    BrowserVpn.Status.ON -> Text(Countries.flag(BrowserVpn.exitCountry ?: BrowserVpn.current()?.country.orEmpty()), Glass.text, 16)
                    BrowserVpn.Status.CONNECTING -> Icon(Icons.Rounded.VpnLock, null, tint = Glass.orange, modifier = Modifier.size(19.dp))
                    BrowserVpn.Status.FAILED -> Icon(Icons.Rounded.ErrorOutline, null, tint = Glass.orange, modifier = Modifier.size(19.dp))
                    BrowserVpn.Status.OFF -> Icon(Icons.Rounded.Shield, null, tint = Glass.sub, modifier = Modifier.size(19.dp))
                }
            }
            Spacer(Modifier.width(6.dp))
            Row(Modifier.weight(1f).height(42.dp).clip(Glass.pill).background(if (priv) Color(0xFF2A2433) else Glass.raised)
                .border(1.dp, if (editing) Glass.blue.copy(alpha = 0.7f) else Glass.border, Glass.pill)
                .padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                // v0.28.2: a plain-http page says "Not secure" (secure forms and saved logins won't fill it unless it's on the LAN).
                val insecure = com.blaze.assistant.connect.UrlSafety.notSecure(tab?.url.orEmpty())
                Icon(when { priv -> Icons.Rounded.VisibilityOff; insecure -> Icons.Rounded.ErrorOutline; tab?.url.orEmpty().startsWith("https") -> Icons.Rounded.Lock; else -> Icons.Rounded.Search },
                    if (insecure) "Not secure" else null, tint = if (insecure) Glass.orange else Glass.weak, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                if (insecure && !editing) { Text("Not secure", Glass.orange, 12, maxLines = 1); Spacer(Modifier.width(6.dp)) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (bar.isEmpty()) Text(if (priv) "Private: search or type a web address" else "Search or type a web address", Glass.weak, 14, maxLines = 1)
                    BasicTextField(bar, { bar = it }, singleLine = true, cursorBrush = SolidColor(Glass.blue),
                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = Glass.text),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { tab?.let { BrowserEngine.load(it, bar) }; focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { editing = it.isFocused })
                }
                val loading = (tab?.progress ?: 100) < 100
                Icon(if (loading) Icons.Rounded.Close else Icons.Rounded.Refresh, if (loading) "Stop loading" else "Reload", tint = Glass.sub,
                    modifier = Modifier.size(30.dp).clip(CircleShape).clickable { tab?.web?.let { if (loading) it.stopLoading() else it.reload() } }.padding(6.dp))
            }
            Spacer(Modifier.width(6.dp))
            GlassIconButton(Icons.Rounded.AutoAwesome, "Ask Blaze about this page", size = 38.dp, bg = Glass.blue,
                enabled = tab?.url.orEmpty().startsWith("http") || previewPage != null) { askFirst = null; askOpen = true }
        }
        // v0.33.3: no status line and no tab pills (the tabs button keeps the count); just a 2dp progress line laid
        // over the bottom edge of the address row, taking no layout space.
        if ((tab?.progress ?: 100) in 0..99) ThinProgress(tab?.progress ?: 100, Modifier.align(Alignment.BottomCenter).padding(horizontal = 22.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { areaSize = it }) {
        Box(Modifier.fillMaxSize().padding(horizontal = 6.dp).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 28.dp, bottomEnd = 28.dp)).background(Color.White)) {
            if (previewPage != null) {
                previewPage()
                if (pointer) PagePointerLayer(null, host, onClose = { pointer = false }, preview = previewPointer, previewMenu = previewMenu, startTip = previewTip)
            } else if (tab != null) {
                val blank = tab.url.isBlank() || tab.url == "about:blank"
                key(tab.id) { WebHost(tab, onScroll = { down -> if (panel == Panel.NONE) barShown = !down }) }
                if (blank) StartPage(tick) { u -> BrowserEngine.load(tab, u); panel = Panel.NONE }
                if (pointer) PagePointerLayer(tab.web, host, onClose = { pointer = false })
                // v0.29.7 saved passwords: offer to save after a sign-in, or offer saved accounts on a sign-in form.
                val offer = BrowserEngine.loginOffer
                if (!pointer && panel == Panel.NONE && !priv) {
                    if (offer != null && offer.tabId == tab.id) SaveLoginCard(offer, onSave = { BrowserEngine.acceptLoginOffer() },
                        onNever = { BrowserEngine.neverForSite() }, onDismiss = { BrowserEngine.dismissLoginOffer() })
                    else if (tab.fillAccounts.isNotEmpty()) LoginFillBar(tab.fillAccounts, onPick = { BrowserEngine.fillLogin(tab, it) }, onDismiss = { tab.fillAccounts = emptyList() })
                }
            }
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            androidx.compose.runtime.CompositionLocalProvider(LocalSheetInset provides (if (dock == ToolbarDock.BOTTOM) 72.dp + navBottom else navBottom)) {
            when (panel) {
                Panel.TABS, Panel.NONE -> {}
                Panel.PRIVACY -> Sheet { PrivacySheet(onImport = onImport, onPrivateTab = { BrowserEngine.newTab(ctx, isPrivate = true); panel = Panel.NONE }) }
                Panel.MENU -> Sheet { Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    val url = tab?.url.orEmpty()
                    val marked = remember(url, tick) { BrowserData.isBookmarked(ctx, url) }
                    MenuRow(Icons.Rounded.Add, "New tab") { BrowserEngine.newTab(ctx); panel = Panel.NONE }
                    MenuRow(Icons.Rounded.VisibilityOff, "New private tab") { BrowserEngine.newTab(ctx, isPrivate = true); panel = Panel.NONE }
                    MenuRow(Icons.Rounded.VpnLock, "VPN & privacy" + when (BrowserVpn.status) { BrowserVpn.Status.ON -> "  \u00b7  " + Countries.flag(BrowserVpn.exitCountry ?: BrowserVpn.current()?.country.orEmpty()); BrowserVpn.Status.FAILED -> "  \u00b7  not connected"; else -> "" }) { panel = Panel.PRIVACY }
                    if (url.startsWith("http")) MenuRow(if (marked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (marked) "Remove bookmark" else "Bookmark this page") { BrowserData.toggleBookmark(ctx, tab?.title.orEmpty(), url); tick++; panel = Panel.NONE }
                    if (url.startsWith("http")) MenuRow(Icons.Rounded.OfflinePin, "Save page for offline") {
                        tab?.let { t -> BrowserEngine.savePage(t) { msg -> android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() } }
                        panel = Panel.NONE }
                    // v0.33.2: a site as its own app on the home screen (real icon, no browser chrome).
                    if (url.startsWith("https://") && !priv) MenuRow(Icons.Rounded.AddToHomeScreen, "Add to Home screen / Install app") { installUrl = url; panel = Panel.INSTALL_SITE }
                    MenuRow(Icons.Rounded.Apps, "Installed apps") { panel = Panel.SITE_APPS }
                    MenuRow(Icons.Rounded.Bookmarks, "Bookmarks") { panel = Panel.BOOKMARKS }
                    MenuRow(Icons.Rounded.LibraryBooks, "Saved pages") { panel = Panel.SAVED_PAGES }
                    MenuRow(Icons.Rounded.Key, "Saved passwords") { panel = Panel.PASSWORDS }
                    MenuRow(Icons.Rounded.History, "History") { panel = Panel.HISTORY }
                    MenuRow(if (BrowserEngine.desktop) Icons.Rounded.PhoneAndroid else Icons.Rounded.DesktopWindows, if (BrowserEngine.desktop) "Mobile site" else "Desktop site") { BrowserEngine.useDesktop(!BrowserEngine.desktop); panel = Panel.NONE }
                    if (url.startsWith("http")) {
                        MenuRow(Icons.Rounded.AutoAwesome, "Ask Blaze about this page") { askFirst = null; askOpen = true; panel = Panel.NONE }
                        MenuRow(Icons.Rounded.AdsClick, "Summon the Blaze pointer") { pointer = true; panel = Panel.NONE }
                        MenuRow(Icons.Rounded.Share, "Share link") {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); panel = Panel.NONE }
                        MenuRow(Icons.Rounded.OpenInNew, "Open in another app") {
                            try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                            panel = Panel.NONE }
                    }
                    MenuRow(Icons.Rounded.Download, "Downloads") {
                        try { ctx.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                        panel = Panel.NONE }
                    MenuRow(Icons.Rounded.ClearAll, if (priv) "Close all private tabs" else "Close all tabs") {
                        val p = priv
                        BrowserEngine.closeAll(p); if (BrowserEngine.normalTabs().isEmpty()) BrowserEngine.newTab(ctx)
                        if (!p || BrowserEngine.tab()?.isPrivate == true) BrowserEngine.current = BrowserEngine.tabs.indexOfLast { !it.isPrivate }
                        panel = Panel.NONE }
                }
                }
                Panel.PASSWORDS -> Sheet { SavedPasswordsPanel() }
                Panel.INSTALL_SITE -> Sheet { InstallSiteBody(installUrl) { panel = Panel.NONE } }
                Panel.SITE_APPS -> Sheet { InstalledSitesBody { panel = Panel.NONE } }
                Panel.SAVED_PAGES -> Sheet {
                    SavedPagesPanel(onOpen = { p ->
                        if (!BrowserEngine.openSaved(ctx, p)) android.widget.Toast.makeText(ctx, "That saved page is missing.", android.widget.Toast.LENGTH_SHORT).show()
                        panel = Panel.NONE
                    }, onRead = { p -> reading = p; panel = Panel.NONE })
                }
                Panel.BOOKMARKS -> Sheet { EntryList("Bookmarks", BrowserData.bookmarks(ctx), "No bookmarks yet.") { tab?.let { t -> BrowserEngine.load(t, it) }; panel = Panel.NONE } }
                Panel.HISTORY -> Sheet {
                    EntryList("History", BrowserData.history(ctx), "No history yet.") { tab?.let { t -> BrowserEngine.load(t, it) }; panel = Panel.NONE }
                    MenuRow(Icons.Rounded.DeleteSweep, "Clear history") { BrowserData.clearHistory(ctx); panel = Panel.NONE }
                }
            }
            }
        }

        // v0.33.3: the bar is a floating glass pill over the page (no layout space), inset from the edges and the nav bar;
        // it slides away on scroll down and back on scroll up, and always shows while a panel is open.
        // v0.34.1: drag it by its grip to the bottom, top, left or right of the page; on release it snaps (animated) to the
        // nearest edge, never resting in between, and the edge is remembered. The grip is the only drag target, so taps
        // on the buttons are untouched. Left / right turn it into a column.
        val showBar = barShown || panel != Panel.NONE || pointer || tab?.url.isNullOrBlank() || tab?.url == "about:blank"
        val hide by androidx.compose.animation.core.animateFloatAsState(if (showBar) 0f else 1f, androidx.compose.animation.core.tween(220), label = "bar")
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val dragOff = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        val snapAlpha = remember { Animatable(1f) }
        var barSize by remember { mutableStateOf(IntSize.Zero) }
        var dragging by remember { mutableStateOf(false) }
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        suspend fun snapBar() {
            val area = areaSize; val bar = barSize
            if (area.width == 0 || bar.width == 0) { dragOff.animateTo(Offset.Zero); return }
            val margin = with(density) { 10.dp.toPx() }
            val bottom = with(density) { navInset.toPx() }
            val thick = (if (dock.vertical) bar.width else bar.height).toFloat()
            val (hx, hy) = ToolbarDock.center(dock, area.width.toFloat(), area.height.toFloat(), thick, margin, bottom)
            val target = ToolbarDock.nearest(hx + dragOff.value.x, hy + dragOff.value.y, area.width.toFloat(), area.height.toFloat())
            if (target == dock) { dragOff.animateTo(Offset.Zero, spring(dampingRatio = 0.72f, stiffness = 500f)); return }
            // Glide to where it will sit, fading, then dock there (the layout and orientation change) and fade back in.
            val (tx, ty) = ToolbarDock.center(target, area.width.toFloat(), area.height.toFloat(), thick, margin, bottom)
            coroutineScope {
                launch { snapAlpha.animateTo(0.2f, tween(170)) }
                dragOff.animateTo(Offset(tx - hx, ty - hy), tween(190))
            }
            dock = target
            ToolbarDock.save(target)
            dragOff.snapTo(Offset.Zero)
            snapAlpha.animateTo(1f, tween(220))
        }
        val vertical = dock.vertical
        val barMod = Modifier.align(dock.alignment)
            .offset { IntOffset(dragOff.value.x.roundToInt(), dragOff.value.y.roundToInt()) }
            .graphicsLayer {
                val away = hide * ((if (vertical) size.width else size.height) + 48.dp.toPx())
                when (dock) {
                    ToolbarDock.BOTTOM -> translationY = away
                    ToolbarDock.TOP -> translationY = -away
                    ToolbarDock.LEFT -> translationX = -away
                    ToolbarDock.RIGHT -> translationX = away
                }
                alpha = (1f - hide) * snapAlpha.value
                if (dragging) { scaleX = 1.04f; scaleY = 1.04f }
            }
            .then(when (dock) {
                ToolbarDock.BOTTOM -> Modifier.navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 10.dp).fillMaxWidth()
                ToolbarDock.TOP -> Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp).fillMaxWidth()
                ToolbarDock.LEFT -> Modifier.navigationBarsPadding().padding(start = 10.dp, top = 10.dp, bottom = 10.dp)
                ToolbarDock.RIGHT -> Modifier.navigationBarsPadding().padding(end = 10.dp, top = 10.dp, bottom = 10.dp)
            })
            .onSizeChanged { barSize = it }
            .shadow(16.dp, Glass.pill).clip(Glass.pill).background(Glass.surface.copy(alpha = 0.93f)).border(1.dp, Glass.border, Glass.pill)
        val grip: @Composable () -> Unit = {
            Box(Modifier.size(if (vertical) 42.dp else 22.dp, if (vertical) 22.dp else 42.dp)
                .pointerInput(dock, navInset, areaSize) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false; scope.launch { snapBar() } },
                        onDragCancel = { dragging = false; scope.launch { snapBar() } },
                        onDrag = { ch, d -> ch.consume(); scope.launch { dragOff.snapTo(dragOff.value + d) } })
                }
                .semantics { contentDescription = "Move the toolbar: drag it to the top, bottom, left or right" }, contentAlignment = Alignment.Center) {
                Icon(if (vertical) Icons.Rounded.DragHandle else Icons.Rounded.DragIndicator, contentDescription = null,
                    tint = if (dragging) Glass.blue else Glass.weak, modifier = Modifier.size(18.dp))
            }
        }
        val buttons: @Composable () -> Unit = {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", size = 42.dp, bg = Color.Transparent, enabled = tab?.canBack == true) { tab?.let { BrowserEngine.back(it) } }
            GlassIconButton(Icons.Rounded.ArrowForward, "Forward", size = 42.dp, bg = Color.Transparent, enabled = tab?.canForward == true) { tab?.let { BrowserEngine.forward(it) } }
            GlassIconButton(Icons.Rounded.Home, "Start page", size = 42.dp, bg = Color.Transparent) { tab?.let { BrowserEngine.load(it, "about:blank") }; panel = Panel.NONE; pointer = false }
            Box(Modifier.size(46.dp).clip(CircleShape).background(if (pointer) Glass.raised2 else Glass.raised)
                .border(1.dp, if (pointer) Glass.blue else Glass.border, CircleShape)
                .combinedClickable(onClick = { pointer = !pointer; panel = Panel.NONE }, onLongClick = { pointer = true; panel = Panel.NONE },
                    onClickLabel = if (pointer) "Put the Blaze pointer away" else "Summon the Blaze pointer")
                .semantics { contentDescription = if (pointer) "Put the Blaze pointer away" else "Summon the Blaze pointer" }, contentAlignment = Alignment.Center) {
                MascotGlyph(36.dp, pulse = pointer)
            }
            Box(Modifier.size(42.dp).clip(CircleShape).clickable { BrowserEngine.snapshot(); panel = Panel.TABS }
                .semantics { contentDescription = "Tabs" }, contentAlignment = Alignment.Center) {
                val n = BrowserEngine.tabs.count { it.isPrivate == priv }
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).border(1.6.dp, Glass.text, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text(if (n > 99) ":D" else "$n", Glass.text, 11, weight = FontWeight.SemiBold) }
            }
            GlassIconButton(Icons.Rounded.MoreHoriz, "Menu", size = 42.dp, bg = Color.Transparent) { panel = if (panel == Panel.MENU) Panel.NONE else Panel.MENU }
        }
        if (vertical) Column(barMod.verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) { grip(); buttons() }
        else Row(barMod.padding(start = 2.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) { grip(); buttons() }
        }
    }

    if (askOpen) PageAskSheet(title = tab?.title.orEmpty(), initial = askFirst,
        readPage = { BrowserEngine.capture(ctx) }, web = tab?.web?.takeIf { previewAsk == null },
        onDismiss = { askOpen = false; askFirst = null }, preview = previewAsk)
}

@Composable
private fun WebHost(tab: BrowserEngine.Tab, onScroll: ((down: Boolean) -> Unit)? = null) {
    val cb = androidx.compose.runtime.rememberUpdatedState(onScroll)
    AndroidView(factory = { FrameLayout(it) }, update = { f ->
        val w = tab.web
        if (onScroll != null) w.setOnScrollChangeListener { _, _, y, _, oldY ->
            val d = ToolbarScroll.direction(y, oldY)
            if (d != null) cb.value?.invoke(d)
        } else w.setOnScrollChangeListener(null)
        if (w.parent !== f) { (w.parent as? ViewGroup)?.removeView(w); f.removeAllViews(); f.addView(w, FrameLayout.LayoutParams(-1, -1)) }
        // v0.30.5: Home's live preview shows this page scaled down; full size here.
        if (w.scaleX != 1f || w.scaleY != 1f) { w.scaleX = 1f; w.scaleY = 1f }
    }, modifier = Modifier.fillMaxSize())
}

private fun display(u: String) = if (u == "about:blank") "" else if (SavedPages.isSavedUrl(u)) "Offline copy" else u.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

@Composable
private fun StartPage(tick: Int, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val marks = remember(tick) { BrowserData.bookmarks(ctx) }
    val recent = remember(tick) { BrowserData.history(ctx).take(6) }
    val quick = listOf("Google" to "google.com", "YouTube" to "youtube.com", "Wikipedia" to "wikipedia.org", "Gmail" to "mail.google.com")
    LazyColumn(Modifier.fillMaxSize().background(Glass.bg).padding(horizontal = 18.dp)) {
        item {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MascotGlyph(54.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Blaze browser", Glass.text, 24, weight = FontWeight.Medium)
                    Text("Tap the mascot below to point at anything on a page.", Glass.sub, 13)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { quick.forEach { (n, u) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Glass.surface).border(1.dp, Glass.border, RoundedCornerShape(18.dp))
                    .clickable { open(u) }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Glass.raised2), contentAlignment = Alignment.Center) {
                        Text(n.take(1), Glass.text, 15, weight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(6.dp))
                    Text(n, Glass.sub, 11, maxLines = 1)
                } } }
        }
        if (marks.isNotEmpty()) { item { Spacer(Modifier.height(22.dp)); Head("Bookmarks") }
            items(marks.take(12)) { e -> EntryRow(e) { open(e.url) } } }
        if (recent.isNotEmpty()) { item { Spacer(Modifier.height(22.dp)); Head("Recent") }
            items(recent) { e -> EntryRow(e) { open(e.url) } } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable private fun Head(s: String) = Text(s, Glass.sub, 12, weight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))

@Composable private fun EntryList(title: String, l: List<BrowserData.Entry>, empty: String, open: (String) -> Unit) {
    Text(title, Glass.text, 16, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 8.dp))
    if (l.isEmpty()) Text(empty, Glass.sub, 13, modifier = Modifier.padding(start = 18.dp, bottom = 16.dp))
    LazyColumn(Modifier.heightIn(max = 420.dp).padding(horizontal = 8.dp)) { items(l.take(150)) { e -> EntryRow(e) { open(e.url) } } }
}

@Composable private fun EntryRow(e: BrowserData.Entry, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Glass.raised2), contentAlignment = Alignment.Center) {
            Text(display(e.url).take(1).uppercase().ifBlank { "\u2022" }, Glass.text, 13, weight = FontWeight.SemiBold) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title.ifBlank { e.url }, Glass.text, 14, maxLines = 1)
            Text(display(e.url), Glass.sub, 12, maxLines = 1)
        }
    }
}

/** v0.33.3: room under a sheet for the floating toolbar (the browser provides it; 0 elsewhere). */
internal val LocalSheetInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

@Composable internal fun Sheet(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().shadow(18.dp, shape).clip(shape).background(Glass.surface).border(1.dp, Glass.border, shape)
            .padding(top = 10.dp, bottom = 8.dp + LocalSheetInset.current), content = content)
    }
}

@Composable private fun MenuRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Glass.raised), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Glass.text, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(text, Glass.text, 15)
    }
}

@Composable private fun Text(s: String, color: androidx.compose.ui.graphics.Color, size: Int, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, weight: FontWeight = FontWeight.Normal) =
    androidx.compose.material3.Text(s, modifier = modifier, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = if (size >= 20) Type.Display else Type.Sans, fontWeight = weight, fontSize = size.sp, color = color))

/** v0.33.3: a 2dp progress line (brand gradient, rounded), overlaid on the address row's bottom edge. */
@Composable
private fun ThinProgress(progress: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).clip(Glass.pill)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(5, 100) / 100f).fillMaxHeight().clip(Glass.pill).background(Glass.brand))
    }
}

/**
 * v0.34.1 (pure, tested): the edges the floating toolbar can dock to, the nearest one to where it was dropped, and the
 * centre it sits at for a page area of [w] x [h] px ([thick] = the bar's depth across its edge, [margin] its inset, [bottom]
 * the navigation bar under the page). Kept in Settings as "browser_bar_dock".
 */
enum class ToolbarDock(val id: String, val vertical: Boolean, val alignment: Alignment) {
    BOTTOM("bottom", false, Alignment.BottomCenter), TOP("top", false, Alignment.TopCenter),
    LEFT("left", true, Alignment.CenterStart), RIGHT("right", true, Alignment.CenterEnd);

    companion object {
        const val KEY = "browser_bar_dock"
        fun of(id: String?): ToolbarDock = entries.firstOrNull { it.id == id } ?: BOTTOM
        fun load(): ToolbarDock = of(runCatching { com.blaze.assistant.agent.Settings.str(KEY, BOTTOM.id) }.getOrNull())
        fun save(d: ToolbarDock) { runCatching { com.blaze.assistant.agent.Settings.setStr(KEY, d.id) } }

        /** The edge closest to the point (x, y) in a w x h area; a tie keeps the order bottom, top, left, right. */
        fun nearest(x: Float, y: Float, w: Float, h: Float): ToolbarDock {
            val d = mapOf(BOTTOM to h - y, TOP to y, LEFT to x, RIGHT to w - x)
            return entries.minBy { d.getValue(it) }
        }

        fun center(d: ToolbarDock, w: Float, h: Float, thick: Float, margin: Float, bottom: Float): Pair<Float, Float> = when (d) {
            BOTTOM -> w / 2 to h - bottom - margin - thick / 2
            TOP -> w / 2 to margin + thick / 2
            LEFT -> margin + thick / 2 to (h - bottom) / 2
            RIGHT -> w - margin - thick / 2 to (h - bottom) / 2
        }
    }
}

/** Pure (tested): which way a scroll moves the floating toolbar. true = down (hide), false = up or at the top (show), null = jitter. */
object ToolbarScroll {
    const val SLOP = 12
    fun direction(y: Int, oldY: Int): Boolean? = when {
        y <= 0 -> false
        y - oldY > SLOP -> true
        oldY - y > SLOP -> false
        else -> null
    }
}
