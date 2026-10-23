package com.blaze.assistant.phone

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Blaze Phone (spike): the only class that talks to the vendored engine (phone-engine, NewBlackbox). Everything here is
 * reached lazily: Blaze's main process loads none of it (no class, no native library) until Blaze Phone is used.
 *
 * Engine processes (":black" server, ":p0".."p3" guests) are different: Android starts them with Blaze's Application, so
 * BlazeApp hands them to [attach] / [create] first thing and skips all of Blaze's own start-up there.
 */
object BlazePhoneEngine {
    const val USER = 0

    /** Engine configuration: no launcher splash, no daemon, no VPN; installs only go through BlazePhone.installGuest. */
    private fun config(ctx: Context) = object : top.niunaijun.blackbox.app.configuration.ClientConfiguration() {
        override fun getHostPackageName(): String = ctx.packageName
        override fun isEnableDaemonService(): Boolean = false
        override fun isEnableLauncherActivity(): Boolean = false
        override fun isUseVpnNetwork(): Boolean = false
        override fun requestInstallPackage(file: File?, userId: Int): Boolean = false
    }

    /** Engine process only: from BlazeApp.attachBaseContext. */
    fun attach(base: Context, processName: String) {
        val core = top.niunaijun.blackbox.BlackBoxCore.get()
        core.doAttachBaseContext(base, config(base))
        if (BlazePhonePolicy.proc(processName, base.packageName) == BlazePhonePolicy.Proc.GUEST) {
            core.addAppLifecycleCallback(object : top.niunaijun.blackbox.app.configuration.AppLifecycleCallback() {
                override fun afterApplicationOnCreate(packageName: String?, processName: String?, application: android.app.Application?, userId: Int) {
                    GuestAgent.guestPkg = packageName
                }
            })
            GuestAgent.start(base.packageName, processName)
        }
    }

    /** Engine process only: from BlazeApp.onCreate. */
    fun create() { top.niunaijun.blackbox.BlackBoxCore.get().doCreate() }

    @Volatile private var mainReady = false

    /** Main process, first use of Blaze Phone (off the main thread: the engine waits for its server process). */
    @Synchronized fun ensureMain(ctx: Context) {
        if (mainReady) return
        val core = top.niunaijun.blackbox.BlackBoxCore.get()
        core.doAttachBaseContext(ctx, config(ctx))
        core.doCreate()
        mainReady = true
    }

    val initialized get() = mainReady

    /** The real engine behind BlazePhone.Engine. */
    class Real(private val ctx: Context) : BlazePhone.Engine {
        private val core get() = top.niunaijun.blackbox.BlackBoxCore.get()
        override fun ensure() = ensureMain(ctx)
        override fun install(apk: File): BlazePhone.InstallOutcome {
            val r = core.installPackageAsUser(apk, USER)
            return BlazePhone.InstallOutcome(r.success, r.packageName, r.msg)
        }
        override fun uninstall(pkg: String) = core.uninstallPackageAsUser(pkg, USER)
        override fun isInstalled(pkg: String) = core.isInstalled(pkg, USER)
        override fun installed(): List<String> = core.getInstalledPackages(0, USER).map { it.packageName }
        override fun launch(pkg: String, displayId: Int): Boolean {
            val intent: Intent = top.niunaijun.blackbox.BlackBoxCore.getBPackageManager().getLaunchIntentForPackage(pkg, USER) ?: return false
            intent.putExtra(top.niunaijun.blackbox.core.system.am.GuestDisplays.EXTRA, displayId)
            top.niunaijun.blackbox.BlackBoxCore.getBActivityManager().startActivity(intent, USER)
            return true
        }
        override fun stop(pkg: String) = core.stopPackage(pkg, USER)
    }
}
