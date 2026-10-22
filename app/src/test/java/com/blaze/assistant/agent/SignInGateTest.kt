package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.4: a message sent while signed out shows a Sign in card, waits, and goes out once signed in. */
class SignInGateTest {
    @Test fun signedOutFailureGetsTheCard() {
        assertTrue(SignInGate.isSignedOut(401, Cloud.SIGNED_OUT))
        assertTrue(SignInGate.applies(401, Cloud.SIGNED_OUT, offlineBrainAnswers = false))
    }

    @Test fun otherFailuresStayErrors() {
        assertFalse(SignInGate.applies(401, Cloud.EXPIRED, false))
        assertFalse(SignInGate.applies(500, Cloud.SIGNED_OUT, false))
        assertFalse(SignInGate.applies(0, Cloud.UNREACHABLE, false))
        assertFalse(SignInGate.applies(401, null, false))
    }

    @Test fun anotherModelThatAnswersMeansNoCard() {
        assertFalse(SignInGate.applies(401, Cloud.SIGNED_OUT, offlineBrainAnswers = true))
    }

    @Test fun releasedOnlyWhenSignInCompletes() {
        assertTrue(SignInGate.release(wasSignedIn = false, signedIn = true, held = 1))
        assertFalse(SignInGate.release(false, true, 0))   // nothing held
        assertFalse(SignInGate.release(true, true, 2))    // app start with a saved session, or a refresh
        assertFalse(SignInGate.release(true, false, 1))   // signed out
        assertFalse(SignInGate.release(false, false, 1))
    }

    @Test fun eachHeldMessageGoesOnce() {
        val h = SignInGate.Holds()
        h.hold("main", 7); h.hold("p1", 3); h.hold("main", 9)   // a newer card in the same chat replaces the older
        assertEquals(2, h.size())
        assertEquals(listOf(SignInGate.Held("p1", 3), SignInGate.Held("main", 9)), h.takeAll())
        assertEquals(0, h.size())
        assertEquals(emptyList<SignInGate.Held>(), h.takeAll())
        h.hold("x", 1); h.drop("x"); assertEquals(0, h.size())
    }

    @Test fun sentOnlyWhileTheCardIsStillTheNewestMessage() {
        assertTrue(SignInGate.stillWaiting(9, SignInGate.CARD, 9, busy = false))
        assertFalse(SignInGate.stillWaiting(10, "", 9, false))          // the user sent something else meanwhile
        assertFalse(SignInGate.stillWaiting(9, SignInGate.CARD, 9, true)) // a turn is running (Retry tapped)
        assertFalse(SignInGate.stillWaiting(null, null, 9, false))       // chat cleared
        assertFalse(SignInGate.stillWaiting(9, "", 9, false))
    }

    @Test fun cardLineIsPlain() {
        assertEquals(SignInGate.LINE, PlainError.of(SignInGate.LINE))
        assertEquals("Sign in", SignInGate.BUTTON)
    }
}
