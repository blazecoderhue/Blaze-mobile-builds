package com.blaze.assistant.mediaapps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.30.5 (Blazeinchue: "let Blaze learn the style of music and videos the user listens to and watches", and the Store should
 * open on scrollable ideas instead of a blank search). Blaze Music / Blaze Video note what is played, streamed and
 * downloaded (title + artist / channel, on this phone only). [TasteLogic] turns that into search ideas for the Store
 * (favourite artists / channels and recurring styles first, then starter ideas) and a one-line summary Blaze keeps in
 * memory, so the assistant knows the user's taste too.
 */
data class TasteEvent(val kind: String, val title: String, val author: String, val weight: Int, val at: Long)

/** Pure (TasteLogicTest). */
object TasteLogic {
    const val MAX_EVENTS = 400
    private const val HALF_LIFE_DAYS = 30.0

    val starterAudio = listOf("Top hits this week", "Afrobeats", "Amapiano", "Chill lo-fi beats", "Gospel worship", "Hip hop new releases",
        "R&B slow jams", "Zambian music", "Acoustic covers", "Workout mix", "Piano for focus", "Throwback 2000s hits")
    val starterVideo = listOf("Trending music videos", "Football highlights", "Movie trailers", "Tech reviews", "Comedy sketches",
        "Documentaries", "Cooking recipes", "How it's made", "Travel vlogs", "Science explained", "Gaming highlights", "Car reviews")

    private val noise = setOf(
        "official", "video", "music", "lyric", "lyrics", "audio", "visualizer", "visualiser", "hd", "4k", "hq", "remastered", "remaster",
        "feat", "ft", "the", "and", "with", "from", "for", "you", "your", "this", "that", "live", "version", "full", "new", "song", "songs",
        "mv", "clip", "topic", "vevo", "prod", "edit", "remix", "mix", "cover", "part", "episode", "ep", "vol", "official music video",
        "a", "an", "of", "in", "on", "to", "my", "me", "is", "it", "at", "by", "vs", "x")

    private fun decay(at: Long, now: Long): Double = Math.pow(0.5, ((now - at).coerceAtLeast(0) / 86_400_000.0) / HALF_LIFE_DAYS)

    /** "Jared Benjamin - Topic" / "JaredBenjaminVEVO" → "Jared Benjamin". */
    fun cleanAuthor(a: String): String = a.replace(Regex("(?i)\\s*-\\s*topic$"), "").replace(Regex("(?i)vevo$"), "")
        .replace(Regex("(?i)\\s*official$"), "").trim()

    fun words(title: String): List<String> = title.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .split(Regex("[^\\p{L}\\p{N}&']+"))
        .filter { it.length > 2 && it !in noise && !it.all(Char::isDigit) }

    fun push(events: List<TasteEvent>, e: TasteEvent): List<TasteEvent> {
        if (e.title.isBlank() && e.author.isBlank()) return events
        return (events + e).takeLast(MAX_EVENTS)
    }

    /** Favourite artists / channels for [kind], best first. */
    fun topAuthors(events: List<TasteEvent>, kind: String, now: Long, n: Int = 4): List<String> {
        val score = HashMap<String, Double>(); val name = HashMap<String, String>()
        events.filter { it.kind == kind }.forEach { e ->
            val a = cleanAuthor(e.author); if (a.isBlank()) return@forEach
            val k = a.lowercase()
            score[k] = (score[k] ?: 0.0) + e.weight * decay(e.at, now); name.putIfAbsent(k, a)
        }
        return score.entries.sortedByDescending { it.value }.take(n).map { name[it.key]!! }
    }

    /** Words that keep coming back in titles (seen in 2+ different items), best first. */
    fun topStyles(events: List<TasteEvent>, kind: String, now: Long, n: Int = 3): List<String> {
        val score = HashMap<String, Double>(); val items = HashMap<String, MutableSet<String>>()
        val authors = events.filter { it.kind == kind }.map { cleanAuthor(it.author).lowercase() }.toSet()
        events.filter { it.kind == kind }.forEach { e ->
            words(e.title).toSet().forEach { w ->
                if (authors.any { a -> a.split(' ').contains(w) }) return@forEach   // a name, not a style
                score[w] = (score[w] ?: 0.0) + e.weight * decay(e.at, now)
                items.getOrPut(w) { HashSet() }.add(e.title.lowercase())
            }
        }
        return score.entries.filter { (items[it.key]?.size ?: 0) >= 2 }.sortedByDescending { it.value }.take(n).map { it.key }
    }

