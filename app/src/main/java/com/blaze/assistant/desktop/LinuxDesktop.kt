package com.blaze.assistant.desktop

import android.content.Context
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.desktop.DesktopPlan.Phase
import com.blaze.assistant.terminal.Distros
import com.blaze.assistant.terminal.Linux
import com.blaze.assistant.terminal.StopToken
import com.blaze.assistant.terminal.Terminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.25.1: a real Linux desktop (XFCE under TigerVNC) inside Blaze's Alpine Linux, started from the Desktop view's
 * one Start button or by the agent's `desktop` tool. Installs on demand ([DesktopPlan.installScript]), runs the X
 * server + session as a background job, and hands the viewer a fresh random password each start. Stop kills every
 * desktop process. If Android kills the session (low memory), the view offers Resume.
 */
object LinuxDesktop {
    data class State(val phase: Phase = Phase.IDLE, val line: String = "", val pct: Int? = null, val error: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var jobId = 0
    @Volatile var password: String = ""; private set

    private const val PREF_ON = "linux_desktop_on"
    private const val PREF_MIRROR = "linux_desktop_mirror"
    /** v0.33.5: which Linux the desktop (and the Terminal, BlazeOS Linux apps, the agent's linux=true) use. Missing = Alpine. */
    private const val PREF_DISTRO = "linux_desktop_distro"
    /** v0.33.5: a download the user paused (shown as "Download paused" after Blaze restarts). */
    private const val PREF_PAUSED = "linux_desktop_paused"
    /** v0.34.0: screen size + colour depth ("1280x720@16"), used from the next start. */
    private const val PREF_DISPLAY = "linux_desktop_display"
    /** v0.34.0: restart the desktop by itself when it dies (on by default, at most 3 times in 10 minutes). */
    private const val PREF_AUTORESTART = "linux_desktop_autorestart"

    /** v0.33.5: Stop for the running download / install (page button and the notification's Stop). */
    private val stopToken = StopToken()

    /** The chosen Linux (Alpine unless the user picked another). */
    fun distro(): DesktopDistro.Info = DesktopDistro.of(try { Settings.str(PREF_DISTRO, DesktopDistro.DEFAULT) } catch (_: Throwable) { DesktopDistro.DEFAULT })
    val distroId = MutableStateFlow("")

    /** Picks the Linux for the desktop. Not while downloading, starting or running (the page says so). */
    fun setDistro(id: String): Boolean {
        if (!DesktopFlow.canSwitch(_state.value.phase)) return false
        val d = DesktopDistro.of(id)
        Settings.setStr(PREF_DISTRO, d.id); distroId.value = d.id
        Settings.setBool(PREF_PAUSED, false)
        set(Phase.IDLE, "")
        refresh()
        return true
    }

    /**
     * The Linux the Terminal and the agent's `linux=true` commands run in: the chosen one once its base is installed,
     * else Alpine (which Blaze's own tools always set up).
     */
    fun shellDistro(): String = try { distro().id.takeIf { Distros.installed(it) } ?: DesktopDistro.DEFAULT } catch (_: Throwable) { DesktopDistro.DEFAULT }

    /** On opening the page: a paused download that never finished shows as paused. */
    fun refresh() {
        try {
            distroId.value = distro().id
            val s = _state.value
            val p = DesktopFlow.onLaunch(s.phase, Settings.bool(PREF_PAUSED, false), installed())
            if (p != s.phase) set(p, DesktopFlow.PAUSED_LINE, s.pct)
        } catch (_: Throwable) { }
    }

    private fun did() = distro().id

    /** Set by the live desktop card: open the full-screen viewer as soon as the Desktop view shows. */
    val viewerRequest = MutableStateFlow(false)
    fun requestViewer() { viewerRequest.value = true }

    /**
     * v0.25.5: starts (and installs if needed) in Blaze's own scope, not the screen's, so leaving the Desktop view or
     * closing Settings doesn't cancel a download half-way. The caller can await the plain result.
     */
    fun startAsync(ctx: Context): Deferred<String> { val app = ctx.applicationContext; return scope.async { start(app) } }

    private fun set(p: Phase, line: String, pct: Int? = null, err: String = "") {
        // v0.33.5: once Stop was tapped, late progress lines from the download being torn down don't flip the page back.
        if (stopToken.stopped && (p == Phase.INSTALLING || p == Phase.ERROR)) return
        val was = _state.value.phase
        _state.value = State(p, line, pct, err)
        // v0.45.2 Keep desktop running: the foreground service follows the phase (started when it runs, gone when stopped).
        if (was != p && (p == Phase.RUNNING || p == Phase.STOPPED || p == Phase.ERROR || p == Phase.KILLED || p == Phase.IDLE))
            appCtx?.let { c -> try { DesktopAlive.sync(c) } catch (_: Throwable) { } }
    }

    /** v0.45.2: the desktop should be on (it was started and not stopped by the user). */
    fun desktopOn(): Boolean = try { Settings.bool(PREF_ON, false) } catch (_: Throwable) { false }

    /** v0.45.2: the keep-alive service's check (every 15 s): a desktop that stopped answering gets repaired or marked killed. */
    suspend fun keepAliveCheck(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
        if (_state.value.phase != Phase.RUNNING || alive()) return
        if (!watchdogCheck()) { if (!userStopped) set(Phase.KILLED, KeepAlive.killedNote(KeepAlive.on())) }
    }

    fun cpuOk(): Boolean = DesktopPlan.supported(android.os.Build.SUPPORTED_ABIS.firstOrNull()) && Linux.supported()

    fun installed(): Boolean = installed(distro())

    /** [d]'s base Linux and its desktop packages are both installed (for Alpine: the same check as before v0.33.5). */
    fun installed(d: DesktopDistro.Info): Boolean = try {
        Distros.installed(d.id) && File(Linux.rootfs(d.id), DesktopPlan.INSTALLED_MARK.removePrefix("/")).exists()
    } catch (_: Throwable) { false }

    /** True when it was running and Android ended it (shown as a Resume card). */
    fun wasKilled(): Boolean = Settings.bool(PREF_ON, false) && _state.value.phase != Phase.RUNNING && _state.value.phase != Phase.STARTING

    fun freeMb(ctx: Context): Long = try {
        val mi = android.app.ActivityManager.MemoryInfo()
        ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi); mi.availMem / 1_000_000
    } catch (_: Throwable) { 1_000 }

