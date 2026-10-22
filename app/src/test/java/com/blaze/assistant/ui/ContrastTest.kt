package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.45.7 readability: every picker theme x its scene x light/dark x Background strength keeps body text at WCAG AA
 * 4.5:1 and secondary text / icons at 3:1 on the scene's darkest and brightest 1 % (ThemeScenes.TONE, measured from the
 * bundled pictures). Chips are opaque with a 3:1 border. The sheet veil fades out instead of ending in a hard band.
 *
 * What this does NOT cover: real rendered pixels (ShotsTest can't render on Linux aarch64), particles drawn over the
 * veil, and user photos (Look & feel › Photo, not a theme scene).
 */
class ContrastTest {
    private val palettes = listOf("light" to LightColors, "dark" to DarkColors)

    @Test fun mathMatchesWcag() {
        assertEquals(21.0, Contrast.ratio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, Contrast.ratio(Color.Red, Color.Red), 0.0001)
        assertEquals(4.54, Contrast.ratio(Color(0xFF767676), Color.White), 0.02)
        val half = Contrast.over(Color.Black.copy(alpha = 0.5f), Color.White)
        assertEquals(0.5f, half.red, 0.01f); assertEquals(1f, half.alpha, 0f)
    }

    @Test fun everySceneIsMeasured() {
        for (s in ThemeScenes.ALL) assertTrue("$s has no TONE", s in ThemeScenes.TONE)
        for ((s, t) in ThemeScenes.TONE) assertTrue(s, t.first in 0f..1f && t.second in 0f..1f && t.first < t.second)
    }

    @Test fun everyThemeAndSceneKeepsTextAaAtEveryStrength() {
        val scenes = (LivingThemes.PICKER.mapNotNull { ThemeScenes.of(it.id) } + WeatherLook.State.values().map { ThemeScenes.forWeather(it) } + ThemeScenes.ALL).distinct()
        var checked = 0
        for (scene in scenes) for ((name, c) in palettes) for (step in 0..10) {
            val st = step / 10f
            val a = SceneVeil.alpha(scene, c.paper, c.body, c.sub, c.dark, st)
            val (lo, hi) = ThemeScenes.TONE.getValue(scene)
            for (g in listOf(lo, hi)) {
                val bg = Contrast.grey(g)
                assertTrue("$scene/$name/st=$st body", Contrast.onScene(c.body, bg, c.paper, a) >= Contrast.AA_TEXT)
                assertTrue("$scene/$name/st=$st ink", Contrast.onScene(c.ink, bg, c.paper, a) >= Contrast.AA_TEXT)
                assertTrue("$scene/$name/st=$st sub", Contrast.onScene(c.sub, bg, c.paper, a) >= Contrast.AA_UI)
            }
            checked++
        }
        assertTrue(checked >= 13 * 2 * 11)
    }

    @Test fun theSliderOnlyAddsVeil() {
        for ((_, c) in palettes) for (scene in ThemeScenes.ALL) {
            val aa = SceneVeil.aa(scene, c.paper, c.body, c.sub, c.dark)
            assertEquals(aa, SceneVeil.alpha(scene, c.paper, c.body, c.sub, c.dark, 1f), 0.0001f)
            assertEquals(ThemeScenes.MAX_SCRIM, SceneVeil.alpha(scene, c.paper, c.body, c.sub, c.dark, 0f), 0.0001f)
            assertEquals(aa, SceneVeil.alpha(scene, c.paper, c.body, c.sub, c.dark, 5f), 0.0001f)
            var prev = 2f
            for (s in 0..10) { val v = SceneVeil.alpha(scene, c.paper, c.body, c.sub, c.dark, s / 10f); assertTrue(v <= prev); prev = v }
        }
    }

    @Test fun theOldFixedVeilFailedSomeScenes() {
        // Before 0.45.7 the veil was 0.55 (dark) / 0.62 (light) for every scene; these fell below AA (e.g. lagoon in dark).
        val c = DarkColors
        val (lo, hi) = ThemeScenes.TONE.getValue("lagoon")
        val worst = minOf(Contrast.onScene(c.body, Contrast.grey(lo), c.paper, 0.55f), Contrast.onScene(c.body, Contrast.grey(hi), c.paper, 0.55f))
        val subWorst = minOf(Contrast.onScene(c.sub, Contrast.grey(lo), c.paper, 0.55f), Contrast.onScene(c.sub, Contrast.grey(hi), c.paper, 0.55f))
        assertFalse(worst >= Contrast.AA_TEXT && subWorst >= Contrast.AA_UI)
    }

    @Test fun chipsAreSolidWithAVisibleBorder() {
        for ((name, c) in palettes) {
            val fill = ChipStyle.fill(c)
            assertEquals("$name fill opaque", 1f, fill.alpha, 0f)
            assertTrue("$name chip text", Contrast.ratio(Contrast.over(c.accent, fill), fill) >= Contrast.AA_TEXT)
            assertTrue("$name chip border", Contrast.ratio(ChipStyle.edge(c), fill) >= Contrast.AA_UI)
        }
    }

    @Test fun sheetVeilHasNoHardEdge() {
        val stops = SheetVeil.stops(Color.Black)
        assertEquals(0f, stops.last().second.alpha, 0f)
        assertEquals(1f, stops.last().first, 0f)
        assertTrue(stops.first().second.alpha <= 0.12f)
        assertTrue(stops[1].first <= 0.75f)
    }

    @Test fun secretFieldsAndSyncSwitch() {
        assertTrue(SecretField.masked(secret = true, reveal = false))
        assertFalse(SecretField.masked(secret = true, reveal = true))
        assertFalse(SecretField.masked(secret = false, reveal = false))
        assertEquals("Show password", SecretField.label(false)); assertEquals("Hide password", SecretField.label(true))
        // Before a passphrase is set: the switch is off while the label says off (it used to show ON).
        val pending = CloudSyncSwitch.view(on = false, setupOpen = true, needsPassphrase = false, statusLine = "")
        assertFalse(pending.checked); assertTrue(pending.label.startsWith("Off"))
        assertEquals(CloudSyncSwitch.View(false, CloudSyncSwitch.OFF), CloudSyncSwitch.view(false, false, false, ""))
        assertEquals(CloudSyncSwitch.View(true, "Synced 2 min ago"), CloudSyncSwitch.view(true, false, false, "Synced 2 min ago"))
        assertEquals(CloudSyncSwitch.View(true, "Enter your sync passphrase to keep syncing."), CloudSyncSwitch.view(true, false, true, "Enter your sync passphrase to keep syncing."))
    }

    @Test fun everyMaskedFieldHasARevealToggle() {
        // Source scan: each PasswordVisualTransformation() is behind a reveal / show state (no field without an eye).
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (l.contains("PasswordVisualTransformation()")) "${f.name}:${i + 1}: $l" else null }
        }.toList()
        assertTrue(hits.size >= 4)
        for (h in hits) assertTrue("no reveal toggle: $h", Regex("reveal|masked\\)|SecretField\\.masked").containsMatchIn(h))
    }
}
