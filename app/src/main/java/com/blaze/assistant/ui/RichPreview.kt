package com.blaze.assistant.ui

import androidx.compose.animation.core.Animatable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * v0.31.0 detached rich blocks. Diagrams, pictures, charts, tables, compare cards and mini tools never sit inside the
 * reply's text any more: each becomes its own small glass preview window in the thread (styled like the Home news
 * rows: title, a quiet source / type line, a thumbnail on the right). Tapping it opens the block full-screen in a glass
 * window ([GlassViewer]) with the existing renderers (zoom and pan for diagrams and pictures).
 */
object RichPreview {
    /** True when [b] is shown as a detached preview window instead of inline. */
    fun detached(b: RichReplies.Block): Boolean = when (b) {
        is RichReplies.Block.Diagram, is RichReplies.Block.Image, is RichReplies.Block.Images, is RichReplies.Block.Chart,
        is RichReplies.Block.Table, is RichReplies.Block.Compare, is RichReplies.Block.Tool -> true
        // v0.31.1: only visuals still streaming in are windows; a ```choices / ```form in progress stays inline.
        is RichReplies.Block.Pending -> b.kind in WINDOW_KINDS
        is RichReplies.Block.Plain -> b.seg is AnswerCards.Seg.Table
        else -> false
    }

    /** The type label on the card. */
    fun kind(b: RichReplies.Block): String = when (b) {
        is RichReplies.Block.Diagram -> "Diagram"
        is RichReplies.Block.Image -> "Image"
        is RichReplies.Block.Images -> "Images"
        is RichReplies.Block.Chart -> "Chart"
        is RichReplies.Block.Table -> "Table"
        is RichReplies.Block.Compare -> "Comparison"
        is RichReplies.Block.Tool -> "Mini tool"
        is RichReplies.Block.Pending -> when (b.kind) { "chart" -> "Chart"; "table" -> "Table"; "compare" -> "Comparison"; "tool" -> "Mini tool"; else -> "Diagram" }
        is RichReplies.Block.Plain -> if (b.seg is AnswerCards.Seg.Table) "Table" else "Block"
        else -> "Block"
    }

    /** The card's title. */
    fun title(b: RichReplies.Block): String = when (b) {
        is RichReplies.Block.Diagram -> "${DiagramRender.label(b.kind)} diagram"
        is RichReplies.Block.Image -> b.img.alt.ifBlank { "Picture" }
        is RichReplies.Block.Images -> "${b.imgs.size} pictures"
        is RichReplies.Block.Chart -> b.chart.title.ifBlank { "Chart" }
        is RichReplies.Block.Table -> b.table.title.ifBlank { "Table · ${b.table.rows.size} rows" }
        is RichReplies.Block.Compare -> b.compare.title.ifBlank { "Comparison" }
        is RichReplies.Block.Tool -> b.tool.title.ifBlank { "Mini tool" }
        is RichReplies.Block.Pending -> title(b.fallback)
        is RichReplies.Block.Plain -> (b.seg as? AnswerCards.Seg.Table)?.let { "Table · ${(it.rows.size - 1).coerceAtLeast(0)} rows" } ?: "Block"
        else -> "Block"
    }

    /** Pending kinds that are drawn as windows. */
    val WINDOW_KINDS = setOf("diagram", "chart", "table", "compare", "tool")

    /** Diagrams and pictures zoom and pan in the viewer; the rest scroll. */
    fun zoomable(b: RichReplies.Block): Boolean = b is RichReplies.Block.Diagram || b is RichReplies.Block.Image ||
        (b is RichReplies.Block.Pending && b.fallback is RichReplies.Block.Diagram)

    /** The table a block shows, if it is one (```table card, Markdown / HTML / ASCII table). */
    fun table(b: RichReplies.Block): RichKit.TableCard? = when (b) {
        is RichReplies.Block.Table -> b.table
        is RichReplies.Block.Plain -> (b.seg as? AnswerCards.Seg.Table)?.let { RichKit.tableFromMd(it.rows) }
        is RichReplies.Block.Pending -> table(b.fallback)
        else -> null
    }

