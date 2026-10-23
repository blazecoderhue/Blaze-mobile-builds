package com.blaze.assistant.browser.phone

/**
 * 0.45.19-speed: Blaze Phone's own timings on this phone (page read; act until the tool returns with the page
 * re-read), newest [MAX] of each, so phone_status can show real p50 / p95 from the device.
 */
object PhonePerf {
    const val READ = "page read"
    const val ACT = "act to verified"
    private const val MAX = 200
    private val data = LinkedHashMap<String, ArrayDeque<Long>>()

    @Synchronized fun add(kind: String, ms: Long) {
        val q = data.getOrPut(kind) { ArrayDeque() }
        q.addLast(ms.coerceAtLeast(0L))
        while (q.size > MAX) q.removeFirst()
    }

    /** Nearest-rank percentile ([p] in 0..1). */
    fun pct(xs: List<Long>, p: Double): Long {
        if (xs.isEmpty()) return 0L
        val s = xs.sorted()
        return s[(Math.ceil(p * s.size).toInt() - 1).coerceIn(0, s.size - 1)]
    }

    /** (p50, p95, count) or null with no samples. */
    @Synchronized fun stats(kind: String): Triple<Long, Long, Int>? {
        val q = data[kind]?.toList() ?: return null
        if (q.isEmpty()) return null
        return Triple(pct(q, 0.5), pct(q, 0.95), q.size)
    }

    @Synchronized fun line(): String {
        val parts = listOf(READ, ACT).mapNotNull { k -> stats(k)?.let { (p50, p95, n) -> "$k p50 $p50 ms / p95 $p95 ms (n=$n)" } }
        return if (parts.isEmpty()) "" else "Speed on this phone: " + parts.joinToString("; ") + "."
    }

    @Synchronized fun reset() { data.clear() }
}
