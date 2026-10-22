package com.blaze.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * v0.31.0 the water droplet. Replaces every "Thinking…" / "Thought it through" / typing line in the thread: a small
 * glossy greyscale liquid-glass drop. While a reply streams it rides the end of the newest word (spring); when the
 * reply drops a rich block (diagram, picture, chart, table…) it FALLS onto that block's preview window (gravity ease,
 * a squash and a ripple), then follows the text that continues below. When the stream ends it settles and dissolves.
 *
 * Performance: one Canvas per live reply, moved with graphicsLayer only (no relayout); the wobble clock runs only
 * while the drop is on screen and motion is on; with Remove animations it just appears / fades, never falls.
 */

/** Where the droplet should go, in the reply's own coordinates. One per streaming reply ([LocalOrb]). */
class OrbTracker {
    /** The reply's root layout (set by the reply's Box). */
    var root: LayoutCoordinates? = null
    var target by mutableStateOf(Offset.Unspecified)
        private set
    /** True when [target] is the top of a rich block's preview window (the drop lands there with a splash). */
    var onCard by mutableStateOf(false)
        private set

    /**
     * v0.31.2 where the pair rests: a small slot reserved at the end of the reply's Time · Copy · Quote · Retry row,
     * so the waiting drop + halo never sit on top of the words (Unspecified until that row is laid out).
     */
    var rest by mutableStateOf(Offset.Unspecified)
        private set

    fun aimRest(coords: LayoutCoordinates) {
        val r = root ?: return
        if (!r.isAttached || !coords.isAttached) return
        val c = Offset(coords.size.width / 2f, coords.size.height / 2f)
        val p = try { r.localPositionOf(coords, c) } catch (_: Exception) { return }
        if (p != rest) rest = p
    }

    /** Aim at [local] inside [coords]. */
    fun aim(coords: LayoutCoordinates, local: Offset, card: Boolean) {
        val r = root ?: return
        if (!r.isAttached || !coords.isAttached) return
        val p = try { r.localPositionOf(coords, local) } catch (_: Exception) { return }
        if (p != target || card != onCard) { target = p; onCard = card }
    }

    /** Aim just after the last character of a laid-out text (vertically centred on its last line). */
    fun aimAtTextEnd(coords: LayoutCoordinates, layout: TextLayoutResult, gapPx: Float) {
        if (layout.lineCount == 0) return
        val line = layout.lineCount - 1
        val x = layout.getLineRight(line) + gapPx
        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        aim(coords, Offset(x, y), card = false)
    }
}

val LocalOrb = staticCompositionLocalOf<OrbTracker?> { null }

/**
 * v0.31.1 where the waiting drop sits: exactly where the reply's first line will start, so the hand-over to the reply's
 * droplet layer is seamless. The reply's text starts 2 dp down in its bubble with a 23 sp line (centre ≈ 13.5 dp); the
 * thinking drop is drawn at the centre of a [SIZE] × 2.6 canvas, [SIZE] × 0.7 from its left edge.
 */
object OrbAnchor {
    val SIZE: Dp = 14.dp
    /** Moves the thinking drop's centre onto the first line's centre. */
    val thinkingOffsetY: Dp = 13.5.dp - SIZE * 1.3f
    /** The drop's centre x where a line starts (same as the thinking drop). */
    val startX: Dp = SIZE * 0.7f
}

/** Pure helpers (tested in spirit by the motion rules): how long a fall of [dyPx] takes, and gravity's ease. */
object OrbMotion {
    /** Falls are short and grow with the distance like a real drop (t ∝ √d), clamped to 220–560 ms. */
    fun fallMs(dyPx: Float, density: Float): Int = (sqrt((dyPx / density).coerceAtLeast(0f)) * 38f).toInt().coerceIn(220, 560)
    /** Accelerating like gravity (quadratic ease-in). */
    val Gravity: Easing = Easing { t -> t * t }
    /** A drop of more than this (dp) is a fall instead of a glide. */
    const val FALL_DP = 28f
}

