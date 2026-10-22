package com.blaze.assistant.desktop

import com.blaze.assistant.desktop.DesktopDistro.Pm
import com.blaze.assistant.desktop.DesktopInstall.Result
import com.blaze.assistant.desktop.DesktopPlan.Phase
import com.blaze.assistant.terminal.TermLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.5: the desktop runs in the user's choice of Linux, and Stop pauses the download without losing it. */
class DesktopDistroTest {
    private val ok = Result(0, "BLAZE_STEP_OK")
    private val done = Result(0, "BLAZE_DESKTOP_INSTALLED")

    @Test fun alpineIsTheDefaultAndUnchanged() {
        assertEquals("alpine", DesktopDistro.of(null).id)
        assertEquals("alpine", DesktopDistro.of("").id)
        assertEquals("alpine", DesktopDistro.of("plan9").id)
        assertEquals("ubuntu", DesktopDistro.of(" Ubuntu ").id)
        // An existing Alpine desktop keeps the very same packages, batches and size, so nothing is reinstalled.
        assertEquals(DesktopPlan.BATCHES, DesktopDistro.ALPINE.batches)
        assertEquals(DesktopPlan.OPTIONAL, DesktopDistro.ALPINE.optional)
        assertEquals(DesktopPlan.sizeLine(), DesktopDistro.ALPINE.sizeLine())
        assertTrue(DesktopDistro.ALPINE.recommended)
        assertEquals(listOf("alpine"), DesktopDistro.ALL.filter { it.recommended }.map { it.id })
    }

    @Test fun fiveChoicesWithTheTerminalsIds() {
        assertEquals(listOf("alpine", "ubuntu", "debian", "archlinux", "fedora"), DesktopDistro.ALL.map { it.id })
        // Same ids as the Terminal's Linux list, so the base install (Distros.install) and rootfs folders are shared.
        DesktopDistro.ALL.forEach { assertTrue(it.id, TermLogic.distro(it.id) != null) }
        assertEquals(Pm.APK, DesktopDistro.ALPINE.pm); assertEquals(Pm.APT, DesktopDistro.UBUNTU.pm); assertEquals(Pm.APT, DesktopDistro.DEBIAN.pm)
        assertEquals(Pm.PACMAN, DesktopDistro.ARCH.pm); assertEquals(Pm.DNF, DesktopDistro.FEDORA.pm)
    }

    @Test fun availableFollowsTheCpu() {
        assertEquals(DesktopDistro.ALL, DesktopDistro.available("aarch64"))
        assertEquals(listOf("alpine", "ubuntu", "debian", "archlinux"), DesktopDistro.available("arm").map { it.id })
        assertEquals(listOf("alpine"), DesktopDistro.available(null).map { it.id })
        // Matches the rootfs tarballs the Terminal knows about.
        DesktopDistro.available("arm").filter { it.id != "alpine" }.forEach { assertTrue(TermLogic.distro(it.id)!!.urls["arm"] != null) }
    }

    @Test fun realArm64PackageNames() {
        // Checked 2026-10-18 against bookworm / noble arm64, Arch Linux ARM aarch64 and Fedora 41 aarch64.
        val deb = DesktopDistro.DEBIAN.packages
        listOf("tigervnc-standalone-server", "tigervnc-tools", "xfdesktop4", "fonts-dejavu-core", "dbus-x11", "procps").forEach { assertTrue(it, it in deb) }
        assertEquals(deb, DesktopDistro.UBUNTU.packages)
        val arch = DesktopDistro.ARCH.packages
        listOf("tigervnc", "xfdesktop", "ttf-dejavu", "procps-ng", "dbus").forEach { assertTrue(it, it in arch) }
        val fed = DesktopDistro.FEDORA.packages
        listOf("tigervnc-server-minimal", "dbus-x11", "Thunar", "ImageMagick", "dejavu-sans-fonts").forEach { assertTrue(it, it in fed) }
        assertFalse("tigervnc-server" in fed)
        DesktopDistro.ALL.forEach { d ->
            assertTrue(d.id, d.packages.any { it.startsWith("xfce4-session") }); assertTrue(d.id, d.packages.contains("xdotool"))
            assertEquals(d.id, d.packages.size, d.packages.toSet().size)
            d.packages.forEach { assertTrue("$it", DesktopDistro.safe(it)) }
            d.optional.forEach { assertTrue(d.id, it in d.batches) }
            assertFalse(d.id, d.packages.contains("netsurf"))
        }
    }

