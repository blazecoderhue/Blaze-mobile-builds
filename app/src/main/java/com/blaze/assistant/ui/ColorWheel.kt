package com.blaze.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.DesignPage
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** The studio's brand kit swatches (phase 1: the Blaze palette; phase 2 reads the project's brand kit). */
val BrandSwatches = listOf("#111112", "#F6F5F1", "#1F4BD8", "#4DA8FF", "#FFC94D", "#F2F2F3")

fun argbColor(argb: Long): Color = Color(argb.toInt())

/**
 * v0.35.0 colour picker: a full hue/saturation wheel, a brightness bar, an opacity bar, a hex field
 * (#RGB, #RRGGBB, #RRGGBBAA) and brand swatches. Every change reports the colour as hex.
 */
@Composable
fun ColorWheelPicker(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, wheel: Dp = 168.dp,
                     swatches: List<String> = BrandSwatches) {
    val c = LocalBlaze.current
    val start = remember(value) { DesignPage.argbToHsv(DesignPage.parseHex(value) ?: 0xFFFFFFFFL) }
    var hue by remember { mutableStateOf(start[0]) }
    var sat by remember { mutableStateOf(start[1]) }
    var v by remember { mutableStateOf(start[2]) }
    var alpha by remember { mutableStateOf(start[3]) }
    val argb = DesignPage.hsvToArgb(hue, sat, v, alpha)
    var hex by remember { mutableStateOf(DesignPage.toHex(argb)) }
    var hexFocus by remember { mutableStateOf(false) }
    // Picked from outside (a swatch, a new element): follow it.
    LaunchedEffect(value) {
        val p = DesignPage.parseHex(value) ?: return@LaunchedEffect
        if (p != DesignPage.hsvToArgb(hue, sat, v, alpha)) {
            val h = DesignPage.argbToHsv(p); hue = h[0]; sat = h[1]; v = h[2]; alpha = h[3]
        }
        if (!hexFocus) hex = DesignPage.toHex(p)
    }
    fun emit() { val a = DesignPage.hsvToArgb(hue, sat, v, alpha); if (!hexFocus) hex = DesignPage.toHex(a); onChange(DesignPage.toHex(a)) }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HueWheel(hue, sat, v, Modifier.size(wheel)) { h, s -> hue = h; sat = s; emit() }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(44.dp).clip(BlazeShapes.small).background(checker(c.dark)).border(1.dp, c.hair, BlazeShapes.small)) {
                    Box(Modifier.matchParentSize().background(argbColor(argb)))
                }
                Label("Brightness", color = c.sub, size = 9.sp)
                ColorBar(v, Brush.horizontalGradient(listOf(Color.Black, argbColor(DesignPage.hsvToArgb(hue, sat, 1.0)))), "Brightness") { v = it; emit() }
                Label("Opacity", color = c.sub, size = 9.sp)
                ColorBar(alpha, Brush.horizontalGradient(listOf(argbColor(argb).copy(alpha = 0f), argbColor(argb).copy(alpha = 1f))), "Opacity", checkered = true) { alpha = it; emit() }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(132.dp).clip(BlazeShapes.pill).background(c.faint, BlazeShapes.pill).border(1.dp, c.hair, BlazeShapes.pill)
                .padding(horizontal = 14.dp, vertical = 9.dp)) {
                BasicTextField(hex, { t ->
                    hex = t.take(9).uppercase()
                    DesignPage.parseHex(hex)?.takeIf { hex.removePrefix("#").length in setOf(3, 4, 6, 8) }?.let { p ->
                        val h = DesignPage.argbToHsv(p); hue = h[0]; sat = h[1]; v = h[2]; alpha = h[3]; onChange(DesignPage.toHex(p))
                    }
                }, singleLine = true, cursorBrush = SolidColor(c.accent),
                    textStyle = TextStyle(fontFamily = Type.Mono, fontSize = 14.sp, color = c.ink),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { hexFocus = false; hex = DesignPage.toHex(DesignPage.hsvToArgb(hue, sat, v, alpha)) }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Hex colour" }
                        .onFocusChanged { st -> hexFocus = st.isFocused; if (!st.isFocused) hex = DesignPage.toHex(DesignPage.hsvToArgb(hue, sat, v, alpha)) })
            }
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                swatches.forEach { s ->
                    val p = DesignPage.parseHex(s) ?: return@forEach
                    Box(Modifier.size(26.dp).clip(BlazeShapes.pill).background(argbColor(p), BlazeShapes.pill)
                        .border(if (p == argb) 2.dp else 1.dp, if (p == argb) c.ink else c.hair, BlazeShapes.pill)
                        .clickable { val h = DesignPage.argbToHsv(p); hue = h[0]; sat = h[1]; v = h[2]; alpha = h[3]; emit() }
                        .semantics { contentDescription = "Swatch $s" })
                }
            }
        }
    }
}

