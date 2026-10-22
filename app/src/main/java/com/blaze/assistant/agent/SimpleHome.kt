package com.blaze.assistant.agent

/**
 * v0.39.0 Simple chat home, pure part (SimpleHomeTest): the greeting by time of day, two time-aware starter chips,
 * reply chips under a question from Blaze (Yes / No / Later / Do it for me), and the @blaze / @halo / @all tag.
 */
object SimpleHome {
    data class Chip(val label: String, val prompt: String, val icon: String = "")

    /** "Good morning", "Good afternoon", "Good evening", or "Still up" late at night (with the name when known). */
    fun greeting(hour: Int, name: String = ""): String {
        val n = name.trim().substringBefore(' ')
        return when (hour) {
            in 5..11 -> "Good morning" + (if (n.isNotEmpty()) ", $n" else "")
            in 12..16 -> "Good afternoon" + (if (n.isNotEmpty()) ", $n" else "")
            in 17..21 -> "Good evening" + (if (n.isNotEmpty()) ", $n" else "")
            else -> "Still up" + (if (n.isNotEmpty()) ", $n?" else "?")
        }
    }

    /** Two starters that fit the hour. */
    fun starters(hour: Int): List<Chip> = when (hour) {
        in 5..11 -> listOf(
            Chip("Plan my day", "Plan my day: what's on today, and anything I should not forget.", "sun"),
            Chip("What did I miss?", "What did I miss overnight? Messages, missed calls and anything important.", "bell"))
        in 12..16 -> listOf(
            Chip("Plan my day", "Plan the rest of my day: what's left today, and anything I should not forget.", "sun"),
            Chip("What did I miss?", "What did I miss today? Messages, missed calls and anything important.", "bell"))
        in 17..21 -> listOf(
            Chip("Plan tomorrow", "Help me plan tomorrow: what's on, and what I should get ready tonight.", "moon"),
            Chip("Catch up", "Catch me up: messages, missed calls and anything I still need to answer today.", "bell"))
        else -> listOf(
            Chip("Wind down", "Help me wind down: check my alarm for tomorrow and keep things quiet until morning.", "moon"),
            Chip("Catch up", "Catch me up quickly on anything I still need to answer.", "bell"))
    }

    // ---------------- reply chips ----------------

    private val askStart = Regex("(?i)^(want|shall|should|do you|would you|can i|could i|may i|ok to|okay to|is it ok|say|send|reply|are you|will you|did you|have you|is that|does that|go ahead|book|set|add|remind|call|text|message|pay|share|turn|open|save|delete|remove)\\b")
    private val actionWord = Regex("(?i)\\b(send|reply|say|text|message|thank|book|set|add|remind|call|share|order|buy|pay|schedule|turn|open|save|write|draft|fix|clean|tidy|move|play|download|install)\\b")
    private val openQ = Regex("(?i)^(what|which|when|where|who|why|how)\\b")

    /** The question at the end of a reply ("…. Say thank you on WhatsApp?"), or "" when it doesn't end with one. */
    fun lastQuestion(reply: String): String {
        val t = reply.trim().trimEnd('*', '_', ' ')
        if (!t.endsWith("?")) return ""
        val body = t.dropLast(1)
        val cut = maxOf(body.lastIndexOf(". "), body.lastIndexOf("! "), body.lastIndexOf("? "), body.lastIndexOf('\n'))
        return (if (cut >= 0) body.substring(cut + 1) else body).trim().trim('*', '_') + "?"
    }

    /**
     * Chips for a reply from Blaze: a yes/no question gets Yes / No / Later, plus "Do it for me" when it's about doing
     * something. Open questions ("What time?") and anything that isn't a question get none.
     */
    fun replyChips(reply: String): List<Chip> {
        val q = lastQuestion(reply)
        if (q.isEmpty() || q.length > 160) return emptyList()
        val core = q.removeSuffix("?").trim()
        if (openQ.containsMatchIn(core) && !core.contains(" or ", true)) return emptyList()
        // "Coffee or tea?" is a choice, not a yes/no.
        if (Regex("(?i)\\bor\\b").containsMatchIn(core) && !Regex("(?i)\\bor not\\b").containsMatchIn(core)) return emptyList()
        if (!askStart.containsMatchIn(core)) return emptyList()
        val chips = mutableListOf(Chip("Yes", "Yes"), Chip("No", "No, thanks"), Chip("Later", "Not now. Remind me later."))
        if (actionWord.containsMatchIn(core)) chips += Chip("Do it for me", "Yes, please do it for me.")
        return chips
    }

    // ---------------- drafts and the home-button sheet ----------------

    private val messageVerb = Regex("(?i)\\b(send|text|message|reply|email|whatsapp|sms|post|dm)\\b")

    /** An approval card for a message reads as a draft ("Draft · not sent yet" with a Send button). */
    fun isDraft(title: String, action: String): Boolean = messageVerb.containsMatchIn(title) || action.trim().equals("send", true)

    /** "Sees: Finance team on WhatsApp" / "Sees: WhatsApp" ("" when Blaze can't see the screen). */
    fun seesLine(appLabel: String, screenTitle: String = ""): String = when {
        appLabel.isBlank() -> ""
        screenTitle.isNotBlank() && !screenTitle.equals(appLabel, true) -> "Sees: ${screenTitle.take(40)} on $appLabel"
        else -> "Sees: $appLabel"
    }

    // ---------------- @blaze / @halo / @all ----------------

    val TALK_TO = listOf("blaze", "halo", "all")
    private val anyTag = Regex("(?i)(^|\\s)@(all|both|everyone|duo|blaze|halo)\\b")

    /** The message as sent: "@halo …" / "@all …" in front unless it already names someone; Blaze needs no tag. Commands are left alone. */
    fun tagged(text: String, who: String): String {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("/") || who == "blaze" || anyTag.containsMatchIn(t)) return t
        return if (who in TALK_TO) "@$who $t" else t
    }
}
