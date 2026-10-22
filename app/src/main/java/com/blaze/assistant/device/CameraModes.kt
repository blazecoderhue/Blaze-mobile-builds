package com.blaze.assistant.device

/**
 * Blaze Camera's modes and where each capture goes (pure, CameraModesTest). All on-device: Photo, Ask (photo → chat
 * with a prompt ready), Text (ML Kit OCR, the Magic Hand recogniser), Translate (OCR, then Blaze translates it: ML Kit
 * translation isn't bundled), Scan (ML Kit barcode scanning, bundled model), Document (ML Kit document scanner when
 * Google Play services has it, else a photo framed as a page).
 */
object CameraModes {
    enum class Mode(val id: String, val label: String, val talkback: String) {
        PHOTO("photo", "Photo", "Photo mode: take a picture"),
        ASK("ask", "Ask", "Ask mode: snap something and ask Blaze about it"),
        TEXT("text", "Text", "Text mode: read the words in a picture"),
        TRANSLATE("translate", "Translate", "Translate mode: read and translate the words in a picture"),
        SCAN("scan", "Scan", "Scan mode: read a QR code or barcode"),
        DOCUMENT("document", "Document", "Document mode: scan a page"),
    }

    /** The mode bar, left to right. */
    val bar: List<Mode> = listOf(Mode.SCAN, Mode.TEXT, Mode.ASK, Mode.PHOTO, Mode.TRANSLATE, Mode.DOCUMENT)

    /** Tool / intent / pref value → mode. Old open_camera words map too ("qr" → Scan, "ocr" → Text). */
    fun parse(s: String?): Mode? = when (s?.trim()?.lowercase()) {
        "photo", "picture", "camera", "selfie" -> Mode.PHOTO
        "ask", "look", "see", "what", "identify", "describe" -> Mode.ASK
        "text", "ocr", "read" -> Mode.TEXT
        "translate", "translation" -> Mode.TRANSLATE
        "scan", "qr", "barcode", "code" -> Mode.SCAN
        "document", "doc", "page", "pdf", "receipt" -> Mode.DOCUMENT
        else -> null
    }

    /** Where Blaze Camera was opened from. */
    enum class Origin {
        /** + → Camera fallback: one photo into the file the chat passed (old TakePicture contract). */
        CHAT_ATTACH,
        /** + → Blaze Camera. */
        CHAT_PLUS,
        /** The user asked in words ("read this sign", "scanne ce document"): the result answers that turn. */
        REQUEST,
        /** The model called blaze_camera(mode) during a turn. */
        TOOL,
        /** Magic Hand's dock button. */
        MAGIC_HAND,
        /** open_camera from outside a chat (a photo goes to Pictures/Blaze). */
        STANDALONE,
    }

    enum class Route {
        /** Give the file back to the caller (CHAT_ATTACH). */
        RETURN_FILE,
        /** Send it into the chat that asked, as a new turn, so Blaze answers about it at once. */
        SEND_TURN,
        /** Put it in the chat's message box (photo attached, a prompt line ready) for the user to finish. */
        DRAFT,
        /** Show the result card in the camera (Text: Copy / Translate / Search / Save; Scan: the action chip). */
        CARD,
        /** Save the photo to Pictures/Blaze. */
        GALLERY,
    }

    fun route(mode: Mode, origin: Origin): Route = when (origin) {
        Origin.CHAT_ATTACH -> Route.RETURN_FILE
        Origin.REQUEST, Origin.TOOL -> Route.SEND_TURN
        Origin.CHAT_PLUS, Origin.MAGIC_HAND, Origin.STANDALONE -> when (mode) {
            Mode.PHOTO -> if (origin == Origin.STANDALONE) Route.GALLERY else Route.DRAFT
            Mode.ASK, Mode.DOCUMENT -> Route.DRAFT
            Mode.TEXT, Mode.SCAN -> Route.CARD
            // No on-device translator is bundled: Blaze translates the text it read.
            Mode.TRANSLATE -> Route.SEND_TURN
        }
    }

    /** Modes shown in the bar for an origin (CHAT_ATTACH is a plain camera: no bar). */
    fun barFor(origin: Origin): List<Mode> = if (origin == Origin.CHAT_ATTACH) emptyList() else bar

    /** The mode to open in: the asked one, else the last one used (+ → Blaze Camera starts on Ask the first time). */
    fun startMode(asked: Mode?, last: String?, origin: Origin): Mode = when {
        origin == Origin.CHAT_ATTACH -> Mode.PHOTO
        asked != null -> asked
        else -> parse(last) ?: if (origin == Origin.STANDALONE) Mode.PHOTO else Mode.ASK
    }

