package com.blaze.assistant.mediaapps

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.34.0 agent tool `video_ops`: Blaze Video from the chat. Plays a library video, lists series, turns subtitles on
 * in a language (or off), translates subtitles with AI, writes a recap of what's been said so far, and switches
 * autoplay / smart downloads / data saver.
 */
object VideoTools {
    const val TOOL = "video_ops"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Blaze Video (the in-app video player). action: play (query = a video title in the user's library), series (list the " +
            "series / episodes in the library), subtitles (language = a language name or code, or \"off\"; for the video playing now), " +
            "translate_subtitles (language: AI-translates the video's subtitles and turns them on), recap (a short recap of the video " +
            "playing now, from its subtitles, up to where the user is), settings (autoplay / smart_downloads / data_saver true|false).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("play", "series", "subtitles", "translate_subtitles", "recap", "settings"))))
            .put("query", JSONObject().put("type", "string"))
            .put("language", JSONObject().put("type", "string"))
            .put("autoplay", JSONObject().put("type", "boolean"))
            .put("smart_downloads", JSONObject().put("type", "boolean"))
            .put("data_saver", JSONObject().put("type", "boolean")))
            .put("required", JSONArray(listOf("action")))))

    fun describe(args: JSONObject): String = when (args.optString("action")) {
        "play" -> "Opening the video"; "recap" -> "Writing a recap"; "translate_subtitles" -> "Translating subtitles"
        "subtitles" -> "Setting subtitles"; "series" -> "Looking at your series"; else -> "Video settings"
    }

    /** Best library match for [q]: every word of it in the title, else the most words. */
    fun find(videos: List<Track>, q: String): Track? {
        val words = q.lowercase().split(Regex("\\W+")).filter { it.length > 1 }
        if (words.isEmpty()) return null
        return videos.map { v -> v to words.count { v.title.lowercase().contains(it) } }.filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<Track, Int>>({ it.second }, { -it.first.title.length }))?.first
    }

    suspend fun run(ctx: Context, args: JSONObject): String {
        withContext(Dispatchers.IO) { MediaLib.ensure(ctx) }
        val live = VideoActivity.live
        return when (args.optString("action")) {
            "play" -> {
                val v = find(MediaLib.videos.value, args.optString("query")) ?: return "No video like \u201c${args.optString("query")}\u201d in the library. " +
                    "Videos come from the Video Store (downloads) and Blaze's Gallery."
                VideoActivity.openAndPlay(ctx, v.key); "Playing \u201c${v.title}\u201d in Blaze Video."
            }
            "series" -> {
                val s = VideoSmart.series(MediaLib.videos.value)
                if (s.isEmpty()) "No series in the library (episodes are grouped from titles like \u201cShow S01E02\u201d or \u201cShow - Episode 3\u201d)."
                else s.joinToString("\n") { (n, eps) -> "$n: ${eps.size} episodes (${eps.first().title} \u2026 ${eps.last().title})" }
            }
            "subtitles" -> live?.subtitlesTo(args.optString("language").ifBlank { "off" }) ?: "Blaze Video isn't open. Play a video first."
            "translate_subtitles" -> live?.translate(args.optString("language")) ?: "Blaze Video isn't open. Play a video first."
            "recap" -> live?.recap() ?: "Blaze Video isn't open. Play a video first."
            "settings" -> {
                val out = ArrayList<String>()
                if (args.has("autoplay")) { MediaPrefs.setBool(ctx, MediaPrefs.AUTOPLAY, args.optBoolean("autoplay")); out += "autoplay ${onOff(args.optBoolean("autoplay"))}" }
                if (args.has("smart_downloads")) { MediaPrefs.setBool(ctx, MediaPrefs.SMART_DL, args.optBoolean("smart_downloads")); out += "smart downloads ${onOff(args.optBoolean("smart_downloads"))}" }
                if (args.has("data_saver")) { MediaPrefs.setBool(ctx, MediaPrefs.DATA_SAVER, args.optBoolean("data_saver")); out += "data saver ${onOff(args.optBoolean("data_saver"))}" }
                if (out.isEmpty()) "Autoplay ${onOff(MediaPrefs.autoplay(ctx))}, smart downloads ${onOff(MediaPrefs.smartDownloads(ctx))}, data saver ${onOff(MediaPrefs.dataSaver(ctx))}."
                else "Video: " + out.joinToString(", ") + "."
            }
            else -> "Unknown action."
        }
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"
}
