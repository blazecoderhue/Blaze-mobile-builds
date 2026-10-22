package com.blaze.assistant.blazeos

import android.content.Context
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.33.2 BlazeOS, the device. Blaze's own virtual phone: a custom machine (not the host phone, not an Android VM) that
 * runs only BlazeOS apps on its own sandboxed disk (OsDisk). One instance per process; the screen is BlazeOsActivity.
 *
 * Power: Off → Booting (mascot boot animation; host resources read and the budget set) → On ⇄ Paused, Power off,
 * Restart (rules in OsMachine). Pause frees memory (icon cache, background apps, media) and keeps the app in front,
 * which Resume brings back with its state. Everything here runs on the main thread except disk work (Dispatchers.IO).
 */
object BlazeOs {
    const val VERSION = "1.0"
    private lateinit var app: Context
    val ready: Boolean get() = ::app.isInitialized
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val power = MutableStateFlow(Power.OFF)
    val budget = MutableStateFlow(OsResources.allocate(HostInfo(3L * OsResources.GB, OsResources.GB, false, 256, 4L * OsResources.GB, 26)))
    val tasks = MutableStateFlow(OsTasks())
    val prefs = MutableStateFlow(OsPrefs())
    val registry = MutableStateFlow(OsRegistry())
    val grants = MutableStateFlow(OsGrants())
    val diskUsed = MutableStateFlow(0L)
    /** 0..1 while booting (the boot screen's bar). */
    val bootProgress = MutableStateFlow(0f)

    data class Banner(val app: String, val title: String, val body: String, val at: Long = System.currentTimeMillis())
    /** The banner on screen now (BlazeOS's own notifications; never the host's). */
    val banner = MutableStateFlow<Banner?>(null)
    /** The last notifications, newest first (shown in the control centre). */
    val notices = MutableStateFlow<List<Banner>>(emptyList())

    /** A BlazeOS permission prompt waiting for an answer: "allow", "once" or "deny". */
    data class PermAsk(val app: String, val appLabel: String, val perm: String, val answer: CompletableDeferred<String>)
    val permAsk = MutableStateFlow<PermAsk?>(null)

    /** Set by BlazeOsActivity: asks the host (Android) for a runtime permission once BlazeOS's own answer is yes. */
    @Volatile var hostRequester: (suspend (String) -> Boolean)? = null

    /** True while a Blaze screen opened from BlazeOS (Video, Browser, Terminal, Camera) is in front: no auto-pause. */
    @Volatile var handoff = false

    private var bootJob: Job? = null

    // ---------------- setup ----------------

    /** From BlazeApp.onCreate: only reads (and once migrates) the prefs. The disk is opened at boot. */
    fun init(ctx: Context) {
        app = ctx.applicationContext
        val w = OsPrefs.migrate { get(it) }
        for ((k, v) in w) Settings.setStr(k, v)
        prefs.value = OsPrefs.read { get(it) }
    }

    private fun get(k: String): String? = Settings.str(k, "").ifEmpty { null }

    /** The Classic board instead of BlazeOS on Blaze's Home tab (Blaze settings toggle). */
    var classic: Boolean
        get() = prefs.value.classic
        set(v) = update { it.copy(classic = v) }

    fun update(f: (OsPrefs) -> OsPrefs) {
        val n = f(prefs.value)
        if (n == prefs.value) return
        prefs.value = n
        if (ready) for ((k, v) in OsPrefs.write(n)) if (Settings.str(k, "") != v) Settings.setStr(k, v)
    }

    // ---------------- power ----------------

    private fun go(e: PowerEvent): Boolean {
        val n = OsMachine.next(power.value, e) ?: return false
        power.value = n
        return true
    }

    /** Tapping Home: boot when off, wake when paused. */
    fun open() {
        when (OsMachine.onOpen(power.value)) {
            PowerEvent.POWER_ON -> { if (go(PowerEvent.POWER_ON)) runBoot() }
            PowerEvent.RESUME -> resume()
            else -> { }
        }
    }

