package com.blaze.assistant.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.blaze.assistant.ui.Overlay
import com.blaze.assistant.voice.Voice
import com.blaze.assistant.voice.WakeWordService
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Blaze knowing and steering itself: "shut down", "stop", "be quiet", "hide the orb"… are handled
 * on-device before anything else (so they never get misread as a phone command), and the model
 * gets a blaze_self tool for the rest (switch model, change a setting, read its own crash log).
 */
object SelfControl {
    /** The open Blaze screen, so "close" can actually close it. */
    @Volatile var activity: WeakReference<android.app.Activity>? = null
    private val main = Handler(Looper.getMainLooper())

    private data class Intent(val re: Regex, val action: String)
    private val intents = listOf(
        Intent(Regex("""^(please |hey blaze,? |blaze,? )?(shut ?down|turn (yourself )?off|power off|quit|exit|close( (yourself|blaze|the panel|this))?|go away|go to sleep|sleep|dismiss|good ?bye|bye( blaze)?|that'?s all|nothing else|we'?re done)( (now|please|for now|blaze))*[.!]*$"""), "close"),
        Intent(Regex("""(^|\b)(nothing for now|that'?s it for now)\b.*\b(shut ?down|close|go away|sleep)\b"""), "close"),
        Intent(Regex("""^(please )?(stop|cancel|abort|never ?mind|forget it|hold on|wait|pause)( (it|that|this|now|please|everything))*[.!]*$"""), "stop"),
        Intent(Regex("""^(please )?(be quiet|shut up|stop talking|stop speaking|quiet|silence|hush)( (now|please|blaze))*[.!]*$"""), "quiet"),
        Intent(Regex("""^(please )?(help me )?(set ?up|finish (the )?setup|check (your|my) setup|what'?s (missing|not set up)|are you (fully )?set ?up)( blaze)?[?.!]*$"""), "setup"),
        Intent(Regex("""^(please )?(hide|remove) (the |your )?(orb|bubble|floating button)[.!]*$"""), "hide_orb"),
        Intent(Regex("""^(please )?(show|bring back|pin) (the |your )?(orb|bubble|floating button)[.!]*$"""), "show_orb"),
        Intent(Regex("""^(please )?(turn off|disable|stop) (the )?(wake word|hey blaze|listening for hey blaze)[.!]*$|^stop listening[.!]*$"""), "wake_off"),
        Intent(Regex("""^(please )?(turn on|enable|start) (the )?(wake word|hey blaze)[.!]*$"""), "wake_on"),
        Intent(Regex("""^(please )?(mute (yourself|your voice|your replies)|stop reading (your )?(replies|answers)( out loud| aloud)?|don'?t (talk|speak) out loud)[.!]*$"""), "voice_off"),
        Intent(Regex("""^(please )?(unmute|talk to me|speak (your )?(replies|answers)|read (your )?(replies|answers) (aloud|out loud))[.!]*$"""), "voice_on"),
    )

