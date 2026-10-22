package com.blaze.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Memory
import com.blaze.assistant.agent.Mind
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.draw.clip

/**
 * Memory (v0.19.0): Blaze Desktop's Memory view on the phone. An animated graph of the memories
 * table (Mind.all(): kind, subject, related), with a list toggle, search, and a detail card to
 * view, edit and forget a memory. [mems0], [mode0], [select0] and [query0] are for screenshots.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MemoryPage(mems0: List<Mind.Mem>? = null, mode0: String = "", select0: Long = 0L, query0: String = "") {
    val c = LocalBlaze.current.mono
    val facts by Memory.facts.collectAsState()
    val mems = remember(facts, mems0) {
        mems0 ?: if (Mind.ready) Mind.all() else facts.mapIndexed { i, f -> Mind.Mem(-(i + 1L), "fact", "", f) }
    }
    var mode by remember { mutableStateOf(mode0.ifBlank { runCatching { Settings.str("memory_view", "graph") }.getOrDefault("graph") }) }
    var query by remember { mutableStateOf(query0) }
    var sel by remember { mutableLongStateOf(select0) }
    if (sel != 0L && mems.none { it.id == sel }) sel = 0L
    // v0.26.3: one plain line when blaze.db couldn't be used (Blaze is on the copy it kept in mind.db).
    val notice = if (mems0 == null) Mind.notice else null

    if (mems.isEmpty()) {
        PageHead("Memory", "What Blaze remembers")
        if (notice != null) Text(notice, modifier = Modifier.padding(bottom = 8.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 17.sp, color = c.sub))
        Rows { EmptyState("Nothing remembered yet",
            "Tell Blaze things like \u201cremember my sister is Mary\u201d, or just talk: it saves lasting facts as you go. Each one appears here as a dot, linked to the people and projects it's about.") }
        return
    }
    val graph = remember(mems) { MemGraph(mems).also { it.settle() } }
    val q = query.trim()
    val matches = remember(q, mems) {
        if (q.isEmpty()) null else mems.filter { (it.subject + " " + it.text + " " + it.kind + " " + it.related).contains(q, true) }.map { it.id }.toSet()
    }
    val kinds = graph.cats.size
    PageHead("Memory", "What Blaze remembers",
        "${mems.size} ${if (mems.size == 1) "memory" else "memories"} \u00b7 $kinds ${if (kinds == 1) "kind" else "kinds"} \u00b7 ${graph.edges.size} ${if (graph.edges.size == 1) "link" else "links"}")
    if (notice != null) Text(notice, modifier = Modifier.padding(bottom = 8.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 17.sp, color = c.sub))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        KitSearch(query, { query = it }, "Search memories", Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Segmented(listOf("graph" to "Graph", "list" to "List"), mode) { mode = it; runCatching { Settings.setStr("memory_view", it) } }
    }
    if (matches != null) Label(if (matches.isEmpty()) "No memory matches \u201c$q\u201d" else "${matches.size} ${if (matches.size == 1) "match" else "matches"}",
        size = 9.sp, color = if (matches.isEmpty()) c.sub else c.accent, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(12.dp))
    val selected = mems.firstOrNull { it.id == sel }
    if (mode == "graph") {
        MemoryGraphView(graph, selectedId = sel, matches = matches) { sel = it }
        if (graph.clipped > 0) Label("Showing the newest ${graph.nodes.size}. The list has all ${mems.size}.", size = 9.sp, modifier = Modifier.padding(top = 8.dp))
        if (selected != null) {
            // Bring the card into view when a dot is tapped (it sits under the graph on a phone).
            val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
            LaunchedEffect(sel) { delay(60); runCatching { bring.bringIntoView() } }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.bringIntoViewRequester(bring)) { MemoryDetail(selected, graph, onSelect = { sel = it }, onClose = { sel = 0L }) }
        }
    } else {
        MemoryList(mems, matches, sel, graph) { sel = if (sel == it) 0L else it }
    }
    Spacer(Modifier.height(16.dp))
    ForgetAll(mems.size)
}

@Composable
private fun ForgetAll(n: Int) {
    val c = LocalBlaze.current.mono
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Backups (Settings \u203a Backup) include every memory.", modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 17.sp, color = c.sub))
        Spacer(Modifier.width(10.dp))
        KitButton(if (armed) "Forget all $n?" else "Forget all", if (armed) Btn.INK else Btn.GHOST) {
            if (armed) { Memory.clear(); armed = false } else armed = true
        }
    }
}

// ---------------------------------------------------------------- graph

private class Cam(var x: Float = 0f, var y: Float = 0f, var z: Float = 1f)

/**
 * The canvas. The layout is already settled, so a frame only draws: breathing dots, the newest
 * memory's pulse, the reveal. Frames are capped at ~20 fps and stop altogether after 12 s without
 * a touch, while the app isn't resumed (screen off, another app), or when the page leaves.
 */
