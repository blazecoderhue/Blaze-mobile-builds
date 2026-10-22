package com.blaze.assistant.media

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.blaze.assistant.media.GalleryModel.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.34.0 Gallery, the Android side: the clean-up scan (duplicates + blurry photos from 128 px thumbnails, cached
 * by file and date), the 30-day Trash (Blaze's own files move to filesDir/gallery_trash; phone photos use Android's
 * own trash on Android 11+, which also empties itself after 30 days), location stripping for shares, and saving an
 * edited copy.
 */
object GalleryClean {
    private val cache = HashMap<String, GalleryTools.Scan>()

    /** Scans the pictures (newest first, at most [max]) and reports progress. */
    suspend fun scan(list: List<Entry>, max: Int = 3000, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<GalleryTools.Scan> = withContext(Dispatchers.IO) {
        val pics = list.filter { it.kind == "image" }.sortedByDescending { it.time }.take(max)
        pics.mapIndexedNotNull { i, e ->
            if (i % 10 == 0) onProgress(i, pics.size)
            val key = e.id + "@" + e.time
            cache[key] ?: run {
                val b = GalleryRepo.thumb(e, 128) ?: return@mapIndexedNotNull null
                val w = b.width; val h = b.height
                val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
                val g = GalleryTools.grey(px)
                GalleryTools.Scan(e.id, GalleryTools.dHash(g, w, h), GalleryTools.sharpness(g, w, h), e.size, e.time).also { cache[key] = it }
            }
        }
    }

    // ---------------------------------------------------------------- trash

    private fun trashDir(ctx: Context) = File(ctx.filesDir, "gallery_trash").apply { mkdirs() }
    private fun index(ctx: Context) = File(trashDir(ctx), "index.json")

    fun trashed(ctx: Context): List<GalleryTools.Trashed> = runCatching { GalleryTools.trashParse(index(ctx).readText()) }.getOrDefault(emptyList())
        .filter { File(it.file).exists() }
    private fun saveIndex(ctx: Context, l: List<GalleryTools.Trashed>) { index(ctx).writeText(GalleryTools.trashJson(l)) }

    /**
     * Moves to the Trash. Blaze's files move at once; phone photos (Android 11+) come back as an [IntentSender] for
     * Android's own "Move to trash?" prompt. On Android 10 and older, phone photos are deleted as before.
     */
    suspend fun trash(ctx: Context, list: List<Entry>): Pair<Int, IntentSender?> = withContext(Dispatchers.IO) {
        var moved = 0
        val l = trashed(ctx).toMutableList()
        for (e in list.filter { it.isFile }) {
            val f = File(e.path); if (!f.exists()) continue
            val dest = File(trashDir(ctx), System.currentTimeMillis().toString(36) + "_" + f.name)
            if (f.renameTo(dest) || runCatching { f.copyTo(dest, true); f.delete() }.getOrDefault(false)) {
                File(f.path + ".json").takeIf { it.exists() }?.renameTo(File(dest.path + ".json"))
                l += GalleryTools.Trashed(dest.path, f.path, e.title, System.currentTimeMillis()); moved++
            }
        }
        saveIndex(ctx, l)
        Media.refresh()
        val phone = list.filter { !it.isFile }.map { Uri.parse(it.id) }
        val sender = if (phone.isNotEmpty() && Build.VERSION.SDK_INT >= 30)
            runCatching { MediaStore.createTrashRequest(ctx.contentResolver, phone, true).intentSender }.getOrNull() else null
        if (phone.isNotEmpty() && Build.VERSION.SDK_INT < 30) GalleryRepo.delete(list.filter { !it.isFile })
        moved to sender
    }

    /** Puts a Trash item back where it was. */
    suspend fun restore(ctx: Context, t: GalleryTools.Trashed): Boolean = withContext(Dispatchers.IO) {
        val src = File(t.file); val dest = File(t.original)
        dest.parentFile?.mkdirs()
        val ok = src.renameTo(dest) || runCatching { src.copyTo(dest, true); src.delete() }.getOrDefault(false)
        if (ok) { File(src.path + ".json").takeIf { it.exists() }?.renameTo(File(dest.path + ".json")); saveIndex(ctx, trashed(ctx).filter { it.file != t.file }); Media.refresh() }
        ok
    }

    fun deleteForever(ctx: Context, t: GalleryTools.Trashed) {
        File(t.file).delete(); File(t.file + ".json").delete()
        saveIndex(ctx, trashed(ctx).filter { it.file != t.file })
    }

    /** Empties what's been in the Trash for 30 days (called when the Gallery opens). */
    fun purge(ctx: Context) {
        val all = runCatching { GalleryTools.trashParse(index(ctx).readText()) }.getOrDefault(emptyList())
        val old = GalleryTools.expired(all, System.currentTimeMillis())
        if (old.isEmpty()) return
        old.forEach { File(it.file).delete(); File(it.file + ".json").delete() }
        saveIndex(ctx, all - old.toSet())
    }

    /** Phone photos in Android's trash (Android 11+), to show next to Blaze's own. */
    fun phoneTrash(ctx: Context): List<Uri> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val out = ArrayList<Uri>()
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        runCatching {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                while (c.moveToNext()) out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
            }
        }
        return out
    }

    fun restorePhoneTrash(ctx: Context, uris: List<Uri>): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty()) runCatching { MediaStore.createTrashRequest(ctx.contentResolver, uris, false).intentSender }.getOrNull() else null

    // ---------------------------------------------------------------- strip location on share

    /**
     * Copies of the JPEG / PNG / WebP pictures with every GPS tag removed (in the cache's share/ folder), for sharing.
     * Other items pass through unchanged. Returns the entries to share (copies are Blaze files).
     */
    fun withoutLocation(ctx: Context, list: List<Entry>): List<Entry> {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() } }
        return list.map { e ->
            if (e.kind != "image" || !e.name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }) return@map e
            try {
                val out = File(dir, e.name)
                (if (e.isFile) File(e.path).inputStream() else ctx.contentResolver.openInputStream(Uri.parse(e.id)))?.use { inp -> out.outputStream().use { inp.copyTo(it) } }
                val ex = android.media.ExifInterface(out.path)
                listOf(android.media.ExifInterface.TAG_GPS_LATITUDE, android.media.ExifInterface.TAG_GPS_LATITUDE_REF, android.media.ExifInterface.TAG_GPS_LONGITUDE,
                    android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, android.media.ExifInterface.TAG_GPS_ALTITUDE, android.media.ExifInterface.TAG_GPS_ALTITUDE_REF,
                    android.media.ExifInterface.TAG_GPS_TIMESTAMP, android.media.ExifInterface.TAG_GPS_DATESTAMP, android.media.ExifInterface.TAG_GPS_PROCESSING_METHOD,
                    "GPSSpeed", "GPSSpeedRef", "GPSImgDirection", "GPSImgDirectionRef", "GPSDestLatitude", "GPSDestLongitude", "GPSAreaInformation")
                    .forEach { ex.setAttribute(it, null) }
                ex.saveAttributes()
                e.copy(id = "file:" + out.path)
            } catch (_: Exception) { e }
        }
    }

    // ---------------------------------------------------------------- editor

    /** Decodes a picture for editing, at most [maxPx] on its long side. */
    suspend fun load(ctx: Context, e: Entry, maxPx: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            fun open() = if (e.isFile) File(e.path).inputStream() else ctx.contentResolver.openInputStream(Uri.parse(e.id))
            open()?.use { BitmapFactory.decodeStream(it, null, opts) }
            var s = 1; while (maxOf(opts.outWidth, opts.outHeight) / (s * 2) >= maxPx) s *= 2
            open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
        } catch (_: Throwable) { null }
    }

    /** Rotation (degrees, multiple of 90), crop aspect (0 = keep) and colour → a new bitmap. */
    fun render(src: Bitmap, rotate: Int, aspect: Float, brightness: Float, contrast: Float, saturation: Float): Bitmap {
        val m = Matrix().apply { postRotate(rotate.toFloat()) }
        val rot = if (rotate % 360 != 0) Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) else src
        val r = GalleryTools.centerCrop(rot.width, rot.height, aspect)
        val crop = if (aspect > 0f) Bitmap.createBitmap(rot, r[0], r[1], r[2], r[3]) else rot
        val out = Bitmap.createBitmap(crop.width, crop.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(crop, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix(GalleryTools.colorMatrix(brightness, contrast, saturation))) })
        return out
    }

    /** Saves an edited copy into Blaze's Gallery (the original stays). */
    suspend fun saveCopy(ctx: Context, b: Bitmap, title: String): File = withContext(Dispatchers.IO) {
        val f = File(ctx.cacheDir, "edit_" + System.currentTimeMillis() + ".jpg")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val saved = Media.add(f, "$title (edited)")
        f.delete()
        saved
    }
}
