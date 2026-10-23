package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.9: the replies the live gateway gave on the strict schedule cases, checked against the phone's [context] block. */
class ScheduleTruthTest {
    private val sunday = """
        [context]
        now: Sun 25 Oct 2026 10:14, Africa/Lusaka (UTC+02:00)
        schedule Sun 25 Oct 2026 (1 item): 08:30 "Church 9:00–12:30"
        weather: 29°C Clear, Ndola (Open-Meteo · 10:00)
        next after that day (not that day): Mon 26 Oct 08:00 CIT class
        [/context]
    """.trimIndent()
    private val wednesday = """
        [context]
        now: Wed 21 Oct 2026 10:14, Africa/Lusaka (UTC+02:00)
        schedule Wed 21 Oct 2026 (2 items): 08:00 "CIT class"; 14:00 "NET class"
        next after that day (not that day): Sun 25 Oct 08:30 Church 9:00–12:30
        [/context]
    """.trimIndent()
    private val empty = """
        [context]
        now: Sat 24 Oct 2026 10:14, Africa/Lusaka (UTC+02:00)
        schedule Sat 24 Oct 2026: nothing scheduled
        [/context]
    """.trimIndent()

    @Test fun parsesTheBlock() {
        val d = ScheduleTruth.days(sunday)
        assertEquals(1, d.size)
        assertEquals("Sun 25 Oct 2026", d[0].label)
        assertEquals(listOf("08:30" to "Church 9:00–12:30"), d[0].items)
        assertEquals("CIT class", ScheduleTruth.nextTitle(sunday))
        assertEquals(2, ScheduleTruth.days(wednesday)[0].items.size)
        assertTrue(ScheduleTruth.days(empty).isEmpty())
    }

    @Test fun emptyClaimIsRebuilt() {
        for (r in listOf("Nothing scheduled. Want me to open the calendar?", "Nothing scheduled today. Want to add something to your calendar?",
                "Nothing scheduled today. Want me to check the weather?")) {
            val g = ScheduleTruth.fix(r, sunday, true)
            assertEquals("Today (Sun 25 Oct): 08:30 Church 9:00–12:30.", g)
        }
    }

    @Test fun otherDaysItemIsRebuiltButChipsStay() {
        val r = "Today you have Church at 8:30 and CIT class from 9:00 to 12:30. Want me to open the calendar?\n<<follow-ups: a | b | c>>"
        assertEquals("Today (Sun 25 Oct): 08:30 Church 9:00–12:30.\n\n<<follow-ups: a | b | c>>", ScheduleTruth.fix(r, sunday, true))
        // Named with its own day: fine.
        val ok = "Just Church at 08:30 today. Your CIT class is on Monday at 08:00."
        assertEquals(ok, ScheduleTruth.fix(ok, sunday, true))
    }

    @Test fun rightAnswersAreLeftAlone() {
        for (r in listOf("You have church at 08:30 today. Want me to open the calendar?", "You have church at 9:00. Want me to set a reminder?",
                "Just church this morning, 9:00–12:30. Nothing else scheduled for today.\n\n<<follow-ups: x | y | z>>"))
            assertEquals(r, ScheduleTruth.fix(r, sunday, true))
        for (r in listOf("You have CIT class at 8:00 and NET class at 2:00.\nWant to add a reminder?",
                "Yes — CIT class at 8 this morning and NET class at 2 this afternoon."))
            assertEquals(r, ScheduleTruth.fix(r, wednesday, true))
    }

    @Test fun missingItemIsRebuilt() {
        assertEquals("Today (Wed 21 Oct): 08:00 CIT class; 14:00 NET class.", ScheduleTruth.fix("You have CIT class at 08:00.", wednesday, true))
    }

    @Test fun notAScheduleAskOrNothingOnTheDay() {
        val r = "Nothing scheduled. Want me to open the calendar?"
        assertEquals(r, ScheduleTruth.fix(r, sunday, false))
        assertEquals(r, ScheduleTruth.fix(r, empty, true))
        assertFalse(ScheduleTruth.wrong("Hey Sam.", emptyList(), null))
        assertEquals("<<follow-ups: a | b | c>>", ScheduleTruth.fix("<<follow-ups: a | b | c>>", sunday, true))
        assertEquals("", ScheduleTruth.fix("", sunday, true))
    }
}
