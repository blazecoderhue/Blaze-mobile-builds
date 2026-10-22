package com.blaze.assistant.desktop

import com.blaze.assistant.desktop.DesktopPlan.Phase

/**
 * v0.33.5, pure (DesktopDistroTest): which Linux the desktop, the Terminal and BlazeOS's Linux apps run in. Alpine
 * stays the default (an existing Alpine desktop keeps working, nothing is reinstalled); Ubuntu, Debian, Arch Linux ARM
 * and Fedora install from the same proot-distro rootfs tarballs as the Terminal's "More Linux" list
 * ([com.blaze.assistant.terminal.TermLogic.DISTROS], same ids), then the desktop packages with that Linux's own
 * package manager.
 *
 * Every package name below was checked on 2026-10-18 against the real arm64 / aarch64 indexes: Alpine v3.20
 * main + community, Debian bookworm and Ubuntu noble (packages.debian.org / packages.ubuntu.com, arm64), Arch Linux
 * ARM aarch64 core + extra (mirror.archlinuxarm.org *.db), Fedora 41 Everything aarch64 (primary.xml, which also
 * showed /usr/bin/Xvnc and /usr/bin/vncpasswd in tigervnc-server-minimal and dbus-launch in dbus-x11).
 */
object DesktopDistro {
    enum class Pm(val tool: String) { APK("apk"), APT("apt"), PACMAN("pacman"), DNF("dnf") }

    data class Info(
        val id: String, val name: String, val pm: Pm, val batches: List<List<String>>, val optional: Set<List<String>>,
        val downloadMb: Int, val diskMb: Int, val blurb: String, val recommended: Boolean = false,
    ) {
        val packages: List<String> get() = batches.flatten()
        fun sizeLine(): String = "About $downloadMb MB to download and $diskMb MB on the phone. Wi-Fi is best; it takes " +
            (if (downloadMb <= 300) "5\u201315" else "10\u201330") + " minutes the first time."
    }

    const val DEFAULT = "alpine"

    private val apt = listOf(
        listOf("procps", "dbus", "dbus-x11", "fonts-dejavu-core"),
        listOf("tigervnc-standalone-server", "tigervnc-tools", "xdotool"),
        listOf("xfce4-session", "xfwm4", "xfce4-panel", "xfdesktop4", "xfce4-settings"),
        listOf("xfce4-terminal", "thunar", "mousepad", "adwaita-icon-theme"),
        listOf("imagemagick"),
        listOf("dillo"),
    )

    val ALPINE = Info("alpine", "Alpine", Pm.APK, DesktopPlan.BATCHES, DesktopPlan.OPTIONAL, DesktopPlan.DOWNLOAD_MB, DesktopPlan.DISK_MB,
        "Smallest and fastest. Recommended for most phones.", recommended = true)
    val UBUNTU = Info("ubuntu", "Ubuntu", Pm.APT, apt, setOf(listOf("imagemagick"), listOf("dillo")), 400, 1200,
        "Ubuntu 24.04 with apt. The most guides online.")
    val DEBIAN = Info("debian", "Debian", Pm.APT, apt, setOf(listOf("imagemagick"), listOf("dillo")), 350, 1100,
        "Debian 12 with apt. Stable and well known.")
    val ARCH = Info("archlinux", "Arch", Pm.PACMAN, listOf(
        listOf("procps-ng", "dbus", "ttf-dejavu"),
        listOf("tigervnc", "xdotool"),
        listOf("xfce4-session", "xfwm4", "xfce4-panel", "xfdesktop", "xfce4-settings"),
        listOf("xfce4-terminal", "thunar", "mousepad", "adwaita-icon-theme"),
        listOf("imagemagick"),
        listOf("dillo"),
    ), setOf(listOf("imagemagick"), listOf("dillo")), 550, 1600, "Arch Linux ARM with pacman. Newest versions, biggest download.")
    val FEDORA = Info("fedora", "Fedora", Pm.DNF, listOf(
        listOf("procps-ng", "dbus-x11", "dejavu-sans-fonts"),
        listOf("tigervnc-server-minimal", "xdotool"),
        listOf("xfce4-session", "xfwm4", "xfce4-panel", "xfdesktop", "xfce4-settings"),
        listOf("xfce4-terminal", "Thunar", "mousepad", "adwaita-icon-theme"),
        listOf("ImageMagick"),
        listOf("dillo"),
    ), setOf(listOf("ImageMagick"), listOf("dillo")), 420, 1300, "Fedora with dnf. 64-bit phones only.")

