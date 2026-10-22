package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** v0.45.9: the schedule resolved on the phone for one date (ScheduleDay), incl. recurring-rule expansion. */
class ScheduleDayTest {
    private val lusaka = ZoneId.of("Africa/Lusaka")
    private val wed = LocalDate.of(2026, 10, 21)
    private val sun = LocalDate.of(2026, 10, 25)

    private fun weekly(title: String, mask: Int, h: Int, m: Int) = ScheduleDay.Entry(title, "reminder", ScheduleDay.Rule.Weekly(mask, LocalTime.of(h, m)))
    private fun titles(d: LocalDate, z: ZoneId, e: List<ScheduleDay.Entry>) = ScheduleDay.itemsOn(d, z, e).map { it.label() }

    /** The phone's case: church on Sunday, CIT and NET classes on weekdays. */
    private val fixture = listOf(
        weekly("Church 9:00\u201312:30", ScheduleDay.bit(DayOfWeek.SUNDAY), 8, 30),
        weekly("CIT class", ScheduleDay.bit(DayOfWeek.MONDAY) or ScheduleDay.bit(DayOfWeek.WEDNESDAY), 8, 0),
        weekly("NET class", ScheduleDay.bit(DayOfWeek.TUESDAY) or ScheduleDay.bit(DayOfWeek.WEDNESDAY), 14, 0),
    )

    @Test fun wednesdayHasOnlyTheClassesAndSundayOnlyChurch() {
        assertEquals(listOf("08:00 CIT class", "14:00 NET class"), titles(wed, lusaka, fixture))
        assertEquals(listOf("08:30 Church 9:00\u201312:30"), titles(sun, lusaka, fixture))
        assertEquals("schedule Wed 21 Oct 2026: 08:00 CIT class; 14:00 NET class", ScheduleDay.line(wed, ScheduleDay.itemsOn(wed, lusaka, fixture)))
        assertEquals("schedule Sat 24 Oct 2026: nothing scheduled", ScheduleDay.line(LocalDate.of(2026, 10, 24), ScheduleDay.itemsOn(LocalDate.of(2026, 10, 24), lusaka, fixture)))
        assertEquals("Sun 25 Oct 08:30 Church 9:00\u201312:30", ScheduleDay.next(LocalDate.of(2026, 10, 24), lusaka, fixture))
    }

    @Test fun dailyWeekdaysOneWeeklyDay() {
        val e = listOf(weekly("Daily", ScheduleDay.EVERY_DAY, 7, 0), weekly("Work", ScheduleDay.WEEKDAYS, 9, 0), weekly("Gym", ScheduleDay.bit(DayOfWeek.THURSDAY), 18, 15))
        assertEquals(listOf("07:00 Daily", "09:00 Work"), titles(wed, lusaka, e))
        assertEquals(listOf("07:00 Daily", "09:00 Work", "18:15 Gym"), titles(wed.plusDays(1), lusaka, e))
        assertEquals(listOf("07:00 Daily"), titles(sun, lusaka, e))
        assertEquals(listOf("07:00 Daily"), titles(sun.minusDays(1), lusaka, e))
        // Off reminders never show.
        assertTrue(ScheduleDay.itemsOn(wed, lusaka, listOf(ScheduleDay.Entry("Off", "reminder", ScheduleDay.Rule.Weekly(127, LocalTime.NOON), enabled = false))).isEmpty())
    }

    @Test fun oneOffsLandOnTheirLocalDate() {
        val at = ZonedDateTime.of(2026, 10, 21, 23, 30, 0, 0, lusaka).toInstant()   // 21:30 UTC
        val e = listOf(ScheduleDay.Entry("Due: CIT102 lab 3", "reminder", ScheduleDay.Rule.Once(at)))
        assertEquals(listOf("23:30 Due: CIT102 lab 3"), titles(wed, lusaka, e))
        assertTrue(titles(wed.plusDays(1), lusaka, e).isEmpty())
        // The same instant is already Thursday in Tokyo.
        assertEquals(listOf("06:30 Due: CIT102 lab 3"), titles(wed.plusDays(1), ZoneId.of("Asia/Tokyo"), e))
        val alarm = ScheduleDay.Entry("Gym", "alarm", ScheduleDay.Rule.Once(ZonedDateTime.of(2026, 10, 22, 7, 15, 0, 0, lusaka).toInstant()))
        assertEquals(listOf("07:15 Gym (alarm)"), titles(wed.plusDays(1), lusaka, listOf(alarm)))
    }

