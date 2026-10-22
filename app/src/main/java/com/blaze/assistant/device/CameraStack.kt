package com.blaze.assistant.device

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * v0.34.0 Blaze Camera Night and HDR shots on the phone: a short burst (Night: 4 frames, aligned, averaged and lifted)
 * or an exposure bracket (HDR: about −2 / 0 / +2 EV, fused), at up to 1600 px (1280 on low-RAM phones) so a Galaxy
 * low-end phone handles it. The pixel maths is in [CameraSmart]. Also the 4-corner document crop.
 */
object CameraStack {
    private suspend fun grab(ctrl: CameraController, ctx: Context, maxLong: Int): Bitmap? = suspendCancellableCoroutine { cont ->
        ctrl.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val b = try {
                    val raw = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    val k = minOf(1f, maxLong.toFloat() / maxOf(raw.width, raw.height))
                    val m = Matrix().apply { postScale(k, k); postRotate(rot.toFloat()) }
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
                } catch (_: Throwable) { null } finally { image.close() }
                if (cont.isActive) cont.resume(b)
            }
            override fun onError(exception: ImageCaptureException) { if (cont.isActive) cont.resume(null) }
        })
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /**
     * Takes a Night ("night") or HDR ("hdr") shot into [out] (JPEG). [ev] = the camera's exposure-compensation range and
     * step, [current] = the index to go back to. [say] shows progress. False when the camera couldn't give the frames.
     */
    suspend fun capture(ctx: Context, ctrl: CameraController, kind: String, out: File, lowRam: Boolean,
                        lower: Int, upper: Int, step: Float, current: Int, say: (String) -> Unit): Boolean {
        val maxLong = if (lowRam) 1280 else 1600
        val frames = ArrayList<Bitmap>()
        try {
            if (kind == "hdr") {
                val br = CameraSmart.bracket(lower, upper, step)
                for ((i, idx) in br.withIndex()) {
                    say("HDR \u00b7 shot ${i + 1} of ${br.size}")
                    runCatching { ctrl.cameraControl?.setExposureCompensationIndex(idx) }
                    delay(450)   // let auto-exposure settle on the new value
                    grab(ctrl, ctx, maxLong)?.let { frames += it }
                }
                runCatching { ctrl.cameraControl?.setExposureCompensationIndex(current) }
            } else {
                for (i in 0 until 4) { say("Night \u00b7 hold still \u00b7 ${i + 1} of 4"); grab(ctrl, ctx, maxLong)?.let { frames += it } }
            }
            if (frames.isEmpty()) return false
            say(if (kind == "hdr") "Merging the exposures\u2026" else "Stacking the frames\u2026")
            val w = frames[0].width; val h = frames[0].height
            val same = frames.filter { it.width == w && it.height == h }
            val result = withContext(Dispatchers.Default) {
                val px = same.map { pixels(it) }
                if (kind == "hdr") {
                    // Align the bracket to the middle exposure before fusing.
                    val refIdx = px.size / 2
                    val (g0, sw, sh) = CameraSmart.smallGrey(px[refIdx], w, h, 4)
                    val shifts = px.map { p -> val (g, _, _) = CameraSmart.smallGrey(p, w, h, 4); CameraSmart.align(g0, g, sw, sh, 6).let { (dx, dy) -> dx * 4 to dy * 4 } }
                    val aligned = px.mapIndexed { i, p -> if (shifts[i] == (0 to 0)) p else CameraSmart.stack(listOf(p), listOf(shifts[i]), w, h) }
                    CameraSmart.fuse(aligned)
                } else {
                    val (g0, sw, sh) = CameraSmart.smallGrey(px[0], w, h, 4)
                    val shifts = px.map { p -> val (g, _, _) = CameraSmart.smallGrey(p, w, h, 4); CameraSmart.align(g0, g, sw, sh, 6).let { (dx, dy) -> dx * 4 to dy * 4 } }
                    val avg = CameraSmart.stack(px, shifts, w, h)
                    CameraSmart.lift(avg, CameraSmart.nightGamma(avg))
                }
            }
            val bmp = Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
            withContext(Dispatchers.IO) { out.parentFile?.mkdirs(); out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
            return true
        } catch (_: Throwable) {
            return false
        } finally { say("") }
    }

    /** Warps the page inside [corners] (tl, tr, br, bl, in the picture's pixels) to a flat rectangle, overwriting [f]. */
    suspend fun cropDocument(f: File, corners: List<Pair<Float, Float>>): Boolean = withContext(Dispatchers.Default) {
        try {
            val src = android.graphics.BitmapFactory.decodeFile(f.path) ?: return@withContext false
            val c = CameraSmart.order(corners)
            val (w, h) = CameraSmart.pageSize(c)
            val m = Matrix()
            val s = floatArrayOf(c[0].first, c[0].second, c[1].first, c[1].second, c[2].first, c[2].second, c[3].first, c[3].second)
            val d = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
            if (!m.setPolyToPoly(s, 0, d, 0, 4)) return@withContext false
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
            f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            true
        } catch (_: Throwable) { false }
    }
}
