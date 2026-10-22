package com.blaze.assistant.ui

import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** v0.39.0 Look & feel prefs as live state (Settings › Look & feel writes, the background reads). */
object Look {
    private fun s(k: String, d: String) = try { com.blaze.assistant.agent.Settings.str(k, d) } catch (_: Throwable) { d }
    private fun b(k: String, d: Boolean) = try { com.blaze.assistant.agent.Settings.bool(k, d) } catch (_: Throwable) { d }
    val bg = MutableStateFlow(LivingLogic.Bg.of(s("look_bg", LivingLogic.DEFAULT)))
    val accent = MutableStateFlow(s("look_accent", ""))
    val music = MutableStateFlow(b("look_music", false))
    val still = MutableStateFlow(b("look_still", false))
    val photo = MutableStateFlow(s("look_photo", ""))
    /** v0.45.2: the picked theme (LivingThemes id; "" = Classic, the look as before). */
    val theme = MutableStateFlow(s("look_theme", LivingThemes.CLASSIC))
    fun setTheme(id: String) { theme.value = id; ThemeTint.set(id); try { com.blaze.assistant.agent.Settings.setStr("look_theme", id) } catch (_: Throwable) { } }

    fun setBg(v: LivingLogic.Bg) { bg.value = v; try { com.blaze.assistant.agent.Settings.setStr("look_bg", v.id) } catch (_: Throwable) { } }
    fun setAccent(v: String) { accent.value = v; try { com.blaze.assistant.agent.Settings.setStr("look_accent", v) } catch (_: Throwable) { } }
    fun setMusic(v: Boolean) { music.value = v; try { com.blaze.assistant.agent.Settings.setBool("look_music", v) } catch (_: Throwable) { } }
    fun setStill(v: Boolean) { still.value = v; try { com.blaze.assistant.agent.Settings.setBool("look_still", v) } catch (_: Throwable) { } }
    /** v0.40.0: cloud sync brought Look & feel from another phone. */
    fun reload() {
        bg.value = LivingLogic.Bg.of(s("look_bg", LivingLogic.DEFAULT)); accent.value = s("look_accent", "")
        music.value = b("look_music", false); still.value = b("look_still", false)
        theme.value = s("look_theme", LivingThemes.CLASSIC); ThemeTint.set(theme.value)
    }
    /** v0.45.7 Look & feel › Background strength (0 = scene faint, 1 = strongest that keeps text AA). */
    val strength = MutableStateFlow(s("look_strength", ThemeScenes.DEFAULT_STRENGTH.toString()).toFloatOrNull()?.coerceIn(0f, 1f) ?: ThemeScenes.DEFAULT_STRENGTH)
    fun setStrength(v: Float) { strength.value = v.coerceIn(0f, 1f); try { com.blaze.assistant.agent.Settings.setStr("look_strength", "%.2f".format(java.util.Locale.ROOT, strength.value)) } catch (_: Throwable) { } }
    fun setPhoto(v: String) { photo.value = v; try { com.blaze.assistant.agent.Settings.setStr("look_photo", v) } catch (_: Throwable) { } }
}

/**
 * The living background, drawn behind the existing glass UI with plain Canvas calls (no shaders, ~30 fps, nothing
 * when Calm paper is picked, so the default look is untouched). A soft scrim on top keeps text readable. It stands
 * still with Save battery on, in power saver, on a low battery, or with system animations off; Move with music makes
 * it pulse while Blaze Music plays.
 */
