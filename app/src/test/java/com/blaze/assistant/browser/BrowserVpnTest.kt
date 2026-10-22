package com.blaze.assistant.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BrowserVpnTest {
    private val key = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    private val pub = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="

    private val proton = """
        [Interface]
        # Key for Blaze
        PrivateKey = $key
        Address = 10.2.0.2/32
        DNS = 10.2.0.1

        [Peer]
        # NL-FREE#1
        PublicKey = $pub
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 185.107.56.1:51820
    """.trimIndent()

    @Test fun parsesAProviderConfig() {
        val c = WireGuardConfig.parse(proton)
        assertEquals(key, c.privateKey)
        assertEquals(listOf("10.2.0.2/32"), c.addresses)
        assertEquals(listOf("10.2.0.1"), c.dns)
        assertEquals(1, c.peers.size)
        assertEquals("185.107.56.1", c.peers[0].endpointHost)
        assertEquals(51820, c.peers[0].endpointPort)
        assertEquals(listOf("0.0.0.0/0", "::/0"), c.peers[0].allowedIps)
    }

    @Test fun multiValueAndIpv6AndHostnames() {
        val c = WireGuardConfig.parse("""
            [Interface]
            PrivateKey=$key
            Address = 10.64.1.2/32, fc00:bbbb::2/128
            DNS = 10.64.0.1, search.lan
            MTU = 1280
            [Peer]
            PublicKey=$pub
            PresharedKey = $key
            AllowedIPs = 0.0.0.0/0
            AllowedIPs = ::/0
            Endpoint = [2a03:1b20::1]:51820
            PersistentKeepalive = 25
        """.trimIndent())
        assertEquals(2, c.addresses.size)
        assertEquals(listOf("10.64.0.1"), c.dns)   // search domains dropped
        assertEquals(1280, c.mtu)
        assertEquals("2a03:1b20::1", c.peers[0].endpointHost)
        assertEquals(listOf("0.0.0.0/0", "::/0"), c.peers[0].allowedIps)
        assertEquals(25, c.peers[0].keepalive)
        assertEquals("vpn.example.com" to 443, WireGuardConfig.splitEndpoint("vpn.example.com:443"))
    }

    @Test fun proxyJsonResolvesEndpoints() {
        val c = WireGuardConfig.parse(proton.replace("185.107.56.1", "nl-free-1.protonvpn.net"))
        val j = JSONObject(c.toProxyJson { if (it == "nl-free-1.protonvpn.net") "185.107.56.1" else error(it) })
        assertEquals(key, j.getString("private_key"))
        val p = j.getJSONArray("peers").getJSONObject(0)
        assertEquals("185.107.56.1:51820", p.getString("endpoint"))
        assertEquals(pub, p.getString("public_key"))
        val v6 = JSONObject(c.toProxyJson { "2a03:1b20::1" }).getJSONArray("peers").getJSONObject(0)
        assertEquals("[2a03:1b20::1]:51820", v6.getString("endpoint"))
    }

    @Test fun rejectsWhatIsntAConfig() {
        for (bad in listOf("", "hello", proton.replace(key, "short"), proton.replace("Endpoint = 185.107.56.1:51820", ""),
                proton.replace("Address = 10.2.0.2/32", ""), proton.substringBefore("[Peer]"), proton.replace(":51820", ""))) {
            try { WireGuardConfig.parse(bad); fail("accepted: $bad") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.isNotBlank()) }
        }
    }

    @Test fun guessesCountryFromProviderNames() {
        assertEquals("NL", WireGuardConfig.guessCountry("NL-FREE#1.conf"))
        assertEquals("US", WireGuardConfig.guessCountry("US-NY#12"))
        assertEquals("SE", WireGuardConfig.guessCountry("mullvad-se-sto-wg-001.conf"))
        assertEquals("DE", WireGuardConfig.guessCountry("wireguard/de-fra-wg-002.conf"))
        assertEquals("GB", WireGuardConfig.guessCountry("uk-lon.conf"))
        assertEquals("JP", WireGuardConfig.guessCountry("Pasted config", "jp12.example.net"))
        assertNull(WireGuardConfig.guessCountry("work-vpn", "185.107.56.1"))
        assertNull(WireGuardConfig.guessCountry("home"))
    }

    @Test fun countries() {
        assertEquals("\uD83C\uDDF3\uD83C\uDDF1", Countries.flag("NL"))
        assertEquals("DE", Countries.find("germany")?.code)
        assertEquals("DE", Countries.find("de")?.code)
        assertEquals("GB", Countries.find("UK")?.code)
        assertEquals("US", Countries.find("USA")?.code)
        assertNull(Countries.find("Atlantis"))
        assertEquals(Countries.all.size, Countries.all.map { it.code }.toSet().size)
        Countries.all.forEach { assertTrue(it.tz, it.tz.contains('/')); assertTrue(it.lat in -90.0..90.0 && it.lon in -180.0..180.0) }
    }

    @Test fun maskScript() {
        val jp = LocationMask.script(Countries.byCode("JP"), seed = 1)
        assertTrue(jp.contains("\"tz\":\"Asia/Tokyo\""))
        assertTrue(jp.contains("\"lang\":\"ja-JP\""))
        assertTrue(jp.contains("RTCPeerConnection"))
        val lat = Regex("\"lat\":([0-9.]+)").find(jp)!!.groupValues[1].toDouble()
        assertTrue(Math.abs(lat - 35.6762) < 0.05)   // near Tokyo, jittered
        val none = LocationMask.script(null)
        assertTrue(none.contains("var C={}"))
        assertTrue(none.contains("User denied Geolocation"))
        assertFalse(none.contains("Asia/"))
    }
}
