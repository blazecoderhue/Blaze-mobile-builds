package com.blaze.assistant.mediaapps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blaze.assistant.R
import com.blaze.assistant.mediaapps.store.Choice
import com.blaze.assistant.mediaapps.store.StoreDetail
import com.blaze.assistant.mediaapps.store.StoreError
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.StoreSources
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * v0.30.1: the Store's download queue. One at a time, in order, run by a WorkManager worker in the foreground (progress
 * notification), so it keeps going with Blaze closed and comes back after the app is killed or the phone restarts.
 * Each stream is fetched in 8 MB HTTP ranges into a .part file, so a dropped connection resumes where it stopped.
 * Expired or refused stream urls are fetched again from YouTube; network errors retry with backoff (2 s … 5 min, up to
 * 8 tries, reset whenever bytes arrive); no network = wait for it. Pause keeps the .part files; Cancel deletes them.
 */
object Downloads {
    private const val FILE = "mediaapps/downloads.json"
    private const val WORK = "blaze-media-downloads"
    const val CHANNEL = "blaze_media_dl"
    const val NOTE_ID = 4711

    private var app: Context? = null
    private val _jobs = MutableStateFlow<List<DlJob>>(emptyList())
    val jobs: StateFlow<List<DlJob>> = _jobs
    private val lock = Any()

    fun ensure(ctx: Context) {
        synchronized(lock) {
            if (app != null) return
            app = ctx.applicationContext
            val f = File(ctx.filesDir, FILE)
            _jobs.value = if (f.isFile) DlLogic.listFromJson(f.readText()) else emptyList()
        }
    }

    private fun save() {
        val c = app ?: return
        val f = File(c.filesDir, FILE); f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(DlLogic.listToJson(_jobs.value)); tmp.renameTo(f)
    }

    fun get(id: String): DlJob? = _jobs.value.firstOrNull { it.id == id }

    fun update(id: String, fn: (DlJob) -> DlJob): DlJob? = synchronized(lock) {
        var out: DlJob? = null
        _jobs.value = _jobs.value.map { if (it.id == id) fn(it).also { n -> out = n } else it }
        save(); out
    }

    /** Adds a download for [choice] of [d] (from [item]'s source) and starts the queue. Returns the job. */
    fun add(ctx: Context, item: StoreItem, d: StoreDetail, choice: Choice, kind: String): DlJob {
        ensure(ctx)
        val parts = choice.keys.mapNotNull { k -> d.formats.firstOrNull { it.key == k } }.map { DlPart(it.key, it.url, it.contentLength, 0, it.container) }
        val now = System.currentTimeMillis()
        val job = DlJob("dl" + now.toString(36) + (100..999).random(), d.id.ifBlank { item.id }, kind,
            choice.title.ifBlank { d.title.ifBlank { item.title } },
            d.author.ifBlank { item.author }, (if (choice.title.isNotBlank()) 0 else d.lengthSec).takeIf { it > 0 } ?: if (choice.title.isBlank()) item.durationSec else 0,
            item.thumb.ifBlank { d.thumb }, choice.label, choice.id, choice.ext, parts, createdAt = now,
            source = item.source, licence = d.licence.ifBlank { item.licence }, fileTitle = choice.title,
            // Wave A 25: the viewer's caption language (or a translation into it) plus English go with the file, for offline.
            subs = if (kind == "video") Subs.indexJson(Subs.forDownload(d.subtitles, SubsLoader.pref(ctx), java.util.Locale.getDefault().language)) else "")
        synchronized(lock) { _jobs.value = _jobs.value + job; save() }
        Taste.note(ctx, kind, job.title, job.channel, Taste.DOWNLOAD)
        kick(ctx)
        return job
    }

