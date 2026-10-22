package com.blaze.assistant.blazeos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.2 BlazeOS: the power state machine, the open-apps list and the resource budget from the host. */
class OsMachineTest {
    private val GB = OsResources.GB
    private val MB = OsResources.MB

    // ---------------- power ----------------

    @Test fun bootPauseResumeOff() {
        var s = Power.OFF
        s = OsMachine.next(s, PowerEvent.POWER_ON)!!; assertEquals(Power.BOOTING, s)
        s = OsMachine.next(s, PowerEvent.BOOT_DONE)!!; assertEquals(Power.ON, s)
        s = OsMachine.next(s, PowerEvent.PAUSE)!!; assertEquals(Power.PAUSED, s)
        s = OsMachine.next(s, PowerEvent.RESUME)!!; assertEquals(Power.ON, s)
        s = OsMachine.next(s, PowerEvent.POWER_OFF)!!; assertEquals(Power.OFF, s)
    }

    @Test fun eventsThatMeanNothingAreIgnored() {
        assertNull(OsMachine.next(Power.ON, PowerEvent.POWER_ON))
        assertNull(OsMachine.next(Power.PAUSED, PowerEvent.POWER_ON))
        assertNull(OsMachine.next(Power.OFF, PowerEvent.PAUSE))
        assertNull(OsMachine.next(Power.OFF, PowerEvent.RESUME))
        assertNull(OsMachine.next(Power.ON, PowerEvent.RESUME))
        assertNull(OsMachine.next(Power.OFF, PowerEvent.POWER_OFF))
        assertNull(OsMachine.next(Power.OFF, PowerEvent.RESTART))
        assertNull(OsMachine.next(Power.BOOTING, PowerEvent.RESTART))
        assertNull(OsMachine.next(Power.ON, PowerEvent.BOOT_DONE))
    }

    @Test fun restartBootFailureAndLeavingDuringBoot() {
        assertEquals(Power.BOOTING, OsMachine.next(Power.ON, PowerEvent.RESTART))
        assertEquals(Power.BOOTING, OsMachine.next(Power.PAUSED, PowerEvent.RESTART))
        assertEquals(Power.OFF, OsMachine.next(Power.BOOTING, PowerEvent.BOOT_FAILED))
        // Leaving Blaze while BlazeOS boots cancels the boot.
        assertEquals(Power.OFF, OsMachine.next(Power.BOOTING, PowerEvent.PAUSE))
        assertEquals(Power.OFF, OsMachine.next(Power.PAUSED, PowerEvent.POWER_OFF))
        assertEquals(Power.OFF, OsMachine.next(Power.BOOTING, PowerEvent.POWER_OFF))
    }

    @Test fun openingHomeBootsOrWakes() {
        assertEquals(PowerEvent.POWER_ON, OsMachine.onOpen(Power.OFF))
        assertEquals(PowerEvent.RESUME, OsMachine.onOpen(Power.PAUSED))
        assertNull(OsMachine.onOpen(Power.ON))
        assertNull(OsMachine.onOpen(Power.BOOTING))
    }

    @Test fun screenOnAndAppsOnlyWhileRunning() {
        assertTrue(OsMachine.keepScreenOn(Power.ON)); assertTrue(OsMachine.keepScreenOn(Power.BOOTING))
        assertFalse(OsMachine.keepScreenOn(Power.PAUSED)); assertFalse(OsMachine.keepScreenOn(Power.OFF))
        assertTrue(OsMachine.appsRun(Power.ON)); assertFalse(OsMachine.appsRun(Power.PAUSED))
        assertTrue(OsMachine.bootMs(AnimLevel.FULL) > OsMachine.bootMs(AnimLevel.REDUCED))
        assertTrue(OsMachine.bootMs(AnimLevel.OFF) <= 300)
        assertEquals("Paused", OsMachine.label(Power.PAUSED))
    }

    // ---------------- tasks ----------------

    @Test fun launchingBeyondTheBudgetClosesTheOldest() {
        var t = OsTasks()
        t = t.launch("a", 2).first
        t = t.launch("b", 2).first
        val (t3, closed) = t.launch("c", 2)
        assertEquals(listOf("a"), closed)
        assertEquals(listOf("b", "c"), t3.open)
        assertEquals("c", t3.front)
        // Bringing an open app forward closes nothing.
        val (t4, none) = t3.launch("b", 2)
        assertTrue(none.isEmpty()); assertEquals(listOf("c", "b"), t4.open); assertEquals(listOf("b", "c"), t4.recents)
    }

