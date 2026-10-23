package com.blaze.assistant.appstore

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 0.45.19 Blaze App Store: the on-phone side. Catalog from Site (ETag, cache, bundled seed), search, installed/updates,
 * and one job per app through [InstallPipeline]. Free, no ads, no gates (user policy, 2026-10-23); every listing carries
 * [StorePolicy.NOTICE], its license and its source repo.
 *
 * Install destination (user decision 2026-10-23 10:29, A): the user's own phone through Android's PackageInstaller
 * ([StoreInstaller]); the user confirms every install in Android's dialog and uninstalls from Android's settings.
 * Blaze Phone's sandbox stays parked; the release page is a fallback link only.
 */
object AppStore {
    data class Job(val appId: String, val step: StoreStep, val message: String, val done: Long = 0, val total: Long = 0, val update: Boolean = false)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _catalog = MutableStateFlow(Catalog.EMPTY)
    val catalog: StateFlow<Catalog> = _catalog
    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    val jobs: StateFlow<Map<String, Job>> = _jobs
    /** Catalog apps installed on this phone, by package. */
    private val _installed = MutableStateFlow<Map<String, InstalledInfo>>(emptyMap())
    val installed: StateFlow<Map<String, InstalledInfo>> = _installed
    /** App id -> newer release tag, for installed apps. */
    private val _updates = MutableStateFlow<Map<String, String>>(emptyMap())
    val updates: StateFlow<Map<String, String>> = _updates

