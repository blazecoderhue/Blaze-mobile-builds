package com.blaze.assistant.terminal

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.29.2: more Linux than Alpine. Ubuntu, Debian, Arch, Fedora and Manjaro install from proot-distro's
 * rootfs tarballs into filesDir/linux/<id> and run under the same PRoot as Alpine. Any of them can be open
 * in several Terminal tabs at once.
 */
object Distros {
    val status = MutableStateFlow("")
    val installing = MutableStateFlow<String?>(null)

    private fun abi() = Build.SUPPORTED_ABIS.firstOrNull()
    fun installable() = TermLogic.installable(abi())

    fun installed(id: String): Boolean = if (id == "alpine") Linux.installed()
        else File(Linux.rootfs(id), ".blaze_ok").exists()

    fun installedIds(): Set<String> = TermLogic.DISTROS.map { it.id }.filter { runCatching { installed(it) }.getOrDefault(false) }.toSet()

    suspend fun install(id: String, stop: StopToken = StopToken()): String = withContext(Dispatchers.IO) {
        if (id == "alpine") return@withContext Linux.install(stop)
        val d = TermLogic.distro(id) ?: return@withContext "Unknown Linux: $id"
        if (!Linux.supported()) return@withContext "This phone's CPU isn't supported for Linux."
        val cpu = TermLogic.cpu(abi()); val url = cpu?.let { d.urls[it] } ?: return@withContext "${d.name} isn't built for this phone's CPU."
        if (installed(id)) return@withContext "${d.name} is already installed."
        if (installing.value != null) return@withContext "Already installing ${installing.value}. One at a time."
        installing.value = d.name
        val txz = File(Linux.base().apply { mkdirs() }, ".dl-$id.tar.xz")   // filesDir, not cache: big downloads survive a cache trim and resume
        try {
            Terminal.setup()
            val resumed = File(txz.path + ".part").let { it.exists() && it.length() > 0 }
            status.value = if (resumed) "Resuming ${d.name}…" else "Downloading ${d.name}…"
            try {
                // v0.33.5: resumes from .part (filesDir), and Stop (the Desktop page / notification) pauses it.
                // v0.34.0: every rootfs is SHA-256 checked (TermLogic.CHECKSUMS); a bad download is thrown away and retried.
                Fetch.download(listOf(url), txz, TermLogic.sha256(d, cpu.orEmpty()), progress = { got, total, retry ->
                    status.value = retry ?: Fetch.mbLine(d.name, got, total, resumed)
                }, sleep = stop.sleep, open = stop::open, cancelled = stop.cancelled)
            } catch (e: Exception) {
                if (e is Fetch.StoppedException) return@withContext "Download paused.".also { status.value = "Download paused" }
                return@withContext "${Fetch.reason(e)}, so ${d.name} didn't finish downloading. Tap install again to carry on.".also { status.value = it }
            }
            status.value = "Unpacking ${d.name}…"
            val r = Linux.rootfs(id); r.deleteRecursively(); r.mkdirs()
            val n = try {
                Linux.untarStream(org.tukaani.xz.XZInputStream(java.io.BufferedInputStream(txz.inputStream(), 256 * 1024)), r, 1) {
                    status.value = "Unpacking ${d.name}… $it files"
                }
            } catch (e: Exception) {
                txz.delete(); r.deleteRecursively()
                return@withContext "Unpacking ${d.name} failed: ${e.message}. Tap install to retry.".also { status.value = it }
            }
            txz.delete()
            if (!File(r, "bin/sh").exists() && !File(r, "usr/bin/sh").exists()) {
                r.deleteRecursively()
                return@withContext "Unpacking ${d.name} failed: no shell after $n files.".also { status.value = it }
            }
            Linux.prepare(r, id)
            File(r, ".blaze_ok").writeText(url)
            "${d.name} installed.".also { status.value = it }
        } catch (e: Exception) {
            "Install failed: ${e.message}".also { status.value = it }
        } finally { installing.value = null }
    }

    fun uninstall(id: String) { if (id == "alpine") Linux.uninstall() else Linux.rootfs(id).deleteRecursively() }
}
