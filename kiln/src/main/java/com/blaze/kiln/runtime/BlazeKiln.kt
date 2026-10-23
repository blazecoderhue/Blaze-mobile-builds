package com.blaze.kiln.runtime

import android.content.Context
import android.content.Intent
import com.blaze.kiln.apk.ApkInspector
import com.blaze.kiln.apk.ApkReport
import com.blaze.kiln.apk.DeviceProfile
import com.blaze.kiln.diag.HarnessStep
import com.blaze.kiln.diag.RefusalClassifier
import com.blaze.kiln.diag.StepResult
import com.blaze.kiln.manifest.LaunchMode
import com.blaze.kiln.manifest.ManifestReader
import com.blaze.kiln.route.IdentityRewriter
import com.blaze.kiln.route.LaunchEnvelope
import com.blaze.kiln.route.LaunchToken
import com.blaze.kiln.route.ProcessKey
import com.blaze.kiln.route.ProcessTable
import com.blaze.kiln.route.SlotAllocator
import com.blaze.kiln.store.GuestPackage
import com.blaze.kiln.store.GuestRegistry
import com.blaze.kiln.store.StorageLayout
import java.io.File

// Kiln's front door (ENGINE_DESIGN.md 1.1). One instance per process: in Blaze's main process it is the
// Coordinator (registry, slot leases, install/uninstall); in a :gN process it also installs the interception and
// loads the guest. M1 scope: install an APK, launch its one activity. Not wired into Blaze's shipping UI.

class BlazeKiln private constructor(private val appContext: Context) {

    val layout = StorageLayout(File(appContext.filesDir, "kiln"))
    val registry = GuestRegistry(layout)
    private val allocators = HashMap<Int, SlotAllocator>()
    private val processes = ProcessTable(size = com.blaze.kiln.route.GuestProcesses.DECLARED)
    private val tokens: LaunchToken by lazy { LaunchToken(keyMaterial()) }
    private var intercepting = false

    val rewriter: IdentityRewriter by lazy {
        IdentityRewriter(appContext.packageName, { registry.packages() }) { pkg, cls ->
            allocators.values.firstNotNullOfOrNull { a -> a.active.firstOrNull { it.guestPackage == pkg && it.guestClass == cls }?.slot?.className }
        }
    }

