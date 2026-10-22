package com.blaze.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v0.23.2: a native, flat-shaded drawing of a 3D design's parts (the same `blaze-parts` JSON the
 * three.js page renders). Used where WebGL isn't available: the Robolectric screenshots, and as a
 * still preview. Painter's algorithm over small meshes per shape; no Android graphics beyond a Canvas.
 */
object PartsSketch {

    /** v0.23.5: shown when a design has no part that can be drawn. */
    const val NO_PARTS = "No drawable parts in this design yet. Ask Blaze to add or fix its parts."

    data class Face(val pts: List<Pair<Float, Float>>, val depth: Double, val argb: Long)

    private class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun dot(o: V) = x * o.x + y * o.y + z * o.z
        fun norm(): V { val l = sqrt(dot(this)).takeIf { it > 1e-9 } ?: 1.0; return V(x / l, y / l, z / l) }
    }

    private fun arr(o: JSONObject, k: String, def: DoubleArray): DoubleArray =
        o.optJSONArray(k)?.let { a -> DoubleArray(def.size) { i -> a.optDouble(i, def[i]).let { v -> if (v.isNaN()) def[i] else v } } } ?: def

    /** Euler XYZ (three.js default order) in degrees, then scale, then translate. */
    private fun transform(p: JSONObject): (V) -> V {
        val (rx, ry, rz) = arr(p, "rotation", doubleArrayOf(0.0, 0.0, 0.0)).map { Math.toRadians(it) }
        val (sx, sy, sz) = arr(p, "scale", doubleArrayOf(1.0, 1.0, 1.0)).toList()
        val (tx, ty, tz) = arr(p, "position", doubleArrayOf(0.0, 0.0, 0.0)).toList()
        return { v ->
            var x = v.x * sx; var y = v.y * sy; var z = v.z * sz
            // R = Rx * Ry * Rz applied to the vector: first Rz, then Ry, then Rx.
            var t = x * cos(rz) - y * sin(rz); y = x * sin(rz) + y * cos(rz); x = t
            t = x * cos(ry) + z * sin(ry); z = -x * sin(ry) + z * cos(ry); x = t
            t = y * cos(rx) - z * sin(rx); z = y * sin(rx) + z * cos(rx); y = t
            V(x + tx, y + ty, z + tz)
        }
    }

    private fun d(p: JSONObject, k: String, def: Double) = p.optDouble(k, def).let { if (it.isNaN()) def else it }

    private val drawable = setOf("box", "cylinder", "capsule", "cone", "sphere", "torus", "plane")

    /** Local-space polygons for a part's shape. */
    private fun mesh(p: JSONObject): List<List<V>> {
        val n = 18
        fun ring(r: Double, y: Double) = (0 until n).map { i -> val a = 2 * Math.PI * i / n; V(r * cos(a), y, r * sin(a)) }
        fun tube(rBottom: Double, rTop: Double, h: Double): List<List<V>> {
            val b = ring(rBottom, -h / 2); val t = ring(rTop, h / 2)
            val sides = (0 until n).map { i -> listOf(b[i], b[(i + 1) % n], t[(i + 1) % n], t[i]) }
            return sides + listOf(b.reversed(), t)
        }
        // v0.23.5: aliases ("Cylinder", "nosecone", "CylinderGeometry") resolve; anything unknown is drawn as a box.
        val shape = com.blaze.assistant.agent.Designs.normShape(p).first.let { if (it in drawable || it == "group" || it == "custom") it else "box" }
        return when (shape) {
            "box" -> { val (w, h, dd) = arr(p, "size", doubleArrayOf(100.0, 100.0, 100.0)).map { it / 2 }
                val c = listOf(V(-w, -h, -dd), V(w, -h, -dd), V(w, h, -dd), V(-w, h, -dd), V(-w, -h, dd), V(w, -h, dd), V(w, h, dd), V(-w, h, dd))
                listOf(listOf(0, 3, 2, 1), listOf(4, 5, 6, 7), listOf(0, 1, 5, 4), listOf(3, 7, 6, 2), listOf(0, 4, 7, 3), listOf(1, 2, 6, 5)).map { f -> f.map { c[it] } } }
            "cylinder" -> tube(d(p, "radius", 50.0), d(p, "radiusTop", d(p, "radius", 50.0)), d(p, "height", 100.0))
            "capsule" -> { val r = d(p, "radius", 25.0); tube(r, r, d(p, "height", 100.0) + r) }
            "cone" -> tube(d(p, "radius", 50.0), 0.001, d(p, "height", 100.0))
            "sphere" -> { val r = d(p, "radius", 50.0); val la = 9; val lo = 16
                (0 until la).flatMap { i -> (0 until lo).map { j ->
                    fun pt(a: Int, b: Int): V { val t = Math.PI * a / la; val ph = 2 * Math.PI * b / lo; return V(r * sin(t) * cos(ph), r * cos(t), r * sin(t) * sin(ph)) }
                    listOf(pt(i, j), pt(i, j + 1), pt(i + 1, j + 1), pt(i + 1, j)) } } }
            "torus" -> { val R = d(p, "radius", 50.0); val tr = d(p, "tube", 10.0); val a = 20; val b = 8
                (0 until a).flatMap { i -> (0 until b).map { j ->
                    fun pt(u: Int, v: Int): V { val th = 2 * Math.PI * u / a; val ph = 2 * Math.PI * v / b
                        return V((R + tr * cos(ph)) * cos(th), (R + tr * cos(ph)) * sin(th), tr * sin(ph)) }
                    listOf(pt(i, j), pt(i + 1, j), pt(i + 1, j + 1), pt(i, j + 1)) } } }
            "plane" -> { val (w, dd) = arr(p, "size", doubleArrayOf(100.0, 100.0)).map { it / 2 }; listOf(listOf(V(-w, 0.0, -dd), V(w, 0.0, -dd), V(w, 0.0, dd), V(-w, 0.0, dd))) }
            else -> emptyList()
        }
    }

    private fun color(p: JSONObject): Long {
        val h = p.optString("color", "#cccccc").removePrefix("#")
        val rgb = when (h.length) { 3 -> h.map { "$it$it" }.joinToString(""); 6 -> h; else -> "cccccc" }
        return 0xFF000000L or (rgb.toLongOrNull(16) ?: 0xCCCCCC)
    }

    private fun shade(argb: Long, k: Double): Long {
        fun c(sh: Int) = (((argb shr sh) and 0xFF) * k).toInt().coerceIn(0, 255).toLong()
        return 0xFF000000L or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    /** Projected, shaded and depth-sorted faces of [spec] fitted into [w]×[h] (far faces first). */
    fun faces(spec: JSONObject, w: Float, h: Float, yawDeg: Double = 35.0, pitchDeg: Double = 22.0, zoom: Double = 1.0): List<Face> {
        val parts = spec.optJSONArray("parts") ?: JSONArray()
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until parts.length()) parts.optJSONObject(i)?.let { byId[it.optString("id")] = it }
        fun visible(p: JSONObject): Boolean { var c: JSONObject? = p; var guard = 0
            while (c != null && guard++ < 20) { if (c.has("visible") && !c.optBoolean("visible", true)) return false; c = byId[c.optString("parent")] }; return true }
        fun world(p: JSONObject): (V) -> V { val chain = ArrayList<(V) -> V>(); var c: JSONObject? = p; var guard = 0
            while (c != null && guard++ < 20) { chain += transform(c); c = byId[c.optString("parent")] }
            return { v -> chain.fold(v) { acc, f -> f(acc) } } }
        val yaw = Math.toRadians(yawDeg); val pitch = Math.toRadians(pitchDeg)
        fun view(v: V): V { val x = v.x * cos(yaw) - v.z * sin(yaw); val z0 = v.x * sin(yaw) + v.z * cos(yaw)
            val y = v.y * cos(pitch) - z0 * sin(pitch); val z = v.y * sin(pitch) + z0 * cos(pitch); return V(x, y, z) }
        val light = V(-0.4, 0.8, 0.45).norm()
        val raw = ArrayList<Triple<List<V>, Double, Long>>()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (!visible(p)) continue
            val tf = world(p); val col = color(p)
            for (poly in mesh(p)) {
                val wp = poly.map(tf)
                if (wp.size < 3) continue
                // v0.23.5: a NaN / infinite number (bad size, position, scale) would break the fit; skip that face.
                if (wp.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) continue
                val nrm = (wp[1] - wp[0]).cross(wp[2] - wp[0]).norm()
                val lit = 0.45 + 0.55 * kotlin.math.abs(nrm.dot(light))
                val vp = wp.map(::view)
                raw += Triple(vp, vp.sumOf { it.z } / vp.size, shade(col, lit))
            }
        }
        if (raw.isEmpty()) return emptyList()
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        raw.forEach { (vs, _, _) -> vs.forEach { minX = min(minX, it.x); maxX = max(maxX, it.x); minY = min(minY, it.y); maxY = max(maxY, it.y) } }
        val sc = 0.82 * zoom.coerceIn(0.2, 8.0) * min(w / max(maxX - minX, 1e-6), h / max(maxY - minY, 1e-6))
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        return raw.sortedBy { it.second }.map { (vs, depth, c) ->
            Face(vs.map { ((w / 2 + (it.x - cx) * sc).toFloat()) to ((h / 2 - (it.y - cy) * sc).toFloat()) }, depth, c)
        }
    }
}

