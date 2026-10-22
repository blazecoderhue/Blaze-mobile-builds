package com.blaze.assistant.desktop

import java.security.SecureRandom

/**
 * v0.25.1 Linux desktop, pure part (DesktopPlanTest): what gets installed inside Blaze's Alpine Linux, the exact
 * shell lines that install / start / stop it, the VNC password, the defaults for a 3–4 GB phone, and the plain
 * progress words. A real XFCE desktop runs in proot under a TigerVNC X server (Xvnc) bound to 127.0.0.1 only, with
 * a random password; Blaze's own RFB viewer ([Rfb]) shows it full screen. All packages are free and open source
 * from Alpine's repositories, downloaded on first Start (none are bundled in the APK).
 */
object DesktopPlan {
    const val DISPLAY = ":1"
    const val PORT = 5901
    /** Light defaults for a low-memory phone: small screen, 16-bit colour. */
    const val WIDTH = 1024
    const val HEIGHT = 576
    const val DEPTH = 16

    /** XFCE (desktop, terminal, file manager), a small browser, fonts, the VNC server and the agent's tools. */
    val PACKAGES = listOf(
        "xfce4", "xfce4-terminal", "thunar", "mousepad", "xfce4-session", "xfwm4", "xfdesktop", "xfce4-panel",
        "adwaita-icon-theme", "font-dejavu", "dbus", "dbus-x11", "tigervnc", "xdotool", "imagemagick", "dillo",
    )
    /** Download / on-disk estimates for the plain-words line before installing (measured on Alpine 3.20 aarch64, rounded up). */
    const val DOWNLOAD_MB = 260
    const val DISK_MB = 750
    /** Below this much free RAM the desktop would get killed straight away. */
    const val MIN_FREE_MB = 350

    fun supported(abi: String?): Boolean = abi != null && (abi.startsWith("arm64") || abi.startsWith("armeabi-v7a") || abi == "x86_64")

    const val UNSUPPORTED = "This phone's processor can't run Blaze's Linux, so the desktop isn't available here. Everything else in Blaze still works."

    fun sizeLine(): String = "About $DOWNLOAD_MB MB to download and $DISK_MB MB on the phone. Wi-Fi is best; it takes 5\u201315 minutes the first time."