    private fun runBoot() {
        bootJob?.cancel()
        bootProgress.value = 0f
        bootJob = scope.launch {
            val t0 = System.currentTimeMillis()
            val ok = withContext(Dispatchers.IO) {
                try {
                    OsDisk.ensure(app)
                    val b = OsResources.allocate(OsHost.read(app))
                    budget.value = b
                    bootProgress.value = 0.3f
                    carryOldWallpaper()
                    registry.value = OsDisk.registry(app)
                    grants.value = OsDisk.grants(app)
                    preinstall()
                    bootProgress.value = 0.7f
                    OsDisk.trimCache(app, b.diskCacheBytes)
                    diskUsed.value = OsDisk.used(app)
                    true
                } catch (_: Exception) { false }
            }
            OsImages.resize(budget.value.imageCacheBytes)
            val rest = OsMachine.bootMs(budget.value.anim) - (System.currentTimeMillis() - t0)
            if (rest > 0) delay(rest)
            bootProgress.value = 1f
            if (!ok) { go(PowerEvent.BOOT_FAILED); return@launch }
            if (go(PowerEvent.BOOT_DONE)) {
                tasks.value = OsTasks().restore(prefs.value.lastApp) { exists(it) }
                applyVolume()
            }
        }
    }

    /** Pause & leave (also the real power button and leaving Blaze): frees memory, keeps the app in front. */
    fun pause() {
        if (power.value == Power.BOOTING) { bootJob?.cancel(); go(PowerEvent.PAUSE); return }
        if (!go(PowerEvent.PAUSE)) return
        val (t, _) = tasks.value.pause()
        tasks.value = t
        update { it.copy(lastApp = t.front) }
        freeMemory()
    }

    fun resume() {
        if (!go(PowerEvent.RESUME)) return
        if (tasks.value.front.isNotEmpty() && !exists(tasks.value.front)) tasks.value = OsTasks()
        OsWeb.resumeAll(); OsWasm.resumeAll()
        applyVolume()
    }

    fun powerOff() {
        bootJob?.cancel()
        if (!go(PowerEvent.POWER_OFF)) return
        tasks.value = OsTasks()
        update { it.copy(lastApp = "") }
        freeMemory()
    }

    fun restart() {
        if (!go(PowerEvent.RESTART)) return
        tasks.value = OsTasks()
        update { it.copy(lastApp = "") }
        freeMemory()
        runBoot()
    }

    /** Drops what can be rebuilt: decoded icons and pictures, part of the disk cache, and stops BlazeOS's media. */
    private fun freeMemory() {
        OsImages.clear()
        OsWeb.pauseAll(); OsWasm.pauseAll()
        stopMedia()
        if (ready) scope.launch(Dispatchers.IO) { OsDisk.trimCache(app, budget.value.diskCacheBytes / 2) }
        System.gc()
    }

    private fun stopMedia() {
        try { com.blaze.assistant.mediaapps.MusicService.current?.livePlayer?.let { it.pause(); it.volume = 1f } } catch (_: Throwable) { }
    }

    /** BlazeOS volume reaches Blaze Music's player while BlazeOS is on (back to full when it pauses / powers off). */
    fun applyVolume() {
        try { com.blaze.assistant.mediaapps.MusicService.current?.livePlayer?.volume = prefs.value.volume / 100f } catch (_: Throwable) { }
    }

    fun setVolume(v: Int) { update { it.copy(volume = v.coerceIn(0, 100)) }; applyVolume() }
    fun setBrightness(v: Int) = update { it.copy(brightness = v.coerceIn(OsPrefs.MIN_BRIGHTNESS, 100)) }

    /**
     * Factory reset: powers off, wipes the BlazeOS disk (apps, their data, permissions, wallpaper photo) and puts the
     * BlazeOS settings back to new. Nothing else of Blaze's is touched; the Classic board choice is kept.
     */
    suspend fun factoryReset() {
        powerOff()
        OsWeb.releaseAll(); OsWasm.releaseAll()
        for (a in registry.value.apps) OsWeb.forgetOrigin(a.id)
        OsWeb.wipeProfile()
        withContext(Dispatchers.IO) { OsDisk.wipe(app) }
        registry.value = OsRegistry(); grants.value = OsGrants(); diskUsed.value = 0L
        val classicNow = prefs.value.classic
        update { OsPrefs(classic = classicNow) }
    }

    // ---------------- apps ----------------

    /** Every app on this BlazeOS: the built-ins, then the installed ones. */
    fun apps(reg: OsRegistry = registry.value): List<OsApp> = OsApps.merge(reg.apps.map {
        OsApp(it.appId, it.manifest.name, it.manifest.entry.type, removable = true)
    })

    fun exists(id: String): Boolean = if (id.startsWith(OsApps.BUILTIN)) id.removePrefix(OsApps.BUILTIN) in OsApps.builtinKeys
        else registry.value.has(id.removePrefix(OsApps.PKG))

