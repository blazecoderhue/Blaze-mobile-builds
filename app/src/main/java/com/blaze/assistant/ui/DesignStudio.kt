package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FlipToFront
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.DesignPage
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v0.35.0 Design studio, Page mode: the page drawn natively from its spec, with pan (one finger on the
 * board), pinch / double-tap zoom, tap to select, drag to move and corner / edge handles to resize.
 * State is hoisted: [onChange] gets every edit while the finger moves; the studio saves after a pause.
 */
class PageView {
    var zoom by mutableStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    fun fit() { zoom = 1f; pan = Offset.Zero }
}

/** The board's dotted grid (the concept's canvas), in the theme's hairline colour. */
fun Modifier.dotGrid(color: Color, step: Float = 40f): Modifier = drawBehind {
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) { drawCircle(color, 1.4f, Offset(x, y)); x += step }
        y += step
    }
}

@Composable
fun PageCanvas(doc: DesignPage.Doc, view: PageView, selected: String?, outlines: Boolean, highlight: String? = null,
               onSelect: (String?) -> Unit, onChange: (DesignPage.El) -> Unit, onEditText: (DesignPage.El) -> Unit,
               modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    val dens = LocalDensity.current
    val docNow by rememberUpdatedState(doc)
    val selectedNow by rememberUpdatedState(selected)
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()
        .background(if (c.dark) Color(0xFF060607) else Color(0xFFE7E7EA))
        .dotGrid(c.hair)
        // One finger pans the board, two pinch; a tap on the board clears the selection, a double tap zooms.
        .pointerInput(Unit) { detectTransformGestures { _, p, z, _ -> view.zoom = (view.zoom * z).coerceIn(0.5f, 4f); view.pan += p } }
        .pointerInput(Unit) { detectTapGestures(onTap = { onSelect(null) }, onDoubleTap = { if (view.zoom > 1f) view.fit() else view.zoom = 2f }) }) {
        // Room for the floating pills: 60 px each side, 48 top and bottom (as the concept).
        val availW = constraints.maxWidth - with(dens) { 60.dp.toPx() }
        val availH = constraints.maxHeight - with(dens) { 104.dp.toPx() }
        val s = min(availW / doc.w, availH / doc.h).coerceAtLeast(0.01f)   // screen px per page px at zoom 1
        val wDp = with(dens) { (doc.w * s).toDp() }; val hDp = with(dens) { (doc.h * s).toDp() }
        val layer = Modifier.align(Alignment.Center).size(wDp, hDp).graphicsLayer {
            scaleX = view.zoom; scaleY = view.zoom; translationX = view.pan.x; translationY = view.pan.y }
        // The page itself (clipped like the export).
        Box(layer.shadow(18.dp, RoundedCornerShape(6.dp), clip = false).clip(RoundedCornerShape(6.dp))
            .background(argbColor(DesignPage.parseHex(doc.background) ?: 0xFF0B0B0EL))) {
            for (e in doc.painted()) ElementView(e, s, outlines || e.id == highlight, c.dark)
        }
        // Hit targets and the selection frame sit on an unclipped twin, so handles at the edge stay reachable.
        Box(layer) {
            for (e in doc.painted()) {
                if (e.id == selected) continue
                Box(Modifier.offset { IntOffset((e.x * s).roundToInt(), (e.y * s).roundToInt()) }
                    .size(with(dens) { (e.w * s).toFloat().toDp() }, with(dens) { (e.h * s).toFloat().toDp() })
                    .pointerInput(e.id) { detectTapGestures(onTap = { onSelect(e.id) }, onDoubleTap = { onSelect(e.id); if (e.isText) onEditText(e) }) }
                    .semantics { contentDescription = e.label })
            }
            doc.el(selected)?.let { e -> SelectionFrame(e, s, view.zoom, c.dark,
                onDrag = { h, dx, dy ->
                    val cur = docNow.el(selectedNow) ?: return@SelectionFrame
                    onChange(cur.at(DesignPage.drag(cur.frame(), h, (dx / s).toDouble(), (dy / s).toDouble(), keepAspect = cur.type == "ellipse",
                        bounds = docNow.w.toDouble() to docNow.h.toDouble())))
                }, onEditText = { docNow.el(selectedNow)?.takeIf { it.isText }?.let(onEditText) }) }
        }
    }
}