    /** Remember the user's own choices only (a mode Blaze picked from a request isn't a preference). */
    fun remembers(origin: Origin): Boolean = origin == Origin.CHAT_PLUS || origin == Origin.MAGIC_HAND || origin == Origin.STANDALONE

    /** Live analysis (no shutter) for these modes. */
    fun live(mode: Mode) = mode == Mode.SCAN

    /** The prompt line the chat gets with a capture. [asked] is the user's own words when they asked. */
    fun prompt(mode: Mode, asked: String?, language: String = "English"): String {
        asked?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return when (mode) {
            Mode.PHOTO -> ""
            Mode.ASK -> "What is this?"
            Mode.TEXT -> "Here's the text from Blaze Camera."
            Mode.TRANSLATE -> "Translate this into $language."
            Mode.SCAN -> "I scanned this code. What is it?"
            Mode.DOCUMENT -> "Here's a document I scanned."
        }
    }

    /** What the turn carries besides the photo: the words read on the device, or the code's value. */
    fun payload(mode: Mode, ocr: String?, code: String?): String = when (mode) {
        Mode.TEXT, Mode.TRANSLATE -> ocr?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n\n[Text read on the phone by Blaze Camera]\n$it" }
            ?: "\n\n[Blaze Camera found no readable text; the photo is attached.]"
        Mode.SCAN -> code?.let { "\n\n[Scanned code]\n$it" }.orEmpty()
        else -> ""
    }

    /** The step line in chat while the camera is open. */
    fun openingLine(mode: Mode): String = when (mode) {
        Mode.PHOTO -> "Opening Blaze Camera. Take the picture and I'll have a look."
        Mode.ASK -> "Opening Blaze Camera. Point it at the thing and tap the shutter; I'll tell you what I see."
        Mode.TEXT -> "Opening Blaze Camera in Text mode. Snap the words and I'll read them."
        Mode.TRANSLATE -> "Opening Blaze Camera in Translate mode. Snap the text and I'll translate it."
        Mode.SCAN -> "Opening Blaze Camera in Scan mode. Point it at the code."
        Mode.DOCUMENT -> "Opening Blaze Camera in Document mode. Hold the page flat and fill the frame."
    }

    // ---------------------------------------------------------------- scan results

    enum class CodeKind { URL, WIFI, CONTACT, PHONE, EMAIL, TEXT }

    /** The chips a scanned code offers, main action first. Copy is always there. */
    fun scanActions(kind: CodeKind): List<String> = when (kind) {
        CodeKind.URL -> listOf("Open link", "Copy", "Ask Blaze")
        CodeKind.WIFI -> listOf("Join Wi-Fi", "Copy password", "Ask Blaze")
        CodeKind.CONTACT -> listOf("Add contact", "Copy", "Ask Blaze")
        CodeKind.PHONE -> listOf("Call", "Copy", "Ask Blaze")
        CodeKind.EMAIL -> listOf("Email", "Copy", "Ask Blaze")
        CodeKind.TEXT -> listOf("Copy", "Ask Blaze")
    }

    /** Plain text → kind (used for QR text that ML Kit typed as TEXT, and in tests). */
    fun kindOf(raw: String): CodeKind {
        val t = raw.trim()
        return when {
            Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t) -> CodeKind.URL
            t.startsWith("WIFI:", true) -> CodeKind.WIFI
            t.startsWith("BEGIN:VCARD", true) || t.startsWith("MECARD:", true) -> CodeKind.CONTACT
            t.startsWith("tel:", true) -> CodeKind.PHONE
            t.startsWith("mailto:", true) || t.startsWith("MATMSG:", true) -> CodeKind.EMAIL
            else -> CodeKind.TEXT
        }
    }

    data class Wifi(val ssid: String, val password: String, val security: String)
    /** WIFI:T:WPA;S:name;P:secret;; (escapes \; \, \: \\ handled). */
    fun parseWifi(raw: String): Wifi? {
        if (!raw.trim().startsWith("WIFI:", true)) return null
        val body = raw.trim().substring(5)
        val fields = mutableMapOf<String, String>()
        var key = StringBuilder(); var value = StringBuilder(); var inValue = false; var esc = false
        for (ch in body) {
            when {
                esc -> { (if (inValue) value else key).append(ch); esc = false }
                ch == '\\' -> esc = true
                ch == ':' && !inValue -> inValue = true
                ch == ';' -> { if (key.isNotEmpty()) fields[key.toString().uppercase()] = value.toString(); key = StringBuilder(); value = StringBuilder(); inValue = false }
                else -> (if (inValue) value else key).append(ch)
            }
        }
        if (key.isNotEmpty()) fields[key.toString().uppercase()] = value.toString()
        val ssid = fields["S"] ?: return null
        return Wifi(ssid, fields["P"].orEmpty(), fields["T"].orEmpty().ifBlank { "nopass" })
    }
}