    /** Installs if needed, then starts. Safe to call twice. Returns a plain sentence. */
    suspend fun start(ctx: Context): String = lock.withLock {
        if (!cpuOk()) { set(Phase.UNSUPPORTED, DesktopPlan.UNSUPPORTED); return DesktopPlan.UNSUPPORTED }
        // v0.43.0: "already running" only when the port really answers AND we hold its password (after Blaze restarts the
        // password is gone from memory, so the viewer couldn't sign in: restart it cleanly instead).
        if (alive()) {
            if (password.isNotBlank()) { set(Phase.RUNNING, "Running"); appCtx = ctx.applicationContext; userStopped = false; startWatchdog(); return "The desktop is running." }
        }
        if (freeMb(ctx) < DesktopPlan.MIN_FREE_MB) {
            val m = "Your phone is low on free memory right now. Close a few apps, then tap Start again."
            set(Phase.ERROR, m, err = m); return m
        }
        stopToken.reset()
        userStopped = false
        appCtx = ctx.applicationContext
        Settings.setBool(PREF_PAUSED, false)
        DesktopKeeper.clearPaused(ctx.applicationContext)
        val d = distro()
        try {
            if (!Distros.installed(d.id) || !installed(d)) {
                // Keep the process (and Wi-Fi) awake while downloading, even with the screen off.
                set(Phase.INSTALLING, "Getting ready to download\u2026", 0)
                DesktopKeeper.start(ctx.applicationContext)
                try {
                    if (!Distros.installed(d.id)) {
                        set(Phase.INSTALLING, if (d.id == DesktopDistro.DEFAULT) "Setting up Linux first\u2026" else "Setting up ${d.name} first\u2026", 0)
                        val flow = if (d.id == DesktopDistro.DEFAULT) Linux.status else Distros.status
                        val mirror = scope.launch { flow.collect { if (it.isNotBlank()) set(Phase.INSTALLING, it, _state.value.pct) } }
                        val r = try { Distros.install(d.id, stopToken) } finally { mirror.cancel() }
                        if (stopToken.stopped) return paused(ctx)
                        if (!Distros.installed(d.id)) { set(Phase.ERROR, r, err = r); return r }
                    }
                    if (!installed(d)) {
                        val r = install(d)
                        if (r == DesktopFlow.STOPPED_MARK || stopToken.stopped) return paused(ctx)
                        if (r != null) { set(Phase.ERROR, r, err = r); return r }
                    }
                } finally { DesktopKeeper.stop(ctx.applicationContext) }
            }
            if (stopToken.stopped) return paused(ctx)
            set(Phase.STARTING, "Starting the desktop\u2026")
            return bringUp(d)
        } catch (e: Exception) {
            if (stopToken.stopped) return paused(ctx)
            val m = "The desktop didn't start: ${e.message ?: e.javaClass.simpleName}"
            set(Phase.ERROR, m, err = m); m
        }
    }

    /** The last failed start's log lines (shown under Repair, and sent to the agent). */
    @Volatile var lastLog: List<String> = emptyList(); private set

