package com.blaze.assistant.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Blaze Phone (spike): the screen a guest runs on. A private VirtualDisplay that Blaze owns (OWN_CONTENT_ONLY, not
 * public, not presentation), backed by an ImageReader, so its frames come to Blaze for the live view and never reach
 * the user's real screen. The guest is launched onto it with ActivityOptions.setLaunchDisplayId (phone-engine's
 * ActivityStack); Android lets the display's owner (Blaze's uid, which the guest processes share) launch there.
 */
class GuestScreen(ctx: Context, val pkg: String, val width: Int, val height: Int, val dpi: Int) : BlazePhone.Screen {
    private val thread = HandlerThread("blaze-phone-frames-$pkg").apply { start() }
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private val display: VirtualDisplay
    private val _frames = MutableStateFlow(0L)
    /** Frames received so far (the live view collects this and reads [frame]). */
    override val frames: StateFlow<Long> = _frames
    @Volatile private var last: Bitmap? = null

    init {
        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val p = img.planes[0]
                val rowPx = p.rowStride / p.pixelStride
                val bmp = Bitmap.createBitmap(rowPx, height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(p.buffer)
                last = if (rowPx == width) bmp else Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
                _frames.value = _frames.value + 1
            } finally { img.close() }
        }, Handler(thread.looper))
        val dm = ctx.getSystemService(DisplayManager::class.java)
        display = dm.createVirtualDisplay("blaze-phone-$pkg", width, height, dpi, reader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
    }

    override val displayId: Int get() = display.display.displayId

    /** The newest frame (a copy the caller owns), or null before the first one. */
    override fun frame(): Bitmap? = last?.let { it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) }

    override fun close() {
        try { display.release() } catch (_: Exception) { }
        try { reader.close() } catch (_: Exception) { }
        thread.quitSafely()
        last = null
    }
}