@Composable
private fun ElementView(e: DesignPage.El, s: Float, outline: Boolean, dark: Boolean) {
    val dens = LocalDensity.current
    val shape = if (e.type == "ellipse") RoundedCornerShape(50) else RoundedCornerShape(with(dens) { (e.radius * s).toFloat().toDp() })
    val fill = DesignPage.parseHex(e.fill)
    Box(Modifier.offset { IntOffset((e.x * s).roundToInt(), (e.y * s).roundToInt()) }
        .size(with(dens) { (e.w * s).toFloat().toDp() }, with(dens) { (e.h * s).toFloat().toDp() })
        .then(if (fill != null) Modifier.clip(shape).background(argbColor(fill), shape) else Modifier)
        .then(if (outline) Modifier.border(1.dp, Color(0x704DA8FF), shape) else Modifier)
        .padding(horizontal = if (e.type == "button") with(dens) { (e.size * s * 0.6).toFloat().toDp() } else 0.dp),
        contentAlignment = when (e.align) { "center" -> Alignment.Center; "right" -> Alignment.CenterEnd; else -> Alignment.CenterStart }) {
        if (e.isText && e.text.isNotEmpty()) Text(e.text,
            style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight(e.weight.coerceIn(100, 900)),
                fontSize = with(dens) { (e.size * s).toFloat().toSp() }, lineHeight = with(dens) { (e.size * s * 1.1).toFloat().toSp() },
                color = argbColor(DesignPage.parseHex(e.color) ?: 0xFFF2F2F3L),
                textAlign = when (e.align) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start }))
    }
}

/** The selected element's frame: drag inside to move, the four corners and four edges to resize. Handles keep their size at any zoom. */
@Composable
private fun SelectionFrame(e: DesignPage.El, s: Float, zoom: Float, dark: Boolean, onDrag: (DesignPage.Handle, Float, Float) -> Unit, onEditText: () -> Unit) {
    val dens = LocalDensity.current
    val blue = if (dark) Color(0xFF4DA8FF) else Color(0xFF2E8BF0)
    val w = (e.w * s).toFloat(); val h = (e.h * s).toFloat()
    Box(Modifier.offset { IntOffset((e.x * s).roundToInt(), (e.y * s).roundToInt()) }
        .size(with(dens) { w.toDp() }, with(dens) { h.toDp() })
        .border((1.5f / zoom).dp, blue)
        .pointerInput(e.id) { detectDragGestures { ch, d -> ch.consume(); onDrag(DesignPage.Handle.MOVE, d.x, d.y) } }
        .pointerInput(e.id) { detectTapGestures(onDoubleTap = { onEditText() }) }
        .semantics { contentDescription = "Selected: ${e.label}. Drag to move." }) {
        val hs = 22f / zoom   // touch size of a handle, dp
        val dot = 11f / zoom
        val handles = listOf(
            DesignPage.Handle.TL to Offset(0f, 0f), DesignPage.Handle.TR to Offset(1f, 0f), DesignPage.Handle.BL to Offset(0f, 1f), DesignPage.Handle.BR to Offset(1f, 1f),
            DesignPage.Handle.T to Offset(.5f, 0f), DesignPage.Handle.B to Offset(.5f, 1f), DesignPage.Handle.L to Offset(0f, .5f), DesignPage.Handle.R to Offset(1f, .5f))
        for ((handle, at) in handles) {
            val corner = handle in setOf(DesignPage.Handle.TL, DesignPage.Handle.TR, DesignPage.Handle.BL, DesignPage.Handle.BR)
            val hsPx = with(dens) { hs.dp.toPx() }
            Box(Modifier.offset { IntOffset((w * at.x - hsPx / 2).roundToInt(), (h * at.y - hsPx / 2).roundToInt()) }.size(hs.dp)
                .pointerInput(e.id, handle) { detectDragGestures { ch, d -> ch.consume(); onDrag(handle, d.x, d.y) } }
                .semantics { contentDescription = "Resize ${handle.name.lowercase()}" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (corner) dot.dp else (dot * 0.8f).dp).clip(BlazeShapes.pill).background(Color.White, BlazeShapes.pill)
                    .border((1.5f / zoom).dp, blue, BlazeShapes.pill))
            }
        }
    }
}

