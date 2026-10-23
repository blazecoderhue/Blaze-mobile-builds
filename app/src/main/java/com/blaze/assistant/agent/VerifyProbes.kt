package com.blaze.assistant.agent

import android.app.AlarmManager
import android.content.Context
import java.io.File
import java.util.Calendar

/**
 * v0.44.0: the phone side of [ToolVerify.Probes]: what is in front (phone control), the next system alarm, and whether
 * a written file is there. Each check is cheap and returns null when it can't tell (no accessibility), so the result
 * stays "couldn't confirm" instead of guessing.
 */
class VerifyProbes(private val ctx: Context) : ToolVerify.Probes {
    override val self: String get() = ctx.packageName

    override suspend fun foreground(): String? =
        com.blaze.assistant.phone.BlazeAccessibilityService.instance?.currentPkg()?.takeIf { it.isNotBlank() }

    override suspend fun nextAlarm(): Pair<Int, Int>? = try {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.nextAlarmClock?.triggerTime?.let { t -> Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.HOUR_OF_DAY) to it.get(Calendar.MINUTE) } }
    } catch (_: Throwable) { null }

    override suspend fun settingOn(setting: String): Boolean? = try {
        when (setting) {
            "wifi" -> (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
            "bluetooth" -> (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled
            "airplane" -> android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            "do_not_disturb" -> (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).currentInterruptionFilter.let {
                it != android.app.NotificationManager.INTERRUPTION_FILTER_ALL && it != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            "mobile_data" -> android.provider.Settings.Global.getInt(ctx.contentResolver, "mobile_data", -1).takeIf { it >= 0 }?.let { it != 0 }
            else -> null
        }
    } catch (_: Throwable) { null }

    // ---------- v0.45.13 verify-after-act ----------

    override suspend fun clockAlarmOn(pid: String, hhmm: String): Boolean? = try {
        val r = com.blaze.assistant.phone.ClockUi.read(ctx, pid, hhmm)
        // Back to Blaze after looking (the alarm was set with the Clock UI skipped).
        try { ctx.startActivity(android.content.Intent(ctx, com.blaze.assistant.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Throwable) { }
        r?.first
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }

    override suspend fun calendarHas(title: String, startMs: Long): Boolean? = try {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) null
        else {
            val b = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(b, startMs - 60_000L); android.content.ContentUris.appendId(b, startMs + 60_000L)
            ctx.contentResolver.query(b.build(), arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN), null, null, null)?.use { c ->
                var hit = false
                while (c.moveToNext()) { if (c.getString(0).orEmpty().trim().equals(title.trim(), true) && Math.abs(c.getLong(1) - startMs) <= 60_000L) { hit = true; break } }
                hit
            }
        }
    } catch (_: Throwable) { null }

    override suspend fun contactHas(name: String, phone: String): Boolean? = try {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) null
        else {
            val digits = phone.filter(Char::isDigit).takeLast(9)
            if (digits.isNotEmpty()) ctx.contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?", arrayOf(name.trim()), null)?.use { c ->
                var hit = false
                while (c.moveToNext()) if (c.getString(1).orEmpty().filter(Char::isDigit).endsWith(digits)) { hit = true; break }
                hit
            }
            else ctx.contentResolver.query(android.provider.ContactsContract.Contacts.CONTENT_URI, arrayOf(android.provider.ContactsContract.Contacts._ID),
                "${android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?", arrayOf(name.trim()), null)?.use { it.count > 0 }
        }
    } catch (_: Throwable) { null }

    override suspend fun timerRunning(): Boolean? = try {
        val nb = com.blaze.assistant.notify.NotificationBrain.instance
        if (nb == null) null
        else nb.activeNotifications.orEmpty().any { sb ->
            val pk = sb.packageName.orEmpty()
            (pk.contains("clock", true) || pk.contains("deskclock", true)) && sb.isOngoing &&
                (sb.notification.category == android.app.Notification.CATEGORY_ALARM || sb.notification.category == android.app.Notification.CATEGORY_STOPWATCH ||
                    sb.notification.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty().contains("timer", true) ||
                    sb.notification.channelId.orEmpty().contains("timer", true))
        }
    } catch (_: Throwable) { null }

    override suspend fun nowPlaying(pkg: String): String? = try {
        if (com.blaze.assistant.notify.NotificationBrain.instance == null) null
        else {
            val msm = ctx.getSystemService(android.media.session.MediaSessionManager::class.java)
            val list = msm?.getActiveSessions(android.content.ComponentName(ctx, com.blaze.assistant.notify.NotificationBrain::class.java)).orEmpty()
            list.filter { it.packageName == pkg }.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING ||
                it.playbackState?.state == android.media.session.PlaybackState.STATE_BUFFERING }
                ?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        }
    } catch (_: Throwable) { null }

    override suspend fun screenWords(): String? = try {
        com.blaze.assistant.phone.BlazeAccessibilityService.instance?.visibleWords()?.joinToString(" ")?.lowercase()
    } catch (_: Throwable) { null }

    override fun fileSize(path: String): Long? = try {
        val f = File(path)
        if (f.isFile) f.length() else if (f.parentFile?.canRead() == true) -1L else null
    } catch (_: Throwable) { null }
}
