package com.blaze.assistant.agent

/**
 * v0.45.13 verify-after-act (phone-control sweep 0). A phone action is "done" only when the phone READ IT BACK:
 * the accessibility tree, the Clock / Calendar / Contacts providers, Settings, NotificationManager (DND), Wi-Fi /
 * Bluetooth state, the active media session. Tools that check their own effect end their result with one of two
 * markers, and [ToolVerify.classify] trusts only those:
 *
 *   "Checked on the phone: <what was read back>."   -> verified
 *   "Not confirmed: <what is left> Next step: <…>"    -> unverified (the model must say so; never "done")
 *
 * Seen on a phone (0.45.9, Wed 21 Oct 2026 17:13 CAT): "remove the alarm" answered "Alarm removed." and posted it as a
 * notification while Samsung Clock still showed "… (Blaze A2)" 18:15 switched ON. process(delete) only fired
 * AlarmClock.ACTION_DISMISS_ALARM, dropped the registry row and its result was classed verified by default.
 *
 * Also here, pure (ActCheckTest):
 *  - [GoalCheck]: opening an app (or a Settings page) is step one, never the goal. A final "done" for a multi-step ask
 *    whose only verified results are opens is caught and the flow continues (one nudge), then said honestly.
 *  - [TimeEcho]: a reply about an alarm / timer / reminder Blaze set this turn says the time that was really set.
 *  - [PhoneCases]: the strict case set (each with its proof tools and verifier) used by the in-app self-test runner
 *    (PhoneQa) and the model-side replay.
 */
object ActCheck {
    const val OK = "Checked on the phone:"
    const val NOT = "Not confirmed:"

    fun checked(evidence: String) = "$OK ${evidence.trim().trimEnd('.')}."
    fun notConfirmed(what: String, next: String) = "$NOT ${what.trim().trimEnd('.')}. Next step: ${next.trim().trimEnd('.')}."

    /** The verdict a self-checking tool's own words give, or null when it carries no marker. */
    fun verdictOf(result: String): ToolVerify.Verdict? {
        val r = result.trim()
        val iNot = r.indexOf(NOT); val iOk = r.indexOf(OK)
        // The last marker wins (a tool that first saw it on, then off, ends with the final reading).
        return when {
            iNot >= 0 && iNot > iOk -> ToolVerify.Verdict(ToolVerify.Status.UNVERIFIED, r.substring(iNot + NOT.length).trim().substringBefore(" Next step:").trimEnd('.').take(160))
            iOk >= 0 -> ToolVerify.Verdict(ToolVerify.Status.VERIFIED, r.substring(iOk + OK.length).trim().trimEnd('.').take(160))
            else -> null
        }
    }
}

/** v0.45.13: opening an app is step 1 of a phone task, never success by itself. Pure. */
object GoalCheck {
    /** Tools that only bring something to the front. */
    val OPENERS = setOf("open_app", "open_settings_page", "open_settings", "open_url", "open_camera")

    /** Asks that need more than an app in front (play X, change a setting, directions, draft, add, delete…). */
    private val multiStep = Regex("(?i)\\b(play|watch|search|find|turn (?:on|off)|switch (?:on|off)|enable|disable|change|set|toggle|" +
        "directions?|navigate|route|draft|write|text|message|add|create|delete|remove|cancel|call|send|book|order|post)\\b")
    /** "open YouTube" / "launch WhatsApp" / "open Display settings" alone: opening IS the goal. */
    private val openOnly = Regex("(?i)^\\s*(?:@\\w+\\s+)?(?:please\\s+|can you\\s+|could you\\s+)?(?:open|launch|start|show(?: me)?|go to)\\s+(?:the\\s+|my\\s+)?[\\w .'-]{1,40}?(?:\\s+(?:app|page|screen|settings))?[\\s.!?]*$")
    private val doneWords = Regex("(?i)\\b(done|all set|it'?s (?:on|off|playing|set|added|saved|ready)|is (?:now )?(?:on|off|playing|set|added|saved)|i(?:'ve| have)? (?:turned|switched|changed|set|played|started|added|created|drafted|deleted|removed)|now playing|playing now|here (?:are|is) (?:the )?directions|directions (?:are )?(?:up|ready|open))\\b")

