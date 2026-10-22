package com.blaze.assistant.agent

/** Low-data mode (v0.16.0): bytes Blaze's own process sent and received, for "0.42 MB" under an answer. */
object DataMeter {
    fun used(): Long = try {
        val uid = android.os.Process.myUid()
        val rx = android.net.TrafficStats.getUidRxBytes(uid); val tx = android.net.TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) -1L else rx + tx
    } catch (_: Throwable) { -1L }

    /** The chip for a task that started at [from] bytes; null when the phone doesn't report traffic. */
    fun chip(from: Long): String? {
        val now = used(); if (from < 0 || now < 0) return null
        return format(now - from)
    }

    fun format(bytes: Long): String = "\u2193\u2191 " + when {
        bytes < 0 -> "0 MB"
        bytes < 10_000_000 -> "%.2f MB".format(java.util.Locale.US, bytes / 1_000_000.0)
        else -> "%.1f MB".format(java.util.Locale.US, bytes / 1_000_000.0)
    }
}
