package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.45.2 regression: "open the browser in it" with the Linux desktop running ran `apk add firefox` with run_shell and
 * earlier sent the user to a VNC client, because desktop / desktop_task were in no tool group ("more", sent only on
 * request). Desktop requests now turn on the desktop group, keep both tools past the cap and steer to desktop_task.
 */
class DesktopRouteTest {
    private fun tool(name: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", "does $name"))
    private fun names(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") }

    @Test fun theDesktopToolsHaveAGroup() {
        assertEquals("desktop", ToolRouter.groupOf("desktop_task"))
        assertEquals("desktop", ToolRouter.groupOf("desktop"))
    }

    @Test fun theReportedRequestRoutesToTheDesktopAgent() {
        // The phone's case: desktop running, "open the browser in it".
        assertTrue(DesktopRoute.wants("open the browser in it", desktopRunning = true, recentDesktop = false))
        assertTrue(DesktopRoute.wants("can you open firefox there", desktopRunning = false, recentDesktop = true))
        assertTrue(DesktopRoute.wants("open the browser on the linux desktop", desktopRunning = false, recentDesktop = false))
        assertTrue(DesktopRoute.wants("go to wikipedia in the desktop", false, false))
        assertTrue(DesktopRoute.wants("show me the desktop", false, false))
        // Phone requests stay phone requests.
        assertFalse(DesktopRoute.wants("open the browser", desktopRunning = true, recentDesktop = true))
        assertFalse(DesktopRoute.wants("open whatsapp on my phone", true, true))
        assertFalse(DesktopRoute.wants("open the browser in it", desktopRunning = false, recentDesktop = false))
        assertFalse(DesktopRoute.wants("what's the weather", true, true))
    }

    @Test fun recentDesktopTalkCounts() {
        assertTrue(DesktopRoute.mentioned("The Linux desktop is running."))
        assertFalse(DesktopRoute.mentioned("Opened WhatsApp."))
    }

    @Test fun guessTurnsOnTheGroupForRequestsThatNameIt() {
        assertTrue("desktop" in ToolRouter.guess("open firefox on the linux desktop"))
        assertTrue("desktop" in ToolRouter.guess("click the terminal in xfce"))
    }

    @Test fun groupSelectionSendsBothDesktopTools() {
        val all = JSONArray().apply { listOf("run_shell", "desktop", "desktop_task", "open_app", "browser_open").forEach { put(tool(it)) } }
        val on = names(ToolRouter.selectDefault(all, setOf("desktop", "terminal")))
        assertTrue(on.containsAll(listOf("desktop", "desktop_task")))
        val off = names(ToolRouter.selectDefault(all, setOf("terminal")))
        assertFalse(off.contains("desktop_task"))   // without the route they stay out (the old bug's shape)
    }

    @Test fun toolPickKeepsTheDesktopAgentPastTheCap() {
        val many = (1..80).map { "extra_tool_$it" } + listOf("desktop", "desktop_task")
        val sel = JSONArray().apply { many.forEach { put(tool(it)) } }
        val out = names(ToolPick.pick(sel, "open the browser in it", emptySet(), setOf("desktop"), max = 20, must = DesktopRoute.TOOLS))
        assertTrue(out.containsAll(listOf("desktop", "desktop_task")))
        assertTrue(out.size <= 20)
    }

    @Test fun simpleModeDoesNotDropTheDesktopGroup() {
        assertTrue(PromptProfile.simpleGroupOk(DesktopRoute.GROUP, "open the browser in it"))
    }

    @Test fun hintSteersToDesktopTaskAndNeverVnc() {
        assertTrue(DesktopRoute.HINT.contains("desktop_task"))
        assertTrue(DesktopRoute.HINT.contains("Never tell the user to use a VNC client"))
        assertTrue(DesktopRoute.HINT.contains("open_view linux"))
    }

    @Test fun vncAdviceIsCaught() {
        assertTrue(DesktopRoute.vncAdvice("Connect with a VNC client at localhost:5901 to see it."))
        assertTrue(DesktopRoute.vncAdvice("Use a VNC viewer app."))
        assertTrue(DesktopRoute.vncAdvice("open 127.0.0.1:5901"))
        assertFalse(DesktopRoute.vncAdvice("The desktop is running. Tap View desktop to watch it."))
        assertFalse(DesktopRoute.NO_VNC_LINE.let { DesktopRoute.vncAdvice(it) })
    }

    @Test fun agentWiresTheRoute() {
        val src = java.io.File("src/main/java/com/blaze/assistant/agent/Agent.kt").readText()
        assertTrue(src.contains("DesktopRoute.wants(userNow"))
        assertTrue(src.contains("must = (if (DesktopRoute.GROUP in groupsOn) DesktopRoute.TOOLS else emptySet())"))
        assertTrue(src.contains("DesktopRoute.HINT"))
        assertTrue(src.contains("!desk0 && FastActions.plainDevice"))
    }

    @Test fun viewDesktopIsDiscoverable() {
        val v = java.io.File("src/main/java/com/blaze/assistant/ui/Views.kt").readText()
        assertTrue(v.contains("\"view desktop\""))
        assertTrue(v.contains("if (id == \"linux\") \"View desktop\""))
    }
}
