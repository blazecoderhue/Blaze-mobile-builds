package com.blaze.assistant.agent

/**
 * Ack now, work in the background, report when done (HQ 22 Oct 20:35).
 *
 * A task gets one instant acknowledgment as a plain chat message from Blaze ("Got it, setting that reminder for 9:00
 * tomorrow. I'll confirm when it's set."), the work goes on in the background (a small task chip in the chat, no
 * "Blaze is thinking" line and no working notification), and the end is a normal message: "Done ✓ …" with the receipt
 * when the action was verified, or an honest notice when it wasn't.
 *
 * Rules (all pure, AckTest):
 *  - a greeting, thanks or small talk gets a normal reply and no ack ([wants]);
 *  - an ack never says the task is done ([safe]); a gateway ack that does is dropped;
 *  - one ack per turn, whoever sends it first (the on-device ack, the gateway's `ack` event, Halo or Blaze) ([Gate]);
 *  - "Done ✓" only when every action the turn claims was verified ([receipt]).
 */
object Ack {
    const val META = "ack"
    const val DONE = "Done \u2713"
    /** The on-device ack waits this long, so a fast-lane command (a few hundred ms) answers without one. Well under 1 s. */
    const val LOCAL_DELAY_MS = 350L

    private val greeting = Regex(
        "^(hi|hey|hello|yo|hiya|howdy|sup|good (morning|afternoon|evening|night)|gm|gn|thanks?( you)?|thank u|thx|ty|ok(ay)?|cool|nice|great|" +
            "lol|haha+|bye|see ya|how are you|how's it going|what's up|wassup)( (blaze|halo|there|mate|man|bro))?[ .!?\u2026]*$",
        RegexOption.IGNORE_CASE)

    /** Verbs that make a message a task (something to do, not something to answer). */
    private val doVerb = Regex(
        "\\b(remind|set|schedule|book|order|buy|send|text|message|email|call|create|make|add|delete|remove|turn (on|off)|switch|enable|disable|" +
            "open|play|download|save|export|share|install|update|find (me|a|the)|search for|look up|research|write|draft|plan|build|generate|" +
            "translate|summari[sz]e|clean|organi[sz]e|move|copy|rename|cancel|wake me|alarm|timer|track|post|upload|record|start|stop)\\b",
        RegexOption.IGNORE_CASE)

    /** Words that would tell the user the task already happened. */
    private val doneClaim = Regex(
        "(\\bdone\\b|\u2713|\u2714|\\b(sent|booked|ordered|scheduled|saved|deleted|removed|downloaded|installed|posted|cancell?ed)\\b|\\ball set\\b|\\bcompleted?\\b|\\bfinished\\b|\\bsuccessfully\\b|" +
            "\\b(i've|i have|i just|i)\\s+(set|sent|booked|ordered|created|made|added|deleted|removed|scheduled|saved|turned|switched|" +
            "downloaded|installed|posted|played|opened|cancelled|canceled|started|stopped)\\b|" +
            "\\b(is|are|has been|have been|was|were)\\s+(now\\s+)?(set|sent|booked|ordered|created|added|deleted|removed|scheduled|saved|on|off|" +
            "downloaded|installed|posted|playing|open|cancell?ed|started|stopped)\\b)",
        RegexOption.IGNORE_CASE)

    /** "I'll confirm when it's set": a promise about the end, not a claim. Taken out before [doneClaim] is checked. */
    private val promise = Regex("\\b(when|once|after|as soon as)\\b[^.!?]*", RegexOption.IGNORE_CASE)

