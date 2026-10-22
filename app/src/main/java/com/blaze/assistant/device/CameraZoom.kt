package com.blaze.assistant.device

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v0.30.x Blaze Camera Pro: the pure maths behind zoom presets, the half-circle zoom dial (protractor), digital zoom
 * past the lens's hardware maximum, tap-to-focus coordinates, exposure and the self-timer (CameraZoomTest).
 *
 * One number drives everything: the **total** zoom ratio the user sees ("3.4x"). It is split into what the camera
 * does in hardware ([Split.hw], CameraControl.setZoomRatio, at most the lens's maxZoomRatio) and what Blaze does
 * on top ([Split.digital]): the preview is scaled by that factor and the saved photo is centre-cropped by the same
 * factor and upscaled with a light unsharp mask ([Sharpen]). Pinch and the dial only ever change the total, so they
 * flow across the hardware / digital boundary with no jump.
 */
object CameraZoom {
    /** Candidate preset buttons. 0.5x only appears when the camera reports a ratio below 1 (an ultrawide lens). */
    val PRESETS = floatArrayOf(0.5f, 1f, 2f, 5f, 10f)

    /** Total zoom ceiling with digital zoom on the back camera, and on the front camera (selfies don't need 10x). */
    const val BACK_MAX = 10f
    const val FRONT_MAX = 4f

    /** The camera's min ratio must be at most this for 0.5x to count as "an ultrawide exists". */
    const val ULTRAWIDE_MIN = 0.7f

    /** The dial is a half circle: [ARC_DEGREES] from the left end (min zoom) to the right end (max zoom). */
    const val ARC_DEGREES = 180f

    private const val EPS = 0.02f

    // ---------------------------------------------------------------- range and presets

    /** Lowest total ratio: the camera's own minimum (0.5–0.6 with an ultrawide, else 1). */
    fun minTotal(camMin: Float): Float = if (camMin.isNaN() || camMin <= 0f) 1f else min(camMin, 1f)

    /** Highest total ratio: the lens's hardware max, raised to [cap] with digital zoom (never lowered). */
    fun maxTotal(hwMax: Float, front: Boolean = false): Float {
        val hw = if (hwMax.isNaN() || hwMax < 1f) 1f else hwMax
        return max(hw, if (front) FRONT_MAX else BACK_MAX)
    }

    /** Preset buttons that make sense for this camera: inside [min, max], 0.5x only with an ultrawide, 1x always. */
    fun presets(camMin: Float, totalMax: Float): List<Float> {
        val lo = minTotal(camMin)
        return PRESETS.filter { p ->
            when {
                p < 1f -> lo <= ULTRAWIDE_MIN
                p == 1f -> true
                else -> p <= totalMax + EPS
            }
        }.map { if (it < 1f) max(it, lo) else it }
    }

    /**
     * The highlighted preset for a live ratio: the largest preset at or below it (between 2x and 5x the "2" button is
     * lit and shows the live value). Below the first preset, the first one.
     */
    fun activePreset(ratio: Float, presets: List<Float>): Float? {
        if (presets.isEmpty()) return null
        return presets.lastOrNull { it <= ratio * (1f + EPS) } ?: presets.first()
    }

    /** The ratio sits on a preset (within 2%): the button shows "2x" instead of the live "2.3x". */
    fun onPreset(ratio: Float, preset: Float): Boolean = abs(ratio - preset) <= preset * EPS

    /** "1x", "2x", "0.5x", "3.4x", "10x": one decimal, dropped when it's .0. */
    fun label(ratio: Float): String {
        val r = (ratio * 10f).roundToInt() / 10f
        return if (r == r.toInt().toFloat()) "${r.toInt()}x" else "${r}x"
    }

    /** Short preset label for the pill row: "0.5", "1x", "2", "5", "10" (iPhone-style, the "x" only on 1). */
    fun presetLabel(p: Float): String = if (p == 1f) "1x" else label(p).removeSuffix("x")

    fun clamp(ratio: Float, lo: Float, hi: Float): Float = if (ratio.isNaN()) lo else ratio.coerceIn(lo, max(lo, hi))

    // ---------------------------------------------------------------- the dial: angle <-> ratio on a log scale

    /**
     * Where a ratio sits on the arc, in degrees: 180° (left end) = [lo], 0° (right end) = [hi]. Log scale, so
     * 1x→2x takes as much of the arc as 5x→10x and the low end (where people zoom most) isn't crushed.
     */
    fun angleFor(ratio: Float, lo: Float, hi: Float): Float {
        if (hi <= lo) return ARC_DEGREES
        val t = ln(clamp(ratio, lo, hi) / lo) / ln(hi / lo)
        return ARC_DEGREES * (1f - t)
    }

    /** The inverse of [angleFor]: an angle on the arc (clamped to 0..180°) back to a ratio. */
    fun ratioFor(angle: Float, lo: Float, hi: Float): Float {
        if (hi <= lo) return lo
        val t = 1f - angle.coerceIn(0f, ARC_DEGREES) / ARC_DEGREES
        return clamp(lo * exp(t * ln(hi / lo)), lo, hi)
    }

    /**
     * The arc angle a finger at ([dx], [dy]) from the dial's centre points at (screen y grows downward). Above the
     * centre that's 0..180°; a finger that slides below the centre line pins to the nearer end.
     */
    fun touchAngle(dx: Float, dy: Float): Float {
        if (dx == 0f && dy == 0f) return 90f
        val deg = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())).toFloat()   // -180..180, 0 = right, 90 = up
        return when {
            deg in 0f..ARC_DEGREES -> deg
            dx >= 0f -> 0f
            else -> ARC_DEGREES
        }
    }

    /**
     * Labelled stops on the dial: the preset buttons for this range, plus the far end ("4x" on a front camera,
     * "15x" on a lens that goes past 10x) when it isn't already close to the last stop.
     */
    fun stops(lo: Float, hi: Float): List<Float> {
        val base = presets(lo, hi).toMutableList()
        val last = base.lastOrNull() ?: lo
        if (hi > last * 1.3f) base += hi
        return base.distinct().sorted()
    }

    /** Minor ticks every [stepDeg] degrees along the arc (37 ticks at 5°), returned as ratios. */
    fun minorTicks(lo: Float, hi: Float, stepDeg: Float = 5f): List<Float> {
        val n = (ARC_DEGREES / stepDeg).roundToInt()
        return (0..n).map { ratioFor(ARC_DEGREES - it * stepDeg, lo, hi) }
    }

    /**
     * The labelled stop passed between two ratios (for a light haptic tick), or null. Landing exactly on a stop counts;
     * leaving one doesn't, so resting a finger on 2x ticks once.
     */
    fun crossedStop(prev: Float, next: Float, stops: List<Float>): Float? {
        if (prev == next) return null
        val (a, b) = if (next > prev) prev to next else next to prev
        val passed = stops.filter { s -> if (next > prev) s > a && s <= b else s >= a && s < b }
        if (passed.isEmpty()) return null
        return if (next > prev) passed.last() else passed.first()
    }

    /** On release, a ratio within [tolDeg] degrees of a labelled stop settles on it (so "2x" means exactly 2x). */
    fun snap(ratio: Float, lo: Float, hi: Float, stops: List<Float>, tolDeg: Float = 3f): Float {
        val a = angleFor(ratio, lo, hi)
        val near = stops.minByOrNull { abs(angleFor(it, lo, hi) - a) } ?: return ratio
        return if (abs(angleFor(near, lo, hi) - a) <= tolDeg) near else ratio
    }

    /** Pinch: the new total after a gesture's scale factor, clamped to the range. */
    fun pinch(ratio: Float, scale: Float, lo: Float, hi: Float): Float =
        if (scale.isNaN() || scale <= 0f) ratio else clamp(ratio * scale, lo, hi)

    // ---------------------------------------------------------------- hardware / digital split

    data class Split(val hw: Float, val digital: Float) { val isDigital get() = digital > 1f + 1e-3f }

    /** The lens zooms up to [hwMax]; anything beyond is digital (total = hw × digital). */
    fun split(total: Float, hwMax: Float): Split {
        val cap = if (hwMax.isNaN() || hwMax < 1f) 1f else hwMax
        val t = if (total.isNaN() || total <= 0f) 1f else total
        return if (t <= cap) Split(t, 1f) else Split(cap, t / cap)
    }

    /** A centred crop: [left, top, width, height] of a [w]×[h] image zoomed in by [factor] (≥ 1). */
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    fun cropRect(w: Int, h: Int, factor: Float): Crop {
        if (w <= 0 || h <= 0) return Crop(0, 0, max(w, 0), max(h, 0))
        val f = if (factor.isNaN() || factor < 1f) 1f else factor
        val cw = max(1, (w / f).roundToInt()).coerceAtMost(w)
        val ch = max(1, (h / f).roundToInt()).coerceAtMost(h)
        return Crop((w - cw) / 2, (h - ch) / 2, cw, ch)
    }

    /** Power-of-two decode sample for the crop so it decodes no bigger than needed for an [outLong]-px result. */
    fun cropSample(cropW: Int, cropH: Int, outLong: Int): Int {
        var s = 1
        val long = max(cropW, cropH)
        while (long / (s * 2) >= outLong && s < 64) s *= 2
        return s
    }

    /** Output size of a digitally zoomed photo: the original frame's size, capped at [maxLong] on the long edge. */
    fun outputSize(w: Int, h: Int, maxLong: Int): Pair<Int, Int> = CameraFlow.scaledSize(w, h, maxLong)

    /** Unsharp-mask strength for a digital factor: more upscaling, a little more sharpening; never harsh. */
    fun sharpenAmount(digital: Float): Float = if (digital <= 1.01f) 0f else (0.35f + 0.15f * (digital - 1f)).coerceAtMost(0.9f)

    /**
     * A tap on the scaled preview, back to the PreviewView's own (unscaled) coordinates for its MeteringPointFactory.
     * The preview is scaled by [scale] about its centre ([cx], [cy]).
     */
    fun unscale(x: Float, y: Float, cx: Float, cy: Float, scale: Float): Pair<Float, Float> {
        val s = if (scale.isNaN() || scale <= 0f) 1f else scale
        return (cx + (x - cx) / s) to (cy + (y - cy) / s)
    }

    // ---------------------------------------------------------------- exposure

    /** Slider fraction 0..1 (bottom..top) to an exposure-compensation index in [lower, upper]. */
    fun evIndex(fraction: Float, lower: Int, upper: Int): Int {
        if (upper <= lower) return 0
        val f = if (fraction.isNaN()) 0.5f else fraction.coerceIn(0f, 1f)
        return (lower + f * (upper - lower)).roundToInt().coerceIn(lower, upper)
    }

    fun evFraction(index: Int, lower: Int, upper: Int): Float =
        if (upper <= lower) 0.5f else ((index - lower).toFloat() / (upper - lower)).coerceIn(0f, 1f)

    /** "+0.7", "-1.3", "0": the index times the step (EV), one decimal. */
    fun evLabel(index: Int, step: Float): String {
        val ev = ((index * step) * 10f).roundToInt() / 10f
        return when {
            ev == 0f -> "0"
            ev > 0f -> "+$ev"
            else -> "$ev"
        }
    }
}

