package com.blaze.assistant.mediaapps

/**
 * v0.45.6 (phone 21:27, "play music" asked for approval, then drove the screen into BlazeFX): Blaze Music's transport
 * (play, pause, resume, next, previous, stop, now) as a pure decision, so chat, the music_ops tool and the in-app
 * fast path ([com.blaze.assistant.agent.InAppRoute]) all do the same safe local thing with no card and no screen control.
 *
 * "play" with nothing queued never guesses: it asks what to play and offers the last playlist or the local music.
 * Pure Kotlin; MusicCtlTest.
 */
object MusicCtl {
    val ACTIONS = listOf("play", "pause", "resume", "next", "previous", "stop", "now")

    /** What the player looks like right now. [service] = MusicService is running; [queue] = items in its queue. */
    data class State(val service: Boolean, val playing: Boolean, val queue: Int, val savedQueue: Int, val songs: Int,
                     val lastList: String = "", val title: String = "")

    sealed class Decision {
        /** Run [op] on the running player ("play", "pause", "next", "previous", "stop"). */
        data class Player(val op: String, val say: String) : Decision()
        /** Start the service and resume the saved queue (it opens Blaze Music's player). */
        data class ResumeSaved(val say: String) : Decision()
        /** Nothing to act on: [say] is the honest answer or the question to the user. */
        data class Say(val say: String, val asks: Boolean = false) : Decision()
    }

    fun norm(action: String): String = when (action.trim().lowercase()) {
        "play", "start" -> "play"
        "resume", "continue", "unpause" -> "resume"
        "pause", "hold" -> "pause"
        "next", "skip", "forward" -> "next"
        "previous", "prev", "back", "last" -> "previous"
        "stop" -> "stop"
        "now", "status", "now_playing", "what" -> "now"
        else -> action.trim().lowercase()
    }

    /** The question when "play" has nothing to play. Offers the last playlist and/or local music when they exist. */
    fun askWhat(s: State): String {
        if (s.songs == 0) return "There's no music in Blaze Music yet. What should I play? Name a song or artist and I'll find it, or send a playlist link to import."
        val offers = ArrayList<String>()
        if (s.lastList.isNotBlank()) offers += "your last playlist, ${s.lastList}"
        offers += "a shuffle of your local music (${s.songs} song${if (s.songs == 1) "" else "s"})"
        return "Nothing is queued. What should I play? I can start " + offers.joinToString(" or ") + "."
    }

    fun decide(action: String, s: State): Decision {
        val a = norm(action)
        val now = if (s.title.isNotBlank()) " \u201c${s.title}\u201d" else ""
        return when (a) {
            "play", "resume" -> when {
                s.service && s.playing -> Decision.Say("Already playing$now in Blaze Music.")
                s.service && s.queue > 0 -> Decision.Player("play", "Playing$now in Blaze Music.")
                !s.service && s.savedQueue > 0 -> Decision.ResumeSaved("Picking up where you left off in Blaze Music.")
                else -> Decision.Say(askWhat(s), asks = true)
            }
            "pause", "stop" -> when {
                s.service && s.playing -> Decision.Player("pause", "Paused$now.")
                else -> Decision.Say("Nothing is playing in Blaze Music.")
            }
            "next", "previous" -> when {
                s.service && s.queue > 0 -> Decision.Player(a, if (a == "next") "Skipped to the next song." else "Back to the previous song.")
                else -> Decision.Say("Nothing is queued in Blaze Music. " + askWhat(s), asks = true)
            }
            "now" -> when {
                s.service && s.title.isNotBlank() -> Decision.Say((if (s.playing) "Playing" else "Paused on") + now + " in Blaze Music.")
                else -> Decision.Say("Nothing is playing in Blaze Music.")
            }
            else -> Decision.Say("Unknown action.")
        }
    }
}
