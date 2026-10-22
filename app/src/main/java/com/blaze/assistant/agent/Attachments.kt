package com.blaze.assistant.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Turns a picked photo, PDF or text file into content the model can read. */
object Attachments {
    private const val MAX_PDF_PAGES = 6

    /**
     * [mimeHint] is the type the sender declared (e.g. a clipboard item's description). Some
     * providers (Samsung clipboard, keyboards, screenshots) don't answer getType, so we fall back
     * to the hint, the file name, and finally a peek at the bytes.
     */
    suspend fun load(ctx: Context, uri: Uri, mimeHint: String = ""): Attachment? = withContext(Dispatchers.IO) {
        try {
            val cr = ctx.contentResolver
            val mime = (try { cr.getType(uri) } catch (_: Exception) { null }).orEmpty()
                .ifBlank { mimeHint }.ifBlank { guessMime(ctx, uri) }
            val name = (try { cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } } catch (_: Exception) { null }) ?: uri.lastPathSegment ?: "file"
            when {
                mime.startsWith("image/") -> {
                    val bmp = decodeScaled(ctx, uri, 1536) ?: return@withContext null
                    Attachment(name, listOf(image(bmp)), saveThumb(ctx, bmp, name))
                }
                mime == "application/pdf" -> {
                    val parts = mutableListOf<JSONObject>()
                    cr.openFileDescriptor(uri, "r")?.use { fd ->
                        PdfRenderer(fd).use { pdf ->
                            val n = minOf(pdf.pageCount, MAX_PDF_PAGES)
                            if (pdf.pageCount > n) parts += text("(PDF has ${pdf.pageCount} pages; showing the first $n.)")
                            for (i in 0 until n) pdf.openPage(i).use { page ->
                                val w = 1100; val h = (w.toFloat() * page.height / page.width).toInt()
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                parts += image(bmp, 75)
                            }
                        }
                    }
                    val first = try { cr.openFileDescriptor(uri, "r")?.use { fd -> PdfRenderer(fd).use { pdf -> pdf.openPage(0).use { pg ->
                        Bitmap.createBitmap(480, (480f * pg.height / pg.width).toInt(), Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(Color.WHITE); pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } } } } } catch (_: Exception) { null }
                    Attachment(name, parts, first?.let { saveThumb(ctx, it, name) })
                }
                mime.startsWith("text/") || name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv") || name.endsWith(".json") -> {
                    val body = cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty().take(60_000)
                    Attachment(name, listOf(text("File $name:\n$body")))
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** Type from the file name, or by sniffing the first bytes (images and PDFs). */
    private fun guessMime(ctx: Context, uri: Uri): String {
        val ext = uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        val head = try { ctx.contentResolver.openInputStream(uri)?.use { s -> ByteArray(8).also { s.read(it) } } } catch (_: Exception) { null } ?: return ""
        if (head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte()) return "application/pdf"
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } } catch (_: Exception) { }
        return if (o.outWidth > 0) (o.outMimeType ?: "image/*") else ""
    }

    /** A picture at [uri] as a data: URL the model can look at (for tools). */
    suspend fun imageUrl(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try { decodeScaled(ctx, uri, 1536)?.let { image(it).getJSONObject("image_url").getString("url") } } catch (_: Exception) { null }
    }

    private fun decodeScaled(ctx: Context, uri: Uri, max: Int): Bitmap? {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val s = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        // v0.28.x: BitmapFactory ignores EXIF, so a portrait photo from many phones (Samsung, Xiaomi…) reached the model sideways.
        val o2 = try { cr.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1 } catch (_: Exception) { 1 }
        val deg = com.blaze.assistant.device.CameraFlow.exifDegrees(o2); val mirror = com.blaze.assistant.device.CameraFlow.exifMirrored(o2)
        if (deg == 0 && !mirror) return scaled
        return try {
            val m = android.graphics.Matrix().apply { if (deg != 0) postRotate(deg.toFloat()); if (mirror) postScale(-1f, 1f) }
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true)
        } catch (_: Throwable) { scaled }
    }

    /** A small copy kept with the chat so your own photos show as pictures, not file names. */
    private fun saveThumb(ctx: Context, bmp: Bitmap, name: String): String? = try {
        val dir = java.io.File(ctx.filesDir, "attachments").apply { mkdirs() }
        val s = minOf(1f, 720f / maxOf(bmp.width, bmp.height))
        val b = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        val f = java.io.File(dir, "${System.currentTimeMillis()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)}.jpg")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        f.absolutePath
    } catch (_: Exception) { null }

    private fun image(bmp: Bitmap, q: Int = 82): JSONObject {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
        val url = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))
    }

    private fun text(t: String) = JSONObject().put("type", "text").put("text", t)
}
