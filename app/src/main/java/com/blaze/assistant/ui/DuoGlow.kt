package com.blaze.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.blaze.assistant.agent.Duo
import kotlinx.coroutines.launch
import kotlin.math.sin

/*
 * v0.31.2 Halo x Blaze, the look.
 *  - The glassy drop (Blaze) now sits inside a golden halo ring (Halo), like the app icon.
 *  - When Blaze writes, the drop leaves the ring and rides the end of the text; the newest words shimmer BLUE behind it.
 *  - When Halo writes, the ring leaves the drop and rides ahead of the text; the newest words shimmer GOLD behind it.
 *  - When the reply ends, one last band of that colour sweeps over the words and fades; the traveller glides home,
 *    the two join again and dim, and stay there waiting (on the newest reply only).
 * Everything is draw-only (no relayout), and nothing moves with Remove animations on.
 */

/** Who is speaking in the reply being drawn (Blaze by default). */
val LocalSpeaker = compositionLocalOf { Duo.Who.BLAZE }

object DuoColors {
    // v0.45.2: a picked theme tints the drop and its halo (ThemeTint, Compose state: the orb redraws when it changes).
    val blazeBlue: Color get() = ThemeTint.current.value?.let { Color(it.droplet) } ?: Color(0xFF4DA8FF)
    val blazeIce: Color get() = ThemeTint.current.value?.let { Color(LivingLogic.mix(it.droplet, 0xFFFFFFFF, 0.6f)) } ?: Color(0xFFBFE3FF)
    val haloGold: Color get() = ThemeTint.current.value?.let { Color(it.halo) } ?: Color(0xFFFFC94D)
    val haloCream: Color get() = ThemeTint.current.value?.let { Color(LivingLogic.mix(it.halo, 0xFFFFFFFF, 0.6f)) } ?: Color(0xFFFFF0C2)
    fun main(w: Duo.Who) = if (w == Duo.Who.HALO) haloGold else blazeBlue
    fun light(w: Duo.Who) = if (w == Duo.Who.HALO) haloCream else blazeIce
}

/** The halo: a glowing gold ring of radius ≈ 1.75 [r] around [c], breathing gently with the clock [t]. */
fun DrawScope.drawHaloRing(c: Offset, r: Float, t: Float, alpha: Float = 1f) {
    if (alpha <= 0.01f || r <= 0f) return
    val ringR = r * 1.75f * (1f + 0.03f * sin(t * 1.7f))
    val w = (r * 0.2f).coerceAtLeast(1.2f)
    val outer = ringR + w * 3.2f
    val gold = DuoColors.haloGold
    // soft glow on both sides of the ring
    drawCircle(Brush.radialGradient(
        0f to Color.Transparent,
        ((ringR - w * 3f) / outer).coerceIn(0f, 1f) to Color.Transparent,
        (ringR / outer) to gold.copy(alpha = 0.42f * alpha),
        1f to Color.Transparent,
        center = c, radius = outer), radius = outer, center = c)
    // the ring itself, brighter at the top-left like the drop
    drawCircle(Brush.linearGradient(listOf(DuoColors.haloCream.copy(alpha = alpha), gold.copy(alpha = 0.95f * alpha), Color(0xFFE0A21F).copy(alpha = 0.9f * alpha)),
        start = Offset(c.x - ringR, c.y - ringR), end = Offset(c.x + ringR, c.y + ringR)), radius = ringR, center = c, style = Stroke(width = w))
    drawCircle(Color.White.copy(alpha = 0.55f * alpha), radius = ringR, center = c, style = Stroke(width = w * 0.32f))
}

/**
 * The shimmer over a text block. [tracking] = this block is the streaming edge: a glow in the speaker's colour sits on
 * the newest words (a light glint runs back over them); when tracking ends, one band sweeps the block and fades away.
 * [layout] gives the block's current text layout (read at draw time).
 */
