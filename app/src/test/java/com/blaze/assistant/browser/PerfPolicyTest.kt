package com.blaze.assistant.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unreleased (browser perf): the hardware score, tiers, limits, user override, trim levels and tab discarding. */
class PerfPolicyTest {
    private val gb = 1024L * 1024 * 1024
    private fun hw(ramGb: Double, heap: Int = 256, cores: Int = 8, sdk: Int = 33, pc: Int = 0, lowRam: Boolean = false) =
        PerfPolicy.Hardware((ramGb * gb).toLong(), lowRam, heap, cores, sdk, pc)

    @Test fun scoreGrowsWithEveryHardwareSignal() {
        val base = PerfPolicy.score(hw(4.0, 192, 4))
        assertTrue(PerfPolicy.score(hw(8.0, 192, 4)) > base)
        assertTrue(PerfPolicy.score(hw(4.0, 384, 4)) > base)
        assertTrue(PerfPolicy.score(hw(4.0, 192, 8)) > base)
        // A declared performance class counts; an undeclared one (0) is left out rather than counted as zero.
        assertTrue(PerfPolicy.score(hw(4.0, 192, 4, pc = 34)) > base)
        assertTrue(PerfPolicy.score(hw(4.0, 192, 4, pc = 0)) == base)
    }

    @Test fun scoreIsContinuousAcrossRam() {
        var last = -1f
        var ram = 1.0
        while (ram <= 16.0) {
            val s = PerfPolicy.score(hw(ram))
            assertTrue("score must not drop as RAM grows ($ram GB)", s >= last)
            if (last >= 0f) assertTrue("no big jumps between nearby RAM sizes ($ram GB)", s - last < 0.1f)
            last = s; ram += 0.5
        }
        assertEquals(0f, PerfPolicy.ramScore(2 * gb), 1e-4f)
        assertEquals(1f, PerfPolicy.ramScore(12 * gb), 1e-4f)
        assertEquals(1f, PerfPolicy.ramScore(24 * gb), 1e-4f)
    }

    @Test fun lowRamFlagAndOldAndroidAreConservative() {
        assertEquals(PerfTier.LOW, PerfPolicy.tier(PerfPolicy.score(hw(8.0, 384, 8, lowRam = true))))
        assertTrue(PerfPolicy.score(hw(4.0, sdk = 27)) < PerfPolicy.score(hw(4.0, sdk = 33)))
    }

    @Test fun tiersSpanTheRange() {
        assertEquals(PerfTier.LOW, PerfPolicy.budget(hw(2.0, 128, 4, sdk = 28)).tier)
        assertEquals(PerfTier.MID, PerfPolicy.budget(hw(4.0, 256, 8)).tier)
        assertEquals(PerfTier.HIGH, PerfPolicy.budget(hw(12.0, 512, 8, pc = 33)).tier)
    }

    @Test fun highEndKeepsFullSpeedFeatures() {
        val b = PerfPolicy.budget(hw(12.0, 512, 8, pc = 34))
        assertEquals(PerfPolicy.MAX_LIVE, b.maxLiveWebViews)
        assertTrue(b.offscreenPreRaster)
        assertEquals(0.35f, b.thumbScale, 0.01f)
        assertEquals(220, b.animMs)
        assertEquals(TabStore.MAX_TOTAL_STATE, b.persistStateBudget)
        assertEquals(1500L, b.saveDebounceMs)
    }

    @Test fun constrainedPhonesGetALeanProfile() {
        val b = PerfPolicy.budget(hw(2.0, 128, 4, sdk = 28))
        assertTrue(b.maxLiveWebViews in 1..2)
        assertFalse(b.offscreenPreRaster)
        assertTrue(b.thumbScale < 0.2f)
        assertEquals(0, b.animMs)
        assertTrue(b.saveDebounceMs > 2500L)
        assertTrue(b.persistStateBudget < 1_300_000)
    }

    @Test fun limitsScaleMonotonicallyWithScore() {
        var prev = PerfPolicy.budgetFor(0f)
        for (i in 1..20) {
            val b = PerfPolicy.budgetFor(i / 20f)
            assertTrue(b.maxLiveWebViews >= prev.maxLiveWebViews)
            assertTrue(b.maxThumbs >= prev.maxThumbs)
            assertTrue(b.thumbScale >= prev.thumbScale)
            assertTrue(b.animMs >= prev.animMs)
            assertTrue(b.saveDebounceMs <= prev.saveDebounceMs)
            prev = b
        }
    }

