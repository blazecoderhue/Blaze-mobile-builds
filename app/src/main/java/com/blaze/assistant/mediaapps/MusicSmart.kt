package com.blaze.assistant.mediaapps

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** One stretch of listening: [ms] of song [key] that started at [at]. */
data class PlayEvent(val key: String, val at: Long, val ms: Long)

/**
 * v0.34.0 Blaze Music, the pure rules (MusicSmartTest): smart playlists, daily mixes, Wrapped, Blaze DJ's picks,
 * equalizer presets on any phone's bands, the crossfade / sleep-fade volume curves, the tag fixer, cover lookups and
 * playlist import parsing (YouTube and Spotify).
 */
object MusicSmart {
    data class Stat(val plays: Int, val lastAt: Long, val totalMs: Long)

    /** A listen counts as a play from 30 s (or half a short song). */
    fun stats(events: List<PlayEvent>, minMs: Long = 30_000): Map<String, Stat> {
        val out = HashMap<String, Stat>()
        for (e in events) {
            val s = out[e.key] ?: Stat(0, 0, 0)
            out[e.key] = Stat(s.plays + if (e.ms >= minMs) 1 else 0, maxOf(s.lastAt, e.at), s.totalMs + e.ms)
        }
        return out
    }

    data class SmartList(val id: String, val name: String, val about: String, val keys: List<String>)

    private const val DAY = 86_400_000L

    /** The smart playlists, refreshed from the library and the play log every time they're shown. Empty ones are left out. */
    fun smartLists(songs: List<Track>, events: List<PlayEvent>, liked: List<String>, now: Long): List<SmartList> {
        val st = stats(events)
        val have = songs.map { it.key }.toSet()
        val recentEv = stats(events.filter { now - it.at <= 14 * DAY })
        val out = ArrayList<SmartList>()
        out += SmartList("smart:top", "Most played", "Your 50 most played songs",
            songs.filter { (st[it.key]?.plays ?: 0) >= 2 }.sortedByDescending { st[it.key]?.plays ?: 0 }.take(50).map { it.key })
        out += SmartList("smart:repeat", "On repeat", "What you've played most in the last two weeks",
            songs.filter { (recentEv[it.key]?.plays ?: 0) >= 2 }.sortedByDescending { recentEv[it.key]?.plays ?: 0 }.take(30).map { it.key })
        out += SmartList("smart:new", "Recently added", "Added in the last 30 days",
            songs.filter { it.addedAt > 0 && now - it.addedAt <= 30 * DAY }.sortedByDescending { it.addedAt }.map { it.key })
        out += SmartList("smart:forgotten", "Forgotten favourites", "Songs you loved but haven't played in 6 weeks",
            songs.filter { (st[it.key]?.plays ?: 0) >= 3 && now - (st[it.key]?.lastAt ?: now) >= 42 * DAY }.sortedByDescending { st[it.key]?.plays ?: 0 }.take(40).map { it.key })
        out += SmartList("smart:never", "Never played", "In your library, never listened to",
            songs.filter { st[it.key] == null }.sortedByDescending { it.addedAt }.take(100).map { it.key })
        out += SmartList("smart:liked", "Liked, newest first", "Every song you liked", liked.filter { it in have })
        return out.filter { it.keys.isNotEmpty() }
    }

