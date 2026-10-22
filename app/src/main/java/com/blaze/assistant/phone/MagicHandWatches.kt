package com.blaze.assistant.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Magic Hand "watch this" (v0.24.7), Android side: the watch list in filesDir/magic_watches.json, an hourly
 * WorkManager check (never more often: [MagicHandWatch.MIN_GAP_MS]) and a re-read whenever the watched app comes to
 * the front. A value can only be read while its app is on screen, so a check with the app closed just waits for
 * the next one. A change posts one notification ("Price went down: K1,299 → K1,099").
 */
object MagicHandWatches {
    private const val FILE = "magic_watches.json"
    private const val WORK = "magic_hand_watches"
    private const val CH = "blaze_watches"
    private val _list = MutableStateFlow<List<MagicHandWatch.Watch>>(emptyList())
    val list: StateFlow<List<MagicHandWatch.Watch>> = _list
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun load(ctx: Context): List<MagicHandWatch.Watch> {
        if (!loaded) {
            _list.value = try { file(ctx).takeIf { it.exists() }?.readText()?.let(MagicHandWatch::listFromJson) ?: emptyList() } catch (_: Exception) { emptyList() }
            loaded = true
        }
        return _list.value
    }

    private fun save(ctx: Context, l: List<MagicHandWatch.Watch>) {
        _list.value = l
        try { file(ctx).writeText(MagicHandWatch.listToJson(l)) } catch (_: Exception) { }
        schedule(ctx)
    }

    fun add(ctx: Context, w: MagicHandWatch.Watch) = save(ctx, load(ctx).filter { it.id != w.id } + w)
    fun remove(ctx: Context, id: String) = save(ctx, load(ctx).filter { it.id != id })

    /** Hourly check while there's at least one watch; cancelled when the list is empty. */
    fun schedule(ctx: Context) {
        val wm = try { WorkManager.getInstance(ctx.applicationContext) } catch (_: Exception) { return }
        if (load(ctx).isEmpty()) { wm.cancelUniqueWork(WORK); return }
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MagicHandWatchWorker>(MagicHandWatch.CHECK_HOURS, TimeUnit.HOURS).build())
    }

    /** Re-reads the due watches of [pkg] if it's on screen; notifies about changes. Returns how many changed. */
    fun check(svc: BlazeAccessibilityService, pkg: String, now: Long = System.currentTimeMillis()): Int {
        if (com.blaze.assistant.agent.Agent.privateChat.value) return 0
        val all = load(svc)
        val due = all.filter { it.pkg == pkg && MagicHandWatch.due(it, now) }
        if (due.isEmpty()) return 0
        var changed = 0
        val updated = all.map { w ->
            if (w !in due) w else {
                val read = try { MagicHandScreen.reread(svc, w) } catch (_: Exception) { null }
                val (nw, c) = MagicHandWatch.apply(w, read, now)
                if (c.changed) { changed++; notifyChange(svc, nw, c) }
                if (c.unreadable) w else nw
            }
        }
        save(svc, updated)
        return changed
    }

    private fun notifyChange(ctx: Context, w: MagicHandWatch.Watch, c: MagicHandWatch.Change) {
        val app = ctx.applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 26) app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Blaze watches", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a value you asked Magic Hand to watch changes"
            })
        val open = app.packageManager.getLaunchIntentForPackage(w.pkg)?.let {
            android.app.PendingIntent.getActivity(app, w.id.hashCode(), it, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(app, CH).setSmallIcon(com.blaze.assistant.R.drawable.ic_stat_blaze).setColor(0xFF4C7DFF.toInt())
            .setContentTitle(w.app.ifBlank { "Blaze watch" }).setContentText(c.summary(w.label))
            .setAutoCancel(true).apply { open?.let { setContentIntent(it) } }.build()
        try { NotificationManagerCompat.from(app).notify(7400 + (w.id.hashCode() and 0xff), n) } catch (_: SecurityException) { }
    }
}

class MagicHandWatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try {
            val svc = BlazeAccessibilityService.instance ?: return Result.success()
            val pkg = svc.currentPkg()
            if (pkg.isNotBlank()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { MagicHandWatches.check(svc, pkg) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { }
        return Result.success()
    }
}
