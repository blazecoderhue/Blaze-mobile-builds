package com.blaze.assistant.blazeos

/*
 * v0.33.2 BlazeOS: the machine's pure rules. The power state machine (Off → Booting → On ⇄ Paused, Power off,
 * Restart), the app task list (which apps are open, which one is in front, which one Back and Resume go to) and the
 * resource budget taken from the host at boot. Plain Kotlin, unit tested in OsMachineTest. The Android side is
 * BlazeOs.kt (the device) and OsHost.kt (reading the host).
 */

/** The four states of the BlazeOS device. */
enum class Power { OFF, BOOTING, ON, PAUSED }

/** What can happen to the device. */
enum class PowerEvent { POWER_ON, BOOT_DONE, BOOT_FAILED, PAUSE, RESUME, POWER_OFF, RESTART }

object OsMachine {
    /** How long the boot animation runs (ms); reduced animation boots faster, no animation boots at once. */
    fun bootMs(anim: AnimLevel): Long = when (anim) { AnimLevel.FULL -> 1800L; AnimLevel.REDUCED -> 900L; AnimLevel.OFF -> 250L }

    /**
     * The next state, or null when [e] means nothing in [s] (the caller ignores it).
     * - POWER_ON: Off → Booting. A paused machine is woken with RESUME, not booted again.
     * - BOOT_DONE: Booting → On. BOOT_FAILED: Booting → Off.
     * - PAUSE: On → Paused. Leaving while it still boots cancels the boot (Booting → Off), so the next open boots cleanly.
     * - RESUME: Paused → On.
     * - POWER_OFF: anything but Off → Off.
     * - RESTART: On / Paused → Booting.
     */
    fun next(s: Power, e: PowerEvent): Power? = when (e) {
        PowerEvent.POWER_ON -> if (s == Power.OFF) Power.BOOTING else null
        PowerEvent.BOOT_DONE -> if (s == Power.BOOTING) Power.ON else null
        PowerEvent.BOOT_FAILED -> if (s == Power.BOOTING) Power.OFF else null
        PowerEvent.PAUSE -> when (s) { Power.ON -> Power.PAUSED; Power.BOOTING -> Power.OFF; else -> null }
        PowerEvent.RESUME -> if (s == Power.PAUSED) Power.ON else null
        PowerEvent.POWER_OFF -> if (s != Power.OFF) Power.OFF else null
        PowerEvent.RESTART -> if (s == Power.ON || s == Power.PAUSED) Power.BOOTING else null
    }

    /** What opening BlazeOS (tapping Home) does in each state. */
    fun onOpen(s: Power): PowerEvent? = when (s) { Power.OFF -> PowerEvent.POWER_ON; Power.PAUSED -> PowerEvent.RESUME; else -> null }

    /** Apps can run and draw only while On. */
    fun appsRun(s: Power) = s == Power.ON

    /** The host screen stays on while BlazeOS boots or is on (never while Off or Paused). */
    fun keepScreenOn(s: Power) = s == Power.ON || s == Power.BOOTING

    /** Plain words for the state (Blaze's Home card, About). */
    fun label(s: Power): String = when (s) { Power.OFF -> "Off"; Power.BOOTING -> "Starting…"; Power.ON -> "On"; Power.PAUSED -> "Paused" }
}

/**
 * The open apps, newest last. [front] is the app on screen ("" = the BlazeOS home screen). Apps beyond the budget's
 * [OsBudget.maxLiveApps] are closed oldest first (their state is dropped, as a phone does under memory pressure).
 */
data class OsTasks(val open: List<String> = emptyList(), val front: String = "") {
    /** Opens (or brings forward) [id]; returns the new tasks and the apps that had to close. */
    fun launch(id: String, maxLive: Int): Pair<OsTasks, List<String>> {
        val list = (open - id) + id
        val keep = maxLive.coerceAtLeast(1)
        val closed = if (list.size > keep) list.take(list.size - keep) else emptyList()
        return OsTasks(list - closed.toSet(), id) to closed
    }

    /** The home indicator: back to the home screen, apps stay open. */
    fun home(): OsTasks = copy(front = "")

    /** Back on an app's own root screen: it goes to the background and the home screen shows (like a phone). */
    fun back(): OsTasks = home()

    /** Swipe an app away in the switcher (or the app was uninstalled). */
    fun close(id: String): OsTasks = OsTasks(open - id, if (front == id) "" else front)

    /** Pause frees memory: only the app in front stays (its state is restored on resume); the rest close. */
    fun pause(): Pair<OsTasks, List<String>> {
        val keep = if (front.isNotEmpty()) listOf(front) else emptyList()
        return OsTasks(keep, front) to (open - keep.toSet())
    }

