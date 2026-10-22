package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsPrefTest {
    @Test fun secretPrefs() {
        listOf("key_groq", "keys_extra_groq", "conn_tok_github", "conn_tok_blaze_session", "mcp_tok_x", "mcp_config")
            .forEach { assertTrue(it, Secrets.isSecretPref(it)) }
    }

    @Test fun notSecretPrefs() {
        listOf("key_rest", "keysync_t_groq", "provider", "model_groq", "custom_base", "mcp_servers")
            .forEach { assertFalse(it, Secrets.isSecretPref(it)) }
    }

    @Test fun providerOf() {
        assertEquals("groq", Secrets.providerOf("key_groq"))
        assertEquals("openai", Secrets.providerOf("keys_extra_openai"))
        assertNull(Secrets.providerOf("key_rest"))
        assertNull(Secrets.providerOf("conn_tok_github"))
    }
}
