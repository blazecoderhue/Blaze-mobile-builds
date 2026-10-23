package com.blaze.store

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blaze.store.data.*
import com.blaze.store.platform.AppTracker
import com.blaze.store.platform.DlStatus
import com.blaze.store.platform.Downloads
import com.blaze.store.platform.VtGuard
import com.blaze.store.ui.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Background updates that run with Blaze Store closed, and across reboots (persisted job).
 * Each pass: refresh the catalog, read installed versions from Android itself, download what is newer, check the APK is the
 * same package, then install it over the existing app. Root and Shizuku install anything; without them Android 12+ installs
 * updates silently for apps Blaze Store installed. Whatever can't finish here is logged, kept ready to install, and reported on the next start.
 */
/** Blaze Store's own update channel: the "store" entry of Blaze's versions.json (no GitHub releases). */
data class StoreRelease(val version: String, val url: String, val sha256: String? = null, val notes: String? = null)

object SelfUpdate {
    const val VERSIONS_URL = "https://blazeinchue.pages.dev/versions.json"
    const val KEY = "store"

    /** {"store": {"version": "0.1.0", "url": "https://blazeinchue.pages.dev/dl/store/v0.1.0", "sha256": "...", "notes": "..."}} */
    fun parse(text: String): StoreRelease? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject[KEY] as? JsonObject ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
        val v = s("version") ?: return null; val u = s("url")?.takeIf { it.startsWith("https://") } ?: return null
        StoreRelease(v, u, s("sha256")?.lowercase(), s("notes"))
    }.getOrNull()

    suspend fun fetch(): StoreRelease? = runCatching { parse(Net.retry("$VERSIONS_URL?t=${System.currentTimeMillis()}", 2, noStore = true)) }.getOrNull()

    /** versions.json wins over whatever the catalog config says about the store's own version and download. */
    fun apply(c: StoreConfig, r: StoreRelease?): StoreConfig =
        if (r == null) c else c.copy(latestStoreVersion = r.version, storeDownloadUrl = r.url, storeSha256 = r.sha256 ?: c.storeSha256)
}

object AutoUpdate {
    private const val JOB_ID = 7340
    private const val CHANNEL = "blaze_updates"
    /** Set by MainActivity: while the store is on screen it handles updates itself, so the job steps aside. */
    @Volatile var uiVisible = false

