package com.blaze.assistant.agent

import java.util.Calendar

/**
 * v0.24.1: the composer's rotating placeholder ("Check my Gmail…", "Open WhatsApp…", "Wish Gloria happy birthday").
 *
 * Built only from what is already on the phone: connected accounts, launchable apps, memories, reminders,
 * calendar events and project names. No network or model call, nothing leaves the device, and it is only ever
 * shown as placeholder text in Blaze's own composer. Generic fallbacks fill the list; at most [MAX] hints.
 * Pure Kotlin (no Android), unit-tested in ComposerHintsTest; `ui/ComposerHintsSource` gathers the inputs.
 */
object ComposerHints {
    const val MAX = 8
    const val ROTATE_MS = 3_000L
    /** Shown when there is nothing to rotate, with reduced motion, or while listening isn't on. */
    const val DEFAULT = "Message Blaze, or type /"

    /** A memory line: [kind] (person, project…), who/what it's about, and the text. */
    data class Note(val kind: String, val subject: String, val text: String, val updated: Long = 0)

    /** Something coming up: a calendar event or a one-off reminder, at [at] (epoch ms). */
    data class Upcoming(val title: String, val at: Long, val calendar: Boolean)

    data class Sources(
        /** Connected account ids: google, github, spotify, notion, slack. */
        val connectors: List<String> = emptyList(),
        /** Labels of launchable apps on the phone. */
        val apps: List<String> = emptyList(),
        val notes: List<Note> = emptyList(),
        val upcoming: List<Upcoming> = emptyList(),
        /** Recent topics, newest first (project names). */
        val recent: List<String> = emptyList(),
        val now: Long = System.currentTimeMillis(),
    )

    private val connectorHints = linkedMapOf(
        "google" to listOf("Check my Gmail…", "What's on my calendar today?"),
        "github" to listOf("Check my GitHub notifications…"),
        "spotify" to listOf("Play something on Spotify…"),
        "notion" to listOf("Search my Notion…"),
        "slack" to listOf("Catch up on Slack…"),
    )

    /** Apps worth suggesting, in order, with what to say. Matched against installed app labels. */
    private val appHints = listOf(
        "WhatsApp" to "Open WhatsApp…",
        "YouTube" to "Play lo-fi on YouTube…",
        "Spotify" to "Open Spotify…",
        "Instagram" to "Open Instagram…",
        "TikTok" to "Open TikTok…",
        "Telegram" to "Open Telegram…",
        "Maps" to "Directions to campus in Maps…",
        "Chrome" to "Look something up in Chrome…",
        "Facebook" to "Open Facebook…",
        "Snapchat" to "Open Snapchat…",
    )

    val fallbacks = listOf(
        "Set an alarm for 6:30",
        "Make me flashcards on…",
        "Draw a lion wearing sunglasses",
        "Summarise a PDF for me…",
        "What's the weather tomorrow?",
    )

    fun build(s: Sources): List<String> {
        val out = LinkedHashSet<String>()
        fun add(h: String) { val t = clean(h); if (t.isNotBlank() && out.size < MAX) out += t }

        birthdaysToday(s.notes, s.now).take(2).forEach { add("Wish $it happy birthday") }
        upcomingHints(s.upcoming, s.now).take(2).forEach(::add)
        s.connectors.map { it.lowercase() }.distinct().flatMap { connectorHints[it].orEmpty().take(1) }.take(2).forEach(::add)
        appHints(s.apps).take(2).forEach(::add)
        s.recent.map { clean(it, 28) }.filter { it.isNotBlank() }.take(1).forEach { add("Pick up $it…") }
        if ("google" in s.connectors.map { it.lowercase() } && s.upcoming.none { it.calendar }) add("What's on my calendar today?")
        fallbacks.forEach { if (out.size < 5) add(it) }
        return out.toList().take(MAX)
    }

    /** "Open WhatsApp…" for installed apps on the list, in list order. */
    fun appHints(labels: List<String>): List<String> {
        val have = labels.map { it.trim().lowercase() }.toSet()
        return appHints.filter { (name, _) -> name.lowercase() in have || have.any { it == "google ${name.lowercase()}" } }.map { it.second }
    }

    /** Calendar events and reminders in the next 24 h (calendar first), soonest first. */
    fun upcomingHints(list: List<Upcoming>, now: Long): List<String> =
        list.filter { it.at in now..(now + 24 * 3_600_000L) && it.title.isNotBlank() }
            .sortedWith(compareBy({ !it.calendar }, { it.at }))
            .map { u ->
                val t = clean(u.title, 28)
                if (u.calendar) "Get me ready for $t" else "Help me with $t"
            }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val bday = Regex("""\b(birthday|bday|b-day|born)\b""", RegexOption.IGNORE_CASE)
    private val iso = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val dayMonth = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\b""", RegexOption.IGNORE_CASE)
    private val monthDay = Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b""", RegexOption.IGNORE_CASE)

    /** (month 1-12, day) of a birthday written in [text], or null. */
    fun birthdayOf(text: String): Pair<Int, Int>? {
        if (!bday.containsMatchIn(text)) return null
        iso.find(text)?.let { m -> return m.groupValues[2].toInt() to m.groupValues[3].toInt() }
        dayMonth.find(text)?.let { m -> return (months.indexOf(m.groupValues[2].lowercase().take(3)) + 1) to m.groupValues[1].toInt() }
        monthDay.find(text)?.let { m -> return (months.indexOf(m.groupValues[1].lowercase().take(3)) + 1) to m.groupValues[2].toInt() }
        return null
    }

    /** Names of people whose remembered birthday is today. */
    fun birthdaysToday(notes: List<Note>, now: Long): List<String> {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val today = (c.get(Calendar.MONTH) + 1) to c.get(Calendar.DAY_OF_MONTH)
        return notes.filter { it.kind.equals("person", true) && it.subject.isNotBlank() }
            .filter { birthdayOf(it.text) == today }
            .map { firstName(it.subject) }.distinct()
    }

    private fun firstName(s: String): String = clean(s, 40).substringBefore(" (").trim().substringBefore(" ")

    /** One line, no control characters, trimmed to [max] with an ellipsis. */
    fun clean(s: String, max: Int = 44): String {
        val t = s.replace(Regex("[\\p{Cntrl}]+"), " ").replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }
}