@Composable
fun LivingBackground(modifier: Modifier = Modifier.fillMaxSize()) {
    val bg by Look.bg.collectAsState()
    val themeId by Look.theme.collectAsState()
    val theme = remember(themeId) { LivingThemes.of(themeId) }
    val themed = theme.motion != LivingThemes.Motion.NONE
    if (bg == LivingLogic.Bg.PAPER && !themed) return
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val still by Look.still.collectAsState()
    val musicOn by Look.music.collectAsState()
    val photoUri by Look.photo.collectAsState()
    var powerSave by remember { mutableStateOf(false) }
    var battery by remember { mutableIntStateOf(100) }
    var charging by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var weather by remember { mutableStateOf(LivingLogic.Weather.UNKNOWN) }
    var tempC by remember { mutableStateOf<Int?>(null) }
    var windy by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.MINUTE)) }
    // Slow readings: battery, power saver, music, the clock (every few seconds), the weather (from a Home weather widget, if any).
    LaunchedEffect(Unit) {
        var n = 0
        while (isActive) {
            try {
                val pm = ctx.getSystemService(PowerManager::class.java); powerSave = pm?.isPowerSaveMode == true
                val bm = ctx.getSystemService(BatteryManager::class.java)
                battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
                charging = bm?.isCharging ?: true
            } catch (_: Throwable) { }
            playing = try { com.blaze.assistant.agent.Awareness.get("music")?.playing == true } catch (_: Throwable) { false }
            val cal = Calendar.getInstance(); hour = cal.get(Calendar.HOUR_OF_DAY); minute = cal.get(Calendar.MINUTE)
            if ((bg == LivingLogic.Bg.SKY || theme.motion == LivingThemes.Motion.WEATHER) && n % 60 == 0) withContext(Dispatchers.IO) { readWeatherFull() }.let { (w, tc, wd) -> weather = w; tempC = tc; windy = wd }
            n++
            delay(if (musicOn) 1_000 else 5_000)
        }
    }
    val moving = LivingLogic.moving(if (themed) LivingLogic.Bg.AURORA else bg, still, powerSave, battery, charging, MotionTokens.enabled)
    // v0.45.6 jank: the clock is read only while drawing (never in composition), so the background redraws each frame
    // without recomposing 30 times a second, and it ticks at up to 60 fps (was capped at 30, which looked like jitter).
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        val start = withFrameMillis { it } - (clock.floatValue * 1000).toLong()
        var last = 0L
        while (isActive) {
            withFrameMillis { ms -> if (ms - last >= LivingLogic.FRAME_MS) { last = ms; clock.floatValue = (ms - start) / 1000f } }
        }
    }
    val photo = remember(photoUri) { mutableStateOf<Bitmap?>(null) }
    if (bg == LivingLogic.Bg.PHOTO) LaunchedEffect(photoUri) { photo.value = withContext(Dispatchers.IO) { loadPhoto(ctx, photoUri) } }
    val sky = remember(hour, minute / 10, weather) { LivingLogic.sky(hour, minute, weather).map { Color(it) } }
    val night = LivingLogic.phase(hour, minute) == LivingLogic.Phase.NIGHT
    val wLook = if (theme.motion == LivingThemes.Motion.WEATHER) remember(weather, tempC, windy, hour, minute / 10) { WeatherLook.look(weather, tempC, windy, hour, minute) } else null
    // Low-memory phones get half the particles.
    val dense = remember { try { !(ctx.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice ?: false) } catch (_: Throwable) { true } }
    // v0.45.3: the theme's scene picture, decoded once at screen size (ThemeSceneImages), behind the particles.
    val metrics = ctx.resources.displayMetrics
    val sceneId = if (themed) remember(themeId, wLook?.state, weather) { ThemeScenes.of(themeId, wLook?.state)?.let { if (theme.motion == LivingThemes.Motion.WEATHER && wLook != null) ThemeScenes.forWeather(wLook.state, weather) else it } } else null
    val scene = rememberScene(sceneId, metrics.widthPixels, metrics.heightPixels)
    val strength by Look.strength.collectAsState()
    val veil = remember(sceneId, c.paper, c.body, c.sub, c.dark, strength) { SceneVeil.alpha(sceneId, c.paper, c.body, c.sub, c.dark, strength) }
    Canvas(modifier) {
        val t = clock.floatValue
        val pulse = LivingLogic.musicPulse(playing, musicOn && moving, t.toDouble())
        if (themed) {
            if (scene != null) {
                // The picture under a scrim in the page colour (dark or light) so text keeps AA contrast; particles on top.
                // v0.45.7: the veil is sized per scene so body text stays 4.5:1 (secondary 3:1); Background strength only adds to it.
                drawTheme(theme, t, pulse, c.dark, wLook, dense, scene, veil, c.paper)
            } else { drawTheme(theme, t, pulse, c.dark, wLook, dense); drawRect(c.paper.copy(alpha = if (c.dark) 0.34f else 0.42f)) }
            return@Canvas
        }
        when (bg) {
            LivingLogic.Bg.SKY -> drawSky(sky, night, t, weather)
            LivingLogic.Bg.AURORA -> drawAurora(t, pulse)
            LivingLogic.Bg.OCEAN -> drawOcean(t, pulse)
            LivingLogic.Bg.MOSAIC -> drawMosaic(t, pulse)
            LivingLogic.Bg.PHOTO -> drawPhoto(photo.value, t, pulse)
            LivingLogic.Bg.PAPER -> {}
        }
        if (pulse > 0f && bg != LivingLogic.Bg.AURORA) drawRect(Color.White.copy(alpha = 0.05f * pulse))
        // Readability: a soft wash in the theme's ground colour so text and glass read on any background.
        drawRect(c.paper.copy(alpha = if (c.dark) 0.42f else 0.5f))
    }
}