    fun label(id: String): String = apps().firstOrNull { it.id == id }?.label ?: id.substringAfter(':')

    /** Opens [id] in front (call after [request] when it needs permissions). Apps beyond the budget close oldest first. */
    fun launch(id: String) {
        if (power.value != Power.ON || !exists(id)) return
        val (t, _) = tasks.value.launch(id, budget.value.maxLiveApps)
        tasks.value = t
    }

    fun home() { tasks.value = tasks.value.home() }
    fun close(id: String) { tasks.value = tasks.value.close(id) }

    /**
     * Asks for BlazeOS permissions [perms] for [appId] (its own prompt), then the host phone's permission behind each
     * when it has one. True when everything was allowed.
     */
    suspend fun request(appId: String, perms: List<String>): Boolean {
        for (p in perms) {
            when (grants.value.decide(appId, p)) {
                OsGrants.Decision.DENIED -> { notify("Settings", "${OsPerms.label(p)} is off for ${label(appId)}", "Turn it on in Settings › Apps."); return false }
                OsGrants.Decision.ASK -> {
                    val d = CompletableDeferred<String>()
                    permAsk.value = PermAsk(appId, label(appId), p, d)
                    val a = try { d.await() } finally { permAsk.value = null }
                    if (a != "once") setGrant(appId, p, a == "allow")
                    if (a == "deny") return false
                }
                OsGrants.Decision.ALLOWED -> { }
            }
            val host = OsPerms.hostPermission(p)
            if (host != null && app.checkSelfPermission(host) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val ok = hostRequester?.invoke(host) ?: false
                if (!ok) { notify("Settings", "Blaze can't use the ${OsPerms.label(p).lowercase()}", "Android said no. Allow it for Blaze in the phone's settings."); return false }
            }
        }
        return true
    }

    fun setGrant(appId: String, perm: String, allow: Boolean?) {
        val g = if (allow == null) grants.value.reset(appId, perm) else grants.value.set(appId, perm, allow)
        grants.value = g
        scope.launch(Dispatchers.IO) { OsDisk.saveGrants(app, g) }
    }

    /** A BlazeOS notification (a banner over BlazeOS; never posted to the host). */
    fun notify(appLabel: String, title: String, body: String) {
        val b = Banner(appLabel, title.take(80), body.take(200))
        banner.value = b
        notices.value = (listOf(b) + notices.value).take(20)
        scope.launch { delay(4000); if (banner.value == b) banner.value = null }
    }

    // ---------------- App Store ----------------

    private var catalogCache: OsCatalog? = null

    /** The store catalog: the bundled one (assets/blazeos/catalog.json); a remote one is merged in when the catalog names it. */
    suspend fun catalog(): OsCatalog = catalogCache ?: withContext(Dispatchers.IO) {
        val local = try { OsManifest.catalog(app.assets.open("blazeos/catalog.json").bufferedReader().use { it.readText() }) } catch (_: Exception) { OsCatalog(1, emptyList()) }
        val merged = if (local.remote.isEmpty()) local else try {
            val c = java.net.URL(local.remote).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val r = OsManifest.catalog(c.inputStream.bufferedReader().use { it.readText().take(512 * 1024) })
            val ids = r.apps.map { it.manifest.id }.toHashSet()
            local.copy(apps = r.apps + local.apps.filter { it.manifest.id !in ids })
        } catch (_: Exception) { local }
        merged.also { catalogCache = it }
    }

