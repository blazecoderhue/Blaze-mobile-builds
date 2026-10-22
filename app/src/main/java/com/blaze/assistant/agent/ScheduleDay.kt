package com.blaze.assistant.agent

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * v0.45.9: the user's schedule resolved ON THE PHONE into the actual items of one date.
 *
 * Seen on a phone (0.45.8, Wed 21 Oct 2026 10:14 CAT): "anything on for today?" got the raw reminder list back and
 * "so what's on for today specifically?" got "Today you have church from 9:00am to 12:30pm": a SUNDAY reminder told as
 * Wednesday's. The model was handed undated rules (list_routines: "Church: 08:30 Sun …") and picked the wrong one.
 *
 * Now every source is expanded here, for a given local date in the phone's IANA zone:
 *  - Blaze reminders / routines (Routines): a weekday mask (Mon=1 … Sun=64; 0 = once at its next time), a dated one-off
 *    (`at`), or every-N-minutes jobs;
 *  - Clock alarms Blaze set (Processes kind "alarm": a due time, optional weekday mask);
 *  - calendar events: the provider's Instances (Android already expands RRULEs there) or, for anything that only has
 *    a start + RRULE, [RRule] below;
 * and only that date's items (with times) are sent, labelled with the date: `schedule Wed 21 Oct 2026: 08:00 CIT class`.
 * Pure (java.time only), so ScheduleDayTest covers daily / weekdays / one weekly day / one-offs / across midnight / DST.
 */
object ScheduleDay {
    /** Mon=1 … Sun=64, the Routines mask. */
    fun bit(d: DayOfWeek): Int = 1 shl (d.value - 1)
    const val EVERY_DAY = 127
    const val WEEKDAYS = 31

    sealed class Rule {
        /** A time of day on the days in [mask] (Routines.days; 0 = once, at the next [time] after [created]). */
        data class Weekly(val mask: Int, val time: LocalTime, val created: Instant? = null) : Rule()
        /** One moment (a dated reminder, a one-off alarm). */
        data class Once(val at: Instant) : Rule()
        /** Every N minutes (a shell job). Shown as one line per day, not every run. */
        data class Every(val minutes: Int) : Rule()
        /** A start, a duration and an RRULE (RFC 5545 subset: DAILY / WEEKLY / MONTHLY / YEARLY, INTERVAL, BYDAY, BYMONTHDAY, COUNT, UNTIL). */
        data class Recurring(val start: ZonedDateTime, val minutes: Long, val rrule: String) : Rule()
        /** A concrete span (a calendar instance the provider expanded already). [allDay] events have no times. */
        data class Span(val start: Instant, val end: Instant, val allDay: Boolean = false) : Rule()
    }

    data class Entry(val title: String, val kind: String, val rule: Rule, val enabled: Boolean = true)

    /** One resolved item on a date. [from]/[to] are local times (null = all day / no time). */
    data class Item(val title: String, val kind: String, val from: LocalTime?, val to: LocalTime? = null,
                    val note: String = "") {
        /** [quoted] (the [context] block): the title in quotes, so a time inside it ("Church 9:00–12:30") stays part of it. */
        fun label(quoted: Boolean = false, passed: Boolean = false): String = buildString {
            if (from != null) { append(hm(from)); if (to != null && to != from) append("\u2013").append(hm(to)); append(' ') }
            else append("all day ")
            if (quoted) append('"').append(title).append('"') else append(title)
            if (kind == "alarm") append(" (alarm)")
            if (note.isNotBlank()) append(" (").append(note).append(')')
            if (passed) append(" (passed)")
        }

        /**
         * v0.45.13 (phone 17:11: a 10:15 class offered as still to do): over by [now] on its own date. An item with an
         * end is over at its end, one without at its start; all-day items never are.
         */
        fun passed(now: LocalTime?): Boolean {
            if (now == null || from == null || note.isNotBlank()) return false
            // A time span inside the title ("Church 9:00–12:30") is when it really ends; a reminder or class with no end
            // is still "on" for an hour; an alarm is over once it has rung.
            val end = to ?: titleEnd(title)?.takeIf { it.isAfter(from) }
                ?: if (kind == "alarm") from else (if (from.isAfter(LocalTime.of(22, 59))) LocalTime.of(23, 59) else from.plusHours(1))
            return !end.isAfter(now)
        }
    }

