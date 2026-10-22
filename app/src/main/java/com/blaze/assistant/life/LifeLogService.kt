package com.blaze.assistant.life

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.blaze.assistant.MainActivity
import com.blaze.assistant.R
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Mind
import com.blaze.assistant.agent.MindLogs
import com.blaze.assistant.voice.Voice
import com.blaze.assistant.voice.WakeModel
import com.blaze.assistant.voice.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * The life-log recorder (v0.19.0). A microphone foreground service with a persistent notification (Pause / Stop),
 * so Android's mic indicator is on whenever it listens; it never records silently and keeps no audio.
 *
 * Engine: Vosk with the small English model in filesDir/wake-model (shared with the wake word, downloaded once with
 * progress the first time, never re-downloaded on updates). A sound gate runs the recogniser only while there is
 * voice-level sound, so a quiet room costs almost no CPU. Without the model, Android's SpeechRecognizer runs in a
 * restart loop until the download finishes.
 *
 * Auto-pause (mic released): phone calls, another app recording, Blaze's own voice listening, battery below
 * [LifeLogs.LOW_BATTERY]% unless charging. Resumes on its own when the reason clears; a pause from the user waits
 * for Resume.
 */
class LifeLogService : Service() {
    companion object {
        const val ACT_START = "start"
        const val ACT_PAUSE = "pause"
        const val ACT_RESUME = "resume"
        const val ACT_STOP = "stop"
        const val EXTRA_BRAINSTORM = "brainstorm"
        private const val CH = "blaze_life"
        private const val ID = 31
        private const val RATE = 16000
        private const val USER = "you paused it"
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chunker = LogChunker()
    private var brainstorm = false
    private var startedAt = 0L
    private var conv = ""
    @Volatile private var pausedFor = ""          // "" = listening
    @Volatile private var running = false
    @Volatile private var model: Model? = null
    private var thread: Thread? = null
    @Volatile private var engineOn = false
    @Volatile private var gen = 0                 // a Vosk thread runs only while its generation is current
    private var fallback: SpeechRecognizer? = null
    private var fallbackErrors = 0
    private var lastTick = 0L
    private var wakeWasOn = false
    private var recCallback: AudioManager.AudioRecordingCallback? = null
    @Volatile private var othersRecording = false
    private var lastBatteryCheck = 0L
    private var lowBattery = false
    // v0.33.0 Life Log 2: record the voiced stretches, transcribe and review after Stop.
    private var recordMode = false
    @Volatile private var writer: LifeAudio.Writer? = null
    private var sessionId = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Life logs", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown the whole time life logs are on"; setShowBadge(false) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_PAUSE -> { if (running) setPause(USER) }
            ACT_RESUME -> { if (running && pausedFor.isNotBlank()) { pausedFor = ""; LifeLogs.update { it.copy(paused = "") }; evaluate(force = true) } }
            ACT_STOP -> { finish(); return START_NOT_STICKY }
            else -> if (!running) begin(intent?.getBooleanExtra(EXTRA_BRAINSTORM, false) ?: false)
        }
        if (!running) { stopSelf(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }

    private fun begin(bs: Boolean) {
        brainstorm = bs
        startedAt = System.currentTimeMillis()
        conv = if (bs) "brainstorm:$startedAt" else "life_log:${LifeLogs.day(startedAt)}"
        recordMode = LifeLogs.recordMode
        if (recordMode) {
            sessionId = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(startedAt))
            writer = try { LifeAudio.Writer(LifeAudio.dir(this, sessionId), startedAt, bs) } catch (_: Exception) { null }
            if (writer == null) recordMode = false
        }
        if (!goForeground()) { stopSelf(); return }
        running = true
        LifeLogs.set(LifeLogs.State(on = true, brainstorm = bs, since = startedAt, session = conv))
        // One mic at a time on a low-RAM phone: the wake word sleeps while logs run ("stop logs" works by voice instead).
        wakeWasOn = WakeWordService.running
        if (wakeWasOn) WakeWordService.stop(this)
        Mind.init(applicationContext)
        watchRecordings()
        lastTick = System.currentTimeMillis()
        main.post(monitor)
        prepareEngine()
    }

    // ---------------- engine ----------------

    private fun prepareEngine() {
        if (recordMode) {
            // Recording needs no model now; fetch them in the background so the review can start straight after Stop.
            LifeLogs.update { it.copy(engine = "recording (transcribed after you stop)") }
            if (!WakeModel.ready(this)) scope.launch { WakeModel.download(applicationContext) }
            if (!SpkModel.ready(this)) scope.launch { SpkModel.download(applicationContext) }
            main.post { evaluate(force = true) }
            return
        }
        if (WakeModel.ready(this)) { loadVosk(); return }
        // First use: download the model once (progress in the notification and Settings) and use Android's recogniser meanwhile.
        LifeLogs.update { it.copy(engine = "Android speech recogniser", downloading = 0) }
        scope.launch {
            val prog = launch { WakeModel.progress.collect { p -> LifeLogs.update { it.copy(downloading = p) }; main.post { refreshNotification() } } }
            val ok = WakeModel.download(applicationContext).isSuccess
            prog.cancel()
            LifeLogs.update { it.copy(downloading = -1) }
            if (ok && running) main.post { stopFallback(); loadVosk() }
        }
        main.post { evaluate(force = true) }
    }

