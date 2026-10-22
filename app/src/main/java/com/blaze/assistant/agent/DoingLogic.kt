package com.blaze.assistant.agent

import java.util.Calendar

/**
 * v0.39.0 "Things Blaze is doing", pure part (DoingLogicTest): friendly names and "when" lines for routines, and the
 * "Today I…" diary from Awareness events.
 */
object DoingLogic {
    /** "Blaze · Tidy up phone storage" → "Tidy up phone storage"; empty → "Something I do for you". */
    fun friendlyName(name: String): String =
        name.trim().removePrefix("Blaze \u00b7 ").removePrefix("Blaze - ").trim().replaceFirstChar { it.uppercase() }.ifBlank { "Something I do for you" }

    private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)

    /** "Every weekday at 07:00", "Every night at 02:00", "Tomorrow at 14:30", "Every 2 hours". */
    fun whenLine(days: Int, hour: Int, minute: Int, at: Long, everyMinutes: Int, now: Long = System.currentTimeMillis()): String {
        if (everyMinutes > 0) return if (everyMinutes % 60 == 0) (if (everyMinutes == 60) "Every hour" else "Every ${everyMinutes / 60} hours") else "Every $everyMinutes minutes"
        val time = hm(hour, minute)
        if (at > 0 || days == 0) {
            val target = if (at > 0) at else nextAt(hour, minute, now)
            val d = dayDiff(now, target)
            return when (d) { 0 -> "Today at $time"; 1 -> "Tomorrow at $time"; else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(target)) + " at $time" }
        }
        return when (days) {
            127 -> if (hour >= 22 || hour < 5) "Every night at $time" else if (hour < 12) "Every morning at $time" else "Every day at $time"
            31 -> "Every weekday at $time"
            96 -> "Every weekend at $time"
            else -> {
                val names = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday").filterIndexed { i, _ -> days and (1 shl i) != 0 }
                "Every " + (if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()) + " at $time"
            }
        }
    }

    private fun nextAt(h: Int, m: Int, now: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun dayDiff(from: Long, to: Long): Int {
        fun day(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        return Math.round((day(to) - day(from)) / 86_400_000.0).toInt()
    }

    data class DiaryLine(val time: String, val text: String)

    /** Today's events, oldest first, as "09:12  Replied to Mary". */
    fun today(events: List<Awareness.Event>, now: Long = System.currentTimeMillis()): List<DiaryLine> =
        events.filter { dayDiff(it.at, now) == 0 }.sortedBy { it.at }.map {
            val c = Calendar.getInstance().apply { timeInMillis = it.at }
            DiaryLine(hm(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)), it.text.trim().replaceFirstChar { ch -> ch.uppercase() })
        }

    /** One line for the top of the diary: "Played 3 songs, used the computer, …" (the first few things, lower-cased after the first). */
    fun summary(lines: List<DiaryLine>, max: Int = 3): String {
        if (lines.isEmpty()) return "Nothing yet today."
        val picks = lines.takeLast(max).map { it.text.trimEnd('.') }
        return picks.mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { it.lowercase() } }.joinToString(", ") + (if (lines.size > max) " and more." else ".")
    }
}
