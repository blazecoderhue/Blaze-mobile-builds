package com.blaze.assistant.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ack-and-background (HQ 22 Oct 20:35): an acknowledged task keeps going when you leave Blaze, and is never lost silently.
 *
 *  - [Ledger]: every acknowledged task is written down (synchronously, so a kill can't lose it) and crossed off when its
 *    turn ends. A task still on the ledger after a restart either got its Cloud-run reply (picked up by
 *    Agent.pickUpCloudRuns) or gets one honest line: it stopped before it could be confirmed. Never a false "done".
 *  - [TaskWorker]: a WorkManager job (expedited when the quota allows) that holds the process while a task runs and,
 *    after the process was killed, starts it again so the pick-up delivers the result or the honest notice. On Android
 *    12+ an expedited job shows no notification; older versions need one, so it is IMPORTANCE_MIN, silent, no heads-up,
 *    and says nothing about "working" ([QuietChannel]).
 */
object TaskKeeper {
    const val UNIQUE = "blaze_task_keeper"
    const val MAX_HOLD_MS = 9 * 60_000L

    data class Task(val chat: String, val user: Long, val said: String, val at: Long)

    /** The honest line for a task that stopped when Blaze was closed (no reply came back for it). */
    fun stoppedLine(said: String): String {
        val s = said.trim().replace(Regex("\\s+"), " ").let { if (it.length > 60) it.take(57).trimEnd() + "\u2026" else it }
        return "I was working on \u201c$s\u201d when Blaze closed, so it stopped before I could confirm it. Send it again and I'll finish it."
    }

    /** Pure JSON form of the ledger (TaskKeeperTest). */
    object Codec {
        fun encode(list: List<Task>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("chat", it.chat).put("user", it.user).put("said", it.said.take(400)).put("at", it.at)) }
        }.toString()
        fun decode(s: String?): List<Task> = try {
            val a = JSONArray(s ?: "[]")
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Task(it.optString("chat"), it.optLong("user"), it.optString("said"), it.optLong("at")) }
                .filter { it.chat.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * What to do with a task left on the ledger: [Verdict.ANSWERED] (a reply came after it), [Verdict.WAIT] (its Cloud run
     * is still being followed), [Verdict.REPORT] (nothing will answer it: one honest line), [Verdict.DROP] (too old).
     */
    enum class Verdict { ANSWERED, WAIT, REPORT, DROP }

    fun verdict(t: Task, answered: Boolean, cloudPending: Boolean, now: Long): Verdict = when {
        answered -> Verdict.ANSWERED
        now - t.at > CloudRuns.KEEP_MS -> Verdict.DROP
        cloudPending -> Verdict.WAIT
        else -> Verdict.REPORT
    }

    class Ledger(private val get: () -> String?, private val put: (String) -> Unit) {
        private val lock = Any()
        fun all(): List<Task> = synchronized(lock) { Codec.decode(get()) }
        fun add(t: Task) = synchronized(lock) { put(Codec.encode(Codec.decode(get()).filterNot { it.chat == t.chat && it.user == t.user } + t)) }
        fun remove(chat: String, user: Long) = synchronized(lock) { put(Codec.encode(Codec.decode(get()).filterNot { it.chat == chat && it.user == user })) }
    }

    @Volatile private var ledger0: Ledger? = null

    fun ledger(ctx: Context): Ledger = ledger0 ?: synchronized(this) {
        ledger0 ?: run {
            val p = ctx.applicationContext.getSharedPreferences("blaze_tasks", Context.MODE_PRIVATE)
            // commit(): written before the next line runs, so a kill right after the ack can't lose the task.
            Ledger({ p.getString("live", null) }, { v -> p.edit().putString("live", v).commit() })
        }.also { ledger0 = it }
    }

    /** A task was acknowledged: write it down and make sure the keeper job runs. Never throws. */
    fun started(ctx: Context, t: Task) {
        try { ledger(ctx).add(t) } catch (_: Throwable) { }
        try {
            val req = OneTimeWorkRequestBuilder<TaskWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        } catch (_: Throwable) { }
    }

    /** Its turn ended (reply, failure or stop): cross it off. */
    fun finished(ctx: Context, chat: String, user: Long) { try { ledger(ctx).remove(chat, user) } catch (_: Throwable) { } }
}

/** Holds the process while acknowledged tasks run; after a kill, its rerun lets Agent.init's pick-up finish or report them. */
class TaskWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val t0 = System.currentTimeMillis()
        // Agent.init (BlazeApp.onCreate) has already started the Cloud-run pick-up; it also settles the ledger.
        while (System.currentTimeMillis() - t0 < TaskKeeper.MAX_HOLD_MS) {
            val live = try { Agent.anyTaskRunning() } catch (_: Throwable) { false }
            if (!live) {
                try { Agent.settleTasks() } catch (_: Throwable) { }
                if (TaskKeeper.ledger(applicationContext).all().isEmpty()) return Result.success()
            }
            delay(2_000)
        }
        return if (TaskKeeper.ledger(applicationContext).all().isEmpty()) Result.success() else Result.retry()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = QuietChannel.info(applicationContext)
}

/** Android < 12 needs a notification for an expedited job: minimal, silent, no heads-up, no "working" words. */
object QuietChannel {
    const val ID = "blaze_quiet"
    const val NOTE_ID = 41
    const val TITLE = "Blaze"

    fun info(ctx: Context): ForegroundInfo {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            nm?.createNotificationChannel(android.app.NotificationChannel(ID, "Background tasks", android.app.NotificationManager.IMPORTANCE_MIN).apply {
                description = "Only on older Android versions, while a task you gave Blaze finishes in the background. Silent."
                setShowBadge(false); setSound(null, null); enableVibration(false)
            })
        }
        val n = androidx.core.app.NotificationCompat.Builder(ctx, ID)
            .setSmallIcon(com.blaze.assistant.R.drawable.ic_stat_blaze)
            .setContentTitle(TITLE)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setSilent(true).setOngoing(true).setShowWhen(false)
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(NOTE_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTE_ID, n)
    }
}