    private val spanRe = Regex("""\b\d{1,2}[:.]\d{2}\s*(?:am|pm)?\s*[\u2013\u2014-]\s*(\d{1,2})[:.](\d{2})\s*(am|pm)?""", RegexOption.IGNORE_CASE)

    /** "Church 9:00–12:30" → 12:30 (24 h; "pm" adds 12). */
    fun titleEnd(title: String): LocalTime? = spanRe.find(title)?.let { m ->
        var h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (m.groupValues[3].equals("pm", true) && h < 12) h += 12
        if (h in 0..23 && mi in 0..59) LocalTime.of(h, mi) else null
    }

    fun hm(t: LocalTime): String = "%02d:%02d".format(Locale.US, t.hour, t.minute)

    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    private val shortFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    /** "Wed 21 Oct 2026" (English, fixed: this is for the model, not shown to the user). */
    fun dayLabel(d: LocalDate): String = d.format(dayFmt)
    fun shortLabel(d: LocalDate): String = d.format(shortFmt)

    /** The items of [entries] on [date] in [zone], sorted by time (all-day first). */
    fun itemsOn(date: LocalDate, zone: ZoneId, entries: List<Entry>): List<Item> {
        val out = ArrayList<Item>()
        for (e in entries) {
            if (!e.enabled) continue
            when (val r = e.rule) {
                is Rule.Weekly -> {
                    if (r.mask == 0) {
                        // Once at the next time after it was made (Routines.next): only that one date.
                        val c = r.created ?: continue
                        val made = c.atZone(zone)
                        var d = made.toLocalDate()
                        if (!made.toLocalTime().isBefore(r.time)) d = d.plusDays(1)
                        if (d == date) out += Item(e.title, e.kind, r.time)
                    } else if (r.mask and bit(date.dayOfWeek) != 0) out += Item(e.title, e.kind, r.time)
                }
                is Rule.Once -> {
                    val z = r.at.atZone(zone)
                    if (z.toLocalDate() == date) out += Item(e.title, e.kind, z.toLocalTime().truncatedTo(ChronoUnit.MINUTES))
                }
                is Rule.Every -> if (r.minutes > 0) out += Item(e.title, e.kind, null, note = "every ${if (r.minutes % 60 == 0) "${r.minutes / 60} h" else "${r.minutes} min"}")
                is Rule.Span -> spanOn(e, r.start.atZone(zone), r.end.atZone(zone), r.allDay, date)?.let { out += it }
                is Rule.Recurring -> {
                    val local = r.start.withZoneSameInstant(zone)
                    // An instance that started the day before can run into [date] (across midnight).
                    for (d in listOf(date.minusDays(1), date)) {
                        if (!RRule.occursOn(r.rrule, local, d)) continue
                        val s = ZonedDateTime.of(d, local.toLocalTime(), zone)
                        spanOn(e, s, s.plusMinutes(r.minutes), false, date)?.let { out += it }
                    }
                }
            }
        }
        return out.sortedWith(compareBy<Item>({ it.from != null }, { it.from }, { it.title }))
    }

    private fun spanOn(e: Entry, s: ZonedDateTime, end: ZonedDateTime, allDay: Boolean, date: LocalDate): Item? {
        val sd = s.toLocalDate()
        // An all-day event's end is exclusive (the next midnight).
        val ed = if (allDay || (end.toLocalTime() == LocalTime.MIDNIGHT && end.isAfter(s))) end.minusNanos(1).toLocalDate() else end.toLocalDate()
        if (date.isBefore(sd) || date.isAfter(ed)) return null
        if (allDay) return Item(e.title, e.kind, null)
        val from = if (sd == date) s.toLocalTime() else LocalTime.MIDNIGHT
        val to = if (ed == date && end.isAfter(s)) end.toLocalTime() else null
        val note = when { sd != date -> "from ${shortLabel(sd)}"; ed != date -> "until ${shortLabel(ed)} ${hm(end.toLocalTime())}"; else -> "" }
        return Item(e.title, e.kind, from.truncatedTo(ChronoUnit.MINUTES), to?.truncatedTo(ChronoUnit.MINUTES), note)
    }

