package com.blaze.assistant.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * v0.29.3: small preview pictures for Home cards (news, videos). Downscaled to ~360 px wide on decode, kept in a 6 MB
 * memory cache and a 15 MB disk cache, https only, at most 3 downloads at once. Low-RAM phones still get them because
 * nothing bigger than a thumbnail is ever decoded.
 */
object HomeImages {
    private val mem = object : android.util.LruCache<String, ImageBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val gate = kotlinx.coroutines.sync.Semaphore(3)
    private val locks = HashMap<String, Mutex>()

    private fun key(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "home-img").apply { mkdirs() }

    /** Inexact-power-of-two sample size so the decoded width is about [target] px. */
    fun sample(width: Int, target: Int = 360): Int { var s = 1; while (width / (s * 2) >= target) s *= 2; return s }

    suspend fun load(ctx: Context, url: String): ImageBitmap? {
        if (!url.startsWith("https://")) return null
        mem.get(url)?.let { return it }
        val lock = synchronized(locks) { locks.getOrPut(url) { Mutex() } }
        return lock.withLock {
            mem.get(url) ?: withContext(Dispatchers.IO) {
                val f = File(dir(ctx), key(url))
                try {
                    if (!f.isFile || f.length() == 0L) gate.acquire().let {
                        try {
                            val c = URL(url).openConnection() as HttpURLConnection
                            c.connectTimeout = 8000; c.readTimeout = 10000; c.instanceFollowRedirects = true
                            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) Blaze")
                            if (c.responseCode !in 200..299) return@withContext null
                            val tmp = File(f.path + ".part")
                            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                            if (tmp.length() > 4_000_000) { tmp.delete(); return@withContext null }
                            tmp.renameTo(f)
                        } finally { gate.release() }
                    }
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, o)
                    if (o.outWidth <= 0) { f.delete(); return@withContext null }
                    val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth) }) ?: return@withContext null
                    trim(dir(ctx))
                    bmp.asImageBitmap().also { mem.put(url, it) }
                } catch (_: Throwable) { null }
            }
        }
    }

    private fun trim(d: File) {
        val files = d.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total < 15_000_000) return
        for (f in files.sortedBy { it.lastModified() }) { if (total < 10_000_000) break; total -= f.length(); f.delete() }
    }
}

/** The picture at [url], or null while it loads / when it can't. */
@Composable
fun rememberHomeImage(url: String): ImageBitmap? {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, url) { value = if (url.isBlank()) null else HomeImages.load(ctx, url) }
    return img
}
