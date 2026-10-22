package com.blaze.assistant.agent

import com.blaze.assistant.link.PcMessages
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class MarketerPackTest {
    @Test fun brandKitMatchesDesktopShapeAndMerges() {
        Seller.brandFileOverride = File(Files.createTempDirectory("brand").toFile(), "brand.json")
        assertTrue(Seller.brand().empty)
        assertTrue(Seller.run(JSONObject().put("action", "get")).startsWith("No brand kit yet"))
        val r = Seller.run(JSONObject().put("action", "set").put("name", "HomeGlow").put("paper", "FFFAF2").put("ink", "#1D1A16")
            .put("accents", "#E8743B, navy").put("fonts", "Playfair Display, Inter").put("notes", "Warm and cosy"))
        assertTrue(r.contains("BRAND KIT"))
        val b = Seller.brand()
        assertEquals("#fffaf2", b.paper); assertEquals("#1d1a16", b.ink)
        assertEquals(listOf("#e8743b", "navy"), b.accents)
        assertEquals(listOf("Playfair Display", "Inter"), b.fonts)
        // Desktop's studio.Brand JSON keys
        val j = JSONObject(Seller.brandFileOverride!!.readText())
        listOf("name", "paper", "ink", "accents", "fonts", "notes").forEach { assertTrue(it, j.has(it)) }
        // Only the given fields change; "-" clears one.
        Seller.run(JSONObject().put("action", "set").put("notes", "-").put("radius", "12px"))
        val b2 = Seller.brand()
        assertEquals("", b2.notes); assertEquals("12px", b2.radius); assertEquals("HomeGlow", b2.name)
        assertTrue(b2.prompt().contains("Accent colours: #e8743b, navy (first is primary)"))
    }

    @Test fun pcMessageRequests() {
        assertNull(PcMessages.parse(JSONObject().put("id", "1").put("text", "What's my battery?")))
        val s = PcMessages.parse(JSONObject().put("id", "2").put("kind", "message").put("to", "Chanda").put("text", "Hi Chanda, your lamp ships today."))!!
        assertEquals("whatsapp", s.app)
        assertNull(PcMessages.problem(s))
        assertTrue(PcMessages.problem(s.copy(app = "sms"))!!.contains("WhatsApp or Telegram"))
        assertTrue(PcMessages.problem(s.copy(text = " ".trim()))!!.contains("empty"))
        assertTrue(PcMessages.problem(s.copy(to = ""))!!.contains("recipient"))
        assertEquals("Not sent: the user declined on the phone.", PcMessages.outcome("The user declined. Nothing was sent."))
        assertEquals("Sent to Chanda on WhatsApp.", PcMessages.outcome("Sent to Chanda on WhatsApp."))
        assertTrue(PcMessages.outcome("WhatsApp isn't installed.").startsWith("Not sent"))
        val legacy = PcMessages.parse(JSONObject().put("kind", "whatsapp_reply").put("to", "x").put("message", "hello"))!!
        assertEquals("hello", legacy.text)
    }

    @Test fun pcSendsAlwaysShowTheCard() {
        // The always flag must bypass standing permissions and Autopilot in Agent.confirm (read the source:
        // Agent needs Android at init).
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val agent = File(root, "java/com/blaze/assistant/agent/Agent.kt").readText()
        // v0.28.2: the decision moved to ConfirmGate (pure, see ConfirmGateTest); always = the card shows.
        assertTrue(agent.contains("ConfirmGate.decide(always,"))
        assertEquals(ConfirmGate.Decision.SHOW, ConfirmGate.decide(always = true, destructive = false, preApproved = true, autopilot = true, tainted = false))
        assertTrue(File(root, "java/com/blaze/assistant/link/DesktopLink.kt").readText().contains("always = true, from = pcName"))
    }

    @Test fun marketerSkillsShipWithMatchingHashes() {
        val root = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }
        val idx = JSONObject(File(root, "index.json").readText()).getJSONArray("skills")
        val byName = (0 until idx.length()).map { idx.getJSONObject(it) }.associateBy { it.getString("name") }
        for (n in listOf("content-calendar", "product-listing", "ad-lab", "research-brief", "whatsapp-replies", "weekly-digest")) {
            val md = File(root, "$n/SKILL.md").readText()
            assertTrue("$n front matter", md.startsWith("---\nname: $n\n"))
            val sha = MessageDigest.getInstance("SHA-256").digest(md.toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals("$n sha256", sha, byName.getValue(n).getString("sha256"))
        }
        assertTrue(File(root, "ad-lab/SKILL.md").readText().contains("hook × 0.4"))
        assertTrue(File(root, "research-brief/SKILL.md").readText().contains("deep-research"))
        assertFalse(File(root, "whatsapp-replies/SKILL.md").readText().contains("ask_phone"))
    }

    @Test fun digestJobIsWeeklyOnMonday() {
        assertEquals(1, com.blaze.assistant.routine.Routines.parseDays("mon"))
        assertTrue(Seller.DIGEST_PROMPT.contains("weekly-digest"))
    }
}
