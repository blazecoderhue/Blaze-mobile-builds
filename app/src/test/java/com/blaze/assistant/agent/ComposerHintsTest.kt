package com.blaze.assistant.agent

import com.blaze.assistant.agent.ComposerHints.Note
import com.blaze.assistant.agent.ComposerHints.Sources
import com.blaze.assistant.agent.ComposerHints.Upcoming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** v0.24.1 rotating composer placeholder: built only from on-device data, personal first, fallbacks, capped. */
class ComposerHintsTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 13, 9, 0, 0) }.timeInMillis
    private val hour = 3_600_000L

    @Test fun emptySourcesGiveGenericFallbacks() {
        val h = ComposerHints.build(Sources(now = now))
        assertEquals(5, h.size)
        assertTrue(h.all { it in ComposerHints.fallbacks })
    }

    @Test fun connectorsAndAppsTheUserHas() {
        val h = ComposerHints.build(Sources(connectors = listOf("google", "github"), apps = listOf("WhatsApp", "YouTube", "Calculator"), now = now))
        assertTrue("Check my Gmail…" in h)
        assertTrue("Check my GitHub notifications…" in h)
        assertTrue("Open WhatsApp…" in h)
        assertTrue("Play lo-fi on YouTube…" in h)
        // nothing for accounts or apps that aren't there
        assertFalse(h.any { "Spotify" in it || "Slack" in it || "Notion" in it || "Telegram" in it })
    }

    @Test fun appsMatchByLabelAndOrder() {
        assertEquals(listOf("Open WhatsApp…", "Directions to campus in Maps…"), ComposerHints.appHints(listOf("maps", " WhatsApp ", "Clock")))
        assertTrue(ComposerHints.appHints(listOf("Google Maps")).isNotEmpty())
        assertTrue(ComposerHints.appHints(emptyList()).isEmpty())
    }

    @Test fun birthdayTodayComesFirst() {
        val notes = listOf(
            Note("person", "Gloria Gozza", "Luke's mother; birthday 13 October"),
            Note("person", "Mutale", "Classmate, born on March 2"),
            Note("project", "Oct 13 launch", "birthday party planning for the store"), // not a person
            Note("person", "Chanda", "Likes football"))
        val h = ComposerHints.build(Sources(notes = notes, apps = listOf("WhatsApp"), now = now))
        assertEquals("Wish Gloria happy birthday", h.first())
        assertFalse(h.any { "Mutale" in it || "launch" in it })
    }

    @Test fun birthdayFormats() {
        assertEquals(10 to 13, ComposerHints.birthdayOf("Birthday: 2004-10-13"))
        assertEquals(10 to 13, ComposerHints.birthdayOf("her bday is Oct 13th"))
        assertEquals(10 to 13, ComposerHints.birthdayOf("born on the 13th of October"))
        assertEquals(1 to 18, ComposerHints.birthdayOf("Birthday 18 Jan"))
        assertNull(ComposerHints.birthdayOf("Meeting on 13 October")) // no birthday word
        assertNull(ComposerHints.birthdayOf("birthday sometime soon"))
    }

    @Test fun upcomingInTheNextDayOnly() {
        val up = listOf(
            Upcoming("NET103 quiz", now + 5 * hour, calendar = true),
            Upcoming("Submit CIT102 assignment", now + 2 * hour, calendar = false),
            Upcoming("Old thing", now - hour, calendar = true),
            Upcoming("Next week exam", now + 72 * hour, calendar = true))
        assertEquals(listOf("Get me ready for NET103 quiz", "Help me with Submit CIT102 assignment"), ComposerHints.upcomingHints(up, now))
        val h = ComposerHints.build(Sources(upcoming = up, now = now))
        assertTrue("Get me ready for NET103 quiz" in h)
        assertFalse(h.any { "Old thing" in it || "Next week" in it })
    }

    @Test fun cappedDedupedAndClean() {
        val h = ComposerHints.build(Sources(
            connectors = listOf("google", "github", "spotify", "notion", "slack", "google"),
            apps = listOf("WhatsApp", "YouTube", "Spotify", "Instagram", "TikTok"),
            notes = listOf(Note("person", "Gloria", "birthday 13 October"), Note("person", "Ana", "birthday Oct 13")),
            upcoming = listOf(Upcoming("A\nvery long calendar event title that keeps going on", now + hour, true), Upcoming("B", now + 2 * hour, false)),
            recent = listOf("HomeGlow store"), now = now))
        assertEquals(ComposerHints.MAX, h.size)
        assertEquals(h.size, h.toSet().size)
        assertTrue(h.none { it.contains('\n') })
        assertTrue(h.all { it.length <= 60 })
        assertTrue(h[0].startsWith("Wish ") && h[1].startsWith("Wish "))
    }

    @Test fun recentTopic() {
        val h = ComposerHints.build(Sources(recent = listOf("HomeGlow store", "Portfolio"), now = now))
        assertEquals("Pick up HomeGlow store…", h.first())
        assertFalse(h.any { "Portfolio" in it })
    }

    @Test fun cleanTrims() {
        assertEquals("a b", ComposerHints.clean(" a\n\tb "))
        assertEquals(10, ComposerHints.clean("x".repeat(30), 10).length)
        assertTrue(ComposerHints.clean("x".repeat(30), 10).endsWith("…"))
    }
}