    /** "schedule Wed 21 Oct 2026: 08:00 CIT class; 14:00 NET class" or "… : nothing scheduled". */
    fun line(date: LocalDate, items: List<Item>, maxItems: Int = 6): String {
        val shown = items.take(maxItems).joinToString("; ") { it.label() }
        val more = if (items.size > maxItems) "; +${items.size - maxItems} more" else ""
        return "schedule ${dayLabel(date)}: " + (if (items.isEmpty()) "nothing scheduled" else shown + more)
    }

    /**
     * v0.45.9 replay fix (live 0.8.7, Sunday 1 of 5): the [context] form names how many items the day has and quotes each
     * title, so "08:30 Church 9:00–12:30" can't be read as two items or as nothing:
     * "schedule Sun 25 Oct 2026 (1 item): 08:30 \"Church 9:00–12:30\"".
     */
    fun contextLine(date: LocalDate, items: List<Item>, maxItems: Int = 6, now: LocalTime? = null): String {
        if (items.isEmpty()) return "schedule ${dayLabel(date)}: nothing scheduled"
        // v0.45.13: on today's line, what is already over says so, and the count says how many are still ahead.
        val ahead = items.count { !it.passed(now) }
        // Upcoming items are never cut for passed ones: those go last when the line is too long.
        val keep = if (items.size > maxItems && now != null) (items.filter { !it.passed(now) } + items.filter { it.passed(now) }).take(maxItems).toSet() else items.take(maxItems).toSet()
        val shown = items.filter { it in keep }.joinToString("; ") { it.label(quoted = true, passed = it.passed(now)) }
        val more = if (items.size > maxItems) "; +${items.size - maxItems} more" else ""
        val count = "${items.size} item${if (items.size == 1) "" else "s"}" + (if (now != null && ahead < items.size) ", $ahead still ahead" else "")
        return "schedule ${dayLabel(date)} ($count): " + shown + more
    }

    /** The next item after the last of [dates] (looks ahead up to 14 days): "Sun 25 Oct 08:30 Church". */
    fun next(after: LocalDate, zone: ZoneId, entries: List<Entry>): String? {
        for (i in 1..14) {
            val d = after.plusDays(i.toLong())
            val items = itemsOn(d, zone, entries).filter { it.note.startsWith("every").not() }
            if (items.isNotEmpty()) { val it0 = items.first(); return shortLabel(d) + " " + it0.label() }
        }
        return null
    }

    // ---------------- which dates a message asks about ----------------

    private val days = mapOf("monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY,
        "tues" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
        "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY)
    private val dayRe = Regex("\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues?|wed|thur?s?|fri|sat|sun)\\b")
    private val scheduleWords = Regex("\\b(schedule|plans?|agenda|on for|anything on|what'?s on|whats on|what is on|do i have|have i got|busy|free|reminders?|alarms?|classes|class|meetings?|events?|calendar|appointments?|due|happening|lined up)\\b")

    /**
     * The local dates [text] asks about, or empty when it isn't a date / schedule question.
     * "today/tonight" → today; "tomorrow" → +1; "this week" → today … Sunday; "this weekend" → Sat + Sun;
     * "next week" → next Mon … Sun; a weekday name → its next date (today when it is today).
     */
    fun datesAsked(text: String, today: LocalDate): List<LocalDate> {
        val t = text.lowercase().replace('\u2019', '\'')
        val out = LinkedHashSet<LocalDate>()
        if (Regex("\\b(today|tonight|this (morning|afternoon|evening))\\b").containsMatchIn(t)) out += today
        if (Regex("\\btomorrow\\b|\\btmrw\\b|\\btmr\\b").containsMatchIn(t)) out += today.plusDays(1)
        if (Regex("\\bthis week(end)?\\b").containsMatchIn(t)) {
            if ("weekend" in t) { var d = today; while (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) d = d.plusDays(1); out += d; if (d.dayOfWeek == DayOfWeek.SATURDAY) out += d.plusDays(1) }
            else { var d = today; do { out += d; d = d.plusDays(1) } while (d.dayOfWeek != DayOfWeek.MONDAY) }
        }
        if (Regex("\\bnext week\\b").containsMatchIn(t)) { var d = today.plusDays(1); while (d.dayOfWeek != DayOfWeek.MONDAY) d = d.plusDays(1); repeat(7) { out += d; d = d.plusDays(1) } }
        // A named day only counts when the message is about the schedule ("what's on friday", "do I have class on sun").
        if (out.isEmpty() || scheduleWords.containsMatchIn(t)) for (m in dayRe.findAll(t)) {
            if (!scheduleWords.containsMatchIn(t) && !Regex("\\b(on|this|next|for)\\s+${m.value}\\b").containsMatchIn(t)) continue
            val want = days[m.value] ?: continue
            var d = today
            if (Regex("\\bnext\\s+${m.value}\\b").containsMatchIn(t)) d = d.plusDays(1)
            while (d.dayOfWeek != want) d = d.plusDays(1)
            out += d
        }
        // "anything on?" / "what's my schedule?" with no day named means today.
        if (out.isEmpty() && scheduleWords.containsMatchIn(t) && Regex("\\?|\\b(what|any|anything|do i|have i|am i)\\b").containsMatchIn(t)
            && Regex("\\b(on for|anything on|what'?s on|whats on|schedule|agenda|plans|do i have|busy|lined up)\\b").containsMatchIn(t)) out += today
        return out.toList().sorted().take(7)
    }

    // ---------------- RRULE ----------------

    /** A small RFC 5545 RRULE reader for the cases phones make: DAILY / WEEKLY / MONTHLY / YEARLY, INTERVAL, BYDAY, BYMONTHDAY, COUNT, UNTIL. */
    object RRule {
        private val wd = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
            "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)

        fun parts(rrule: String): Map<String, String> = rrule.removePrefix("RRULE:").split(';').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i).trim().uppercase() to it.substring(i + 1).trim().uppercase() }.toMap()

