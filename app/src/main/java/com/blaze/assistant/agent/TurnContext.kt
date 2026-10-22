package com.blaze.assistant.agent

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * v0.45.9: the real context Blaze and Halo get every turn, so they can be proactive instead of a "How can I assist you
 * today?" chatbot. One compact block (≤ [MAX] chars) at the END of the app's second system message (after the shared
 * persona, which stays byte-identical first). Format agreed with Blaze Site (persona.json v1.0.1 `context_block`):
 *
 *   [context]
 *   now: Wed 21 Oct 2026 10:15, Africa/Lusaka (UTC+02:00)
 *   weather: 29°C Clear, Ndola (Open-Meteo 10:00)
 *   schedule Wed 21 Oct 2026: 08:00 CIT class; 14:00 NET class
 *   next after that day (not that day): Sun 25 Oct 08:30 Church
 *   open: reply to Stefano about Thu 9:00
 *   new in 0.45.8: send starts instantly; theme on every screen; full-screen Design
 *   [/context]
 *
 * `schedule <date>:` lines are resolved on the phone ([ScheduleDay]) for the dates the message asks about (today when
 * it names none); the raw reminder list is never in the block. Lines with nothing to say are left out; `now:` and the
 * schedule lines always stay. Pure; the Android side ([TurnContextSource]) gathers the inputs off the main thread and
 * [Cache] keeps the block ~60 s (invalidated when routines, alarms, weather or follow-ups change), so the send path
 * never waits for it and never makes a network call.
 */
object TurnContext {
    const val MAX = 600
    const val TTL_MS = 60_000L
    /** Turns after an update that still get the "new in" line. */
    const val NEW_IN_TURNS = 3

    /** What's new in the version the user just updated to (short, plain words). */
    val NEW_IN: Map<String, String> = mapOf(
        "0.45.8" to "send starts instantly; theme on every screen; full-screen Design",
        "0.45.9" to "Blaze knows today's schedule, weather and open tasks; no memory saves for small talk",
    )
    /** The release whose news is shown (0.45.9 users still hear about 0.45.8 first, as agreed). */
    const val NEW_IN_VERSION = "0.45.8"

    data class Inputs(
        val now: ZonedDateTime,
        val weather: String? = null,
        val schedule: List<ScheduleDay.Entry> = emptyList(),
        val dates: List<LocalDate> = emptyList(),
        val open: List<String> = emptyList(),
        val newIn: String? = null,
        /** The device calendar can't be read yet: the block says so, and calendar_events shows Android's one-tap prompt. */
        val calendarOff: Boolean = false,
    )

