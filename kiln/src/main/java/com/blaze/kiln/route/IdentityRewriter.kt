package com.blaze.kiln.route

// Identity rewrite on the way to the real system (ENGINE_DESIGN.md 1.4): the system must only ever see Blaze's
// package, uid and component names, or it throws SecurityException (AOSP checks callingPackage against the calling
// uid). The runtime's ServiceShim calls rewriteArgs() on every proxied binder call; this is the pure core.

class IdentityRewriter(
    val hostPackage: String,
    /** Guest packages known to the registry. */
    private val guests: () -> Set<String>,
    /** Maps (guestPackage, guestClass) to the slot class it currently runs in, if any. */
    private val slotFor: (String, String) -> String? = { _, _ -> null },
) {
    fun isGuest(pkg: String?): Boolean = pkg != null && pkg in guests()

    /** A package name as the system should see it. */
    fun outboundPackage(pkg: String?): String? = if (isGuest(pkg)) hostPackage else pkg

    /** A component (package/class) as the system should see it: a guest component becomes its slot. */
    fun outboundComponent(pkg: String, cls: String): Pair<String, String> {
        if (!isGuest(pkg)) return pkg to cls
        val slot = slotFor(pkg, cls)
        return hostPackage to (slot ?: cls)
    }

    /**
     * Rewrites a binder call's arguments in place. Strings equal to a guest package become the host package;
     * [ComponentLike] and [PackageCarrier] values are rewritten through their adapters. Other values pass through.
     * Returns how many arguments changed (for logging/tests).
     */
    fun rewriteArgs(args: Array<Any?>?, adapters: List<ArgAdapter> = emptyList()): Int {
        if (args == null) return 0
        var changed = 0
        for (i in args.indices) {
            val a = args[i] ?: continue
            if (a is String) {
                if (isGuest(a)) { args[i] = hostPackage; changed++ }
                continue
            }
            for (ad in adapters) {
                if (ad.accepts(a)) {
                    val r = ad.rewrite(a, this)
                    if (r !== a) { args[i] = r; changed++ }
                    break
                }
            }
        }
        return changed
    }

    /** Pluggable rewrite for framework types (ComponentName, AttributionSource …) living in the Android runtime. */
    interface ArgAdapter {
        fun accepts(value: Any): Boolean
        /** Return the same instance when nothing changes. */
        fun rewrite(value: Any, rw: IdentityRewriter): Any
    }
}
