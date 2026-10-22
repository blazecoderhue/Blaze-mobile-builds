package com.blaze.assistant.desktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.blaze.assistant.R
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.desktop.DesktopPlan.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * v0.45.2 "Keep desktop running". Android closes the proot Linux desktop to save memory when Blaze is in the
 * background. With this on, the desktop runs under a foreground service (ongoing notification, type specialUse on
 * Android 14), holds a partial wake lock while it runs, asks once for the battery-optimisation exemption, and starts
 * the desktop again if it was killed. Android 12+ can still stop child processes ("phantom process" limits) in rare
 * cases; then the Desktop page says so plainly and offers Restart.
 *
 * Pure rules in [KeepAlive] (DesktopAliveTest); the service and the Android calls below.
 */
object KeepAlive {
    const val PREF = "linux_desktop_keep_running"
    const val PREF_ASKED_BATTERY = "linux_desktop_keep_asked_battery"
    /** Off by default: it costs battery, so the user turns it on. */
    const val DEFAULT = false

    const val TITLE = "Keep desktop running"
    const val SUB = "Keeps the Linux desktop open while you use other apps (a notification shows while it runs). Uses more battery."
    const val CAVEAT = "Android 12 and newer can still close it in rare cases. If that happens, Blaze tells you here and you can restart it."
    const val KILLED_KEEP_NOTE = "Android stopped the desktop even though Keep desktop running is on. This can still happen on Android 12 and newer when the phone is short of memory. Your files are still there."

    /** Run the foreground service: the setting is on and the desktop is (or is becoming) up. */
    fun serviceWanted(keepOn: Boolean, phase: Phase, desktopOn: Boolean): Boolean =
        keepOn && desktopOn && (phase == Phase.RUNNING || phase == Phase.STARTING || phase == Phase.KILLED)

    /** Hold the wake lock only while the desktop really runs. */
    fun wakeLockWanted(keepOn: Boolean, phase: Phase): Boolean = keepOn && phase == Phase.RUNNING

    /** Ask for the battery exemption when the toggle goes on, once, and only if Blaze isn't exempt already. */
    fun askBattery(turningOn: Boolean, askedBefore: Boolean, alreadyExempt: Boolean): Boolean =
        turningOn && !askedBefore && !alreadyExempt

    /**
     * Restart a killed desktop: keep-running on (or the older "Restart by itself"), it should be on, the user didn't
     * stop it, it doesn't answer, and fewer than [limit] restarts in the last 10 minutes (no loop on a phone that is
     * short of memory).
     */
    fun shouldRepair(keepOn: Boolean, autoRestart: Boolean, desktopOn: Boolean, userStopped: Boolean, alive: Boolean,
                     recent: List<Long>, now: Long, limit: Int = 3): Boolean =
        (keepOn || autoRestart) && desktopOn && !userStopped && !alive && recent.count { now - it < 10 * 60_000L } < limit

    /** After the process itself was killed and the sticky service came back: start the desktop again? */
    fun restoreAfterProcessDeath(keepOn: Boolean, desktopOn: Boolean, alive: Boolean): Boolean = keepOn && desktopOn && !alive

    /** The line under "Closed by Android". */
    fun killedNote(keepOn: Boolean): String = if (keepOn) KILLED_KEEP_NOTE else DesktopPlan.KILLED_NOTE

    fun on(): Boolean = try { Settings.bool(PREF, DEFAULT) } catch (_: Throwable) { DEFAULT }
}

/** The Android side: the setting, the battery ask, and starting / stopping [DesktopAliveService]. */
object DesktopAlive {
    private val _on = MutableStateFlow(KeepAlive.on())
    val on: StateFlow<Boolean> = _on

    /** Turns the setting on/off. Returns true when the caller should show the battery-exemption request. */
    fun set(ctx: Context, value: Boolean): Boolean {
        Settings.setBool(KeepAlive.PREF, value); _on.value = value
        val app = ctx.applicationContext
        val ask = KeepAlive.askBattery(value, Settings.bool(KeepAlive.PREF_ASKED_BATTERY, false), exempt(app))
        if (ask) Settings.setBool(KeepAlive.PREF_ASKED_BATTERY, true)
        sync(app)
        return ask
    }

