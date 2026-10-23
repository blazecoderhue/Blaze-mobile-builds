package com.blaze.engine.route

import com.blaze.engine.manifest.LaunchMode

// Host stub components ("slots") and how a guest component is leased one (ENGINE_DESIGN.md 1.1).
// Android starts only components declared in an installed manifest, so Blaze declares a fixed pool and the engine
// hands them out. Pure Kotlin so the whole allocator is unit tested off-device.

const val SLOT_PACKAGE = "com.blaze.assistant"
const val SLOT_CLASS_PREFIX = "com.blaze.engine.slot"

/** Guest processes declared in the host manifest: com.blaze.assistant:g0 … :g7 */
object GuestProcesses {
    const val COUNT = 8
    /** Processes whose slots are actually declared in the manifest today (M1: :g0 only; M2 declares all 8). */
    const val DECLARED = 1
    fun name(index: Int): String {
        require(index in 0 until COUNT) { "no guest process $index" }
        return "$SLOT_PACKAGE:g$index"
    }
}

enum class SlotKind { ACTIVITY, ACTIVITY_TRANSLUCENT, SERVICE, SERVICE_FG, PROVIDER, RECEIVER }

/** Counts per process. Activities are split by launch mode because launchMode is fixed at manifest time. */
object SlotPlan {
    val ACTIVITIES: Map<LaunchMode, Int> = mapOf(
        LaunchMode.STANDARD to 12,
        LaunchMode.SINGLE_TOP to 4,
        LaunchMode.SINGLE_TASK to 4,
        LaunchMode.SINGLE_INSTANCE to 2,
    )
    const val TRANSLUCENT_PER_MODE = 1
    const val SERVICES = 8
    const val FOREGROUND_SERVICE_TYPES = 6
    const val PROVIDERS = 1
    const val RECEIVERS = 1

    fun activityCount(mode: LaunchMode): Int = ACTIVITIES[mode] ?: ACTIVITIES.getValue(LaunchMode.STANDARD)
}

data class SlotId(
    val process: Int,
    val kind: SlotKind,
    val launchMode: LaunchMode,
    val index: Int,
) {
    /** The class name declared in Blaze's manifest for this slot. */
    val className: String
        get() = when (kind) {
            SlotKind.ACTIVITY -> "$SLOT_CLASS_PREFIX.A${process}_${launchMode.code}_$index"
            SlotKind.ACTIVITY_TRANSLUCENT -> "$SLOT_CLASS_PREFIX.T${process}_${launchMode.code}_$index"
            SlotKind.SERVICE -> "$SLOT_CLASS_PREFIX.S${process}_$index"
            SlotKind.SERVICE_FG -> "$SLOT_CLASS_PREFIX.F${process}_$index"
            SlotKind.PROVIDER -> "$SLOT_CLASS_PREFIX.P$process"
            SlotKind.RECEIVER -> "$SLOT_CLASS_PREFIX.R$process"
        }

    val processName: String get() = GuestProcesses.name(process)

    companion object {
        private val ACT = Regex("^\\Q$SLOT_CLASS_PREFIX\\E\\.([AT])(\\d+)_(\\d+)_(\\d+)$")
        private val SVC = Regex("^\\Q$SLOT_CLASS_PREFIX\\E\\.([SF])(\\d+)_(\\d+)$")
        private val SINGLE = Regex("^\\Q$SLOT_CLASS_PREFIX\\E\\.([PR])(\\d+)$")

        fun parse(className: String): SlotId? {
            ACT.matchEntire(className)?.let { m ->
                val kind = if (m.groupValues[1] == "A") SlotKind.ACTIVITY else SlotKind.ACTIVITY_TRANSLUCENT
                return SlotId(m.groupValues[2].toInt(), kind, LaunchMode.of(m.groupValues[3].toInt()), m.groupValues[4].toInt())
            }
            SVC.matchEntire(className)?.let { m ->
                val kind = if (m.groupValues[1] == "S") SlotKind.SERVICE else SlotKind.SERVICE_FG
                return SlotId(m.groupValues[2].toInt(), kind, LaunchMode.STANDARD, m.groupValues[3].toInt())
            }
            SINGLE.matchEntire(className)?.let { m ->
                val kind = if (m.groupValues[1] == "P") SlotKind.PROVIDER else SlotKind.RECEIVER
                return SlotId(m.groupValues[2].toInt(), kind, LaunchMode.STANDARD, 0)
            }
            return null
        }

        fun isSlot(className: String): Boolean = parse(className) != null
    }
}

