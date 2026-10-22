package com.blaze.assistant.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.45.3: every picker theme except Classic and Weather has a bundled scene picture; Weather picks one; Classic none. */
class ThemeScenesTest {
    private fun asset(p: String): File = listOf(File("src/main/assets/$p"), File("app/src/main/assets/$p")).first { it.exists() || it.path.startsWith("app") }

    @Test fun everyThemeResolvesToABundledImage() {
        for (t in LivingThemes.PICKER) {
            if (t.id == LivingThemes.CLASSIC || t.id == LivingThemes.WEATHER) continue
            val s = ThemeScenes.of(t.id)
            assertNotNull("${t.id} has no scene", s)
            val f = asset(ThemeScenes.path(s!!))
            assertTrue("${f.path} missing", f.isFile)
            assertTrue("${f.path} too small to be a full-size scene", f.length() > 20_000)
            val head = f.readBytes().copyOfRange(0, 12)
            assertEquals("RIFF", String(head, 0, 4)); assertEquals("WEBP", String(head, 8, 4))
        }
        assertNull(ThemeScenes.of(LivingThemes.CLASSIC))
    }

    @Test fun everySceneIsBundledAndNoThumbs() {
        for (s in ThemeScenes.ALL) assertTrue(s, asset(ThemeScenes.path(s)).isFile)
        val dir = asset(ThemeScenes.DIR)
        assertTrue(dir.listFiles()!!.none { it.name.contains("thumb") })
    }

    @Test fun weatherPicksAFittingScene() {
        assertEquals("solar_flare", ThemeScenes.of(LivingThemes.WEATHER, WeatherLook.State.CLEAR_DAY))
        assertEquals("desert_dune", ThemeScenes.forWeather(WeatherLook.State.HEATWAVE))
        assertEquals("nebula", ThemeScenes.forWeather(WeatherLook.State.STARRY_NIGHT))
        assertEquals("neon_rain", ThemeScenes.forWeather(WeatherLook.State.RAIN))
        assertEquals("arctic_aurora", ThemeScenes.forWeather(WeatherLook.State.SNOW_NIGHT))
        assertEquals("liquid_mercury", ThemeScenes.forWeather(WeatherLook.State.CLEAR_DAY, LivingLogic.Weather.CLOUDY))
        for (st in WeatherLook.State.values()) assertTrue(ThemeScenes.forWeather(st) in ThemeScenes.ALL)
        assertNotNull(ThemeScenes.of(LivingThemes.WEATHER))
    }

    @Test fun decodeMath() {
        assertEquals(1, ThemeScenes.sampleSize(1376, 768, 1080, 2400))
        assertEquals(2, ThemeScenes.sampleSize(1376, 768, 300, 300))
        assertEquals(4, ThemeScenes.sampleSize(1376, 768, 200, 150))
        assertEquals(1, ThemeScenes.sampleSize(0, 0, 10, 10))
        // Portrait card from a landscape picture: the middle is kept, full height.
        val r = ThemeScenes.cropRect(1376, 768, 500f, 1000f)
        assertEquals(0, r[1]); assertEquals(768, r[3]); assertEquals(384, r[2] - r[0]); assertEquals((1376 - 384) / 2, r[0])
        assertArrayEquals(intArrayOf(0, 0, 1376, 768), ThemeScenes.cropRect(1376, 768, 1376f, 768f))
        assertTrue(ThemeScenes.scrim(true) >= 0.5f && ThemeScenes.scrim(false) >= 0.5f)
    }
}
