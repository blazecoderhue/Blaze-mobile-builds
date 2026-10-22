package com.blaze.assistant.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.blaze.assistant.agent.DesignPage
import com.blaze.assistant.agent.Designs
import com.blaze.assistant.agent.DesignTruth
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*

/** Turns what Blaze passed (URL, ~/path, relative path, absolute path) into something WebView can load. */
fun designUrl(target: String, home: File?): String {
    val t = target.trim()
    if (t.isEmpty()) return ""
    if (Regex("^(https?|file|about|data):", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
    if (Regex("^(localhost|127\\.0\\.0\\.1)(:\\d+)?(/.*)?$").matches(t)) return "http://$t"
    val f = when {
        t.startsWith("~/") && home != null -> File(home, t.removePrefix("~/"))
        t.startsWith("/") -> File(t)
        home != null -> File(home, t)
        else -> File(t)
    }
    return "file://" + (if (f.isDirectory) File(f, "index.html") else f).absolutePath
}

/**
 * v0.23.2: three.js works offline. 3D designs import three.js from a CDN (importmap); the Design view
 * serves those requests from copies bundled in the APK (assets/design/three, three.js r170, MIT), so a
 * scene opens with no network and never waits on the CDN. Returns the asset name for [url], or null.
 */
fun bundledThree(url: String): String? {
    val u = url.substringBefore('?').substringBefore('#')
    if (!Regex("^https?://(cdn\\.jsdelivr\\.net|unpkg\\.com|cdnjs\\.cloudflare\\.com|esm\\.sh|ga\\.jspm\\.io)/", RegexOption.IGNORE_CASE).containsMatchIn(u)) return null
    if (!u.contains("three", ignoreCase = true)) return null
    return when {
        Regex("/build/three\\.module(\\.min)?\\.js$").containsMatchIn(u) || Regex("/three\\.js/r?[\\d.]+/three\\.module(\\.min)?\\.js$").containsMatchIn(u) -> "three.module.min.js"
        u.endsWith("/examples/jsm/controls/OrbitControls.js") || u.endsWith("/addons/controls/OrbitControls.js") -> "OrbitControls.js"
        u.endsWith("/examples/jsm/exporters/GLTFExporter.js") || u.endsWith("/addons/exporters/GLTFExporter.js") -> "GLTFExporter.js"
        // r162's GLTFExporter imports ../utils/TextureUtils.js (only bundled with the r162 build).
        u.endsWith("/examples/jsm/utils/TextureUtils.js") || u.endsWith("/addons/utils/TextureUtils.js") -> "TextureUtils.js"
        else -> null
    }
}

/** The blaze-parts spec of a 3D design file, or null (not a parts-based 3D design, or unreadable). */
fun designSpec(target: String, home: File?): org.json.JSONObject? = try {
    val u = designUrl(target, home)
    if (!u.startsWith("file://")) null else File(u.removePrefix("file://")).takeIf { it.isFile && it.length() < 2_000_000 }
        ?.let { com.blaze.assistant.agent.Designs.specFrom(it.readText()) }
        ?.let { com.blaze.assistant.agent.Designs.normalizeSpec(it) }   // v0.23.5: known shapes, unique ids
} catch (_: Exception) { null }

/** v0.45.6: true when the design file is a three.js page (it must report that it drew, like a parts-based 3D design). */
fun designUsesThree(target: String, home: File?): Boolean = try {
    val u = designUrl(target, home)
    u.startsWith("file://") && File(u.removePrefix("file://")).takeIf { it.isFile && it.length() < 2_000_000 }?.let { DesignServe.usesThree(it.readText()) } == true
} catch (_: Exception) { false }

/**
 * v0.23.3: the JS bridge a Design page calls (window.BlazeDesign), injected by [DesignServe.HOOK_JS].
 * Calls arrive on a WebView binder thread; they're posted to the main thread.
 */
class DesignBridge(private val onGl: (Int, Boolean, String) -> Unit, private val onError: (String) -> Unit,
                   private val onReady: (String) -> Unit, private val onFail: (String) -> Unit, private val onLoaded: () -> Unit = {}) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** v0.28.2: set when the view leaves; later calls (and ones already posted) are dropped. */
    @Volatile var disposed = false
        private set
    fun dispose() { disposed = true; main.removeCallbacksAndMessages(null) }
    private fun post(f: () -> Unit) { if (!disposed) main.post { if (!disposed) f() } }
    // Strings from the page are capped: the bridge only updates the view's state.
    @android.webkit.JavascriptInterface fun gl(level: Int, gl1: Boolean, engine: String?) { val e = DesignServe.bridgeText(engine, 16); post { onGl(level.coerceIn(-1, 2), gl1, e) } }
    @android.webkit.JavascriptInterface fun error(msg: String?) { val m = DesignServe.bridgeText(msg); post { onError(m) } }
    @android.webkit.JavascriptInterface fun ready(engine: String?) { val e = DesignServe.bridgeText(engine, 16); post { onReady(e) } }
    @android.webkit.JavascriptInterface fun fail(msg: String?) { val m = DesignServe.bridgeText(msg); post { onFail(m) } }
    /** v0.45.6: a plain (non-WebGL) page finished loading. */
    @android.webkit.JavascriptInterface fun loaded(kind: String?) { post { onLoaded() } }
}

/** What the current attempt to draw a design has reported. */
private class RenderState {
    var ready by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var forceGl1 by mutableStateOf(false)
    var glLevel = -1
    var gl1 = false
    /** Read from the WebView's request thread (CDN fallback picks the same build as the page). */
    @Volatile var engine: String? = null
    val errors = ArrayList<String>()
    var grace: kotlinx.coroutines.Job? = null
    fun begin() { ready = false; failure = null; glLevel = -1; gl1 = false; engine = null; errors.clear(); grace?.cancel(); grace = null }
}

/**
 * Design view: a live preview of what Blaze builds (HTML files, local servers, URLs), phone or
 * desktop width, with a one-line box to ask for changes without leaving. Exit is in the top bar.
 *
 * v0.23.3: designs in Blaze's home load from a virtual https origin ([DesignServe]); 3D pages report
 * ready / errors through [DesignBridge]; when three.js can't start, the view retries with the WebGL1 build
 * and then draws the parts natively ([PartsSketchView]) with the real error and Retry, so a 3D design
 * always shows something. Errors go to ~/designs/.last_error.txt for Blaze's design tool.
 * [initialFailure] shows the fallback straight away (screenshots).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun DesignPreview(target: String, busy: Boolean, onOpenInBrowser: (String) -> Unit, home: File?, wide: Boolean, reloadKey: Int,
                           preview: Boolean = true, initialFailure: String? = null) {
    val c = LocalBlaze.current
    val fileUrl = remember(target, home) { designUrl(target, home) }
    // v0.28.2: every page is served from the virtual origin (files outside Blaze's home too), so the WebView has no file access.
    val url = remember(fileUrl, home) { DesignServe.servedUrl(fileUrl, home) }
    val appData = androidx.compose.ui.platform.LocalContext.current.applicationInfo?.dataDir?.let { File(it) }
    val extRoot = remember(fileUrl, home) { DesignServe.extRoot(fileUrl, home, appData) }
    /** A link the page tried to open outside the design: offered as "Open in browser", never followed by itself. */
    var external by remember(url) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(reloadKey) { if (reloadKey > 0) reload++ }
    val spec3d = remember(target, reload) { designSpec(target, home) }
    val is3d = spec3d != null
    // v0.45.6: any three.js page must prove it drew (pixels), not only parts-based 3D designs.
    val glPage = is3d || remember(target, reload) { designUsesThree(target, home) }
    val statusPath = remember(fileUrl) { fileUrl.removePrefix("file://") }
    val rs = remember(url) { RenderState().also { it.failure = initialFailure } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val errorFile = remember(home) { home?.let { File(File(it, "designs"), DesignServe.ERROR_FILE) } }

    fun writeErrors() {
        val f = errorFile ?: return
        val text = DesignServe.errorReport(fileUrl.removePrefix("file://"), rs.engine, rs.glLevel, rs.errors.toList(), System.currentTimeMillis())
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { try { f.parentFile?.mkdirs(); f.writeText(text) } catch (_: Exception) { } }
    }
    fun restart(forceGl1: Boolean) { rs.forceGl1 = forceGl1; rs.begin(); reload++ }
    fun fail(msg: String) {
        if (rs.failure != null) return
        rs.grace?.cancel()
        if (rs.errors.none { it == msg }) rs.errors += msg
        writeErrors()
        val engine = if (rs.forceGl1) DesignServe.R162 else rs.engine
        when (DesignServe.onFail(engine, rs.gl1, is3d)) {
            DesignServe.Next.RETRY_WEBGL1 -> restart(true)
            else -> { rs.ready = false; rs.failure = msg; DesignStatus.failed(statusPath, msg) }
        }
    }
    fun noteError(msg: String, fatal: Boolean) {
        if (rs.errors.size >= 20) rs.errors.removeAt(0)
        rs.errors += msg
        writeErrors()
        if (!glPage || rs.failure != null) return
        if (fatal) { fail(msg); return }
        if (!rs.ready && rs.grace == null) rs.grace = scope.launch {
            kotlinx.coroutines.delay(DesignServe.ERROR_GRACE_MS)
            if (!rs.ready && rs.failure == null) fail(rs.errors.firstOrNull() ?: msg)
        }
    }

    // When Blaze finishes a turn, show its latest version.
    var wasBusy by remember { mutableStateOf(busy) }
    androidx.compose.runtime.LaunchedEffect(busy) { if (wasBusy && !busy) restart(rs.forceGl1); wasBusy = busy }
    // A 3D page has READY_TIMEOUT_MS to say it's drawing (the test phone parses three.js slowly).
    androidx.compose.runtime.LaunchedEffect(url, wide, reload, preview) {
        if (preview && glPage && rs.failure == null) {
            kotlinx.coroutines.delay(DesignServe.READY_TIMEOUT_MS)
            if (!rs.ready && rs.failure == null) fail("The 3D engine didn't start within ${DesignServe.READY_TIMEOUT_MS / 1000} s (" + (rs.engine?.ifBlank { null } ?: "no engine") + ").")
        }
    }
    run {
        Box(Modifier.fillMaxSize()) {
            val failure = rs.failure
            if (url.isBlank()) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Label("Design")
                    Spacer(Modifier.padding(4.dp))
                    Text("Ask Blaze for anything visual: a 3D model, a site, an app screen, a diagram, a chart, a poster. It opens here.",
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
                }
            } else if (!preview && glPage && spec3d == null) {
                // v0.45.6: never a blank pane: no WebGL preview here, and no parts to draw natively.
                FailBanner("WebGL previews are off here, so this 3D page can't be drawn.", sketch = false, onRetry = { restart(false) }, modifier = Modifier.align(Alignment.Center))
            } else if (spec3d != null && (!preview || failure != null)) {
                // No WebGL (screenshots), or three.js couldn't start on this phone: draw the same parts natively.
                // The banner sits under the drawing (not over it), so the whole model stays visible.
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        PartsSketchView(spec3d)
                        Label("3D \u00b7 " + spec3d.optString("name") + if (failure != null) " \u00b7 simple view" else "", color = c.sub, size = 9.sp,
                            modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
                    }
                    if (failure != null) FailBanner(failure, sketch = true, onRetry = { restart(false) })
                }
            } else if (preview) {
                androidx.compose.runtime.key(url, wide, reload) {
                    val loadUrl = DesignServe.attemptUrl(url, rs.forceGl1)
                    val bridge = remember { DesignBridge(
                                onGl = { level, gl1, engine -> rs.glLevel = level; rs.gl1 = gl1; rs.engine = engine },
                                onError = { noteError(it, fatal = false) },
                                onReady = { engine -> rs.grace?.cancel(); rs.grace = null; rs.ready = true; rs.engine = engine
                                    DesignStatus.rendered(statusPath, "drew on screen (" + engine + ")")
                                    try { errorFile?.delete() } catch (_: Exception) { } },
                                onFail = { fail(it) },
                                onLoaded = { if (!glPage && rs.failure == null) { rs.ready = true; DesignStatus.rendered(statusPath, "page loaded") } }) }
                    androidx.compose.runtime.DisposableEffect(bridge) { onDispose { bridge.dispose() } }
                    androidx.compose.runtime.LaunchedEffect(Unit) { DesignStatus.pending(statusPath) }
                    val paperArgb = c.paper.toArgb()
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                        WebView(ctx).apply {
                            // v0.45.6: an opaque, hardware-drawn view: a page that draws nothing is never see-through.
                            setBackgroundColor(paperArgb)
                            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // v0.28.2: pages come from the virtual origin (shouldInterceptRequest), never file:// or content://.
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.useWideViewPort = wide
                            settings.loadWithOverviewMode = wide
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            addJavascriptInterface(bridge, "BlazeDesign")
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                        val t = m.message() ?: ""
                                        // "Uncaught …" also reaches window.onerror, which the hook reports with more context.
                                        if (!t.startsWith("Uncaught")) noteError("console: $t (" + (m.sourceId() ?: "").substringAfterLast('/') + ":" + m.lineNumber() + ")",
                                            fatal = DesignServe.fatalConsole(t))
                                        else if (DesignServe.fatalConsole(t)) noteError(t, fatal = true)
                                    }
                                    return false
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView, req: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? =
                                    serveDesign(ctx, req.url.toString(), home, rs.engine, extRoot)
                                // v0.28.2: the top frame stays on the design's origin; a tapped link elsewhere is offered, not followed.
                                override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest): Boolean {
                                    if (!req.isForMainFrame) return false
                                    val next = req.url.toString()
                                    if (DesignServe.allowNavigation(loadUrl, next)) return false
                                    if (req.hasGesture() && (next.startsWith("https://") || next.startsWith("http://"))) external = next
                                    return true
                                }
                                override fun onPageFinished(view: WebView, u: String?) {
                                    if (wide) view.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]');" +
                                        "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.content='width=1280';})()", null)
                                }
                            }
                            // v0.26.1: a Mermaid file (also outside Blaze's home, where requests aren't served by us) is drawn as a picture.
                            val mmd = if (fileUrl.startsWith("file://")) mermaidFile(File(fileUrl.removePrefix("file://"))) else null
                            if (mmd != null) loadDataWithBaseURL(DesignServe.ORIGIN + "/", DesignServe.mermaidHtml(mmd, File(fileUrl).nameWithoutExtension), "text/html", "utf-8", null)
                            else loadUrl(loadUrl)
                        }
                    })
                }
                if (failure != null) FailBanner(failure, sketch = false, onRetry = { restart(false) }, modifier = Modifier.align(Alignment.BottomCenter))
                // v0.45.6: until a 3D page proves it drew, say so (never an empty pane).
                else if (glPage && !rs.ready) Box(Modifier.align(Alignment.Center).clip(BlazeShapes.pill).background(c.menuSurface, BlazeShapes.pill)
                    .border(1.dp, c.hair2, BlazeShapes.pill).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("Drawing the 3D view\u2026", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                }
                external?.let { link ->
                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp).clip(BlazeShapes.pill).background(c.ink, BlazeShapes.pill)
                        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Link to " + (DesignServe.originOf(link).substringAfter("://").ifBlank { "another site" }), maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.paper))
                        ActionText("Open in browser", color = c.paper, size = 9.sp, modifier = Modifier.clickable { external = null; onOpenInBrowser(link) }.padding(6.dp))
                        ActionText("\u2715", color = c.paper, size = 9.sp, modifier = Modifier.clickable { external = null }.padding(6.dp))
                    }
                }
            }
        }
    }
}

