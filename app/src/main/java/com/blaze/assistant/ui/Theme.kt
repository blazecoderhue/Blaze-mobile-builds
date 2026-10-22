package com.blaze.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blaze.assistant.R
import com.blaze.assistant.agent.Mood
import kotlinx.coroutines.delay
import kotlin.random.Random
import androidx.compose.foundation.shape.RoundedCornerShape

/** Design tokens lifted from blazeinchue.netlify.app (paper, ink, hairlines, blue accent); v0.24.8: every edge rounded. */
data class BlazeColors(
    val paper: Color,
    val ink: Color,
    val body: Color,
    val sub: Color,
    val weak: Color,
    val hair: Color,
    val faint: Color,
    val accent: Color,
    val dark: Boolean
)

// v0.31.0: greyscale. Light keeps working (Settings → theme) but the glass look is designed dark-first.
val LightColors = BlazeColors(
    paper = Color(0xFFF4F4F5),
    ink = Color(0xFF111112),
    body = Color(0xD1111112),
    sub = Color(0x8F111112),
    weak = Color(0x57111112),
    hair = Color(0x1F111112),
    faint = Color(0x0F111112),
    accent = Color(0xFF2A2A2E),
    dark = false
)

// v0.31.0: the frosted-glass palette (GlassTokens): near-black ground, white ink, a monochrome accent.
val DarkColors = BlazeColors(
    paper = GlassTokens.bg,
    ink = GlassTokens.ink,
    body = GlassTokens.body,
    sub = GlassTokens.sub,
    weak = GlassTokens.weak,
    hair = Color(0x24FFFFFF),
    faint = Color(0x10FFFFFF),
    accent = GlassTokens.accent,
    dark = true
)

val LocalBlaze = staticCompositionLocalOf { LightColors }

/**
 * v0.45.7: whether the UI draws in the system font. The bundled Inter is a 228-character Latin subset (no Hausa ɓ ɗ ƙ ƴ,
 * Yorùbá ẹ ọ ṣ, Ge'ez or Arabic). Android would fill each missing letter from a system font anyway (no tofu), but
 * letter-by-letter font mixing looks broken, so a reply language that needs them switches the whole UI to the
 * system font (Roboto / Noto, which carry those scripts), and any single text that needs them uses it too ([Type.sansFor]).
 */
object FontPick {
    val system = androidx.compose.runtime.mutableStateOf(false)
    fun refresh() {
        system.value = try { com.blaze.assistant.agent.ReplyLanguage.systemFont(com.blaze.assistant.agent.ReplyLanguage.current()) } catch (_: Throwable) { false }
    }
}

object Type {
    private val Inter = FontFamily(
        Font(R.font.inter_400, FontWeight.Normal),
        Font(R.font.inter_500, FontWeight.Medium),
        Font(R.font.inter_600, FontWeight.SemiBold)
    )
    private val InterTight = FontFamily(
        Font(R.font.inter_tight_400, FontWeight.Normal),
        Font(R.font.inter_tight_500, FontWeight.Medium),
        Font(R.font.inter_tight_600, FontWeight.SemiBold)
    )
    val Sans: FontFamily get() = if (FontPick.system.value) FontFamily.Default else Inter
    val Display: FontFamily get() = if (FontPick.system.value) FontFamily.Default else InterTight
    /** The text font for [text]: the system font when it has letters Inter lacks (so a reply is never drawn in two fonts). */
    fun sansFor(text: String): FontFamily = if (FontPick.system.value || com.blaze.assistant.agent.Scripts.needsSystemFont(text)) FontFamily.Default else Inter
    val Mono = FontFamily.Monospace
}

/** Light/dark override; "system" follows the phone. Backed by Settings.themeMode. */
object ThemeMode {
    val mode = androidx.compose.runtime.mutableStateOf(try { com.blaze.assistant.agent.Settings.themeMode } catch (_: Exception) { "system" })
    fun set(m: String) { mode.value = m; try { com.blaze.assistant.agent.Settings.themeMode = m } catch (_: Throwable) { } }
}