        private fun until(v: String, zone: ZoneId): LocalDate? = try {
            if (v.length >= 8) LocalDate.of(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt()).let { d ->
                // A UTC UNTIL (…T235959Z) is compared on the local date it lands on.
                if (v.endsWith("Z") && v.length >= 15) LocalDateTime.of(d, LocalTime.of(v.substring(9, 11).toInt(), v.substring(11, 13).toInt()))
                    .atZone(ZoneId.of("UTC")).withZoneSameInstant(zone).toLocalDate() else d } else null
        } catch (_: Exception) { null }

        /** Does the series starting at [start] (local) have an occurrence on [date]? */
        fun occursOn(rrule: String, start: ZonedDateTime, date: LocalDate): Boolean {
            val p = parts(rrule)
            val freq = p["FREQ"] ?: return start.toLocalDate() == date
            val first = start.toLocalDate()
            if (date.isBefore(first)) return false
            p["UNTIL"]?.let { u -> until(u, start.zone)?.let { if (date.isAfter(it)) return false } }
            val interval = (p["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val byDay = p["BYDAY"]?.split(',')?.mapNotNull { wd[it.takeLast(2)] }?.toSet().orEmpty()
            val byMonthDay = p["BYMONTHDAY"]?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty()
            fun matches(d: LocalDate): Boolean = when (freq) {
                "DAILY" -> ChronoUnit.DAYS.between(first, d) % interval == 0L && (byDay.isEmpty() || d.dayOfWeek in byDay)
                "WEEKLY" -> {
                    val weeks = ChronoUnit.WEEKS.between(first.with(DayOfWeek.MONDAY), d.with(DayOfWeek.MONDAY))
                    weeks % interval == 0L && (if (byDay.isEmpty()) d.dayOfWeek == first.dayOfWeek else d.dayOfWeek in byDay)
                }
                "MONTHLY" -> ChronoUnit.MONTHS.between(first.withDayOfMonth(1), d.withDayOfMonth(1)) % interval == 0L &&
                    (if (byMonthDay.isNotEmpty()) d.dayOfMonth in byMonthDay else d.dayOfMonth == first.dayOfMonth)
                "YEARLY" -> (d.year - first.year) % interval == 0 && d.month == first.month && d.dayOfMonth == first.dayOfMonth
                else -> d == first
            }
            if (!matches(date)) return false
            val count = p["COUNT"]?.toIntOrNull() ?: return true
            // COUNT: the occurrence on [date] must be one of the first [count] (walks the days; capped at ~10 years).
            var n = 0; var d = first; var guard = 0
            while (!d.isAfter(date) && guard++ < 3700) { if (matches(d)) { n++; if (n > count) return false }; d = d.plusDays(1) }
            return n in 1..count
        }
    }
}