/** The "3D engine couldn't start" banner: the real error, what's shown instead, and Retry. */
@Composable
private fun FailBanner(error: String, sketch: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(12.dp).clip(BlazeShapes.medium).background(DialogColors.surface, BlazeShapes.medium).border(1.dp, DialogColors.border, BlazeShapes.medium)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(DesignServe.failText(error), maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = GlassTokens.warning))
            if (sketch) Text("Showing a simple drawing instead: drag to turn, pinch to zoom.",
                style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = GlassTokens.sub))
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(BlazeShapes.pill).background(GlassTokens.accent, BlazeShapes.pill).clickable { onRetry() }.padding(horizontal = 14.dp, vertical = 8.dp)) {
            ButtonText("Retry", color = GlassTokens.onAccent, size = 13.sp)
        }
    }
}

/**
 * Answers a Design view request: the virtual origin (designs, Blaze's home, bundled three.js) and CDN three.js
 * URLs from the APK (the build matching [engine]). Null = let the network have it.
 */
fun serveDesign(ctx: android.content.Context, url: String, home: File?, engine: String?, ext: File? = null): android.webkit.WebResourceResponse? {
    fun notFound() = android.webkit.WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Access-Control-Allow-Origin" to "*"), java.io.ByteArrayInputStream(ByteArray(0)))
    fun ok(mime: String, data: java.io.InputStream, cache: String) = android.webkit.WebResourceResponse(mime,
        if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript")) "utf-8" else null, 200, "OK",
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to cache), data)
    return try {
        when (val t = DesignServe.resolve(url, home, ext)) {
            is DesignServe.Target.Asset -> try { ok(t.mime, ctx.assets.open(t.path), "max-age=86400") } catch (_: Exception) { notFound() }
            is DesignServe.Target.Disk -> when {
                !t.file.isFile -> notFound()
                t.mime == "text/html" -> {
                    // v0.23.5: a page from an older stock starter is served from the current one (same parts).
                    val html = com.blaze.assistant.agent.Designs.upgradeStarter(t.file.readText(),
                        try { com.blaze.assistant.agent.Designs.templateLoader?.invoke() } catch (_: Exception) { null })
                    val is3d = com.blaze.assistant.agent.Designs.specFrom(html) != null
                    ok("text/html", java.io.ByteArrayInputStream(DesignServe.prepareHtml(html, is3d).toByteArray(Charsets.UTF_8)), "no-store")
                }
                // v0.26.1: a Mermaid file is drawn as a picture, never shown as raw source.
                mermaidFile(t.file) != null -> ok("text/html", java.io.ByteArrayInputStream(
                    DesignServe.mermaidHtml(mermaidFile(t.file)!!, t.file.nameWithoutExtension).toByteArray(Charsets.UTF_8)), "no-store")
                else -> ok(t.mime, java.io.FileInputStream(t.file), "no-store")
            }
            null -> {
                if (url.startsWith(DesignServe.ORIGIN, ignoreCase = true)) return notFound()
                val name = bundledThree(url) ?: return null
                val asset: java.io.InputStream = (try { ctx.assets.open(DesignServe.assetDir(engine ?: DesignServe.R170) + "/" + name) }
                    catch (_: Exception) { try { ctx.assets.open("design/three/$name") } catch (_: Exception) { null } }) ?: return null
                ok("text/javascript", asset, "max-age=86400")
            }
        }
    } catch (_: Exception) { null }
}

