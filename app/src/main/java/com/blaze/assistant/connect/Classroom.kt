package com.blaze.assistant.connect

import com.blaze.assistant.agent.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Student pack (v0.16.0): Google Classroom through the Google connector. Read-only scopes
 * (courses + the student's own coursework), asked for only after the user switches Classroom on,
 * so nobody's Google connection changes by updating. Pure parts here are unit-tested; the HTTP
 * calls live in Connectors.
 */
object Classroom {
    const val SCOPES = "https://www.googleapis.com/auth/classroom.courses.readonly https://www.googleapis.com/auth/classroom.coursework.me.readonly"
    const val API = "https://classroom.googleapis.com/v1"
    val toolNames = setOf("classroom_courses", "classroom_coursework", "classroom_sync")

    /** Settings › Learn › Google Classroom: adds the Classroom scopes to the next Google sign-in. */
    var enabled: Boolean
        get() = Settings.bool("classroom_on", false)
        set(v) = Settings.setBool("classroom_on", v)

    const val NEEDS_SCOPE = "Google Classroom isn't allowed yet. Turn on Settings \u203a Learn \u203a Google Classroom, then reconnect Google once (Settings \u203a Connections) so Blaze can read your courses and assignments."

    data class Work(val id: String, val course: String, val courseName: String, val title: String, val due: Long?,
                    val link: String, val points: Double, val state: String)

    /** Classroom dates are UTC; a missing time means the end of that day (23:59 UTC). */
    fun dueMillis(date: JSONObject?, time: JSONObject?): Long? {
        if (date == null || date.optInt("year") == 0) return null
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(date.optInt("year"), date.optInt("month", 1) - 1, date.optInt("day", 1),
                time?.optInt("hours", 23) ?: 23, time?.optInt("minutes", 59) ?: 59, 0)
        }
        return c.timeInMillis
    }

    fun parseWork(o: JSONObject, courseName: String): Work = Work(o.optString("id"), o.optString("courseId"), courseName, o.optString("title"),
        dueMillis(o.optJSONObject("dueDate"), o.optJSONObject("dueTime")), o.optString("alternateLink"), o.optDouble("maxPoints", 0.0).let { if (it.isNaN()) 0.0 else it }, "")

    /** When to remind: 24h before it's due; if that has passed but it isn't due yet, in 5 minutes. null = too late or no date. */
    fun reminderAt(due: Long?, now: Long = System.currentTimeMillis()): Long? {
        if (due == null || due <= now) return null
        val r = due - 24 * 3_600_000L
        return if (r > now) r else now + 5 * 60_000L
    }

    fun line(w: Work, now: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
        val due = w.due?.let { d -> "due ${fmt.format(Date(d))}" + (if (d < now) " (past)" else "") } ?: "no due date"
        val st = when (w.state) { "TURNED_IN", "RETURNED" -> " · turned in"; "" -> ""; else -> " · not turned in" }
        return "- ${w.courseName}: ${w.title} · $due$st${if (w.points > 0) " · ${w.points.toInt()} pts" else ""}${if (w.link.isNotBlank()) " · ${w.link}" else ""}"
    }

    fun reminderName(w: Work) = "Due: ${w.title}".take(60)
    fun reminderPrompt(w: Work): String {
        val fmt = SimpleDateFormat("EEEE d MMMM 'at' HH:mm", Locale.getDefault())
        return "Remind the user that \"${w.title}\" for ${w.courseName} is due ${w.due?.let { fmt.format(Date(it)) } ?: "soon"}" +
            (if (w.link.isNotBlank()) " (${w.link})" else "") + ". Ask if they want help planning it (study-planner or essay-coach skill). Keep it to two sentences."
    }

    fun tools(fn: (String, String, JSONObject, List<String>) -> JSONObject): List<JSONObject> {
        fun s(d: String) = JSONObject().put("type", "string").put("description", d)
        fun i(d: String) = JSONObject().put("type", "integer").put("description", d)
        return listOf(
            fn("classroom_courses", "List the user's active Google Classroom courses (id, name, section).", JSONObject(), emptyList()),
            fn("classroom_coursework", "List assignments from Google Classroom with due dates and whether they're turned in: one course (course_id) or all, due in the next N days (default 21; include_past for overdue).",
                JSONObject().put("course_id", s("Optional course id from classroom_courses")).put("days", i("Days ahead, default 21")).put("include_past", JSONObject().put("type", "boolean")), emptyList()),
            fn("classroom_sync", "Classroom sync: every assignment due in the next N days (default 30) that isn't turned in becomes a Schedule reminder 24 hours before it's due. Safe to run again: existing reminders are kept.",
                JSONObject().put("days", i("Days ahead, default 30")), emptyList()),
        )
    }

    fun courses(get: (String) -> JSONObject): List<Pair<String, String>> {
        val a = get("$API/courses?studentId=me&courseStates=ACTIVE&pageSize=30").optJSONArray("courses") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("id") to (it.optString("name") + it.optString("section").let { s -> if (s.isBlank()) "" else " ($s)" }) }
    }

    fun work(get: (String) -> JSONObject, courseId: String, days: Int, includePast: Boolean, now: Long = System.currentTimeMillis()): List<Work> {
        val list = courses(get).filter { courseId.isBlank() || it.first == courseId }
        val out = ArrayList<Work>()
        for ((cid, cname) in list.take(12)) {
            val cw = try { get("$API/courses/$cid/courseWork?pageSize=40&orderBy=dueDate%20desc").optJSONArray("courseWork") ?: JSONArray() } catch (_: Exception) { JSONArray() }
            val states = try {
                val s = get("$API/courses/$cid/courseWork/-/studentSubmissions?userId=me&pageSize=60").optJSONArray("studentSubmissions") ?: JSONArray()
                (0 until s.length()).map { s.getJSONObject(it) }.associate { it.optString("courseWorkId") to it.optString("state") }
            } catch (_: Exception) { emptyMap() }
            for (k in 0 until cw.length()) {
                val w = parseWork(cw.getJSONObject(k), cname).let { it.copy(state = states[it.id].orEmpty()) }
                val d = w.due
                val keep = if (d == null) includePast else (d <= now + days * 86_400_000L) && (includePast || d > now - 3_600_000L)
                if (keep) out += w
            }
        }
        return out.sortedBy { it.due ?: Long.MAX_VALUE }
    }

    fun done(w: Work) = w.state == "TURNED_IN" || w.state == "RETURNED"
}
