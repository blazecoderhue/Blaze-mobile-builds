package com.blaze.kiln.runtime

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.blaze.kiln.store.StorageLayout
import java.io.File

// Builds the guest's ApplicationInfo / ActivityInfo / PackageInfo the framework will load it from
// (ENGINE_DESIGN.md 1.2). Uses the public PackageManager.getPackageArchiveInfo for an uninstalled APK, then
// rewrites code, library and data paths into Blaze's storage and the uid to Blaze's own: AOSP's
// ActivityThread.getPackageInfo refuses to load code for an ApplicationInfo whose uid is not the process's app uid.

class GuestApp private constructor(
    val packageName: String,
    val packageInfo: PackageInfo,
    val appInfo: ApplicationInfo,
) {
    fun activity(className: String): ActivityInfo? = packageInfo.activities?.firstOrNull { it.name == className }

    companion object {
        private val cache = HashMap<String, GuestApp>()

        @Suppress("DEPRECATION")
        @Synchronized
        fun load(context: Context, layout: StorageLayout, pkg: String, profile: Int, abi: String?): GuestApp? {
            cache[pkg]?.let { return it }
            val apk = layout.apk(pkg)
            if (!apk.isFile) return null
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or
                PackageManager.GET_RECEIVERS or PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS or
                PackageManager.GET_CONFIGURATIONS or
                (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            val pi = context.packageManager.getPackageArchiveInfo(apk.path, flags) ?: return null
            if (pi.packageName != pkg) return null
            val ai = pi.applicationInfo ?: return null
            rewrite(ai, layout, pkg, profile, abi, context)
            pi.activities?.forEach { it.applicationInfo = ai }
            pi.services?.forEach { it.applicationInfo = ai }
            pi.providers?.forEach { it.applicationInfo = ai }
            pi.receivers?.forEach { it.applicationInfo = ai }
            return GuestApp(pkg, pi, ai).also { cache[pkg] = it }
        }

        @Synchronized fun forget(pkg: String) { cache.remove(pkg) }

        @Synchronized fun loaded(pkg: String): GuestApp? = cache[pkg]

        private fun rewrite(ai: ApplicationInfo, layout: StorageLayout, pkg: String, profile: Int, abi: String?, host: Context) {
            val apk = layout.apk(pkg).path
            ai.sourceDir = apk
            ai.publicSourceDir = apk
            ai.splitSourceDirs = null
            ai.splitPublicSourceDirs = null
            val data = layout.dataDir(profile, pkg)
            data.mkdirs()
            ai.dataDir = data.path
            // Hidden fields used by ContextImpl for credential/device-protected storage on API 24+.
            HiddenApi.set(HiddenApi.field(ApplicationInfo::class.java, "credentialProtectedDataDir"), ai, data.path)
            HiddenApi.set(HiddenApi.field(ApplicationInfo::class.java, "deviceProtectedDataDir"), ai, data.path)
            HiddenApi.set(HiddenApi.field(ApplicationInfo::class.java, "scanSourceDir"), ai, layout.appDir(pkg).path)
            HiddenApi.set(HiddenApi.field(ApplicationInfo::class.java, "scanPublicSourceDir"), ai, layout.appDir(pkg).path)
            ai.nativeLibraryDir = if (abi != null) layout.libDir(pkg, abi).path else File(layout.appDir(pkg), "lib").path
            HiddenApi.set(HiddenApi.field(ApplicationInfo::class.java, "primaryCpuAbi"), ai, abi)
            ai.uid = Process.myUid()
            ai.flags = ai.flags or ApplicationInfo.FLAG_INSTALLED
            ai.enabled = true
            // Keep the guest's own targetSdkVersion: behaviour changes are keyed on it.
            if (ai.processName.isNullOrEmpty()) ai.processName = pkg
            // The guest is loaded as itself (packageName = guest) inside Blaze's uid; never as Blaze.
            check(ai.packageName == pkg)
            check(host.applicationInfo.uid == ai.uid)
        }
    }
}
