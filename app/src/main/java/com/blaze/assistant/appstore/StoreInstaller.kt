package com.blaze.assistant.appstore

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 0.45.19 Store: Android's PackageInstaller (session API) for App Store apps on the user's own phone. Every session
 * asks for user action (Android 12+ USER_ACTION_REQUIRED), so the user confirms each install in Android's own dialog.
 * No silent install, no Shizuku/ADB/root. [StoreInstallReceiver] gets each session's status; the waiting job is found
 * by session id in [StoreSessions].
 */
object StoreInstaller {
    const val ACTION = "com.blaze.assistant.appstore.INSTALL_STATUS"
    const val EXTRA_PKG = "store_pkg"

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun permissionIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun requestPermission(ctx: Context) {
        try { ctx.startActivity(permissionIntent(ctx)) }
        catch (_: Exception) { try { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } }
    }

    /**
     * Writes [apk] into a new session, commits it, and waits for the final status. [onUserAction] fires when Android
     * asks the user (the confirm screen is started, and kept in [StoreSessions.pendingConfirm] so the Store can reopen
     * it). The session is abandoned on timeout or error, so nothing is left behind.
     */
    suspend fun install(ctx: Context, apk: File, info: ApkInfo, timeoutMs: Long, onUserAction: () -> Unit): InstallOutcome {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(info.pkg)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        val id = withContext(Dispatchers.IO) { pi.createSession(params) }
        val waiter = StoreSessions.open(id, info.pkg, onUserAction)
        try {
            withContext(Dispatchers.IO) {
                pi.openSession(id).use { s ->
                    s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out, 64 * 1024) }; s.fsync(out) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val intent = Intent(ctx, StoreInstallReceiver::class.java).setAction(ACTION).setPackage(ctx.packageName).putExtra(EXTRA_PKG, info.pkg)
                    // One request code per session: two installs never overwrite each other's PendingIntent.
                    s.commit(PendingIntent.getBroadcast(ctx, REQUEST_BASE + (id and 0xFFFF), intent, flags).intentSender)
                }
            }
            val out = withTimeoutOrNull(timeoutMs) { waiter.await() }
            if (out == null) { abandon(ctx, id); return InstallOutcome.TimedOut }
            return out
        } catch (e: Exception) {
            abandon(ctx, id)
            if (e is kotlinx.coroutines.CancellationException) throw e
            return InstallOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            StoreSessions.close(id)
        }
    }

    private fun abandon(ctx: Context, id: Int) { try { ctx.packageManager.packageInstaller.abandonSession(id) } catch (_: Exception) { } }

    /** The receiver's work: Android's status to an outcome for the waiting job. */
    fun onStatus(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (!StoreSessions.userAction(id, intent.getStringExtra(EXTRA_PKG), confirm)) return
            confirm?.let { c -> try { ctx.startActivity(Intent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } }
            return
        }
        StoreSessions.finish(id, StoreSessions.outcome(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
    }

    private const val REQUEST_BASE = 0x5100_0000
}

/** Open sessions by id (thread-safe: the receiver runs on the main thread, jobs on IO). */
object StoreSessions {
    private class Open(val pkg: String, val result: CompletableDeferred<InstallOutcome>, val onUserAction: () -> Unit)
    private val open = ConcurrentHashMap<Int, Open>()
    /** Android's confirm screen per package, so the Store can reopen it if it was dismissed or blocked from starting. */
    val pendingConfirm = ConcurrentHashMap<String, Intent>()

    fun open(id: Int, pkg: String, onUserAction: () -> Unit): CompletableDeferred<InstallOutcome> =
        CompletableDeferred<InstallOutcome>().also { open[id] = Open(pkg, it, onUserAction) }

    /** False when no job waits on [id] (a stale broadcast): then no screen is started. */
    fun userAction(id: Int, pkg: String?, confirm: Intent?): Boolean {
        val o = open[id] ?: return false
        if (confirm != null) pendingConfirm[pkg ?: o.pkg] = confirm
        try { o.onUserAction() } catch (_: Exception) { }
        return true
    }

    fun finish(id: Int, out: InstallOutcome) {
        val o = open.remove(id) ?: return
        pendingConfirm.remove(o.pkg)
        o.result.complete(out)
    }

    fun close(id: Int) { open.remove(id)?.let { pendingConfirm.remove(it.pkg); it.result.cancel() } }

    fun isOpen(id: Int) = open.containsKey(id)

    /** PackageInstaller status codes → outcome (StoreInstallTest). */
    fun outcome(status: Int, message: String?): InstallOutcome = when (status) {
        PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Installed
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.Cancelled
        PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallOutcome.Conflict("it conflicts with the installed app (usually a different signing key)")
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallOutcome.Conflict("it isn't compatible with this phone")
        PackageInstaller.STATUS_FAILURE_STORAGE -> InstallOutcome.Failed("not enough free space")
        PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallOutcome.Failed("the install was blocked on this phone")
        PackageInstaller.STATUS_FAILURE_INVALID -> InstallOutcome.Failed("the file is invalid")
        else -> InstallOutcome.Failed(message?.take(160)?.ifBlank { null } ?: "status $status")
    }
}

class StoreInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StoreInstaller.ACTION) return
        StoreInstaller.onStatus(context, intent)
    }
}