    val ALL: List<Info> = listOf(ALPINE, UBUNTU, DEBIAN, ARCH, FEDORA)

    /** The saved choice, or Alpine for anything unknown (also an empty / missing preference). */
    fun of(id: String?): Info = ALL.firstOrNull { it.id == id?.trim()?.lowercase() } ?: ALPINE

    /** The choices this phone can install: Alpine always; the others when a rootfs exists for its CPU ([cpu] = "aarch64" / "arm" / null). */
    fun available(cpu: String?): List<Info> = ALL.filter { it.id == DEFAULT || when (it.id) {
        "fedora" -> cpu == "aarch64"
        else -> cpu == "aarch64" || cpu == "arm"
    } }

    // ---------- install scripts (non-Alpine; Alpine keeps DesktopPlan.stepScript with its mirrors and prefetch) ----------

    /** One-time fixes so the package manager works under PRoot, run before every step (idempotent). */
    fun prep(pm: Pm, id: String = ""): String = when (pm) {
        Pm.APK -> ""
        // A Stop mid-install leaves dpkg half-done: finish it first. Ubuntu needs universe (XFCE, TigerVNC, Dillo live there).
        Pm.APT -> "export DEBIAN_FRONTEND=noninteractive\n" +
            (if (id == "ubuntu") "if [ -f /etc/apt/sources.list.d/ubuntu.sources ] && ! grep -q universe /etc/apt/sources.list.d/ubuntu.sources; then " +
                "sed -i 's/^Components:.*/Components: main restricted universe multiverse/' /etc/apt/sources.list.d/ubuntu.sources; fi\n" else "") +
            "dpkg --configure -a >/dev/null 2>&1 || true\n"
        // PRoot can't drop to pacman's download user or measure free space by mount point; a killed pacman leaves its lock.
        Pm.PACMAN -> "rm -f /var/lib/pacman/db.lck\n" +
            "sed -i -e 's/^DownloadUser/#DownloadUser/' -e 's/^CheckSpace/#CheckSpace/' /etc/pacman.conf\n" +
            "if [ ! -s /etc/pacman.d/gnupg/pubring.gpg ] && [ ! -s /etc/pacman.d/gnupg/pubring.kbx ]; then pacman-key --init && pacman-key --populate archlinuxarm; fi\n"
        Pm.DNF -> ""
    }

    /** Refresh the package list (pacman upgrades too: installing on a stale Arch is a partial upgrade, which can break). */
    fun refreshCmd(pm: Pm): String = when (pm) {
        Pm.APK -> "apk update"
        Pm.APT -> "apt-get -o Acquire::Retries=3 update"
        Pm.PACMAN -> "pacman -Syu --noconfirm --disable-download-timeout"
        Pm.DNF -> "dnf -y --setopt=keepcache=True makecache"
    }

    /** Installs [pkgs] without questions (already-installed ones are skipped by every manager). */
    fun installCmd(pm: Pm, pkgs: List<String>): String {
        val p = pkgs.filter { safe(it) }.joinToString(" ")
        return when (pm) {
            Pm.APK -> "apk add $p"
            Pm.APT -> "apt-get -o Acquire::Retries=3 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold install -y --no-install-recommends $p"
            Pm.PACMAN -> "pacman -S --noconfirm --needed --disable-download-timeout $p"
            Pm.DNF -> "dnf -y --setopt=install_weak_deps=False --setopt=keepcache=True install $p"
        }
    }

    /** Package names only ever reach the shell as words: letters, digits and . _ + - (no leading dash). */
    fun safe(s: String): Boolean = s.isNotEmpty() && s.length < 100 && s.all { it.isLetterOrDigit() || it in "._+-" } && !s.startsWith("-") && !s.startsWith(".")

    /** One install step for a non-Alpine Linux: fixes, refresh when [update], then add [pkgs]. */
    fun stepScript(info: Info, pkgs: List<String>, update: Boolean): String = buildString {
        append("set -e\n").append(prep(info.pm, info.id))
        if (update) append(refreshCmd(info.pm)).append('\n')
        if (pkgs.isNotEmpty()) append(installCmd(info.pm, pkgs)).append('\n')
        append("echo BLAZE_STEP_OK")
    }

    /** "Install: apt install <package>" words for hints. */
    fun installHint(pm: Pm): String = when (pm) {
        Pm.APK -> "apk add <package>"
        Pm.APT -> "apt install <package>"
        Pm.PACMAN -> "pacman -S <package>"
        Pm.DNF -> "dnf install <package>"
    }

