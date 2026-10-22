package com.blaze.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * v0.24.6: the rounded dark Blaze look used by the browser, the page pointer, the assistant overlay and
 * the Ask-about-this-page sheet. Always dark (the overlay sits on top of any app), brand blue + orange
 * from the mascot, pills and circles instead of squares, no stock Material components.
 */
object Glass {
    // v0.31.0: greyscale frosted glass (GlassKit.kt). Names kept; blue / orange are now monochrome accents.
    val bg = GlassTokens.bg
    val surface = Color(0xFF141416)
    val raised = Color(0x1AFFFFFF)
    val raised2 = Color(0x26FFFFFF)
    val border = Color(0x26FFFFFF)
    val text = GlassTokens.ink
    val sub = GlassTokens.sub
    val weak = GlassTokens.weak
    val blue = Color(0xFFE6E6EA)
    val orange = Color(0xFFBDBDC2)
    val cyan = Color(0xFFD0D0D6)
    val privateBg = Color(0xFF16161A)
    val brand: Brush get() = androidx.compose.ui.graphics.SolidColor(blue)
    val pill = RoundedCornerShape(50)
    val card = RoundedCornerShape(24.dp)
}

/** Round icon button with a content description (TalkBack reads it; no text labels on the button). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassIconButton(
    icon: ImageVector, desc: String, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color = Glass.text,
    bg: Color = Glass.raised, enabled: Boolean = true, bordered: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    // v0.27.0: shrinks slightly while pressed (MotionTokens; none with Remove animations on).
    val press = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // v0.31.0: the default fill is a frosted glass disc (rim + sheen); a custom [bg] keeps a flat fill.
    Box(modifier.pressScale(press).size(size)
        .let { if (bg == Glass.raised) it.glass(CircleShape, GlassIntensity.Low, shadow = false) else it.clip(CircleShape).background(bg, CircleShape) }
        .let { if (bordered && bg != Glass.raised) it.border(1.dp, Glass.border, CircleShape) else it }
        .combinedClickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current,
            enabled = enabled, onClick = onClick, onLongClick = onLongClick, onClickLabel = desc)
        .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else Glass.weak, modifier = Modifier.size(size * 0.5f))
    }
}

/** Mascot geometry (same numbers as Luke's art): body circle radius 1 at (0, 0), y down, flame tip up-right. */
object MascotArt {
    private val start = floatArrayOf(0.06f, -1.53f)
    private val cubics = listOf(
        floatArrayOf(-0.35f, -1.35f, -1.0f, -0.62f, -1.0f, 0.0f),
        floatArrayOf(-1.0f, 0.552f, -0.552f, 1.0f, 0.0f, 1.0f),
        floatArrayOf(0.552f, 1.0f, 1.0f, 0.552f, 1.0f, 0.0f),
        floatArrayOf(1.0f, -0.55f, 0.66f, -0.80f, 0.42f, -1.02f),
        floatArrayOf(0.30f, -1.12f, 0.25f, -1.30f, 0.24f, -1.45f),
        floatArrayOf(0.24f, -1.56f, 0.12f, -1.58f, 0.06f, -1.53f))
    /** The flame tip, the pointer's click point (units). */
    val TIP = floatArrayOf(0.16f, -1.555f)

    /** Units → px for a mascot drawn in a [boxPx] square: scale and the body centre. */
    fun scale(boxPx: Float) = boxPx / 3.4f
    fun centre(boxPx: Float) = Offset(boxPx / 2f, boxPx / 2f + 0.29f * scale(boxPx))
    /** Where the tip sits inside a [boxPx] square (so a pointer can put the tip exactly on a point). */
    fun tipIn(boxPx: Float): Offset { val k = scale(boxPx); val c = centre(boxPx); return Offset(c.x + TIP[0] * k, c.y + TIP[1] * k) }

    /** The tip inside a [MascotGlyph] of [boxPx] (the glyph draws at 0.82 of [scale]). */
    fun glyphTip(boxPx: Float): Offset { val k = scale(boxPx) * 0.82f; val c = centre(boxPx); return Offset(c.x + TIP[0] * k, c.y + TIP[1] * k) }

    fun path(c: Offset, k: Float): Path = Path().apply {
        moveTo(c.x + start[0] * k, c.y + start[1] * k)
        cubics.forEach { q -> cubicTo(c.x + q[0] * k, c.y + q[1] * k, c.x + q[2] * k, c.y + q[3] * k, c.x + q[4] * k, c.y + q[5] * k) }
        close()
    }

    private val stops = arrayOf(0f to Color(0xFFFFC15A), 0.18f to Color(0xFFFFA35C), 0.26f to Color(0xFFD99E84), 0.34f to Color(0xFF9AA6C6),
        0.42f to Color(0xFF6FAEF2), 0.70f to Color(0xFF5AA8FC), 1f to Color(0xFF4C93FE))

