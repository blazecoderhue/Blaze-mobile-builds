package com.blaze.assistant.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v0.26.3 offline diagrams: renderer order, the private-chat rule, the cache key and the HTML harness's escaping. */
class DiagramRenderTest {
    private val flow = "flowchart LR\n  Sun[Sunlight] --> Cells[Solar cells] --> Home"

    @Test fun mermaidIsDrawnOnThePhoneFirst() {
        assertEquals(listOf(DiagramRender.Source.LOCAL, DiagramRender.Source.REMOTE), DiagramRender.plan("mermaid", private = false))
        // Private chat: on the phone only, never mermaid.ink.
        assertEquals(listOf(DiagramRender.Source.LOCAL), DiagramRender.plan("mermaid", private = true))
        // Graphviz / PlantUML / D2 have no renderer on the phone: kroki, and nothing at all from a private chat.
        for (k in listOf("graphviz", "plantuml", "d2")) {
            assertEquals(listOf(DiagramRender.Source.REMOTE), DiagramRender.plan(k, private = false))
            assertTrue(DiagramRender.plan(k, private = true).isEmpty())
        }
    }

    @Test fun cacheKeyFollowsCodeKindAndLook() {
        val a = DiagramRender.cacheKey("mermaid", flow, dark = true)
        assertEquals(64, a.length); assertTrue(a.all { it in "0123456789abcdef" })
        assertEquals("stable", a, DiagramRender.cacheKey("mermaid", flow, dark = true))
        assertEquals("line endings and outer space don't matter", a, DiagramRender.cacheKey("mermaid", "\n" + flow.replace("\n", "\r\n") + "  \n", dark = true))
        assertNotEquals(a, DiagramRender.cacheKey("mermaid", flow, dark = false))
        assertNotEquals(a, DiagramRender.cacheKey("mermaid", flow + " --> Grid", dark = true))
        assertNotEquals(a, DiagramRender.cacheKey("graphviz", flow, dark = true))
        assertNotEquals("a web picture never stands in for a local one", a, DiagramRender.cacheKey("mermaid", flow, true, DiagramRender.Source.REMOTE))
    }

    @Test fun jsStringIsSafeAndRoundTrips() {
        val nasty = "A[\"</script><script>alert(1)</script>\"] --> B['x'] \\ `y` & <!-- \u2028\u2029 \u0000\u0007 é 🌞\n\ttab"
        val lit = DiagramRender.jsString(nasty)
        assertTrue(lit.startsWith("\"") && lit.endsWith("\""))
        for (bad in listOf("<", ">", "&", "'", "`", "\u2028", "\u2029", "\u0000", "\n", "</")) assertFalse(bad, lit.contains(bad))
        // It is valid JSON too, and decodes back to the exact text.
        assertEquals(nasty, JSONArray("[$lit]").getString(0))
    }

    @Test fun harnessCantBeInjected() {
        val code = "flowchart TD\n A[\"</script><script>window.pwned=1</script>\"] --> B[\"<img src=x onerror=alert(2)>\"]\n click A call alert(3)"
        val html = DiagramRender.harness(code, dark = true, nonce = "abc123")
        // Exactly two scripts, both carrying the nonce: the bundled mermaid.js and the page's own.
        val scripts = Regex("<script\\b[^>]*>").findAll(html).map { it.value }.toList()
        assertEquals(2, scripts.size)
        assertTrue(scripts.all { it.contains("nonce=\"abc123\"") })
        assertTrue(scripts[0].contains("src=\"${DiagramRender.SCRIPT_URL}\""))
        assertEquals(2, Regex("</script>").findAll(html).count())
        assertFalse(html.contains("<img")); assertFalse(html.contains("window.pwned=1</"))
        // A CSP that allows only those scripts and no network at all.
        assertTrue(html.contains("default-src 'none'; script-src 'nonce-abc123'"))
        assertFalse(html.contains("connect-src")); assertFalse(html.contains("https://mermaid.ink")); assertFalse(html.contains("kroki"))
        // Mermaid in strict mode (labels sanitised, no click callbacks).
        assertTrue(html.contains("\"securityLevel\":\"strict\""))
        // The text is in the page only as the escaped literal.
        assertTrue(html.contains("var code=" + DiagramRender.jsString(code) + ";"))
        // A nonce can't break out of its attribute either.
        assertTrue(DiagramRender.harness(flow, false, nonce = "x\" onload=\"y").contains("nonce=\"xonloady\""))
        assertTrue(DiagramRender.nonce().matches(Regex("[A-Za-z0-9]{24}")))
    }

    @Test fun harnessUsesBlazeColours() {
        val dark = DiagramRender.harness(flow, dark = true, nonce = "n")
        assertTrue(dark.contains("background:#26262A"))   // DarkColors.paper
        assertTrue(dark.contains("\"primaryBorderColor\":\"#6B8CFF\""))   // DarkColors.accent
        assertTrue(dark.contains("\"darkMode\":true"))
        val light = DiagramRender.harness(flow, dark = false, nonce = "n")
        assertTrue(light.contains("\"primaryBorderColor\":\"#1F4BD8\""))   // LightColors.accent
        assertTrue(light.contains("\"darkMode\":false"))
        assertEquals(12, DiagramRender.scale(DiagramRender.look(true)).size)
    }

    @Test fun resultsAreRead() {
        assertNull(DiagramRender.parseResult(null)); assertNull(DiagramRender.parseResult("null")); assertNull(DiagramRender.parseResult("\"\""))
        val ok = JSONObject().put("ok", true).put("png", "iVBORw0KGgo=").put("w", 800).put("h", 400).toString()
        val r = DiagramRender.parseResult(JSONObject.quote(ok))!!
        assertTrue(r.ok); assertEquals("iVBORw0KGgo=", r.png); assertEquals(800, r.w)
        val bad = DiagramRender.parseResult(JSONObject.quote(JSONObject().put("ok", false).put("syntax", true).put("error", "Parse error").toString()))!!
        assertFalse(bad.ok); assertTrue(bad.syntax)
        assertFalse(DiagramRender.parseResult("{garbage")!!.ok)
    }

    @Test fun failuresAreOnePlainLineWithoutTheSource() {
        for (w in DiagramRender.Why.values()) {
            val l = DiagramRender.errorLine("graphviz", w)
            assertFalse(l.contains('\n')); assertTrue(l.length < 120); assertFalse(l.contains("digraph"))
        }
        assertTrue(DiagramRender.errorLine("graphviz", DiagramRender.Why.PRIVATE_REMOTE_ONLY).startsWith("Graphviz diagrams can't be drawn"))
    }

    @Test fun mermaidIsBundledWithItsLicence() {
        val root = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val js = File(root, "diagram/mermaid.min.js")
        assertTrue(js.isFile && js.length() > 1_000_000)
        assertTrue(File(root, "diagram/LICENSE").readText().contains("MIT License"))
        val notices = listOf(File("../THIRD_PARTY_NOTICES.md"), File("THIRD_PARTY_NOTICES.md")).first { it.isFile }.readText()
        assertTrue(notices.contains("mermaid ${DiagramRender.MERMAID_VERSION}"))
    }
}
