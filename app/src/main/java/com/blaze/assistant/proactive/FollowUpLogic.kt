package com.blaze.assistant.proactive

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.38.0 Follow-ups, the pure part (FollowUpsTest). A message that makes a plan ("Jack: can we meet tomorrow at 3 about
 * the budget?") becomes a follow-up: who, which app and conversation, what, when. Blaze asks "Are you going?"; a yes
 * sets a reminder; until the user has replied, opening that app gets a nudge ("I see you're in WhatsApp. Jack wanted
 * to meet tomorrow at 15:00 about the budget: maybe get back to him?"). A reply seen in the notification (the user's
 * own message), a Send tapped in that chat, or a reply sent through Blaze closes the loop.
 */
object FollowUpLogic {
    enum class Status { ASKED, GOING, NOT_GOING, REPLIED, DONE }

    data class Item(val id: String, val pkg: String, val app: String, val conv: String, val person: String, val topic: String,
                    val whenText: String, val whenAt: Long, val needsReply: Boolean, val status: Status, val created: Long,
                    val replied: Boolean = false, val nudgedAt: Long = 0, val reminderSet: Boolean = false, val ask: String = "") {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("pkg", pkg).put("app", app).put("conv", conv).put("person", person)
            .put("topic", topic).put("whenText", whenText).put("whenAt", whenAt).put("needsReply", needsReply).put("status", status.name)
            .put("created", created).put("replied", replied).put("nudgedAt", nudgedAt).put("reminderSet", reminderSet).put("ask", ask)
        companion object {
            fun from(o: JSONObject): Item? = try {
                Item(o.getString("id"), o.optString("pkg"), o.optString("app"), o.optString("conv"), o.optString("person"), o.optString("topic"),
                    o.optString("whenText"), o.optLong("whenAt"), o.optBoolean("needsReply", true), Status.valueOf(o.optString("status", "ASKED")),
                    o.optLong("created"), o.optBoolean("replied"), o.optLong("nudgedAt"), o.optBoolean("reminderSet"), o.optString("ask"))
            } catch (_: Exception) { null }
        }
    }

    /** Cheap first look (no model call): does this message talk about a plan, a meeting, a time or a question to answer? */
    private val planWords = Regex(
        "\\b(meet|meeting|call me|call you|catch up|lunch|dinner|breakfast|coffee|drinks|party|hang ?out|come over|pick (?:you|me) up|" +
            "are you (?:coming|free|around|going)|can we|could we|shall we|let'?s|see you|tomorrow|tonight|this (?:evening|afternoon|morning|weekend)|" +
            "next (?:week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "\\d{1,2}(?::\\d{2})?\\s?(?:am|pm|h)|\\d{1,2}:\\d{2}|o'?clock|deadline|due|appointment|interview|class|lecture|exam|rsvp)\\b",
        RegexOption.IGNORE_CASE)

    fun looksLikePlan(text: String): Boolean = text.length in 8..1200 && planWords.containsMatchIn(text)

    /** What the model pulled out of a message. [whenAt] 0 = no exact time. */
    data class Plan(val topic: String, val whenText: String, val whenAt: Long, val needsReply: Boolean, val ask: String = "")

    fun parsePlan(raw: String?): Plan? {
        if (raw.isNullOrBlank()) return null
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val o = try { JSONObject(raw.substring(a, b + 1)) } catch (_: Exception) { return null }
        if (!o.optBoolean("plan")) return null
        val topic = o.optString("topic").trim().take(80)
        if (topic.isBlank()) return null
        return Plan(topic, o.optString("when").trim().take(60), o.optLong("when_ms", 0).coerceAtLeast(0), o.optBoolean("needs_reply", true),
            o.optString("ask").trim().takeIf { it.endsWith("?") && it.length in 4..80 }.orEmpty())
    }

    /** "Jack texted on WhatsApp about meeting tomorrow at 15:00 about the budget. Are you going?" */
    fun askText(i: Item): String = buildString {
        append(i.person).append(" texted on ").append(i.app).append(" about ").append(i.topic.trimEnd('.'))
        if (i.whenText.isNotBlank() && !i.topic.contains(i.whenText, true)) append(", ").append(i.whenText)
        append(". ").append(i.ask.ifBlank { "Are you going?" })
    }

    /** The nudge when the user opens the app the plan came from. */
    fun nudgeText(i: Item): String = buildString {
        append("I see you're in ").append(i.app).append(". ").append(i.person).append(" wanted to talk about ").append(i.topic.trimEnd('.'))
        if (i.whenText.isNotBlank() && !i.topic.contains(i.whenText, true)) append(", ").append(i.whenText)
        append(". Maybe get back to ").append(i.person.substringBefore(' ')).append('?')
    }

    /** Open items keep watching: not done, not declined, not past (2 h after the time, or 3 days with no time). */
    fun live(i: Item, now: Long): Boolean {
        if (i.status == Status.DONE || i.status == Status.NOT_GOING) return false
        return if (i.whenAt > 0) now < i.whenAt + 2 * 3600_000L else now - i.created < 3 * 86_400_000L
    }

    /** Nudge on opening the app: live, waiting for a reply, not nudged in the last 2 hours. */
    fun shouldNudge(i: Item, pkg: String, now: Long): Boolean =
        live(i, now) && i.pkg == pkg && i.needsReply && !i.replied && now - i.nudgedAt >= 2 * 3600_000L

    /** The reminder goes 30 minutes before (or now + 1 min when that's already past, while the time is still ahead). */
    fun reminderAt(whenAt: Long, now: Long): Long = when {
        whenAt <= 0 -> 0
        whenAt - 30 * 60_000L > now -> whenAt - 30 * 60_000L
        whenAt > now -> now + 60_000L
        else -> 0
    }

    /** One line per live item for the prompt / Awareness ("Jack (WhatsApp): meeting about the budget, tomorrow 15:00, going, not replied"). */
    fun line(i: Item): String = "${i.person} (${i.app}): ${i.topic}" + (if (i.whenText.isNotBlank()) ", ${i.whenText}" else "") +
        when (i.status) { Status.GOING -> ", going"; Status.ASKED -> ", not answered yet"; else -> "" } +
        if (i.needsReply) (if (i.replied) ", replied" else ", not replied yet") else ""

    fun toJson(list: List<Item>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    fun fromJson(s: String?): List<Item> = try {
        val a = JSONArray(s ?: "[]"); (0 until a.length()).mapNotNull { Item.from(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    /** A Send tapped in a chat app: the button's words. */
    private val sendWords = Regex("^(send|send message|enviar|envoyer|senden|invia)$", RegexOption.IGNORE_CASE)
    fun isSend(label: String?): Boolean = !label.isNullOrBlank() && sendWords.matches(label.trim())
}
