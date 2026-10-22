package com.blaze.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.device.CameraZoom
import com.blaze.assistant.device.Haptics
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v0.30.x Blaze Camera Pro: zoom presets, the half-circle zoom dial (protractor), tap-to-focus ring with an exposure
 * slider, the rule-of-thirds grid and the timer countdown. Stateless: [LiveCamera] owns the camera and the state, and
 * ShotsTest draws the same controls over a fake preview. The maths lives in [CameraZoom] (CameraZoomTest).
 */
@Immutable
data class CameraProState(
    /** Total zoom the user sees (hardware × digital). */
    val zoom: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 10f,
    /** The lens's hardware maximum; past it the zoom is digital (marked on the dial). */
    val hwMax: Float = 10f,
    val presets: List<Float> = listOf(1f, 2f, 5f, 10f),
    val dialOpen: Boolean = false,
    val grid: Boolean = false,
    /** Self-timer seconds: 0, 3 or 10. */
    val timer: Int = 0,
    /** Seconds left on a running countdown (0 = none). */
    val countdown: Int = 0,
    /** Where the user tapped to focus, in the camera screen's pixels; null hides the ring. */
    val focus: Offset? = null,
    /** null while focusing, then whether it locked. */
    val focusOk: Boolean? = null,
    val evIndex: Int = 0,
    val evLower: Int = 0,
    val evUpper: Int = 0,
    val evStep: Float = 0f,
    /** Grid and timer buttons (hidden in Scan, which has no shutter). */
    val tools: Boolean = true,
)

class CameraProActions(
    val onZoom: (Float) -> Unit = {},
    val onPreset: (Float) -> Unit = {},
    val onDial: (Boolean) -> Unit = {},
    /** Any touch on the dial: keeps it open. */
    val onDialTouch: () -> Unit = {},
    val onDialRelease: () -> Unit = {},
    val onGrid: () -> Unit = {},
    val onTimer: () -> Unit = {},
    val onEv: (Int) -> Unit = {},
    /** Any touch on the exposure slider: keeps the focus ring up. */
    val onFocusTouch: () -> Unit = {},
)

/** Blaze's accent on the dark camera (the dark theme's blue reads on black in either app theme). */
internal val CamAccent = GlassTokens.accent   // v0.31: monochrome
/** The part of the dial past the lens's hardware max (digital zoom). */
private val CamDigital = GlassTokens.warning
private val CamGlass = Color(0xFF0E0F13)

private val CountStyle = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 112.sp, color = Color.White,
    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 4f), 18f))

/** Rule-of-thirds lines, drawn once per frame on the GPU (no allocations). */
@Composable
fun CameraGrid(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.38f); val sw = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f; val y = size.height * i / 3f
            drawLine(c, Offset(x, 0f), Offset(x, size.height), sw)
            drawLine(c, Offset(0f, y), Offset(size.width, y), sw)
        }
    }
}

/** The big 3 · 2 · 1 in the middle of the screen while the self-timer runs. */
@Composable
fun CameraCountdown(seconds: Int, modifier: Modifier = Modifier) {
    if (seconds <= 0) return
    Box(modifier.fillMaxSize().semantics { contentDescription = "Taking the photo in $seconds" }, contentAlignment = Alignment.Center) {
        Text("$seconds", style = CountStyle)
    }
}

/**
 * The focus ring where the user tapped, with the exposure slider beside it (a sun on a short vertical track; drag up
 * to brighten). The slider moves to the ring's left near the right edge of the screen.
 */
