package com.blaze.assistant.ui

/**
 * v0.45.2 themes, pure part (LivingThemesTest). The twelve Living Particles themes with the Blaze droplet + halo, the
 * immersive Weather theme, and "Classic" (the look as it was: no living theme, the background from Look & feel).
 * Each theme is a living background (ground gradient, particle colours, a motion style) plus the halo and droplet tint
 * and the accent colour that drives the app's accents. Colours are ARGB longs so this file needs no Android or Compose.
 *
 * Pref: `look_theme` (theme id, "" = Classic). The picker (ThemePicker) shows them on a 3D carousel; its arrow buttons
 * are tinted with the accent of the theme they lead to ([arrowTint]).
 */
object LivingThemes {
    enum class Motion {
        /** Classic: nothing drawn here (the old background decides). */ NONE,
        EMBERS, AURORA, BUBBLES, FLARE, NEBULA, INK, PETALS, FIREFLIES, MERCURY, PRISM, DUNE, RAIN,
        /** The Weather theme: the motion follows the weather ([WeatherLook]). */ WEATHER
    }

    data class Theme(
        val id: String, val name: String, val accent: Long,
        /** Ground gradient, top to bottom (dark version). */
        val ground: LongArray,
        /** Particle colours. */
        val particles: LongArray,
        val halo: Long, val droplet: Long, val motion: Motion,
        /** The theme reads best in light mode (picking it switches to light; long-press the profile icon flips it). */
        val light: Boolean = false,
        val sub: String = "",
    )

    const val CLASSIC = ""
    const val WEATHER = "weather"

    val CLASSIC_THEME = Theme(CLASSIC, "Classic", 0xFF4DA8FF, longArrayOf(0xFF0B0B0D, 0xFF111114, 0xFF0B0B0D), longArrayOf(0xFFFFFFFF),
        0xFFFFC94D, 0xFF4DA8FF, Motion.NONE, sub = "The look you had, with your Look & feel background")

