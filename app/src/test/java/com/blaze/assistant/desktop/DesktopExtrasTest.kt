package com.blaze.assistant.desktop

import com.blaze.assistant.terminal.TermLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.34.0 Linux desktop: app drawer, store, display, snapshots, auto-restart, clipboard, destructive commands, checksums. */
class DesktopExtrasTest {
    @Test fun appDrawerParsesDesktopFiles() {
        val out = "@@FILE /usr/share/applications/thunar.desktop\n[Desktop Entry]\nType=Application\nName=Thunar File Manager\nName[de]=Dateimanager\nExec=thunar %F\nIcon=org.xfce.thunar\nCategories=System;\n" +
            "@@FILE /usr/share/applications/hidden.desktop\n[Desktop Entry]\nType=Application\nName=Hidden\nExec=x\nNoDisplay=true\n" +
            "@@FILE /usr/share/applications/link.desktop\n[Desktop Entry]\nType=Link\nName=Web\nURL=x\n" +
            "@@FILE /usr/share/applications/abc.desktop\n[Desktop Entry]\nName=Mousepad\nExec=mousepad %U\n@@END"
        val e = DesktopExtras.parseEntries(out)
        assertEquals(listOf("Mousepad", "Thunar File Manager"), e.map { it.name })
        assertEquals("thunar", e[1].exec); assertEquals("org.xfce.thunar", e[1].icon)
        assertEquals(DesktopPlan.spoolCmd("mousepad"), DesktopExtras.launchCmd(e[0]))
        assertEquals("sh -c 'echo 100%'", DesktopExtras.cleanExec("sh -c 'echo 100%%' %u"))
    }

    @Test fun storeHasRealPackagesPerLinux() {
        val ff = DesktopExtras.STORE.first { it.id == "firefox" }
        assertEquals(listOf("firefox"), DesktopExtras.packagesFor(ff, DesktopDistro.ALPINE))
        assertEquals(listOf("firefox-esr"), DesktopExtras.packagesFor(ff, DesktopDistro.DEBIAN))
        assertNull(DesktopExtras.packagesFor(ff, DesktopDistro.UBUNTU))
        val cs = DesktopExtras.STORE.first { it.id == "code-server" }
        assertNull(DesktopExtras.installScript(cs, DesktopDistro.ALPINE))
        assertTrue(DesktopExtras.installScript(cs, DesktopDistro.DEBIAN)!!.contains("code-server.dev/install.sh"))
        assertEquals("apk add libreoffice && echo BLAZE_STORE_OK", DesktopExtras.installScript(DesktopExtras.STORE.first { it.id == "libreoffice" }, DesktopDistro.ALPINE))
        assertEquals("pacman -Sy --noconfirm --needed libreoffice-fresh && echo BLAZE_STORE_OK", DesktopExtras.installScript(DesktopExtras.STORE.first { it.id == "libreoffice" }, DesktopDistro.ARCH))
        assertTrue(DesktopExtras.STORE.size >= 8)
        DesktopExtras.STORE.forEach { a -> DesktopDistro.ALL.forEach { d -> if (DesktopExtras.packagesFor(a, d) == null) assertTrue(DesktopExtras.whyNot(a, d).isNotBlank()) } }
    }

    @Test fun displaySettings() {
        assertEquals(DesktopExtras.Display(1280, 720, 24), DesktopExtras.parseDisplay("1280x720@24"))
        assertEquals(DesktopExtras.DISPLAYS[0], DesktopExtras.parseDisplay("junk")); assertEquals(DesktopExtras.DISPLAYS[0], DesktopExtras.parseDisplay("9999x9999@16"))
        assertEquals("1920x1080@16", DesktopExtras.displayKey(DesktopExtras.Display(1920, 1080, 16)))
        assertTrue(DesktopExtras.displayMb(DesktopExtras.Display(1920, 1080, 24)) > DesktopExtras.displayMb(DesktopExtras.Display(1024, 576, 16)))
    }

    @Test fun snapshots() {
        val n = DesktopExtras.snapName("alpine", 1760000000000)
        assertEquals(DesktopExtras.Snapshot(n, "alpine", 1760000000000, 5), DesktopExtras.parseSnap(n, 5))
        assertNull(DesktopExtras.parseSnap("other.tar.gz", 1))
        val s = DesktopExtras.snapshotScript(n)
        assertTrue(s.contains("--exclude=./proc") && s.contains("--exclude=./root/phone") && s.contains("BLAZE_SNAP_OK"))
        assertTrue(DesktopExtras.roomForSnapshot(1_000_000_000, 800_000_000)); assertFalse(DesktopExtras.roomForSnapshot(1_000_000_000, 600_000_000))
    }

    @Test fun autoRestartRule() {
        assertTrue(DesktopExtras.shouldRestart(true, false, false, true, emptyList(), 1_000_000))
        assertFalse(DesktopExtras.shouldRestart(true, true, false, true, emptyList(), 1_000_000))
        assertFalse(DesktopExtras.shouldRestart(true, false, true, true, emptyList(), 1_000_000))
        assertFalse(DesktopExtras.shouldRestart(true, false, false, false, emptyList(), 1_000_000))
        assertFalse(DesktopExtras.shouldRestart(true, false, false, true, listOf(999_000, 998_000, 997_000), 1_000_000))
        assertTrue(DesktopExtras.shouldRestart(true, false, false, true, listOf(1, 2, 3), 1_000_000))
    }

    @Test fun clipboardMessages() {
        val b = DesktopExtras.clientCutText("hé\u20ac")
        assertEquals(listOf<Byte>(6, 0, 0, 0, 0, 0, 0, 3, 'h'.code.toByte(), 0xE9.toByte(), '?'.code.toByte()), b.toList())
        assertEquals("a\nb", DesktopExtras.serverCutText("a\r\nb".toByteArray()))
    }

    @Test fun destructiveCommands() {
        listOf("rm -rf /", "sudo rm -rf ~", "rm -fr build", "mkfs.ext4 /dev/sda1", "dd if=/dev/zero of=/dev/sda", ":(){ :|:& };:", "apt remove firefox",
            "pacman -Rns x", "apk del xfce4", "reboot", "curl -s x | sh", "git reset --hard").forEach { assertTrue(it, DesktopExtras.dangerous(it)) }
        listOf("ls -la", "rm notes.txt", "apt install vlc", "echo hello", "cat /etc/os-release", "git status").forEach { assertFalse(it, DesktopExtras.dangerous(it)) }
    }

    @Test fun everyRootfsHasAChecksumAndRealSize() {
        TermLogic.DISTROS.filter { it.id != "alpine" }.forEach { d ->
            d.urls.forEach { (cpu, _) ->
                assertEquals(64, TermLogic.sha256(d, cpu).length)
                assertTrue(TermLogic.bytes(d, cpu) > 10_000_000)
                assertTrue(TermLogic.sizeLine(d, cpu).contains("SHA-256"))
            }
        }
        assertEquals("64.1 MB download \u00b7 SHA-256 checked", TermLogic.sizeLine(TermLogic.distro("ubuntu")!!, "aarch64"))
    }
}
