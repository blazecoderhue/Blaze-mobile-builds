package com.blaze.assistant.mediaapps

import kotlin.random.Random

/**
 * v0.30.1 Blaze Music / Blaze Video: the pure rules (no Android), unit tested in MediaLogicTest.
 * Playback itself is media3 ExoPlayer; these decide what the screens show and what gets stored.
 */

/** One playable thing in the library: a downloaded song / video (MediaStore content uri) or a gallery file (file uri). */
data class Track(
    val key: String,            // stable id: the uri string
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val art: String = "",       // file path or https url of the cover / thumbnail
    val source: String = "download",   // download | gallery | stream
    val addedAt: Long = 0,
    val videoId: String = "",   // YouTube id when it came from the Store
    val kind: String = "audio", // audio | video
    val licence: String = "",   // "CC BY 4.0", "Public domain", "YouTube" for Store downloads
    val storeSource: String = "", // StoreSource.id it came from
)

enum class Repeat {
    OFF, ALL, ONE;
    /** The repeat button cycles off → all → one → off. */
    fun next(): Repeat = when (this) { OFF -> ALL; ALL -> ONE; ONE -> OFF }
}

object QueueLogic {
    /**
     * A shuffle order over [size] items that starts with [start] (the song the user tapped plays first), the rest in
     * random order. Deterministic for a given [seed].
     */
    fun shuffleOrder(size: Int, start: Int, seed: Long): List<Int> {
        if (size <= 0) return emptyList()
        val s = start.coerceIn(0, size - 1)
        val rest = (0 until size).filter { it != s }.shuffled(Random(seed))
        return listOf(s) + rest
    }

    /** Play order: [0..size) when not shuffled, else [order]. */
    fun playOrder(size: Int, shuffled: Boolean, order: List<Int>): List<Int> =
        if (shuffled && order.size == size) order else (0 until size).toList()

    /** The index that plays after [current], or -1 when the queue ends. Repeat ONE stays on the song (auto-advance). */
    fun nextIndex(current: Int, size: Int, repeat: Repeat, order: List<Int>): Int {
        if (size <= 0 || current !in 0 until size) return -1
        if (repeat == Repeat.ONE) return current
        val o = if (order.size == size) order else (0 until size).toList()
        val p = o.indexOf(current)
        return when {
            p < 0 -> -1
            p + 1 < o.size -> o[p + 1]
            repeat == Repeat.ALL -> o[0]
            else -> -1
        }
    }

    /** The index before [current] (the ⏮ button after the first 3 s restarts the song instead; that's the player's). */
    fun prevIndex(current: Int, size: Int, repeat: Repeat, order: List<Int>): Int {
        if (size <= 0 || current !in 0 until size) return -1
        val o = if (order.size == size) order else (0 until size).toList()
        val p = o.indexOf(current)
        return when {
            p < 0 -> -1
            p > 0 -> o[p - 1]
            repeat == Repeat.ALL -> o.last()
            else -> -1
        }
    }

    /** What the queue view lists as "Up next": up to [count] indices after [current], wrapping only with repeat ALL. */
    fun upNext(current: Int, size: Int, repeat: Repeat, order: List<Int>, count: Int = 50): List<Int> {
        val o = if (order.size == size) order else (0 until size).toList()
        val p = o.indexOf(current)
        if (p < 0) return o.take(count)
        val after = o.drop(p + 1)
        val wrap = if (repeat == Repeat.ALL) o.take(p) else emptyList()
        return (after + wrap).take(count)
    }

    /** Moves one item of a list (queue reorder, playlist reorder). Out-of-range moves leave the list unchanged. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList()
        val x = m.removeAt(from)
        m.add(to, x)
        return m
    }
}

data class Playlist(val id: String, val name: String, val keys: List<String> = emptyList(), val updated: Long = 0)

/** Playlist edits: every op returns the new list of playlists (the store saves it). */
object PlaylistOps {
    fun cleanName(name: String): String = name.trim().replace(Regex("\\s+"), " ").take(60)

    /** A name not already taken ("Road trip", "Road trip 2", …). */
    fun uniqueName(lists: List<Playlist>, name: String): String {
        val base = cleanName(name).ifBlank { "New playlist" }
        val taken = lists.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base $n".lowercase() in taken) n++
        return "$base $n"
    }