/** The self-timer: off, 3 s or 10 s, and the countdown (CameraZoomTest). */
object CameraTimer {
    val OPTIONS = intArrayOf(0, 3, 10)

    fun next(seconds: Int): Int {
        val i = OPTIONS.indexOf(seconds)
        return OPTIONS[if (i < 0) 1 else (i + 1) % OPTIONS.size]
    }

    fun normalize(seconds: Int): Int = if (seconds in OPTIONS) seconds else 0

    fun label(seconds: Int): String = if (seconds <= 0) "Timer off" else "Timer ${seconds}s"

    /** Whole seconds still to show at [nowMs] for a countdown started at [startMs] (3, 2, 1, then 0 = shoot). */
    fun remaining(startMs: Long, nowMs: Long, seconds: Int): Int {
        if (seconds <= 0) return 0
        val left = seconds * 1000L - (nowMs - startMs).coerceAtLeast(0L)
        return if (left <= 0L) 0 else ceil(left / 1000.0).toInt()
    }
}

/**
 * A light unsharp mask on ARGB pixels (sharpened = original + amount × (original − blur)), with a small threshold so
 * flat areas and sensor noise aren't amplified. A separable box blur keeps it O(pixels) for low-end phones; callers
 * run it off the main thread.
 */
object Sharpen {
    fun unsharp(px: IntArray, w: Int, h: Int, amount: Float, radius: Int = 1, threshold: Int = 3): IntArray {
        if (w <= 0 || h <= 0 || px.size < w * h || amount <= 0f) return px
        // The result is written over the blur buffer (each pixel reads its blur value first), saving one full-size array.
        val out = boxBlur(px, w, h, max(1, radius))
        for (i in 0 until w * h) {
            val o = px[i]; val b = out[i]
            val a = o ushr 24
            out[i] = (a shl 24) or (ch(o shr 16 and 255, b shr 16 and 255, amount, threshold) shl 16) or
                (ch(o shr 8 and 255, b shr 8 and 255, amount, threshold) shl 8) or ch(o and 255, b and 255, amount, threshold)
        }
        return out
    }

