package com.blaze.assistant.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.45.2 drawing for the Living Particles and Weather themes: plain Canvas calls, a fixed number of particles placed by
 * a hash of their index and the clock (no per-frame allocation of particle state), about 30 fps from LivingBackground.
 * [dense] = false draws about half as many (theme picker previews, low-power phones).
 */
private fun h(i: Int, k: Int): Float { val x = sin(i * 12.9898 + k * 78.233) * 43758.5453; return (x - kotlin.math.floor(x)).toFloat() }

private fun col(v: Long, a: Float = 1f) = Color(v).copy(alpha = (Color(v).alpha * a).coerceIn(0f, 1f))

/** The ground gradient for [t] in dark or light mode. */
fun themeGround(t: LivingThemes.Theme, dark: Boolean, weather: WeatherLook.Look? = null): List<Color> {
    val g = weather?.ground ?: t.ground
    return g.map { Color(if (dark) it else LivingLogic.mix(it, 0xFFF7F2F0, 0.84f)) }
}

fun DrawScope.drawTheme(theme: LivingThemes.Theme, t: Float, pulse: Float, dark: Boolean, weather: WeatherLook.Look? = null, dense: Boolean = true,
                        scene: androidx.compose.ui.graphics.ImageBitmap? = null, scrim: Float = 0f, scrimColor: Color = Color.Black) {
    if (theme.motion == LivingThemes.Motion.NONE) return
    // v0.45.3: the theme's scene picture (centre-cropped to cover) with a scrim, else the ground gradient as before;
    // the living particles are drawn on top either way.
    if (scene != null) { drawSceneCover(scene); if (scrim > 0f) drawRect(scrimColor.copy(alpha = scrim)) }
    else drawRect(Brush.verticalGradient(themeGround(theme, dark, weather)))
    val n = if (dense) 64 else 30
    val pc = if (theme.motion == LivingThemes.Motion.WEATHER && weather != null) longArrayOf(weather.particle, weather.accent) else theme.particles
    // Light mode: particles a touch deeper so they read on the pale ground.
    fun p(i: Int, a: Float): Color { val c = pc[i % pc.size]; return col(if (dark) c else LivingLogic.mix(c, 0xFF000000, 0.25f), a * (if (dark) 1f else 0.85f)) }
    val W = size.width; val H = size.height
    when (theme.motion) {
        LivingThemes.Motion.EMBERS -> for (i in 0 until n) {
            val sp = 18f + h(i, 1) * 40f
            val y = H - ((h(i, 2) * (H + 60f) + t * sp) % (H + 60f))
            val x = h(i, 3) * W + 14f * sin(t * 0.7f + i)
            val fade = (y / H).coerceIn(0f, 1f)
            val r = 1.2f + h(i, 4) * 2.4f
            drawCircle(p(i, 0.18f * fade), r * 4f, Offset(x, y)); drawCircle(p(i, 0.9f * fade), r, Offset(x, y))
        }
        LivingThemes.Motion.AURORA -> {
            for (b in 0 until 3) {
                val path = Path(); val base = H * (0.2f + b * 0.12f); val amp = H * (0.05f + 0.02f * pulse)
                path.moveTo(0f, base); var x = 0f
                while (x <= W) { path.lineTo(x, base + amp * sin(x / W * 2 * PI.toFloat() * (1.1f + b * 0.3f) + t * (0.16f + b * 0.06f))); x += W / 30f }
                path.lineTo(W, base + H * 0.28f); path.lineTo(0f, base + H * 0.28f); path.close()
                drawPath(path, Brush.verticalGradient(listOf(p(b, 0.32f), Color.Transparent), startY = base - amp, endY = base + H * 0.28f))
            }
            for (i in 0 until n / 2) drawCircle(p(i, 0.5f * (0.5f + 0.5f * sin(t + i))), 1f + h(i, 2), Offset(h(i, 0) * W, h(i, 1) * H * 0.8f))
        }
        LivingThemes.Motion.BUBBLES -> for (i in 0 until n) {
            val sp = 10f + h(i, 1) * 22f
            val y = H + 20f - ((h(i, 2) * (H + 40f) + t * sp) % (H + 40f))
            val x = h(i, 3) * W + 10f * sin(t * 0.5f + i * 0.7f)
            val r = 2f + h(i, 4) * 7f
            drawCircle(p(i, 0.55f), r, Offset(x, y), style = Stroke(1.1f)); drawCircle(p(i, 0.08f), r * 2.2f, Offset(x, y))
        }
        LivingThemes.Motion.FLARE -> {
            val sx = W * 0.75f; val sy = H * 0.16f; val R = maxOf(W, H) * 0.9f
            drawCircle(Brush.radialGradient(listOf(p(0, 0.45f + 0.2f * pulse), Color.Transparent), Offset(sx, sy), W * 0.55f), W * 0.55f, Offset(sx, sy))
            for (i in 0 until n) {
                val a = h(i, 1) * 2 * PI.toFloat(); val per = 6f + h(i, 2) * 6f
                val ph = ((t + h(i, 3) * per) % per) / per
                val d = ph * R
                drawCircle(p(i, 0.85f * (1f - ph)), 1.2f + 2f * (1f - ph), Offset(sx + cos(a) * d, sy + sin(a) * d * 1.2f))
            }
        }
        LivingThemes.Motion.NEBULA -> {
            val cx = W * 0.5f; val cy = H * 0.42f
            for (b in 0 until 3) drawCircle(Brush.radialGradient(listOf(p(b, 0.22f), Color.Transparent), Offset(cx + W * 0.2f * sin(t * 0.05f + b * 2), cy + H * 0.1f * cos(t * 0.04f + b)), W * 0.6f),
                W * 0.6f, Offset(cx + W * 0.2f * sin(t * 0.05f + b * 2), cy + H * 0.1f * cos(t * 0.04f + b)))
            for (i in 0 until n) {
                val r = (0.08f + h(i, 1) * 0.55f) * W; val a = h(i, 2) * 6.283f + t * (0.12f / (0.3f + h(i, 1)))
                drawCircle(p(i, 0.75f), 0.8f + h(i, 3) * 1.8f, Offset(cx + cos(a) * r, cy + sin(a) * r * 0.55f))
            }
        }
        LivingThemes.Motion.INK -> {
            for (b in 0 until 5) {
                val x = W * (0.1f + h(b, 1) * 0.8f) + W * 0.1f * sin(t * 0.04f + b); val y = H * (0.1f + h(b, 2) * 0.8f) + H * 0.05f * cos(t * 0.03f + b)
                val r = W * (0.25f + h(b, 3) * 0.3f)
                drawCircle(Brush.radialGradient(listOf(p(b, 0.16f), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
            }
            for (i in 0 until n / 2) drawCircle(p(i, 0.45f), 0.8f + h(i, 3), Offset(h(i, 0) * W + 6f * sin(t * 0.3f + i), (h(i, 1) * H + t * 4f) % H))
        }
        LivingThemes.Motion.PETALS -> for (i in 0 until n / 2) {
            val sp = 14f + h(i, 1) * 20f
            val y = ((h(i, 2) * (H + 40f) + t * sp) % (H + 40f)) - 20f
            val x = h(i, 3) * W + 30f * sin(t * 0.6f + i)
            val w = 5f + h(i, 4) * 6f; val hh = w * (0.45f + 0.35f * abs(sin(t * 1.2f + i)))
            drawOval(p(i, 0.7f), Offset(x - w / 2, y - hh / 2), Size(w, hh))
        }
        LivingThemes.Motion.FIREFLIES -> for (i in 0 until n / 2) {
            val x = W * h(i, 1) + W * 0.08f * sin(t * (0.2f + h(i, 2) * 0.3f) + i)
            val y = H * (0.3f + h(i, 3) * 0.65f) + H * 0.05f * cos(t * (0.17f + h(i, 4) * 0.2f) + i * 2)
            val blink = sin(t * (0.8f + h(i, 5)) + i * 1.7f).let { if (it > 0) it * it else 0f }
            drawCircle(p(i, 0.2f * blink), 10f, Offset(x, y)); drawCircle(p(i, 0.95f * blink), 1.8f, Offset(x, y))
        }
        LivingThemes.Motion.MERCURY -> for (b in 0 until 7) {
            val x = W * (0.5f + 0.38f * sin(t * (0.07f + b * 0.013f) + b * 1.3f)); val y = H * (0.5f + 0.36f * cos(t * (0.06f + b * 0.011f) + b * 0.9f))
            val r = W * (0.06f + h(b, 1) * 0.08f) * (1f + 0.1f * pulse)
            drawCircle(Brush.radialGradient(listOf(p(2, 0.55f), p(1, 0.35f), Color.Transparent), Offset(x - r * 0.3f, y - r * 0.3f), r * 1.6f), r * 1.4f, Offset(x, y))
        }
        LivingThemes.Motion.PRISM -> {
            val cx = W * 0.5f; val cy = H * 0.35f
            for (i in 0 until 10) {
                val a = i / 10f * 6.283f + t * 0.04f
                drawLine(p(i, 0.22f), Offset(cx, cy), Offset(cx + cos(a) * W, cy + sin(a) * W), 2f + 3f * h(i, 1))
            }
            for (i in 0 until n / 2) drawCircle(p(i, 0.6f * (0.4f + 0.6f * abs(sin(t * 0.6f + i)))), 1f + h(i, 2) * 1.5f, Offset(h(i, 0) * W, h(i, 1) * H))
        }
        LivingThemes.Motion.DUNE -> {
            for (k in 0 until 3) {
                val path = Path(); val base = H * (0.72f + k * 0.09f); path.moveTo(0f, H)
                var x = 0f
                while (x <= W) { path.lineTo(x, base + H * 0.03f * sin(x / W * 3.2f + k * 1.7f + t * 0.02f)); x += W / 24f }
                path.lineTo(W, H); path.close(); drawPath(path, p(k, 0.18f + k * 0.08f))
            }
            for (i in 0 until n) {
                val sp = 40f + h(i, 1) * 80f
                val x = (h(i, 2) * (W + 80f) + t * sp) % (W + 80f) - 40f
                val y = H * (0.35f + h(i, 3) * 0.6f) + 6f * sin(t + i)
                drawLine(p(i, 0.4f), Offset(x, y), Offset(x - 6f - 8f * h(i, 4), y), 1.2f)
            }
        }
        LivingThemes.Motion.RAIN -> for (i in 0 until n) {
            val sp = 300f + h(i, 1) * 300f
            val x = h(i, 2) * W; val y = (h(i, 3) * H + t * sp) % H
            val len = 12f + h(i, 4) * 22f
            drawLine(p(i, 0.55f), Offset(x, y), Offset(x - len * 0.18f, y + len), 1.4f)
        }
        LivingThemes.Motion.WEATHER -> drawWeatherFx(weather, t, n, W, H, ::p)
        LivingThemes.Motion.NONE -> {}
    }
    // The dotted particle ribbon near the bottom (as on the reference sheets), in the theme's colours.
    val rows = if (dense) 3 else 2; val dots = if (dense) 40 else 24
    for (r in 0 until rows) for (j in 0 until dots) {
        val x = j / (dots - 1f) * W
        val y = H * (0.86f + r * 0.03f) + H * 0.025f * sin(x / W * 6.283f * 1.4f + t * (0.4f + r * 0.1f) + r)
        drawCircle(p(j + r, 0.35f + 0.25f * pulse), 1.1f, Offset(x, y))
    }
}

private fun DrawScope.drawWeatherFx(w: WeatherLook.Look?, t: Float, n: Int, W: Float, H: Float, p: (Int, Float) -> Color) {
    val fx = w?.fx ?: WeatherLook.Fx.SUN_GLOW
    when (fx) {
        WeatherLook.Fx.SUN_GLOW, WeatherLook.Fx.SHIMMER -> {
            val sx = W * 0.72f; val sy = H * (if (fx == WeatherLook.Fx.SHIMMER) 0.14f else 0.2f)
            drawCircle(Brush.radialGradient(listOf(p(1, 0.5f), Color.Transparent), Offset(sx, sy), W * 0.6f), W * 0.6f, Offset(sx, sy))
            if (fx == WeatherLook.Fx.SHIMMER) for (k in 0 until 6) {
                val y0 = H * (0.45f + k * 0.08f); var x = 0f; var prev = Offset(0f, y0)
                while (x <= W) { val q = Offset(x, y0 + 5f * sin(x / 40f + t * 2f + k)); drawLine(p(1, 0.12f), prev, q, 1.5f); prev = q; x += 18f }
            }
        }
        WeatherLook.Fx.SNOW -> for (i in 0 until n) {
            val y = (h(i, 1) * H + t * (20f + h(i, 2) * 30f)) % H
            drawCircle(p(0, 0.8f), 1.2f + h(i, 3) * 2.2f, Offset(h(i, 4) * W + 18f * sin(t * 0.6f + i), y))
        }
        WeatherLook.Fx.RAIN, WeatherLook.Fx.LIGHTNING -> {
            for (i in 0 until n) {
                val x = h(i, 2) * W; val y = (h(i, 3) * H + t * (380f + h(i, 1) * 260f)) % H
                drawLine(p(0, 0.35f), Offset(x, y), Offset(x - 3f, y + 16f), 1.2f)
            }
            if (fx == WeatherLook.Fx.LIGHTNING) { val c = (t % 7f); if (c < 0.12f || c in 0.22f..0.3f) drawRect(Color.White.copy(alpha = 0.22f)) }
        }
        WeatherLook.Fx.FOG -> for (k in 0 until 5) {
            val y = H * (0.2f + k * 0.15f); val x = ((t * (6f + k * 2f)) % (W * 2)) - W
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, p(0, 0.16f), Color.Transparent), startX = x, endX = x + W * 1.4f), Offset(0f, y), Size(W, H * 0.12f))
        }
        WeatherLook.Fx.LEAVES -> for (i in 0 until n / 2) {
            val x = (h(i, 1) * (W + 60f) + t * (60f + h(i, 2) * 70f)) % (W + 60f) - 30f
            val y = H * h(i, 3) + 20f * sin(t * 1.3f + i) + (t * 12f * h(i, 4)) % (H * 0.2f)
            val s = 4f + h(i, 5) * 5f
            drawOval(p(i, 0.75f), Offset(x, y), Size(s * 1.6f, s * (0.5f + 0.4f * abs(sin(t * 2f + i)))))
        }
        WeatherLook.Fx.STARS -> for (i in 0 until n) {
            val tw = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * (0.6f + h(i, 3)) + i))
            drawCircle(p(0, 0.75f * tw), 0.8f + h(i, 2) * 1.5f, Offset(h(i, 0) * W, h(i, 1) * H * 0.75f))
        }
        WeatherLook.Fx.NONE -> {}
    }
}

/**
 * The Blaze droplet in its halo, tinted for [theme] (the picker previews; the app's own orb takes the same tint
 * through [ThemeTint]).
 */
fun DrawScope.drawDropletHalo(theme: LivingThemes.Theme, center: Offset, r: Float, t: Float) {
    val halo = Color(theme.halo); val drop = Color(theme.droplet)
    val br = 1f + 0.04f * sin(t * 1.4f)
    drawCircle(Brush.radialGradient(listOf(halo.copy(alpha = 0.35f), Color.Transparent), center, r * 2.4f), r * 2.4f, center)
    drawCircle(halo.copy(alpha = 0.85f), r * 1.7f * br, center, style = Stroke(r * 0.12f))
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), drop, drop.copy(alpha = 0.7f)), Offset(center.x - r * 0.3f, center.y - r * 0.35f), r * 1.3f), r, center)
    drawCircle(Color.White.copy(alpha = 0.7f), r * 0.22f, Offset(center.x - r * 0.35f, center.y - r * 0.4f))
}