    /** Draws the mascot (glow, gradient body, cyan highlight, two eyes) with its body centre at [c]. */
    fun DrawScope.drawMascot(c: Offset, k: Float, glow: Float = 1f, outline: Color? = null) {
        if (glow > 0f) {
            drawCircle(Brush.radialGradient(listOf(Color(0x59FF9A3C).copy(alpha = 0.35f * glow), Color.Transparent),
                center = Offset(c.x + 0.05f * k, c.y - 0.95f * k), radius = 1.35f * k), 1.35f * k, Offset(c.x + 0.05f * k, c.y - 0.95f * k))
            drawCircle(Brush.radialGradient(listOf(Color(0x663D7BFF).copy(alpha = 0.4f * glow), Color.Transparent),
                center = Offset(c.x, c.y + 0.25f * k), radius = 1.6f * k), 1.6f * k, Offset(c.x, c.y + 0.25f * k))
        }
        val p = path(c, k)
        drawPath(p, Brush.verticalGradient(*stops, startY = c.y - 1.58f * k, endY = c.y + 1.0f * k))
        drawPath(p, Brush.radialGradient(listOf(Color(0xB366D8FF), Color.Transparent), center = Offset(c.x, c.y + 0.08f * k), radius = 0.78f * k))
        if (outline != null) drawPath(p, outline, style = Stroke(width = 0.09f * k))
        val ew = 0.18f * k; val eh = 0.30f * k
        listOf(-0.31f, 0.30f).forEach { ex ->
            drawRoundRect(Color(0xFF23252D), Offset(c.x + ex * k - ew / 2, c.y - 0.16f * k - eh / 2), Size(ew, eh), CornerRadius(0.09f * k, 0.09f * k))
        }
    }
}

/**
 * The mascot at [size]. [pulse] = listening / working: soft rings breathe out from it (only then does it animate,
 * so an idle mascot costs no frames).
 */
@Composable
fun MascotGlyph(size: Dp, modifier: Modifier = Modifier, pulse: Boolean = false, level: Float = 0f) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (pulse) {
            val t = rememberInfiniteTransition(label = "mascotPulse")
            val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "p")
            val b by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "b")
            Canvas(Modifier.size(size)) {
                val r = this.size.minDimension / 2f
                for (i in 0..1) {
                    val ph = (p + i * 0.5f) % 1f
                    drawCircle(Glass.blue.copy(alpha = (1f - ph) * (0.35f + level.coerceIn(0f, 1f) * 0.3f)), r * (0.55f + ph * 0.45f), style = Stroke(1.5f * density))
                }
            }
            Canvas(Modifier.size(size).graphicsLayer { scaleX = b; scaleY = b }) {
                drawDuoEmblem(Offset(this.size.width / 2f, this.size.height / 2f), this.size.minDimension, p * 6.283f)   // v0.32.0 new icon
            }
        } else Canvas(Modifier.size(size)) {
            drawDuoEmblem(Offset(this.size.width / 2f, this.size.height / 2f), this.size.minDimension)   // v0.32.0 new icon
        }
    }
}

/** Page-load progress in brand colours: a rounded gradient fill with a light sweeping across while it loads. */
@Composable
fun BrandProgress(progress: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp)) {
        if (progress in 0..99) {
            val t = rememberInfiniteTransition(label = "shimmer")
            val x by t.animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x")
            Box(Modifier.fillMaxWidth(progress.coerceIn(4, 100) / 100f).fillMaxHeight().clip(Glass.pill).background(Glass.brand))
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * (progress.coerceIn(4, 100) / 100f)
                val cx = w * x
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent), startX = cx - 40.dp.toPx(), endX = cx + 40.dp.toPx()),
                    size = Size(w, size.height))
            }
        }
    }
}

/** Pure maths for the edge particles (tested): a point [u] (0..1) around the perimeter of a w×h rectangle, inset by [inset]. */
object EdgePath {
    fun at(u: Float, w: Float, h: Float, inset: Float): Offset {
        val W = (w - inset * 2).coerceAtLeast(1f); val H = (h - inset * 2).coerceAtLeast(1f)
        val per = 2 * (W + H)
        var d = (((u % 1f) + 1f) % 1f) * per
        if (d < W) return Offset(inset + d, inset); d -= W
        if (d < H) return Offset(inset + W, inset + d); d -= H
        if (d < W) return Offset(inset + W - d, inset + H); d -= W
        return Offset(inset, inset + H - d)
    }
}

/**
 * The signature particle effect running around the screen edges while the assistant is summoned: round
 * dots in the memory graph's style (ink white with brand blue / orange sparks) drifting along the border, with
 * faint links between neighbours. ~24 fps, 40 dots, gone (no frames) as soon as it leaves composition.
 */
@Composable
fun EdgeParticles(modifier: Modifier = Modifier, energy: Float = 1f) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { now -> if (last == 0L) last = now; if (now - last >= 40) { time += (now - last) / 1000f; last = now } }
            delay(24)
        }
    }
    val dots = remember {
        val r = Random(23)
        List(40) { floatArrayOf(r.nextFloat(), 0.012f + r.nextFloat() * 0.02f, r.nextFloat(), r.nextFloat() * 6.283f) }
    }
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val d = density
        val pts = dots.mapIndexed { i, q ->
            val dir = if (i % 3 == 0) -1f else 1f
            val u = q[0] + dir * q[1] * time * (0.6f + energy * 0.6f)
            val wob = kotlin.math.sin(time * 1.7f + q[3]) * 5f * d
            EdgePath.at(u, size.width, size.height, 6f * d + q[2] * 10f * d + wob)
        }
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            val dist = (a - b).getDistance()
            if (dist < 60f * d) drawLine(Glass.blue.copy(alpha = 0.25f * (1f - dist / (60f * d)) * energy), a, b, 1f * d)
        }
        pts.forEachIndexed { i, p ->
            val col = when { i % 7 == 0 -> Glass.orange; i % 4 == 0 -> Glass.blue; else -> Color.White }
            val s = (2.2f + (i % 3)) * d
            val alpha = (0.45f + 0.35f * kotlin.math.sin(time * 2f + dots[i][3]).let { (it + 1f) / 2f }) * energy
            drawCircle(col.copy(alpha = alpha), s / 2, Offset(p.x, p.y))   // v0.24.8: round dots, no squares
        }
    }
}
