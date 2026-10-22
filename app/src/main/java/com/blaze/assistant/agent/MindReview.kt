package com.blaze.assistant.agent

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * One mind's two memory passes (v0.18.0), both with the shared [Mind.EXTRACT_PROMPT]:
 *
 *  - [afterTurn]: after each finished turn, one cheap background call over the last ~6000 characters of the
 *    exchange; each line goes through Mind.save (dedupe, newest wins). Never blocks the answer.
 *  - Idle review ([MindReviewWorker], WorkManager): when the phone is idle and charging, or idle on Wi-Fi, it works
 *    through unreviewed journal turns (old chats included) in chunks of at most [Mind.CHUNK_CHARS], writes or merges
 *    memories and marks those turns reviewed. A failed model call leaves the turns queued. At most once every
 *    [THROTTLE_MS] and [CHUNKS_PER_RUN] chunks per run. Settings › Learning › *Review past conversations when idle*.
 *
 * Model: the free chain (FreeRouter: free tiers, cheapest and healthiest first; BlazeRouter's auto/best-free when it is
 * the brain), then the background lane of your own brain. Never more than one call at a time.
 */
object MindReview {
    const val THROTTLE_MS = 10 * 60_000L
    const val CHUNKS_PER_RUN = 3
    private const val WORK_POWER = "mind_review_power"
    private const val WORK_WIFI = "mind_review_wifi"
    private val lock = Mutex()

    var enabled: Boolean
        get() = Settings.bool("mind_review", true)
        set(v) = Settings.setBool("mind_review", v)

    /** (Re)schedules or cancels the idle review to match the toggle. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (!enabled) { wm.cancelUniqueWork(WORK_POWER); wm.cancelUniqueWork(WORK_WIFI); return }
        val power = PeriodicWorkRequestBuilder<MindReviewWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).setRequiresCharging(true).build()).build()
        val wifi = PeriodicWorkRequestBuilder<MindReviewWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true).build()).build()
        wm.enqueueUniquePeriodicWork(WORK_POWER, ExistingPeriodicWorkPolicy.KEEP, power)
        wm.enqueueUniquePeriodicWork(WORK_WIFI, ExistingPeriodicWorkPolicy.KEEP, wifi)
    }

    /** One memory call on the cheapest free model; null when nothing answered. */
    suspend fun cheap(req: JSONArray): String? {
        // v0.25.0: through Blaze Cloud like everything else (quick task).
        val r = try { LlmClient.complete(req, null, null, null, LlmClient.Lane.BACKGROUND) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        return r?.optString("content")?.takeIf { it.isNotBlank() }
    }

    /** Saves each extracted line. Returns how many lines were new or changed. */
    fun apply(items: List<Mind.Item>, sourceTurn: Long): Int =
        items.count { Mind.save(it.kind, it.subject, it.text, it.related, sourceTurn).first.let { r -> r == "saved" || r == "updated" } }

    /** After a finished turn (background). Returns how many memories changed. */
    suspend fun afterTurn(exchange: String): Int {
        if (!Mind.ready || exchange.isBlank()) return 0
        return lock.withLock {
            val raw = cheap(Mind.extractRequest(exchange.takeLast(Mind.EXTRACT_CHARS), Mind.core(Mind.all()))) ?: return@withLock 0
            apply(Mind.parseExtract(raw), 0)
        }
    }

    /**
     * Reviews up to [chunks] chunks of unreviewed journal turns. [call] is the model (tests pass a fake).
     * Returns how many turns were marked reviewed.
     */
    suspend fun review(chunks: Int = CHUNKS_PER_RUN, call: suspend (JSONArray) -> String? = { cheap(it) }): Int {
        val d = Mind.db() ?: return 0
        var done = 0
        lock.withLock {
            repeat(chunks) {
                // v0.19.0: chat turns, chat-app messages and life logs are reviewed in separate batches, each with its own
                // header; whatever is learned from overheard speech is always marked "(inferred)".
                val queue = d.unreviewed(200)
                if (queue.isEmpty()) return@withLock
                val cls = MindLogs.classOf(queue.first().conv)
                val whole = Mind.chunk(queue.takeWhile { MindLogs.classOf(it.conv) == cls })
                if (whole.isEmpty()) return@withLock
                // v0.45.0 reactions: turns the user gave 👎 teach nothing (marked reviewed, never sent to the model).
                val batch = whole.filterNot { try { TurnFeedback.excluded(it.conv, it.at) } catch (_: Exception) { false } }
                if (batch.isEmpty()) { d.markReviewed(whole.map { it.id }); done += whole.size; return@repeat }
                val raw = call(Mind.extractRequest(Mind.render(batch) + header(cls), Mind.core(Mind.all()))) ?: return@withLock
                val items = Mind.parseExtract(raw).let { l -> if (cls == "heard") l.map { it.copy(text = Mind.clean(it.text, inferred = true)) } else l }
                apply(items, batch.last().id)
                d.markReviewed(whole.map { it.id })
                done += whole.size
            }
        }
        return done
    }

    /** What the review is looking at, for the non-chat journal classes ([MindLogs.classOf]). */
    fun header(cls: String): String = when (cls) {
        "heard" -> "\n\nNOTE: the lines above are a LIFE LOG: speech the phone's mic overheard, transcribed on the phone (words may be wrong; speakers are unknown and may be other people). " +
            "Only keep what clearly matters to the user: people and who they are, plans, ideas, commitments and deadlines. Mark every line \"(inferred)\"."
        "notif" -> "\n\nNOTE: the lines above are CHAT MESSAGES from the user's messaging apps (role = sender and app; \"You\" = the user). Keep people and who they are to the user, plans and commitments; never codes or bank details."
        else -> ""
    }

    /** The worker's pass: skipped while Blaze is busy, off, or ran less than [THROTTLE_MS] ago. */
    suspend fun idlePass(): Int {
        val d = Mind.db() ?: return 0
        if (!enabled || Agent.busyChats.value.isNotEmpty()) return 0
        val last = d.meta("review_at").toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (now - last < THROTTLE_MS) return 0
        d.setMeta("review_at", now.toString())
        return review()
    }
}

class MindReviewWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try { Mind.init(applicationContext); MindReview.idlePass() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { }
        return Result.success()
    }
}