    fun pause(ctx: Context, id: String) { ensure(ctx); update(id) { DlLogic.pause(it) } }
    fun resume(ctx: Context, id: String) { ensure(ctx); update(id) { DlLogic.resume(it) }; kick(ctx) }
    fun cancel(ctx: Context, id: String) {
        ensure(ctx)
        update(id) { if (it.status == DlStatus.DONE) it else it.copy(status = DlStatus.CANCELED) }
        if (get(id)?.status == DlStatus.CANCELED) clean(ctx, id)
    }
    /** Takes a finished / failed / cancelled download off the list (the file itself stays in the library). */
    fun dismiss(ctx: Context, id: String) {
        ensure(ctx)
        val j = get(id) ?: return
        if (j.active) cancel(ctx, id)
        clean(ctx, id)
        synchronized(lock) { _jobs.value = _jobs.value.filter { it.id != id }; save() }
    }

    fun partFile(ctx: Context, id: String, i: Int) = File(MediaFiles.workDir(ctx), "$id.$i.part")
    fun clean(ctx: Context, id: String) { MediaFiles.workDir(ctx).listFiles()?.filter { it.name.startsWith("$id.") }?.forEach { it.delete() } }

    /** Starts (or queues after the running one) the download worker. */
    fun kick(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** App start: anything left queued / running (killed process, reboot) carries on. */
    fun resumePending(ctx: Context) {
        ensure(ctx)
        if (_jobs.value.any { it.active }) kick(ctx)
    }
}

class MediaDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val c = ctx.applicationContext
    private var lastNote = 0L

    private class Stop : Exception()
    private class Transient(msg: String) : IOException(msg)
    private class Fatal(msg: String) : Exception(msg)

    override suspend fun doWork(): Result {
        Downloads.ensure(c); MediaLib.ensure(c)
        try { setForeground(info("Getting ready…", 0, 0)) } catch (_: Exception) { }
        while (!isStopped) {
            val now = System.currentTimeMillis()
            val job = DlLogic.next(Downloads.jobs.value, now)
            if (job == null) {
                val wake = DlLogic.nextWake(Downloads.jobs.value, now)
                if (wake <= 0) break
                delay((wake - now).coerceIn(500, 60_000)); continue
            }
            val r = runJob(job)
            if (r != null) return r
        }
        return Result.success()
    }

    /** null = carry on with the queue; a Result = stop the worker (no network: WorkManager waits for it). */
    private suspend fun runJob(j0: DlJob): Result? {
        var job = Downloads.update(j0.id) { it.copy(status = DlStatus.RUNNING) } ?: return null
        try {
            if (!DlLogic.hasSpace(MediaFiles.freeBytes(c), job))
                throw Fatal("Not enough space: needs ${com.blaze.assistant.mediaapps.TimeText.size(DlLogic.spaceNeeded(job))} free")
            // v0.45.18: the plan's monthly limit. The server counts (atomic); the download starts only on its yes.
            if (!job.counted) when (val g = com.blaze.assistant.connect.Entitlements.consume(c, job.kind, job.videoId)) {
                com.blaze.assistant.connect.Entitlements.Gate.Ok -> job = Downloads.update(job.id) { it.copy(counted = true) } ?: throw Stop()
                com.blaze.assistant.connect.Entitlements.Gate.Skip -> {}
                is com.blaze.assistant.connect.Entitlements.Gate.Blocked -> throw Fatal(g.message)
                is com.blaze.assistant.connect.Entitlements.Gate.Offline -> throw Transient(g.why)
            }
            var refreshed = 0
            for (i in job.parts.indices) {
                while (true) {
                    job = Downloads.get(job.id) ?: throw Stop()
                    val p = job.parts[i]
                    if (p.total > 0 && p.done >= p.total) break
                    if (DlLogic.urlExpired(DlLogic.expiryOf(p.url), System.currentTimeMillis())) job = refreshUrls(job)
                    when (fetchChunk(job, i)) {
                        Chunk.MORE -> {}
                        Chunk.DONE -> break
                        Chunk.EXPIRED -> {
                            if (++refreshed > 3) throw Transient("The server refused the download")
                            job = refreshUrls(job)
                        }
                    }
                }
            }
            finish(job)
        } catch (_: Stop) {
            val j = Downloads.get(j0.id)
            if (j?.status == DlStatus.CANCELED) Downloads.clean(c, j0.id)
        } catch (e: Fatal) {
            Downloads.update(j0.id) { DlLogic.onError(it, e.message ?: "Failed", false, System.currentTimeMillis()) }
            done(j0.title, "Download failed: ${e.message}", false)
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            val transient = e is IOException || e is StoreError
            val j = Downloads.update(j0.id) { if (it.status != DlStatus.RUNNING) it else DlLogic.onError(it, msg, transient, System.currentTimeMillis()) }
            if (j?.status == DlStatus.FAILED) done(j.title, "Download failed: $msg", false)
            else if (transient && !online()) return Result.retry()
        }
        return null
    }