    /** 8 letters and digits (VNC passwords use the first 8 characters). */
    fun password(rnd: SecureRandom = SecureRandom()): String {
        val abc = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..8).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
    }

    /**
     * v0.25.5: the packages in small batches, each its own `apk add`, so a dropped connection only costs the batch it
     * hit (what's installed stays installed; `apk add` skips it next time). Same set as [PACKAGES].
     */
    val BATCHES: List<List<String>> = listOf(
        listOf("dbus", "dbus-x11", "font-dejavu"),
        listOf("tigervnc", "xdotool"),
        listOf("xfce4-session", "xfwm4", "xfce4-panel", "xfdesktop"),
        listOf("xfce4", "adwaita-icon-theme"),
        listOf("xfce4-terminal", "thunar", "mousepad"),
        listOf("imagemagick"),
        listOf("dillo"),
    )

    /** v0.33.4: nice-to-have batches; a package missing from the mirror skips these instead of failing the whole install. */
    val OPTIONAL: Set<List<String>> = setOf(listOf("imagemagick"), listOf("dillo"))

    /** apk waits this long with no progress before giving up on a connection (its default is 60 s). */
    const val APK_TIMEOUT = 120

    /** Sets `$T` to `--timeout 120` when this apk understands it (apk-tools 2.14+, Alpine 3.20 has 2.14.4), else nothing. */
    const val APK_TIMEOUT_PROBE = "T=''; if apk --timeout 1 --version >/dev/null 2>&1; then T='--timeout $APK_TIMEOUT'; fi"

    /** Points apk at [mirror] (`/etc/apk/repositories`). */
    fun reposLine(mirror: String): String = "mkdir -p /etc/apk && printf '%s' '" + com.blaze.assistant.terminal.AlpineNet.repositories(mirror).replace("'", "") + "' > /etc/apk/repositories"

    /** One install step: refresh the package list from [mirror] when [update], then add [pkgs] (none for the update-only step). */
    fun stepScript(mirror: String, pkgs: List<String>, update: Boolean, staged: List<DesktopPrefetch.Pkg> = emptyList()): String = buildString {
        append("set -e\n").append(reposLine(mirror)).append('\n').append(APK_TIMEOUT_PROBE).append('\n')
        // v0.33.3: apk keeps what it downloaded (a failed batch no longer throws away the packages it already fetched).
        append(APK_CACHE).append('\n')
        if (update) append("apk \$T update\n")
        if (pkgs.isNotEmpty()) append(DesktopPrefetch.installLines(pkgs, staged))
        append("echo BLAZE_STEP_OK")
    }

    /** v0.33.3: turns on apk's package cache (/etc/apk/cache → /var/cache/apk). */
    const val APK_CACHE = "mkdir -p /var/cache/apk && { [ -e /etc/apk/cache ] || ln -s /var/cache/apk /etc/apk/cache; }"

    /**
     * v0.33.5: the same command names in every Linux. Debian / Ubuntu ship Xtigervnc + tigervncpasswd (Xvnc / vncpasswd
     * come from update-alternatives, which a half-finished install may lack); ImageMagick 7 (Arch) may only have `magick`.
     * Links go in /usr/local/bin and are only made when the plain name is missing, so Alpine is untouched.
     */
    const val SHIMS = "(set +e; mkdir -p /usr/local/bin; " +
        "command -v Xvnc >/dev/null 2>&1 || { X=\$(command -v Xtigervnc) && ln -sf \"\$X\" /usr/local/bin/Xvnc; }; " +
        "command -v vncpasswd >/dev/null 2>&1 || { X=\$(command -v tigervncpasswd) && ln -sf \"\$X\" /usr/local/bin/vncpasswd; }; " +
        "command -v import >/dev/null 2>&1 || { command -v magick >/dev/null 2>&1 && printf '#!/bin/sh\\nexec magick import \"\$@\"\\n' > /usr/local/bin/import && chmod 755 /usr/local/bin/import; }; true) >/dev/null 2>&1 || true"

    /** The last step: folders the session needs, and the "installed" mark. */
    const val FINISH_SCRIPT = "set -e\nmkdir -p /root/.vnc /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix\n" + SHIMS + "\n" +
        "touch /root/.blaze_desktop_ok\necho BLAZE_DESKTOP_INSTALLED"

    /** The whole install as one script (first mirror, no retries), for reference and the agent; the app runs it step by step ([DesktopInstall]). */
    fun installScript(): String =
        stepScript(com.blaze.assistant.terminal.AlpineNet.MIRRORS[0], emptyList(), update = true) + "\n" +
            BATCHES.joinToString("\n") { "apk \$T add " + it.joinToString(" ") } + "\n" + FINISH_SCRIPT

    const val INSTALLED_MARK = "/root/.blaze_desktop_ok"


    /** The password file Xvnc reads, written with TigerVNC's own `vncpasswd -f` (the password arrives on stdin, never in argv). */
    fun passwdScript(pw: String): String = SHIMS + "\nmkdir -p /root/.vnc && printf '%s\\n' '${pw.filter { it.isLetterOrDigit() }}' | vncpasswd -f > /root/.vnc/passwd && chmod 600 /root/.vnc/passwd"

    /** Xvnc's arguments: localhost only, password auth, no TCP X11, light resolution and depth. */
    fun xvncArgs(w: Int = WIDTH, h: Int = HEIGHT, depth: Int = DEPTH): List<String> = listOf(
        "Xvnc", DISPLAY, "-geometry", "${w}x$h", "-depth", depth.toString(), "-localhost", "-rfbport", PORT.toString(),
        "-SecurityTypes", "VncAuth", "-PasswordFile", "/root/.vnc/passwd", "-nolisten", "tcp", "-AlwaysShared", "-dpi", "96",
    )

    /** v0.43.0: where the start script keeps Xvnc's process id (the alive check reads it instead of pgrep). */
    const val PID_FILE = "/tmp/.blaze-xvnc.pid"
    /** v0.43.0: apps for the desktop are dropped here as files and started by the desktop's own session (see [startScript]). */
    const val SPOOL = "/tmp/blaze-run"
    const val UP_MARK = "BLAZE_DESKTOP_UP"
    const val FAIL_MARK = "BLAZE_DESKTOP_FAILED"
    const val EXIT_MARK = "BLAZE_XVNC_EXITED"

    /** Stale X server locks and sockets left behind when Xvnc was killed. */
    const val CLEAR_LOCKS = "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 $PID_FILE; mkdir -p /tmp/.X11-unix; chmod 1777 /tmp/.X11-unix"

    /** Background helpers that do nothing in proot and only cost memory / processes (Android 12+ limits child processes). */
    val HIDDEN_AUTOSTART = listOf("xfce4-power-manager", "light-locker", "xscreensaver", "blueman", "nm-applet")

    /**
     * v0.43.0: starts the X server and keeps it up on its own. Before, Xvnc ran in the background of a shell that then
     * became the XFCE session, and proot's --kill-on-exit killed Xvnc the moment the session ended, leaving a stale
     * /tmp/.X11-unix/X1 behind. Now the job's main shell waits on Xvnc itself; XFCE runs beside it (restarted up to 3
     * times if it ends), and a small launcher inside the same proot starts apps dropped into [SPOOL], so they live as
     * long as the desktop instead of dying with the short command that asked for them. A missing password file or an
     * Xvnc that exits prints [FAIL_MARK] and the last lines of /tmp/xvnc.log. "Up" is only ever decided by the app
     * connecting to port 5901 ([DesktopHealth]); [UP_MARK] just means Xvnc made its socket.
     */
    fun startScript(w: Int = WIDTH, h: Int = HEIGHT, depth: Int = DEPTH): String = SHIMS + "\n" + CLEAR_LOCKS + "\n" +
        "[ -s /root/.vnc/passwd ] || { echo '$FAIL_MARK: the desktop password file is missing'; exit 3; }\n" +
        "command -v Xvnc >/dev/null 2>&1 || { echo '$FAIL_MARK: Xvnc is not installed'; exit 4; }\n" +
        "mkdir -p /root/.config/autostart; for a in ${HIDDEN_AUTOSTART.joinToString(" ")}; do printf '[Desktop Entry]\\nHidden=true\\n' > /root/.config/autostart/\$a.desktop; done\n" +
        ": > /tmp/xvnc.log\n" +
        xvncArgs(w, h, depth).joinToString(" ") + " >>/tmp/xvnc.log 2>&1 </dev/null &\n" +
        "XPID=\$!\necho \$XPID > $PID_FILE\n" +
        "i=0; while [ \$i -lt 120 ]; do kill -0 \$XPID 2>/dev/null || break; [ -e /tmp/.X11-unix/X1 ] && break; sleep 0.25; i=\$((i+1)); done\n" +
        "if ! kill -0 \$XPID 2>/dev/null; then echo '$FAIL_MARK: Xvnc exited'; tail -n 15 /tmp/xvnc.log; rm -f $PID_FILE; exit 5; fi\n" +
        "export DISPLAY=$DISPLAY XDG_RUNTIME_DIR=/tmp/xdg NO_AT_BRIDGE=1; mkdir -p /tmp/xdg; chmod 700 /tmp/xdg\n" +
        "echo $UP_MARK\n" +
        "S=''; command -v setsid >/dev/null 2>&1 && S=setsid\n" +
        "(mkdir -p $SPOOL; rm -f $SPOOL/*; while kill -0 \$XPID 2>/dev/null; do for f in $SPOOL/*.cmd; do [ -e \"\$f\" ] || continue; " +
        "c=\$(cat \"\$f\"); rm -f \"\$f\"; (\$S sh -c \"\$c\" >/dev/null 2>&1 </dev/null &); done; sleep 0.4; done) &\n" +
        "(n=0; while kill -0 \$XPID 2>/dev/null && [ \$n -lt 3 ]; do dbus-launch --exit-with-session startxfce4 >>/tmp/xfce.log 2>&1 </dev/null; " +
        "echo \"BLAZE_SESSION_ENDED \$?\" >>/tmp/xfce.log; n=\$((n+1)); sleep 2; done) &\n" +
        "wait \$XPID\n" +
        "echo \"$EXIT_MARK \$?\"; tail -n 15 /tmp/xvnc.log; rm -f $PID_FILE"

    /**
     * Stops everything the desktop started and frees its memory. v0.43.0: every pattern is written as `[X]vnc`, which
     * matches "Xvnc" but not the pattern's own text, so pkill no longer kills the shell running this script (and with
     * it, through --kill-on-exit, the rest of the script) before it reached Xvnc.
     */
    val STOP_SCRIPT = "p=\$(cat $PID_FILE 2>/dev/null); [ -n \"\$p\" ] && kill \"\$p\" 2>/dev/null; for n in " +
        listOf("[x]fce4-session", "[x]fwm4", "[x]fdesktop", "[x]fce4-panel", "[x]fce4-power", "[Tt]hunar", "[d]illo", "[m]ousepad",
            "[x]fconfd", "[d]bus-daemon", "[d]bus-launch", "[X]vnc", "[X]tigervnc").joinToString(" ") { "'$it'" } +
        "; do pkill -f \"\$n\" 2>/dev/null; done; sleep 1; pkill -9 -f '[X]vnc' 2>/dev/null; pkill -9 -f '[X]tigervnc' 2>/dev/null; " +
        "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 $PID_FILE; rm -rf $SPOOL; echo stopped"

    /**
     * Inside-Linux check, for diagnosis only: is the Xvnc the start script launched still a live process? (v0.43.0: the
     * old check, `[ -e X1 ] && pgrep -f 'Xvnc|Xtigervnc'`, always said "up": pgrep found the shell running the check,
     * whose own command line contains "Xvnc", and a stale socket file was enough for the rest.) Whether the desktop is
     * really up is decided by connecting to the port ([DesktopHealth.probe]).
     */
    const val ALIVE_SCRIPT = "p=\$(cat $PID_FILE 2>/dev/null); if [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null; then echo up; else echo down; fi"

    /** The last lines of the X server's and the session's logs (for a failed start and for Repair). */
    const val LOG_TAIL_SCRIPT = "echo '--- xvnc'; tail -n 15 /tmp/xvnc.log 2>/dev/null; echo '--- xfce'; tail -n 5 /tmp/xfce.log 2>/dev/null; true"

    /**
     * v0.43.0 Repair (after [STOP_SCRIPT], which must run on its own): clears stale locks, the launcher folder and the
     * old logs, re-makes the folders and command links ([FINISH_SCRIPT]'s work), and checks Xvnc and vncpasswd are there.
     */
    const val REPAIR_SCRIPT = "mkdir -p /root/.vnc /tmp/.X11-unix /tmp/xdg; chmod 1777 /tmp/.X11-unix; chmod 700 /tmp/xdg\n" +
        "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 $PID_FILE /tmp/xvnc.log /tmp/xfce.log /root/.vnc/*.pid /root/.vnc/*.log; rm -rf $SPOOL\n" + SHIMS + "\n" +
        "command -v Xvnc >/dev/null 2>&1 && echo HAS_XVNC || echo NO_XVNC; command -v vncpasswd >/dev/null 2>&1 && echo HAS_VNCPASSWD || echo NO_VNCPASSWD; echo BLAZE_REPAIRED"

    // ---------- agent tool lines (xdotool + ImageMagick) ----------
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    const val SHOT_PATH = "/root/phone/.desktop_shot.png"
    fun screenshotCmd(): String = "DISPLAY=$DISPLAY import -window root -resize 1024x1024\\> $SHOT_PATH && echo ok"
    fun clickCmd(x: Int, y: Int, button: Int = 1, double: Boolean = false): String =
        "DISPLAY=$DISPLAY xdotool mousemove ${x.coerceAtLeast(0)} ${y.coerceAtLeast(0)} click ${if (double) "--repeat 2 " else ""}${button.coerceIn(1, 5)}"
    fun typeCmd(text: String): String = "DISPLAY=$DISPLAY xdotool type --delay 30 -- ${q(text.take(2000))}"
    /** Only the key names before any shell separator; anything after `;`, `&`, `|`, backtick or `$` is dropped. */
    fun keyCmd(keys: String): String = "DISPLAY=$DISPLAY xdotool key -- ${keys.substringBefore(';').substringBefore('&').substringBefore('|').substringBefore('`').substringBefore('$')
        .split(' ', ',').filter { it.matches(Regex("[A-Za-z0-9_+]{1,40}")) }.joinToString(" ")}"
    /**
     * v0.43.0: opens [app] through the desktop's own launcher ([spoolCmd]). A plain `setsid app &` from a short command
     * died as soon as that command's shell exited (proot --kill-on-exit), so "started Dillo" never showed a window.
     */
    fun runCmd(app: String): String = spoolCmd(appCommand(app))

    /** Hands [cmd] to the running desktop session, which starts it with DISPLAY=:1 and keeps it running. */
    fun spoolCmd(cmd: String): String = "mkdir -p $SPOOL && f=$SPOOL/b\$\$\$(date +%s) && printf '%s\\n' ${q("DISPLAY=$DISPLAY " + cmd)} > \"\$f.tmp\" && mv \"\$f.tmp\" \"\$f.cmd\" && echo started"

    /** Friendly names to commands (anything else is run as typed, still behind the user's yes). */
    fun appCommand(app: String): String = when (app.trim().lowercase()) {
        "terminal", "xfce4-terminal" -> "xfce4-terminal"
        "files", "file manager", "thunar" -> "thunar"
        "browser", "web", "netsurf", "dillo" -> "dillo"
        "editor", "text editor", "mousepad" -> "mousepad"
        else -> app.trim()
    }

    // ---------- plain words ----------
    enum class Phase { IDLE, CHECKING, INSTALLING, STARTING, RUNNING, STOPPING, STOPPED, KILLED, ERROR, UNSUPPORTED, PAUSED }

    /** One plain line for an apk / start log line ("(12/96) Installing gtk+3.0" → "Installing gtk+3.0 (12 of 96)"). */
    fun progressLine(log: String): Pair<Int?, String>? {
        val m = Regex("""\((\d+)/(\d+)\)\s+(Installing|Upgrading)\s+(\S+)""").find(log) ?: return when {
            "fetch" in log && "APKINDEX" in log -> null to "Checking what to download\u2026"
            "BLAZE_DESKTOP_INSTALLED" in log -> 100 to "Installed."
            else -> null
        }
        val (i, n, _, pkg) = m.destructured
        val pct = (i.toInt() * 100 / n.toInt().coerceAtLeast(1)).coerceIn(0, 99)
        return pct to "Installing $pkg ($i of $n)"
    }

    const val KILLED_NOTE = "Android closed the desktop to save memory. Your files are still there. Tap Resume to start it again."
}
