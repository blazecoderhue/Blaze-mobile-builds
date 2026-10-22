package com.blaze.assistant.mediaapps

import android.content.Context
import com.blaze.assistant.mediaapps.store.Choice
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.YouTubeStoreSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.33.0: "download these songs" from the chat, in one go. The agent passes a list (song or video names, or
 * YouTube ids once the user picked); each is searched on YouTube, the best match is scored ([MediaPick]), clear
 * matches are queued straight into the Music / Video download queue at the quality asked for, and unclear ones come
 * back with their top candidates so the agent asks the user, then calls again with the ids they chose.
 */
object MediaFetch {
    const val TOOL = "media_download"
    const val MAX = 25

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Download songs (into Blaze Music) or videos (into Blaze Video) from YouTube, many at once. Give every song/video the user " +
            "named as one item (\"Artist - Title\" when known). Clear matches are queued right away; for items the tool isn't sure about it returns " +
            "candidates: ask the user which one (or skip), then call again with items as {\"id\":\"<video id>\"}. quality: best, high (default), " +
            "small; or for video a height like 720 / 480 / 1080. Audio comes as m4a (plays everywhere) unless format=opus.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("items", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("query", JSONObject().put("type", "string")).put("id", JSONObject().put("type", "string").put("description", "a YouTube video id the user picked")))))
            .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("audio", "video"))))
            .put("quality", JSONObject().put("type", "string"))
            .put("format", JSONObject().put("type", "string").put("enum", JSONArray(listOf("m4a", "opus")))))
            .put("required", JSONArray(listOf("items")))))

    fun describe(args: JSONObject): String {
        val n = args.optJSONArray("items")?.length() ?: 0
        return "Finding ${if (n == 1) "1 ${if (args.optString("kind") == "video") "video" else "song"}" else "$n ${if (args.optString("kind") == "video") "videos" else "songs"}"} to download"
    }

    suspend fun run(ctx: Context, args: JSONObject): String {
        val kind = if (args.optString("kind") == "video") "video" else "audio"
        val quality = args.optString("quality").trim().lowercase()
        val format = args.optString("format").lowercase()
        val arr = args.optJSONArray("items") ?: return "No items given."
        val items = (0 until arr.length()).mapNotNull { i ->
            when (val x = arr.opt(i)) { is JSONObject -> x; is String -> JSONObject().put("query", x); else -> null } }.take(MAX)
        val queued = ArrayList<String>(); val unsure = ArrayList<String>(); val failed = ArrayList<String>()
        for (it in items) {
            val id = it.optString("id").trim(); val q = it.optString("query").trim()
            try {
                val pick: StoreItem = if (id.isNotBlank()) StoreItem(YouTubeStoreSource.id, id, q, "")
                else {
                    if (q.isBlank()) continue
                    val res = YouTubeStoreSource.search(kind, q, "").items.take(6)
                    val ranked = MediaPick.rank(q, kind, res)
                    val best = ranked.firstOrNull()
                    if (best == null) { failed += "\u201c$q\u201d: nothing found"; continue }
                    if (!MediaPick.confident(ranked)) {
                        unsure += "\u201c$q\u201d: " + ranked.take(3).joinToString(" | ") { (s, _) -> "${s.title} \u2014 ${s.author} (${s.durationText}) id=${s.id}" }
                        continue
                    }
                    best.first
                }
                val d = YouTubeStoreSource.detail(pick, kind)
                val ch = MediaPick.choose(kind, YouTubeStoreSource.choices(kind, d), quality, format) ?: throw Exception("no downloadable stream")
                val job = Downloads.add(ctx, pick.copy(title = pick.title.ifBlank { d.title }, author = pick.author.ifBlank { d.author },
                    thumb = pick.thumb.ifBlank { d.thumb }), d, ch, kind)
                queued += "${job.title} (${ch.label})"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { failed += "\u201c${q.ifBlank { id }}\u201d: ${e.message ?: "failed"}" }
        }
        return buildString {
            if (queued.isNotEmpty()) append("Queued ${queued.size} in Blaze ${if (kind == "video") "Video" else "Music"} (downloading now): ${queued.joinToString("; ")}.")
            if (unsure.isNotEmpty()) append("\nNot sure about ${unsure.size}; ask the user which one (or to skip), then call $TOOL again with {\"id\":...}:\n- " + unsure.joinToString("\n- "))
            if (failed.isNotEmpty()) append("\nCouldn't get: ${failed.joinToString("; ")}.")
            if (isEmpty()) append("Nothing to download.")
        }.trim()
    }
}

