package com.blaze.assistant.link

import org.json.JSONObject

/**
 * Marketer pack (v0.17.0): WhatsApp replies drafted on Blaze Desktop and sent from the phone over
 * Blaze Link. The PC puts a structured request in the phone's inbox:
 *
 *   {"id": "...", "kind": "message", "to": "Chanda (HomeGlow customer)", "text": "Hi Chanda, ...", "app": "whatsapp"}
 *
 * The phone never sends without the user's tap: the confirm card is shown even in Autopilot or with a
 * standing OK (Agent.confirm always=true), with "Drafted on <PC>" on it. No model call is made. The
 * reply to the PC says what happened in one line. Plain-text requests keep working as before.
 */
object PcMessages {
    data class Send(val to: String, val text: String, val app: String)

    /** The send in an inbox request, or null when it's an ordinary text request. */
    fun parse(r: JSONObject): Send? {
        val kind = r.optString("kind").lowercase()
        if (kind != "message" && kind != "whatsapp_reply" && kind != "send_message") return null
        val app = r.optString("app", "whatsapp").lowercase().ifBlank { "whatsapp" }
        return Send(r.optString("to").trim(), (r.optString("message").ifBlank { r.optString("text") }).trim(), app)
    }

    /** Why a send can't go, or null when it can. */
    fun problem(s: Send): String? = when {
        s.to.isBlank() -> "No recipient in the request."
        s.text.isBlank() -> "The message is empty."
        s.text.length > 4000 -> "The message is too long for one chat message (${s.text.length} characters)."
        s.app !in setOf("whatsapp", "telegram", "any") -> "From the PC the phone sends WhatsApp or Telegram replies only (asked for ${s.app})."
        else -> null
    }

    /** The one-line answer for the PC from what Messaging returned. */
    fun outcome(result: String): String = when {
        result.contains("declined", true) -> "Not sent: the user declined on the phone."
        result.startsWith("Sent", true) -> result
        else -> "Not sent: $result"
    }
}
