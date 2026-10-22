package com.blaze.assistant.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import com.blaze.assistant.R
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * v0.33.0 Safe Walk: "walk me home" / "I'm taking a taxi, check on me". You pick a trusted contact and a time; Blaze shows
 * an ongoing notification with an "I'm safe" button. If you haven't checked in when the time is up, it buzzes you for 2
 * minutes, then texts the contact your last location (map link) and where you said you were going, and keeps texting the
 * latest location every 10 minutes (3 times) until you tap "I'm safe". Starting it shows a confirmation card that says
 * exactly this, so the text that may go out later is approved up front. Pure message text in [message] (SafeWalkTest).
 */
object SafeWalk {
    const val TOOL = "safe_walk"
    private const val CH = "blaze_safewalk"
    private const val NID = 4400
    private const val ACT_CHECK = "com.blaze.assistant.SAFEWALK_CHECK"
    private const val ACT_SAFE = "com.blaze.assistant.SAFEWALK_SAFE"
    private const val ACT_EXTEND = "com.blaze.assistant.SAFEWALK_EXTEND"

    data class Walk(val name: String, val number: String, val dueAt: Long, val dest: String, val stage: Int)

    fun current(): Walk? = try {
        val s = Settings.str("safe_walk"); if (s.isBlank()) null else JSONObject(s).let {
            Walk(it.optString("name"), it.optString("number"), it.optLong("due"), it.optString("dest"), it.optInt("stage")) }
    } catch (_: Exception) { null }

    private fun save(w: Walk?) = Settings.setStr("safe_walk", w?.let { JSONObject().put("name", it.name).put("number", it.number)
        .put("due", it.dueAt).put("dest", it.dest).put("stage", it.stage).toString() } ?: "")

