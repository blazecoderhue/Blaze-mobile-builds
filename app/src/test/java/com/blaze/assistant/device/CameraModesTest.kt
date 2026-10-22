package com.blaze.assistant.device

import com.blaze.assistant.device.CameraModes.CodeKind
import com.blaze.assistant.device.CameraModes.Mode
import com.blaze.assistant.device.CameraModes.Origin
import com.blaze.assistant.device.CameraModes.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.28.x Blaze Camera: where each mode's capture goes, from each place the camera opens. */
class CameraModesTest {
    @Test fun requestsAndToolCallsAnswerInTheChat() {
        Mode.values().forEach { m ->
            assertEquals(Route.SEND_TURN, CameraModes.route(m, Origin.REQUEST))
            assertEquals(Route.SEND_TURN, CameraModes.route(m, Origin.TOOL))
            assertEquals(Route.RETURN_FILE, CameraModes.route(m, Origin.CHAT_ATTACH))
        }
    }
    @Test fun plusMenuAndMagicHand() {
        for (o in listOf(Origin.CHAT_PLUS, Origin.MAGIC_HAND)) {
            assertEquals(Route.DRAFT, CameraModes.route(Mode.PHOTO, o))
            assertEquals(Route.DRAFT, CameraModes.route(Mode.ASK, o))
            assertEquals(Route.CARD, CameraModes.route(Mode.TEXT, o))
            assertEquals(Route.CARD, CameraModes.route(Mode.SCAN, o))
            assertEquals(Route.SEND_TURN, CameraModes.route(Mode.TRANSLATE, o))   // Blaze translates (no on-device model)
            assertEquals(Route.DRAFT, CameraModes.route(Mode.DOCUMENT, o))
        }
        assertEquals(Route.GALLERY, CameraModes.route(Mode.PHOTO, Origin.STANDALONE))
    }
    @Test fun modesParseFromToolsAndOldWords() {
        assertEquals(Mode.SCAN, CameraModes.parse("qr")); assertEquals(Mode.SCAN, CameraModes.parse("Barcode"))
        assertEquals(Mode.TEXT, CameraModes.parse("ocr")); assertEquals(Mode.DOCUMENT, CameraModes.parse("pdf"))
        assertEquals(Mode.ASK, CameraModes.parse("ask")); assertNull(CameraModes.parse("video")); assertNull(CameraModes.parse(null))
        Mode.values().forEach { assertEquals(it, CameraModes.parse(it.id)) }
    }
    @Test fun startModeAndMemory() {
        assertEquals(Mode.ASK, CameraModes.startMode(null, "", Origin.CHAT_PLUS))         // + → Blaze Camera starts on Ask
        assertEquals(Mode.TEXT, CameraModes.startMode(null, "text", Origin.CHAT_PLUS))    // then the last mode used
        assertEquals(Mode.SCAN, CameraModes.startMode(Mode.SCAN, "text", Origin.REQUEST)) // a request picks its own
        assertEquals(Mode.PHOTO, CameraModes.startMode(Mode.SCAN, "text", Origin.CHAT_ATTACH))
        assertEquals(Mode.PHOTO, CameraModes.startMode(null, null, Origin.STANDALONE))
        assertTrue(CameraModes.remembers(Origin.CHAT_PLUS)); assertFalse(CameraModes.remembers(Origin.REQUEST)); assertFalse(CameraModes.remembers(Origin.TOOL))
        assertTrue(CameraModes.barFor(Origin.CHAT_ATTACH).isEmpty()); assertEquals(6, CameraModes.barFor(Origin.CHAT_PLUS).size)
        assertTrue(CameraModes.live(Mode.SCAN)); assertFalse(CameraModes.live(Mode.TEXT))
    }
    @Test fun promptsAndPayloads() {
        assertEquals("read this sign", CameraModes.prompt(Mode.TEXT, "read this sign"))
        assertEquals("What is this?", CameraModes.prompt(Mode.ASK, null))
        assertEquals("Translate this into French.", CameraModes.prompt(Mode.TRANSLATE, null, "French"))
        assertTrue(CameraModes.payload(Mode.TEXT, "EXIT  →", null).contains("EXIT"))
        assertTrue(CameraModes.payload(Mode.TRANSLATE, "", null).contains("no readable text"))
        assertTrue(CameraModes.payload(Mode.SCAN, null, "https://x.y").contains("https://x.y"))
        assertEquals("", CameraModes.payload(Mode.PHOTO, "ignored", null))
        Mode.values().forEach { assertTrue(CameraModes.openingLine(it).startsWith("Opening Blaze Camera")) }
        Mode.values().forEach { assertTrue(it.talkback.isNotBlank()) }
    }
    @Test fun scanChips() {
        assertEquals(CodeKind.URL, CameraModes.kindOf("https://blaze.ai"))
        assertEquals(CodeKind.WIFI, CameraModes.kindOf("WIFI:T:WPA;S:Home;P:pa\\;ss;;"))
        assertEquals(CodeKind.CONTACT, CameraModes.kindOf("BEGIN:VCARD\nFN:A\nEND:VCARD"))
        assertEquals(CodeKind.TEXT, CameraModes.kindOf("4006381333931"))
        assertEquals("Open link", CameraModes.scanActions(CodeKind.URL).first())
        assertEquals("Join Wi-Fi", CameraModes.scanActions(CodeKind.WIFI).first())
        CodeKind.values().forEach { k -> assertTrue(CameraModes.scanActions(k).any { it.startsWith("Copy") }) }
        val w = CameraModes.parseWifi("WIFI:T:WPA;S:Home Net;P:pa\\;ss;;")!!
        assertEquals("Home Net", w.ssid); assertEquals("pa;ss", w.password); assertEquals("WPA", w.security)
        assertEquals("nopass", CameraModes.parseWifi("WIFI:S:Cafe;;")!!.security)
        assertNull(CameraModes.parseWifi("hello"))
    }
}
