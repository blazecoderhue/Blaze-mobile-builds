package com.blaze.assistant.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * v0.45.9: gathers [TurnContext.Inputs] on the phone. Everything is local and cached: the Home weather card's last
 * result (no network call here, ever), Blaze reminders / routines, the Clock alarms Blaze set plus the phone's next
 * system alarm, calendar instances for the asked dates (only when READ_CALENDAR is already granted; skipped silently
 * otherwise), live follow-ups and an interrupted task. [block] reads the cache first; [warm] builds it on a background
 * thread (app open, typing) so the send path normally just picks it up. Every source is optional: a failure leaves it out.
 */
object TurnContextSource {
    @Volatile private var app: Context? = null
    private const val NEW_IN_SEEN = "ctx_new_in_seen"
    private const val NEW_IN_SHOWN = "ctx_new_in_shown"

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** Routines / alarms / weather / follow-ups changed: the next turn rebuilds the block. */
    fun invalidate() = TurnContext.cache.invalidate()

    /** The block for a turn whose user message is [userText]. Cheap on a cache hit (no I/O). */
    fun block(userText: String, now: ZonedDateTime = ZonedDateTime.now()): String {
        val dates = ScheduleDay.datesAsked(userText, now.toLocalDate())
        return TurnContext.cache.get(TurnContext.key(now, dates), System.currentTimeMillis()) { build(now, dates) }
    }

