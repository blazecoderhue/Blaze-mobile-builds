package com.blaze.assistant.voice

/**
 * v0.45.13 (phone 17:14, 0.45.9): the wake-word notification said "Offline. Turn off in Blaze settings." all the time,
 * even with the phone online. "Offline" meant "the wake word runs on the phone", but it read as "you're offline" and it
 * never changed. Now the line says what the listener is really doing and is re-posted whenever that changes
 * (WakeWordService.note). Pure, so WakeNoteTest covers the wording and the de-duplication.
 */
object WakeNote {
    enum class State { STARTING, LISTENING, PAUSED, SLEEPING, MIC_BLOCKED }

    const val TITLE = "Listening for \u201cHey Blaze\u201d"

    fun title(s: State): String = when (s) {
        State.LISTENING, State.STARTING -> TITLE
        State.PAUSED -> "\u201cHey Blaze\u201d paused"
        State.SLEEPING -> "\u201cHey Blaze\u201d sleeping"
        State.MIC_BLOCKED -> "\u201cHey Blaze\u201d can't hear the mic"
    }

    fun text(s: State): String = when (s) {
        State.STARTING -> "Starting the wake word\u2026"
        State.LISTENING -> "On. Runs on this phone: no audio leaves it until you say \u201cHey Blaze\u201d. Turn off in Blaze settings."
        State.PAUSED -> "Paused while Blaze listens or talks; back on by itself."
        State.SLEEPING -> "Sleeping while the screen is off or the phone is in a pocket."
        State.MIC_BLOCKED -> "Android gave Blaze a silent mic. Tap to bring Blaze to the front."
    }

    /** The state from the service's flags (the mic-blocked warning wins, then pause, then sleep). */
    fun of(listening: Boolean, paused: Boolean, sleeping: Boolean, micBlocked: Boolean): State = when {
        micBlocked -> State.MIC_BLOCKED
        paused -> State.PAUSED
        sleeping -> State.SLEEPING
        listening -> State.LISTENING
        else -> State.STARTING
    }

    /** No word in the line may claim the network state (that is what "Offline" got wrong). */
    fun claimsNetwork(text: String): Boolean = Regex("(?i)\\b(offline|online|no internet|no connection)\\b").containsMatchIn(text)
}
