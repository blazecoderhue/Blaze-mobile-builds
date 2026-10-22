package com.blaze.assistant.media

import com.blaze.assistant.media.GalleryModel.Entry
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * v0.34.0 Gallery, the pure rules (GalleryToolsTest): duplicate and blur finding on small grey thumbnails, On this day,
 * the 30-day Trash, backup planning, pinch-to-zoom grid columns, paging and the basic editor's colour maths.
 */
object GalleryTools {
    // ---------------------------------------------------------------- duplicates + blur

    /** Grey levels (0..255) of ARGB pixels. */
    fun grey(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]; ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }

    /**
     * Difference hash: the picture shrunk to 9×8 grey cells (box average), one bit per left/right brightness step.
     * Near-identical pictures (re-saves, small edits, bursts) differ in only a few of the 64 bits.
     */
    fun dHash(g: IntArray, w: Int, h: Int): Long {
        if (w < 9 || h < 8) return 0
        val cells = IntArray(72)
        for (cy in 0 until 8) for (cx in 0 until 9) {
            val x0 = cx * w / 9; val x1 = maxOf(x0 + 1, (cx + 1) * w / 9); val y0 = cy * h / 8; val y1 = maxOf(y0 + 1, (cy + 1) * h / 8)
            var sum = 0L; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) { sum += g[y * w + x]; n++ }
            cells[cy * 9 + cx] = (sum / n.coerceAtLeast(1)).toInt()
        }
        var bits = 0L
        for (cy in 0 until 8) for (cx in 0 until 8) if (cells[cy * 9 + cx] > cells[cy * 9 + cx + 1]) bits = bits or (1L shl (cy * 8 + cx))
        return bits
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Variance of the Laplacian (edge strength): low = blurry. Computed on a grey picture. */
    fun sharpness(g: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = (g[i - w] + g[i + w] + g[i - 1] + g[i + 1] - 4 * g[i]).toDouble()
            sum += l; sq += l * l; n++
        }
        val mean = sum / n
        return sq / n - mean * mean
    }

    /** Below this sharpness (on a ~128 px thumbnail) a photo counts as blurry. */
    const val BLUR_LIMIT = 45.0

    data class Scan(val id: String, val hash: Long, val sharp: Double, val size: Long, val time: Long)

    /** Groups of near-duplicates (hash distance ≤ [maxDist]), each newest-first; the first is the one to keep. */
    fun duplicates(scans: List<Scan>, maxDist: Int = 5): List<List<Scan>> {
        val parent = IntArray(scans.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var c = x; while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }; return r }
        for (i in scans.indices) for (j in i + 1 until scans.size) {
            if (scans[i].hash == 0L || scans[j].hash == 0L) continue
            if (hamming(scans[i].hash, scans[j].hash) <= maxDist) { val a = find(i); val b = find(j); if (a != b) parent[b] = a }
        }
        return scans.indices.groupBy { find(it) }.values.filter { it.size > 1 }
            .map { g -> g.map { scans[it] }.sortedWith(compareByDescending<Scan> { it.sharp }.thenByDescending { it.size }.thenByDescending { it.time }) }
            .sortedByDescending { it.size }
    }

    fun blurry(scans: List<Scan>, limit: Double = BLUR_LIMIT): List<Scan> = scans.filter { it.sharp in 0.0..limit }.sortedBy { it.sharp }

    /** Bytes freed by trashing all but the first of every group. */
    fun savings(groups: List<List<Scan>>): Long = groups.sumOf { g -> g.drop(1).sumOf { it.size } }

    // ---------------------------------------------------------------- on this day

    /** Pictures and videos taken on this month and day in earlier years, by year (newest year first). */
    fun onThisDay(list: List<Entry>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<Pair<Int, List<Entry>>> {
        val c = Calendar.getInstance(zone).apply { timeInMillis = now }
        val y = c.get(Calendar.YEAR); val m = c.get(Calendar.MONTH); val d = c.get(Calendar.DAY_OF_MONTH)
        val k = Calendar.getInstance(zone)
        return list.filter { it.kind != "audio" }.mapNotNull { e ->
            k.timeInMillis = e.time
            if (k.get(Calendar.MONTH) == m && k.get(Calendar.DAY_OF_MONTH) == d && k.get(Calendar.YEAR) < y) k.get(Calendar.YEAR) to e else null
        }.groupBy({ it.first }, { it.second }).toList().sortedByDescending { it.first }
    }

    fun yearsAgo(year: Int, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val n = Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.YEAR) - year
        return if (n == 1) "1 year ago" else "$n years ago"
    }

    // ---------------------------------------------------------------- trash

    const val TRASH_DAYS = 30
    data class Trashed(val file: String, val original: String, val title: String, val at: Long)

    fun daysLeft(t: Trashed, now: Long): Int = (TRASH_DAYS - ((now - t.at) / 86_400_000L)).toInt().coerceAtLeast(0)
    fun expired(list: List<Trashed>, now: Long): List<Trashed> = list.filter { now - it.at >= TRASH_DAYS * 86_400_000L }

    fun trashJson(l: List<Trashed>): String = JSONArray().apply { l.forEach { put(JSONObject().put("f", it.file).put("o", it.original).put("t", it.title).put("at", it.at)) } }.toString()
    fun trashParse(s: String): List<Trashed> = try {
        val a = JSONArray(s); (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Trashed(it.optString("f"), it.optString("o"), it.optString("t"), it.optLong("at")) } }
            .filter { it.file.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    // ---------------------------------------------------------------- backup planning

    data class Plan(val totalBytes: Long, val photos: Long, val videos: Long, val music: Long, val perMonth: Long,
                    val biggest: List<Entry>, val lines: List<String>)

    /**
     * What a backup needs: totals by kind, the growth per month (last 6 months), the biggest videos, and which
     * common cloud sizes it fits (Google's free 15 GB, 100 GB, 200 GB, 2 TB) with months left at this rate.
     */
    fun plan(list: List<Entry>, now: Long): Plan {
        val photos = list.filter { it.kind == "image" }.sumOf { it.size }
        val videos = list.filter { it.kind == "video" }.sumOf { it.size }
        val music = list.filter { it.kind == "audio" }.sumOf { it.size }
        val total = photos + videos + music
        val six = now - 182L * 86_400_000L
        val perMonth = list.filter { it.time >= six }.sumOf { it.size } / 6
        val lines = ArrayList<String>()
        for ((name, cap) in listOf("Free 15 GB (shared with Gmail / Drive)" to 15_000_000_000L, "100 GB" to 100_000_000_000L, "200 GB" to 200_000_000_000L, "2 TB" to 2_000_000_000_000L)) {
            if (total <= cap) {
                val months = if (perMonth > 0) (cap - total) / perMonth else -1
                lines += "$name: fits" + if (months >= 0) ", about ${if (months > 120) "10+ years" else "$months month" + (if (months == 1L) "" else "s")} of room at your rate" else ""
            } else lines += "$name: too small (needs ${GalleryModel.size(total - cap)} more)"
        }
        if (videos > total / 2 && videos > 0) lines += "Videos are most of it: backing up only photos needs ${GalleryModel.size(photos)}."
        return Plan(total, photos, videos, music, perMonth, list.filter { it.kind == "video" }.sortedByDescending { it.size }.take(5), lines)
    }

    // ---------------------------------------------------------------- grid + paging

    /** Pinch the grid: zooming in shows fewer, bigger tiles (2..6 columns). */
    fun columnsAfterPinch(cols: Int, zoom: Float): Int = when {
        zoom > 1.25f -> (cols - 1).coerceAtLeast(2)
        zoom < 0.8f -> (cols + 1).coerceAtMost(6)
        else -> cols
    }

    /** Page offsets for reading a big media index in steps (first page small so the screen fills fast). */
    fun pages(total: Int, first: Int = 600, size: Int = 2000): List<Pair<Int, Int>> {
        if (total <= 0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var at = 0; var n = first
        while (at < total) { out += at to minOf(n, total - at); at += n; n = size }
        return out
    }

    // ---------------------------------------------------------------- basic editor

    /**
     * The colour matrix (android.graphics.ColorMatrix layout, 4×5 row-major) for brightness (-1..1), contrast
     * (0..2, 1 = as is) and saturation (0..2, 1 = as is).
     */
    fun colorMatrix(brightness: Float, contrast: Float, saturation: Float): FloatArray {
        val s = saturation.coerceIn(0f, 2f)
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        val sat = floatArrayOf(
            lr * (1 - s) + s, lg * (1 - s), lb * (1 - s), 0f, 0f,
            lr * (1 - s), lg * (1 - s) + s, lb * (1 - s), 0f, 0f,
            lr * (1 - s), lg * (1 - s), lb * (1 - s) + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)
        val c = contrast.coerceIn(0f, 2f)
        val off = (1 - c) * 128f + brightness.coerceIn(-1f, 1f) * 255f
        return FloatArray(20) { i ->
            val row = i / 5; val col = i % 5
            if (row == 3) sat[i] else if (col == 4) off else sat[i] * c
        }
    }

    /** A centred crop of [w]×[h] to the aspect [ratio] (w/h; 0 = keep): left, top, width, height. */
    fun centerCrop(w: Int, h: Int, ratio: Float): IntArray {
        if (ratio <= 0f || w <= 0 || h <= 0) return intArrayOf(0, 0, w, h)
        val cur = w.toFloat() / h
        return if (cur > ratio) { val nw = (h * ratio).toInt().coerceAtLeast(1); intArrayOf((w - nw) / 2, 0, nw, h) }
        else { val nh = (w / ratio).toInt().coerceAtLeast(1); intArrayOf(0, (h - nh) / 2, w, nh) }
    }
}
