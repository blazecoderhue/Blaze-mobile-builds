package com.blaze.assistant.agent

import com.blaze.assistant.device.CameraModes.Mode
import java.text.Normalizer

/**
 * v0.28.x Blaze Camera: plain requests that mean "use the camera" open it in the right mode with no model call and
 * no guessing (CameraIntentTest). "scan this document for me" → Document, "look at this" / "can you see this" → Ask,
 * "read this sign" → Text, "translate this menu" → Translate, "scan this QR" → Scan, "take a photo" → Photo. Typed or
 * spoken ("Hey Blaze, scan this document"); English, French, Spanish and Portuguese. The capture comes back into the
 * same chat as the answer to that turn (Agent.fromCamera).
 *
 * Deliberately narrow: short, single-clause requests only, and never when the words point at the screen, a page,
 * a link or something already in the chat ("look at this screen", "translate this: bonjour", "read this link").
 */
object CameraIntent {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** Lowercase, no accents, no wake words / politeness / trailing punctuation. */
    fun clean(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        t = t.replace(Regex("[¿¡\"“”«»]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^(hey|hi|ok|okay|yo|oye|ola|hola|salut|dis)?[ ,]*blaze[ ,!.]*"), "")
        t = t.replace(Regex("[?!.,]+$"), "").trim()
        repeat(2) {
            t = t.replace(Regex("^(please|pls|plz|can you|could you|would you|will you|i want you to|i need you to|help me|" +
                "s'il te plait|s'il vous plait|peux-tu|tu peux|pourrais-tu|por favor|puedes|podrias|pode|podes|voce pode)[ ,]+"), "")
            t = t.replace(Regex("[ ,]+(please|pls|for me|real quick|quickly|now|blaze|pour moi|s'il te plait|s'il vous plait|por favor|para mi|pra mim|para mim)$"), "")
        }
        return t.trim()
    }

    /** Words that mean the thing is on the screen / in the chat, not in front of the camera. */
    private val notCamera = r("""\b(screen|screenshot|website|site|web ?page|webpage|link|url|tab|chat|message|email|mail|file|attachment|image above|picture above|photo above|i sent|i shared|ecran|pantalla|tela|lien|enlace|mensaje|archivo|fichier)\b|https?://|:""")

    private val det = """(this|that|the|my|a|an|ce|cet|cette|le|la|les|mon|ma|un|une|ca|ceci|este|esta|esto|ese|esa|el|mi|un|una|o|meu|minha|esse|essa|isso|isto)"""

    private val rules: List<Pair<Mode, Regex>> = listOf(
        // Document: scan a page / paper / receipt (any language's "scan" + a paper word).
        Mode.DOCUMENT to r("""^(scan|scanne[rz]?|scannes|numerise[rz]?|escanea[r]?|escaneie|escaneia[r]?|digitaliza[r]?|digitalize)( $det)?( \w+)? (document|documents|doc|page|paper|sheet|receipt|letter|form|contract|invoice|notes?|homework|assignment|feuille|recu|lettre|formulaire|facture|devoir|documento|documentos|pagina|hoja|papel|recibo|carta|formulario|factura|folha|recibo)( (into|to|as) (a )?pdf)?$"""),
        Mode.DOCUMENT to r("""^(make|create|turn this into) (a )?pdf (of|from) (this|that)( (page|document|paper))?$"""),
        // Scan: QR codes and barcodes.
        Mode.SCAN to r("""^(scan|scanne[rz]?|escanea[r]?|escaneia[r]?|escaneie|read|lis|lee|ler|leia|open)( $det)?( \w+)? (qr|qr code|qrcode|barcode|bar code|code ?barre|code qr|codigo qr|codigo de barras|codigo|code)$"""),
        Mode.SCAN to r("""^(qr|barcode) scan(ner)?$"""),
        // Translate: "translate this menu", "traduis ce panneau", "traduce este menu".
        Mode.TRANSLATE to r("""^(translate|traduis|traduire|traduisez|traduce|traducir|traduza|traduz|traduzir)( $det)?( (menu|sign|signs|label|text|board|poster|notice|note|card|packet|package|box|instructions|writing|words|panneau|texte|etiquette|affiche|letrero|cartel|texto|etiqueta|aviso|placa|rotulo|cardapio))?( (into|to|en|al|a|para|pra) \w+)?$"""),
        // Text: read what's written on a thing.
        Mode.TEXT to r("""^(read|lis|lire|lisez|lee|leer|leia|ler)( $det)?( (sign|signs|label|text|board|poster|notice|note|card|menu|whiteboard|blackboard|plate|writing|words|panneau|texte|etiquette|affiche|tableau|letrero|cartel|texto|etiqueta|aviso|pizarra|placa|quadro))?( (out )?(to me|aloud|out loud))?$"""),
        Mode.TEXT to r("""^what does (this|that|the) (sign|label|board|poster|notice|note|card|writing) say$"""),
        Mode.TEXT to r("""^(copy|grab|get|extract) (the )?(text|words) (from|off|on) (this|that) (sign|label|board|poster|note|card|paper|page|whiteboard)$"""),
        // Ask: show Blaze something.
        Mode.ASK to r("""^(look at|take a look at|have a look at|check out|see|regarde|mira|olha|veja)( $det)?( (thing|object|plant|flower|bug|insect|animal|food|dish|item|product|rash|part|machine|chose|objet|plante|cosa|objeto|planta|coisa))?$"""),
        Mode.ASK to r("""^(take a look|have a look|look|regarde|mira|olha)$"""),
        Mode.ASK to r("""^(can|could) you see (this|that|it|me|what i'?m holding|what i'?m pointing at)$"""),
        Mode.ASK to r("""^(see|you see|do you see|tu vois|vous voyez|ves|ve|ver|puedes ver|consegues ver|voce ve|ves esto|vois-tu)( $det)?$"""),
        Mode.ASK to r("""^what (is|'s) (this|that) (in my hand|i'?m holding|in front of me|i'?m looking at|i'?m pointing at)$"""),
        Mode.ASK to r("""^what am i (looking at|holding|pointing at)$"""),
        Mode.ASK to r("""^(identify|recognise|recognize|describe) (this|that|what i'?m holding|what i'?m looking at)( (thing|object|plant|flower|bug|insect|animal|food|dish|item|product))?$"""),
        Mode.ASK to r("""^(qu'est-ce que c'est|c'est quoi ca|que es esto|o que e isso|o que e isto)$"""),
        // Photo: Blaze takes one and looks at it.
        Mode.PHOTO to r("""^(take|snap) (a |one )?(photo|picture|pic|selfie|shot)( of (this|that|it|me))?$"""),
        Mode.PHOTO to r("""^(prends|prend) une (photo|selfie)( de (ca|ceci))?$|^(toma|saca) una (foto|selfie)( de esto)?$|^(tira) uma (foto|selfie)( disso)?$"""),
    )

    /** The camera mode [text] asks for, or null when it isn't a camera request. */
    fun match(text: String): Mode? {
        if (text.length > 90 || text.contains('\n')) return null
        val t = clean(text)
        if (t.isEmpty() || t.length > 70) return null
        if (notCamera.containsMatchIn(t)) return null
        return rules.firstOrNull { it.second.matches(t) }?.first
    }
}
