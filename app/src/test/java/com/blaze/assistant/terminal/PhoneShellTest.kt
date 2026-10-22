package com.blaze.assistant.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v0.29.8: the phone tab used to open as "[Process completed (signal 31) - press Enter]". Android's app seccomp filter
 * kills setgid()/setuid() with SIGSYS, and the bundled BusyBox called both at the start of every applet (check_suid's
 * privilege drop). These pin the launch decision, the probe, the exit note and the binary patch that skips the drop.
 */
class PhoneShellTest {
    @Test fun launchUsesBusyboxOnlyWhenTheProbePassed() {
        val bb = "/data/app/x/lib/arm64/libbusybox.so"
        assertEquals(TermLogic.Launch(bb, listOf("ash", "-l"), true), TermLogic.phoneLaunch(bb, true))
        assertEquals(TermLogic.Launch("/system/bin/sh", listOf("sh", "-l"), false), TermLogic.phoneLaunch(bb, false))
        assertEquals(TermLogic.Launch("/system/bin/sh", listOf("sh", "-l"), false), TermLogic.phoneLaunch(null, true))
        assertEquals(TermLogic.Launch("/system/bin/sh", listOf("sh", "-l"), false), TermLogic.phoneLaunch(null, false))
    }

    @Test fun probe() {
        assertTrue(TermLogic.probeOk(0, "ok\n"))
        assertTrue(TermLogic.probeOk(0, "warning: something\nok"))
        assertFalse(TermLogic.probeOk(159, ""))                       // killed by SIGSYS (128 + 31)
        assertFalse(TermLogic.probeOk(127, "libbusybox.so: applet not found"))
        assertFalse(TermLogic.probeOk(0, "nope"))
    }

    @Test fun exitNotes() {
        assertEquals(31, TermLogic.signalOf(-31)); assertNull(TermLogic.signalOf(0)); assertNull(TermLogic.signalOf(2))
        val n = TermLogic.finishNote("phone", true, -31)
        assertNotNull(n); assertTrue(n!!.contains("signal 31")); assertTrue(n.contains("Android's own shell"))
        assertTrue(TermLogic.finishNote("phone", false, -31)!!.contains("signal 31"))
        assertNull(TermLogic.finishNote("phone", true, 0))            // a normal `exit`
        assertNotNull(TermLogic.finishNote("phone", true, -9))
        assertNull(TermLogic.finishNote("ubuntu", true, -31))
    }

    @Test fun pingAndRootToolsStayAndroids() {
        for (a in listOf("sh", "su", "ping", "ping6", "mount")) assertTrue(a, a in TermLogic.KEEP_ANDROIDS)
        for (a in listOf("ls", "top", "vi", "wget")) assertFalse(a, a in TermLogic.KEEP_ANDROIDS)
    }

    @Test fun phoneShellExplainsItself() {
        val w = TermLogic.welcome(TermLogic.PHONE, true)
        assertTrue(w.contains("PHONE SHELL")); assertTrue(w.contains("Not a full Linux")); assertTrue(w.contains("Install a Linux"))
        for (c in listOf("ls", "cd /sdcard", "top", "help")) assertTrue(c, w.contains("   $c "))
        // Lines fit a small phone (low-end phone portrait at the default size is ~45 columns).
        w.split("\r\n").forEach { assertTrue("too wide: $it", it.length <= 44) }
        assertTrue(TermLogic.welcome(TermLogic.PHONE, false).contains("toybox"))
        val labels = TermLogic.PHONE_EXAMPLES.map { it.first }
        assertTrue("help" in labels && "ls" in labels && "top" in labels && "ping" in labels)
        assertTrue(TermLogic.PHONE_EXAMPLES.none { it.second.isBlank() || it.second.contains('\n') })
        assertEquals("Phone shell · built-in tools, no install", TermLogic.PHONE.label)
    }

