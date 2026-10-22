package com.blaze.assistant.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class DashHttpTest {
    private fun req(head: String, body: ByteArray = ByteArray(0)): InputStream =
        ByteArrayInputStream(head.toByteArray(Charsets.ISO_8859_1) + body)

    @Test fun utf8BodyIsReadByBytes() {
        val text = """{"text":"Mwapoleni 👋 café — ndi bwino"}"""
        val b = text.toByteArray(Charsets.UTF_8)
        assertTrue(b.size > text.length) // the old char loop waited for b.size chars here
        val inp = req("POST /api/send?t=abc HTTP/1.1\r\nContent-Length: ${b.size}\r\nContent-Type: application/json\r\n\r\n", b)
        val h = DashHttp.readHead(inp)!!
        assertEquals("POST", h.method); assertEquals("/api/send", h.path); assertEquals("abc", h.token)
        assertEquals(text, DashHttp.readBody(inp, h))
    }

    @Test fun hugeContentLengthIsRefusedWithoutAllocating() {
        val inp = req("POST /api/send HTTP/1.1\r\nContent-Length: 2000000000\r\n\r\n")
        val h = DashHttp.readHead(inp)!!
        try { DashHttp.readBody(inp, h); fail() } catch (e: DashHttp.BadRequest) { assertEquals(413, e.code) }
        val neg = DashHttp.readHead(req("POST / HTTP/1.1\r\nContent-Length: -5\r\n\r\n"))!!
        try { DashHttp.readBody(ByteArrayInputStream(ByteArray(0)), neg); fail() } catch (e: DashHttp.BadRequest) { assertEquals(400, e.code) }
    }

    @Test fun uploadGetsBiggerCapButStillCapped() {
        assertEquals(DashHttp.MAX_BODY, DashHttp.maxBody("/api/send"))
        assertTrue(DashHttp.maxBody("/api/upload") > 16 * 1024 * 1024)
        val h = DashHttp.readHead(req("POST /api/upload HTTP/1.1\r\nContent-Length: ${DashHttp.MAX_UPLOAD_BODY + 1}\r\n\r\n"))!!
        try { DashHttp.readBody(ByteArrayInputStream(ByteArray(0)), h); fail() } catch (e: DashHttp.BadRequest) { assertEquals(413, e.code) }
    }

    @Test fun shortBodyIsAnError() {
        val inp = req("POST /api/send HTTP/1.1\r\nContent-Length: 10\r\n\r\n", "abc".toByteArray())
        val h = DashHttp.readHead(inp)!!
        try { DashHttp.readBody(inp, h); fail() } catch (e: DashHttp.BadRequest) { assertEquals(400, e.code) }
    }

    @Test fun headerLimits() {
        try { DashHttp.readHead(req("GET /" + "a".repeat(DashHttp.MAX_LINE + 10) + " HTTP/1.1\r\n\r\n")); fail() }
        catch (e: DashHttp.BadRequest) { assertEquals(431, e.code) }
        val many = (1..DashHttp.MAX_HEADERS + 1).joinToString("") { "X-$it: v\r\n" }
        try { DashHttp.readHead(req("GET / HTTP/1.1\r\n$many\r\n")); fail() } catch (e: DashHttp.BadRequest) { assertEquals(431, e.code) }
        assertEquals(null, DashHttp.readHead(ByteArrayInputStream(ByteArray(0))))
    }

    @Test fun tokenFromHeaderOrQueryComparedSafely() {
        val h = DashHttp.readHead(req("GET /api/stats?t=fromquery HTTP/1.1\r\nX-Blaze-Token: fromheader\r\n\r\n"))!!
        assertEquals("fromheader", h.token)
        assertTrue(DashHttp.tokenOk("abc", "abc"))
        assertFalse(DashHttp.tokenOk("abd", "abc"))
        assertFalse(DashHttp.tokenOk(null, "abc"))
        assertFalse(DashHttp.tokenOk("", ""))
        assertFalse(DashHttp.tokenOk("abc", "abcd"))
    }

    @Test fun queryDecodingKeepsPlus() {
        val q = DashHttp.parseQuery("path=%2Fsdcard%2Fa+b%20c.txt&id=x%C3%A9&bad")
        assertEquals("/sdcard/a+b c.txt", q["path"]); assertEquals("xé", q["id"]); assertFalse(q.containsKey("bad"))
    }

    @Test fun newTokensAre128Bit() {
        val a = DashHttp.newToken(); val b = DashHttp.newToken()
        assertEquals(32, a.length); assertTrue(a.all { it in "0123456789abcdef" }); assertNotEquals(a, b)
    }
}
