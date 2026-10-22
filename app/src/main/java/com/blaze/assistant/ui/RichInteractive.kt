package com.blaze.assistant.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * v0.26.1 visual answers, drawn: native Compose charts (bar, line, pie, scatter; tap for the value), tappable choice
 * buttons, small forms, live mini tools and compare cards. Rounded cards in the Blaze theme, solid classic Blaze blue,
 * no gradients, no WebView. Pure parsing and maths live in [RichKit] / [Expr].
 */
object RichSend {
    /** How a tapped choice or a submitted form becomes the user's next message (null = read-only, e.g. while Blaze works). */
    val Local = staticCompositionLocalOf<((String) -> Unit)?> { null }
}

private val cardShape = RoundedCornerShape(18.dp)
private val pillShape = RoundedCornerShape(20.dp)
private val fieldShape = RoundedCornerShape(12.dp)

/**
 * Series colours. v0.31 glass: monochrome first (frost white, mid grey), then the desaturated semantic tints and greys,
 * so charts stay readable without blue / orange. [0] doubles as the controls' accent; ink on it is [GlassTokens.onAccent].
 */
val chartColors = listOf(GlassTokens.accent, Color(0xFF8E8E96), GlassTokens.warning, GlassTokens.success,
    GlassTokens.error, Color(0xFFBDBDC4), Color(0xFF66666E), Color(0xFFA9AFB8))

/** Controls' accent, theme-aware: frost white on dark, near-black ink on light (so picked pills stay visible). */
@Composable
private fun richAccent(): Color = if (LocalBlaze.current.dark) GlassTokens.accent else GlassTokens.lightInk
@Composable
private fun richOnAccent(): Color = if (LocalBlaze.current.dark) GlassTokens.onAccent else Color.White

@Composable
private fun richFill(): Color { val c = LocalBlaze.current; return if (LocalBubble.current != null) GlassBubble.cardFill(c) else c.faint }

@Composable
private fun CardTitle(text: String) {
    if (text.isBlank()) return
    val c = LocalBlaze.current
    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
}

@Composable
private fun small(color: Color? = null) = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 16.sp, color = color ?: LocalBlaze.current.sub)

// ---------------- chart ----------------