    val THEMES: List<Theme> = listOf(
        Theme("golden_ember", "Golden Ember", 0xFFF2B35B, longArrayOf(0xFF0B1012, 0xFF1A1410, 0xFF2A1A0E), longArrayOf(0xFFFFC56B, 0xFFFF8A3D, 0xFFFFE3A8),
            0xFFFFC94D, 0xFFFFD58A, Motion.EMBERS, sub = "Warm sparks rising"),
        Theme("arctic_aurora", "Arctic Aurora", 0xFF6FD8D2, longArrayOf(0xFF07101E, 0xFF0D1C30, 0xFF122640), longArrayOf(0xFF6FE7C8, 0xFF62B8FF, 0xFFB79CFF),
            0xFF9EF0E0, 0xFFBDEBFF, Motion.AURORA, sub = "Cold light in slow curtains"),
        Theme("deep_sea", "Deep Sea", 0xFF2EC4DD, longArrayOf(0xFF03121A, 0xFF062433, 0xFF041820), longArrayOf(0xFF39D5F0, 0xFF1C8FB3, 0xFF9BF1FF),
            0xFF5FE0F5, 0xFF7FDDF0, Motion.BUBBLES, sub = "Bubbles drifting up from the deep"),
        Theme("solar_flare", "Solar Flare", 0xFFFF7A3D, longArrayOf(0xFF0E1214, 0xFF2A1308, 0xFF40160A), longArrayOf(0xFFFF6A1A, 0xFFFFB347, 0xFFFF3D2E),
            0xFFFFA24D, 0xFFFFC08A, Motion.FLARE, sub = "Bursts thrown out from the sun"),
        Theme("nebula", "Nebula", 0xFFB06AD9, longArrayOf(0xFF05070F, 0xFF140A22, 0xFF1E0B26), longArrayOf(0xFFB06AD9, 0xFFFF6FA8, 0xFF6A8BFF),
            0xFFE39BFF, 0xFFD7B8FF, Motion.NEBULA, sub = "Star dust turning slowly"),
        Theme("mono_ink", "Mono Ink", 0xFFE6E6E6, longArrayOf(0xFF070707, 0xFF0E0E0E, 0xFF151515), longArrayOf(0xFFFFFFFF, 0xFFBDBDBD, 0xFF7A7A7A),
            0xFFDADADA, 0xFFF2F2F2, Motion.INK, sub = "Ink clouds in black and white"),
        Theme("rose_dawn", "Rose Dawn", 0xFFC2697E, longArrayOf(0xFF241018, 0xFF3A1A26, 0xFF4A2230), longArrayOf(0xFFB85A72, 0xFFE8A3B3, 0xFF8E3A55),
            0xFFF3B9C4, 0xFFE9B3BE, Motion.PETALS, light = true, sub = "Soft petals at first light"),
        Theme("firefly_forest", "Firefly Forest", 0xFFB7D36B, longArrayOf(0xFF070B09, 0xFF0D1710, 0xFF122016), longArrayOf(0xFFD8F27A, 0xFFF6E27A, 0xFF8FCB6B),
            0xFFE2F29A, 0xFFCDE8A0, Motion.FIREFLIES, sub = "Fireflies blinking in the dark"),
        Theme("liquid_mercury", "Liquid Mercury", 0xFFC4CBD2, longArrayOf(0xFF0E1216, 0xFF1A2026, 0xFF262D34), longArrayOf(0xFFE6ECF2, 0xFF9AA6B2, 0xFFFFFFFF),
            0xFFE2E8EE, 0xFFD5DCE3, Motion.MERCURY, sub = "Silver beads that flow and merge"),
        Theme("prism", "Prism", 0xFF8FA8FF, longArrayOf(0xFF0C0E13, 0xFF13161E, 0xFF1A1E28), longArrayOf(0xFFFF5F6D, 0xFFFFC371, 0xFF7CF29A, 0xFF5FB3FF, 0xFFC17BFF),
            0xFFFFFFFF, 0xFFDDE6FF, Motion.PRISM, sub = "Light split into every colour"),
        Theme("desert_dune", "Desert Dune", 0xFFE39A4F, longArrayOf(0xFF1E140C, 0xFF3A2614, 0xFF5A3A1C), longArrayOf(0xFFF2C38B, 0xFFE39A4F, 0xFFFFE2B8),
            0xFFF6CF94, 0xFFF2C79A, Motion.DUNE, light = true, sub = "Sand carried on the wind"),
        Theme("neon_rain", "Neon Rain", 0xFFD25CE0, longArrayOf(0xFF071014, 0xFF0C1620, 0xFF120E22), longArrayOf(0xFFD25CE0, 0xFF3FE0FF, 0xFFFF4FA3),
            0xFFFF7BE9, 0xFF8FEFFF, Motion.RAIN, sub = "Neon streaks falling at night"),
        Theme(WEATHER, "Weather", 0xFF6FB7FF, longArrayOf(0xFF0A1424, 0xFF16304E, 0xFF2A4C70), longArrayOf(0xFFFFFFFF, 0xFFBFE3FF),
            0xFFFFC94D, 0xFF4DA8FF, Motion.WEATHER, sub = "Your temperature, weather and time of day"),
    )

    /** What the picker shows, in order (Classic last, so the first card is a new theme). */
    val PICKER: List<Theme> = THEMES + CLASSIC_THEME

    fun of(id: String?): Theme = PICKER.firstOrNull { it.id == id?.trim() } ?: CLASSIC_THEME

    fun indexOf(id: String?): Int = PICKER.indexOfFirst { it.id == id?.trim() }.let { if (it < 0) PICKER.lastIndex else it }

    /** Index [steps] away from [i] on a loop of [n] (the carousel wraps). */
    fun wrap(i: Int, n: Int): Int = ((i % n) + n) % n