    /** True when [userText] asks for something beyond bringing an app to the front. */
    fun needsMore(userText: String): Boolean = !openOnly.matches(userText.trim()) && multiStep.containsMatchIn(userText)

    /**
     * The turn tried to end with a "done" while every VERIFIED action this turn was only an opener: the flow must go
     * on. [done] = this turn's verdicts in order.
     */
    fun openedOnly(userText: String, reply: String, done: List<ClaimCheck.Done>): Boolean {
        if (!needsMore(userText) || !doneWords.containsMatchIn(reply)) return false
        val acts = done.filter { Receipt.isAction(it.name) && it.name !in setOf("find_tool", "load_tools", "read_screen", "look_at_screen", "wait", "processes") }
        if (acts.isEmpty()) return false
        val verified = acts.filter { it.status == ToolVerify.Status.VERIFIED }
        return verified.isNotEmpty() && verified.all { it.name in OPENERS && !it.why.contains("shows a route") }
    }

    const val NUDGE = "[Blaze check] Opening the app is only the first step: the user's task isn't done yet. Continue the flow now " +
        "(read_screen, then tap / type / search with the right tool) until the result is read back with verified=true, then reply. " +
        "If you can't finish, say plainly what is left."

    fun honest(appOpened: String): String = "I opened $appOpened, but I couldn't finish the rest or confirm it worked. Tell me to try again and I'll carry it through."
}

/**
 * v0.45.13 replay round 1 (gateway 0.8.12, "remove the alarm" with a Clock read-back that still shows it ON): 2 of 5
 * replies said only "Open the Clock app and switch it off." without saying the attempt wasn't confirmed. When a phone
 * action's last result this turn is unconfirmed and the reply doesn't say so, the reply gets one honest line first.
 * Pure; mirrored in rel/check_replay_phone.py.
 */
object UnconfirmedEcho {
    val ACTIONS = setOf("process", "set_alarm", "set_timer", "compose_sms", "add_contact", "add_calendar_event", "device_setting", "phone_mode",
        "tap", "search_in_app", "open_url", "navigate_to", "type_text")
    val HONEST = Regex("(?i)\\b(couldn'?t|could not|can'?t confirm|cannot confirm|not confirmed|unconfirmed|isn'?t confirmed|didn'?t|did not|wasn'?t able|unable|failed|still (?:reads|shows|on|there|listed))\\b")

    /** The unconfirmed evidence the reply must own up to, or null. */
    fun open(done: List<ClaimCheck.Done>): String? {
        val acts = done.filter { it.name in ACTIONS }
        // the newest unconfirmed action that no later result of the same tool, and no later verified action, settled
        val i = acts.indices.lastOrNull { k -> acts[k].status == ToolVerify.Status.UNVERIFIED &&
            (k + 1 until acts.size).none { j -> acts[j].name == acts[k].name || acts[j].status == ToolVerify.Status.VERIFIED } } ?: return null
        return acts[i].why.ifBlank { "the phone didn't read it back" }
    }

    fun fix(reply: String, done: List<ClaimCheck.Done>): String {
        val why = open(done) ?: return reply
        if (HONEST.containsMatchIn(reply)) return reply
        return "I couldn't confirm it: ${why.trim().trimEnd('.')}. " + reply.trimStart()
    }
}

/**
 * v0.45.13 (phone 17:12): the chat said a time that wasn't the one set. A reply that names an alarm / timer / reminder
 * Blaze set THIS turn must say the time that was really set ([actual], "18:15"). Pure.
 */
object TimeEcho {
    private val timeRe = Regex("(?i)\\b([01]?\\d|2[0-3])[:.]([0-5]\\d)(?:\\s*([ap])\\.?m\\.?)?\\b")
    private val aboutRe = Regex("(?i)\\b(alarms?|wake you|wake-up)\\b")