/** The Mermaid source of a previewed file (see [DesignServe.mermaidSource]), or null. Small text files only. */
fun mermaidFile(f: File): String? = try {
    val ext = f.extension.lowercase()
    if (!f.isFile || f.length() > 200_000 || ext !in setOf("mmd", "mermaid", "md", "markdown", "txt", "")) null
    else DesignServe.mermaidSource(f.name, f.readText())
} catch (_: Exception) { null }

// ---------------- v0.35.0 Design studio ----------------

/** The studio's four modes. Page and 3D draw what Blaze makes today; Motion and Audio land in phases 3 and 4. */
enum class StudioMode(val label: String) { PAGE("Page"), THREE_D("3D"), MOTION("Motion"), AUDIO("Audio") }

/** Prompt chips per mode (they ask Blaze; the Page ones name the selected element when there is one). */
/** v0.45.6: what is open, so the chips fit it (no "Make the headline bigger" on a 3D model). */
enum class Artifact { NONE, STUDIO_PAGE, WEB_PAGE, THREE_PAGE, MODEL_3D }

fun artifactOf(hasFile: Boolean, studioPage: Boolean, is3d: Boolean, usesThree: Boolean): Artifact = when {
    !hasFile -> Artifact.NONE
    is3d -> Artifact.MODEL_3D
    studioPage -> Artifact.STUDIO_PAGE
    usesThree -> Artifact.THREE_PAGE
    else -> Artifact.WEB_PAGE
}

