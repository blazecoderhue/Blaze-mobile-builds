package com.blaze.assistant.mediaapps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.33.0 Cover Art Forge: AI covers for songs and thumbnails for videos in the Music / Video library that have none
 * (your own beats, Gallery recordings, downloads whose art failed), or a fresh one for any track by name. Square for
 * audio, 16:9 for video; the picture is saved with the track and used in the player and the notification. Uses the same
 * image service as generate_image. Pure prompt building in [prompt] (CoverArtTest).
 */
object CoverArt {
    const val TOOL = "cover_art"

    private val genreHints = listOf(
        Regex("(?i)gospel|worship|praise") to "uplifting gospel album cover, warm golden light, sky",
        Regex("(?i)amapiano|log drum") to "amapiano album cover, night city, neon, bold shapes",
        Regex("(?i)zed|zambia|kalindula") to "Zambian music album cover, vibrant African patterns, copper tones",
        Regex("(?i)hip ?hop|rap|trap|drill") to "hip-hop album cover, gritty street photography style, dramatic shadows",
        Regex("(?i)love|heart|baby") to "romantic album cover, soft pastel gradient, intimate",
        Regex("(?i)beat|instrumental|lofi|lo-fi") to "lo-fi beat cover art, cosy illustrated room, grain",
        Regex("(?i)lecture|tutorial|how to|lesson") to "clean educational video thumbnail, bold title area, bright",
    )

    /** The image prompt for a track; [style] is the user's own wish ("anime", "minimal black and gold"). */
    fun prompt(title: String, artist: String, kind: String, style: String = ""): String {
        val t = title.replace(Regex("""(?i)\.(mp3|m4a|wav|ogg|opus|mp4|webm|mkv)$"""), "").replace(Regex("""[_]+"""), " ").replace(Regex("""\s*[\[(](official|lyrics?|audio|video|hd|4k)[^\])]*[\])]""", RegexOption.IGNORE_CASE), "").trim()
        val hint = genreHints.firstOrNull { it.first.containsMatchIn("$t $artist") }?.second
            ?: if (kind == "video") "eye-catching video thumbnail, cinematic, high contrast" else "striking album cover art, artistic, cohesive palette"
        return buildString {
            append(if (kind == "video") "YouTube-style thumbnail for a video titled \"$t\"" else "Album cover for the song \"$t\"")
            if (artist.isNotBlank() && !artist.equals("Blaze", true) && !artist.equals("unknown", true)) append(" by $artist")
            append(". ").append(style.ifBlank { hint })
            append(". No text, no letters, no watermark, no logos.")
        }
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Cover Art Forge: make AI cover art for songs / thumbnails for videos in Blaze's Music and Video library " +
            "(missing = every track without art, max 6; or one track by name), saved with the track.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("track", JSONObject().put("type", "string").put("description", "title to match, or \"missing\""))
            .put("style", JSONObject().put("type", "string").put("description", "optional look the user wants")))
            .put("required", JSONArray(listOf("track")))))

    suspend fun run(ctx: Context, a: JSONObject): String {
        MediaLib.ensure(ctx)
        val q = a.optString("track").trim()
        val all = MediaLib.songs.value + MediaLib.videos.value
        val targets = if (q.isBlank() || q.equals("missing", true)) all.filter { it.art.isBlank() }.take(6)
            else all.filter { it.title.contains(q, true) }.take(1)
        if (targets.isEmpty()) return if (q.equals("missing", true) || q.isBlank()) "Every track already has art." else "No track called \u201c$q\u201d in the library."
        val done = ArrayList<String>(); val failed = ArrayList<String>()
        for (t in targets) {
            val video = t.kind == "video"
            val r = com.blaze.assistant.media.Media.generate(prompt(t.title, t.artist, t.kind, a.optString("style")), if (video) 1280 else 1024, if (video) 720 else 1024)
            r.fold({ f ->
                val dest = File(File(ctx.filesDir, "mediaapps/art").apply { mkdirs() }, "forge_${t.key.hashCode().toUInt()}.jpg")
                try { f.copyTo(dest, overwrite = true) } catch (_: Exception) { }
                MediaLib.setArt(t.key, if (dest.exists()) dest.absolutePath else f.absolutePath); done += t.title
            }, { failed += t.title })
        }
        return (if (done.isNotEmpty()) "New art for: ${done.joinToString(", ")}." else "") +
            (if (failed.isNotEmpty()) " The image service failed for: ${failed.joinToString(", ")}; try again in a minute." else "")
    }
}
