package com.blaze.assistant.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.45.20 item 15: phone control asked just in time (an in-chat card with the reason), not in setup. The wait is driven
 * by fake probes on a fake clock; each flow 5 runs, strict 5/5.
 */
class PermissionGateTest {
    private val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private fun src(p: String) = File(root, "com/blaze/assistant/$p").readText()

    private fun flow(grantAfterPolls: Int?, answer: Boolean?, waitMs: Long = 10_000): PermissionGate.Outcome = runBlocking {
        var t = 0L; var polls = 0
        val d = CompletableDeferred<Boolean>()
        if (answer != null) d.complete(answer)
        PermissionGate.await({ polls++; grantAfterPolls != null && polls > grantAfterPolls }, d, waitMs, pollMs = 1, now = { t.also { t += 600 } })
    }

    @Test fun grantedGoesOn_5of5() {
        val r = (0 until 5).map { flow(grantAfterPolls = it * 2, answer = true) }
        assertEquals(r.toString(), 5, r.count { it == PermissionGate.Outcome.GRANTED })
    }

    @Test fun notNowEndsHonestly_5of5() {
        val r = (0 until 5).map { flow(grantAfterPolls = null, answer = false) }
        assertEquals(5, r.count { it == PermissionGate.Outcome.DECLINED })
        val msg = PermissionGate.notDone("tap", PermissionGate.Outcome.DECLINED)
        assertTrue(msg.startsWith("Not done:") && "nothing was tapped" in msg && "Don't retry" in msg)
    }

    @Test fun noSwitchTimesOut_5of5() {
        val r = (0 until 5).map { flow(grantAfterPolls = null, answer = true, waitMs = 3_000L + it * 600) }
        assertEquals(5, r.count { it == PermissionGate.Outcome.TIMED_OUT })
        assertTrue("Not done" in PermissionGate.notDone("read_screen", PermissionGate.Outcome.TIMED_OUT))
    }

    @Test fun asksOnlyWhenNeeded() {
        assertTrue(PermissionGate.shouldAsk("tap", serviceOn = false, phoneControlAllowed = true))
        assertTrue(PermissionGate.shouldAsk("read_screen", false, true))
        assertFalse("already on", PermissionGate.shouldAsk("tap", true, true))
        assertFalse("the user turned phone control off in Blaze", PermissionGate.shouldAsk("tap", false, false))
        listOf("web_search", "set_alarm", "add_calendar_event", "phone_open", "memory_save").forEach { assertFalse(it, PermissionGate.shouldAsk(it, false, true)) }
        assertTrue(PermissionGate.REASON.isNotBlank() && "Accessibility" in PermissionGate.REASON)
    }

    @Test fun setupNoLongerAsksAndTheToolPathDoes() {
        assertTrue(com.blaze.assistant.setup.SetupGate.deps.none { it.id == "accessibility" })
        assertFalse(src("ui/Setup.kt").contains("Need(\"accessibility\""))
        val a = src("agent/Agent.kt")
        assertTrue(a.contains("PermissionGate.shouldAsk(name, com.blaze.assistant.phone.BlazeAccessibilityService.instance != null, Settings.phoneControl)"))
        assertTrue(a.contains("confirm(PermissionGate.TITLE, PermissionGate.REASON, PermissionGate.ACTION, always = true)"))
        // Music no longer asks for notifications on open; a download asks when it starts.
        assertTrue(src("mediaapps/MediaUi.kt").contains("StoragePermission.askNotifications()"))
        assertTrue(src("mediaapps/MusicActivity.kt").contains("StoragePermission.notifAsker = {"))
    }
}