@Composable
fun RichChart(ch: RichKit.Chart) {
    val c = LocalBlaze.current
    var sel by remember(ch) { mutableStateOf<Pair<Int, Int>?>(null) }
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val axisStyle = TextStyle(fontFamily = Type.Sans, fontSize = 9.5.sp, color = c.sub)
    val blue = richAccent()
    Column(Modifier.fillMaxWidth().clip(cardShape).background(richFill()).padding(14.dp)
        .semantics { contentDescription = "Chart: " + ch.title.ifBlank { ch.type.name.lowercase() } + ". Tap a bar or point to read its value." }) {
        CardTitle(ch.title)
        // The tapped value, in a small solid pill (or a hint until something is tapped).
        val tip = sel?.let { RichKit.tipText(ch, it.first, it.second) }.orEmpty()
        Box(Modifier.heightIn(min = 26.dp).padding(bottom = 6.dp)) {
            if (tip.isNotEmpty()) Text(tip, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(pillShape).background(blue).padding(horizontal = 10.dp, vertical = 4.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = richOnAccent()))
            else Text("Tap the chart to see values", style = small(c.weak))
        }
        when (ch.type) {
            RichKit.ChartType.PIE -> PieChart(ch, sel?.second) { i -> sel = if (i == null) null else 0 to i }
            else -> {
                val pts = remember(ch) { ArrayList<Triple<Int, Int, Pair<Float, Float>>>() }
                Canvas(Modifier.fillMaxWidth().height(190.dp).pointerInput(ch) {
                    detectTapGestures { o ->
                        val left = 40.dp.toPx(); val w = size.width - left
                        sel = when (ch.type) {
                            RichKit.ChartType.BAR -> {
                                val n = ch.labels.size
                                val i = RichKit.barIndex(o.x - left, w, n)
                                if (i < 0) null else {
                                    // The series under the finger inside a group of bars.
                                    val groupW = w / n; val inner = (o.x - left - i * groupW) / groupW
                                    val s = ((inner - 0.15f) / 0.7f * ch.series.size).toInt().coerceIn(0, ch.series.size - 1)
                                    s to i
                                }
                            }
                            else -> RichKit.nearest(o.x, o.y, pts.map { it.third }, 28.dp.toPx())?.let { pts[it].first to pts[it].second }
                        }
                    }
                }) {
                    val left = 40.dp.toPx(); val bottom = 22.dp.toPx(); val top = 6.dp.toPx()
                    val w = size.width - left; val h = size.height - bottom - top
                    val all = if (ch.type == RichKit.ChartType.SCATTER) ch.points.flatten().map { it.y } else ch.series.flatMap { it.values }.filter { it.isFinite() }
                    val (lo, hi, step) = RichKit.niceAxis(all.minOrNull() ?: 0.0, all.maxOrNull() ?: 1.0, 4, fromZero = ch.type == RichKit.ChartType.BAR)
                    fun yOf(v: Double) = top + h - ((v - lo) / (hi - lo)).toFloat() * h
                    // Grid + y labels.
                    var t = lo
                    var guard = 0
                    while (t <= hi + step / 2 && guard++ < 12) {
                        val y = yOf(t)
                        drawLine(c.hair, Offset(left, y), Offset(size.width, y), 1f)
                        val lay = measurer.measure(RichKit.short(t), axisStyle)
                        drawText(lay, topLeft = Offset(left - lay.size.width - 6.dp.toPx(), y - lay.size.height / 2f))
                        t += step
                    }
                    pts.clear()
                    when (ch.type) {
                        RichKit.ChartType.BAR -> {
                            val n = ch.labels.size; val groupW = w / n; val ns = ch.series.size
                            val barW = groupW * 0.7f / ns
                            for (i in 0 until n) for (s in 0 until ns) {
                                val v = ch.series[s].values[i]; if (!v.isFinite()) continue
                                val x = left + i * groupW + groupW * 0.15f + s * barW
                                val y0 = yOf(0.0.coerceIn(lo, hi)); val y1 = yOf(v)
                                val col = chartColors[s % chartColors.size].let { if (sel != null && sel != (s to i)) it.copy(alpha = 0.45f) else it }
                                val r = min(6.dp.toPx(), barW / 2f)
                                drawRoundRect(col, Offset(x + barW * 0.06f, min(y0, y1)), Size(barW * 0.88f, max(2f, kotlin.math.abs(y1 - y0))),
                                    androidx.compose.ui.geometry.CornerRadius(r, r))
                            }
                            xLabels(ch.labels, left, w, size.height - bottom + 4.dp.toPx(), measurer, axisStyle)
                        }
                        RichKit.ChartType.LINE -> {
                            val n = ch.labels.size
                            fun xOf(i: Int) = left + if (n <= 1) w / 2f else i * (w / (n - 1))
                            ch.series.forEachIndexed { s, ser ->
                                val col = chartColors[s % chartColors.size]
                                val path = Path(); var started = false
                                ser.values.forEachIndexed { i, v ->
                                    if (!v.isFinite()) { started = false; return@forEachIndexed }
                                    val p = Offset(xOf(i), yOf(v))
                                    if (!started) { path.moveTo(p.x, p.y); started = true } else path.lineTo(p.x, p.y)
                                    pts += Triple(s, i, p.x to p.y)
                                }
                                drawPath(path, col, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                                ser.values.forEachIndexed { i, v ->
                                    if (!v.isFinite()) return@forEachIndexed
                                    val on = sel == (s to i)
                                    drawCircle(col, if (on) 5.5.dp.toPx() else 3.dp.toPx(), Offset(xOf(i), yOf(v)))
                                    if (on) drawCircle(Color.White, 2.dp.toPx(), Offset(xOf(i), yOf(v)))
                                }
                            }
                            xLabels(ch.labels, left, w, size.height - bottom + 4.dp.toPx(), measurer, axisStyle, points = true)
                        }
                        else -> {
                            val xs = ch.points.flatten().map { it.x }
                            val (xlo, xhi, xstep) = RichKit.niceAxis(xs.minOrNull() ?: 0.0, xs.maxOrNull() ?: 1.0, 4, fromZero = false)
                            fun xOf(v: Double) = left + ((v - xlo) / (xhi - xlo)).toFloat() * w
                            var xt = xlo; var g = 0
                            while (xt <= xhi + xstep / 2 && g++ < 12) {
                                val lay = measurer.measure(RichKit.short(xt), axisStyle)
                                drawText(lay, topLeft = Offset((xOf(xt) - lay.size.width / 2f).coerceIn(left, size.width - lay.size.width), size.height - bottom + 4.dp.toPx()))
                                xt += xstep
                            }
                            ch.points.forEachIndexed { s, list ->
                                val col = chartColors[s % chartColors.size]
                                list.forEachIndexed { i, p ->
                                    val o = Offset(xOf(p.x), yOf(p.y)); pts += Triple(s, i, o.x to o.y)
                                    val on = sel == (s to i)
                                    drawCircle(col.copy(alpha = if (on) 1f else 0.8f), if (on) 6.dp.toPx() else 4.dp.toPx(), o)
                                }
                            }
                        }
                    }
                }
                val names = if (ch.type == RichKit.ChartType.SCATTER) ch.pointNames else ch.series.map { it.name }
                if (names.count { it.isNotBlank() } >= 1 && names.size > 1 || (ch.yLabel.isNotBlank() || ch.xLabel.isNotBlank()) && ch.type == RichKit.ChartType.SCATTER)
                    Legend(names.mapIndexed { i, n -> n to chartColors[i % chartColors.size] }.filter { it.first.isNotBlank() },
                        if (ch.type == RichKit.ChartType.SCATTER) listOf(ch.xLabel, ch.yLabel).filter { it.isNotBlank() }.joinToString(" \u2192 ") else "")
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.xLabels(labels: List<String>, left: Float, w: Float, y: Float,
    measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle, points: Boolean = false) {
    val n = labels.size; if (n == 0) return
    // Thin out labels that wouldn't fit (about 44 px each at this size).
    val every = max(1, kotlin.math.ceil(n * 44f / w.coerceAtLeast(1f)).toInt())
    labels.forEachIndexed { i, l ->
        if (i % every != 0) return@forEachIndexed
        val cx = if (points) left + if (n <= 1) w / 2f else i * (w / (n - 1)) else left + (i + 0.5f) * (w / n)
        val lay = measurer.measure(RichKit.axisLabel(l), style)
        drawText(lay, topLeft = Offset((cx - lay.size.width / 2f).coerceIn(0f, left + w - lay.size.width), y))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(items: List<Pair<String, Color>>, axes: String = "") {
    val c = LocalBlaze.current
    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (n, col) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(col)); Spacer(Modifier.width(5.dp))
                Text(n, maxLines = 1, style = small(c.body))
            }
        }
        if (axes.isNotBlank()) Text(axes, style = small())
    }
}

@Composable
private fun PieChart(ch: RichKit.Chart, sel: Int?, onPick: (Int?) -> Unit) {
    val vals = ch.series[0].values
    val total = vals.filter { it.isFinite() && it > 0 }.sum()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(150.dp).pointerInput(ch) {
            detectTapGestures { o ->
                val cx = size.width / 2f; val cy = size.height / 2f
                val dx = o.x - cx; val dy = o.y - cy
                val r = min(cx, cy)
                if (dx * dx + dy * dy > r * r) { onPick(null); return@detectTapGestures }
                // 0° at 12 o'clock, clockwise.
                val deg = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0 + 360.0) % 360.0).toFloat()
                onPick(RichKit.sliceAt(deg, vals).takeIf { it >= 0 })
            }
        }) {
            var start = -90f
            val gap = if (vals.count { it.isFinite() && it > 0 } > 1) 1.5f else 0f
            vals.forEachIndexed { i, v ->
                if (!v.isFinite() || v <= 0 || total <= 0) return@forEachIndexed
                val sweep = (v / total * 360.0).toFloat()
                val on = sel == i
                val inset = if (on) 0f else 4.dp.toPx()
                drawArc(chartColors[i % chartColors.size].let { if (sel != null && !on) it.copy(alpha = 0.5f) else it }, start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), true,
                    topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2))
                start += sweep
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ch.labels.forEachIndexed { i, l ->
                val v = vals.getOrNull(i) ?: Double.NaN
                if (!v.isFinite() || v <= 0) return@forEachIndexed
                Row(Modifier.clip(pillShape).clickable { onPick(if (sel == i) null else i) }.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(chartColors[i % chartColors.size])); Spacer(Modifier.width(6.dp))
                    Text("$l  ${Expr.format(v / total * 100, 0)}%", maxLines = 1, overflow = TextOverflow.Ellipsis, style = small(LocalBlaze.current.body))
                }
            }
        }
    }
}

