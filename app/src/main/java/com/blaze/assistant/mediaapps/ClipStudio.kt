package com.blaze.assistant.mediaapps

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * v0.33.0 Clip Studio: pick videos from the Library / Gallery and Blaze cuts a short vertical reel (default 30 s, 9:16
 * 720x1280) for WhatsApp Status or TikTok: the best-length slice of each clip in order, a caption on each (the duo
 * writes them), and an optional music bed from the Music library, mixed under the clips' own sound. Made on the phone
 * with Media3 Transformer; the reel lands in the Video library. Pure slicing in [plan] (ClipStudioTest).
 */
@UnstableApi
object ClipStudio {
    const val TOOL = "clip_studio"

    data class Slice(val index: Int, val startMs: Long, val endMs: Long)

    /** Share [targetMs] across clips (by length, at least 1.5 s each, never past a clip's end), starting a little in to skip shaky starts. */
    fun plan(durationsMs: List<Long>, targetMs: Long): List<Slice> {
        val usable = durationsMs.mapIndexed { i, d -> i to d }.filter { it.second >= 1000 }
        if (usable.isEmpty()) return emptyList()
        val total = usable.sumOf { it.second }
        if (total <= targetMs) return usable.map { (i, d) -> Slice(i, 0, d) }
        val each = usable.map { (i, d) -> i to maxOf(1500L, targetMs * d / total).coerceAtMost(d) }
        val scale = targetMs.toDouble() / each.sumOf { it.second }
        return each.map { (i, len0) ->
            val d = durationsMs[i]; val len = (len0 * minOf(1.0, scale)).toLong().coerceIn(minOf(1500L, d), d)
            val start = ((d - len) * 0.3).toLong().coerceAtLeast(0)
            Slice(i, start, start + len)
        }
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Clip Studio: cut a short vertical reel (9:16, default 30 s) from videos in the Video library / Gallery, with a caption per clip " +
            "and optional background music from the Music library. Pick clips by title words or 'latest N'. Write short punchy captions yourself.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("clips", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "video titles to match, in order; or [\"latest 4\"]"))
            .put("captions", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            .put("music", JSONObject().put("type", "string").put("description", "song title from the Music library, optional"))
            .put("seconds", JSONObject().put("type", "integer").put("description", "reel length, 10-90, default 30"))
            .put("title", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("clips")))))

    private fun durationMs(ctx: Context, uri: String): Long {
        val r = android.media.MediaMetadataRetriever()
        return try { r.setDataSource(ctx, Uri.parse(uri)); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
        catch (_: Exception) { 0L } finally { try { r.release() } catch (_: Throwable) { } }
    }

    private fun caption(text: String): OverlayEffect {
        val s = SpannableString(" ${text.take(60)} ")
        s.setSpan(ForegroundColorSpan(Color.WHITE), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(AbsoluteSizeSpan(48), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val settings = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.7f).build()
        return OverlayEffect(ImmutableList.of(TextOverlay.createStaticTextOverlay(s, settings)))
    }

    suspend fun run(ctx: Context, a: JSONObject): String {
        MediaLib.ensure(ctx)
        val vids = MediaLib.videos.value
        if (vids.isEmpty()) return "There are no videos in the library yet."
        val want = (0 until (a.optJSONArray("clips")?.length() ?: 0)).map { a.getJSONArray("clips").getString(it).trim() }
        val picked = if (want.size == 1 && Regex("""(?i)^latest\s*(\d+)?$""").matches(want[0]))
            vids.take(Regex("""\d+""").find(want[0])?.value?.toIntOrNull()?.coerceIn(1, 12) ?: 4).reversed()
            else want.mapNotNull { w -> vids.firstOrNull { it.title.contains(w, true) } }.distinctBy { it.key }
        if (picked.isEmpty()) return "None of those videos are in the library. Titles: " + vids.take(10).joinToString(", ") { it.title }
        val target = a.optInt("seconds", 30).coerceIn(10, 90) * 1000L
        val durs = picked.map { durationMs(ctx, it.uri) }
        val slices = plan(durs, target)
        if (slices.isEmpty()) return "Those clips are too short (under a second)."
        val caps = (0 until (a.optJSONArray("captions")?.length() ?: 0)).map { a.getJSONArray("captions").getString(it) }
        val items = slices.mapIndexed { k, s ->
            val mi = MediaItem.Builder().setUri(picked[s.index].uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.startMs).setEndPositionMs(s.endMs).build()).build()
            val fx = ArrayList<androidx.media3.common.Effect>()
            fx += Presentation.createForWidthAndHeight(720, 1280, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            caps.getOrNull(k)?.takeIf { it.isNotBlank() }?.let { fx += caption(it) }
            EditedMediaItem.Builder(mi).setEffects(Effects(ImmutableList.of(), ImmutableList.copyOf(fx))).build()
        }
        val seqs = ArrayList<EditedMediaItemSequence>(); seqs += EditedMediaItemSequence(items)
        val song = a.optString("music").takeIf { it.isNotBlank() }?.let { m -> MediaLib.songs.value.firstOrNull { it.title.contains(m, true) } }
        if (song != null) seqs += EditedMediaItemSequence(ImmutableList.of(EditedMediaItem.Builder(MediaItem.fromUri(song.uri)).setRemoveVideo(true).build()), true)
        val comp = Composition.Builder(seqs).build()
        val title = a.optString("title").ifBlank { "Reel " + java.text.SimpleDateFormat("d MMM HH.mm", java.util.Locale.UK).format(java.util.Date()) }
        val dir = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: ctx.filesDir, "ClipStudio").apply { mkdirs() }
        val out = File(dir, title.replace(Regex("[^A-Za-z0-9 ._-]"), "_") + ".mp4")
        val err = suspendCancellableCoroutine<String?> { cont ->
            Handler(Looper.getMainLooper()).post {
                try {
                    val t = Transformer.Builder(ctx).addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(null) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { if (cont.isActive) cont.resume(exportException.message ?: "export failed") }
                    }).build()
                    t.start(comp, out.absolutePath)
                    cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { try { t.cancel() } catch (_: Throwable) { } } }
                } catch (e: Throwable) { if (cont.isActive) cont.resume(e.message ?: "couldn't start") }
            }
        }
        if (err != null) return "Couldn't make the reel: $err."
        val len = slices.sumOf { it.endMs - it.startMs }
        try { MediaLib.addDownload(Track(Uri.fromFile(out).toString(), Uri.fromFile(out).toString(), title, "Clip Studio", "Clip Studio", len, "", "download",
            System.currentTimeMillis(), "", "video", "Yours", "clipstudio")) } catch (_: Throwable) { }
        return "Reel made: \u201c$title\u201d, ${len / 1000}s, ${slices.size} clips" + (if (song != null) " with ${song.title} underneath" else "") +
            ", 9:16. It's in Videos (Downloads). Offer to share it to WhatsApp Status or TikTok (share_to_app) or make a thumbnail (cover_art)."
    }
}
