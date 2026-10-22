package com.blaze.assistant.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Why "Blaze lost phone control" and how to get it back in one tap (v0.20.0).
 *
 * Android has two separate facts: whether Blaze's accessibility service is switched on in Settings,
 * and whether it's actually connected right now. When Blaze's process dies (a browser page's renderer
 * crash used to take it down; Device care or "close all" can kill it), the service is still on but
 * disconnected for a moment, and on Samsung phones it is often switched off for good.
 */
object PhoneControl {
    enum class State { CONNECTED, RECONNECTING, OFF }

    /** Pure rule (tested): connected wins; switched on but not connected = reconnecting; else off. */
    fun state(connected: Boolean, enabledInSettings: Boolean): State = when {
        connected -> State.CONNECTED
        enabledInSettings -> State.RECONNECTING
        else -> State.OFF
    }

    fun enabledInSettings(ctx: Context): Boolean {
        val s = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { it.startsWith(ctx.packageName + "/") }
    }

    fun state(ctx: Context): State = state(BlazeAccessibilityService.instance != null, enabledInSettings(ctx))

    /** Banner words for each state ("" = no banner). */
    fun banner(s: State): Pair<String, String> = when (s) {
        State.CONNECTED -> "" to ""
        State.RECONNECTING -> "Phone control reconnecting…" to "Fix"
        State.OFF -> "Phone control is off" to "Turn on"
    }

    /** Straight to Blaze's own switch where Android allows it (Android 11+), else the accessibility list. */
    fun fixIntent(ctx: Context): Intent {
        if (Build.VERSION.SDK_INT >= 30) {
            val cn = ComponentName(ctx, BlazeAccessibilityService::class.java).flattenToString()
            val i = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(ctx.packageManager) != null) return i
        }
        return Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Last state seen, so a drop is logged once (it lands in the crash report for diagnosis). */
    @Volatile private var last: State? = null
    fun note(s: State) {
        val prev = last; last = s
        if (prev != null && prev != s) com.blaze.assistant.device.CrashLog.write("Phone control: $prev -> $s")
    }

    const val TIP = "Usually Android or Device care stopped Blaze in the background. Setting Blaze's battery to Unrestricted helps it stay on."
}
