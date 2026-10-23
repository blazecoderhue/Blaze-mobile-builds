package com.blaze.assistant.phone

/**
 * Blaze Phone (spike): the pure rules (BlazePhoneTest). Which process is which, how many guests may run at once, which
 * ones to stop, and the guest slots the engine's manifest declares.
 */
object BlazePhonePolicy {
    /** Guest process slots in phone-engine's manifest (:p0..:p3, ProxyManifest.FREE_COUNT). */
    const val ENGINE_SLOTS = 4
    /** A guest nobody used for this long is stopped. */
    const val IDLE_MS = 5 * 60_000L
    private const val GIB = 1024L * 1024 * 1024

    enum class Proc { MAIN, ENGINE_SERVER, GUEST, OTHER }

    /** What kind of process [name] is for the app [hostPkg] (":black" = engine server, ":p0".."p3" = guest slots). */
    fun proc(name: String?, hostPkg: String): Proc {
        val n = name ?: return Proc.MAIN
        val slot = if (n.startsWith("$hostPkg:p")) n.removePrefix("$hostPkg:p").toIntOrNull() else null
        return when {
            n.isBlank() || n == hostPkg -> Proc.MAIN
            n == "$hostPkg:black" -> Proc.ENGINE_SERVER
            slot != null && slot in 0 until ENGINE_SLOTS -> Proc.GUEST
            else -> Proc.OTHER
        }
    }

    /** The engine (and only the engine) starts in its own processes; Blaze's main process never touches it until used. */
    fun isEngineProcess(name: String?, hostPkg: String): Boolean = proc(name, hostPkg).let { it == Proc.ENGINE_SERVER || it == Proc.GUEST }

    /**
     * Marketing size of a phone's RAM from ActivityManager.MemoryInfo.totalMem, which reports less than the label
     * (the kernel keeps some: a "4 GB" phone says ~3.6-3.8 GiB, a "3 GB" one ~2.7-2.9 GiB): rounded up to a whole GiB.
     */
    fun nominalGb(totalMemBytes: Long): Int =
        if (totalMemBytes <= 0) 0 else kotlin.math.ceil(totalMemBytes.toDouble() / GIB).toInt()

    /** At most 1 live guest on phones under 4 GB, else 2 (each guest costs its own heap + ~25-40 MB of stub overhead). */
    fun maxLiveGuests(totalMemBytes: Long): Int = if (nominalGb(totalMemBytes) < 4) 1 else 2

    /**
     * Which guests to stop before [pkg] starts: the least recently used ones beyond the cap. [live] maps package to its
     * last use (ms). A package that is already live needs nothing stopped.
     */
    fun toEvict(live: Map<String, Long>, pkg: String, cap: Int): List<String> {
        if (pkg in live) return emptyList()
        val over = live.size + 1 - cap.coerceAtLeast(1)
        if (over <= 0) return emptyList()
        return live.entries.sortedBy { it.value }.take(over).map { it.key }
    }

    /** Guests idle for [IDLE_MS] or more at [now]. */
    fun idle(live: Map<String, Long>, now: Long, idleMs: Long = IDLE_MS): List<String> =
        live.filter { now - it.value >= idleMs }.keys.sorted()
}
