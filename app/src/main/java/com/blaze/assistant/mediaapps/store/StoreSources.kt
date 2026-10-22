package com.blaze.assistant.mediaapps.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * v0.30.1: the Store sources this build has. The free ones are built in; YouTube is a plug-in module (youtube/ →
 * Gradle :youtube) that is only compiled in when the folder exists, and is found here by class name at runtime. With
 * no plug-in the YouTube store simply isn't listed.
 */
object StoreSources {
    /** The YouTube plug-in's class (module :youtube). Absent in builds without the youtube/ folder. */
    const val YOUTUBE_CLASS = "com.blaze.assistant.mediaapps.youtube.YouTubeSource"

    /** A Kotlin `object` StoreSource by class name, or null when that module isn't in this build. */
    fun plugin(className: String): StoreSource? = try {
        Class.forName(className).getField("INSTANCE").get(null) as? StoreSource
    } catch (_: Throwable) { null }

    val all: List<StoreSource> by lazy {
        // v0.33.0: the built-in YouTube source (search + the working link clients) replaces the plug-in.
        listOf(YouTubeStoreSource, JamendoSource, ArchiveAudioSource, ArchiveFilmsSource, BlenderMoviesSource, LinkSource)
    }

    /** The source chips (v0.32.1: the Link source is reached by "Download by link", not a chip). */
    fun forKind(kind: String): List<StoreSource> = all.filter { kind in it.kinds && it !== LinkSource }
    fun get(id: String): StoreSource? = all.firstOrNull { it.id == id }
}

/** Small HTTP helpers shared by the sources. */
object StoreHttp {
    suspend fun get(url: String, ua: String = "Blaze/1 (Android; +https://blazeinchue.pages.dev)"): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 25_000
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw StoreError("The server answered $code", code)
            text
        } finally { c.disconnect() }
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