    private fun loadVosk() {
        scope.launch {
            val m = try { Model(WakeModel.dir(this@LifeLogService).absolutePath) } catch (_: Throwable) { null }
            main.post {
                if (!running) { try { m?.close() } catch (_: Throwable) { }; return@post }
                model = m
                LifeLogs.update { it.copy(engine = if (m != null) "offline Vosk model" else "Android speech recogniser") }
                evaluate(force = true)
            }
        }
    }

    private val listening get() = pausedFor.isBlank()

    /** Starts or stops the mic to match the pause state. */
    private fun evaluate(force: Boolean = false) {
        if (!running) return
        if (listening && !engineOn) startEngine()
        else if (!listening && engineOn) stopEngine()
        if (force) refreshNotification()
    }

    private fun startEngine() {
        val m = model
        engineOn = true
        if (recordMode) { startRecorder(); return }
        if (m != null) startVosk(m) else startFallback()
    }

    private fun stopEngine() {
        engineOn = false
        gen++
        thread?.interrupt(); thread = null
        stopFallback()
        flush()
    }

    private fun startVosk(m: Model) {
        val my = ++gen
        thread = Thread {
            var rec: Recognizer? = null
            var ar: android.media.AudioRecord? = null
            try {
                rec = Recognizer(m, RATE.toFloat())
                val min = android.media.AudioRecord.getMinBufferSize(RATE, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                ar = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
                if (ar.state != android.media.AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic busy")
                ar.startRecording()
                val buf = ShortArray(1600)            // 100 ms
                val pre = ArrayDeque<ShortArray>()    // 300 ms pre-roll so the first word isn't cut
                var floor = 250.0
                var openMs = 0                        // hangover left while the gate is open
                var uttStart = 0L
                var fed = false
                while (my == gen && engineOn && running && !Thread.currentThread().isInterrupted) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) { Thread.sleep(50); continue }
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                    val rms = kotlin.math.sqrt(sum / n)
                    val loud = rms > floor * 2.5 && rms > 350
                    if (!loud && openMs <= 0) floor = floor * 0.97 + rms * 0.03   // track the room's noise floor
                    if (loud) { if (openMs <= 0) { uttStart = System.currentTimeMillis() - pre.size * 100L } ; openMs = 1500 }
                    if (openMs > 0) {
                        while (pre.isNotEmpty()) { val p = pre.removeFirst(); rec.acceptWaveForm(p, p.size) }
                        fed = true
                        if (rec.acceptWaveForm(buf, n)) { text(rec.result)?.let { heard(uttStart, System.currentTimeMillis(), it) }; uttStart = System.currentTimeMillis() }
                        openMs -= n / 16
                        if (openMs <= 0 && fed) { text(rec.finalResult)?.let { heard(uttStart, System.currentTimeMillis(), it) }; fed = false }
                    } else {
                        pre.addLast(buf.copyOf(n)); while (pre.size > 3) pre.removeFirst()
                    }
                }
                if (fed) text(rec.finalResult)?.let { heard(uttStart, System.currentTimeMillis(), it) }
            } catch (_: InterruptedException) {
            } catch (_: Throwable) {
                // Mic taken or model failed: fall back to Android's recogniser.
                main.post { if (my == gen && engineOn && running) { model = null; LifeLogs.update { it.copy(engine = "Android speech recogniser") }; startFallback() } }
            } finally {
                try { ar?.stop() } catch (_: Throwable) { }
                try { ar?.release() } catch (_: Throwable) { }
                try { rec?.close() } catch (_: Throwable) { }
            }
        }.apply { priority = Thread.NORM_PRIORITY - 1; start() }
    }