    /** "18:15" for a 24 h hour and minute. */
    fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)

    private fun to24(h: Int, ampm: String?): Int = when {
        ampm == null -> h
        ampm.equals("p", true) && h < 12 -> h + 12
        ampm.equals("a", true) && h == 12 -> 0
        else -> h
    }

    /**
     * [reply] with any time in a sentence about the alarm / reminder that differs from the only time set this turn
     * replaced by it. Several different times set this turn = left alone (can't tell which is which).
     */
    fun fix(reply: String, actual: List<String>): String {
        val set = actual.distinct()
        if (set.size != 1) return reply
        val want = set.first()
        val parts = reply.split(Regex("(?<=[.!?\\n])"))
        return parts.joinToString("") { s ->
            if (!aboutRe.containsMatchIn(s)) s else timeRe.replace(s) { m ->
                val h = to24(m.groupValues[1].toInt(), m.groupValues[3].ifBlank { null })
                val t = hm(h, m.groupValues[2].toInt())
                if (t == want) m.value else want
            }
        }
    }

    /** The HH:mm values the alarm / timer tools set this turn, read from their results ("Alarm set for 18:15"). */
    fun setTimes(results: List<String>): List<String> = results.mapNotNull { r ->
        Regex("Alarm set for (\\d{2}:\\d{2})").find(r)?.groupValues?.get(1)
    }
}

/**
 * v0.45.13 strict phone-control case set (user via HQ, 21 Oct 17:16). Each case: what the user says, the tools that can
 * PROVE it (one must come back verified), the openers that are only allowed on the way, and the verifier the in-app
 * runner reads back after the turn. Shared by PhoneQa (device) and PhoneReplayRequestTest (model side). Pure.
 */
object PhoneCases {
    enum class Verifier { ALARM_SET, ALARM_OFF, CALENDAR_EVENT, DRAFT_NOT_SENT, MEDIA_PLAYING, SETTING_ON, TIMER_RUNNING, SETTINGS_VALUE, CONTACT_SAVED, MAPS_DIRECTIONS, OVERLAY_TAP }

    data class Case(val id: String, val prompt: String, val proof: Set<String>, val verifier: Verifier,
                    /** What the verifier reads back (shown in the report). */
                    val reads: String,
                    /** Words the final reply must not contain (premature or false claims), lower case. */
                    val mustNotSay: List<String> = emptyList(),
                    /** Extra values the verifier needs (label, time, title…). */
                    val expect: Map<String, String> = emptyMap())

