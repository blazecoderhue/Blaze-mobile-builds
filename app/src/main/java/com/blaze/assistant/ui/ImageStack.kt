package com.blaze.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * v0.28.1 (Luke, from Hark's chat): several pictures in one message are a fanned card stack, and the full-screen
 * viewer flips between pictures in 3D.
 *
 * Pure maths in [StackMath] / [FlipMath] (unit tested in ImageStackTest); the stateless frames [ImageStackFrame] and
 * [ImagePagerFrame] draw any moment of a swipe (ShotsTest uses them for the stack and a mid-flip shot); [ImageStack]
 * and [ImagePager] add the gestures. Remove animations: every settle snaps and the flip becomes a plain slide.
 */
object StackMath {
    /** How many cards are drawn (the top one + up to 2 behind). */
    fun layers(n: Int): Int = minOf(n, 3)

    /** The picture shown [depth] cards down from [top], wrapping around. */
    fun at(top: Int, depth: Int, n: Int): Int = if (n <= 0) 0 else ((top + depth) % n + n) % n

    /** Next top after a swipe: left (dir = +1) shows the next picture, right (-1) the previous one, wrapping. */
    fun next(top: Int, dir: Int, n: Int): Int = if (n <= 0) 0 else ((top + dir) % n + n) % n

    data class Layer(val rotation: Float, val scale: Float, val dim: Float, val shiftDp: Float)

    /**
     * A card [depth] down (0 = top) while the top is dragged [progress] (-1..1 of the card width). Behind cards fan
     * out left (depth 1) and right (depth 2), smaller and dimmer, and move up one place as the top leaves.
     */
    fun layer(depth: Int, progress: Float): Layer {
        val p = abs(progress).coerceIn(0f, 1f)
        fun base(d: Int) = when (d) {
            0 -> Layer(0f, 1f, 0f, 0f)
            1 -> Layer(-7f, 0.9f, 0.28f, -14f)
            else -> Layer(7f, 0.82f, 0.45f, 14f)
        }
        if (depth == 0) return Layer(progress * 12f, 1f, 0f, 0f)
        val a = base(depth); val b = base(depth - 1)
        return Layer(a.rotation + (b.rotation - a.rotation) * p, a.scale + (b.scale - a.scale) * p, a.dim + (b.dim - a.dim) * p, a.shiftDp + (b.shiftDp - a.shiftDp) * p)
    }

    /** "2/5" from 6 pictures up, dots below that (null = draw dots). */
    fun label(top: Int, n: Int): String? = if (n > 5) "${top + 1}/$n" else null

    /** A swipe past this share of the card width (or a fast fling) flips. */
    const val FLIP_AT = 0.28f
    fun flips(progress: Float, velocity: Float): Int = when {
        progress <= -FLIP_AT || velocity < -900f -> 1
        progress >= FLIP_AT || velocity > 900f -> -1
        else -> 0
    }
}

object FlipMath {
    /** The Y-axis tilt at a full page turn, in degrees (Luke: about 25–35°). */
    const val MAX_DEG = 30f

    data class Page(val rotationY: Float, val shift: Float, val alpha: Float, val pivotX: Float)

    /**
     * A page at [rel] pages from the one in front (0 = current, -1 = previous, +1 = next) while the drag has moved
     * [progress] pages (negative = towards the next). [motion] false (Remove animations) = a plain slide.
     */
    fun page(rel: Int, progress: Float, motion: Boolean = true): Page {
        val pos = rel + progress            // -1..1 on screen: 0 = centred
        val shift = pos                      // in page widths
        if (!motion) return Page(0f, shift, 1f, 0.5f)
        val rot = (pos * MAX_DEG).coerceIn(-MAX_DEG, MAX_DEG)
        // Turning on the edge nearest the centre, so the page swings in from the other side.
        val pivot = if (pos < 0) 1f else 0f
        return Page(rot, shift, (1f - abs(pos) * 0.35f).coerceIn(0f, 1f), pivot)
    }
}

/** A still frame of the stack: [top] in front, [progress] of the way through a swipe. */
@Composable
fun ImageStackFrame(bitmaps: List<ImageBitmap?>, top: Int, progress: Float, size: Dp = 220.dp, modifier: Modifier = Modifier,
                    onTap: (() -> Unit)? = null, onExpand: (() -> Unit)? = null, dragModifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    val n = bitmaps.size
    val widthPx = with(LocalDensity.current) { size.toPx() }
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.padding(horizontal = 22.dp, vertical = 10.dp).size(size).semantics { contentDescription = "Pictures, ${top + 1} of $n. Swipe to flip through." },
        contentAlignment = Alignment.Center) {
        for (depth in StackMath.layers(n) - 1 downTo 0) {
            val l = StackMath.layer(depth, progress)
            val bmp = bitmaps.getOrNull(StackMath.at(top, depth, n))
            Box(Modifier.fillMaxSize()
                .graphicsLayer {
                    rotationZ = l.rotation; scaleX = l.scale; scaleY = l.scale
                    translationX = if (depth == 0) progress * widthPx else l.shiftDp * density
                    transformOrigin = TransformOrigin(0.5f, 0.9f)
                    shadowElevation = if (depth == 0) 10f else 4f; this.shape = shape; clip = true
                }
                .background(c.faint)
                .then(if (depth == 0) dragModifier.then(if (onTap != null) Modifier.clickable(onClickLabel = "Preview picture") { onTap() } else Modifier) else Modifier)) {
                if (bmp != null) Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (l.dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = l.dim)))
            }
        }
        // Position: dots up to 5, "2/5" style above that.
        val label = StackMath.label(top, n)
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (label != null) Text(label, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White))
            else Row { repeat(n) { i -> if (i > 0) Spacer(Modifier.width(4.dp)); Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == top) 1f else 0.45f))) } }
        }
        if (onExpand != null || onTap == null) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
            .then(if (onExpand != null) Modifier.clickable(onClickLabel = "Open full screen") { onExpand() } else Modifier)
            .semantics { contentDescription = "Open full screen" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.OpenInFull, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/** Several pictures in one message: a fanned stack. Swipe flips with a spring; tap or the round button opens the viewer. */
@Composable
fun ImageStack(paths: List<String>, bitmaps: List<ImageBitmap?>? = null, size: Dp = 220.dp, onOpen: (Int) -> Unit) {
    val n = paths.size
    val bmps = paths.mapIndexed { i, p -> bitmaps?.getOrNull(i) ?: rememberBitmap(p, 480) }
    var top by remember(n) { mutableIntStateOf(0) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val widthPx = with(LocalDensity.current) { size.toPx() }
    val springy = { spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow) }
    val settle = { v: Float ->
        scope.launch {
            val dir = StackMath.flips(drag.value, v)
            if (dir == 0) drag.animateTo(0f, if (MotionTokens.enabled) springy() else snap<Float>())
            else {
                drag.animateTo(-dir * 1.1f, if (MotionTokens.enabled) springy() else snap<Float>())
                top = StackMath.next(top, dir, n)
                drag.snapTo(0f)
            }
        }
    }
    val gestures = Modifier.pointerInput(n) {
        var v = 0f; var lastT = 0L; var lastX = 0f
        detectHorizontalDragGestures(
            onDragStart = { v = 0f; lastT = System.nanoTime(); lastX = 0f },
            onDragEnd = { settle(v) }, onDragCancel = { settle(0f) },
        ) { ch, dx ->
            ch.consume()
            val t = System.nanoTime(); val dt = (t - lastT).coerceAtLeast(1) / 1e9f
            v = dx / dt; lastT = t; lastX += dx
            scope.launch { drag.snapTo((drag.value + dx / widthPx).coerceIn(-1.2f, 1.2f)) }
        }
    }
    ImageStackFrame(bmps, top, drag.value, size, onTap = { onOpen(top) }, onExpand = { onOpen(top) }, dragModifier = gestures)
}

/** A still frame of the viewer: picture [index] in front, the drag [progress] pages along (negative = towards the next). */
@Composable
fun ImagePagerFrame(bitmaps: List<ImageBitmap?>, index: Int, progress: Float, zoom: Float = 1f, pan: Offset = Offset.Zero,
                    onClose: () -> Unit = {}, onSave: (() -> Unit)? = null, gestures: Modifier = Modifier) {
    val n = bitmaps.size
    val motion = MotionTokens.enabled
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val w = constraints.maxWidth.toFloat()
        // Dark, blurred copy of the picture in front behind everything (blur on Android 12+, a plain dark wash before).
        bitmaps.getOrNull(index)?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(36.dp).graphicsLayer { alpha = 0.55f }) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        Box(Modifier.fillMaxSize().then(gestures)) {
            for (rel in listOf(-1, 1, 0)) {
                val i = index + rel
                if (i < 0 || i >= n) continue
                val pg = FlipMath.page(rel, progress, motion)
                if (abs(pg.shift) >= 1.2f) continue
                val bmp = bitmaps[i]
                Box(Modifier.fillMaxSize().graphicsLayer {
                    translationX = pg.shift * w + if (rel == 0) pan.x else 0f
                    translationY = if (rel == 0) pan.y else 0f
                    rotationY = pg.rotationY
                    cameraDistance = 12f * density
                    transformOrigin = TransformOrigin(pg.pivotX, 0.5f)
                    alpha = pg.alpha
                    if (rel == 0) { scaleX = zoom; scaleY = zoom }
                }, contentAlignment = Alignment.Center) {
                    // Sized to the picture's own shape so the rounded corners sit on the picture, not on empty space.
                    if (bmp != null) Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 72.dp), contentAlignment = Alignment.Center) {
                        Image(bmp, contentDescription = "Picture ${i + 1} of $n", contentScale = ContentScale.Fit,
                            modifier = Modifier.aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).clip(RoundedCornerShape(18.dp)))
                    }
                }
            }
        }
        GlassIconButton(Icons.Rounded.Close, "Close", modifier = Modifier.align(Alignment.TopStart).padding(16.dp), onClick = onClose)
        if (onSave != null) GlassIconButton(Icons.Rounded.Download, "Save to Pictures", modifier = Modifier.align(Alignment.TopEnd).padding(16.dp), onClick = onSave)
        if (n > 1) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text("${index + 1}/$n", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White))
        }
    }
}

