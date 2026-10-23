package com.blaze.kiln

import com.blaze.kiln.route.LaunchEnvelope
import com.blaze.kiln.route.LaunchToken
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/** A3 (security): a slot only accepts launch tokens issued with this install's key, for that exact target. */
class LaunchTokenTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun verifiesOnlyTheIssuedTarget() {
        val t = LaunchToken(key)
        val tok = t.issue("org.example.calc", "org.example.calc.Main", 0)
        assertTrue(t.verify(tok, "org.example.calc", "org.example.calc.Main", 0))
        assertFalse(t.verify(tok, "org.example.calc", "org.example.calc.Other", 0))
        assertFalse(t.verify(tok, "org.evil.app", "org.example.calc.Main", 0))
        assertFalse(t.verify(tok, "org.example.calc", "org.example.calc.Main", 1))
    }

    @Test
    fun otherKeyOrTamperingFails() {
        val tok = LaunchToken(key).issue("a.b", "a.b.C", 0)
        assertFalse(LaunchToken(ByteArray(32) { 7 }).verify(tok, "a.b", "a.b.C", 0))
        val flipped = tok.substring(0, tok.length - 1) + (if (tok.last() == '0') '1' else '0')
        assertFalse(LaunchToken(key).verify(flipped, "a.b", "a.b.C", 0))
        assertFalse(LaunchToken(key).verify("zz" + tok.substring(2), "a.b", "a.b.C", 0))
        assertFalse(LaunchToken(key).verify("", "a.b", "a.b.C", 0))
    }

    @Test
    fun noncesDiffer() {
        val t = LaunchToken(key)
        assertNotEquals(t.issue("a.b", "a.b.C", 0), t.issue("a.b", "a.b.C", 0))
    }

    @Test
    fun tokenFitsEnvelope() {
        val tok = LaunchToken(key).issue("a.b", "a.b.C", 0)
        assertEquals(tok.length, (LaunchToken.NONCE_BYTES + LaunchToken.MAC_BYTES) * 2)
        val env = LaunchEnvelope("a.b", "a.b.C", 0, "com.blaze.kiln.slot.A0_0_0", tok)
        assertNotNull(LaunchEnvelope.fromMap(env.toMap()))
    }

    @Test(expectedExceptions = [IllegalArgumentException::class])
    fun shortKeyRefused() { LaunchToken(ByteArray(8)) }
}