/** What a leased slot is currently standing in for. */
data class SlotLease(
    val slot: SlotId,
    val guestPackage: String,
    val guestClass: String,
    /** Opaque task key for activities (the engine's own task grouping), null elsewhere. */
    val taskKey: String?,
    val leasedAtMillis: Long,
)

class SlotExhaustedException(message: String) : Exception(message)

/**
 * Leases slots inside one guest process. Not thread-safe by itself; the Coordinator owns one instance per process
 * and calls it on its own thread.
 */
class SlotAllocator(val process: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private val leases = LinkedHashMap<SlotId, SlotLease>()

    val active: List<SlotLease> get() = leases.values.toList()

    fun leaseOf(slot: SlotId): SlotLease? = leases[slot]

    fun find(guestPackage: String, guestClass: String, taskKey: String? = null): SlotLease? =
        leases.values.firstOrNull { it.guestPackage == guestPackage && it.guestClass == guestClass && (taskKey == null || it.taskKey == taskKey) }

    /**
     * Leases an activity slot for a guest activity. A singleTask/singleInstance activity reuses its existing slot
     * (that is what those modes mean), a standard activity always takes a new one.
     */
    fun leaseActivity(guestPackage: String, guestClass: String, mode: LaunchMode, taskKey: String?, translucent: Boolean = false): SlotLease {
        if (mode == LaunchMode.SINGLE_TASK || mode == LaunchMode.SINGLE_INSTANCE) {
            find(guestPackage, guestClass)?.let { return it }
        } else if (mode == LaunchMode.SINGLE_TOP && taskKey != null) {
            find(guestPackage, guestClass, taskKey)?.let { return it }
        }
        val kind = if (translucent) SlotKind.ACTIVITY_TRANSLUCENT else SlotKind.ACTIVITY
        val count = if (translucent) SlotPlan.TRANSLUCENT_PER_MODE else SlotPlan.activityCount(mode)
        return take(kind, mode, count, guestPackage, guestClass, taskKey)
            ?: (if (mode == LaunchMode.STANDARD && !translucent) recycleStandard(guestPackage, guestClass, taskKey) else null)
            ?: throw SlotExhaustedException("no $kind slot left for $mode in process $process")
    }

    fun leaseService(guestPackage: String, guestClass: String, foreground: Boolean = false): SlotLease {
        find(guestPackage, guestClass)?.let { return it }
        val kind = if (foreground) SlotKind.SERVICE_FG else SlotKind.SERVICE
        val count = if (foreground) SlotPlan.FOREGROUND_SERVICE_TYPES else SlotPlan.SERVICES
        return take(kind, LaunchMode.STANDARD, count, guestPackage, guestClass, null)
            ?: throw SlotExhaustedException("no service slot left in process $process")
    }

    fun release(slot: SlotId): SlotLease? = leases.remove(slot)

    fun releaseAll(guestPackage: String) {
        leases.entries.removeAll { it.value.guestPackage == guestPackage }
    }

    private fun take(kind: SlotKind, mode: LaunchMode, count: Int, pkg: String, cls: String, taskKey: String?): SlotLease? {
        for (i in 0 until count) {
            val id = SlotId(process, kind, if (kind == SlotKind.ACTIVITY || kind == SlotKind.ACTIVITY_TRANSLUCENT) mode else LaunchMode.STANDARD, i)
            if (id !in leases) {
                val lease = SlotLease(id, pkg, cls, taskKey, clock())
                leases[id] = lease
                return lease
            }
        }
        return null
    }

    /** Last resort: recycle the oldest standard-mode lease (its activity is finished by the caller). */
    private fun recycleStandard(pkg: String, cls: String, taskKey: String?): SlotLease? {
        val oldest = leases.values.filter { it.slot.kind == SlotKind.ACTIVITY && it.slot.launchMode == LaunchMode.STANDARD }
            .minByOrNull { it.leasedAtMillis } ?: return null
        leases.remove(oldest.slot)
        val lease = SlotLease(oldest.slot, pkg, cls, taskKey, clock())
        leases[oldest.slot] = lease
        return lease
    }
}
