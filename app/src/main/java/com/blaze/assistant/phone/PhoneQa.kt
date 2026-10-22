package com.blaze.assistant.phone

import android.content.Context
import android.content.Intent
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.PhoneCases
import com.blaze.assistant.agent.PhoneQaReport
import com.blaze.assistant.agent.Processes
import com.blaze.assistant.agent.SaveFile
import com.blaze.assistant.agent.SaveFileAndroid
import com.blaze.assistant.agent.ToolVerify
import com.blaze.assistant.agent.TurnTrace
import com.blaze.assistant.agent.VerifyProbes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v0.45.13 strict phone self-test runner (hidden: type /phoneqa in the chat; "/phoneqa 1 timer,alarm-set" for a quick
 * pass). It runs every [PhoneCases] case [runs] times as REAL turns on this phone (one fresh chat per run, titled
 * "QA · <case> #n"), and after each turn its verifier reads the phone back on its own (Clock list, calendar and
 * contacts providers, Wi-Fi / Bluetooth / DND state, the media session, the screen). Each run records the request id,
 * route / model, tool calls with verdicts, first-token and total time, the screen before and after, and the result.
 * The report (JSON + Markdown) goes to Downloads/Blaze; the chat gets the summary. Functional only, no UI of its own.
 *
 * Loop order is run-major (all cases for run 1, then run 2…), so "turn off the QA wake alarm" always acts on the alarm
 * the run before it just set. Some cases change the phone on purpose (alarms, a calendar event, a contact, Wi-Fi /
 * Bluetooth / DND, dark mode): the checklist (files/qa/phone-control-0.45.x.md) says what to tidy up afterwards.
 */
object PhoneQa {
    @Volatile var running = false; private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(ctx: Context, runs: Int, only: Set<String>) {
        if (running) { Agent.note("The phone self-test is already running."); return }
        running = true
        val app = ctx.applicationContext
        scope.launch {
            try { run(app, runs.coerceIn(1, 10), only) }
            catch (e: Throwable) { say("Phone self-test stopped: ${e.message}") }
            finally { running = false }
        }
    }

    private suspend fun say(text: String, asReply: Boolean = false) =
        kotlinx.coroutines.withContext(Dispatchers.Main) { Agent.note(text, asReply) }

    private fun screen(): String = try {
        BlazeAccessibilityService.instance?.readScreen()?.take(3000) ?: "(phone control off: no screen read)"
    } catch (e: Throwable) { "(screen read failed: ${e.message})" }