    /** Installs (or updates) [l]. Null on success, else why it didn't. */
    suspend fun install(l: OsListing, preinstalled: Boolean = false): String? = withContext(Dispatchers.IO) {
        val m = l.manifest
        val q = budget.value.diskQuota
        val used = OsDisk.used(app)
        if (registry.value.kindOf(m) == OsRegistry.OLDER) return@withContext "A newer version of ${m.name} is already installed"
        if (l.size > 0 && used + l.size - (registry.value.get(m.id)?.size ?: 0) > q) return@withContext "Not enough space on the BlazeOS disk"
        val dir = OsDisk.appDir(app, m.id)
        var size = 0L
        if (OsManifest.packaged(m.entry)) {
            val n = try {
                val input = if (l.pkg.startsWith("asset:")) app.assets.open(l.pkg.removePrefix("asset:"))
                    else (java.net.URL(l.pkg).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 20000 }.inputStream
                input.use { OsDisk.unzip(it, dir, (q - used + (registry.value.get(m.id)?.size ?: 0)).coerceAtLeast(0)) }
            } catch (_: Exception) { -1L }
            if (n < 0) return@withContext "The package couldn't be installed (damaged, too big or not reachable)"
            if (!File(dir, m.entry.path).isFile) { dir.deleteRecursively(); return@withContext "The package has no ${m.entry.path}" }
            size = n
        } else dir.mkdirs()
        OsDisk.writeText(File(dir, "manifest.json"), OsManifest.toJson(m).toString(1))
        OsDisk.dataDir(app, OsApps.pkgId(m.id))
        File(dir, "icon.png").takeIf { it.isFile }?.let { size += it.length() }
        size += File(dir, "manifest.json").length()
        when (val r = registry.value.install(m, size, q, used, System.currentTimeMillis(), preinstalled)) {
            is OsRegistry.Result.Refused -> { if (!registry.value.has(m.id)) dir.deleteRecursively(); r.why }
            is OsRegistry.Result.Ok -> {
                registry.value = r.reg
                OsDisk.saveRegistry(app, r.reg)
                diskUsed.value = OsDisk.used(app)
                null
            }
        }
    }

    /** v0.33.2 "Install app" from the BlazeOS Browser: a website as a BlazeOS app (hosted web entry + its icon). Null = done. */
    suspend fun installSite(m: OsManifestData, iconPng: ByteArray): String? {
        if (power.value != Power.ON) return "BlazeOS isn't on."
        withContext(Dispatchers.IO) { val d = OsDisk.appDir(app, m.id); d.mkdirs(); File(d, "icon.png").writeBytes(iconPng); OsImages.forget(File(d, "icon.png").absolutePath) }
        val why = install(OsListing(m, "", iconPng.size.toLong()))
        if (why == null) notify("Home screen", "${m.name} was added", "It opens like an app, on its own.")
        return why
    }

    /** Uninstall frees the space: the package, the app's data folder and its permission answers go. */
    suspend fun uninstall(manifestId: String) {
        val appId = OsApps.pkgId(manifestId)
        close(appId)
        OsWeb.release(appId); OsWasm.release(appId)
        OsWeb.forgetOrigin(manifestId)
        update { p -> p.copy(order = p.order - appId, dock = p.dock?.let { it - appId }, lastApp = if (p.lastApp == appId) "" else p.lastApp) }
        withContext(Dispatchers.IO) {
            OsDisk.appDir(app, manifestId).deleteRecursively()
            File(OsDisk.data(app), OsDisk.safeName(appId)).deleteRecursively()
            val r = registry.value.uninstall(manifestId)
            registry.value = r; OsDisk.saveRegistry(app, r)
            val g = grants.value.forget(appId)
            grants.value = g; OsDisk.saveGrants(app, g)
            diskUsed.value = OsDisk.used(app)
        }
    }

    /** First boot of a new disk: Calculator and Notes come installed (they can be removed; a factory reset brings them back). */
    private suspend fun preinstall() {
        val flag = File(OsDisk.system(app), ".preinstalled")
        if (flag.exists()) return
        val c = catalog()
        for (id in listOf("blazeos.calculator", "blazeos.notes")) {
            val l = c.apps.firstOrNull { it.manifest.id == id } ?: continue
            if (!registry.value.has(id)) install(l, preinstalled = true)
        }
        try { flag.writeText("1") } catch (_: Exception) { }
    }

    /** The old Home board's photo background becomes the BlazeOS photo wallpaper once (copied onto BlazeOS's disk). */
    private fun carryOldWallpaper() {
        if (prefs.value.wallpaper != "photo") return
        val dst = OsDisk.wallpaper(app)
        if (dst.exists()) return
        val old = File(app.filesDir, "home_bg.jpg")
        try { if (old.exists()) old.copyTo(dst, overwrite = true) } catch (_: Exception) { }
    }

    /** Saves a picked photo as the wallpaper (downscaled to the budget's picture size). */
    suspend fun setWallpaperPhoto(uri: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        val ok = OsImages.saveScaled(app, uri, OsDisk.wallpaper(app), budget.value.imagePx)
        if (ok) { OsImages.forget(OsDisk.wallpaper(app).absolutePath); withContext(Dispatchers.Main) { update { it.copy(wallpaper = "photo") } } }
        ok
    }

    fun context(): Context = app
}