private val chips3d = listOf("Brighter lighting", "Change the main colour", "Make it glossier", "Add a display stand")

fun studioSuggestions(mode: StudioMode, art: Artifact): List<String> = when {
    mode == StudioMode.MOTION || mode == StudioMode.AUDIO -> studioSuggestions(mode)
    art == Artifact.MODEL_3D -> chips3d
    art == Artifact.THREE_PAGE -> listOf("Slower camera move", "Brighter lighting", "Change the background", "Add a title")
    mode == StudioMode.THREE_D -> chips3d
    art == Artifact.WEB_PAGE -> listOf("Bigger headline", "Try a gold accent", "Shorter copy", "Add a button")
    else -> studioSuggestions(mode)
}

fun studioSuggestions(mode: StudioMode): List<String> = when (mode) {
    StudioMode.PAGE -> listOf("Make the headline bigger", "Try a gold accent", "Shorter copy", "Add a QR code")
    StudioMode.THREE_D -> chips3d
    StudioMode.MOTION -> listOf("Slower intro", "Bounce the button", "Stagger the words", "Make it loop smoothly")
    StudioMode.AUDIO -> listOf("Warmer chime", "Add a soft whoosh", "Deeper voice", "Trim to 3 seconds")
}

/** What the composer sends: the request, scoped to the selected element when one is selected. */
fun studioAsk(text: String, mode: StudioMode, element: DesignPage.El?): String {
    val t = text.trim()
    return when {
        element != null -> "In the current design, change the ${element.label.lowercase()} (element id \"${element.id}\"): $t"
        mode == StudioMode.THREE_D -> "In the 3D design: $t"
        mode == StudioMode.MOTION -> "Motion design: $t"
        mode == StudioMode.AUDIO -> "Audio design: $t"
        else -> t
    }
}

