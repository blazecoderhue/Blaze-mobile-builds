package com.blaze.assistant.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * v0.30.2: in Alpine, `cd phone/` said "can't cd to phone/: Function not implemented". The PRoot bundled up to v0.30.1
 * answered chdir()/getcwd() with ENOSYS under Android's app seccomp filter; v0.30.2 ships Termux's proot 5.1.107.96.
 * These pin blaze-doctor (the self-test), the seccomp fallback decision and the bundled binaries (tools/proot/README.md).
 */
class LinuxDoctorTest {
    @Test fun scriptChecksTheCdsThatFailed() {
        val s = LinuxDoctor.script()
        assertTrue(s.startsWith("#!/bin/sh\n"))
        assertTrue(s.contains("chk 'cd ~/phone' 'cd /root && cd phone/ && cd /root/phone' '/root/phone'"))
        assertTrue(s.contains("chk 'cd /sdcard' 'cd /sdcard' '/sdcard'"))
        assertTrue(s.contains("'getcwd'")); assertTrue(s.contains("'stat'")); assertTrue(s.contains("'fork'"))
        assertTrue(s.contains("echo \"blaze-doctor: \$ok ok, \$bad failed\""))
        assertTrue(s.trimEnd().endsWith("exit \$bad"))
        // Every check is one single-quoted word (no stray quote breaks the script).
        LinuxDoctor.CHECKS.forEach { assertFalse(it.name, it.cmd.contains('\'') || it.name.contains('\'')) }
    }

    /** Runs the script for real when a POSIX sh is around (the CI runner has one); skips otherwise. */
    @Test fun scriptRunsAndReportsOnThisMachine() {
        val sh = listOf("/bin/sh", "/system/bin/sh").firstOrNull { File(it).canExecute() } ?: return
        val f = File.createTempFile("blaze-doctor", ".sh").apply { writeText(LinuxDoctor.script()); deleteOnExit() }
        val p = ProcessBuilder(sh, f.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor()
        val r = LinuxDoctor.parse(out)
        assertTrue(out, r.complete)
        assertEquals(out, LinuxDoctor.CHECKS.size, r.ok.size + r.failed.size + out.lines().count { it.startsWith("skip ") })
        assertTrue(out, "cd" in r.ok && "pipe" in r.ok)
    }

    @Test fun parse() {
        val r = LinuxDoctor.parse("ok   cd\nFAIL cd ~/phone: /bin/sh: cd: line 0: can't cd to phone/: Function not implemented\nskip cd /sdcard\nok   stat\nblaze-doctor: 2 ok, 1 failed\n")
        assertEquals(listOf("cd", "stat"), r.ok)
        assertEquals("/bin/sh: cd: line 0: can't cd to phone/: Function not implemented", r.failed["cd ~/phone"])
        assertTrue(r.complete); assertFalse(r.passed); assertTrue(r.cdBroken)
        assertFalse(LinuxDoctor.parse("ok   cd\n").complete)
        assertTrue(LinuxDoctor.parse("ok   cd\nblaze-doctor: 1 ok, 0 failed").passed)
        assertFalse(LinuxDoctor.parse("FAIL fork: x\nblaze-doctor: 0 ok, 1 failed").cdBroken)
    }

    private fun rep(vararg fails: String) = LinuxDoctor.Report(listOf("pipe"), fails.associateWith { "Function not implemented" }, true)

    @Test fun seccompStaysOnUnlessOffIsBetter() {
        assertFalse(LinuxDoctor.noSeccomp(rep(), null))                         // passed: never even try without
        assertFalse(LinuxDoctor.noSeccomp(null, rep()))                         // didn't run
        assertTrue(LinuxDoctor.noSeccomp(rep("cd", "getcwd"), rep()))          // off fixes it
        assertTrue(LinuxDoctor.noSeccomp(rep("cd ~/phone"), rep("fork")))       // off fixes cd at least
        assertFalse(LinuxDoctor.noSeccomp(rep("cd"), rep("cd")))               // off doesn't help
        assertFalse(LinuxDoctor.noSeccomp(rep("cd"), LinuxDoctor.Report(emptyList(), emptyMap(), false)))  // off died half way
        assertTrue(LinuxDoctor.noSeccomp(rep("fork", "stat"), rep("stat")))
        assertFalse(LinuxDoctor.noSeccomp(rep("fork"), rep("stat")))
    }

    @Test fun modeIsPerBuild() {
        val k = TermLogic.bbKey(118, 5)
        assertEquals(true, LinuxDoctor.parseMode(k, LinuxDoctor.modeStamp(k, true)))
        assertEquals(false, LinuxDoctor.parseMode(k, LinuxDoctor.modeStamp(k, false)))
        assertNull(LinuxDoctor.parseMode(TermLogic.bbKey(119, 5), LinuxDoctor.modeStamp(k, true)))
        assertNull(LinuxDoctor.parseMode(k, null)); assertNull(LinuxDoctor.parseMode(k, "$k fast"))
    }

    // ---- the bundled PRoot is Termux's 5.1.107.96 (tools/proot/README.md has the source and the reason) ----

    private fun lib(abi: String, name: String): File? =
        listOf("src/main/jniLibs", "app/src/main/jniLibs").map { File(it, "$abi/$name") }.firstOrNull { it.exists() }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun bundledProotIsTermux5_1_107_96() {
        val want = mapOf(
            "arm64-v8a/libproot.so" to "1545b85b312505db6eb6908ff8b2ded0a77a3bd689c50ae85aa7c1d8445dd717",
            "arm64-v8a/libproot_loader.so" to "cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551",
            "arm64-v8a/libproot_loader32.so" to "c429996fee730e1a4fe12625b22dab5a5bc2847a4cae274d36fd4a19cd8299a8",
            "arm64-v8a/libtalloc.so" to "742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309",
            "arm64-v8a/libandroid-shmem.so" to "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731",
            "armeabi-v7a/libproot.so" to "c547730a38b32afc60e5dab770043526eda605b7cdd9e6d4b4ce6f01a243834d",
            "armeabi-v7a/libproot_loader.so" to "7c3d9f74b8c8a87c46766d8bbeebeabf24cc4de507690ab04ba7f0190a598500",
            "armeabi-v7a/libtalloc.so" to "a376ac7cbd18e6777657328c701b4979610473811761edc0fdb0ec388d8504af",
            "armeabi-v7a/libandroid-shmem.so" to "ef35602fa6c8bca9081af1594fabeacbd98b6e8921dd6be54484291933b8040f",
        )
        for ((path, hash) in want) {
            val (abi, name) = path.split('/')
            val f = lib(abi, name) ?: return     // run from somewhere without the libs
            assertEquals(path, hash, sha(f))
        }
    }

    /** proot needs libtalloc.so.2 and libandroid-shmem.so by name; Linux.prepare() links both into filesDir/linux/lib. */
    @Test fun prootFindsItsLibraries() {
        val f = lib("arm64-v8a", "libproot.so") ?: return
        val s = String(f.readBytes(), Charsets.ISO_8859_1)
        assertTrue(s.contains("libtalloc.so.2")); assertTrue(s.contains("libandroid-shmem.so"))
        // The calls this release is about are handled (Termux's patches for Android's seccomp filter).
        assertTrue(s.contains("PROOT_NO_SECCOMP")); assertTrue(s.contains("faccessat2"))
        val talloc = lib("arm64-v8a", "libtalloc.so")!!
        assertTrue(String(talloc.readBytes(), Charsets.ISO_8859_1).contains("libtalloc.so.2"))
    }
}
