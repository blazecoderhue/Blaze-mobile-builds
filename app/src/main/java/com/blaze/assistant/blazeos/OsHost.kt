package com.blaze.assistant.blazeos

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs

/**
 * v0.33.2 BlazeOS: what BlazeOS reads from the host phone. Memory and free space at boot (for [OsResources.allocate]),
 * and read-only status for the status row and control centre (battery, network). BlazeOS never changes anything on
 * the host: its volume, brightness and dark mode are its own.
 */
object OsHost {
    fun read(ctx: Context): HostInfo {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        try { am?.getMemoryInfo(mi) } catch (_: Exception) { }
        val free = try { StatFs(ctx.filesDir.absolutePath).availableBytes } catch (_: Exception) { 0L }
        val animOff = try {
            android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) { false }
        return HostInfo(
            totalRam = mi.totalMem, availRam = mi.availMem, lowRam = am?.isLowRamDevice == true,
            heapMb = am?.memoryClass ?: 0, freeDisk = free, sdk = Build.VERSION.SDK_INT, animationsOff = animOff,
        )
    }

    /** RAM right now: (available, total, Android's low-memory flag). */
    fun memory(ctx: Context): Triple<Long, Long, Boolean> {
        val mi = ActivityManager.MemoryInfo()
        try { ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi) } catch (_: Exception) { }
        return Triple(mi.availMem, mi.totalMem, mi.lowMemory)
    }

    /** This app's Java heap in use and its limit (bytes). */
    fun heap(): Pair<Long, Long> {
        val r = Runtime.getRuntime()
        return (r.totalMemory() - r.freeMemory()) to r.maxMemory()
    }

    data class Battery(val pct: Int, val charging: Boolean)

    fun battery(ctx: Context): Battery = try {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        Battery(if (level >= 0 && scale > 0) level * 100 / scale else -1,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    } catch (_: Exception) { Battery(-1, false) }

    /** "wifi", "cellular", "other" or "none" (read-only; BlazeOS can't switch the host's Wi-Fi). */
    fun network(ctx: Context): String = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    } catch (_: Exception) { "none" }

    fun networkLabel(n: String): String = when (n) { "wifi" -> "Wi-Fi"; "cellular" -> "Mobile data"; "other" -> "Connected"; else -> "Offline" }
}