    val ALL: List<Case> = listOf(
        Case("alarm-set", "set an alarm for 6:40 am called QA wake", setOf("set_alarm"), Verifier.ALARM_SET,
            "AlarmManager.nextAlarmClock + the Clock alarm list (label and time)", expect = mapOf("time" to "06:40", "label" to "QA wake")),
        Case("alarm-off", "turn off the QA wake alarm", setOf("process"), Verifier.ALARM_OFF,
            "the Clock alarm list: the row labelled QA wake reads off or is gone", expect = mapOf("label" to "QA wake", "time" to "06:40")),
        Case("calendar-event", "add a calendar event QA review tomorrow at 15:00 for 30 minutes", setOf("add_calendar_event", "gcal_create"), Verifier.CALENDAR_EVENT,
            "CalendarContract.Instances: an event titled QA review tomorrow 15:00", expect = mapOf("title" to "QA review", "time" to "15:00")),
        Case("draft-only", "draft a text to 0977000000 saying QA draft test, don't send it", setOf("compose_sms"), Verifier.DRAFT_NOT_SENT,
            "the messaging app's compose box holds the text; nothing in the sent SMS box", mustNotSay = listOf("sent", "message sent", "delivered"),
            expect = mapOf("text" to "QA draft test", "number" to "0977000000")),
        Case("youtube-play", "play Despacito by Luis Fonsi on YouTube", setOf("search_in_app", "tap"), Verifier.MEDIA_PLAYING,
            "MediaSessionManager: YouTube's session is PLAYING and its title matches", expect = mapOf("pkg" to "com.google.android.youtube", "words" to "despacito")),
        Case("settings-toggles", "turn on Wi-Fi, Bluetooth and Do Not Disturb", setOf("device_setting", "toggle_setting"), Verifier.SETTING_ON,
            "WifiManager.isWifiEnabled, BluetoothAdapter.isEnabled, NotificationManager.currentInterruptionFilter", expect = mapOf("settings" to "wifi,bluetooth,do_not_disturb")),
        Case("timer", "set a timer for 2 minutes", setOf("set_timer"), Verifier.TIMER_RUNNING,
            "the Clock app's ongoing timer notification (or the timer screen)", expect = mapOf("seconds" to "120")),
        Case("settings-value", "open display settings and turn dark mode on", setOf("tap", "device_setting"), Verifier.SETTINGS_VALUE,
            "UiModeManager night mode / Settings.Secure ui_night_mode, and the switch in the tree", expect = mapOf("setting" to "dark_mode", "value" to "on")),
        Case("contact-add", "add a contact QA Tester with number 0977123456", setOf("add_contact"), Verifier.CONTACT_SAVED,
            "ContactsContract: a contact named QA Tester with 0977123456", expect = mapOf("name" to "QA Tester", "phone" to "0977123456")),
        Case("maps-directions", "get directions to Levy Junction Mall in Google Maps", setOf("navigate_to", "open_url"), Verifier.MAPS_DIRECTIONS,
            "Maps in front and its screen names the destination with a route (min / km / Start)", expect = mapOf("place" to "Levy Junction")),
        // v0.45.14 (user, live 0.45.13 21:38: the pointer overlay paused every action): a tap under the overlay must go
        // through with the pointer shown, and nothing in the turn may say it paused.
        Case("overlay-taps", "open Do Not Disturb settings and turn it on", setOf("tap"), Verifier.OVERLAY_TAP,
            "NotificationManager.currentInterruptionFilter (DND on), the pointer was on screen this run, and no step or result says paused",
            mustNotSay = listOf("paused"), expect = mapOf("setting" to "do_not_disturb")),
    )

    /** v0.45.14: a step, tool result or reply that says the run paused (there must be none). */
    fun pausedIn(text: String): Boolean = Regex("(?i)\\bpaus(?:e|ed|ing)\\b").containsMatchIn(text)

    fun byId(id: String): Case? = ALL.firstOrNull { it.id == id }

    /** One run's model-side judgement: a proof tool came back verified, no false claim, no "done" before that. */
    data class Judge(val pass: Boolean, val why: String)

    fun judge(case: Case, calls: List<Pair<String, ToolVerify.Status>>, reply: String): Judge {
        val sentences = reply.lowercase().split(Regex("(?<=[.!?\\n])\\s*")).filter { it.isNotBlank() }
        val neg = Regex("\\b(not|never|n't|won't|until|unless|before|once you|when you|if you|tap send|press send)\\b|n't\\b")
        val bad = case.mustNotSay.firstOrNull { w -> sentences.any { s -> Regex("(?<![a-z])" + Regex.escape(w) + "(?![a-z])").containsMatchIn(s) && !neg.containsMatchIn(s) } }
        if (bad != null) return Judge(false, "reply says \u201c$bad\u201d")
        val proven = calls.any { (n, s) -> n in case.proof && s == ToolVerify.Status.VERIFIED }
        if (!proven) return if (Regex("(?i)\\b(couldn'?t|could not|not confirmed|didn'?t|did not|wasn'?t|unable)\\b").containsMatchIn(reply))
            Judge(false, "no proof tool verified (reply was honest about it)") else Judge(false, "no proof tool verified")
        return Judge(true, "proof: " + calls.filter { it.first in case.proof && it.second == ToolVerify.Status.VERIFIED }.joinToString { it.first })
    }
}