@Composable
fun BlazeTheme(content: @Composable () -> Unit) {
    // v0.45.7: the reply language picks Inter or the system font (FontPick), read once per theme host.
    androidx.compose.runtime.remember { FontPick.refresh(); 0 }
    // v0.31.0: the glass UI is dark-first: "system" means dark; light only when picked in the menu.
    // v0.39.0 Look & feel: "auto" follows the local time (dark after sunset); an accent picked there tints the accent only.
    val hour = androidx.compose.runtime.remember(ThemeMode.mode.value) { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val dark = when (ThemeMode.mode.value) { "dark" -> true; "light" -> false; "auto" -> LivingLogic.darkFor("auto", hour); else -> true }
    val accentHex by Look.accent.collectAsState()
    val base = if (dark) DarkColors else LightColors
    // v0.45.2: a picked theme's accent drives the app's accents (Classic: the Look & feel accent as before); on light grounds a
    // pale accent is deepened so it still reads.
    val themeId by Look.theme.collectAsState()
    val c = LivingThemes.accentFor(themeId, LivingLogic.parseAccent(accentHex))?.let { a -> base.copy(accent = Color(if (dark) a else LivingThemes.onLight(a))) } ?: base
    // v0.24.5: Material's own colours mapped to Blaze, so no default purple / lavender can leak into anything Material draws.
    androidx.compose.material3.MaterialTheme(colorScheme = blazeScheme(dark), shapes = BlazeShapes.material) {
        CompositionLocalProvider(LocalBlaze provides c, content = content)
    }
}

/**
 * v0.24.8 "no square UI": the one set of corner radii every Blaze surface uses. Inputs and buttons are pills or [medium],
 * cards [medium], sheets and dialogs [large], tiny marks (checkboxes, tags inside rows) [small]. Nothing is square:
 * `ui/NoSquareTest` fails the build if RectangleShape or RoundedCornerShape(0 appears in ui/.
 */
object BlazeShapes {
    val xs = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    val small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    val pill = androidx.compose.foundation.shape.CircleShape
    /** Material's own shapes mapped to the same radii, so any Material piece that slips in is rounded too. */
    val material = androidx.compose.material3.Shapes(
        extraSmall = xs, small = small, medium = medium, large = large,
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
}

/** Primary buttons: the classic Blaze blue, solid (v0.25.2; the v0.24.8 orange-to-blue gradient is gone). */
/**
 * v0.31.0: monochrome. [primary] is a bright frosted-glass fill that white text reads on (every existing caller draws
 * white text on it); [blue] / [orange] keep their names but are greys / a desaturated warm for warnings.
 */
object BlazeBrush {
    val orange = Color(0xFFD6B48E)
    val blue = Color(0xFFDADADF)
    val primary: androidx.compose.ui.graphics.Brush get() = androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.18f)))
    val primarySoft: androidx.compose.ui.graphics.Brush get() = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.10f))
}

