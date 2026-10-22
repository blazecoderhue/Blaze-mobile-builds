package com.blaze.assistant.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blaze.assistant.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wider Android control with plain public APIs (no root, no Shizuku): clipboard, Blaze's own
 * notifications (with progress and a tap action), the share sheet, ringer/DND/brightness/rotation,
 * jumping straight to any settings page, system actions (power menu, split screen, screenshot,
 * lock), app usage, uninstall prompts and the camera. Anything Android doesn't let an app change
 * directly opens the right settings page for the user instead.
 */
object AndroidControl {
    val names = setOf("clipboard", "post_notification", "share_text", "phone_mode", "open_settings_page", "system_action", "app_usage", "uninstall_app", "open_camera", "list_apps", "blaze_camera")

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun en(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))
    private fun str(d: String = "") = JSONObject().put("type", "string").apply { if (d.isNotBlank()) put("description", d) }

    private val pages = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS, "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS, "hotspot" to "android.settings.TETHER_SETTINGS",
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS, "nfc" to Settings.ACTION_NFC_SETTINGS, "dnd" to "android.settings.ZEN_MODE_SETTINGS",
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY, "display" to Settings.ACTION_DISPLAY_SETTINGS, "sound" to Settings.ACTION_SOUND_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS, "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "notifications" to "android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "data_usage" to Settings.ACTION_DATA_USAGE_SETTINGS,
        "mobile_network" to Settings.ACTION_NETWORK_OPERATOR_SETTINGS, "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS, "date_time" to Settings.ACTION_DATE_SETTINGS,
        "language" to Settings.ACTION_LOCALE_SETTINGS, "security" to Settings.ACTION_SECURITY_SETTINGS, "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "usage_access" to Settings.ACTION_USAGE_ACCESS_SETTINGS, "write_settings" to Settings.ACTION_MANAGE_WRITE_SETTINGS, "cast" to Settings.ACTION_CAST_SETTINGS,
        "vpn" to Settings.ACTION_VPN_SETTINGS, "print" to Settings.ACTION_PRINT_SETTINGS, "default_apps" to Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
    )

    fun tools(): List<JSONObject> = listOf(
        fn("clipboard", "The phone's clipboard, even while Blaze is in the background. get: text on it (and whether there's an image). get_image: look at a copied picture. set: copy text. set_image: copy a file or picture (path) so the user can paste it into any app. history: recent copied text. clear.",
            JSONObject().put("action", en("get", "get_image", "set", "set_image", "history", "clear")).put("text", str("Text to copy (set)")).put("path", str("File to copy (set_image), e.g. a Gallery file or /root/phone/chart.png")), listOf("action")),
        fn("post_notification", "Show a notification from Blaze (a reminder, a test, a progress bar). Tapping it can ask Blaze to do something.",
            JSONObject().put("title", str()).put("text", str()).put("progress", JSONObject().put("type", "integer").put("description", "0-100, optional"))
                .put("on_tap", str("Request Blaze runs when tapped, optional")), listOf("title")),
        fn("share_text", "Open the share sheet with some text or a link.", JSONObject().put("text", str()), listOf("text")),
        fn("phone_mode", "Ringer and screen: ringer normal/vibrate/silent, Do Not Disturb on/off, brightness 0-100, auto-rotate on/off.",
            JSONObject().put("setting", en("ringer", "dnd", "brightness", "rotation")).put("value", str("normal|vibrate|silent, on|off, or 0-100")), listOf("setting", "value")),
        fn("open_settings_page", "Open a specific Android settings page for the user.", JSONObject().put("page", en(*pages.keys.toTypedArray())), listOf("page")),
        fn("system_action", "Power menu, split screen, screenshot, lock screen, recents, notifications, quick settings.",
            JSONObject().put("action", en("power_dialog", "split_screen", "screenshot", "lock", "recents", "notifications", "quick_settings")), listOf("action")),
        fn("app_usage", "Which apps were used most today/this week (needs Usage access).", JSONObject().put("days", JSONObject().put("type", "integer")), emptyList()),
        fn("uninstall_app", "Ask Android to uninstall an app (the user confirms).", JSONObject().put("package", str("Package name")), listOf("package")),
        fn("list_apps", "List the apps installed on this phone (name, package, game or not); the chat shows them as a tappable card. Without a filter: every launchable app. " +
            "The filter matches loosely: words of the name or package, partial words, and categories ('car rally game', 'music', 'maps'). Use it before saying an app isn't installed, and instead of asking the user what they have.",
            JSONObject().put("filter", str("Optional words, e.g. 'rally game', 'bank', 'whatsapp'")), emptyList()),
        fn("open_camera", "Open the phone's own camera app for the user (photo or video, saved to their gallery), or scan a QR code. To SEE something yourself use blaze_camera instead.", JSONObject().put("mode", en("photo", "video", "qr")), emptyList()),
        fn("blaze_camera", "Open Blaze Camera so the user can show you something. The capture comes back into this chat as their next message. Modes: ask (look at a thing and answer), photo, text (read words, on-device OCR), translate (read words and translate them), scan (QR code / barcode), document (scan a page, crop and clean it).",
            JSONObject().put("mode", en("ask", "photo", "text", "translate", "scan", "document")), listOf("mode")),
    )

    private fun start(ctx: Context, i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }

    fun run(ctx: Context, name: String, a: JSONObject): String { return when (name) {
        "clipboard" -> {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            if (a.optString("action") == "set") { cm.setPrimaryClip(ClipData.newPlainText("Blaze", a.optString("text"))); "Copied to the clipboard." }
            else cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.take(4000)?.ifBlank { null }
                ?: "The clipboard is empty (Android only lets Blaze read it while Blaze is on screen)."
        }
        "post_notification" -> notify(ctx, a.optString("title"), a.optString("text"), if (a.has("progress")) a.optInt("progress") else -1, a.optString("on_tap"))
        "share_text" -> if (start(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.optString("text")), "Share"))) "Opened the share sheet." else "Couldn't open sharing."
        "phone_mode" -> mode(ctx, a.optString("setting"), a.optString("value").trim().lowercase())
        "open_settings_page" -> {
            val p = a.optString("page"); val act = pages[p] ?: return "Unknown page."
            if (start(ctx, Intent(act))) "Opened $p settings." else if (start(ctx, Intent(Settings.ACTION_SETTINGS))) "That page isn't on this phone, so I opened Settings." else "Couldn't open settings."
        }
        "system_action" -> system(a.optString("action"))
        "app_usage" -> usage(ctx, a.optInt("days", 1).coerceIn(1, 30))
        "list_apps" -> {
            val pm = ctx.packageManager
            val f = a.optString("filter").trim()
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
                .filter { f.isBlank() || it.first.contains(f, true) || it.second.contains(f, true) }.sortedBy { it.first.lowercase() }
            if (apps.isEmpty()) "No installed app matches \u201c$f\u201d." else "${apps.size} apps:\n" + apps.take(150).joinToString("\n") { "${it.first} (${it.second})" }
        }
        "uninstall_app" -> if (start(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.optString("package"))))) "Android is asking the user to confirm the uninstall." else "Couldn't start the uninstall."
        "blaze_camera" -> com.blaze.assistant.device.BlazeCamera.openFromTool(ctx, a.optString("mode"), a.optString("_chat").ifBlank { null }, a.optString("_asked").ifBlank { null })
        "open_camera" -> openCamera(ctx, a.optString("mode", "photo"))
        else -> "Unknown tool"
    } }

    /**
     * v0.28.x camera-fix: the camera app first (STILL_IMAGE / VIDEO_CAMERA need no permission), then Blaze Camera; QR goes
     * straight to Blaze Camera's Scan mode (ML Kit, it can act on the code). Every failure is logged with its real
     * exception, and the reply says what really happened instead of a blanket "No camera app found."
     */
    private fun openCamera(ctx: Context, mode: String): String {
        val cs = com.blaze.assistant.device.CameraSupport
        if (!cs.hasCamera(ctx)) return "This phone has no camera."
        fun tryStart(i: Intent, what: String): Throwable? = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null }
            catch (e: Exception) { cs.log("open_camera $what failed", e); e }
        val blaze = com.blaze.assistant.device.BlazeCamera
        return when (mode) {
            "qr" -> if (blaze.open(ctx, com.blaze.assistant.device.CameraModes.Mode.SCAN, com.blaze.assistant.device.CameraModes.Origin.STANDALONE)) "Opened Blaze Camera's scanner: point it at the code." else "Couldn't open the scanner (it's in Blaze's log)."
            "video" -> {
                val e1 = tryStart(Intent(android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA), "video camera") ?: return "Opened the video camera."
                // ACTION_VIDEO_CAPTURE needs Blaze's CAMERA permission (declared by the QR library), so only try it when granted.
                if (cs.granted(ctx) && tryStart(Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE), "video capture") == null) return "Opened the video camera."
                if (e1 is SecurityException) "Android blocked the video camera (${e1.message?.take(80)}). Blaze's own camera takes photos only."
                else if (!cs.granted(ctx)) "No video camera app answered directly, and Blaze's Camera permission is off. Turn it on in Settings → Apps → Blaze → Permissions."
                else "This phone has no video camera app Blaze can open."
            }
            else -> {
                tryStart(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "still camera") ?: return "Opened the camera."
                if (blaze.open(ctx, com.blaze.assistant.device.CameraModes.Mode.PHOTO, com.blaze.assistant.device.CameraModes.Origin.STANDALONE)) "The phone's camera app didn't open, so I opened Blaze Camera (photos save to Pictures/Blaze)."
                else "Couldn't open any camera (the reason is in Blaze's log)."
            }
        }
    }

    private var nextNote = 500

    private fun notify(ctx: Context, title: String, text0: String, progress: Int, onTap: String): String {
        val text = com.blaze.assistant.ui.Md.plain(text0)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("blaze_custom", "Blaze notes", NotificationManager.IMPORTANCE_DEFAULT))
        val b = NotificationCompat.Builder(ctx, "blaze_custom").setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(title.ifBlank { "Blaze" }).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
        if (progress in 0..100) b.setProgress(100, progress, false).setOnlyAlertOnce(true)
        if (onTap.isNotBlank()) {
            val pi = android.app.PendingIntent.getBroadcast(ctx, onTap.hashCode(), Intent(ctx, com.blaze.assistant.notify.ActionReceiver::class.java)
                .setAction(com.blaze.assistant.notify.ActionReceiver.DO).putExtra("prompt", onTap),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            b.setContentIntent(pi)
        } else {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                b.setContentIntent(android.app.PendingIntent.getActivity(ctx, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE))
            }
        }
        return try { NotificationManagerCompat.from(ctx).notify(nextNote++, b.build()); "Notification shown." }
        catch (_: SecurityException) { "Blaze isn't allowed to post notifications. Turn them on in Settings → Apps → Blaze → Notifications." }
    }

    private fun mode(ctx: Context, setting: String, v: String): String {
        val audio = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return when (setting) {
            "ringer" -> try {
                audio.ringerMode = when (v) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
                "Ringer set to $v."
            } catch (_: SecurityException) { start(ctx, Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")); "Android needs Do Not Disturb access for Blaze first: I opened that page, switch Blaze on." }
            "dnd" -> if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(if (v == "on") NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                "Do Not Disturb ${if (v == "on") "on" else "off"}."
            } else { start(ctx, Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")); "Allow Blaze Do Not Disturb access on the page I opened, then ask again." }
            "brightness", "rotation" -> if (!Settings.System.canWrite(ctx)) {
                start(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                "Android needs 'Modify system settings' for Blaze: I opened that page, switch it on and ask again."
            } else if (setting == "brightness") {
                val pct = v.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: return "Give brightness as 0-100."
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceAtLeast(1))
                "Brightness $pct%."
            } else {
                Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (v == "on") 1 else 0)
                "Auto-rotate ${if (v == "on") "on" else "off"}."
            }
            else -> "Unknown setting."
        }
    }

    private fun system(action: String): String {
        val svc = BlazeAccessibilityService.instance ?: return "Turn on Blaze phone control first."
        val g = when (action) {
            "power_dialog" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "split_screen" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return "Screenshots need Android 9+."
            "lock" -> if (Build.VERSION.SDK_INT >= 28) android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return "Locking needs Android 9+."
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "Unknown action."
        }
        return if (svc.performGlobalAction(g)) "Done: ${action.replace('_', ' ')}." else "The phone didn't allow ${action.replace('_', ' ')}."
    }

    private fun usage(ctx: Context, days: Int): String {
        val usm = ctx.getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return "Usage stats aren't available."
        val end = System.currentTimeMillis()
        val stats = try { usm.queryAndAggregateUsageStats(end - days * 86_400_000L, end) } catch (_: Exception) { emptyMap() }
        if (stats.isEmpty()) { start(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); return "Blaze needs Usage access: I opened that page, switch Blaze on and ask again." }
        val pm = ctx.packageManager
        return stats.values.filter { it.totalTimeInForeground > 60_000 }.sortedByDescending { it.totalTimeInForeground }.take(10).joinToString("\n") {
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(it.packageName, 0)).toString() } catch (_: Exception) { it.packageName }
            val m = it.totalTimeInForeground / 60_000
            "$label: ${if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"}"
        }.ifBlank { "No app was used for more than a minute." }
    }
}
