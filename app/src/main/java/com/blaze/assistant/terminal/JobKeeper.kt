package com.blaze.assistant.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blaze.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps background terminal jobs alive when Blaze is closed (a foreground service holds the
 * process), enforces each job's time budget, and posts a notification when a job finishes or
 * fails, with the last lines of its output. Stops itself when no jobs are left.
 */
class JobKeeper : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: kotlinx.coroutines.Job? = null

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, JobKeeper::class.java)
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (_: Exception) { }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun note(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("blaze_jobs", "Blaze background jobs", NotificationManager.IMPORTANCE_MIN))
        return NotificationCompat.Builder(this, "blaze_jobs").setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle("Blaze is running jobs").setContentText(text).setOngoing(true).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = note("${Terminal.jobCount()} running")
            if (Build.VERSION.SDK_INT >= 29) startForeground(78, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(78, n)
        } catch (_: Exception) { }
        if (loop?.isActive != true) loop = scope.launch {
            while (true) {
                delay(2000)
                Terminal.sweep { msg -> com.blaze.assistant.notify.Notifier.proactive(msg, "", false) }
                val left = Terminal.jobCount()
                if (left == 0) { stopSelf(); break }
                try { getSystemService(NotificationManager::class.java).notify(78, note("$left running")) } catch (_: Exception) { }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { loop?.cancel(); super.onDestroy() }
}