/**
 * Draws the droplet: a wobbling liquid outline (sin deformations of a circle), a frosted fill brighter at the top-left,
 * a dark refracted crescent at the bottom, a caustic glint, a specular highlight and a faint glow.
 * [stretch] > 1 elongates it vertically (falling), < 1 squashes it (landing).
 */
fun DrawScope.drawWaterOrb(center: Offset, r: Float, t: Float, stretch: Float = 1f, alpha: Float = 1f) {
    if (alpha <= 0f || r <= 0f) return
    // glow
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f * alpha), Color.Transparent), center = center, radius = r * 2.4f),
        radius = r * 2.4f, center = center)
    val path = Path()
    val n = 28
    val sx = 1f / sqrt(stretch.coerceAtLeast(0.2f)); val sy = stretch
    for (i in 0..n) {
        val a = (i.toFloat() / n) * 2f * PI.toFloat()
        val k = 1f + 0.055f * sin(3f * a + t * 2.2f) + 0.03f * sin(5f * a - t * 3.1f) + 0.02f * sin(2f * a + t * 1.3f)
        // a drop is a little heavier at the bottom
        val heavy = 1f + 0.06f * ((sin(a) + 1f) / 2f)
        val x = center.x + cos(a) * r * k * sx
        val y = center.y + sin(a) * r * k * sy * heavy
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    // body: frosted, brighter at the top-left
    drawPath(path, Brush.radialGradient(listOf(Color.White.copy(alpha = 0.62f * alpha), Color.White.copy(alpha = 0.20f * alpha),
        Color(0xFF9A9AA0).copy(alpha = 0.18f * alpha)), center = Offset(center.x - r * 0.35f, center.y - r * 0.4f), radius = r * 1.6f))
    // refraction: the ground seen through the drop, darker at the bottom-right
    drawPath(path, Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f * alpha)),
        center = Offset(center.x - r * 0.2f, center.y - r * 0.25f), radius = r * 1.25f))
    // rim
    drawPath(path, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.75f * alpha), Color.White.copy(alpha = 0.10f * alpha)),
        start = Offset(center.x - r, center.y - r), end = Offset(center.x + r, center.y + r)), style = Stroke(width = (r * 0.11f).coerceAtLeast(1f)))
    // caustic: light focused through the drop onto its lower edge
    drawArc(Color.White.copy(alpha = 0.38f * alpha), startAngle = 35f, sweepAngle = 70f, useCenter = false,
        topLeft = Offset(center.x - r * 0.62f * sx, center.y - r * 0.55f * sy), size = Size(r * 1.24f * sx, r * 1.24f * sy),
        style = Stroke(width = r * 0.14f))
    // specular highlight, top-left
    drawOval(Color.White.copy(alpha = 0.92f * alpha), topLeft = Offset(center.x - r * 0.58f * sx, center.y - r * 0.62f * sy),
        size = Size(r * 0.42f, r * 0.26f))
    drawCircle(Color.White.copy(alpha = 0.7f * alpha), radius = r * 0.07f, center = Offset(center.x + r * 0.30f * sx, center.y - r * 0.42f * sy))
}

/** A ripple where the drop landed: two thin expanding ellipses and two tiny splash beads ([p] 0 → 1). */
fun DrawScope.drawSplash(at: Offset, r: Float, p: Float) {
    if (p <= 0f || p >= 1f) return
    val fade = 1f - p
    for (k in 0..1) {
        val q = (p - k * 0.18f).coerceIn(0f, 1f)
        if (q <= 0f) continue
        val w = r * (1.2f + q * 6f); val h = w * 0.28f
        drawOval(Color.White.copy(alpha = 0.45f * fade * (1f - k * 0.4f)), topLeft = Offset(at.x - w, at.y + r * 0.6f - h), size = Size(w * 2f, h * 2f),
            style = Stroke(width = (r * 0.12f).coerceAtLeast(1f)))
    }
    val up = sin(p * PI.toFloat()) * r * 2.2f
    for (s in listOf(-1f, 1f)) drawCircle(Color.White.copy(alpha = 0.7f * fade), radius = r * 0.16f,
        center = Offset(at.x + s * r * (0.8f + p * 2.2f), at.y - up))
}

