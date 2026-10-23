package com.blaze.assistant.terminal

import android.content.Context
import android.content.Intent
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A real shell on the phone: Android's sh plus ~300 BusyBox tools (vi, wget, httpd, tar, grep,
 * awk, nc…) shipped inside the APK. Home is filesDir/home. Long jobs (local servers) run in the
 * background. For a full Linux (pkg install python/nodejs/gradle) Blaze hands commands to Termux.
 */
object Terminal {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.blaze.assistant.device.CrashLog.guard)

    fun home(): File = File(app.filesDir, "home").apply { mkdirs() }
    private fun bin(): File = File(app.filesDir, "usr/bin")
    private fun busybox() = File(app.applicationInfo.nativeLibraryDir, "libbusybox.so")
    fun hasBusybox() = ::app.isInitialized && busybox().exists()

    fun init(ctx: Context) { app = ctx.applicationContext; Linux.init(ctx) }

    /**
     * v0.29.8: false when BusyBox can't run on this phone (the start-up probe failed), so the phone tab uses Android's own
     * sh and nothing BusyBox is linked onto PATH. Before v0.29.8 every BusyBox applet died at start with SIGSYS (signal 31):
     * the bundled builds drop privileges with setgid()/setuid(), which Android's app seccomp filter kills (the binaries
     * are now patched to skip that; see tools/busybox/README.md).
     */
    @Volatile var busyboxOk = false
        private set

    /** The phone tab saw BusyBox fail (killed by Android, or dead at once): stop using it until the app is updated. */
    fun markBusyboxBroken() {
        busyboxOk = false
        try {
            bin().deleteRecursively(); bin().mkdirs()
            File(app.filesDir, "usr/.bb").apply { parentFile?.mkdirs() }.writeText(TermLogic.bbStamp(buildKey(), TermLogic.BbVerdict(false, false)))
            writeRc()
        } catch (_: Exception) { }
    }

    /** The phone shell's start-up file (help command, prompt), sourced through \$ENV. */
    fun phoneRc(): File = File(app.filesDir, "usr/etc/phonerc")

    /**
     * Links every BusyBox applet into usr/bin once per build (cheap, no root). v0.29.8: BusyBox is first run
     * through a "busybox" link (argv[0] must be busybox: run as libbusybox.so it answered "applet not found", so
     * `--list` came back empty and no tool was ever linked), and probed before anything is linked.
     * v0.30.2: the probe is `busybox true` and then `busybox sh -c 'echo ok'` (each with a timeout, and killed if it hangs);
     * any failure means the phone tab starts straight on Android's /system/bin/sh. A second probe runs the file tools
     * (chmod, ln, rmdir) that the 32-bit BusyBox reaches through syscalls Android's 32-bit filter kills; if it fails those
     * applets are left to Android's toybox. The verdict is saved per build ([TermLogic.bbKey]: versionCode + install time).
     */
    @Synchronized fun setup() {
        if (!hasBusybox()) { busyboxOk = false; writeRc(); return }
        val stamp = File(app.filesDir, "usr/.bb")
        val key = buildKey()
        val prev = TermLogic.bbParse(key, try { stamp.readText() } catch (_: Exception) { null })
        if (prev != null && !prev.ok) { busyboxOk = false; if (!phoneRc().exists()) writeRc(); return }
        if (prev != null && File(bin(), "busybox").exists()) { busyboxOk = true; if (!phoneRc().exists()) writeRc(); return }
        bin().deleteRecursively(); bin().mkdirs()
        val link = File(bin(), "busybox")
        try { Os.symlink(busybox().absolutePath, link.absolutePath) } catch (_: Exception) { }
        val via = if (link.exists()) link.absolutePath else busybox().absolutePath
        busyboxOk = TermLogic.trueProbeOk(run(listOf(via, "true"))?.first) &&
            run(listOf(via, "sh", "-c", "echo ok"))?.let { (code, out) -> TermLogic.probeOk(code, out) } == true
        var legacyOk = true
        if (busyboxOk) {
            legacyOk = probeFileTools(via)
            val applets = run(listOf(via, "--list"))?.second?.lines() ?: emptyList()
            for (name in TermLogic.appletsToLink(applets, legacyOk)) {
                try { Os.symlink(busybox().absolutePath, File(bin(), name).absolutePath) } catch (_: Exception) { }
            }
        } else { bin().deleteRecursively(); bin().mkdirs() }
        stamp.parentFile?.mkdirs(); stamp.writeText(TermLogic.bbStamp(key, TermLogic.BbVerdict(busyboxOk, legacyOk)))
        writeRc()
    }

    /** Runs [cmd] with no stdin, returning (exit code, output), or null when it couldn't start or hung (it is killed). */
    private fun run(cmd: List<String>, seconds: Long = 5): Pair<Int, String>? = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        val out = StringBuilder()
        val reader = Thread { try { out.append(p.inputStream.bufferedReader().readText()) } catch (_: Exception) { } }.apply { isDaemon = true; start() }
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); null }
        else { reader.join(1000); p.exitValue() to synchronized(out) { out.toString() } }
    } catch (_: Exception) { null }

    /**
     * The file tools the 32-bit BusyBox runs through old syscalls (chmod, symlink, mkdir + rmdir, rm). Only a kill by
     * the sandbox counts (exit 159 = 128 + SIGSYS): a plain error, like SELinux refusing something, isn't this problem.
     */
    private fun probeFileTools(via: String): Boolean {
        val dir = File(app.cacheDir, "bbprobe").apply { deleteRecursively(); mkdirs() }
        val r = run(listOf("/system/bin/sh", "-c", TermLogic.FILE_TOOLS_PROBE, via, dir.absolutePath))
        dir.deleteRecursively()
        return TermLogic.fileToolsOk(r?.second)
    }

    /** v0.30.2: the build the BusyBox verdict belongs to (versionCode + install time, so a reinstall probes again). */
    fun buildKey(): String = try {
        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION") val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        TermLogic.bbKey(code, pi.lastUpdateTime)
    } catch (_: Exception) { TermLogic.bbKey(com.blaze.assistant.BuildConfig.VERSION_CODE.toLong(), 0) }

    private fun writeRc() {
        try { phoneRc().parentFile?.mkdirs(); phoneRc().writeText(TermLogic.phoneRc(busyboxOk)) } catch (_: Exception) { }
    }

    fun env(): Map<String, String> = mapOf(
        "HOME" to home().absolutePath,
        "PATH" to "${bin().absolutePath}:/system/bin:/system/xbin",
        "TMPDIR" to app.cacheDir.absolutePath,
        "TERM" to "dumb",
        "LANG" to "en_US.UTF-8",
        "PREFIX" to File(app.filesDir, "usr").absolutePath,
    )

    private fun builder(cmd: String) = ProcessBuilder("/system/bin/sh", "-c", cmd).directory(home()).redirectErrorStream(true)
        .also { pb -> pb.environment().putAll(env()) }

    // ---------- Interactive sessions (Terminal screen tabs) ----------

    /** One shell tab. "phone" = Android sh + BusyBox, "linux" = Alpine via PRoot. */
    class Session(val id: Int, initialMode: String) {
        val out = MutableStateFlow("")
        val mode = MutableStateFlow(initialMode)
        @Volatile var shell: Process? = null
        val history = ArrayList<String>()
        fun append(s: String) { val v = out.value + s; out.value = if (v.length > 120_000) v.takeLast(100_000) else v }
        val title get() = "${if (mode.value == "linux") "linux" else "sh"} $id"
    }

    private var nextSession = 1
    val sessions = MutableStateFlow(listOf(Session(nextSession++, "phone")))
    val active = MutableStateFlow(sessions.value.first())

    /** Read-only tab: every command Blaze itself runs, with its output, live. */
    val agentLog = MutableStateFlow("")
    fun logAgent(cmd: String, result: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val v = agentLog.value + "[$stamp] $cmd\n${result.take(4000)}\n\n"
        agentLog.value = if (v.length > 100_000) v.takeLast(80_000) else v
    }

    // Back-compat for the active tab.
    val output: StateFlow<String> get() = active.value.out
    val mode: MutableStateFlow<String> get() = active.value.mode

    fun newSession(m: String = "phone"): Session {
        val s = Session(nextSession++, if (m == "linux" && Distros.installed(defaultDistro())) "linux" else "phone")
        sessions.value = sessions.value + s
        active.value = s
        startShell()
        return s
    }

    fun select(s: Session) { active.value = s; startShell() }

    fun closeSession(s: Session) {
        s.shell?.destroy(); s.shell = null
        val left = sessions.value - s
        sessions.value = left.ifEmpty { listOf(Session(nextSession++, "phone")) }
        if (active.value == s) active.value = sessions.value.last()
        startShell()
    }

    fun startShell(sess: Session = active.value) {
        if (sess.shell?.isAlive == true) return
        if (sess.out.value.isEmpty()) sess.append(banner(sess))
        scope.launch {
            synchronized(sess) {
                if (sess.shell?.isAlive == true) return@launch
                try {
                    setup()
                    val dist = defaultDistro()
                    val linux = sess.mode.value == "linux" && Distros.installed(dist)
                    val pb = if (linux) ProcessBuilder(Linux.command(null, distro = dist)).also { it.environment().putAll(env()); it.environment().putAll(Linux.env()) }
                             else ProcessBuilder("/system/bin/sh").also { it.environment().putAll(env()) }
                    val p = pb.directory(home()).redirectErrorStream(true).start()
                    sess.shell = p
                    sess.append(if (linux) "[${TermLogic.distro(dist)?.name ?: "Linux"} — ${installHint(dist)} to install software]\n" else "[phone shell ready]\n")
                    launch {
                        val buf = CharArray(4096)
                        val r = p.inputStream.bufferedReader()
                        try { while (true) { val n = r.read(buf); if (n < 0) break; sess.append(String(buf, 0, n)) } } catch (_: Exception) { }
                        sess.append("\n[shell exited${try { " with code " + p.exitValue() } catch (_: Exception) { "" }}]\n")
                    }
                } catch (e: Exception) {
                    sess.append("Couldn't start the shell: ${e.message}\n")
                }
            }
        }
    }

    private fun banner(s: Session) = "Blaze terminal · ${if (s.mode.value == "linux") (TermLogic.distro(defaultDistro())?.name ?: "Linux") else "sh + BusyBox"} · home is ~\n"

    fun switchMode(m: String) {
        val s = active.value
        if (s.mode.value == m) return
        s.mode.value = m
        s.shell?.destroy(); s.shell = null
        s.append("\n— switching to ${if (m == "linux") (TermLogic.distro(defaultDistro())?.name ?: "Linux") else "phone shell"} —\n")
        startShell(s)
    }

    fun input(line: String) {
        val s = active.value
        s.append("$ $line\n")
        if (line.isNotBlank() && s.history.lastOrNull() != line) { s.history += line; if (s.history.size > 200) s.history.removeAt(0) }
        val p = s.shell
        if (p == null || !p.isAlive) { startShell(s); scope.launch { kotlinx.coroutines.delay(1200); write(s, line + "\n") } } else scope.launch { write(s, line + "\n") }
    }

    /** Raw bytes (control keys): Ctrl-D closes input, others pass through. */
    fun sendRaw(text: String) { val s = active.value; scope.launch { write(s, text) } }
    fun eof() { val s = active.value; try { s.shell?.outputStream?.close() } catch (_: Exception) { }; s.append("^D\n") }

    private fun write(s: Session, text: String) {
        try { s.shell?.outputStream?.let { it.write(text.toByteArray()); it.flush() } } catch (e: Exception) { s.append("error: ${e.message}\n") }
    }

    fun interrupt() { val s = active.value; s.shell?.destroy(); s.shell = null; s.append("^C\n"); startShell(s) }
    fun clearScreen() { active.value.out.value = "" }

    /** Tab completion against files in ~ (the interactive shell has no TTY, so we do it here). */
    fun complete(line: String): String {
        val token = line.substringAfterLast(' ')
        val dirPart = token.substringBeforeLast('/', "")
        val prefix = token.substringAfterLast('/')
        val dir = when {
            dirPart.startsWith("~") -> File(home(), dirPart.removePrefix("~").trimStart('/'))
            dirPart.startsWith("/") -> File(dirPart)
            dirPart.isEmpty() && token.startsWith("/") -> File("/")
            else -> File(home(), dirPart)
        }
        val matches = (dir.list() ?: emptyArray()).filter { it.startsWith(prefix) }.sorted()
        if (matches.isEmpty()) return line
        if (matches.size > 1) {
            active.value.append(matches.take(40).joinToString("  ") + "\n")
            val common = matches.reduce { acc, m -> acc.commonPrefixWith(m) }
            return line.dropLast(prefix.length) + common
        }
        val m = matches.first()
        val isDir = File(dir, m).isDirectory
        return line.dropLast(prefix.length) + m + if (isDir) "/" else " "
    }

    // ---------- For the agent ----------

    private val danger = Regex("(\\brm\\s+-[a-z]*r[a-z]*f?\\s+(/|~|\\*|\\.\\s*$))|\\bmkfs|\\bdd\\s+if=|\\breboot\\b|\\bsu\\b|>\\s*/dev/", RegexOption.IGNORE_CASE)
    fun risky(cmd: String) = danger.containsMatchIn(cmd)

    // The agent's shell remembers where it is (cd) and what it exported (~/.blaze_env) between calls.
    @Volatile private var phoneCwd: String = ""
    @Volatile private var linuxCwd: String = "/root"
    fun agentCwd(): File = phoneCwd.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory } ?: home()
    private const val MARK = "__BLZCWD__"
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun wrap(cmd: String, linux: Boolean): String {
        val cwd = if (linux) linuxCwd else agentCwd().absolutePath
        val envFile = if (linux) "/root/phone/.blaze_env" else "\$HOME/.blaze_env"
        return "[ -f \"$envFile\" ] && . \"$envFile\"; cd ${q(cwd)} 2>/dev/null\n$cmd\n__rc=\$?; echo \"$MARK\$(pwd)\"; exit \$__rc"
    }

    /**
     * The Linux a `linux=true` command runs in when the caller doesn't name one: Alpine, or (when Alpine isn't installed)
     * another installed Linux. Wave B-4: the old desktop Linux choice (pref linux_desktop_distro) belonged to the removed Linux desktop page
     * and is ignored now.
     */
    fun defaultDistro(): String = try { TermLogic.defaultDistro(Distros.installedIds()) } catch (_: Throwable) { "alpine" }

    /** The package manager hint for [distro] ("apt install <package>"). */
    fun installHint(distro: String): String = TermLogic.installHint(distro)

    suspend fun run(cmd: String, timeoutSec: Int = 60, linux: Boolean = false, distro: String? = null): String = withContext(Dispatchers.IO) {
        setup()
        val dist = if (linux) (distro ?: defaultDistro()) else "alpine"
        if (linux && !Distros.installed(dist)) return@withContext if (dist == "alpine") "Linux isn't installed yet. Call linux_install (one-time ~3.5 MB download), or use run_code which sets it up for you."
            else "${TermLogic.distro(dist)?.name ?: dist} isn't installed yet. Install it from the Terminal's + menu."
        val full = wrap(cmd, linux)
        val p = try { if (linux) ProcessBuilder(Linux.command(full, distro = dist)).directory(home()).redirectErrorStream(true)
                .also { it.environment().putAll(env()); it.environment().putAll(Linux.env()) }.start() else builder(full).start() } catch (e: Exception) { return@withContext "Error: ${e.message}" }
        try { p.outputStream.close() } catch (_: Exception) { }   // no stdin: commands that wait for input fail fast instead of hanging
        val out = StringBuilder()
        val reader = launch { try { p.inputStream.bufferedReader().forEachLine { if (out.length < 200_000) out.append(it).append('\n') } } catch (_: Exception) { } }
        val done = withTimeoutOrNull(timeoutSec.coerceIn(1, 600) * 1000L) { while (p.isAlive) kotlinx.coroutines.delay(50); true }
        if (done == null) { p.destroy(); reader.cancel(); return@withContext out.toString().replace(Regex("$MARK.*\n?"), "").takeLast(8000) + "\n[stopped after ${timeoutSec}s; rerun with background=true for servers or long work, then terminal_jobs id=N]" }
        reader.join()
        val code = p.exitValue()
        var raw = out.toString()
        Regex("$MARK(.*)").findAll(raw).lastOrNull()?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { if (linux) linuxCwd = it else phoneCwd = it }
        raw = raw.replace(Regex("$MARK.*\n?"), "")
        val text = if (raw.length > 8000) raw.take(2000) + "\n…[${raw.length - 8000} chars cut; redirect to a file and use file_read/file_search]…\n" + raw.takeLast(6000) else raw
        val hint = when {
            code == 127 && !linux -> "\n[hint: command not found in the phone shell. For python/node/git/curl/gcc use linux=true (apk add <pkg>) or run_code.]"
            code == 127 && linux && dist == "alpine" -> "\n[hint: not installed in Linux; apk add <package> first (apk search <name> to find it).]"
            code == 127 && linux -> "\n[hint: not installed in ${TermLogic.distro(dist)?.name ?: dist}; ${installHint(dist)} first.]"
            code != 0 && Regex("(?i)permission denied").containsMatchIn(raw) -> "\n[hint: Android blocks that path for apps; work inside ~ or /sdcard.]"
            code != 0 && Regex("(?i)no space left").containsMatchIn(raw) -> "\n[hint: phone storage is full.]"
            else -> ""
        }
        val where = if (linux) linuxCwd else agentCwd().absolutePath.replace(home().absolutePath, "~")
        val result = "exit $code · cwd $where\n$text".trimEnd() + hint
        logAgent("${if (linux) (if (dist == "alpine") "linux" else dist) else "sh"} $ ${cmd.take(300)}", result)
        result
    }

    fun jobLog(id: Int, lines: Int): String = synchronized(jobs) {
        val j = jobs[id] ?: return "No job $id. ${if (jobs.isEmpty()) "No background jobs." else "Jobs: " + jobs.keys.joinToString()}"
        val state = if (j.proc.isAlive) "running ${(System.currentTimeMillis() - j.started) / 1000}s" else "exited ${j.proc.exitValue()}"
        "[${j.id}] $state · ${j.cmd.take(80)}\n" + synchronized(j.log) { j.log.toString().trimEnd().lines().takeLast(lines.coerceIn(1, 400)).joinToString("\n") }
    }

    fun jobInput(id: Int, text: String, enter: Boolean): String {
        val j = synchronized(jobs) { jobs[id] } ?: return "No job $id."
        if (!j.proc.isAlive) return "Job $id has already exited."
        return try { j.proc.outputStream.apply { write((text + if (enter) "\n" else "").toByteArray()); flush() }; Thread.sleep(600)
            "Sent to job $id. Latest output:\n" + synchronized(j.log) { j.log.toString().trimEnd().lines().takeLast(15).joinToString("\n") }
        } catch (e: Exception) { "Couldn't write to job $id: ${e.message}" }
    }

    data class Job(val id: Int, val cmd: String, val proc: Process, val log: StringBuilder, val started: Long, val maxMs: Long = 0, var reported: Boolean = false, val pid: String = "")
    private val jobs = LinkedHashMap<Int, Job>()
    private var nextJob = 1

    fun startJob(cmd: String, linux: Boolean = false, maxMinutes: Int = 0, distro: String? = null): String {
        setup()
        val dist = if (linux) (distro ?: defaultDistro()) else "alpine"
        if (linux && !Distros.installed(dist)) return if (dist == "alpine") "Linux isn't installed yet (linux_install)." else "${TermLogic.distro(dist)?.name ?: dist} isn't installed yet."
        val wrapped = (if (linux) "[ -f /root/phone/.blaze_env ] && . /root/phone/.blaze_env; cd ${q(linuxCwd)} 2>/dev/null\n" else "[ -f \"\$HOME/.blaze_env\" ] && . \"\$HOME/.blaze_env\"; cd ${q(agentCwd().absolutePath)} 2>/dev/null\n") + cmd
        val p = try { if (linux) ProcessBuilder(Linux.command(wrapped, distro = dist)).directory(home()).redirectErrorStream(true)
                .also { it.environment().putAll(env()); it.environment().putAll(Linux.env()) }.start() else builder(wrapped).start() } catch (e: Exception) { return "Error: ${e.message}" }
        val jid = nextJob++
        val pid = com.blaze.assistant.agent.Processes.register("job", cmd.take(50), jid.toString(), props = org.json.JSONObject().put("command", cmd).put("max_minutes", maxMinutes))
        val j = Job(jid, cmd, p, StringBuilder(), System.currentTimeMillis(), maxMinutes * 60_000L, pid = pid)
        synchronized(jobs) { jobs[j.id] = j }
        JobKeeper.start(app)
        scope.launch { try { p.inputStream.bufferedReader().forEachLine { synchronized(j.log) { j.log.append(it).append('\n'); if (j.log.length > 50_000) j.log.delete(0, 20_000) } } } catch (_: Exception) { } }
        Thread.sleep(1500)
        logAgent("job ${j.id} $ ${cmd.take(300)}", "(running in background)")
        val port = Regex("""(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::\]|port)[: ]+(\d{2,5})""", RegexOption.IGNORE_CASE).find(synchronized(j.log) { j.log.toString() } + " " + cmd)?.groupValues?.get(1)
        val preview = port?.let { "\n[server on port $it: call preview_server port=$it to open it in Blaze's browser]" } ?: ""
        return "Started job ${j.id} [ID $pid]: $cmd\n" + (if (!p.isAlive) "It already exited (code ${p.exitValue()}).\n" else "") + synchronized(j.log) { j.log.toString().takeLast(1500) } + preview
    }

    fun listJobs(): String = synchronized(jobs) {
        if (jobs.isEmpty()) "No background jobs." else jobs.values.joinToString("\n") { j ->
            "[${j.id}] ${if (j.proc.isAlive) "running" else "exited ${j.proc.exitValue()}"} · ${(System.currentTimeMillis() - j.started) / 1000}s · ${j.cmd.take(80)}\n" +
                synchronized(j.log) { j.log.toString().takeLast(400).prependIndent("    ") } }
    }

    /** Called by JobKeeper: enforce budgets and report finished jobs once. */
    fun sweep(report: (String) -> Unit) {
        val now = System.currentTimeMillis()
        val list = synchronized(jobs) { jobs.values.toList() }
        for (j in list) {
            if (j.proc.isAlive && j.maxMs > 0 && now - j.started > j.maxMs) { j.proc.destroy(); synchronized(j.log) { j.log.append("[stopped: time budget used up]\n") } }
            if (!j.proc.isAlive && !j.reported) {
                j.reported = true
                val code = try { j.proc.exitValue() } catch (_: Exception) { -1 }
                val tail = synchronized(j.log) { j.log.toString().trim().lines().takeLast(3).joinToString(" | ") }.take(200)
                report("Job ${j.pid.ifBlank { j.id.toString() }} ${if (code == 0) "finished" else "failed (exit $code)"}: ${j.cmd.take(50)}${if (tail.isNotBlank()) "\n$tail" else ""}")
            }
        }
    }

    fun jobAlive(id: Int): Boolean = synchronized(jobs) { jobs[id]?.proc?.isAlive == true }
    fun stopJob(id: Int): String = synchronized(jobs) { jobs.remove(id)?.let { it.proc.destroy(); "Stopped job $id." } ?: "No job $id." }
    fun jobCount() = synchronized(jobs) { jobs.values.count { it.proc.isAlive } }

    // ---------- Termux bridge ----------

    fun termuxInstalled(ctx: Context) = try { ctx.packageManager.getPackageInfo("com.termux", 0); true } catch (_: Exception) { false }

    const val TERMUX_FIX = "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings"
    private const val RESULT_ACTION = "com.blaze.assistant.TERMUX_RESULT"

    /**
     * Runs [cmd] in Termux and (in background mode) returns its real output. Sets itself up:
     * asks Android for the "Run commands in Termux" permission with the system dialog (one tap,
     * no Settings digging) and, if Termux still refuses, puts the one-line fix on the clipboard
     * and opens Termux so the user only has to paste.
     */
    suspend fun runInTermux(ctx: Context, cmd: String, background: Boolean): String {
        if (!termuxInstalled(ctx)) return "Termux isn't installed. Blaze's own Linux (run_code / run_shell) does the same job with no setup; use that."
        val perm = "com.termux.permission.RUN_COMMAND"
        if (!com.blaze.assistant.device.Perms.ensure(ctx, perm))
            return "The user didn't allow 'Run commands in Termux' in the Android pop-up. Use Blaze's own Linux (run_code / run_shell) instead."
        val id = System.nanoTime().toInt()
        val result = kotlinx.coroutines.CompletableDeferred<android.os.Bundle?>()
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { result.complete(i.getBundleExtra("result")) }
        }
        val action = "$RESULT_ACTION.$id"
        androidx.core.content.ContextCompat.registerReceiver(ctx, rx, android.content.IntentFilter(action), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        val pi = android.app.PendingIntent.getBroadcast(ctx, id, Intent(action).setPackage(ctx.packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0))
        val i = Intent().setClassName("com.termux", "com.termux.app.RunCommandService").setAction("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", cmd))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            val b = kotlinx.coroutines.withTimeoutOrNull(if (background) 120_000L else 8_000L) { result.await() }
            val err = b?.getInt("err", -1) ?: -1
            val errmsg = b?.getString("errmsg").orEmpty()
            when {
                b == null && background -> "Sent to Termux; no answer within 2 minutes (still running, or Termux blocked it silently). If nothing happened, call termux_run with command 'echo ok' to test the link."
                b == null -> "Opened in a Termux window: $cmd"
                errmsg.contains("allow-external-apps", true) || errmsg.contains("external app", true) -> needsFix(ctx)
                else -> {
                    val out = b.getString("stdout").orEmpty(); val se = b.getString("stderr").orEmpty()
                    val code = if (b.containsKey("exitCode")) b.getInt("exitCode") else err
                    buildString {
                        append("exit ").append(code).append('\n')
                        if (out.isNotBlank()) append(out.takeLast(6000))
                        if (se.isNotBlank()) append("\n[stderr]\n").append(se.takeLast(3000))
                        if (errmsg.isNotBlank() && out.isBlank()) append("\n[termux] ").append(errmsg.take(500))
                    }
                }
            }
        } catch (e: SecurityException) { needsFix(ctx) }
        catch (e: Exception) { "Error: ${e.message}" }
        finally { runCatching { ctx.unregisterReceiver(rx) } }
    }

    /** Termux blocks other apps until allow-external-apps is set inside Termux: copy the fix and open Termux. */
    private fun needsFix(ctx: Context): String {
        try { ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Termux fix", TERMUX_FIX)) } catch (_: Exception) { }
        try { ctx.packageManager.getLaunchIntentForPackage("com.termux")?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (_: Exception) { }
        return "Termux is blocking other apps until one setting is on. Blaze already copied the fix to the clipboard and opened Termux. " +
            "Tell the user in ONE short line: 'Termux is open: long-press, tap Paste, press Enter, then come back.' When they're back, retry termux_run. " +
            "(Better: Blaze's own Linux via run_code / run_shell needs none of this.)"
    }

    // ---------- Tool definitions ----------

    val names = setOf("run_shell", "terminal_jobs", "terminal_stop", "termux_run", "linux_install", "preview_server")
    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun p(t: String, d: String) = JSONObject().put("type", t).put("description", d)

    /** v0.33.5: the linux=true description names the Linux it really runs in and its package manager. */
    private fun linuxParam(): String {
        val d = defaultDistro()
        return if (d == "alpine") "Run inside Blaze's Alpine Linux (apk add python3/nodejs/git/openjdk17…) instead of the phone shell"
        else "Run inside Blaze's ${TermLogic.distro(d)?.name ?: d} (${installHint(d)}, e.g. python3, nodejs, git) instead of the phone shell"
    }

    /** v0.45.9: [tools] without the Termux check (no Context), so the full-list tests can build it on the JVM. */
    fun tools(ctx: Context): List<JSONObject> = shellTools() + listOfNotNull(if (termuxInstalled(ctx)) termuxTool() else null)

    internal fun shellTools(): List<JSONObject> = buildList {
        add(fn("run_shell", "Run a shell command on the phone (Android sh + BusyBox: ls, cat, grep, sed, awk, wget, curl-less http via wget, tar, unzip, vi, nc, httpd, find, ps, top, df, du...). Working dir is Blaze's home (~). Set background=true for servers, downloads, scraping or anything over ~30 s: it keeps running if Blaze is closed and the user gets a notification when it finishes or fails (optionally limit it with max_minutes). Returns exit code, current folder and output. The shell remembers cd between calls; put exports in ~/.blaze_env to keep them. For writing files use file_write, not echo/heredocs.",
            JSONObject().put("command", p("string", "Shell command")).put("timeout_seconds", p("integer", "Max seconds (default 60)")).put("background", p("boolean", "Run as a background job")).put("max_minutes", p("integer", "Time budget for a background job; 0 = none"))
            .put("linux", p("boolean", try { linuxParam() } catch (_: Throwable) { "Run inside Blaze's Linux instead of the phone shell" })), listOf("command")))
        add(fn("linux_install", "Download and set up Alpine Linux inside Blaze (one time, ~3.5 MB, no root).", JSONObject(), emptyList()))
        add(fn("terminal_jobs", "Background shell jobs. No id: list them with a short tail of output. With id: that job's latest output (lines, default 80). Type into one with job_input, stop with terminal_stop.",
            JSONObject().put("id", p("integer", "Job number for its full output")).put("lines", p("integer", "Last N lines, default 80")), emptyList()))
        add(fn("preview_server", "Open a local server running on the phone (e.g. a dev server started as a background job) in Blaze's built-in browser and show it to the user.",
            JSONObject().put("port", p("integer", "Port, e.g. 8000")).put("path", p("string", "Optional path, e.g. /index.html")), listOf("port")))
        add(fn("terminal_stop", "Stop a background shell job.", JSONObject().put("id", p("integer", "Job number")), listOf("id")))
    }

    private fun termuxTool(): JSONObject = fn("termux_run", "Run a command in the Termux app and get its output back. Only when the user wants Termux; Blaze's own Linux (run_code/run_shell) is the default. Sets itself up (permission pop-up, copies the one-line fix if Termux blocks it).",
            JSONObject().put("command", p("string", "bash command")).put("background", p("boolean", "Run without opening a Termux window")), listOf("command"))

}
