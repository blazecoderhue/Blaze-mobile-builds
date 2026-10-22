package com.blaze.assistant.phone

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.delay

/**
 * Opens apps directly on search results via deep links / intents, so "play lofi on YouTube"
 * needs no tapping at all. Falls back to the app's web search page.
 */
object AppSearch {
    private data class Target(val pkg: String?, val build: (String) -> Intent)

    private fun view(u: String, pkg: String? = null) = Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply { if (pkg != null) setPackage(pkg) }
    private fun enc(q: String) = Uri.encode(q)

    private val targets: Map<String, Target> = mapOf(
        "youtube" to Target("com.google.android.youtube") { q -> view("https://www.youtube.com/results?search_query=${enc(q)}", "com.google.android.youtube") },
        "youtube music" to Target("com.google.android.apps.youtube.music") { q -> view("https://music.youtube.com/search?q=${enc(q)}", "com.google.android.apps.youtube.music") },
        "spotify" to Target("com.spotify.music") { q -> view("spotify:search:${enc(q)}", "com.spotify.music") },
        "play store" to Target("com.android.vending") { q -> view("market://search?q=${enc(q)}", "com.android.vending") },
        "maps" to Target("com.google.android.apps.maps") { q -> view("geo:0,0?q=${enc(q)}", "com.google.android.apps.maps") },
        "google" to Target("com.google.android.googlequicksearchbox") { q -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q) },
        "chrome" to Target("com.android.chrome") { q -> view("https://www.google.com/search?q=${enc(q)}", "com.android.chrome") },
        "tiktok" to Target("com.zhiliaoapp.musically") { q -> view("https://www.tiktok.com/search?q=${enc(q)}", "com.zhiliaoapp.musically") },
        "x" to Target("com.twitter.android") { q -> view("https://x.com/search?q=${enc(q)}", "com.twitter.android") },
        "netflix" to Target("com.netflix.mediaclient") { q -> view("https://www.netflix.com/search?q=${enc(q)}", "com.netflix.mediaclient") },
        "amazon" to Target("com.amazon.mShop.android.shopping") { q -> view("https://www.amazon.com/s?k=${enc(q)}", "com.amazon.mShop.android.shopping") },
    )
    private val aliases = mapOf("yt" to "youtube", "google maps" to "maps", "playstore" to "play store", "google play" to "play store",
        "twitter" to "x", "yt music" to "youtube music", "browser" to "chrome")

    suspend fun run(ctx: Context, app: String, query: String, play: Boolean): String {
        if (query.isBlank()) return "Error: nothing to search for."
        val key = app.lowercase().trim().removeSuffix(" app").let { aliases[it] ?: it }
        val t = targets[key] ?: targets.entries.firstOrNull { key.contains(it.key) || it.key.contains(key) }?.value
        val pm = ctx.packageManager
        // Music "play X": ask the app to play the best match (Spotify / YouTube Music honour this).
        if (play && (key == "spotify" || key == "youtube music")) {
            val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*").setPackage(t?.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i); return "Asked ${app.trim()} to play \u201c$query\u201d." } catch (_: Exception) { }
        }
        if (t != null) {
            val installed = t.pkg == null || pm.getLaunchIntentForPackage(t.pkg) != null
            val intent = t.build(query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!installed) intent.setPackage(null)
            try {
                ctx.startActivity(intent)
            } catch (_: Exception) {
                try { ctx.startActivity(intent.setPackage(null)) } catch (e: Exception) { return "Error: couldn't open ${app}: ${e.message}" }
            }
            BlazeAccessibilityService.instance?.settle(2500, 400) ?: delay(1200)
            if (play && key == "youtube") return playFirstYouTube(query)
            return "Opened ${app.trim()} search results for \u201c$query\u201d." + if (!installed) " (App not installed, so it opened on the web.)" else ""
        }
        // Unknown app: open it, then the model can use type_text(submit=true) on its search field.
        return "No direct search link for $app. Open it with open_app, then type_text with submit=true into its search field."
    }

    /** On YouTube results, tap the first video so it starts playing. */
    private suspend fun playFirstYouTube(query: String): String {
        val svc = BlazeAccessibilityService.instance ?: return "Opened YouTube results for \u201c$query\u201d. Turn on phone control so I can start the first video."
        val screen = svc.readScreen()
        val line = screen.lines().firstOrNull { l -> l.startsWith("[") && (l.contains(" minutes") || l.contains(" views") || l.contains("Go to channel").not() && l.contains("play video", true)) }
        val id = line?.substringAfter('[')?.substringBefore(']')?.toIntOrNull()
        if (id != null) { svc.tap(id); svc.settle(2000, 300); return "Playing the top YouTube result for \u201c$query\u201d." }
        return "Opened YouTube results for \u201c$query\u201d. The screen is returned so you can tap a video."
    }
}