    fun create(lists: List<Playlist>, name: String, id: String, now: Long = 0): List<Playlist> =
        lists + Playlist(id, uniqueName(lists, name), emptyList(), now)

    fun rename(lists: List<Playlist>, id: String, name: String, now: Long = 0): List<Playlist> {
        val n = cleanName(name)
        if (n.isBlank()) return lists
        val others = lists.filter { it.id != id }
        return lists.map { if (it.id == id) it.copy(name = uniqueName(others, n), updated = now) else it }
    }

    fun delete(lists: List<Playlist>, id: String): List<Playlist> = lists.filter { it.id != id }

    /** Adds songs at the end, skipping ones already in the playlist. */
    fun add(lists: List<Playlist>, id: String, keys: List<String>, now: Long = 0): List<Playlist> = lists.map { p ->
        if (p.id != id) p else {
            val have = p.keys.toMutableList()
            keys.forEach { if (it !in have) have += it }
            p.copy(keys = have, updated = now)
        }
    }

    fun removeAt(lists: List<Playlist>, id: String, index: Int, now: Long = 0): List<Playlist> = lists.map { p ->
        if (p.id != id || index !in p.keys.indices) p else p.copy(keys = p.keys.filterIndexed { i, _ -> i != index }, updated = now)
    }

    fun move(lists: List<Playlist>, id: String, from: Int, to: Int, now: Long = 0): List<Playlist> = lists.map { p ->
        if (p.id != id) p else p.copy(keys = QueueLogic.move(p.keys, from, to), updated = now)
    }

    /** A song deleted from the library leaves every playlist. */
    fun forget(lists: List<Playlist>, key: String): List<Playlist> = lists.map { p -> if (key in p.keys) p.copy(keys = p.keys - key) else p }
}

object Recents {
    /** Most recent first, no duplicates, at most [max]. */
    fun push(list: List<String>, key: String, max: Int = 50): List<String> = (listOf(key) + list.filter { it != key }).take(max)
}

object ResumeLogic {
    /** Resume a video where it was left unless it was barely started or practically finished. */
    fun resumeAt(savedMs: Long, durationMs: Long): Long = when {
        savedMs < 5_000 -> 0
        durationMs > 0 && savedMs > durationMs - 10_000 -> 0
        else -> savedMs
    }

    /** Whether to keep saving a position (finished videos are forgotten so they start over next time). */
    fun keep(posMs: Long, durationMs: Long): Boolean = resumeAt(posMs, durationMs) > 0
}

object TimeText {
    fun clock(ms: Long): String = com.blaze.assistant.mediaapps.store.StoreText.clock(ms)
    fun parseClock(s: String): Int = com.blaze.assistant.mediaapps.store.StoreText.parseClock(s)
    fun size(bytes: Long): String = com.blaze.assistant.mediaapps.store.StoreText.size(bytes)
}

object SleepTimer {
    val presetsMin = listOf(5, 10, 15, 30, 45, 60, 90)
    /** Milliseconds left on a timer that ends at [endAt] (0 when off or over). */
    fun left(endAt: Long, now: Long): Long = if (endAt <= 0) 0 else (endAt - now).coerceAtLeast(0)
}

object Speeds {
    val all = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    fun label(s: Float): String = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "×"
    fun next(s: Float): Float { val i = all.indexOfFirst { it > s + 0.001f }; return if (i < 0) all.first() else all[i] }
}

/** Library grouping for the Artists / Albums views. */
object LibraryGroups {
    fun artists(tracks: List<Track>): List<Pair<String, List<Track>>> =
        tracks.groupBy { it.artist.ifBlank { "Unknown artist" } }.toList().sortedBy { it.first.lowercase() }

    fun albums(tracks: List<Track>): List<Pair<String, List<Track>>> =
        tracks.groupBy { it.album.ifBlank { it.artist.ifBlank { "Singles" } } }.toList().sortedBy { it.first.lowercase() }

    fun search(tracks: List<Track>, q: String): List<Track> {
        val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return tracks
        return tracks.filter { t -> val hay = (t.title + " " + t.artist + " " + t.album).lowercase(); words.all { it in hay } }
    }
}