@Composable
fun FocusRing(s: CameraProState, a: CameraProActions) {
    val at = s.focus ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val ring = 76.dp; val track = 120.dp
        val ringPx = with(d) { ring.toPx() }; val trackPx = with(d) { track.toPx() }
        val wPx = with(d) { maxWidth.toPx() }; val hPx = with(d) { maxHeight.toPx() }
        val color by animateColorAsState(when (s.focusOk) { true -> CamAccent; false -> Color.White.copy(alpha = 0.6f); null -> Color.White }, MotionTokens.fast(), label = "focus")
        val scale by animateFloatAsState(if (s.focusOk == null) 1.12f else 1f, MotionTokens.medium(), label = "focusScale")
        val cx = at.x.coerceIn(ringPx / 2, wPx - ringPx / 2); val cy = at.y.coerceIn(trackPx / 2, hPx - trackPx / 2)
        Box(Modifier.offset { IntOffset((cx - ringPx / 2).roundToInt(), (cy - ringPx / 2).roundToInt()) }.size(ring)) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2 * scale - 2.dp.toPx()
                drawCircle(color, r, style = Stroke(1.6.dp.toPx()))
                drawCircle(color.copy(alpha = 0.9f), 2.dp.toPx())
            }
        }
        val hasEv = s.evUpper > s.evLower
        if (hasEv) {
            val gap = with(d) { 14.dp.toPx() }; val slotW = with(d) { 34.dp.toPx() }
            val rightSide = cx + ringPx / 2 + gap + slotW <= wPx
            val sx = if (rightSide) cx + ringPx / 2 + gap else cx - ringPx / 2 - gap - slotW
            val frac = CameraZoom.evFraction(s.evIndex, s.evLower, s.evUpper)
            val latestFrac by rememberUpdatedState(frac)
            val label = CameraZoom.evLabel(s.evIndex, s.evStep)
            Column(Modifier.offset { IntOffset(sx.roundToInt(), (cy - trackPx / 2 - with(d) { 22.dp.toPx() }).roundToInt()) }.width(34.dp)
                .semantics { contentDescription = "Exposure"; stateDescription = "$label EV" },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = if (s.evIndex == 0) Color.White else CamAccent,
                    shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 4f)))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(34.dp).height(track).pointerInput(s.evLower, s.evUpper) {
                    var f = 0f
                    detectVerticalDragGestures(onDragStart = { f = latestFrac; a.onFocusTouch() }) { change, dy ->
                        change.consume()
                        f = (f - dy / trackPx).coerceIn(0f, 1f)
                        a.onEv(CameraZoom.evIndex(f, s.evLower, s.evUpper)); a.onFocusTouch()
                    }
                }) {
                    Canvas(Modifier.fillMaxSize()) {
                        val x = size.width / 2; val sun = 11.dp.toPx()
                        val y = size.height - frac * size.height
                        val line = Color.White.copy(alpha = 0.75f); val sw = 1.4.dp.toPx()
                        if (y - sun > 0f) drawLine(line, Offset(x, 0f), Offset(x, y - sun), sw, cap = StrokeCap.Round)
                        if (y + sun < size.height) drawLine(line, Offset(x, y + sun), Offset(x, size.height), sw, cap = StrokeCap.Round)
                    }
                    Icon(Icons.Rounded.WbSunny, null, tint = if (s.evIndex == 0) Color.White else CamAccent,
                        modifier = Modifier.offset { IntOffset(0, (trackPx - frac * trackPx - with(d) { 9.dp.toPx() }).roundToInt()) }
                            .align(Alignment.TopCenter).size(18.dp))
                }
            }
        }
    }
}

/** Small round tool button for the top bar (grid, timer). */
@Composable
fun CamTool(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.4f), MotionTokens.fast(), label = "tool")
    Box(Modifier.size(44.dp).clip(CircleShape).background(bg)
        .pointerInput(desc, on) { detectTapGestures(onTap = { onClick() }) }
        .semantics { contentDescription = desc; role = Role.Button; selected = on }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = if (on) CamAccent else Color.White, modifier = Modifier.size(22.dp))
    }
}

/**
 * Zoom preset pills: 0.5 (ultrawide only), 1x, 2, 5, 10. The lit one is the largest at or below the live zoom and
 * shows the live value ("3.4x") when between presets. Tap another to jump there; tap the lit one, or long-press any,
 * to open the dial.
 */
@Composable
fun ZoomPresets(s: CameraProState, a: CameraProActions) {
    val active = CameraZoom.activePreset(s.zoom, s.presets)
    Row(Modifier.clip(RoundedCornerShape(50)).background(CamGlass.copy(alpha = 0.55f)).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
        .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        s.presets.forEach { p ->
            val on = p == active
            val label = if (on && !CameraZoom.onPreset(s.zoom, p)) CameraZoom.label(s.zoom) else if (on) CameraZoom.label(p) else CameraZoom.presetLabel(p)
            val size by animateFloatAsState(if (on) 40f else 32f, MotionTokens.fast(), label = "preset")
            Box(Modifier.size(size.dp).clip(CircleShape).background(if (on) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                .pointerInput(p, on) { detectTapGestures(onTap = { if (on) a.onDial(true) else a.onPreset(p) }, onLongPress = { a.onDial(true) }) }
                .semantics { role = Role.Button; selected = on; contentDescription = "Zoom ${CameraZoom.label(p)}" + if (on) ", now ${CameraZoom.label(s.zoom)}. Tap for the zoom dial." else "" },
                contentAlignment = Alignment.Center) {
                Text(label, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    fontSize = if (on) 12.sp else 11.sp, color = if (on) CamAccent else Color.White))
            }
        }
    }
}