    @Test fun rcDefinesHelp() {
        for (bb in listOf(true, false)) {
            val rc = TermLogic.phoneRc(bb)
            assertTrue(rc.contains("help() {")); assertTrue(rc.contains("cat <<'BLAZE_HELP'")); assertTrue(rc.trimEnd().endsWith("}"))
            assertEquals(2, Regex("(?m)^BLAZE_HELP$|<<'BLAZE_HELP'").findAll(rc).count())
            assertTrue(rc.contains("ping -c 3 google.com")); assertTrue(rc.contains("Install a Linux"))
            assertEquals(bb, rc.contains("busybox --list"))
        }
        assertTrue(TermLogic.phoneRc(true).contains("PS1='phone:\\w \\$ '"))
        assertTrue(TermLogic.phoneRc(false).contains("PS1='phone:\${PWD##*/} \$ '"))
    }

    // ---- v0.30.2: probe per build, auto-fallback on the first death, 32-bit file tools ----

    @Test fun verdictIsPerBuild() {
        val k = TermLogic.bbKey(118, 1_760_000_000_000)
        assertEquals("v118:1760000000000", k)
        assertEquals(TermLogic.BbVerdict(true, true), TermLogic.bbParse(k, TermLogic.bbStamp(k, TermLogic.BbVerdict(true, true))))
        assertEquals(TermLogic.BbVerdict(true, false), TermLogic.bbParse(k, TermLogic.bbStamp(k, TermLogic.BbVerdict(true, false))))
        assertEquals(false, TermLogic.bbParse(k, TermLogic.bbStamp(k, TermLogic.BbVerdict(false, true)))!!.ok)
        // Another build (update, reinstall) or the v0.29.8 format ("<lastUpdateTime> ok"): probe again.
        assertNull(TermLogic.bbParse(TermLogic.bbKey(119, 1_760_000_000_000), TermLogic.bbStamp(k, TermLogic.BbVerdict(true))))
        assertNull(TermLogic.bbParse(TermLogic.bbKey(118, 1), TermLogic.bbStamp(k, TermLogic.BbVerdict(true))))
        assertNull(TermLogic.bbParse(k, "1760000000000 ok"))
        assertNull(TermLogic.bbParse(k, null)); assertNull(TermLogic.bbParse(k, "")); assertNull(TermLogic.bbParse(k, "$k maybe"))
    }

    @Test fun trueProbe() {
        assertTrue(TermLogic.trueProbeOk(0))
        for (c in listOf(1, 127, 159, 139, 132, null)) assertFalse("$c", TermLogic.trueProbeOk(c))
    }

    @Test fun firstDeathFallsBackAtOnce() {
        // Killed by the sandbox: always, even long after it started.
        assertTrue(TermLogic.autoFallback("phone", true, -31, 100))
        assertTrue(TermLogic.autoFallback("phone", true, -31, 3_600_000))
        // Any error or signal within seconds: BusyBox didn't start.
        assertTrue(TermLogic.autoFallback("phone", true, 127, 200))
        assertTrue(TermLogic.autoFallback("phone", true, -11, 1_000))
        assertTrue(TermLogic.autoFallback("phone", true, -4, 4_999))
        // Not for a normal exit, a later crash, Android's own sh, or a Linux tab.
        assertFalse(TermLogic.autoFallback("phone", true, 0, 100))
        assertFalse(TermLogic.autoFallback("phone", true, 1, 60_000))
        assertFalse(TermLogic.autoFallback("phone", true, -9, 60_000))
        assertFalse(TermLogic.autoFallback("phone", false, -31, 100))
        assertFalse(TermLogic.autoFallback("alpine", true, -31, 100))
    }

    @Test fun fallbackNoteSaysWhyAndFitsASmallPhone() {
        val n = TermLogic.fallbackNote(-31)
        assertTrue(n.contains("signal 31")); assertTrue(n.contains("Android's own shell")); assertTrue(n.contains("Nothing to do"))
        assertTrue(TermLogic.fallbackNote(127).contains("exit code 127"))
        assertTrue(TermLogic.fallbackNote(-11).contains("signal 11"))
        assertTrue(n.endsWith("\r\n\r\n"))
        for (x in listOf(-31, 127, -11)) TermLogic.fallbackNote(x).split("\r\n").forEach { assertTrue("too wide: $it", it.length <= 44) }
    }

