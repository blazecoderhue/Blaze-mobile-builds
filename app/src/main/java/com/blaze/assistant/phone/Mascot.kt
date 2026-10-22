package com.blaze.assistant.phone

/**
 * Blaze's mascot (Luke's art, v0.24.5): a flame / teardrop with its pointed tip at the top right, a gradient from warm
 * orange at the top to bright blue at the bottom with a cyan glow in the middle, two dark rounded-rectangle eyes and
 * a soft orange / blue glow around it.
 *
 * Pure geometry in "units": the round body is a circle of radius 1 centred on (0, 0), y down; the flame tip reaches
 * up to y ≈ -1.56. The on-screen pointer (phone/Pointer.kt, android.graphics), its Compose preview (ui/PointerPreview.kt)
 * and the launcher / themed / notification icons (res/drawable/ic_launcher_*.xml, ic_stat_blaze.xml, generated from the
 * same numbers) all draw this one shape. The tip ([TIP]) is the pointer's click point.
 */
object Mascot {
    class Shape(val start: FloatArray, val cubics: List<FloatArray>)

    /** The outline: the left side sweeps down from the tip, a round bottom, the right side curls back up into the tip. */
    val body = Shape(floatArrayOf(0.06f, -1.53f), listOf(
        floatArrayOf(-0.35f, -1.35f, -1.0f, -0.62f, -1.0f, 0.0f),
        floatArrayOf(-1.0f, 0.552f, -0.552f, 1.0f, 0.0f, 1.0f),
        floatArrayOf(0.552f, 1.0f, 1.0f, 0.552f, 1.0f, 0.0f),
        floatArrayOf(1.0f, -0.55f, 0.66f, -0.80f, 0.42f, -1.02f),
        floatArrayOf(0.30f, -1.12f, 0.25f, -1.30f, 0.24f, -1.45f),
        floatArrayOf(0.24f, -1.56f, 0.12f, -1.58f, 0.06f, -1.53f)))

    /** The click point: the very top of the flame tip. */
    val TIP = floatArrayOf(0.16f, -1.555f)
    /** Height of the whole mascot in units (tip to bottom). */
    const val HEIGHT = 2.555f

    /** Eye centres (units) and size: dark rounded rectangles, a little above the middle. */
    val EYES = listOf(floatArrayOf(-0.31f, -0.16f), floatArrayOf(0.30f, -0.16f))
    const val EYE_W = 0.18f
    const val EYE_H = 0.30f
    const val EYE_R = 0.09f

    /** Body gradient, top (y = [GRAD_TOP]) to bottom (y = [GRAD_BOTTOM]): orange → peach → blue. */
    const val GRAD_TOP = -1.58f
    const val GRAD_BOTTOM = 1.0f
    val STOPS = floatArrayOf(0f, 0.18f, 0.26f, 0.34f, 0.42f, 0.70f, 1f)
    val COLORS = intArrayOf(0xFFFFC15A.toInt(), 0xFFFFA35C.toInt(), 0xFFD99E84.toInt(), 0xFF9AA6C6.toInt(),
        0xFF6FAEF2.toInt(), 0xFF5AA8FC.toInt(), 0xFF4C93FE.toInt())
    /** Cyan highlight in the middle of the body (radial, fades out). */
    const val HIGHLIGHT = 0xB366D8FF.toInt()
    val HIGHLIGHT_AT = floatArrayOf(0f, 0.08f)
    const val HIGHLIGHT_R = 0.78f
    /** Glow behind: orange around the tip, blue around the body. */
    const val GLOW_ORANGE = 0x59FF9A3C
    val GLOW_ORANGE_AT = floatArrayOf(0.05f, -0.95f)
    const val GLOW_ORANGE_R = 1.35f
    const val GLOW_BLUE = 0x663D7BFF
    val GLOW_BLUE_AT = floatArrayOf(0f, 0.25f)
    const val GLOW_BLUE_R = 1.6f
    const val EYE = 0xFF23252D.toInt()
    /** The launcher icon background. */
    const val ICON_BG = 0xFF212226.toInt()

    /**
     * Walks [s] at [k] px per unit with the unit point [anchor] placed at (ax, ay) and the shape scaled by
     * ([sx], [sy]) around it (so a squish keeps the click point still): [move] once, then [cubic] per segment.
     */
    fun walk(s: Shape, ax: Float, ay: Float, k: Float, sx: Float = 1f, sy: Float = 1f, anchor: FloatArray = TIP,
             move: (Float, Float) -> Unit, cubic: (Float, Float, Float, Float, Float, Float) -> Unit) {
        val X = { v: Float -> ax + (v - anchor[0]) * k * sx }
        val Y = { v: Float -> ay + (v - anchor[1]) * k * sy }
        move(X(s.start[0]), Y(s.start[1]))
        for (c in s.cubics) cubic(X(c[0]), Y(c[1]), X(c[2]), Y(c[3]), X(c[4]), Y(c[5]))
    }

    /** A unit point mapped like [walk] does. */
    fun at(u: FloatArray, ax: Float, ay: Float, k: Float, sx: Float = 1f, sy: Float = 1f, anchor: FloatArray = TIP) =
        floatArrayOf(ax + (u[0] - anchor[0]) * k * sx, ay + (u[1] - anchor[1]) * k * sy)

    /** Bounds of a shape in units (minX, minY, maxX, maxY), from its end and control points (tests). */
    fun bounds(s: Shape): FloatArray {
        val xs = listOf(s.start[0]) + s.cubics.flatMap { listOf(it[0], it[2], it[4]) }
        val ys = listOf(s.start[1]) + s.cubics.flatMap { listOf(it[1], it[3], it[5]) }
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }
}