@Composable
fun Modifier.duoShimmer(speaker: Duo.Who, tracking: Boolean, layout: () -> TextLayoutResult?): Modifier {
    if (!MotionTokens.enabled) return this
    val glow = remember { Animatable(0f) }
    val sweep = remember { Animatable(-1f) }
    var wasTracking by remember { mutableStateOf(false) }
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(tracking) {
        if (tracking) { wasTracking = true; glow.animateTo(1f, tween(240)) }
        else if (wasTracking) {
            wasTracking = false
            launch { glow.animateTo(0f, tween(700)) }
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
            sweep.snapTo(-1f)
        }
    }
    val active = tracking || glow.value > 0.01f || sweep.value >= 0f
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now -> if (last != 0L) clock.floatValue += (now - last) / 1_000_000_000f; last = now }
    }
    if (!active) return this
    val main = DuoColors.main(speaker); val light = DuoColors.light(speaker)
    return this.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val l = layout() ?: return@drawWithContent
        if (l.lineCount == 0) return@drawWithContent
        val g = glow.value; val s = sweep.value; val t = clock.floatValue
        if (g > 0.01f) {
            val line = l.lineCount - 1
            val cx = l.getLineRight(line); val top = l.getLineTop(line); val bottom = l.getLineBottom(line)
            val cy = (top + bottom) / 2f
            val reach = 150.dp.toPx()
            // the colour: strongest at the newest words, fading back into the text colour
            drawRect(Brush.radialGradient(listOf(main.copy(alpha = 0.95f * g), main.copy(alpha = 0.55f * g), Color.Transparent),
                center = Offset(cx, cy), radius = reach), blendMode = BlendMode.SrcAtop)
            // a glint that runs back over the last line, again and again
            val bx = cx - reach * ((t * 0.85f) % 1f)
            val band = 22.dp.toPx()
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, light.copy(alpha = 0.85f * g), Color.Transparent), startX = bx - band, endX = bx + band),
                topLeft = Offset(0f, top), size = Size(size.width, bottom - top), blendMode = BlendMode.SrcAtop)
        }
        if (s in 0f..1f) {
            // the closing sweep: a diagonal band of the speaker's light crossing the block, fading as it goes
            val w = size.width; val h = size.height
            val x = -w * 0.35f + w * 1.7f * s
            val a = (1f - s) * (if (s < 0.1f) s / 0.1f else 1f)
            val half = 90.dp.toPx()
            drawRect(Brush.linearGradient(listOf(Color.Transparent, main.copy(alpha = 0.9f * a), light.copy(alpha = 0.9f * a), main.copy(alpha = 0.9f * a), Color.Transparent),
                start = Offset(x - half, 0f), end = Offset(x + half, h.coerceAtMost(half))), blendMode = BlendMode.SrcAtop)
        }
    }
}

/**
 * v0.32.0 the Blaze icon everywhere in the app: the glass drop (Blaze) inside its gold halo (Halo), centred in a square of
 * side [w]. [t] = clock for the wobble and the breathing ring (0 = a still picture).
 */
fun DrawScope.drawDuoEmblem(center: Offset, w: Float, t: Float = 0f, alpha: Float = 1f) {
    val r = w * 0.19f
    drawHaloRing(center, r, t, alpha)
    drawWaterOrb(center, r, t, alpha = alpha)
}

/** The new Blaze icon as a composable: still when idle, gently alive when [animate]. */
@Composable
fun DuoEmblem(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, animate: Boolean = false) {
    val clock = if (animate && com.blaze.assistant.ui.MotionTokens.enabled) {
        val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "emblem")
        tr.animateFloat(0f, (2 * Math.PI * 10).toFloat(), androidx.compose.animation.core.infiniteRepeatable(
            tween(20_000, easing = androidx.compose.animation.core.LinearEasing)), label = "t").value
    } else 0f
    androidx.compose.foundation.Canvas(modifier.then(Modifier.size(size))) {
        drawDuoEmblem(Offset(this.size.width / 2f, this.size.height / 2f), this.size.minDimension, clock)
    }
}

/** v0.32.0: lets the phone pointer (a plain View) draw the icon through a CanvasDrawScope. */
object DuoGlowAccess {
    fun DrawScope.emblem(center: Offset, w: Float, t: Float) = drawDuoEmblem(center, w, t)
}
