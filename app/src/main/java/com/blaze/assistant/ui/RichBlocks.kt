package com.blaze.assistant.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * v0.24.9 rich replies, drawn: step-flow cards, a section picture beside its text, image rows and the tilted deck
 * with a card flip (instant when Android's "Remove animations" is on), and inline diagrams. Rounded, dark-friendly,
 * orange / blue accents. Pictures load from the web on a background thread into a small memory cache.
 */
object RichImages {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(24) {}

    /** Screenshots and tests swap the network for their own pictures. */
    val Local = staticCompositionLocalOf<((String) -> ImageBitmap?)?> { null }

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(ctx: android.content.Context, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        try {
            val bytes = if (url.startsWith("/")) java.io.File(url).takeIf { it.isFile && it.length() < 12_000_000 }?.readBytes()
            else {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 20_000; c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Blaze/1 (Android)")
                if (c.responseCode !in 200..299) null else c.inputStream.use { s ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
                    while (true) { val n = s.read(buf); if (n < 0) break; total += n; if (total > 12_000_000) return@use null; out.write(buf, 0, n) }
                    out.toByteArray()
                }
            } ?: return@withContext null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > 1400) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
            bmp.asImageBitmap().also { cache.put(url, it) }
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    }
}

private val pictureShape = RoundedCornerShape(16.dp)

/** One picture: the image, a soft placeholder while it loads, its alt text if it can't. */
@Composable
fun RichPicture(img: RichReplies.Img, modifier: Modifier = Modifier, ratio: Float = 4f / 3f, crop: Boolean = true) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val override = RichImages.Local.current
    val bmp by produceState(override?.invoke(img.url) ?: RichImages.cached(img.url), img.url) {
        if (value == null && override == null) value = RichImages.load(ctx, img.url)
    }
    Box(modifier.aspectRatio(ratio).clip(pictureShape).background(c.faint).semantics { contentDescription = img.alt.ifBlank { "Picture" } },
        contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, contentDescription = null, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Text(img.alt.ifBlank { "Picture" }, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 11.5.sp, color = c.sub))
    }
}

@Composable
fun RichImageText(img: RichReplies.Img, md: String, text: @Composable (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        RichPicture(img, Modifier.width(108.dp), ratio = 1f)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { text(md) }
    }
}

/** Up to 3 pictures side by side; 4 or more become the deck. */
@Composable
fun RichImageGroup(imgs: List<RichReplies.Img>, animatorScale: Float = 1f) {
    if (RichReplies.layout(imgs.size) == RichReplies.Layout.ROW) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            imgs.forEach { RichPicture(it, Modifier.weight(1f), ratio = if (imgs.size == 1) 16f / 10f else 1f) }
        }
    } else RichDeck(imgs, animatorScale)
}

/**
 * The deck: the top picture straight, the next few peeking out tilted under it. Swipe left / right (or the arrows)
 * to turn it; it flips like a card, or swaps instantly when animations are removed.
 */
@Composable
fun RichDeck(imgs: List<RichReplies.Img>, animatorScale: Float = 1f, start: Int = 0) {
    val c = LocalBlaze.current
    val n = imgs.size
    var index by androidx.compose.runtime.remember { mutableIntStateOf(start.coerceIn(0, (n - 1).coerceAtLeast(0))) }
    val off = RichReplies.animationsOff(animatorScale)
    val flipT = remember { Animatable(0f) }
    var dir by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    fun turn(d: Int) {
        if (n < 2 || flipT.isRunning) return
        dir = d
        if (off) { index = RichReplies.next(index, n, d); return }
        scope.launch {
            flipT.snapTo(0f)
            flipT.animateTo(0.5f, tween((RichReplies.FLIP_MS / 2 / animatorScale.coerceAtLeast(0.1f)).roundToInt()))
            index = RichReplies.next(index, n, d)
            flipT.animateTo(1f, tween((RichReplies.FLIP_MS / 2 / animatorScale.coerceAtLeast(0.1f)).roundToInt()))
            flipT.snapTo(0f)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp, start = 10.dp, end = 10.dp)) {
            val wPx = with(density) { maxWidth.toPx() }
            // Cards underneath, deepest first.
            for (depth in minOf(RichReplies.DECK_SHOWN, n - 1) downTo 1) {
                val img = imgs[RichReplies.next(index, n, depth)]
                val p = RichReplies.peek(depth)
                Box(Modifier.fillMaxWidth().offset { IntOffset((p * density.density * (if (depth % 2 == 1) 1 else -1) * 0.6f).roundToInt(), (p * density.density).roundToInt()) }
                    .graphicsLayer { rotationZ = RichReplies.tilt(depth); alpha = 1f - depth * 0.12f }
                    .shadow(6.dp, pictureShape)) {
                    RichPicture(img, Modifier.fillMaxWidth(), ratio = 4f / 3f)
                }
            }
            val (angle, _) = RichReplies.flip(flipT.value, dir)
            var drag by remember { mutableIntStateOf(0) }
            Box(Modifier.fillMaxWidth()
                .graphicsLayer { rotationY = angle; cameraDistance = 14f * density.density; translationX = drag.toFloat() }
                .shadow(10.dp, pictureShape)
                .pointerInput(n, off) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { val d = RichReplies.swipeDir(total, wPx); drag = 0; if (d != 0) turn(d) },
                        onDragCancel = { drag = 0 },
                    ) { _, dx -> total += dx; drag = (total * 0.35f).roundToInt() }
                }
                .semantics { contentDescription = "Picture ${RichReplies.counter(index, n)}: ${imgs[index].alt}. Swipe for the next one." }) {
                RichPicture(imgs[index], Modifier.fillMaxWidth(), ratio = 4f / 3f)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            DeckArrow(Icons.Rounded.ChevronLeft, "Previous picture") { turn(-1) }
            Spacer(Modifier.width(10.dp))
            Text(RichReplies.counter(index, n), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.sub))
            Spacer(Modifier.width(10.dp))
            DeckArrow(Icons.Rounded.ChevronRight, "Next picture") { turn(1) }
        }
        imgs[index].alt.takeIf { it.isNotBlank() }?.let {
            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 12.dp, end = 12.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        }
    }
}

