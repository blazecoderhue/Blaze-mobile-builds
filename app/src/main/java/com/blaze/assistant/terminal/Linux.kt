package com.blaze.assistant.terminal

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A real Linux (Alpine) inside Blaze, run with PRoot like UserLAnd/Termux do: no root needed.
 * `apk add python3 nodejs git openjdk17 …` all work. The rootfs (~3.5 MB) is downloaded once
 * into filesDir/linux/alpine; PRoot and its loader ship in the APK as native libs.
 */
object Linux {
    private const val VER = AlpineNet.VER
    private lateinit var app: Context
    val status = MutableStateFlow("")          // install progress text
    val installing = MutableStateFlow(false)

    fun init(ctx: Context) { app = ctx.applicationContext }
    internal fun nat(name: String) = File(app.applicationInfo.nativeLibraryDir, name)
    fun base() = File(app.filesDir, "linux")
    fun rootfs() = File(base(), "alpine")
    /** v0.29.2: every distro has its own rootfs folder (filesDir/linux/<id>); Alpine keeps "alpine". */
    fun rootfs(id: String) = File(base(), id)
    fun installed() = ::app.isInitialized && File(rootfs(), "bin/busybox").exists() && File(rootfs(), ".blaze_ok").exists()
    fun supported() = ::app.isInitialized && nat("libproot.so").exists() && nat("libproot_loader.so").exists()

    private fun arch() = AlpineNet.arch(Build.SUPPORTED_ABIS.firstOrNull())

    suspend fun install(stop: StopToken = StopToken()): String = withContext(Dispatchers.IO) {
        if (!supported()) return@withContext "This phone's CPU isn't supported for Linux."
        if (installing.value) {   // another caller (e.g. the setup prefetch) is on it: wait for it instead of skipping
            var waited = 0
            while (installing.value && waited < 900) { kotlinx.coroutines.delay(1000); waited++ }
            return@withContext if (installed()) "Alpine Linux $VER installed." else "Install didn't finish: ${status.value}"
        }
        if (installed()) return@withContext "Alpine Linux $VER is already installed."
        installing.value = true
        try {
            Terminal.setup()
            // v0.25.5: resumable (HTTP Range), retried with backoff across several mirrors, SHA-256 checked. v0.25.1 used one
            // plain HttpURLConnection to one mirror: a dropped connection failed the install, and a cut-short file went
            // straight to the unpacker.
            val a = arch()
            // v0.33.3: kept in filesDir (cacheDir can be cleared by Android mid-download) and resumed from its .part.
            val tgz = File(base(), "alpine-$VER-$a.tar.gz").also { base().mkdirs() }
            File(app.cacheDir, "alpine-$VER-$a.tar.gz").let { old -> if (old.exists() && !tgz.exists()) old.renameTo(tgz) }
            File(app.cacheDir, "alpine-$VER-$a.tar.gz.part").let { old -> val np = File(tgz.path + ".part"); if (old.exists() && !np.exists()) old.renameTo(np) }
            val unpacked = File(rootfs(), ".blaze_unpacked")
            if (!(unpacked.exists() && File(rootfs(), "bin/busybox").exists())) {
                val sha = AlpineNet.ROOTFS_SHA256[a].orEmpty()
                val resumed = File(tgz.path + ".part").let { it.exists() && it.length() > 0 }
                status.value = if (resumed) "Resuming the Linux download\u2026" else "Downloading Alpine Linux\u2026"
                try {
                    Fetch.download(AlpineNet.rootfsUrls(a), tgz, sha, progress = { got, total, retry ->
                        status.value = retry ?: Fetch.mbLine("Alpine Linux", got, total, resumed)
                    }, sleep = stop.sleep, open = stop::open, cancelled = stop.cancelled)
                } catch (e: Exception) {
                    if (e is Fetch.StoppedException) { status.value = "Download paused"; return@withContext "Download paused." }
                    val m = "${Fetch.reason(e)}, so Linux didn't finish downloading. What's downloaded is kept: tap Install to carry on."
                    status.value = m
                    return@withContext m
                }
                status.value = "Unpacking\u2026"
                rootfs().deleteRecursively(); rootfs().mkdirs()
                val n = try { untar(tgz, rootfs()) } catch (e: Exception) {
                    // Only a damaged archive is thrown away; a verified one is kept so the next try just unpacks again.
                    if (sha.isBlank() || Fetch.sha256(tgz) != sha) tgz.delete()
                    status.value = "Unpacking failed: ${e.message}. Tap Install to retry."
                    return@withContext "Unpacking failed: ${e.message}"
                }
                if (!File(rootfs(), "bin/busybox").exists()) {
                    status.value = "Unpacking failed ($n files). Tap Install to retry."
                    return@withContext "Unpacking failed: busybox missing after $n files."
                }
                unpacked.writeText(VER)   // stage marker: a later failure (prepare) never downloads or unpacks again
                tgz.delete()
            }
            prepare()
            File(rootfs(), ".blaze_ok").writeText(VER)
            status.value = "Installed. Run: apk update && apk add python3"
            "Alpine Linux $VER installed."
        } catch (e: Exception) {
            status.value = "Install failed: ${e.message}. Tap Install to retry."
            "Install failed: ${e.message}"
        } finally { installing.value = false }
    }

