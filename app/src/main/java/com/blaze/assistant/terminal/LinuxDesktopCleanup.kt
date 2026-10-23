package com.blaze.assistant.terminal

import android.content.Context
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wave B-4 (HQ item 12): the on-phone Linux desktop (XFCE + TigerVNC under PRoot, its Desktop page, live cards, the
 * desktop agent tools, BlazeOS Linux apps and its foreground services) was removed. This runs once after
 * the update, in the background, and frees what the desktop left behind:
 *
 *  - its saved settings ([LEGACY_PREFS]) are dropped, so nothing reads or offers them again;
 *  - its notification channels are deleted;
 *  - in every installed Linux that has the desktop's mark (/root/.blaze_desktop_ok) the desktop-only packages are
 *    removed with that Linux's own package manager ([removeScript]) and its marks, VNC folder, staged packages and
 *    package caches are deleted ([leftovers]);
 *  - partial desktop downloads (.dl-<id>.tar.xz and .part) in filesDir/linux are deleted when no install is running.
 *
 * The Linux itself (Alpine and any other the Terminal installed) stays: the Terminal, run_shell linux=true, run_code
 * and git use it. Packages that are useful outside the desktop (imagemagick, fonts, dbus, procps) are kept. Desktop
 * snapshots in ~/.snapshots are the user's own backups and are left alone. Pure parts: [removeScript], [leftovers],
 * [LEGACY_PREFS] (WaveB4NoLinuxTest).
 */
object LinuxDesktopCleanup {
    /** Set once the cleanup ran to the end (or gave up after [MAX_TRIES] launches). */
    const val PREF_DONE = "linux_desktop_removed_v1"
    private const val PREF_TRIES = "linux_desktop_removed_tries"
    private const val MAX_TRIES = 3

    /** Every pref key the removed desktop wrote. */
    val LEGACY_PREFS = listOf(
        "linux_desktop_on", "linux_desktop_mirror", "linux_desktop_distro", "linux_desktop_paused", "linux_desktop_display",
        "linux_desktop_autorestart", "linux_desktop_keep_running", "linux_desktop_keep_asked_battery",
    )

    /** The desktop's notification channels (download progress, "desktop running"). */
    val LEGACY_CHANNELS = listOf("blaze_desktop", "blaze_desktop_running")

    /** The file the desktop install left to say it was done (inside the rootfs). */
    const val MARK = "root/.blaze_desktop_ok"

    /** Desktop-only packages per package manager (the desktop's own install lists without imagemagick / fonts / dbus / procps). */
    private val PKGS: Map<String, List<String>> = mapOf(
        "apk" to listOf("xfce4", "xfce4-terminal", "thunar", "mousepad", "xfce4-session", "xfwm4", "xfdesktop", "xfce4-panel",
            "adwaita-icon-theme", "dbus-x11", "tigervnc", "xdotool", "dillo"),
        "apt" to listOf("xfce4-session", "xfwm4", "xfce4-panel", "xfdesktop4", "xfce4-settings", "xfce4-terminal", "thunar", "mousepad",
            "adwaita-icon-theme", "dbus-x11", "tigervnc-standalone-server", "tigervnc-tools", "xdotool", "dillo"),
        "pacman" to listOf("xfce4-session", "xfwm4", "xfce4-panel", "xfdesktop", "xfce4-settings", "xfce4-terminal", "thunar", "mousepad",
            "adwaita-icon-theme", "tigervnc", "xdotool", "dillo"),
        "dnf" to listOf("xfce4-session", "xfwm4", "xfce4-panel", "xfdesktop", "xfce4-settings", "xfce4-terminal", "Thunar", "mousepad",
            "adwaita-icon-theme", "dbus-x11", "tigervnc-server-minimal", "xdotool", "dillo"),
    )

    /** The package manager a Linux uses, by its id. */
    fun pm(distro: String): String = when (distro) {
        "ubuntu", "debian" -> "apt"
        "archlinux", "manjaro" -> "pacman"
        "fedora" -> "dnf"
        else -> "apk"
    }

