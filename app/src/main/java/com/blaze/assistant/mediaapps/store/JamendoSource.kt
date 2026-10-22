package com.blaze.assistant.mediaapps.store

import android.content.Context
import com.blaze.assistant.mediaapps.TimeText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.30.1: Jamendo (jamendo.com), independent music under Creative Commons, through its v3 API. Needs a Jamendo
 * client_id (free at https://devportal.jamendo.com). Where it comes from, first found wins:
 *  1. the one the user pasted in the Music Store (saved in filesDir/mediaapps/jamendo.json),
 *  2. the build's BuildConfig.JAMENDO_CLIENT_ID (env JAMENDO_CLIENT_ID or gradle property jamendo.clientId at build time).
 * No key is ever hard-coded; with none, the source shows a "not configured" card instead of results.
 */
object JamendoParse {
    fun search(json: String, offset: Int, limit: Int): StorePage {
        val root = JSONObject(json)
        val h = root.optJSONObject("headers")
        if (h != null && h.optString("status") == "failed") {
            val msg = h.optString("error_message").ifBlank { "Jamendo refused the request" }
            throw StoreError(if (h.optInt("code") == 5 || "client" in msg.lowercase()) "Jamendo didn't accept the client_id. Check it in the Music Store." else msg)
        }
        val res = root.optJSONArray("results") ?: JSONArray()
        val out = ArrayList<StoreItem>()
        for (i in 0 until res.length()) {
            val t = res.optJSONObject(i) ?: continue
            val id = t.optString("id"); if (id.isEmpty()) continue
            val lic = t.optString("license_ccurl")
            val sec = t.optInt("duration")
            out += StoreItem("jamendo", id, t.optString("name"), t.optString("artist_name"), sec, if (sec > 0) TimeText.clock(sec * 1000L) else "",
                t.optString("image").ifBlank { t.optString("album_image") }, Licence.label(lic).ifBlank { "Creative Commons" }, lic, square = true,
                extra = mapOf(
                    "album" to t.optString("album_name"),
                    "audio" to t.optString("audio"),
                    "download" to t.optString("audiodownload"),
                    "allowed" to t.optBoolean("audiodownload_allowed", true).toString(),
                ))
        }
        val count = h?.optInt("results_count") ?: res.length()
        return StorePage(out, if (count >= limit) (offset + limit).toString() else "")
    }

    /** Jamendo's two mp3 encodings: mp31 (96 kbps) and mp32 (VBR, ~190 kbps); the download url gives the best. */
    fun detail(item: StoreItem): StoreDetail {
        val fm = ArrayList<StoreFormat>()
        val allowed = item.extra["allowed"] != "false"
        val dl = item.extra["download"].orEmpty()
        val stream = item.extra["audio"].orEmpty()
        val est = { kbps: Int -> kbps.toLong() * 1000 * item.durationSec / 8 }
        if (allowed && dl.isNotBlank()) fm += StoreFormat("mp32", dl, "audio/mpeg", "mp3", 192_000, est(192), name = item.title)
        if (stream.isNotBlank()) fm += StoreFormat("mp31", withFormat(stream, "mp31"), "audio/mpeg", "mp3", 96_000, est(96), name = item.title)
        return StoreDetail(item.id, item.title, item.author, item.durationSec, item.thumb, fm, item.licence, item.licenceUrl)
    }

    fun withFormat(url: String, f: String): String =
        if (Regex("""[?&]format=""").containsMatchIn(url)) url.replace(Regex("""([?&]format=)[^&]*"""), "$1$f") else url + (if ('?' in url) "&" else "?") + "format=$f"

    fun choices(d: StoreDetail): List<Choice> = d.formats.map { f ->
        val hi = f.key == "mp32"
        Choice(f.key, if (hi) "High \u00b7 MP3 VBR" else "Low \u00b7 MP3 96 kbps", listOf("mp3", TimeText.size(f.contentLength) + " (about)").joinToString(" \u00b7 "),
            listOf(f.key), "mp3", f.contentLength, kbps = f.kbps)
    }
}

object JamendoSource : StoreSource {
    override val id = "jamendo"
    override val name = "Jamendo"
    override val kinds = setOf("audio")
    override val browsable get() = true
    private const val LIMIT = 30

    private var dir: File? = null
    @Volatile private var userId: String = ""

    fun init(ctx: Context) {
        if (dir != null) return
        dir = File(ctx.filesDir, "mediaapps").apply { mkdirs() }
        userId = runCatching { JSONObject(File(dir, "jamendo.json").readText()).optString("client_id") }.getOrDefault("")
    }

    fun buildId(): String = try { com.blaze.assistant.BuildConfig.JAMENDO_CLIENT_ID } catch (_: Throwable) { "" }
    fun clientId(): String = userId.trim().ifBlank { buildId().trim() }

    fun setClientId(id: String) {
        userId = id.trim()
        dir?.let { runCatching { File(it, "jamendo.json").writeText(JSONObject().put("client_id", userId).toString()) } }
    }

    /** v0.30.5: where a free client_id is made (the setup card's "Get a free ID" button). */
    const val DEVPORTAL = "https://devportal.jamendo.com"

    /** True when Jamendo answers a one-track request with this client_id. */
    suspend fun works(id: String): Boolean = try {
        val r = JSONObject(StoreHttp.get("https://api.jamendo.com/v3.0/tracks/?client_id=" + StoreHttp.enc(id.trim()) + "&format=json&limit=1"))
        r.optJSONObject("headers")?.optString("status") == "success"
    } catch (_: Exception) { false }

    /** v0.30.5: the jamendo_setup tool, so "Ask Blaze to set it up" can save the id Blaze got from the dev portal. */
    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", TOOL)
        .put("description", "Save a Jamendo API client_id for Blaze Music's Jamendo Store (checked against Jamendo before it's saved). " +
            "Get one free at $DEVPORTAL: sign in or sign up (ask the user before creating an account), My applications, create an app " +
            "(name Blaze Music, website https://blazeinchue.pages.dev), then copy its Client ID here.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("client_id", JSONObject().put("type", "string").put("description", "The app's Client ID (8 hex characters)")))
            .put("required", JSONArray().put("client_id"))))
    const val TOOL = "jamendo_setup"

    suspend fun runTool(args: JSONObject): String {
        val id = args.optString("client_id").trim()
        if (id.isEmpty()) return "Error: give client_id."
        if (!works(id)) return "Jamendo didn't accept that client_id. Check it on $DEVPORTAL (My applications) and try again."
        setClientId(id)
        return "Saved. The Jamendo Store in Blaze Music works now."
    }

    override fun setupNeeded(): String? = if (clientId().isBlank())
        "Jamendo needs a free client_id. Blaze can get it for you, or make one on the Jamendo dev portal and paste it below." else null

    override suspend fun search(kind: String, query: String, next: String): StorePage {
        val id = clientId().ifBlank { throw StoreError(setupNeeded() ?: "Jamendo isn't configured") }
        val offset = next.toIntOrNull() ?: 0
        val q = query.trim()
        val url = "https://api.jamendo.com/v3.0/tracks/?client_id=" + StoreHttp.enc(id) + "&format=json&limit=$LIMIT&offset=$offset" +
            "&include=licenses&audioformat=mp32&imagesize=300" + (if (q.isEmpty()) "&order=popularity_month" else "&search=" + StoreHttp.enc(q))
        return JamendoParse.search(StoreHttp.get(url), offset, LIMIT)
    }

    override suspend fun detail(item: StoreItem, kind: String) = JamendoParse.detail(item)
    override fun choices(kind: String, d: StoreDetail) = JamendoParse.choices(d)
}
