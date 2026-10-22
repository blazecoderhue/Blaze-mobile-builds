package com.blaze.assistant.proactive

import com.blaze.assistant.proactive.JarvisLogic.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisLogicTest {
    private val now = 1_800_000_000_000L

    @Test fun levelsGrowMoreEager() {
        assertEquals(Level.EAGER, Level.of(null)); assertEquals(Level.JARVIS, Level.of("jarvis")); assertEquals(Level.EAGER, Level.of("nope"))
        val g = JarvisLogic.limits(Level.GENTLE); val e = JarvisLogic.limits(Level.EAGER); val j = JarvisLogic.limits(Level.JARVIS)
        assertTrue(g.questionsPerDay < e.questionsPerDay && e.questionsPerDay < j.questionsPerDay)
        assertTrue(j.glanceEveryMin < e.glanceEveryMin && e.glanceEveryMin < g.glanceEveryMin)
        assertTrue(j.selfRoutines && !e.selfRoutines)
        assertEquals(0, JarvisLogic.limits(Level.OFF).questionsPerDay)
        assertEquals(0, JarvisLogic.limits(Level.GENTLE).cameraEveryMin)
    }

    @Test fun questionsRespectCapsGapsAndHours() {
        assertTrue(JarvisLogic.questionDue(Level.EAGER, 0, 0, now, 14, false))
        assertFalse(JarvisLogic.questionDue(Level.EAGER, 2, 0, now, 14, false))            // daily cap
        assertFalse(JarvisLogic.questionDue(Level.EAGER, 1, now - 60 * 60_000L, now, 14, false))  // too soon
        assertTrue(JarvisLogic.questionDue(Level.EAGER, 1, now - 4 * 3600_000L, now, 14, false))
        assertFalse(JarvisLogic.questionDue(Level.EAGER, 0, 0, now, 7, false))             // too early
        assertFalse(JarvisLogic.questionDue(Level.EAGER, 0, 0, now, 23, false))            // too late
        assertFalse(JarvisLogic.questionDue(Level.EAGER, 0, 0, now, 14, true))             // quiet hours
        assertFalse(JarvisLogic.questionDue(Level.OFF, 0, 0, now, 14, false))
    }

    @Test fun pickSkipsAskedAndKnownTopics() {
        val first = JarvisLogic.pick(emptySet(), "", "", 0)
        assertEquals("fun", first?.id)
        // The user's hobby is already in memory: the fun questions are skipped.
        val q = JarvisLogic.pick(emptySet(), "Ananya's hobbies: painting", "", 0)
        assertNotNull(q); assertTrue(q!!.id != "fun")
        // Same topic twice in a row is avoided when there's another.
        val next = JarvisLogic.pick(setOf("fun"), "", "fun", 0)
        assertTrue(next!!.topic != "fun")
        assertNull(JarvisLogic.pick(JarvisLogic.bank.map { it.id }.toSet(), "", "", 3))
        // The seed rotates the choice.
        assertTrue((0..5).mapNotNull { JarvisLogic.pick(emptySet(), "", "", it)?.id }.toSet().size > 1)
    }

    @Test fun neverGlancesAtPrivateScreens() {
        assertTrue(JarvisLogic.sensitive("com.zanaco.mobile", ""))
        assertTrue(JarvisLogic.sensitive("com.x8bit.bitwarden", ""))
        assertTrue(JarvisLogic.sensitive("com.google.android.apps.authenticator2", ""))
        assertTrue(JarvisLogic.sensitive("com.android.settings", ""))
        assertTrue(JarvisLogic.sensitive("com.example.shop", "Enter your password to continue"))
        assertTrue(JarvisLogic.sensitive("com.example.shop", "Card number  CVV"))
        assertTrue(JarvisLogic.sensitive("com.whatever", "Your verification code is 123456"))
        assertFalse(JarvisLogic.sensitive("com.spotify.music", "Liked songs"))
        assertFalse(JarvisLogic.sensitive("com.booking", "Hotels in Livingstone"))
    }

    @Test fun glanceableSkipsBlazeLauncherAndRepeats() {
        assertFalse(JarvisLogic.glanceable("com.blaze.assistant", "com.blaze.assistant", false, "", 0, now))
        assertFalse(JarvisLogic.glanceable("com.sec.android.app.launcher", "com.blaze.assistant", true, "", 0, now))
        assertFalse(JarvisLogic.glanceable("com.booking", "x", false, "com.booking", now - 5 * 60_000L, now))
        assertTrue(JarvisLogic.glanceable("com.booking", "x", false, "com.booking", now - 25 * 60_000L, now))
        assertTrue(JarvisLogic.glanceable("com.booking", "x", false, "com.spotify.music", now - 60_000L, now))
        assertFalse(JarvisLogic.glanceable("", "x", false, "", 0, now))
        assertTrue(JarvisLogic.everyDue(25, now - 30 * 60_000L, now)); assertFalse(JarvisLogic.everyDue(25, now - 10 * 60_000L, now)); assertFalse(JarvisLogic.everyDue(0, 0, now))
    }

    @Test fun usageLine() {
        assertEquals("TikTok 2 h 10 min, WhatsApp 48 min", JarvisLogic.usageLine(listOf("WhatsApp" to 48 * 60_000L, "Clock" to 60_000L, "TikTok" to 130 * 60_000L)))
        assertEquals("1 h", JarvisLogic.duration(60 * 60_000L))
        assertEquals("", JarvisLogic.usageLine(emptyList()))
    }

    @Test fun parsesModelReplies() {
        val g = JarvisLogic.parseGlance("Sure: {\"say\": \"Want me to compare these three hotels?\", \"suggest\": \"Compare the hotels\", \"note\": \"looking at hotels in Livingstone\"}")
        assertEquals("Compare the hotels", g.suggest); assertTrue(g.say.startsWith("Want me"))
        assertEquals("", JarvisLogic.parseGlance("no json here").say)
        val r = JarvisLogic.parseRoutine("{\"name\":\"Wind down\",\"prompt\":\"Suggest putting the phone away\",\"hour\":22,\"minute\":30,\"days\":127,\"why\":\"Late scrolling.\"}")
        assertEquals(22, r?.hour); assertEquals("Wind down", r?.name)
        assertNull(JarvisLogic.parseRoutine("{\"name\":\"\"}"))
        assertNull(JarvisLogic.parseRoutine("{\"name\":\"x\",\"prompt\":\"y\",\"hour\":25}"))
        assertNull(JarvisLogic.parseRoutine("{\"name\":\"x\",\"prompt\":\"y\",\"hour\":8,\"days\":0}"))
        assertEquals("What got you into jazz?", JarvisLogic.parseQuestion("{\"question\": \"What got you into jazz?\"}"))
        assertEquals("", JarvisLogic.parseQuestion("{\"question\": \"Tell me about jazz.\"}"))
    }
}