    /** The theme [dir] (-1 left, +1 right) from the front card [front]. */
    fun neighbour(list: List<Theme>, front: Int, dir: Int): Theme = list[wrap(front + dir, list.size)]

    /** The arrow on that side is tinted with the accent of the theme it leads to. */
    fun arrowTint(list: List<Theme>, front: Int, dir: Int): Long = neighbour(list, front, dir).accent

    /** The front card for a fractional carousel position (rounded, wrapped). */
    fun frontOf(pos: Float, n: Int): Int = wrap(kotlin.math.round(pos).toInt(), n)

    /**
     * 3D cylinder placement of a card [d] slots from the front (fractional while swiping): rotationY (degrees), the
     * horizontal shift as a fraction of the card width, scale and alpha. The front card faces the user; neighbours turn
     * away and shrink; cards more than 2 slots away are hidden.
     */
    data class Place(val rotationY: Float, val shift: Float, val scale: Float, val alpha: Float, val visible: Boolean)

    fun place(d: Float): Place {
        val a = d.coerceIn(-2.5f, 2.5f)
        val angle = a * 38f
        val rad = Math.toRadians(angle.toDouble())
        val shift = (kotlin.math.sin(rad) * 0.95).toFloat()
        val scale = (0.78 + 0.22 * kotlin.math.cos(rad)).toFloat()
        val alpha = (1f - kotlin.math.abs(a) * 0.32f).coerceIn(0f, 1f)
        return Place(-angle, shift, scale, alpha, kotlin.math.abs(d) < 2.4f)
    }

    /** Signed distance from [pos] to card [i] on a loop of [n] (shortest way round). */
    fun distance(i: Int, pos: Float, n: Int): Float {
        var d = i - pos
        while (d > n / 2f) d -= n
        while (d < -n / 2f) d += n
        return d
    }

    /** The accent the app uses: the theme's, unless Classic (then the Look & feel accent, or none). */
    fun accentFor(themeId: String, lookAccent: Long?): Long? = if (themeId.isBlank()) lookAccent else of(themeId).accent

    /** An accent deep enough to read on a light ground (pale accents are mixed toward black). */
    fun onLight(a: Long): Long {
        val r = (a shr 16) and 0xFF; val g = (a shr 8) and 0xFF; val b = a and 0xFF
        val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        return if (lum > 0.55) LivingLogic.mix(a, 0xFF000000, ((lum - 0.4) * 1.2).toFloat().coerceIn(0f, 0.7f)) else a
    }

    /** Light/dark after picking [t]: its preferred mode for light themes, otherwise unchanged. */
    fun modeAfterPick(t: Theme, current: String): String = if (t.light) "light" else if (current == "light" && t.motion != Motion.NONE) "dark" else current
}

/** v0.45.2: long-press on the profile icon flips light / dark (pure: the new mode and the short message). */
object LightDark {
    fun toggle(currentlyDark: Boolean): Pair<String, String> = if (currentlyDark) "light" to "Light theme" else "dark" to "Dark theme"
    /** The long-press window the profile icon uses (the system long-press timeout, typically 400–500 ms). */
    const val MIN_MS = 400L
    const val MAX_MS = 500L
}

/**
 * v0.45.2 the immersive Weather theme, pure part: temperature sets the colour, the weather sets the motion and the
 * local time of day sets the light. Works without data (a sensible default for the time of day) and never throws.
 */
object WeatherLook {
    enum class State(val label: String) {
        HEATWAVE("Heatwave"), SNOW_NIGHT("Snow night"), RAIN("Rain"), THUNDERSTORM("Thunderstorm"), FOG_DAWN("Fog dawn"),
        WINDY_AUTUMN("Windy autumn"), STARRY_NIGHT("Starry night"), TROPICAL_SUNSET("Tropical sunset"), CLEAR_DAY("Clear day")
    }

