package com.blaze.assistant.mediaapps

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.34.0 agent tool `music_ops`: Blaze Music's smart side from the chat. Blaze DJ, daily mixes and smart playlists,
 * Wrapped, the equalizer, crossfade, data saver, playlist import (YouTube / Spotify links) and the tag fixer.
 */
object MusicTools {
    const val TOOL = "music_ops"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Blaze Music (the in-app music player; use this, never the terminal or screen control, for Blaze Music). " +
            "action: play | pause | resume | next | previous | stop | now (transport: safe, runs at once; play with nothing queued returns a question to ask the user), dj (on = true|false: Blaze DJ keeps playing songs the user loves), " +
            "mixes (list daily mixes and smart playlists), play_list (name = a daily mix or smart playlist name like \"Daily Mix 1\" / \"Most played\" / " +
            "\"On repeat\", shuffle), wrapped (period = month|year|all: listening stats), eq (preset = " + MusicSmart.presets.keys.joinToString(" | ") + "), " +
            "crossfade (seconds = 0|3|6|10), data_saver (on), import (url = a YouTube or Spotify playlist / album link: downloads every song and builds " +
            "the playlist), fix_tags (cleans titles / artists of downloaded songs and fetches real covers).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(MusicCtl.ACTIONS + listOf("dj", "mixes", "play_list", "wrapped", "eq", "crossfade", "data_saver", "import", "fix_tags"))))
            .put("on", JSONObject().put("type", "boolean"))
            .put("name", JSONObject().put("type", "string"))
            .put("shuffle", JSONObject().put("type", "boolean"))
            .put("period", JSONObject().put("type", "string"))
            .put("preset", JSONObject().put("type", "string"))
            .put("seconds", JSONObject().put("type", "integer"))
            .put("url", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action")))))

    fun describe(args: JSONObject): String = when (args.optString("action")) {
        "import" -> "Importing the playlist"; "fix_tags" -> "Fixing song tags and covers"; "wrapped" -> "Working out your Wrapped"
        "dj" -> "Blaze DJ"; "eq" -> "Setting the equalizer"; else -> "Blaze Music"
    }

    private fun lists(): List<MusicSmart.SmartList> {
        val now = System.currentTimeMillis()
        val songs = MediaLib.songs.value
        return MusicSmart.dailyMixes(songs, MediaLib.plays.value, now / 86_400_000L) + MusicSmart.smartLists(songs, MediaLib.plays.value, MediaLib.liked.value, now)
    }

    private suspend fun play(ctx: Context, keys: List<String>, shuffle: Boolean): Boolean = withContext(Dispatchers.Main) {
        val svc = MusicService.current
        if (svc != null) svc.playKeys(keys, shuffle) > 0
        else { MusicService.pendingPlay = keys to shuffle; MusicActivity.open(ctx, "player"); true }
    }

    /** v0.45.6: the player's state for [MusicCtl]. Main thread for the service fields. */
    suspend fun state(ctx: Context): MusicCtl.State = withContext(Dispatchers.Main) {
        val svc = MusicService.current
        val saved = try { withContext(Dispatchers.IO) { MediaLib.loadQueue()?.first?.size ?: 0 } } catch (_: Exception) { 0 }
        MusicCtl.State(service = svc != null, playing = svc?.isPlayingNow() == true, queue = svc?.queueSize() ?: 0, savedQueue = saved,
            songs = MediaLib.songs.value.size, lastList = MediaPrefs.str(ctx, LAST_LIST), title = svc?.nowTitle().orEmpty())
    }

    const val LAST_LIST = "music_last_list"

    /** v0.45.6: play / pause / resume / next / previous / stop / now, decided by [MusicCtl]. */
    suspend fun transport(ctx: Context, action: String): String {
        withContext(Dispatchers.IO) { MediaLib.ensure(ctx) }
        return when (val d = MusicCtl.decide(action, state(ctx))) {
            is MusicCtl.Decision.Player -> { withContext(Dispatchers.Main) { MusicService.current?.transport(d.op) }; d.say }
            is MusicCtl.Decision.ResumeSaved -> { withContext(Dispatchers.Main) { MusicService.pendingResume = true; MusicActivity.open(ctx, "player") }; d.say }
            is MusicCtl.Decision.Say -> d.say
        }
    }

    suspend fun run(ctx: Context, args: JSONObject): String {
        if (MusicCtl.norm(args.optString("action")) in MusicCtl.ACTIONS) return transport(ctx, args.optString("action"))
        withContext(Dispatchers.IO) { MediaLib.ensure(ctx) }
        return when (args.optString("action")) {
            "dj" -> {
                val on = !args.has("on") || args.optBoolean("on")
                if (!on) { MediaPrefs.setBool(ctx, MediaPrefs.DJ, false); return "Blaze DJ is off." }
                if (MediaLib.songs.value.isEmpty()) return "There are no songs in Blaze Music yet. Download some (media_download) or import a playlist first."
                withContext(Dispatchers.Main) {
                    val svc = MusicService.current
                    if (svc != null) svc.startDj(true) else {
                        MediaPrefs.setBool(ctx, MediaPrefs.DJ, true)
                        MusicService.pendingPlay = MusicSmart.djPicks(MediaLib.songs.value, MediaLib.plays.value, MediaLib.liked.value.toSet(), MediaLib.recent.value, 10, System.nanoTime()).map { it.key } to false
                        MusicActivity.open(ctx, "player")
                    }
                }
                "Blaze DJ is on: it plays songs the user loves and keeps adding more when the queue runs out."
            }
            "mixes" -> lists().joinToString("\n") { "${it.name}: ${it.about} (${it.keys.size} songs)" }.ifBlank { "No mixes yet: they build from songs played in Blaze Music." }
            "play_list" -> {
                val n = args.optString("name").trim()
                val l = lists().firstOrNull { it.name.equals(n, true) } ?: lists().firstOrNull { it.name.contains(n, true) || n.contains(it.name, true) }
                    ?: return "No mix or smart playlist called \u201c$n\u201d. Have: " + lists().joinToString(", ") { it.name }
                MediaPrefs.setStr(ctx, LAST_LIST, l.name)
                if (play(ctx, l.keys, args.optBoolean("shuffle"))) "Playing ${l.name} (${l.keys.size} songs)." else "Couldn't start ${l.name}."
            }
            "wrapped" -> MusicExtras.wrappedText(MusicExtras.wrapped(args.optString("period").ifBlank { "year" }))
            "eq" -> {
                val p = MusicSmart.presets.keys.firstOrNull { it.equals(args.optString("preset").trim(), true) }
                    ?: return "Presets: " + MusicSmart.presets.keys.joinToString(", ")
                MediaPrefs.setStr(ctx, MediaPrefs.EQ, p)
                withContext(Dispatchers.Main) { MusicService.current?.applyEq(p) }
                if (p == "Flat") "Equalizer off (flat)." else "Equalizer set to $p."
            }
            "crossfade" -> {
                val sec = args.optInt("seconds", 0).coerceIn(0, 12)
                MediaPrefs.setInt(ctx, MediaPrefs.CROSSFADE, sec)
                if (sec == 0) "No fade between songs." else "Songs now fade out and in over $sec s."
            }
            "data_saver" -> {
                val on = !args.has("on") || args.optBoolean("on")
                MediaPrefs.setBool(ctx, MediaPrefs.DATA_SAVER, on)
                if (on) "Data saver on: on mobile data, streams use the smallest quality." else "Data saver off."
            }
            "import" -> {
                val url = args.optString("url").trim()
                if (url.isBlank()) "Give the playlist link (YouTube or Spotify)." else MusicExtras.importPlaylist(ctx, url)
            }
            "fix_tags" -> {
                val r = MusicExtras.fixTags(ctx)
                "Checked ${r.checked} downloaded songs: ${r.renamed} renamed, ${r.covers} got real covers."
            }
            else -> "Unknown action."
        }
    }
}