    /** Resume after a restart of the host app: the last app comes back in front when it still exists. */
    fun restore(last: String, exists: (String) -> Boolean): OsTasks =
        if (last.isNotEmpty() && exists(last)) OsTasks(listOf(last), last) else OsTasks()

    /** Recents, newest first. */
    val recents: List<String> get() = open.reversed()
}

/** How much the device animates. */
enum class AnimLevel { FULL, REDUCED, OFF }

/** What the host phone has, read at boot (OsHost.read). Bytes unless named otherwise. */
data class HostInfo(
    val totalRam: Long,
    val availRam: Long,
    val lowRam: Boolean,
    /** ActivityManager.memoryClass: this app's heap limit in MB. */
    val heapMb: Int,
    val freeDisk: Long,
    val sdk: Int,
    /** The phone's "Remove animations" (animator scale 0). */
    val animationsOff: Boolean = false,
)

/** The resources BlazeOS gives itself for this boot. */
data class OsBudget(
    val tier: String,
    /** The BlazeOS disk may hold at most this many bytes (apps + their data). */
    val diskQuota: Long,
    /** In-memory icon / image cache, bytes. */
    val imageCacheBytes: Long,
    /** On-disk cache (web apps, thumbnails), bytes. */
    val diskCacheBytes: Long,
    /** Largest icon bitmap kept, px. */
    val iconPx: Int,
    /** Largest photo / wallpaper side decoded, px. */
    val imagePx: Int,
    /** Real RenderEffect blur (API 31+ and not a low tier); otherwise the frost is drawn. */
    val blur: Boolean,
    val anim: AnimLevel,
    /** Apps kept open at once (more close oldest first). */
    val maxLiveApps: Int,
)

object OsResources {
    const val GB = 1024L * 1024 * 1024
    const val MB = 1024L * 1024
    /** The disk quota is at most this (8 GB) … */
    const val QUOTA_MAX = 8 * GB
    /** … and at most this share of the host's free space. */
    const val QUOTA_SHARE = 0.25
    /** Below this the device still gets a small working disk (but never more than half the free space). */
    const val QUOTA_FLOOR = 256 * MB

    /**
     * Low: Android says low-RAM, under 3.5 GB of RAM, a heap of 192 MB or less, or under 600 MB free right now
     * (a low-end phone with 3 GB lands here). High: 6 GB or more with a 384 MB+ heap. Mid otherwise.
     */
    fun tier(h: HostInfo): String = when {
        h.lowRam || h.totalRam < 3584 * MB || h.heapMb in 1..192 || h.availRam in 1 until 600 * MB -> "low"
        h.totalRam >= 6 * GB && h.heapMb >= 384 -> "high"
        else -> "mid"
    }

    /** The disk quota: min(8 GB, 25 % of free space), at least [QUOTA_FLOOR] when half the free space allows it. */
    fun quota(freeDisk: Long): Long {
        val free = freeDisk.coerceAtLeast(0)
        val share = (free * QUOTA_SHARE).toLong()
        val floor = minOf(QUOTA_FLOOR, free / 2)
        return minOf(QUOTA_MAX, maxOf(share, floor))
    }

    fun allocate(h: HostInfo): OsBudget {
        val t = tier(h)
        val quota = quota(h.freeDisk)
        // Image cache: a slice of the heap (an eighth), capped per tier.
        val heap = (if (h.heapMb > 0) h.heapMb else 128).toLong() * MB
        val imgCap = when (t) { "low" -> 8 * MB; "mid" -> 16 * MB; else -> 32 * MB }
        val diskCacheCap = when (t) { "low" -> 64 * MB; "mid" -> 128 * MB; else -> 256 * MB }
        val anim = when { h.animationsOff -> AnimLevel.OFF; t == "low" -> AnimLevel.REDUCED; else -> AnimLevel.FULL }
        return OsBudget(
            tier = t,
            diskQuota = quota,
            imageCacheBytes = minOf(imgCap, heap / 8),
            diskCacheBytes = minOf(diskCacheCap, quota / 10),
            iconPx = if (t == "low") 96 else 144,
            imagePx = when (t) { "low" -> 1280; "mid" -> 1920; else -> 2560 },
            blur = h.sdk >= 31 && t != "low",
            anim = anim,
            maxLiveApps = when (t) { "low" -> 2; "mid" -> 3; else -> 5 },
        )
    }

    /** "1.2 GB" / "340 MB" / "12 KB". */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b >= GB -> String.format(java.util.Locale.US, "%.1f GB", b.toDouble() / GB)
            b >= MB -> "${b / MB} MB"
            b >= 1024 -> "${b / 1024} KB"
            else -> "$b B"
        }
    }
}
