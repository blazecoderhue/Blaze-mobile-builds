package com.blaze.assistant.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.45.23: the dependency list now only feeds Settings › Permissions & setup; nothing gates the app at launch. */
class SetupGateTest {
    @Test fun everyDependencyStaysOnTheSettingsPage() {
        assertEquals(listOf("notifications", "linux", "mic", "overlay", "battery", "desktop"), SetupGate.deps.map { it.id })
        SetupGate.deps.forEach { assertNotNull(SetupGate.dep(it.id)); assertTrue(it.why.isNotBlank()) }
    }

    @Test fun theDonePrefKeyIsUnchangedForTheBackup() {
        // 0.45.22 users who finished the old gate keep "setup_deps_done"; BlazeApp's one-time backup still reads it.
        assertEquals("setup_deps_done", SetupGate.PREF_DONE)
        assertEquals("setup_backup_v", SetupGate.PREF_BACKUP)
    }
}
