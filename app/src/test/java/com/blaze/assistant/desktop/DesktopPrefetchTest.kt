package com.blaze.assistant.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.3: the desktop's packages are fetched by Blaze (resumable) and installed from local files. */
class DesktopPrefetchTest {
    @Test fun parsesSimulate() {
        val out = """
            fetch https://dl-cdn.alpinelinux.org/alpine/v3.20/main/aarch64/APKINDEX.tar.gz
            (1/3) Installing libxfce4util (4.18.2-r0)
            (2/3) Upgrading busybox (1.36.1-r28 -> 1.36.1-r29)
            (3/3) Installing gtk+3.0 (3.24.43-r0)
            OK: 512 MiB in 120 packages
            BLAZE_SIM_OK
        """.trimIndent()
        val p = DesktopPrefetch.parseSimulate(out)
        assertEquals(listOf("libxfce4util-4.18.2-r0.apk", "busybox-1.36.1-r29.apk", "gtk+3.0-3.24.43-r0.apk"), p.map { it.file })
        assertTrue(DesktopPrefetch.parseSimulate("OK: 10 MiB in 3 packages").isEmpty())
        assertTrue(DesktopPrefetch.parseSimulate("(1/1) Installing ../etc (1)").isEmpty())   // never a path
        assertFalse(DesktopPrefetch.safe("a/b")); assertFalse(DesktopPrefetch.safe(".hidden"))
    }

    @Test fun urlsMainThenCommunityPerMirror() {
        val u = DesktopPrefetch.urls(DesktopPrefetch.Pkg("xfce4", "4.18-r0"), "aarch64", listOf("https://a/alpine/", "https://b/alpine"), first = 1)
        assertEquals(listOf(
            "https://b/alpine/v3.20/main/aarch64/xfce4-4.18-r0.apk", "https://b/alpine/v3.20/community/aarch64/xfce4-4.18-r0.apk",
            "https://a/alpine/v3.20/main/aarch64/xfce4-4.18-r0.apk", "https://a/alpine/v3.20/community/aarch64/xfce4-4.18-r0.apk"), u)
    }

    @Test fun scripts() {
        val staged = listOf(DesktopPrefetch.Pkg("tigervnc", "1.13-r0"))
        val s = DesktopPlan.stepScript("https://a/alpine", listOf("tigervnc", "xdotool"), update = false, staged = staged)
        assertTrue(s.contains("apk \$T add /var/cache/blaze-apk/tigervnc-1.13-r0.apk\napk \$T add tigervnc xdotool\nrm -f /var/cache/blaze-apk/tigervnc-1.13-r0.apk"))
        assertTrue(s.contains("ln -s /var/cache/apk /etc/apk/cache"))
        val sim = DesktopPrefetch.simulateScript("https://a/alpine", listOf("xfce4"), update = true)
        assertTrue(sim.contains("apk \$T update\napk \$T add --simulate xfce4")); assertTrue(sim.endsWith("echo BLAZE_SIM_OK"))
        assertEquals("Resuming xfce4 (2 of 9) \u00b7 41.2 MB so far", DesktopPrefetch.batchLine(DesktopPrefetch.Pkg("xfce4", "1"), 1, 9, 41_200_000, true))
    }

    @Test fun installUsesThePrefetchAndSkipsTheExtraUpdate() {
        val scripts = mutableListOf<String>(); var asked = 0
        val ok = DesktopInstall.Result(0, "BLAZE_STEP_OK"); val done = DesktopInstall.Result(0, "BLAZE_DESKTOP_INSTALLED")
        val r = DesktopInstall.run(exec = { s, _ -> scripts += s; if (s.contains("BLAZE_DESKTOP_INSTALLED")) done else ok }, status = { _, _ -> }, sleep = { },
            prefetch = { _, pkgs, _ -> asked++; listOf(DesktopPrefetch.Pkg(pkgs.first(), "1-r0")) })
        assertEquals(null, r)
        assertEquals(DesktopPlan.BATCHES.size, asked)
        assertTrue(scripts[1].contains("/var/cache/blaze-apk/${DesktopPlan.BATCHES[0].first()}-1-r0.apk"))
        // a failed prefetch (null) falls back to plain apk add
        val s2 = mutableListOf<String>()
        DesktopInstall.run(exec = { s, _ -> s2 += s; if (s.contains("BLAZE_DESKTOP_INSTALLED")) done else ok }, status = { _, _ -> }, sleep = { }, prefetch = { _, _, _ -> null })
        assertTrue(s2[1].contains("apk \$T add " + DesktopPlan.BATCHES[0].joinToString(" "))); assertFalse(s2[1].contains("blaze-apk"))
    }
}