/** Turn and zoom for the native drawing: one-finger drag turns (yaw/pitch), pinch zooms. Pure, unit-tested. */
data class SketchView(val yaw: Double = 35.0, val pitch: Double = 22.0, val zoom: Double = 1.0) {
    fun drag(dx: Float, dy: Float): SketchView = copy(yaw = (yaw - dx * 0.4) % 360.0, pitch = (pitch + dy * 0.4).coerceIn(-85.0, 85.0))
    fun pinch(factor: Float): SketchView = copy(zoom = (zoom * factor).coerceIn(0.3, 6.0))
}

/** Draws [spec] (a blaze-parts object) filling its box. v0.23.3: drag to turn, pinch to zoom ([interactive]). */
@Composable
fun PartsSketchView(spec: JSONObject, modifier: Modifier = Modifier.fillMaxSize(), interactive: Boolean = true) {
    var view by remember(spec.toString()) { mutableStateOf(SketchView()) }
    val gestures = if (!interactive) Modifier else Modifier.pointerInput(spec.toString()) {
        detectTransformGestures { _, pan, zoom, _ -> view = view.drag(pan.x, pan.y).let { if (zoom != 1f) it.pinch(zoom) else it } }
    }
    // v0.23.5: say so when nothing can be drawn, instead of an empty box.
    val drawable = remember(spec.toString()) { PartsSketch.faces(spec, 100f, 100f).isNotEmpty() }
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val fs = PartsSketch.faces(spec, size.width, size.height, view.yaw, view.pitch, view.zoom)
            for (f in fs) {
                val path = Path().apply { moveTo(f.pts[0].first, f.pts[0].second); for (k in 1 until f.pts.size) lineTo(f.pts[k].first, f.pts[k].second); close() }
                drawPath(path, Color(f.argb.toInt()), style = Fill)
            }
        }
        if (!drawable) androidx.compose.material3.Text(PartsSketch.NO_PARTS, color = Color(0xFF9E9E9E),
            modifier = Modifier.padding(16.dp))
    }
}
