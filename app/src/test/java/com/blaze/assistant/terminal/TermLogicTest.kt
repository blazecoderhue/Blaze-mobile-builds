package com.blaze.assistant.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermLogicTest {
    @Test fun termuxKeyRows() {
        assertEquals(listOf("ESC", "/", "―", "HOME", "↑", "END", "PGUP"), TermLogic.ROWS[0].map { it.label })
        assertEquals(listOf("⇥", "CTRL", "ALT", "←", "↓", "→", "PGDN"), TermLogic.ROWS[1].map { it.label })
        assertTrue(TermLogic.ROWS.flatten().all { it.code != 0 || it.text.isNotEmpty() || it.toggle.isNotEmpty() })
    }

    @Test fun envsPreferLinux() {
        assertEquals(listOf("alpine", "ubuntu", "phone"), TermLogic.envs(setOf("ubuntu", "alpine")).map { it.id })
        assertEquals(listOf("phone"), TermLogic.envs(emptySet()).map { it.id })
        assertEquals("alpine", TermLogic.pick(null, setOf("alpine")).id)
        assertEquals("debian", TermLogic.pick("debian", setOf("alpine", "debian")).id)
        assertEquals("phone", TermLogic.pick("alpine", emptySet()).id)
    }

    @Test fun distrosPerCpu() {
        assertEquals("aarch64", TermLogic.cpu("arm64-v8a")); assertEquals("arm", TermLogic.cpu("armeabi-v7a")); assertEquals(null, TermLogic.cpu("x86_64"))
        assertEquals(listOf("alpine", "ubuntu", "debian", "archlinux", "fedora", "manjaro"), TermLogic.installable("arm64-v8a").map { it.id })
        assertEquals(listOf("alpine", "ubuntu", "debian", "archlinux"), TermLogic.installable("armeabi-v7a").map { it.id })
        assertEquals(listOf("alpine"), TermLogic.installable("x86_64").map { it.id })
        TermLogic.DISTROS.flatMap { it.urls.values }.forEach { assertTrue(it, it.startsWith("https://") && it.endsWith(".tar.xz")) }
        assertEquals("Ubuntu 24.04", TermLogic.distro("ubuntu")!!.env().label)
        assertEquals("Alpine Linux", TermLogic.ALPINE.label)
        assertTrue(TermLogic.welcome(TermLogic.distro("archlinux")!!.env()).contains("pacman -S <package>"))
    }

    @Test fun paxRecords() {
        val long = "usr/share/" + "x".repeat(120)
        fun rec(k: String, v: String): String { val body = " $k=$v\n"; var n = body.toByteArray().size + 2; while ("$n$body".toByteArray().size != n) n++; return "$n$body" }
        assertEquals(listOf("path" to long, "linkpath" to "../bin/é"), TermLogic.pax(rec("path", long) + rec("linkpath", "../bin/é")))
        assertEquals(emptyList<Pair<String, String>>(), TermLogic.pax("garbage"))
    }

    @Test fun phoneShell() {
        assertEquals("/lib/libbusybox.so" to listOf("ash", "-l"), TermLogic.phoneArgs("/lib/libbusybox.so"))
        assertEquals("/system/bin/sh" to listOf("sh", "-l"), TermLogic.phoneArgs(null))
    }

    @Test fun envForcesColourTerm() {
        val e = TermLogic.envArray(mapOf("HOME" to "/h", "TERM" to "dumb"), mapOf("PATH" to "/bin"))
        assertTrue("TERM=xterm-256color" in e); assertTrue("HOME=/h" in e); assertTrue("PATH=/bin" in e)
        assertTrue(e.none { it == "TERM=dumb" })
    }

    @Test fun tabsAndZoom() {
        assertEquals("1 alpine", TermLogic.tabLabel(1, TermLogic.ALPINE, null, false))
        assertEquals("2 vim", TermLogic.tabLabel(2, TermLogic.ALPINE, "vim", false))
        assertEquals("3 phone sh ✕", TermLogic.tabLabel(3, TermLogic.PHONE, "", true))
        assertEquals(TermLogic.MAX_FONT, TermLogic.zoom(60, 2f)); assertEquals(TermLogic.MIN_FONT, TermLogic.zoom(15, 0.2f))
        assertTrue(TermLogic.welcome(TermLogic.ALPINE).contains("apk add <package>"))
    }
}
