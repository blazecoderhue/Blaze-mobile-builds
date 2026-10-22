package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.26.2: phone-state questions are answered on the phone from its own readings, with no model call or tool loop. */
class PhoneStateTest {
    @Test fun jacksQuestionsAreRingerQuestions() {
        assertEquals(PhoneState.Kind.RINGER, PhoneState.kind("what sound profile is my phone set to?"))
        assertEquals(PhoneState.Kind.RINGER, PhoneState.kind("am I on mute in terms of device sound profile"))
        assertEquals(PhoneState.Kind.RINGER, PhoneState.kind("Is my phone on silent?"))
        assertEquals(PhoneState.Kind.RINGER, PhoneState.kind("is do not disturb on"))
        assertEquals(PhoneState.Kind.BATTERY, PhoneState.kind("how's my battery?"))
        assertEquals(PhoneState.Kind.NETWORK, PhoneState.kind("am I on wifi?"))
    }

    @Test fun actionsAndTasksAreNotStateQuestions() {
        assertNull(PhoneState.kind("mute my phone"))
        assertNull(PhoneState.kind("turn on do not disturb"))
        assertNull(PhoneState.kind("set my phone to vibrate"))
        assertNull(PhoneState.kind("why is my phone on silent when I get WhatsApp messages and how do I fix it in settings"))
        assertNull(PhoneState.kind("what's the weather"))
    }

    @Test fun answersComeStraightFromTheReadings() {
        assertEquals("You're not on mute: the ringer is on normal, so calls and notifications ring.", PhoneState.answer(PhoneState.Kind.RINGER, "normal", false, 80, false, "Wi-Fi"))
        assertTrue(PhoneState.answer(PhoneState.Kind.RINGER, "vibrate", false, 80, false, "").startsWith("Your phone is on vibrate"))
        assertTrue(PhoneState.answer(PhoneState.Kind.RINGER, "silent", true, 80, false, "").contains("Do Not Disturb is on"))
        assertTrue(PhoneState.answer(PhoneState.Kind.RINGER, "normal", true, 80, false, "").contains("Do Not Disturb is on"))
        assertEquals("54% and charging.", PhoneState.answer(PhoneState.Kind.BATTERY, "normal", false, 54, true, ""))
        assertTrue(PhoneState.RULE.contains("No tools"))
    }
}
