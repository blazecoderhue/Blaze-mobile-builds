package com.blaze.assistant

import com.blaze.assistant.agent.Awareness
import com.blaze.assistant.agent.CommandPolicy
import com.blaze.assistant.agent.Commands
import com.blaze.assistant.agent.ToolRouter
import com.blaze.assistant.agent.ToolTruth
import com.blaze.assistant.agent.ToolVerify
import com.blaze.assistant.blazeos.OsApps
import com.blaze.assistant.blazeos.OsManifest
import com.blaze.assistant.terminal.LinuxDesktopCleanup
import com.blaze.assistant.terminal.TermLogic
import com.blaze.assistant.ui.Views
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wave B-4 (HQ item 12): the on-phone Linux desktop (XFCE + TigerVNC under PRoot) is gone: no tool, prompt line,
 * view, settings page, chat card, BlazeOS app or service offers it. Blaze's Alpine terminal (run_shell linux=true,
 * run_code, git; PRoot + BusyBox in jniLibs) stays, and Blaze Desktop pairing (the PC app) is untouched. The desktop's
 * saved settings and leftovers are cleaned once after the update ([LinuxDesktopCleanup]).
 */
class WaveB4NoLinuxTest {
    private fun file(rel: String): File = listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() } ?: File("app/$rel")
    private val main = file("src/main/java/com/blaze/assistant")
    private fun src(rel: String) = File(main, rel).readText()

    @Test fun desktopCodeIsGone() {
        assertFalse("the desktop package", File(main, "desktop").exists())
        listOf("ui/LinuxDesktopPage.kt", "ui/DesktopLiveCard.kt", "ui/DesktopTaskCard.kt", "ui/DesktopExtrasSection.kt", "ui/KeepDesktopRow.kt",
            "agent/DesktopRoute.kt").forEach { assertFalse(it, File(main, it).exists()) }
        // Blaze Desktop (the PC app) pairing stays.
        assertTrue(File(main, "link/DesktopLink.kt").exists()); assertTrue(File(main, "ui/DesktopPage.kt").exists())
        val all = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        for (f in all) {
            val t = f.readText()
            listOf("com.blaze.assistant.desktop.", "LinuxDesktop.", "DesktopRoute.", "DesktopAgent.", "desktop_task", "open_view linux").forEach {
                assertFalse("${f.name} still has $it", t.contains(it))
            }
        }
    }

    @Test fun noToolOrPromptOffersIt() {
        assertFalse("desktop_task" in ToolTruth.MUST)
        assertFalse(ToolTruth.RULE.contains("Linux desktop")); assertFalse(ToolTruth.NUDGE.contains("desktop_task"))
        assertFalse("desktop" in ToolVerify.PROBED); assertFalse(ToolVerify.PROMPT_RULE.contains("desktop action"))
        assertTrue(ToolRouter.groups.none { it.id == "desktop" || it.names("desktop_task") || it.names("desktop") })
        assertTrue(CommandPolicy.Kind.values().map { it.name } == listOf("SHELL", "GIT"))
        assertNull(ToolTruth.choiceFor("open example.com in the Linux browser", emptyList(), listOf("desktop_task", "browser_open", "open_view")))
        val agent = src("agent/Agent.kt")
        listOf("LINUX DESKTOP", "desktop action=repair", "vncNudged", "Desktop view", "port 5901").forEach { assertFalse(it, agent.contains(it)) }
        Awareness.reset()
        val r = Awareness.report(Views.all.map { Triple(it.name, it.id, it.desc) })
        assertFalse(r.contains("desktop_task")); assertFalse(r.contains("Linux desktop"))
    }

    @Test fun noScreenChipOrCommandOffersIt() {
        assertTrue(Views.all.none { it.id == "linux" })
        listOf("linux", "xfce", "vnc", "linux desktop", "view desktop").forEach { assertNull(it, Views.find(it)) }
        assertTrue(Views.find("desktop") != null)   // Blaze Desktop pairing
        assertFalse(Views.offerLabel("linux").contains("View desktop"))
        assertTrue(Commands.all().none { it.name == "linux" })
        assertTrue(com.blaze.assistant.ui.settingsPages.none { it.id == "linux" })
        // An old "linux" page id (a saved link, /linux) lands on the Terminal instead of a blank page.
        assertEquals("terminal", com.blaze.assistant.agent.settingsPageFor("linux"))
        listOf("ui/Shell.kt", "ui/ChatScreen.kt", "ui/SettingsSheet.kt", "ui/SettingsV18.kt").forEach { f ->
            val t = src(f)
            assertFalse(f, t.contains("PlusItem(\"linux\"")); assertFalse(f, t.contains("LinuxDesktopPage")); assertFalse(f, t.contains("KeepDesktopRow"))
        }
    }

    @Test fun blazeOsHasNoLinuxApps() {
        assertFalse("linux" in OsApps.builtinKeys)
        assertFalse("linux" in OsManifest.types)
        assertTrue(OsManifest.parse("""{"id":"x.eyes","name":"Eyes","entry":{"type":"linux","command":"xeyes"}}""") is OsManifest.Parsed.Bad)
        val cat = OsManifest.catalog(file("src/main/assets/blazeos/catalog.json").readText())
        assertTrue(cat.errors.toString(), cat.errors.isEmpty())
        assertFalse(file("src/main/assets/blazeos/catalog.json").readText().contains("\"linux\""))
    }

    @Test fun manifestDropsDesktopServicesAndPermission() {
        val m = file("src/main/AndroidManifest.xml").readText()
        assertFalse(m.contains("DesktopKeeper")); assertFalse(m.contains("DesktopAliveService"))
        assertFalse(m.contains("FOREGROUND_SERVICE_SPECIAL_USE")); assertFalse(m.contains("specialUse"))
        // The terminal's own job service stays.
        assertTrue(m.contains(".terminal.JobKeeper"))
    }

    @Test fun terminalStaysWithItsBinaries() {
        val names = com.blaze.assistant.terminal.Terminal.shellTools().map { it.getJSONObject("function").getString("name") }
        assertTrue(names.toString(), "run_shell" in names)
        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            assertTrue("$abi busybox", file("src/main/jniLibs/$abi/libbusybox.so").exists())
            assertTrue("$abi proot", file("src/main/jniLibs/$abi/libproot.so").exists())
        }
        assertEquals("alpine", TermLogic.defaultDistro(emptySet()))
        assertEquals("alpine", TermLogic.defaultDistro(setOf("alpine", "ubuntu")))
        assertEquals("ubuntu", TermLogic.defaultDistro(setOf("ubuntu")))
        assertEquals("apt install <package>", TermLogic.installHint("debian"))
        assertEquals("apk add <package>", TermLogic.installHint("alpine"))
        assertEquals("pacman -S <package>", TermLogic.installHint("archlinux"))
        assertEquals("dnf install <package>", TermLogic.installHint("fedora"))
    }

    @Test fun oldSettingsAreDroppedAndLeftoversFreed() {
        listOf("linux_desktop_on", "linux_desktop_mirror", "linux_desktop_distro", "linux_desktop_paused", "linux_desktop_display",
            "linux_desktop_autorestart", "linux_desktop_keep_running", "linux_desktop_keep_asked_battery").forEach {
            assertTrue(it, it in LinuxDesktopCleanup.LEGACY_PREFS)
        }
        // Nothing in the app still reads one of them.
        val all = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "LinuxDesktopCleanup.kt" }.map { it.readText() }.joinToString("\n")
        LinuxDesktopCleanup.LEGACY_PREFS.forEach { assertFalse(it, all.contains("\"$it\"")) }
        for (d in listOf("alpine", "ubuntu", "debian", "archlinux", "manjaro", "fedora")) {
            val s = LinuxDesktopCleanup.removeScript(d)
            assertTrue(d, s.contains("xfwm4") && s.contains("xdotool") && s.endsWith("echo BLAZE_DESKTOP_REMOVED"))
            assertTrue(d, s.contains("rm -f /" + LinuxDesktopCleanup.MARK))
            // Packages the terminal / run_code still use stay.
            assertFalse(d, Regex("""\bimagemagick\b|\bImageMagick\b|font|procps""").containsMatchIn(s))
            assertTrue(d, LinuxDesktopCleanup.MARK in LinuxDesktopCleanup.leftovers(d))
        }
        assertTrue(LinuxDesktopCleanup.removeScript("alpine").contains("apk del"))
        assertTrue(LinuxDesktopCleanup.removeScript("ubuntu").contains("apt-get -y -q purge"))
        assertTrue(LinuxDesktopCleanup.removeScript("archlinux").contains("pacman -Rns"))
        assertTrue(LinuxDesktopCleanup.removeScript("fedora").contains("dnf -y -q remove"))
        assertTrue(src("BlazeApp.kt").contains("LinuxDesktopCleanup.runOnce"))
    }
}