/**
 * Design studio (v0.35.0, phase 1): header (design name, theme, open), a mode switcher (Page · 3D · Motion · Audio),
 * the stage with floating tools (format, version and undo on top, select / colour / fit rail on the right, the design
 * check and zoom at the bottom), a contextual dock (layers in Page mode), prompt chips and the ask box.
 *
 * Page mode edits studio pages (kind canvas) natively: tap to select, drag to move, handles to resize, a colour wheel
 * with hex, text and format; each pause in editing saves a new version (Undo and Blaze's next edit see it). Other
 * pages, sites and 3D designs are previewed live as before ([DesignPreview]).
 */
@Composable
fun DesignView(target: String, busy: Boolean, status: String, onAsk: (String) -> Unit, onOpenInBrowser: (String) -> Unit, home: File?,
               preview: Boolean = true, initialFailure: String? = null,
               /** v0.45.8: Design is its own full-screen view; this is its "Back to chat" (null: no back button). */
               onBack: (() -> Unit)? = null) {
    val c = LocalBlaze.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val fileUrl = remember(target, home) { designUrl(target, home) }
    val file = remember(fileUrl) { fileUrl.takeIf { it.startsWith("file://") }?.let { File(it.removePrefix("file://")) } }
    var reload by remember { mutableIntStateOf(0) }
    // The studio page in the file (null: a site, an HTML page, a 3D scene, or nothing yet).
    var doc by remember(file) { mutableStateOf<DesignPage.Doc?>(null) }
    var savedDoc by remember(file) { mutableStateOf<DesignPage.Doc?>(null) }
    var is3d by remember(file) { mutableStateOf(false) }
    var usesThree by remember(file) { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(file, reload) {
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { file?.takeIf { it.isFile && it.length() < 2_000_000 }?.readText() } catch (_: Exception) { null }
        }
        val d = loaded?.let { DesignPage.fromHtml(it) }
        doc = d; savedDoc = d
        is3d = loaded?.let { Designs.specFrom(it) != null } == true
        usesThree = loaded?.let { DesignServe.usesThree(it) } == true
        version = try { Designs.current()?.takeIf { file != null && it.path == file.absolutePath }?.version ?: 0 } catch (_: Exception) { 0 }
    }
    var mode by remember(file) { mutableStateOf(StudioMode.PAGE) }
    androidx.compose.runtime.LaunchedEffect(is3d) { if (is3d) mode = StudioMode.THREE_D }
    // Blaze finished a turn: show its latest version.
    var wasBusy by remember { mutableStateOf(busy) }
    androidx.compose.runtime.LaunchedEffect(busy) { if (wasBusy && !busy) reload++; wasBusy = busy }
    // v0.45.6: the agent changed the design mid-turn: show it now, so its render check sees this version.
    val agentTick by DesignStatus.reload.collectAsState()
    androidx.compose.runtime.LaunchedEffect(agentTick) { if (agentTick > 0) reload++ }

    val view = remember { PageView() }
    var selected by remember(file) { mutableStateOf<String?>(null) }
    var outlines by remember { mutableStateOf(false) }
    var wide by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf("") }          // "", format, colour, text
    var colourTarget by remember { mutableStateOf("color") }
    var ask by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val d = doc
    val sel = d?.el(selected)

    // Hand edits save after a pause, as a new version.
    androidx.compose.runtime.LaunchedEffect(doc) {
        val now = doc ?: return@LaunchedEffect
        if (now == savedDoc) return@LaunchedEffect
        kotlinx.coroutines.delay(900)
        val cur = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Designs.savePage(now) }
        if (cur != null) { savedDoc = now; version = cur.version; note = "Saved as v${cur.version}" }
    }
    androidx.compose.runtime.LaunchedEffect(note) { if (note.isNotEmpty()) { kotlinx.coroutines.delay(2200); note = "" } }
    fun edit(e: DesignPage.El) { doc = doc?.replace(e) }
    fun newPage() {
        scope.launch {
            val made = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { Designs.createPage("New page", "post") } catch (_: Exception) { null } }
            if (made != null) Views.designTarget.value = made.path
        }
    }
    fun undo() {
        scope.launch {
            val o = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Designs.undoCurrent() }
            note = if (o.ok) "Back to the previous version" else "Nothing to undo"
            reload++
        }
    }

    // v0.45.8: the theme's scene shows behind Design (Classic keeps the glass ground).
    Box(Modifier.fillMaxSize().screenGround { background(c.paper).glassBackground(c.dark) }) {
        Column(Modifier.fillMaxSize()) {
            // Header: name, theme, open.
            Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 16.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) { BackButton(onBack, desc = DesignNav.BACK_LABEL); Spacer(Modifier.width(4.dp)) }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Design", style = TextStyle(fontFamily = Type.Display, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 24.sp, color = c.ink))
                        Spacer(Modifier.width(8.dp))
                        // v0.45.6: Design is labelled Beta (header and the view chip).
                        Box(Modifier.clip(BlazeShapes.pill).border(1.dp, c.hair2, BlazeShapes.pill).padding(horizontal = 7.dp, vertical = 2.dp)) {
                            Text(DESIGN_BETA, style = TextStyle(fontFamily = Type.Sans, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 10.sp, color = c.sub))
                        }
                    }
                    Text(d?.name ?: file?.parentFile?.name ?: target.trim().ifBlank { "Nothing open yet" }, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                }
                // v0.45.8: the shared theme switcher (the same options as every other screen).
                Box(Modifier.glass(BlazeShapes.pill, GlassIntensity.Low, shadow = false)) { ThemeSwitch() }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.glass(BlazeShapes.pill, GlassIntensity.Low, shadow = false)) {
                    StudioIcon(if (c.dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                        if (c.dark) "Light theme" else "Dark theme") { ThemeMode.set(if (c.dark) "light" else "dark") }
                }
                if (fileUrl.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.glass(BlazeShapes.pill, GlassIntensity.Low, shadow = false)) {
                        StudioIcon(Icons.Rounded.IosShare, "Open outside the app") { onOpenInBrowser(fileUrl) }
                    }
                }
            }
            // Mode switcher.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp).glass(BlazeShapes.pill, GlassIntensity.Low, shadow = false).padding(4.dp)) {
                StudioMode.values().forEach { m ->
                    val on = m == mode
                    val bg by androidx.compose.animation.animateColorAsState(if (on) c.ink else Color.Transparent, label = "mode")
                    Box(Modifier.weight(1f).height(34.dp).clip(BlazeShapes.pill).background(bg, BlazeShapes.pill)
                        .clickable { mode = m; selected = null }, contentAlignment = Alignment.Center) {
                        Text(m.label, style = TextStyle(fontFamily = Type.Sans, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 12.5.sp,
                            color = if (on) c.paper else c.sub))
                    }
                }
            }
            // Stage.
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(26.dp)).border(1.dp, c.hair, RoundedCornerShape(26.dp))) {
                when {
                    mode == StudioMode.PAGE && d != null -> PageCanvas(d, view, selected, outlines, highlight = null,
                        onSelect = { selected = it }, onChange = { edit(it) }, onEditText = { selected = it.id; sheet = "text" })
                    // v0.45.6: live pages sit below the Phone / Desktop and version pills, so the page's own text never runs under them.
                    mode == StudioMode.PAGE && file != null && !is3d -> Box(Modifier.fillMaxSize().padding(top = STAGE_INSET_DP.dp)) { DesignPreview(target, busy, onOpenInBrowser, home, wide, reload, preview, initialFailure) }
                    mode == StudioMode.THREE_D && is3d -> Box(Modifier.fillMaxSize().padding(top = if (version > 0) STAGE_INSET_DP.dp else 0.dp)) { DesignPreview(target, busy, onOpenInBrowser, home, false, reload, preview, initialFailure) }
                    mode == StudioMode.MOTION && file != null -> StudioStatus(DesignTruth.motionStatus(is3d), if (is3d) "Open the 3D tab" else "Open the Page tab") { mode = if (is3d) StudioMode.THREE_D else StudioMode.PAGE }
                    else -> StudioEmpty(mode, hasPage = d != null, onNewPage = { newPage() }, onAsk = { onAsk(studioAsk(it, mode, null)) })
                }
                // Top left: format (studio page) or Phone / Desktop (live page).
                Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (mode == StudioMode.PAGE && d != null) {
                        val f = DesignPage.format(d.format)
                        StudioPill(f.label, f.ratio) { sheet = "format" }
                    } else if (mode == StudioMode.PAGE && file != null && !is3d) {
                        StudioPill("Phone", on = !wide) { wide = false }
                        StudioPill("Desktop", on = wide) { wide = true }
                    }
                }
                // Top right: version and undo.
                if (version > 0 && ((mode == StudioMode.PAGE && !is3d) || (mode == StudioMode.THREE_D && is3d))) Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StudioPill("v$version", icon = Icons.Rounded.History)
                    StudioPill("", icon = Icons.Rounded.Undo) { undo() }
                }
                if (mode == StudioMode.PAGE && d != null) {
                    // Right rail: select (outlines), colour, fit.
                    Column(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).glass(RoundedCornerShape(22.dp), GlassIntensity.Medium, shadow = false).padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        StudioIcon(Icons.Rounded.NearMe, "Select, move, resize", on = outlines) {
                            outlines = !outlines; if (outlines) note = "Tap anything · drag to move · handles to resize" }
                        StudioIcon(Icons.Rounded.Palette, "Colour") {
                            if (sel == null) { outlines = true; note = "Tap an element to colour it" } else { colourTarget = if (sel.isText) "color" else "fill"; sheet = "colour" } }
                        StudioIcon(Icons.Rounded.FitScreen, "Fit") { view.fit() }
                    }
                    // Bottom: the design check (or the element bar) and zoom.
                    if (sel != null) ElementBar(sel, onText = { sheet = "text" }, onColour = { colourTarget = if (sel.isText) "color" else "fill"; sheet = "colour" },
                        onFront = { doc = DesignPage.bringToFront(d, sel.id) }, onAsk = { note = "Ask for a change to the ${sel.label.lowercase()}" },
                        onRemove = { doc = d.copy(els = d.els.filter { it.id != sel.id }); selected = null; note = "Removed · Undo brings it back" },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 50.dp))
                    else Box(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                        val cr = remember(d) { DesignPage.critique(d) }
                        CriticChip(cr) { cr.weakest?.let { selected = it; note = cr.note } }
                    }
                    Row(Modifier.align(Alignment.BottomEnd).padding(10.dp).glass(BlazeShapes.pill, GlassIntensity.Medium, shadow = false),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("\u2212", modifier = Modifier.clickable { view.zoom = (view.zoom - 0.25f).coerceAtLeast(0.5f); if (view.zoom <= 1f) view.pan = androidx.compose.ui.geometry.Offset.Zero }.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink))
                        Text("${(view.zoom * 100).roundToInt()}%", modifier = Modifier.clickable { view.fit() },
                            style = TextStyle(fontFamily = Type.Sans, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 12.sp, color = c.ink))
                        Text("+", modifier = Modifier.clickable { view.zoom = (view.zoom + 0.25f).coerceAtMost(4f) }.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink))
                    }
                }
                if (busy) Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).background(c.menuSurface).border(1.dp, c.hair2, RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text(status.ifBlank { "Blaze is designing\u2026" }, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.ink))
                }
                if (note.isNotEmpty()) Box(Modifier.align(Alignment.TopCenter).padding(top = 48.dp).clip(BlazeShapes.pill).background(c.menuSurface, BlazeShapes.pill)
                    .border(1.dp, c.hair2, BlazeShapes.pill).padding(horizontal = 15.dp, vertical = 9.dp)) {
                    Text(note, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.ink))
                }
            }
            // Dock: layers (Page mode with a studio page).
            if (mode == StudioMode.PAGE && d != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                d.painted().reversed().forEach { e ->
                    val on = e.id == selected
                    Row(Modifier.height(44.dp).clip(BlazeShapes.medium).border(1.5.dp, if (on) Color(0xFF4DA8FF) else c.hair, BlazeShapes.medium)
                        .then(if (on) Modifier.background(Color(0x144DA8FF), BlazeShapes.medium) else Modifier)
                        .clickable { selected = if (on) null else e.id }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val sw = DesignPage.parseHex(if (e.isText && e.fill.isBlank()) e.color else e.fill.ifBlank { e.color }) ?: 0xFF888888L
                        Box(Modifier.size(18.dp).clip(BlazeShapes.pill).background(argbColor(sw), BlazeShapes.pill).border(1.dp, c.hair2, BlazeShapes.pill))
                        Spacer(Modifier.width(8.dp))
                        Text(e.label, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 12.5.sp, color = c.ink))
                    }
                }
            }
            // Prompt chips.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                studioSuggestions(mode, artifactOf(file != null, d != null, is3d, usesThree)).forEach { s ->
                    Box(Modifier.height(28.dp).clip(BlazeShapes.pill).border(1.dp, c.hair, BlazeShapes.pill).clickable(enabled = !busy) { onAsk(studioAsk(s, mode, sel)) }
                        .padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
                        Text(s, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                    }
                }
            }
            // Ask box (scoped to the selected element).
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).glass(RoundedCornerShape(24.dp), GlassIntensity.Low, shadow = false)
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sel != null) Row(Modifier.height(26.dp).clip(BlazeShapes.pill).background(Color(0x244DA8FF), BlazeShapes.pill).clickable { selected = null }
                    .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sel.label + "  \u00d7", maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 11.5.sp, color = Color(0xFF4DA8FF)))
                }
                Box(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 9.dp)) {
                    if (ask.isEmpty()) Text(if (busy) status.ifBlank { "Working\u2026" } else if (sel != null) "Change the ${sel.label.lowercase()}\u2026" else "Describe a change\u2026", maxLines = 1,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = if (busy) c.accent else c.weak))
                    BasicTextField(ask, { ask = it }, maxLines = 4, cursorBrush = SolidColor(c.accent),
                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink),
                        keyboardOptions = ComposerKeys.Message,   // v0.28.1: Enter = new line; Send sends
                        modifier = Modifier.fillMaxWidth())
                }
                val canSend = ask.isNotBlank() && !busy
                Box(Modifier.size(36.dp).clip(BlazeShapes.pill).background(if (canSend) c.ink else c.ink.copy(alpha = 0.35f), BlazeShapes.pill)
                    .clickable(enabled = canSend) { onAsk(studioAsk(ask, mode, sel)); ask = "" }.semantics { contentDescription = "Send" }, contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(Icons.Rounded.ArrowUpward, null, tint = c.paper, modifier = Modifier.size(19.dp))
                }
            }
        }
        // Sheets.
        when {
            sheet == "format" && d != null -> StudioSheet("Format", "Page", onDismiss = { sheet = "" }) {
                FormatGrid(d.format) { f -> doc = DesignPage.reformat(d, f); view.fit(); sheet = "" }
            }
            sheet == "colour" && sel != null && d != null -> StudioSheet(sel.label, onDismiss = { sheet = "" },
                right = { ColourTarget(colourTarget, sel.isText) { colourTarget = it } }) {
                ColourSheetBody(sel, colourTarget, { colourTarget = it },
                    onColour = { hex -> doc?.el(sel.id)?.let { e -> edit(if (colourTarget == "fill") e.copy(fill = hex) else e.copy(color = hex)) } },
                    onSize = { sz -> doc?.el(sel.id)?.let { e -> edit(e.copy(size = sz)) } }, onDone = { sheet = "" })
            }
            sheet == "text" && sel != null -> StudioSheet(sel.label, "Text", onDismiss = { sheet = "" }) {
                TextSheetBody(sel.text) { t -> doc?.el(sel.id)?.let { e -> edit(e.copy(text = t)) }; sheet = "" }
            }
        }
    }
}

