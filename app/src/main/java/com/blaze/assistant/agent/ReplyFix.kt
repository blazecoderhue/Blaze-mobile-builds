package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 0.45.15 (strict replays on the phone route, 22 Oct): two model slips the app can correct before anything runs.
 *
 *  - [keepFlowTool]: on the first step of a two-step flow (open_settings → tap, processes → process) the model was named
 *    the first tool but sent a run_plan beside it ("open_settings_page{dnd}" + "run_plan{tap element 17}" in one step).
 *    The extra call ran blind and the follow-up tap was never named, so "Do Not Disturb is now on." stood with no read-back
 *    (overlay-taps 3/5 failed this way). Only the named tool's calls are kept on that step.
 *  - [fixRelativeDay]: "tomorrow at 15:00" on Wed 21 Oct came back as start "2026-10-23 15:00" (device 22:40 and the
 *    replay). When the ask says today / tomorrow / the day after tomorrow and the event's date is a different day, the
 *    date is moved to that day; the times the model chose stay.
 *
 * Pure; ReplyFixTest.
 */
object ReplyFix {
    private fun nameOf(c: JSONObject?) = c?.optJSONObject("function")?.optString("name").orEmpty()

    /** Drops every call but [named]'s from [reply] when [named] starts a flow ([ToolTruth.follow]) and was called. Count dropped. */
    fun keepFlowTool(reply: JSONObject, named: String?, userText: String): Int {
        if (named == null) return 0
        if (ToolTruth.follow(userText)[named] == null) return 0
        val calls = reply.optJSONArray("tool_calls") ?: return 0
        val list = (0 until calls.length()).mapNotNull { calls.optJSONObject(it) }
        if (list.none { nameOf(it) == named } || list.all { nameOf(it) == named }) return 0
        val keep = JSONArray(); list.filter { nameOf(it) == named }.forEach { keep.put(it) }
        reply.put("tool_calls", keep)
        return list.size - keep.length()
    }

    private val dayAsk = Regex("""(?i)\b(day after tomorrow|tomorrow|tonight|today)\b""")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val EVENT_TOOLS = setOf("add_calendar_event", "gcal_create")

    /** The day the ask names relative to [today], or null. */
    fun askedDay(userText: String, today: LocalDate): LocalDate? = when (dayAsk.find(userText)?.value?.lowercase()) {
        "day after tomorrow" -> today.plusDays(2)
        "tomorrow" -> today.plusDays(1)
        "today", "tonight" -> today
        else -> null
    }

    /** Moves an event call's start (and end, by the same days) onto the day the ask names. True when it changed. */
    fun fixRelativeDay(args: JSONObject, userText: String, today: LocalDate): Boolean {
        val want = askedDay(userText, today) ?: return false
        val start = args.optString("start").trim()
        val s = try { LocalDateTime.parse(start.replace('T', ' ').take(16), fmt) } catch (_: Exception) { return false }
        if (s.toLocalDate() == want) return false
        val shift = java.time.temporal.ChronoUnit.DAYS.between(s.toLocalDate(), want)
        args.put("start", s.plusDays(shift).format(fmt))
        val end = args.optString("end").trim()
        if (end.isNotEmpty()) try { args.put("end", LocalDateTime.parse(end.replace('T', ' ').take(16), fmt).plusDays(shift).format(fmt)) } catch (_: Exception) { }
        return true
    }

    /** [fixRelativeDay] on every event call in [reply]. Count fixed. */
    fun fixEventDays(reply: JSONObject, userText: String, today: LocalDate): Int {
        val calls = reply.optJSONArray("tool_calls") ?: return 0
        var n = 0
        for (i in 0 until calls.length()) {
            val f = calls.optJSONObject(i)?.optJSONObject("function") ?: continue
            if (f.optString("name") !in EVENT_TOOLS) continue
            val a = ToolCalls.parseArgs(f.opt("arguments")) ?: continue
            if (fixRelativeDay(a, userText, today)) { f.put("arguments", a.toString()); n++ }
        }
        return n
    }
}
