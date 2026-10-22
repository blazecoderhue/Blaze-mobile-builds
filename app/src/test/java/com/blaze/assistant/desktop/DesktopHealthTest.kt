package com.blaze.assistant.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** v0.43.0: the Linux desktop counts as running only when its port answers; scripts, retries and the plain words around it. */
class DesktopHealthTest {
    @Test fun bannerCheck() {
        assertTrue(DesktopHealth.isRfbBanner("RFB 003.008\n".toByteArray()))
        assertTrue(DesktopHealth.isRfbBanner("RFB 003.003\n".toByteArray()))
        assertFalse(DesktopHealth.isRfbBanner("HTTP/1.1 200".toByteArray()))
        assertFalse(DesktopHealth.isRfbBanner("RFB 0".toByteArray()))
        assertFalse(DesktopHealth.isRfbBanner(ByteArray(0)))
    }

    @Test fun probeTalksToARealSocket() {
        // Nothing listening: refused (what the phone hit: ECONNREFUSED on 5901).
        val free = ServerSocket(0).use { it.localPort }
        assertEquals(DesktopHealth.Probe.REFUSED, DesktopHealth.probe(free, 500, 500))
        // A VNC server greeting: up.
        ServerSocket(0).use { ss ->
            val t = Thread { ss.accept().use { it.getOutputStream().write("RFB 003.008\n".toByteArray()) } }.apply { start() }
            assertEquals(DesktopHealth.Probe.UP, DesktopHealth.probe(ss.localPort, 1000, 1000)); t.join(2000)
        }
        // Something else on the port: not VNC.
        ServerSocket(0).use { ss ->
            val t = Thread { ss.accept().use { it.getOutputStream().write("SSH-2.0-OpenSSH\r\n".toByteArray()) } }.apply { start() }
            assertEquals(DesktopHealth.Probe.NOT_VNC, DesktopHealth.probe(ss.localPort, 1000, 1000)); t.join(2000)
        }
    }

    private class Clock { var t = 0L }

    @Test fun waitsUntilThePortAnswers() = runBlocking {
        val c = Clock(); var n = 0
        val w = DesktopHealth.waitUntilUp(45_000, 500, check = { n++; if (n < 5) DesktopHealth.Probe.REFUSED else DesktopHealth.Probe.UP },
            now = { c.t }, sleep = { c.t += it })
        assertTrue(w.ok); assertEquals(5, w.attempts); assertEquals(2000, w.waitedMs)
    }

    @Test fun givesUpAtTheTimeout() = runBlocking {
        val c = Clock()
        val w = DesktopHealth.waitUntilUp(10_000, 500, check = { DesktopHealth.Probe.REFUSED }, now = { c.t }, sleep = { c.t += it })
        assertFalse(w.ok); assertFalse(w.gaveUpEarly); assertTrue(w.waitedMs <= 10_000); assertEquals(DesktopHealth.Probe.REFUSED, w.last)
    }

    @Test fun stopsEarlyWhenTheStartJobDied() = runBlocking {
        val c = Clock(); var checks = 0
        val w = DesktopHealth.waitUntilUp(45_000, 500, check = { checks++; DesktopHealth.Probe.REFUSED }, stop = { c.t >= 1500 }, now = { c.t }, sleep = { c.t += it })
        assertFalse(w.ok); assertTrue(w.gaveUpEarly); assertTrue(c.t < 3000)
    }

    @Test fun viewerRetryRule() {
        assertEquals(500L, DesktopHealth.retryDelay(0, 0))
        assertEquals(2500L, DesktopHealth.retryDelay(9, 1000))
        assertEquals(200L, DesktopHealth.retryDelay(4, 29_800))
        assertNull(DesktopHealth.retryDelay(1, 30_000))
        val raw = "failed to connect to /127.0.0.1 (port 5901) from /127.0.0.1 (port 47034) after 5000ms: isConnected failed: ECONNREFUSED (Connection refused)"
        assertTrue(DesktopHealth.retryable(raw))
        assertFalse(DesktopHealth.retryable("Wrong desktop password."))
    }

    @Test fun plainWordsInsteadOfECONNREFUSED() {
        val raw = "failed to connect to /127.0.0.1 (port 5901) from /127.0.0.1 (port 47034) after 5000ms: isConnected failed: ECONNREFUSED (Connection refused)"
        val p = DesktopHealth.plainError(raw)
        assertEquals(DesktopHealth.NOT_RUNNING, p)
        listOf("ECONNREFUSED", "5901", "127.0.0.1", "isConnected").forEach { assertFalse(it, p.contains(it)) }
        assertEquals(DesktopHealth.AUTH, DesktopHealth.plainError("Wrong desktop password."))
        assertEquals("", DesktopHealth.plainError(""))
    }