    @Suppress("DEPRECATION")
    fun schedule(ctx: Context, s: Settings) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!s.autoUpdateEnabled) { js.cancel(JOB_ID); return }
        val net = if (s.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
        // Rescheduling an identical job restarts its clock, so only touch it when it is missing or the network rule changed
        if (js.getPendingJob(JOB_ID)?.networkType == net) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, AutoUpdateJob::class.java))
            .setPeriodic(6 * 3_600_000L, 3_600_000L).setRequiredNetworkType(net).setPersisted(true)
            .apply { if (Build.VERSION.SDK_INT >= 26) setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true) }
            .build())
    }

    suspend fun run(ctx: Context) {
        Store.init(ctx); val s0 = Store.settings.value
        if (!s0.autoUpdateEnabled || uiVisible) return
        Downloads.init(ctx, s0.forcedStoreUpdate?.downloadFileName)
        val tracker = AppTracker(ctx).apply { root = s0.rootMode }
        val apps = catalog(ctx, s0); if (apps.isEmpty()) return
        val now = System.currentTimeMillis()
        // Trust Android, not the last sync: the user may have updated or removed apps since Blaze Store last ran
        val live = s0.installedVersions.mapNotNull { (id, _) ->
            val pkg = s0.resolvedPackageNames[id] ?: apps.firstOrNull { it.id == id }?.packageName ?: return@mapNotNull null
            tracker.appInfo(pkg).takeIf { it.installed }?.let { id to it.version }
        }.toMap()
        val todo = availableUpdates(apps, s0.copy(installedVersions = live), now)
            .filter { it.installsHere() && it.id !in Store.data.value.activeDownloads && (s0.resolvedPackageNames[it.id] ?: it.packageName) != ctx.packageName }
        val log = mutableListOf<AutoResult>()
        for (app in todo) {
            if (uiVisible) break
            val r = update(ctx, tracker, app, s0)
            if (r != null) log += r
        }
        if (log.isNotEmpty()) Store.settings.update { it.copy(autoLog = (it.autoLog + log).takeLast(30)) }
        Store.settings.save(); Store.data.save()
        notifySummary(ctx, log)
    }

    /** Catalog for this pass: fresh when the network allows, otherwise the last one the store cached. */
    internal suspend fun catalog(ctx: Context, s: Settings): List<AppItem> = withContext(Dispatchers.IO) {
        val repo = Repository(ctx)
        val fresh = runCatching {
            val r = repo.loadCatalog(s, false); val m = CatalogProcessor.parseMirror(r.mirror)
            if (r.failed || m.isEmpty()) null else CatalogProcessor.process(r.rawApps, m) + Store.data.value.importedApps.map { CatalogProcessor.processItem(it, m) }
        }.getOrNull()
        fresh?.takeIf { it.isNotEmpty() } ?: (repo.cached()?.apps.orEmpty() + Store.data.value.importedApps)
    }

    /** One app: download (or reuse a finished download), verify, install. Null = nothing worth reporting (download-only mode). */
    private suspend fun update(ctx: Context, tracker: AppTracker, app: AppItem, s: Settings): AutoResult? {
        val pkg = s.resolvedPackageNames[app.id] ?: app.packageName.orEmpty()
        val version = downloadVersionMeta(app, null, s.streamOf(app.id)).first
        fun result(ok: Boolean, error: String? = null) = AutoResult(app.id, app.name, version, ok, error, System.currentTimeMillis())
        val url = sanitizeDownloadUrl(app.variants.firstOrNull()?.url ?: app.downloadUrl)
        if (url == "#" || !url.startsWith("http")) return result(false, "No direct APK download for this app.")
        val name = apkFileName(app, version)
        val f = Downloads.file(name) ?: return result(false, "Invalid file name.")
        if (!f.exists() || f.length() == 0L) {
            runCatching { Downloads.start(url, name) }.onFailure { return result(false, "Download could not start.") }
            val st = withTimeoutOrNull(20 * 60_000L) { Downloads.state.first { it[name]?.status != DlStatus.RUNNING } }?.get(name)?.status
            Downloads.forget(name)
            if (st != DlStatus.SUCCESSFUL || Downloads.status(name).status != DlStatus.SUCCESSFUL) return result(false, "Download failed. Blaze Store retries on its next pass.")
        }
        // Never install something other than what is installed: a wrong or tampered file stops here
        val actual = withContext(Dispatchers.IO) { if (tracker.isValidApk(f)) tracker.archivePackage(f) else null }
        if (actual == null) { f.delete(); return result(false, "The downloaded file is not a valid APK.") }
        if (pkg.isNotEmpty() && actual != pkg) { f.delete(); return result(false, "The download is a different app ($actual), so it was not installed.") }
        val now = System.currentTimeMillis()
        Store.data.update { if (app.id in it.readyToInstall) it else it.completeDownload(app.id, name, now) }
        if (!s.autoInstall) { notifyReady(ctx, app, name); return null }
        // VirusTotal before every install; without a verdict the update waits in the notification for the user
        val vt = VtGuard.check(f, s.virusTotalApiKey)
        if (!vt.allowsInstall) { notifyReady(ctx, app, name, vt.message); return result(false, vt.message) }

        val error = install(ctx, tracker, s, f, actual)
        if (error != null) { notifyReady(ctx, app, name, error); return result(false, error) }
        val ver = tracker.appInfo(actual).version
        Store.settings.update { it.copy(installedVersions = it.installedVersions + (app.id to ver), lastRemoteVersions = it.lastRemoteVersions + (app.id to ver),
            packageOwners = it.packageOwners + (normalizePackageName(actual) to app.id)) }
        Store.data.update { it.recordInstalled(app.id, ver, null, now).copy(readyToInstall = it.readyToInstall - app.id) }
        f.delete()
        return result(true)
    }

    /** Null on success, else why it failed, worded for the user. */
    private suspend fun install(ctx: Context, tracker: AppTracker, s: Settings, f: File, pkg: String): String? {
        if (s.rootMode || s.useShizuku) return runCatching { tracker.installShizuku(f.name, s.updateLock) }.exceptionOrNull()?.let { reason(it.message) }
        if (Build.VERSION.SDK_INT < 31) return "Android ${Build.VERSION.RELEASE} needs you to confirm installs. Root or Shizuku make them silent."
        if (!tracker.canRequestInstalls()) return "Allow Blaze Store to install apps, then updates can install on their own."
        val (status, msg) = sessionInstall(ctx, f, pkg)
        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> null
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "Android asked to confirm this one. That happens for apps another store installed, or apps built for an old Android version."
            else -> reason(msg)
        }
    }

    private fun reason(m: String?) = when {
        m == null -> "Install failed."
        "UPDATE_INCOMPATIBLE" in m || "signatures do not match" in m.lowercase() -> "This update is signed by a different developer than the installed app. Uninstall it first to switch."
        "INSUFFICIENT_STORAGE" in m -> "Not enough storage to install."
        "VERSION_DOWNGRADE" in m -> "The installed version is newer than the catalog."
        "Shizuku" in m -> "Shizuku is not running, so the silent install could not happen."
        else -> m.take(160)
    }

    private val sessions = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, String?>>>()

    /** PackageInstaller session that asks for no user action. Android allows it for apps Blaze Store installed (Android 12+). */
    private suspend fun sessionInstall(ctx: Context, f: File, pkg: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg); setSize(f.length()); setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params); val done = CompletableDeferred<Pair<Int, String?>>(); sessions[id] = done
        try {
            pi.openSession(id).use { session ->
                session.openWrite("base.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                val i = Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName)
                session.commit(PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
            }
            withTimeoutOrNull(3 * 60_000L) { done.await() } ?: (PackageInstaller.STATUS_FAILURE to "Android did not answer in time. Blaze Store retries on its next pass.")
        } catch (e: Exception) {
            runCatching { pi.abandonSession(id) }; PackageInstaller.STATUS_FAILURE to e.message
        } finally { sessions -= id }
    }

    /** Session results. A pending confirmation is abandoned: the file stays ready, and the user installs it with one tap from the notification. */
    fun onInstallResult(ctx: Context, i: Intent) {
        val id = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) runCatching { ctx.packageManager.packageInstaller.abandonSession(id) }
        sessions[id]?.complete(status to i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    private fun channel(ctx: Context) = NotificationManagerCompat.from(ctx).createNotificationChannel(
        NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName("Store Updates").build())

    /** Opens the app's page with the file ready, the same extras the store's own "ready" notification uses. */
    private fun notifyReady(ctx: Context, app: AppItem, file: String, error: String? = null) = runCatching {
        channel(ctx)
        val i = Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("appId", app.id).putExtra("fileName", file)
        NotificationManagerCompat.from(ctx).notify(app.id.hashCode(), NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (error == null) "${app.name} update ready" else "${app.name} needs you").setContentText(error ?: "Tap to install.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(error ?: "Tap to install.")).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, app.id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
    }

    private fun notifySummary(ctx: Context, log: List<AutoResult>) = runCatching {
        val ok = log.filter { it.ok }; if (ok.isEmpty()) return@runCatching
        channel(ctx)
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        NotificationManagerCompat.from(ctx).notify(JOB_ID, NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(com.blaze.store.R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (ok.size == 1) "${ok[0].name} updated" else "${ok.size} apps updated").setContentText(ok.joinToString { it.name }).setAutoCancel(true)
            .apply { launch?.let { setContentIntent(PendingIntent.getActivity(ctx, JOB_ID, it, PendingIntent.FLAG_IMMUTABLE)) } }.build())
    }
}

class AutoUpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onStartJob(p: JobParameters): Boolean {
        job = scope.launch { runCatching { AutoUpdate.run(applicationContext) }; jobFinished(p, false) }
        return true
    }
    // Stopped mid-pass (constraints lost, time limit): half-done downloads resume next pass, so ask to be retried
    override fun onStopJob(p: JobParameters): Boolean { job?.cancel(); runCatching { Store.settings.save(); Store.data.save() }; return true }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) = AutoUpdate.onInstallResult(ctx, i)
}