    fun exempt(ctx: Context): Boolean = try {
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    } catch (_: Throwable) { false }

    /** ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS for Blaze (needs the manifest permission); falls back to the list. */
    fun requestExemption(ctx: Context) {
        try {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try { ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }

    /** Starts or stops the service to match the setting and the desktop's phase. Safe to call often. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        val want = KeepAlive.serviceWanted(KeepAlive.on(), LinuxDesktop.state.value.phase, LinuxDesktop.desktopOn())
        try {
            if (want) {
                val i = Intent(app, DesktopAliveService::class.java)
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
            } else if (DesktopAliveService.running) app.stopService(Intent(app, DesktopAliveService::class.java))
        } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("DesktopAlive.sync", e) }
    }
}

class DesktopAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: kotlinx.coroutines.Job? = null
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var restoring = false

    companion object {
        private const val ID = 81
        private const val CHANNEL = "blaze_desktop_running"
        const val ACTION_STOP = "com.blaze.assistant.desktop.STOP_DESKTOP"
        @Volatile var running = false; private set
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun note(): Notification {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Linux desktop running", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(this, ID + 100, Intent(this, DesktopAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val st = LinuxDesktop.state.value
        val line = when (st.phase) { Phase.RUNNING -> "${LinuxDesktop.distro().name} is running. Keep desktop running is on."; Phase.KILLED -> "Android closed the desktop. Open Blaze to restart it."; else -> st.line.ifBlank { "Starting\u2026" } }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle("Linux desktop").setContentText(line)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "Stop desktop", stop)
            .also { b -> open?.let { b.setContentIntent(it) } }.build()
    }

    private fun foreground() {
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, note())
        } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("DesktopAliveService.startForeground", e) }
    }

    private fun wakeLock(want: Boolean) {
        try {
            if (want && wake?.isHeld != true) {
                if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blaze:desktop-running")
                    .apply { setReferenceCounted(false) }
                wake?.acquire(60 * 60_000L)   // renewed by the loop below while the desktop runs
            } else if (!want && wake?.isHeld == true) wake?.release()
        } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("DesktopAliveService.wakeLock", e) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        foreground()
        if (intent?.action == ACTION_STOP) {
            scope.launch { LinuxDesktop.stop(); stopSelf() }
            return START_NOT_STICKY
        }
        // Null intent = Android restarted this sticky service after killing Blaze's process: bring the desktop back.
        if (intent == null) { restoring = true; scope.launch {
            try { if (KeepAlive.restoreAfterProcessDeath(KeepAlive.on(), LinuxDesktop.desktopOn(), LinuxDesktop.alive())) LinuxDesktop.repair(applicationContext) }
            finally { restoring = false }
        } }
        if (loop?.isActive != true) loop = scope.launch {
            var last = ""; var renewed = 0L
            while (true) {
                val st = LinuxDesktop.state.value
                if (!restoring && !KeepAlive.serviceWanted(KeepAlive.on(), st.phase, LinuxDesktop.desktopOn())) { stopSelf(); break }
                val wantWake = KeepAlive.wakeLockWanted(KeepAlive.on(), st.phase)
                val now = System.currentTimeMillis()
                if (wantWake && now - renewed > 50 * 60_000L) { wake?.takeIf { it.isHeld }?.release(); renewed = now }
                wakeLock(wantWake)
                val key = "${st.phase}|${st.line}"
                if (key != last) { last = key; try { getSystemService(NotificationManager::class.java).notify(ID, note()) } catch (_: Exception) { } }
                if (st.phase == Phase.RUNNING) LinuxDesktop.keepAliveCheck(applicationContext)
                delay(15_000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        loop?.cancel()
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wake = null
        super.onDestroy()
    }
}