    // ---------- BlazeOS Linux apps ----------

    /** Alpine names (what BlazeOS manifests list) → this Linux's names, for the apps the store carries and a few common ones. */
    private val NAMES: Map<String, Map<Pm, List<String>>> = mapOf(
        "xeyes" to mapOf(Pm.APT to listOf("x11-apps"), Pm.PACMAN to listOf("xorg-xeyes"), Pm.DNF to listOf("xeyes")),
        "xcalc" to mapOf(Pm.APT to listOf("x11-apps"), Pm.PACMAN to listOf("xorg-xcalc"), Pm.DNF to listOf("xcalc")),
        "xclock" to mapOf(Pm.APT to listOf("x11-apps"), Pm.PACMAN to listOf("xorg-xclock"), Pm.DNF to listOf("xclock")),
        "font-dejavu" to mapOf(Pm.APT to listOf("fonts-dejavu-core"), Pm.PACMAN to listOf("ttf-dejavu"), Pm.DNF to listOf("dejavu-sans-fonts")),
        "imagemagick" to mapOf(Pm.DNF to listOf("ImageMagick")),
        "thunar" to mapOf(Pm.DNF to listOf("Thunar")),
        "py3-pip" to mapOf(Pm.APT to listOf("python3-pip"), Pm.PACMAN to listOf("python-pip"), Pm.DNF to listOf("python3-pip")),
        "python3" to mapOf(Pm.PACMAN to listOf("python")),
        "build-base" to mapOf(Pm.APT to listOf("build-essential"), Pm.PACMAN to listOf("base-devel"), Pm.DNF to listOf("gcc", "make")),
    )

    /**
     * The packages to install for a BlazeOS Linux app: [perDistro] (a manifest's own list for this Linux, by id) wins,
     * else each Alpine name mapped through the table above, else kept as it is (most names are the same everywhere).
     */
    fun mapPackages(info: Info, alpine: List<String>, perDistro: Map<String, List<String>> = emptyMap()): List<String> {
        perDistro[info.id]?.takeIf { it.isNotEmpty() }?.let { return it.filter { p -> safe(p) }.distinct() }
        if (info.pm == Pm.APK) return alpine.filter { safe(it) }.distinct()
        return alpine.flatMap { NAMES[it]?.get(info.pm) ?: listOf(it) }.filter { safe(it) }.distinct()
    }

    /** The shell line that installs a Linux app's packages in [info] (refreshing the list first where it's needed). */
    fun appInstallScript(info: Info, pkgs: List<String>): String = when (info.pm) {
        Pm.APK -> "apk add --no-cache " + pkgs.filter { safe(it) }.joinToString(" ")
        Pm.APT -> prep(info.pm, info.id) + "{ " + installCmd(info.pm, pkgs) + " || { " + refreshCmd(info.pm) + " && " + installCmd(info.pm, pkgs) + "; }; }"
        Pm.PACMAN -> prep(info.pm, info.id) + installCmd(info.pm, pkgs)
        Pm.DNF -> installCmd(info.pm, pkgs)
    }

    // ---------- progress words ----------

    private val numbered = Regex("""[(\[]\s*(\d+)/(\d+)\s*[)\]]\s+(?:Installing|Upgrading|installing|upgrading)\s*:?\s+(\S+)""")
    private val aptLine = Regex("""^(?:Setting up|Unpacking)\s+(\S+?)(?::\S+)?\s""")
    /** "Get:12 http://deb.debian.org/debian bookworm/main arm64 xfwm4 arm64 4.18.0-1 [600 kB]" (older apt leaves out the first arch). */
    private val aptGet = Regex("""^Get:\d+\s+\S+\s+\S+\s+(?:(?:arm64|armhf|amd64|all)\s+)?(\S+)\s""")

    /** One plain line for a package manager's output line (percent when the line has "n/total"), or null. */
    fun progressLine(pm: Pm, line: String): Pair<Int?, String>? {
        if (pm == Pm.APK) return DesktopPlan.progressLine(line)
        numbered.find(line)?.let { m ->
            val (i, n, pkg) = m.destructured
            val pct = (i.toInt() * 100 / n.toInt().coerceAtLeast(1)).coerceIn(0, 99)
            return pct to "Installing ${pkg.substringBefore(".aarch64").substringBefore(".noarch")} ($i of $n)"
        }
        if (pm == Pm.APT) {
            aptGet.find(line)?.let { return null to "Downloading ${it.groupValues[1]}\u2026" }
            aptLine.find(line)?.let { return null to "Installing ${it.groupValues[1]}\u2026" }
        }
        if (pm == Pm.PACMAN && line.trim().startsWith(":: Synchronizing")) return null to "Checking what to download\u2026"
        return null
    }

