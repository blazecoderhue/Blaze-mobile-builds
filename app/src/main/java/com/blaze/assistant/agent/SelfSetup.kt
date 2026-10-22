package com.blaze.assistant.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Blaze's knowledge of its own setup: what's installed, granted, connected or missing, and how
 * to fix each one (open the exact system screen, or install it itself).
 */
object SelfSetup {
    data class Item(val id: String, val name: String, val ok: Boolean, val why: String, val fix: String, val essential: Boolean = false)

    /** Asks the open Blaze screen to show a settings page (ChatScreen listens). */
    val openPage = MutableStateFlow<String?>(null)

    private fun perm(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun audit(ctx: Context): List<Item> {
        val a11y = com.blaze.assistant.phone.BlazeAccessibilityService.isEnabled(ctx)
        val overlay = android.provider.Settings.canDrawOverlays(ctx)
        val notifs = Build.VERSION.SDK_INT < 33 || perm(ctx, Manifest.permission.POST_NOTIFICATIONS)
        val mic = perm(ctx, Manifest.permission.RECORD_AUDIO)
        val battery = ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        val wakeModel = com.blaze.assistant.voice.WakeModel.ready(ctx)
        val linux = com.blaze.assistant.terminal.Linux.installed()
        val skills = com.blaze.assistant.phone.Skills.groups.filter { !com.blaze.assistant.phone.Skills.granted(ctx, it.value) }.keys
        val conns = try { com.blaze.assistant.connect.Connectors.connectedCount() } catch (_: Exception) { 0 }
        return listOf(
            Item("brain", "Blaze account", Settings.ready, "Needed to think and chat: Blaze Cloud answers once you're signed in.", "open settings account and sign in", true),
            Item("accessibility", "Phone control", a11y && Settings.phoneControl, "Lets Blaze see and tap the screen.", "open accessibility", true),
            Item("overlay", "Draw over apps", overlay, "Needed for the floating orb and the panel over other apps.", "open overlay", true),
            Item("microphone", "Microphone", mic, "Needed for voice and \u201cHey Blaze\u201d.", "open app_permissions (or tap the mic in Blaze once)", true),
            Item("notifications", "Notifications", notifs, "So Blaze can tell you when tasks finish and send heads-ups.", "open app_permissions"),
            Item("wake_model", "\u201cHey Blaze\u201d voice model", wakeModel, "Offline wake word (about 40 MB, one time).", "install wake_model"),
            Item("wake_word", "\u201cHey Blaze\u201d listening", Settings.wakeWord && wakeModel, "Call Blaze hands-free.", "action wake_on (after the model is installed)"),
            Item("assistant", "Default assistant", com.blaze.assistant.assist.BlazeInteractionService.isDefault(ctx), "Hold Home / power to call Blaze over any app.", "open assistant"),
            Item("battery", "Unrestricted battery", battery, "Stops Android from killing Blaze in the background (wake word, routines).", "open battery"),
            Item("notification_access", "Notification access", com.blaze.assistant.notify.NotificationBrain.enabled(ctx), "Lets Blaze read and reply to notifications and warn you about urgent ones.", "open notification_access"),
            Item("skills", "Contacts, SMS, calls, calendar", skills.isEmpty(), if (skills.isEmpty()) "All granted." else "Missing: ${skills.joinToString()}.", "open settings control (grant them there)"),
            Item("fast_lane", "On-device fast lane", FastLane.ready(ctx), "Instant offline commands (about 35 MB).", "install fast_lane"),
            Item("terminal", "Terminal (shell + BusyBox)", java.io.File(ctx.applicationInfo.nativeLibraryDir, "libbusybox.so").exists(), "Built in; nothing to install.", "-"),
            Item("linux", "Linux (Alpine)", linux, "Real Linux for python3, node, git\u2026 (about 3.5 MB).", "install linux"),
            Item("connections", "Connected accounts", conns > 0, "$conns connected (Google, GitHub, Spotify, Notion, Slack).", "open settings connections"),
        )
    }

    /** Full readout for the model. */
    fun report(ctx: Context): String {
        val items = audit(ctx)
        val missing = items.filter { !it.ok }
        return buildString {
            append(SelfControl.status(ctx)).append("\n\nSetup (").append(items.count { it.ok }).append("/").append(items.size).append(" ready):\n")
            items.forEach { append(if (it.ok) "\u2713 " else "\u2715 ").append(it.name).append(" \u2014 ").append(it.why).apply { if (!it.ok) append(" Fix: ").append(it.fix) }.append('\n') }
            if (missing.isNotEmpty()) append("\nTo help the user finish setup: go one item at a time, essentials first, explain in one line why, then open or install it with blaze_self and check again.")
        }
    }

    /** One line for the system prompt: what's missing right now. */
    fun promptLine(ctx: Context): String = try {
        val missing = audit(ctx).filter { !it.ok && it.id != "connections" && it.id != "fast_lane" }
        if (missing.isEmpty()) "" else "\nYour own setup is missing: ${missing.joinToString { it.name }}. If something the user asks needs one of these, say so and offer to set it up (blaze_self setup_check / open / install)."
    } catch (_: Exception) { "" }

    /** Opens the system or Blaze screen that fixes [target]. */
    fun open(ctx: Context, target: String): String {
        val pkg = Uri.parse("package:" + ctx.packageName)
        val i: Intent? = when (target.lowercase().removePrefix("settings ").trim()) {
            "accessibility" -> Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "overlay" -> Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            "app_permissions", "permissions", "microphone", "notifications" -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            "battery" -> Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "notification_access" -> com.blaze.assistant.notify.NotificationBrain.settingsIntent()
            "assistant" -> { com.blaze.assistant.assist.BlazeInteractionService.openPicker(ctx); return "Opened the Digital assistant picker. Pick Blaze." }
            "exact_alarms" -> if (Build.VERSION.SDK_INT >= 31) Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            else -> null
        }
        if (i != null) return try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Opened the ${target.replace('_', ' ')} screen. Tell the user what to switch on there."
        } catch (e: Exception) { "Couldn't open it: ${e.message}" }
        // Otherwise a page inside Blaze's own settings.
        val page = settingsPageFor(target)
        openPage.value = page
        ctx.startActivity(Intent(ctx, com.blaze.assistant.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        return "Opened Blaze settings \u2192 $page."
    }

    suspend fun install(ctx: Context, what: String): String = when (what.lowercase()) {
        "linux" -> com.blaze.assistant.terminal.Linux.install()
        "wake_model", "voice_model" -> com.blaze.assistant.voice.WakeModel.download(ctx).fold({ "Voice model installed. Turn on the wake word with wake_on." }, { "Download failed: ${it.message}" })
        "fast_lane" -> FastLane.download(ctx).fold({ "Fast lane installed." }, { "Download failed: ${it.message}" })
        else -> "I can install: linux, wake_model, fast_lane."
    }
}


/**
 * Turns whatever the model wrote ("settings voice", "Voice & wake word", "ai/control/voice/term")
 * into a real settings page id. Unknown → "" (the settings list), never a blank page.
 */
fun settingsPageFor(raw: String): String {
    val ids = com.blaze.assistant.ui.settingsPages.map { it.id }
    val alias = mapOf("router" to "ai", "blazerouter" to "ai", "update" to "updates", "upgrade" to "updates", "permission" to "permissions", "version" to "about", "brain" to "ai", "model" to "ai", "models" to "ai", "keys" to "ai", "provider" to "ai",
        "wake" to "voice", "speech" to "voice", "tts" to "voice", "call" to "voice", "mic" to "voice", "speak" to "voice",
        "term" to "terminal", "shell" to "terminal", "termux" to "terminal", "linux" to "terminal",
        "look" to "feel", "theme" to "feel", "sounds" to "feel", "accounts" to "connections", "connected" to "connections",
        "personality" to "persona", "phone" to "control", "accessibility" to "control", "skills" to "control",
        "library" to "skills", "signin" to "account", "login" to "account", "signup" to "account", "lock" to "privacy", "safety" to "privacy", "diagnostics" to "device", "health" to "device", "export" to "backup",
        "brainstorm" to "logs", "lifelogs" to "logs", "transcripts" to "logs", "autoreply" to "replies")
    val words = raw.lowercase().replace("settings", " ").split(Regex("[^a-z_]+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return ""
    // Several pages listed (the model copied the example): only trust it when they all point to one page.
    val hits = words.mapNotNull { w -> if (w in ids) w else alias[w] ?: com.blaze.assistant.ui.settingsPages.firstOrNull { p -> p.title.lowercase().split(Regex("[^a-z]+")).contains(w) }?.id }.distinct()
    return if (hits.size == 1) hits.first() else ""
}
