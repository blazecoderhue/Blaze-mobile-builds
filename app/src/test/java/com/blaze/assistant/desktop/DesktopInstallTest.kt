package com.blaze.assistant.desktop

import com.blaze.assistant.desktop.DesktopInstall.Fail
import com.blaze.assistant.desktop.DesktopInstall.Result
import com.blaze.assistant.terminal.AlpineNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.25.5: the desktop install retries, switches mirrors, keeps progress and speaks plainly. */
class DesktopInstallTest {
    private val ok = Result(0, "OK: 120 MiB in 80 packages\nBLAZE_STEP_OK")
    private val done = Result(0, "BLAZE_DESKTOP_INSTALLED")
    private val drop = Result(1, "ERROR: https://dl-cdn.alpinelinux.org/alpine/v3.20/main/aarch64/gtk+3.0-3.24.41-r0.apk: temporary error (try again later)")

    private fun mirrorOf(script: String) = AlpineNet.MIRRORS.indexOfFirst { script.contains("$it/v3.20/main") }

    @Test fun classifiesApkErrors() {
        assertEquals(Fail.NETWORK, DesktopInstall.classify(drop.tail))
        assertEquals(Fail.NETWORK, DesktopInstall.classify("ERROR: ...: DNS lookup error"))
        assertEquals(Fail.NETWORK, DesktopInstall.classify("WARNING: fetching https://x/APKINDEX.tar.gz: network error (check Internet connection and firewall)"))
        assertEquals(Fail.SPACE, DesktopInstall.classify("ERROR: failed to extract usr/lib/libgtk-3.so: No space left on device"))
        assertEquals(Fail.MISSING, DesktopInstall.classify("ERROR: unable to select packages:\n  netsurf (no such package)"))
        assertEquals(Fail.OTHER, DesktopInstall.classify("ERROR: something odd"))
    }

    @Test fun backoffAndMirrorRotation() {
        assertEquals(listOf(0, 3, 6, 12, 24, 48, 60, 60), (1..8).map { DesktopInstall.backoffSec(it) })
        assertEquals(0 to 1, DesktopInstall.nextMirror(0, 1, 6))
        assertEquals(1 to 0, DesktopInstall.nextMirror(0, 2, 6))
        assertEquals(0 to 0, DesktopInstall.nextMirror(5, 2, 6))
        assertEquals(0 to 2, DesktopInstall.nextMirror(0, 2, 1))
    }

    @Test fun plainLines() {
        assertEquals("Connection dropped. Retrying (2 of 5)\u2026", DesktopInstall.retryLine(Fail.NETWORK, 2, 5, null))
        assertEquals("Connection dropped. Trying another server (eu.edge.kernel.org), 3 of 5\u2026",
            DesktopInstall.retryLine(Fail.NETWORK, 3, 5, "eu.edge.kernel.org"))
        assertTrue(DesktopInstall.failLine(Fail.NETWORK, "").contains("tap Start to carry on"))
        assertTrue(DesktopInstall.failLine(Fail.SPACE, "").contains("750 MB"))
        assertTrue(DesktopInstall.failLine(Fail.OTHER, "ERROR: weird thing").endsWith("(weird thing)"))
        listOf(Fail.NETWORK, Fail.SPACE, Fail.MISSING).forEach { assertTrue(!DesktopInstall.failLine(it, drop.tail).contains("apk")) }
    }

    @Test fun batchesCoverEveryPackageAndScriptsAreRight() {
        assertEquals(DesktopPlan.PACKAGES.toSet(), DesktopPlan.BATCHES.flatten().toSet())
        assertEquals(DesktopPlan.PACKAGES.size, DesktopPlan.BATCHES.flatten().size)
        val s = DesktopPlan.stepScript(AlpineNet.MIRRORS[1], listOf("tigervnc", "xdotool"), update = true)
        assertTrue(s.contains("https://eu.edge.kernel.org/alpine/v3.20/main\nhttps://eu.edge.kernel.org/alpine/v3.20/community"))
        assertTrue(s.contains("> /etc/apk/repositories"))
        assertTrue(s.contains("--timeout ${DesktopPlan.APK_TIMEOUT}"))
        assertTrue(s.contains("apk \$T update\n")); assertTrue(s.contains("apk \$T add tigervnc xdotool"))
        assertTrue(s.trimEnd().endsWith("echo BLAZE_STEP_OK")); assertTrue(s.startsWith("set -e"))
        val noUpdate = DesktopPlan.stepScript(AlpineNet.MIRRORS[0], listOf("netsurf"), update = false)
        assertTrue(!noUpdate.contains("update"))
    }

