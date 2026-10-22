package com.blaze.assistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatchTest {
    private val juno = "Juno: New Origins Complete Edition"

    /** The real failure: "open it for me" about a gallery picture opened a game. */
    @Test fun openItForMeNeverOpensAGame() {
        assertTrue(AppMatch.refersBack("open it for me"))
        assertEquals("", AppMatch.clean("it for me"))
        assertEquals("", AppMatch.clean("it"))
        assertNull(AppMatch.score("it", juno, "com.floatingorigin.juno"))
        assertNull(AppMatch.score("it for me", juno, "com.floatingorigin.juno"))
        assertNull(AppMatch.score("on", juno, "x"))
    }

    @Test fun referencesGoToTheFullAgent() {
        for (t in listOf("open it", "open that", "show me the picture", "send this to mum", "open the file", "do it again", "open that one"))
            assertTrue(t, AppMatch.refersBack(t))
        for (t in listOf("open whatsapp", "open youtube", "launch the camera", "flashlight on", "open settings"))
            assertFalse(t, AppMatch.refersBack(t))
    }

    @Test fun realAppsStillMatch() {
        assertEquals(0, AppMatch.score("whatsapp", "WhatsApp", "com.whatsapp"))
        assertEquals(0, AppMatch.score("whatsapp for me", "WhatsApp", "com.whatsapp"))
        assertEquals(1, AppMatch.score("juno", juno, "x"))
        assertEquals(2, AppMatch.score("new origins", juno, "x"))
        assertEquals(1, AppMatch.score("google", "Google Maps", "com.google.android.apps.maps"))
        assertEquals(3, AppMatch.score("whatsapp business", "WhatsApp", "com.whatsapp"))
        assertEquals(4, AppMatch.score("whatsapp", "WA Messenger", "com.whatsapp"))
    }

    /** Whole words only: a short ask never matches the inside of a word. */
    @Test fun noMidWordMatches() {
        assertNull(AppMatch.score("edit", juno, "x"))
        assertNull(AppMatch.score("cam", "Instagram", "com.instagram.android"))
        assertNull(AppMatch.score("tube", "YouTube", "com.google.android.youtube"))
        assertNull(AppMatch.score("me", "Messages", "com.samsung.android.messaging"))
    }
}