/** Hue around, saturation outwards; brightness is drawn over it so the wheel shows the real colours. */
@Composable
private fun HueWheel(hue: Double, sat: Double, v: Double, modifier: Modifier, onPick: (Double, Double) -> Unit) {
    val c = LocalBlaze.current
    fun pick(o: Offset, w: Float, h: Float) {
        val r = min(w, h) / 2f
        val dx = o.x - w / 2f; val dy = o.y - h / 2f
        val ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
        onPick(ang, (hypot(dx, dy) / r).toDouble().coerceIn(0.0, 1.0))
    }
    Canvas(modifier
        .pointerInput(Unit) { detectTapGestures { pick(it, size.width.toFloat(), size.height.toFloat()) } }
        .pointerInput(Unit) { detectDragGestures(onDragStart = { pick(it, size.width.toFloat(), size.height.toFloat()) }) { ch, _ ->
            ch.consume(); pick(ch.position, size.width.toFloat(), size.height.toFloat()) } }
        .semantics { contentDescription = "Colour wheel" }) {
        val r = min(size.width, size.height) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val hues = (0..12).map { argbColor(DesignPage.hsvToArgb(it * 30.0, 1.0, 1.0)) }
        drawCircle(Brush.sweepGradient(hues, center), r, center)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r, center)
        drawCircle(Color.Black.copy(alpha = (1 - v).toFloat()), r, center)
        drawCircle(c.hair, r, center, style = Stroke(1.dp.toPx()))
        val a = Math.toRadians(hue)
        val p = Offset(center.x + (cos(a) * sat * r).toFloat(), center.y + (sin(a) * sat * r).toFloat())
        drawCircle(Color.Black.copy(alpha = 0.35f), 11.dp.toPx(), p)
        drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(3.dp.toPx()))
        drawCircle(argbColor(DesignPage.hsvToArgb(hue, sat, v)), 7.dp.toPx(), p)
    }
}

@Composable
private fun ColorBar(value: Double, brush: Brush, name: String, checkered: Boolean = false, onValue: (Double) -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.fillMaxWidth().height(22.dp).clip(BlazeShapes.pill).then(if (checkered) Modifier.background(checker(c.dark)) else Modifier)
        .background(brush).border(1.dp, c.hair, BlazeShapes.pill)
        .pointerInput(Unit) { detectTapGestures { onValue((it.x / size.width).toDouble().coerceIn(0.0, 1.0)) } }
        .pointerInput(Unit) { detectDragGestures { ch, _ -> ch.consume(); onValue((ch.position.x / size.width).toDouble().coerceIn(0.0, 1.0)) } }
        .semantics { contentDescription = name }) {
        Canvas(Modifier.matchParentSize()) {
            val x = (value * size.width).toFloat().coerceIn(size.height / 2, size.width - size.height / 2)
            drawCircle(Color.White, size.height / 2 - 2.dp.toPx(), Offset(x, size.height / 2), style = Stroke(2.5.dp.toPx()))
        }
    }
}

/** A subtle checkerboard (as a brush) so translucent colours read as translucent. */
private fun checker(dark: Boolean): Brush {
    val a = if (dark) Color(0xFF2A2A2E) else Color(0xFFE2E2E6)
    val b = if (dark) Color(0xFF1A1A1D) else Color(0xFFF7F7F9)
    return Brush.linearGradient(listOf(a, b, a, b, a, b, a, b), start = Offset.Zero, end = Offset(36f, 36f),
        tileMode = androidx.compose.ui.graphics.TileMode.Repeated)
}