    @Test fun installCommandsUseTheRightPackageManager() {
        assertEquals("apk add a b", DesktopDistro.installCmd(Pm.APK, listOf("a", "b")))
        assertTrue(DesktopDistro.installCmd(Pm.APT, listOf("dillo")).let { it.startsWith("apt-get") && "install -y --no-install-recommends dillo" in it })
        assertEquals("pacman -S --noconfirm --needed --disable-download-timeout dillo", DesktopDistro.installCmd(Pm.PACMAN, listOf("dillo")))
        assertTrue(DesktopDistro.installCmd(Pm.DNF, listOf("dillo")).let { it.startsWith("dnf -y") && it.endsWith("install dillo") && "install_weak_deps=False" in it })
        // Nothing that could become a shell option or a second command gets through.
        assertEquals("apk add ok", DesktopDistro.installCmd(Pm.APK, listOf("ok", "-rf", "x;rm", "\$(id)")))
        assertTrue(DesktopDistro.refreshCmd(Pm.PACMAN).startsWith("pacman -Syu"))
        assertTrue(DesktopDistro.refreshCmd(Pm.APT).contains("update"))
    }

    @Test fun stepScriptsForEachLinux() {
        val u = DesktopDistro.stepScript(DesktopDistro.UBUNTU, listOf("dillo"), update = true)
        assertTrue(u.startsWith("set -e")); assertTrue(u.trimEnd().endsWith("echo BLAZE_STEP_OK"))
        assertTrue("DEBIAN_FRONTEND=noninteractive" in u); assertTrue("dpkg --configure -a" in u); assertTrue("universe" in u)
        assertTrue(u.indexOf("apt-get -o Acquire::Retries=3 update") < u.indexOf("install -y"))
        val d = DesktopDistro.stepScript(DesktopDistro.DEBIAN, listOf("dillo"), update = false)
        assertFalse("universe" in d); assertFalse(" update" in d)
        val a = DesktopDistro.stepScript(DesktopDistro.ARCH, emptyList(), update = true)
        assertTrue("db.lck" in a); assertTrue("#DownloadUser" in a); assertTrue("pacman -Syu" in a); assertFalse("pacman -S --" in a)
        val f = DesktopDistro.stepScript(DesktopDistro.FEDORA, listOf("tigervnc-server-minimal"), update = true)
        assertTrue("makecache" in f); assertTrue("install tigervnc-server-minimal" in f)
        assertFalse("apk" in u || "apk" in a || "apk" in f)
    }

    @Test fun installRunsWithTheChosenPackageManager() {
        val scripts = mutableListOf<String>()
        val r = DesktopInstall.run(exec = { s, _ -> scripts += s; if (s.contains("BLAZE_DESKTOP_INSTALLED")) done else ok },
            status = { _, _ -> }, sleep = { }, distro = DesktopDistro.FEDORA)
        assertNull(r)
        assertEquals(1 + DesktopDistro.FEDORA.batches.size + 1, scripts.size)
        assertTrue(scripts[0].contains("makecache"))
        assertTrue(scripts.none { it.contains("apk ") })
        assertTrue(scripts[2].contains("install tigervnc-server-minimal xdotool"))
    }

    @Test fun aptErrorsAreClassified() {
        assertEquals(DesktopInstall.Fail.MISSING, DesktopInstall.classify("E: Unable to locate package dillo"))
        assertEquals(DesktopInstall.Fail.MISSING, DesktopInstall.classify("error: target not found: dillo"))
        assertEquals(DesktopInstall.Fail.MISSING, DesktopInstall.classify("No match for argument: dillo"))
        assertEquals(DesktopInstall.Fail.NETWORK, DesktopInstall.classify("E: Failed to fetch http://ports.ubuntu.com/pool/x.deb  Connection failed"))
        assertEquals(DesktopInstall.Fail.NETWORK, DesktopInstall.classify("error: failed retrieving file 'x.pkg.tar.xz' from mirror : Operation too slow"))
        assertEquals(DesktopInstall.Fail.NETWORK, DesktopInstall.classify("Curl error (28): Timeout was reached"))
    }