    /**
     * Up to 3 daily mixes: each starts from one of your most-listened artists (their songs) and is filled with your other
     * most played songs, reshuffled every day ([day] = days since epoch).
     */
    fun dailyMixes(songs: List<Track>, events: List<PlayEvent>, day: Long, size: Int = 25): List<SmartList> {
        val st = stats(events)
        val byArtist = songs.filter { it.artist.isNotBlank() }.groupBy { it.artist.lowercase() }
        val topArtists = byArtist.entries.map { (a, l) -> a to l.sumOf { st[it.key]?.totalMs ?: 0 } }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(3).map { it.first }
        if (topArtists.isEmpty()) return emptyList()
        val popular = songs.sortedByDescending { st[it.key]?.totalMs ?: 0 }
        return topArtists.mapIndexed { i, a ->
            val rnd = Random(day * 31 + i)
            val core = byArtist[a].orEmpty().shuffled(rnd).take(size / 2)
            val fill = popular.filter { it.artist.lowercase() != a && it !in core }.take(size * 2).shuffled(rnd).take(size - core.size)
            val mix = (core + fill).shuffled(rnd)
            val name = byArtist[a]!!.first().artist
            SmartList("mix:$i", "Daily Mix ${i + 1}", "$name and more", mix.map { it.key })
        }.filter { it.keys.size >= 3 }
    }

    data class Wrapped(
        val minutes: Long, val plays: Int, val songs: Int, val artists: Int,
        val topSongs: List<Pair<Track, Int>>, val topArtists: List<Pair<String, Long>>, val busiestHour: Int, val label: String,
    )

