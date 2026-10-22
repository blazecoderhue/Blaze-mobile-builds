package com.blaze.assistant.desktop

import com.blaze.assistant.desktop.DesktopPlan.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.2 Keep desktop running: when the service runs, the wake lock, the battery ask and restarting a killed desktop. */
class DesktopAliveTest {
    @Test fun offByDefault() { assertFalse(KeepAlive.DEFAULT) }

    @Test fun serviceRunsOnlyWithTheSettingAndAWantedDesktop() {
        assertTrue(KeepAlive.serviceWanted(true, Phase.RUNNING, desktopOn = true))
        assertTrue(KeepAlive.serviceWanted(true, Phase.STARTING, desktopOn = true))
        assertTrue(KeepAlive.serviceWanted(true, Phase.KILLED, desktopOn = true))     // stays up to restart / show the note
        assertFalse(KeepAlive.serviceWanted(false, Phase.RUNNING, desktopOn = true))
        assertFalse(KeepAlive.serviceWanted(true, Phase.RUNNING, desktopOn = false))  // stopped by the user
        assertFalse(KeepAlive.serviceWanted(true, Phase.STOPPED, desktopOn = true))
        assertFalse(KeepAlive.serviceWanted(true, Phase.INSTALLING, desktopOn = true)) // the download has its own service
    }

    @Test fun wakeLockOnlyWhileItRuns() {
        assertTrue(KeepAlive.wakeLockWanted(true, Phase.RUNNING))
        assertFalse(KeepAlive.wakeLockWanted(true, Phase.STARTING))
        assertFalse(KeepAlive.wakeLockWanted(false, Phase.RUNNING))
    }

    @Test fun batteryExemptionAskedOnceWhenTurnedOn() {
        assertTrue(KeepAlive.askBattery(turningOn = true, askedBefore = false, alreadyExempt = false))
        assertFalse(KeepAlive.askBattery(turningOn = true, askedBefore = true, alreadyExempt = false))
        assertFalse(KeepAlive.askBattery(turningOn = true, askedBefore = false, alreadyExempt = true))
        assertFalse(KeepAlive.askBattery(turningOn = false, askedBefore = false, alreadyExempt = false))
    }

    @Test fun killedDesktopIsRepairedWithACap() {
        val now = 1_000_000_000L
        assertTrue(KeepAlive.shouldRepair(keepOn = true, autoRestart = false, desktopOn = true, userStopped = false, alive = false, recent = emptyList(), now = now))
        assertFalse(KeepAlive.shouldRepair(true, false, true, userStopped = true, alive = false, recent = emptyList(), now = now))
        assertFalse(KeepAlive.shouldRepair(true, false, true, false, alive = true, recent = emptyList(), now = now))
        assertFalse(KeepAlive.shouldRepair(false, false, true, false, false, emptyList(), now))          // neither setting on
        assertTrue(KeepAlive.shouldRepair(false, true, true, false, false, emptyList(), now))            // the older Restart by itself
        val three = listOf(now - 60_000, now - 120_000, now - 180_000)
        assertFalse(KeepAlive.shouldRepair(true, true, true, false, false, three, now))                  // no restart loop
        assertTrue(KeepAlive.shouldRepair(true, true, true, false, false, three.map { it - 20 * 60_000 }, now))
    }

    @Test fun processDeathRestoresOnlyAWantedDesktop() {
        assertTrue(KeepAlive.restoreAfterProcessDeath(true, desktopOn = true, alive = false))
        assertFalse(KeepAlive.restoreAfterProcessDeath(true, desktopOn = false, alive = false))
        assertFalse(KeepAlive.restoreAfterProcessDeath(false, desktopOn = true, alive = false))
        assertFalse(KeepAlive.restoreAfterProcessDeath(true, desktopOn = true, alive = true))
    }

    @Test fun honestCopy() {
        assertTrue(KeepAlive.CAVEAT.contains("Android 12"))
        assertTrue(KeepAlive.killedNote(true).contains("Keep desktop running is on"))
        assertEquals(DesktopPlan.KILLED_NOTE, KeepAlive.killedNote(false))
    }

    @Test fun manifestDeclaresTheServiceTypeAndPermissions() {
        val m = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(m.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertTrue(m.contains("android.permission.WAKE_LOCK"))
        val svc = m.substringAfter(".desktop.DesktopAliveService").substringBefore("</service>")
        assertTrue(svc.contains("foregroundServiceType=\"specialUse\""))
        assertTrue(svc.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
    }
}
