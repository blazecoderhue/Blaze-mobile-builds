package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.blaze.assistant.ui.LivingLogic.Weather as W

/** v0.45.2 themes: the list, the carousel's neighbour-tinted arrows, placement, the Weather theme and light / dark. */
class LivingThemesTest {
    private val names = listOf("Golden Ember", "Arctic Aurora", "Deep Sea", "Solar Flare", "Nebula", "Mono Ink", "Rose Dawn",
        "Firefly Forest", "Liquid Mercury", "Prism", "Desert Dune", "Neon Rain")

    @Test fun twelveParticleThemesPlusWeatherAndClassic() {
        assertEquals(names + "Weather", LivingThemes.THEMES.map { it.name })
        assertEquals(LivingThemes.THEMES.size + 1, LivingThemes.PICKER.size)
        assertEquals("Classic", LivingThemes.PICKER.last().name)
        assertEquals(LivingThemes.PICKER.size, LivingThemes.PICKER.map { it.id }.toSet().size)
        LivingThemes.THEMES.forEach { t -> assertNotEquals(LivingThemes.Motion.NONE, t.motion); assertTrue(t.particles.isNotEmpty()); assertEquals(3, t.ground.size) }
        assertEquals(LivingThemes.Motion.WEATHER, LivingThemes.of("weather").motion)
        assertEquals(LivingThemes.CLASSIC_THEME, LivingThemes.of("no-such-theme"))   // unknown / old prefs = Classic, no crash
        assertEquals(LivingThemes.CLASSIC_THEME, LivingThemes.of(""))
    }

    @Test fun arrowsTakeTheAccentOfTheThemeTheyLeadTo() {
        val l = LivingThemes.PICKER
        val firefly = l.indexOfFirst { it.name == "Firefly Forest" }
        // Rose Dawn is Firefly Forest's left neighbour: the left arrow is rose; the right one is Liquid Mercury's silver.
        assertEquals("Rose Dawn", LivingThemes.neighbour(l, firefly, -1).name)
        assertEquals(LivingThemes.of("rose_dawn").accent, LivingThemes.arrowTint(l, firefly, -1))
        assertEquals(LivingThemes.of("liquid_mercury").accent, LivingThemes.arrowTint(l, firefly, 1))
        // It loops: left of the first is the last (Classic), right of the last is the first.
        assertEquals(l.last().accent, LivingThemes.arrowTint(l, 0, -1))
        assertEquals(l.first().accent, LivingThemes.arrowTint(l, l.lastIndex, 1))
    }

    @Test fun frontAndDistanceWrap() {
        val n = LivingThemes.PICKER.size
        assertEquals(0, LivingThemes.frontOf(0.4f, n)); assertEquals(1, LivingThemes.frontOf(0.6f, n))
        assertEquals(n - 1, LivingThemes.frontOf(-1f, n)); assertEquals(0, LivingThemes.frontOf(n.toFloat(), n))
        assertEquals(1f, LivingThemes.distance(0, n - 1f, n), 1e-4f)    // the first card is right of the last
        assertEquals(-1f, LivingThemes.distance(n - 1, 0f, n), 1e-4f)
    }

    @Test fun cylinderPlacement() {
        val f = LivingThemes.place(0f)
        assertEquals(0f, f.rotationY, 1e-4f); assertEquals(1f, f.scale, 1e-4f); assertEquals(1f, f.alpha, 1e-4f)
        val l = LivingThemes.place(-1f); val r = LivingThemes.place(1f)
        assertEquals(-l.rotationY, r.rotationY, 1e-4f); assertEquals(-l.shift, r.shift, 1e-4f)   // symmetric, turned away
        assertTrue(r.rotationY != 0f && r.scale < 1f && r.alpha < 1f && r.visible)
        assertFalse(LivingThemes.place(3f).visible)
    }

