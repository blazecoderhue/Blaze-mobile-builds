package com.blaze.assistant.life

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.blaze.assistant.MainActivity
import com.blaze.assistant.R
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Mind
import com.blaze.assistant.agent.MindLogs
import com.blaze.assistant.agent.MindReview
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.voice.WakeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File

/**
 * v0.33.0 Life Log 2, after Stop: transcribe the recorded session with Vosk (+ the speaker model for voices), split it
 * by voice ([Diarize]), let Blaze and Halo review it ([LifeReview]), save the memories and the labelled transcript,
 * post the write-up to the chat, then delete the audio (unless "Keep recordings"). Runs in the foreground (dataSync)
 * with progress; sessions left half-done (phone restarted) are picked up by [resumePending].
 */
class LifeLogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        private const val WORK = "lifelog_review"
        private const val CH = "blaze_life_review"
        private const val NOTE = 34
        const val PREF_KEEP = "life_logs_keep_audio"
        const val PREF_VOICES = "life_logs_voices"

        fun enqueue(ctx: Context, id: String) {
            val req = OneTimeWorkRequestBuilder<LifeLogWorker>().setInputData(workDataOf("id" to id)).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("$WORK:$id", ExistingWorkPolicy.KEEP, req)
        }

        /** App start: sessions that were recorded but never reviewed. */
        fun resumePending(ctx: Context) {
            LifeAudio.sessions(ctx).filter { LifeAudio.state(it) in setOf("queued", "transcribing", "reviewing") }.forEach { enqueue(ctx, it.name) }
        }

        var keepAudio: Boolean
            get() = try { Settings.bool(PREF_KEEP, false) } catch (_: Throwable) { false }
            set(v) = Settings.setBool(PREF_KEEP, v)
    }

    private val c = applicationContext

    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val d = LifeAudio.dir(c, id)
        if (!d.isDirectory) return Result.success()
        try { setForeground(info("Getting ready\u2026", 0)) } catch (_: Exception) { }
        Mind.init(c)
        return try {
            done(process(d))
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            LifeAudio.setState(d, "failed", JSONObject().put("error", e.message ?: "failed"))
            done("I couldn't finish reviewing that life log: ${e.message ?: "something went wrong"}. The recording is kept; say \u201creview my last life log\u201d to try again.")
            Result.success()
        }
    }

    private suspend fun process(d: File): String {
        val idx = LifeAudio.readIndex(d)
        val segs = LifeAudio.segments(idx)
        val started = idx.optLong("started")
        val brainstorm = idx.optBoolean("brainstorm")
        if (segs.isEmpty()) { finishAudio(d); return "The life log ended, but no one spoke loud enough to record. Nothing to review." }

        // 1. models (downloaded once; the speaker model is optional)
        LifeAudio.setState(d, "transcribing")
        if (!WakeModel.ready(c)) { progress("Getting the offline speech model once\u2026", 0); WakeModel.download(c).getOrThrow() }
        if (!SpkModel.ready(c)) { progress("Getting the voice model once\u2026", 0); SpkModel.download(c) }

        // 2. transcribe each segment, a voice vector per utterance
        val utts = ArrayList<Diarize.Utt>()
        val rooms = ArrayList<String>()
        withContext(Dispatchers.IO) {
            val model = Model(WakeModel.dir(c).absolutePath)
            val spk = if (SpkModel.ready(c)) try { SpeakerModel(SpkModel.dir(c).absolutePath) } catch (_: Throwable) { null } else null
            try {
                val total = segs.sumOf { it.bytes }.coerceAtLeast(1); var doneBytes = 0L
                for (s in segs) {
                    val rec = if (spk != null) Recognizer(model, LifeAudio.RATE.toFloat(), spk) else Recognizer(model, LifeAudio.RATE.toFloat())
                    rec.setWords(true)
                    try {
                        val pcm = LifeAudio.read(d, s.offset, s.bytes)
                        fun take(json: String) {
                            val o = try { JSONObject(json) } catch (_: Exception) { return }
                            val text = o.optString("text").trim(); if (text.isBlank()) return
                            val words = o.optJSONArray("result")
                            val st = words?.optJSONObject(0)?.optDouble("start") ?: 0.0
                            val en = words?.optJSONObject(words.length() - 1)?.optDouble("end") ?: st
                            val (vec, frames) = Diarize.voskVector(o)
                            utts += Diarize.Utt(s.startMs + (st * 1000).toLong(), s.startMs + (en * 1000).toLong(), text, vec, frames, s.rms)
                            rooms += LifeAudio.roomLabel(s.floor)
                        }
                        var i = 0
                        while (i < pcm.size) {
                            val n = minOf(4000, pcm.size - i)
                            if (rec.acceptWaveForm(pcm.copyOfRange(i, i + n), n)) take(rec.result)
                            i += n
                        }
                        take(rec.finalResult)
                    } finally { rec.close() }
                    doneBytes += s.bytes
                    progress("Transcribing ${doneBytes * 100 / total}%", (doneBytes * 100 / total).toInt())
                }
            } finally { try { spk?.close() } catch (_: Throwable) { }; try { model.close() } catch (_: Throwable) { } }
        }
        if (utts.isEmpty()) { finishAudio(d); return "The life log is over, but I couldn't make out any words in it. Nothing to review." }

        // 3. voices
        val groups = Diarize.cluster(utts)
        val cents = Diarize.centroids(utts, groups)
        val known = Diarize.knownFromJson(Settings.str(PREF_VOICES, "[]"))
        val knownNames = Diarize.matchKnown(cents, known).mapKeys { "S${it.key + 1}" }
        val lines = utts.indices.map { LifeReview.Line(utts[it].startMs, "S${groups[it] + 1}", utts[it].text, rooms[it]) }
        val voices = groups.toSet().size
        val talk = utts.indices.groupBy { groups[it] }.map { (g, ix) -> "S${g + 1}: ${ix.size} lines, loudness ${ix.map { utts[it].rms }.average().toInt()}" }
        val minutes = ((utts.last().endMs - utts.first().startMs) / 60_000).coerceAtLeast(1)
        val meta = "Recorded ${java.text.SimpleDateFormat("EEE d MMM yyyy, HH:mm", java.util.Locale.US).format(java.util.Date(started))} for about $minutes min" +
            (if (brainstorm) " (a brainstorm: the user thinking out loud)" else "") + ". Voices: ${talk.joinToString("; ")}." +
            (if (cents.isEmpty()) " (No voice model: voices could not be split; S1 is everyone.)" else "")

        // 4. the labelled transcript into the journal (searchable; the idle review learns from it too)
        val transcript = LifeReview.transcript(lines, knownNames)
        val conv = if (brainstorm) "brainstorm:$started" else "life_log:${LifeLogs.day(started)}"
        try { Mind.db()?.let { db -> transcript.chunked(3000).forEach { part -> MindLogs.append(db, conv, "heard", part, started / 1000, MindLogs.LIFE) } } } catch (_: Exception) { }

        // 5. Blaze drafts, Halo reviews (per ~18k-char part for long days)
        LifeAudio.setState(d, "reviewing")
        progress("Blaze and Halo are going over it\u2026", 0)
        val parts = splitLines(transcript, 18_000).take(6)
        val results = ArrayList<LifeReview.Result>()
        for ((i, part) in parts.withIndex()) {
            val m = meta + if (parts.size > 1) " Part ${i + 1} of ${parts.size}." else ""
            val draft = MindReview.cheap(LifeReview.blazeRequest(part, m))
            val b = LifeReview.parse(draft)
            val h = if (draft != null && b != null) LifeReview.parse(MindReview.cheap(LifeReview.haloRequest(part, m, draft.take(8000)))) else null
            (h ?: b)?.let { results += it }
            progress("Blaze and Halo are going over it (${i + 1}/${parts.size})", (i + 1) * 100 / parts.size)
        }
        if (results.isEmpty()) {
            LifeAudio.setState(d, "failed", JSONObject().put("error", "review"))
            return "Your life log is transcribed and saved ($minutes min, $voices ${if (voices == 1) "voice" else "voices"}), but Blaze and Halo couldn't review it right now. " +
                "Say \u201creview my last life log\u201d later."
        }
        val r = merge(results)

        // 6. memories, and the named voices for next time
        var saved = 0
        val tag = if (brainstorm) "brainstorm" else "life log ${LifeLogs.day(started)}"
        r.memories.forEach { mm ->
            val res = Mind.save(Mind.normKind(mm.kind), mm.subject.ifBlank { "Life log" }, "${mm.text} (heard in a $tag, inferred)").first
            if (res == "saved" || res == "updated") saved++
        }
        if (r.todos.isNotEmpty()) Mind.save("project", "Said I'd do (${LifeLogs.day(started)})", r.todos.take(6).joinToString("; ") + " (from a life log, inferred)")
        var kn = known
        r.speakers.forEach { (s, name) ->
            val g = s.removePrefix("S").toIntOrNull()?.minus(1) ?: return@forEach
            val v = cents[g] ?: return@forEach
            if (LifeReview.nameable(name)) kn = Diarize.learn(kn, name.trim(), v, utts.indices.count { groups[it] == g }.coerceAtMost(50))
        }
        if (kn !== known) Settings.setStr(PREF_VOICES, Diarize.knownToJson(kn.take(40)))
        finishAudio(d)
        return LifeReview.format(r, minutes, voices, saved)
    }

    private fun splitLines(t: String, max: Int): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder()
        t.lineSequence().forEach { l -> if (sb.length + l.length > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }; sb.append(l).append('\n') }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun merge(rs: List<LifeReview.Result>): LifeReview.Result = if (rs.size == 1) rs[0] else LifeReview.Result(
        rs.flatMap { it.scenes }, rs.fold(emptyMap()) { a, x -> a + x.speakers }, rs.flatMap { it.memories }.distinctBy { it.text.lowercase() }.take(30),
        rs.flatMap { it.todos }.distinct().take(10), rs.first().title, rs.mapNotNull { it.halo.ifBlank { null } }.lastOrNull().orEmpty())

    private fun finishAudio(d: File) { if (keepAudio) LifeAudio.setState(d, "done") else d.deleteRecursively() }

    private suspend fun done(text: String) = withContext(Dispatchers.Main) {
        Agent.note(text, asReply = true)
        if (!Agent.inForeground) try { com.blaze.assistant.notify.Notifier.reply(text.replace("**", "").take(400), false) } catch (_: Throwable) { }
    }

    private suspend fun progress(text: String, pct: Int) { try { setForeground(info(text, pct)) } catch (_: Exception) { } }

    private fun info(text: String, pct: Int): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26) c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Life log review", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(c, 35, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CH).setSmallIcon(R.drawable.ic_stat_blaze).setContentTitle("Reviewing your life log")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setProgress(100, pct, pct <= 0).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTE, n)
    }
}

/** Vosk's speaker (x-vector) model, ~13 MB, downloaded once into filesDir/spk-model. */
object SpkModel {
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
    fun dir(ctx: Context) = File(ctx.filesDir, "spk-model")
    fun ready(ctx: Context) = File(dir(ctx), "final.ext.raw").exists() || File(dir(ctx), "mfcc.conf").exists()

    suspend fun download(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val tmp = File(ctx.cacheDir, "spk.zip")
            val conn = java.net.URL(URL_ZIP).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 20_000; conn.readTimeout = 30_000
            conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            val target = dir(ctx); target.deleteRecursively(); target.mkdirs()
            java.util.zip.ZipInputStream(tmp.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val rel = e.name.substringAfter('/', ""); if (rel.isEmpty()) continue
                    val f = File(target, rel)
                    if (!f.canonicalPath.startsWith(target.canonicalPath)) continue
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                }
            }
            tmp.delete(); ready(ctx)
        } catch (_: Exception) { false }
    }
}
