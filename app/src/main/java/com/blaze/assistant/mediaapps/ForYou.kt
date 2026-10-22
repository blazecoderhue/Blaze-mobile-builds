package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.StoreItem

/**
 * Wave A items 27-28: Videos and Music open on a "For you" mix with nothing narrowed. The source row and the
 * channel / genre row both start on "All"; tapping a chip narrows, tapping it again goes back to All.
 * Pure logic here (unit tested); StoreView wires it.
 */
object ForYou {
    const val ALL = ""          // the "All" chip in either row
    const val SEEDS = 3         // how many taste ideas feed the For you mix

    /** Known genres, matched in titles and artist names of what they actually played (lower-case key → chip label). */
    val genreWords: List<Pair<String, String>> = listOf(
        "amapiano" to "Amapiano", "afrobeat" to "Afrobeats", "afro beat" to "Afrobeats", "afropop" to "Afrobeats",
        "gospel" to "Gospel", "worship" to "Gospel", "praise" to "Gospel", "hip hop" to "Hip hop", "hiphop" to "Hip hop", "rap" to "Hip hop",
        "drill" to "Drill", "trap" to "Trap", "r&b" to "R&B", "rnb" to "R&B", "soul" to "Soul", "lo-fi" to "Lo-fi", "lofi" to "Lo-fi",
        "jazz" to "Jazz", "blues" to "Blues", "rock" to "Rock", "metal" to "Metal", "punk" to "Punk", "indie" to "Indie",
        "pop" to "Pop", "k-pop" to "K-pop", "kpop" to "K-pop", "reggae" to "Reggae", "dancehall" to "Dancehall", "house" to "House",
        "techno" to "Techno", "edm" to "EDM", "classical" to "Classical", "piano" to "Piano", "acoustic" to "Acoustic",
        "country" to "Country", "kalindula" to "Kalindula", "zambian" to "Zambian", "bongo" to "Bongo Flava", "kwaito" to "Kwaito",
        "gqom" to "Gqom", "rhumba" to "Rhumba", "rumba" to "Rhumba", "soundtrack" to "Soundtracks", "ost" to "Soundtracks",
    )
    val starterGenres = listOf("Afrobeats", "Amapiano", "Hip hop", "R&B", "Gospel", "Pop", "Lo-fi", "Zambian", "Jazz", "Rock", "Classical", "Dancehall")

    private fun has(text: String, word: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])").containsMatchIn(text)

    /** Genres from play history, strongest first (recent plays count more), then starters for a cold start. */
    fun genres(events: List<TasteEvent>, now: Long, max: Int = 10): List<String> {
        val score = LinkedHashMap<String, Double>()
        events.filter { it.kind != "video" }.forEach { e ->
            val text = (e.title + " " + e.author).lowercase()
            val w = e.weight * Math.pow(0.5, ((now - e.at).coerceAtLeast(0) / 86_400_000.0) / 30.0)
            genreWords.map { it.second }.distinct().forEach { label ->
                if (genreWords.any { (k, l) -> l == label && has(text, k) }) score[label] = (score[label] ?: 0.0) + w
            }
        }
        val learned = score.entries.sortedByDescending { it.value }.map { it.key }
        return (learned + starterGenres).distinct().take(max)
    }

    /** The narrowing chips after "All": channels/topics for video, genres for music. */
    fun chips(kind: String, events: List<TasteEvent>, now: Long): List<String> =
        if (kind == "video") TasteLogic.ideas(events, kind, now) else genres(events, now)

    /** The searches the For you mix runs: the top taste seeds (channels / genres they play), nothing typed. */
    fun seeds(kind: String, events: List<TasteEvent>, now: Long): List<String> =
        if (kind == "video") TasteLogic.ideas(events, kind, now).take(SEEDS)
        else (genres(events, now).take(2) + TasteLogic.topAuthors(events, kind, now, 1).map { "$it mix" }).distinct().take(SEEDS)

    /**
     * The queries one search runs. A typed query narrowed by a channel/genre is both; a chip alone is the chip;
     * a typed query alone is the query; nothing at all is the For you mix ([seeds]).
     */
    fun queries(typed: String, chip: String, seeds: List<String>): List<String> {
        val t = typed.trim(); val c = chip.trim()
        return when {
            t.isNotEmpty() && c.isNotEmpty() -> listOf("$t $c")
            c.isNotEmpty() -> listOf(c)
            t.isNotEmpty() -> listOf(t)
            else -> seeds.filter { it.isNotBlank() }.ifEmpty { listOf("") }
        }
    }

    /** True when the screen is showing the For you mix (nothing typed, no chip). */
    fun isMix(typed: String, chip: String): Boolean = typed.isBlank() && chip.isBlank()

    /** Tap on a chip: select it, or go back to All when it was already selected. */
    fun toggle(current: String, tapped: String): String = if (current == tapped) ALL else tapped

    /** Source ids a search covers: every ready source for All, else just the picked one. */
    fun sources(picked: String, ready: List<String>): List<String> = if (picked == ALL) ready else ready.filter { it == picked }.ifEmpty { listOf(picked) }

    /** Interleave result lists (one from each source/seed in turn) and drop repeats, so no single source floods the top. */
    fun mix(lists: List<List<StoreItem>>, max: Int = 60): List<StoreItem> {
        val out = ArrayList<StoreItem>(); val seen = HashSet<String>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (l in lists) {
            val it = l.getOrNull(i) ?: continue
            val k = it.source + ":" + it.id
            if (seen.add(k)) out += it
            if (out.size >= max) return out
        }
        return out
    }
}
