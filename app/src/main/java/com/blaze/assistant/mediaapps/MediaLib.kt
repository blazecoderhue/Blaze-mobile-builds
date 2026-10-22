package com.blaze.assistant.mediaapps

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.30.1: the Music and Video libraries: what Blaze downloaded (Music/Blaze/Music, Movies/Blaze/Videos) plus the
 * audio and video files already in Blaze's Gallery, and the user's playlists, recently played and video resume
 * positions. Kept as small JSON files in filesDir/mediaapps (survive updates; included in Blaze's own backup).
 */
object MediaLib {
    private var app: Context? = null
    private val lock = Any()

    private val _songs = MutableStateFlow<List<Track>>(emptyList())
    private val _videos = MutableStateFlow<List<Track>>(emptyList())
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    private val _recentVideos = MutableStateFlow<List<String>>(emptyList())
    val songs: StateFlow<List<Track>> = _songs
    val videos: StateFlow<List<Track>> = _videos
    val playlists: StateFlow<List<Playlist>> = _playlists
    val recent: StateFlow<List<String>> = _recent
    val recentVideos: StateFlow<List<String>> = _recentVideos
    private val _liked = MutableStateFlow<List<String>>(emptyList())
    /** Liked songs, newest first. */
    val liked: StateFlow<List<String>> = _liked

    private var downloads = listOf<Track>()   // what Blaze downloaded (both kinds)
    private val positions = HashMap<String, Long>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "mediaapps").apply { mkdirs() }
    private fun f(ctx: Context, n: String) = File(dir(ctx), n)

    fun ensure(ctx: Context) {
        synchronized(lock) {
            if (app != null) return
            app = ctx.applicationContext
            val c = app!!
            Taste.init(c)
            downloads = readTracks(f(c, "library.json"))
            _playlists.value = runCatching { val a = JSONArray(f(c, "playlists.json").readText()); (0 until a.length()).map { pl(a.getJSONObject(it)) } }.getOrDefault(emptyList())
            _recent.value = readStrings(f(c, "recent.json"))
            _recentVideos.value = readStrings(f(c, "recent-videos.json"))
            _liked.value = readStrings(f(c, "liked.json"))
            _plays.value = runCatching { MusicSmart.eventsParse(f(c, "plays.json").readText()) }.getOrDefault(emptyList())
            pendingImports.putAll(runCatching { val o = JSONObject(f(c, "imports.json").readText()); o.keys().asSequence().associateWith { o.optString(it) } }.getOrDefault(emptyMap()))
            runCatching { val o = JSONObject(f(c, "positions.json").readText()); o.keys().forEach { positions[it] = o.optLong(it) } }
        }
        refresh()
    }

    /** Re-reads the Gallery and drops downloads the user deleted elsewhere. Call off the main thread when possible. */
    fun refresh() {
        val c = app ?: return
        val kept = downloads.filter { MediaFiles.exists(c, it.uri) }
        if (kept.size != downloads.size) { downloads = kept; saveTracks() }
        val gallery = try {
            com.blaze.assistant.media.Media.init(c)
            com.blaze.assistant.media.Media.items.value.filter { it.kind == "audio" || it.kind == "video" }.map { m ->
                Track(Uri.fromFile(m.file).toString(), Uri.fromFile(m.file).toString(), m.title, "Blaze", "Gallery", 0, "", "gallery", m.time, "", m.kind)
            }
        } catch (_: Exception) { emptyList() }
        if (artOverride.isEmpty()) runCatching { val o = JSONObject(f(c, "art.json").readText()); o.keys().forEach { artOverride[it] = o.optString(it) } }
        val galleryArt = gallery.map { g -> artOverride[g.key]?.takeIf { File(it).exists() }?.let { g.copy(art = it) } ?: g }
        _songs.value = (kept.filter { it.kind == "audio" } + galleryArt.filter { it.kind == "audio" }).sortedByDescending { it.addedAt }
        _videos.value = (kept.filter { it.kind == "video" } + galleryArt.filter { it.kind == "video" }).sortedByDescending { it.addedAt }
    }

    // v0.33.0 Cover Art Forge.
    private var artOverride = HashMap<String, String>()
    fun setArt(key: String, path: String) {
        val c = app ?: return
        synchronized(lock) {
            if (downloads.any { it.key == key }) downloads = downloads.map { if (it.key == key) it.copy(art = path) else it }
            else { artOverride[key] = path; write(f(c, "art.json"), JSONObject(artOverride as Map<*, *>).toString()) }
        }
        saveTracks(); refresh()
    }

    fun find(key: String): Track? = _songs.value.firstOrNull { it.key == key } ?: _videos.value.firstOrNull { it.key == key }

    fun addDownload(t: Track) {
        synchronized(lock) { downloads = listOf(t) + downloads.filter { it.key != t.key } }
        saveTracks(); refresh()
        // v0.34.0 playlist import: a song that was queued by an import lands in its playlist.
        val vid = t.videoId.removePrefix("yt:")
        pendingImports.remove(vid)?.let { plId ->
            setPlaylists(_playlists.value.map { if (it.id == plId && t.key !in it.keys) it.copy(keys = it.keys + t.key, updated = System.currentTimeMillis()) else it })
            savePending()
        }
    }

    // ---- v0.34.0 play log (smart playlists, daily mixes, Wrapped, Blaze DJ) ----
    private val _plays = MutableStateFlow<List<PlayEvent>>(emptyList())
    val plays: StateFlow<List<PlayEvent>> = _plays
    fun logPlay(key: String, at: Long, ms: Long) {
        val c = app ?: return
        if (ms < 3_000 || key.startsWith("stream:")) return
        val l = (_plays.value + PlayEvent(key, at, ms)).let { if (it.size > 6000) it.drop(it.size - 6000) else it }
        _plays.value = l
        write(f(c, "plays.json"), MusicSmart.eventsJson(l))
    }

    // ---- v0.34.0 tag fixer ----
    fun updateTags(key: String, title: String, artist: String): Boolean {
        val c = app ?: return false
        var hit = false
        synchronized(lock) { downloads = downloads.map { if (it.key == key) { hit = true; it.copy(title = title, artist = artist) } else it } }
        if (hit) { saveTracks(); refresh() }
        return hit
    }

    // ---- v0.34.0 pending playlist imports: video id → playlist id ----
    private val pendingImports = HashMap<String, String>()
    fun expectImport(videoId: String, playlistId: String) { pendingImports[videoId.removePrefix("yt:")] = playlistId; savePending() }
    private fun savePending() { val c = app ?: return; write(f(c, "imports.json"), JSONObject(pendingImports as Map<*, *>).toString()) }

    /** Removes a downloaded file from the phone (gallery files are the Gallery's to delete). */
    fun deleteDownload(ctx: Context, key: String): Boolean {
        val t = downloads.firstOrNull { it.key == key } ?: return false
        MediaFiles.delete(ctx, t.uri)
        synchronized(lock) { downloads = downloads.filter { it.key != key } }
        setPlaylists(PlaylistOps.forget(_playlists.value, key))
        if (key in _liked.value) toggleLike(key)
        saveTracks(); refresh()
        return true
    }

    /**
     * Wave B item 20: deletes several downloads at once (multi-select). One library save, one playlist save and one
     * refresh for the lot, so deleting 200 songs doesn't rebuild the library 200 times. Returns how many were removed.
     */
    fun deleteDownloads(ctx: Context, keys: Collection<String>): Int {
        val want = keys.toHashSet()
        val gone = downloads.filter { it.key in want }
        if (gone.isEmpty()) return 0
        gone.forEach { MediaFiles.delete(ctx, it.uri) }
        val goneKeys = gone.map { it.key }.toHashSet()
        synchronized(lock) { downloads = downloads.filter { it.key !in goneKeys } }
        var pls = _playlists.value
        goneKeys.forEach { pls = PlaylistOps.forget(pls, it) }
        if (pls != _playlists.value) setPlaylists(pls)
        if (_liked.value.any { it in goneKeys }) {
            _liked.value = _liked.value.filter { it !in goneKeys }
            app?.let { write(f(it, "liked.json"), JSONArray(_liked.value).toString()) }
        }
        saveTracks(); refresh()
        return gone.size
    }

    // ---- playlists ----
    fun setPlaylists(l: List<Playlist>) {
        _playlists.value = l
        val c = app ?: return
        val a = JSONArray(); l.forEach { p -> a.put(JSONObject().put("id", p.id).put("name", p.name).put("keys", JSONArray(p.keys)).put("u", p.updated)) }
        write(f(c, "playlists.json"), a.toString())
    }
    fun newPlaylistId() = "pl" + System.currentTimeMillis().toString(36) + (100..999).random()

    // ---- liked ----
    fun isLiked(key: String) = key in _liked.value
    fun toggleLike(key: String) {
        val c = app ?: return
        _liked.value = if (key in _liked.value) _liked.value - key else listOf(key) + _liked.value
        write(f(c, "liked.json"), JSONArray(_liked.value).toString())
    }

    // ---- the last music queue (restored when the player starts again) ----
    fun saveQueue(tracks: List<Track>, index: Int, posMs: Long) {
        val c = app ?: return
        val a = JSONArray(); tracks.forEach { a.put(trackJson(it)) }
        write(f(c, "queue.json"), JSONObject().put("items", a).put("index", index).put("pos", posMs).toString())
    }
    fun loadQueue(): Triple<List<Track>, Int, Long>? {
        val c = app ?: return null
        return runCatching {
            val o = JSONObject(f(c, "queue.json").readText())
            val a = o.getJSONArray("items")
            val items = (0 until a.length()).map { trackFrom(a.getJSONObject(it)) }.filter { it.source != "stream" && MediaFiles.exists(c, it.uri) }
            if (items.isEmpty()) null else Triple(items, o.optInt("index").coerceIn(0, items.size - 1), o.optLong("pos"))
        }.getOrNull()
    }

    // ---- recents / positions ----
    fun played(t: Track) {
        val c = app ?: return
        Taste.note(c, t.kind, t.title, t.artist, Taste.PLAY)
        if (t.kind == "video") { _recentVideos.value = Recents.push(_recentVideos.value, t.key); write(f(c, "recent-videos.json"), JSONArray(_recentVideos.value).toString()) }
        else { _recent.value = Recents.push(_recent.value, t.key); write(f(c, "recent.json"), JSONArray(_recent.value).toString()) }
    }

    fun position(key: String): Long = synchronized(lock) { positions[key] ?: 0 }
    fun savePosition(key: String, posMs: Long, durMs: Long) {
        val c = app ?: return
        synchronized(lock) {
            if (ResumeLogic.keep(posMs, durMs)) positions[key] = posMs else positions.remove(key)
            if (positions.size > 300) positions.keys.take(positions.size - 300).forEach { positions.remove(it) }
            write(f(c, "positions.json"), JSONObject(positions as Map<*, *>).toString())
        }
    }

    // ---- JSON ----
    private fun write(file: File, text: String) = try { val tmp = File(file.path + ".tmp"); tmp.writeText(text); tmp.renameTo(file) } catch (_: Exception) { false }
    private fun readStrings(file: File): List<String> = runCatching { val a = JSONArray(file.readText()); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())
    private fun pl(o: JSONObject) = Playlist(o.optString("id"), o.optString("name"),
        o.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), o.optLong("u"))

    fun trackJson(t: Track): JSONObject = JSONObject().put("k", t.key).put("u", t.uri).put("t", t.title).put("a", t.artist).put("al", t.album)
        .put("d", t.durationMs).put("art", t.art).put("src", t.source).put("at", t.addedAt).put("vid", t.videoId).put("kind", t.kind).put("lic", t.licence).put("ss", t.storeSource)
    fun trackFrom(o: JSONObject) = Track(o.optString("k"), o.optString("u"), o.optString("t"), o.optString("a"), o.optString("al"), o.optLong("d"),
        o.optString("art"), o.optString("src"), o.optLong("at"), o.optString("vid"), o.optString("kind", "audio"), o.optString("lic"), o.optString("ss"))

    private fun readTracks(file: File): List<Track> = runCatching { val a = JSONArray(file.readText()); (0 until a.length()).map { trackFrom(a.getJSONObject(it)) } }.getOrDefault(emptyList())
    private fun saveTracks() {
        val c = app ?: return
        val a = JSONArray(); synchronized(lock) { downloads.forEach { a.put(trackJson(it)) } }
        write(f(c, "library.json"), a.toString())
    }
}
