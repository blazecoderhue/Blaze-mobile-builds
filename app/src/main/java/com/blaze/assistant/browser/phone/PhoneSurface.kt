package com.blaze.assistant.browser.phone

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * 0.45.19 Blaze Phone browser: Blaze's own screen. A private VirtualDisplay (no MediaProjection, nothing of the user's
 * screen: only Blaze's own window is on it) backed by an ImageReader, with a Presentation window holding the WebView.
 * The page renders and runs there while the user is in other apps.
 *
 * Frames: the compositor only produces an image when the page drew something new ("on change"). The newest image is
 * held (one at a time, nothing converted) and turned into a bitmap only when the live view watches (throttled by
 * [PhonePolicy.frameInterval]: ≤5 fps while Blaze acts, ≤2 idle, lower in low-RAM mode) or a screenshot asks. Two
 * reusable bitmaps (double buffer) for the live view, so frames cost no allocations.
 *
 * Everything here runs on the main thread except the reader callback, which runs on its own [HandlerThread].
 */
class PhoneSurface(private val app: Context, val cfg: PhonePolicy.Config) {
    val size: PhonePolicy.DisplaySize = app.resources.displayMetrics.let { dm ->
        PhonePolicy.displaySize(minOf(dm.widthPixels, dm.heightPixels), maxOf(dm.widthPixels, dm.heightPixels), dm.densityDpi, cfg.scale)
    }
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    /** The Presentation's root; the WebView lives here unless the user has taken control. */
    var container: FrameLayout? = null
        private set
    @Volatile private var held: Image? = null
    private val gate = PhonePolicy.FrameGate()
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var next = 0
    /** Someone is looking at frames (the chat tile or Blaze Phone full-screen). */
    @Volatile var watching = 0
    /** Last time Blaze acted (frames come faster for a few seconds after). */
    @Volatile var actedAt = 0L
    /** Called on the reader thread with a fresh frame (a reused bitmap: draw it, don't keep it past the next frame). */
    @Volatile var onFrame: ((Bitmap, Int, Int) -> Unit)? = null
    @Volatile var frames = 0L
        private set

    val ok: Boolean get() = presentation != null

    /** Builds the display and shows [web] on it. False when this phone refuses (then Blaze browses offscreen, no frames). */
    fun start(web: WebView): Boolean {
        if (presentation != null) { put(web); return true }
        return try {
            val t = HandlerThread("blaze-phone-frames").also { it.start() }
            thread = t; handler = Handler(t.looper)
            val r = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, cfg.maxImages.coerceAtLeast(2))
            reader = r
            r.setOnImageAvailableListener({ onImage(it) }, handler)
            val dm = app.getSystemService(DisplayManager::class.java)
            // Private (not PUBLIC), own content only: no other app's window or mirroring can land here.
            val vd = dm.createVirtualDisplay("blaze-phone", size.width, size.height, size.dpi, r.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            display = vd
            val p = Presentation(app, vd.display)
            p.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
            val root = FrameLayout(p.context)
            root.setBackgroundColor(android.graphics.Color.WHITE)
            p.setContentView(root, ViewGroup.LayoutParams(size.width, size.height))
            p.setCancelable(false)
            p.show()
            container = root; presentation = p
            put(web)
            true
        } catch (e: Throwable) {
            com.blaze.assistant.device.CrashLog.quiet("PhoneSurface.start", e)
            release(); false
        }
    }

    /** Puts [web] on Blaze's screen (from wherever it was, e.g. back from the user's full-screen control). */
    fun put(web: WebView) {
        val root = container ?: return
        if (web.parent === root) return
        (web.parent as? ViewGroup)?.removeView(web)
        web.scaleX = 1f; web.scaleY = 1f
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.visibility = View.VISIBLE
        web.requestFocus()
        // The display has no input focus of its own; tell the page its window is focused so fields accept keys and caret.
        try { web.dispatchWindowFocusChanged(true) } catch (_: Throwable) { }
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return
        held?.let { try { it.close() } catch (_: Throwable) { } }
        held = img
        frames++
        val now = System.currentTimeMillis()
        if (watching > 0 && onFrame != null && gate.allow(now, PhonePolicy.frameInterval(cfg, now - actedAt < 3000))) {
            val b = toBitmap(img, reuse = true) ?: return
            onFrame?.invoke(b, size.width, size.height)
        }
    }

