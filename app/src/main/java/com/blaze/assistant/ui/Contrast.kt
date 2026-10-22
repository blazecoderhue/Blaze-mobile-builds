package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color

/**
 * v0.45.7 readability: WCAG 2.x contrast math (pure; ContrastTest runs every theme x scene through it).
 *
 * Colours are blended the way Compose draws them (straight alpha in sRGB), then compared by relative luminance.
 * AA = 4.5:1 for text, 3:1 for icons, chip borders and secondary labels.
 */
object Contrast {
    const val AA_TEXT = 4.5
    const val AA_UI = 3.0

    private fun lin(c: Double): Double = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    fun luminance(c: Color): Double = 0.2126 * lin(c.red.toDouble()) + 0.7152 * lin(c.green.toDouble()) + 0.0722 * lin(c.blue.toDouble())

    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [top] (with its own alpha, times [alpha]) drawn over the opaque [bottom]. */
    fun over(top: Color, bottom: Color, alpha: Float = 1f): Color {
        val a = (top.alpha * alpha).coerceIn(0f, 1f)
        return Color(top.red * a + bottom.red * (1 - a), top.green * a + bottom.green * (1 - a), top.blue * a + bottom.blue * (1 - a), 1f)
    }

    /** A solid (opaque) version of a see-through fill as it looks on [ground]: chips stay readable over any scene. */
    fun solid(fill: Color, ground: Color): Color = over(fill, ground.copy(alpha = 1f))

    /** Grey of sRGB level [v] (0..1), the way scene brightness is stored. */
    fun grey(v: Float): Color = Color(v, v, v, 1f)

    /** Text [fg] on the scene pixel [scene] under a [scrimColor] veil of [scrim] alpha. */
    fun onScene(fg: Color, scene: Color, scrimColor: Color, scrim: Float): Double {
        val ground = over(scrimColor, scene, scrim)
        return ratio(over(fg, ground), ground)
    }

    /**
     * The smallest veil alpha (in steps of 0.01) that keeps [body] at 4.5:1 and [sub] at 3:1 on both the darkest
     * and the brightest part of a scene ([lo], [hi] as sRGB grey). 1 when even a full veil can't (never for real palettes).
     */
    fun minScrim(lo: Float, hi: Float, scrimColor: Color, body: Color, sub: Color): Float {
        for (i in 0..100) {
            val a = i / 100f
            val ok = listOf(lo, hi).all { g ->
                onScene(body, grey(g), scrimColor, a) >= AA_TEXT && onScene(sub, grey(g), scrimColor, a) >= AA_UI
            }
            if (ok) return a
        }
        return 1f
    }
}

/** v0.45.7: the veil over a theme's scene behind the chat, sized from the scene's measured brightness ([ThemeScenes.TONE]). */
object SceneVeil {
    private val memo = java.util.concurrent.ConcurrentHashMap<String, Float>()

    /** The least veil (page colour [paper]) keeping [body] at 4.5:1 and [sub] at 3:1 on [scene]'s darkest and brightest 1 %. */
    fun aa(scene: String?, paper: Color, body: Color, sub: Color, dark: Boolean): Float {
        val t = scene?.let { ThemeScenes.TONE[it] } ?: return ThemeScenes.scrim(dark)
        return memo.getOrPut("$scene|${paper.value}|${body.value}|${sub.value}") { Contrast.minScrim(t.first, t.second, paper, body, sub) }
    }

    /**
     * [strength] 1 = the AA minimum (scene strongest), 0 = [ThemeScenes.MAX_SCRIM] (scene faint). Never below the AA
     * minimum, whatever the slider says.
     */
    fun alpha(scene: String?, paper: Color, body: Color, sub: Color, dark: Boolean, strength: Float = ThemeScenes.DEFAULT_STRENGTH): Float {
        val aa = aa(scene, paper, body, sub, dark)
        val st = strength.coerceIn(0f, 1f)
        return maxOf(aa, aa + (1f - st) * (ThemeScenes.MAX_SCRIM - aa))
    }
}

/** v0.45.7: suggestion chips and pills are solid (no see-through glass over a busy scene) with a visible border. */
object ChipStyle {
    /** An opaque fill a touch off the page colour. */
    fun fill(c: BlazeColors): Color = Contrast.solid(c.ink.copy(alpha = if (c.dark) 0.10f else 0.06f), c.paper)
    /** A border at 3:1 or more against the fill (WCAG 1.4.11). */
    fun edge(c: BlazeColors): Color = Contrast.over(c.sub, fill(c))
}