/** Pure match scoring and quality picking for [MediaFetch] (MediaFetchTest). */
object MediaPick {
    private val junk = listOf("live", "cover", "remix", "karaoke", "reaction", "8d", "slowed", "sped up", "nightcore", "instrumental", "tutorial", "lesson", "mashup", "bass boosted", "1 hour", "10 hours", "loop")

    fun words(s: String): List<String> = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.length > 1 && it !in setOf("the", "ft", "feat", "official", "video", "audio", "lyrics", "lyric", "music", "hd", "hq", "by", "and") }

    fun score(query: String, kind: String, it: StoreItem): Double {
        val q = words(query).distinct()
        if (q.isEmpty()) return 0.0
        val hay = words(it.title + " " + it.author).toSet()
        var s = q.count { w -> w in hay || hay.any { h -> h.startsWith(w) && w.length >= 4 } }.toDouble() / q.size
        val tl = (it.title + " ").lowercase(); val ql = query.lowercase()
        junk.forEach { j -> if (Regex("\\b${Regex.escape(j)}\\b").containsMatchIn(tl) && !ql.contains(j)) s -= 0.3 }
        if (it.author.endsWith(" - Topic") || it.author.contains("VEVO", true)) s += 0.1
        if (kind == "audio" && (tl.contains("official audio") || tl.contains("official music video") || tl.contains("lyric"))) s += 0.05
        if (kind == "audio" && it.durationSec > 15 * 60 && !Regex("\\b(mix|album|full|playlist|hour)\\b").containsMatchIn(ql)) s -= 0.4
        return s
    }

    fun rank(query: String, kind: String, items: List<StoreItem>): List<Pair<StoreItem, Double>> =
        items.mapIndexed { i, it -> it to score(query, kind, it) - i * 0.02 }.sortedByDescending { it.second }

    /** Clear match: nearly every word found, and nothing else close behind (or the runner-up is the same song). */
    fun confident(r: List<Pair<StoreItem, Double>>): Boolean {
        val a = r.firstOrNull() ?: return false
        if (a.second < 0.8) return false
        val b = r.getOrNull(1) ?: return true
        if (a.second - b.second >= 0.15) return true
        // runner-up close: fine when both are the same song (official audio vs video), not two different ones
        val wa = words(a.first.title).toSet(); val wb = words(b.first.title).toSet()
        val overlap = wa.intersect(wb).size.toDouble() / maxOf(1, maxOf(wa.size, wb.size))
        return overlap >= 0.8 && kotlin.math.abs(a.first.durationSec - b.first.durationSec) <= 8
    }

    /** The download option for a quality word ("best", "high", "small", "720", "720p"). */
    fun choose(kind: String, choices: List<Choice>, quality: String, format: String = ""): Choice? {
        if (choices.isEmpty()) return null
        if (kind == "video") {
            val h = Regex("(\\d{3,4})").find(quality)?.groupValues?.get(1)?.toIntOrNull()
                ?: when (quality) { "best" -> 10_000; "small", "low" -> 360; else -> 720 }
            return choices.filter { it.height in 1..h }.maxByOrNull { it.height } ?: choices.minByOrNull { it.height }
        }
        val pool = if (format == "opus") choices.filter { it.ext == "opus" }.ifEmpty { choices } else choices.filter { it.ext != "opus" }.ifEmpty { choices }
        return when (quality) {
            "small", "low", "smaller" -> pool.minByOrNull { it.kbps }
            else -> pool.maxByOrNull { it.kbps }
        }
    }
}