    enum class Mode { ZOOM, TABLE, SCROLL }

    /** How the floating window lays a block out. */
    fun mode(b: RichReplies.Block, streaming: Boolean): Mode = when {
        b is RichReplies.Block.Pending && streaming -> Mode.SCROLL
        zoomable(b) -> Mode.ZOOM
        table(b) != null -> Mode.TABLE
        else -> Mode.SCROLL
    }
}

/** A block drawn at full size (window) or as the source of a miniature (thumbnail). [window] = inside the floating window. */
@Composable
fun RichBlockContent(b: RichReplies.Block, streaming: Boolean, window: Boolean = false) {
    when (b) {
        is RichReplies.Block.Diagram -> RichDiagram(b, maxH = if (window) Dp.Infinity else 420.dp)
        is RichReplies.Block.Image -> if (window) WindowPicture(b.img) else RichPicture(b.img, Modifier.fillMaxWidth(), crop = false)
        is RichReplies.Block.Images -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            b.imgs.forEach { if (window) WindowPicture(it) else RichPicture(it, Modifier.fillMaxWidth(), crop = false) }
        }
        is RichReplies.Block.Chart -> RichChart(b.chart)
        is RichReplies.Block.Table -> TableCardView(b.table)
        is RichReplies.Block.Compare -> RichCompare(b.compare)
        is RichReplies.Block.Tool -> RichTool(b.tool)
        is RichReplies.Block.Pending -> if (streaming) RichPending(b.kind) else RichBlockContent(b.fallback, false, window)
        is RichReplies.Block.Plain -> (b.seg as? AnswerCards.Seg.Table)?.let { s -> RichKit.tableFromMd(s.rows)?.let { TableCardView(it) } ?: TableGrid(s.rows) }
        else -> {}
    }
}

/** A picture at its own aspect ratio, fitted to the window width (the zoom box fits its height). */
@Composable
private fun WindowPicture(img: RichReplies.Img) {
    val ctx = LocalContext.current
    val override = RichImages.Local.current
    val bmp by produceState(override?.invoke(img.url) ?: RichImages.cached(img.url), img.url) {
        if (value == null && override == null) value = RichImages.load(ctx, img.url)
    }
    val b = bmp
    if (b == null) RichPicture(img, Modifier.fillMaxWidth(), crop = false)
    else Image(b, contentDescription = img.alt.ifBlank { "Picture" }, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().aspectRatio((b.width.toFloat() / b.height.coerceAtLeast(1)).coerceIn(0.2f, 6f)))
}

/**
 * [content] laid out at [design] width and scaled down into a [w] × [h] thumbnail: a real miniature of the block
 * (drawn, never interactive: a tap anywhere opens the window). Pictures use the picture itself, cropped.
 */
@Composable
private fun Miniature(b: RichReplies.Block, streaming: Boolean, w: Dp, h: Dp, onOpen: () -> Unit, design: Dp = 320.dp) {
    val scale = w / design
    val shape = BlazeShapes.small
    Box(Modifier.size(w, h).clip(shape).background(Color.White.copy(alpha = 0.05f)).border(1.dp, Color.White.copy(alpha = 0.30f), shape)) {
        val pic = when (b) { is RichReplies.Block.Image -> b.img; is RichReplies.Block.Images -> b.imgs.firstOrNull(); else -> null }
        if (pic != null) RichPicture(pic, Modifier.fillMaxSize(), ratio = w / h, crop = true)
        else Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredWidth(design)
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }) { RichBlockContent(b, streaming) }
        // the miniature's own buttons (Copy, chart taps…) never get the tap
        Box(Modifier.matchParentSize().clickable(onClick = onOpen))
    }
}

/**
 * The preview window for one detached block. [tail] = it is the last thing in a streaming reply, so the droplet aims
 * at (and lands on) its top edge; [landIn] = it just arrived while streaming, so it forms under the falling drop.
 */
