package com.blaze.assistant.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blaze.assistant.MainActivity
import com.blaze.assistant.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * v0.33.0 Time Capsule: "write a letter to me for next year", "remind future me why I started this". A sealed note
 * (optionally with a snapshot of what Blaze knows about your goals right now) that stays locked, unreadable even by
 * Blaze's tools, until its date; then it opens as a notification and lands in the chat. Pure date maths in [openAt]
 * (TimeCapsuleTest).
 */
object TimeCapsule {
    const val TOOL = "time_capsule"
    private const val CH = "blaze_capsule"

    data class Capsule(val id: String, val sealedAt: Long, val openAt: Long, val title: String, val text: String, val opened: Boolean)

    /** "2027-10-18", "in 30 days" (days), or "1y"/"6m" style. Midday local time. Null if invalid or in the past. */
    fun openAt(date: String?, days: Int, now: Long): Long? {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        if (!date.isNullOrBlank()) {
            val m = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""").find(date.trim()) ?: Regex("""^(\d+)\s*([dwmy])$""", RegexOption.IGNORE_CASE).find(date.trim())?.let { r ->
                val n = r.groupValues[1].toInt()
                when (r.groupValues[2].lowercase()) { "d" -> c.add(Calendar.DAY_OF_MONTH, n); "w" -> c.add(Calendar.DAY_OF_MONTH, 7 * n); "m" -> c.add(Calendar.MONTH, n); else -> c.add(Calendar.YEAR, n) }
                c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                return c.timeInMillis.takeIf { it > now }
            } ?: return null
            c.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(), 12, 0, 0); c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis.takeIf { it > now }
        }
        if (days <= 0) return null
        c.add(Calendar.DAY_OF_MONTH, days); c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "capsules.json")
    @Synchronized fun all(ctx: Context): List<Capsule> = try {
        val a = JSONArray(file(ctx).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { Capsule(it.optString("id"), it.optLong("sealed"), it.optLong("open"), it.optString("title"), it.optString("text"), it.optBoolean("opened")) }
    } catch (_: Exception) { emptyList() }
    @Synchronized private fun write(ctx: Context, l: List<Capsule>) = file(ctx).writeText(JSONArray(l.map {
        JSONObject().put("id", it.id).put("sealed", it.sealedAt).put("open", it.openAt).put("title", it.title).put("text", it.text).put("opened", it.opened) }).toString())

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Time Capsule: seal a note for the user's future self (or from a conversation) that opens on a date as a notification " +
            "and in the chat. Sealed notes can't be read before then (list shows only titles and dates).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("seal", "list", "cancel"))))
            .put("title", JSONObject().put("type", "string")).put("text", JSONObject().put("type", "string").put("description", "the note, in the user's words"))
            .put("open_on", JSONObject().put("type", "string").put("description", "YYYY-MM-DD, or 30d / 6m / 1y"))
            .put("days", JSONObject().put("type", "integer")).put("id", JSONObject().put("type", "string")))
            .put("required", JSONArray(listOf("action")))))

    fun run(ctx: Context, a: JSONObject): String {
        val df = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
        val now = System.currentTimeMillis()
        return when (a.optString("action")) {
            "seal" -> {
                val text = a.optString("text").trim(); if (text.isEmpty()) return "What should the capsule say?"
                val at = openAt(a.optString("open_on"), a.optInt("days", 0), now) ?: return "When should it open? Give a future date."
                val c = Capsule("tc" + now.toString(36), now, at, a.optString("title").ifBlank { "A note from ${df.format(now)}" }.take(60), text.take(8000), false)
                write(ctx, all(ctx) + c); schedule(ctx)
                "Sealed \u201c${c.title}\u201d. It opens on ${df.format(at)}."
            }
            "list" -> all(ctx).filter { !it.opened }.sortedBy { it.openAt }.joinToString("\n") { "${it.id}: \u201c${it.title}\u201d · sealed ${df.format(it.sealedAt)} · opens ${df.format(it.openAt)}" }
                .ifBlank { "No sealed capsules." }
            "cancel" -> { val id = a.optString("id"); val l = all(ctx); if (l.none { it.id == id }) "No capsule $id." else { write(ctx, l.filterNot { it.id == id }); "Capsule removed." } }
            else -> "Unknown action."
        }
    }

    /** Next wake: the earliest sealed capsule (WorkManager, survives reboots). */
    fun schedule(ctx: Context) {
        val next = all(ctx).filter { !it.opened }.minOfOrNull { it.openAt } ?: return
        val delay = (next - System.currentTimeMillis()).coerceAtLeast(5_000)
        try { WorkManager.getInstance(ctx).enqueueUniqueWork("time_capsule", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TimeCapsuleWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()) } catch (_: Throwable) { }
    }

    fun openDue(ctx: Context) {
        val now = System.currentTimeMillis()
        val l = all(ctx); val due = l.filter { !it.opened && it.openAt <= now + 60_000 }
        if (due.isEmpty()) { schedule(ctx); return }
        write(ctx, l.map { c -> if (due.any { it.id == c.id }) c.copy(opened = true) else c })
        val df = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Time capsules", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 77, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        due.forEach { c ->
            val body = "Sealed ${df.format(c.sealedAt)}:\n\n${c.text}"
            try { nm.notify(4500 + (c.id.hashCode() and 0xff), NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat_blaze)
                .setContentTitle("Time capsule opened: ${c.title}").setContentText(c.text.take(120)).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open).setAutoCancel(true).build()) } catch (_: Throwable) { }
            try { Agent.note("\u23f3 **Time capsule opened: ${c.title}** (sealed ${df.format(c.sealedAt)})\n\n${c.text}", asReply = true) } catch (_: Throwable) { }
        }
        schedule(ctx)
    }
}

class TimeCapsuleWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result { try { TimeCapsule.openDue(applicationContext) } catch (_: Throwable) { }; return Result.success() }
}