// ---------------- choices ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RichChoices(ch: RichKit.Choices) {
    val c = LocalBlaze.current
    val send = RichSend.Local.current
    var picked by rememberSaveable(ch.toString()) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        if (ch.prompt.isNotBlank()) Text(ch.prompt, modifier = Modifier.padding(bottom = 8.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.ink))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ch.options.forEach { o ->
                val on = picked == o.label
                val enabled = send != null && picked.isEmpty()
                Text(o.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(pillShape).background(if (on) richAccent() else c.accentSoft)
                        .clickable(enabled = enabled, onClickLabel = "Send \u201c${o.label}\u201d") { picked = o.label; send?.invoke(o.send) }
                        .heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = if (on) richOnAccent() else if (send == null && picked.isEmpty()) c.sub else c.accent))
            }
        }
    }
}

// ---------------- form ----------------

@Composable
private fun RichField(value: String, onChange: (String) -> Unit, placeholder: String, number: Boolean, enabled: Boolean, unit: String = "") {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().clip(fieldShape).background(c.field).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.weak))
            BasicTextField(value, { onChange(if (number) RichKit.numberInput(it) else it.take(120)) }, enabled = enabled, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
                cursorBrush = SolidColor(richAccent()),
                textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink), modifier = Modifier.fillMaxWidth())
        }
        if (unit.isNotEmpty()) Text(unit, style = small())
    }
}