    @Test fun accentDrivesTheAppAndReadsOnLight() {
        assertEquals(LivingThemes.of("neon_rain").accent, LivingThemes.accentFor("neon_rain", 0xFF123456))
        assertEquals(0xFF123456, LivingThemes.accentFor("", 0xFF123456))
        assertEquals(null, LivingThemes.accentFor("", null))
        val mono = LivingThemes.of("mono_ink").accent
        assertNotEquals(mono, LivingThemes.onLight(mono))                    // pale accent deepened on light
        assertEquals(0xFF2F2F8F, LivingThemes.onLight(0xFF2F2F8F))           // dark accent kept
        assertEquals("light", LivingThemes.modeAfterPick(LivingThemes.of("rose_dawn"), "dark"))
        assertEquals("dark", LivingThemes.modeAfterPick(LivingThemes.of("nebula"), "dark"))
    }

    @Test fun longPressTogglesLightAndDark() {
        assertEquals("light" to "Light theme", LightDark.toggle(currentlyDark = true))
        assertEquals("dark" to "Dark theme", LightDark.toggle(currentlyDark = false))
        var dark = true
        repeat(3) { dark = LightDark.toggle(dark).first == "dark" }
        assertFalse(dark)
        assertTrue(LightDark.MIN_MS in 400..500 && LightDark.MAX_MS in 400..500)
    }

    @Test fun weatherThemeStates() {
        assertEquals(WeatherLook.State.HEATWAVE, WeatherLook.state(W.CLEAR, 38, false, 14))
        assertEquals(WeatherLook.State.SNOW_NIGHT, WeatherLook.state(W.SNOW, -2, false, 23))
        assertEquals(WeatherLook.State.RAIN, WeatherLook.state(W.RAIN, 18, false, 11))
        assertEquals(WeatherLook.State.THUNDERSTORM, WeatherLook.state(W.STORM, 24, true, 16))
        assertEquals(WeatherLook.State.FOG_DAWN, WeatherLook.state(W.FOG, 9, false, 6))
        assertEquals(WeatherLook.State.WINDY_AUTUMN, WeatherLook.state(W.CLOUDY, 14, true, 15))
        assertEquals(WeatherLook.State.STARRY_NIGHT, WeatherLook.state(W.CLEAR, 15, false, 1))
        assertEquals(WeatherLook.State.TROPICAL_SUNSET, WeatherLook.state(W.CLEAR, 27, false, 18, 30))
        // Offline / no data: a default by time of day, never a crash.
        assertEquals(WeatherLook.State.STARRY_NIGHT, WeatherLook.state(W.UNKNOWN, null, false, 2))
        assertEquals(WeatherLook.State.CLEAR_DAY, WeatherLook.state(W.UNKNOWN, null, false, 12))
        WeatherLook.State.values().forEach { assertTrue(it.label.isNotBlank()) }
        // Temperature sets the colour.
        assertNotEquals(WeatherLook.tempColor(-5), WeatherLook.tempColor(36))
        val look = WeatherLook.look(W.UNKNOWN, null, false, 12)
        assertEquals(3, look.ground.size)
    }

    @Test fun weatherWordsParse() {
        assertEquals(24, WeatherLook.tempOf("Sunny · 24°"))
        assertEquals(-3, WeatherLook.tempOf("-3 °C"))
        assertEquals(24, WeatherLook.tempOf("75°F"))
        assertEquals(null, WeatherLook.tempOf("—"))
        assertTrue(WeatherLook.windy("Cloudy · gusts 50 km/h"))
        assertTrue(WeatherLook.windy("wind 35 km/h"))
        assertFalse(WeatherLook.windy("Clear · 10 km/h"))
    }

    @Test fun profileMenuHasThemesAndNoLightRow() {
        val src = java.io.File("src/main/java/com/blaze/assistant/ui/ChatScreen.kt").readText()
        assertTrue(src.contains("MenuItem(\"themes\", \"Themes\""))
        assertFalse(src.contains("MenuItem(\"theme\", if (dark) \"Light theme\""))
        assertTrue(src.contains("onAvatarLong"))
        assertTrue(java.io.File("src/main/java/com/blaze/assistant/ui/Shell.kt").readText().contains("onLongClick = onAvatarLong"))
        // Light / dark is still in Settings › Look & feel.
        assertTrue(java.io.File("src/main/java/com/blaze/assistant/ui/SimplePages.kt").readText().contains("\"light\" to \"Light\", \"dark\" to \"Dark\""))
    }
}