    fun message(dest: String, lat: Double?, lng: Double?, minsLate: Long, update: Boolean): String = buildString {
        append(if (update) "Safe Walk update: " else "Safe Walk alert from Blaze: ")
        if (!update) append("I haven't checked in ${if (minsLate > 0) "$minsLate min after" else "at"} the time I set" +
            (if (dest.isNotBlank()) " on my way to $dest" else "") + ". Please check on me. ")
        append(if (lat != null && lng != null) "Last location: https://maps.google.com/?q=%.5f,%.5f".format(Locale.US, lat, lng) else "Location unavailable.")
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Safe Walk: the user names a trusted contact and how long they'll be (walking home, a taxi, a date). If they don't " +
            "tap I'm safe in time, Blaze texts the contact their location. Only start when the user asks; it shows a confirmation card.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("start", "safe", "extend", "status"))))
            .put("contact", JSONObject().put("type", "string").put("description", "contact name or number"))
            .put("minutes", JSONObject().put("type", "integer").put("description", "start: how long until check-in; extend: extra minutes"))
            .put("destination", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action")))))

    suspend fun run(ctx: Context, a: JSONObject): String {
        val df = SimpleDateFormat("HH:mm", Locale.getDefault())
        return when (a.optString("action")) {
            "status" -> current()?.let { "Safe Walk on: check in by ${df.format(it.dueAt)} or ${it.name} gets your location." } ?: "No Safe Walk running."
            "safe" -> if (current() == null) "No Safe Walk running." else { stop(ctx, true); "Checked in. Safe Walk stopped." }
            "extend" -> current()?.let { w -> val n = w.copy(dueAt = maxOf(w.dueAt, System.currentTimeMillis()) + a.optInt("minutes", 15).coerceIn(1, 600) * 60_000L, stage = 0)
                save(n); arm(ctx, n.dueAt); ongoing(ctx, n); "Extended: check in by ${df.format(n.dueAt)}." } ?: "No Safe Walk running."
            "start" -> {
                val mins = a.optInt("minutes", 0); if (mins !in 1..720) return "How many minutes until you should be there?"
                val c = a.optString("contact").trim(); if (c.isEmpty()) return "Who should get your location if you don't check in?"
                if (ContextCompatHas(ctx, Manifest.permission.SEND_SMS).not()) return "Blaze needs the SMS permission for Safe Walk (Settings › Apps › Blaze › Permissions)."
                val (who, num) = Skills.resolve(ctx, c) ?: return "Couldn't tell which number \"$c\" is. Ask the user which contact."
                val due = System.currentTimeMillis() + mins * 60_000L
                val dest = a.optString("destination").trim()
                if (!Agent.confirm("Start Safe Walk with $who?", "If you don't tap \u201cI'm safe\u201d by ${df.format(due)}, Blaze texts $who ($num) your location" +
                        (if (dest.isNotBlank()) " and that you were going to $dest" else "") + ", then updates them every 10 min until you check in.", "Start", always = true))
                    return "The user declined. Safe Walk NOT started."
                val w = Walk(who, num, due, dest, 0); save(w); arm(ctx, due); ongoing(ctx, w)
                "Safe Walk on. Check in by ${df.format(due)} (tap I'm safe in the notification, or tell me)."
            }
            else -> "Unknown action."
        }
    }

    private fun ContextCompatHas(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun pi(ctx: Context, act: String, code: Int) = PendingIntent.getBroadcast(ctx, code, Intent(ctx, SafeWalkReceiver::class.java).setAction(act),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    private fun arm(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(ctx, ACT_CHECK, 1)
        try { if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
              else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) } catch (_: Exception) { am.set(AlarmManager.RTC_WAKEUP, at, p) }
    }

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).also {
        if (Build.VERSION.SDK_INT >= 26) it.createNotificationChannel(NotificationChannel(CH, "Safe Walk", NotificationManager.IMPORTANCE_HIGH)) }

    private fun ongoing(ctx: Context, w: Walk, text: String? = null) {
        val df = SimpleDateFormat("HH:mm", Locale.getDefault())
        try { nm(ctx).notify(NID, NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat_blaze).setOngoing(true)
            .setContentTitle("Safe Walk · check in by ${df.format(w.dueAt)}")
            .setContentText(text ?: "${w.name} gets your location if you don't check in.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text ?: "${w.name} gets your location if you don't check in."))
            .addAction(0, "I'm safe", pi(ctx, ACT_SAFE, 2)).addAction(0, "+15 min", pi(ctx, ACT_EXTEND, 3))
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()) } catch (_: Throwable) { }
    }

    fun stop(ctx: Context, safe: Boolean) {
        val w = current()
        save(null)
        try { ctx.getSystemService(AlarmManager::class.java)?.cancel(pi(ctx, ACT_CHECK, 1)) } catch (_: Exception) { }
        try { nm(ctx).cancel(NID) } catch (_: Throwable) { }
        if (safe && w != null && w.stage >= 2) send(ctx, w.number, "Safe Walk: I've checked in, I'm safe. (Blaze)")
    }

    @SuppressLint("MissingPermission")
    private fun lastFix(ctx: Context): Pair<Double, Double>? = try {
        val lm = ctx.getSystemService(LocationManager::class.java)
        lm?.getProviders(true)?.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }?.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
    } catch (_: Exception) { null }

    private fun send(ctx: Context, number: String, msg: String) {
        try {
            val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(msg), null, null)
        } catch (_: Exception) { }
    }

    /** Alarm: stage 0 → buzz + 2 min grace; 1 → text the contact; 2..4 → location updates every 10 min. */
    fun onAlarm(ctx: Context) {
        val w = current() ?: return
        val now = System.currentTimeMillis()
        when {
            w.stage == 0 -> { val n = w.copy(stage = 1); save(n); ongoing(ctx, n, "Time's up. Tap I'm safe now, or ${w.name} gets your location in 2 minutes."); arm(ctx, now + 120_000L) }
            w.stage < 5 -> {
                val fix = lastFix(ctx)
                send(ctx, w.number, message(w.dest, fix?.first, fix?.second, (now - w.dueAt) / 60_000, update = w.stage >= 2))
                val n = w.copy(stage = w.stage + 1); save(n)
                ongoing(ctx, n, "${w.name} has your location. Tap I'm safe to stop the updates.")
                if (n.stage < 5) arm(ctx, now + 600_000L)
            }
        }
    }

    internal fun handle(ctx: Context, action: String?) {
        when (action) {
            ACT_CHECK -> onAlarm(ctx)
            ACT_SAFE -> stop(ctx, true)
            ACT_EXTEND -> current()?.let { w -> val n = w.copy(dueAt = maxOf(w.dueAt, System.currentTimeMillis()) + 15 * 60_000L, stage = 0); save(n); arm(ctx, n.dueAt); ongoing(ctx, n) }
            Intent.ACTION_BOOT_COMPLETED -> current()?.let { arm(ctx, maxOf(it.dueAt, System.currentTimeMillis() + 5_000)); ongoing(ctx, it) }
        }
    }
}

class SafeWalkReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        SafeWalk.handle(ctx.applicationContext, intent.action)
    }
}
