package com.blaze.assistant.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.30.4: storage in decimal GB like Samsung's Analyze storage (128 GB total, used, free, used %, SD card). */
class StorageMathTest {
    private val G = StorageMath.GB

    @Test fun theLowEndPhoneNumbers() {
        // Samsung: 61.98 GB used of 128 GB (48%).
        val v = StorageMath.Vol("Internal storage", 128 * G, 128 * G - 61_980_000_000L)
        assertEquals(61_980_000_000L, v.used)
        assertEquals(48, v.usedPct)
        assertEquals("Internal storage: 62.0 GB used of 128 GB (48%), 66.0 GB free.", StorageMath.line(v))
    }

    @Test fun advertisedSizeFromAPartition() {
        // The old reading's 118 GB /data partition is a 128 GB phone.
        assertEquals(128 * G, StorageMath.advertised(118_000_000_000L))
        assertEquals(64 * G, StorageMath.advertised(52_000_000_000L))
        assertEquals(256 * G, StorageMath.advertised(240_000_000_000L))
        assertEquals(1000 * G, StorageMath.advertised(990_000_000_000L))
        assertEquals(0L, StorageMath.advertised(0))
    }

    @Test fun formattingAndNoOverflow() {
        assertEquals("128 GB", StorageMath.gb(128 * G))
        assertEquals("5.9 GB", StorageMath.gb(5_900_000_000L))
        assertEquals("750 MB", StorageMath.gb(750_000_000L))
        // Terabyte cards stay Long all the way (no Int overflow).
        val sd = StorageMath.Vol("SD card", 1000 * G, 400 * G)
        assertEquals(60, sd.usedPct)
        assertEquals(0, StorageMath.Vol("x", 0, 0).usedPct)
        val all = StorageMath.describe(StorageMath.Vol("Internal storage", 128 * G, 66 * G), listOf(StorageMath.Vol("SD card", 32 * G, 30 * G)))
        assertTrue(all, all.contains("Internal storage: 62 GB used of 128 GB (48%), 66 GB free.") && all.contains("SD card: 2 GB used of 32 GB (6%), 30 GB free."))
        assertEquals("Internal storage: couldn't read it.", StorageMath.describe(null, emptyList()))
    }
}
