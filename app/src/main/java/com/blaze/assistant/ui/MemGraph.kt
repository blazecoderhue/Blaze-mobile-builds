package com.blaze.assistant.ui

import com.blaze.assistant.agent.Mind
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The memory graph's model (v0.19.0), a phone-sized port of Blaze Desktop's Memory view
 * (frontend/dist/memory.js): memories are nodes, grouped by kind around a ring of anchors,
 * linked when they share a subject, name each other in `related`, mention each other's subject,
 * or came from the same turn. Pure Kotlin (no Android), so it's unit-tested on the JVM.
 *
 * Low CPU by design: the layout is settled once up front ([settle]), the canvas only animates
 * the breathing dots, and links are capped per node ([MAX_DEG]) and nodes in total ([MAX_NODES]).
 */
class MemGraph(mems: List<Mind.Mem>) {
    class Node(val mem: Mind.Mem, val i: Int, val cat: Int) {
        var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
        var deg = 0
        val size: Float get() = 4.5f + sqrt(deg.toFloat()) * 1.5f
        val label: String get() = mem.subject.ifBlank { mem.text.take(28) }
    }
    data class Edge(val a: Int, val b: Int, val w: Int, val why: String)
    data class Anchor(val x: Float, val y: Float)

    /** Newest [MAX_NODES] memories; the list view shows every one. */
    val mems: List<Mind.Mem> = if (mems.size > MAX_NODES) mems.sortedBy { it.created }.takeLast(MAX_NODES) else mems
    val clipped: Int = mems.size - this.mems.size
    val cats: List<String> = Mind.KINDS.filter { k -> this.mems.any { Mind.normKind(it.kind) == k } }
    val nodes: List<Node> = this.mems.mapIndexed { i, m -> Node(m, i, cats.indexOf(Mind.normKind(m.kind)).coerceAtLeast(0)) }
    val edges: List<Edge> = links(this.mems)
    val anchors: List<Anchor>
    /** Index of the newest memory: it sends out a slow blue pulse. */
    val newest: Int = nodes.maxByOrNull { it.mem.created }?.i ?: -1
    var alpha = 1f
        private set

    init {
        edges.forEach { nodes[it.a].deg++; nodes[it.b].deg++ }
        // Category anchors on a ring sized by how many nodes there are; big categories get more room.
        // Taller than wide: a phone is portrait, so the graph fills the stage instead of a strip across it.
        val n = nodes.size
        val r = if (cats.size <= 1) 0f else 70f + sqrt(n.toFloat()) * 24f
        val counts = cats.map { k -> nodes.count { cats[it.cat] == k } }
        val total = counts.sumOf { sqrt(it.toDouble()) }.takeIf { it > 0 } ?: 1.0
        var acc = -Math.PI / 2
        anchors = counts.map { cnt ->
            val span = sqrt(cnt.toDouble()) / total * Math.PI * 2; val a = acc + span / 2; acc += span
            Anchor((cos(a) * r * 0.75).toFloat(), (sin(a) * r * 1.35).toFloat())
        }.ifEmpty { listOf(Anchor(0f, 0f)) }
        // Golden-angle spiral around each anchor: a stable start, so the same memories always land the same way.
        nodes.forEachIndexed { k, nd ->
            val an = anchors[nd.cat.coerceIn(0, anchors.size - 1)]
            val j = k * 2.39996; val rr = 6 * sqrt((k % 97 + 1).toDouble())
            nd.x = (an.x + cos(j) * rr).toFloat(); nd.y = (an.y + sin(j) * rr).toFloat()
        }
    }

    fun neighbours(i: Int): List<Pair<Int, Edge>> = edges.filter { it.a == i || it.b == i }.sortedByDescending { it.w }
        .map { (if (it.a == i) it.b else it.a) to it }

