package com.blaze.assistant.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import com.blaze.assistant.agent.Agent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-chat self-update. v0.45.4: reads the Blaze site's versions.json ([VersionFeed]), downloads android.url in the app
 * (resumable, [ApkDownload]), checks it against android.sha256 and the signing key, and installs it through
 * PackageInstaller. Prefs, keys and models live in app storage, so an update keeps them all.
 */
object Updater {
    const val TOOL = "update_blaze"
    const val CARD = "update:card"

    sealed class State {
        object Idle : State()
        object Checking : State()
        /** v0.28.1: [note] = a plain line while it retries or waits for a connection ("" = downloading normally). */
        data class Downloading(val version: String, val pct: Int, val note: String = "") : State()
        data class Installing(val version: String) : State()
        data class UpToDate(val version: String) : State()
        data class Ready(val version: String) : State()   // newer build found, not installed (install=false)
        data class NeedsPermission(val version: String) : State()
        data class Done(val version: String) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", TOOL)
        .put("description", "Update the Blaze app itself to the newest build (checks, downloads and installs; settings, keys and models are kept). Use when the user asks to update Blaze or check for a new version. install=false only checks.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("install", JSONObject().put("type", "boolean").put("description", "Install if newer (default true)")))))

    /** v0.24.4: the update runs in the background (its own scope, not the chat turn) so it can be stopped on its own. */
    @Volatile private var job: kotlinx.coroutines.Job? = null

    /** The /update command: show the (one) card and start in the background. */
    fun start(ctx: Context) {
        showCard()
        launch(ctx, true)
    }

    private fun launch(ctx: Context, install: Boolean) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch { run(app, install) }
    }

    /** One update card at a time: never a second one in the chat. */
    fun showCard() { if (!Agent.hasCard(CARD)) Agent.card("Blaze update", CARD) }

    fun retry(ctx: Context) { showCard(); launch(ctx, true) }

    /**
     * The update_blaze tool: a check answers right away; an install starts in the background and returns, so the
     * chat isn't held while it downloads (progress is on the card and in the status notification, with Stop).
     */
    suspend fun fromTool(ctx: Context, install: Boolean): String {
        if (!install) return run(ctx.applicationContext, false)
        val s = _state.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing) return "An update is already running in the background."
        showCard()
        launch(ctx, true)
        return "Started updating Blaze in the background. Progress is on the update card and in the notification (with Stop); Android asks the user to confirm the install at the end."
    }

