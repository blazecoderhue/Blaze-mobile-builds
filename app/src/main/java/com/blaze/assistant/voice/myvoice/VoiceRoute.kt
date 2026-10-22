package com.blaze.assistant.voice.myvoice

/**
 * The decisions behind My voice, kept pure so they're tested on the JVM (VoiceRouteTest):
 * which voice speaks, when a slow phone hands the rest of a reply to the system voice, and what to tell the user (once).
 */
object VoiceRoute {
    enum class Route { SYSTEM, MINE }

    /** Why My voice isn't speaking. Each reason is told to the user at most once. */
    enum class Reason { NONE, NOT_CHOSEN, NOT_SET_UP, SLOW, FAILED }

    /** After a reply was too slow, the system voice speaks for this long before My voice is tried again. */
    const val SLOW_COOLDOWN_MS = 6 * 60 * 60 * 1000L

    fun pick(chosen: Boolean, packReady: Boolean, voiceReady: Boolean, failedThisRun: Boolean, slowAt: Long, keepWhenSlow: Boolean, now: Long): Pair<Route, Reason> = when {
        !chosen -> Route.SYSTEM to Reason.NOT_CHOSEN
        !packReady || !voiceReady -> Route.SYSTEM to Reason.NOT_SET_UP
        failedThisRun -> Route.SYSTEM to Reason.FAILED
        !keepWhenSlow && slowAt > 0 && now - slowAt in 0 until SLOW_COOLDOWN_MS -> Route.SYSTEM to Reason.SLOW
        else -> Route.MINE to Reason.NONE
    }

    /** Plain words for the one-time notice; null when there's nothing to say. */
    fun notice(reason: Reason): String? = when (reason) {
        Reason.SLOW -> "Your voice is too slow on this phone right now, so I'm using the phone voice. You can change this in Settings → Voice."
        Reason.FAILED -> "Your voice couldn't start on this phone, so I'm using the phone voice for now."
        Reason.NOT_SET_UP -> "Your voice isn't set up yet, so I'm using the phone voice. Finish it in Settings → Voice."
        else -> null
    }

    /** Settings stores told reasons as "SLOW,FAILED". Returns the text to show now (or null) and the new stored value. */
    fun tellOnce(reason: Reason, told: String): Pair<String?, String> {
        val text = notice(reason) ?: return null to told
        val set = told.split(',').filter { it.isNotBlank() }.toMutableSet()
        if (reason.name in set) return null to told
        set += reason.name
        return text to set.sorted().joinToString(",")
    }

    /** Fewer flow steps = faster on low-end CPUs, a little rougher. */
    fun numSteps(quality: String, lowEnd: Boolean): Int = when (quality) {
        "fast" -> 2
        "balanced" -> 3
        "best" -> 5
        else -> 3   // measured: steps barely change speed (lm_main dominates), so Auto keeps quality
    }

    /** A low-memory phone: 4 GB RAM or less, or only little cores. Picks threads and default quality. */
    fun lowEnd(totalRamBytes: Long, cores: Int): Boolean = totalRamBytes in 1..(4_300L * 1024 * 1024) || cores <= 4

    fun threads(cores: Int): Int = when {
        cores >= 8 -> 4
        cores >= 4 -> 2
        else -> 1
    }
}

/**
 * Watches how fast My voice makes speech compared with how long that speech plays. While piece N plays, piece N+1
 * is being made; if making takes longer than playing, the reply stutters, so hand the rest to the system voice.
 * The first piece is allowed to be slow (the model warms up) unless it's hopeless.
 */
class SpeedWatch(
    private val keepWhenSlow: Boolean,
    private val maxRtf: Float = 1.15f,
    private val hopelessRtf: Float = 2.5f,
    private val maxFirstAudioMs: Long = 8_000,
) {
    enum class Verdict { KEEP, SLOW }

    private var avg = -1f
    var pieces = 0
        private set
    /** Last measured real-time factor (time to make / time it plays). Below 1 = faster than real time. */
    var lastRtf = 0f
        private set

    fun onPiece(makeMs: Long, audioMs: Long): Verdict {
        pieces++
        val rtf = if (audioMs <= 0) 10f else makeMs.toFloat() / audioMs
        lastRtf = rtf
        if (keepWhenSlow) return Verdict.KEEP
        if (pieces == 1) {
            return if (rtf > hopelessRtf || makeMs > maxFirstAudioMs) Verdict.SLOW else Verdict.KEEP
        }
        avg = if (avg < 0) rtf else avg * 0.5f + rtf * 0.5f
        return if (avg > maxRtf || rtf > hopelessRtf) Verdict.SLOW else Verdict.KEEP
    }

    companion object {
        /** Plain verdict for the setup test. */
        fun describe(rtf: Float): String = when {
            rtf <= 0f -> ""
            rtf < 0.7f -> "Fast enough on this phone."
            rtf <= 1.0f -> "Just fast enough on this phone. Long replies may pause now and then."
            rtf <= 2.0f -> "A bit slow on this phone: it takes longer to make the speech than to say it, so replies will pause between sentences."
            else -> "Too slow on this phone for live replies. Blaze will use the phone voice when it can't keep up."
        }
    }
}
