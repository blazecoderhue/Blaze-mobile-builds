package com.blaze.assistant.device

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** A light tick when an action lands, a double tick when a task finishes. */
object Haptics {
    private fun vib(ctx: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    fun tick(ctx: Context) {
        if (!com.blaze.assistant.agent.Settings.haptics) return
        try {
            val v = vib(ctx) ?: return
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else v.vibrate(VibrationEffect.createOneShot(12, 60))
        } catch (_: Exception) { }
    }

    fun done(ctx: Context) {
        if (!com.blaze.assistant.agent.Settings.haptics) return
        try {
            val v = vib(ctx) ?: return
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
            else v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 15, 80, 15), -1))
        } catch (_: Exception) { }
    }
}