    private fun backToBlaze(ctx: Context) {
        try { ctx.startActivity(Intent(ctx, com.blaze.assistant.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Throwable) { }
    }

    /** The newest registry alarm whose name or label holds [words] → (id, "HH:mm"). */
    private fun alarmFor(words: String): Pair<String, String>? = Processes.all.value.lastOrNull { p ->
        p.kind == "alarm" && (p.title.contains(words, true) || p.props.optString("label").contains(words, true) || p.props.optString("name").contains(words, true))
    }?.let { it.id to it.props.optString("time") }

    private fun tomorrowAt(hhmm: String): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, hhmm.substringBefore(':').toInt()); set(Calendar.MINUTE, hhmm.substringAfter(':').toInt())
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    /** The verifier: reads the phone back after the turn. (result, evidence); null result = couldn't read. */
    private suspend fun verify(ctx: Context, c: PhoneCases.Case, p: VerifyProbes, runStart: Long = 0L, paused: Boolean = false): Pair<Boolean?, String> {
        val e = c.expect
        return when (c.verifier) {
            PhoneCases.Verifier.ALARM_SET -> {
                val a = alarmFor(e["label"].orEmpty())
                val next = p.nextAlarm()
                val clock = a?.let { ClockUi.read(ctx, it.first, it.second) }
                backToBlaze(ctx)
                when {
                    clock?.first == true -> true to "Clock list: ${clock.second}"
                    next != null && "%02d:%02d".format(next.first, next.second) == e["time"] -> true to "AlarmManager next alarm ${e["time"]}"
                    a == null -> false to "no alarm named ${e["label"]} was registered"
                    clock == null -> null to "phone control off; next system alarm ${next?.let { "%02d:%02d".format(it.first, it.second) } ?: "none"}"
                    else -> false to "Clock list: ${clock.second}"
                }
            }
            PhoneCases.Verifier.ALARM_OFF -> {
                val a = alarmFor(e["label"].orEmpty())
                val clock = ClockUi.read(ctx, a?.first ?: "A0", a?.second?.ifBlank { null } ?: e["time"])
                backToBlaze(ctx)
                when {
                    clock == null -> null to "phone control off: the Clock list can't be read"
                    clock.first == false -> true to "Clock list: ${clock.second}"
                    clock.first == null -> true to "Clock list has no QA wake row (deleted)"
                    else -> false to "Clock list: ${clock.second}"
                }
            }
            PhoneCases.Verifier.CALENDAR_EVENT -> when (p.calendarHas(e["title"].orEmpty(), tomorrowAt(e["time"] ?: "15:00"))) {
                true -> true to "CalendarContract.Instances has \u201c${e["title"]}\u201d tomorrow ${e["time"]}"
                false -> false to "no \u201c${e["title"]}\u201d tomorrow ${e["time"]} in the calendar"
                null -> null to "calendar not readable (permission)"
            }
            PhoneCases.Verifier.DRAFT_NOT_SENT -> {
                val words = p.screenWords()
                val sent = sentSms(ctx, e["text"].orEmpty())
                when {
                    sent == true -> false to "an SMS with the text WAS sent"
                    words == null -> null to "phone control off"
                    e["text"].orEmpty().lowercase().split(' ').filter { it.length >= 3 }.all { it in words } ->
                        true to "the compose box on ${p.foreground()} holds the text; " + (if (sent == false) "sent box has no such SMS" else "sent box not readable")
                    else -> false to "the draft text isn't on the screen (${p.foreground()})"
                }
            }
            PhoneCases.Verifier.MEDIA_PLAYING -> {
                delay(2500)
                val t = p.nowPlaying(e["pkg"].orEmpty())
                when {
                    t == null -> null to "notification access off: media sessions can't be read"
                    t.isBlank() -> false to "nothing playing in ${e["pkg"]}"
                    t.contains(e["words"].orEmpty(), true) -> true to "playing \u201c$t\u201d"
                    else -> false to "playing \u201c$t\u201d (not the asked video)"
                }
            }
            PhoneCases.Verifier.SETTING_ON -> {
                val r = e["settings"].orEmpty().split(',').map { it to p.settingOn(it) }
                when {
                    r.any { it.second == null } -> null to r.joinToString { "${it.first}=${it.second}" }
                    r.all { it.second == true } -> true to r.joinToString { "${it.first}=on" }
                    else -> false to r.joinToString { "${it.first}=${if (it.second == true) "on" else "off"}" }
                }
            }
            PhoneCases.Verifier.TIMER_RUNNING -> when (p.timerRunning()) {
                true -> true to "the Clock app's ongoing timer notification is there"
                false -> false to "no running Clock timer notification"
                null -> null to "notification access off"
            }
            PhoneCases.Verifier.SETTINGS_VALUE -> {
                val um = ctx.getSystemService(android.app.UiModeManager::class.java)
                val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                val mode = um?.nightMode
                if (mode == android.app.UiModeManager.MODE_NIGHT_YES || night) true to "dark mode on (UiModeManager.nightMode=$mode, config night=$night)"
                else false to "dark mode off (UiModeManager.nightMode=$mode, config night=$night)"
            }
            PhoneCases.Verifier.CONTACT_SAVED -> when (p.contactHas(e["name"].orEmpty(), e["phone"].orEmpty())) {
                true -> true to "ContactsContract has ${e["name"]} with ${e["phone"]}"
                false -> false to "no ${e["name"]} with ${e["phone"]} in Contacts"
                null -> null to "contacts not readable (permission)"
            }
            PhoneCases.Verifier.OVERLAY_TAP -> {
                val dnd = p.settingOn(e["setting"] ?: "do_not_disturb")
                val shown = Pointer.shownAt >= runStart
                when {
                    paused -> false to "the turn says it paused (it must never pause)"
                    dnd == null -> null to "DND not readable (Do Not Disturb access off)"
                    !shown -> false to "DND=${if (dnd) "on" else "off"}, but the pointer overlay wasn't shown this run (no tap went under it)"
                    dnd -> true to "DND on, tapped with the pointer overlay shown, no pause"
                    else -> false to "DND still off after the tap under the overlay"
                }
            }
            PhoneCases.Verifier.MAPS_DIRECTIONS -> {
                delay(1500)
                val fg = p.foreground().orEmpty()
                val w = p.screenWords()
                when {
                    w == null -> null to "phone control off"
                    fg.contains("maps", true) && w.contains(e["place"].orEmpty().lowercase().substringBefore(' ')) && Regex("\\b(min|hr|km|start|directions)\\b").containsMatchIn(w) ->
                        true to "$fg in front with a route to ${e["place"]}"
                    else -> false to "front: $fg; route to ${e["place"]} not on the screen"
                }
            }
        }
    }

    /** true = an SMS with [text] is in the sent box from the last 15 min; false = none; null = can't read. */
    private fun sentSms(ctx: Context, text: String): Boolean? = try {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) null
        else ctx.contentResolver.query(android.net.Uri.parse("content://sms/sent"), arrayOf("body", "date"), "date > ?",
            arrayOf((System.currentTimeMillis() - 15 * 60_000L).toString()), null)?.use { c ->
            var hit = false; while (c.moveToNext()) if (c.getString(0).orEmpty().contains(text, true)) { hit = true; break }; hit }
    } catch (_: Throwable) { null }