    /** Stop (from the notification or the card): cancels the download and removes the card. */
    fun stop() {
        job?.cancel(); job = null
        val s = _state.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing || s is State.NeedsPermission) _state.value = State.Idle
        Agent.removeCard(CARD)
    }

    /** True when the card should go away by itself: the update finished (or there was nothing to do). */
    fun finished(s: State): Boolean = s is State.Done || s is State.UpToDate

    private val KEY_LAST_CHECK = "update_last_check"
    private val KEY_DISMISSED = "update_banner_dismissed"

    /** v0.45.4: the "Blaze X.Y.Z is available" banner (null = hidden). */
    data class Banner(val version: String, val line: String, val notes: String)
    private val _banner = MutableStateFlow<Banner?>(null)
    val banner: StateFlow<Banner?> = _banner

    /**
     * v0.45.4: at most once a day, on app open, a quiet look at versions.json. A newer Blaze shows a small banner the
     * user can dismiss (that version then stays hidden). Offline or any error: nothing is shown and the day isn't used up.
     */
    fun checkInBackground(ctx: Context) {
        val now = System.currentTimeMillis()
        val last = try { com.blaze.assistant.agent.Settings.str(KEY_LAST_CHECK, "0").toLongOrNull() ?: 0L } catch (_: Exception) { return }
        if (!VersionFeed.due(now, last) || job?.isActive == true) return
        val app = ctx.applicationContext
        scope.launch {
            val a = withContext(Dispatchers.IO) { try { latest() } catch (_: Exception) { null } } ?: return@launch
            try { com.blaze.assistant.agent.Settings.setStr(KEY_LAST_CHECK, now.toString()) } catch (_: Exception) { }
            val dismissed = try { com.blaze.assistant.agent.Settings.str(KEY_DISMISSED, "") } catch (_: Exception) { "" }
            VersionFeed.banner(a, installed(app), dismissed)?.let { _banner.value = Banner(a.version, it, a.notes) }
        }
    }

    /** The banner's ×: hide it and don't show this version again. */
    fun dismissBanner() {
        val b = _banner.value ?: return
        try { com.blaze.assistant.agent.Settings.setStr(KEY_DISMISSED, b.version) } catch (_: Exception) { }
        _banner.value = null
    }

    /** The banner's Update: the normal in-app update (card + download + install). */
    fun updateFromBanner(ctx: Context) { _banner.value = null; start(ctx) }

    init {
        scope.launch {
            _state.collectLatest { st ->
                val ms = ApkDownload.dismissAfterMs(st) ?: return@collectLatest
                kotlinx.coroutines.delay(ms)
                if (_state.value == st && job?.isActive != true) try { Agent.removeCard(CARD) } catch (_: Throwable) { }
            }
        }
    }

    fun installed(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    @Suppress("DEPRECATION")
    fun installedCode(ctx: Context): Long = try {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else i.versionCode.toLong()
    } catch (_: Exception) { 0L }

    private fun conn(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            // v0.28.1: long read timeout for slow mobile data (a stalled read is retried and resumed, not fatal).
            connectTimeout = 20_000; readTimeout = 90_000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
            setRequestProperty("User-Agent", "Blaze-Android")
        }

    /** versions.json's android entry, fresh (no cache). Throws [UpdateError] with plain words. */
    private fun latest(): VersionFeed.Android {
        val c = conn(VersionFeed.URL)
        try {
            if (c.responseCode !in 200..299) throw UpdateError("The Blaze site answered with an error (${c.responseCode}). Try again in a minute.")
            return VersionFeed.parse(c.inputStream.bufferedReader().readText())
                ?: throw UpdateError("The Blaze site's version list couldn't be read. Try again later.")
        } finally { c.disconnect() }
    }

    private fun fail(msg: String): String { _state.value = State.Failed(msg); return msg }

    /** Checks and (optionally) installs. Returns a plain sentence for the model. */
    suspend fun run(ctx: Context, install: Boolean): String {
        val s = _state.value
        if (s is State.Checking || s is State.Downloading || s is State.Installing) return "An update is already running."
        _state.value = State.Checking
        val have = installed(ctx)
        return try {
            val entry = withContext(Dispatchers.IO) { latest() }
            val version = entry.version
            val name = "blaze-$version.apk"
            val isNewer = VersionFeed.newer(version, have)
            if (!isNewer) { _state.value = State.UpToDate(have); return "Blaze is up to date (v$have)." }
            if (!install) { _state.value = State.Ready(version); return "Blaze v$version is available (installed: v$have). Say update to install it." }
            if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                _state.value = State.NeedsPermission(version)
                ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Blaze v$version is ready. Android asks once before an app can install its own updates: turn on 'Allow from this source' for Blaze, then tap Retry on the update card."
            }
            _state.value = State.Downloading(version, 0)
            val apk = withContext(Dispatchers.IO) {
                // v0.28.1: resumable, retried download (ApkDownload); v0.45.4: only from versions.json's url. The partial file of this same build is kept
                // between tries and between Retry taps; anything else in the folder is cleared.
                val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
                val f = File(dir, name)
                val part = File(dir, "$name.part")
                dir.listFiles()?.forEach { if (it.name != part.name) it.delete() }
                val me = coroutineContext[kotlinx.coroutines.Job]
                val cancelled = { me?.isActive == false }
                val sources = VersionFeed.sources(entry)
                var last = -1
                try {
                    ApkDownload.fetch(sources, httpIo(ctx), part, f, 0L, entry.sha256, cancelled) { ev ->
                        when (ev) {
                            is ApkDownload.Event.Progress -> {
                                val total = ev.total
                                val pct = if (total > 0) (ev.done * 100 / total).toInt().coerceIn(0, 100) else 0
                                val cur = _state.value
                                if (pct != last || (cur is State.Downloading && cur.note.isNotEmpty())) { last = pct; _state.value = State.Downloading(version, pct) }
                            }
                            is ApkDownload.Event.Retrying -> _state.value = State.Downloading(version, last.coerceAtLeast(0), ApkDownload.retryLine(ev))
                            is ApkDownload.Event.Offline -> _state.value = State.Downloading(version, last.coerceAtLeast(0), ApkDownload.OFFLINE)
                        }
                    }
                } catch (e: ApkDownload.Cancelled) { throw kotlinx.coroutines.CancellationException("Stopped") }
                catch (e: ApkDownload.GaveUp) { com.blaze.assistant.device.CrashLog.error("Update download", e)
                    throw UpdateError("The connection kept dropping, so I stopped for now. What's downloaded is kept: tap Retry and I'll carry on from there.") }
                catch (e: ApkDownload.Damaged) { f.delete(); throw UpdateError(e.message ?: VersionFeed.SHA_MISMATCH) }
                f
            }
            // Check it before Android sees it: same app, newer build, signed by the key this Blaze trusts.
            withContext(Dispatchers.IO) { verify(ctx, apk) }?.let { apk.delete(); return fail(it) }
            _state.value = State.Installing(version)
            withContext(Dispatchers.IO) { installApk(ctx, apk) }
            "Downloaded Blaze v$version. Android is asking the user to confirm the install; Blaze restarts after it."
        } catch (e: UpdateError) { fail(e.message ?: "Update failed.") }
        catch (e: java.net.UnknownHostException) { fail("No internet connection. Connect and tap Retry.") }
        catch (e: java.net.SocketTimeoutException) { fail("The Blaze site is taking too long to answer. Tap Retry.") }
        catch (e: java.io.IOException) { com.blaze.assistant.device.CrashLog.error("Update", e); fail("The connection dropped while checking for the update. Tap Retry.") }
        catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = State.Idle
            try { File(ctx.cacheDir, "update").listFiles()?.forEach { it.delete() } } catch (_: Exception) { }
            throw e
        }
        catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("Update", e); fail("Something went wrong with the update. Tap Retry and I'll try again.") }
    }

    private class UpdateError(msg: String) : Exception(msg)

    /** v0.28.1: real HTTP for ApkDownload: Range from the partial file; redirects followed (the site 302s to the registry). */
    private fun httpIo(ctx: Context): ApkDownload.Io = object : ApkDownload.Io {
        override fun open(url: String, from: Long): ApkDownload.Response {
            val c = conn(url)
            c.setRequestProperty("Accept", "application/octet-stream")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = c.responseCode
            val body = if (code in 200..299) c.inputStream else (c.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0)))
            return ApkDownload.Response(code, c.contentLengthLong, c.getHeaderField("Content-Range"), body)
        }
        override fun online(): Boolean = try {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: Exception) { true }
    }

    /**
     * 0.45.16: Blaze's signing key changed in 0.45.15 (the old key was public). A phone whose Blaze can't take the new
     * build in place is told the one fix: uninstall once and reinstall from the site. The site says the same.
     */
    const val KEY_CHANGED = "Blaze's signing key changed in 0.45.15, so this phone can't update in place. Uninstall Blaze once, " +
        "then install it again from blazeinchue.pages.dev. Updates work normally after that."

    /** Why a downloaded APK must not be installed (null = fine). */
    @Suppress("DEPRECATION")
    private fun verify(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return "That download isn't a valid app file. Tap Retry."
        if (info.packageName != ctx.packageName) return "That download isn't Blaze, so I didn't install it."
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= 28) mine.longVersionCode else mine.versionCode.toLong()
        if (newCode <= oldCode) return "That download isn't newer than the Blaze you have, so I didn't install it."
        fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        val trusted: Set<String>; val offered: Set<String>
        if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return "That download isn't signed, so I didn't install it."
            trusted = mine.signingInfo.apkContentsSigners.map { sha(it.toByteArray()) }.toSet()
            // A build signed with a newer key in Blaze's key lineage lists the old key in its history.
            offered = (si.apkContentsSigners + (si.signingCertificateHistory ?: emptyArray())).map { sha(it.toByteArray()) }.toSet()
        } else {
            trusted = mine.signatures.map { sha(it.toByteArray()) }.toSet()
            offered = (info.signatures ?: emptyArray()).map { sha(it.toByteArray()) }.toSet()
        }
        if (trusted.isEmpty() || !offered.containsAll(trusted)) return KEY_CHANGED
        // 0.45.15 key rotation: from now on every Blaze update is signed by the private release key (the old key is public and
        // burned). On Android 9+ the download's current signer must be that key; the old key only counts as its history.
        if (Build.VERSION.SDK_INT >= 28) {
            val current = info.signingInfo?.apkContentsSigners?.map { sha(it.toByteArray()) }?.toSet().orEmpty()
            if (!UpdateKeys.pinned(current)) return "That download isn't signed by Blaze's release key, so I didn't install it."
        }
        return null
    }

    private fun installApk(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            session.openWrite("blaze.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = PendingIntent.getBroadcast(ctx, 7301, Intent(ctx, InstallReceiver::class.java), flags)
            session.commit(intent.intentSender)
        }
    }

    internal fun onResult(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val v = (_state.value as? State.Installing)?.version ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Done(v)
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = State.Failed("The install was cancelled. Tap Retry when you're ready.")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                _state.value = State.Failed(KEY_CHANGED)
            PackageInstaller.STATUS_FAILURE_STORAGE -> _state.value = State.Failed("Not enough free space to install. Free some up and tap Retry.")
            else -> _state.value = State.Failed("Android couldn't install the update" +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { ": $it" } ?: ".") + " Tap Retry.")
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onResult(context, intent)
}
