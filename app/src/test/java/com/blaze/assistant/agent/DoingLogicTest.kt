package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class DoingLogicTest {
    private fun at(h: Int, m: Int, plusDays: Int = 0) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_YEAR, plusDays) }.timeInMillis

    @Test fun friendlyWhenLines() {
        assertEquals("Every weekday at 07:00", DoingLogic.whenLine(31, 7, 0, 0, 0))
        assertEquals("Every night at 02:00", DoingLogic.whenLine(127, 2, 0, 0, 0))
        assertEquals("Every morning at 07:30", DoingLogic.whenLine(127, 7, 30, 0, 0))
        assertEquals("Every Friday at 18:00", DoingLogic.whenLine(16, 18, 0, 0, 0))
        assertEquals("Every 2 hours", DoingLogic.whenLine(127, 0, 0, 0, 120))
        val now = at(10, 0)
        assertEquals("Tomorrow at 14:30", DoingLogic.whenLine(0, 14, 30, at(14, 30, 1), 0, now))
    }

    @Test fun names() {
        assertEquals("Tidy up phone storage", DoingLogic.friendlyName("Blaze \u00b7 tidy up phone storage"))
        assertEquals("Something I do for you", DoingLogic.friendlyName(" "))
    }

    @Test fun todayDiary() {
        val now = at(16, 0)
        val ev = listOf(Awareness.Event("x", "replied to Mary", at(9, 12)), Awareness.Event("x", "old thing", at(9, 0, -1)),
            Awareness.Event("x", "set 2 reminders", at(11, 40)))
        val d = DoingLogic.today(ev, now)
        assertEquals(listOf("09:12", "11:40"), d.map { it.time })
        assertEquals("Replied to Mary", d[0].text)
        assertEquals("Replied to Mary, set 2 reminders.", DoingLogic.summary(d))
        assertEquals("Nothing yet today.", DoingLogic.summary(emptyList()))
    }
}
