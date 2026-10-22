package com.blaze.assistant.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class UrlSafetyTest {
    @Test fun httpsIsNeverRisky() {
        assertFalse(UrlSafety.isRiskyCleartext("https://api.example.com/v1"))
        assertFalse(UrlSafety.isRiskyCleartext(""))
    }

    @Test fun localHttpIsAllowed() {
        listOf(
            "http://127.0.0.1:8080/v1", "http://localhost:11434", "http://192.168.1.20:20128/v1",
            "http://10.0.0.5/mcp", "http://172.20.1.1/sse", "http://100.101.102.103:7377",
            "http://my-pc.local:3000", "http://box.tail1234.ts.net/mcp", "http://[::1]:8080", "HTTP://LOCALHOST/",
        ).forEach { assertFalse(it, UrlSafety.isRiskyCleartext(it)) }
    }

    @Test fun publicHttpIsRisky() {
        listOf(
            "http://api.example.com/v1", "http://8.8.8.8/mcp", "http://172.32.0.1/", "http://100.128.0.1/",
            "http://user:pw@example.org:80/x", "http://192.169.0.1",
        ).forEach { assertTrue(it, UrlSafety.isRiskyCleartext(it)) }
    }

    @Test fun hostParsing() {
        assertEquals("example.org", UrlSafety.host("http://user:pw@Example.org:80/x?y#z"))
        assertEquals("::1", UrlSafety.host("http://[::1]:8080/"))
    }

    // v0.28.2: the host classifier behind the cleartext rule.
    @Test fun hostClassifier() {
        listOf("localhost", "LOCALHOST", "127.0.0.1", "127.9.9.9", "::1", "10.0.2.2", "10.1.2.3", "172.16.0.1", "172.31.255.255",
            "192.168.0.10", "100.64.0.1", "100.127.255.254", "169.254.1.1", "printer.local", "nas.lan", "box.home.arpa",
            "pc.tail1234.ts.net", "desktop", "my-pc", "fd12:3456::1", "fe80::1", "app.localhost", "router.local.",
        ).forEach { assertTrue(it, UrlSafety.isPrivateHost(it)) }
        listOf("", "example.com", "8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.63.255.255", "100.128.0.1",
            "11.0.0.1", "localhost.example.com", "127.0.0.1.nip.io", "evil-local.com", "ts.net.example.com", "2001:4860::8888", "1234",
        ).forEach { assertFalse(it, UrlSafety.isPrivateHost(it)) }
    }

    @Test fun refusesPublicCleartextWithAClearError() {
        assertNull(UrlSafety.problem("https://api.example.com/v1"))
        assertNull(UrlSafety.problem("http://192.168.1.20:20128/v1"))
        assertNull(UrlSafety.problem("http://127.0.0.1:8080"))
        val p = UrlSafety.problem("http://api.example.com/v1")!!
        assertTrue(p, p.contains("api.example.com") && p.contains("https://api.example.com/v1"))
        try { UrlSafety.check("http://api.example.com/v1"); fail("should refuse") } catch (e: UrlSafety.CleartextBlocked) { assertEquals("api.example.com", e.host) }
        UrlSafety.check("http://10.0.2.2:11434/v1")   // the emulator's host loopback is fine
        UrlSafety.check("https://openrouter.ai/api/v1")
    }

    @Test fun credentialsNeverGoOverPublicHttp() {
        assertTrue(UrlSafety.carriesSecret("http://x.com/", mapOf("Authorization" to "Bearer abc")))
        assertTrue(UrlSafety.carriesSecret("http://x.com/", mapOf("x-api-key" to "k")))
        assertTrue(UrlSafety.carriesSecret("http://x.com/", mapOf("X-Goog-Api-Key" to "k")))
        assertTrue(UrlSafety.carriesSecret("http://x.com/models?key=abc", emptyMap()))
        assertTrue(UrlSafety.carriesSecret("http://x.com/cb?access_token=abc", emptyMap()))
        assertFalse(UrlSafety.carriesSecret("http://x.com/page?q=hello", mapOf("User-Agent" to "Blaze", "Accept" to "text/html", "Keep-Alive" to "1")))
        assertFalse(UrlSafety.carriesSecret("http://x.com/", mapOf("Authorization" to "")))
        // Reading a public http page is fine; sending a key to it isn't; a LAN server may get the key.
        UrlSafety.checkSecret("http://x.com/page", mapOf("User-Agent" to "Blaze"))
        try { UrlSafety.checkSecret("http://x.com/v1", mapOf("Authorization" to "Bearer abc")); fail("should refuse") } catch (_: UrlSafety.CleartextBlocked) { }
        UrlSafety.checkSecret("http://192.168.1.5:20128/v1", mapOf("Authorization" to "Bearer abc"))
        UrlSafety.checkSecret("https://x.com/v1", mapOf("Authorization" to "Bearer abc"))
    }

    @Test fun browserNotSecureAndAutofill() {
        assertTrue(UrlSafety.notSecure("http://example.com/login"))
        assertFalse(UrlSafety.notSecure("https://example.com/login"))
        assertTrue(UrlSafety.autofillAllowed("https://example.com/login"))
        assertFalse(UrlSafety.autofillAllowed("http://example.com/login"))
        assertTrue(UrlSafety.autofillAllowed("http://192.168.1.1/login"))   // the router's own page
        assertTrue(UrlSafety.autofillAllowed("http://localhost:3000/"))
        assertFalse(UrlSafety.autofillAllowed("about:blank"))
        assertFalse(UrlSafety.autofillAllowed("file:///sdcard/x.html"))
    }
}