@Composable
private fun Segments(options: List<String>, selected: String, enabled: Boolean, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o.equals(selected, ignoreCase = true)
            Text(o, maxLines = 1, modifier = Modifier.clip(pillShape).background(if (on) richAccent() else c.field)
                .clickable(enabled = enabled) { onPick(o) }.padding(horizontal = 14.dp, vertical = 8.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) richOnAccent() else c.body))
        }
    }
}

@Composable
private fun BlueButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(pillShape).background(if (enabled) richAccent() else richAccent().copy(alpha = 0.4f))
        .clickable(enabled = enabled, onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
        Text(label, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = richOnAccent()))
    }
}

@Composable
fun RichForm(f: RichKit.Form) {
    val c = LocalBlaze.current
    val send = RichSend.Local.current
    val vals = remember(f) { mutableStateMapOf<String, String>().apply { f.fields.forEach { put(it.id, it.default) } } }
    var sent by rememberSaveable(f.toString()) { mutableStateOf(false) }
    val enabled = send != null && !sent
    Column(Modifier.fillMaxWidth().clip(cardShape).background(richFill()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (f.title.isNotBlank()) Text(f.title, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
        if (f.intro.isNotBlank()) Text(f.intro, style = small(c.body))
        f.fields.forEach { fd ->
            when (fd.type) {
                RichKit.FieldType.TOGGLE -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(fd.label, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
                    KitSwitch(vals[fd.id] == "yes") { if (enabled) vals[fd.id] = if (vals[fd.id] == "yes") "no" else "yes" }
                }
                else -> Column {
                    Text(fd.label, modifier = Modifier.padding(bottom = 5.dp), style = small())
                    if (fd.type == RichKit.FieldType.SELECT) Segments(fd.options, vals[fd.id].orEmpty(), enabled) { vals[fd.id] = it }
                    else RichField(vals[fd.id].orEmpty(), { vals[fd.id] = it }, fd.placeholder, fd.type == RichKit.FieldType.NUMBER, enabled, fd.unit)
                }
            }
        }
        BlueButton(if (sent) "Sent" else f.submit, enabled) { sent = true; send?.invoke(RichKit.formMessage(f, vals.toMap())) }
    }
}

// ---------------- tool ----------------

@Composable
fun RichTool(t: RichKit.Tool) {
    val c = LocalBlaze.current
    val vals = remember(t) { mutableStateMapOf<String, Double>().apply { putAll(RichKit.defaults(t)) } }
    val texts = remember(t) { mutableStateMapOf<String, String>().apply { t.inputs.filter { it.type == RichKit.InputType.NUMBER }.forEach { put(it.id, Expr.format(it.default, if (it.default == Math.floor(it.default)) 0 else 2).replace(",", "")) } } }
    val outs = RichKit.compute(t, vals.toMap())
    Column(Modifier.fillMaxWidth().clip(cardShape).background(richFill()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(richAccent())); Spacer(Modifier.width(8.dp))
            Text(t.title, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
        }
        t.inputs.forEach { inp ->
            val v = vals[inp.id] ?: inp.default
            when (inp.type) {
                RichKit.InputType.SLIDER -> Column {
                    Row(Modifier.fillMaxWidth()) {
                        Text(inp.label, modifier = Modifier.weight(1f), style = small())
                        Text(RichKit.showInput(inp, v), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
                    }
                    val steps = (((inp.max - inp.min) / inp.step).toInt() - 1).coerceIn(0, 200)
                    Slider(value = v.toFloat(), onValueChange = { vals[inp.id] = RichKit.snap(it.toDouble(), inp) },
                        valueRange = inp.min.toFloat()..inp.max.toFloat(), steps = if ((inp.max - inp.min) / inp.step <= 200) steps else 0,
                        colors = SliderDefaults.colors(thumbColor = richAccent(), activeTrackColor = richAccent(), inactiveTrackColor = c.hair,
                            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription = "${inp.label}: ${RichKit.showInput(inp, v)}" })
                }
                RichKit.InputType.SELECT -> Column {
                    Text(inp.label, modifier = Modifier.padding(bottom = 5.dp), style = small())
                    Segments(inp.options.map { it.label }, RichKit.showInput(inp, v), true) { l -> inp.options.firstOrNull { it.label == l }?.let { vals[inp.id] = it.value } }
                }
                RichKit.InputType.NUMBER -> Column {
                    Text(inp.label, modifier = Modifier.padding(bottom = 5.dp), style = small())
                    Row(Modifier.fillMaxWidth().clip(fieldShape).background(c.field).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (inp.prefix.isNotEmpty()) Text(inp.prefix, modifier = Modifier.padding(end = 4.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
                        BasicTextField(texts[inp.id].orEmpty(), { s ->
                            val clean = RichKit.numberInput(s); texts[inp.id] = clean
                            // A half-typed number ("", "-", "3.") leaves the value blank so the outputs go blank, not wrong.
                            vals[inp.id] = clean.toDoubleOrNull() ?: Double.NaN
                        }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), cursorBrush = SolidColor(richAccent()),
                            textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink), modifier = Modifier.weight(1f))
                        if (inp.suffix.isNotEmpty()) Text(inp.suffix, style = small())
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
        t.outputs.forEachIndexed { i, o ->
            val shown = outs.getOrElse(i) { "" }.ifEmpty { "\u2014" }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(o.label, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = if (o.big) 14.sp else 13.sp,
                    fontWeight = if (o.big) FontWeight.SemiBold else FontWeight.Normal, color = if (o.big) c.ink else c.body))
                Text(shown, style = TextStyle(fontFamily = Type.Sans, fontSize = if (o.big) 22.sp else 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if (o.big) richAccent() else c.ink))
            }
        }
        if (t.note.isNotBlank()) Text(t.note, style = small())
    }
}

// ---------------- compare ----------------

@Composable
fun RichCompare(cp: RichKit.Compare) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().clip(cardShape).background(richFill()).padding(14.dp)) {
        CardTitle(cp.title)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cp.columns.forEachIndexed { i, n ->
                val pick = i == cp.pick
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (pick) richAccent() else c.field).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(n, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (pick) richOnAccent() else c.ink))
                    if (pick) Text("Pick", style = TextStyle(fontFamily = Type.Sans, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, color = richOnAccent().copy(alpha = 0.75f)))
                }
            }
        }
        cp.rows.forEach { r ->
            Box(Modifier.fillMaxWidth().padding(top = 9.dp).height(1.dp).background(c.hair))
            Text(r.label.uppercase(), modifier = Modifier.padding(top = 7.dp, bottom = 3.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 9.5.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.Medium, color = c.sub))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.values.forEach { v ->
                    Text(v.ifBlank { "\u2014" }, modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
                }
            }
        }
        if (cp.verdict.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(cp.verdict, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.accentSoft).padding(horizontal = 12.dp, vertical = 9.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = c.ink))
        }
    }
}

// ---------------- still streaming ----------------

@Composable
fun RichPending(kind: String) {
    val c = LocalBlaze.current
    val tr = rememberInfiniteTransition(label = "pending")
    val a by tr.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pa")
    Row(Modifier.fillMaxWidth().clip(cardShape).background(richFill()).padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(richAccent().copy(alpha = a))); Spacer(Modifier.width(10.dp))
        Text(RichReplies.pendingLabel(kind), style = small())
    }
}
