package com.blaze.assistant.device

/**
 * v0.30.4 storage numbers like the phone's own Settings show them. "What's my storage space" answered "5.9 GB free of
 * 118 GB" on a 128 GB low-end phone whose Analyze storage screen said 61.98 GB used of 128 GB (48 %). The old reading
 * was StatFs(/data): its total is the /data partition only (118 GB; the system partitions are left out) and its
 * availableBytes is only the blocks an ordinary app may allocate right now, without the space Android frees on demand
 * (clearable app caches) or the filesystem's reserved blocks. Settings uses StorageStatsManager (total rounded to the
 * advertised size, free including what it can clear), so Blaze does too now, with StatFs only as the fallback.
 * All in decimal GB (10^9 bytes), as Samsung shows them. Pure (StorageMathTest).
 */
object StorageMath {
    const val GB = 1_000_000_000L

    /** One volume: [total] and [free] in bytes. */
    data class Vol(val name: String, val total: Long, val free: Long) {
        val used: Long get() = (total - free).coerceAtLeast(0)
        val usedPct: Int get() = if (total <= 0) 0 else Math.round(used * 100.0 / total).toInt()
        val ok: Boolean get() = total > 0 && free >= 0
    }

    /**
     * The advertised size for a raw partition size, the way Android's own Settings rounds it (FileUtils.roundStorageSize):
     * the next power of two in decimal GB ("118 GB" of /data → 128 GB). Only for the StatFs fallback.
     */
    fun advertised(bytes: Long): Long {
        if (bytes <= 0) return 0
        var size = 1L; var unit = 1L
        while (size * unit < bytes) {
            size = size shl 1
            if (size > 512) { size = 1; unit *= 1000 }
        }
        return size * unit
    }

    /** "61.98 GB" → "62.0 GB"; totals that are whole numbers show none ("128 GB"); under 1 GB in MB. */
    fun gb(bytes: Long): String {
        if (bytes < GB) return "${Math.round(bytes / 1_000_000.0)} MB"
        val v = bytes.toDouble() / GB
        return if (bytes % GB == 0L) "${bytes / GB} GB" else String.format(java.util.Locale.US, "%.1f GB", v)
    }

    /** "Internal storage: 62.0 GB used of 128 GB (48%), 66.0 GB free." */
    fun line(v: Vol): String = "${v.name}: ${gb(v.used)} used of ${gb(v.total)} (${v.usedPct}%), ${gb(v.free)} free."

    /** The storage lines for device_status: internal first, then any SD card. */
    fun describe(internal: Vol?, sd: List<Vol>): String = buildString {
        append(if (internal != null && internal.ok) line(internal) else "Internal storage: couldn't read it.")
        for (s in sd.filter { it.ok }) append(' ').append(line(s))
    }
}