    @Test fun happyPathRunsEachStepOnce() {
        val scripts = mutableListOf<String>(); val lines = mutableListOf<String>()
        val r = DesktopInstall.run(exec = { s, _ -> scripts += s; if (s.contains("BLAZE_DESKTOP_INSTALLED")) done else ok },
            status = { _, l -> lines += l }, sleep = { error("no waiting on the happy path") })
        assertNull(r)
        assertEquals(1 + DesktopPlan.BATCHES.size + 1, scripts.size)
        assertTrue(scripts[0].contains("apk \$T update")); assertTrue(!scripts[2].contains("update"))   // one refresh, then just adds
        assertTrue(lines.none { it.contains("Retrying") })
    }

    @Test fun droppedConnectionRetriesThenSwitchesMirrorAndKeepsGoing() {
        var calls = 0; val waits = mutableListOf<Long>(); val lines = mutableListOf<String>(); val mirrors = mutableListOf<Int>()
        val tvnc = mutableListOf<String>()
        val r = DesktopInstall.run(exec = { s, _ ->
            calls++
            when {
                s.contains("BLAZE_DESKTOP_INSTALLED") -> done
                s.contains("add tigervnc") -> { tvnc += s; if (tvnc.size <= 2) drop else ok }   // drops twice on the first mirror
                else -> ok
            }
        }, status = { _, l -> lines += l }, sleep = { waits += it }, onMirror = { mirrors += it })
        assertNull(r)
        assertEquals(3, tvnc.size)
        assertEquals(0, mirrorOf(tvnc[0])); assertEquals(0, mirrorOf(tvnc[1])); assertEquals(1, mirrorOf(tvnc[2]))
        assertTrue(tvnc[1].contains("apk \$T update"))                 // refreshes the list after a failure
        assertEquals(listOf(3_000L, 6_000L), waits)
        assertEquals(listOf(1), mirrors)
        assertTrue(lines.contains("Connection dropped. Retrying (2 of 12)\u2026"))
        assertTrue(lines.any { it.startsWith("Connection dropped. Trying another server (eu.edge.kernel.org)") })
        // The first batch (done before the drops) isn't redone.
        assertEquals(DesktopPlan.BATCHES.size + 2 + 2, calls)
    }

    @Test fun givesUpPlainlyAfterAllTries() {
        val r = DesktopInstall.run(exec = { s, _ -> if (s.contains("add ")) drop else ok }, status = { _, _ -> }, sleep = { })
        assertEquals(DesktopInstall.failLine(Fail.NETWORK, ""), r)
    }

    @Test fun missingOptionalPackageIsSkippedNotFatal() {
        var browserTries = 0
        val missing = Result(1, "ERROR: unable to select packages:\n  dillo (no such package)")
        val r = DesktopInstall.run(exec = { s, _ ->
            when {
                s.contains("BLAZE_DESKTOP_INSTALLED") -> done
                s.contains("add dillo") -> { browserTries++; missing }
                else -> ok
            }
        }, status = { _, _ -> }, sleep = { })
        assertNull(r)
        assertEquals(2, browserTries)
    }

    @Test fun missingRequiredPackageStopsAfterThreeTries() {
        var n = 0
        val r = DesktopInstall.run(exec = { s, _ -> if (s.contains("add tigervnc")) { n++; Result(1, "ERROR: unable to select packages:\n  tigervnc (no such package)") } else ok },
            status = { _, _ -> }, sleep = { })
        assertEquals(3, n); assertEquals(DesktopInstall.failLine(Fail.MISSING, ""), r)
    }

    @Test fun everyDesktopPackageIsOnAlpine320() {
        // Checked against the v3.20 main + community indexes on 2026-10-18 (netsurf isn't there; dillo is).
        assertTrue("netsurf" !in DesktopPlan.PACKAGES); assertTrue("dillo" in DesktopPlan.PACKAGES)
    }

    @Test fun outOfSpaceStopsAtOnce() {
        var n = 0
        val r = DesktopInstall.run(exec = { s, _ -> if (s.contains("add ")) { n++; Result(1, "ERROR: No space left on device") } else ok },
            status = { _, _ -> }, sleep = { error("no retry when the phone is full") })
        assertEquals(1, n); assertTrue(r!!.contains("ran out of space"))
    }

    @Test fun progressRisesThroughTheSteps() {
        val pcts = mutableListOf<Int>()
        DesktopInstall.run(exec = { s, line -> if (s.contains("add ")) line("(5/10) Installing gtk+3.0 (3.24)"); if (s.contains("BLAZE_DESKTOP_INSTALLED")) done else ok },
            status = { p, _ -> if (p != null) pcts += p }, sleep = { })
        assertEquals(pcts.sorted(), pcts)
        assertTrue(pcts.last() in 80..99)
        assertEquals(DesktopInstall.pct(0, 9, 0), 0); assertEquals(99, DesktopInstall.pct(9, 9, 100))
    }
}