    private enum class Chunk { MORE, DONE, EXPIRED }

    private suspend fun fetchChunk(job: DlJob, i: Int): Chunk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fetchChunkIo(job, i) }

    private suspend fun fetchChunkIo(job: DlJob, i: Int): Chunk {
        val p = job.parts[i]
        val file = Downloads.partFile(c, job.id, i)
        var have = if (file.isFile) file.length() else 0L
        if (p.total in 1 until have) { file.delete(); have = 0 }
        val conn = URL(p.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", StoreSources.get(job.source)?.userAgent ?: "Blaze/1 (Android)")
            conn.setRequestProperty("Range", DlLogic.range(have, p.total))
            val code = conn.responseCode
            when (DlLogic.onStatus(code)) {
                DlLogic.Resume.COMPLETE -> { Downloads.update(job.id) { DlLogic.onProgress(it, i, have, have) }; return Chunk.DONE }
                DlLogic.Resume.RETRY -> if (code == 403 || code == 410) return Chunk.EXPIRED else throw Transient("Server busy ($code)")
                DlLogic.Resume.FAIL -> throw Fatal("The server answered $code")
                DlLogic.Resume.RESTART -> { have = 0 }
                DlLogic.Resume.APPEND -> {}
            }
            val total = DlLogic.totalFromContentRange(conn.getHeaderField("Content-Range")).takeIf { it > 0 }
                ?: if (code == 200) conn.contentLengthLong.takeIf { it > 0 } ?: p.total else p.total
            FileOutputStream(file, have > 0).use { out ->
                conn.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    var since = 0L
                    while (inp.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n); have += n; since += n
                        if (since >= 512 * 1024) {
                            since = 0
                            val now = Downloads.update(job.id) { DlLogic.onProgress(it, i, have, total) }
                            if (now == null || now.status != DlStatus.RUNNING || isStopped) { out.flush(); throw Stop() }
                            note(now)
                        }
                    }
                }
            }
            val j = Downloads.update(job.id) { DlLogic.onProgress(it, i, have, total) } ?: throw Stop()
            note(j)
            if (j.status != DlStatus.RUNNING) throw Stop()
            return if (total > 0 && have >= total) Chunk.DONE else if (total <= 0 && code == 200) { Downloads.update(job.id) { DlLogic.onProgress(it, i, have, have) }; Chunk.DONE } else Chunk.MORE
        } finally { conn.disconnect() }
    }

    /** Stream urls expire (YouTube: ~6 h) or get refused: ask the source again and swap in the same streams' new urls. */
    private suspend fun refreshUrls(job: DlJob): DlJob {
        val src = StoreSources.get(job.source) ?: throw Fatal("This store isn't available in this build")
        val s = src.refresh(job.videoId, job.kind) ?: throw Fatal("The server refused the download")
        return Downloads.update(job.id) { j ->
            j.copy(parts = j.parts.map { p ->
                val f = s.formats.firstOrNull { it.key == p.key } ?: throw Fatal("The source no longer offers this quality")
                p.copy(url = f.url, total = if (p.total > 0) p.total else f.contentLength)
            })
        } ?: throw Stop()
    }

    private suspend fun finish(job: DlJob) {
        note(job, "Saving…")
        val work = MediaFiles.workDir(c)
        val parts = job.parts.indices.map { Downloads.partFile(c, job.id, it) }
        val out = File(work, job.id + ".out." + job.ext)
        var ext = job.ext
        val src: File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try {
            when {
                parts.size == 2 -> { Mux.merge(parts[0], parts[1], out, job.ext); out }
                // DASH audio (YouTube's .m4a / Opus-in-WebM) is remuxed into a plain file; mp3 / ogg / flac are kept as they are.
                job.kind == "audio" && job.parts[0].container in setOf("mp4", "webm") -> { Mux.remux(parts[0], out, job.ext); out }
                else -> parts[0]
            }
        } catch (e: Exception) {
            if (parts.size == 2) throw Fatal("Couldn't merge the video and audio (${e.message})")
            // Couldn't remux (old Android for Ogg): keep the stream as YouTube sent it.
            ext = job.parts[0].container.ifBlank { job.ext }.let { if (it == "mp4") "m4a" else it }
            parts[0]
        } }
        val name = DlLogic.fileName(job.title, ext)
        val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MediaFiles.publish(c, src, job.kind, name, job.title, job.channel, job.durationSec * 1000L) }
        val art = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveArt(job) }
        MediaLib.addDownload(Track(uri.toString(), uri.toString(), job.title, job.channel, "", job.durationSec * 1000L, art, "download",
            System.currentTimeMillis(), job.videoId, job.kind, job.licence, job.source))
        // Wave A 25: captions kept with the download so they work offline (best effort; never fails the download).
        if (job.subs.isNotBlank()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            note(job, "Saving subtitles\u2026")
            Subs.indexParse(job.subs).forEach { t ->
                runCatching { val cues = SubsLoader.fetchYouTube(t); if (cues.isNotEmpty()) SubsLoader.save(c, uri.toString(), t, cues) }
            }
        }
        Downloads.update(job.id) { it.copy(status = DlStatus.DONE, outUri = uri.toString(), error = "") }
        Downloads.clean(c, job.id)
        done(job.title, if (job.kind == "video") "Saved to Videos" else "Saved to Music", true)
    }

    private fun saveArt(job: DlJob): String = try {
        val f = File(MediaFiles.artDir(c), (job.source + "_" + job.videoId).replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jpg")
        if (!f.isFile && job.thumb.startsWith("http")) URL(job.thumb.replace("/mqdefault.jpg", "/hqdefault.jpg")).openStream().use { inp -> f.outputStream().use { inp.copyTo(it) } }
        if (f.isFile) f.path else job.thumb
    } catch (_: Exception) { job.thumb }

    private fun online(): Boolean = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        cm.activeNetwork != null
    } catch (_: Exception) { true }

    // ---- notifications ----
    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(Downloads.CHANNEL, "Music & video downloads", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openIntent(kind: String): PendingIntent {
        val i = Intent(c, if (kind == "video") VideoActivity::class.java else MusicActivity::class.java)
            .putExtra(MusicActivity.EXTRA_TAB, "store").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(c, kind.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun info(text: String, max: Int, now: Int, title: String = "Downloading", kind: String = "audio"): ForegroundInfo {
        channel()
        val n = NotificationCompat.Builder(c, Downloads.CHANNEL).setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(max, now, max == 0).setContentIntent(openIntent(kind)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(Downloads.NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(Downloads.NOTE_ID, n)
    }

    private suspend fun note(j: DlJob, line: String = "") {
        val now = System.currentTimeMillis()
        if (line.isEmpty() && now - lastNote < 1000) return
        lastNote = now
        val left = Downloads.jobs.value.count { it.active } - 1
        val text = line.ifEmpty {
            "${(j.progress * 100).toInt()}% · ${TimeText.size(j.done)} of ${TimeText.size(j.total)}" + if (left > 0) " · $left more queued" else ""
        }
        try { setForeground(info(text, 100, (j.progress * 100).toInt(), j.title, j.kind)) } catch (_: Exception) { }
    }

    private fun done(title: String, text: String, ok: Boolean) {
        try {
            channel()
            val n = NotificationCompat.Builder(c, Downloads.CHANNEL).setSmallIcon(R.drawable.ic_stat_blaze)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).build()
            c.getSystemService(NotificationManager::class.java).notify((title.hashCode() and 0xffff) + if (ok) 0 else 1, n)
        } catch (_: Exception) { }
    }
}