    /** The held image as a bitmap: one of the two live buffers ([reuse]) or a new one (screenshots). */
    private fun toBitmap(img: Image, reuse: Boolean): Bitmap? = try {
        val pl = img.planes[0]
        val ps = pl.pixelStride; val rs = pl.rowStride
        val bw = rs / ps
        val b = if (reuse) {
            val cur = buffers[next]?.takeIf { it.width == bw && it.height == img.height && !it.isRecycled }
                ?: Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888).also { buffers[next] = it }
            next = 1 - next; cur
        } else Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
        pl.buffer.rewind()
        b.copyPixelsFromBuffer(pl.buffer)
        b
    } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("PhoneSurface.toBitmap", e); null }

    /**
     * A screenshot of Blaze's screen now (the newest frame; waits up to [waitMs] for one when none came yet), scaled to
     * [maxWidth], as JPEG bytes (what the gateway's vision models take). Null when there is no display.
     */
    suspend fun screenshot(maxWidth: Int = cfg.shotMaxWidth, quality: Int = cfg.jpegQuality, waitMs: Long = 1500): ByteArray? {
        val h = handler ?: return null
        if (held == null) withTimeoutOrNull(waitMs) { while (held == null) kotlinx.coroutines.delay(50) }
        val d = CompletableDeferred<ByteArray?>()
        h.post { d.complete(encode(maxWidth, quality)) }
        return withTimeoutOrNull(3000) { d.await() }
    }

    /** Reader thread: the held frame, cropped to the display, scaled to [maxWidth], as JPEG. */
    private fun encode(maxWidth: Int, quality: Int): ByteArray? = try {
        val img = held
        val full = if (img == null) null else toBitmap(img, reuse = false)
        if (full == null) null else {
            val crop = if (full.width != size.width) Bitmap.createBitmap(full, 0, 0, size.width.coerceAtMost(full.width), full.height) else full
            val w = crop.width.coerceAtMost(maxWidth.coerceAtLeast(240))
            val out = if (w < crop.width) Bitmap.createScaledBitmap(crop, w, (crop.height.toLong() * w / crop.width).toInt().coerceAtLeast(1), true) else crop
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), bos)
            if (out !== crop) out.recycle()
            if (crop !== full) crop.recycle()
            full.recycle()
            bos.toByteArray()
        }
    } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("PhoneSurface.screenshot", e); null }

    /** Bytes the surface holds right now (display buffers + live bitmaps), for phone_status's RAM line. */
    fun bytesHeld(): Long {
        val buf = size.width.toLong() * size.height * 4
        return (if (ok) buf * cfg.maxImages.coerceAtLeast(2) else 0L) + buffers.sumOf { b -> b?.takeIf { !it.isRecycled }?.byteCount?.toLong() ?: 0L }
    }

    /** Frees the window, the display, the reader and its thread. The WebView is left to its owner. */
    fun release() {
        try { container?.removeAllViews() } catch (_: Throwable) { }
        try { presentation?.dismiss() } catch (_: Throwable) { }
        presentation = null; container = null
        try { display?.release() } catch (_: Throwable) { }
        display = null
        val h = handler; val r = reader
        handler = null; reader = null
        val done = Runnable {
            try { held?.close() } catch (_: Throwable) { }
            held = null
            try { r?.close() } catch (_: Throwable) { }
            // Dropped, not recycled: the live view may still be drawing the last one (GC frees them).
            buffers[0] = null; buffers[1] = null
        }
        if (h != null) { h.post(done); thread?.quitSafely() } else done.run()
        thread = null
        onFrame = null
        gate.reset()
    }
}
