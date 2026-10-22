package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class TimeCapsuleTest {
    private val now = Calendar.getInstance().apply { set(2026, 9, 18, 1, 0, 0) }.timeInMillis
    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }
    @Test fun dates() {
        val a = cal(TimeCapsule.openAt("2027-10-18", 0, now)!!); assertEquals(2027, a.get(Calendar.YEAR)); assertEquals(12, a.get(Calendar.HOUR_OF_DAY))
        assertEquals(2027, cal(TimeCapsule.openAt("1y", 0, now)!!).get(Calendar.YEAR))
        assertEquals(3, cal(TimeCapsule.openAt("6m", 0, now)!!).get(Calendar.MONTH))
        assertEquals(28, cal(TimeCapsule.openAt(null, 10, now)!!).get(Calendar.DAY_OF_MONTH))
        assertNull(TimeCapsule.openAt("2020-01-01", 0, now)); assertNull(TimeCapsule.openAt("soon", 0, now)); assertNull(TimeCapsule.openAt("", 0, now))
    }
}