    @Test fun homeBackCloseKeepTheRightApps() {
        val t = OsTasks(listOf("a", "b"), "b")
        assertEquals(OsTasks(listOf("a", "b"), ""), t.home())
        assertEquals("", t.back().front)
        assertEquals(OsTasks(listOf("a"), ""), t.close("b"))
        assertEquals(OsTasks(listOf("b"), "b"), t.close("a"))
    }

    @Test fun pauseKeepsOnlyTheFrontAppAndRestoreBringsItBack() {
        val (p, closed) = OsTasks(listOf("a", "b", "c"), "b").pause()
        assertEquals(OsTasks(listOf("b"), "b"), p)
        assertEquals(listOf("a", "c"), closed)
        val (h, all) = OsTasks(listOf("a", "b"), "").pause()
        assertEquals(OsTasks(emptyList(), ""), h); assertEquals(listOf("a", "b"), all)
        assertEquals(OsTasks(listOf("blaze:clock"), "blaze:clock"), OsTasks().restore("blaze:clock") { true })
        assertEquals(OsTasks(), OsTasks().restore("pkg:gone") { false })
        assertEquals(OsTasks(), OsTasks().restore("") { true })
    }

    // ---------------- resources ----------------

    private fun lowEnd(free: Long = 20 * GB) = HostInfo(totalRam = 3 * GB, availRam = 900 * MB, lowRam = false, heapMb = 256, freeDisk = free, sdk = 31)

    @Test fun threeGbPhoneGetsTheLowTier() {
        val b = OsResources.allocate(lowEnd())
        assertEquals("low", b.tier)
        assertFalse("no real blur on a low tier, even on API 31", b.blur)
        assertEquals(AnimLevel.REDUCED, b.anim)
        assertEquals(2, b.maxLiveApps)
        assertEquals(96, b.iconPx)
        assertEquals(8 * MB, b.imageCacheBytes)
        // 25 % of 20 GB = 5 GB.
        assertEquals(5 * GB, b.diskQuota)
    }

    @Test fun quotaIsMinOf8GbAnd25PercentWithASmallFloor() {
        assertEquals(8 * GB, OsResources.quota(100 * GB))
        assertEquals(2 * GB, OsResources.quota(8 * GB))
        // 600 MB free: 25 % is 150 MB, the floor gives 256 MB (half of free is 300 MB).
        assertEquals(256 * MB, OsResources.quota(600 * MB))
        // 200 MB free: never more than half of it.
        assertEquals(100 * MB, OsResources.quota(200 * MB))
        assertEquals(0L, OsResources.quota(0))
        assertEquals(0L, OsResources.quota(-5))
    }

    @Test fun tiers() {
        assertEquals("low", OsResources.tier(lowEnd().copy(totalRam = 8 * GB, lowRam = true, heapMb = 512)))
        assertEquals("low", OsResources.tier(lowEnd().copy(totalRam = 8 * GB, heapMb = 512, availRam = 300 * MB)))
        assertEquals("mid", OsResources.tier(lowEnd().copy(totalRam = 4 * GB, availRam = 2 * GB, heapMb = 256)))
        assertEquals("high", OsResources.tier(lowEnd().copy(totalRam = 8 * GB, availRam = 4 * GB, heapMb = 512)))
        val hi = OsResources.allocate(lowEnd().copy(totalRam = 8 * GB, availRam = 4 * GB, heapMb = 512, sdk = 34))
        assertTrue(hi.blur); assertEquals(AnimLevel.FULL, hi.anim); assertEquals(5, hi.maxLiveApps)
        assertEquals(32 * MB, hi.imageCacheBytes)
        // Blur needs API 31 whatever the tier.
        assertFalse(OsResources.allocate(lowEnd().copy(totalRam = 8 * GB, availRam = 4 * GB, heapMb = 512, sdk = 30)).blur)
        // The phone's Remove animations wins.
        assertEquals(AnimLevel.OFF, OsResources.allocate(lowEnd().copy(animationsOff = true)).anim)
    }

    @Test fun cachesStayInsideTheHeapAndTheQuota() {
        val tiny = OsResources.allocate(lowEnd(free = 400 * MB).copy(heapMb = 48))
        assertEquals(6 * MB, tiny.imageCacheBytes)
        assertTrue(tiny.diskCacheBytes <= tiny.diskQuota / 10)
        assertEquals("1.5 GB", OsResources.size(GB + GB / 2))
        assertEquals("340 MB", OsResources.size(340 * MB))
        assertEquals("12 KB", OsResources.size(12 * 1024))
        assertEquals("0 B", OsResources.size(-1))
    }
}
