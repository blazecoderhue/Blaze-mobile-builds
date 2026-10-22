package com.blaze.assistant.desktop

import com.blaze.assistant.desktop.DesktopDistro.Pm

/**
 * v0.34.0 Linux desktop, the pure rules (DesktopExtrasTest): the app drawer (.desktop files), a curated app store
 * with real package names per Linux, display sizes, snapshots, the auto-restart rule, the two-way clipboard
 * messages and the "this command could break things" check.
 */
object DesktopExtras {
    // ---------------------------------------------------------------- app drawer

    data class AppEntry(val name: String, val exec: String, val icon: String, val categories: String, val file: String)

    /** Lists every app the desktop's .desktop files describe (NoDisplay / Hidden ones and duplicates left out). */
    const val LIST_APPS_SCRIPT = "for f in /usr/share/applications/*.desktop /root/.local/share/applications/*.desktop; do " +
        "[ -f \"\$f\" ] && { echo \"@@FILE \$f\"; sed -n '/^\\[Desktop Entry\\]/,/^\\[/p' \"\$f\" | head -n 60; }; done 2>/dev/null; echo @@END"

    fun parseEntries(out: String): List<AppEntry> {
        val res = ArrayList<AppEntry>()
        for (chunk in out.split("@@FILE ").drop(1)) {
            val lines = chunk.lines()
            val file = lines.first().trim()
            val kv = HashMap<String, String>()
            for (l in lines.drop(1)) {
                if (l.startsWith("@@END")) break
                val i = l.indexOf('='); if (i <= 0) continue
                val k = l.substring(0, i).trim(); if (k !in kv) kv[k] = l.substring(i + 1).trim()
            }
            if (kv["Type"]?.let { it != "Application" } == true) continue
            if (kv["NoDisplay"].equals("true", true) || kv["Hidden"].equals("true", true)) continue
            val name = kv["Name"] ?: continue; val exec = kv["Exec"]?.let { cleanExec(it) } ?: continue
            if (exec.isBlank() || res.any { it.name == name }) continue
            res += AppEntry(name, exec, kv["Icon"].orEmpty(), kv["Categories"].orEmpty(), file)
        }
        return res.sortedBy { it.name.lowercase() }
    }

    /** Exec without the field codes (%U %f %i …) that only a launcher fills in. */
    fun cleanExec(exec: String): String = exec.replace(Regex("""\s*%[fFuUdDnNickvm]"""), "").replace("%%", "%").trim()

    /** A launch line that's safe to hand the shell: only the .desktop's own command, started in the background. */
    fun launchCmd(e: AppEntry): String = DesktopPlan.spoolCmd(e.exec)   // v0.43.0: through the desktop's own launcher (survives the command that asked)

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    // ---------------------------------------------------------------- curated store

    /** One app with its real package name on each Linux (null = not available there, with why in [note]). */
    data class StoreApp(val id: String, val name: String, val about: String, val pkgs: Map<Pm, List<String>?>, val launch: String,
                        val note: Map<Pm, String> = emptyMap(), val afterInstall: Map<Pm, String> = emptyMap())