    /** Your listening between [from] and [to]: minutes, top songs (by plays), top artists (by minutes), the busiest hour. */
    fun wrapped(events: List<PlayEvent>, songs: List<Track>, from: Long, to: Long, label: String, hourOf: (Long) -> Int): Wrapped? {
        val ev = events.filter { it.at in from until to }
        if (ev.isEmpty()) return null
        val byKey = songs.associateBy { it.key }
        val st = stats(ev)
        val top = st.entries.mapNotNull { (k, s) -> byKey[k]?.let { it to s.plays } }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
        val art = HashMap<String, Long>()
        ev.forEach { e -> byKey[e.key]?.artist?.takeIf { it.isNotBlank() }?.let { art[it] = (art[it] ?: 0) + e.ms } }
        val hours = IntArray(24); ev.forEach { hours[hourOf(it.at).coerceIn(0, 23)] += 1 }
        return Wrapped(ev.sumOf { it.ms } / 60_000, st.values.sumOf { it.plays }, st.keys.count { it in byKey }, art.size,
            top, art.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value / 60_000 }, hours.indices.maxByOrNull { hours[it] } ?: 0, label)
    }

    /**
     * Blaze DJ: [n] songs to add when the queue runs out. Weighted by how much you play them (and liked ones), never
     * one of the last 20 played, no repeats, a little randomness so it isn't the same every time.
     */
    fun djPicks(songs: List<Track>, events: List<PlayEvent>, liked: Set<String>, recent: List<String>, n: Int, seed: Long): List<Track> {
        val st = stats(events)
        val avoid = recent.take(20).toSet()
        val rnd = Random(seed)
        val pool = songs.filter { it.key !in avoid }.ifEmpty { songs }
        val scored = pool.map { t ->
            val s = st[t.key]
            val w = 1.0 + (s?.plays ?: 0) * 2 + (if (t.key in liked) 6 else 0) + (s?.totalMs ?: 0) / 600_000.0
            t to w * (0.5 + rnd.nextDouble())
        }
        return scored.sortedByDescending { it.second }.take(n).map { it.first }
    }

    // ---------------------------------------------------------------- equalizer

    /** Preset curves as (frequency Hz, gain millibels); applied to whatever bands the phone's equalizer has. */
    val presets: Map<String, List<Pair<Int, Int>>> = linkedMapOf(
        "Flat" to listOf(60 to 0, 14_000 to 0),
        "Bass boost" to listOf(60 to 600, 230 to 350, 910 to 0, 3_600 to 0, 14_000 to 0),
        "Bass reducer" to listOf(60 to -600, 230 to -300, 910 to 0, 3_600 to 0, 14_000 to 0),
        "Vocal" to listOf(60 to -200, 230 to 0, 910 to 350, 3_600 to 400, 14_000 to 100),
        "Treble boost" to listOf(60 to 0, 230 to 0, 910 to 0, 3_600 to 350, 14_000 to 600),
        "Rock" to listOf(60 to 450, 230 to 250, 910 to -150, 3_600 to 250, 14_000 to 450),
        "Pop" to listOf(60 to -100, 230 to 200, 910 to 400, 3_600 to 200, 14_000 to -100),
        "Jazz" to listOf(60 to 300, 230 to 150, 910 to -100, 3_600 to 150, 14_000 to 300),
        "Classical" to listOf(60 to 350, 230 to 200, 910 to -150, 3_600 to 200, 14_000 to 350),
        "Hip-hop" to listOf(60 to 550, 230 to 350, 910 to 0, 3_600 to 150, 14_000 to 250),
        "Small speaker" to listOf(60 to -400, 230 to 200, 910 to 300, 3_600 to 300, 14_000 to 0),
    )

    /** The preset's gain at each of the phone's band centres (Hz), linear in log-frequency, clamped to [min]..[max] mB. */
    fun bandLevels(preset: String, centersHz: List<Int>, min: Int, max: Int): List<Int> {
        val pts = presets[preset] ?: presets.getValue("Flat")
        return centersHz.map { f ->
            val lf = kotlin.math.ln(f.coerceAtLeast(1).toDouble())
            val g = when {
                f <= pts.first().first -> pts.first().second.toDouble()
                f >= pts.last().first -> pts.last().second.toDouble()
                else -> {
                    val i = pts.indexOfLast { it.first <= f }
                    val (f0, g0) = pts[i]; val (f1, g1) = pts[i + 1]
                    val t = (lf - kotlin.math.ln(f0.toDouble())) / (kotlin.math.ln(f1.toDouble()) - kotlin.math.ln(f0.toDouble()))
                    g0 + (g1 - g0) * t
                }
            }
            Math.round(g).toInt().coerceIn(min, max)
        }
    }

    // ---------------------------------------------------------------- fades

    /** Crossfade-style volume: fades in over the first [fadeMs] and out over the last [fadeMs] of a song. 1 when off. */
    fun fadeVolume(posMs: Long, durMs: Long, fadeMs: Long): Float {
        if (fadeMs <= 0 || durMs <= 0 || durMs < fadeMs * 3) return 1f
        val inV = (posMs.toFloat() / fadeMs).coerceIn(0f, 1f)
        val outV = ((durMs - posMs).toFloat() / fadeMs).coerceIn(0f, 1f)
        return minOf(inV, outV).coerceAtLeast(0.05f)
    }

    /** Sleep fade: full volume until the last [fadeMs] before the timer, then down to silence. */
    fun sleepVolume(leftMs: Long, fadeMs: Long = 30_000): Float = if (leftMs >= fadeMs) 1f else (leftMs.toFloat() / fadeMs).coerceIn(0f, 1f)

    // ---------------------------------------------------------------- tag fixer

    private val noise = Regex("""(?i)\s*[(\[]\s*(official\s*(music\s*)?(video|audio|lyric\s*video|visuali[sz]er)|lyrics?|lyric video|audio|hd|hq|4k|1080p|720p|video\s*oficial|clip officiel|visuali[sz]er|explicit|remastered(\s*\d{4})?|full song)\s*[)\]]""")
    private val trailing = Regex("""(?i)\s*[-|]\s*(official\s*(music\s*)?(video|audio)|lyrics?|audio)\s*$""")

    /** "Artist - Title (Official Video)" / channel "ArtistVEVO" / "Artist - Topic" → (artist, title). */
    fun fixTags(title: String, artist: String): Pair<String, String> {
        var t = title.replace(noise, "").replace(trailing, "").replace(Regex("\\s+"), " ").trim()
        var a = artist.replace(Regex("(?i)\\s*-\\s*topic$"), "").replace(Regex("(?i)vevo$"), "").replace(Regex("(?i)\\s*official$"), "").trim()
        val m = Regex("""^(.+?)\s+[-\u2013\u2014|]\s+(.+)$""").find(t)
        if (m != null) {
            val left = m.groupValues[1].trim(); val right = m.groupValues[2].trim()
            if (right.equals(a, true)) t = left else { a = left; t = right }
        }
        t = t.trim('"', '\u201c', '\u201d', ' ')
        return a.ifBlank { artist } to t.ifBlank { title }
    }

    /** The first song's cover from an iTunes Search reply, 600 px. */
    fun itunesArt(json: String): String? = try {
        val r = JSONObject(json).optJSONArray("results")
        r?.optJSONObject(0)?.optString("artworkUrl100")?.takeIf { it.startsWith("http") }?.replace("100x100bb", "600x600bb")
    } catch (_: Exception) { null }

    // ---------------------------------------------------------------- playlist import

    fun youtubePlaylistId(url: String): String? = Regex("""[?&]list=([A-Za-z0-9_-]{10,})""").find(url)?.groupValues?.get(1)
    fun spotifyPlaylistId(url: String): String? = Regex("""(?:open\.spotify\.com/(?:intl-[a-z]+/)?(?:embed/)?playlist/|spotify:playlist:)([A-Za-z0-9]{16,})""").find(url)?.groupValues?.get(1)
    fun spotifyAlbumId(url: String): String? = Regex("""(?:open\.spotify\.com/(?:intl-[a-z]+/)?(?:embed/)?album/|spotify:album:)([A-Za-z0-9]{16,})""").find(url)?.groupValues?.get(1)

    /** Spotify's public embed page → ("Artist - Title" list, playlist name). */
    fun spotifyEmbed(html: String): Pair<List<String>, String> {
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: return emptyList<String>() to ""
        val root = try { JSONObject(json) } catch (_: Exception) { return emptyList<String>() to "" }
        val entity = root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
            ?: return emptyList<String>() to ""
        val list = entity.optJSONArray("trackList") ?: JSONArray()
        val out = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title"); val by = t.optString("subtitle").replace('\u00a0', ' ')
            if (title.isBlank()) null else if (by.isBlank()) title else "$by - $title"
        }
        return out to entity.optString("name").ifBlank { entity.optString("title") }
    }

    /** One page of a YouTube playlist (InnerTube browse) → (videoId, "title") list, continuation token, playlist title. */
    fun ytPlaylist(json: String): Triple<List<Pair<String, String>>, String, String> {
        val out = ArrayList<Pair<String, String>>(); var next = ""; var name = ""
        fun runs(o: JSONObject?): String = o?.optString("simpleText")?.takeIf { it.isNotBlank() }
            ?: o?.optJSONArray("runs")?.let { a -> (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> {
                    x.optJSONObject("playlistVideoRenderer")?.let { v ->
                        val id = v.optString("videoId"); val t = runs(v.optJSONObject("title"))
                        val by = runs(v.optJSONObject("shortBylineText"))
                        if (id.isNotBlank() && out.none { it.first == id }) out += id to (if (by.isNotBlank() && !t.contains(by, true)) "$by - $t" else t)
                        return
                    }
                    x.optJSONObject("playlistMetadataRenderer")?.optString("title")?.takeIf { it.isNotBlank() }?.let { name = it }
                    x.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }?.let { next = it }
                    x.keys().forEach { walk(x.opt(it)) }
                }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(try { JSONObject(json) } catch (_: Exception) { JSONObject() })
        return Triple(out, next, name)
    }

    // ---------------------------------------------------------------- play log JSON

    fun eventsJson(ev: List<PlayEvent>): String = JSONArray().apply { ev.forEach { put(JSONArray().put(it.key).put(it.at).put(it.ms)) } }.toString()
    fun eventsParse(s: String): List<PlayEvent> = try {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { PlayEvent(it.optString(0), it.optLong(1), it.optLong(2)) } }.filter { it.key.isNotBlank() }
    } catch (_: Exception) { emptyList() }
}
