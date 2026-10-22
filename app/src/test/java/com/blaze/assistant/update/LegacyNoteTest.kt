package com.blaze.assistant.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.45.18: Android 7/8 phones on the old key get one line to back up, uninstall and reinstall from the site. */
class LegacyNoteTest {
    private val old = setOf(UpdateKeys.BURNED_CERT)
    private val new = setOf(UpdateKeys.RELEASE_CERT)

    @Test fun oldKeyBelow28GetsTheNote() {
        assertEquals(UpdateKeys.LEGACY_NOTE, UpdateKeys.legacyNote(24, old))
        assertEquals(UpdateKeys.LEGACY_NOTE, UpdateKeys.legacyNote(27, old))
    }
    @Test fun nothingOnAndroid9Up() { assertNull(UpdateKeys.legacyNote(28, old)); assertNull(UpdateKeys.legacyNote(34, old)) }
    @Test fun nothingWhenAlreadyOnTheReleaseKey() { assertNull(UpdateKeys.legacyNote(26, new)); assertNull(UpdateKeys.legacyNote(26, setOf(UpdateKeys.RELEASE_CERT.uppercase()))) }
    @Test fun nothingWhenSignersUnreadable() = assertNull(UpdateKeys.legacyNote(26, emptySet()))
    @Test fun oneLineThatSaysWhatToDo() {
        val n = UpdateKeys.LEGACY_NOTE
        assert('\n' !in n); assert("back up" in n); assert("uninstall" in n); assert("blazeinchue.pages.dev" in n)
    }
}
