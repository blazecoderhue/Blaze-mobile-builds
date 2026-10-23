package com.blaze.kiln.route

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Launch tokens (ENGINE_DESIGN.md 1.3): the Coordinator (main process) issues them and a slot (a :gN process) checks
// them, so the check must work across processes of the same uid without IPC. Token = nonce + HMAC-SHA256 over the
// launch target with a per-install key kept in Blaze's private storage. A slot refuses an envelope whose token does not
// verify, so another app cannot aim a crafted intent at a slot to make it load arbitrary code. Pure JVM (javax.crypto).

class LaunchToken(private val key: ByteArray) {
    init { require(key.size >= 32) { "key too short" } }

    fun issue(guestPackage: String, guestClass: String, profile: Int, nonce: ByteArray = randomNonce()): String {
        require(nonce.size == NONCE_BYTES)
        return hex(nonce) + hex(mac(nonce, guestPackage, guestClass, profile))
    }

    fun verify(token: String, guestPackage: String, guestClass: String, profile: Int): Boolean {
        if (token.length != (NONCE_BYTES + MAC_BYTES) * 2) return false
        val raw = unhex(token) ?: return false
        val nonce = raw.copyOfRange(0, NONCE_BYTES)
        val got = raw.copyOfRange(NONCE_BYTES, raw.size)
        return java.security.MessageDigest.isEqual(got, mac(nonce, guestPackage, guestClass, profile))
    }

    private fun mac(nonce: ByteArray, pkg: String, cls: String, profile: Int): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        m.update(nonce)
        m.update("$pkg\u0000$cls\u0000$profile".toByteArray(Charsets.UTF_8))
        return m.doFinal().copyOf(MAC_BYTES)
    }

    companion object {
        const val NONCE_BYTES = 12
        const val MAC_BYTES = 20
        private val rnd = java.security.SecureRandom()

        fun randomNonce(): ByteArray = ByteArray(NONCE_BYTES).also { rnd.nextBytes(it) }
        fun newKey(): ByteArray = ByteArray(32).also { rnd.nextBytes(it) }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String): ByteArray? {
            if (s.length % 2 != 0) return null
            return try { ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() } } catch (e: NumberFormatException) { null }
        }
    }
}