    /** One running job per app id, shared by the Store page and the agent's tools (no double installs). */
    private val running = ConcurrentHashMap<String, Deferred<PipelineResult>>()
    private val cancels = ConcurrentHashMap<String, AtomicBoolean>()
    private val refreshing = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)

    private val cache by lazy { CatalogCache(File(app.filesDir, "store"), { runCatching { app.assets.open("store/catalog.json").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }) }
    private val dlDir get() = File(app.cacheDir, "store-dl")

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        _catalog.value = cache.load()
        // Leftovers from a run the system killed mid-download: never installed later, never kept.
        scope.launch {
            try { if (running.isEmpty()) dlDir.listFiles()?.forEach { it.delete() } } catch (_: Exception) { }
            scanInstalled()
        }
    }

    val ready: Boolean get() = ::app.isInitialized
    /** The application context (after [init]). */
    internal val ctx: Context get() = app

    /** Refreshes from Site when the copy is older than 6 h (or [force]). One refresh at a time. */
    fun refresh(force: Boolean = false) {
        if (!ready) return
        if (!force && System.currentTimeMillis() - cache.checkedAt < CatalogCache.STALE_MS && _catalog.value.apps.isNotEmpty()) return
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try { _catalog.value = cache.refresh(CatalogCache.URL, http).catalog; scanInstalled() } finally { refreshing.set(false) }
        }
    }

    fun search(q: String, category: String = Catalog.ALL): List<StoreApp> {
        val inCat = _catalog.value.inCategory(category)
        return if (q.isBlank()) inCat else StoreSearch.search(inCat, q)
    }

    fun find(q: String): StoreApp? = _catalog.value.byId(q.trim().lowercase()) ?: StoreSearch.resolve(_catalog.value.apps, q)

    fun openSource(ctx: Context, a: StoreApp) = open(ctx, a.source.url)

    /** Fallback link only (never an install target). */
    fun openReleases(ctx: Context, a: StoreApp) = open(ctx, a.source.releasesUrl)

    fun open(ctx: Context, url: String): Boolean =
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }

    /** Starts an installed app. False when it isn't installed or has no launcher screen. */
    fun openApp(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }

    /** Reopens Android's install dialog for [a] if a session is waiting on it. */
    fun reopenInstaller(ctx: Context, a: StoreApp): Boolean {
        val i = StoreSessions.pendingConfirm[a.pkg] ?: return false
        return try { ctx.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }

    fun canInstall(): Boolean = ready && StoreInstaller.canInstall(app)
    fun requestInstallPermission(ctx: Context) = StoreInstaller.requestPermission(ctx)

    /** Starts (or joins) the job for [a]; [confirm] is the ConfirmGate card. Returns the running job. */
    fun start(a: StoreApp, update: Boolean = isInstalled(a), confirm: suspend (String, String) -> Boolean): Deferred<PipelineResult> {
        running[a.id]?.takeIf { it.isActive }?.let { return it }
        synchronized(running) {
            running[a.id]?.takeIf { it.isActive }?.let { return it }
            val flag = AtomicBoolean(false); cancels[a.id] = flag
            val gate = CompletableDeferred<Unit>()
            val job = scope.async {
                gate.await()
                val r: PipelineResult = try {
                    InstallPipeline(env(confirm)).run(a, update, report = { s, m, done, total -> put(Job(a.id, s, m, done, total, update)) }, cancelled = { flag.get() })
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    PipelineResult(StoreStep.FAILED, "Not installed: ${e.message ?: "error"}.")
                }
                put(Job(a.id, r.step, r.message, update = update))
                if (r.ok) scanInstalledAfter(a)
                r
            }
            running[a.id] = job
            put(Job(a.id, StoreStep.QUEUED, "Waiting", update = update))
            job.invokeOnCompletion { running.remove(a.id, job); cancels.remove(a.id, flag) }
            gate.complete(Unit)
            return job
        }
    }

    /** Old entry point for the page: starts the job and returns its current state. */
    fun get(a: StoreApp, confirm: suspend (String, String) -> Boolean): Job { start(a, confirm = confirm); return _jobs.value[a.id] ?: Job(a.id, StoreStep.QUEUED, "Waiting") }

    suspend fun runJob(a: StoreApp, update: Boolean = isInstalled(a), confirm: suspend (String, String) -> Boolean): PipelineResult = start(a, update, confirm).await()

    fun cancel(appId: String) { cancels[appId]?.set(true) }

    private fun put(j: Job) = _jobs.update { it + (j.appId to j) }

    fun isInstalled(a: StoreApp) = _installed.value.containsKey(a.pkg)

    private fun scanInstalledAfter(a: StoreApp) { scanInstalled(); _updates.update { it - a.id } }

    /** Which catalog apps are on this phone (package visibility: Blaze's manifest queries launcher apps). */
    fun scanInstalled() {
        if (!ready) return
        val pm = app.packageManager
        val cat = _catalog.value
        val found = HashMap<String, InstalledInfo>()
        for (a in cat.apps) installedInfo(pm, a.pkg)?.let { found[a.pkg] = it }
        _installed.value = found
        // A catalog-known newer version code is an update without asking the network.
        val known = cat.apps.mapNotNull { a ->
            val have = found[a.pkg] ?: return@mapNotNull null
            a.releases.entries.filter { it.value.versionCode > have.versionCode }.maxByOrNull { it.value.versionCode }?.let { a.id to it.key }
        }.toMap()
        _updates.update { cur -> cur.filterKeys { id -> cat.byId(id)?.pkg?.let { it in found } == true } + known }
    }

    /**
     * Resolves the latest release of each installed catalog app (at most [max]: GitHub allows 60 unauthenticated calls
     * an hour) and marks the newer ones. One check at a time.
     */
    fun checkUpdates(max: Int = 15) {
        if (!ready || !checking.compareAndSet(false, true)) return
        scope.launch {
            try {
                scanInstalled()
                val abis = Build.SUPPORTED_ABIS.toList()
                val mine = _catalog.value.apps.filter { it.pkg in _installed.value }.take(max)
                for (a in mine) {
                    val tag = try { ReleaseResolver({ u -> getText(u) }, abis).resolve(a).tag } catch (_: Exception) { continue }
                    val have = _installed.value[a.pkg] ?: continue
                    if (StoreVersions.newer(tag, have.versionName)) _updates.update { it + (a.id to tag) }
                }
            } finally { checking.set(false) }
        }
    }

    private fun env(confirm: suspend (String, String) -> Boolean) = object : InstallPipeline.Env {
        private val abis = Build.SUPPORTED_ABIS.toList()
        override suspend fun confirmInstall(title: String, detail: String) = confirm(title, detail)
        override suspend fun confirmRisk(title: String, detail: String) = confirm(title, detail)
        override fun canInstall() = StoreInstaller.canInstall(app)
        override fun requestInstallPermission() = StoreInstaller.requestPermission(app)
        override suspend fun awaitInstallPermission(timeoutMs: Long): Boolean =
            withTimeoutOrNull(timeoutMs) { while (!StoreInstaller.canInstall(app)) delay(1000); true } ?: false
        override fun resolve(app: StoreApp) = ReleaseResolver({ u -> getText(u) }, abis).resolve(app)
        override fun download(app: StoreApp, asset: ResolvedAsset, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File {
            val dir = dlDir.apply { mkdirs() }
            val out = File(dir, "${app.id}-${System.nanoTime()}.apk")
            var c: HttpURLConnection? = null
            try {
                c = URL(asset.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Blaze-Store")
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                if (c.url.protocol != "https") throw IllegalStateException("the download left https")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: asset.size
                if (total > MAX_APK) throw IllegalStateException("file is bigger than ${MAX_APK / 1_000_000} MB")
                var done = 0L; var last = 0L
                c.inputStream.use { i -> out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw IllegalStateException("cancelled")
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (done > MAX_APK) throw IllegalStateException("file is bigger than ${MAX_APK / 1_000_000} MB")
                        if (done - last > 256 * 1024) { progress(done, total); last = done }
                    }
                } }
                progress(done, total)
                return out
            } catch (e: Exception) {
                out.delete(); throw e
            } finally { c?.disconnect() }
        }
        override fun sha256(file: File): String = file.inputStream().use { i ->
            val md = MessageDigest.getInstance("SHA-256"); val b = ByteArray(64 * 1024)
            while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) }
            hex(md.digest())
        }
        override fun inspect(file: File): ApkInfo? {
            val pi = this@AppStore.app.packageManager.getPackageArchiveInfo(file.path, sigFlags) ?: return null
            return ApkInfo(pi.packageName, code(pi), pi.versionName.orEmpty(), signers(pi))
        }
        override fun installed(pkg: String): InstalledInfo? = installedInfo(this@AppStore.app.packageManager, pkg)
        override fun scan(sha256: String): VtVerdict = VirusTotal.lookup(sha256, vtKey())
        override suspend fun install(apk: File, info: ApkInfo, onUserAction: () -> Unit): InstallOutcome =
            StoreInstaller.install(this@AppStore.app, apk, info, InstallPipeline.USER_WAIT_MS, onUserAction)
        override suspend fun awaitInstalled(pkg: String, versionCode: Long, timeoutMs: Long): Boolean =
            withTimeoutOrNull(timeoutMs) { while ((installed(pkg)?.versionCode ?: -1) < versionCode) delay(500); true } ?: false
        override fun delete(file: File) { try { file.delete() } catch (_: Exception) { } }
        override fun sdkInt(): Int = Build.VERSION.SDK_INT
    }

    @Suppress("DEPRECATION")
    private val sigFlags: Int get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun installedInfo(pm: PackageManager, pkg: String): InstalledInfo? = try {
        val pi = pm.getPackageInfo(pkg, sigFlags)
        InstalledInfo(code(pi), pi.versionName.orEmpty(), signers(pi))
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private fun code(pi: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

    /** Current signers plus the rotation history (Android 9+), as hex SHA-256 of each certificate. */
    @Suppress("DEPRECATION")
    private fun signers(pi: PackageInfo): Set<String> {
        val sigs: List<android.content.pm.Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo
            when {
                si == null -> emptyList()
                si.hasMultipleSigners() -> si.apkContentsSigners?.toList().orEmpty()
                else -> si.signingCertificateHistory?.toList().orEmpty() + si.apkContentsSigners?.toList().orEmpty()
            }
        } else pi.signatures?.toList().orEmpty()
        return sigs.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** The user's own VirusTotal key (Store › VirusTotal key), else none: "not scanned" is said plainly. */
    fun vtKey(): String? = if (!ready) null else app.getSharedPreferences("store", Context.MODE_PRIVATE).getString("vt_key", null)?.ifBlank { null }
    fun setVtKey(k: String) { if (ready) app.getSharedPreferences("store", Context.MODE_PRIVATE).edit().putString("vt_key", k.trim()).apply() }

    private fun getText(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "Blaze-Store")
            if (c.responseCode !in 200..299) throw ReleaseResolver.NotFound("The release list answered HTTP ${c.responseCode}.")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
    }

    private val http = CatalogCache.Http { url, etag ->
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            if (etag != null) c.setRequestProperty("If-None-Match", etag)
            val code = c.responseCode
            val body = if (code == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
            CatalogCache.Response(code, body, c.getHeaderField("ETag"))
        } finally { c.disconnect() }
    }

    const val MAX_APK = 600_000_000L
}