@Composable
private fun DeckArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.size(34.dp).clip(CircleShape).background(c.faint).clickable(onClickLabel = desc, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = desc, tint = c.ink, modifier = Modifier.size(20.dp))
    }
}

/** Step-flow card: numbered blue dots joined by a line, a bold title and its detail per step. */
@Composable
fun RichSteps(steps: List<RichReplies.Step>, text: @Composable (String) -> Unit) {
    val c = LocalBlaze.current
    val orange = Color(0xFF4C7DFF); val blue = Color(0xFF4C7DFF) // v0.25.2: classic solid blue, no orange
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.faint).padding(horizontal = 12.dp, vertical = 12.dp)) {
        steps.forEachIndexed { i, s ->
            val f = if (steps.size <= 1) 0f else i / (steps.size - 1f)
            val dot = androidx.compose.ui.graphics.lerp(orange, blue, f)
            Row(Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(dot), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
                    }
                    if (i < steps.size - 1) Box(Modifier.width(2.dp).height(if (s.body.isBlank()) 14.dp else 30.dp).clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(dot, androidx.compose.ui.graphics.lerp(orange, blue, (i + 1) / (steps.size - 1f))))))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(top = 3.dp, bottom = 8.dp)) {
                    Text(s.title, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
                    if (s.body.isNotBlank()) { Spacer(Modifier.height(2.dp)); text(s.body) }
                }
            }
        }
    }
}

/**
 * An inline diagram. v0.26.3: Mermaid is drawn on the phone ([DiagramRenderer], bundled mermaid.js, Blaze's colours in
 * light and dark, cached), so it works offline and in private chats; the web renderers are only the fallback. While it
 * draws, a quiet placeholder; if nothing can draw it, one plain line with "Show code" (the source is never the picture).
 * Copy keeps the source.
 */
@Composable
fun RichDiagram(d: RichReplies.Block.Diagram, maxH: androidx.compose.ui.unit.Dp = 420.dp) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val override = RichImages.Local.current   // screenshots and tests bring their own pictures
    val look = DiagramRender.look(c.dark)
    val state by produceState<DiagramRenderer.Outcome?>(
        override?.invoke(d.url)?.let { DiagramRenderer.Outcome.Picture(it, remote = false) } ?: DiagramRenderer.cached(d.kind, d.code, d.private, c.dark),
        d.kind, d.code, d.private, c.dark) {
        if (value == null && override == null) value = DiagramRenderer.draw(ctx, d.kind, d.code, d.private, c.dark, d.url)
    }
    var showCode by remember(d.code) { mutableStateOf(false) }
    val s = state
    val cardBg = when {
        s is DiagramRenderer.Outcome.Picture && s.remote -> Color.White
        s is DiagramRenderer.Outcome.Picture -> Color(android.graphics.Color.parseColor(look.bg))
        else -> c.faint
    }
    val label = "${DiagramRender.label(d.kind)} diagram"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cardBg).semantics { contentDescription = label }) {
        when (s) {
            // v0.27.0: a soft shimmer while the diagram is drawn (a plain fill with Remove animations on).
            null -> Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).shimmer(c.faint, c.paper.copy(alpha = 0.35f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Text("Drawing the diagram\u2026", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
            is DiagramRenderer.Outcome.Picture -> {
                val ratio = (s.bmp.width.toFloat() / s.bmp.height.coerceAtLeast(1)).coerceIn(0.4f, 4f)
                Image(s.bmp, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = maxH).aspectRatio(ratio, matchHeightConstraintsFirst = true))
            }
            is DiagramRenderer.Outcome.Failed -> Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.line, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body))
                    Spacer(Modifier.width(10.dp))
                    Text(if (showCode) "Hide code" else "Show code", modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showCode = !showCode }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = c.accent))
                }
                if (showCode) {
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(d.code, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink))
                    }
                }
            }
        }
        val onCard = if (s is DiagramRenderer.Outcome.Picture && s.remote) Color(0xFF5A5A5E) else c.sub
        Text(label + if (s is DiagramRenderer.Outcome.Picture && !s.remote) " \u00b7 drawn on your phone" else "",
            modifier = Modifier.fillMaxWidth().background(if (s is DiagramRenderer.Outcome.Picture && s.remote) Color(0xFFF1F1EF) else c.faint)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = onCard))
    }
}

/** Reads Android's animator duration scale ("Remove animations" = 0). */
@Composable
fun animatorScale(): Float {
    val ctx = LocalContext.current
    return remember { try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Exception) { 1f } }
}