    @Test fun missingOptionalOnArchIsSkipped() {
        var tries = 0
        val r = DesktopInstall.run(exec = { s, _ ->
            when {
                s.contains("BLAZE_DESKTOP_INSTALLED") -> done
                s.contains("needed --disable-download-timeout dillo") -> { tries++; Result(1, "error: target not found: dillo") }
                else -> ok
            }
        }, status = { _, _ -> }, sleep = { }, distro = DesktopDistro.ARCH)
        assertNull(r); assertEquals(2, tries)
    }

    @Test fun progressWordsForEachManager() {
        assertEquals(50 to "Installing xfwm4 (5 of 10)", DesktopDistro.progressLine(Pm.PACMAN, "( 5/10) installing xfwm4"))
        assertEquals(30 to "Installing xdotool-3.20211022.1-6.fc41 (3 of 10)", DesktopDistro.progressLine(Pm.DNF, "[ 3/10] Installing xdotool-3.20211022.1-6.fc41.aarch64"))
        assertEquals(null to "Installing thunar\u2026", DesktopDistro.progressLine(Pm.APT, "Setting up thunar:arm64 (4.18.4-1) ..."))
        assertEquals(null to "Downloading xfwm4\u2026", DesktopDistro.progressLine(Pm.APT, "Get:12 http://deb.debian.org/debian bookworm/main arm64 xfwm4 arm64 4.18.0-1 [600 kB]"))
        assertEquals(null to "Downloading thunar\u2026", DesktopDistro.progressLine(Pm.APT, "Get:3 http://ports.ubuntu.com/ubuntu-ports noble/universe thunar arm64 4.18.8-1build3 [1 MB]"))
        assertEquals(DesktopPlan.progressLine("(12/96) Installing gtk+3.0 (3.24)"), DesktopDistro.progressLine(Pm.APK, "(12/96) Installing gtk+3.0 (3.24)"))
        assertNull(DesktopDistro.progressLine(Pm.APT, "Reading package lists..."))
    }

    @Test fun blazeOsLinuxAppPackagesPerDistro() {
        assertEquals(listOf("xeyes"), DesktopDistro.mapPackages(DesktopDistro.ALPINE, listOf("xeyes")))
        assertEquals(listOf("x11-apps"), DesktopDistro.mapPackages(DesktopDistro.UBUNTU, listOf("xeyes", "xcalc")))
        assertEquals(listOf("xorg-xeyes"), DesktopDistro.mapPackages(DesktopDistro.ARCH, listOf("xeyes")))
        assertEquals(listOf("xcalc"), DesktopDistro.mapPackages(DesktopDistro.FEDORA, listOf("xcalc")))
        assertEquals(listOf("gimp"), DesktopDistro.mapPackages(DesktopDistro.DEBIAN, listOf("gimp")))          // same name everywhere
        assertEquals(listOf("my-pkg"), DesktopDistro.mapPackages(DesktopDistro.FEDORA, listOf("xeyes"), mapOf("fedora" to listOf("my-pkg"))))
        assertTrue(DesktopDistro.appInstallScript(DesktopDistro.ALPINE, listOf("xeyes")).startsWith("apk add --no-cache xeyes"))
        assertTrue(DesktopDistro.appInstallScript(DesktopDistro.DEBIAN, listOf("x11-apps")).let { "apt-get" in it && "update" in it && "x11-apps" in it })
        assertTrue(DesktopDistro.appInstallScript(DesktopDistro.ARCH, listOf("xorg-xeyes")).contains("pacman -S"))
        assertTrue(DesktopDistro.appInstallScript(DesktopDistro.FEDORA, listOf("xeyes")).startsWith("dnf -y"))
    }

    @Test fun startScriptsWorkOnEveryLinux() {
        // Debian / Ubuntu only ship Xtigervnc + tigervncpasswd for sure; the shims give every Linux the same names.
        assertTrue(DesktopPlan.SHIMS.contains("Xtigervnc")); assertTrue(DesktopPlan.SHIMS.contains("tigervncpasswd"))
        assertTrue(DesktopPlan.startScript().startsWith(DesktopPlan.SHIMS))
        assertTrue(DesktopPlan.passwdScript("ab12CD34").startsWith(DesktopPlan.SHIMS))
        assertTrue(DesktopPlan.FINISH_SCRIPT.contains(DesktopPlan.SHIMS))
        // Shims never fail a `set -e` step.
        assertTrue(DesktopPlan.SHIMS.endsWith("|| true"))
        assertTrue(DesktopPlan.STOP_SCRIPT.contains("[X]tigervnc"))
        assertTrue(DesktopPlan.ALIVE_SCRIPT.contains(DesktopPlan.PID_FILE))
    }