    @Test fun acrossMidnight() {
        // A Friday 23:30 weekly reminder is Friday's, never Saturday's.
        val late = weekly("Late check", ScheduleDay.bit(DayOfWeek.FRIDAY), 23, 30)
        val fri = LocalDate.of(2026, 10, 23); val sat = fri.plusDays(1)
        assertEquals(listOf("23:30 Late check"), titles(fri, lusaka, listOf(late)))
        assertTrue(titles(sat, lusaka, listOf(late)).isEmpty())
        // A Friday 22:00 – Saturday 01:00 event shows on both days, labelled.
        val party = ScheduleDay.Entry("Party", "event", ScheduleDay.Rule.Span(ZonedDateTime.of(fri, LocalTime.of(22, 0), lusaka).toInstant(),
            ZonedDateTime.of(sat, LocalTime.of(1, 0), lusaka).toInstant()))
        assertEquals(listOf("22:00 Party (until Sat 24 Oct 01:00)"), titles(fri, lusaka, listOf(party)))
        assertEquals(listOf("00:00\u201301:00 Party (from Fri 23 Oct)"), titles(sat, lusaka, listOf(party)))
        // A weekly RRULE event on Fridays 23:00 for 2 h runs into Saturday too.
        val rr = ScheduleDay.Entry("Night shift", "event", ScheduleDay.Rule.Recurring(ZonedDateTime.of(LocalDate.of(2026, 10, 2), LocalTime.of(23, 0), lusaka), 120, "FREQ=WEEKLY;BYDAY=FR"))
        assertEquals(listOf("23:00 Night shift (until Sat 24 Oct 01:00)"), titles(fri, lusaka, listOf(rr)))
        assertEquals(listOf("00:00\u201301:00 Night shift (from Fri 23 Oct)"), titles(sat, lusaka, listOf(rr)))
        // An all-day event (exclusive end at the next midnight) is one day only.
        val allDay = ScheduleDay.Entry("Holiday", "event", ScheduleDay.Rule.Span(fri.atStartOfDay(lusaka).toInstant(), sat.atStartOfDay(lusaka).toInstant(), allDay = true))
        assertEquals(listOf("all day Holiday"), titles(fri, lusaka, listOf(allDay)))
        assertTrue(titles(sat, lusaka, listOf(allDay)).isEmpty())
    }

    @Test fun acrossADstBoundaryTheWallClockStays() {
        // Europe/London leaves summer time on Sun 25 Oct 2026 (02:00 BST → 01:00 GMT).
        val london = ZoneId.of("Europe/London")
        val e = listOf(weekly("Service", ScheduleDay.bit(DayOfWeek.SUNDAY), 9, 0),
            ScheduleDay.Entry("Standup", "event", ScheduleDay.Rule.Recurring(ZonedDateTime.of(LocalDate.of(2026, 10, 19), LocalTime.of(8, 0), london), 15, "FREQ=DAILY")))
        assertEquals(listOf("08:00\u201308:15 Standup", "09:00 Service"), titles(sun, london, e))
        assertEquals(listOf("08:00\u201308:15 Standup"), titles(sun.plusDays(1), london, e))
        assertEquals(listOf("08:00\u201308:15 Standup"), titles(sun.minusDays(1), london, e))
        // A one-off at 01:30 on the night the clocks go back is still that local date.
        val once = ScheduleDay.Entry("Night bus", "reminder", ScheduleDay.Rule.Once(ZonedDateTime.of(sun, LocalTime.of(1, 30), london).toInstant()))
        assertEquals(listOf("01:30 Night bus"), titles(sun, london, listOf(once)))
    }

