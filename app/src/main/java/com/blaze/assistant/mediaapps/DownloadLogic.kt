package com.blaze.assistant.mediaapps

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.30.1: the download queue's state, persisted as JSON so a download survives a network drop, the app being killed
 * or the phone rebooting (the worker picks it up again and resumes each part from its .part file with an HTTP Range).
 * Pure (DownloadLogicTest); the worker is in Downloads.kt.
 */
enum class DlStatus { QUEUED, RUNNING, PAUSED, FAILED, DONE, CANCELED }

data class DlPart(
    val key: String,        // StoreFormat.key (YouTube itag, archive.org file name, …)
    val url: String,
    val total: Long,        // bytes, 0 = unknown until the first response
    val done: Long = 0,     // bytes already in the .part file
    val container: String = "",
)

data class DlJob(
    val id: String,
    val videoId: String,        // the source's item id (YouTube video id, archive.org identifier, Jamendo track id)
    val kind: String,           // audio | video
    val title: String,
    val channel: String,
    val durationSec: Int,
    val thumb: String,
    val quality: String,        // Choice.label
    val choiceId: String,       // Choice.id, to pick the same streams again when urls expire
    val ext: String,            // final file extension
    val parts: List<DlPart>,
    val status: DlStatus = DlStatus.QUEUED,
    val attempts: Int = 0,
    val error: String = "",
    val nextTryAt: Long = 0,
    val createdAt: Long = 0,
    val outUri: String = "",
    val source: String = "youtube",   // StoreSource.id
    val licence: String = "",
    val fileTitle: String = "",       // one track of an album item
    val subs: String = "",            // wave A 25: JSON (Subs.indexJson) of caption tracks saved with the video for offline
) {
    val total: Long get() = parts.sumOf { it.total }
    val done: Long get() = parts.sumOf { it.done.coerceAtMost(if (it.total > 0) it.total else Long.MAX_VALUE) }
    val progress: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val active: Boolean get() = status == DlStatus.QUEUED || status == DlStatus.RUNNING
}

object DlLogic {
    const val MAX_ATTEMPTS = 8
    const val CHUNK = 8L * 1024 * 1024      // YouTube throttles single long reads; 8 MB ranges stay fast
    const val SPACE_MARGIN = 50L * 1024 * 1024

    /** 2 s, 4 s, 8 s … capped at 5 min. */
    fun backoffMs(attempt: Int): Long = (2000L shl (attempt.coerceIn(0, 20))).coerceAtMost(5 * 60_000L)

    /**
     * A failed try. [transient] = network drop, timeout, 5xx, an expired url (403/410): queued again with backoff until
     * [MAX_ATTEMPTS]; anything else (no space, unplayable video) fails straight away.
     */
    fun onError(job: DlJob, message: String, transient: Boolean, now: Long): DlJob {
        val a = job.attempts + 1
        return if (transient && a < MAX_ATTEMPTS) job.copy(status = DlStatus.QUEUED, attempts = a, error = message, nextTryAt = now + backoffMs(a - 1))
        else job.copy(status = DlStatus.FAILED, attempts = a, error = message, nextTryAt = 0)
    }

    /** Bytes written: progress made resets the attempt count (a flaky network shouldn't fail a long download). */
    fun onProgress(job: DlJob, partIndex: Int, done: Long, total: Long = -1): DlJob = job.copy(
        parts = job.parts.mapIndexed { i, p -> if (i == partIndex) p.copy(done = done, total = if (total >= 0) total else p.total) else p },
        attempts = 0, error = "", status = DlStatus.RUNNING)

    /** The Range header for the next chunk of a part: from what we have, at most [chunk] bytes. */
    fun range(done: Long, total: Long, chunk: Long = CHUNK): String {
        val end = if (total > 0) minOf(total - 1, done + chunk - 1) else done + chunk - 1
        return "bytes=$done-$end"
    }

    /** True when a part's url is about to expire (or has): fetch fresh urls before the next request. */
    fun urlExpired(expireSec: Long, nowMs: Long, marginSec: Long = 120): Boolean = expireSec > 0 && nowMs / 1000 + marginSec >= expireSec

