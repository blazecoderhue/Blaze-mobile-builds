package com.blaze.assistant.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.blaze.assistant.agent.FastLane
import com.blaze.assistant.agent.LlmClient
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** One button that tests every part of Blaze and says what's broken and why. */
object SelfCheck {
    data class Item(val name: String, val ok: Boolean, val detail: String, val fix: String = "")

    suspend fun run(ctx: Context, includeAi: Boolean = true): List<Item> {
        val out = mutableListOf<Item>()
        // AI
        val t0 = System.currentTimeMillis()
        if (includeAi) out += try {
            withTimeoutOrNull(25_000) {
                LlmClient.complete(JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with the single word OK.")), null, null, null, LlmClient.Lane.BACKGROUND)
            }?.let { Item("AI model", true, "Blaze Cloud answered in ${System.currentTimeMillis() - t0} ms") }
                ?: Item("AI model", false, "No answer in 25 s. Check your data or Wi-Fi.")
        } catch (e: Exception) { Item("AI model", false, e.message?.take(160) ?: "Failed") }
        val signed = Settings.ready
        out += Item("Blaze account", signed, if (signed) "Signed in: Blaze Cloud answers" else "Not signed in (Settings → Account).", "settings account")
        val dropped = Settings.secretsDropped
        val plain = com.blaze.assistant.agent.Secrets.plainFallbacks
        out += Item("Saved keys", dropped == 0 && plain == 0, when {
            plain > 0 -> "The phone's secure key store failed, so $plain key(s) were saved unencrypted this run. Restart the phone; if it keeps happening, send the crash report."
            dropped > 0 -> "$dropped key(s) or sign-in(s) came from another phone and couldn't be read here. Sign in to your Blaze account to sync model keys back, and reconnect accounts."
            else -> "Encrypted with this phone's Keystore" }, if (dropped > 0) "settings account" else "")
        val http = (listOf(Settings.customBase) + com.blaze.assistant.connect.Mcp.servers.value.map { it.url })
            .filter { it.isNotBlank() && com.blaze.assistant.connect.UrlSafety.isRiskyCleartext(it) }
        out += Item("Unencrypted links", http.isEmpty(), if (http.isEmpty()) "No http:// servers on the open internet"
            else "These use plain http:// over the internet, so keys and chats travel unencrypted: " +
                http.joinToString(", ") { com.blaze.assistant.connect.UrlSafety.host(it) } + ". Switch them to https://.")
        // Internet
        val net = try {
            withTimeoutOrNull(8_000) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                (java.net.URL("https://www.google.com/generate_204").openConnection() as java.net.HttpURLConnection).run { connectTimeout = 6000; readTimeout = 6000; responseCode in 200..399 } } } == true
        } catch (_: Exception) { false }
        out += Item("Internet", net, if (net) "Online" else "Can't reach the internet: cloud models, search and downloads won't work.")
        // Terminal
        val T = com.blaze.assistant.terminal.Terminal
        val sh = try { withTimeoutOrNull(10_000) { T.run("echo blaze-ok", 8) }?.contains("blaze-ok") == true } catch (_: Exception) { false }
        out += Item("Terminal", sh, if (sh) "Phone shell works${if (T.busyboxOk) " · BusyBox tools ready" else if (T.hasBusybox()) " · BusyBox blocked by Android (using Android's own tools)" else " · BusyBox missing (fewer tools)"}" else "The phone shell didn't answer.")
        val L = com.blaze.assistant.terminal.Linux
        val linuxOk = if (L.installed()) try { withTimeoutOrNull(30_000) { T.run("echo linux-ok", 25, true) }?.contains("linux-ok") == true } catch (_: Exception) { false } else false
        out += Item("Linux", linuxOk || !L.supported(), when {
            !L.supported() -> "Not supported on this CPU (Termux can be used instead)."
            !L.installed() -> "Not installed yet. run_code and git set it up automatically, or install it in Settings → Terminal."
            linuxOk -> "Alpine runs (${L.sizeMb()} MB)"
            else -> "Installed but didn't start. Reinstall it in Settings → Terminal." }, if (L.supported() && !linuxOk) "settings terminal" else "")
        val gh = com.blaze.assistant.connect.Connectors.connected("github")
        out += Item("Git / GitHub", true, if (gh) "GitHub connected: git can push and pull private repos" else "GitHub not connected: git works for public repos only (Settings → Connections).")
        val free = ctx.filesDir.usableSpace / 1e9
        out += Item("Storage", free > 0.5, "%.1f GB free for Blaze".format(free))
        // Runtime permissions
        val P = Manifest.permission.READ_CONTACTS
        listOf("Contacts" to arrayOf(P), "Calendar" to arrayOf(Manifest.permission.READ_CALENDAR), "SMS" to arrayOf(Manifest.permission.READ_SMS),
            "Call log" to arrayOf(Manifest.permission.READ_CALL_LOG), "Location" to arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)).forEach { (label, perms) ->
            val ok = Perms.has(ctx, *perms)
            out += Item("$label permission", true, if (ok) "Allowed" else "Not yet allowed: Blaze will ask the first time it needs it.")
        }
        val am = ctx.getSystemService(android.app.AlarmManager::class.java)
        val exact = android.os.Build.VERSION.SDK_INT < 31 || am?.canScheduleExactAlarms() == true
        out += Item("Exact alarms", exact, if (exact) "Routines and scheduled jobs run on time" else "Not allowed: routines may run a few minutes late.", "exact_alarms")
        out += Item("Fast lane", FastLane.ready(ctx), when {
            !FastLane.supported(ctx) -> "This phone's chip isn't supported."
            !FastLane.ready(ctx) -> "Model not downloaded yet."
            !Settings.fastLane -> "Downloaded but turned off."
            else -> "Ready · ${com.blaze.assistant.agent.IntentCache.size()} cached commands" })
        out += Item("Phone control", com.blaze.assistant.phone.BlazeAccessibilityService.instance != null,
            if (com.blaze.assistant.phone.BlazeAccessibilityService.instance != null) "Accessibility service connected" else "Turn on Blaze in Accessibility settings.", "accessibility")
        out += Item("Notifications access", com.blaze.assistant.notify.NotificationBrain.enabled(ctx),
            if (com.blaze.assistant.notify.NotificationBrain.enabled(ctx)) "Can read and reply to notifications" else "Off: Blaze can't see or react to notifications.", "notification_access")
        out += Item("Overlay", android.provider.Settings.canDrawOverlays(ctx), if (android.provider.Settings.canDrawOverlays(ctx)) "Orb and live task card allowed" else "Not allowed: no floating orb or task card.", "overlay")
        val mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        out += Item("Microphone", mic, if (mic) "Allowed" else "Not allowed: voice and wake word won't work.", "app_permissions")
        val notif = android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        out += Item("Post notifications", notif, if (notif) "Allowed" else "Not allowed: Blaze can't speak up on its own.", "app_permissions")
        val wake = java.io.File(ctx.filesDir, "wake-model").exists()
        out += Item("Wake word model", wake, if (wake) "Downloaded" else "Not downloaded yet (Settings → Voice).", "settings voice")
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val unrestricted = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        out += Item("Background running", unrestricted, if (unrestricted) "Battery optimisation is off for Blaze" else "Battery optimisation may stop Blaze in the background.", "battery")
        val conns = com.blaze.assistant.connect.Connectors.connectedCount()
        out += Item("Connections", true, "$conns connected")
        try {
            val m = withTimeoutOrNull(15_000) { com.blaze.assistant.connect.Mcp.tools().size }
            out += Item("MCP servers", m != null, if (m == null) "A server didn't answer in 15 s" else "$m tools available")
        } catch (e: Exception) { out += Item("MCP servers", false, e.message?.take(120) ?: "Failed") }
        // Voice out, voice in, instant commands, built-in browser
        out += Item("Spoken replies", com.blaze.assistant.voice.Voice.ttsOk(), if (com.blaze.assistant.voice.Voice.ttsOk()) "Text-to-speech ready" else "No text-to-speech engine ready. Install or enable Google Speech Services.")
        val sr = android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)
        out += Item("Speech recognition", sr, if (sr) "Available" else "No speech recognizer on this phone. Install the Google app.")
        val rules = listOf("set a timer for 5 minutes", "turn on the torch", "open whatsapp", "set an alarm for 6:30 am").all { com.blaze.assistant.agent.QuickRules.parse(it) != null }
        out += Item("Instant commands", rules, if (rules) "Alarms, timers, torch, volume and apps answer instantly, offline" else "Rule parser failed")
        val web = try { android.webkit.WebSettings.getDefaultUserAgent(ctx).isNotBlank() } catch (_: Exception) { false }
        out += Item("Built-in browser", web, if (web) "Ready · ${com.blaze.assistant.browser.BrowserEngine.tabs.size} tabs open" else "Android System WebView is missing or disabled. Update it in the Play Store.")
        val crashes = CrashLog.crashes()
        out += Item("Crash log", crashes == 0, if (crashes == 0) "No crashes recorded" else "$crashes crash(es) recorded. Send the report from this page.")
        val s = DeviceStatus.snap(ctx)
        val w = DeviceStatus.warnings(s)
        out += Item("Phone health", w.isEmpty(), if (w.isEmpty()) "Battery ${s.battery}%, ${"%.1f".format(s.freeStorageGb)} GB free" else w.joinToString("; "))
        return out
    }

    /** Text report for the agent's self_test tool. */
    suspend fun report(ctx: Context): String {
        val r = run(ctx, includeAi = false)
        val bad = r.filter { !it.ok }
        return "Self-check: ${r.size - bad.size}/${r.size} passed.\n" +
            (if (bad.isEmpty()) "Everything works." else "Problems:\n" + bad.joinToString("\n") { "- ${it.name}: ${it.detail}" }) +
            "\nOK: " + r.filter { it.ok }.joinToString(", ") { it.name }
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", "self_test")
        .put("description", "Check Blaze itself: API key, internet, terminal, Linux, git, phone control, notifications, permissions, storage, voice. Use when something isn't working or before a long task; then tell the user exactly what to fix.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray())))
}
