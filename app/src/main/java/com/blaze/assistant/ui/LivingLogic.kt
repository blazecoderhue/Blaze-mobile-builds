package com.blaze.assistant.ui

/**
 * v0.39.0 Look & feel, pure part (LivingLogicTest): which living background, the sky colours for the local time of day
 * (and the weather when a Home weather widget already has it), and when the background must keep still.
 *
 * Prefs (new keys only): `look_bg` (paper default = the app's current background, so nothing changes until the user
 * picks one), `look_accent` (ARGB hex, "" = the current accent), `look_music` (Move with music), `look_still`
 * (Save battery: keep it still), `look_photo` (content uri for Your photo).
 * Colours are ARGB longs so this file needs no Android or Compose.
 */
object LivingLogic {
    /** v0.45.6: background animation frame step (ms): up to 60 fps (was 33 ms, 30 fps). Read in the draw phase only. */
    const val FRAME_MS = 15L
    enum class Bg(val id: String, val title: String, val sub: String, val moves: Boolean) {
        SKY("sky", "Your sky", "Your time and weather", true),
        AURORA("aurora", "Aurora", "Slow flowing colour", true),
        OCEAN("ocean", "Ocean", "Soft water ripples", true),
        PAPER("paper", "Calm paper", "Still, no motion", false),
        MOSAIC("mosaic", "Mosaic", "Gently drifting shapes", true),
        PHOTO("photo", "Your photo", "Pick one, it moves", true);
        companion object { fun of(id: String?): Bg = values().firstOrNull { it.id == id?.trim()?.lowercase() } ?: PAPER }
    }

    const val DEFAULT = "paper"

    enum class Phase { NIGHT, DAWN, MORNING, DAY, SUNSET, DUSK }

    /** The part of the day for a local hour (0..23) and minute. */
    fun phase(hour: Int, minute: Int = 0): Phase {
        val h = hour + minute / 60f
        return when {
            h < 5f -> Phase.NIGHT
            h < 7f -> Phase.DAWN
            h < 10f -> Phase.MORNING
            h < 17.5f -> Phase.DAY
            h < 19.5f -> Phase.SUNSET
            h < 21f -> Phase.DUSK
            else -> Phase.NIGHT
        }
    }

    /** Top-to-bottom sky colours for a phase (3 stops). */
    fun skyColors(p: Phase): LongArray = when (p) {
        Phase.NIGHT -> longArrayOf(0xFF05070F, 0xFF0B1230, 0xFF1A1F45)
        Phase.DAWN -> longArrayOf(0xFF27305E, 0xFF9C6B8E, 0xFFF2B48A)
        Phase.MORNING -> longArrayOf(0xFF7FB4E6, 0xFFB9D8F0, 0xFFF4E3C8)
        Phase.DAY -> longArrayOf(0xFF4F8FD8, 0xFF8DBDEB, 0xFFD9ECF8)
        Phase.SUNSET -> longArrayOf(0xFF3B3D7A, 0xFFE07A5F, 0xFFF6C177)
        Phase.DUSK -> longArrayOf(0xFF151A3E, 0xFF4B3A6E, 0xFFB86B77)
    }

    enum class Weather { CLEAR, CLOUDY, RAIN, STORM, SNOW, FOG, UNKNOWN }

    /** A Home weather widget's words ("Partly cloudy · feels 21° · …") to a sky mood. */
    fun weatherOf(label: String?): Weather {
        val t = label.orEmpty().substringBefore('\u00b7').trim().lowercase()
        return when {
            t.isEmpty() || t == "\u2014" -> Weather.UNKNOWN
            "thunder" in t -> Weather.STORM
            "snow" in t -> Weather.SNOW
            "rain" in t || "drizzle" in t || "shower" in t -> Weather.RAIN
            "fog" in t -> Weather.FOG
            "cloud" in t -> Weather.CLOUDY
            "clear" in t -> Weather.CLEAR
            else -> Weather.UNKNOWN
        }
    }

    /** How much grey the weather mixes into the sky (0 = none). */
    fun overcast(w: Weather): Float = when (w) {
        Weather.CLOUDY -> 0.25f; Weather.RAIN -> 0.4f; Weather.STORM -> 0.55f; Weather.FOG -> 0.45f; Weather.SNOW -> 0.3f
        else -> 0f
    }

    /** Mixes [a] toward [b] by [k] (0..1), per channel, alpha kept from [a]. */
    fun mix(a: Long, b: Long, k: Float): Long {
        val kk = k.coerceIn(0f, 1f)
        fun ch(v: Long, s: Int) = ((v shr s) and 0xFF).toInt()
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * kk).toInt().coerceIn(0, 255).toLong()
        return (a and 0xFF000000) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /** The sky for now: the phase's colours, greyed by the weather. */
    fun sky(hour: Int, minute: Int, w: Weather = Weather.UNKNOWN): LongArray {
        val base = skyColors(phase(hour, minute))
        val g = overcast(w)
        if (g == 0f) return base
        val grey = if (phase(hour, minute) == Phase.NIGHT) 0xFF20232B else 0xFF9AA3AE
        return LongArray(base.size) { mix(base[it], grey, g) }
    }

    /** True when the sky is dark enough that text should be light (Auto theme follows it). */
    fun skyIsDark(hour: Int, minute: Int = 0): Boolean = phase(hour, minute) in setOf(Phase.NIGHT, Phase.DUSK)

    /** Theme for Light / Dark / Auto: Auto = dark after sunset and before dawn. */
    fun darkFor(theme: String, hour: Int, systemDark: Boolean = true): Boolean = when (theme) {
        "light" -> false
        "dark" -> true
        "auto" -> phase(hour) in setOf(Phase.NIGHT, Phase.DUSK)
        else -> systemDark
    }

    /**
     * Whether the background may move: the user's choice, Save battery (keep it still), the phone's power saver or a
     * low battery (≤ 20 % and not charging), or system "remove animations".
     */
    fun moving(bg: Bg, keepStill: Boolean, powerSave: Boolean, batteryPct: Int, charging: Boolean, animationsOn: Boolean = true): Boolean =
        bg.moves && !keepStill && !powerSave && animationsOn && !(batteryPct in 0..20 && !charging)

    /** Pulse strength for Move with music: on only while Blaze Music plays; a gentle beat around 0.5 Hz–2 Hz. */
    fun musicPulse(playing: Boolean, enabled: Boolean, tSec: Double, bpm: Int = 96): Float {
        if (!playing || !enabled) return 0f
        val beat = (tSec * bpm / 60.0) % 1.0
        // A quick rise and a slow fall each beat, 0..1.
        val v = if (beat < 0.15) beat / 0.15 else 1.0 - (beat - 0.15) / 0.85
        return (v * v).toFloat().coerceIn(0f, 1f)
    }

    /** Accent presets for Settings › Look & feel ("" = keep the current accent). */
    val ACCENTS = listOf("" to "Current", "FF2F6BFF" to "Blue", "FF14B8A6" to "Teal", "FFF59E0B" to "Amber", "FFEC4899" to "Pink", "FF8B5CF6" to "Violet")

    fun parseAccent(hex: String?): Long? = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 8 }?.toLongOrNull(16)
}