    /**
     * v0.43.0: starts Xvnc + XFCE and reports success only once port 5901 accepts a VNC connection (up to 45 s; a
     * start job that exits early stops the wait). On failure: [Phase.ERROR] with a plain line, and the result carries
     * the last lines of /tmp/xvnc.log.
     */
    private suspend fun bringUp(d: DesktopDistro.Info): String {
        val t0 = System.currentTimeMillis()
        Terminal.run(DesktopPlan.STOP_SCRIPT, timeoutSec = 20, linux = true, distro = d.id)
        if (jobId > 0) { try { Terminal.stopJob(jobId) } catch (_: Exception) { }; jobId = 0 }
        password = DesktopPlan.password()
        Terminal.run(DesktopPlan.passwdScript(password), timeoutSec = 30, linux = true, distro = d.id)
        val disp = display()
        val out = Terminal.startJob(DesktopPlan.startScript(disp.w, disp.h, disp.depth), linux = true, maxMinutes = 0, distro = d.id)
        jobId = Regex("Started job (\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        set(Phase.STARTING, "Starting the desktop\u2026 checking it answers")
        val w = DesktopHealth.waitUntilUp(timeoutMs = 45_000, intervalMs = 500,
            check = { probe() }, stop = { jobId == 0 || !Terminal.jobAlive(jobId) })
        val ms = System.currentTimeMillis() - t0
        try { android.util.Log.i("BlazeSpeed", "desktop start ${if (w.ok) "up" else "failed"} in $ms ms, ${w.attempts} port checks, last=${w.last}") } catch (_: Throwable) { }
        if (w.ok) {
            lastLog = emptyList()
            Settings.setBool(PREF_ON, true); running = disp; set(Phase.RUNNING, "Running"); startWatchdog()
            return "The desktop is running. Open the Desktop view to see it."
        }
        val jobTail = if (jobId > 0) Terminal.jobLog(jobId, 30) else out
        val marks = jobTail.lines().drop(1).filter { DesktopPlan.FAIL_MARK in it || DesktopPlan.EXIT_MARK in it }.joinToString("\n")
        val log = DesktopHealth.logTail(marks + "\n" + Terminal.run(DesktopPlan.LOG_TAIL_SCRIPT, timeoutSec = 15, linux = true, distro = d.id), 10)
        lastLog = log
        if (jobId > 0) { try { Terminal.stopJob(jobId) } catch (_: Exception) { }; jobId = 0 }
        Settings.setBool(PREF_ON, false)
        val m = DesktopHealth.failLine(log)
        set(Phase.ERROR, m, err = m)
        return DesktopHealth.failedResult(log)
    }

    /**
     * v0.43.0 Repair: stops everything, clears stale locks / sockets / the launcher folder, re-runs the finishing
     * step's links (Xvnc, vncpasswd), then starts again with the port check. Returns the start's result.
     */
    suspend fun repair(ctx: Context): String = lock.withLock {
        if (!cpuOk()) return DesktopPlan.UNSUPPORTED
        val d = distro()
        if (!installed(d)) return "The desktop isn't installed yet. Tap Start to install it."
        appCtx = ctx.applicationContext; userStopped = false
        watchdog?.cancel(); watchdog = null
        set(Phase.STARTING, "Repairing the desktop\u2026")
        try {
            Terminal.run(DesktopPlan.STOP_SCRIPT, timeoutSec = 20, linux = true, distro = d.id)
            if (jobId > 0) { try { Terminal.stopJob(jobId) } catch (_: Exception) { }; jobId = 0 }
            val r = Terminal.run(DesktopPlan.REPAIR_SCRIPT, timeoutSec = 30, linux = true, distro = d.id)
            if ("NO_XVNC" in r) {
                val m = "The desktop's display server is missing. Remove the downloaded files and tap Start to reinstall it."
                set(Phase.ERROR, m, err = m); return "FAILED: Xvnc is not installed in ${d.name}. $m"
            }
            bringUp(d)
        } catch (e: Exception) {
            val m = "Repair didn't finish: ${e.message ?: e.javaClass.simpleName}"
            set(Phase.ERROR, m, err = m); "FAILED: $m"
        }
    }

    fun repairAsync(ctx: Context): Deferred<String> { val app = ctx.applicationContext; return scope.async { repair(app) } }

    /**
     * apk add with progress, step by step with retries and mirror fallback ([DesktopInstall]); null on success, else a
     * plain error. The mirror that last worked is remembered for next time.
     */
    private suspend fun install(d: DesktopDistro.Info): String? = withContext(Dispatchers.IO) {
        set(Phase.INSTALLING, "Downloading the desktop\u2026 " + d.sizeLine(), 0)
        val alpine = d.id == DesktopDistro.DEFAULT
        val start = Settings.str(PREF_MIRROR, "0").toIntOrNull() ?: 0
        DesktopInstall.run(
            exec = { script, onLine -> runStep(script, onLine, d.id) },
            status = { pct, line -> set(Phase.INSTALLING, line, pct ?: _state.value.pct) },
            sleep = stopToken.sleep,
            startMirror = if (alpine) start else 0,
            onMirror = { if (alpine) Settings.setStr(PREF_MIRROR, it.toString()) },
            prefetch = if (alpine) ::prefetch else null,
            distro = d,
            stopped = stopToken.cancelled,
        )
    }

    /** v0.33.5: the download stopped by the user: keep everything, show "Download paused". */
    private fun paused(ctx: Context): String {
        val pct = _state.value.pct
        _state.value = State(Phase.PAUSED, DesktopFlow.PAUSED_LINE, pct)
        try { Settings.setBool(PREF_PAUSED, true) } catch (_: Throwable) { }
        DesktopKeeper.showPaused(ctx.applicationContext, keptBytes())
        return "The desktop download is paused. What's downloaded so far is kept; Start carries on from there."
    }

    /**
     * Stop from the page or the notification. During a download or install it pauses (kills the install process,
     * closes the download, keeps every file); otherwise it stops the running desktop.
     */
    fun stopInstall(ctx: Context): String {
        val ph = _state.value.phase
        // Only the desktop's own install (it holds the token); a Terminal-started Linux install isn't ours to stop.
        val downloading = ph == Phase.INSTALLING
        return if (downloading) {
            stopToken.stop()
            _state.value = State(Phase.PAUSED, "Stopping the download\u2026", _state.value.pct)
            "Pausing the download."
        } else { scope.launch { stop() }; "Stopping the desktop." }
    }

    /** Bytes kept on the phone for the chosen Linux's unfinished download (partial archives, package caches, staged packages). */
    fun keptBytes(): Long = try {
        val d = distro()
        DesktopDistro.removalPlan(d, Distros.installed(d.id), alpineArchive()).sumOf { p -> sizeOf(resolve(p)) }
    } catch (_: Throwable) { 0 }

    private fun alpineArchive() = "alpine-${com.blaze.assistant.terminal.AlpineNet.VER}-${com.blaze.assistant.terminal.AlpineNet.arch(android.os.Build.SUPPORTED_ABIS.firstOrNull())}.tar.gz"

    private fun resolve(p: String): File = File(Linux.base(), p.removeSuffix("/*"))

    /** Size without following symlinks (a rootfs links to /proc, /sdcard…). */
    private fun sizeOf(f: File): Long {
        val path = f.toPath()
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0
        if (java.nio.file.Files.isSymbolicLink(path)) return 0
        if (!f.isDirectory) return f.length()
        return (f.listFiles() ?: emptyArray()).sumOf { sizeOf(it) }
    }

    /** Deletes without following symlinks; [keepDir] keeps [f] itself (only its contents go). */
    private fun delete(f: File, keepDir: Boolean = false) {
        val path = f.toPath()
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (!java.nio.file.Files.isSymbolicLink(path) && f.isDirectory) (f.listFiles() ?: emptyArray()).forEach { delete(it, keepDir = keepDir && it.isDirectory && it.name == "partial") }
        if (!keepDir) runCatching { java.nio.file.Files.deleteIfExists(path) }
    }

    /**
     * v0.33.5 "Remove downloaded files": deletes the chosen Linux's partial downloads and package caches (and the
     * Linux itself when it never finished installing; an installed Alpine always stays). Not while anything runs.
     */
    suspend fun removeDownloads(): String = withContext(Dispatchers.IO) {
        if (!DesktopFlow.canSwitch(_state.value.phase)) return@withContext "Stop the download first."
        lock.withLock {
            val d = distro()
            val before = keptBytes()
            for (p in DesktopDistro.removalPlan(d, Distros.installed(d.id), alpineArchive())) delete(resolve(p), keepDir = p.endsWith("/*"))
            Settings.setBool(PREF_PAUSED, false)
            _state.value = State(DesktopFlow.afterRemove(), "")
            "Removed %.1f MB of downloaded files.".format(java.util.Locale.US, before / 1_000_000.0)
        }
    }

    /**
     * v0.33.3: downloads a batch's missing packages with [com.blaze.assistant.terminal.Fetch] (resumable, many retries,
     * mirrors) into the rootfs stage folder. Null when apk couldn't say what is missing (the step then uses plain apk add).
     */
    private fun prefetch(mirror: Int, pkgs: List<String>, update: Boolean): List<DesktopPrefetch.Pkg>? {
        val mirrors = com.blaze.assistant.terminal.AlpineNet.MIRRORS
        val full = StringBuilder()
        val sim = runStep(DesktopPrefetch.simulateScript(mirrors[mirror], pkgs, update), { full.append(it).append('\n') })
        if (sim.code != 0 || "BLAZE_SIM_OK" !in sim.tail) return null
        val list = DesktopPrefetch.parseSimulate(full.toString())
        if (list.isEmpty()) return emptyList()
        val arch = com.blaze.assistant.terminal.AlpineNet.arch(android.os.Build.SUPPORTED_ABIS.firstOrNull())
        val stage = File(Linux.rootfs(), DesktopPrefetch.STAGE).also { it.mkdirs() }
        var done = 0L
        list.forEachIndexed { i, p ->
            val dest = File(stage, p.file)
            if (dest.isFile && dest.length() > 0) { done += dest.length(); return@forEachIndexed }
            val resuming = File(dest.path + ".part").let { it.exists() && it.length() > 0 }
            val base = done
            com.blaze.assistant.terminal.Fetch.download(DesktopPrefetch.urls(p, arch, mirrors, mirror), dest, "",
                progress = { got, _, retry ->
                    set(Phase.INSTALLING, retry ?: DesktopPrefetch.batchLine(p, i, list.size, base + got, resuming), _state.value.pct)
                }, sleep = stopToken.sleep, open = stopToken::open, cancelled = stopToken.cancelled)
            done += dest.length()
        }
        return list
    }

    /** Runs one install step inside Alpine, streaming its output; never throws. */
    private fun runStep(script: String, onLine: (String) -> Unit, distro: String = DesktopDistro.DEFAULT): DesktopInstall.Result {
        if (stopToken.stopped) return DesktopInstall.Result(-1, DesktopFlow.STOPPED_MARK)
        val p = try {
            ProcessBuilder(Linux.command(script, distro = distro)).redirectErrorStream(true)
                .also { it.environment().putAll(Linux.env()) }.start()
        } catch (e: Exception) { return DesktopInstall.Result(-1, "ERROR: couldn't start the installer: ${e.message}") }
        stopToken.track(p)
        try { p.outputStream.close() } catch (_: Exception) { }
        val tail = ArrayDeque<String>()
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                tail.addLast(line); if (tail.size > 12) tail.removeFirst()
                onLine(line)
            }
        } catch (e: Exception) { tail.addLast("ERROR: ${e.message}") }
        val code = try { p.waitFor() } catch (_: Exception) { -1 }
        stopToken.track(null)
        return DesktopInstall.Result(code, tail.joinToString("\n"))
    }

    /** v0.43.0: the desktop is up only when port 5901 answers with a VNC greeting ([DesktopHealth]). */
    suspend fun alive(): Boolean = probe() == DesktopHealth.Probe.UP

    private suspend fun probe(): DesktopHealth.Probe = withContext(Dispatchers.IO) { DesktopHealth.probe() }

    /** Kills every desktop process and frees its memory. */
    suspend fun stop(): String {
        set(Phase.STOPPING, "Stopping\u2026")
        userStopped = true
        watchdog?.cancel(); watchdog = null
        Settings.setBool(PREF_ON, false)
        try { Terminal.run(DesktopPlan.STOP_SCRIPT, timeoutSec = 20, linux = true, distro = did()) } catch (_: Exception) { }
        if (jobId > 0) try { Terminal.stopJob(jobId) } catch (_: Exception) { }
        jobId = 0; password = ""; clickOk = false
        System.gc()
        set(Phase.STOPPED, "Stopped. Memory freed.")
        return "The desktop is stopped."
    }

    /** The viewer lost its connection: if the X server is gone, Android (or a crash) ended it. */
    fun lost() { scope.launch { if (!alive() && !watchdogCheck()) set(Phase.KILLED, KeepAlive.killedNote(KeepAlive.on())) } }

    // ---------- v0.34.0 auto-restart watchdog ----------
    @Volatile private var userStopped = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var watchdog: kotlinx.coroutines.Job? = null
    private val restarts = ArrayList<Long>()

    fun autoRestart(): Boolean = try { Settings.bool(PREF_AUTORESTART, true) } catch (_: Throwable) { true }
    fun setAutoRestart(on: Boolean) { Settings.setBool(PREF_AUTORESTART, on); if (on && _state.value.phase == Phase.RUNNING) startWatchdog() }

    /** Checks every 20 s while the desktop should be up; Android or a crash ending it brings it back ([DesktopExtras.shouldRestart]). */
    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (true) {
                delay(20_000)
                if (userStopped || !Settings.bool(PREF_ON, false)) break
                val ph = _state.value.phase
                if (ph == Phase.INSTALLING || ph == Phase.STARTING || ph == Phase.STOPPING || ph == Phase.PAUSED) continue
                if (alive()) continue
                if (!watchdogCheck()) { if (!userStopped) set(Phase.KILLED, KeepAlive.killedNote(KeepAlive.on())); break }
            }
        }
    }

    /** True when it is restarting the desktop; false when the rule says no (setting off, stopped by the user, or 3 restarts in 10 min). */
    private fun watchdogCheck(): Boolean {
        val ctx = appCtx ?: return false
        val now = System.currentTimeMillis()
        val recent = synchronized(restarts) { restarts.removeAll { now - it > 10 * 60_000L }; restarts.toList() }
        // v0.45.2: Keep desktop running also restarts it (same 3-in-10-minutes cap), even with "Restart by itself" off.
        if (!KeepAlive.shouldRepair(KeepAlive.on(), autoRestart(), Settings.bool(PREF_ON, false), userStopped, alive = false, recent = recent, now = now)) return false
        synchronized(restarts) { restarts += now }
        set(Phase.STARTING, "The desktop closed. Restarting it\u2026")
        scope.launch { start(ctx) }
        return true
    }

    // ---------- v0.34.0 display ----------
    fun display(): DesktopExtras.Display = try { DesktopExtras.parseDisplay(Settings.str(PREF_DISPLAY, "")) } catch (_: Throwable) { DesktopExtras.DISPLAYS[0] }
    fun setDisplay(d: DesktopExtras.Display) = Settings.setStr(PREF_DISPLAY, DesktopExtras.displayKey(d))
    /** The size the running desktop was started with (the setting applies from the next start). */
    @Volatile var running: DesktopExtras.Display = DesktopExtras.DISPLAYS[0]; private set

    // ---------- v0.34.0 app drawer + curated store ----------
    private fun phoneDir(ctx: Context) = File(ctx.filesDir, "home")

    /** Every app the desktop's .desktop files list (read through a file: the list is longer than a command's output keeps). */
    suspend fun apps(ctx: Context): List<DesktopExtras.AppEntry> = withContext(Dispatchers.IO) {
        if (!installed()) return@withContext emptyList()
        val f = File(phoneDir(ctx), ".desktop_apps.txt"); f.delete()
        Terminal.run("{ " + DesktopExtras.LIST_APPS_SCRIPT + "; } > /root/phone/.desktop_apps.txt 2>/dev/null; echo listed", timeoutSec = 30, linux = true, distro = did())
        val out = try { f.readText() } catch (_: Exception) { "" }
        f.delete()
        DesktopExtras.parseEntries(out)
    }

    suspend fun launch(e: DesktopExtras.AppEntry): String {
        if (!alive()) return "The desktop isn't running."
        Terminal.run(DesktopExtras.launchCmd(e), timeoutSec = 15, linux = true, distro = did())
        return "Opened ${e.name}."
    }

    suspend fun launchStore(app: DesktopExtras.StoreApp): String {
        if (!alive()) return "Installed. Start the desktop to open it."
        Terminal.run(DesktopExtras.launchApp(app), timeoutSec = 15, linux = true, distro = did())
        return "Opened ${app.name}."
    }

    /** One store install at a time (its progress, for the page). */
    val storeBusy = MutableStateFlow<String?>(null)
    val storeLine = MutableStateFlow("")

    /** Installs a curated app with the Linux's own package manager; returns a plain sentence. No time limit (LibreOffice is big). */
    suspend fun installApp(app: DesktopExtras.StoreApp): String = withContext(Dispatchers.IO) {
        val d = distro()
        if (!installed(d)) return@withContext "Install the desktop first (Start on the Desktop page)."
        val script = DesktopExtras.installScript(app, d) ?: return@withContext DesktopExtras.whyNot(app, d)
        storeBusy.value?.let { return@withContext "Already installing $it. One at a time." }
        storeBusy.value = app.name; storeLine.value = "Installing ${app.name}\u2026"
        try {
            val tail = ArrayDeque<String>()
            val p = ProcessBuilder(Linux.command(script, distro = d.id)).redirectErrorStream(true).also { it.environment().putAll(Linux.env()) }.start()
            try { p.outputStream.close() } catch (_: Exception) { }
            p.inputStream.bufferedReader().forEachLine { line ->
                tail.addLast(line); if (tail.size > 8) tail.removeFirst()
                val l = line.trim()
                if (l.isNotEmpty()) storeLine.value = (DesktopPlan.progressLine(l)?.second ?: l).take(90)
            }
            val code = p.waitFor()
            if (code == 0 && tail.any { "BLAZE_STORE_OK" in it }) "${app.name} is installed."
            else "${app.name} didn't install: " + tail.filter { it.isNotBlank() && "BLAZE_STORE_OK" !in it }.takeLast(2).joinToString(" ").take(240)
        } catch (e: Exception) { "${app.name} didn't install: ${e.message}" }
        finally { storeBusy.value = null; storeLine.value = "" }
    }

    // ---------- v0.34.0 snapshots ----------
    private fun snapDir(ctx: Context) = File(phoneDir(ctx), ".snapshots")

    fun snapshots(ctx: Context): List<DesktopExtras.Snapshot> = (snapDir(ctx).listFiles() ?: emptyArray<File>())
        .mapNotNull { f -> if (f.isFile) DesktopExtras.parseSnap(f.name, f.length()) else null }.sortedByDescending { it.at }

    val snapBusy = MutableStateFlow(false)

    /** Packs the chosen Linux (everything installed and changed in it) into one file in Blaze's phone folder. */
    suspend fun createSnapshot(ctx: Context): String = withContext(Dispatchers.IO) {
        val d = distro()
        if (!installed(d)) return@withContext "Install the desktop first."
        if (snapBusy.value) return@withContext "A snapshot is already being made or restored."
        val rootBytes = sizeOf(Linux.rootfs(d.id))
        val dir = snapDir(ctx).apply { mkdirs() }
        if (!DesktopExtras.roomForSnapshot(rootBytes, dir.usableSpace))
            return@withContext "Not enough free space: a snapshot of ${d.name} needs about ${(rootBytes / 2 + 200_000_000L) / 1_000_000} MB free."
        snapBusy.value = true
        try {
            val name = DesktopExtras.snapName(d.id, System.currentTimeMillis())
            val r = Terminal.run(DesktopExtras.snapshotScript(name), timeoutSec = 600, linux = true, distro = d.id)
            if ("BLAZE_SNAP_OK" in r) "Snapshot saved (%.0f MB).".format(java.util.Locale.US, File(dir, name).length() / 1_000_000.0)
            else { File(dir, "$name.part").delete(); "The snapshot didn't finish: " + r.lines().drop(1).filter { it.isNotBlank() }.takeLast(2).joinToString(" ").take(200) }
        } finally { snapBusy.value = false }
    }

    /**
     * Puts the Linux back the way it was in [snap]: unpacked next to it first, then swapped in, so a failed restore
     * leaves the current Linux untouched. Only while the desktop is stopped.
     */
    suspend fun restoreSnapshot(ctx: Context, snap: DesktopExtras.Snapshot): String = withContext(Dispatchers.IO) {
        val ph = _state.value.phase
        if (ph == Phase.RUNNING || ph == Phase.STARTING || ph == Phase.INSTALLING || alive()) return@withContext "Stop the desktop first."
        if (snap.distro != distro().id) return@withContext "That snapshot is of ${DesktopDistro.of(snap.distro).name}; pick that Linux first."
        if (snapBusy.value) return@withContext "A snapshot is already being made or restored."
        val f = File(snapDir(ctx), snap.file)
        if (!f.isFile) return@withContext "That snapshot is gone."
        snapBusy.value = true
        try {
            lock.withLock {
                val id = snap.distro
                val stage = File(Linux.base(), ".restore-$id"); val old = File(Linux.base(), ".old-$id")
                try {
                    delete(stage); delete(old); stage.mkdirs()
                    Linux.untarStream(java.util.zip.GZIPInputStream(java.io.BufferedInputStream(f.inputStream(), 256 * 1024)), stage, 0) { }
                    if (!File(stage, "bin/sh").exists() && !File(stage, "usr/bin/sh").exists() && !File(stage, "bin/busybox").exists()) {
                        delete(stage); "That snapshot looks broken (no shell in it). Nothing was changed."
                    } else {
                        listOf("proc", "sys", "dev", "tmp", "sdcard", "root/phone").forEach { File(stage, it).mkdirs() }
                        val r = Linux.rootfs(id)
                        if (r.exists() && !r.renameTo(old)) { delete(stage); "Couldn't swap the Linux folders. Nothing was changed." }
                        else if (!stage.renameTo(r)) { old.renameTo(r); "Couldn't swap the Linux folders. Nothing was changed." }
                        else {
                            Linux.prepare(r, id)
                            if (!File(r, ".blaze_ok").exists()) File(r, ".blaze_ok").writeText("restored")
                            delete(old)
                            set(Phase.IDLE, "")
                            "Restored the snapshot from " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(snap.at)) + "."
                        }
                    }
                } catch (e: Exception) {
                    delete(stage)
                    if (!Linux.rootfs(id).exists() && old.exists()) old.renameTo(Linux.rootfs(id))
                    "The restore didn't finish (${e.message ?: e.javaClass.simpleName}). Your Linux wasn't changed."
                }
            }
        } finally { snapBusy.value = false }
    }

    fun deleteSnapshot(ctx: Context, snap: DesktopExtras.Snapshot): Boolean = File(snapDir(ctx), snap.file).delete()


    // ---------- agent tool ----------
    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", "desktop")
        .put("description", "A real Linux desktop (XFCE) inside Blaze's ${distro().name} Linux (the user's choice on the Desktop page; " +
            "install software in it with ${distro().pm.tool}), shown in the Desktop view. " +
            "start (installs on first use; its result starts with VERIFIED only when the desktop really answers, FAILED otherwise: never tell the user it runs, or that an app in it opened, without VERIFIED, ~${distro().downloadMb} MB; while it downloads, stop pauses it and start resumes), stop, status (checks the port; trust only its words), repair (clears stale locks and restarts), screenshot (attached as an image; coordinates are in that picture's pixels), " +
            "click x y [button 1 left / 3 right] [double], type text, key keys (xdotool names like ctrl+s Return), run app (terminal, files, browser, editor or a command), open (show the Desktop view), " +
            "apps (the installed apps), launch app=<name from apps>, store (curated apps for this Linux), install app=<store id>, snapshot (save the Linux as it is now), snapshots (list them). " +
            "For click and type, set label to what you're clicking or typing into in 1-3 plain words (\"Save\", \"Terminal\"): the user watches a live view with that label.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("start", "stop", "status", "repair", "screenshot", "click", "type", "key", "run", "open", "apps", "launch", "store", "install", "snapshot", "snapshots"))))
            .put("x", JSONObject().put("type", "integer")).put("y", JSONObject().put("type", "integer"))
            .put("button", JSONObject().put("type", "integer")).put("double", JSONObject().put("type", "boolean"))
            .put("text", JSONObject().put("type", "string")).put("keys", JSONObject().put("type", "string"))
            .put("app", JSONObject().put("type", "string"))
            .put("label", JSONObject().put("type", "string").put("description", "What you're clicking or typing into, 1-3 plain words, shown to the user (e.g. Save, Terminal, File menu).")))
            .put("required", JSONArray(listOf("action")))))

    /** The screenshot as a data URL (≤ 1024 px), or null. */
    suspend fun screenshot(ctx: Context): String? = withContext(Dispatchers.IO) {
        val r = Terminal.run(DesktopPlan.screenshotCmd(), timeoutSec = 30, linux = true, distro = did())
        if (!r.contains("ok")) return@withContext null
        val f = File(File(ctx.filesDir, "home"), ".desktop_shot.png")
        if (!f.exists()) return@withContext null
        val b = f.readBytes(); f.delete()
        DesktopLive.shot(b)
        "data:image/png;base64," + android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    }

    /**
     * Runs one tool call. [confirm] asks the user (Blaze's usual approval card); start (a big download the first time),
     * type, key and run always ask; click asks unless the user already approved desktop control this session.
     */
    suspend fun run(ctx: Context, args: JSONObject, shots: MutableList<String>, confirm: suspend (String, String) -> Boolean, open: () -> Unit,
                    cmdOk: DesktopCmdOk? = null): String {
        val a = args.optString("action")
        // v0.25.5: the user tapped X on the live desktop card.
        if (DesktopLive.stopped && a !in setOf("status", "stop")) return "The user tapped X on the live desktop card to stop your desktop work. " +
            "Don't use the desktop tool again in this task unless the user asks; tell them you stopped and what was done so far."
        val words = DesktopNarration.status(args, installed())
        // Without a policy (old callers) every command goes through the old checks unchanged.
        val ok: DesktopCmdOk = cmdOk ?: { _, _, _, _, _, legacy -> legacy() }
        val r = act(ctx, a, args, shots, confirm, open, words, ok)
        return r
    }

    /** Wraps one desktop action in the live card's begin / end (only after the user's yes, so the card shows real work). */
    private suspend fun <T> live(words: String?, x: Int = -1, y: Int = -1, block: suspend () -> T): T {
        if (words == null) return block()
        DesktopLive.begin(words, x, y)
        try { return block() } finally { DesktopLive.end() }
    }

    private suspend fun act(ctx: Context, a: String, args: JSONObject, shots: MutableList<String>, confirm: suspend (String, String) -> Boolean, open: () -> Unit, words: String?, ok: DesktopCmdOk): String {
        if (a !in setOf("status", "stop", "open") && !cpuOk()) return DesktopPlan.UNSUPPORTED
        val needsUp = a in setOf("screenshot", "click", "type", "key", "run")
        if (needsUp && !alive()) return "The desktop isn't running. Call desktop action=start first."
        return when (a) {
            "start" -> {
                if (!installed()) {
                    if (!confirm("Install the Linux desktop (${distro().name})?", distro().sizeLine())) "The user said no; nothing was installed."
                    // The first install takes minutes: it runs in the background and the Desktop view shows progress.
                    else { scope.launch { start(ctx) }; open(); "Installing the ${distro().name} desktop in the background (${distro().sizeLine()}). Call desktop action=status in a few minutes." }
                } else live(words) { start(ctx) }.let { r -> if (r.startsWith("The desktop is running")) "VERIFIED: the Linux desktop is running (it answered just now). The user can watch it in Blaze (live card / View desktop); never suggest a VNC client." else r }
            }
            "repair" -> live(words) { repair(ctx) }.let { r -> if (r.startsWith("The desktop is running")) "VERIFIED: repaired; the Linux desktop is running (it answered just now)." else r }
            "stop" -> if (_state.value.phase == Phase.INSTALLING) stopInstall(ctx) else live(words) { stop() }
            "status" -> when {
                !cpuOk() -> DesktopPlan.UNSUPPORTED
                _state.value.phase == Phase.INSTALLING -> "Installing ${distro().name}: ${_state.value.line}"
                _state.value.phase == Phase.PAUSED -> "${distro().name} download paused by the user; start resumes it."
                alive() -> "VERIFIED running: ${distro().name} at ${running.w}x${running.h}, ${running.depth}-bit colour, only reachable inside Blaze (it answered just now). The user watches it in Blaze (View desktop), not with a VNC client."
                _state.value.phase == Phase.ERROR -> "NOT running: the last start failed. ${_state.value.error}" + (if (lastLog.isNotEmpty()) " Log: " + lastLog.joinToString(" | ").take(400) else "") + " Don't say it is running; offer action=repair."
                installed() -> "NOT running: ${distro().name} desktop installed, but nothing answers on port 5901. Don't say it is running."
                else -> "Not installed (start installs ${distro().name}: ${distro().sizeLine()})"
            }
            "open" -> { open(); "Opened the Desktop view." }
            "screenshot" -> live(words) { screenshot(ctx) }?.let { shots += it; "Desktop screenshot attached in the next message (coordinates are in its pixels; the desktop is ${running.w}x${running.h})." }
                ?: "Couldn't take a desktop screenshot."
            "click" -> {
                val x = args.optInt("x", -1); val y = args.optInt("y", -1)
                if (x < 0 || y < 0) "click needs x and y."
                else if (!clickOk && !confirm("Let Blaze click in the Linux desktop?", "At $x, $y. You'll be asked once per session.").also { clickOk = it }) "The user said no."
                else {
                    // v0.34.0: screenshots are at most 1024 px, so a bigger screen's clicks are scaled back up.
                    val k = maxOf(1.0, maxOf(running.w, running.h) / 1024.0)
                    live(words, x, y) { Terminal.run(DesktopPlan.clickCmd((x * k).toInt(), (y * k).toInt(), args.optInt("button", 1), args.optBoolean("double")), timeoutSec = 15, linux = true, distro = did()) }.let { "Clicked at $x, $y." }
                }
            }
            "type" -> {
                val t = args.optString("text")
                if (t.isBlank()) "type needs text."
                // v0.45.3: Normal mode types safe commands without a card and asks for risky ones (one card with the warning);
                // Autopilot keeps the old two checks. v0.34.0: a plain warning before anything that could delete files or break the Linux.
                else if (!ok(com.blaze.assistant.agent.CommandPolicy.Kind.DESKTOP_TYPE, t, if (DesktopExtras.dangerous(t)) DesktopExtras.dangerTitle("Type it anyway?") else "Type in the Linux desktop?", "\u201c${t.take(160)}\u201d", DesktopExtras.dangerous(t)) {
                        confirm("Type in the Linux desktop?", "\u201c${t.take(120)}\u201d") && (!DesktopExtras.dangerous(t) || confirm(DesktopExtras.dangerTitle("Type it anyway?"), "\u201c${t.take(200)}\u201d")) })
                    "The user said no."
                else live(words) { Terminal.run(DesktopPlan.typeCmd(t), timeoutSec = 60, linux = true, distro = did()) }.let { "Typed ${t.length} characters." }
            }
            "key" -> {
                val k = args.optString("keys")
                if (k.isBlank()) "key needs keys."
                else if (!ok(com.blaze.assistant.agent.CommandPolicy.Kind.DESKTOP_KEY, k, "Press keys in the Linux desktop?", k, false) { confirm("Press keys in the Linux desktop?", k) }) "The user said no."
                else live(words) { Terminal.run(DesktopPlan.keyCmd(k), timeoutSec = 15, linux = true, distro = did()) }.let { "Pressed $k." }
            }
            "run" -> {
                val app = args.optString("app")
                if (app.isBlank()) "run needs app."
                else if (!DesktopPlan.appCommand(app).let { cmd -> val bad = DesktopExtras.dangerous(cmd)
                        ok(com.blaze.assistant.agent.CommandPolicy.Kind.DESKTOP_OPEN, app, if (bad) DesktopExtras.dangerTitle("Run it anyway?") else "Open in the Linux desktop?", cmd.take(200), bad) {
                            confirm("Open in the Linux desktop?", cmd) && (!bad || confirm(DesktopExtras.dangerTitle("Run it anyway?"), cmd.take(200))) } }) "The user said no."
                else live(words) { Terminal.run(DesktopPlan.runCmd(app), timeoutSec = 15, linux = true, distro = did()) }.let { "Asked the desktop to open ${DesktopPlan.appCommand(app)}. Not confirmed yet: take a screenshot and check its window is there before telling the user it opened." }
            }
            "apps" -> apps(ctx).let { l -> if (l.isEmpty()) "No apps found (is the desktop installed?)." else l.joinToString("\n") { "${it.name}: ${it.exec}" } }
            "launch" -> {
                val n = args.optString("app").ifBlank { args.optString("text") }.trim()
                val list = if (n.isBlank()) emptyList() else apps(ctx)
                val e = list.firstOrNull { it.name.equals(n, true) } ?: list.firstOrNull { it.name.contains(n, true) }
                if (n.isBlank()) "launch needs app (a name from action=apps)."
                else if (e == null) "No app called \u201c$n\u201d. Call action=apps for the list."
                else if (!ok(com.blaze.assistant.agent.CommandPolicy.Kind.DESKTOP_OPEN, e.name, "Open ${e.name} in the Linux desktop?", e.exec, DesktopExtras.dangerous(e.exec)) { confirm("Open ${e.name} in the Linux desktop?", e.exec) }) "The user said no."
                else live(words) { launch(e) }
            }
            "store" -> distro().let { d -> DesktopExtras.STORE.joinToString("\n") { s -> "${s.id}: ${s.name} \u2014 ${s.about}" + (if (DesktopExtras.packagesFor(s, d) == null) " (not on ${d.name}: ${DesktopExtras.whyNot(s, d)})" else "") } }
            "install" -> {
                val s = DesktopExtras.STORE.firstOrNull { it.id == args.optString("app").trim().lowercase() }
                val pk = s?.let { DesktopExtras.packagesFor(it, distro()) }
                when {
                    s == null -> "Unknown store app. Call action=store for the ids."
                    pk == null -> DesktopExtras.whyNot(s, distro())
                    !confirm("Install ${s.name} in the Linux desktop?", "With ${distro().pm.tool}: " + pk.joinToString(" ")) -> "The user said no."
                    else -> installApp(s)
                }
            }
            "snapshot" -> if (!confirm("Save a snapshot of ${distro().name}?", "Everything installed and changed in the Linux, kept on this phone.")) "The user said no." else createSnapshot(ctx)
            "snapshots" -> snapshots(ctx).let { l -> if (l.isEmpty()) "No snapshots yet." else l.joinToString("\n") { "${it.file} (${it.bytes / 1_000_000} MB)" } + "\nRestoring is done by the user on the Desktop page." }
            else -> "Unknown action. Use start, stop, status, repair, screenshot, click, type, key, run, open, apps, launch, store, install, snapshot or snapshots."
        }
    }

    @Volatile private var clickOk = false

    /** v0.39.0 hands-free desktop (DesktopAgent): one xdotool line in the running desktop's Linux. */
    suspend fun exec(cmd: String, timeoutSec: Int = 15): String = Terminal.run(cmd, timeoutSec = timeoutSec, linux = true, distro = did())
}

/** v0.45.3: the command approval for desktop type / key / run / launch: (kind, text, title, detail, risky, the old check) → may it run. */
typealias DesktopCmdOk = suspend (com.blaze.assistant.agent.CommandPolicy.Kind, String, String, String, Boolean, suspend () -> Boolean) -> Boolean
