package com.blaze.assistant.browser

/**
 * v0.29.1: the browser shows what it is pulling while a page loads ("Pulling 23 files · fonts.gstatic.com › inter.woff2 · 4s"),
 * so a slow network looks like work, not a freeze. After [STALL_MS] with nothing new it says it's still waiting on the site.
 * Pure Kotlin (LoadFeedTest).
 */
object LoadFeed {
    const val STALL_MS = 6_000L

    /** "fonts.gstatic.com › inter.woff2" from a resource URL (no query, no "www.", file name trimmed). */
    fun label(url: String): String {
        val noQuery = url.substringBefore('#').substringBefore('?')
        val rest = noQuery.substringAfter("://", noQuery)
        val host = rest.substringBefore('/').removePrefix("www.").substringBefore(':')
        val file = rest.substringAfter('/', "").trimEnd('/').substringAfterLast('/')
        val f = if (file.length > 28) file.take(12) + "…" + file.takeLast(12) else file
        return if (f.isEmpty()) host else "$host › $f"
    }

    /** What kind of thing a URL is, for the line ("image", "script", "style", "font", "video", "data"). */
    fun kind(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png", "jpg", "jpeg", "webp", "gif", "svg", "avif", "ico" -> "image"
            "js", "mjs" -> "script"
            "css" -> "style"
            "woff", "woff2", "ttf", "otf" -> "font"
            "mp4", "webm", "m3u8", "ts", "mp3" -> "media"
            "json" -> "data"
            else -> "page"
        }
    }

    /** The line under the progress bar, or "" when nothing is loading. */
    fun line(progress: Int, pulls: Int, last: String, pageHost: String, startedAt: Long, lastPullAt: Long, now: Long): String {
        if (progress !in 0..99) return ""
        val secs = ((now - startedAt).coerceAtLeast(0) / 1000).toInt()
        val t = if (secs >= 1) " · ${secs}s" else ""
        if (pulls == 0) return "Connecting to ${pageHost.ifBlank { "the site" }}$t"
        if (now - lastPullAt >= STALL_MS) return "Slow network · still waiting on ${pageHost.ifBlank { "the site" }} · $pulls files so far$t"
        return "Pulling $pulls file${if (pulls == 1) "" else "s"} · $last$t"
    }
}
