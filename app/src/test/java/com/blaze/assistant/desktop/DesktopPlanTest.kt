package com.blaze.assistant.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.25.1: the Linux desktop's install / start / stop lines, its light defaults, password and plain progress. */
class DesktopPlanTest {
    @Test fun vncIsLocalOnlyWithPasswordAndLightDefaults() {
        val a = DesktopPlan.xvncArgs()
        assertTrue("-localhost" in a); assertTrue("VncAuth" in a); assertTrue("-PasswordFile" in a)
        assertEquals("16", a[a.indexOf("-depth") + 1]); assertEquals("1024x576", a[a.indexOf("-geometry") + 1])
        assertEquals("tcp", a[a.indexOf("-nolisten") + 1])
        val s = DesktopPlan.startScript()
        assertTrue(s.contains("Xvnc :1")); assertTrue(s.contains("startxfce4")); assertTrue(s.contains("DISPLAY=:1"))
        assertTrue(DesktopPlan.STOP_SCRIPT.contains("pkill -9 -f '[X]vnc'"))
    }

    @Test fun installsFreeDesktopBits() {
        val i = DesktopPlan.installScript()
        listOf("xfce4", "xfce4-terminal", "thunar", "tigervnc", "xdotool", "imagemagick", "font-dejavu", "dillo").forEach { assertTrue(it, i.contains(" $it")) }
        assertTrue(i.contains(DesktopPlan.INSTALLED_MARK))
        assertTrue(DesktopPlan.sizeLine().contains("Wi-Fi"))
    }

    @Test fun passwordIsRandomAndNeverInArgs() {
        val a = DesktopPlan.password(); val b = DesktopPlan.password()
        assertEquals(8, a.length); assertTrue(a.all { it.isLetterOrDigit() }); assertTrue(a != b)
        val p = DesktopPlan.passwdScript("ab'c;rm -rf /")
        assertTrue(p.contains("vncpasswd -f")); assertFalse(p.contains(";rm")); assertFalse(p.contains("'ab'c"))
        DesktopPlan.xvncArgs().forEach { assertFalse(it.contains(a)) }
    }

    @Test fun cpuSupport() {
        assertTrue(DesktopPlan.supported("arm64-v8a")); assertTrue(DesktopPlan.supported("armeabi-v7a"))
        assertFalse(DesktopPlan.supported("x86")); assertFalse(DesktopPlan.supported(null))
        assertFalse(DesktopPlan.UNSUPPORTED.contains("proot"))
    }

    @Test fun plainProgress() {
        assertEquals(12 to "Installing gtk+3.0 (12 of 96)", DesktopPlan.progressLine("(12/96) Installing gtk+3.0 (3.24.41-r0)"))
        assertEquals("Installing font-dejavu (96 of 96)", DesktopPlan.progressLine("(96/96) Installing font-dejavu (2.37-r5)")!!.second)
        assertEquals(100, DesktopPlan.progressLine("BLAZE_DESKTOP_INSTALLED")!!.first)
        assertNull(DesktopPlan.progressLine("random noise"))
    }

    @Test fun agentCommandsAreQuoted() {
        assertEquals("DISPLAY=:1 xdotool mousemove 10 20 click 3", DesktopPlan.clickCmd(10, 20, 3))
        assertTrue(DesktopPlan.typeCmd("it's \$HOME; rm -rf /").contains("'it'\\''s \$HOME; rm -rf /'"))
        assertEquals("DISPLAY=:1 xdotool key -- ctrl+s Return", DesktopPlan.keyCmd("ctrl+s Return; reboot"))
        assertEquals("xfce4-terminal", DesktopPlan.appCommand("Terminal"))
        assertTrue(DesktopPlan.screenshotCmd().contains("import -window root"))
    }
}