/**
 * The zoom dial: a half-circle protractor in dark glass. Minor ticks every 5°, labelled stops (1x, 2x, 5x, 10x…),
 * the part past the lens's hardware max tinted as digital, an accent arc up to the thumb and the live value in the
 * middle. Drag along the arc to turn it (relative, so touching never jumps), tap a spot to go there; a light haptic
 * tick at each labelled stop; it settles on a stop that's within 3° when let go.
 */
@Composable
fun ZoomDial(s: CameraProState, a: CameraProActions, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lo = s.minZoom; val hi = s.maxZoom
    val stops = remember(lo, hi) { CameraZoom.stops(lo, hi) }
    val ticks = remember(lo, hi) { CameraZoom.minorTicks(lo, hi) }
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val zoom by rememberUpdatedState(s.zoom)
    val labelStyle = remember { TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Color.White) }
    val valueStyle = remember { TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = Color.White) }
    val tagStyle = remember { TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.2.sp, color = CamDigital) }
    val digitalFrom = if (s.hwMax < hi - 0.01f) s.hwMax else null

    fun center(w: Float, h: Float, pad: Float) = Offset(w / 2f, h - pad)
    fun go(r: Float) {
        val next = CameraZoom.clamp(r, lo, hi)
        if (CameraZoom.crossedStop(zoom, next, stops) != null) Haptics.tick(ctx)
        a.onZoom(next); a.onDialTouch()
    }

    Box(modifier.fillMaxWidth().widthIn(max = 380.dp).aspectRatio(1.72f)
        .semantics { contentDescription = "Zoom dial"; stateDescription = CameraZoom.label(s.zoom) }) {
        Canvas(Modifier.fillMaxSize()
            .pointerInput(lo, hi) {
                val pad = 10.dp.toPx()
                var last = 0f
                detectDragGestures(
                    onDragStart = { p -> val c = center(size.width.toFloat(), size.height.toFloat(), pad); last = CameraZoom.touchAngle(p.x - c.x, p.y - c.y); a.onDialTouch() },
                    onDragEnd = { a.onDialRelease() },
                    onDragCancel = { a.onDialRelease() },
                ) { change, _ ->
                    change.consume()
                    val c = center(size.width.toFloat(), size.height.toFloat(), pad)
                    val now = CameraZoom.touchAngle(change.position.x - c.x, change.position.y - c.y)
                    val angle = CameraZoom.angleFor(zoom, lo, hi) + (now - last)
                    last = now
                    go(CameraZoom.ratioFor(angle, lo, hi))
                }
            }
            .pointerInput(lo, hi) {
                val pad = 10.dp.toPx()
                detectTapGestures { p ->
                    val c = center(size.width.toFloat(), size.height.toFloat(), pad)
                    go(CameraZoom.ratioFor(CameraZoom.touchAngle(p.x - c.x, p.y - c.y), lo, hi)); a.onDialRelease()
                }
            }) {
            val pad = 10.dp.toPx()
            val c = center(size.width, size.height, pad)
            val rimOut = minOf(size.width / 2f - 6.dp.toPx(), c.y - 6.dp.toPx())
            val r = rimOut - 12.dp.toPx()                 // the track
            // Dark glass half disc with a soft inner glow and a hairline rim.
            drawArc(Brush.radialGradient(listOf(CamGlass.copy(alpha = 0.52f), CamGlass.copy(alpha = 0.78f)), c, rimOut),
                180f, 180f, true, Offset(c.x - rimOut, c.y - rimOut), Size(rimOut * 2, rimOut * 2))
            drawArc(Color.White.copy(alpha = 0.13f), 180f, 180f, false, Offset(c.x - rimOut, c.y - rimOut), Size(rimOut * 2, rimOut * 2), style = Stroke(1.dp.toPx()))
            drawLine(Color.White.copy(alpha = 0.13f), Offset(c.x - rimOut, c.y), Offset(c.x + rimOut, c.y), 1.dp.toPx())

            fun pt(deg: Float, rad: Float): Offset { val t = Math.toRadians(deg.toDouble()); return Offset(c.x + rad * cos(t).toFloat(), c.y - rad * sin(t).toFloat()) }
            val tl = Offset(c.x - r, c.y - r); val sz = Size(r * 2, r * 2)
            // Track, the digital stretch, then the accent arc from the low end to the thumb.
            drawArc(Color.White.copy(alpha = 0.16f), 180f, 180f, false, tl, sz, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            if (digitalFrom != null) {
                // The digital stretch: tinted on the track and as a thin outer rim that stays visible under the accent arc.
                val db = CameraZoom.angleFor(digitalFrom, lo, hi)
                drawArc(CamDigital.copy(alpha = 0.55f), -db, db, false, tl, sz, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                val ro = r + 6.dp.toPx()
                drawArc(CamDigital.copy(alpha = 0.85f), -db, db, false, Offset(c.x - ro, c.y - ro), Size(ro * 2, ro * 2), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                drawLine(CamDigital, pt(db, r - 2.dp.toPx()), pt(db, r + 9.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
            }
            val cur = CameraZoom.angleFor(s.zoom, lo, hi)
            if (cur < 179.5f) drawArc(CamAccent, 180f, 180f - cur, false, tl, sz, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))

            // Minor ticks every 5°, inside the track.
            val tickA = Color.White.copy(alpha = 0.32f)
            ticks.forEach { t ->
                val deg = CameraZoom.angleFor(t, lo, hi)
                drawLine(tickA, pt(deg, r - 6.dp.toPx()), pt(deg, r - 12.dp.toPx()), 1.dp.toPx(), cap = StrokeCap.Round)
            }
            // Labelled stops: long ticks and labels.
            stops.forEach { st ->
                val deg = CameraZoom.angleFor(st, lo, hi)
                val lit = CameraZoom.onPreset(s.zoom, st)
                drawLine(if (lit) CamAccent else Color.White.copy(alpha = 0.92f), pt(deg, r - 6.dp.toPx()), pt(deg, r - 19.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
                val text = measurer.measure(CameraZoom.label(st), if (lit) labelStyle.copy(color = CamAccent) else labelStyle)
                val p = pt(deg, r - 32.dp.toPx())
                drawText(text, topLeft = Offset(p.x - text.size.width / 2f, p.y - text.size.height / 2f))
            }
            // A faint needle and the thumb.
            val th = pt(cur, r)
            drawLine(Color.White.copy(alpha = 0.12f), c, th, 1.dp.toPx())
            drawCircle(Color.Black.copy(alpha = 0.35f), 12.dp.toPx(), th + Offset(0f, 1.5.dp.toPx()))
            drawCircle(Color.White, 10.dp.toPx(), th)
            drawCircle(CamAccent, 6.dp.toPx(), th)

            // Live value in the middle, "DIGITAL" under it past the lens's max.
            val v = measurer.measure(CameraZoom.label(s.zoom), valueStyle)
            drawText(v, topLeft = Offset(c.x - v.size.width / 2f, c.y - 8.dp.toPx() - v.size.height - (if (digitalFrom != null) 10.dp.toPx() else 0f)))
            if (CameraZoom.split(s.zoom, s.hwMax).isDigital) {
                val tag = measurer.measure("DIGITAL", tagStyle)
                drawText(tag, topLeft = Offset(c.x - tag.size.width / 2f, c.y - 6.dp.toPx() - tag.size.height))
            }
        }
    }
}

/** The zoom area above the shutter: the preset pills, or the dial while it's open (fades and grows in). */
@Composable
fun ZoomControls(s: CameraProState, a: CameraProActions) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(!s.dialOpen, enter = fadeIn(MotionTokens.fast()), exit = fadeOut(MotionTokens.fast())) { ZoomPresets(s, a) }
        AnimatedVisibility(s.dialOpen, enter = fadeIn(MotionTokens.fast()) + scaleIn(MotionTokens.medium(), initialScale = 0.9f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)),
            exit = fadeOut(MotionTokens.fast()) + scaleOut(MotionTokens.fast(), targetScale = 0.94f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f))) {
            ZoomDial(s, a)
        }
    }
}