@Composable
fun RichPreviewCard(b: RichReplies.Block, key: String, streaming: Boolean, tail: Boolean, landIn: Boolean) {
    val c = LocalBlaze.current
    var open by rememberSaveable(key) { mutableStateOf(false) }
    val orb = LocalOrb.current
    val motion = MotionTokens.enabled
    // Forms under the drop: waits for the fall, then springs up from a flattened puddle (dampingRatio 0.8).
    val form = remember(key) { Animatable(if (landIn && motion) 0f else 1f) }
    LaunchedEffect(key) { if (form.value < 1f) { kotlinx.coroutines.delay(180); form.animateTo(1f, spring(0.8f, Spring.StiffnessMediumLow)) } }
    val title = RichPreview.title(b); val kind = RichPreview.kind(b)
    Row(Modifier.widthIn(max = 380.dp).fillMaxWidth()
        .graphicsLayer {
            val f = form.value
            alpha = (f * 1.4f).coerceAtMost(1f); scaleY = 0.55f + 0.45f * f; scaleX = 0.9f + 0.1f * f
            transformOrigin = TransformOrigin(0.5f, 0f)
        }
        .onGloballyPositioned { co -> if (tail && orb != null) orb.aim(co, Offset(co.size.width / 2f, 0f), card = true) }
        .glass(GlassTokens.cardShape, GlassIntensity.Medium)
        .clickable(onClickLabel = "Open $kind", role = Role.Button) { open = true }
        .semantics(mergeDescendants = true) { contentDescription = "$kind: $title. Tap to open." }
        .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp, color = c.ink))
            Spacer(Modifier.heightIn(min = 3.dp))
            Text(if (b is RichReplies.Block.Pending && streaming) "$kind · forming…" else "$kind · tap to open", maxLines = 1,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 11.5.sp, color = c.sub))
        }
        Spacer(Modifier.width(10.dp))
        Miniature(b, streaming, 76.dp, 56.dp, onOpen = { open = true })
    }
    if (open) RichBlockViewer(b, streaming) { open = false }
}

/**
 * The floating glass window for one block ([GlassFloatingWindow]), drawn by the block's own renderer with real bounded
 * constraints: diagrams and pictures fit the window, then pinch-zoom / pan (double-tap zooms in / resets); tables scroll
 * both ways under a sticky header; charts, compare cards and mini tools take the window width and scroll vertically.
 */
@Composable
fun RichBlockViewer(b: RichReplies.Block, streaming: Boolean, onDismiss: () -> Unit) {
    val mode = RichPreview.mode(b, streaming)
    GlassFloatingWindow(RichPreview.title(b), onDismiss, subtitle = RichPreview.kind(b), fillHeight = mode == RichPreview.Mode.ZOOM) {
        when (mode) {
            RichPreview.Mode.ZOOM -> ZoomPanBox(maxWidth) { RichBlockContent(b, streaming, window = true) }
            RichPreview.Mode.TABLE -> RichPreview.table(b)?.let { TableWindow(it) }
            RichPreview.Mode.SCROLL -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(2.dp)) {
                RichBlockContent(b, streaming, window = true)
            }
        }
    }
}

/**
 * Lays [content] out at the window's width with unbounded height, then scales it to fit the window (never up) and lets
 * you pinch-zoom (1–8×), pan, and double-tap (zoom in at the tap / back to fit).
 */
