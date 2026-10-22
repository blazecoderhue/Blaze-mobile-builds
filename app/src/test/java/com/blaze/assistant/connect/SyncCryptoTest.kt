package com.blaze.assistant.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncCryptoTest {
    // Tests use a low iteration count; the app uses SyncCrypto.ITERATIONS (600 000).
    private val salt = SyncCrypto.newSalt()
    private val key = SyncCrypto.deriveKey("correct horse battery", salt, 1_000)

    @Test fun roundTrip() {
        val text = """{"v":1,"data":{"text":"Hello, Mutale. Ünïcødé ✓"}}""" + "x".repeat(5000)
        val blob = SyncCrypto.seal(key, "chat", "main", text)
        assertEquals(text, SyncCrypto.open(key, "chat", "main", blob))
        // gzip inside: a repetitive chat is much smaller than its text.
        assertTrue(blob.length < text.length)
        // A fresh nonce every time.
        assertNotEquals(blob, SyncCrypto.seal(key, "chat", "main", text))
    }

    @Test fun aadBindsKindAndId() {
        val blob = SyncCrypto.seal(key, "chat", "main", "secret")
        for ((k, id) in listOf("chat" to "other", "memory" to "main", "design" to "d.main")) {
            try { SyncCrypto.open(key, k, id, blob); fail("opened under $k/$id") } catch (_: Exception) { }
        }
    }

    @Test fun tamperedBlobFails() {
        val raw = SyncCrypto.unb64(SyncCrypto.seal(key, "chat", "c1", "hello"))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        try { SyncCrypto.open(key, "chat", "c1", SyncCrypto.b64(raw)); fail("tampered blob opened") } catch (_: Exception) { }
    }

    @Test fun keyDependsOnPassphraseSaltAndIterations() {
        assertTrue(SyncCrypto.deriveKey("correct horse battery", salt, 1_000).contentEquals(key))
        assertFalse(SyncCrypto.deriveKey("correct horse batterY", salt, 1_000).contentEquals(key))
        assertFalse(SyncCrypto.deriveKey("correct horse battery", SyncCrypto.newSalt(), 1_000).contentEquals(key))
        assertFalse(SyncCrypto.deriveKey("correct horse battery", salt, 1_001).contentEquals(key))
        assertEquals(32, key.size)
    }

    @Test fun passphraseCheck() {
        val check = SyncCrypto.makeCheck(key)
        assertTrue(SyncCrypto.checks(key, check))
        assertFalse(SyncCrypto.checks(SyncCrypto.deriveKey("wrong passphrase", salt, 1_000), check))
        assertFalse(SyncCrypto.checks(key, "not-base64!!"))
        // The check blob is bound to its own slot: it can't be passed off as an item.
        try { SyncCrypto.open(key, "chat", "v1", check); fail() } catch (_: Exception) { }
    }

    @Test fun kdfStrings() {
        assertEquals(600_000, SyncCrypto.ITERATIONS)
        assertEquals("pbkdf2-sha256:600000", SyncCrypto.KDF)
        assertEquals(310_000, SyncCrypto.iterationsOf("pbkdf2-sha256:310000"))
        assertEquals(SyncCrypto.ITERATIONS, SyncCrypto.iterationsOf(""))
        assertEquals(SyncCrypto.ITERATIONS, SyncCrypto.iterationsOf("pbkdf2-sha256:5"))
        assertEquals(SyncCrypto.ITERATIONS, SyncCrypto.iterationsOf("scrypt:1"))
        assertTrue(SyncCrypto.unb64(SyncCrypto.newSalt()).size == 16)
    }
}
