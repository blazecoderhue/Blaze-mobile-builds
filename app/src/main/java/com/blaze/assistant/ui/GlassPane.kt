package com.blaze.assistant.ui

import android.graphics.Bitmap
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * v0.30.5: how far the Home glass windows lean when you slide a finger beside them. Slight on purpose (a few degrees).
 * Pure (HomePicturesTest, GlassPaneTest).
 */
object GlassTilt {
    const val MAX_Y = 8f   // degrees, left/right
    const val MAX_X = 6f   // degrees, up/down
    /** Drag offset (px) → tilt degrees, clamped so it never flips. [span] = the size along that axis. */
    fun tilt(drag: Float, span: Float, max: Float): Float = if (span <= 0f) 0f else (drag / span * max * 2.2f).coerceIn(-max, max)
    /**
     * v0.30.5: a finger that comes down on the page itself only scrolls it; anywhere else (the glass frame, the title
     * bar, the Home backdrop around the window) tilts the window. [l], [t], [r], [b] = the page's box (px).
     */
    fun tiltsFrom(x: Float, y: Float, l: Float, t: Float, r: Float, b: Float): Boolean =
        !(r > l && b > t && x >= l && x <= r && y >= t && y <= b)
}

/** v0.30.5: the fixed glass windows on Home are near-opaque so their title stays readable over the cards behind. */
object GlassPaneLook {
    fun frame(dark: Boolean): Brush = if (dark)
        Brush.verticalGradient(listOf(Color(0xFF26262C).copy(alpha = 0.97f), Color(0xFF18181C).copy(alpha = 0.95f)))
        else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.97f), Color(0xFFF3F5FA).copy(alpha = 0.94f)))
    fun hairline(dark: Boolean): Color = if (dark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.10f)
    /** The rim of glass around the page, in dp. */
    const val RIM_DP = 12
    const val SCREEN_RADIUS_DP = 18

    /** A rounded clip done by Android itself on the live page's View (never a Compose layer around a WebView). */
    fun roundCorners(v: View, radiusPx: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, radiusPx) }
        }
        v.clipToOutline = true
    }

    /** A still picture of a page (what the window shows while it leans), [scale] x its size. Null if it can't be drawn. */
    fun capture(v: View?, scale: Float = 1f): ImageBitmap? {
        if (v == null || v.width <= 0 || v.height <= 0) return null
        return try {
            val b = Bitmap.createBitmap((v.width * scale).toInt().coerceAtLeast(1), (v.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val cv = android.graphics.Canvas(b); cv.drawColor(android.graphics.Color.WHITE)
            cv.scale(scale, scale); cv.translate(-v.scrollX.toFloat(), -v.scrollY.toFloat())
            v.draw(cv)
            b.asImageBitmap()
        } catch (_: Throwable) { null }
    }
}

/**
 * v0.30.5: a fixed-size pane of glass centred over Home (no moving, no resizing, like the first v0.29.3 window).
 *
 * Sliding a finger on the glass frame or on the backdrop around it tilts the pane a few degrees, springing back flat on
 * release (never with the phone's "Remove animations" on). A finger on the page only reaches the page.
 *
 * The live page (a WebView) is never inside the leaning layer: a WebView drawn under a 3D-rotated / clipped Compose
 * layer can come out black. It sits flat on top of the glass, in the screen's place; while the pane leans (and while it
 * springs back) the live page is hidden and a still picture of it ([capture], taken when the lean starts) leans with
 * the glass instead.
 */
@Composable
internal fun TiltGlassPane(
    paneSize: (maxW: Dp, maxH: Dp) -> DpSize,
    capture: () -> ImageBitmap?,
    onBackdropTap: () -> Unit,
    onPositioned: (Offset) -> Unit = {},
    leaning: (Boolean) -> Unit = {},
    header: @Composable RowScope.() -> Unit,
    screen: @Composable BoxScope.(showLive: Boolean) -> Unit,
    above: @Composable BoxScope.() -> Unit = {},
) {
    val c = LocalBlaze.current
    val d = LocalDensity.current
    val scope = rememberCoroutineScope()
    val rx = remember { Animatable(0f) }
    val ry = remember { Animatable(0f) }
    val takeStill by rememberUpdatedState(capture)
    val onLean by rememberUpdatedState(leaning)
    val onTap by rememberUpdatedState(onBackdropTap)
    val motion = MotionTokens.enabled
    val appear = remember { Animatable(if (motion) 0.92f else 1f) }
    var appeared by remember { mutableStateOf(!motion) }
    var still by remember { mutableStateOf<ImageBitmap?>(null) }
    var tilting by remember { mutableStateOf(false) }
    var gen by remember { mutableIntStateOf(0) }
    var headerPx by remember { mutableIntStateOf(0) }
    var boxPos by remember { mutableStateOf(Offset.Zero) }
    var page by remember { mutableStateOf(Rect.Zero) }   // the live page's box, window px
    LaunchedEffect(Unit) {
        if (motion) appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
        appeared = true
    }
    val paneShape = RoundedCornerShape(28.dp)
    val screenShape = RoundedCornerShape(GlassPaneLook.SCREEN_RADIUS_DP.dp)
    val none = remember { MutableInteractionSource() }

    BoxWithConstraints(Modifier.fillMaxSize()
        .onGloballyPositioned { boxPos = it.positionInWindow(); onPositioned(boxPos) }
        .background(Color.Black.copy(alpha = if (c.dark) 0.35f else 0.18f))
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!MotionTokens.enabled) return@awaitEachGesture
                val p = page   // window coordinates
                val at = down.position + boxPos
                if (!GlassTilt.tiltsFrom(at.x, at.y, p.left, p.top, p.right, p.bottom)) return@awaitEachGesture
                var tx = 0f; var ty = 0f
                val first = awaitTouchSlopOrCancellation(down.id) { ch, over -> ch.consume(); tx += over.x; ty += over.y } ?: return@awaitEachGesture
                val my = ++gen
                if (!tilting) { still = takeStill(); tilting = true; onLean(true) }
                val w = size.width.toFloat(); val h = size.height.toFloat()
                drag(first.id) { mv ->
                    val a = mv.positionChange(); mv.consume(); tx += a.x; ty += a.y
                    scope.launch { ry.snapTo(GlassTilt.tilt(tx, w, GlassTilt.MAX_Y)) }
                    scope.launch { rx.snapTo(-GlassTilt.tilt(ty, h, GlassTilt.MAX_X)) }
                }
                scope.launch {
                    coroutineScope {
                        launch { rx.animateTo(0f, spring(0.45f, Spring.StiffnessLow)) }
                        launch { ry.animateTo(0f, spring(0.45f, Spring.StiffnessLow)) }
                    }
                    if (gen == my) { tilting = false; still = null; onLean(false) }
                }
            }
        }
        .clickable(interactionSource = none, indication = null) { onTap() }) {
        val sz = paneSize(maxWidth, maxHeight)
        val rim = GlassPaneLook.RIM_DP.dp
        Box(Modifier.align(Alignment.Center).size(sz)) {
            // The glass (frame, title bar and the screen's place). This is the only layer that leans.
            Column(Modifier.fillMaxSize()
                .graphicsLayer {
                    rotationX = rx.value; rotationY = ry.value; scaleX = appear.value; scaleY = appear.value
                    cameraDistance = 14f * density; transformOrigin = TransformOrigin.Center
                    shadowElevation = 24.dp.toPx(); shape = paneShape; clip = true
                }
                .background(GlassPaneLook.frame(c.dark)).border(1.dp, GlassPaneLook.hairline(c.dark), paneShape)
                .padding(start = rim, end = rim, top = rim - 2.dp, bottom = rim)) {
                Row(Modifier.fillMaxWidth().onSizeChanged { headerPx = it.height }.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, content = header)
                Box(Modifier.fillMaxWidth().weight(1f).clip(screenShape)
                    .border(1.dp, Color.Black.copy(alpha = 0.18f), screenShape).background(Color.White)) {
                    val s = still
                    if (s != null && (tilting || !appeared)) Image(s, contentDescription = null, contentScale = ContentScale.FillWidth,
                        alignment = Alignment.TopStart, modifier = Modifier.fillMaxSize())
                }
            }
            // The live page: flat, unscaled, no Compose layer around it; rounded by its own View outline.
            val showLive = appeared && !tilting && headerPx > 0
            Column(Modifier.fillMaxSize().padding(start = rim, end = rim, top = rim - 2.dp, bottom = rim)) {
                Spacer(Modifier.height(with(d) { headerPx.toDp() }))
                Box(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { lc ->
                    val pos = lc.positionInWindow()
                    page = Rect(pos.x, pos.y, pos.x + lc.size.width, pos.y + lc.size.height)
                }) { screen(showLive) }
            }
        }
        above()
    }
}
