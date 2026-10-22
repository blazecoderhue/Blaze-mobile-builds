package com.blaze.assistant.browser

import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.Glass
import com.blaze.assistant.ui.GlassIconButton
import com.blaze.assistant.ui.MascotArt
import com.blaze.assistant.ui.MascotGlyph
import com.blaze.assistant.ui.Type
import kotlin.math.roundToInt

/** Where a link from the pointer opens. */
enum class OpenMode { SAME, NEW, BACKGROUND }

/** What the page pointer can do in its host (the browser, or a view's WebView). */
class PointerHost(
    val openUrl: (String, OpenMode) -> Unit,
    val ask: (String) -> Unit,
    val pip: ((PagePointer.Hit, Float, Float) -> Unit)? = null,
    val isPrivate: Boolean = false,
)

fun PagePointer.Action.icon(): ImageVector = when (this) {
    PagePointer.Action.DOWNLOAD -> Icons.Rounded.Download
    PagePointer.Action.COPY_LINK -> Icons.Rounded.Link
    PagePointer.Action.PIP -> Icons.Rounded.PictureInPictureAlt
    PagePointer.Action.SAVE_IMAGE -> Icons.Rounded.SaveAlt
    PagePointer.Action.COPY_IMAGE_LINK -> Icons.Rounded.Link
    PagePointer.Action.SEARCH_IMAGE -> Icons.Rounded.ImageSearch
    PagePointer.Action.OPEN -> Icons.Rounded.OpenInBrowser
    PagePointer.Action.OPEN_BG -> Icons.Rounded.Tab
    PagePointer.Action.SHARE -> Icons.Rounded.Share
    PagePointer.Action.COPY_TEXT -> Icons.Rounded.ContentCopy
    PagePointer.Action.TRANSLATE -> Icons.Rounded.Translate
    PagePointer.Action.EXPLAIN -> Icons.Rounded.Lightbulb
    PagePointer.Action.SUMMARISE -> Icons.Rounded.Summarize
    PagePointer.Action.ASK -> Icons.Rounded.AutoAwesome
}

fun PagePointer.Kind.icon(): ImageVector = when (this) {
    PagePointer.Kind.VIDEO -> Icons.Rounded.Movie
    PagePointer.Kind.AUDIO -> Icons.Rounded.Audiotrack
    PagePointer.Kind.IMAGE -> Icons.Rounded.Image
    PagePointer.Kind.LINK -> Icons.Rounded.Link
    PagePointer.Kind.TEXT -> Icons.Rounded.TextFields
    PagePointer.Kind.OTHER -> Icons.Rounded.Widgets
}

/**
 * The summoned pointer over a WebView: drag the mascot and the element under its flame tip lights up with a
 * rounded glow (JS hit-testing in the page); double-tap the mascot for the action menu, placed where there's room.
 * Touches anywhere else go to the page as usual. [preview] / [previewMenu] / [startTip] are for screenshots.
 */
@Composable
fun PagePointerLayer(web: WebView?, host: PointerHost, onClose: () -> Unit, modifier: Modifier = Modifier,
                     preview: PagePointer.Hit? = null, previewMenu: Boolean = false, startTip: Offset? = null) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val W = constraints.maxWidth.toFloat(); val H = constraints.maxHeight.toFloat()
        val d = density.density
        var tip by remember { mutableStateOf(startTip ?: Offset(W * 0.5f, H * 0.42f)) }
        var hit by remember { mutableStateOf(preview) }
        var menu by remember { mutableStateOf(previewMenu) }
        var inflight by remember { mutableStateOf(false) }
        var again by remember { mutableStateOf(false) }
        var menuAfter by remember { mutableStateOf(false) }

        fun probe() {
            val w = web ?: return
            if (inflight) { again = true; return }
            inflight = true
            try {
                w.evaluateJavascript(PagePointer.hitJs(tip.x, tip.y)) { r ->
                    inflight = false
                    hit = PagePointer.parse(r)
                    if (menuAfter) { menuAfter = false; menu = hit != null }
                    if (again) { again = false; probe() }
                }
            } catch (_: Exception) { inflight = false }
        }
        LaunchedEffect(web) { if (preview == null) probe() }

        // Highlight: a rounded outline with a soft glow around the element under the tip.
        hit?.takeIf { it.w > 0f && it.h > 0f }?.let { h ->
            Canvas(Modifier.fillMaxSize()) {
                val pad = 4f * d
                val tl = Offset(h.l - pad, h.t - pad); val sz = Size(h.w + pad * 2, h.h + pad * 2)
                val cr = CornerRadius(12f * d, 12f * d)
                drawRoundRect(Glass.blue.copy(alpha = 0.08f), tl, sz, cr)
                for (i in 3 downTo 1) drawRoundRect(Glass.blue.copy(alpha = 0.10f * (4 - i)), tl, sz, cr, style = Stroke(width = i * 4f * d))
                drawRoundRect(Glass.brand, tl, sz, cr, style = Stroke(width = 2f * d))
            }
        }

        // The mascot: its flame tip is the pointer.
        val box = 60.dp
        val boxPx = with(density) { box.toPx() }
        val tipIn = MascotArt.glyphTip(boxPx)
        Box(Modifier
            .offset { IntOffset((tip.x - tipIn.x).roundToInt(), (tip.y - tipIn.y).roundToInt()) }
            .size(box)
            .semantics { contentDescription = "Blaze pointer. Drag onto something on the page, double-tap for options." }
            .pointerInput(web) {
                detectDragGestures(onDragEnd = { probe() }) { ch, delta ->
                    ch.consume(); menu = false
                    tip = Offset((tip.x + delta.x).coerceIn(0f, W - 1f), (tip.y + delta.y).coerceIn(0f, H - 1f))
                    probe()
                }
            }
            .pointerInput(web) {
                detectTapGestures(onDoubleTap = { if (hit != null) menu = true else { menuAfter = true; probe() } },
                    onTap = { if (menu) menu = false else Toast.makeText(ctx, "Double-tap Blaze for options", Toast.LENGTH_SHORT).show() })
            }) {
            MascotGlyph(box, pulse = inflight || menu)
        }

        // Hint + put-away button at the top.
        Row(Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 12.dp, end = 12.dp)
            .shadow(10.dp, Glass.pill).clip(Glass.pill).background(Glass.surface).border(1.dp, Glass.border, Glass.pill)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(hit?.let { "On: " + it.describe } ?: "Drag Blaze onto anything \u00b7 double-tap for options", maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 250.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Glass.text))
            Spacer(Modifier.width(6.dp))
            GlassIconButton(Icons.Rounded.Close, "Put the pointer away", size = 32.dp, onClick = onClose)
        }

        // The action menu.
        val h = hit
        if (menu && h != null) {
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { menu = false })
            val actions = PagePointer.actionsFor(h.kind)
            val (mw, mh) = PagePointer.menuSize(actions.size, W / d - 24f)
            val p = PagePointer.place(tip.x, tip.y, h.t, h.t + h.h, mw * d, mh * d, W, H, 12f * d, 10f * d)
            Box(Modifier.offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }) {
                PointerMenuCard(h, actions, mw) { a ->
                    menu = false
                    runAction(ctx, a, h, web, host, tip)
                }
            }
        }
    }
}

