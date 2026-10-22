package com.blaze.assistant.terminal

import android.view.KeyEvent

/**
 * v0.29.2 Blaze Terminal: pure parts (TermLogicTest). The extra-keys rows copy Termux's default layout, the
 * environment list decides which shells a new tab can open, and [phoneArgs] builds the phone shell's argv.
 */
object TermLogic {
    /** A key in the extra-keys rows: [code] is an Android key code, [text] is typed as-is, [toggle] is CTRL/ALT. */
    data class Key(val label: String, val code: Int = 0, val text: String = "", val toggle: String = "", val repeat: Boolean = false)

    val ROWS: List<List<Key>> = listOf(
        listOf(Key("ESC", KeyEvent.KEYCODE_ESCAPE), Key("/", text = "/"), Key("―", text = "-"), Key("HOME", KeyEvent.KEYCODE_MOVE_HOME),
            Key("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true), Key("END", KeyEvent.KEYCODE_MOVE_END), Key("PGUP", KeyEvent.KEYCODE_PAGE_UP, repeat = true)),
        listOf(Key("⇥", KeyEvent.KEYCODE_TAB), Key("CTRL", toggle = "ctrl"), Key("ALT", toggle = "alt"), Key("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
            Key("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true), Key("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true), Key("PGDN", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true)),
    )

    /** A shell a tab can run. [hint] = the package-manager lines in the welcome text. */
    data class Env(val id: String, val label: String, val short: String, val hint: List<String> = emptyList())

    /** A Linux the user can install. [urls] maps CPU ("aarch64"/"arm") to a proot-distro rootfs tarball (.tar.xz). */
    data class Distro(val id: String, val name: String, val version: String, val sizeMb: Map<String, Int>,
                      val urls: Map<String, String>, val hint: List<String>,
                      /** v0.34.0: SHA-256 of each rootfs tarball (checked after download) and its exact size in bytes. */
                      val sha256: Map<String, String> = emptyMap(), val bytes: Map<String, Long> = emptyMap()) {
        fun env() = Env(id, "$name $version".trim(), id, hint)
    }

    private const val PD = "https://github.com/termux/proot-distro/releases/download"

    val DISTROS: List<Distro> = listOf(
        Distro("alpine", "Alpine Linux", "", mapOf("aarch64" to 4, "arm" to 4), emptyMap(),
            listOf("Search:  apk search <query>", "Install: apk add <package>", "Upgrade: apk update && apk upgrade")),
        Distro("ubuntu", "Ubuntu", "24.04", mapOf("aarch64" to 64, "arm" to 63),
            mapOf("aarch64" to "$PD/v4.18.0/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz", "arm" to "$PD/v4.18.0/ubuntu-noble-arm-pd-v4.18.0.tar.xz"),
            listOf("Search:  apt search <query>", "Install: apt install <package>", "Upgrade: apt update && apt upgrade")),
        Distro("debian", "Debian", "12", mapOf("aarch64" to 43, "arm" to 42),
            mapOf("aarch64" to "$PD/v4.17.3/debian-bookworm-aarch64-pd-v4.17.3.tar.xz", "arm" to "$PD/v4.17.3/debian-bookworm-arm-pd-v4.17.3.tar.xz"),
            listOf("Search:  apt search <query>", "Install: apt install <package>", "Upgrade: apt update && apt upgrade")),
        Distro("archlinux", "Arch Linux", "ARM", mapOf("aarch64" to 150, "arm" to 141),
            mapOf("aarch64" to "$PD/v4.22.1/archlinux-aarch64-pd-v4.22.1.tar.xz", "arm" to "$PD/v4.22.1/archlinux-arm-pd-v4.22.1.tar.xz"),
            listOf("Search:  pacman -Ss <query>", "Install: pacman -S <package>", "Upgrade: pacman -Syu")),
        Distro("fedora", "Fedora", "", mapOf("aarch64" to 34), mapOf("aarch64" to "$PD/v4.24.0/fedora-aarch64-pd-v4.24.0.tar.xz"),
            listOf("Search:  dnf search <query>", "Install: dnf install <package>", "Upgrade: dnf upgrade")),
        Distro("manjaro", "Manjaro", "ARM", mapOf("aarch64" to 141), mapOf("aarch64" to "$PD/v4.18.0/manjaro-aarch64-pd-v4.18.0.tar.xz"),
            listOf("Search:  pacman -Ss <query>", "Install: pacman -S <package>", "Upgrade: pacman -Syu")),
    )

    /**
     * v0.34.0: checksums and real sizes for every rootfs Blaze downloads. Computed on 2026-10-18 by streaming each
     * release asset through sha256sum; they match termux/proot-distro's own distro-plugins (TARBALL_SHA256) and the
     * GitHub release asset sizes.
     */
    val CHECKSUMS: Map<String, Pair<String, Long>> = mapOf(
        "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz" to ("91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa" to 64_133_552L),
        "ubuntu-noble-arm-pd-v4.18.0.tar.xz" to ("2afb7e1ff17983fa2cf4c57edeea6be427ffb0359d8628b24a147b4c8aa276d5" to 63_043_912L),
        "debian-bookworm-aarch64-pd-v4.17.3.tar.xz" to ("3a841a794ae5999b33e33b329582ed0379d4f54ca62c6ce5a8eb9cff5ef8900b" to 42_912_980L),
        "debian-bookworm-arm-pd-v4.17.3.tar.xz" to ("85861ab139d4042302796cf46a93a9efbcb4808c06f7a1ae5fb71812f4564424" to 41_469_448L),
        "archlinux-aarch64-pd-v4.22.1.tar.xz" to ("b7e4cfb1414a281f90bfd39a503f72f38e03c31b356927972f797988fb48b5b1" to 149_200_240L),
        "archlinux-arm-pd-v4.22.1.tar.xz" to ("25ccafc3234bc9e0cd37ea240a4b6ec349464e88cf22e9db0d11a9f1a927d336" to 140_201_652L),
        "fedora-aarch64-pd-v4.24.0.tar.xz" to ("48abf1d8b9cc7625d4212cc604ce3c113ea6d6d806de60b2c3f74c5b5452cd72" to 33_614_832L),
        "manjaro-aarch64-pd-v4.18.0.tar.xz" to ("f0aa5f70a4ccfe00f658bf9adc4a18d8332e894591839adb990d913c9aa604b9" to 140_306_612L),
    )

    /** The SHA-256 for this distro's tarball on [cpu] ("" = not known: then it isn't checked). */
    fun sha256(d: Distro, cpu: String): String = d.urls[cpu]?.substringAfterLast('/')?.let { CHECKSUMS[it]?.first }.orEmpty()
    /** Exact download size in bytes (0 = unknown). */
    fun bytes(d: Distro, cpu: String): Long = d.urls[cpu]?.substringAfterLast('/')?.let { CHECKSUMS[it]?.second } ?: 0L
    /** "64.1 MB download, SHA-256 checked" / Alpine's own line. */
    fun sizeLine(d: Distro, cpu: String?): String {
        if (cpu == null) return ""
        val b = bytes(d, cpu)
        return if (b > 0) "%.1f MB download \u00b7 SHA-256 checked".format(b / 1e6) else d.sizeMb[cpu]?.let { "~$it MB" }.orEmpty()
    }

    fun distro(id: String): Distro? = DISTROS.firstOrNull { it.id == id }

    /** "aarch64" / "arm" for the phone's first ABI, null when no rootfs is built for it. */
    fun cpu(abi: String?): String? = when (abi) { "arm64-v8a" -> "aarch64"; "armeabi-v7a", "armeabi" -> "arm"; else -> null }

    /** The distros this phone can install (Alpine has its own installer and works everywhere Linux does). */
    fun installable(abi: String?): List<Distro> = DISTROS.filter { it.id == "alpine" || cpu(abi)?.let { c -> it.urls[c] } != null }

    /** v0.29.8: the "phone" tab, renamed so it says what it is: this phone's own command line, nothing to install. */
    val PHONE = Env("phone", "Phone shell · built-in tools, no install", "phone sh")
    val ALPINE = DISTROS.first().env()

    /** What a new tab can run: the installed distros (in list order), then the phone shell. */
    fun envs(installedIds: Set<String>): List<Env> = DISTROS.filter { it.id in installedIds }.map { it.env() } + PHONE

    /** The default for a new tab: the last one used if it's still there, else the first available. */
    fun pick(last: String?, installedIds: Set<String>): Env = envs(installedIds).let { l -> l.firstOrNull { it.id == last } ?: l.first() }

    /** v0.33.5: a new tab opens in the Linux chosen on the Desktop page when it's one besides Alpine and installed; otherwise as [pick]. */
    fun pick(last: String?, installedIds: Set<String>, chosen: String?): Env =
        if (chosen != null && chosen != "alpine" && chosen in installedIds) envs(installedIds).first { it.id == chosen } else pick(last, installedIds)

    /** pax extended header records: "<len> key=value\n" … */
    fun pax(body: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var i = 0
        val bytes = body.toByteArray(Charsets.UTF_8)
        while (i < bytes.size) {
            var sp = i; while (sp < bytes.size && bytes[sp] != ' '.code.toByte()) sp++
            val len = String(bytes, i, sp - i).trim().toIntOrNull() ?: break
            if (len <= 0 || i + len > bytes.size) break
            val rec = String(bytes, sp + 1, i + len - sp - 1, Charsets.UTF_8).trimEnd('\n')
            val eq = rec.indexOf('=')
            if (eq > 0) out += rec.substring(0, eq) to rec.substring(eq + 1)
            i += len
        }
        return out
    }

    /** argv for the phone shell: BusyBox ash (line editing, history) when BusyBox ships, else Android's sh. */
    fun phoneArgs(busybox: String?): Pair<String, List<String>> =
        if (busybox != null) busybox to listOf("ash", "-l") else "/system/bin/sh" to listOf("sh", "-l")

    /**
     * v0.29.8: how the phone tab starts. BusyBox ash only when BusyBox is there AND passed the start-up probe
     * ([probeOk]); otherwise Android's own /system/bin/sh (mksh + toybox: ls, top, ping, ps… still work), so the tab
     * never opens as "[Process completed (signal 31)]". [busybox] is the path of libbusybox.so (argv[0] "ash" picks the applet).
     */
    data class Launch(val path: String, val argv: List<String>, val busybox: Boolean)

    fun phoneLaunch(busybox: String?, busyboxWorks: Boolean): Launch =
        if (busybox != null && busyboxWorks) Launch(busybox, listOf("ash", "-l"), true)
        else Launch("/system/bin/sh", listOf("sh", "-l"), false)

    /** The BusyBox start-up probe (`busybox sh -c 'echo ok'`) passed: exit 0 and "ok". A seccomp kill exits 128+31 = 159. */
    fun probeOk(exitCode: Int, output: String): Boolean = exitCode == 0 && output.trim().lines().lastOrNull()?.trim() == "ok"

    const val SIGSYS = 31

    /** Termux's TerminalSession exit status: >= 0 an exit code, < 0 minus the signal that killed it. */
    fun signalOf(exitStatus: Int): Int? = if (exitStatus < 0) -exitStatus else null

    /**
     * v0.29.8: the extra line under "[Process completed …]" when a phone tab dies at once. Signal 31 (SIGSYS) means
     * Android's app sandbox (seccomp) killed it for a blocked system call; the next start uses Android's own shell.
     */
    fun finishNote(envId: String, usedBusybox: Boolean, exitStatus: Int): String? {
        if (envId != "phone") return null
        return when (signalOf(exitStatus)) {
            SIGSYS -> if (usedBusybox) "\r\n Android's app sandbox stopped BusyBox (signal 31 = a blocked system call).\r\n Press Enter: this tab reopens with Android's own shell instead.\r\n"
                      else "\r\n Android's app sandbox stopped the shell (signal 31). Press Enter to try again.\r\n"
            null -> null
            else -> "\r\n The shell stopped. Press Enter to open a fresh one.\r\n"
        }
    }

    // ---- v0.30.2: the phone tab never opens dead ----

    /**
     * v0.30.2: the per-build key of the BusyBox verdict in usr/.bb. The probe runs again whenever the APK changes
     * (versionCode, or a reinstall of the same version: lastUpdateTime), never on every start.
     */
    fun bbKey(versionCode: Long, lastUpdate: Long): String = "v$versionCode:$lastUpdate"

    /** What the start-up probe decided: [ok] = ash runs at all; [legacyOk] = the file tools (chmod, ln, rmdir…) do too. */
    data class BbVerdict(val ok: Boolean, val legacyOk: Boolean = true)

    fun bbStamp(key: String, v: BbVerdict): String =
        "$key " + (if (!v.ok) "broken" else if (v.legacyOk) "ok" else "ok nolegacy")

    /** The verdict saved for this build, or null when there is none (first start, or the app was updated). */
    fun bbParse(key: String, stamp: String?): BbVerdict? {
        val parts = stamp?.trim()?.split(' ') ?: return null
        if (parts.size < 2 || parts[0] != key) return null
        return when (parts[1]) {
            "ok" -> BbVerdict(true, parts.getOrNull(2) != "nolegacy")
            "broken" -> BbVerdict(false, false)
            else -> null
        }
    }

    /** The quick `busybox true` probe: any exit but 0 (127 applet not found, 159 = killed by SIGSYS, 139 SIGSEGV…) fails it. */
    fun trueProbeOk(exitCode: Int?): Boolean = exitCode == 0

    /**
     * Applets that call the old path syscalls (chmod, chown32, link, symlink, rmdir, mknod). The 32-bit BusyBox
     * (1.31.0, musl) uses them, and Android's 32-bit app seccomp filter only allows the *at() forms, so on a phone that
     * runs Blaze as armeabi-v7a they die with SIGSYS. When the start-up file-tools probe fails, these stay Android's
     * (toybox in /system/bin) instead. Found with qemu-arm -strace against bionic's SECCOMP lists (tools/busybox/README.md).
     */
    val LEGACY_SYSCALL_APPLETS = setOf("chmod", "chown", "chgrp", "ln", "rmdir", "rm", "cp", "mv", "mkfifo", "mknod",
        "tar", "cpio", "unzip", "install", "patch", "rpm", "rpm2cpio", "dpkg", "dpkg-deb", "ar", "lzop", "unlzop")

    /** sh -c script, $0 = busybox, $1 = an empty scratch dir: prints "sigsys" for each tool the sandbox kills, then "done". */
    const val FILE_TOOLS_PROBE = "cd \"\$1\" || exit 1; : > f; " +
        "for c in 'chmod 600 f' 'ln -s f s' 'mkdir d' 'rmdir d' 'rm -f f s'; do \"\$0\" \$c; [ \$? -eq 159 ] && echo sigsys; done; echo done"

    /** The file-tools probe ran to the end and no tool was killed by the sandbox. */
    fun fileToolsOk(output: String?): Boolean = output != null && output.lines().any { it.trim() == "done" } && output.lines().none { it.trim() == "sigsys" }

    /** Which BusyBox applets get a usr/bin link: never Android's must-keeps, and the file tools only when they work. */
    fun appletsToLink(applets: List<String>, legacyOk: Boolean): List<String> = applets.map { it.trim() }
        .filter { it.isNotEmpty() && !it.contains('/') && it != "busybox" && it !in KEEP_ANDROIDS }
        .filter { legacyOk || it !in LEGACY_SYSCALL_APPLETS }
        .distinct()

    /** A BusyBox phone tab that ends this soon after it started, with an error, is BusyBox failing to start. */
    const val QUICK_DEATH_MS = 5_000L

    /**
     * v0.30.2: should this finished tab be replaced, at once and with nothing for the user to do, by a tab running
     * Android's own /system/bin/sh? Yes for a BusyBox phone tab killed by the sandbox (signal 31, whenever it happens),
     * and for one that died with any error or signal within [QUICK_DEATH_MS]. A normal `exit` (status 0) never is.
     */
    fun autoFallback(envId: String, usedBusybox: Boolean, exitStatus: Int, aliveMs: Long): Boolean {
        if (envId != "phone" || !usedBusybox || exitStatus == 0) return false
        return signalOf(exitStatus) == SIGSYS || aliveMs in 0 until QUICK_DEATH_MS
    }

    /** The line printed (under the welcome) in the tab that replaced a BusyBox tab that couldn't start. */
    fun fallbackNote(exitStatus: Int): String {
        val why = when (val sig = signalOf(exitStatus)) {
            SIGSYS -> listOf(" (signal 31: Android's app sandbox", " blocked a system call).")
            null -> listOf(" (exit code $exitStatus).")
            else -> listOf(" (signal $sig).")
        }
        return (listOf(" BusyBox couldn't start on this phone") + why +
            listOf(" This tab runs Android's own shell", " instead. Nothing to do: type away.", "", ""))
            .joinToString("\r\n")
    }

    /** BusyBox applets not linked onto PATH: Android's own versions work for apps (ping uses Android's ICMP sockets) or need root anyway. */
    val KEEP_ANDROIDS = setOf("sh", "su", "reboot", "init", "mount", "umount", "ping", "ping6", "login", "passwd")

    /** Tappable example commands above the extra keys in a phone tab: (label, what it types). Each runs on tap. */
    val PHONE_EXAMPLES: List<Pair<String, String>> = listOf(
        "help" to "help", "ls" to "ls -l", "cd storage" to "cd /sdcard && ls", "cd home" to "cd ~ && pwd",
        "top" to "top", "ping" to "ping -c 3 google.com", "space" to "df -h", "phone info" to "uname -a; getprop ro.product.model",
        "clear" to "clear",
    )

    /** "KEY=value" pairs for the PTY (the native side clears the environment first). */
    fun envArray(vararg maps: Map<String, String>): Array<String> {
        val m = linkedMapOf<String, String>()
        for (x in maps) m.putAll(x)
        m["TERM"] = "xterm-256color"; m["COLORTERM"] = "truecolor"
        return m.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    /** Tab chip text: "1 alpine", or the program's title when it set one ("1 vim"). */
    fun tabLabel(n: Int, env: Env, title: String?, finished: Boolean): String {
        val t = title?.trim()?.takeIf { it.isNotEmpty() && it.length <= 24 } ?: env.short
        return "$n $t" + if (finished) " ✕" else ""
    }

    const val MIN_FONT = 14
    const val MAX_FONT = 64

    /** Pinch zoom: the new text size in px, kept in range. */
    fun zoom(current: Int, scale: Float): Int = (current * scale).toInt().coerceIn(MIN_FONT, MAX_FONT)

    /** The first lines of a new tab, Termux-style. Lines stay under ~44 columns so a small phone doesn't wrap them. */
    fun welcome(env: Env, busybox: Boolean = true): String = buildString {
        append("\r\n Welcome to Blaze Terminal!\r\n\r\n")
        if (env.id != "phone") {
            append(" ${env.label}, running on this phone.\r\n\r\n Working with packages:\r\n\r\n")
            for (h in env.hint) append("  - $h\r\n")
            append("\r\n Your Blaze files are in ~/phone, storage in /sdcard.\r\n")
            append(" Something odd? Run blaze-doctor.\r\n")
            append(" Pinch to zoom · long-press to copy · ⋮ to paste.\r\n\r\n")
        } else {
            append(" This is the PHONE SHELL: a command line\r\n")
            append(" for this phone, using the tools built\r\n")
            append(if (busybox) " into Android and Blaze (BusyBox).\r\n" else " into Android (toybox).\r\n")
            append(" Nothing to install.\r\n\r\n")
            append(" Not a full Linux: no apt, python or git.\r\n")
            append(" For those: + → Install a Linux…\r\n\r\n")
            append(" Try these, or tap the buttons below:\r\n")
            for ((cmd, what) in PHONE_TOUR) append("   ").append(cmd.padEnd(11)).append(what).append("\r\n")
            append("\r\n Type a command and press Enter.\r\n Ctrl-C stops a running command.\r\n\r\n")
        }
    }

    /** The welcome text's short tour of the phone shell: command → what it does. */
    val PHONE_TOUR: List<Pair<String, String>> = listOf(
        "ls" to "list the files here",
        "cd /sdcard" to "your phone's storage",
        "cd ~" to "back to the home folder",
        "top" to "what's running (q quits)",
        "df -h" to "free storage space",
        "help" to "every tool you can use",
    )

    /**
     * v0.29.8: the phone shell's start-up file (sourced through \$ENV by BusyBox ash and Android's mksh): a `help`
     * command that explains the shell and lists its tools, plus a short prompt. [busybox] = BusyBox ash is the shell.
     */
    fun phoneRc(busybox: Boolean): String = buildString {
        append("# Blaze Terminal phone shell (written by Blaze on each start; put your own settings in ~/.profile)\n")
        append(if (busybox) "PS1='phone:\\w \\$ '\n" else "PS1='phone:\${PWD##*/} \$ '\n")
        append("help() {\n  cat <<'BLAZE_HELP'\n")
        append(HELP_TEXT)
        append(if (busybox) "\nEvery tool here:   busybox --list   (or: ls /system/bin)\n" else "\nEvery tool here:   ls /system/bin\n")
        append("A tool's options:  <tool> --help    e.g.  ls --help\nBLAZE_HELP\n}\n")
    }

    val HELP_TEXT = """
Blaze phone shell: commands for this phone itself.
Type one and press Enter. Ctrl-C stops a command.

Files and folders
  ls                list files (ls -l for details)
  cd <folder>       go into a folder (cd .. = up)
  pwd               where am I?
  cd /sdcard        your photos, downloads, documents
  cat <file>        show a file
  cp / mv / rm      copy, move, delete
  mkdir <name>      make a folder
  vi <file>         edit a text file (Esc :wq saves)
  find . -name '*.jpg'   search for files

This phone
  top               running programs (q quits)
  df -h             free storage
  free -m           memory
  uname -a          Android's kernel
  getprop ro.product.model   phone model

Network
  ping -c 3 google.com   is the internet working?
  wget <url>        download a file
  ip addr           this phone's addresses

Want apt, python, node or git?
  Tap + at the top, then "Install a Linux…".
""".trimStart('\n')
}