    enum class Fx { NONE, SHIMMER, SNOW, RAIN, LIGHTNING, FOG, LEAVES, STARS, SUN_GLOW }

    data class Look(val state: State, val fx: Fx, val ground: LongArray, val particle: Long, val accent: Long, val light: Float)

    /** "24°" / "24 °C" / "-3°" / "75°F" in a widget's words → °C, or null. */
    fun tempOf(text: String?): Int? {
        val m = Regex("""(-?\d{1,3})\s*°\s*([CF])?""", RegexOption.IGNORE_CASE).find(text.orEmpty()) ?: return null
        val v = m.groupValues[1].toIntOrNull() ?: return null
        return if (m.groupValues[2].equals("F", true)) Math.round((v - 32) * 5 / 9.0).toInt() else v
    }

    /** Wind words in the weather line ("windy", "gusts", "breeze", "20 km/h"…). */
    fun windy(text: String?): Boolean {
        val t = text.orEmpty().lowercase()
        if (Regex("""\b(windy|gust\w*|gale|breez\w*|blustery)\b""").containsMatchIn(t)) return true
        val kmh = Regex("""(\d{2,3})\s*km/?h""").find(t)?.groupValues?.get(1)?.toIntOrNull()
        return kmh != null && kmh >= 30
    }

    /** Colour for a temperature: cold blue → mild teal → warm amber → hot red-orange. */
    fun tempColor(c: Int?): Long = when {
        c == null -> 0xFF6FB7FF
        c <= 0 -> 0xFF9FC9FF
        c <= 10 -> 0xFF6FB7FF
        c <= 20 -> 0xFF5FD3B5
        c <= 28 -> 0xFFF2B35B
        c <= 34 -> 0xFFFF8A3D
        else -> 0xFFFF5A2E
    }

    /**
     * The state for the weather [w] (LivingLogic.Weather), temperature [tempC] (null = unknown), wind and the local
     * [hour]. Offline / no data: night → starry night, dusk/sunset → tropical sunset, dawn → fog dawn, else clear day.
     */
    fun state(w: LivingLogic.Weather, tempC: Int?, wind: Boolean, hour: Int, minute: Int = 0): State {
        val ph = LivingLogic.phase(hour, minute)
        val night = ph == LivingLogic.Phase.NIGHT || ph == LivingLogic.Phase.DUSK
        return when {
            w == LivingLogic.Weather.STORM -> State.THUNDERSTORM
            w == LivingLogic.Weather.SNOW || (tempC != null && tempC <= 0 && night) -> State.SNOW_NIGHT
            w == LivingLogic.Weather.RAIN -> State.RAIN
            w == LivingLogic.Weather.FOG -> State.FOG_DAWN
            tempC != null && tempC >= 35 -> State.HEATWAVE
            wind -> State.WINDY_AUTUMN
            night -> State.STARRY_NIGHT
            ph == LivingLogic.Phase.SUNSET -> State.TROPICAL_SUNSET
            ph == LivingLogic.Phase.DAWN && w == LivingLogic.Weather.CLOUDY -> State.FOG_DAWN
            tempC != null && tempC >= 30 && w != LivingLogic.Weather.CLOUDY -> State.HEATWAVE
            else -> State.CLEAR_DAY
        }
    }

