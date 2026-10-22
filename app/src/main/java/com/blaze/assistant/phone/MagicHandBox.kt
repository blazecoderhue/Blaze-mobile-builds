package com.blaze.assistant.phone

import com.blaze.assistant.phone.MagicHandLogic.Box

/**
 * Magic Hand grab box (v0.26.0), pure part (MagicHandBoxTest): after a circle-to-grab, the blue rectangle can be
 * moved (drag from inside) and resized (drag a corner or an edge) to fix a wrong selection. The text is read again
 * for the new rectangle when the finger lifts. All in screen px.
 */
object MagicHandBox {
    /** What a touch on the box grabs. MOVE = inside; the rest resize the named sides. */
    enum class Handle(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false) {
        NONE, MOVE,
        LEFT(left = true), TOP(top = true), RIGHT(right = true), BOTTOM(bottom = true),
        TOP_LEFT(left = true, top = true), TOP_RIGHT(right = true, top = true),
        BOTTOM_LEFT(left = true, bottom = true), BOTTOM_RIGHT(right = true, bottom = true);
        val resizes: Boolean get() = left || top || right || bottom
    }

    /** Where the box may go: the screen minus the status bar and the navigation bar. */
    data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w: Int get() = r - l
        val h: Int get() = b - t
    }

    fun bounds(screenW: Int, screenH: Int, topInset: Int = 0, bottomInset: Int = 0): Bounds =
        Bounds(0, topInset.coerceAtLeast(0), screenW.coerceAtLeast(1), (screenH - bottomInset).coerceAtLeast(topInset + 1))

    /**
     * Which handle a touch at ([x], [y]) is on. Corners win over edges, edges over the inside; [reach] is how far
     * from a corner or an edge (inside or outside the box) still counts. On a box too small to tell the inside from
     * its edges, the inside (MOVE) wins in the middle third so a small box can still be dragged.
     */
    fun hit(box: Box, x: Int, y: Int, reach: Int): Handle {
        if (x < box.l - reach || x > box.r + reach || y < box.t - reach || y > box.b + reach) return Handle.NONE
        val nearL = kotlin.math.abs(x - box.l) <= reach
        val nearR = kotlin.math.abs(x - box.r) <= reach
        val nearT = kotlin.math.abs(y - box.t) <= reach
        val nearB = kotlin.math.abs(y - box.b) <= reach
        val inside = x in box.l..box.r && y in box.t..box.b
        // Small box: the middle third is always "move".
        if (inside && (box.w < reach * 3 || box.h < reach * 3)) {
            val mx = x - box.l in box.w / 3..box.w * 2 / 3
            val my = y - box.t in box.h / 3..box.h * 2 / 3
            if (mx && my) return Handle.MOVE
        }
        // Prefer the closer side when the box is so thin both are in reach.
        val l = nearL && (!nearR || x - box.l <= box.r - x)
        val r = nearR && !l
        val t = nearT && (!nearB || y - box.t <= box.b - y)
        val b = nearB && !t
        return when {
            l && t -> Handle.TOP_LEFT
            r && t -> Handle.TOP_RIGHT
            l && b -> Handle.BOTTOM_LEFT
            r && b -> Handle.BOTTOM_RIGHT
            l && y in box.t..box.b -> Handle.LEFT
            r && y in box.t..box.b -> Handle.RIGHT
            t && x in box.l..box.r -> Handle.TOP
            b && x in box.l..box.r -> Handle.BOTTOM
            inside -> Handle.MOVE
            else -> Handle.NONE
        }
    }

    /**
     * The box after dragging [handle] by ([dx], [dy]) from [start]: MOVE keeps the size and stays inside [bounds];
     * a resize moves only the named sides, never past [bounds] and never smaller than [minSize] on either side
     * (the opposite side stays put).
     */
    fun drag(start: Box, handle: Handle, dx: Int, dy: Int, bounds: Bounds, minSize: Int): Box {
        val min = minSize.coerceAtMost(minOf(bounds.w, bounds.h)).coerceAtLeast(1)
        val s = fit(start, bounds, min)
        return when {
            handle == Handle.NONE -> s
            handle == Handle.MOVE -> {
                val l = (s.l + dx).coerceIn(bounds.l, bounds.r - s.w)
                val t = (s.t + dy).coerceIn(bounds.t, bounds.b - s.h)
                Box(l, t, l + s.w, t + s.h)
            }
            else -> {
                val l = if (handle.left) (s.l + dx).coerceIn(bounds.l, s.r - min) else s.l
                val r = if (handle.right) (s.r + dx).coerceIn(s.l + min, bounds.r) else s.r
                val t = if (handle.top) (s.t + dy).coerceIn(bounds.t, s.b - min) else s.t
                val b = if (handle.bottom) (s.b + dy).coerceIn(s.t + min, bounds.b) else s.b
                Box(l, t, r, b)
            }
        }
    }

    /** [b] made valid: at least [minSize] each way, inside [bounds] (shifted in, not cut, when it can be). */
    fun fit(b: Box, bounds: Bounds, minSize: Int): Box {
        val min = minSize.coerceAtMost(minOf(bounds.w, bounds.h)).coerceAtLeast(1)
        val w = (b.w).coerceIn(min, bounds.w)
        val h = (b.h).coerceIn(min, bounds.h)
        val l = b.l.coerceIn(bounds.l, bounds.r - w)
        val t = b.t.coerceIn(bounds.t, bounds.b - h)
        return Box(l, t, l + w, t + h)
    }

    /** True when the box moved or changed size enough (over [slop] px on any side) to read the text again. */
    fun changed(a: Box, b: Box, slop: Int): Boolean =
        kotlin.math.abs(a.l - b.l) > slop || kotlin.math.abs(a.t - b.t) > slop || kotlin.math.abs(a.r - b.r) > slop || kotlin.math.abs(a.b - b.b) > slop

    /** The touch window around the box: the box plus [pad] on every side for the handles (it may poke off-screen). */
    fun window(b: Box, pad: Int): Box = Box(b.l - pad, b.t - pad, b.r + pad, b.b + pad)

    // ---------- v0.27.0: drawing, snapping, undo ----------

    /**
     * The live preview while a circle or a drag is being drawn: the bounding box of the points so far, once it is at
     * least [minPx] on both sides (null before that, so a tap or a short wobble shows nothing).
     */
    fun liveBox(points: List<MagicHandLogic.P>, minPx: Int): Box? {
        if (points.size < 2) return null
        val l = points.minOf { it.x }.toInt(); val t = points.minOf { it.y }.toInt()
        val r = kotlin.math.ceil(points.maxOf { it.x }.toDouble()).toInt(); val b = kotlin.math.ceil(points.maxOf { it.y }.toDouble()).toInt()
        return if (r - l < minPx || b - t < minPx) null else Box(l, t, r, b)
    }

    /**
     * The drawn box snapped to the text under it on release: the union of the text blocks ([nodes], screen px) that sit
     * mostly inside it (at least half of a block's area, or its centre), grown by [pad]. Blocks far bigger than the
     * drawing (a whole page container) are ignored, and no side moves out more than [maxGrow] of the drawing's size,
     * so a long paragraph never swallows the screen. With no text under it, the drawing stays as drawn.
     */
    fun snap(drawn: Box, nodes: List<Box>, pad: Int, bounds: Bounds, minSize: Int, maxGrow: Float = 0.5f): Box {
        if (drawn.isEmpty()) return drawn
        val area = drawn.w.toLong() * drawn.h
        val picked = nodes.filter { n ->
            if (n.isEmpty() || n.w.toLong() * n.h > area * 6) return@filter false
            val iw = minOf(n.r, drawn.r) - maxOf(n.l, drawn.l); val ih = minOf(n.b, drawn.b) - maxOf(n.t, drawn.t)
            val inter = if (iw > 0 && ih > 0) iw.toLong() * ih else 0L
            inter * 2 >= n.w.toLong() * n.h || drawn.contains(n.cx, n.cy)
        }
        if (picked.isEmpty()) return drawn
        val gx = (drawn.w * maxGrow).toInt(); val gy = (drawn.h * maxGrow).toInt()
        val l = (picked.minOf { it.l } - pad).coerceAtLeast(drawn.l - gx)
        val t = (picked.minOf { it.t } - pad).coerceAtLeast(drawn.t - gy)
        val r = (picked.maxOf { it.r } + pad).coerceAtMost(drawn.r + gx)
        val b = (picked.maxOf { it.b } + pad).coerceAtMost(drawn.b + gy)
        if (r <= l || b <= t) return drawn
        return fit(Box(l, t, r, b), bounds, minSize)
    }

    /** Earlier boxes for Undo (newest last, at most [cap]); the box on screen is never in it. */
    class History(private val cap: Int = 12) {
        private val items = ArrayDeque<Box>()
        val canUndo: Boolean get() = items.isNotEmpty()
        /** Remember [b] before it changes (a repeat of the newest is skipped). */
        fun push(b: Box) { if (items.lastOrNull() != b) { items.addLast(b); while (items.size > cap) items.removeFirst() } }
        /** The box to go back to, or null. */
        fun undo(): Box? = items.removeLastOrNull()
        fun clear() = items.clear()
    }

    /** First-time help: the drawing hint and the corner coach mark show for the first [TIPS_TIMES] grabs only. */
    fun showTip(timesShown: Int): Boolean = timesShown < TIPS_TIMES
    const val TIPS_TIMES = 3

    /** How far (px) the box must change on snap to count as a snap (and get its own haptic tick). */
    fun snapped(drawn: Box, snapped: Box, slop: Int): Boolean = changed(drawn, snapped, slop)

    /** How long after the finger lifts the text is read again (a quick second drag cancels the first read). */
    const val REREAD_DELAY_MS = 350L
    /** The smallest box, in dp. */
    const val MIN_DP = 40
    /** How far from a corner or edge still grabs it, in dp (v0.27.0: 24, so each handle is a 48 dp touch target). */
    const val REACH_DP = 24
    /** The touch margin around the box, in dp (at least [REACH_DP], so the outer half of every handle is touchable). */
    const val PAD_DP = 28
    /** Text-block padding when the box snaps to text, in dp. */
    const val SNAP_PAD_DP = 6
}
