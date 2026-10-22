package com.blaze.assistant.mediaapps

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.blaze.assistant.mediaapps.store.LinkYt
import com.blaze.assistant.mediaapps.store.StoreSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * v0.34.0 subtitles, the Android side: fetches a YouTube caption track (json3, then vtt, then the plain XML),
 * reads a .srt / .vtt the user picks, and keeps every loaded track per video as clean WebVTT in
 * filesDir/mediaapps/subs/<video>/ with an index, so a video gets its subtitles back next time (offline too).
 * The player side-loads these files as subtitle tracks ([config]).
 */
object SubsLoader {
    private const val PREFS = "blaze_video"
    private const val KEY_PREF = "subs_pref"      // "" never chosen, "off", or a language code

    fun pref(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PREF, "").orEmpty()
    fun setPref(ctx: Context, v: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PREF, v).apply()

    private fun dir(ctx: Context, videoKey: String) = File(ctx.filesDir, "mediaapps/subs/" + Subs.folderFor(videoKey))

    /** Tracks saved for this video (urls point at local .vtt files). */
    fun saved(ctx: Context, videoKey: String): List<StoreSubtitle> {
        val idx = File(dir(ctx, videoKey), "index.json")
        if (!idx.isFile) return emptyList()
        return Subs.indexParse(idx.readText()).filter { File(Uri.parse(it.url).path ?: "").isFile }
    }

    /** Writes [cues] as this video's track [t] and returns it pointing at the local file. */
    fun save(ctx: Context, videoKey: String, t: StoreSubtitle, cues: List<Cue>): StoreSubtitle {
        val d = dir(ctx, videoKey).apply { mkdirs() }
        val f = File(d, t.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".vtt")
        f.writeText(Subs.toVtt(cues))
        val local = t.copy(url = Uri.fromFile(f).toString())
        val list = saved(ctx, videoKey).filter { it.id != t.id } + local
        File(d, "index.json").writeText(Subs.indexJson(list))
        return local
    }

    /** The cues of a saved track (for translation and recap). */
    fun cues(t: StoreSubtitle): List<Cue> = try { Subs.parse(File(Uri.parse(t.url).path ?: "").readText()) } catch (_: Exception) { emptyList() }

    /** Downloads one YouTube caption track; empty when YouTube gave nothing usable. */
    suspend fun fetchYouTube(t: StoreSubtitle): List<Cue> = withContext(Dispatchers.IO) {
        for (fmt in listOf("json3", "vtt", "")) {
            val u = if (fmt.isEmpty()) t.url else Subs.withFormat(t.url, fmt)
            val cues = try { Subs.parse(get(u)) } catch (_: Exception) { emptyList() }
            if (cues.isNotEmpty()) return@withContext cues
        }
        emptyList()
    }

    private fun get(url: String): String {
        val h = URL(url).openConnection() as HttpURLConnection
        try {
            h.connectTimeout = 10_000; h.readTimeout = 15_000
            h.setRequestProperty("User-Agent", LinkYt.web.ua)
            if (h.responseCode !in 200..299) return ""
            return h.inputStream.use { it.readBytes() }.let { decode(it) }
        } finally { h.disconnect() }
    }

    /** UTF-8 when it is, else Windows-1252 (old .srt files). */
    fun decode(b: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (_: CharacterCodingException) { String(b, charset("windows-1252")) }

    /** A .srt / .vtt the user picked → (display name, cues). Throws with a plain message when it isn't one. */
    suspend fun readFile(ctx: Context, uri: Uri): Pair<String, List<Cue>> = withContext(Dispatchers.IO) {
        var name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (c.getLong(1) > 8_000_000) throw IllegalArgumentException("That file is too big to be subtitles.")
                }
            }
        }.exceptionOrNull()?.let { if (it is IllegalArgumentException) throw it }
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Couldn't open that file.")
        val cues = Subs.parse(decode(bytes))
        if (cues.isEmpty()) throw IllegalArgumentException("No subtitles found in \u201c$name\u201d. Pick a .srt or .vtt file.")
        name to cues
    }

    /** The player's side-loaded subtitle for a saved track. */
    fun config(t: StoreSubtitle, default: Boolean): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(t.url))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(t.lang.ifBlank { null })
            .setLabel(Subs.label(t))
            .setId(t.id)
            .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
}