private fun runAction(ctx: android.content.Context, a: PagePointer.Action, h: PagePointer.Hit, web: WebView?, host: PointerHost, tip: Offset) {
    val toast = { s: String -> Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show() }
    when (a) {
        PagePointer.Action.DOWNLOAD, PagePointer.Action.SAVE_IMAGE -> PointerActions.download(ctx, h.src, web, host.isPrivate)
        PagePointer.Action.COPY_LINK -> PointerActions.copy(ctx, "Link", h.href.ifBlank { h.src })
        PagePointer.Action.COPY_IMAGE_LINK -> PointerActions.copy(ctx, "Image link", h.src)
        PagePointer.Action.PIP -> host.pip?.invoke(h, tip.x, tip.y) ?: toast("Picture-in-picture works in Blaze's browser.")
        PagePointer.Action.SEARCH_IMAGE -> if (h.src.startsWith("http")) host.openUrl(PointerActions.lensUrl(h.src), OpenMode.NEW)
            else toast("This picture has no web address to search with.")
        PagePointer.Action.OPEN -> host.openUrl(h.href, OpenMode.SAME)
        PagePointer.Action.OPEN_BG -> { host.openUrl(h.href, OpenMode.BACKGROUND); toast("Opened in a background tab") }
        PagePointer.Action.SHARE -> PointerActions.share(ctx, h.href)
        PagePointer.Action.COPY_TEXT -> PointerActions.copy(ctx, "Text", h.words)
        PagePointer.Action.TRANSLATE -> if (h.words.isBlank()) toast("No text here to translate.") else host.openUrl(PointerActions.translateUrl(h.words), OpenMode.NEW)
        PagePointer.Action.EXPLAIN, PagePointer.Action.SUMMARISE, PagePointer.Action.ASK ->
            host.ask(PagePointer.prompt(a, h, web?.title.orEmpty(), web?.url.orEmpty()))
    }
}

/** One tile of the pointer menu (shared by the page pointer and Magic Hand, v0.24.7). */
class PointerTile(val label: String, val spoken: String, val icon: ImageVector, val primary: Boolean = false, val onClick: () -> Unit)

/** The floating action menu: a rounded dark card, the element on top, then icon tiles with short labels. */
@Composable
fun PointerMenuCard(h: PagePointer.Hit, actions: List<PagePointer.Action>, widthDp: Float, onPick: (PagePointer.Action) -> Unit) =
    PointerTiles(h.kind.icon(), h.describe, actions.map { a ->
        PointerTile(PagePointer.label(a, h.kind), PagePointer.spoken(a, h.kind), a.icon(), a == PagePointer.Action.ASK) { onPick(a) }
    }, widthDp)

/** The v0.24.6 pointer menu's look for any set of tiles (Magic Hand reuses it for its radial menu). */
@Composable
fun PointerTiles(headIcon: ImageVector, head: String, tiles: List<PointerTile>, widthDp: Float) {
    val shape = RoundedCornerShape(22.dp)
    val cols = PagePointer.columns(tiles.size)
    val tile = ((widthDp - PagePointer.PAD * 2) / cols)
    Column(Modifier.width(widthDp.dp).shadow(16.dp, shape).clip(shape).background(Glass.surface).border(1.dp, Glass.border, shape)
        .padding(PagePointer.PAD.dp)) {
        Row(Modifier.fillMaxWidth().height((PagePointer.HEAD - 4).dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(headIcon, contentDescription = null, tint = Glass.orange, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(head, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Glass.sub))
        }
        Spacer(Modifier.height(4.dp))
        tiles.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.Start) {
                row.forEach { t ->
                    Column(Modifier.width(tile.dp).height(PagePointer.TILE_H.dp).clip(RoundedCornerShape(16.dp))
                        .clickable(onClickLabel = t.spoken) { t.onClick() }
                        .semantics { contentDescription = t.spoken }
                        .padding(top = 6.dp, start = 2.dp, end = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(if (t.primary) Glass.blue else Glass.raised2),
                            contentAlignment = Alignment.Center) {
                            Icon(t.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(t.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            style = TextStyle(fontFamily = Type.Sans, fontSize = 10.5.sp, lineHeight = 12.sp, color = Glass.text))
                    }
                }
            }
        }
    }
}