/** v0.45.2: the Home weather widget's condition, temperature (°C) and wind; unknown / null / false when there is none (offline safe). */
private fun readWeatherFull(): Triple<LivingLogic.Weather, Int?, Boolean> = try {
    val st = com.blaze.assistant.home.HomeBoard.storeOrNull()
    val w = st?.all()?.firstOrNull { it.type == com.blaze.assistant.home.WidgetTypes.WEATHER }
    val d = w?.let { st.data(it.id) }
    val words = listOfNotNull(d?.value, d?.headline, d?.change).joinToString(" \u00b7 ")
    Triple(LivingLogic.weatherOf(d?.change), WeatherLook.tempOf(words), WeatherLook.windy(words))
} catch (_: Throwable) { Triple(LivingLogic.Weather.UNKNOWN, null, false) }

private fun readWeather(): LivingLogic.Weather = try {
    val st = com.blaze.assistant.home.HomeBoard.storeOrNull()
    val w = st?.all()?.firstOrNull { it.type == com.blaze.assistant.home.WidgetTypes.WEATHER }
    LivingLogic.weatherOf(w?.let { st.data(it.id)?.change })
} catch (_: Throwable) { LivingLogic.Weather.UNKNOWN }

private fun loadPhoto(ctx: android.content.Context, uri: String): Bitmap? {
    if (uri.isBlank()) return null
    return try {
        val u = android.net.Uri.parse(uri)
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 1400) sample *= 2
        ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
    } catch (_: Throwable) { null }
}

private fun rnd(i: Int, k: Int): Float { val x = sin(i * 12.9898 + k * 78.233) * 43758.5453; return (x - kotlin.math.floor(x)).toFloat() }

private fun DrawScope.drawSky(cols: List<Color>, night: Boolean, t: Float, w: LivingLogic.Weather) {
    drawRect(Brush.verticalGradient(cols))
    val W = size.width; val H = size.height
    if (night) {
        for (i in 0 until 60) {
            val tw = 0.45f + 0.55f * (0.5f + 0.5f * sin(t * (0.6f + rnd(i, 3)) + i).toFloat())
            drawCircle(Color.White.copy(alpha = 0.7f * tw), radius = 0.8f + rnd(i, 2) * 1.6f, center = Offset(rnd(i, 0) * W, rnd(i, 1) * H * 0.7f))
        }
        drawCircle(Brush.radialGradient(listOf(Color(0x55E8ECFF), Color.Transparent), center = Offset(W * 0.78f, H * 0.14f), radius = W * 0.35f), W * 0.35f, Offset(W * 0.78f, H * 0.14f))
        drawCircle(Color(0xFFEDEFF7).copy(alpha = 0.9f), W * 0.05f, Offset(W * 0.78f, H * 0.14f))
    } else {
        val sx = W * (0.72f + 0.02f * sin(t * 0.05f)); val sy = H * (0.18f + 0.01f * cos(t * 0.04f))
        drawCircle(Brush.radialGradient(listOf(Color(0x88FFF3D6), Color.Transparent), center = Offset(sx, sy), radius = W * 0.6f), W * 0.6f, Offset(sx, sy))
    }
    val clouds = when (w) { LivingLogic.Weather.CLOUDY, LivingLogic.Weather.RAIN, LivingLogic.Weather.STORM, LivingLogic.Weather.FOG -> 7; else -> 3 }
    for (i in 0 until clouds) {
        val speed = 6f + rnd(i, 5) * 10f
        val x = ((rnd(i, 6) * (W + 400f) + t * speed) % (W + 400f)) - 200f
        val y = H * (0.08f + rnd(i, 7) * 0.45f)
        val r = 70f + rnd(i, 8) * 90f
        val a = if (night) 0.06f else 0.22f
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = a), Color.Transparent), center = Offset(x, y), radius = r * 1.6f), r * 1.6f, Offset(x, y))
    }
    if (w == LivingLogic.Weather.RAIN || w == LivingLogic.Weather.STORM) for (i in 0 until 70) {
        val x = rnd(i, 9) * W; val y = ((rnd(i, 10) * H + t * 420f * (0.7f + rnd(i, 11) * 0.6f)) % H)
        drawLine(Color.White.copy(alpha = 0.18f), Offset(x, y), Offset(x - 3f, y + 14f), 1.2f)
    }
}