/** Full-screen viewer: 3D flip between pictures (plain slide with Remove animations), pinch zoom (no flipping while zoomed), close and save. */
@Composable
fun ImagePager(paths: List<String>, start: Int, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val n = paths.size
    val bmps = paths.map { rememberBitmap(it, 1600) }
    var index by remember { mutableIntStateOf(start.coerceIn(0, (n - 1).coerceAtLeast(0))) }
    val drag = remember { Animatable(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val gestures = Modifier.pointerInput(n) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var paging = false
                    var lastX = 0f; var vel = 0f; var lastT = System.nanoTime()
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.none { it.pressed }) break
                        val z = ev.calculateZoom(); val p = ev.calculatePan()
                        if (ev.changes.size > 1 || zoom > 1.01f) {
                            // Zoomed: pinch and pan the picture; flipping is off until it's back to 1×.
                            zoom = (zoom * z).coerceIn(1f, 5f)
                            pan = if (zoom <= 1.01f) Offset.Zero else pan + p
                        } else if (p.x != 0f) {
                            paging = true
                            val t = System.nanoTime(); vel = p.x / ((t - lastT).coerceAtLeast(1) / 1e9f); lastT = t; lastX += p.x
                            var v = drag.value + p.x / w
                            if ((index == 0 && v > 0) || (index == n - 1 && v < 0)) v = drag.value + p.x / w / 3f   // rubber band at the ends
                            scope.launch { drag.snapTo(v.coerceIn(-1f, 1f)) }
                        }
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    if (paging) scope.launch {
                        val dir = StackMath.flips(drag.value * 1.2f, vel).let { if ((it == 1 && index >= n - 1) || (it == -1 && index <= 0)) 0 else it }
                        val spec = if (MotionTokens.enabled) spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow) else snap<Float>()
                        if (dir == 0) drag.animateTo(0f, spec)
                        else { drag.animateTo(-dir.toFloat(), spec); index += dir; drag.snapTo(0f); zoom = 1f; pan = Offset.Zero }
                    }
                }
            }
            ImagePagerFrame(bmps, index, drag.value, zoom, pan, onClose = onDismiss, onSave = {
                val ok = saveToPictures(ctx, paths[index])
                android.widget.Toast.makeText(ctx, if (ok) "Saved to Pictures/Blaze" else "Couldn't save that picture", android.widget.Toast.LENGTH_SHORT).show()
            }, gestures = gestures)
        }
    }
}

/** Copies a picture file into Pictures/Blaze (Android 10+ MediaStore; no permission needed). */
fun saveToPictures(ctx: android.content.Context, path: String): Boolean = try {
    val f = java.io.File(path)
    if (!f.exists() || android.os.Build.VERSION.SDK_INT < 29) false else {
        val ext = f.extension.lowercase().ifBlank { "jpg" }
        val mime = when (ext) { "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg" }
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "blaze-${System.currentTimeMillis()}.$ext")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Blaze")
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri != null && (ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) }; true } ?: false)
    }
} catch (_: Exception) { false }