    /** An unscored turn that puts the phone in the state the case starts from (so "turn on" isn't already on). */
    private suspend fun setup(ctx: Context, c: PhoneCases.Case, p: VerifyProbes): String? = when (c.id) {
        "settings-toggles" -> {
            val data = p.settingOn("mobile_data") == true
            "turn off Bluetooth and Do Not Disturb" + if (data) " and Wi-Fi" else ""
        }
        "settings-value" -> "turn dark mode off"
        "overlay-taps" -> "turn off Do Not Disturb"
        else -> null
    }

    private suspend fun run(ctx: Context, runs: Int, only: Set<String>) {
        val cases = PhoneCases.ALL.filter { only.isEmpty() || it.id in only }
        val probes = VerifyProbes(ctx)
        val out = ArrayList<PhoneQaReport.Run>()
        val started = System.currentTimeMillis()
        say("Phone self-test: ${cases.size} cases \u00d7 $runs runs. Leave the phone on its own until it says it's finished; approve any card it shows.")
        for (i in 1..runs) for (c in cases) {
            try {
                setup(ctx, c, probes)?.let { s -> Agent.askFresh("QA \u00b7 setup ${c.id} #$i", s, keep = false); backToBlaze(ctx); delay(1500) }
                val before = screen()
                val runStart = System.currentTimeMillis()
                val (chat, reply, _) = Agent.askFresh("QA \u00b7 ${c.id} #$i", c.prompt)
                val after = screen()
                val tr = reply?.trace?.let { TurnTrace.fromJson(it) }
                val tools = PhoneQaReport.toolsFrom(reply?.receipt.orEmpty())
                val calls = tools.map { it.name to (runCatching { ToolVerify.Status.valueOf(it.status) }.getOrNull() ?: ToolVerify.Status.UNVERIFIED) }
                val j = PhoneCases.judge(c, calls, reply?.text.orEmpty())
                val paused = PhoneCases.pausedIn(reply?.text.orEmpty() + "\n" + reply?.receipt.orEmpty())
                val (ok, ev) = try { verify(ctx, c, probes, runStart, paused) } catch (e: Throwable) { null to "verifier error: ${e.message}" }
                out += PhoneQaReport.Run(c.id, i, c.prompt, tr?.let { t -> t.requestIds.ifEmpty { listOfNotNull(t.requestId.ifBlank { null }) } } ?: emptyList(),
                    tr?.route.orEmpty(), tr?.model.orEmpty(), tr?.provider.orEmpty(), tools,
                    tr?.let { if (it.clientTtftMs > 0) it.clientTtftMs else -1L } ?: -1, tr?.totalMs ?: -1,   // 0.45.15: per-turn TTFT (StartTrace marks are the app's first send only: 68027 / 7052 on every run)
                    before, after, ok, ev, j.pass, j.why, reply?.text.orEmpty(), chat,
                    if (reply == null) "no reply" else if (reply.role == com.blaze.assistant.agent.Role.ERROR) "error reply" else "")
                say("QA ${c.id} #$i: ${if (out.last().pass) "PASS" else "FAIL"} (${ev.take(80)})")
            } catch (e: Throwable) {
                out += PhoneQaReport.Run(c.id, i, c.prompt, emptyList(), "", "", "", emptyList(), -1, -1, "", "", null, "", false, "", "", "", "runner error: ${e.message}")
            }
            backToBlaze(ctx); delay(1200)
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(started))
        val meta = linkedMapOf("app" to com.blaze.assistant.BuildConfig.VERSION_NAME + " (" + com.blaze.assistant.BuildConfig.VERSION_CODE + ")",
            "device" to android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + ", Android " + android.os.Build.VERSION.RELEASE,
            "started" to SimpleDateFormat("yyyy-MM-dd HH:mm ZZZZ", Locale.US).format(Date(started)), "runs_per_case" to runs.toString(),
            "phone_control" to (BlazeAccessibilityService.instance != null).toString(),
            "notification_access" to (com.blaze.assistant.notify.NotificationBrain.instance != null).toString())
        val j = SaveFileAndroid.save(ctx, SaveFile.Plan("blaze-phoneqa-$stamp.json", "application/json", PhoneQaReport.json(out, meta)))
        val m = SaveFileAndroid.save(ctx, SaveFile.Plan("blaze-phoneqa-$stamp.md", "text/markdown", PhoneQaReport.markdown(out, meta)))
        val sum = PhoneQaReport.summary(out).joinToString("\n") { "${it.case}: ${it.passed}/${it.runs}" + if (it.strict) " PASS" else " FAIL" }
        say("Phone self-test finished.\n$sum\n${SaveFile.pathIn(j) ?: j}\n${SaveFile.pathIn(m) ?: m}\nShare both files with the team (Files \u2192 Downloads \u2192 Blaze).", asReply = true)
    }
}