    /** The Store's scrollable ideas: what they like first, then starters. Never empty. */
    fun ideas(events: List<TasteEvent>, kind: String, now: Long, max: Int = 12): List<String> {
        val video = kind == "video"
        val out = LinkedHashSet<String>()
        topAuthors(events, kind, now).forEach { out += if (video) it else "$it mix" }
        topStyles(events, kind, now).forEach { out += if (video) it.replaceFirstChar(Char::titlecase) else it.replaceFirstChar(Char::titlecase) + " music" }
        (if (video) starterVideo else starterAudio).forEach { out += it }
        return out.take(max)
    }

    /** One line for Blaze's memory, or "" when there isn't enough to say yet. */
    fun summary(events: List<TasteEvent>, now: Long): String {
        val parts = ArrayList<String>()
        val ma = topAuthors(events, "audio", now, 5); val ms = topStyles(events, "audio", now, 3)
        val va = topAuthors(events, "video", now, 4); val vs = topStyles(events, "video", now, 3)
        if (ma.isNotEmpty() || ms.isNotEmpty()) parts += "Music: " + (ma + ms).joinToString(", ")
        if (va.isNotEmpty() || vs.isNotEmpty()) parts += "Videos: " + (va + vs).joinToString(", ")
        if (parts.isEmpty() || events.size < 5) return ""
        return "Listens to / watches in Blaze Music and Video (learned from plays and downloads): " + parts.joinToString(". ") + "."
    }

    fun toJson(events: List<TasteEvent>): String = JSONArray(events.map {
        JSONObject().put("k", it.kind).put("t", it.title).put("a", it.author).put("w", it.weight).put("at", it.at)
    }).toString()

    fun fromJson(s: String): List<TasteEvent> = try {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            TasteEvent(o.optString("k", "audio"), o.optString("t"), o.optString("a"), o.optInt("w", 1), o.optLong("at")) } }
    } catch (_: Exception) { emptyList() }
}

/** The saved taste (filesDir/mediaapps/taste.json). */
object Taste {
    const val PLAY = 1
    const val DOWNLOAD = 3
    @Volatile private var events: List<TasteEvent> = emptyList()
    @Volatile private var file: File? = null
    private var sinceMemory = 0

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(File(ctx.filesDir, "mediaapps").apply { mkdirs() }, "taste.json")
        file = f
        events = if (f.isFile) TasteLogic.fromJson(runCatching { f.readText() }.getOrDefault("")) else emptyList()
    }

    fun ideas(ctx: Context, kind: String): List<String> { init(ctx); return TasteLogic.ideas(events, kind, System.currentTimeMillis()) }
    /** Wave A 27-28: the For you chips (channels for video, genres for music) and the mix's seed searches. */
    fun chips(ctx: Context, kind: String): List<String> { init(ctx); return ForYou.chips(kind, events, System.currentTimeMillis()) }
    fun seeds(ctx: Context, kind: String): List<String> { init(ctx); return ForYou.seeds(kind, events, System.currentTimeMillis()) }

    @Synchronized fun note(ctx: Context?, kind: String, title: String, author: String, weight: Int) {
        if (ctx != null) init(ctx)
        if (file == null) return
        val now = System.currentTimeMillis()
        // The same song noted twice within a minute (play + transition) counts once.
        if (events.lastOrNull()?.let { it.title == title && it.kind == kind && now - it.at < 60_000 } == true) return
        events = TasteLogic.push(events, TasteEvent(kind, title.take(160), author.take(80), weight, now))
        runCatching { file?.writeText(TasteLogic.toJson(events)) }
        if (++sinceMemory >= 5) { sinceMemory = 0; toMemory(now) }
    }

    /** Keeps one "taste" preference line in Blaze's memory (updated in place as the taste changes). */
    private fun toMemory(now: Long) {
        val line = TasteLogic.summary(events, now).ifEmpty { return }
        try {
            val M = com.blaze.assistant.agent.Mind
            val old = M.all().firstOrNull { it.kind == "preference" && it.subject.equals(SUBJECT, true) }
            if (old != null) { if (old.text != line) M.update(old.id, line) } else M.save("preference", SUBJECT, line)
        } catch (_: Throwable) { }
    }

    const val SUBJECT = "music and video taste"
}