    // ---------- Remove downloaded files ----------

    /**
     * What "Remove downloaded files" deletes, relative to filesDir/linux: half-downloaded base archives, the package
     * caches inside [info]'s Linux, and the Linux itself when its base never finished installing ([baseInstalled]
     * false). An installed Alpine is never removed (the Terminal, run_code and git use it). Entries ending in a
     * slash and star mean "the files inside, keep the folder" (apt needs its archives/partial folder to exist).
     */
    fun removalPlan(info: Info, baseInstalled: Boolean, alpineArchive: String): List<String> = buildList {
        val r = info.id
        if (info.id == DEFAULT) {
            add(alpineArchive); add("$alpineArchive.part")
            if (!baseInstalled) add(r)
        } else {
            add(".dl-${info.id}.tar.xz"); add(".dl-${info.id}.tar.xz.part")
            if (!baseInstalled) add(r)
        }
        if (baseInstalled) when (info.pm) {
            Pm.APK -> { add("$r/${DesktopPrefetch.STAGE}"); add("$r/var/cache/apk/*") }
            Pm.APT -> add("$r/var/cache/apt/archives/*")
            Pm.PACMAN -> add("$r/var/cache/pacman/pkg/*")
            Pm.DNF -> { add("$r/var/cache/dnf/*"); add("$r/var/cache/libdnf5/*") }
        }
    }
}

/**
 * v0.33.5, pure (DesktopFlowTest): Stop during a download, the "Download paused" state, Start resuming, and "Remove
 * downloaded files". The Android side ([LinuxDesktop]) follows these rules.
 */
object DesktopFlow {
    const val PAUSED_LINE = "Download paused"
    const val STOPPED_MARK = "BLAZE_STOPPED"

    /** A Stop button belongs on these (the install's "Working…" became "Stop download"). */
    fun canStop(p: Phase): Boolean = p == Phase.INSTALLING || p == Phase.STARTING || p == Phase.RUNNING

    /** True when Stop means "pause the download" (keep what's downloaded) rather than "stop the desktop". */
    fun stopPauses(p: Phase): Boolean = p == Phase.INSTALLING

    /** Where Stop lands: a download pauses, a running or starting desktop stops. */
    fun afterStop(p: Phase): Phase = if (stopPauses(p)) Phase.PAUSED else Phase.STOPPED

    /** On opening Blaze again: a paused download that never finished shows as paused (not "Not installed yet"). */
    fun onLaunch(p: Phase, pausedPref: Boolean, installed: Boolean): Phase =
        if ((p == Phase.IDLE || p == Phase.STOPPED) && pausedPref && !installed) Phase.PAUSED else p

    /** After "Remove downloaded files": back to the start. */
    fun afterRemove(): Phase = Phase.IDLE

    /** The distro can change only when nothing is downloading, starting or running. */
    fun canSwitch(p: Phase): Boolean = p !in setOf(Phase.INSTALLING, Phase.STARTING, Phase.STOPPING, Phase.RUNNING)

    /**
     * "Remove downloaded files" is offered while paused, after an error, or idle with an unfinished install, and only
     * when there's something to remove. An installed, idle desktop doesn't show it.
     */
    fun canRemove(p: Phase, keptBytes: Long, installed: Boolean = false): Boolean = keptBytes > 0 && when (p) {
        Phase.PAUSED, Phase.ERROR -> true
        Phase.IDLE, Phase.STOPPED -> !installed
        else -> false
    }

    /** The paused card's line. */
    fun pausedLine(keptBytes: Long, distro: String): String {
        val mb = "%.1f".format(java.util.Locale.US, keptBytes / 1_000_000.0)
        return if (keptBytes > 0) "$mb MB of $distro is kept on the phone. Start carries on from where it stopped."
        else "Start carries on from where it stopped."
    }

    /** The main button's label for a phase. */
    fun mainLabel(p: Phase, killed: Boolean): String = when {
        killed -> "Resume"
        p == Phase.INSTALLING -> "Stop download"
        p == Phase.STARTING || p == Phase.STOPPING -> "Stop"
        p == Phase.PAUSED -> "Start"
        else -> "Start"
    }
}
