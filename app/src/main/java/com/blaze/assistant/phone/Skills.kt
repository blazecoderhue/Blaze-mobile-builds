package com.blaze.assistant.phone

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.blaze.assistant.agent.Agent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Messages, calls, contacts, calendar and quick settings. Work without phone control. */
object Skills {
    /** Which runtime permissions each skill group needs (Settings → Skills grants them). */
    val groups = linkedMapOf(
        "Contacts" to arrayOf(Manifest.permission.READ_CONTACTS),
        "Messages" to arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS),
        "Calls" to arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE),
        "Calendar" to arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    )

    fun granted(ctx: Context, perms: Array<String>) =
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    private fun missing(group: String) =
        "Blaze doesn't have the $group permission yet. Tell the user to open Blaze → Settings → Skills and turn on $group."

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))))
    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    val names = setOf("find_contact", "read_messages", "send_sms", "recent_calls", "call_number",
        "calendar_events", "add_calendar_event", "device_setting")

    fun definitions(): List<JSONObject> = listOf(
        fn("find_contact", "Look up a contact's phone number(s) by name.",
            JSONObject().put("name", p("string", "Name or part of it")), listOf("name")),
        fn("read_messages", "Read recent SMS messages, optionally only from one contact name or number.",
            JSONObject().put("from", p("string", "Contact name or number (optional)")).put("limit", p("integer", "How many, default 10"))),
        fn("send_sms", "Send an SMS directly. The user is asked to approve before it sends.",
            JSONObject().put("to", p("string", "Phone number or contact name")).put("message", p("string", "Text to send")), listOf("to", "message")),
        fn("recent_calls", "List recent calls (missed, incoming, outgoing).",
            JSONObject().put("limit", p("integer", "How many, default 10"))),
        fn("call_number", "Place a phone call. The user is asked to approve first.",
            JSONObject().put("to", p("string", "Phone number or contact name")), listOf("to")),
        fn("calendar_events", "List calendar events from now for the next N days.",
            JSONObject().put("days", p("integer", "Days ahead, default 1 (today)"))),
        fn("add_calendar_event", "Add an event to the user's calendar.",
            JSONObject().put("title", p("string", "Event title"))
                .put("start", p("string", "Start, local time, format yyyy-MM-dd HH:mm"))
                .put("end", p("string", "End, yyyy-MM-dd HH:mm (optional, default 1 hour)"))
                .put("location", p("string", "Optional")), listOf("title", "start")),
        fn("device_setting", "Quick settings: flashlight on/off, volume up/down/mute/0-100, battery level, or open the Wi-Fi, Bluetooth, mobile data, Do Not Disturb, or brightness panel for the user.",
            JSONObject().put("setting", JSONObject().put("type", "string").put("enum",
                JSONArray(listOf("flashlight", "volume", "battery", "wifi", "bluetooth", "mobile_data", "do_not_disturb", "brightness", "airplane"))))
                .put("value", p("string", "on/off, up/down/mute, or a number 0-100")), listOf("setting"))
    )

    fun describe(name: String, a: JSONObject): String = when (name) {
        "find_contact" -> "Looking up ${a.optString("name")}"
        "read_messages" -> "Reading messages"
        "send_sms" -> "Texting ${a.optString("to")}"
        "recent_calls" -> "Checking recent calls"
        "call_number" -> "Calling ${a.optString("to")}"
        "calendar_events" -> "Checking your calendar"
        "add_calendar_event" -> "Adding \"${a.optString("title")}\" to your calendar"
        "device_setting" -> "Adjusting ${a.optString("setting").replace('_', ' ')}"
        else -> name
    }

    /** The runtime permissions each tool needs: asked for in-app the first time instead of failing. */
    private val needs = mapOf(
        "find_contact" to arrayOf(Manifest.permission.READ_CONTACTS),
        "read_messages" to arrayOf(Manifest.permission.READ_SMS),
        "send_sms" to arrayOf(Manifest.permission.SEND_SMS),
        "recent_calls" to arrayOf(Manifest.permission.READ_CALL_LOG),
        "calendar_events" to arrayOf(Manifest.permission.READ_CALENDAR),
        "add_calendar_event" to arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
    )

    /** v0.45.9: never "I can't access your calendar": calling calendar_events again shows Android's one-tap prompt. */
    const val CALENDAR_OFF = "Calendar access is off: the user didn't allow it in the one-tap prompt (yet). Say that in one short line and offer to ask again; " +
        "if they say yes, call calendar_events again (it shows the prompt and then lists the events). Never say you can't access calendars."

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = try {
        val want = needs[name]
        val denied = if (want != null && !com.blaze.assistant.device.Perms.ensure(ctx, *want) && name != "add_calendar_event")
            (if (name == "calendar_events") CALENDAR_OFF else com.blaze.assistant.device.Perms.denied(name.replace('_', ' '))) else null
        denied ?: when (name) {
            "find_contact" -> findContact(ctx, a.getString("name"))
            "read_messages" -> readSms(ctx, a.optString("from").ifBlank { null }, a.optInt("limit", 10))
            "send_sms" -> sendSms(ctx, a.getString("to"), a.getString("message"))
            "recent_calls" -> recentCalls(ctx, a.optInt("limit", 10))
            "call_number" -> call(ctx, a.getString("to"))
            "calendar_events" -> events(ctx, a.optInt("days", 1))
            "add_calendar_event" -> addEvent(ctx, a)
            "device_setting" -> setting(ctx, a.getString("setting"), a.optString("value"))
            else -> "Unknown skill $name"
        }
    } catch (e: SecurityException) { "Permission denied: ${e.message}" } catch (e: Exception) { "Error: ${e.message}" }

    // ---------- Contacts ----------

    internal fun contacts(ctx: Context, name: String): List<Pair<String, String>> {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)?.use {
            while (it.moveToNext() && out.size < 8) out += it.getString(0) to it.getString(1)
        }
        if (out.isNotEmpty()) return out.distinctBy { it.second.filter(Char::isDigit) }
        // v0.28.7: nothing by LIKE (a typo, emoji, words in another order): score every contact instead.
        val all = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
            while (it.moveToNext() && all.size < 5000) { val n = it.getString(0) ?: continue; all += n to (it.getString(1) ?: continue) }
        }
        return all.map { it to SendCheck.score(name, it.first) }.filter { it.second > 0 }.sortedByDescending { it.second }
            .map { it.first }.distinctBy { it.second.filter(Char::isDigit) }.take(8)
    }

    private fun findContact(ctx: Context, name: String): String {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return missing("Contacts")
        val c = contacts(ctx, name)
        return if (c.isEmpty()) "No contact matches \"$name\"." else c.joinToString("\n") { "${it.first}: ${it.second}" }
    }

    /** A number, or the single contact matching a name. */
    internal fun resolve(ctx: Context, to: String): Pair<String, String>? {
        val digits = to.filter { it.isDigit() || it == '+' }
        if (digits.length >= 5 && digits.length >= to.count { !it.isWhitespace() } - 2) return to to digits
        val c = contacts(ctx, to)
        return if (c.size == 1 || (c.isNotEmpty() && c.all { it.first == c[0].first })) c[0] else null
    }

    private fun nameFor(ctx: Context, number: String): String? {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    // ---------- Messages ----------

    private val stamp get() = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

    private fun readSms(ctx: Context, from: String?, limit: Int): String {
        if (!has(ctx, Manifest.permission.READ_SMS)) return missing("Messages")
        val numbers = from?.let { f -> resolve(ctx, f)?.second?.let { listOf(it) } ?: contacts(ctx, f).map { it.second } }
        val out = mutableListOf<String>()
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            null, null, "${Telephony.Sms.DATE} DESC")?.use {
            while (it.moveToNext() && out.size < limit.coerceIn(1, 30)) {
                val addr = it.getString(0) ?: continue
                if (numbers != null && numbers.none { n -> n.filter(Char::isDigit).takeLast(9) == addr.filter(Char::isDigit).takeLast(9) }) continue
                val who = nameFor(ctx, addr) ?: addr
                val dir = if (it.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "You → $who" else "$who"
                out += "[${stamp.format(Date(it.getLong(2)))}] $dir: ${it.getString(1).take(300)}"
            }
        }
        return if (out.isEmpty()) "No messages found." else out.joinToString("\n")
    }

    private suspend fun sendSms(ctx: Context, to: String, msg: String): String {
        if (!has(ctx, Manifest.permission.SEND_SMS)) return missing("Messages")
        val (who, number) = resolve(ctx, to) ?: return "Couldn't tell which number \"$to\" is. Use find_contact and ask the user."
        if (!Agent.confirm("Send a text to $who?", "\u201c$msg\u201d", "Send", always = true)) return "The user declined. The message was NOT sent."
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(msg)
        sms.sendMultipartTextMessage(number, null, parts, null, null)
        return "Sent to $who ($number)."
    }

    // ---------- Calls ----------

    private fun recentCalls(ctx: Context, limit: Int): String {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return missing("Calls")
        val out = mutableListOf<String>()
        ctx.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC")?.use {
            while (it.moveToNext() && out.size < limit.coerceIn(1, 30)) {
                val kind = when (it.getInt(2)) {
                    CallLog.Calls.MISSED_TYPE -> "Missed"; CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"; CallLog.Calls.REJECTED_TYPE -> "Rejected"; else -> "Call"
                }
                val who = it.getString(1)?.takeIf { n -> n.isNotBlank() } ?: it.getString(0)
                out += "[${stamp.format(Date(it.getLong(3)))}] $kind: $who (${it.getLong(4)}s)"
            }
        }
        return if (out.isEmpty()) "No calls found." else out.joinToString("\n")
    }

    private suspend fun call(ctx: Context, to: String): String {
        val (who, number) = resolve(ctx, to) ?: return "Couldn't tell which number \"$to\" is. Use find_contact and ask the user."
        if (!has(ctx, Manifest.permission.CALL_PHONE)) {
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Opened the dialer for $who; the user presses call."
        }
        if (!Agent.confirm("Call $who?", number, "Call", always = true)) return "The user declined. No call was placed."
        ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Calling $who."
    }

    // ---------- Calendar ----------

    private fun events(ctx: Context, days: Int): String {
        if (!has(ctx, Manifest.permission.READ_CALENDAR)) return missing("Calendar")
        val now = System.currentTimeMillis()
        val endCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days.coerceIn(1, 31) - 1); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(b, now - 3_600_000)
        android.content.ContentUris.appendId(b, endCal.timeInMillis)
        val out = mutableListOf<String>()
        ctx.contentResolver.query(b.build(),
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
            null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use {
            val f = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
            while (it.moveToNext() && out.size < 40) {
                val time = if (it.getInt(3) == 1) "All day " + SimpleDateFormat("EEE d MMM", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(it.getLong(1)))
                    else f.format(Date(it.getLong(1))) + "–" + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.getLong(2)))
                val loc = it.getString(4)?.takeIf { l -> l.isNotBlank() }?.let { l -> " @ $l" } ?: ""
                out += "$time: ${it.getString(0)}$loc"
            }
        }
        return eventsResult(out)
    }

    /**
     * v0.45.9 replay fix (rpG schedule-wednesday 4/5: after an empty calendar_events the reply said "Nothing scheduled today"
     * although the [context] schedule line had CIT 08:00 and NET 14:00): an empty result says it is only the phone calendar.
     */
    const val EVENTS_NONE = "No events in the phone's calendar app for that range. That is only the Android calendar, not the whole day: " +
        "the schedule line in [context] still holds the day's classes and reminders, so when asked what's on, name every item on it with its time."

    /**
     * v0.45.9 replay fix (live 0.8.7, "View calendar" 1 of 5 answered only "Open the calendar?"): the result says what to do
     * with the events, so the reply lists them. Pure; HaloReplayRequestTest builds its fixture with it.
     */
    fun eventsResult(lines: List<String>): String =
        if (lines.isEmpty()) EVENTS_NONE else "Calendar events (tell the user each one with its time and place):\n" + lines.joinToString("\n")

    private fun addEvent(ctx: Context, a: JSONObject): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val start = f.parse(a.getString("start"))?.time ?: return "Couldn't read the start time."
        val end = a.optString("end").takeIf { it.isNotBlank() }?.let { f.parse(it)?.time } ?: (start + 3_600_000)
        if (!has(ctx, Manifest.permission.WRITE_CALENDAR)) {
            ctx.startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, a.getString("title"))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                .putExtra(CalendarContract.Events.EVENT_LOCATION, a.optString("location"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Opened the calendar with the event filled in; the user taps Save."
        }
        // The user's primary writable calendar.
        val calId = ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null, null)?.use {
            var first: Long? = null; var primary: Long? = null
            while (it.moveToNext()) { if (first == null) first = it.getLong(0); if (it.getInt(1) == 1) primary = it.getLong(0) }
            primary ?: first
        } ?: return "No writable calendar on this phone."
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, a.getString("title"))
            put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            a.optString("location").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return "The calendar refused the event."
        val pid = com.blaze.assistant.agent.Processes.register("event", a.getString("title"), android.content.ContentUris.parseId(uri).toString(), start,
            JSONObject().put("location", a.optString("location")).put("start", a.getString("start")).put("end", a.optString("end")))
        return "Added \"${a.getString("title")}\" on ${SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(start))}. [ID $pid]"
    }

    // ---------- Quick settings ----------

    private fun panel(ctx: Context, action: String, fallback: String): String {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); fallback } catch (_: Exception) {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opened Settings."
        }
    }

    private suspend fun setting(ctx: Context, s: String, value: String): String {
        val v = value.trim().lowercase()
        // Radios Android won't let apps flip directly: flip the Quick Settings tile like a person, then fall back to the panel.
        val tileNames = mapOf(
            "wifi" to listOf("Wi-Fi", "Wifi", "WLAN"),
            "bluetooth" to listOf("Bluetooth"),
            "airplane" to listOf("Airplane mode", "Aeroplane mode", "Flight mode", "Airplane", "Aeroplane"),
            "mobile_data" to listOf("Mobile data", "Cellular data"),
            "do_not_disturb" to listOf("Do not disturb", "Do Not Disturb", "DND"),
        )
        val want = when (v) { "on", "enable", "true", "1" -> true; "off", "disable", "false", "0" -> false; else -> null }
        // v0.28.2: changing a phone setting always shows the confirm card, except a command the user typed or said
        // themselves (the fast lane, ConfirmGate.Direct). Reading (battery) and opening a settings panel don't ask.
        suspend fun approved(): Boolean = kotlin.coroutines.coroutineContext[com.blaze.assistant.agent.ConfirmGate.DirectKey] != null ||
            Agent.confirm("Change a phone setting?", settingLine(s, v), "Change", always = true)
        tileNames[s]?.let { names ->
            val svc = BlazeAccessibilityService.instance
            if (svc != null && !approved()) return "The user declined. The setting was NOT changed."
            if (svc != null) try { svc.toggleQuickTile(names, want)?.let { return it } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        }
        return when (s) {
            "flashlight" -> {
                if (!approved()) return "The user declined. The setting was NOT changed."
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: return "This phone has no flashlight."
                val on = v != "off"
                cm.setTorchMode(id, on); "Flashlight ${if (on) "on" else "off"}."
            }
            "volume" -> {
                if (v.isNotEmpty() && !approved()) return "The user declined. The setting was NOT changed."
                val am = ctx.getSystemService(AudioManager::class.java)
                val st = AudioManager.STREAM_MUSIC
                when {
                    v == "up" -> am.adjustStreamVolume(st, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    v == "down" -> am.adjustStreamVolume(st, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    v == "mute" || v == "off" -> am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    v == "unmute" || v == "on" -> am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    v.toIntOrNull() != null -> am.setStreamVolume(st, am.getStreamMaxVolume(st) * v.toInt().coerceIn(0, 100) / 100, AudioManager.FLAG_SHOW_UI)
                }
                "Media volume is ${am.getStreamVolume(st) * 100 / am.getStreamMaxVolume(st).coerceAtLeast(1)}%."
            }
            "battery" -> {
                val bm = ctx.getSystemService(BatteryManager::class.java)
                "Battery ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%${if (bm.isCharging) ", charging" else ""}."
            }
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) panel(ctx, android.provider.Settings.Panel.ACTION_WIFI, "Opened the Wi-Fi panel. Turn on Phone control so I can flip it for you.")
                else panel(ctx, android.provider.Settings.ACTION_WIFI_SETTINGS, "Opened Wi-Fi settings.")
            "bluetooth" -> panel(ctx, android.provider.Settings.ACTION_BLUETOOTH_SETTINGS, "Opened Bluetooth settings.")
            "mobile_data" -> if (Build.VERSION.SDK_INT >= 29) panel(ctx, android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "Opened the internet panel.")
                else panel(ctx, android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS, "Opened mobile data settings.")
            "do_not_disturb" -> panel(ctx, "android.settings.ZEN_MODE_SETTINGS", "Opened Do Not Disturb settings.")
            "brightness" -> panel(ctx, android.provider.Settings.ACTION_DISPLAY_SETTINGS, "Opened display settings.")
            "airplane" -> panel(ctx, android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Opened airplane mode settings.")
            else -> "Unknown setting."
        }
    }

    /** The confirm card's line for a setting change ("Wi-Fi: off", "Media volume: 40"). */
    private fun settingLine(s: String, v: String): String {
        val name = when (s) { "wifi" -> "Wi-Fi"; "do_not_disturb" -> "Do Not Disturb"; "mobile_data" -> "Mobile data"; "airplane" -> "Airplane mode"
            "volume" -> "Media volume"; else -> s.replace('_', ' ').replaceFirstChar { it.uppercase() } }
        return if (v.isBlank()) "$name: switch" else "$name: $v"
    }

}