    /**
     * Instant on-device answers for the basics (time, date, battery), so they never wait on a model.
     * Returns the reply, or null when this isn't one of them.
     */
    fun quick(ctx: Context, text: String): String? {
        // v0.26.2: "what sound profile is my phone set to?", "am I on mute?" → straight from the phone's own readings.
        PhoneState.kind(text)?.let { k ->
            try {
                val snap = com.blaze.assistant.device.DeviceStatus.snap(ctx)
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                val dnd = nm != null && nm.currentInterruptionFilter > android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                return PhoneState.answer(k, snap.ringer, dnd, snap.battery, snap.charging, snap.network)
            } catch (_: Exception) { }
        }
        val t = text.trim().lowercase().replace(Regex("[?.!,]+"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("hey blaze ").removePrefix("blaze ").removePrefix("please ").removeSuffix(" please").removeSuffix(" blaze").trim()
        if (t.length > 45) return null
        val now = java.util.Date()
        val time = android.text.format.DateFormat.getTimeFormat(ctx).format(now)
        val date = java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.getDefault()).format(now)
        val timeQ = Regex("""^(what'?s|what is|tell me) (the )?(time|current time)( (now|right now|it is|is it))?$|^what time (is it|it is)( now| right now)?$|^(the )?time( now| please| right now)?$|^(current|exact) time$|^do you (know|have) the time$""")
        val dateQ = Regex("""^(what'?s|what is|tell me) (the |today'?s )?(date|day)( today)?$|^what (day|date) (is it|is today|is it today)$|^(today'?s )?date$|^what'?s today$""")
        val battQ = Regex("""^(what'?s |what is |how much |check )?(my |the )?(battery|charge)( level| percentage| left| life)?( left)?( do i have)?$|^how much battery (do i have|is left|left)$""")
        return when {
            timeQ.matches(t) -> "It's $time."
            dateQ.matches(t) -> "It's $date."
            battQ.matches(t) -> try {
                val i = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val lvl = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val sc = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                val plugged = (i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                if (lvl < 0) null else "${lvl * 100 / sc}%${if (plugged) " and charging" else ""}."
            } catch (_: Exception) { null }
            else -> null
        }
    }

    /** An on-device match for a self-command, or null. Works even while a task is running. */
    fun match(text: String): String? {
        val t = text.trim().lowercase().replace(Regex("\\s+"), " ")
        if (t.length > 60) return null
        return intents.firstOrNull { it.re.containsMatchIn(t) }?.action
    }

    /** Runs a self-action and returns what to say. */
    fun run(ctx: Context, action: String, args: JSONObject = JSONObject()): String = when (action) {
        "close" -> {
            Agent.stopAll(); Voice.stopSpeaking(); Voice.cancelListening()
            main.postDelayed({
                try { Overlay.closePanel() } catch (_: Throwable) { }
                try { activity?.get()?.moveTaskToBack(true) } catch (_: Throwable) { }
            }, 700)
            if (Settings.wakeWord) "Going quiet. Say \u201cHey Blaze\u201d when you need me." else "Going quiet. Tap the orb when you need me."
        }
        "setup" -> {
            val missing = SelfSetup.audit(ctx).filter { !it.ok && it.id !in setOf("connections", "fast_lane") }
            if (missing.isEmpty()) "I'm fully set up. Everything that matters is on."
            else "${missing.size} things left: ${missing.joinToString { it.name }}. Say “let's do it” and I'll take you through them one by one."
        }
        "stop" -> { Agent.stopAll(); Voice.stopSpeaking(); "Stopped." }
        "quiet" -> { Voice.stopSpeaking(); "" }
        "hide_orb" -> { main.post { Overlay.hideOrb(ctx) }; "Orb hidden. Ask me to bring it back any time." }
        "show_orb" -> if (Overlay.canDrawOverApps(ctx)) { main.post { Settings.orbPinned = true; Overlay.showOrb(ctx) }; "Orb's back." }
            else "I need permission to draw over other apps first. It's in Settings → Floating orb."
        "wake_off" -> { Settings.wakeWord = false; WakeWordService.stop(ctx); "Stopped listening for \u201cHey Blaze\u201d." }
        "wake_on" -> { Settings.wakeWord = true; WakeWordService.start(ctx)
            if (com.blaze.assistant.voice.WakeModel.ready(ctx)) "Listening for \u201cHey Blaze\u201d." else "Turned on. The voice model still needs downloading in Settings → Voice." }
        "voice_off" -> { Settings.speakReplies = false; Voice.stopSpeaking(); "Okay, I'll answer in text only." }
        "voice_on" -> { Settings.speakReplies = true; "Okay, I'll read answers aloud when you ask by voice." }
        "status", "setup_check" -> SelfSetup.report(ctx)
        "open" -> SelfSetup.open(ctx, args.optString("target"))
        "speed_stats" -> SpeedLog.summary() + SpeedLog.firstWordLine() + SpeedLog.lastPhases().let { if (it.isBlank()) "" else "\nLast turn: $it" } + SpeedLog.screenLine()
        // v0.33.1: where the tokens go, per feature, plus the tool cache and the learned router.
        "token_ledger" -> TokenLedger.report() + "\n" + ToolCache.stats() + "\n" + LearnedRoutes.stats()
        "tool_stats" -> ToolStats.report() + (LlmClient.lastHealed.takeIf { it.isNotBlank() }?.let { "\n\nLast self-healed error: ${it.take(200)}" } ?: "")
        "crash_log" -> com.blaze.assistant.device.CrashLog.read().takeLast(3000).ifBlank { "No errors logged." }
        // v0.25.0: no model choice any more; Blaze Cloud picks per message.
        "set_model" -> "Blaze picks the best free model for every message by itself. There's nothing to choose."
        "set_persona" -> {
            val id = args.optString("persona")
            val pz = Personas.all.firstOrNull { it.id.equals(id, true) || it.name.equals(id, true) }
            if (pz != null) { Settings.personaId = pz.id; "Personality set to ${pz.name}." } else "No personality called \u201c$id\u201d. Options: ${Personas.all.joinToString { it.name }}."
        }
        "set_setting" -> setSetting(args.optString("key"), args.optString("value"))
        else -> "Unknown self action \u201c$action\u201d."
    }

    private val boolKeys = mapOf(
        "phone_control" to "phone control", "speak_replies" to "read replies aloud", "followups" to "follow-up chips",
        "show_reasoning" to "show thinking", "particles" to "background dots", "fast_lane" to "fast lane", "auto_learn" to "auto-learning",
        "screen_memory" to "screen memory", "confirm_risky" to "confirm risky taps", "voice_loop" to "conversation mode",
        "show_taps" to "show where I tap", "sounds" to "sounds", "compact_panel" to "compact panel",
        "wake_gate" to "wake sound gate", "wake_screen_off" to "listen with screen off", "wake_charging" to "listen while charging", "wake_pocket" to "pause in pocket",
        "digest_mode" to "save non-urgent heads-ups for the briefs", "evening_brief" to "evening brief", "morning_brief" to "morning brief", "home_headsups" to "heads-ups about new things on Home", "speed_mode" to "speed mode (no animations)",
        "tool_cache" to "reuse identical web lookups", "speculative_fetch" to "prefetch top search results on Wi-Fi", "learned_router" to "learned tool router",
        "adaptive_think" to "adaptive thinking budget", "token_ledger" to "token ledger")

    private fun setSetting(key: String, value: String): String {
        val k = key.trim().lowercase()
        if (k in boolKeys) {
            val v = value.trim().lowercase() in setOf("true", "on", "yes", "1", "enable", "enabled")
            Settings.setBool(k, v); return "${boolKeys[k]} is now ${if (v) "on" else "off"}."
        }
        if (k == "wake_conf") { Settings.setStr("wake_conf", value); return "Wake sensitivity set." }
        return "I can't change \u201c$key\u201d. I can change: ${boolKeys.keys.joinToString()}, wake_conf."
    }

    fun status(ctx: Context): String {
        val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("?")
        return buildString {
            append("Blaze $v. Brain: Blaze Cloud (picks the best free model per message). Personality: ${Personas.byId(Settings.personaId).name}.\n")
            append("Phone control ${if (Settings.phoneControl && com.blaze.assistant.phone.BlazeAccessibilityService.instance != null) "on" else "off"}, ")
            append("wake word ${if (Settings.wakeWord) "on" else "off"}, read aloud ${if (Settings.speakReplies) "on" else "off"}, ")
            append("fast lane ${if (Settings.fastLane) "on" else "off"}, orb ${if (Overlay.orbShowing) "showing" else "hidden"}.\n")
            append("Changeable with set_setting: ${boolKeys.keys.joinToString()}.")
        }
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", "blaze_self")
        .put("description", "Control Blaze itself: close/go quiet, stop the task, hide or show the floating orb, wake word on/off, read-aloud on/off, switch AI model or personality, change one of Blaze's settings, check its own setup (setup_check: what's installed, granted or missing — use it to guide the user through full setup), open the exact system/Blaze screen that fixes something, install Linux / the voice model / the fast lane, read its crash log to diagnose a problem, or token_ledger: where its tokens go per feature (biggest costs + fixes), the tool cache and the learned router.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("close", "stop", "hide_orb", "show_orb", "wake_on", "wake_off",
                "voice_on", "voice_off", "status", "setup_check", "open", "install", "crash_log", "speed_stats", "tool_stats", "token_ledger", "set_model", "set_persona", "set_setting"))))
            .put("provider", JSONObject().put("type", "string")).put("model", JSONObject().put("type", "string"))
            .put("persona", JSONObject().put("type", "string"))
            .put("target", JSONObject().put("type", "string").put("description", "open: accessibility, overlay, app_permissions, battery, notification_access, assistant, exact_alarms, or ONE Blaze settings page id: ai, voice, control, terminal, connections, feel, persona, memory, routines, privacy, device, orb, backup"))
            .put("what", JSONObject().put("type", "string").put("description", "install: linux, wake_model or fast_lane"))
            .put("key", JSONObject().put("type", "string")).put("value", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action")))))
}
