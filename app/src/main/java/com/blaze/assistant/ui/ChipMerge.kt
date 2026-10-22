package com.blaze.assistant.ui

/**
 * v0.43.0: long-press a suggestion chip and drop it on another to ask for both at once. Pure (unit tested in ChipMergeTest);
 * the drag itself is in FollowupRow.
 *
 * A chip is a list of parts: one part for a plain suggestion, several once chips were combined. Parts keep the order the
 * chips had in the row, so "set gentle wake tone" dropped on "schedule bedtime reminder" (or the other way round) reads
 * "set gentle wake tone and schedule bedtime reminder".
 */
object ChipMerge {
    /** The request a chip sends: "a", "a and b", "a, b and c". Repeated parts (any case) are dropped. */
    fun text(parts: List<String>): String {
        val p = parts.map { it.trim().trimEnd('.', '!', ',', ';').replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        return when (p.size) {
            0 -> ""
            1 -> p[0]
            2 -> "${p[0]} and ${p[1]}"
            else -> p.dropLast(1).joinToString(", ") + " and " + p.last()
        }
    }

    /** Chips from the row's suggestions: one part each. */
    fun start(labels: List<String>): List<List<String>> = labels.map { listOf(it) }

    /**
     * Drop chip [from] on chip [onto]: the two become one chip where [onto] was (parts in row order, [from]'s parts first
     * when it sat to the left). Anything out of range, or a chip dropped on itself, changes nothing.
     */
    fun merge(chips: List<List<String>>, from: Int, onto: Int): List<List<String>> {
        if (from == onto || from !in chips.indices || onto !in chips.indices) return chips
        val joined = if (from < onto) chips[from] + chips[onto] else chips[onto] + chips[from]
        val dedup = joined.distinctBy { it.trim().lowercase() }
        return chips.mapIndexedNotNull { i, c -> when (i) { from -> null; onto -> dedup; else -> c } }
    }

    /** A combined chip dropped where there is no chip goes back to the chips it was made of (a plain chip just snaps back). */
    fun split(chips: List<List<String>>, at: Int): List<List<String>> {
        if (at !in chips.indices || chips[at].size < 2) return chips
        return chips.flatMapIndexed { i, c -> if (i == at) c.map { listOf(it) } else listOf(c) }
    }

    /** A chip's box in one coordinate space (px). */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val cx get() = (left + right) / 2f
        val cy get() = (top + bottom) / 2f
        fun contains(x: Float, y: Float, slop: Float = 0f) = x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
    }

    /**
     * Which chip the dragged one is over: the chip (not [dragged]) whose box holds the dragged chip's centre ([x], [y]),
     * the nearest centre if several do; -1 for none.
     */
    fun target(boxes: List<Box?>, dragged: Int, x: Float, y: Float, slop: Float = 0f): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        boxes.forEachIndexed { i, b ->
            if (i == dragged || b == null || !b.contains(x, y, slop)) return@forEachIndexed
            val d = (b.cx - x) * (b.cx - x) + (b.cy - y) * (b.cy - y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}
