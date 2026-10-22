package com.blaze.assistant.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v0.43.0: the maths of the "who am I talking to" arc (Blaze · Halo · All) that opens from the header orb. Pure, unit
 * tested in TalkArcMathTest; drawing and gestures are in TalkArc.kt.
 *
 * The three icons sit on a semicircle. [pos] is the (fractional) index of the icon at the top: icon i sits at
 * 90° + (pos − i) · STEP, so with Halo focused Blaze is on the left, Halo on top and All on the right. Dragging around
 * the arc (or sideways) turns it; letting go snaps to the nearest icon.
 */
object TalkArcMath {
    val TARGETS = listOf("blaze", "halo", "all")
    const val STEP = 45f

    fun indexOf(who: String): Int = TARGETS.indexOf(who).coerceAtLeast(0)
    fun targetAt(i: Int): String = TARGETS[i.coerceIn(0, TARGETS.size - 1)]
    fun label(who: String): String = when (who) { "halo" -> "Halo"; "all" -> "All · Halo × Blaze"; else -> "Blaze" }

    fun clamp(pos: Float): Float = pos.coerceIn(0f, (TARGETS.size - 1).toFloat())

    /** The icon a resting arc shows on top. */
    fun snap(pos: Float): Int = clamp(pos).roundToInt()

    /** Degrees (0 = right, 90 = top, 180 = left) of icon [i] when [pos] is on top. */
    fun angle(i: Int, pos: Float): Float = 90f + (pos - i) * STEP

    /** Icon [i]'s centre relative to the arc's centre, y pointing down as on screen. */
    fun offset(i: Int, pos: Float, radius: Float): Pair<Float, Float> {
        val a = angle(i, pos) * PI.toFloat() / 180f
        return cos(a) * radius to -sin(a) * radius
    }

    /** 1 for the focused icon, falling to 0 one step away. */
    fun focus(i: Int, pos: Float): Float = (1f - abs(pos - i)).coerceIn(0f, 1f)

    /** Size factor: the focused icon is larger. */
    fun scale(i: Int, pos: Float): Float = 1f + 0.45f * focus(i, pos)

    /** Angle (degrees, as in [angle]) of a touch at ([x], [y]) from the arc's centre, y down. */
    fun touchAngle(x: Float, y: Float): Float = (atan2(0f - y, x) * 180f / PI.toFloat())

    /**
     * Turn the arc as a finger moves from [fromDeg] to [toDeg] around the centre. The icons follow the finger: pulling the
     * right-hand icon up over the top (the angle rising) brings it to the top.
     */
    fun rotate(pos: Float, fromDeg: Float, toDeg: Float): Float {
        var d = toDeg - fromDeg
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return clamp(pos + d / STEP)
    }

    /** A sideways swipe of [dx] px on an arc of [radius] px, as the same turn (a swipe to the left brings the right icon up). */
    fun swipe(pos: Float, dx: Float, radius: Float): Float =
        if (radius <= 0f) clamp(pos) else clamp(pos - dx / (radius * STEP * PI.toFloat() / 180f))

    /** The icon under a tap at ([x], [y]) from the centre, within [hit] px of its centre; -1 for none. */
    fun hit(x: Float, y: Float, pos: Float, radius: Float, hit: Float): Int {
        var best = -1; var bestD = hit * hit
        for (i in TARGETS.indices) {
            val (ox, oy) = offset(i, pos, radius)
            val d = (ox - x) * (ox - x) + (oy - y) * (oy - y)
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }
}