    /** Stream urls that carry their expiry (YouTube's googlevideo: expire=unix seconds); 0 when absent. */
    fun expiryOf(url: String): Long = Regex("""[?&]expire=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /**
     * Content-Range "bytes 100-199/5000" → 5000; "bytes star/5000" → 5000; -1 when unknown.
     */
    fun totalFromContentRange(v: String?): Long = v?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1

    /**
     * What a resumed request did with the file: 206 continues it; 200 means the server ignored Range (start over);
     * 416 means we already have everything.
     */
    enum class Resume { APPEND, RESTART, COMPLETE, RETRY, FAIL }
    fun onStatus(code: Int): Resume = when (code) {
        206 -> Resume.APPEND
        200 -> Resume.RESTART
        416 -> Resume.COMPLETE
        403, 410, 408, 429 -> Resume.RETRY
        in 500..599 -> Resume.RETRY
        else -> Resume.FAIL
    }

    /** The next job to run: oldest queued whose backoff has passed (RUNNING ones from a killed process count as queued). */
    fun next(jobs: List<DlJob>, now: Long): DlJob? =
        jobs.filter { (it.status == DlStatus.QUEUED || it.status == DlStatus.RUNNING) && it.nextTryAt <= now }.minByOrNull { it.createdAt }

    /** Earliest time something is waiting for its backoff (0 = nothing waiting). */
    fun nextWake(jobs: List<DlJob>, now: Long): Long =
        jobs.filter { it.status == DlStatus.QUEUED && it.nextTryAt > now }.minOfOrNull { it.nextTryAt } ?: 0

    /** Bytes still needed on disk: the rest of every part, plus a full copy when parts are merged / copied out. */
    fun spaceNeeded(job: DlJob): Long {
        val left = job.parts.sumOf { (it.total - it.done).coerceAtLeast(0) }
        return left + job.total + SPACE_MARGIN
    }

    fun hasSpace(freeBytes: Long, job: DlJob): Boolean = freeBytes >= spaceNeeded(job)

    fun pause(job: DlJob): DlJob = if (job.active) job.copy(status = DlStatus.PAUSED, nextTryAt = 0) else job
    fun resume(job: DlJob): DlJob = if (job.status == DlStatus.PAUSED || job.status == DlStatus.FAILED) job.copy(status = DlStatus.QUEUED, attempts = 0, error = "", nextTryAt = 0) else job

    /** A safe file name for a title ("AC/DC: Back in Black" → "AC-DC- Back in Black"). */
    fun fileName(title: String, ext: String): String {
        val base = title.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "-").replace(Regex("\\s+"), " ").trim().trim('.').take(80).ifBlank { "Blaze" }
        return "$base.$ext"
    }

    // ---- JSON ----
    fun toJson(j: DlJob): JSONObject = JSONObject().put("id", j.id).put("vid", j.videoId).put("kind", j.kind).put("title", j.title)
        .put("ch", j.channel).put("dur", j.durationSec).put("thumb", j.thumb).put("q", j.quality).put("cid", j.choiceId).put("ext", j.ext)
        .put("src", j.source).put("lic", j.licence).put("ft", j.fileTitle).put("st", j.status.name).put("att", j.attempts).put("err", j.error).put("next", j.nextTryAt).put("at", j.createdAt).put("out", j.outUri).put("subs", j.subs)
        .put("parts", JSONArray().also { a -> j.parts.forEach { p -> a.put(JSONObject().put("key", p.key).put("url", p.url).put("total", p.total).put("done", p.done).put("c", p.container)) } })

    fun fromJson(o: JSONObject): DlJob {
        val pa = o.optJSONArray("parts") ?: JSONArray()
        val parts = (0 until pa.length()).map { i -> val p = pa.getJSONObject(i); DlPart(p.optString("key").ifEmpty { p.optInt("itag").toString() }, p.optString("url"), p.optLong("total"), p.optLong("done"), p.optString("c")) }
        return DlJob(o.optString("id"), o.optString("vid"), o.optString("kind"), o.optString("title"), o.optString("ch"), o.optInt("dur"),
            o.optString("thumb"), o.optString("q"), o.optString("cid"), o.optString("ext"), parts,
            runCatching { DlStatus.valueOf(o.optString("st")) }.getOrDefault(DlStatus.QUEUED), o.optInt("att"), o.optString("err"),
            o.optLong("next"), o.optLong("at"), o.optString("out"), o.optString("src", "youtube"), o.optString("lic"), o.optString("ft"), o.optString("subs"))
    }

    fun listToJson(jobs: List<DlJob>): String = JSONArray().also { a -> jobs.forEach { a.put(toJson(it)) } }.toString()
    fun listFromJson(s: String): List<DlJob> = runCatching { val a = JSONArray(s); (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }.getOrDefault(emptyList())
}