    fun look(w: LivingLogic.Weather, tempC: Int?, wind: Boolean, hour: Int, minute: Int = 0): Look {
        val s = state(w, tempC, wind, hour, minute)
        val sky = LivingLogic.sky(hour, minute, w)
        val tc = tempColor(tempC)
        val (fx, ground, light) = when (s) {
            State.HEATWAVE -> Triple(Fx.SHIMMER, longArrayOf(0xFF3A1A0C, 0xFF8A3A16, 0xFFE07A3A), 1f)
            State.SNOW_NIGHT -> Triple(Fx.SNOW, longArrayOf(0xFF0A1226, 0xFF16244A, 0xFF2C3E66), 0.25f)
            State.RAIN -> Triple(Fx.RAIN, LongArray(3) { LivingLogic.mix(sky[it], 0xFF2A3640, 0.55f) }, 0.5f)
            State.THUNDERSTORM -> Triple(Fx.LIGHTNING, longArrayOf(0xFF0B1220, 0xFF1A2236, 0xFF2A2F48), 0.3f)
            State.FOG_DAWN -> Triple(Fx.FOG, LongArray(3) { LivingLogic.mix(sky[it], 0xFFB9BCC6, 0.55f) }, 0.7f)
            State.WINDY_AUTUMN -> Triple(Fx.LEAVES, longArrayOf(0xFF2A1A12, 0xFF6A3A1C, 0xFFB8743A), 0.7f)
            State.STARRY_NIGHT -> Triple(Fx.STARS, longArrayOf(0xFF05070F, 0xFF0B1230, 0xFF1A1F45), 0.15f)
            State.TROPICAL_SUNSET -> Triple(Fx.SUN_GLOW, longArrayOf(0xFF3A0E2A, 0xFFB8405A, 0xFFF29A5A), 0.6f)
            State.CLEAR_DAY -> Triple(Fx.SUN_GLOW, sky, 1f)
        }
        val particle = when (fx) { Fx.SNOW, Fx.STARS, Fx.RAIN, Fx.FOG -> 0xFFFFFFFF; Fx.LEAVES -> 0xFFE8873A; else -> tc }
        return Look(s, fx, ground, particle, tc, light)
    }
}

/**
 * v0.45.2: the current theme's droplet / halo tint as Compose state, so the Blaze orb and its halo (DuoColors) redraw in
 * the theme's colours. Null = Classic (blue drop, gold halo, as before).
 */
object ThemeTint {
    val current = androidx.compose.runtime.mutableStateOf<LivingThemes.Theme?>(null)
    fun set(id: String) { current.value = LivingThemes.of(id).takeIf { it.motion != LivingThemes.Motion.NONE } }
    init { try { set(com.blaze.assistant.agent.Settings.str("look_theme", LivingThemes.CLASSIC)) } catch (_: Throwable) { } }
}

/**
 * v0.45.3: the Blaze Site's scene pictures behind each theme (assets/theme-scenes/<scene>.webp, 1376x768). Every
 * Living Particles theme has the scene of the same name; Weather picks one from the weather and the time of day;
 * Classic has none. Pure (ThemeScenesTest checks every scene is bundled).
 */
object ThemeScenes {
    const val DIR = "theme-scenes"

    /** Every bundled scene (file name without .webp). */
    val ALL = listOf("arctic_aurora", "deep_sea", "desert_dune", "firefly_forest", "golden_ember", "lagoon", "liquid_mercury",
        "mono_ink", "nebula", "neon_rain", "prism", "rose_dawn", "solar_flare")

    /** Theme ids that differ from their scene's name (none today; kept so a renamed theme still finds its picture). */
    private val ALIAS = mapOf<String, String>()

    fun path(scene: String) = "$DIR/$scene.webp"

    /** The scene for a picker theme ([weather] only for the Weather theme); null = no picture (Classic). */
    fun of(themeId: String, weather: WeatherLook.State? = null): String? = when (themeId) {
        LivingThemes.CLASSIC -> null
        LivingThemes.WEATHER -> forWeather(weather ?: WeatherLook.State.CLEAR_DAY)
        else -> (ALIAS[themeId] ?: themeId).takeIf { it in ALL }
    }