    /**
     * Pure-Kotlin .tar.gz unpacker (files, folders, symlinks, hard links, modes). Replaces the
     * busybox `tar` call, which could stall or die silently on some phones and left "Unpacking…".
     */
    private fun untar(tgz: File, dest: File): Int =
        untarStream(java.util.zip.GZIPInputStream(java.io.BufferedInputStream(tgz.inputStream(), 64 * 1024)), dest, 0)

    /** v0.29.2: any tar stream (gz or xz), with pax path/linkpath headers and [strip] leading folders (proot-distro tarballs). */
    internal fun untarStream(stream: java.io.InputStream, dest: File, strip: Int, onCount: (Int) -> Unit = { status.value = "Unpacking… $it files" }): Int {
        val root = dest.canonicalPath
        var count = 0; var longName: String? = null; var longLink: String? = null
        val hard = mutableListOf<Pair<File, String>>()
        fun stripped(n: String): String { var x = n.removePrefix("./"); repeat(strip) { val i = x.indexOf('/'); x = if (i < 0) "" else x.substring(i + 1) }; return x }
        stream.use { inp ->
            val hdr = ByteArray(512)
            fun readFully(b: ByteArray, len: Int): Boolean { var o = 0; while (o < len) { val r = inp.read(b, o, len - o); if (r < 0) return false; o += r }; return true }
            fun str(o: Int, l: Int): String { var e = o; while (e < o + l && hdr[e] != 0.toByte()) e++; return String(hdr, o, e - o, Charsets.UTF_8) }
            fun oct(o: Int, l: Int): Long = str(o, l).trim().ifEmpty { "0" }.toLong(8)
            fun skip(n: Long) { var left = n; val b = ByteArray(8192); while (left > 0) { val r = inp.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (r < 0) break; left -= r } }
            while (true) {
                if (!readFully(hdr, 512)) break
                if (hdr.all { it == 0.toByte() }) break
                val size = oct(124, 12); val type = hdr[156].toInt().toChar()
                val padded = (size + 511) / 512 * 512
                if (type == 'L' || type == 'K') {               // GNU long name / long link
                    val b = ByteArray(padded.toInt()); readFully(b, padded.toInt())
                    val v = String(b, 0, size.toInt(), Charsets.UTF_8).trimEnd('\u0000')
                    if (type == 'L') longName = v else longLink = v; continue
                }
                if (type == 'x' || type == 'g') {                 // pax headers: Debian/Ubuntu put long paths here
                    val b = ByteArray(padded.toInt()); readFully(b, padded.toInt())
                    if (type == 'x') for ((k, v) in TermLogic.pax(String(b, 0, size.toInt(), Charsets.UTF_8))) {
                        if (k == "path") longName = v else if (k == "linkpath") longLink = v
                    }
                    continue
                }
                val prefix = str(345, 155)
                var name = longName ?: (if (prefix.isNotEmpty()) "$prefix/" else "") + str(0, 100)
                val link = longLink ?: str(157, 100)
                longName = null; longLink = null
                name = stripped(name).trimEnd('/')
                val mode = (oct(100, 8) and 0xFFF).toInt()
                if (name.isEmpty()) { skip(padded); continue }
                val out = File(dest, name)
                if (!out.canonicalPath.startsWith(root) && type != '2') { skip(padded); continue }
                out.parentFile?.mkdirs()
                when (type) {
                    '5' -> { out.mkdirs(); runCatching { Os.chmod(out.path, mode or 0b111_000_000) } }
                    '2' -> { runCatching { out.delete(); Os.symlink(link, out.path) } }
                    '1' -> hard += out to stripped(link)
                    '0', '\u0000', '7' -> {
                        out.delete()
                        out.outputStream().use { o -> var left = size; val b = ByteArray(64 * 1024)
                            while (left > 0) { val r = inp.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (r < 0) throw java.io.EOFException("archive cut short"); o.write(b, 0, r); left -= r } }
                        skip(padded - size)
                        runCatching { Os.chmod(out.path, mode or 0b110_000_000) }
                    }
                    else -> skip(padded)
                }
                count++
                if (count % 50 == 0) onCount(count)
            }
        }
        for ((f, target) in hard) runCatching { File(dest, target).copyTo(f, overwrite = true); Os.chmod(f.path, Os.stat(File(dest, target).path).st_mode and 0xFFF) }
        return count
    }

