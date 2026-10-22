package com.blaze.assistant.device

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Android side of [CameraFlow]: what this phone has, the capture intent, logging and photo clean-up. */
object CameraSupport {
    const val TAG = "BlazeCamera"
    private const val ASKED = "camera_perm_asked"
    private const val IN_APP = "camera_prefer_inapp"
    private const val GRID = "camera_grid"
    private const val TIMER = "camera_timer"

    fun hasCamera(ctx: Context): Boolean = try {
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    } catch (_: Exception) { true }

    /** CAMERA in the merged manifest (zxing-android-embedded adds it; see [CameraFlow]). */
    fun permissionDeclared(ctx: Context): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.CAMERA) == true
    } catch (_: Exception) { true }

    fun granted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    var askedBefore: Boolean
        get() = try { com.blaze.assistant.agent.Settings.bool(ASKED, false) } catch (_: Throwable) { false }
        set(v) { try { com.blaze.assistant.agent.Settings.setBool(ASKED, v) } catch (_: Throwable) { } }

    /** Set after the phone's camera app failed once here: next time Blaze's own camera opens straight away. */
    var preferInApp: Boolean
        get() = try { com.blaze.assistant.agent.Settings.bool(IN_APP, false) } catch (_: Throwable) { false }
        set(v) { try { com.blaze.assistant.agent.Settings.setBool(IN_APP, v) } catch (_: Throwable) { } }

    /** v0.30.x Blaze Camera Pro: the rule-of-thirds grid, remembered between sessions. */
    var grid: Boolean
        get() = try { com.blaze.assistant.agent.Settings.bool(GRID, false) } catch (_: Throwable) { false }
        set(v) { try { com.blaze.assistant.agent.Settings.setBool(GRID, v) } catch (_: Throwable) { } }

    /** The self-timer (0, 3 or 10 s), remembered between sessions. */
    var timer: Int
        get() = try { CameraTimer.normalize(com.blaze.assistant.agent.Settings.str(TIMER, "0").toIntOrNull() ?: 0) } catch (_: Throwable) { 0 }
        set(v) { try { com.blaze.assistant.agent.Settings.setStr(TIMER, CameraTimer.normalize(v).toString()) } catch (_: Throwable) { } }

    /** A fresh, not-yet-created file in cache/camera (FileProvider path "camera/") and its content uri. */
    fun newPhoto(ctx: Context): Pair<File, Uri> {
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        // Old shots pile up otherwise (each one is copied into the chat as a thumbnail when attached).
        try { dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() } } catch (_: Exception) { }
        val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        return f to FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }

    /** ACTION_IMAGE_CAPTURE with the output uri granted explicitly (EXTRA_OUTPUT + ClipData + read/write flags). */
    fun captureIntent(uri: Uri): Intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        .putExtra(MediaStore.EXTRA_OUTPUT, uri)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri("output", uri) }

    /** Something answers ACTION_IMAGE_CAPTURE (visible thanks to the manifest's <queries> entry). */
    fun hasCaptureHandler(ctx: Context): Boolean = try {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(ctx.packageManager) != null
    } catch (_: Exception) { true }

    /** Logcat + Blaze's own diagnostics log (Settings → Device & diagnostics → crash report). */
    fun log(what: String, e: Throwable? = null) {
        val line = "CAMERA $what" + (e?.let { " · ${it.javaClass.name}: ${it.message}" } ?: "") + " · ${device()}"
        try { if (e != null) Log.w(TAG, line, e) else Log.i(TAG, line) } catch (_: Throwable) { }
        CrashLog.write(line)
    }
    fun device() = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun bytes(f: File): Long = try { if (f.exists()) f.length() else 0L } catch (_: Exception) { 0L }

    /**
     * Upright and ~1600 px: applies the EXIF rotation / mirror into the pixels (BitmapFactory ignores EXIF, so a
     * sideways phone photo reached the model sideways) and shrinks 12–200 MP shots so low-end phones don't run
     * out of memory later. Rewrites [file] in place; leaves it untouched on any failure.
     */
    fun normalize(file: File, max: Int = CameraFlow.TARGET_LONG_EDGE): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val orientation = try { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { 1 }
        val deg = CameraFlow.exifDegrees(orientation); val mirror = CameraFlow.exifMirrored(orientation)
        if (bounds.outWidth <= 0) false
        else if (maxOf(bounds.outWidth, bounds.outHeight) <= max && deg == 0 && !mirror) true
        else {
            var sample = CameraFlow.sampleFor(bounds.outWidth, bounds.outHeight, max)
            var bmp: Bitmap? = null
            while (bmp == null && sample <= 64) {
                bmp = try { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) }
                      catch (_: OutOfMemoryError) { null }
                if (bmp == null) sample *= 2
            }
            val src = bmp ?: throw IllegalStateException("couldn't decode the photo")
            val (w, h) = CameraFlow.scaledSize(src.width, src.height, max)
            val m = Matrix()
            m.postScale(w.toFloat() / src.width, h.toFloat() / src.height)
            if (deg != 0) m.postRotate(deg.toFloat())
            if (mirror) m.postScale(-1f, 1f)
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (out !== src) out.recycle(); src.recycle()
            tmp.renameTo(file) || run { file.writeBytes(tmp.readBytes()); tmp.delete(); true }
        }
    } catch (e: Throwable) { log("normalize failed", e); false }

    /**
     * v0.30.x digital zoom on the saved photo: the same centred crop the preview showed ([CameraZoom.cropRect], by
     * [factor] past the lens's hardware max), decoded straight from the JPEG with BitmapRegionDecoder (the full frame is
     * never decoded), upscaled back to the frame's size (long edge ≤ [maxLong]) and lightly sharpened ([Sharpen]).
     * The EXIF orientation is carried over so [normalize] still turns it upright (a centred crop is the same whichever
     * way the phone was held). Runs on a background thread; rewrites [file] in place; leaves it untouched on failure.
     * Out of memory on a small phone: tries again at half the size without sharpening.
     */
    fun digitalZoom(file: File, factor: Float, maxLong: Int): Boolean {
        if (factor <= 1.01f) return true
        return try { cropScale(file, factor, maxLong, sharpen = true) }
        catch (_: OutOfMemoryError) {
            log("digital zoom: out of memory at $maxLong px, retrying smaller")
            try { cropScale(file, factor, maxLong / 2, sharpen = false) } catch (e: Throwable) { log("digital zoom failed", e); false }
        } catch (e: Throwable) { log("digital zoom failed", e); false }
    }

    private fun cropScale(file: File, factor: Float, maxLong: Int, sharpen: Boolean): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val w = bounds.outWidth; val h = bounds.outHeight
        if (w <= 0 || h <= 0) return false
        val orientation = try { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { 1 }
        val crop = CameraZoom.cropRect(w, h, factor)
        val (ow, oh) = CameraZoom.outputSize(w, h, maxLong)
        val sample = CameraZoom.cropSample(crop.width, crop.height, maxOf(ow, oh))
        val dec = if (Build.VERSION.SDK_INT >= 31) android.graphics.BitmapRegionDecoder.newInstance(file.path)
            else @Suppress("DEPRECATION") android.graphics.BitmapRegionDecoder.newInstance(file.path, false)
        val part = (try {
            dec?.decodeRegion(android.graphics.Rect(crop.left, crop.top, crop.left + crop.width, crop.top + crop.height),
                BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } finally { dec?.recycle() }) ?: return false
        val scaled = Bitmap.createScaledBitmap(part, ow, oh, true)
        if (scaled !== part) part.recycle()
        val amount = if (sharpen) CameraZoom.sharpenAmount(factor) else 0f
        val out = if (amount > 0f) {
            val px = IntArray(ow * oh)
            scaled.getPixels(px, 0, ow, 0, 0, ow, oh)
            val sharp = Sharpen.unsharp(px, ow, oh, amount)
            val b = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            b.setPixels(sharp, 0, ow, 0, 0, ow, oh)
            scaled.recycle(); b
        } else scaled
        val tmp = File(file.parentFile, file.name + ".zoom")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        out.recycle()
        if (orientation != ExifInterface.ORIENTATION_NORMAL) try {
            ExifInterface(tmp.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        } catch (e: Exception) { log("digital zoom: couldn't keep the orientation", e) }
        val ok = tmp.renameTo(file) || run { file.writeBytes(tmp.readBytes()); tmp.delete(); true }
        log("digital zoom ${"%.2f".format(factor)}x: ${w}x$h → crop ${crop.width}x${crop.height} (1/$sample) → ${ow}x$oh, sharpen ${"%.2f".format(amount)}")
        return ok
    }
}