/** The wobble clock: seconds while [running], frozen otherwise (no frames). */
@Composable
private fun orbClock(running: Boolean): androidx.compose.runtime.State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now -> if (last != 0L) t.floatValue += (now - last) / 1_000_000_000f; last = now }
    }
    return t
}

/**
 * The droplet layer of one reply. Put it last inside the reply's Box (it draws over the text, laid out at TopStart and
 * moved with graphicsLayer). [live] = the reply is streaming.
 *
 * v0.31.2 Halo x Blaze: the drop sits inside its golden halo. The SPEAKER's half leaves home and rides the end of the
 * text (Blaze: the drop, Halo: the ring) while the other half waits at the start of the reply. When the stream ends the
 * traveller glides home, they join and dim, and stay there waiting while [latest] (the newest reply, chat idle);
 * otherwise they fade away.
 */
@Composable
fun BoxScope.OrbLayer(tracker: OrbTracker, live: Boolean, size: Dp = 14.dp, speaker: com.blaze.assistant.agent.Duo.Who =
    com.blaze.assistant.agent.Duo.Who.BLAZE, latest: Boolean = false, alone: Boolean = false) {
    val motion = MotionTokens.enabled
    val d = LocalDensity.current
    var shown by remember { mutableStateOf(live) }
    val vis = remember { Animatable(if (live) 1f else 0f) }
    /** 1 = awake (streaming), WAIT_DIM = joined and waiting. */
    val dim = remember { Animatable(1f) }
    val pos = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val stretch = remember { Animatable(1f) }
    val splash = remember { Animatable(1f) }
    var started by remember { mutableStateOf(false) }
    var homing by remember { mutableStateOf(false) }
    /** v0.31.2: the traveller has reached the rest slot and now simply sits there with its other half. */
    var docked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haloSpeaks = speaker == com.blaze.assistant.agent.Duo.Who.HALO
    LaunchedEffect(live, latest) {
        if (live) {
            shown = true; homing = false; docked = false
            vis.snapTo(1f); dim.snapTo(1f)
        } else if (shown) {
            homing = true
            // v0.31.2 fix: finish OFF the words. The traveller leaves the last word and glides down into the rest slot at
            // the end of the Time · Copy · Retry row, joins its other half, and they dim and wait (newest reply) or fade.
            val rest = tracker.rest
            if (rest.isSpecified && started) {
                if (motion) pos.animateTo(rest, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)) else pos.snapTo(rest)
                docked = true
                if (latest) dim.animateTo(WAIT_DIM, tween(if (motion) 900 else MotionTokens.SHORT))
                else { vis.animateTo(0f, tween(if (motion) 420 else MotionTokens.SHORT)); shown = false }
            } else {
                // No rest slot on screen (a reply in the middle of a group): never park on the text, just fade away.
                vis.animateTo(0f, tween(if (motion) 320 else MotionTokens.SHORT)); shown = false
            }
        }
    }
    LaunchedEffect(tracker) {
        snapshotFlow { tracker.target to tracker.onCard }.collectLatest { (t, card) ->
            if (!t.isSpecified || homing) return@collectLatest
            if (!started) {
                // v0.31.1: start where the thinking drop was (the start of the first line), then glide onto the text end.
                started = true
                pos.snapTo(Offset(with(d) { OrbAnchor.startX.toPx() }, t.y))
                if (motion) pos.animateTo(t, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) else pos.snapTo(t)
                return@collectLatest
            }
            if (!motion) { pos.snapTo(t); return@collectLatest }
            val dy = t.y - pos.value.y
            if (dy > OrbMotion.FALL_DP * d.density) {
                val ms = OrbMotion.fallMs(dy, d.density)
                scope.launch { stretch.animateTo(1.22f, tween(ms / 2)) }
                pos.animateTo(t, tween(ms, easing = OrbMotion.Gravity))
                // landing: squash, spring back, splash if it landed on a preview window
                scope.launch { stretch.snapTo(0.72f); stretch.animateTo(1f, spring(0.42f, Spring.StiffnessMediumLow)) }
                if (card) scope.launch { splash.snapTo(0f); splash.animateTo(1f, tween(560)) }
            } else {
                pos.animateTo(t, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
            }
        }
    }
    if (!shown) return
    val clock by orbClock(running = motion && (live || !docked))
    val boxDp = size * 5
    val half = with(d) { boxDp.toPx() } / 2f
    // The half that waits in the rest slot while the other one travels (only once that slot is laid out).
    // v0.32.0 split: [alone] = the other half is off working (Blaze's drop), so only the speaker's half is drawn.
    if (tracker.rest.isSpecified && started && !alone) {
        Canvas(Modifier.align(Alignment.TopStart).size(boxDp).graphicsLayer {
            val h = tracker.rest
            translationX = h.x - half; translationY = h.y - half
            alpha = vis.value * minOf(0.6f, dim.value)
        }) {
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val r = size.toPx() / 2f
            if (haloSpeaks) drawWaterOrb(c, r, clock) else drawHaloRing(c, r, clock)
        }
    }
    // The traveller (the speaker's half). Once docked it follows the slot (the row can still move as the list settles).
    Canvas(Modifier.align(Alignment.TopStart).size(boxDp).graphicsLayer {
        val p = if (docked && tracker.rest.isSpecified) tracker.rest else pos.value
        translationX = p.x - half; translationY = p.y - half
        alpha = vis.value * dim.value
        val s = 0.55f + 0.45f * vis.value
        scaleX = s; scaleY = s
    }.semantics { contentDescription = if (haloSpeaks) "Halo is writing" else "Blaze is writing" }) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val r = size.toPx() / 2f
        if (haloSpeaks) drawHaloRing(c, r, clock)
        else { drawSplash(c, r, splash.value); drawWaterOrb(c, r, clock, stretch.value) }
    }
}