    fun device(): DeviceProfile = DeviceProfile(
        sdkInt = android.os.Build.VERSION.SDK_INT,
        supportedAbis = android.os.Build.SUPPORTED_ABIS.toList(),
        pageSize = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE).toInt() }.getOrDefault(4096),
    )

    fun start(): BlazeKiln {
        KilnDiag.init(appContext)
        registry.load()
        return this
    }

    // ---- install / inspect ----

    fun inspect(apk: File): ApkReport = ApkInspector.inspect(apk, device())

    /** Copies the APK in and records it. Returns null with the reason logged when the APK cannot run here. */
    fun install(apk: File): GuestPackage? {
        val report = inspect(apk)
        val manifest = report.manifest
        if (manifest == null || report.milestone == null) {
            KilnLog.w("cannot run this APK: ${report.blockers.joinToString { it.why }}")
            return null
        }
        val rec = registry.install(apk, manifest, report.chosenAbi)
        GuestApp.forget(rec.packageName)
        GuestLoader(appContext, layout).extractNativeLibs(rec.packageName, report.chosenAbi)
        KilnLog.d("installed a guest package (milestone ${report.milestone})")
        return rec
    }

    /** What the test harness needs from an install: each main-process step, and the record when it worked. */
    data class InstallOutcome(
        val record: GuestPackage?,
        val report: ApkReport?,
        val steps: List<StepResult>,
    ) {
        val failedStep: HarnessStep? get() = steps.firstOrNull { it.ok == false }?.step
    }

    /**
     * Same as [install], step by step for the harness (ENGINE_M1_PHONE_TEST.md). An APK beyond M1 (services, more
     * activities, native code) is still installed so a tester can try it; only hard blockers stop it.
     */
    fun installWithReport(apk: File): InstallOutcome {
        val steps = ArrayList<StepResult>()
        fun done(rec: GuestPackage?, rep: ApkReport?) = InstallOutcome(rec, rep, steps.toList()).also { o ->
            for (s in o.steps) s.ok?.let { KilnDiag.step(s.step, it, s.detail) }
        }
        val report = try { inspect(apk) } catch (t: Throwable) {
            steps += StepResult(HarnessStep.READ_APK, false, "${t.javaClass.simpleName}: ${t.message}")
            return done(null, null)
        }
        val m = report.manifest
        if (m == null || report.milestone == null) {
            steps += StepResult(HarnessStep.READ_APK, false, "cannot run here: " + report.blockers.joinToString { it.why }.ifEmpty { "no manifest" })
            return done(null, report)
        }
        val beyond = if (report.concerns.isEmpty()) "" else "; beyond M1: " + report.concerns.joinToString { it.why }
        steps += StepResult(HarnessStep.READ_APK, true, "${m.packageName} ${m.versionName ?: ""} needs ${report.milestone}$beyond")
        val rec = try {
            registry.install(apk, m, report.chosenAbi)
        } catch (t: Throwable) {
            steps += StepResult(HarnessStep.INSTALL, false, "${t.javaClass.simpleName}: ${t.message}")
            return done(null, report)
        }
        GuestApp.forget(rec.packageName)
        val apkFile = layout.apk(rec.packageName)
        if (apkFile.canWrite()) {
            steps += StepResult(HarnessStep.INSTALL, false, "APK copy is still writable (Android 14 refuses to load it)")
            return done(rec, report)
        }
        steps += StepResult(HarnessStep.INSTALL, true, "read-only copy, ${apkFile.length()} bytes")
        try {
            val abi = GuestLoader(appContext, layout).extractNativeLibs(rec.packageName, report.chosenAbi)
            steps += StepResult(HarnessStep.NATIVE_LIBS, true, abi?.let { "extracted $it" } ?: "none (Java/Kotlin only)")
        } catch (t: Throwable) {
            steps += StepResult(HarnessStep.NATIVE_LIBS, false, "${t.javaClass.simpleName}: ${t.message}")
            return done(rec, report)
        }
        return done(rec, report)
    }

    /** Kills Kiln's guest processes (:gN) of this app and forgets their slot leases. Returns how many were running. */
    fun stopGuestProcesses(): Int {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return 0
        var n = 0
        am.runningAppProcesses?.forEach { p ->
            if (p.processName.startsWith(appContext.packageName + ":g") && p.pid != android.os.Process.myPid()) {
                android.os.Process.killProcess(p.pid)
                n++
            }
        }
        synchronized(this) {
            allocators.clear()
            registry.packages().forEach { processes.releasePackage(it) }
        }
        return n
    }

    /** Releases [pkg]'s old slot leases, then builds a fresh launch (harness "Launch again"). */
    fun relaunchIntent(pkg: String, profile: Int = 0): Intent? {
        synchronized(this) { allocators.values.forEach { it.releaseAll(pkg) } }
        return launchIntent(pkg, profile)
    }

    fun uninstall(pkg: String): Boolean {
        allocators.values.forEach { it.releaseAll(pkg) }
        processes.releasePackage(pkg)
        GuestApp.forget(pkg)
        return registry.uninstall(pkg)
    }

    fun installed(): List<GuestPackage> = registry.all()

    // ---- launching ----

    /** Intent that starts a guest's launcher activity in a slot; null when it is not installed or has no launcher. */
    fun launchIntent(pkg: String, profile: Int = 0): Intent? {
        val rec = registry.get(pkg) ?: return null
        val cls = rec.launcherActivity ?: return null
        return slotIntentFor(pkg, cls, profile, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
    }

    fun slotIntentFor(pkg: String, cls: String, profile: Int, original: Intent): Intent? {
        val rec = registry.get(pkg) ?: return null
        val key = ProcessKey(pkg, pkg)
        val assignment = processes.assign(key) ?: run { KilnLog.w("no guest process free"); return null }
        assignment.evicted?.let { evicted ->
            allocators[assignment.index]?.releaseAll(evicted.guestPackage)
        }
        val allocator = allocators.getOrPut(assignment.index) { SlotAllocator(assignment.index) }
        val mode = launchModeOf(pkg, cls)
        val lease = allocator.leaseActivity(pkg, cls, mode, taskKey = pkg)
        val env = LaunchEnvelope(
            guestPackage = pkg,
            guestClass = cls,
            profile = profile,
            slotClass = lease.slot.className,
            token = tokens.issue(pkg, cls, profile),
            taskKey = pkg,
        )
        val intent = Intent(original)
        intent.setClassName(appContext.packageName, lease.slot.className)
        intent.`package` = null
        intent.putExtra(LaunchEnvelope.K_ORIGINAL_INTENT, Intent(original).setClassName(pkg, cls))
        for ((k, v) in env.toMap()) intent.putExtra(k, v)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        KilnLog.d("guest launch prepared in process ${assignment.index} (${rec.versionName})")
        return intent
    }

    private fun launchModeOf(pkg: String, cls: String): LaunchMode {
        val apk = layout.apk(pkg)
        if (!apk.isFile) return LaunchMode.STANDARD
        return try {
            java.util.zip.ZipFile(apk).use { zip ->
                val e = zip.getEntry("AndroidManifest.xml") ?: return LaunchMode.STANDARD
                val m = zip.getInputStream(e).use { ManifestReader.read(it.readBytes()) }
                m.activity(cls)?.launchMode ?: LaunchMode.STANDARD
            }
        } catch (t: Throwable) {
            KilnLog.w("manifest re-read failed; assuming standard launch mode")
            LaunchMode.STANDARD
        }
    }

    // ---- inside a guest process ----

    /** Installs interception in this process. Called from a slot component before the guest is created. */
    @Synchronized
    fun installHooks(): Boolean {
        if (intercepting) return true
        KilnDiag.init(appContext)
        KilnDiag.watchCrashes()
        val unsealed = HiddenApi.unseal()
        val loader = GuestLoader(appContext, layout)
        val swap = LaunchSwap(
            onGuestLaunch = { pkg, profile, abi ->
                if (GuestApp.loaded(pkg) == null) {
                    val loaded = try { loader.load(pkg, profile, abi) } catch (t: Throwable) {
                        KilnDiag.step(HarnessStep.GUEST_LOADED, false, "${t.javaClass.simpleName}: ${t.message}")
                        null
                    }
                    if (loaded != null) {
                        KilnDiag.step(HarnessStep.GUEST_LOADED, true,
                            if (loaded.viaFramework) "framework LoadedApk" else "fallback class loader + resources")
                    } else if (GuestApp.loaded(pkg) == null) {
                        KilnDiag.step(HarnessStep.GUEST_LOADED, false, "could not read the guest APK (getPackageArchiveInfo / loader)")
                    }
                }
            },
            // The guest process may have started before the latest install: re-read the registry once if needed.
            abiOf = { pkg -> (registry.get(pkg) ?: run { registry.load(); registry.get(pkg) })?.abi },
            tokens = tokens,
        )
        val interceptor = LaunchInterceptor(rewriter) { intent ->
            val cn = intent.component ?: return@LaunchInterceptor null
            if (!rewriter.isGuest(cn.packageName)) return@LaunchInterceptor null
            val slotIntent = slotIntentFor(cn.packageName, cn.className, 0, intent) ?: return@LaunchInterceptor null
            LaunchInterceptor.envelopeOf(slotIntent)
        }
        val pmOk = installPackageManagerShim(loader)
        val swapOk = swap.install()
        val icOk = interceptor.install()
        val cached = ServiceCacheShim.install(RefusalClassifier.CACHE_SHIMMED, rewriter, AndroidArgAdapters.all())
        val ok = swapOk && icOk
        if (!pmOk) KilnLog.w("package-manager shim not installed; guests cannot query themselves")
        intercepting = ok
        if (!ok) KilnLog.w("interception not fully installed on API ${android.os.Build.VERSION.SDK_INT}")
        val missing = RefusalClassifier.CACHE_SHIMMED - cached.toSet()
        KilnDiag.step(HarnessStep.GUEST_HOOKS, ok,
            "API ${android.os.Build.VERSION.SDK_INT}; hidden API ${if (unsealed) "open" else "CLOSED"}; launch swap ${if (swapOk) "ok" else "FAILED"}; " +
                "activity manager shim ${if (icOk) "ok" else "FAILED"}; package shim ${if (pmOk) "ok" else "FAILED"}; " +
                "services wrapped: ${cached.joinToString(",").ifEmpty { "none" }}" + (if (missing.isNotEmpty()) "; not wrapped: ${missing.joinToString(",")}" else ""))
        return ok
    }

    /** Replaces ActivityThread.sPackageManager (the IPackageManager every new ContextImpl uses) with our shim. */
    private fun installPackageManagerShim(loader: GuestLoader): Boolean {
        val atClass = HiddenApi.classOrNull("android.app.ActivityThread") ?: return false
        // getPackageManager() fills the static on first use.
        val real = HiddenApi.call(HiddenApi.method(atClass, "getPackageManager"), null) ?: return false
        if (java.lang.reflect.Proxy.isProxyClass(real.javaClass)) return true
        val shim = PackageManagerShim(registry, appContext.packageName) { pkg ->
            if (!rewriter.isGuest(pkg)) null
            else GuestApp.loaded(pkg)?.packageInfo
                ?: registry.get(pkg)?.let { GuestApp.load(appContext, layout, pkg, 0, it.abi)?.packageInfo }
        }
        val proxy = ServiceShim.wrap("package", real, rewriter, AndroidArgAdapters.all(), shim.handlers())
        return HiddenApi.set(HiddenApi.field(atClass, "sPackageManager"), null, proxy)
    }

    /** Per-install HMAC key for launch tokens, kept in Blaze's own private storage. */
    private fun keyMaterial(): ByteArray {
        val f = File(layout.root, "token.key")
        f.parentFile?.mkdirs()
        if (f.isFile && f.length() >= 32) return f.readBytes()
        val k = LaunchToken.newKey()
        val tmp = File(f.parentFile, "token.key.tmp")
        tmp.writeBytes(k)
        tmp.renameTo(f)
        f.setReadable(true, true)
        return k
    }

    companion object {
        @Volatile private var instance: BlazeKiln? = null

        @JvmStatic
        fun get(context: Context): BlazeKiln =
            instance ?: synchronized(this) {
                instance ?: BlazeKiln(context.applicationContext).start().also { instance = it }
            }

        /** True when this process is one of the guest processes (:gN). */
        @JvmStatic
        fun isGuestProcess(context: Context): Boolean = processName(context)?.contains(":g") == true

        @JvmStatic
        fun processName(context: Context): String? =
            if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            else (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
                ?.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
    }
}

/** Answers a guest's questions about itself and hides Blaze from it (ENGINE_DESIGN.md 1.4). M1: self-queries only. */
class PackageManagerShim(
    private val registry: GuestRegistry,
    private val hostPackage: String,
    private val guestApp: (String) -> android.content.pm.PackageInfo?,
) {
    fun handlers(): Map<String, ShimHandler> = mapOf(
        "getPackageInfo" to ShimHandler { call ->
            val pkg = call.args.firstOrNull() as? String ?: return@ShimHandler PassThrough
            guestApp(pkg) ?: PassThrough
        },
        "getApplicationInfo" to ShimHandler { call ->
            val pkg = call.args.firstOrNull() as? String ?: return@ShimHandler PassThrough
            guestApp(pkg)?.applicationInfo ?: PassThrough
        },
        "getInstalledPackages" to ShimHandler { PassThrough },
    )

    /** Packages a guest is allowed to see: itself and the device's real apps, never Blaze. */
    fun visible(from: String, candidates: List<String>): List<String> =
        candidates.filter { it != hostPackage || it == from }
}
