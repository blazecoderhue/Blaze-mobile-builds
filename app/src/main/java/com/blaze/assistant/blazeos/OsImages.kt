package com.blaze.assistant.blazeos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File

/**
 * v0.33.2 BlazeOS pictures: icons, the wallpaper photo and Photos thumbnails, always decoded off the main thread and
 * downscaled at decode time (inSampleSize, then an exact scale to the size asked), kept in one LRU sized by the boot
 * budget ([OsBudget.imageCacheBytes], 8 MB on a low-end phone). Pause clears it.
 */
object OsImages {
    private var cache = newCache(8L * 1024 * 1024)

    private fun newCache(bytes: Long) = object : LruCache<String, Bitmap>(bytes.coerceIn(1L * 1024 * 1024, 64L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Synchronized fun resize(bytes: Long) { cache.evictAll(); cache = newCache(bytes) }
    @Synchronized fun clear() = cache.evictAll()
    @Synchronized fun forget(path: String) { for (k in cache.snapshot().keys) if (k.startsWith("$path@")) cache.remove(k) }
    @Synchronized private fun hit(k: String): Bitmap? = cache.get(k)
    @Synchronized private fun put(k: String, b: Bitmap) { cache.put(k, b) }

    /** The decode sample for [maxPx] (pure maths in OsIcons.sampleSize, unit tested). */
    fun sample(w: Int, h: Int, maxPx: Int): Int = OsIcons.sampleSize(w, h, maxPx)

    /** A picture file decoded to at most [maxPx] on its longer side. Call from Dispatchers.IO. */
    fun load(path: String, maxPx: Int): Bitmap? {
        val key = "$path@$maxPx"
        hit(key)?.let { return it }
        val f = File(path)
        if (!f.isFile) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight, maxPx); inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val b = BitmapFactory.decodeFile(path, opts) ?: return null
            val big = maxOf(b.width, b.height)
            val out = if (big > maxPx) Bitmap.createScaledBitmap(b, b.width * maxPx / big, b.height * maxPx / big, true).also { if (it != b) b.recycle() } else b
            put(key, out)
            out
        } catch (_: Throwable) { null }
    }

    /** Copies a picked picture to [dest] as a JPEG at most [maxPx] on its longer side. */
    fun saveScaled(ctx: Context, uri: Uri, dest: File, maxPx: Int): Boolean = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth.coerceAtLeast(1), o.outHeight.coerceAtLeast(1), maxPx) }
        val b = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (b == null) false else {
            val big = maxOf(b.width, b.height)
            val out = if (big > maxPx) Bitmap.createScaledBitmap(b, b.width * maxPx / big, b.height * maxPx / big, true) else b
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            true
        }
    } catch (_: Throwable) { false }
}