/** How bright the joined drop + halo stay while they wait after a reply (never gone). */
const val WAIT_DIM = 0.38f

/**
 * The droplet on its own, where a status line used to be ("Thinking it through…", the empty "Working" card):
 * it wobbles and bobs gently inside its golden halo while Blaze and Halo think. [label] is read by TalkBack.
 */
@Composable
fun ThinkingOrb(label: String, modifier: Modifier = Modifier, size: Dp = 16.dp, ring: Float = 1f) {
    val motion = MotionTokens.enabled
    val clock by orbClock(running = motion)
    val appear = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) appear.animateTo(1f, spring(0.8f, Spring.StiffnessMediumLow)) }
    Canvas(modifier.size(size * 2.6f).semantics { contentDescription = label }.graphicsLayer {
        alpha = appear.value; scaleX = 0.6f + 0.4f * appear.value; scaleY = scaleX
    }) {
        val r = size.toPx() / 2f
        val bob = if (motion) sin(clock * 2.4f) * r * 0.22f else 0f
        val c = Offset(r * 1.4f, this.size.height / 2f + bob)
        // v0.31.2: the halo breathes around the drop (drawn outside the canvas bounds; nothing clips it).
        drawHaloRing(c, r, clock, alpha = ring * (0.9f + 0.1f * sin(clock * 1.3f)))
        drawWaterOrb(c, r, clock, 1f + (if (motion) sin(clock * 4.8f) * 0.04f else 0f))
    }
}