    /** DNS, hosts, a profile, and stand-ins for /proc files Android hides from apps. */
    private fun prepare() = prepare(rootfs(), "alpine")

    internal fun prepare(r: File, name: String) {
        // v0.25.5: the phone's own DNS first (some Wi-Fi networks block 8.8.8.8 / 1.1.1.1), refreshed on every command.
        File(r, "etc/resolv.conf").apply { delete(); writeText(AlpineNet.resolvConf(phoneDns())) }
        File(r, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(r, "etc/profile.d").mkdirs()
        File(r, "etc/profile.d/blaze.sh").writeText("export PS1='$name:\\w\\$ '\nexport LANG=C.UTF-8\n")
        // apt drops to the _apt user to download, which PRoot can't do: keep it root.
        if (File(r, "etc/apt").isDirectory) File(r, "etc/apt/apt.conf.d").apply { mkdirs() }.resolve("99blaze").writeText("APT::Sandbox::User \"root\";\n")
        File(r, "root").mkdirs(); File(r, "tmp").mkdirs()
        // v0.30.2: real mount points for the binds (like proot-distro's), so PRoot doesn't have to fake ("glue") them.
        File(r, "root/phone").mkdirs(); File(r, "sdcard").mkdirs()
        // v0.30.2: `blaze-doctor` checks cd, getcwd, stat, the file tools and fork from inside the Linux.
        runCatching {
            val doc = File(r, LinuxDoctor.PATH_IN_ROOTFS).apply { parentFile?.mkdirs() }
            val text = LinuxDoctor.script()
            if (!doc.exists() || doc.readText() != text) doc.writeText(text)
            Os.chmod(doc.path, 493)   // 0755
        }
        val fake = File(base(), "fake").apply { mkdirs() }
        File(fake, "stat").writeText("cpu  1000 0 1000 100000 0 0 0 0 0 0\n" + (0 until Runtime.getRuntime().availableProcessors()).joinToString("") { "cpu$it 250 0 250 25000 0 0 0 0 0 0\n" } +
            "intr 0\nctxt 0\nbtime 0\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
        File(fake, "uptime").writeText("1000.00 900.00\n")
        File(fake, "version").writeText("Linux version 5.10.0 (blaze@android) #1 SMP\n")
        val lib = File(base(), "lib").apply { mkdirs() }
        // v0.30.2: Termux's proot also needs libandroid-shmem.so (found by file name through LD_LIBRARY_PATH).
        linkLib(File(lib, "libtalloc.so.2"), "libtalloc.so")
        if (nat("libandroid-shmem.so").exists()) linkLib(File(lib, "libandroid-shmem.so"), "libandroid-shmem.so")
    }

    /**
     * An app update moves nativeLibraryDir (/data/app/~~random/...), leaving this link dangling: exists() is false
     * but the link itself is still there, so a plain symlink() failed with EEXIST and proot couldn't start.
     * Always point it at the current install.
     */
    private fun linkLib(link: File, name: String) {
        val target = nat(name).absolutePath
        val current = try { Os.readlink(link.absolutePath) } catch (_: Exception) { null }
        if (current != target || !link.exists()) {
            try { Os.remove(link.absolutePath) } catch (_: Exception) { link.delete() }
            try { Os.symlink(target, link.absolutePath) } catch (_: Exception) {
                try { File(target).copyTo(link, overwrite = true) } catch (_: Exception) { }
            }
        }
    }

    private fun phoneDns(): List<String> = try {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /** The command line that runs [cmd] (or a login shell) inside Alpine. */
    fun command(cmd: String?, term: String = "xterm", distro: String = "alpine"): List<String> {
        val rf = if (distro == "alpine") rootfs() else rootfs(distro)
        if (distro == "alpine") prepare() else prepare(rf, distro)
        val r = rf.absolutePath
        val fake = File(base(), "fake")
        val args = mutableListOf(nat("libproot.so").absolutePath, "--kill-on-exit", "--link2symlink", "-0", "-r", r,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/system", "-b", "/apex",
            "-b", "${app.filesDir.absolutePath}/home:/root/phone")
        if (!File("/proc/stat").canRead()) { args += "-b"; args += "${fake.absolutePath}/stat:/proc/stat" }
        if (!File("/proc/uptime").canRead()) { args += "-b"; args += "${fake.absolutePath}/uptime:/proc/uptime" }
        if (!File("/proc/version").canRead()) { args += "-b"; args += "${fake.absolutePath}/version:/proc/version" }
        if (File("/sdcard").canRead()) { args += "-b"; args += "/sdcard" }
        args += listOf("-w", "/root", "/usr/bin/env", "-i", "HOME=/root", "TERM=$term", "LANG=C.UTF-8", "COLORTERM=truecolor",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        // v0.29.2: the interactive terminal gets bash when the user installed it (apk add bash), else Alpine's ash.
        args += if (cmd == null) listOf(if (File(rf, "bin/bash").exists() || File(rf, "usr/bin/bash").exists()) "/bin/bash" else "/bin/sh", "-l") else listOf("/bin/sh", "-lc", cmd)
        return args
    }

    fun env(): Map<String, String> {
        val tmp = File(app.cacheDir, "proot").apply { mkdirs() }
        return buildMap {
            put("PROOT_TMP_DIR", tmp.absolutePath)
            put("PROOT_LOADER", nat("libproot_loader.so").absolutePath)
            if (nat("libproot_loader32.so").exists()) put("PROOT_LOADER_32", nat("libproot_loader32.so").absolutePath)
            put("LD_LIBRARY_PATH", File(base(), "lib").absolutePath)
            // v0.30.2: "1" only when this build's self-test found PRoot's seccomp acceleration broken on this phone.
            if (noSeccomp == null) noSeccomp = LinuxDoctor.parseMode(Terminal.buildKey(), runCatching { modeFile().readText() }.getOrNull())
            put("PROOT_NO_SECCOMP", if (noSeccomp == true) "1" else "0")
        }
    }

    /** This build's verdict on PRoot's seccomp acceleration (null = not tested yet: keep it on). */
    @Volatile private var noSeccomp: Boolean? = null
    private val selfTesting = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun modeFile() = File(base(), ".proot_mode")

    /**
     * v0.30.2: once per build, in the background, runs blaze-doctor in [distro] with PRoot's seccomp acceleration on
     * and, only if that fails, off; the faster mode that works is used from then on (LinuxDoctor.noSeccomp). The
     * result is kept in filesDir/linux/doctor.txt. Never blocks a tab from opening.
     */
    fun selfTest(distro: String = "alpine") {
        if (!::app.isInitialized || !supported() || !Distros.installed(distro)) return
        val key = Terminal.buildKey()
        if (noSeccomp == null) noSeccomp = LinuxDoctor.parseMode(key, runCatching { modeFile().readText() }.getOrNull())
        if (noSeccomp != null || !selfTesting.compareAndSet(false, true)) return
        Thread {
            try {
                val on = doctor(distro, false)
                val off = if (on?.passed == true) null else doctor(distro, true)
                val decision = LinuxDoctor.noSeccomp(on, off)
                noSeccomp = decision
                runCatching { modeFile().writeText(LinuxDoctor.modeStamp(key, decision)) }
                runCatching {
                    File(base(), "doctor.txt").writeText("build $key, $distro\nseccomp on: ${on ?: "didn't run"}\n" +
                        (off?.let { "seccomp off: $it\n" } ?: "") + "using PROOT_NO_SECCOMP=${if (decision) 1 else 0}\n")
                }
            } catch (_: Throwable) { } finally { selfTesting.set(false) }
        }.apply { isDaemon = true; name = "blaze-doctor" }.start()
    }

    private fun doctor(distro: String, noSec: Boolean): LinuxDoctor.Report? = try {
        val pb = ProcessBuilder(command("/bin/sh /${LinuxDoctor.PATH_IN_ROOTFS}", distro = distro)).redirectErrorStream(true)
        pb.environment().putAll(Terminal.env()); pb.environment().putAll(env())
        pb.environment()["PROOT_NO_SECCOMP"] = if (noSec) "1" else "0"
        val p = pb.start()
        val out = StringBuilder()
        val t = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); null }
        else { t.join(2000); LinuxDoctor.parse(out.toString()) }
    } catch (_: Exception) { null }

    fun uninstall() { rootfs().deleteRecursively(); status.value = "Removed." }
    fun sizeMb(): Long = try { rootfs().walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_000_000 } catch (_: Exception) { 0 }
}