    @Test fun fileToolsProbe() {
        assertTrue(TermLogic.fileToolsOk("done\n"))
        assertTrue(TermLogic.fileToolsOk("ln: f: Permission denied\ndone"))     // an ordinary error isn't the sandbox
        assertFalse(TermLogic.fileToolsOk("sigsys\ndone\n"))
        assertFalse(TermLogic.fileToolsOk(""))                                   // didn't finish
        assertFalse(TermLogic.fileToolsOk(null))                                 // hung, killed
        // Runs for real where there's a POSIX sh; a fake "busybox" that kills itself with SIGSYS on chmod.
        val sh = listOf("/bin/sh", "/system/bin/sh").firstOrNull { File(it).canExecute() } ?: return
        val dir = createTempDir(); val fake = File(dir, "bb").apply {
            writeText("#!/bin/sh\ncase \"\$1\" in chmod) kill -SYS \$\$;; esac\nexit 0\n"); setExecutable(true) }
        val work = File(dir, "w").apply { mkdirs() }
        val out = ProcessBuilder(sh, "-c", TermLogic.FILE_TOOLS_PROBE, fake.absolutePath, work.absolutePath).redirectErrorStream(true).start()
            .let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
        assertFalse(out, TermLogic.fileToolsOk(out)); assertTrue(out, out.contains("sigsys"))
        fake.writeText("#!/bin/sh\nexit 0\n")
        val out2 = ProcessBuilder(sh, "-c", TermLogic.FILE_TOOLS_PROBE, fake.absolutePath, work.absolutePath).redirectErrorStream(true).start()
            .let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
        assertTrue(out2, TermLogic.fileToolsOk(out2))
        dir.deleteRecursively()
    }

    @Test fun fileToolsStayAndroidsWhenTheyDontWork() {
        val all = listOf("ls", "chmod", "ln", "rmdir", "rm", "cp", "mv", "tar", "vi", "ping", "sh", "busybox", " top ", "", "a/b")
        assertEquals(listOf("ls", "chmod", "ln", "rmdir", "rm", "cp", "mv", "tar", "vi", "top"), TermLogic.appletsToLink(all, true))
        assertEquals(listOf("ls", "vi", "top"), TermLogic.appletsToLink(all, false))
        // The ones qemu-arm -strace showed calling chmod/chown32/link/symlink/rmdir/mknod in the 32-bit build.
        for (a in listOf("chmod", "chown", "ln", "rmdir", "rm", "cp", "mkfifo", "tar")) assertTrue(a, a in TermLogic.LEGACY_SYSCALL_APPLETS)
    }

    // ---- the BusyBox binaries: check_suid's privilege drop is skipped (tools/busybox/README.md) ----

    private fun word(f: File, off: Int): Int {
        val b = f.readBytes()
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun lib(abi: String): File? = listOf("src/main/jniLibs", "app/src/main/jniLibs").map { File(it, "$abi/libbusybox.so") }.firstOrNull { it.exists() }

    @Test fun arm64BusyboxSkipsSetidDrop() {
        val f = lib("arm64-v8a") ?: return
        // 0x404758: was `cbnz w3, 0x404768` (0x35000083: drop privileges when the applet is BB_SUID_DROP), now `b 0x404768`.
        assertEquals(0x14000004, word(f, 0x4758))
        // The two calls it now jumps over: bl setgid; ldr w0, [x21, #0xfd4]; bl setuid.
        assertEquals(0x9403410B.toInt(), word(f, 0x475c))
    }

    @Test fun armv7BusyboxSkipsSetidDrop() {
        val f = lib("armeabi-v7a") ?: return
        // 0x1f22c: was `bne 0x1f240` (0x1A000003), now `b 0x1f240`, over bl setgid / bl setuid.
        assertEquals(0xEA000003.toInt(), word(f, 0xf22c))
    }
}