    @Test fun logTailAndDiagnosis() {
        val out = "exit 0 · cwd /root\n--- xvnc\n\nXvnc TigerVNC 1.13.1 - built Apr 11 2024\nCopyright (C) 1999-2022 TigerVNC Team\nSee https://www.tigervnc.org\n" +
            "Underlying X server release 12101007\n vncext:      VNC extension running!\n(EE) \nFatal server error:\n(EE) vncExtInit: failed to bind socket: Address in use (98)\n(EE) \n--- xfce\n"
        val t = DesktopHealth.logTail(out)
        assertEquals(listOf("vncext:      VNC extension running!", "Fatal server error:", "(EE) vncExtInit: failed to bind socket: Address in use (98)"), t)
        assertTrue(DesktopHealth.diagnose(t.joinToString("\n"))!!.contains("5901"))
        assertNotNull(DesktopHealth.diagnose("BLAZE_DESKTOP_FAILED: the desktop password file is missing"))
        assertTrue(DesktopHealth.diagnose("Fatal server error:\nServer is already active for display 1")!!.contains("lock"))
        assertNull(DesktopHealth.diagnose("all good"))
    }

    @Test fun agentResultsNeverClaimRunningOnFailure() {
        val f = DesktopHealth.failedResult(listOf("(EE) vncExtInit: failed to bind socket: Address in use (98)"))
        assertTrue(f.startsWith("FAILED:")); assertTrue(f.contains("Do not tell the user it is running")); assertTrue(f.contains("Address in use"))
        assertFalse(DesktopHealth.saysRunning(f))
        assertTrue(DesktopHealth.saysRunning(DesktopHealth.verifiedResult(3200)))
        assertTrue(DesktopHealth.verifiedResult(3200).contains("3.2 s"))
        assertTrue(DesktopHealth.failLine(emptyList()).contains("Repair"))
        assertTrue(DesktopHealth.failedResult(emptyList()).contains("empty"))
    }

    @Test fun startScriptKeepsXvncUpOnItsOwn() {
        val s = DesktopPlan.startScript()
        // Xvnc is waited on by the job's main shell; XFCE runs beside it; apps go through the spool launcher.
        assertTrue(s.contains("XPID=\$!")); assertTrue(s.contains("wait \$XPID"))
        assertFalse(s.contains("exec dbus-launch"))
        assertTrue(s.contains("dbus-launch --exit-with-session startxfce4"))
        assertTrue(s.contains(DesktopPlan.SPOOL)); assertTrue(s.contains(DesktopPlan.PID_FILE))
        assertTrue(s.contains("[ -s /root/.vnc/passwd ] ||")); assertTrue(s.contains(DesktopPlan.FAIL_MARK))
        assertTrue(s.contains("rm -f /tmp/.X1-lock /tmp/.X11-unix/X1"))
        assertTrue(s.indexOf("Xvnc :1") < s.indexOf(DesktopPlan.UP_MARK))
        assertTrue(s.contains("tail -n 15 /tmp/xvnc.log"))
    }

    @Test fun aliveAndStopNeverMatchThemselves() {
        // The old check matched its own command line ("pgrep -f 'Xvnc|Xtigervnc'"), so it always said "up".
        assertFalse(DesktopPlan.ALIVE_SCRIPT.contains("pgrep"))
        assertTrue(DesktopPlan.ALIVE_SCRIPT.contains("kill -0"))
        // Every pkill -f pattern is bracketed, so it can't match the script that runs it.
        val pats = Regex("pkill (?:-9 )?-f '([^']+)'").findAll(DesktopPlan.STOP_SCRIPT).map { it.groupValues[1] }.toList() +
            Regex("'(\\[[^']+)'").findAll(DesktopPlan.STOP_SCRIPT).map { it.groupValues[1] }.toList()
        assertTrue(pats.isNotEmpty())
        pats.forEach { p -> assertFalse("$p matches its own text", Regex(p).containsMatchIn(DesktopPlan.STOP_SCRIPT)) }
        assertTrue(Regex("[X]vnc").containsMatchIn("Xvnc :1 -geometry"))
        assertTrue(DesktopPlan.REPAIR_SCRIPT.contains("rm -f /tmp/.X1-lock")); assertTrue(DesktopPlan.REPAIR_SCRIPT.contains(DesktopPlan.SHIMS))
    }

    @Test fun appsGoThroughTheDesktopsLauncher() {
        val r = DesktopPlan.runCmd("browser")
        assertTrue(r.contains(DesktopPlan.SPOOL)); assertTrue(r.contains("'DISPLAY=:1 dillo'")); assertFalse(r.contains("setsid"))
        val evil = DesktopPlan.spoolCmd("x'; rm -rf /; echo '")
        assertTrue(evil.contains("'DISPLAY=:1 x'\\''; rm -rf /; echo '\\'''"))
    }
}