// ---------------- studio pieces ----------------

/** A round frosted icon button (the rail, the header). */
@Composable
fun StudioIcon(icon: ImageVector, label: String, on: Boolean = false, size: Int = 36, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.size(size.dp).clip(BlazeShapes.pill).then(if (on) Modifier.background(c.ink, BlazeShapes.pill) else Modifier)
        .clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (on) c.paper else c.ink, modifier = Modifier.size(19.dp))
    }
}

/** A floating frosted pill on the stage. */
@Composable
fun StudioPill(text: String, small: String = "", icon: ImageVector? = null, on: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = LocalBlaze.current
    Row(modifier.height(30.dp).clip(BlazeShapes.pill)
        .then(if (on) Modifier.background(c.ink, BlazeShapes.pill) else Modifier.glass(BlazeShapes.pill, GlassIntensity.Medium, shadow = false))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = if (on) c.paper else c.ink, modifier = Modifier.size(15.dp)); if (text.isNotEmpty()) Spacer(Modifier.width(5.dp)) }
        if (text.isNotEmpty()) Text(text, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = if (on) c.paper else c.ink))
        if (small.isNotEmpty()) { Spacer(Modifier.width(5.dp)); Text(small, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = (if (on) c.paper else c.ink).copy(alpha = 0.55f))) }
    }
}

/** The element action bar over the stage: Text, Colour, Front, Ask, Remove. */
@Composable
fun ElementBar(e: DesignPage.El, onText: () -> Unit, onColour: () -> Unit, onFront: () -> Unit, onAsk: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    Row(modifier.clip(BlazeShapes.pill).background(c.menuSurface, BlazeShapes.pill).border(1.dp, c.hair2, BlazeShapes.pill).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun b(icon: ImageVector, label: String, show: Boolean = true, f: () -> Unit) {
            if (!show) return
            Row(Modifier.height(32.dp).clip(BlazeShapes.pill).clickable(onClick = f).padding(horizontal = 10.dp).semantics { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = c.ink, modifier = Modifier.size(15.dp))
                if (label != "Remove") { Spacer(Modifier.width(5.dp)); Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = c.ink)) }
            }
        }
        b(Icons.Rounded.TextFields, "Text", e.isText, onText)
        b(Icons.Rounded.Palette, "Colour", true, onColour)
        b(Icons.Rounded.FlipToFront, "Front", true, onFront)
        b(Icons.Rounded.AutoAwesome, "Ask", true, onAsk)
        b(Icons.Rounded.Delete, "Remove", true, onRemove)
    }
}

/** The critic chip: score and the one thing to fix. */
@Composable
fun CriticChip(cr: DesignPage.Critique, onClick: () -> Unit) {
    val c = LocalBlaze.current
    val tone = when { cr.score >= 85 -> GlassTokens.success; cr.score >= 65 -> GlassTokens.warning; else -> GlassTokens.error }
    Row(Modifier.height(30.dp).clip(BlazeShapes.pill).glass(BlazeShapes.pill, GlassIntensity.Medium, shadow = false).clickable(onClick = onClick)
        .padding(start = 5.dp, end = 11.dp).semantics { contentDescription = "Design check ${cr.score}: ${cr.note}" }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(21.dp).clip(BlazeShapes.pill).background(tone, BlazeShapes.pill), contentAlignment = Alignment.Center) {
            Text("${cr.score}", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = Color(0xFF0B0B0C)))
        }
        Spacer(Modifier.width(7.dp))
        Text(cr.note, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.ink))
    }
}