    @Test fun removalPlanKeepsAnInstalledAlpine() {
        val a = DesktopDistro.removalPlan(DesktopDistro.ALPINE, baseInstalled = true, alpineArchive = "alpine-3.20.3-aarch64.tar.gz")
        assertFalse("alpine" in a)
        assertTrue("alpine/var/cache/blaze-apk" in a); assertTrue("alpine/var/cache/apk/*" in a)
        assertTrue("alpine-3.20.3-aarch64.tar.gz.part" in a)
        val half = DesktopDistro.removalPlan(DesktopDistro.ALPINE, baseInstalled = false, alpineArchive = "x.tar.gz")
        assertTrue("alpine" in half); assertTrue("x.tar.gz.part" in half)
        val u = DesktopDistro.removalPlan(DesktopDistro.UBUNTU, baseInstalled = false, alpineArchive = "x")
        assertEquals(listOf(".dl-ubuntu.tar.xz", ".dl-ubuntu.tar.xz.part", "ubuntu"), u)
        assertTrue("debian/var/cache/apt/archives/*" in DesktopDistro.removalPlan(DesktopDistro.DEBIAN, true, "x"))
        assertTrue("archlinux/var/cache/pacman/pkg/*" in DesktopDistro.removalPlan(DesktopDistro.ARCH, true, "x"))
        assertTrue("fedora/var/cache/dnf/*" in DesktopDistro.removalPlan(DesktopDistro.FEDORA, true, "x"))
        // Never anything outside filesDir/linux.
        DesktopDistro.ALL.forEach { d -> listOf(true, false).forEach { b -> DesktopDistro.removalPlan(d, b, "x").forEach { p ->
            assertFalse(p, p.startsWith("/") || p.contains("..")) } } }
    }
}

/** v0.33.5: Stop, "Download paused", Start resuming, and "Remove downloaded files". */
class DesktopFlowTest {
    private val ok = Result(0, "BLAZE_STEP_OK")
    private val done = Result(0, "BLAZE_DESKTOP_INSTALLED")

    @Test fun stopPausesADownloadAndStopsADesktop() {
        assertTrue(DesktopFlow.canStop(Phase.INSTALLING)); assertTrue(DesktopFlow.canStop(Phase.RUNNING))
        assertFalse(DesktopFlow.canStop(Phase.IDLE)); assertFalse(DesktopFlow.canStop(Phase.PAUSED))
        assertEquals(Phase.PAUSED, DesktopFlow.afterStop(Phase.INSTALLING))
        assertEquals(Phase.STOPPED, DesktopFlow.afterStop(Phase.RUNNING))
        assertEquals(Phase.STOPPED, DesktopFlow.afterStop(Phase.STARTING))
    }

    @Test fun labels() {
        assertEquals("Stop download", DesktopFlow.mainLabel(Phase.INSTALLING, false))
        assertEquals("Start", DesktopFlow.mainLabel(Phase.PAUSED, false))
        assertEquals("Resume", DesktopFlow.mainLabel(Phase.IDLE, true))
        assertEquals("Start", DesktopFlow.mainLabel(Phase.IDLE, false))
        assertEquals("41.2 MB of Ubuntu is kept on the phone. Start carries on from where it stopped.", DesktopFlow.pausedLine(41_200_000, "Ubuntu"))
        assertEquals("Start carries on from where it stopped.", DesktopFlow.pausedLine(0, "Ubuntu"))
    }

    @Test fun pausedSurvivesARestartUntilInstalled() {
        assertEquals(Phase.PAUSED, DesktopFlow.onLaunch(Phase.IDLE, pausedPref = true, installed = false))
        assertEquals(Phase.IDLE, DesktopFlow.onLaunch(Phase.IDLE, pausedPref = true, installed = true))
        assertEquals(Phase.IDLE, DesktopFlow.onLaunch(Phase.IDLE, pausedPref = false, installed = false))
        assertEquals(Phase.RUNNING, DesktopFlow.onLaunch(Phase.RUNNING, pausedPref = true, installed = false))
    }