/** Material colour scheme from Blaze's tokens (v0.24.5): blue primary, orange secondary, Blaze surfaces; never purple. */
fun blazeScheme(dark: Boolean): androidx.compose.material3.ColorScheme {
    // v0.31.0: Material's slots mapped to the greyscale glass palette, so stock components pick up the look.
    // Containers are opaque greys (Material layers them), surfaces sit a step above the near-black ground.
    return if (dark) androidx.compose.material3.darkColorScheme(
        primary = GlassTokens.accent, onPrimary = GlassTokens.onAccent, primaryContainer = Color(0xFF2A2A2E), onPrimaryContainer = GlassTokens.ink,
        secondary = Color(0xFFBDBDC2), onSecondary = GlassTokens.onAccent, secondaryContainer = Color(0xFF242427), onSecondaryContainer = GlassTokens.ink,
        tertiary = Color(0xFFA9A9AE), onTertiary = GlassTokens.onAccent, tertiaryContainer = Color(0xFF242427), onTertiaryContainer = GlassTokens.ink,
        background = GlassTokens.bg, onBackground = GlassTokens.ink, surface = Color(0xFF131315), onSurface = GlassTokens.ink,
        surfaceVariant = Color(0xFF1E1E21), onSurfaceVariant = GlassTokens.sub, surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFFEDEDEF), inverseOnSurface = Color(0xFF111112), inversePrimary = Color(0xFF3A3A3E),
        outline = Color(0x40FFFFFF), outlineVariant = Color(0x1FFFFFFF), scrim = Color.Black,
        surfaceBright = Color(0xFF242427), surfaceDim = Color(0xFF0A0A0C), surfaceContainerLowest = Color(0xFF0C0C0E),
        surfaceContainerLow = Color(0xFF121214), surfaceContainer = Color(0xFF161618), surfaceContainerHigh = Color(0xFF1B1B1E),
        surfaceContainerHighest = Color(0xFF212124), error = GlassTokens.error, onError = Color(0xFF1A0F0E),
        errorContainer = Color(0xFF3A2624), onErrorContainer = Color(0xFFF2D6D2))
    else androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF1C1C1F), onPrimary = Color.White, primaryContainer = Color(0xFFE4E4E7), onPrimaryContainer = Color(0xFF111112),
        secondary = Color(0xFF4A4A4F), onSecondary = Color.White, secondaryContainer = Color(0xFFE8E8EA), onSecondaryContainer = Color(0xFF111112),
        tertiary = Color(0xFF5A5A5F), onTertiary = Color.White, tertiaryContainer = Color(0xFFE8E8EA), onTertiaryContainer = Color(0xFF111112),
        background = LightColors.paper, onBackground = LightColors.ink, surface = Color(0xFFFFFFFF), onSurface = LightColors.ink,
        surfaceVariant = Color(0xFFEDEDEF), onSurfaceVariant = LightColors.sub, surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFF161618), inverseOnSurface = GlassTokens.ink, inversePrimary = GlassTokens.accent,
        outline = Color(0x40111112), outlineVariant = Color(0x1F111112), scrim = Color.Black,
        surfaceBright = Color.White, surfaceDim = Color(0xFFE6E6E8), surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF8F8F9), surfaceContainer = Color(0xFFF2F2F3), surfaceContainerHigh = Color(0xFFEDEDEF),
        surfaceContainerHighest = Color(0xFFE8E8EA), error = Color(0xFFA8554D), onError = Color.White)
}

/** Sun/moon: tap flips light and dark, long-press goes back to following the phone. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ThemeButton(modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(modifier.size(38.dp).hairline(c.hair, BlazeShapes.pill).combinedClickable(
        onClick = { ThemeMode.set(if (c.dark) "light" else "dark") },
        onLongClick = { ThemeMode.set("system"); android.widget.Toast.makeText(ctx, "Following your phone's theme", android.widget.Toast.LENGTH_SHORT).show() }
    ), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(16.dp)) {
            val r = size.minDimension / 2
            if (c.dark) {
                // Sun: disc and eight rays (tap for light).
                drawCircle(c.ink, r * 0.42f, center)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val dx = kotlin.math.cos(a).toFloat(); val dy = kotlin.math.sin(a).toFloat()
                    drawLine(c.ink, Offset(center.x + dx * r * 0.62f, center.y + dy * r * 0.62f), Offset(center.x + dx * r * 0.95f, center.y + dy * r * 0.95f), 1.6.dp.toPx())
                }
            } else {
                // Moon: a disc with a bite taken out (tap for dark).
                drawCircle(c.ink, r * 0.8f, center)
                drawCircle(c.paper, r * 0.66f, Offset(center.x + r * 0.42f, center.y - r * 0.3f))
            }
        }
    }
}

/** Uppercase mono label, like the site's eyebrows and nav. */
@Composable
fun Label(text: String, color: Color = LocalBlaze.current.sub, size: TextUnit = 11.sp, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text.uppercase(),
        modifier = modifier,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Type.Mono, fontSize = size, letterSpacing = 0.14.em, color = color)
    )
}

/** 1px hairline on the bottom edge. */
fun Modifier.hairlineBottom(color: Color) = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
}

fun Modifier.hairlineTop(color: Color) = drawBehind {
    drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f)
}

