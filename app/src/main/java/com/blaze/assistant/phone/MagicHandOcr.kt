package com.blaze.assistant.phone

import com.blaze.assistant.phone.MagicHandLogic.Box

/**
 * v0.28.1: the pure half of the grab read (unit tested in MagicHandOcrTest).
 *
 * Why: the grab used to OCR exactly the selected rectangle, so a line cut by the box edge came back as junk
 * ("ervAnood lot Hark handlA"). Now the crop is taken with a margin ([MARGIN_DP]) so cut lines are read whole, and
 * [keep] drops every line whose bounding box is mostly outside the selected region. The bitmap is also downscaled
 * before OCR ([scaleFor]) so a big crop never stalls the read.
 */
object MagicHandOcr {
    /** One OCR line and its bounding box, in the crop's pixel space. */
    data class Line(val box: Box, val text: String)

    /** How far around the selected box the OCR crop reaches, so partial lines are seen whole (then filtered). */
    const val MARGIN_DP = 16
    /** A line is kept when at least this share of its box is inside the selected region. */
    const val MIN_INSIDE = 0.6f
    /** Longest side handed to the recogniser; ML Kit reads Latin text well at this size and much faster. */
    const val MAX_SIDE = 1280

    /** Fraction (0..1) of [line]'s area inside [region]. An empty line box counts as outside. */
    fun insideFraction(line: Box, region: Box): Float {
        if (line.isEmpty()) return 0f
        val l = maxOf(line.l, region.l); val t = maxOf(line.t, region.t)
        val r = minOf(line.r, region.r); val b = minOf(line.b, region.b)
        if (r <= l || b <= t) return 0f
        return ((r - l).toLong() * (b - t)).toFloat() / (line.w.toLong() * line.h).toFloat()
    }

    /** Lines mostly inside [region], top to bottom then left to right, joined with newlines. */
    fun keep(lines: List<Line>, region: Box, minInside: Float = MIN_INSIDE): String =
        lines.filter { it.text.isNotBlank() && insideFraction(it.box, region) >= minInside }
            .sortedWith(compareBy<Line>({ rowOf(it, lines) }, { it.box.l }))
            .joinToString("\n") { it.text.trim() }

    /** Lines whose vertical centres are within half a line height share a row (ML Kit can split one row in two). */
    private fun rowOf(l: Line, all: List<Line>): Int {
        val h = maxOf(1, l.box.h)
        return all.filter { kotlin.math.abs(it.box.cy - l.box.cy) <= h / 2 }.minOf { it.box.t }
    }

    /** The crop rectangle: [region] grown by [margin] px, clamped to the screen. */
    fun cropFor(region: Box, margin: Int, screenW: Int, screenH: Int): Box =
        Box(maxOf(0, region.l - margin), maxOf(0, region.t - margin), minOf(screenW, region.r + margin), minOf(screenH, region.b + margin))

    /** [region] in the coordinates of [crop] (both in screen pixels), then scaled by [scale]. */
    fun regionIn(crop: Box, region: Box, scale: Float = 1f): Box =
        Box(((region.l - crop.l) * scale).toInt(), ((region.t - crop.t) * scale).toInt(),
            ((region.r - crop.l) * scale).toInt(), ((region.b - crop.t) * scale).toInt())

    /** Scale (≤ 1) that brings a [w]×[h] bitmap's longest side down to [maxSide]. */
    fun scaleFor(w: Int, h: Int, maxSide: Int = MAX_SIDE): Float {
        val m = maxOf(w, h)
        return if (m <= maxSide || m <= 0) 1f else maxSide.toFloat() / m
    }
}