    @Test fun switchingAndRemovingOnlyWhenNothingRuns() {
        listOf(Phase.INSTALLING, Phase.STARTING, Phase.STOPPING, Phase.RUNNING).forEach { assertFalse(it.name, DesktopFlow.canSwitch(it)) }
        listOf(Phase.IDLE, Phase.PAUSED, Phase.STOPPED, Phase.ERROR, Phase.KILLED).forEach { assertTrue(it.name, DesktopFlow.canSwitch(it)) }
        assertTrue(DesktopFlow.canRemove(Phase.PAUSED, 10)); assertFalse(DesktopFlow.canRemove(Phase.PAUSED, 0))
        assertFalse(DesktopFlow.canRemove(Phase.INSTALLING, 10)); assertFalse(DesktopFlow.canRemove(Phase.RUNNING, 10))
        assertTrue(DesktopFlow.canRemove(Phase.IDLE, 10, installed = false)); assertFalse(DesktopFlow.canRemove(Phase.IDLE, 10, installed = true))
        assertTrue(DesktopFlow.canRemove(Phase.ERROR, 10, installed = true))
        assertEquals(Phase.IDLE, DesktopFlow.afterRemove())
    }

    @Test fun stopMidInstallReturnsAtOnceAndStartResumesWhereItStopped() {
        // First Start: Stop is tapped while the third batch (XFCE session) is installing.
        var stop = false
        val first = mutableListOf<String>()
        val r1 = DesktopInstall.run(exec = { s, _ ->
            first += s
            if (s.contains("xfce4-session")) { stop = true; Result(137, "Killed") } else if (s.contains("BLAZE_DESKTOP_INSTALLED")) done else ok
        }, status = { _, _ -> }, sleep = { error("no retry wait after Stop") }, stopped = { stop }, distro = DesktopDistro.DEBIAN)
        assertEquals(DesktopFlow.STOPPED_MARK, r1)
        assertEquals(4, first.size)                       // refresh + 2 batches + the one that was stopped; nothing after it
        assertTrue(first.none { it.contains("BLAZE_DESKTOP_INSTALLED") })

        // Second Start: the package manager skips what's installed, so each step is cheap; it carries on to the end.
        val second = mutableListOf<String>()
        val r2 = DesktopInstall.run(exec = { s, _ -> second += s; if (s.contains("BLAZE_DESKTOP_INSTALLED")) done else ok },
            status = { _, _ -> }, sleep = { }, distro = DesktopDistro.DEBIAN)
        assertNull(r2)
        assertTrue(second.any { it.contains("xfce4-session") })
        assertTrue(second[1].contains("dpkg --configure -a"))  // finishes what the killed install left half-done
    }

    @Test fun stopDuringARetryWaitEndsTheWait() {
        var stop = false
        var n = 0
        val r = DesktopInstall.run(exec = { s, _ -> if (s.contains("add tigervnc")) { n++; Result(1, "ERROR: temporary error (try again later)") } else ok },
            status = { _, _ -> }, sleep = { stop = true }, stopped = { stop })
        assertEquals(DesktopFlow.STOPPED_MARK, r); assertEquals(1, n)
    }

    @Test fun stopBeforeAnythingRunsDoesNothing() {
        val r = DesktopInstall.run(exec = { _, _ -> error("nothing runs after Stop") }, status = { _, _ -> }, sleep = { }, stopped = { true })
        assertEquals(DesktopFlow.STOPPED_MARK, r)
    }

    @Test fun terminalOpensInTheChosenLinux() {
        val inst = setOf("alpine", "ubuntu")
        assertEquals("ubuntu", TermLogic.pick(null, inst, "ubuntu").id)
        assertEquals("ubuntu", TermLogic.pick("phone", inst, "ubuntu").id)
        assertEquals("phone", TermLogic.pick("phone", inst, "alpine").id)     // Alpine chosen: the old rule (last tab used)
        assertEquals("alpine", TermLogic.pick(null, inst, "fedora").id)       // chosen but not installed yet
    }
}
