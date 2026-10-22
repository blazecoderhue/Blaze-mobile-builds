package com.blaze.assistant.mediaapps

import android.content.Context
import com.blaze.assistant.mediaapps.store.LinkYt
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.YouTubeStoreSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

/**
 * v0.34.0 Blaze Music, the Android side of the extras: the tag fixer (clean titles / artists, real covers from the
 * iTunes Search API), playlist import (a YouTube or Spotify playlist link → a Blaze playlist that fills as the songs
 * download) and Wrapped for a period.
 */
object MusicExtras {
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    private fun get(url: String): String {
        val h = URL(url).openConnection() as HttpURLConnection
        try {
            h.connectTimeout = 12_000; h.readTimeout = 20_000; h.setRequestProperty("User-Agent", UA)
            if (h.responseCode !in 200..299) throw IllegalStateException("answered ${h.responseCode}")
            return h.inputStream.bufferedReader().use { it.readText() }
        } finally { h.disconnect() }
    }

    data class FixResult(val renamed: Int, val covers: Int, val checked: Int)

    /**
     * Tag fixer over the songs Blaze downloaded: "Artist - Title (Official Video)" style titles are split and cleaned,
     * and songs whose cover is only a video frame get the album cover from the iTunes Search API (600 px).
     */
    suspend fun fixTags(ctx: Context, keys: List<String>? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }): FixResult = withContext(Dispatchers.IO) {
        MediaLib.ensure(ctx)
        val songs = MediaLib.songs.value.filter { it.source == "download" && (keys == null || it.key in keys) }
        var renamed = 0; var covers = 0
        songs.forEachIndexed { i, t ->
            onProgress(i + 1, songs.size)
            val (a, ti) = MusicSmart.fixTags(t.title, t.artist)
            if (a != t.artist || ti != t.title) { if (MediaLib.updateTags(t.key, ti, a)) renamed++ }
            val frame = t.art.isBlank() || t.art.contains("ytimg") || t.art.contains("_yt:") || t.art.contains("youtube_")
            if (frame) {
                val art = try {
                    MusicSmart.itunesArt(get("https://itunes.apple.com/search?entity=song&limit=1&term=" + URLEncoder.encode("$a $ti", "UTF-8")))
                } catch (_: Exception) { null }
                if (art != null) try {
                    val f = File(MediaFiles.artDir(ctx), "cover_" + Subs.folderFor(t.key) + ".jpg")
                    URL(art).openStream().use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    if (f.length() > 1000) { MediaLib.setArt(t.key, f.path); covers++ }
                } catch (_: Exception) { }
            }
        }
        FixResult(renamed, covers, songs.size)
    }

    /**
     * Playlist import: a YouTube playlist (all its videos, by id) or a Spotify playlist / album (each "Artist - Title"
     * found on YouTube, clear matches only). Every song is queued for download and a Blaze playlist is created that
     * fills as they land. Returns a sentence for the screen / the agent.
     */
    suspend fun importPlaylist(ctx: Context, link: String, max: Int = 100): String = withContext(Dispatchers.IO) {
        MediaLib.ensure(ctx)
        val ytList = MusicSmart.youtubePlaylistId(link)
        val spList = MusicSmart.spotifyPlaylistId(link)
        val spAlbum = MusicSmart.spotifyAlbumId(link)
        val picks = ArrayList<StoreItem>(); val skipped = ArrayList<String>()
        var name = ""
        when {
            ytList != null -> {
                var next = ""; var pages = 0
                do {
                    val (items, cont, title) = MusicSmart.ytPlaylist(LinkYt.playlist(ytList, next))
                    if (name.isBlank()) name = title
                    items.forEach { (id, t) -> if (picks.size < max) picks += StoreItem(YouTubeStoreSource.id, id, t, "") }
                    next = cont; pages++
                } while (next.isNotBlank() && picks.size < max && pages < 8)
            }
            spList != null || spAlbum != null -> {
                val (names, title) = MusicSmart.spotifyEmbed(get("https://open.spotify.com/embed/" + (if (spList != null) "playlist/$spList" else "album/$spAlbum")))
                name = title
                for (q in names.take(max)) {
                    val res = try { YouTubeStoreSource.search("audio", q, "").items.take(6) } catch (_: Exception) { emptyList() }
                    val ranked = MediaPick.rank(q, "audio", res)
                    val best = ranked.firstOrNull()
                    if (best != null && MediaPick.confident(ranked)) picks += best.first else skipped += q
                }
            }
            else -> return@withContext "That isn't a YouTube or Spotify playlist link."
        }
        if (picks.isEmpty()) return@withContext "Couldn't read any songs from that playlist" + (if (skipped.isNotEmpty()) " (no clear YouTube match for ${skipped.size})." else ". It may be private.")
        val plName = name.ifBlank { "Imported playlist" }
        val id = MediaLib.newPlaylistId()
        MediaLib.setPlaylists(PlaylistOps.create(MediaLib.playlists.value, plName, id))
        val have = MediaLib.songs.value.associateBy { it.videoId.removePrefix("yt:") }
        var queued = 0; var already = 0; val failed = ArrayList<String>()
        for (p in picks) {
            val t0 = have[p.id]
            if (t0 != null) {
                MediaLib.setPlaylists(MediaLib.playlists.value.map { if (it.id == id) it.copy(keys = it.keys + t0.key) else it }); already++
                continue
            }
            try {
                val d = YouTubeStoreSource.detail(p, "audio")
                val ch = MediaPick.choose("audio", YouTubeStoreSource.choices("audio", d), if (MediaPrefs.saving(ctx)) "small" else "high") ?: throw Exception("no stream")
                MediaLib.expectImport(p.id, id)
                Downloads.add(ctx, p.copy(title = p.title.ifBlank { d.title }, author = d.author, thumb = d.thumb), d, ch, "audio")
                queued++
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed += p.title.ifBlank { p.id } }
        }
        buildString {
            append("Imported \u201c$plName\u201d: $queued downloading")
            if (already > 0) append(", $already already in your library")
            append(". The playlist fills as they finish.")
            if (skipped.isNotEmpty()) append(" No clear match for ${skipped.size}: ${skipped.take(5).joinToString("; ")}.")
            if (failed.isNotEmpty()) append(" Couldn't get ${failed.size}.")
        }
    }

    /** Wrapped for "month" | "year" | "all" (Africa/whatever local time: the phone's zone). */
    fun wrapped(period: String): MusicSmart.Wrapped? {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        val (from, label) = when (period) {
            "month" -> { cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.timeInMillis to "This month" }
            "all" -> 0L to "All time"
            else -> { cal.set(Calendar.DAY_OF_YEAR, 1); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.timeInMillis to "${cal.get(Calendar.YEAR)} so far" }
        }
        return MusicSmart.wrapped(MediaLib.plays.value, MediaLib.songs.value, from, now + 1, label) { at ->
            Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY) }
    }

    fun wrappedText(w: MusicSmart.Wrapped?): String {
        if (w == null) return "Nothing played yet in this period. Wrapped counts songs you play in Blaze Music."
        return buildString {
            append("${w.label}: ${w.minutes} minutes, ${w.plays} plays, ${w.songs} songs, ${w.artists} artists.\n")
            if (w.topSongs.isNotEmpty()) append("Top songs: " + w.topSongs.joinToString("; ") { "${it.first.title} (${it.second})" } + "\n")
            if (w.topArtists.isNotEmpty()) append("Top artists: " + w.topArtists.joinToString("; ") { "${it.first} (${it.second} min)" } + "\n")
            append("You listen most around ${"%02d".format(w.busiestHour)}:00.")
        }
    }
}