/** A bottom sheet inside the studio (over a scrim), the concept's frosted sheet. */
@Composable
fun StudioSheet(title: String, subtitle: String = "", onDismiss: () -> Unit, right: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { onDismiss() } })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 36.dp, bottomEnd = 36.dp))
            .background(c.menuSurface).border(1.dp, c.hair, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 36.dp, bottomEnd = 36.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 20.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).size(38.dp, 5.dp).clip(BlazeShapes.pill).background(c.hair2, BlazeShapes.pill))
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), maxLines = 1,
                    style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = c.ink))
                if (right != null) right() else if (subtitle.isNotEmpty()) Text(subtitle, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = c.sub))
            }
            content()
        }
    }
}

/** Format sheet: the page sizes, each drawn to its ratio. */
@Composable
fun FormatGrid(current: String, onPick: (DesignPage.Format) -> Unit) {
    val c = LocalBlaze.current
    DesignPage.FORMATS.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { f ->
                val on = f.id == current
                Column(Modifier.weight(1f).clip(BlazeShapes.medium).border(1.5.dp, if (on) c.ink else c.hair, BlazeShapes.medium).clickable { onPick(f) }
                    .padding(top = 12.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val k = 34f / maxOf(f.w, f.h)
                    Box(Modifier.size((f.w * k).dp, (f.h * k).dp).border(1.5.dp, if (on) c.ink else c.sub, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(8.dp))
                    Text(f.label, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = if (on) c.ink else c.sub))
                    Text(f.ratio, style = TextStyle(fontFamily = Type.Sans, fontSize = 10.5.sp, color = c.weak))
                }
            }
        }
    }
}

/** Colour sheet body: Text / Fill target, the wheel, and a size slider for text. */
@Composable
fun ColourSheetBody(e: DesignPage.El, target: String, onTarget: (String) -> Unit, onColour: (String) -> Unit, onSize: (Double) -> Unit, onDone: () -> Unit) {
    val c = LocalBlaze.current
    val value = if (target == "fill") e.fill.ifBlank { "#4DA8FF" } else e.color
    ColorWheelPicker(value, onColour, Modifier.fillMaxWidth())
    if (e.isText) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).hairlineTop(c.hair).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Size", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, color = c.sub))
            Spacer(Modifier.width(12.dp))
            Slider(e.size.toFloat(), { onSize(it.roundToInt().toDouble()) }, valueRange = 8f..240f, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = c.ink, activeTrackColor = Color(0xFF4DA8FF), inactiveTrackColor = c.hair2))
            Text("${e.size.roundToInt()}px", modifier = Modifier.width(52.dp), textAlign = TextAlign.End,
                style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, color = c.sub))
        }
    }
    Spacer(Modifier.height(12.dp))
    BlazeButton("Done", modifier = Modifier.fillMaxWidth(), onClick = onDone)
}

/** The Text / Fill switch in the colour sheet's title row. */
@Composable
fun ColourTarget(target: String, canText: Boolean, onTarget: (String) -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.clip(BlazeShapes.pill).background(c.faint, BlazeShapes.pill).padding(3.dp)) {
        listOf("color" to "Text", "fill" to "Fill").filter { it.first == "fill" || canText }.forEach { (k, label) ->
            val on = target == k
            Box(Modifier.height(28.dp).clip(BlazeShapes.pill).then(if (on) Modifier.background(c.raised, BlazeShapes.pill) else Modifier)
                .clickable { onTarget(k) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(label, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, color = if (on) c.ink else c.sub))
            }
        }
    }
}

/** Text sheet body: edit an element's words. */
@Composable
fun TextSheetBody(initial: String, onSave: (String) -> Unit) {
    val c = LocalBlaze.current
    var t by remember(initial) { mutableStateOf(initial) }
    Box(Modifier.fillMaxWidth().heightIn(min = 90.dp).clip(BlazeShapes.medium).background(c.faint, BlazeShapes.medium).border(1.dp, c.hair, BlazeShapes.medium).padding(14.dp)) {
        BasicTextField(t, { t = it }, cursorBrush = SolidColor(c.accent), textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink),
            modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(12.dp))
    BlazeButton("Done", modifier = Modifier.fillMaxWidth()) { onSave(t) }
}