@Composable
fun MemoryGraphView(graph: MemGraph, selectedId: Long, matches: Set<Long>?, height: androidx.compose.ui.unit.Dp = 420.dp,
                    onSelect: (Long) -> Unit) {
    val c = LocalBlaze.current.mono
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val cam = remember(graph) { Cam() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var camTick by remember { mutableIntStateOf(0) }
    var frame by remember { mutableLongStateOf(0L) }
    var started by remember(graph) { mutableLongStateOf(-1L) }
    var poke by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lstate by lifecycle.currentStateFlow.collectAsState()
    val resumed = lstate.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    val reduce = remember { runCatching { !Settings.particles }.getOrDefault(false) }
    val dens = androidx.compose.ui.platform.LocalDensity.current.density

    fun fit() {
        if (size.width == 0) return
        val b = graph.bounds()
        // Room for the overlays: count + tools and the top cluster's label above, the hint below.
        val side = 36f; val top = 76f; val bottom = 44f
        val w = size.width / dens; val h = size.height / dens
        val z = min(2.2f, max(0.15f, min((w - side * 2) / (b[2] - b[0]).coerceAtLeast(1f), (h - top - bottom) / (b[3] - b[1]).coerceAtLeast(1f))))
        cam.x = (b[0] + b[2]) / 2; cam.y = (b[1] + b[3]) / 2 - (top - bottom) / 2f / z; cam.z = z; camTick++
    }
    LaunchedEffect(graph) { fit() }

    // The frame clock: ~20 fps, only while it's worth drawing. It stops after IDLE_MS without a touch
    // (or after the reveal when particles are off), while the app isn't resumed, and when the page leaves.
    LaunchedEffect(resumed, poke, graph) {
        if (!resumed) return@LaunchedEffect
        var first = -1L
        while (true) {
            var t0 = 0L
            androidx.compose.runtime.withFrameMillis { t -> frame = t; t0 = t; if (first < 0) first = t; if (started < 0) started = t }
            if (t0 - first > (if (reduce) REVEAL_MS + 100 else IDLE_MS)) break
            delay(FRAME_MS)
        }
    }
    fun touch() { poke++ }

    val selIndex = graph.nodes.firstOrNull { it.mem.id == selectedId }?.i ?: -1
    val near = remember(selIndex, graph) { if (selIndex < 0) emptySet() else graph.neighbours(selIndex).map { it.first }.toSet() }
    val matchIdx = remember(matches, graph) { matches?.let { m -> graph.nodes.filter { it.mem.id in m }.map { it.i }.toSet() } }
    val layouts = remember(graph, c) { HashMap<String, TextLayoutResult>() }
    val labelStyle = TextStyle(fontFamily = Type.Mono, fontSize = 9.5.sp, letterSpacing = 0.06.em, fontWeight = FontWeight.Medium)
    val catStyle = TextStyle(fontFamily = Type.Mono, fontSize = 10.sp, letterSpacing = 0.14.em, fontWeight = FontWeight.Medium)
    fun layout(t: String, st: TextStyle): TextLayoutResult = layouts.getOrPut(t + "|" + st.fontSize) { measurer.measure(t, st, maxLines = 1) }

    Box(Modifier.fillMaxWidth().height(height).pageGlass(c, PageGlass.row).clipToBounds()
        .onSizeChanged { if (it != size) { size = it; fit() } }) {
        Canvas(Modifier.fillMaxSize()
            .pointerInput(graph) {
                detectTapGestures(onTap = { p ->
                    val d = density
                    val wx = (p.x - size.width / 2f) / (cam.z * d) + cam.x
                    val wy = (p.y - size.height / 2f) / (cam.z * d) + cam.y
                    val i = graph.hit(wx, wy, 22f / cam.z)
                    onSelect(if (i >= 0) graph.nodes[i].mem.id else 0L); touch()
                }, onDoubleTap = { fit(); touch() })
            }
            .pointerInput(graph) {
                // Two fingers pan and pinch; one finger is left to the page's scroll.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val down = ev.changes.count { it.pressed }
                        if (down >= 2) {
                            val zoom = ev.calculateZoom(); val pan = ev.calculatePan(); val cen = ev.calculateCentroid()
                            val d = density
                            val wx = (cen.x - size.width / 2f) / (cam.z * d) + cam.x
                            val wy = (cen.y - size.height / 2f) / (cam.z * d) + cam.y
                            val z = (cam.z * zoom).coerceIn(0.15f, 6f)
                            cam.x = wx - (cen.x - size.width / 2f) / (z * d) - pan.x / (z * d)
                            cam.y = wy - (cen.y - size.height / 2f) / (z * d) - pan.y / (z * d)
                            cam.z = z; camTick++; touch()
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }) {
            camTick.let { }
            val t = frame
            dotGrid(this, c)
            val d = density; val z = cam.z * d; val w = this.size.width; val h = this.size.height
            fun sx(x: Float) = (x - cam.x) * z + w / 2f
            fun sy(y: Float) = (y - cam.y) * z + h / 2f
            val ns = graph.nodes
            val anyDim = selIndex >= 0 || matchIdx != null
            fun dimmed(i: Int) = when {
                selIndex >= 0 -> i != selIndex && i !in near
                matchIdx != null -> i !in matchIdx
                else -> false
            }
            val reveal = if (started < 0) 0f else ((t - started) / REVEAL_MS.toFloat()).coerceIn(0f, 1f)

            // Links: one hairline pass, then the focused node's links in blue.
            val edgeCol = c.ink.copy(alpha = if (c.dark) 0.20f else 0.15f)
            for (e in graph.edges) {
                if (selIndex >= 0 && (e.a == selIndex || e.b == selIndex)) continue
                val ra = revealOf(e.a, ns.size, reveal); val rb = revealOf(e.b, ns.size, reveal)
                if (ra <= 0f || rb <= 0f) continue
                val A = ns[e.a]; val B = ns[e.b]
                val a = if (anyDim && (dimmed(e.a) || dimmed(e.b))) 0.35f else 1f
                drawLine(edgeCol.copy(alpha = edgeCol.alpha * a * min(ra, rb)), Offset(sx(A.x), sy(A.y)), Offset(sx(B.x), sy(B.y)), 1f)
            }
            if (selIndex >= 0) for (e in graph.edges) {
                if (e.a != selIndex && e.b != selIndex) continue
                val A = ns[e.a]; val B = ns[e.b]
                drawLine(c.accent.copy(alpha = 0.75f), Offset(sx(A.x), sy(A.y)), Offset(sx(B.x), sy(B.y)), 1.25f * d)
            }

            // Cluster labels (KIND · 03) and hairline corner brackets, like Desktop's categories.
            val placed = ArrayList<FloatArray>()
            if (graph.cats.size > 1) graph.cats.forEachIndexed { k, name ->
                val members = ns.filter { it.cat == k }
                if (members.isEmpty()) return@forEachIndexed
                val cx = members.map { it.x }.average().toFloat()
                val x0 = members.minOf { it.x }; val x1 = members.maxOf { it.x }; val y0 = members.minOf { it.y }; val y1 = members.maxOf { it.y }
                val txt = "${name.uppercase()} \u00b7 %02d".format(members.size)
                val lay = layout(txt, catStyle)
                val X = sx(cx) - lay.size.width / 2f; val Y = sy(y0) - 16f * d - lay.size.height
                val dimCat = selIndex >= 0 && ns[selIndex].cat != k
                drawText(lay, color = if (selIndex >= 0 && !dimCat) c.accent else c.sub, topLeft = Offset(X, Y), alpha = reveal * (if (dimCat) 0.35f else 0.75f))
                placed += floatArrayOf(X - 6f, Y - 2f, X + lay.size.width + 6f, Y + lay.size.height + 2f)
                if (members.size > 1 && cam.z > 0.3f) {
                    val bx0 = sx(x0) - 14f * d; val bx1 = sx(x1) + 14f * d; val by0 = sy(y0) - 9f * d; val by1 = sy(y1) + 14f * d; val L = 7f * d
                    val col = (if (selIndex >= 0 && !dimCat) c.accent else c.ink).copy(alpha = reveal * (if (selIndex >= 0 && !dimCat) 0.8f else 0.22f))
                    listOf(
                        floatArrayOf(bx0, by0 + L, bx0, by0), floatArrayOf(bx0, by0, bx0 + L, by0), floatArrayOf(bx1 - L, by0, bx1, by0), floatArrayOf(bx1, by0, bx1, by0 + L),
                        floatArrayOf(bx1, by1 - L, bx1, by1), floatArrayOf(bx1, by1, bx1 - L, by1), floatArrayOf(bx0 + L, by1, bx0, by1), floatArrayOf(bx0, by1, bx0, by1 - L)
                    ).forEach { s -> drawLine(col, Offset(s[0], s[1]), Offset(s[2], s[3]), 1f) }
                }
            }

            // Nodes: square particle dots. Big on screen: a breathing 3x3 grid of dots; small: one square.
            for (n in ns) {
                val r = revealOf(n.i, ns.size, reveal)
                if (r <= 0f) continue
                val X = sx(n.x); val Y = sy(n.y)
                if (X < -40 || Y < -40 || X > w + 40 || Y > h + 40) continue
                var s = max(4f * d, n.size * z) * (0.4f + 0.6f * r)
                val b = 1f + sin(t / 1300f + n.i * 1.7f) * 0.07f
                val dim = dimmed(n.i)
                val hot = n.i == selIndex || (matchIdx != null && n.i in matchIdx)
                val col = if (hot) c.accent else c.ink
                val base = if (dim) (if (c.dark) 0.22f else 0.16f) else 1f
                if (s >= 9f * d) {
                    val g = s / 3f; val ds = g * 0.62f * b; val o = (g - ds) / 2f
                    for (yy in 0 until 3) for (xx in 0 until 3) {
                        val a = if ((xx + yy) % 2 == 1 && !hot) (if (dim) 0.1f else 0.55f) else base
                        // v0.24.8: round particle dots (were squares).
                        drawCircle(col.copy(alpha = a * r), ds / 2f, Offset(X - s / 2 + xx * g + g / 2f, Y - s / 2 + yy * g + g / 2f))
                    }
                } else { s *= b; drawCircle(col.copy(alpha = base * r), s / 2f, Offset(X, Y)) }
                if (n.i == selIndex) {
                    val rr = s / 2 + 5f * d
                    drawCircle(c.accent, rr, Offset(X, Y), style = Stroke(1f * d))
                }
                // The newest memory sends out a slow blue pulse.
                if (n.i == graph.newest && selIndex < 0 && !reduce && reveal >= 1f) {
                    val ph = (t % 2600L) / 2600f; val rr = s / 2 + 3f * d + ph * 16f * d
                    drawCircle(c.accent.copy(alpha = (1 - ph) * 0.6f), rr, Offset(X, Y), style = Stroke(1f * d))
                }
            }

            // Labels: mono uppercase under the node. The focus and its neighbours always; otherwise the
            // most-linked first, only when there's room, never overlapping.
            val lz = if (ns.size > 200) 1.5f else if (ns.size > 60) 1.1f else 0.7f
            val top = ns.sortedByDescending { it.deg }.take(if (cam.z >= lz) 60 else 12).map { it.i }.toSet()
            val want = ns.filter { it.i == selIndex || it.i in near || (matchIdx != null && it.i in matchIdx) || (selIndex < 0 && matchIdx == null && it.i in top) }
                .sortedWith(compareByDescending<MemGraph.Node> { it.i == selIndex }.thenByDescending { it.i in near }.thenByDescending { it.deg })
            var count = 0
            val said = HashSet<String>()
            for (n in want) {
                if (count >= 40) break
                val txt = n.label.uppercase().let { if (it.length > 22) it.take(21) + "\u2026" else it }
                // One label per subject (three "You" memories read as one YOU), the selected one always.
                if (n.i != selIndex && txt in said) continue
                val lay = layout(txt, labelStyle)
                val X = sx(n.x) - lay.size.width / 2f; val Y = sy(n.y) + max(4f * d, n.size * z) / 2f + 5f * d
                if (X + lay.size.width < 0 || X > w || Y > h || Y < 0) continue
                val rect = floatArrayOf(X - 4f, Y - 1f, X + lay.size.width + 4f, Y + lay.size.height + 1f)
                val forced = n.i == selIndex
                if (!forced && placed.any { p -> rect[0] < p[2] && rect[2] > p[0] && rect[1] < p[3] && rect[3] > p[1] }) continue
                placed += rect; count++; said += txt
                // A paper backing keeps labels readable where they cross links and dots.
                drawRoundRect(c.paper.copy(alpha = if (forced) 0.95f else 0.82f * reveal), Offset(rect[0], rect[1]), Size(rect[2] - rect[0], rect[3] - rect[1]),
                    androidx.compose.ui.geometry.CornerRadius(6f * d, 6f * d))
                drawText(lay, color = if (forced) c.accent else c.ink, topLeft = Offset(X, Y), alpha = reveal * (if (forced) 1f else if (dimmed(n.i)) 0.25f else 0.78f))
            }
        }
        // Count (top left), tools (top right), hint (bottom left).
        Label("${graph.nodes.size} ${if (graph.nodes.size == 1) "memory" else "memories"} \u00b7 ${graph.edges.size} links", size = 9.sp, color = c.weak,
            maxLines = 1, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        Row(Modifier.align(Alignment.TopEnd).padding(10.dp).pageGlass(c, PageGlass.pill)) {
            GraphTool("+") { cam.z = (cam.z * 1.3f).coerceAtMost(6f); camTick++; touch() }
            Box(Modifier.width(1.dp).height(30.dp).background(c.hair))
            GraphTool("\u2212") { cam.z = (cam.z / 1.3f).coerceAtLeast(0.15f); camTick++; touch() }
            Box(Modifier.width(1.dp).height(30.dp).background(c.hair))
            GraphTool("Fit") { fit(); touch() }
        }
        Label(if (selIndex >= 0) "Tap empty space to clear" else "Tap a dot to open it \u00b7 pinch to zoom", size = 9.sp, color = c.weak, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
    }
}

@Composable
private fun GraphTool(text: String, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.height(30.dp).width(if (text.length > 1) 40.dp else 32.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(fontFamily = if (text.length > 1) Type.Mono else Type.Sans, fontSize = if (text.length > 1) 10.sp else 16.sp,
            letterSpacing = if (text.length > 1) 0.1.em else 0.em, color = c.sub))
    }
}

private const val FRAME_MS = 50L
private const val IDLE_MS = 12_000L
private const val REVEAL_MS = 800L

/** Nodes appear oldest first over the reveal (a quick version of Desktop's replay). */
private fun revealOf(i: Int, n: Int, reveal: Float): Float {
    if (reveal >= 1f) return 1f
    val at = if (n <= 1) 0f else i.toFloat() / n * 0.6f
    return ((reveal - at) / 0.4f).coerceIn(0f, 1f)
}

/** Desktop's stage background: a faint dot grid. */
private fun dotGrid(ds: DrawScope, c: BlazeColors) = with(ds) {
    val step = 18f * density; val r = 0.9f * density
    val col = c.ink.copy(alpha = if (c.dark) 0.16f else 0.13f)
    var y = step / 2
    while (y < size.height) { var x = step / 2; while (x < size.width) { drawCircle(col, r / 2f, Offset(x, y)); x += step }; y += step }
}

// ---------------------------------------------------------------- list

@Composable
private fun MemoryList(mems: List<Mind.Mem>, matches: Set<Long>?, sel: Long, graph: MemGraph, onTap: (Long) -> Unit) {
    val shown = mems.filter { matches == null || it.id in matches }
    if (shown.isEmpty()) { Rows { EmptyState("No memory matches", "Try a name, a place or a word from the memory.", mark = false) }; return }
    Mind.KINDS.forEach { k ->
        val group = shown.filter { Mind.normKind(it.kind) == k }.sortedByDescending { it.updated.coerceAtLeast(it.created) }
        if (group.isEmpty()) return@forEach
        GroupHead("$k \u00b7 %02d".format(group.size))
        Rows {
            group.forEachIndexed { i, m ->
                KitRow(m.subject.ifBlank { m.text }, if (m.subject.isBlank()) "" else m.text, first = i == 0,
                    onClick = { onTap(m.id) }, lead = { KindMark(k, on = m.id == sel) })
                if (m.id == sel) Box(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                    MemoryDetail(m, graph, onSelect = { onTap(it) }, onClose = { onTap(m.id) })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun KindMark(kind: String, on: Boolean = false) {
    val c = LocalBlaze.current.mono
    Box(Modifier.size(8.dp).background(if (on) c.accent else c.ink.copy(alpha = 0.85f), BlazeShapes.pill))
}

// ---------------------------------------------------------------- detail

private val dateFmt = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
private fun date(ms: Long) = if (ms <= 0) "\u2014" else dateFmt.format(java.util.Date(if (ms < 100_000_000_000L) ms * 1000 else ms))

/** View, edit and forget one memory (Desktop's detail panel). Forget asks for a second tap. */
@Composable
fun MemoryDetail(m: Mind.Mem, graph: MemGraph, onSelect: (Long) -> Unit, onClose: () -> Unit) {
    val c = LocalBlaze.current.mono
    var text by remember(m.id, m.text) { mutableStateOf(m.text) }
    var subject by remember(m.id, m.subject) { mutableStateOf(m.subject) }
    var kind by remember(m.id, m.kind) { mutableStateOf(Mind.normKind(m.kind)) }
    var armed by remember(m.id) { mutableStateOf(false) }
    var note by remember(m.id) { mutableStateOf("") }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    val changed = text.trim() != m.text || subject.trim() != m.subject || kind != Mind.normKind(m.kind)
    val idx = graph.nodes.firstOrNull { it.mem.id == m.id }?.i ?: -1
    val links = if (idx >= 0) graph.neighbours(idx) else emptyList()

    Row(Modifier.fillMaxWidth().pageGlass(c, PageGlass.row)
        .drawBehind { drawRoundRect(c.accent, topLeft = androidx.compose.ui.geometry.Offset(4.dp.toPx(), 10.dp.toPx()), size = Size(3.dp.toPx(), (size.height - 20.dp.toPx()).coerceAtLeast(1f)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())) }) {
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(c.accent, BlazeShapes.pill)); Spacer(Modifier.width(8.dp))
                Label(kind, size = 10.sp, color = c.sub, modifier = Modifier.weight(1f), maxLines = 1)
                Box(Modifier.size(36.dp).clip(BlazeShapes.pill).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Text("\u00d7", style = TextStyle(fontFamily = Type.Sans, fontSize = 18.sp, color = c.sub))
                }
            }
            Text(m.subject.ifBlank { "Memory" }, modifier = Modifier.padding(end = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp, letterSpacing = (-0.01).em, color = c.ink))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().padding(end = 8.dp).pageGlass(c, PageGlass.row).heightIn(min = 84.dp).padding(12.dp)) {
                BasicTextField(text, { text = it; note = "" }, cursorBrush = SolidColor(c.accent),
                    textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 21.sp, color = c.ink), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            // Meta, as Desktop's two-column rows.
            Column(Modifier.fillMaxWidth().padding(end = 8.dp).hairlineTop(c.hair)) {
                MetaRow("Kind") {
                    // Six kinds in two rows of three, so none is cut off on a 360dp phone.
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Mind.KINDS.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                row.forEach { k ->
                                    val on = k == kind
                                    Box(Modifier.weight(1f).clip(BlazeShapes.pill).background(if (on) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent, BlazeShapes.pill).clickable { kind = k; note = "" }
                                        .padding(vertical = 5.dp), contentAlignment = Alignment.Center) {
                                        Text(k.replaceFirstChar { it.uppercase() }, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = if (on) c.accent else c.sub))
                                    }
                                }
                            }
                        }
                    }
                }
                MetaRow("Subject") {
                    BasicTextField(subject, { subject = it; note = "" }, singleLine = true, cursorBrush = SolidColor(c.accent),
                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body), modifier = Modifier.fillMaxWidth())
                }
                if (m.related.isNotBlank()) MetaRow("Related") { MetaText(m.related) }
                MetaRow("Saved") { MetaText(date(m.created) + if (m.updated > m.created) " \u00b7 updated " + date(m.updated) else "") }
            }
            Spacer(Modifier.height(12.dp))
            Label("Linked \u00b7 %02d".format(links.size), size = 10.sp)
            Spacer(Modifier.height(6.dp))
            if (links.isEmpty()) Text("No links yet. Memories link when they share a subject, name each other, or came from the same chat.",
                modifier = Modifier.padding(end = 8.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 18.sp, color = c.weak))
            links.take(8).forEach { (j, e) ->
                val o = graph.nodes[j].mem
                Row(Modifier.fillMaxWidth().clickable { onSelect(o.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(c.ink.copy(alpha = 0.85f), BlazeShapes.pill)); Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(o.subject.ifBlank { o.text }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, color = c.ink))
                        Label(e.why, size = 9.sp, color = c.weak, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitButton("Save", Btn.ACCENT, enabled = changed && text.isNotBlank()) {
                    val ok = if (m.id < 0) { Memory.add(text.trim(), replaces = m.text); true }
                    else Mind.update(m.id, text.trim(), kind, subject.trim())
                    note = if (ok) "Saved" else "Not saved: that looks like a secret"
                }
                KitButton(if (armed) "Tap again to forget" else "Forget", if (armed) Btn.INK else Btn.LINE) {
                    if (!armed) armed = true else {
                        if (m.id < 0) Memory.remove((-m.id - 1).toInt()) else Mind.delete(m.id)
                        armed = false; onClose()
                    }
                }
            }
            if (note.isNotEmpty()) Label(note, size = 9.sp, color = c.accent, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun MetaRow(label: String, content: @Composable () -> Unit) {
    val c = LocalBlaze.current.mono
    Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).heightIn(min = 36.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, size = 9.5.sp, modifier = Modifier.width(72.dp), maxLines = 1)
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun MetaText(t: String) {
    val c = LocalBlaze.current.mono
    Text(t, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.body))
}