    /** v0.33.0: the record-mode mic loop: the same sound gate, but voiced audio goes to the session file. */
    private fun startRecorder() {
        val my = ++gen
        val w = writer ?: return
        thread = Thread {
            var ar: android.media.AudioRecord? = null
            try {
                val min = android.media.AudioRecord.getMinBufferSize(RATE, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                ar = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
                if (ar.state != android.media.AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic busy")
                ar.startRecording()
                val buf = ShortArray(1600)
                val pre = ArrayDeque<ShortArray>()
                var floor = 250.0
                var openMs = 0
                var since = 0L
                while (my == gen && engineOn && running && !Thread.currentThread().isInterrupted) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) { Thread.sleep(50); continue }
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                    val rms = kotlin.math.sqrt(sum / n)
                    val loud = rms > floor * 2.5 && rms > 350
                    if (!loud && openMs <= 0) floor = floor * 0.97 + rms * 0.03
                    if (loud) {
                        if (openMs <= 0) { w.open(System.currentTimeMillis() - pre.size * 100L, floor.toInt()); while (pre.isNotEmpty()) { val p = pre.removeFirst(); w.write(p, p.size) } }
                        openMs = 2000
                    }
                    if (openMs > 0) {
                        w.write(buf, n)
                        openMs -= n / 16
                        if (openMs <= 0) w.close()
                        if (System.currentTimeMillis() - since > 1500) { since = System.currentTimeMillis(); main.post { LifeLogs.update { it.copy(last = "recording\u2026") } } }
                    } else { pre.addLast(buf.copyOf(n)); while (pre.size > 3) pre.removeFirst() }
                }
                w.close()
            } catch (_: InterruptedException) { w.close()
            } catch (_: Throwable) {
                main.post { if (my == gen && engineOn && running) setPause("the mic is busy") }
            } finally {
                try { ar?.stop() } catch (_: Throwable) { }
                try { ar?.release() } catch (_: Throwable) { }
            }
        }.apply { priority = Thread.NORM_PRIORITY - 1; start() }
    }

    private fun text(json: String?): String? = try { JSONObject(json ?: "{}").optString("text").trim().ifBlank { null } } catch (_: Exception) { null }

