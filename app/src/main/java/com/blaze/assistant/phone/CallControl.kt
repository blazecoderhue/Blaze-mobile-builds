package com.blaze.assistant.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityNodeInfo
import com.blaze.assistant.device.Perms
import com.blaze.assistant.voice.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Call controls: speaker, mute, end, answer, decline, hold, keypad and call volume.
 *
 * Two kinds of call:
 *  - a call with Blaze itself (the call screen): handled directly through [Voice].
 *  - a real phone call: Android's telecom API where it allows it (answer / end, needs the
 *    "answer phone calls" permission), the phone's own in-call buttons through the accessibility
 *    service for speaker / mute / hold / keypad (so it works on Samsung and other dialers), and
 *    AudioManager as a last resort.
 */
@Suppress("DEPRECATION")
object CallControl {
    val names = setOf("call_control")

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "call_control")
        .put("description", "Control a call in progress: the user's call with you (Blaze's call screen) or a real phone call. " +
            "speaker on/off (loudspeaker), mute on/off (their microphone), end, answer or decline a ringing call, hold, keypad digits (e.g. \"1\" for menus), " +
            "louder/quieter/max call volume, status. Also start_blaze_call to start a voice call with you. Use this, never the Settings app, when the user asks for loudspeaker, mute or hang up.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("status", "speaker", "mute", "end", "answer", "decline", "hold", "keypad", "louder", "quieter", "max_volume", "start_blaze_call"))))
            .put("on", JSONObject().put("type", "boolean").put("description", "speaker / mute / hold: true to turn on, false to turn off (default true)"))
            .put("digits", JSONObject().put("type", "string").put("description", "keypad: digits to press, e.g. \"1\" or \"*123#\"")))
            .put("required", JSONArray(listOf("action")))))

    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)
    private fun tm(ctx: Context) = ctx.getSystemService(TelecomManager::class.java)

    @SuppressLint("MissingPermission")
    private fun phoneCall(ctx: Context): Boolean =
        (Perms.has(ctx, Manifest.permission.READ_PHONE_STATE) && runCatching { tm(ctx).isInCall }.getOrDefault(false)) ||
            am(ctx).mode == AudioManager.MODE_IN_CALL

    @SuppressLint("MissingPermission")
    private fun ringing(ctx: Context): Boolean = am(ctx).mode == AudioManager.MODE_RINGTONE ||
        (Perms.has(ctx, Manifest.permission.READ_PHONE_STATE) && Build.VERSION.SDK_INT >= 26 &&
            runCatching { ctx.getSystemService(android.telephony.TelephonyManager::class.java).callState == android.telephony.TelephonyManager.CALL_STATE_RINGING }.getOrDefault(false))

    suspend fun run(ctx: Context, a: JSONObject): String {
        val action = a.optString("action")
        val on = if (a.has("on")) a.optBoolean("on") else true
        if (action == "start_blaze_call") {
            if (Voice.inCall) return "You're already on a call with the user."
            ctx.startActivity(com.blaze.assistant.IntentKey.sign(ctx, Intent(ctx, com.blaze.assistant.MainActivity::class.java).setAction(com.blaze.assistant.MainActivity.ACTION_CALL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)))
            return "Started a voice call with the user. Say one short line to greet them."
        }
        // A call with Blaze itself.
        if (Voice.inCall && !phoneCall(ctx)) return when (action) {
            "speaker" -> { Voice.callSpeaker.value = on; "Loudspeaker ${if (on) "on" else "off (earpiece)"}." }
            "mute" -> { Voice.callMute.value = on; if (on) "Muted the mic. Tell the user to tap Unmute to talk again." else "Mic unmuted." }
            "end" -> { Voice.callEnded.value = Voice.callEnded.value + 1; "Ended the call." }
            "louder", "quieter", "max_volume" -> volume(ctx, action, if (Voice.callSpeaker.value) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL)
            "status" -> "On a call with Blaze. Loudspeaker ${if (Voice.callSpeaker.value) "on" else "off"}, mic ${if (Voice.callMute.value) "muted" else "on"}."
            else -> "That doesn't apply to a call with Blaze."
        }
        return when (action) {
            "status" -> when {
                ringing(ctx) -> "A call is ringing."
                phoneCall(ctx) -> "A phone call is in progress. Loudspeaker ${if (speakerOn(ctx)) "on" else "off"}, mic ${if (am(ctx).isMicrophoneMute) "muted" else "on"}."
                else -> "No call right now."
            }
            "answer" -> answer(ctx)
            "decline" -> if (!ringing(ctx)) "Nothing is ringing." else end(ctx, "Declined the call.")
            "end" -> if (!phoneCall(ctx) && !ringing(ctx)) "There's no call to end." else end(ctx, "Hung up.")
            "speaker" -> if (!phoneCall(ctx)) "There's no phone call right now." else speaker(ctx, on)
            "mute" -> if (!phoneCall(ctx)) "There's no phone call right now." else mute(ctx, on)
            "hold" -> if (!phoneCall(ctx)) "There's no phone call right now." else
                toggle(ctx, listOf("hold", "hold call", "resume", "unhold"), on)?.let { "Call ${if (on) "on hold" else "resumed"}." } ?: "This phone's call screen has no Hold button I can reach."
            "keypad" -> keypad(ctx, a.optString("digits"))
            "louder", "quieter", "max_volume" -> volume(ctx, action, AudioManager.STREAM_VOICE_CALL)
            else -> "Unknown action."
        }
    }

    // ---------- real phone calls ----------

    @SuppressLint("MissingPermission")
    private suspend fun answer(ctx: Context): String {
        if (!ringing(ctx)) return "Nothing is ringing."
        if (Build.VERSION.SDK_INT >= 26 && Perms.ensure(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) {
            if (runCatching { tm(ctx).acceptRingingCall() }.isSuccess) return "Answered the call."
        }
        return if (tapAny(listOf("answer", "accept"))) "Answered the call." else "Couldn't answer it. Allow Blaze to answer calls (App info › Permissions › Phone)."
    }

    @SuppressLint("MissingPermission")
    private suspend fun end(ctx: Context, done: String): String {
        if (Build.VERSION.SDK_INT >= 28 && Perms.ensure(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) {
            val ok = runCatching { tm(ctx).endCall() }.getOrDefault(false)
            if (ok) return done
        }
        showCallScreen(ctx)
        return if (tapAny(listOf("end call", "end", "decline", "hang up", "reject"))) done
        else "Couldn't hang up from here. Allow Blaze to manage calls (App info › Permissions › Phone)."
    }

    private fun speakerOn(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) am(ctx).communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        else am(ctx).isSpeakerphoneOn

    private suspend fun speaker(ctx: Context, on: Boolean): String {
        if (speakerOn(ctx) == on) return "Loudspeaker is already ${if (on) "on" else "off"}."
        showCallScreen(ctx)
        // The dialer's own button is the reliable way: apps can't route someone else's call audio on new Android.
        if (toggle(ctx, listOf("speaker", "speakerphone", "loudspeaker", "speaker phone"), on) != null) return "Loudspeaker ${if (on) "on" else "off"}."
        withContext(Dispatchers.Main) {
            val am = am(ctx)
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
                else am.clearCommunicationDevice()
            }
            am.isSpeakerphoneOn = on
        }
        delay(300)
        return if (speakerOn(ctx) == on) "Loudspeaker ${if (on) "on" else "off"}." else "This phone didn't let me switch the loudspeaker. Turn on Blaze's accessibility service so I can press the call screen's Speaker button."
    }

    private suspend fun mute(ctx: Context, on: Boolean): String {
        showCallScreen(ctx)
        if (toggle(ctx, listOf("mute", "mute call", "microphone"), on) != null) return if (on) "Muted your mic." else "Unmuted your mic."
        withContext(Dispatchers.Main) { am(ctx).isMicrophoneMute = on }
        return if (am(ctx).isMicrophoneMute == on) (if (on) "Muted your mic." else "Unmuted your mic.") else "Couldn't change mute. Turn on Blaze's accessibility service."
    }

    private suspend fun keypad(ctx: Context, digits: String): String {
        val d = digits.filter { it.isDigit() || it == '*' || it == '#' }
        if (d.isEmpty()) return "Say which digits to press."
        showCallScreen(ctx)
        if (findAny(listOf("1")) == null) { tapAny(listOf("keypad", "dialpad", "dial pad")); delay(500) }
        var pressed = 0
        for (ch in d) {
            val label = when (ch) { '*' -> "*"; '#' -> "#"; else -> ch.toString() }
            if (tapAny(listOf(label), exact = true)) pressed++ else break
            delay(250)
        }
        return if (pressed == d.length) "Pressed $d." else "Pressed $pressed of ${d.length} digits; the keypad wasn't reachable."
    }

    private fun volume(ctx: Context, action: String, stream: Int): String {
        val am = am(ctx)
        when (action) {
            "louder" -> repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "quieter" -> repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            else -> am.setStreamVolume(stream, am.getStreamMaxVolume(stream), AudioManager.FLAG_SHOW_UI)
        }
        return "Call volume ${am.getStreamVolume(stream)} of ${am.getStreamMaxVolume(stream)}."
    }

    @SuppressLint("MissingPermission")
    private suspend fun showCallScreen(ctx: Context) {
        if (Perms.has(ctx, Manifest.permission.READ_PHONE_STATE)) runCatching { tm(ctx).showInCallScreen(false) }
        delay(600)
    }

    // ---------- the dialer's own buttons ----------

    private fun labels(n: AccessibilityNodeInfo) = listOfNotNull(n.text?.toString(), n.contentDescription?.toString(),
        n.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ')).map { it.trim().lowercase() }

    private fun findAny(want: List<String>, exact: Boolean = false): AccessibilityNodeInfo? {
        val root = BlazeAccessibilityService.instance?.rootInActiveWindow ?: return null
        val w = want.map { it.lowercase() }
        var best: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || best != null) return
            val ls = labels(n)
            if (ls.any { l -> w.any { x -> l == x || (!exact && (l.startsWith("$x ") || l.endsWith(" $x"))) } } && n.isVisibleToUser) {
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                best = c ?: n; return
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return best
    }

    private suspend fun tapAny(want: List<String>, exact: Boolean = false): Boolean {
        val svc = BlazeAccessibilityService.instance ?: return false
        val n = findAny(want, exact) ?: return false
        return svc.tapNode(n)
    }

    /** Set an on/off call button; null when the button isn't on screen. */
    private suspend fun toggle(ctx: Context, want: List<String>, on: Boolean): Boolean? {
        val svc = BlazeAccessibilityService.instance ?: return null
        val n = findAny(want) ?: return null
        val desc = if (Build.VERSION.SDK_INT >= 30) n.stateDescription?.toString()?.lowercase().orEmpty() else ""
        val state = when {
            n.isCheckable -> n.isChecked
            desc.isNotEmpty() -> desc == "on" || ((desc.contains("selected") || desc.contains("checked")) && !desc.contains("not "))
            else -> n.isSelected
        }
        if (state == on) return true
        return svc.tapNode(n)
    }
}
