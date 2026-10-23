package com.blaze.kiln.route

// Which guest package owns which guest process (ENGINE_DESIGN.md 1.5): one package per :gN process, never two,
// because guests' static state and Application objects would collide. Pure Kotlin; the Coordinator owns it.

data class ProcessKey(val guestPackage: String, val guestProcess: String)

class ProcessTable(private val size: Int = GuestProcesses.COUNT, private val clock: () -> Long = System::currentTimeMillis) {
    private val owners = arrayOfNulls<ProcessKey>(size)
    private val lastUsed = LongArray(size)
    private val running = BooleanArray(size)

    /** Index of the process that runs [key], if any. */
    fun indexOf(key: ProcessKey): Int? = owners.indexOfFirst { it == key }.takeIf { it >= 0 }

    fun ownerOf(index: Int): ProcessKey? = owners.getOrNull(index)

    /**
     * Returns the process for [key], assigning a free one if needed. When all are taken, the least recently used
     * process that is not running is reassigned; [Assignment.evicted] names its previous owner so the caller can
     * release its slot leases. Null when every process is busy with a running guest.
     */
    fun assign(key: ProcessKey): Assignment? {
        indexOf(key)?.let { touch(it); return Assignment(it, null) }
        val free = owners.indexOfFirst { it == null }
        if (free >= 0) { owners[free] = key; touch(free); return Assignment(free, null) }
        val victim = (0 until size).filter { !running[it] }.minByOrNull { lastUsed[it] } ?: return null
        val prev = owners[victim]
        owners[victim] = key
        touch(victim)
        return Assignment(victim, prev)
    }

    fun markRunning(index: Int, isRunning: Boolean) { running[index] = isRunning; if (isRunning) touch(index) }

    /** Process died: it stays assigned (a restart lands in the same place) but is no longer running. */
    fun onProcessDied(index: Int) { running[index] = false }

    fun releasePackage(guestPackage: String) {
        for (i in 0 until size) if (owners[i]?.guestPackage == guestPackage) { owners[i] = null; running[i] = false }
    }

    private fun touch(i: Int) { lastUsed[i] = clock() }

    data class Assignment(val index: Int, val evicted: ProcessKey?)
}
