package com.blaze.assistant.agent

/**
 * Ack now, work in the background, report when done (HQ 22 Oct 20:35).
 *
 * 0.45.19 chat fixes (user rules): an ack is a normal Blaze message, either one short line saying what Blaze is doing
 * ("Turning on dark mode.") or nothing at all. Never a placeholder ("Got it, I'm on it. I'll message you here when it's
 * done."). A question is answered, never acked. A task that finishes within [FAST_MS] gets no ack: only its verified
 * result. The ack has no label or styling of its own; the result follows as a normal message.
 *
 * Rules (all pure, AckTest / AckBackgroundTest / ChatAck19Test):
 *  - a greeting, thanks, small talk or a question gets a normal reply and no ack ([wants]);
 *  - an ack never says the task is done and never is a placeholder ([safe]); a gateway ack that is either is dropped;
 *  - one ack per turn, whoever sends it first (the on-device ack, the gateway's `ack` event, Halo or Blaze) ([Gate]);
 *  - "Done ✓" only when every action the turn ran was verified, never on an answer or a "no" ([receipt]).
 */
object Ack {
    const val META = "ack"
    const val DONE = "Done \u2713"
    /**
     * A task that finishes within this long gets no ack at all, just its verified result (a single toggle answers in well
     * under 2 s). The on-device ack and the gateway's `ack` event both wait this long from the start of the turn and are
     * dropped when the answer came first.
     */
    const val FAST_MS = 2_000L
    const val LOCAL_DELAY_MS = FAST_MS

    /** How much longer an ack must wait before it may show, for a turn that started at [startedAt]. Pure. */
    fun holdMs(startedAt: Long, now: Long): Long = (FAST_MS - (now - startedAt)).coerceIn(0L, FAST_MS)

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

    /**
     * A question gets answered, never acked ("did you already book?", "what is Blaze phone doing"). A polite request
     * phrased as a question ("can you turn on dark mode?") is still a task.
     */
    private val askLead = Regex(
        "^(did|do|does|is|are|was|were|have|has|had|am|what|what's|whats|why|how|when|where|who|whose|which|should|shall|" +
            "isn't|aren't|didn't|doesn't|haven't|hasn't|wasn't)\\b", RegexOption.IGNORE_CASE)
    private val politeAsk = Regex("^(please\\s+)?(can|could|would|will) you\\b", RegexOption.IGNORE_CASE)
    private val politeLead = Regex(
        "^(please\\s+|(hey\\s+)?(blaze|halo)[,!]?\\s+|(can|could|would|will) you\\s+(please\\s+)?|i (want|need) you to\\s+|i'd like you to\\s+)+",
        RegexOption.IGNORE_CASE)

