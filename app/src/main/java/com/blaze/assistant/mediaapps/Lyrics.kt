package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.StoreError
import com.blaze.assistant.mediaapps.store.StoreHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.30.1: lyrics for the now-playing screen from LRCLIB (lrclib.net: free, no key, community lyrics, synced when
 * available). Looked up only when the user opens Lyrics, cached on the phone (filesDir/mediaapps/lyrics) so they
 * work offline afterwards. "Not found" is cached too, for a day.
 */
object Lyrics {
    data class Result(val synced: List<LyricLine>, val plain: String, val instrumental: Boolean = false) {
        val empty get() = synced.isEmpty() && plain.isBlank() && !instrumental
    }

    private val mem = HashMap<String, Result>()

    private fun key(t: Track) = (t.artist + "|" + t.title).lowercase().hashCode().toUInt().toString(16)

    fun parse(json: String): Result {
        val o = JSONObject(json)
        return Result(Lrc.parse(o.optString("syncedLyrics").takeIf { it != "null" }.orEmpty()),
            o.optString("plainLyrics").takeIf { it != "null" }.orEmpty(), o.optBoolean("instrumental"))
    }

    suspend fun get(dir: File, t: Track): Result {
        val k = key(t)
        mem[k]?.let { return it }
        val f = File(File(dir, "mediaapps/lyrics").apply { mkdirs() }, "$k.json")
        if (f.isFile && (f.length() > 2 || System.currentTimeMillis() - f.lastModified() < 86_400_000L)) {
            val r = if (f.length() <= 2) Result(emptyList(), "") else runCatching { parse(f.readText()) }.getOrDefault(Result(emptyList(), ""))
            mem[k] = r; return r
        }
        val title = Lrc.cleanTitle(t.title, t.artist)
        val dur = (t.durationMs / 1000).toInt()
        var body = ""
        try {
            body = StoreHttp.get("https://lrclib.net/api/get?track_name=" + StoreHttp.enc(title) + "&artist_name=" + StoreHttp.enc(t.artist) +
                (if (dur > 0) "&duration=$dur" else ""))
        } catch (e: StoreError) {
            if (e.code != 404) throw e
            // No exact match: best search hit.
            val a = JSONArray(StoreHttp.get("https://lrclib.net/api/search?q=" + StoreHttp.enc((t.artist + " " + title).trim())))
            if (a.length() > 0) body = a.getJSONObject(0).toString()
        }
        val r = if (body.isBlank()) Result(emptyList(), "") else parse(body)
        runCatching { f.writeText(if (r.empty) "{}" else body) }
        mem[k] = r
        return r
    }
}