@Composable
private fun ZoomPanBox(width: Dp, content: @Composable () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var child by remember { mutableStateOf(IntSize.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    fun fit(): Float = if (child.height > 0 && child.width > 0 && box.height > 0)
        minOf(1f, box.height.toFloat() / child.height, box.width.toFloat() / child.width) else 1f
    fun clamp(p: Offset, z: Float): Offset {
        val s = fit() * z
        val mx = ((child.width * s - box.width) / 2f).coerceAtLeast(0f)
        val my = ((child.height * s - box.height) / 2f).coerceAtLeast(0f)
        return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
    }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { box = it }
        .pointerInput(Unit) {
            detectTransformGestures { _, p, z, _ ->
                val nz = (zoom * z).coerceIn(1f, 8f)
                zoom = nz
                pan = if (nz <= 1.001f) Offset.Zero else clamp(pan + p, nz)
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { o ->
                if (zoom > 1.01f) { zoom = 1f; pan = Offset.Zero }
                else { zoom = 2.5f; pan = clamp((Offset(box.width / 2f, box.height / 2f) - o) * 1.5f, 2.5f) }
            })
        }
        .semantics { contentDescription = "Pinch to zoom, double-tap to reset" },
        contentAlignment = Alignment.Center) {
        Box(Modifier.requiredWidth(width).wrapContentHeight(Alignment.CenterVertically, unbounded = true).onSizeChanged { child = it }
            .graphicsLayer { val s = fit() * zoom; scaleX = s; scaleY = s; translationX = pan.x; translationY = pan.y }) { content() }
    }
}

/**
 * A table in the window: the title and Copy / TSV, a sticky header row, then the body scrolling vertically; header and
 * body share one horizontal scroll so wide tables slide sideways together. Columns are sized to their content
 * (72–280 dp) and cells wrap.
 */
@Composable
private fun TableWindow(t: RichKit.TableCard) {
    val c = LocalBlaze.current
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val cols = t.cols
    if (cols == 0) return
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val d = LocalDensity.current
    val headStyle = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = TableCardStyle.Head)
    val cellStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = TableCardStyle.Cell)
    val widths = remember(t) {
        val all = listOfNotNull(t.headers.takeIf { it.isNotEmpty() }) + t.rows
        (0 until cols).map { k ->
            val natural = all.maxOf { r ->
                val plain = Md.plain(r.getOrElse(k) { "" }).take(120)
                measurer.measure(plain, cellStyle, maxLines = 1).size.width
            }
            with(d) { (natural.toDp() + 24.dp).coerceIn(72.dp, 280.dp) }
        }
    }
    val h = rememberScrollState()
    val v = rememberScrollState()
    val codeBg = Color.White.copy(alpha = 0.12f)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(TableCardStyle.Fill)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t.title.ifBlank { "${t.rows.size} rows · $cols columns" }, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = if (t.title.isBlank()) FontWeight.Normal else FontWeight.Bold,
                    fontSize = if (t.title.isBlank()) 12.sp else 15.sp, color = if (t.title.isBlank()) TableCardStyle.Head else TableCardStyle.Cell))
            fun copy(s: String, what: String) { clip.setText(AnnotatedString(s)); Toast.makeText(ctx, what, Toast.LENGTH_SHORT).show() }
            Text("Copy", modifier = Modifier.clip(RoundedCornerShape(50)).clickable { copy(RichKit.tablePlain(t), "Copied") }.padding(horizontal = 10.dp, vertical = 6.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TableCardStyle.Head))
            Text("TSV", modifier = Modifier.clip(RoundedCornerShape(50)).clickable { copy(RichKit.tableTsv(t), "Copied as TSV") }.padding(horizontal = 10.dp, vertical = 6.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TableCardStyle.Head))
        }
        if (t.headers.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f)).horizontalScroll(h)) {
                for (k in 0 until cols) {
                    val x = t.headers.getOrElse(k) { "" }
                    Text(remember(x) { Md.render(x, c.accent, codeBg) }, modifier = Modifier.width(widths[k]).padding(horizontal = 12.dp, vertical = 9.dp), style = headStyle)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(TableCardStyle.Divider))
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(v)) {
            SelectionContainer {
                Column(Modifier.horizontalScroll(h)) {
                    t.rows.forEachIndexed { r, row ->
                        Row(Modifier.background(if (r % 2 == 1) Color.White.copy(alpha = 0.03f) else Color.Transparent)) {
                            for (k in 0 until cols) {
                                val x = row.getOrElse(k) { "" }
                                Text(remember(x) { Md.render(x, c.accent, codeBg) }, modifier = Modifier.width(widths[k]).padding(horizontal = 12.dp, vertical = 10.dp),
                                    style = if (k == 0) cellStyle.copy(fontWeight = FontWeight.Medium) else cellStyle)
                            }
                        }
                    }
                }
            }
        }
    }
}
