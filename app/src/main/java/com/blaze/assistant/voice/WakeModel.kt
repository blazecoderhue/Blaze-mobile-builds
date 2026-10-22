package com.blaze.assistant.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** The small offline English model the wake word listens with (~40 MB, downloaded once). */
object WakeModel {
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    const val SIZE_MB = 40

    /** -1 = not downloading, otherwise 0..100. */
    val progress = MutableStateFlow(-1)

    fun dir(ctx: Context) = File(ctx.filesDir, "wake-model")
    fun ready(ctx: Context) = File(dir(ctx), "am/final.mdl").exists() || File(dir(ctx), "final.mdl").exists()

    suspend fun download(ctx: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            progress.value = 0
            val tmp = File(ctx.cacheDir, "wake.zip")
            val conn = URL(URL_ZIP).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000; conn.readTimeout = 30000
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (SIZE_MB * 1_000_000L)
            conn.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        progress.value = ((done * 90) / total).toInt().coerceAtMost(90)
                    }
                }
            }
            val target = dir(ctx); target.deleteRecursively(); target.mkdirs()
            ZipInputStream(tmp.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    // Drop the zip's top folder so files land directly in wake-model/
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val f = File(target, rel)
                    if (!f.canonicalPath.startsWith(target.canonicalPath)) continue
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                }
            }
            tmp.delete()
            progress.value = 100
            Unit
        }.also { if (it.isFailure) progress.value = -1 }
    }
}