    /** True when [text] is a task that should be acknowledged first. */
    fun wants(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("[Scheduled routine") || t.startsWith("/")) return false
        if (greeting.matches(t)) return false
        return doVerb.containsMatchIn(t)
    }

    /** True when [ack] doesn't claim the task is already done (the future "I'll confirm when it's set" is fine). */
    fun safe(ack: String): Boolean {
        val t = ack.trim()
        if (t.isEmpty() || t.length > 280) return false
        return !doneClaim.containsMatchIn(promise.replace(t, ""))
    }

    private val reminder = Regex("\\bremind(er)?\\b", RegexOption.IGNORE_CASE)
    private val alarm = Regex("\\b(alarm|wake me)\\b", RegexOption.IGNORE_CASE)
    private val timer = Regex("\\btimer\\b", RegexOption.IGNORE_CASE)
    private val clock = Regex("\\b(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?(?=\\s|$|[,.!?])", RegexOption.IGNORE_CASE)
    private val atClock = Regex("\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?\\b", RegexOption.IGNORE_CASE)
    private val inSpan = Regex("\\bin\\s+(\\d{1,3}|an?|one|half an?)\\s*(min(ute)?s?|hours?|hrs?|h|seconds?|secs?)\\b", RegexOption.IGNORE_CASE)
    private val day = Regex("\\b(today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", RegexOption.IGNORE_CASE)

    /** "for 9:00 tomorrow", "in 10 minutes", "" when the message names no time. */
    fun whenPart(text: String): String {
        inSpan.find(text)?.let { m ->
            val n = when (m.groupValues[1].lowercase()) { "a", "an", "one" -> "1"; "half a", "half an" -> "30"; else -> m.groupValues[1] }
            val u0 = m.groupValues[2].lowercase()
            val unit = when {
                m.groupValues[1].lowercase().startsWith("half") -> "minutes"
                u0.startsWith("h") -> if (n == "1") "hour" else "hours"
                u0.startsWith("s") -> if (n == "1") "second" else "seconds"
                else -> if (n == "1") "minute" else "minutes"
            }
            return "in $n $unit"
        }
        val c = atClock.find(text) ?: clock.find(text)?.takeIf { it.groupValues[2].isNotEmpty() || it.groupValues[3].isNotEmpty() }
        val d = day.find(text)?.value?.lowercase()?.let { if (it == "today" || it == "tonight" || it == "tomorrow") it else it.replaceFirstChar { ch -> ch.uppercase() } }
        val time = c?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let null
            if (h > 23) return@let null
            val mm = m.groupValues[2].ifEmpty { "00" }
            val ap = m.groupValues[3].lowercase().replace(".", "")
            "$h:$mm" + if (ap.isNotEmpty()) " $ap" else ""
        }
        return when {
            time != null && d != null -> "for $time $d"
            time != null -> "for $time"
            d != null -> "for $d"
            else -> ""
        }
    }

    /**
     * The on-device ack for [text] (no model, under a second), or null when it shouldn't get one. Never says done.
     */
    fun local(text: String): String? {
        if (!wants(text)) return null
        val w = whenPart(text).let { if (it.isEmpty()) "" else " $it" }
        val a = when {
            timer.containsMatchIn(text) -> "Got it, starting that timer$w. I'll confirm when it's running."
            alarm.containsMatchIn(text) -> "Got it, setting that alarm$w. I'll confirm when it's set."
            reminder.containsMatchIn(text) -> "Got it, setting that reminder$w. I'll confirm when it's set."
            else -> "Got it, I'm on it. I'll message you here when it's done."
        }
        return a.takeIf { safe(it) }
    }

    /** The task chip's short label while the work runs in the background ("Reminder", "Task"). */
    fun chip(text: String): String = when {
        timer.containsMatchIn(text) -> "Timer"
        alarm.containsMatchIn(text) -> "Alarm"
        reminder.containsMatchIn(text) -> "Reminder"
        else -> "Task"
    } + " \u00b7 in progress"

    /**
     * The finished message for an acknowledged turn: "Done ✓ <reply>" when every action it ran was verified and the
     * reply wasn't rewritten as a failure; otherwise the reply as it is (ClaimCheck already made it honest).
     * [verified] / [unproven] = tools whose last result was verified / failed or unconfirmed.
     */
    fun receipt(reply: String, verified: Int, unproven: Int, rewrote: Boolean): String {
        val r = reply.trim()
        if (r.isEmpty() || r.startsWith(DONE)) return r
        if (verified == 0 || unproven > 0 || rewrote) return r
        return "$DONE $r"
    }

    /** One ack per turn: the first [take] wins, later ones (the gateway after a local ack, Halo after Blaze) are dropped. */
    class Gate {
        @Volatile var shown: String? = null; private set
        @Volatile var finished = false
        /** The user message the ack answered (the task ledger's key). */
        @Volatile var user = 0L
        /** True when [ack] should be shown now (nothing shown yet, the turn hasn't answered yet, and it is safe). */
        @Synchronized fun take(ack: String): Boolean {
            if (shown != null || finished || !safe(ack)) return false
            shown = ack; return true
        }
        val acked: Boolean get() = shown != null
    }
}