    /** True when [text] asks something rather than asks for something. Pure. */
    fun isQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (askLead.containsMatchIn(t)) return true
        return t.endsWith("?") && !politeAsk.containsMatchIn(t)
    }

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

    /**
     * Placeholder lines that say nothing about the task ("Got it, I'm on it. I'll message you here when it's done.",
     * Halo's "passing that straight to Blaze"). Never shown: an ack is specific or there is none.
     */
    private val generic = Regex(
        "(\\bi'?m on it\\b|^\\s*((got it|ok(ay)?|sure|alright)[,.!\\s\u2014-]*)?on it\\b|\\bi'?ll (message|text|ping|update) you\\b|\\bi'?ll let you know\\b|" +
            "\\bi'?ll get back to you\\b|\\bworking on it\\b|\\bpass(ing|ed)? (that|this|it) (straight |right )?(on |over )?to blaze\\b|" +
            "\\bpassed to blaze\\b|\\bhanding (that|this|it) (over )?to blaze\\b|\\blet me know if you need anything\\b|\\b(one|a) (moment|sec|second)\\b|" +
            "\\bgive me a (moment|sec|second)\\b|^\\s*got it[.!]?\\s*$|^\\s*halo here\\b)",
        RegexOption.IGNORE_CASE)

    /** True for a placeholder line that names nothing Blaze is doing. Pure. */
    fun isGeneric(ack: String): Boolean = generic.containsMatchIn(ack.trim())

    /** True when [text] is a task that should be acknowledged first. */
    fun wants(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("[Scheduled routine") || t.startsWith("/")) return false
        if (greeting.matches(t)) return false
        if (isQuestion(t)) return false
        return doVerb.containsMatchIn(t)
    }

    /** True when [ack] doesn't claim the task is already done and isn't a placeholder. */
    fun safe(ack: String): Boolean {
        val t = ack.trim()
        if (t.isEmpty() || t.length > 280) return false
        if (isGeneric(t)) return false
        return !doneClaim.containsMatchIn(promise.replace(t, ""))
    }

    private val reminder = Regex("\\bremind(er)?\\b", RegexOption.IGNORE_CASE)
    private val alarm = Regex("\\b(alarm|wake me)\\b", RegexOption.IGNORE_CASE)
    private val timer = Regex("\\btimer\\b", RegexOption.IGNORE_CASE)
    private val clock = Regex("\\b(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?(?=\\s|$|[,.!?])", RegexOption.IGNORE_CASE)
    private val atClock = Regex("\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?\\b", RegexOption.IGNORE_CASE)
    private val inSpan = Regex("\\bin\\s+(\\d{1,3}|an?|one|half an?)\\s*(min(ute)?s?|hours?|hrs?|h|seconds?|secs?)\\b", RegexOption.IGNORE_CASE)
    private val forSpan = Regex("\\bfor\\s+(\\d{1,3})\\s*(min(ute)?s?|hours?|hrs?|h|seconds?|secs?)\\b", RegexOption.IGNORE_CASE)
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

    // ---- the specific ack line ("Turning on dark mode.") ----

    private val gerunds = linkedMapOf(
        "turn on" to "Turning on", "turn off" to "Turning off", "switch on" to "Switching on", "switch off" to "Switching off",
        "enable" to "Turning on", "disable" to "Turning off", "search for" to "Searching for", "look up" to "Looking up",
        "schedule" to "Scheduling", "book" to "Booking", "order" to "Ordering", "buy" to "Buying", "send" to "Sending", "text" to "Texting",
        "message" to "Messaging", "email" to "Emailing", "call" to "Calling", "create" to "Creating", "make" to "Making", "add" to "Adding",
        "delete" to "Deleting", "remove" to "Removing", "switch" to "Switching", "open" to "Opening", "play" to "Playing",
        "download" to "Downloading", "save" to "Saving", "export" to "Exporting", "share" to "Sharing", "install" to "Installing",
        "update" to "Updating", "find" to "Finding", "research" to "Researching", "write" to "Writing", "draft" to "Drafting",
        "plan" to "Planning", "build" to "Building", "generate" to "Generating", "translate" to "Translating", "summarise" to "Summarising",
        "summarize" to "Summarizing", "clean" to "Cleaning", "organise" to "Organising", "organize" to "Organizing", "move" to "Moving",
        "copy" to "Copying", "rename" to "Renaming", "cancel" to "Cancelling", "track" to "Tracking", "post" to "Posting",
        "upload" to "Uploading", "record" to "Recording", "start" to "Starting", "stop" to "Stopping", "set" to "Setting",
    )
    private val verbAt = Regex("\\b(" + gerunds.keys.joinToString("|") { it.replace(" ", "\\s+") } + ")\\b", RegexOption.IGNORE_CASE)
    private val tailJunk = Regex("(\\s*,?\\s*(please|pls|plz|for me|right now|now|asap|thanks|thank you))+\\s*[.!?\u2026]*$", RegexOption.IGNORE_CASE)
    private val clauseCut = Regex(",|;|\\s\u2014\\s|\\s-\\s|\\.\\s|\\b(and then|then|because|so that)\\b", RegexOption.IGNORE_CASE)
    /** The longest echoed object; a longer one gives no ack rather than a cut-off line. */
    const val OBJECT_MAX = 64

    /** First person to second person in the echoed part ("my alarm" -> "your alarm"). Pure. */
    fun yours(s: String): String = s
        .replace(Regex("\\bI'm\\b", RegexOption.IGNORE_CASE), "you're").replace(Regex("\\bI am\\b"), "you are")
        .replace(Regex("\\bmyself\\b", RegexOption.IGNORE_CASE), "yourself").replace(Regex("\\bmine\\b", RegexOption.IGNORE_CASE), "yours")
        .replace(Regex("\\bmy\\b", RegexOption.IGNORE_CASE), "your").replace(Regex("\\bme\\b", RegexOption.IGNORE_CASE), "you")
        .replace(Regex("\\bI\\b"), "you")

    /** "Booking a room on automationintesting.online for next weekend." for the message, or null (no clear object). Pure. */
    fun describe(text: String): String? {
        val t = politeLead.replace(text.trim(), "").trim()
        val m = verbAt.find(t) ?: return null
        val verb = m.value.lowercase().replace(Regex("\\s+"), " ")
        var obj = t.substring(m.range.last + 1)
        clauseCut.find(obj)?.let { obj = obj.substring(0, it.range.first) }
        obj = tailJunk.replace(obj.trim(), "").trim().trimEnd('.', '!', '?', ',', ':', ';').trim()
        if (obj.isEmpty() || obj.length > OBJECT_MAX) return null
        val g = gerunds[verb] ?: return null
        return ("$g " + yours(obj) + ".").replace(Regex("\\s+"), " ")
    }

    /**
     * The on-device ack for [text] (no model, under a second): a short line saying what Blaze is doing, or null when it
     * shouldn't get one or there is nothing specific to say (never a placeholder). Never says done.
     */
    fun local(text: String): String? {
        if (!wants(text)) return null
        val w = whenPart(text).let { if (it.isEmpty()) "" else " $it" }
        // "delete my 7am alarm" is a delete, not "Setting an alarm".
        val verb = verbAt.find(politeLead.replace(text.trim(), ""))?.value?.lowercase()?.replace(Regex("\\s+"), " ")
        val undoing = verb != null && verb in setOf("delete", "remove", "cancel", "stop", "turn off", "disable", "switch off", "move", "rename", "update", "snooze")
        val a = when {
            undoing -> describe(text)
            timer.containsMatchIn(text) -> forSpan.find(text)?.let { f ->
                val u = f.groupValues[2].lowercase()
                val unit = when { u.startsWith("h") -> "hour"; u.startsWith("s") -> "second"; else -> "minute" }
                "Starting a ${f.groupValues[1]}-$unit timer."
            } ?: "Starting a timer$w."
            alarm.containsMatchIn(text) -> "Setting an alarm$w."
            reminder.containsMatchIn(text) -> "Setting a reminder$w."
            else -> describe(text)
        } ?: return null
        return a.takeIf { safe(it) }
    }

    /** The task chip's short label while the work runs in the background ("Reminder", "Task"). */
    fun chip(text: String): String = when {
        timer.containsMatchIn(text) -> "Timer"
        alarm.containsMatchIn(text) -> "Alarm"
        reminder.containsMatchIn(text) -> "Reminder"
        else -> "Task"
    } + " \u00b7 in progress"

    /** Tools that only look (read a page, check status, search): they never make a turn "Done ✓". Pure. */
    fun isReadOnly(tool: String): Boolean =
        Regex("(status|read|screenshot|look|search|fetch|list|get|wait|about|docs|recall|query|check|show|describe|find)", RegexOption.IGNORE_CASE)
            .containsMatchIn(tool)

    private val negative = Regex(
        "^(no\\b|not\\b|nope\\b|i (couldn't|could not|can't|cannot|didn't|did not|wasn't|haven't)\\b|unfortunately\\b|sorry\\b)", RegexOption.IGNORE_CASE)

    /**
     * The finished message for an acknowledged turn: "Done ✓ <reply>" when every action it ran was verified and the
     * reply wasn't rewritten as a failure; otherwise the reply as it is (ClaimCheck already made it honest).
     * [verified] / [unproven] = action tools whose last result was verified / failed or unconfirmed (read-only tools are
     * left out, [isReadOnly]). Never on the answer to a question ([asked]) or a reply that says no / couldn't
     * (0.45.19 phone: "Done ✓ No, the booking was not completed").
     */
    fun receipt(reply: String, verified: Int, unproven: Int, rewrote: Boolean, asked: String = ""): String {
        val r = reply.trim()
        if (r.isEmpty() || r.startsWith(DONE)) return r
        if (verified == 0 || unproven > 0 || rewrote) return r
        if (asked.isNotBlank() && isQuestion(asked)) return r
        if (negative.containsMatchIn(r)) return r
        return "$DONE $r"
    }

    /** One ack per turn: the first [take] wins, later ones (the gateway after a local ack, Halo after Blaze) are dropped. */
    class Gate(val at: Long = System.currentTimeMillis()) {
        @Volatile var shown: String? = null; private set
        @Volatile var finished = false
        /** The user message the ack answered (the task ledger's key). */
        @Volatile var user = 0L
        /** What the user said (a question never ends in "Done ✓", [receipt]). */
        @Volatile var said = ""
        /** True when [ack] should be shown now (nothing shown yet, the turn hasn't answered yet, and it is safe). */
        @Synchronized fun take(ack: String): Boolean {
            if (shown != null || finished || !safe(ack)) return false
            shown = ack; return true
        }
        val acked: Boolean get() = shown != null
    }
}
