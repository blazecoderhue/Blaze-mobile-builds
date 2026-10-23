package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

/**
 * 0.45.20 chat UI (spec A, B, C), the pure half (runs on every JVM; the rendered half is ChatUi20ShotsTest). Strict 5/5.
 * Contrast: 3 themes x light / dark, every text in or on a bubble at >= 4.5:1 over the darkest and brightest thing that
 * can be behind it (pure black, pure white, the page, the theme's ground and particle colours).
 */
class ChatUi20Test {
    private fun five(f: () -> Unit) = repeat(5) { f() }

    /** The three themes under test: Classic, a dark living theme and a light one. */
    private val themes = listOf("", "golden_ember", "rose_dawn")

    /** The palette BlazeTheme builds for [id] in [dark] (same rule as Theme.kt). */
    private fun palette(id: String, dark: Boolean): BlazeColors {
        val base = if (dark) DarkColors else LightColors
        return LivingThemes.accentFor(id, null)?.let { a -> base.copy(accent = Color(if (dark) a else LivingThemes.onLight(a))) } ?: base
    }

    private fun backdrops(id: String, c: BlazeColors): List<Color> {
        val t = LivingThemes.of(id)
        return (listOf(Color.Black, Color.White, c.paper.copy(alpha = 1f)) + t.ground.map { Color(it) } + t.particles.map { Color(it) } + Color(t.accent))
    }

    @Test fun contrast_everyText_3themes_lightAndDark() = five {
        var checked = 0
        for (id in themes) for (dark in listOf(false, true)) {
            val c = palette(id, dark)
            for (g in backdrops(id, c)) {
                for (mine in listOf(true, false)) {
                    val fill = Contrast.over(BubblePalette.fill(c, mine), g)
                    val text = Contrast.ratio(Contrast.over(BubblePalette.text(c, mine), fill), fill)
                    val meta = Contrast.ratio(Contrast.over(BubblePalette.meta(c, mine), fill), fill)
                    assertTrue("$id dark=$dark mine=$mine on $g text $text", text >= 4.5)
                    assertTrue("$id dark=$dark mine=$mine on $g time $meta", meta >= 4.5)
                    checked += 2
                }
                val cap = Contrast.over(BubblePalette.capsule(c), g)
                val capInk = Contrast.ratio(Contrast.over(BubblePalette.capsuleInk(c), cap), cap)
                assertTrue("$id dark=$dark capsule (delivery / day chip / step pill) $capInk", capInk >= 4.5)
                checked++
            }
        }
        assertTrue(checked > 100)
    }

    @Test fun bubbles_areSemiOpaque_notSolid_andThemeAware() = five {
        for (id in themes) for (dark in listOf(false, true)) {
            val c = palette(id, dark)
            for (mine in listOf(true, false)) {
                val a = BubblePalette.fill(c, mine).alpha
                assertTrue("$id $dark $mine alpha $a", a >= BubblePalette.FROST && a < 1f)
            }
        }
        // Your bubble follows the theme accent; Blaze's stays neutral; never WhatsApp green.
        val ember = palette("golden_ember", true); val rose = palette("rose_dawn", true)
        assertTrue(BubblePalette.fill(ember, true) != BubblePalette.fill(rose, true))
        assertEquals(BubblePalette.fill(ember, false), BubblePalette.fill(rose, false))
        // Blaze's own greys and the theme accent, never WhatsApp's green.
        for (id in themes) for (dark in listOf(false, true)) for (mine in listOf(true, false)) {
            val f = BubblePalette.fill(palette(id, dark), mine)
            assertTrue("$id $dark $mine greenish", !(f.green > f.red + 0.08f && f.green > f.blue + 0.08f))
        }
    }

    @Test fun grouping_tailOnFirstOfARun_tighterSpacing() = five {
        val p = Bubbles.positions(listOf(Side.ME, Side.ME, Side.ME, Side.BLAZE, Side.BLAZE, Side.ME), listOf(1, 2, 3, 4, 5, 6))
        assertEquals(listOf(GroupPos.FIRST, GroupPos.MIDDLE, GroupPos.LAST, GroupPos.FIRST, GroupPos.LAST, GroupPos.SINGLE), p)
        assertEquals(listOf(true, false, false, true, false, true), p.map { it.starts })   // tail
        assertTrue(Bubbles.gapAbove(GroupPos.MIDDLE, false) < Bubbles.gapAbove(GroupPos.FIRST, false))
        assertEquals(0.80f, BubblePalette.MAX_SHARE)
    }

    @Test fun dayChips_todayYesterday() = five {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 23, 11, 0) }.timeInMillis
        val y = now - 24 * 3600_000L
        val old = Calendar.getInstance().apply { set(2025, Calendar.MARCH, 2, 9, 0) }.timeInMillis
        assertEquals("Today", BubbleDays.label(now, now))
        assertEquals("Yesterday", BubbleDays.label(y, now))
        assertEquals("2 Mar 2025", BubbleDays.label(old, now, java.util.Locale.UK))
        assertTrue(BubbleDays.startsDay(0, now))
        assertTrue(BubbleDays.startsDay(y, now))
        assertTrue(!BubbleDays.startsDay(now - 60_000, now))
        assertTrue(!BubbleDays.startsDay(now, 0))
        assertEquals(5, BubbleDays.time(now).length)
    }

    @Test fun chatScreen_hooks() = five {
        val src = listOf(File("src/main/java/com/blaze/assistant/ui/ChatScreen.kt"), File("app/src/main/java/com/blaze/assistant/ui/ChatScreen.kt")).first { it.exists() }.readText()
        assertTrue("delivery only under the latest user bubble", src.contains("delivery = if (item.m.id == lastUserId)"))
        assertTrue("action row only on the latest Blaze reply", src.contains("showActs = item.m.id == lastBlazeId && mood == Mood.IDLE"))
        assertTrue(src.contains("StepPillRow(") && src.contains("DayChip(") && src.contains("BubbleMenu("))
        // No ticks anywhere in the label.
        val label = listOf(File("src/main/java/com/blaze/assistant/ui/DeliveryStatus.kt"), File("app/src/main/java/com/blaze/assistant/ui/DeliveryStatus.kt")).first { it.exists() }.readText()
        assertTrue(!label.contains("\u2713") && !label.contains("Icons.") && label.contains("LiveRegionMode.Polite"))
    }
}
