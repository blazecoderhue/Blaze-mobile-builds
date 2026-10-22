package com.blaze.assistant.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class WarpFreeTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // RFC 7748 §6.1 (Alice) and §5.2 (first scalar-mult vector).
    @Test fun x25519MatchesRfc7748() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", X25519.publicKey(alice).hex())
        val k = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", X25519.scalarMult(k, u).hex())
        assertEquals("hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo=", WarpFree.publicKeyOf("dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo="))
    }

    @Test fun keyPairsAreValidWireGuardKeys() {
        val a = WarpFree.newKeyPair(SecureRandom())
        val b = WarpFree.newKeyPair(SecureRandom())
        assertTrue(WarpFree.isKey(a.privateKey)); assertTrue(WarpFree.isKey(a.publicKey))
        assertNotEquals(a.privateKey, b.privateKey)
        assertEquals(a.publicKey, WarpFree.publicKeyOf(a.privateKey))
        val raw = Base64.getDecoder().decode(a.privateKey)
        assertEquals(0, raw[0].toInt() and 7)          // clamped
        assertEquals(64, raw[31].toInt() and 0xC0)
    }

    @Test fun registrationBodyHasWhatCloudflareNeeds() {
        val o = JSONObject(WarpFree.registrationBody("PUB", "2026-10-14T09:49:00.000Z"))
        assertEquals("PUB", o.getString("key"))
        assertEquals("2026-10-14T09:49:00.000Z", o.getString("tos"))
        assertEquals("Android", o.getString("type"))
        assertEquals("en_US", o.getString("locale"))
        assertTrue(o.getBoolean("warp_enabled"))
        assertEquals("curve25519", o.getString("key_type"))
        assertEquals("wireguard", o.getString("tunnel_type"))
        assertTrue(o.has("install_id") && o.has("fcm_token"))
        assertEquals("https://api.cloudflareclient.com/v0a2158/reg", WarpFree.API_VARIANTS.first().regUrl)
    }

    private val keys = WarpFree.KeyPair("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=")

    private val answer = """
        {"id":"dev-123","type":"a","model":"PC","key":"xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=","token":"tok-abc",
         "account":{"id":"acc","account_type":"free","warp_plus":false},
         "config":{"client_id":"AbCd","peers":[{"public_key":"bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
           "endpoint":{"v4":"162.159.192.7:0","v6":"[2606:4700:d0::a29f:c007]:0","host":"engage.cloudflareclient.com:2408"}}],
           "interface":{"addresses":{"v4":"172.16.0.2","v6":"2606:4700:110:8a36:df92:102a:9602:fa18"}},
           "services":{"http_proxy":"172.16.0.1:2480"}}}
    """.trimIndent()

    @Test fun parsesTheRegistrationAnswer() {
        val a = WarpFree.parseRegistration(answer, keys, 42)
        assertEquals("dev-123", a.deviceId); assertEquals("tok-abc", a.token)
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", a.peerPublicKey)
        assertEquals(WarpFree.Endpoint("engage.cloudflareclient.com", 2408), a.endpoint)
        assertEquals("172.16.0.2", a.v4); assertEquals("2606:4700:110:8a36:df92:102a:9602:fa18", a.v6)
        assertEquals("AbCd", a.clientId); assertEquals(42L, a.created)
        assertEquals("162.159.192.7", WarpFree.peerIp(answer))
    }

    @Test fun parsesAWrappedAnswerAndFallsBackToDefaults() {
        val wrapped = """{"success":true,"result":{"id":"d","token":"t","config":{"peers":[{"endpoint":{"v4":"162.159.193.5:0","ports":[500]}}],
            "interface":{"addresses":{"v4":"172.16.0.2/32"}}}}}"""
        val a = WarpFree.parseRegistration(wrapped, keys)
        assertEquals(WarpFree.DEFAULT_PEER_KEY, a.peerPublicKey)
        assertEquals(WarpFree.Endpoint("162.159.193.5", 500), a.endpoint)
        assertEquals(listOf("172.16.0.2/32"), a.addresses())
    }

    @Test fun errorsArePlainWords() {
        try { WarpFree.parseRegistration("""{"success":false,"errors":[{"code":1001,"message":"Invalid public key"}]}""", keys); fail() }
        catch (e: IllegalArgumentException) { assertEquals("Cloudflare refused the free sign-up: Invalid public key.", e.message) }
        try { WarpFree.parseRegistration("<html>", keys); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("couldn't read")) }
        try { WarpFree.parseRegistration("""{"id":"x","config":{"peers":[]}}""", keys); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("address")) }
        assertEquals("Cloudflare is limiting sign-ups from this network right now.", WarpFree.httpError(429, ""))
        assertTrue(WarpFree.httpError(503, "oops").contains("503"))
        assertTrue(WarpFree.httpError(400, """{"success":false,"errors":[{"message":"bad"}]}""").endsWith("bad."))
    }

    @Test fun buildsAConfigThatParsesAndFeedsWgproxy() {
        val a = WarpFree.parseRegistration(answer, keys)
        val c = WireGuardConfig.parse(a.toConf())      // the same parser imported configs go through
        assertEquals(keys.privateKey, c.privateKey)
        assertEquals(listOf("172.16.0.2/32", "2606:4700:110:8a36:df92:102a:9602:fa18/128"), c.addresses)
        assertEquals(1280, c.mtu)
        assertEquals("engage.cloudflareclient.com", c.peers[0].endpointHost)
        assertEquals(25, c.peers[0].keepalive)
        val j = JSONObject(a.config(WarpFree.Endpoint("162.159.192.1", 4500)).toProxyJson { it })
        val p = j.getJSONArray("peers").getJSONObject(0)
        assertEquals("162.159.192.1:4500", p.getString("endpoint"))
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", p.getString("public_key"))
        assertEquals(2, p.getJSONArray("allowed_ips").length())
        assertEquals(1280, j.getInt("mtu"))
    }

    @Test fun accountSurvivesSaveAndLoad() {
        val a = WarpFree.parseRegistration(answer, keys, 7).copy(lastGood = WarpFree.Endpoint("162.159.192.1", 500))
        val b = WarpFree.Account.fromJson(a.toJson())
        assertEquals(a, b)
        assertTrue(JSONObject(a.toJson()).getString("conf").contains("[Peer]"))
        try { WarpFree.Account.fromJson("""{"private_key":"nope","v4":"172.16.0.2"}"""); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun endpointFallbackOrder() {
        val a = WarpFree.parseRegistration(answer, keys)
        val c = WarpFree.candidates(a, "162.159.192.7")
        assertEquals(WarpFree.Endpoint("engage.cloudflareclient.com", 2408), c[0])
        assertEquals(listOf(2408, 500, 1701, 4500), c.subList(1, 5).map { it.port })
        assertTrue(c.subList(1, 5).all { it.host == "162.159.192.7" })
        assertEquals(listOf(2408, 500, 1701, 4500), c.subList(5, 9).map { it.port })
        assertTrue(c.subList(5, 9).all { it.host == "162.159.192.1" })
        assertEquals(c.size, c.toSet().size)
        // The last route that worked goes first, without repeating it later.
        val good = WarpFree.Endpoint("162.159.192.1", 1701)
        val d = WarpFree.candidates(a.copy(lastGood = good), null)
        assertEquals(good, d[0])
        assertEquals(1, d.count { it == good })
        assertEquals(5, d.size)   // last good + engage + the default IP on its other 3 ports
    }

    @Test fun backoffDoublesThenCaps() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (1..7).map { WarpFree.backoffMs(it) })
        assertEquals(2_000L, WarpFree.backoffMs(0))
    }

    @Test fun traceTellsWarpApart() {
        val on = WarpFree.parseTrace("fl=12f\nh=www.cloudflare.com\nip=104.28.1.2\nts=1\nloc=ZA\ncolo=JNB\nwarp=on\ngateway=off\n")
        assertEquals("104.28.1.2", on.ip); assertEquals("ZA", on.country); assertTrue(on.warpOn)
        val off = WarpFree.parseTrace("ip=41.1.2.3\nloc=ZM\nwarp=off\n")
        assertFalse(off.warpOn); assertEquals("ZM", off.country)
        assertNull(WarpFree.parseTrace("ip=1.1.1.1\nloc=XX\n").country)
        assertTrue(WarpFree.parseTrace("warp=plus").warpOn)
    }

    @Test fun statusLinesArePlain() {
        assertEquals("Connecting to Blaze Free…", WarpFree.tryingLine(0, WarpFree.Endpoint("x", 2408)))
        assertEquals("That route is blocked here. Trying port 500…", WarpFree.tryingLine(2, WarpFree.Endpoint("x", 500)))
    }
}
