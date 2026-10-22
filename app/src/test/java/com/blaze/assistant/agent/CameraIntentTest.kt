package com.blaze.assistant.agent

import com.blaze.assistant.device.CameraModes.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.28.x: plain requests open Blaze Camera in the right mode, with no model guess; everything else is left alone. */
class CameraIntentTest {
    private fun assertMode(m: Mode?, text: String) = assertEquals(text, m, CameraIntent.match(text))

    @Test fun documents() {
        assertMode(Mode.DOCUMENT, "scan this document for me")
        assertMode(Mode.DOCUMENT, "Hey Blaze, scan this document")
        assertMode(Mode.DOCUMENT, "scan my homework")
        assertMode(Mode.DOCUMENT, "Can you scan this receipt please?")
        assertMode(Mode.DOCUMENT, "scanne ce document")                 // French
        assertMode(Mode.DOCUMENT, "Scanne ce document s'il te plaît")
        assertMode(Mode.DOCUMENT, "escanea este documento")             // Spanish
        assertMode(Mode.DOCUMENT, "¿Puedes escanear este documento?")
        assertMode(Mode.DOCUMENT, "digitaliza este documento")          // Portuguese
    }
    @Test fun askLooks() {
        assertMode(Mode.ASK, "look at this")
        assertMode(Mode.ASK, "Can you see this?")
        assertMode(Mode.ASK, "what am I looking at")
        assertMode(Mode.ASK, "ok blaze, look at this plant")
        assertMode(Mode.ASK, "mira esto")
        assertMode(Mode.ASK, "¿puedes ver esto?")
        assertMode(Mode.ASK, "regarde ça")
    }
    @Test fun textTranslateScanPhoto() {
        assertMode(Mode.TEXT, "read this sign")
        assertMode(Mode.TEXT, "what does this sign say?")
        assertMode(Mode.TEXT, "lee este letrero")
        assertMode(Mode.TRANSLATE, "translate this menu")
        assertMode(Mode.TRANSLATE, "translate this menu into English")
        assertMode(Mode.TRANSLATE, "traduis ce menu")
        assertMode(Mode.TRANSLATE, "traduce este letrero")
        assertMode(Mode.SCAN, "scan this QR")
        assertMode(Mode.SCAN, "scan this qr code please")
        assertMode(Mode.SCAN, "escanea este código QR")
        assertMode(Mode.SCAN, "scan the barcode")
        assertMode(Mode.PHOTO, "take a photo")
        assertMode(Mode.PHOTO, "prends une photo")
    }
    @Test fun notCameraRequests() {
        listOf("look at this screen", "take a look at my screen", "translate this: bonjour tout le monde", "translate hello to french",
            "read this link", "read my messages", "what is this", "what is the weather", "scan my phone for viruses", "open whatsapp",
            "look at this website https://example.com", "scan this document and email it to Ana with a summary of the main points please",
            "read the file I sent", "translate this page").forEach { assertMode(null, it) }
    }
}
