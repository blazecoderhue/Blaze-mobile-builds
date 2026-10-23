package com.blaze.kiln.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import com.blaze.kiln.apk.AbiSelector
import com.blaze.kiln.store.StorageLayout
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipFile

// Loads a guest's code and resources (ENGINE_DESIGN.md 1.2).
//
// Preferred path: hand the rewritten ApplicationInfo to ActivityThread.getPackageInfo, so AOSP builds the LoadedApk
// (class loader, resources, Application) exactly as it does for an installed app. Fallback path (used when that
// non-SDK call is unavailable on a release): our own DexClassLoader + Resources built from the APK, which is enough
// for M1 but gives up AOSP's own Application bootstrap.

class GuestLoader(private val context: Context, private val layout: StorageLayout) {

    data class Loaded(
        val classLoader: ClassLoader,
        val resources: Resources,
        val appInfo: ApplicationInfo,
        /** True when AOSP's own LoadedApk built this (the path we want on a device). */
        val viaFramework: Boolean,
    )

    fun load(pkg: String, profile: Int, abi: String?): Loaded? {
        val guest = GuestApp.load(context, layout, pkg, profile, abi) ?: return null
        val ai = guest.appInfo
        extractNativeLibs(pkg, abi)
        frameworkLoadedApk(ai)?.let { loadedApk ->
            val cl = HiddenApi.call(HiddenApi.method(loadedApk.javaClass, "getClassLoader"), loadedApk) as? ClassLoader
            val res = HiddenApi.call(HiddenApi.method(loadedApk.javaClass, "getResources"), loadedApk) as? Resources
            if (cl != null && res != null) return Loaded(cl, res, ai, true)
            KilnLog.w("LoadedApk gave no class loader/resources; using the fallback loader")
        }
        val cl = fallbackClassLoader(ai) ?: return null
        val res = resourcesFor(ai) ?: return null
        return Loaded(cl, res, ai, false)
    }

    /** AOSP: ActivityThread.getPackageInfo(ApplicationInfo, CompatibilityInfo, int flags). */
    private fun frameworkLoadedApk(ai: ApplicationInfo): Any? {
        val at = ActivityThreadRef.current() ?: return null
        val compatInfoClass = HiddenApi.classOrNull("android.content.res.CompatibilityInfo") ?: return null
        val defaultCompat = HiddenApi.get(HiddenApi.field(compatInfoClass, "DEFAULT_COMPATIBILITY_INFO"), null)
        val m = HiddenApi.method(at.javaClass, "getPackageInfo", ApplicationInfo::class.java, compatInfoClass, Int::class.javaPrimitiveType!!)
            ?: return null
        // flags 1 = Context.CONTEXT_INCLUDE_CODE (public constant, named here as AOSP passes it).
        return HiddenApi.call(m, at, ai, defaultCompat, Context.CONTEXT_INCLUDE_CODE)
    }

    private fun fallbackClassLoader(ai: ApplicationInfo): ClassLoader? = try {
        // Parent = the boot class loader, so guest classes never see Blaze's (no AndroidX clashes).
        DexClassLoader(ai.sourceDir, File(ai.dataDir, "code_cache").apply { mkdirs() }.path, ai.nativeLibraryDir, String::class.java.classLoader)
    } catch (t: Throwable) {
        KilnLog.e("guest class loader failed", t); null
    }

    /** Public API: builds an AssetManager over an uninstalled APK through its ApplicationInfo.sourceDir. */
    fun resourcesFor(ai: ApplicationInfo): Resources? = try {
        context.packageManager.getResourcesForApplication(ai)
    } catch (t: Throwable) {
        KilnLog.w("getResourcesForApplication failed (${t.javaClass.simpleName}); building AssetManager directly")
        assetManagerResources(ai.sourceDir)
    }

    private fun assetManagerResources(apkPath: String): Resources? = try {
        val am = AssetManager::class.java.newInstance()
        val add = HiddenApi.method(AssetManager::class.java, "addAssetPath", String::class.java)
        val cookie = HiddenApi.call(add, am, apkPath) as? Int ?: 0
        if (cookie == 0) null
        else Resources(am, context.resources.displayMetrics, context.resources.configuration)
    } catch (t: Throwable) {
        KilnLog.e("AssetManager path failed", t); null
    }

    /**
     * Extracts the lib/<abi>/ .so files into the guest's own lib dir so System.loadLibrary resolves through
     * ApplicationInfo.nativeLibraryDir. Returns the chosen ABI, or null when the APK has no native code.
     */
    fun extractNativeLibs(pkg: String, preferredAbi: String? = null): String? {
        val apk = layout.apk(pkg)
        if (!apk.isFile) return null
        ZipFile(apk).use { zip ->
            val byAbi = LinkedHashMap<String, MutableList<String>>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val n = entries.nextElement().name
                val m = Regex("^lib/([^/]+)/([^/]+\\.so)$").matchEntire(n) ?: continue
                byAbi.getOrPut(m.groupValues[1]) { mutableListOf() }.add(m.groupValues[2])
            }
            if (byAbi.isEmpty()) return null
            val abi = preferredAbi?.takeIf { it in byAbi }
                ?: AbiSelector.choose(byAbi.keys, Build.SUPPORTED_ABIS.toList())
                ?: return null
            val dir = layout.libDir(pkg, abi)
            dir.mkdirs()
            for (lib in byAbi.getValue(abi)) {
                val out = File(dir, lib)
                val entry = zip.getEntry("lib/$abi/$lib") ?: continue
                if (out.isFile && out.length() == entry.size) continue
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            return abi
        }
    }
}

/** android.app.ActivityThread, the per-process framework object Kiln reaches into. */
object ActivityThreadRef {
    private val cls by lazy { HiddenApi.classOrNull("android.app.ActivityThread") }

    fun current(): Any? = HiddenApi.call(HiddenApi.method(cls, "currentActivityThread"), null)

    /** The process's main Handler (ActivityThread.mH), where lifecycle messages arrive. */
    fun handler(): android.os.Handler? = HiddenApi.get(HiddenApi.field(cls, "mH"), current()) as? android.os.Handler
}