private fun DrawScope.drawAurora(t: Float, pulse: Float) {
    val W = size.width; val H = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF050A16), Color(0xFF0A1630), Color(0xFF07101F))))
    val bands = listOf(Color(0xFF2EE6A6), Color(0xFF3AA0FF), Color(0xFFB45CFF))
    bands.forEachIndexed { b, col ->
        val path = androidx.compose.ui.graphics.Path()
        val base = H * (0.22f + b * 0.13f)
        val amp = H * (0.05f + 0.02f * pulse)
        path.moveTo(0f, base)
        var x = 0f
        while (x <= W) {
            val y = base + amp * sin(x / W * 2 * PI.toFloat() * (1.1f + b * 0.3f) + t * (0.18f + b * 0.07f)) + amp * 0.5f * sin(x / W * 7f - t * 0.11f)
            path.lineTo(x, y); x += W / 40f
        }
        path.lineTo(W, base + H * 0.3f); path.lineTo(0f, base + H * 0.3f); path.close()
        drawPath(path, Brush.verticalGradient(listOf(col.copy(alpha = 0.32f + 0.18f * pulse), Color.Transparent), startY = base - amp, endY = base + H * 0.3f))
    }
}

private fun DrawScope.drawOcean(t: Float, pulse: Float) {
    val W = size.width; val H = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0B3A5B), Color(0xFF0E5A7E), Color(0xFF0A2E48))))
    for (i in 0 until 5) {
        val cx = W * (0.15f + rnd(i, 1) * 0.7f); val cy = H * (0.2f + rnd(i, 2) * 0.6f)
        val period = 7f + rnd(i, 3) * 5f
        val ph = ((t + rnd(i, 4) * period) % period) / period
        val r = ph * W * 0.7f
        drawCircle(Color.White.copy(alpha = (1f - ph) * (0.12f + 0.08f * pulse)), r, Offset(cx, cy), style = Stroke(2f + 3f * (1f - ph)))
    }
    for (k in 0 until 4) {
        val y0 = H * (0.3f + k * 0.18f)
        var x = 0f; var prev = Offset(0f, y0)
        while (x <= W) {
            val p = Offset(x, y0 + 10f * sin(x / 90f + t * 0.6f + k))
            drawLine(Color.White.copy(alpha = 0.05f), prev, p, 2f); prev = p; x += 16f
        }
    }
}

private fun DrawScope.drawMosaic(t: Float, pulse: Float) {
    val W = size.width; val H = size.height
    drawRect(Color(0xFF15161B))
    val hues = listOf(Color(0xFF3B4C7A), Color(0xFF5A3B6E), Color(0xFF2F6061), Color(0xFF6E5A3B), Color(0xFF41455A))
    val cell = W / 5f
    var i = 0
    var y = -cell
    while (y < H + cell) {
        var x = -cell
        while (x < W + cell) {
            val dx = 10f * sin(t * 0.2f + i * 0.7f); val dy = 10f * cos(t * 0.17f + i * 0.5f)
            val s = cell * (0.78f + 0.08f * pulse)
            drawRoundRect(hues[i % hues.size].copy(alpha = 0.55f + 0.25f * rnd(i, 1)), Offset(x + dx, y + dy), Size(s, s), CornerRadius(cell * 0.22f))
            x += cell; i++
        }
        y += cell
    }
}

private fun DrawScope.drawPhoto(bmp: Bitmap?, t: Float, pulse: Float) {
    if (bmp == null) { drawRect(Brush.verticalGradient(listOf(Color(0xFF1B1D24), Color(0xFF101114)))); return }
    val k = maxOf(size.width / bmp.width, size.height / bmp.height) * (1.08f + 0.04f * sin(t * 0.05f) + 0.02f * pulse)
    val w = bmp.width * k; val h = bmp.height * k
    val ox = (size.width - w) / 2f + 12f * sin(t * 0.03f); val oy = (size.height - h) / 2f + 12f * cos(t * 0.027f)
    drawImage(bmp.asImageBitmap(), dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)))
}