    /** Builds today's block in the background (app open, first keypress), so Send finds it ready. */
    fun warm() {
        if (app == null) return
        Thread { try { block("") } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("TurnContext.warm", e) } }.apply { isDaemon = true }.start()
    }

    /** Counts a turn for the "new in" line (call once per user turn). */
    fun countTurn() {
        try {
            val v = TurnContext.NEW_IN_VERSION
            if (Settings.str(NEW_IN_SEEN) != v) { Settings.setStr(NEW_IN_SEEN, v); Settings.setInt(NEW_IN_SHOWN, 1) }
            else Settings.setInt(NEW_IN_SHOWN, Settings.int(NEW_IN_SHOWN, 0) + 1)
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("TurnContext.countTurn", e) }
    }

    fun build(now: ZonedDateTime, dates: List<LocalDate>): String {
        val zone = now.zone
        val want = dates.ifEmpty { listOf(now.toLocalDate()) }
        val entries = ArrayList<ScheduleDay.Entry>()
        safe { entries += routines() }
        safe { entries += alarms(zone) }
        safe { entries += calendar(want, zone) }
        val newIn = try {
            val v = TurnContext.NEW_IN_VERSION
            val installed = com.blaze.assistant.BuildConfig.VERSION_NAME
            // Only on builds that carry this release's news (0.45.8 and the 0.45.9 that follows it).
            if (installed != v && installed != "0.45.9") null
            else TurnContext.newInLine(v, Settings.str(NEW_IN_SEEN), Settings.int(NEW_IN_SHOWN, 0))
        } catch (_: Throwable) { null }
        return TurnContext.block(TurnContext.Inputs(now, weather(), entries, dates, open(), newIn, calendarOff = !calendarAllowed()))
    }

    private inline fun safe(f: () -> Unit) { try { f() } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("TurnContext.source", e) } }

    fun routines(): List<ScheduleDay.Entry> = com.blaze.assistant.routine.Routines.all.value.filter { !it.isCommand || it.notify != "never" }.map { r ->
        val rule = when {
            r.at > 0 -> ScheduleDay.Rule.Once(Instant.ofEpochMilli(r.at))
            r.everyMinutes > 0 -> ScheduleDay.Rule.Every(r.everyMinutes)
            // "Once" (no date): it fires at its next time, so that is its one date.
            r.days == 0 -> ScheduleDay.Rule.Once(Instant.ofEpochMilli(com.blaze.assistant.routine.Routines.next(r)))
            else -> ScheduleDay.Rule.Weekly(r.days, LocalTime.of(r.hour.coerceIn(0, 23), r.minute.coerceIn(0, 59)))
        }
        ScheduleDay.Entry(r.name, "reminder", rule, r.enabled)
    }

    private fun alarms(zone: ZoneId): List<ScheduleDay.Entry> {
        val out = ArrayList<ScheduleDay.Entry>()
        val now = System.currentTimeMillis()
        Processes.all.value.filter { it.kind == "alarm" && it.status == "active" && it.due > now - 86_400_000L }.forEach { p ->
            out += ScheduleDay.Entry(p.title, "alarm", ScheduleDay.Rule.Once(Instant.ofEpochMilli(p.due)))
        }
        // The phone's next alarm from any clock app (only the next one is visible to apps).
        val ctx = app ?: return out
        ctx.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock?.let { n ->
            if (out.none { Math.abs(it.rule.let { r -> (r as ScheduleDay.Rule.Once).at.toEpochMilli() } - n.triggerTime) < 60_000L })
                out += ScheduleDay.Entry("Alarm", "alarm", ScheduleDay.Rule.Once(Instant.ofEpochMilli(n.triggerTime)))
        }
        return out
    }

    private fun calendar(dates: List<LocalDate>, zone: ZoneId): List<ScheduleDay.Entry> {
        val ctx = app ?: return emptyList()
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val from = dates.min().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = dates.max().plusDays(15).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from); android.content.ContentUris.appendId(it, to) }.build()
        val out = ArrayList<ScheduleDay.Entry>()
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && out.size < 60) {
                val title = c.getString(0).orEmpty().ifBlank { "Event" }
                val allDay = c.getInt(3) == 1
                // All-day instances are stored in UTC midnights: read them as local dates.
                val (b, e) = if (allDay) {
                    val bd = Instant.ofEpochMilli(c.getLong(1)).atZone(ZoneId.of("UTC")).toLocalDate()
                    val ed = Instant.ofEpochMilli(c.getLong(2)).atZone(ZoneId.of("UTC")).toLocalDate()
                    bd.atStartOfDay(zone).toInstant() to ed.atStartOfDay(zone).toInstant()
                } else Instant.ofEpochMilli(c.getLong(1)) to Instant.ofEpochMilli(c.getLong(2))
                out += ScheduleDay.Entry(title, "event", ScheduleDay.Rule.Span(b, e, allDay))
            }
        }
        return out
    }

    /** The Home weather card's cached reading: "29°C Clear, Ndola (Open-Meteo · 10:00)"; null when none or older than 3 h. */
    private fun weather(): String? = try {
        val w = com.blaze.assistant.home.HomeBoard.widgets.value.firstOrNull { it.type == com.blaze.assistant.home.WidgetTypes.WEATHER }
        val d = w?.let { com.blaze.assistant.home.HomeBoard.data.value[it.id] }
        if (d == null || d.value.isBlank() || d.fetched <= 0 || System.currentTimeMillis() - d.fetched > 3 * 3_600_000L) null
        else weatherLine(d.value, d.change, d.headline)
    } catch (_: Throwable) { null }

    /** "29°C" + "Clear · feels 31° · wind 9 km/h · Open-Meteo · 10:00" + "Ndola" → "29°C Clear, Ndola (Open-Meteo · 10:00)". */
    fun weatherLine(value: String, change: String, place: String): String {
        val parts = change.split(" \u00b7 ").map { it.trim() }.filter { it.isNotBlank() }
        val cond = parts.firstOrNull { !it.startsWith("feels") && !it.startsWith("wind") && !Regex("^\\d{1,2}:\\d{2}").containsMatchIn(it) }
        val src = parts.takeLast(2).filter { it != cond && !it.startsWith("feels") && !it.startsWith("wind") }.joinToString(" \u00b7 ")
        return listOfNotNull(value, cond).joinToString(" ") + (if (place.isNotBlank()) ", $place" else "") + (if (src.isNotBlank()) " ($src)" else "")
    }

    private fun granted(p: String) = app?.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** True when the device calendar can be read (the Android permission, not Google Calendar). */
    fun calendarAllowed(): Boolean = granted(Manifest.permission.READ_CALENDAR)

    /** What chips may offer on this phone right now (ChipGate). Local checks only. */
    suspend fun caps(): ChipGate.Caps {
        val ctx = app ?: return ChipGate.Caps()
        fun conn(id: String) = try { com.blaze.assistant.connect.Connectors.connected(id) } catch (_: Throwable) { false }
        val music = try { val st = com.blaze.assistant.mediaapps.MusicTools.state(ctx); st.queue > 0 || st.savedQueue > 0 || st.songs > 0 || st.lastList.isNotBlank() }
            catch (_: Throwable) { false } || conn("spotify")
        val signed = try { Settings.gatewayToken().isNotBlank() } catch (_: Throwable) { false }
        return ChipGate.Caps(
            calendar = calendarAllowed() || conn("google"),
            weather = signed || weather() != null,
            music = music,
            sms = granted(Manifest.permission.SEND_SMS),
            calls = granted(Manifest.permission.CALL_PHONE),
            contacts = granted(Manifest.permission.READ_CONTACTS),
            email = conn("google"),
            location = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            phoneControl = try { Settings.phoneControl } catch (_: Throwable) { false })
    }

    /** 1–3 unfinished things: live follow-ups first. */
    private fun open(): List<String> = try {
        val now = System.currentTimeMillis()
        com.blaze.assistant.proactive.FollowUps.all().filter { com.blaze.assistant.proactive.FollowUpLogic.live(it, now) && it.needsReply && !it.replied }
            .sortedByDescending { it.created }.take(3).map { i ->
                "reply to ${i.person.ifBlank { i.conv }}" + (if (i.topic.isNotBlank()) " about ${i.topic}" else "") + (if (i.whenText.isNotBlank()) " (${i.whenText})" else "") }
    } catch (_: Throwable) { emptyList() }
}