    /**
     * One shell script (run inside that Linux) that removes the desktop's packages one by one, skipping any that isn't
     * installed or that another installed package still needs, so it never fails as a whole and never removes something
     * the user installed for themselves.
     */
    fun removeScript(distro: String): String {
        val list = PKGS.getValue(pm(distro)).joinToString(" ")
        val loop = when (pm(distro)) {
            "apt" -> "export DEBIAN_FRONTEND=noninteractive; for p in $list; do dpkg -s \"\$p\" >/dev/null 2>&1 && apt-get -y -q purge \"\$p\" >/dev/null 2>&1; done; " +
                "apt-get -y -q autoremove --purge >/dev/null 2>&1; apt-get -q clean >/dev/null 2>&1"
            "pacman" -> "for p in $list; do pacman -Q \"\$p\" >/dev/null 2>&1 && pacman -Rns --noconfirm \"\$p\" >/dev/null 2>&1; done"
            "dnf" -> "for p in $list; do rpm -q \"\$p\" >/dev/null 2>&1 && dnf -y -q remove \"\$p\" >/dev/null 2>&1; done; dnf -q clean all >/dev/null 2>&1"
            else -> "for p in $list; do apk info -e \"\$p\" >/dev/null 2>&1 && apk del -q \"\$p\" >/dev/null 2>&1; done"
        }
        return "($loop); rm -rf /root/.vnc /tmp/.X11-unix /tmp/.X1-lock /tmp/blaze-run /tmp/.blaze-xvnc.pid; rm -f /$MARK; echo BLAZE_DESKTOP_REMOVED"
    }

    /** Paths inside a rootfs (relative) the desktop left: its mark, VNC folder, staged packages and package caches. */
    fun leftovers(distro: String): List<String> = listOf(MARK, "root/.vnc", "var/cache/blaze-apk") + when (pm(distro)) {
        "apt" -> listOf("var/cache/apt/archives")
        "pacman" -> listOf("var/cache/pacman/pkg")
        "dnf" -> listOf("var/cache/dnf", "var/cache/libdnf5")
        else -> listOf("var/cache/apk")
    }

    /** Files the desktop kept in Blaze's home (~). */
    val HOME_FILES = listOf(".desktop_shot.png", ".desktop_apps.txt")

    /** Runs the one-time cleanup. Safe to call on every start: it returns at once when it is done. */
    suspend fun runOnce(ctx: Context) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        try {
            if (Settings.bool(PREF_DONE, false)) return@withContext
            val tries = Settings.int(PREF_TRIES, 0) + 1
            Settings.setInt(PREF_TRIES, tries)
            Settings.removeKeys(LEGACY_PREFS)
            try {
                val nm = app.getSystemService(android.app.NotificationManager::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) LEGACY_CHANNELS.forEach { runCatching { nm?.deleteNotificationChannel(it) } }
            } catch (_: Throwable) { }
            HOME_FILES.forEach { runCatching { File(File(app.filesDir, "home"), it).delete() } }
            var ok = true
            if (Linux.supported()) for (id in TermLogic.DISTROS.map { it.id }) {
                val root = Linux.rootfs(id)
                if (!File(root, MARK).exists()) continue
                if (Distros.installed(id)) {
                    val out = try { Terminal.run(removeScript(id), timeoutSec = 900, linux = true, distro = id) } catch (e: Exception) { "Error: ${e.message}" }
                    if ("BLAZE_DESKTOP_REMOVED" !in out) ok = false
                }
                leftovers(id).forEach { deleteNoFollow(File(root, it), keepDir = it.startsWith("var/cache/")) }
            }
            if (!Linux.installing.value && Distros.installing.value == null) {
                (Linux.base().listFiles() ?: emptyArray()).filter { it.name.startsWith(".dl-") && (it.name.endsWith(".tar.xz") || it.name.endsWith(".tar.xz.part")) }
                    .forEach { runCatching { it.delete() } }
            }
            if (ok || tries >= MAX_TRIES) Settings.setBool(PREF_DONE, true)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            com.blaze.assistant.device.CrashLog.quiet("LinuxDesktopCleanup", e)
        }
    }

    /** Deletes without following symlinks (a rootfs links to /proc, /sdcard…); [keepDir] keeps [f] itself, only its contents go. */
    private fun deleteNoFollow(f: File, keepDir: Boolean = false) {
        val path = f.toPath()
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (!java.nio.file.Files.isSymbolicLink(path) && f.isDirectory) (f.listFiles() ?: emptyArray()).forEach { deleteNoFollow(it) }
        if (!keepDir) runCatching { java.nio.file.Files.deleteIfExists(path) }
    }
}