    @Test fun ramCapsLiveWebViewsEvenInPerformanceMode() {
        val small = hw(2.0, 384, 8, pc = 34)
        val perf = PerfPolicy.budget(small, SpeedMode.PERFORMANCE)
        assertTrue(perf.maxLiveWebViews <= PerfPolicy.ramLiveCap(2 * gb))
        assertEquals(1, PerfPolicy.ramLiveCap(1 * gb))
        assertEquals(PerfPolicy.MAX_LIVE, PerfPolicy.ramLiveCap(16 * gb))
    }

    @Test fun userModesOverrideAuto() {
        val mid = hw(6.0, 256, 8)
        val auto = PerfPolicy.budget(mid, SpeedMode.AUTO)
        val perf = PerfPolicy.budget(mid, SpeedMode.PERFORMANCE)
        val batt = PerfPolicy.budget(mid, SpeedMode.BATTERY)
        assertTrue(perf.score > auto.score && batt.score < auto.score)
        assertTrue(perf.maxLiveWebViews >= auto.maxLiveWebViews && batt.maxLiveWebViews < auto.maxLiveWebViews)
        assertEquals(PerfTier.LOW, batt.tier)
        assertEquals(SpeedMode.AUTO, SpeedMode.of(null)); assertEquals(SpeedMode.AUTO, SpeedMode.of("nonsense"))
        assertEquals(SpeedMode.BATTERY, SpeedMode.of("battery"))
        assertEquals(SpeedMode.PERFORMANCE, SpeedMode.AUTO.next()); assertEquals(SpeedMode.AUTO, SpeedMode.BATTERY.next())
    }

    @Test fun trimFreesMoreUnderMorePressureAndOnSmallerPhones() {
        val hi = PerfPolicy.budgetFor(0.9f); val lo = PerfPolicy.budgetFor(0.2f)
        for (b in listOf(hi, lo)) {
            assertEquals(b.maxLiveWebViews, PerfPolicy.keepLiveOnTrim(0, b))
            assertEquals(1, PerfPolicy.keepLiveOnTrim(PerfPolicy.TRIM_RUNNING_CRITICAL, b))
            assertEquals(1, PerfPolicy.keepLiveOnTrim(PerfPolicy.TRIM_MODERATE, b))
            assertEquals(1, PerfPolicy.keepLiveOnTrim(80, b))
            assertEquals(0, PerfPolicy.keepThumbsOnTrim(PerfPolicy.TRIM_MODERATE, b))
        }
        // Hiding the UI alone keeps a high-end phone's tabs; a constrained one sleeps them.
        assertEquals(hi.maxLiveWebViews, PerfPolicy.keepLiveOnTrim(PerfPolicy.TRIM_UI_HIDDEN, hi))
        assertEquals(1, PerfPolicy.keepLiveOnTrim(PerfPolicy.TRIM_UI_HIDDEN, lo))
        assertTrue(PerfPolicy.keepLiveOnTrim(PerfPolicy.TRIM_RUNNING_LOW, hi) > 1)
    }

    @Test fun discardsLeastRecentlyShownButNeverCurrentOrBusy() {
        val tabs = listOf(
            PerfPolicy.TabUse(1, live = true, current = false, busy = false, lastShown = 10),
            PerfPolicy.TabUse(2, live = true, current = false, busy = true, lastShown = 5),
            PerfPolicy.TabUse(3, live = true, current = true, busy = false, lastShown = 1),
            PerfPolicy.TabUse(4, live = true, current = false, busy = false, lastShown = 20),
            PerfPolicy.TabUse(5, live = false, current = false, busy = false, lastShown = 0))
        assertEquals(listOf(1, 4), PerfPolicy.toDiscard(tabs, 1))
        assertEquals(listOf(1), PerfPolicy.toDiscard(tabs, 3))
        assertEquals(emptyList<Int>(), PerfPolicy.toDiscard(tabs, 4))
        assertEquals(listOf(1, 4), PerfPolicy.toDiscard(tabs, 0))   // at least the current tab stays
        assertEquals(listOf(3, 1), PerfPolicy.thumbsToDrop(tabs, setOf(1, 3, 4), 1))
        assertEquals(emptyList<Int>(), PerfPolicy.thumbsToDrop(tabs, setOf(1), 5))
    }

    @Test fun tabStoreHonoursTheDeviceStateBudget() {
        val st = mapOf("k" to "b:" + "A".repeat(300_000))
        val saved = TabStore.Saved(List(5) { TabStore.Snap("https://e$it.com/", "T$it", st) }, 0)
        fun kept(json: String) = TabStore.decode(json).tabs.count { it.state.isNotEmpty() }
        assertEquals(5, kept(TabStore.encode(saved)))
        assertEquals(3, kept(TabStore.encode(saved, 1_000_000)))
        assertEquals(5, TabStore.decode(TabStore.encode(saved, 0)).tabs.size)   // URLs always kept
        assertEquals(0, kept(TabStore.encode(saved, 0)))
    }
}
