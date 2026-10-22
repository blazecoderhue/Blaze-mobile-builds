package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FixCardsTest {
    @Test fun matchesMissingAccess() {
        assertEquals(FixCards.Kind.NOTIFICATIONS, FixCards.kindOf("Notification access is off. Ask the user to turn it on in Blaze › Settings."))
        assertEquals(FixCards.Kind.PHONE_CONTROL, FixCards.kindOf("Phone control is off, so I can't take a screenshot."))
        assertEquals(FixCards.Kind.USAGE, FixCards.kindOf("Blaze needs Usage access: I opened that page."))
        assertEquals(FixCards.Kind.APP_PERMISSION, FixCards.kindOf("Error: permission denied for contacts"))
        assertNull(FixCards.kindOf("Opened WhatsApp."))
    }

    @Test fun plainHeadlinesAndButton() {
        assertEquals("I couldn't read WhatsApp", FixCards.headline("whatsapp_read", FixCards.Kind.NOTIFICATIONS))
        assertEquals("I couldn't read your messages", FixCards.headline("read_notifications", FixCards.Kind.NOTIFICATIONS))
        assertEquals("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", FixCards.Kind.NOTIFICATIONS.action)
    }
}