    /** A fitting scene for the weather: sun → solar flare / dunes, night → nebula, rain → neon rain, snow → aurora, fog → mercury… */
    fun forWeather(s: WeatherLook.State): String = when (s) {
        WeatherLook.State.HEATWAVE -> "desert_dune"
        WeatherLook.State.CLEAR_DAY -> "solar_flare"
        WeatherLook.State.SNOW_NIGHT -> "arctic_aurora"
        WeatherLook.State.RAIN -> "neon_rain"
        WeatherLook.State.THUNDERSTORM -> "neon_rain"
        WeatherLook.State.FOG_DAWN -> "liquid_mercury"
        WeatherLook.State.WINDY_AUTUMN -> "golden_ember"
        WeatherLook.State.STARRY_NIGHT -> "nebula"
        WeatherLook.State.TROPICAL_SUNSET -> "lagoon"
    }

    /** Cloudy days (no rain) read as mercury rather than sun. */
    fun forWeather(s: WeatherLook.State, w: LivingLogic.Weather): String =
        if (s == WeatherLook.State.CLEAR_DAY && w == LivingLogic.Weather.CLOUDY) "liquid_mercury" else forWeather(s)

    /**
     * The scrim over a scene so text keeps AA contrast: alpha of the page colour (dark: near-black, light: near-white)
     * drawn over the picture. Picture luminance is unknown here, so the values are chosen for the worst case of these
     * scenes (bright highlights) together with the glass bubbles: 0.55 dark, 0.62 light. Cards in the picker use less.
     */
    fun scrim(dark: Boolean, card: Boolean = false): Float = when {
        card -> if (dark) 0.18f else 0.30f
        dark -> 0.55f
        else -> 0.62f
    }

    /**
     * v0.45.7: brightness of each bundled scene as sRGB grey (darkest 1 %, brightest 1 %), measured from the webp files
     * (200 px thumbnails, Rec. 709 luminance). The veil is sized from these so text stays AA on the scene's worst spots.
     */
    val TONE: Map<String, Pair<Float, Float>> = mapOf(
        "arctic_aurora" to (0.024f to 0.775f), "deep_sea" to (0.123f to 0.815f), "desert_dune" to (0.118f to 0.806f),
        "firefly_forest" to (0.006f to 0.451f), "golden_ember" to (0.010f to 0.386f), "lagoon" to (0.328f to 0.947f),
        "liquid_mercury" to (0.169f to 0.848f), "mono_ink" to (0.035f to 0.941f), "nebula" to (0.069f to 0.522f),
        "neon_rain" to (0.027f to 0.818f), "prism" to (0.023f to 0.893f), "rose_dawn" to (0.198f to 0.963f),
        "solar_flare" to (0.011f to 0.688f),
    )
    /** The veil at Background strength 0 (the scene only just shows). */
    const val MAX_SCRIM = 0.92f
    /** Settings › Look & feel › Background strength, default: the scene a little dimmer than the AA minimum. */
    const val DEFAULT_STRENGTH = 0.7f

    /** inSampleSize for decoding a [w]x[h] picture to cover [reqW]x[reqH] (power of two, never below the target). */
    fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        if (w <= 0 || h <= 0 || reqW <= 0 || reqH <= 0) return 1
        // Cover: the picture must stay at least as big as the target on the side that is cropped least.
        val scale = maxOf(reqW.toFloat() / w, reqH.toFloat() / h)
        var s = 1
        while (scale * s * 2 <= 1f) s *= 2
        return s
    }

    /** The source rectangle (left, top, right, bottom) that centre-crops a [w]x[h] picture to the [dw]x[dh] aspect. */
    fun cropRect(w: Int, h: Int, dw: Float, dh: Float): IntArray {
        if (w <= 0 || h <= 0 || dw <= 0f || dh <= 0f) return intArrayOf(0, 0, maxOf(w, 0), maxOf(h, 0))
        val target = dw / dh
        val src = w.toFloat() / h
        return if (src > target) {
            val cw = (h * target).toInt().coerceIn(1, w); val l = (w - cw) / 2
            intArrayOf(l, 0, l + cw, h)
        } else {
            val ch = (w / target).toInt().coerceIn(1, h); val t = (h - ch) / 2
            intArrayOf(0, t, w, t + ch)
        }
    }
}