/**
 * 1dp outline that follows a rounded [shape] (v0.24.8: [BlazeShapes.medium] by default, never square) and clips what comes
 * after it (content, ripples) to the same shape. Give the background before it the same shape: `.background(x, shape)`.
 */
fun Modifier.hairline(color: Color, shape: androidx.compose.ui.graphics.Shape = BlazeShapes.medium) =
    clip(shape).border(1.dp, color, shape)

/** v0.29.4: the particle blues (light theme: a deep sky blue on paper; dark: a bright periwinkle on charcoal). */
object ParticleBlue {
    // v0.31.0: monochrome (the name stays for callers): soft white dust on the dark glass, graphite on light.
    fun of(dark: Boolean): Color = if (dark) Color(0xFFD8D8DC) else Color(0xFF6A6A70)
    fun hot(dark: Boolean): Color = if (dark) Color(0xFFFFFFFF) else Color(0xFF2A2A2E)
}

private data class Dot(val x: Float, val y: Float, val r: Float, val vx: Float, val vy: Float)

/** Faint drifting particle dots (the site's button-hover particles), livelier while Blaze works. */
@Composable
fun ParticleField(mood: Mood, modifier: Modifier = Modifier, overlay: Boolean = false, paused: Boolean = false) {
    val c = LocalBlaze.current
    if (!com.blaze.assistant.agent.Settings.particles) return
    val energy by animateFloatAsState(if (mood == Mood.IDLE) 0f else 1f, tween(700), label = "e")
    // A slow clock instead of 60/120 fps: ~12 fps when idle, ~30 fps while working.
    var time by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val busy = mood != Mood.IDLE
    LaunchedEffect(busy, paused) {
        if (paused) return@LaunchedEffect
        var last = 0L
        val gap = if (busy) 33L else 85L
        while (true) {
            androidx.compose.runtime.withFrameMillis { now ->
                if (last == 0L) last = now
                if (now - last >= gap) { time = (time + (now - last) / 60_000f) % 1f; last = now }
            }
            if (!busy) delay(gap - 16)
        }
    }
    val dots = remember {
        val rnd = Random(11)
        List(44) {
            Dot(rnd.nextFloat(), rnd.nextFloat(), 0.8f + rnd.nextFloat() * 0.9f,
                (rnd.nextFloat() - 0.5f) * 2f, -0.6f - rnd.nextFloat() * 1.4f)
        }
    }
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val w = size.width; val h = size.height
        val speed = 1f + energy * 5f
        dots.forEachIndexed { i, d ->
            val px = ((d.x + d.vx * time * speed) % 1f + 1f) % 1f
            val py = ((d.y + d.vy * time * speed) % 1f + 1f) % 1f
            // v0.29.4: blue in both themes and easier to see (they were faint grey ink).
            val a = (if (c.dark) 0.34f else 0.30f) + 0.18f * energy + if (i % 7 == 0) 0.14f else 0f
            val col = if (i % 9 == 0 && energy > 0.1f) ParticleBlue.hot(c.dark).copy(alpha = 0.75f * energy) else ParticleBlue.of(c.dark).copy(alpha = a.coerceAtMost(0.8f))
            drawCircle(if (overlay) col.copy(alpha = col.alpha * 0.8f) else col, d.r * 1.35f * density, Offset(px * w, py * h))
        }
    }
}

/**
 * Brand mark (v0.24.8): Blaze's mascot in a dark circle (it used to be a square with a pixel "B"). While Blaze works a
 * thin orange-to-blue ring spins around it.
 */
@Composable
fun BrandMark(mood: Mood, size: Dp, modifier: Modifier = Modifier) {
    val busy = mood != Mood.IDLE
    Box(modifier.size(size).clip(BlazeShapes.pill).background(Color(com.blaze.assistant.phone.Mascot.ICON_BG), BlazeShapes.pill),
        contentAlignment = Alignment.Center) {
        // v0.32.0: the new Blaze icon (glass drop in its gold halo); a spinning ring around it while Blaze works.
        DuoEmblem(size * 1.05f, animate = busy)
    }
}
