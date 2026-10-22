package com.blaze.assistant.agent

/**
 * v0.45.6 (phone 21:27): "play music" went to the model, which ran `music_ops play` as a shell command (approval card),
 * then fell back to screen control and opened BlazeFX. Requests Blaze can do itself are now routed in-app FIRST, before
 * the model and long before screen control:
 *  - Blaze Music transport ("play music", "pause the music", "next song", "previous track", "what's playing") ->
 *    music_ops (no approval: safe local actions);
 *  - Blaze's own screens ("open Blaze Music", "open projects", "show my gallery") -> open_view.
 * [toolFor] does the same for a shell line that names an in-app tool ("music_ops play"), so it runs as that tool.
 * Anything that names another app or service (Spotify, YouTube…) is left to the normal turn. Pure; InAppRouteTest.
 */
object InAppRoute {
    sealed class Route {
        data class Music(val action: String) : Route()
        data class View(val id: String) : Route()
    }

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val otherApp = r("""\b(spotify|youtube|yt music|apple music|deezer|boomplay|audiomack|soundcloud|tidal|amazon music|on my (?:pc|computer|laptop|tv))\b""")

    private val play = r("""^(?:play|start|put on|turn on)(?: (?:some|the|my))? (?:music|songs?|tunes)(?: (?:in|on) blaze(?: music)?)?$|^play (?:something|a song)$|^(?:resume|continue|unpause)(?: (?:the|my))? (?:music|song|playback|track)$|^play blaze music$""")
    private val pause = r("""^(?:pause|stop|turn off)(?: (?:the|my))? (?:music|song|playback|track)$""")
    private val next = r("""^(?:next|skip)(?: (?:the|this))? (?:song|track)$|^(?:play )?(?:the )?next (?:song|track)$|^skip(?: it)?$""")
    private val prev = r("""^(?:play )?(?:the )?(?:previous|last) (?:song|track)$|^go back a (?:song|track)$""")
    private val now = r("""^(?:what'?s|what is) (?:playing|this song)(?: now)?$|^what song is (?:this|playing)$""")
    private val view = r("""^(?:open|show|go to|take me to|switch to)(?: me)?(?: (?:the|my))? (blaze music|music player|music|projects|gallery|terminal|blaze settings|memory|schedule|design(?: studio)?|home)(?: (?:screen|page|tab|view))?(?: in blaze)?$""")

    fun clean(text: String): String = text.trim().lowercase()
        .replace(Regex("[?.!,]+"), " ").replace(Regex("\\s+"), " ").trim()
        .removePrefix("hey blaze ").removePrefix("blaze ").removePrefix("please ").removeSuffix(" please").removeSuffix(" blaze").trim()

    /** The in-app route for a request, or null (the normal turn handles it). */
    fun match(text: String): Route? {
        val t = clean(text)
        if (t.isEmpty() || t.length > 60 || otherApp.containsMatchIn(t)) return null
        return when {
            play.matches(t) -> Route.Music("play")
            pause.matches(t) -> Route.Music("pause")
            next.matches(t) -> Route.Music("next")
            prev.matches(t) -> Route.Music("previous")
            now.matches(t) -> Route.Music("now")
            else -> view.matchEntire(t)?.groupValues?.get(1)?.let { v ->
                Route.View(when {
                    v.contains("music") -> "music"
                    v == "blaze settings" -> "settings"
                    v.startsWith("design") -> "design"
                    else -> v
                })
            }
        }
    }

    /** In-app tools a model sometimes types as a shell command. */
    val SHELL_ALIASES = setOf("music_ops", "open_view")

    /**
     * A shell line that is really an in-app tool call ("music_ops play", "music_ops next", "open_view music"): the tool and
     * its arguments, or null. Only one simple command (no pipes, no ; or &&).
     */
    fun toolFor(command: String): Pair<String, Map<String, String>>? {
        val c = command.trim()
        if (c.isEmpty() || Regex("""[;&|`$<>]""").containsMatchIn(c)) return null
        val w = c.split(Regex("\\s+"))
        val tool = w[0].substringAfterLast('/')
        if (tool !in SHELL_ALIASES) return null
        val arg = w.drop(1).map { it.trim('"', '\'').removePrefix("--action=").removePrefix("action=").removePrefix("--view=").removePrefix("view=") }
            .firstOrNull { it.isNotBlank() && !it.startsWith("-") } ?: return null
        return when (tool) {
            "music_ops" -> tool to mapOf("action" to arg.lowercase())
            else -> tool to mapOf("view" to arg.lowercase())
        }
    }
}
