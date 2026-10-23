package com.blaze.assistant.phone

/**
 * Blaze Phone (spike): can this APK run inside Blaze's sandbox, and if not, why, in words the user understands
 * (BlazePhoneTest). Blockers stop the install before the confirm card; warnings go on the card. [explainCrash] turns a
 * guest's crash into the same kind of sentence.
 */
object GuestCompat {
    /** What Blaze reads from an APK before installing it (PackageManager.getPackageArchiveInfo + the zip's entries). */
    data class Apk(
        val pkg: String,
        val label: String = pkg,
        val minSdk: Int = 1,
        val permissions: Set<String> = emptySet(),
        val metaData: Set<String> = emptySet(),
        /** Permissions guarding the app's services (BIND_ACCESSIBILITY_SERVICE, BIND_VPN_SERVICE…). */
        val servicePermissions: Set<String> = emptySet(),
        val receiverPermissions: Set<String> = emptySet(),
        /** ABIs found under lib/ in the APK; empty = no native code. */
        val nativeAbis: Set<String> = emptySet(),
        val hasLauncher: Boolean = true,
        /** Base APK of a Play bundle whose splits are missing (meta-data com.android.vending.splits.required = true). */
        val splitsRequired: Boolean = false,
    )

    data class Verdict(val blockers: List<String>, val warnings: List<String>) {
        val ok get() = blockers.isEmpty()
        /** One message for the user: "<app> can't run in Blaze Phone: it needs …" or null when it can. */
        fun message(label: String): String? = if (ok) null else "$label can't run in Blaze Phone: it " + blockers.joinToString("; it ") + "."
    }

    const val GMS_META = "com.google.android.gms.version"

    fun check(apk: Apk, hostPkg: String, deviceSdk: Int, deviceAbis: List<String>): Verdict {
        val b = ArrayList<String>(); val w = ArrayList<String>()
        if (apk.pkg == hostPkg) b += "is Blaze itself"
        if (apk.minSdk > deviceSdk) b += "needs Android API ${apk.minSdk} (this phone has $deviceSdk)"
        if (apk.splitsRequired) b += "is only part of an app (a Play Store split bundle); Blaze needs the full single APK"
        if (apk.nativeAbis.isNotEmpty() && apk.nativeAbis.none { it in deviceAbis })
            b += "is built only for ${apk.nativeAbis.sorted().joinToString("/")} processors (this phone is ${deviceAbis.joinToString("/")})"
        if (!apk.hasLauncher) b += "has no screen to open (no launcher activity)"
        val gms = GMS_META in apk.metaData || apk.permissions.any { it.startsWith("com.google.android.c2dm.") }
        val googleApp = apk.pkg.startsWith("com.google.android.") || apk.pkg == "com.android.vending"
        when {
            googleApp -> b += "needs Google Play services (it's a Google app), which Blaze Phone doesn't have"
            "com.android.vending.CHECK_LICENSE" in apk.permissions -> b += "needs Google Play licensing (it checks its purchase with the Play Store), which Blaze Phone doesn't have"
            gms -> w += "Uses Google Play services, which Blaze Phone doesn't have: Google sign-in, maps, push messages and in-app purchases may not work."
        }
        val roles = mapOf(
            "android.permission.BIND_ACCESSIBILITY_SERVICE" to "an accessibility service",
            "android.permission.BIND_VPN_SERVICE" to "a VPN",
            "android.permission.BIND_DEVICE_ADMIN" to "device admin",
            "android.permission.BIND_INPUT_METHOD" to "a keyboard",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" to "notification access",
        )
        val needs = roles.filterKeys { it in apk.servicePermissions || it in apk.receiverPermissions }.values.toList()
        if (needs.isNotEmpty()) {
            val what = needs.joinToString(", ")
            if (!apk.hasLauncher) b += "only works as $what, which apps inside Blaze Phone can't be"
            else w += "Its $what part won't work inside Blaze Phone (Android only gives that to installed apps)."
        }
        if ("android.permission.SEND_SMS" in apk.permissions || "android.permission.CALL_PHONE" in apk.permissions)
            w += "It asks to send texts or make calls; inside Blaze Phone it only gets what Blaze itself is allowed."
        return Verdict(b, w)
    }

    /** A guest crash (exception text / stack) as the honest reason, or null when there's nothing specific to say. */
    fun explainCrash(text: String?): String? {
        val t = text ?: return null
        return when {
            Regex("""GooglePlayServices(NotAvailable|Repairable)|com\.google\.android\.gms\.common\.(GooglePlayServicesUtil|api\.GoogleApiActivity)|Google Play services (is )?(missing|not available|out of date)""").containsMatchIn(t) ->
                "needs Google Play services, which Blaze Phone doesn't have"
            Regex("""(?i)play ?integrity|SafetyNet|com\.google\.android\.play\.core\.integrity""").containsMatchIn(t) ->
                "checks the phone with Google Play Integrity, which apps in a sandbox can't pass"
            Regex("""UnsatisfiedLinkError|couldn't find "lib[^"]+\.so"""").containsMatchIn(t) ->
                "has native code this phone can't load inside Blaze Phone"
            Regex("""(?i)(root|clone|virtual|emulator|xposed)[ _-]?(detect|check)""").containsMatchIn(t) ->
                "refuses to run in a virtual space (it checks for cloned or rooted environments)"
            Regex("""SecurityException.*permission""", RegexOption.IGNORE_CASE).containsMatchIn(t) ->
                "needs an Android permission Blaze Phone can't give it"
            else -> null
        }
    }
}