    private fun ch(o: Int, b: Int, amount: Float, threshold: Int): Int {
        val d = o - b
        if (abs(d) < threshold) return o
        return (o + amount * d).roundToInt().coerceIn(0, 255)
    }

    /** Box blur (horizontal then vertical), edge pixels clamped. Alpha is copied from the source. */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        val win = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val p = px[row + k.coerceIn(0, w - 1)]; sr += p shr 16 and 255; sg += p shr 8 and 255; sb += p and 255 }
            for (x in 0 until w) {
                tmp[row + x] = (px[row + x] and -0x1000000) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                val outP = px[row + (x - r).coerceIn(0, w - 1)]; val inP = px[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += (inP shr 16 and 255) - (outP shr 16 and 255); sg += (inP shr 8 and 255) - (outP shr 8 and 255); sb += (inP and 255) - (outP and 255)
            }
        }
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val p = tmp[k.coerceIn(0, h - 1) * w + x]; sr += p shr 16 and 255; sg += p shr 8 and 255; sb += p and 255 }
            for (y in 0 until h) {
                out[y * w + x] = (tmp[y * w + x] and -0x1000000) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                val outP = tmp[(y - r).coerceIn(0, h - 1) * w + x]; val inP = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += (inP shr 16 and 255) - (outP shr 16 and 255); sg += (inP shr 8 and 255) - (outP shr 8 and 255); sb += (inP and 255) - (outP and 255)
            }
        }
        return out
    }
}