    /**
     * Package names checked on 2026-10-18 against Alpine v3.20 main + community aarch64 APKINDEX, Debian bookworm arm64,
     * Ubuntu noble arm64 (main + universe) and Arch Linux ARM aarch64 core + extra. Fedora names are Fedora's usual ones
     * (its index wasn't reachable from here). Ubuntu's firefox / chromium are snaps, which can't run under PRoot.
     */
    val STORE: List<StoreApp> = listOf(
        StoreApp("firefox", "Firefox", "The full web browser.", mapOf(Pm.APK to listOf("firefox"), Pm.APT to listOf("firefox-esr"),
            Pm.PACMAN to listOf("firefox"), Pm.DNF to listOf("firefox")), "firefox-esr || firefox",
            note = mapOf(Pm.APT to "On Ubuntu Firefox is a snap and can't run here; Debian and Alpine have a real one.")),
        StoreApp("libreoffice", "LibreOffice", "Documents, spreadsheets and slides.", mapOf(Pm.APK to listOf("libreoffice"), Pm.APT to listOf("libreoffice"),
            Pm.PACMAN to listOf("libreoffice-fresh"), Pm.DNF to listOf("libreoffice")), "libreoffice"),
        StoreApp("code-server", "VS Code (code-server)", "VS Code in a browser tab, served on 127.0.0.1:8080 (open it in Blaze's browser).",
            mapOf(Pm.APK to null, Pm.APT to listOf("curl", "ca-certificates"), Pm.PACMAN to null, Pm.DNF to listOf("curl")),
            "code-server --bind-addr 127.0.0.1:8080 --auth none",
            note = mapOf(Pm.APK to "Needs Debian, Ubuntu or Fedora (code-server ships glibc builds).", Pm.PACMAN to "Not packaged for Arch Linux ARM; pick Debian or Ubuntu."),
            afterInstall = mapOf(Pm.APT to "curl -fsSL https://code-server.dev/install.sh | sh", Pm.DNF to "curl -fsSL https://code-server.dev/install.sh | sh")),
        StoreApp("gimp", "GIMP", "Photo editing.", mapOf(Pm.APK to listOf("gimp"), Pm.APT to listOf("gimp"), Pm.PACMAN to listOf("gimp"), Pm.DNF to listOf("gimp")), "gimp"),
        StoreApp("inkscape", "Inkscape", "Vector drawing.", mapOf(Pm.APK to listOf("inkscape"), Pm.APT to listOf("inkscape"), Pm.PACMAN to listOf("inkscape"), Pm.DNF to listOf("inkscape")), "inkscape"),
        StoreApp("vlc", "VLC", "Plays any video or music file.", mapOf(Pm.APK to listOf("vlc-qt"), Pm.APT to listOf("vlc"), Pm.PACMAN to listOf("vlc"), Pm.DNF to listOf("vlc")), "vlc"),
        StoreApp("geany", "Geany", "A light code editor.", mapOf(Pm.APK to listOf("geany"), Pm.APT to listOf("geany"), Pm.PACMAN to listOf("geany"), Pm.DNF to listOf("geany")), "geany"),
        StoreApp("thonny", "Thonny", "Python for beginners, with its own editor.", mapOf(Pm.APK to listOf("thonny"), Pm.APT to listOf("thonny"), Pm.PACMAN to null, Pm.DNF to listOf("thonny")),
            "thonny", note = mapOf(Pm.PACMAN to "Not in Arch Linux ARM's repositories.")),
        StoreApp("htop", "htop", "See what's running and using memory.", mapOf(Pm.APK to listOf("htop"), Pm.APT to listOf("htop"), Pm.PACMAN to listOf("htop"), Pm.DNF to listOf("htop")),
            "xfce4-terminal -e htop"),
        StoreApp("git", "Git", "Version control (used from the terminal).", mapOf(Pm.APK to listOf("git"), Pm.APT to listOf("git"), Pm.PACMAN to listOf("git"), Pm.DNF to listOf("git")),
            "xfce4-terminal"),
    )

    /** The packages for [app] on this Linux; null when it isn't available there. Ubuntu's Firefox is the one exception to apt. */
    fun packagesFor(app: StoreApp, d: DesktopDistro.Info): List<String>? {
        if (app.id == "firefox" && d.id == "ubuntu") return null
        return app.pkgs[d.pm]
    }

    fun whyNot(app: StoreApp, d: DesktopDistro.Info): String = app.note[d.pm] ?: "Not available on ${d.name}."

    /** The install script: the package manager (non-interactive), then the app's own step if it has one. */
    fun installScript(app: StoreApp, d: DesktopDistro.Info): String? {
        val p = packagesFor(app, d) ?: return null
        val list = p.joinToString(" ")
        val base = when (d.pm) {
            Pm.APK -> "apk add $list"
            Pm.APT -> "export DEBIAN_FRONTEND=noninteractive; apt-get update -q && apt-get install -y -q $list"
            Pm.PACMAN -> "pacman -Sy --noconfirm --needed $list"
            Pm.DNF -> "dnf install -y $list"
        }
        return listOfNotNull(base, app.afterInstall[d.pm]).joinToString(" && ") + " && echo BLAZE_STORE_OK"
    }

    fun launchApp(app: StoreApp): String = DesktopPlan.spoolCmd(app.launch)

    // ---------------------------------------------------------------- display

    data class Display(val w: Int, val h: Int, val depth: Int) { val label get() = "${w}\u00d7$h \u00b7 $depth-bit" }

    val DISPLAYS = listOf(Display(1024, 576, 16), Display(1280, 720, 16), Display(1600, 900, 16), Display(1920, 1080, 16), Display(1280, 720, 24), Display(720, 1280, 16))