    private val nowFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.ENGLISH)

    /** "Wed 21 Oct 2026 10:15, Africa/Lusaka (UTC+02:00)". */
    fun nowLine(now: ZonedDateTime): String {
        val off = now.offset.id.let { if (it == "Z") "+00:00" else it }
        return "now: " + now.format(nowFmt) + ", " + now.zone.id + " (UTC" + off + ")"
    }

    fun block(i: Inputs): String {
        val zone: ZoneId = i.now.zone
        val today = i.now.toLocalDate()
        val dates = i.dates.ifEmpty { listOf(today) }
        val nowT = i.now.toLocalTime().withSecond(0).withNano(0)
        val sched = dates.map { d -> ScheduleDay.contextLine(d, ScheduleDay.itemsOn(d, zone, i.schedule), if (dates.size > 2) 3 else 6, if (d == today) nowT else null) }
        val next = ScheduleDay.next(dates.max(), zone, i.schedule)?.let { "$NEXT$it" }
        val weather = i.weather?.takeIf { it.isNotBlank() }?.let { "weather: " + it.trim() }
        val open = i.open.filter { it.isNotBlank() }.take(3).takeIf { it.isNotEmpty() }?.let { l -> "open: " + l.joinToString("; ") { it.trim().take(70) } }
        val newIn = i.newIn?.takeIf { it.isNotBlank() }
        val calOff = if (i.calendarOff) "calendar: access not allowed yet; to show it call calendar_events (it shows Android's one-tap prompt), never say you can't access it" else null
        val head = nowLine(i.now)
        fun render(lines: List<String?>) = (listOf("[context]", head) + lines.filterNotNull() + "[/context]").joinToString("\n")
        // Drop the least useful lines first until it fits: new in, open (shortened), next, weather; then shorten schedule.
        val optional = mutableListOf(weather, calOff, next, open, newIn)
        var out = render(sched + optional)
        if (out.length <= MAX) return out
        for (k in listOf(4, 3, 2, 0, 1)) { optional[k] = null; out = render(sched + optional); if (out.length <= MAX) return out }
        val cut = sched.map { if (it.length > 110) it.take(107) + "\u2026" else it }
        out = render(cut)
        return if (out.length <= MAX) out else out.take(MAX - 11) + "\n[/context]"
    }

    /** The "new in" line for the first [NEW_IN_TURNS] turns after an update; [shown] = turns already shown for [seenFor]. */
    fun newInLine(version: String, seenFor: String, shown: Int): String? {
        val notes = NEW_IN[version] ?: return null
        if (seenFor == version && shown >= NEW_IN_TURNS) return null
        return "new in $version: $notes"
    }

    /** v0.45.9 replay: "next:" alone made codestral read Monday's class into Sunday's list, so the label says it's another day. */
    const val NEXT = "next after that day (not that day): "

    /** The stable rule (static part of the prompt, not the block): how to use it. */
    const val RULE = "\nCONTEXT: the [context] block at the end of this message is real and current. For any date or schedule question use only its now: and schedule <date>: lines; never guess the day or move an item to another day; if a line says nothing scheduled, say so and offer one useful next step. " +
        "The schedule line already holds that date's reminders, alarms and calendar events, earlier ones included: when asked what's on, name every item on it with its time, and call an alarm an alarm and a reminder a reminder (they can have different times). " +
        "An item marked (passed) is over: never offer it or describe it as upcoming, next or still to do. For \"anything else\", \"what's left\", \"to do today\" or \"what's next\" name only the items still ahead; if none are, say nothing else is on today. " +
        "An empty calendar_events or list_routines result never means the day is empty. " +
        "Each schedule item is a time plus one quoted title (a time inside the quotes is part of the title); the (N items) count is how many that day has, so never answer nothing scheduled when it has items, even ones earlier that day. Items on the next after that day line are on another day: never list them as that day's. " +
        "On greetings, small talk, thanks or \"what should I do\", answer in a few words, then offer 1 to 3 concrete next steps named from that block (an item, the weather, an open task). Don't repeat suggestions already offered in this conversation."

    /** v0.45.9: the tools a calendar / meetings / schedule question always carries (with the [context] schedule lines). */
    val SCHEDULE_TOOLS = setOf("calendar_events", "list_routines", "processes", "create_routine")
    private val scheduleRe = Regex("\\b(calendars?|meetings?|events?|schedules?|agenda|appointments?|reminders?|alarms?|classes|class|lectures?|upcoming|anything on|what'?s on|whats on|what is on|what do i have|do i have anything|am i busy|am i free|" +
        // v0.45.13 (phone 17:11): "anything else to do today?" / "what's left today" / "anything other than those".
        "anything else (?:to do|on|planned|scheduled|today|for today|left)|what else (?:is on|do i have|today|is there today)|anything other than (?:those|that|these)|what'?s left (?:today|for today|to do)|whats left|anything left (?:today|to do)|left to do today|to do today|rest of (?:the|my) day|what'?s next today)\\b", RegexOption.IGNORE_CASE)

    // v0.45.9: a short yes to an offer ("@halo Yes" after "I can check your Google Calendar, want me to?").
    private val yesRe = Regex("^(?:@(?:halo|blaze|all)\\s+)?(?:yes|yeah|yea|yep|yup|ya|sure|ok|okay|alright|please|go ahead|do it|go for it|check it|please do|yes please|sure thing|of course|definitely)(?:[\\s,]+(?:please|thanks|thank you|go ahead|do it|check|check it|sure|ok|okay))*[\\s.!]*$", RegexOption.IGNORE_CASE)
    private val offerRe = Regex("\\b(calendars?|meetings?|events?|schedules?|agenda|appointments?|reminders?|routines?|classes|class|lectures?|what'?s on|your day|today'?s plan)\\b", RegexOption.IGNORE_CASE)

    /** A yes to the previous reply's calendar / schedule / reminder offer: the turn needs the same tools as the ask itself. */
    fun scheduleFollowUp(text: String, lastReply: String): Boolean =
        yesRe.matches(text.trim().replace('\u2019', '\'')) && offerRe.containsMatchIn(lastReply)

    /** A question or request about the calendar, meetings, reminders or a day's schedule ("View calendar", "anything on for today?"). */
    fun scheduleAsk(text: String): Boolean = text.replace('\u2019', '\'').let { t -> scheduleRe.containsMatchIn(t) && !actionRe.containsMatchIn(t) }

    /**
     * 0.45.15 (device /phoneqa 22:40, alarm-off 0/5): "turn off the QA wake alarm" matched "alarms?" and, with no tool run,
     * ScheduleTruth rewrote the reply into today's agenda. An ask to change something is an action, not a schedule question.
     */
    private val actionRe = Regex("(?i)^\\s*(?:please\\s+|can you\\s+|could you\\s+)?(?:turn|switch|shut|set|remove|delete|cancel|disable|dismiss|stop|kill|clear|add|create|make|put|schedule|book|snooze|move|change|edit|rename)\\b")

    // ---------------- cache ----------------

    /** Keeps the newest block per key for [TTL_MS]; [invalidate] drops it when its inputs change. */
    class Cache(private val ttl: Long = TTL_MS) {
        private data class E(val key: String, val at: Long, val text: String, val gen: Long)
        @Volatile private var e: E? = null
        @Volatile private var gen = 0L
        @Volatile var hits = 0; private set
        @Volatile var builds = 0; private set

        fun invalidate() { gen++ }

        /** The cached block when fresh, else null (never builds). */
        fun peek(key: String, now: Long): String? = e?.takeIf { it.key == key && it.gen == gen && now - it.at in 0 until ttl }?.text

        fun get(key: String, now: Long, build: () -> String): String {
            peek(key, now)?.let { hits++; return it }
            val g = gen
            val t = build(); builds++
            e = E(key, now, t, g)
            return t
        }
    }

    /** Cache key: the minute, the zone and the dates asked (a new minute, zone or date set builds a new block). */
    fun key(now: ZonedDateTime, dates: List<LocalDate>): String =
        now.zone.id + "|" + now.toLocalDate() + "|" + dates.joinToString(",") + "|" + (now.hour * 60 + now.minute) / 1

    val cache = Cache()
}