/** v0.45.6: the Design studio's Beta label. */
const val DESIGN_BETA = "BETA"
/** v0.45.6: how far live pages sit below the stage's top pills (Phone / Desktop, version, undo). */
const val STAGE_INSET_DP = 52

/** v0.45.6: one honest status for a mode that isn't built yet (Motion), with a way to the tab that has the work. */
@Composable
private fun StudioStatus(text: String, action: String, onAction: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.fillMaxSize().background(if (c.dark) Color(0xFF060607) else Color(0xFFE7E7EA)).dotGrid(c.hair)) {
        Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
            Spacer(Modifier.height(16.dp))
            BlazeButton(action, kind = BtnKind.SECONDARY, onClick = onAction)
        }
    }
}

/** An empty stage: what this mode makes, a way to start, and honest notes for modes still to come. */
@Composable
private fun StudioEmpty(mode: StudioMode, hasPage: Boolean, onNewPage: () -> Unit, onAsk: (String) -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.fillMaxSize().background(if (c.dark) Color(0xFF060607) else Color(0xFFE7E7EA)).dotGrid(c.hair)) {
        Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Label(mode.label)
            Spacer(Modifier.height(8.dp))
            Text(when (mode) {
                StudioMode.PAGE -> "Posts, stories, slides, icons and posters. Start a page and move, resize and recolour anything by hand, or ask Blaze for one."
                StudioMode.THREE_D -> "Ask Blaze for a 3D model: a product, a room, a logo in glass. Drag to orbit, pinch to zoom."
                StudioMode.MOTION -> "Animated intros, loops and transitions. The Motion timeline isn't built yet; Blaze can make an animated page, which opens on the Page tab."
                StudioMode.AUDIO -> "Sonic logos, chimes and sound beds. The audio tracks are coming to the studio next."
            }, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
            Spacer(Modifier.height(16.dp))
            when (mode) {
                StudioMode.PAGE -> if (!hasPage) BlazeButton("New page", onClick = onNewPage)
                StudioMode.THREE_D -> BlazeButton("Make a 3D model", kind = BtnKind.SECONDARY) { onAsk("Make a 3D model of a glass water-drop orb in a gold halo") }
                StudioMode.MOTION -> BlazeButton("Make an animated intro", kind = BtnKind.SECONDARY) { onAsk("Make a 3-second animated intro page for the current design") }
                StudioMode.AUDIO -> {}
            }
        }
    }
}