    fun displayKey(d: Display) = "${d.w}x${d.h}@${d.depth}"
    fun parseDisplay(s: String?): Display {
        val m = Regex("""^(\d{3,4})x(\d{3,4})@(16|24)$""").find(s?.trim().orEmpty()) ?: return DISPLAYS[0]
        val d = Display(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        return if (d.w in 640..2560 && d.h in 480..2560) d else DISPLAYS[0]
    }

    /** Rough memory the screen needs (Xvnc framebuffer + XFCE), to warn on 3 GB phones. */
    fun displayMb(d: Display): Int = (d.w * d.h * (if (d.depth == 24) 4 else 2) * 3 / 1_000_000) + 40

    // ---------------------------------------------------------------- snapshots

    data class Snapshot(val file: String, val distro: String, val at: Long, val bytes: Long)

    /** blaze-snap-<distro>-<epoch ms>.tar.gz */
    fun snapName(distro: String, at: Long) = "blaze-snap-$distro-$at.tar.gz"
    fun parseSnap(name: String, bytes: Long): Snapshot? = Regex("""^blaze-snap-([a-z0-9]+)-(\d{10,})\.tar\.gz$""").find(name)?.let {
        Snapshot(name, it.groupValues[1], it.groupValues[2].toLong(), bytes) }

    /**
     * Run inside the Linux itself: packs everything except the virtual folders and the shared phone folder into
     * /root/phone/.snapshots (that folder is the phone's own storage, so the snapshot survives a reinstall).
     */
    fun snapshotScript(name: String): String = "mkdir -p /root/phone/.snapshots && cd / && tar -czf /root/phone/.snapshots/$name.part " +
        "--exclude=./proc --exclude=./sys --exclude=./dev --exclude=./tmp --exclude=./sdcard --exclude=./storage --exclude=./root/phone --exclude=./root/Phone " +
        "--exclude=./var/cache/apk --exclude=./var/cache/apt --exclude=./var/cache/pacman --exclude=./var/cache/dnf . 2>/tmp/snap.err; " +
        "r=\$?; if [ \$r -le 1 ]; then mv /root/phone/.snapshots/$name.part /root/phone/.snapshots/$name && echo BLAZE_SNAP_OK; else rm -f /root/phone/.snapshots/$name.part; tail -n 3 /tmp/snap.err; fi"

    /** Enough room for a snapshot: about the Linux's size again (compressed ~45 %), plus 200 MB to spare. */
    fun roomForSnapshot(rootfsBytes: Long, freeBytes: Long): Boolean = freeBytes > rootfsBytes / 2 + 200_000_000L

    // ---------------------------------------------------------------- auto-restart

    /**
     * Restart the desktop by itself: it was running ([wasOn]), the user didn't stop it, it isn't up now, the setting
     * is on, and it hasn't been restarted 3 times in the last 10 minutes (no restart loops on a phone short of memory).
     */
    fun shouldRestart(wasOn: Boolean, userStopped: Boolean, alive: Boolean, setting: Boolean, recent: List<Long>, now: Long): Boolean =
        wasOn && !userStopped && !alive && setting && recent.count { now - it < 10 * 60_000L } < 3

    // ---------------------------------------------------------------- clipboard (RFB cut text)

    /** ClientCutText: the phone's clipboard into the desktop (Latin-1 per the RFB spec; other characters become '?'). */
    fun clientCutText(text: String): ByteArray {
        val b = text.take(100_000).map { if (it.code in 0..255) it.code.toByte() else '?'.code.toByte() }.toByteArray()
        val out = ByteArray(8 + b.size)
        out[0] = 6
        val n = b.size
        out[4] = (n ushr 24).toByte(); out[5] = (n ushr 16).toByte(); out[6] = (n ushr 8).toByte(); out[7] = n.toByte()
        System.arraycopy(b, 0, out, 8, n)
        return out
    }

    fun serverCutText(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1).replace("\r\n", "\n")

    // ---------------------------------------------------------------- destructive commands

    private val danger = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+(/|~|\*|/\*|\.\.?)(\s|$)"""), Regex("""\brm\s+-[a-zA-Z]*r[a-zA-Z]*f|\brm\s+-[a-zA-Z]*f[a-zA-Z]*r"""),
        Regex("""\bmkfs(\.\w+)?\b"""), Regex("""\bdd\b[^|;&]*\bof="""), Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"""),
        Regex(""">\s*/dev/(sd|mmcblk|nvme|block)"""), Regex("""\bchmod\s+-R\s+0?777\s+/(\s|$)"""), Regex("""\bchown\s+-R\b[^;&|]*\s/(\s|$)"""),
        Regex("""\b(apt(-get)?|dnf|yum)\s+(remove|purge|autoremove)\b"""), Regex("""\bpacman\s+-R"""), Regex("""\bapk\s+del\b"""),
        Regex("""\b(shutdown|reboot|halt|poweroff)\b"""), Regex("""\bkill\s+-9\s+-1\b"""), Regex("""\bgit\s+(push\s+.*--force|reset\s+--hard|clean\s+-[a-z]*f)"""),
        Regex("""\bcurl\b[^|]*\|\s*(sudo\s+)?(sh|bash)\b"""), Regex("""\bwget\b[^|]*\|\s*(sudo\s+)?(sh|bash)\b"""),
    )

    /** True when a command could delete files, remove software or take the system down: Blaze asks first, saying so. */
    fun dangerous(cmd: String): Boolean = danger.any { it.containsMatchIn(cmd) }

    fun dangerTitle(what: String) = "Careful: this could delete files or break the Linux. $what"
}
