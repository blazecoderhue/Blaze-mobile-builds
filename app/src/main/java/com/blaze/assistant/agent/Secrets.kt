package com.blaze.assistant.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts API keys and sign-in tokens with a key that never leaves the phone's Android Keystore.
 * Stored values look like "enc1:<base64>". Plain values (from older versions or a backup import)
 * still read fine and are encrypted on the next start.
 */
object Secrets {
    private const val ALIAS = "blaze_secrets"
    private const val PREFIX = "enc1:"

    /** True when this phone already has Blaze's Keystore key. False on a fresh install or a restore onto a new phone. */
    fun hasKey(): Boolean = try {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(ALIAS)
    } catch (_: Exception) { true }   // can't tell: assume yes, so nothing is dropped by mistake

    /** How many values this run had to keep in plain text because the Keystore failed (shown in the self-check). */
    @Volatile var plainFallbacks = 0
        private set

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    fun isEncrypted(v: String) = v.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isEmpty() || isEncrypted(plain)) return plain
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(plain.toByteArray())
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Keystore unavailable: keep working rather than lose the key, but say so (log + self-check).
            plainFallbacks++
            com.blaze.assistant.device.CrashLog.error("Secrets.encrypt fell back to plain text", e)
            plain
        }
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        cache[stored]?.let { return it }
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12)).also { cache[stored] = it }
        } catch (_: Exception) { "" }   // e.g. restored onto another phone
    }

    /**
     * Pref names that hold secrets. `keys_extra_<pid>` (extra keys per provider) is a secret too; `key_rest`
     * (which key is resting on which model) is not, and encrypting it made the resting marks unreadable.
     */
    fun isSecretPref(k: String) = k != "key_rest" &&
        (k.startsWith("key_") || k.startsWith("keys_extra_") || k.startsWith("conn_tok_") || k.startsWith("mcp_tok_") || k == "mcp_config")

    /** Provider id a key pref belongs to (`key_groq`, `keys_extra_groq` -> `groq`), or null. */
    fun providerOf(k: String): String? = when {
        k == "key_rest" -> null
        k.startsWith("keys_extra_") -> k.removePrefix("keys_extra_").ifBlank { null }
        k.startsWith("key_") -> k.removePrefix("key_").ifBlank { null }
        else -> null
    }
}