    /** One step of the force layout: repulsion (capped range), springs on links, pull to the kind's anchor. */
    fun tick() {
        val ns = nodes; val n = ns.size; val a = alpha
        if (n == 0) return
        val dmax2 = 260f * 260f
        for (p in 0 until n) {
            val A = ns[p]
            for (q in p + 1 until n) {
                val B = ns[q]
                var dx = B.x - A.x; var dy = B.y - A.y; var d2 = dx * dx + dy * dy
                if (d2 > dmax2) continue
                if (d2 < 1f) { dx = ((p % 7) - 3) * 0.1f + 0.05f; dy = ((q % 5) - 2) * 0.1f + 0.05f; d2 = 1f }
                val f = -30f * a / d2
                A.vx += dx * f; A.vy += dy * f; B.vx -= dx * f; B.vy -= dy * f
            }
        }
        for (e in edges) {
            val A = ns[e.a]; val B = ns[e.b]
            var dx = B.x + B.vx - A.x - A.vx; var dy = B.y + B.vy - A.y - A.vy
            val l = sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: 1f
            val k = (l - 30f) / l * a * (0.12f + min(e.w, 5) * 0.03f) / sqrt(max(1, min(A.deg, B.deg)).toFloat()) * (if (A.cat == B.cat) 1f else 0.3f)
            dx *= k; dy *= k
            val bias = A.deg.toFloat() / (A.deg + B.deg).coerceAtLeast(1)
            B.vx -= dx * (1 - bias); B.vy -= dy * (1 - bias); A.vx += dx * bias; A.vy += dy * bias
        }
        val kc = 0.16f * a; val kg = 0.004f * a
        for (nd in ns) {
            val an = anchors[nd.cat.coerceIn(0, anchors.size - 1)]
            nd.vx += (an.x - nd.x) * kc - nd.x * kg; nd.vy += (an.y - nd.y) * kc - nd.y * kg
            nd.vx *= 0.58f; nd.vy *= 0.58f; nd.x += nd.vx; nd.y += nd.vy
        }
        alpha += (0f - alpha) * 0.028f
    }

    /** Run the layout until it stops moving (or [maxTicks]); afterwards nothing is simulated per frame. */
    fun settle(maxTicks: Int = SETTLE_TICKS): Int {
        var k = 0
        while (k < maxTicks && alpha > 0.004f) { tick(); k++ }
        alpha = 0f
        return k
    }

    /** World bounding box of the nodes: x0, y0, x1, y1. */
    fun bounds(): FloatArray {
        if (nodes.isEmpty()) return floatArrayOf(-100f, -100f, 100f, 100f)
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        nodes.forEach { x0 = min(x0, it.x); y0 = min(y0, it.y); x1 = max(x1, it.x); y1 = max(y1, it.y) }
        return floatArrayOf(x0, y0, x1, y1)
    }

    /** Nearest node to a world point within [r] world units, or -1. */
    fun hit(wx: Float, wy: Float, r: Float): Int {
        var best = -1; var bd = r * r
        nodes.forEach { val dx = it.x - wx; val dy = it.y - wy; val d = dx * dx + dy * dy; if (d <= bd) { bd = d; best = it.i } }
        return best
    }

    /** Count of nodes per kind, in [cats] order. */
    fun catCounts(): List<Int> = cats.indices.map { k -> nodes.count { it.cat == k } }

    companion object {
        const val MAX_NODES = 400
        const val MAX_DEG = 8
        const val SETTLE_TICKS = 260

        private fun norm(s: String) = s.trim().lowercase()

        /**
         * Links between memories, strongest first, at most [MAX_DEG] per node. Weights:
         * same subject 3 · named in the other's `related` 2 · subject mentioned in the other's text 1 · same source turn 1.
         */
        fun links(mems: List<Mind.Mem>): List<Edge> {
            val n = mems.size
            val subj = mems.map { norm(it.subject) }
            val rel = mems.map { m -> m.related.split(',', ';').map { norm(it) }.filter { it.isNotEmpty() }.toSet() }
            val txt = mems.map { Mind.words(it.text) }
            val subjWords = subj.map { s -> Mind.words(s) }
            val all = ArrayList<Edge>()
            for (a in 0 until n) for (b in a + 1 until n) {
                var w = 0; val why = ArrayList<String>(2)
                if (subj[a].isNotEmpty() && subj[a] == subj[b]) { w += 3; why += "same subject" }
                if ((subj[b].isNotEmpty() && subj[b] in rel[a]) || (subj[a].isNotEmpty() && subj[a] in rel[b])) { w += 2; why += "related" }
                else if (subj[a] != subj[b]) {
                    val ma = subj[b].length >= 3 && subjWords[b].isNotEmpty() && txt[a].containsAll(subjWords[b])
                    val mb = subj[a].length >= 3 && subjWords[a].isNotEmpty() && txt[b].containsAll(subjWords[a])
                    if (ma || mb) { w += 1; why += "mentioned" }
                }
                if (mems[a].sourceTurn > 0 && mems[a].sourceTurn == mems[b].sourceTurn) { w += 1; why += "same chat" }
                if (w > 0) all += Edge(a, b, w, why.joinToString(" · "))
            }
            // Strongest first; a node keeps at most MAX_DEG links so busy subjects don't turn into a hairball.
            val deg = IntArray(n)
            return all.sortedWith(compareByDescending<Edge> { it.w }.thenBy { it.a }.thenBy { it.b }).filter { e ->
                if (deg[e.a] >= MAX_DEG || deg[e.b] >= MAX_DEG) false else { deg[e.a]++; deg[e.b]++; true }
            }
        }
    }
}
