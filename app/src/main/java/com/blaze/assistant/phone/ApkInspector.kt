package com.blaze.assistant.phone

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.util.zip.ZipFile

/** Blaze Phone (spike): what [GuestCompat] needs from an APK file, read without installing it anywhere. */
object ApkInspector {
    @Suppress("DEPRECATION")
    fun read(ctx: Context, apk: File): GuestCompat.Apk? {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_ACTIVITIES
        val pi = pm.getPackageArchiveInfo(apk.path, flags) ?: return null
        val ai = pi.applicationInfo ?: return null
        ai.sourceDir = apk.path; ai.publicSourceDir = apk.path
        val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Exception) { pi.packageName }
        val meta = ai.metaData
        val abis = try {
            ZipFile(apk).use { z -> z.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                .mapNotNull { it.split('/').getOrNull(1) }.toSet() }
        } catch (_: Exception) { emptySet() }
        // Intent filters of an APK that isn't installed aren't readable here; "has activities" stands in for "has a screen".
        // A guest with no launcher activity is caught at launch (engine has no launch intent).
        return GuestCompat.Apk(
            pkg = pi.packageName,
            label = label,
            minSdk = if (android.os.Build.VERSION.SDK_INT >= 24) ai.minSdkVersion else 1,
            permissions = pi.requestedPermissions?.toSet().orEmpty(),
            metaData = meta?.keySet().orEmpty(),
            servicePermissions = pi.services?.mapNotNull { it.permission }?.toSet().orEmpty(),
            receiverPermissions = pi.receivers?.mapNotNull { it.permission }?.toSet().orEmpty(),
            nativeAbis = abis,
            hasLauncher = !pi.activities.isNullOrEmpty(),
            splitsRequired = meta?.getBoolean("com.android.vending.splits.required", false) == true,
        )
    }
}
