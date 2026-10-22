package com.blaze.assistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * v0.26.3 offline diagrams, the Android part. Draws Mermaid with the bundled mermaid.js in an offscreen WebView
 * (never attached to the screen) and keeps the picture as a PNG in filesDir/diagrams/<sha-256>.png, so a diagram
 * is drawn once and shows again instantly, also in airplane mode.
 *
 *  - Nothing leaves the phone: the page's only allowed requests are the bundled script (served from the APK);
 *    every other request gets a 403 from [WebViewClient.shouldInterceptRequest], and the page's CSP allows none.
 *  - The UI thread is never blocked: WebView calls are short and the wait is a suspended poll ([DiagramRender.TIMEOUT_MS]).
 *  - One diagram at a time ([lock]); the WebView is reused for the next one and destroyed after [IDLE_MS] idle.
 *  - Order and fallbacks: [DiagramRender.plan]. A syntax error isn't retried elsewhere (it would fail there too).
 */
object DiagramRenderer {
    const val ASSET = "diagram/mermaid.min.js"
    private const val IDLE_MS = 30_000L
    private const val CACHE_FILES = 300
    private const val CACHE_BYTES = 60L * 1024 * 1024

    sealed class Outcome {
        /** [remote] = drawn by mermaid.ink / kroki (light picture on white). */
        data class Picture(val bmp: ImageBitmap, val remote: Boolean) : Outcome()
        data class Failed(val line: String) : Outcome()
    }

    private val lock = Mutex()
    private val mem = object : android.util.LruCache<String, ImageBitmap>(16) {}
    @SuppressLint("StaticFieldLeak") private var warm: WebView? = null   // application context only
    private var idle: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun dir(ctx: Context) = File(ctx.filesDir, "diagrams")

    /** A picture already in memory (no disk, no drawing): lets a recomposed reply show its diagram at once. */
    fun cached(kind: String, code: String, private: Boolean, dark: Boolean): Outcome? {
        for (src in DiagramRender.plan(kind, private)) mem.get(DiagramRender.cacheKey(kind, code, dark, src))?.let {
            return Outcome.Picture(it, src == DiagramRender.Source.REMOTE) }
        return null
    }

    suspend fun draw(ctx: Context, kind: String, code: String, private: Boolean, dark: Boolean, remoteUrl: String): Outcome {
        val plan = DiagramRender.plan(kind, private)
        if (plan.isEmpty()) return Outcome.Failed(DiagramRender.errorLine(kind, DiagramRender.Why.PRIVATE_REMOTE_ONLY))
        var why = DiagramRender.Why.FAILED
        for (src in plan) {
            val key = DiagramRender.cacheKey(kind, code, dark, src)
            mem.get(key)?.let { return Outcome.Picture(it, src == DiagramRender.Source.REMOTE) }
            fromDisk(ctx, key)?.let { mem.put(key, it); return Outcome.Picture(it, src == DiagramRender.Source.REMOTE) }
            when (src) {
                DiagramRender.Source.LOCAL -> {
                    if (code.length > DiagramRender.MAX_CODE) { why = DiagramRender.Why.TOO_LONG; continue }
                    val r = try { lock.withLock { renderLocal(ctx, code, dark) } } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Throwable) { DiagramRender.Result(false, error = e.message ?: "WebView unavailable") }
                    if (r.ok) {
                        val bmp = savePng(ctx, key, r.png)
                        if (bmp != null) { mem.put(key, bmp); return Outcome.Picture(bmp, false) }
                    } else {
                        if (r.syntax) return Outcome.Failed(DiagramRender.errorLine(kind, DiagramRender.Why.SYNTAX))
                        why = if (r.error == "timeout") DiagramRender.Why.TIMEOUT else DiagramRender.Why.FAILED
                    }
                }
                DiagramRender.Source.REMOTE -> {
                    val bmp = RichImages.load(ctx, remoteUrl)
                    if (bmp != null) { mem.put(key, bmp); return Outcome.Picture(bmp, true) }
                    why = DiagramRender.Why.OFFLINE
                }
            }
        }
        return Outcome.Failed(DiagramRender.errorLine(kind, why))
    }

    private suspend fun fromDisk(ctx: Context, key: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = File(dir(ctx), "$key.png")
        if (!f.isFile) return@withContext null
        try { BitmapFactory.decodeFile(f.path)?.asImageBitmap()?.also { f.setLastModified(System.currentTimeMillis()) } } catch (_: Throwable) { null }
    }

    private suspend fun savePng(ctx: Context, key: String, b64: String): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val bytes = java.util.Base64.getDecoder().decode(b64)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            val d = dir(ctx).apply { mkdirs() }
            val tmp = File(d, "$key.tmp"); tmp.writeBytes(bytes); tmp.renameTo(File(d, "$key.png"))
            prune(d)
            bmp.asImageBitmap()
        } catch (_: Throwable) { null }
    }

    /** Keeps the cache to the newest [CACHE_FILES] pictures and [CACHE_BYTES]. */
    private fun prune(d: File) {
        val fs = d.listFiles { f -> f.name.endsWith(".png") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        fs.forEachIndexed { i, f -> total += f.length(); if (i >= CACHE_FILES || total > CACHE_BYTES) f.delete() }
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(ctx: Context): WebView {
        warm?.let { return it }
        val app = ctx.applicationContext
        val wv = WebView(app)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.domStorageEnabled = false
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url.toString()
                if (u == DiagramRender.SCRIPT_URL) return try {
                    WebResourceResponse("text/javascript", "utf-8", 200, "OK", mapOf("Cache-Control" to "max-age=86400"), app.assets.open(ASSET))
                } catch (_: Exception) { blocked() }
                return blocked()   // nothing else, and never the network
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = true
        }
        // Offscreen: a size so layout and getBBox work; never added to a window.
        wv.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        wv.layout(0, 0, 800, 600)
        warm = wv
        return wv
    }

    private suspend fun evaluate(wv: WebView, js: String): String? = suspendCancellableCoroutine { c ->
        try { wv.evaluateJavascript(js) { if (c.isActive) c.resume(it) } } catch (_: Throwable) { if (c.isActive) c.resume(null) }
    }

    private suspend fun renderLocal(ctx: Context, code: String, dark: Boolean): DiagramRender.Result = withContext(Dispatchers.Main) {
        idle?.cancel()
        val wv = webView(ctx)
        try {
            wv.loadDataWithBaseURL(DiagramRender.PAGE_URL, DiagramRender.harness(code, dark), "text/html", "utf-8", null)
            withTimeoutOrNull(DiagramRender.TIMEOUT_MS) {
                var r: DiagramRender.Result? = null
                while (r == null) { delay(120); r = DiagramRender.parseResult(evaluate(wv, "window.__blaze||''")) }
                r
            } ?: DiagramRender.Result(false, error = "timeout").also { recycle() }
        } catch (e: Throwable) { recycle(); throw e }
        finally {
            try { wv.loadUrl("about:blank") } catch (_: Throwable) { }
            idle = scope.launch { delay(IDLE_MS); recycle() }   // destroyed after a while without diagrams
        }
    }

    private fun recycle() {
        val w = warm ?: return
        warm = null
        try { w.stopLoading(); w.destroy() } catch (_: Throwable) { }
    }
}
