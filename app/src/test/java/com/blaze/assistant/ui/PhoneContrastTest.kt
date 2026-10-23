package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.45.19 Blaze Phone UI fix 3 (contrast), strict 5/5. The tile's chip was white text on a white-ish chip ("Reading
 * Blaze Phone's page", 1.2:1). Every text / ground pair the Blaze Phone tile and screen draw now comes from
 * [PhoneColors] and must reach WCAG AA (4.5:1) for every picker theme x light / dark, computed here with the WCAG
 * formula (cross-checked against the app's own [Contrast]). Real pixels on a phone are a device check.
 */
class PhoneContrastTest {
    private fun root(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private fun src(p: String) = File(root(), "com/blaze/assistant/$p").readText()

    /** The accent BlazeTheme hands the UI for [t] in [dark] (a pale accent is deepened on light grounds). */
    private fun accentOf(t: LivingThemes.Theme, dark: Boolean, classicPick: Long?): Long? {
        val a = LivingThemes.accentFor(t.id, classicPick) ?: return null
        return if (dark) a else LivingThemes.onLight(a)
    }

    private fun fiveTimes(name: String, body: (Int) -> Unit) {
        var pass = 0
        for (i in 1..5) { body(i); pass++ }
        assertEquals("$name strict 5/5", 5, pass)
    }

    @Test fun wcagMathMatchesTheAppsContrastHelper() = fiveTimes("wcag math") { i ->
        assertEquals(21.0, PhoneColors.contrast(0xFF000000L, 0xFFFFFFFFL), 0.01)
        assertEquals(1.0, PhoneColors.contrast(0xFF336699L, 0xFF336699L), 0.0001)
        val r = java.util.Random(i.toLong())
        repeat(200) {
            val a = 0xFF000000L or r.nextInt(1 shl 24).toLong(); val b = 0xFF000000L or r.nextInt(1 shl 24).toLong()
            assertEquals(Contrast.ratio(Color(a), Color(b)).toDouble(), PhoneColors.contrast(a, b), 0.05)
        }
    }

    @Test fun theOldChipFailedAndTheNewOnesPass() = fiveTimes("regression") {
        // What the screenshots showed: white on Glass.blue (#E6E6EA).
        assertTrue(PhoneColors.contrast(0xFFFFFFFFL, 0xFFE6E6EAL) < 1.5)
        // StatusPill's default pair (the desktop cards use it too).
        assertTrue(PhoneColors.contrast(PhoneColors.STATUS_ON_CHIP, PhoneColors.STATUS_CHIP) >= PhoneColors.AA)
    }

    @Test fun everyThemeTimesLightAndDarkMeetsAA() = fiveTimes("themes x modes") { run ->
        val r = java.util.Random(run * 7919L)
        val classicPicks = listOf<Long?>(null, 0xFFFFFFFFL, 0xFF000000L, 0xFF777777L, 0xFFFFEB3BL) +
            (1..20).map { 0xFF000000L or r.nextInt(1 shl 24).toLong() }
        var checked = 0
        for (t in LivingThemes.PICKER) for (dark in listOf(true, false)) {
            val picks = if (t.id == LivingThemes.CLASSIC) classicPicks else listOf(null)
            for (pick in picks) {
                val p = PhoneColors.of(accentOf(t, dark, pick), dark)
                assertEquals(dark, p.dark)
                for ((name, pair) in p.pairs()) {
                    val ratio = PhoneColors.contrast(pair.first, pair.second)
                    assertTrue("${t.name} ${if (dark) "dark" else "light"} accent=${pick?.toString(16)} $name: %.2f".format(ratio), ratio >= PhoneColors.AA)
                    assertEquals("$name fg opaque", 0xFFL, (pair.first ushr 24) and 0xFF)
                    assertEquals("$name bg opaque", 0xFFL, (pair.second ushr 24) and 0xFF)
                    checked++
                }
                // The chip still reads as a chip on the card (not the card's own colour).
                assertTrue("${t.name} chip vs card", PhoneColors.contrast(p.chip, p.card) >= 1.3)
            }
        }
        assertTrue(checked >= LivingThemes.PICKER.size * 2 * 14)
    }

    @Test fun anyAccentAtAllStillMeetsAA() = fiveTimes("any accent") { run ->
        val r = java.util.Random(run.toLong())
        repeat(400) {
            val a = 0xFF000000L or r.nextInt(1 shl 24).toLong()
            for (dark in listOf(true, false)) {
                val p = PhoneColors.of(a, dark)
                assertTrue(PhoneColors.contrast(p.onChip, p.chip) >= PhoneColors.AA)
            }
        }
    }

    @Test fun theTileAndScreenDrawOnlyWithThePalette() = fiveTimes("sources") {
        val tile = src("ui/PhoneLiveCard.kt")
        val screen = src("browser/phone/PhoneBrowserActivity.kt")
        for ((n, s) in listOf("PhoneLiveCard" to tile, "PhoneBrowserActivity" to screen)) {
            assertFalse("$n: Glass.blue", s.contains("Glass.blue"))
            assertFalse("$n: Glass.text", s.contains("Glass.text"))
            assertFalse("$n: Glass.sub", s.contains("Glass.sub"))
            assertFalse("$n: Glass.surface", s.contains("Glass.surface"))
            assertFalse("$n: LightColors", s.contains("LightColors"))
            assertFalse("$n: hard-coded white / black text", Regex("""color = Color\.(White|Black)""").containsMatchIn(s))
            assertTrue("$n uses the palette", s.contains("phonePalette()"))
        }
        assertTrue(tile.contains("StatusPill(pill, working = live.mode == PhonePolicy.Mode.AGENT, chip = Color(pal.chip), onChip = Color(pal.onChip))"))
        assertTrue(screen.contains("chip = Color(pal.chip), onChip = Color(pal.onChip)"))
        val pill = src("ui/DesktopLiveCard.kt").substringAfter("fun StatusPill(").substringBefore("\n}\n")
        assertFalse("StatusPill text is no longer white", pill.contains("color = Color.White"))
        assertTrue(pill.contains("color = onChip"))
    }
}