    @Test fun rruleSubset() {
        val start = ZonedDateTime.of(LocalDate.of(2026, 10, 5), LocalTime.of(10, 0), lusaka)   // a Monday
        val r = ScheduleDay.RRule
        assertTrue(r.occursOn("FREQ=WEEKLY;BYDAY=MO,WE", start, wed))
        assertFalse(r.occursOn("FREQ=WEEKLY;BYDAY=MO,WE", start, sun))
        // Series week of Mon 5 Oct: every other week is Wed 7, Wed 21 Oct, Wed 4 Nov (not Wed 14 or Wed 28).
        assertTrue("every other week", r.occursOn("FREQ=WEEKLY;INTERVAL=2;BYDAY=WE", start, wed))
        assertFalse(r.occursOn("FREQ=WEEKLY;INTERVAL=2;BYDAY=WE", start, wed.plusDays(7)))
        assertTrue("weekdays", r.occursOn("RRULE:FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR", start, wed))
        assertFalse(r.occursOn("FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR", start, sun))
        assertFalse("UNTIL passed", r.occursOn("FREQ=DAILY;UNTIL=20261020T215959Z", start, wed))
        assertTrue(r.occursOn("FREQ=DAILY;UNTIL=20261021T215959Z", start, wed))
        assertTrue("COUNT=3 → 5, 6, 7 Oct", r.occursOn("FREQ=DAILY;COUNT=3", start, LocalDate.of(2026, 10, 7)))
        assertFalse(r.occursOn("FREQ=DAILY;COUNT=3", start, LocalDate.of(2026, 10, 8)))
        assertTrue(r.occursOn("FREQ=MONTHLY;BYMONTHDAY=21", start, wed))
        assertTrue(r.occursOn("FREQ=YEARLY", start, LocalDate.of(2027, 10, 5)))
        assertFalse("before the start", r.occursOn("FREQ=DAILY", start, LocalDate.of(2026, 10, 4)))
        assertTrue("no RRULE = the start date only", r.occursOn("", start, LocalDate.of(2026, 10, 5)))
    }

    @Test fun datesAskedFromTheMessage() {
        fun d(t: String) = ScheduleDay.datesAsked(t, wed)
        assertEquals(listOf(wed), d("anything on for today?"))
        assertEquals(listOf(wed), d("so what's on for today specifically?"))
        assertEquals(listOf(wed), d("what's on?"))
        assertEquals(listOf(wed.plusDays(1)), d("do I have class tomorrow"))
        assertEquals(listOf(sun), d("what's on sunday"))
        assertEquals(listOf(sun), d("anything on for Sunday?"))
        assertEquals(listOf(wed.plusDays(2)), d("what do I have on friday"))
        assertEquals(listOf(wed.plusDays(7)), d("what's on next wednesday"))
        assertEquals((0..4).map { wed.plusDays(it.toLong()) }, d("what's my schedule this week"))
        assertEquals(listOf(wed.plusDays(3), sun), d("anything on this weekend?"))
        assertTrue(d("doing good thanks!").isEmpty())
        assertTrue(d("I sat down for lunch").isEmpty())
        assertTrue(d("well as the assistant shouldn't you be telling me?").isEmpty())
    }

    @Test fun routinesParseDaysWholeWords() {
        val r = com.blaze.assistant.routine.Routines
        assertEquals(ScheduleDay.bit(DayOfWeek.SUNDAY), r.parseDays("sunday"))
        assertEquals(ScheduleDay.bit(DayOfWeek.MONDAY) or ScheduleDay.bit(DayOfWeek.WEDNESDAY), r.parseDays("Mondays and Wednesdays"))
        assertEquals(ScheduleDay.bit(DayOfWeek.TUESDAY) or ScheduleDay.bit(DayOfWeek.THURSDAY), r.parseDays("tue, thurs"))
        assertEquals(31, r.parseDays("weekdays"))
        assertEquals(127, r.parseDays("daily"))
        assertEquals(0, r.parseDays("once"))
        // "sunset" is not Sunday and "month" is not Monday (both used to match).
        assertEquals(127, r.parseDays("at sunset"))
        assertEquals(127, r.parseDays("every month"))
        // "weekly" with no day is today's weekday, not every day.
        assertEquals(ScheduleDay.bit(LocalDate.now().dayOfWeek), r.parseDays("weekly"))
    }
}
