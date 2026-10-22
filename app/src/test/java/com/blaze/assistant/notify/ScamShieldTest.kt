package com.blaze.assistant.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.33.0 Scam Shield. */
class ScamShieldTest {
    @Test fun classicScams() {
        assertTrue(ScamShield.score("Hello, I have sent K500 to your number by mistake, please send it back to 0977123456").scam)
        assertTrue(ScamShield.score("Congratulations! You have won K10,000 in the MTN promotion. Dial *115*5# to claim").scam)
        assertTrue(ScamShield.score("Your Airtel Money account is blocked. Verify now: http://airtel-verify.co/login").scam)
        assertTrue(ScamShield.score("Work from home and earn K800 daily, registration fee K150 only").scam)
    }

    @Test fun normalMessages() {
        assertFalse(ScamShield.score("Are we still meeting at 3? I'll bring the notes").scam)
        assertFalse(ScamShield.score("You have received K200 from JOHN BANDA. New balance K450.").scam)
        assertFalse(ScamShield.score("Your OTP is 445566").scam)
    }
}
