package com.blaze.assistant.connect

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * v0.40.0 cloud sync, the crypto (pure JVM, unit-tested in SyncCryptoTest). End to end: the key comes from a passphrase
 * the user picks and never leaves the phone; the Blaze Gateway stores only what [seal] returns.
 *
 *  key   = PBKDF2-HMAC-SHA256(passphrase, salt, [ITERATIONS], 256 bits); the salt is random, kept on the gateway
 *  blob  = base64(nonce[12] || AES-256-GCM(gzip(plaintext), aad = "blaze-sync-v1|<kind>|<id>"))
 *  check = [seal] of [CHECK_TEXT] under kind "_check", id "v1": a second phone verifies the passphrase against it
 *
 * The AAD binds every blob to its kind and id, so the server can't swap one item's blob into another.
 */
object SyncCrypto {
    const val ITERATIONS = 600_000
    const val KDF = "pbkdf2-sha256:$ITERATIONS"
    private const val AAD = "blaze-sync-v1"
    private const val CHECK_KIND = "_check"
    private const val CHECK_ID = "v1"
    private const val CHECK_TEXT = "blaze-sync-check-v1"
    private val rng = SecureRandom()

    class BadPassphrase : Exception("That passphrase doesn't match the one used on your other phone.")

    fun newSalt(): String = b64(ByteArray(16).also { rng.nextBytes(it) })

    /** The iteration count a gateway "kdf" string names ("pbkdf2-sha256:600000"); [ITERATIONS] when it says nothing usable. */
    fun iterationsOf(kdf: String): Int {
        val p = kdf.trim().lowercase()
        if (!p.startsWith("pbkdf2-sha256")) return ITERATIONS
        return p.substringAfter(':', "").toIntOrNull()?.takeIf { it in 10_000..10_000_000 } ?: ITERATIONS
    }

    /** Slow on purpose (about a second on a phone): call it off the main thread. */
    fun deriveKey(passphrase: String, saltB64: String, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), unb64(saltB64), iterations, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    fun aad(kind: String, id: String): ByteArray = "$AAD|$kind|$id".toByteArray()

    fun seal(key: ByteArray, kind: String, id: String, plain: String): String {
        require(key.size == 32) { "bad key" }
        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad(kind, id))
        return b64(nonce + c.doFinal(gzip(plain.toByteArray())))
    }

    /** Throws when the key is wrong, the blob was changed, or it belongs to another kind / id. */
    fun open(key: ByteArray, kind: String, id: String, blob: String): String {
        val raw = unb64(blob)
        require(raw.size > 28) { "blob too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        c.updateAAD(aad(kind, id))
        return String(gunzip(c.doFinal(raw, 12, raw.size - 12)))
    }

    fun makeCheck(key: ByteArray): String = seal(key, CHECK_KIND, CHECK_ID, CHECK_TEXT)

    fun checks(key: ByteArray, check: String): Boolean = try { open(key, CHECK_KIND, CHECK_ID, check) == CHECK_TEXT } catch (_: Exception) { false }

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s.trim())

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
}