    // Android's recogniser, restarted after every result (no model yet, or the mic failed for Vosk).
    private fun startFallback() {
        if (fallback != null || !engineOn) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { setPause("no speech recogniser on this phone; download the offline model in Settings \u203a Life logs"); return }
        val r = try { SpeechRecognizer.createSpeechRecognizer(this) } catch (_: Throwable) { null } ?: return
        fallback = r
        var start = System.currentTimeMillis()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { start = System.currentTimeMillis() }
            override fun onBeginningOfSpeech() { start = System.currentTimeMillis() }
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onResults(b: Bundle?) {
                fallbackErrors = 0
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard(start, System.currentTimeMillis(), it) }
                again(250)
            }
            override fun onError(code: Int) {
                when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> again(300)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> setPause("Blaze needs the microphone permission")
                    else -> { fallbackErrors++
                        if (fallbackErrors >= 12) setPause("the speech recogniser keeps failing; download the offline model in Settings \u203a Life logs")
                        else again(minOf(30_000L, 1000L shl minOf(fallbackErrors, 5))) }
                }
            }
        })
        listenOnce()
    }

    private fun listenOnce() {
        val r = fallback ?: return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2500L)
        try { r.startListening(i) } catch (_: Throwable) { again(2000) }
    }

    private fun again(ms: Long) { main.postDelayed({ if (engineOn && running && fallback != null) { try { fallback?.cancel() } catch (_: Throwable) { }; listenOnce() } }, ms) }

    private fun stopFallback() {
        val r = fallback ?: return
        fallback = null
        try { r.cancel(); r.destroy() } catch (_: Throwable) { }
    }

    // ---------------- text ----------------

    /** One recognised utterance (any thread). */
    private fun heard(start: Long, end: Long, raw: String) {
        main.post {
            if (!running) return@post
            when (LogChunker.command(raw)) {
                "stop" -> { finish(); return@post }
                "pause" -> { setPause(USER); return@post }
            }
            LifeLogs.update { it.copy(last = raw.take(120)) }
            chunker.add(LogChunker.Utterance(start, end, raw)).forEach { save(it) }
            refreshNotification()
        }
    }

    private fun save(c: LogChunker.Chunk) {
        val entry = LogChunker.entry(c, brainstorm)
        val cv = conv
        scope.launch { try { Mind.db()?.let { MindLogs.append(it, cv, "heard", entry, c.start / 1000, MindLogs.LIFE) } } catch (_: Exception) { } }
    }

    private fun flush() { chunker.flush()?.let { save(it) } }

    // ---------------- auto-pause ----------------

    private fun setPause(reason: String) {
        if (pausedFor == reason) return
        if (pausedFor == USER && reason != USER) return    // the user's pause wins until they resume
        pausedFor = reason
        LifeLogs.update { it.copy(paused = reason) }
        evaluate(force = true)
    }

    private fun autoReason(): String {
        val am = getSystemService(AudioManager::class.java)
        if (am != null && (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE)) return "phone call"
        if (Voice.state.value != Voice.State.IDLE) return "Blaze is listening"
        if (othersRecording && model != null) return "another app is using the mic"
        val now = System.currentTimeMillis()
        if (now - lastBatteryCheck > 60_000) {
            lastBatteryCheck = now
            val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0 && s > 0) l * 100 / s else 100 } ?: 100
            val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            lowBattery = level < LifeLogs.LOW_BATTERY && !plugged
        }
        if (lowBattery) return "battery below ${LifeLogs.LOW_BATTERY}%"
        return ""
    }

    private fun watchRecordings() {
        if (Build.VERSION.SDK_INT < 29) return
        val am = getSystemService(AudioManager::class.java) ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                // Our own capture is silenced when another app takes the mic (a voice note, a call app, the camera).
                othersRecording = configs.any { it.isClientSilenced } || (!engineOn && configs.isNotEmpty())
            }
        }
        recCallback = cb
        try { am.registerAudioRecordingCallback(cb, main) } catch (_: Throwable) { }
    }

    /** Every 3 s: auto-pause reasons, silence-closed chunks, time logged. Cheap (no audio work). */
    private val monitor = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (listening) LifeLogs.addMs(now - lastTick)
            lastTick = now
            val r = autoReason()
            if (pausedFor != USER) {
                if (r.isNotBlank()) setPause(r)
                else if (pausedFor.isNotBlank()) { pausedFor = ""; LifeLogs.update { it.copy(paused = "") }; evaluate(force = true) }
            }
            chunker.tick(now)?.let { save(it) }
            main.postDelayed(this, 3_000)
        }
    }

    // ---------------- notification ----------------

    private fun pending(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, LifeLogService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun build(): Notification {
        val s = LifeLogs.state.value
        val open = PendingIntent.getActivity(this, 31, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val what = if (brainstorm) "Brainstorm log" else "Life logs"
        val title = if (pausedFor.isBlank()) (if (recordMode) "$what on: recording (transcribed after you stop)" else "$what on: transcribing what the mic hears") else "$what paused: $pausedFor"
        val text = when {
            s.downloading in 0..99 -> "Getting the offline model once (${s.downloading}%)"
            pausedFor.isNotBlank() -> if (recordMode) "The mic is off." else "The mic is off. Text only, no audio is kept."
            recordMode -> "Only voiced moments are kept. Tap Stop and Blaze & Halo review it."
            s.last.isNotBlank() -> "\u201c${s.last}\u201d"
            else -> "Text only, no audio. Say \u201cstop logs\u201d or tap Stop."
        }
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (s.downloading in 0..99) setProgress(100, s.downloading, false) }
            .addAction(0, if (pausedFor == USER) "Resume" else "Pause", pending(if (pausedFor == USER) ACT_RESUME else ACT_PAUSE, 32))
            .addAction(0, "Stop", pending(ACT_STOP, 33))
            .build()
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(this, ID, build(), if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        true
    } catch (_: Exception) { false }

    private var lastNotify = 0L
    private fun refreshNotification() {
        if (!running) return
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1500) return
        lastNotify = now
        try { getSystemService(NotificationManager::class.java).notify(ID, build()) } catch (_: Exception) { }
    }

    // ---------------- stop ----------------

    private fun finish() {
        if (!running) { stopSelf(); return }
        running = false
        engineOn = false
        gen++
        thread?.interrupt(); thread = null
        stopFallback()
        flush()
        main.removeCallbacks(monitor)
        LifeLogs.addMs(System.currentTimeMillis() - lastTick)
        if (Build.VERSION.SDK_INT >= 29) recCallback?.let { try { getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(it) } catch (_: Throwable) { } }
        val bs = brainstorm; val c = conv; val at = startedAt
        LifeLogs.set(LifeLogs.State())
        if (recordMode) {
            // Life Log 2: close the file once the mic thread has let go, then transcribe + review in the background.
            val w = writer; writer = null; val id = sessionId; val app = applicationContext
            main.postDelayed({
                val n = try { w?.finish() ?: 0 } catch (_: Exception) { 0 }
                val d = LifeAudio.dir(app, id)
                if (n > 0) { LifeAudio.setState(d, "queued"); LifeLogWorker.enqueue(app, id)
                    Agent.note("Stopped. I'm transcribing the recording now, then Blaze and Halo will go over it and save what matters.", asReply = true) }
                else { d.deleteRecursively(); Agent.note("Stopped. Nobody spoke loud enough to record, so there's nothing to review.", asReply = true) }
            }, 400)
        }
        // Let the last chunk land before the summary reads the session.
        else if (bs) main.postDelayed({ LifeLogs.summarize(c, at) }, 600)
        if (wakeWasOn) main.postDelayed({ WakeWordService.start(applicationContext) }, 800)
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Throwable) { }
        stopSelf()
    }

    override fun onDestroy() {
        if (running) finish()
        val m = model; model = null
        // The Vosk thread may still be closing its recogniser; free the model after it.
        Thread { try { Thread.sleep(500); m?.close() } catch (_: Throwable) { } }.start()
        scope.cancel()
        super.onDestroy()
    }
}
