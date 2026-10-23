package com.blaze.engine.route

// What travels inside the intent that starts a slot (ENGINE_DESIGN.md 1.3, steps 2 and 4). The runtime copies these
// keys into the slot intent's extras together with the guest's original Intent (a Parcelable); this class is the
// pure, testable part: key names, validation, and the opaque launch token.

data class LaunchEnvelope(
    val guestPackage: String,
    val guestClass: String,
    val profile: Int,
    val slotClass: String,
    /** Random per launch; the slot refuses an envelope whose token the Coordinator did not issue. */
    val token: String,
    val taskKey: String? = null,
    val requestCode: Int = -1,
) {
    fun toMap(): Map<String, String> = buildMap {
        put(K_PKG, guestPackage)
        put(K_CLASS, guestClass)
        put(K_PROFILE, profile.toString())
        put(K_SLOT, slotClass)
        put(K_TOKEN, token)
        taskKey?.let { put(K_TASK, it) }
        if (requestCode != -1) put(K_REQ, requestCode.toString())
        put(K_VERSION, VERSION.toString())
    }

    companion object {
        const val VERSION = 1
        const val K_PREFIX = "com.blaze.engine.env."
        const val K_PKG = K_PREFIX + "pkg"
        const val K_CLASS = K_PREFIX + "cls"
        const val K_PROFILE = K_PREFIX + "profile"
        const val K_SLOT = K_PREFIX + "slot"
        const val K_TOKEN = K_PREFIX + "token"
        const val K_TASK = K_PREFIX + "task"
        const val K_REQ = K_PREFIX + "req"
        const val K_VERSION = K_PREFIX + "v"
        /** Extra holding the guest's own Intent (Parcelable) next to the keys above. */
        const val K_ORIGINAL_INTENT = K_PREFIX + "intent"

        /** Null when the map is not a complete, current, well-formed envelope. */
        fun fromMap(m: Map<String, String?>): LaunchEnvelope? {
            if (m[K_VERSION]?.toIntOrNull() != VERSION) return null
            val pkg = m[K_PKG]?.takeIf { com.blaze.engine.store.PackageNames.isValid(it) } ?: return null
            val cls = m[K_CLASS]?.takeIf { it.isNotBlank() && it.length < 512 } ?: return null
            val profile = m[K_PROFILE]?.toIntOrNull()?.takeIf { it in 0..999 } ?: return null
            val slot = m[K_SLOT]?.takeIf { SlotId.isSlot(it) } ?: return null
            val token = m[K_TOKEN]?.takeIf { it.length in 16..128 } ?: return null
            return LaunchEnvelope(pkg, cls, profile, slot, token, m[K_TASK], m[K_REQ]?.toIntOrNull() ?: -1)
        }
    }
}
