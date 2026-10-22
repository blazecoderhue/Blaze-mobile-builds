package com.blaze.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.45.3: loads the theme scene pictures (ThemeScenes) from assets, decoded once at about screen size (inSampleSize,
 * RGB_565: they are opaque photos) and kept in a small LRU, so a picture is never decoded twice and the 3D carousel's
 * neighbours are ready before they turn to the front ([preload]). Decoding is off the main thread.
 */
object ThemeSceneImages {
    // ~4 scenes at 1376x768 RGB_565 ≈ 2 MB each.
    private val cache = object : LruCache<String, ImageBitmap>(9 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
    }
    private val loading = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun cached(scene: String, reqW: Int, reqH: Int): ImageBitmap? = cache.get(key(scene, reqW, reqH))

    private fun key(scene: String, w: Int, h: Int): String = "$scene@" + ThemeScenes.sampleSize(1376, 768, w, h)

    /** Decodes [scene] for a [reqW]x[reqH] area (blocking: call off the main thread). Null if it isn't bundled. */
    fun load(ctx: Context, scene: String, reqW: Int, reqH: Int): ImageBitmap? {
        val k = key(scene, reqW, reqH)
        cache.get(k)?.let { return it }
        return try {
            val path = ThemeScenes.path(scene)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            val o = BitmapFactory.Options().apply {
                inSampleSize = ThemeScenes.sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bm = ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, o) } ?: return null
            bm.prepareToDraw()
            bm.asImageBitmap().also { cache.put(k, it) }
        } catch (_: Throwable) { null }
    }

    /** Starts decoding the [scenes] in the background (the carousel's neighbours). */
    suspend fun preload(ctx: Context, scenes: List<String>, reqW: Int, reqH: Int) = withContext(Dispatchers.IO) {
        for (s in scenes.distinct()) {
            if (cached(s, reqW, reqH) != null || loading.putIfAbsent(s, true) != null) continue
            try { load(ctx, s, reqW, reqH) } finally { loading.remove(s) }
        }
    }
}

/** The decoded scene for a [reqW]x[reqH] area, or null until it is ready (or when [scene] is null). */
@Composable
fun rememberScene(scene: String?, reqW: Int, reqH: Int): ImageBitmap? {
    if (scene == null || reqW <= 0 || reqH <= 0) return null
    val ctx = LocalContext.current
    var img by remember(scene, reqW, reqH) { mutableStateOf(ThemeSceneImages.cached(scene, reqW, reqH)) }
    LaunchedEffect(scene, reqW, reqH) {
        if (img == null) img = withContext(Dispatchers.IO) { ThemeSceneImages.load(ctx, scene, reqW, reqH) }
    }
    return img
}

/** Draws [img] centre-cropped to cover the whole draw area (ContentScale.Crop). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSceneCover(img: ImageBitmap, alpha: Float = 1f) {
    val r = ThemeScenes.cropRect(img.width, img.height, size.width, size.height)
    drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset(r[0], r[1]), srcSize = androidx.compose.ui.unit.IntSize(r[2] - r[0], r[3] - r[1]),
        dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)), alpha = alpha,
        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
}
