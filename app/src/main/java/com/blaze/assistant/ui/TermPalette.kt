package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * v0.45.8: the Terminal's colours, derived from the picked theme ([AppTheme]) with readable contrast (WCAG math from
 * [Contrast], 0.45.7). Pure, so ThemeEverywhereTest checks every theme x light/dark.
 *
 * - Ground: the theme's darkest ground colour in dark mode, a paper tinted with the theme's accent in light mode.
 *   Classic keeps the near-black terminal it always had.
 * - Text, cursor, the accent (selected tab, Ctrl/Alt on) and ANSI colours 1 to 15 are each pushed toward white (dark)
 *   or black (light) until they reach [MIN] (4.5:1) against the ground.
 * - ANSI 0 ("black") is a background colour in practice (`ls` highlights, prompts), so it is checked the other way:
 *   text drawn on it keeps 4.5:1.
 * - Key caps, edges and the bars are the ground with a little ink mixed in; key labels keep 4.5:1 on the key caps.
 */
object TermPalette {
    const val MIN = 4.5

    data class Colors(
        val bg: Int, val fg: Int, val cursor: Int, val accent: Int, val dim: Int,
        val keyBg: Int, val keyEdge: Int, val barBg: Int, val sheet: Int, val row: Int,
        /** ANSI 0..15. */
        val ansi: IntArray,
        val dark: Boolean,
    )

    /** xterm-like base hues; each is then pushed to [MIN] against the ground. */
    private val BASE = longArrayOf(
        0xFF1C1C1F, 0xFFE5534B, 0xFF57C46B, 0xFFE0B043, 0xFF5B8CFF, 0xFFC26DDB, 0xFF3FC1C9, 0xFFC9C9C9,
        0xFF5C5C63, 0xFFFF6F66, 0xFF7EE08F, 0xFFF4CE6A, 0xFF86A9FF, 0xFFDB93F0, 0xFF6EDDE4, 0xFFFFFFFF,
    )

    fun of(themeId: String?, dark: Boolean): Colors {
        val th = LivingThemes.of(themeId)
        val classic = th.motion == LivingThemes.Motion.NONE
        val accent0 = Color(th.accent)
        val bg = when {
            dark && classic -> Color(0xFF0B0B0D)
            dark -> Color(th.ground.first()).copy(alpha = 1f)
            classic -> Color(0xFFF7F7F8)
            else -> Contrast.over(accent0.copy(alpha = 0.08f), Color(0xFFFAF8F5))
        }
        val towards = if (dark) Color.White else Color.Black
        val fg = ensure(if (dark) Contrast.over(accent0.copy(alpha = 0.06f), Color(0xFFF0F0EE)) else Color(0xFF16161A), bg, towards, 7.0)
        val accent = ensure(if (classic) (if (dark) Color(0xFF6B8CFF) else Color(0xFF3355CC)) else accent0, bg, towards, MIN)
        val dim = ensure(Contrast.over(fg.copy(alpha = 0.55f), bg), bg, towards, MIN)
        val ansi = IntArray(16)
        for (i in 0 until 16) {
            val base = Color(BASE[i])
            ansi[i] = when (i) {
                // "Black": a background slot. Dark: a step above the ground; light: a dark slot that text reads on (white text).
                0 -> (if (dark) Contrast.over(Color.White.copy(alpha = 0.10f), bg) else Color(0xFF2A2A2E)).toArgb()
                else -> ensure(if (!dark && (i == 7 || i == 15)) Color(0xFF55555C) else base, bg, towards, MIN).toArgb()
            }
        }
        val keyBg = Contrast.over(towards.copy(alpha = if (dark) 0.07f else 0.06f), bg)
        val keyEdge = Contrast.over(towards.copy(alpha = if (dark) 0.15f else 0.14f), bg)
        val barBg = Contrast.over(Color.Black.copy(alpha = if (dark) 0.25f else 0.03f), bg)
        val sheet = Contrast.over(towards.copy(alpha = if (dark) 0.08f else 0.03f), bg)
        val row = Contrast.over(towards.copy(alpha = if (dark) 0.13f else 0.07f), bg)
        return Colors(bg.toArgb(), fg.toArgb(), accent.toArgb(), accent.toArgb(), dim.toArgb(), keyBg.toArgb(), keyEdge.toArgb(), barBg.toArgb(),
            sheet.toArgb(), row.toArgb(), ansi, dark)
    }

    /** Text on ANSI 0 (a background slot): the ground's ink. */
    fun onAnsi0(p: Colors): Int = p.fg.let { if (p.dark) it else Color.White.toArgb() }

    /** Mixes [c] toward [towards] in 5 % steps until it reaches [min] against [bg] (pure white / black always does on these grounds). */
    fun ensure(c: Color, bg: Color, towards: Color, min: Double): Color {
        var x = c.copy(alpha = 1f)
        var k = 0
        while (Contrast.ratio(x, bg) < min && k < 20) { k++; x = Contrast.over(towards.copy(alpha = k * 0.05f), c.copy(alpha = 1f)) }
        return x
    }

    fun ratio(a: Int, b: Int): Double = Contrast.ratio(Color(a), Color(b))
}
