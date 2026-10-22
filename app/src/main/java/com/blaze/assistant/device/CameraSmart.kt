package com.blaze.assistant.device

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v0.34.0 Blaze Camera, the pure maths (CameraSmartTest): night stacking (align + average + lift), HDR merge
 * (exposure fusion of a bracket), the 4-corner document crop, pro exposure / depth-of-field maths, and reading
 * prices, sums and product barcodes out of what the camera saw.
 */
object CameraSmart {
    // ---------------------------------------------------------------- night stacking + HDR

    fun luma(p: Int): Int = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    /** Grey of an ARGB frame shrunk by [step] (every step-th pixel), for alignment. */
    fun smallGrey(argb: IntArray, w: Int, h: Int, step: Int): Triple<IntArray, Int, Int> {
        val sw = w / step; val sh = h / step
        return Triple(IntArray(sw * sh) { i -> luma(argb[(i / sw) * step * w + (i % sw) * step]) }, sw, sh)
    }

    /**
     * The shift (dx, dy) that best lines [img] up with [ref] (both grey, same size), searched within ±[range] pixels by
     * mean absolute difference over the middle of the frame. Hand shake between burst frames is mostly a shift.
     */
    fun align(ref: IntArray, img: IntArray, w: Int, h: Int, range: Int): Pair<Int, Int> {
        var best = Long.MAX_VALUE; var bx = 0; var by = 0
        val x0 = range; val x1 = w - range; val y0 = range; val y1 = h - range
        if (x1 <= x0 || y1 <= y0) return 0 to 0
        for (dy in -range..range) for (dx in -range..range) {
            var sum = 0L
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) { sum += abs(ref[y * w + x] - img[(y + dy) * w + (x + dx)]); x += 2 }
                y += 2
            }
            if (sum < best || (sum == best && abs(dx) + abs(dy) < abs(bx) + abs(by))) { best = sum; bx = dx; by = dy }
        }
        return bx to by
    }

    /** Averages frames (each moved by its shift) into one: noise drops by about √n. Pixels off the edge use the first frame. */
    fun stack(frames: List<IntArray>, shifts: List<Pair<Int, Int>>, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var n = 0
            frames.forEachIndexed { k, f ->
                val (dx, dy) = shifts[k]
                val sx = x + dx; val sy = y + dy
                if (sx in 0 until w && sy in 0 until h) { val p = f[sy * w + sx]; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; n++ }
            }
            if (n == 0) { out[y * w + x] = frames[0][y * w + x]; continue }
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return out
    }

    /** Night lift: brightens the shadows (gamma [gamma] < 1) without blowing out what's already bright. */
    fun lift(argb: IntArray, gamma: Double = 0.75): IntArray {
        val lut = IntArray(256) { (255.0 * (it / 255.0).pow(gamma)).roundToInt().coerceIn(0, 255) }
        return IntArray(argb.size) { i -> val p = argb[i]; (0xFF shl 24) or (lut[p shr 16 and 0xFF] shl 16) or (lut[p shr 8 and 0xFF] shl 8) or lut[p and 0xFF] }
    }

    /** How much to lift a stacked night shot: darker frames get more (mean luma → gamma 0.55..1). */
    fun nightGamma(argb: IntArray): Double {
        if (argb.isEmpty()) return 1.0
        var s = 0L; var n = 0; var i = 0
        while (i < argb.size) { s += luma(argb[i]); n++; i += 16 }
        val mean = s.toDouble() / n / 255.0
        return (0.55 + mean * 0.9).coerceIn(0.55, 1.0)
    }

    /**
     * HDR merge (exposure fusion, after Mertens): every pixel is a weighted mix of the bracket, each frame weighted by
     * how well exposed that pixel is in it (near mid-grey), so shadows come from the bright frame and skies from the dark one.
     */
    fun fuse(frames: List<IntArray>): IntArray {
        if (frames.isEmpty()) return IntArray(0)
        val n = frames[0].size
        val out = IntArray(n)
        for (i in 0 until n) {
            var wr = 0.0; var wg = 0.0; var wb = 0.0; var wsum = 0.0
            for (f in frames) {
                val p = f[i]
                val l = luma(p) / 255.0
                val wgt = exp(-((l - 0.5) * (l - 0.5)) / (2 * 0.2 * 0.2)) + 1e-4
                wr += (p shr 16 and 0xFF) * wgt; wg += (p shr 8 and 0xFF) * wgt; wb += (p and 0xFF) * wgt; wsum += wgt
            }
            out[i] = (0xFF shl 24) or ((wr / wsum).roundToInt().coerceIn(0, 255) shl 16) or ((wg / wsum).roundToInt().coerceIn(0, 255) shl 8) or (wb / wsum).roundToInt().coerceIn(0, 255)
        }
        return out
    }

    /** Exposure-compensation indexes for a bracket of about −2 / 0 / +2 EV, inside the camera's range. */
    fun bracket(lower: Int, upper: Int, step: Float): List<Int> {
        if (step <= 0f || (lower == 0 && upper == 0)) return listOf(0)
        val two = (2f / step).roundToInt().coerceAtLeast(1)
        return listOf((-two).coerceAtLeast(lower), 0, two.coerceAtMost(upper)).distinct()
    }

    // ---------------------------------------------------------------- document crop

    /** Corners (x, y) as tl, tr, br, bl, put in that order from any 4 points. */
    fun order(pts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        require(pts.size == 4)
        val tl = pts.minBy { it.first + it.second }; val br = pts.maxBy { it.first + it.second }
        val tr = pts.minBy { it.second - it.first }; val bl = pts.maxBy { it.second - it.first }
        return listOf(tl, tr, br, bl)
    }

    /** Starting corners: the page assumed to fill the frame less an 8 % margin. */
    fun defaultCorners(w: Int, h: Int): List<Pair<Float, Float>> {
        val mx = w * 0.08f; val my = h * 0.08f
        return listOf(mx to my, w - mx to my, w - mx to h - my, mx to h - my)
    }

    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = sqrt((a.first - b.first) * (a.first - b.first) + (a.second - b.second) * (a.second - b.second))

    /** Output page size for corners tl, tr, br, bl: the longer of each pair of opposite edges. */
    fun pageSize(c: List<Pair<Float, Float>>): Pair<Int, Int> {
        val w = maxOf(dist(c[0], c[1]), dist(c[3], c[2])).roundToInt().coerceAtLeast(1)
        val h = maxOf(dist(c[0], c[3]), dist(c[1], c[2])).roundToInt().coerceAtLeast(1)
        return w to h
    }

    /** Index of the corner nearest to (x, y). */
    fun nearest(c: List<Pair<Float, Float>>, x: Float, y: Float): Int = c.indices.minBy { dist(c[it], x to y) }

    // ---------------------------------------------------------------- pro maths

    /** EV at ISO 100 for an f-number, shutter time (s) and ISO. */
    fun ev100(aperture: Double, shutterSec: Double, iso: Double): Double = ln(aperture * aperture / shutterSec) / ln(2.0) - ln(iso / 100.0) / ln(2.0)

    /** The shutter time giving the same exposure at a new aperture / ISO. */
    fun equivalentShutter(shutterSec: Double, aperture: Double, iso: Double, newAperture: Double, newIso: Double): Double =
        shutterSec * (newAperture * newAperture) / (aperture * aperture) * (iso / newIso)

    /** Slowest hand-held shutter for a focal length (1 / (focal × crop)), halved for every stop of stabilisation. */
    fun handheld(focalMm: Double, crop: Double, stabStops: Int = 0): Double = (1.0 / (focalMm * crop)) * 2.0.pow(stabStops)

    /** Hyperfocal distance (m) for focal (mm), f-number and circle of confusion (mm; ~0.005 for phone sensors). */
    fun hyperfocal(focalMm: Double, aperture: Double, cocMm: Double): Double = (focalMm * focalMm / (aperture * cocMm) + focalMm) / 1000.0

    /** Near and far limits of sharpness (m) focused at [distM]; far = +∞ past the hyperfocal distance. */
    fun depthOfField(focalMm: Double, aperture: Double, cocMm: Double, distM: Double): Pair<Double, Double> {
        val hMm = hyperfocal(focalMm, aperture, cocMm) * 1000; val s = distM * 1000
        val near = hMm * s / (hMm + (s - focalMm)); val far = if (s >= hMm) Double.POSITIVE_INFINITY else hMm * s / (hMm - (s - focalMm))
        return near / 1000 to far / 1000
    }

    /** Shutter time with an ND filter of [stops]. */
    fun ndShutter(shutterSec: Double, stops: Int): Double = shutterSec * 2.0.pow(stops)

    /** "1/250 s", "2 s", "1/8 s". */
    fun shutterText(sec: Double): String = when {
        sec >= 1 -> if (sec >= 10) "${sec.roundToInt()} s" else "%.1f s".format(sec).replace(".0 s", " s")
        else -> "1/${(1 / sec).roundToInt()} s"
    }

    // ---------------------------------------------------------------- reading prices, sums, products

    data class Price(val currency: String, val amount: Double)

    private val priceRe = Regex("""(?i)(ZMW|K|KSh|USD|US\$|\$|€|EUR|£|GBP|R|ZAR|₦|NGN|₹|INR)\s?(\d{1,3}(?:[ ,]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)(?![\d])|(\d{1,3}(?:[ ,]\d{3})*(?:[.,]\d{2}))\s?(ZMW|USD|EUR|GBP|ZAR|NGN|KES|INR)\b""")

    /** Prices in OCR text ("K 25.00", "ZMW1,250", "$3.99", "12,50 EUR"), in reading order. */
    fun prices(text: String): List<Price> = priceRe.findAll(text).mapNotNull { m ->
        val cur = (m.groupValues[1].ifBlank { m.groupValues[4] }).uppercase().let { if (it == "K") "ZMW" else if (it == "US$") "USD" else it }
        val raw = m.groupValues[2].ifBlank { m.groupValues[3] }
        val num = normalizeNumber(raw) ?: return@mapNotNull null
        Price(cur, num)
    }.toList()

    /** "1,250.50" / "1 250,50" / "12,50" → 1250.5 / 1250.5 / 12.5. */
    fun normalizeNumber(s: String): Double? {
        val t = s.replace(" ", "")
        val lastSep = maxOf(t.lastIndexOf('.'), t.lastIndexOf(','))
        val cleaned = if (lastSep >= 0 && t.length - lastSep - 1 in 1..2) t.substring(0, lastSep).replace(",", "").replace(".", "") + "." + t.substring(lastSep + 1)
            else t.replace(",", "").replace(".", "")
        return cleaned.toDoubleOrNull()
    }

    /** Total per currency, e.g. "ZMW 65.00". */
    fun totals(p: List<Price>): Map<String, Double> = p.groupBy { it.currency }.mapValues { (_, l) -> l.sumOf { it.amount } }

    /** Lines that are sums to work out ("12 × 4 + 3 =", "45/9"), turned into plain expressions. */
    fun sums(text: String): List<String> = text.lines().map { it.trim().trimEnd('=', '?', ' ') }
        .filter { l -> l.length in 3..80 && Regex("""^[\d\s.+\-*/×÷x:()^%,]+$""").matches(l) && Regex("""\d\s*[+\-*/×÷x:^]\s*\(?\d""").containsMatchIn(l) }
        .map { it.replace('×', '*').replace('÷', '/').replace(Regex("""(?<=[\d)])\s*[x:]\s*(?=[\d(])""")) { m -> if (m.value.contains(':')) "/" else "*" }.replace(",", "") }
        .distinct()

    /** EAN-13 / EAN-8 / UPC-A with a valid check digit: a product barcode. */
    fun isProductCode(raw: String): Boolean {
        val d = raw.trim()
        if (!d.all { it.isDigit() } || d.length !in setOf(8, 12, 13)) return false
        val digits = d.map { it - '0' }
        val body = digits.dropLast(1).reversed()
        val sum = body.mapIndexed { i, v -> if (i % 2 == 0) v * 3 else v }.sum()
        return (10 - sum % 10) % 10 == digits.last()
    }

    /** Open Food Facts product reply → "Brand Name · 500 g" (null when not found). */
    fun product(json: String): String? = try {
        val o = JSONObject(json)
        if (o.optInt("status") != 1) null else o.optJSONObject("product")?.let { p ->
            listOf(listOf(p.optString("brands").substringBefore(','), p.optString("product_name")).filter { it.isNotBlank() }.joinToString(" "), p.optString("quantity"))
                .filter { it.isNotBlank() }.joinToString(" \u00b7 ").ifBlank { null }
        }
    } catch (_: Exception) { null }
}
