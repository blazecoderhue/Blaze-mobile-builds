package com.blaze.assistant.voice

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.blaze.assistant.MainActivity
import com.blaze.assistant.R
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.assist.BlazeInteractionService
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Always-on listener for "Hey Blaze" / "Wake up Blaze". Runs fully offline with a
 * small grammar, so nothing you say leaves the phone until the wake phrase is heard.
 */
class WakeWordService : Service() {
    companion object {
        private const val CH = "blaze_wake"
        private const val ID = 3
        private const val ACT_PAUSE = "pause"
        private const val ACT_RESUME = "resume"
        @Volatile var running = false; private set

        private const val ACT_REFRESH = "refresh"
        /** Start, or (when already running) re-promote from the foreground so Android gives the mic back. */
        fun start(ctx: Context) {
            if (!Settings.wakeWord || !WakeModel.ready(ctx)) return
            if (com.blaze.assistant.life.LifeLogs.active) return   // life logs hold the mic (v0.19.0)
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, WakeWordService::class.java).setAction(if (running) ACT_REFRESH else null)) } catch (_: Exception) { }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WakeWordService::class.java)) }
        fun pause(ctx: Context) { if (running) send(ctx, ACT_PAUSE) }
        fun resume(ctx: Context) { if (running) send(ctx, ACT_RESUME) }
        private fun send(ctx: Context, a: String) =
            try { ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(a)) } catch (_: Exception) { null }
    }

    private var model: Model? = null
    @Volatile private var paused = false
    @Volatile private var listening = false
    private var thread: Thread? = null
    private var lastFire = 0L
    @Volatile private var pausedAt = 0L
    private val main by lazy { android.os.Handler(mainLooper) }

    // Power saving: sleep (mic fully released) while the screen is off or the phone is in a pocket.
    @Volatile private var screenOff = false
    @Volatile private var pocket = false
    private var charging = false
    private val sleeping get() = (screenOff && !Settings.bool("wake_screen_off", false) && !(charging && Settings.bool("wake_charging", true))) ||
        (pocket && Settings.bool("wake_pocket", true))
    private var sensors: android.hardware.SensorManager? = null
    private var proxListening = false
    private val pocketOn = Runnable { pocket = true; refreshSleep() }
    private val prox = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val near = e.values.isNotEmpty() && e.values[0] < minOf(e.sensor.maximumRange, 3f)
            main.removeCallbacks(pocketOn)
            if (near) main.postDelayed(pocketOn, 2500)   // covered for a few seconds = in a pocket / face down
            else if (pocket) { pocket = false; refreshSleep() }
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }
    private val screenRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOff = true; setProx(false) }
                Intent.ACTION_SCREEN_ON -> { screenOff = false; setProx(true) }
                Intent.ACTION_POWER_CONNECTED -> charging = true
                Intent.ACTION_POWER_DISCONNECTED -> charging = false
            }
            refreshSleep()
        }
    }

    private fun setProx(on: Boolean) {
        val sm = sensors ?: return
        val s = sm.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY) ?: return
        try {
            if (on && !proxListening && Settings.bool("wake_pocket", true)) { sm.registerListener(prox, s, android.hardware.SensorManager.SENSOR_DELAY_NORMAL); proxListening = true }
            if (!on && proxListening) { sm.unregisterListener(prox); proxListening = false; main.removeCallbacks(pocketOn); pocket = false }
        } catch (_: Exception) { }
    }

    private fun refreshSleep() {
        if (sleeping) stopListening() else if (!paused) main.postDelayed({ startListening() }, 300)
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH, "Wake word", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Blaze listens for \"Hey Blaze\""; setShowBadge(false)
                })
        }
        if (!goForeground()) { running = false; stopSelf(); return }
        Thread {
            try {
                model = Model(WakeModel.dir(this).absolutePath)
                startListening()
            } catch (_: Exception) { stopSelf() }
        }.start()
        main.postDelayed(watchdog, 15_000)
        try {
            sensors = getSystemService(android.hardware.SensorManager::class.java)
            val pm = getSystemService(android.os.PowerManager::class.java)
            screenOff = !(pm?.isInteractive ?: true)
            charging = (registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            registerReceiver(screenRx, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            })
            if (!screenOff) setProx(true)
        } catch (_: Exception) { }
    }

    // v0.45.13: the notification shows the listener's real state (WakeNote) and is re-posted when it changes.
    @Volatile private var micBlocked = false
    @Volatile private var shownState: WakeNote.State? = null

    private fun build(st: WakeNote.State): Notification {
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(WakeNote.title(st))
            .setContentText(WakeNote.text(st))
            .setStyle(NotificationCompat.BigTextStyle().bigText(WakeNote.text(st)))
            .setOngoing(true).setContentIntent(open).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN).build()
    }

    /** Re-posts the notification when the state changed (cheap no-op otherwise). */
    private fun note() {
        if (!running) return
        val st = WakeNote.of(listening, paused, sleeping, micBlocked)
        if (st == shownState) return
        shownState = st
        try { getSystemService(NotificationManager::class.java)?.notify(ID, build(st)) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.quiet("WakeNote", e) }
    }

    private fun goForeground(): Boolean {
        val st = WakeNote.of(listening, paused, sleeping, micBlocked)
        shownState = st
        val n: Notification = build(st)
        try {
            ServiceCompat.startForeground(this, ID, n,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (e: Exception) { return false }
        return true
    }

    /**
     * Self-heal: if a pause was never followed by a resume (the panel closed without listening,
     * a recogniser error, TTS cut off), start listening again on our own.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val idle = Voice.state.value == Voice.State.IDLE
            if (paused && idle && System.currentTimeMillis() - pausedAt > 12_000) { paused = false; startListening() }
            else if (!paused && !listening && !sleeping) startListening()
            main.postDelayed(this, 10_000)
        }
    }

    /**
     * Our own mic loop (instead of Vosk's SpeechService) so we can notice when Android hands us
     * silence: a microphone service started from the background gets an all-zero stream on
     * Android 11+. Then we ask the user to tap once to bring Blaze to the front.
     */
    @Synchronized private fun startListening() {
        val m = model ?: return
        if (paused || listening || sleeping) return
        listening = true
        thread = Thread {
            var rec: Recognizer? = null
            var ar: android.media.AudioRecord? = null
            try {
                // Decoy words soak up sounds that are close but not the wake phrase.
                rec = Recognizer(m, 16000f, GRAMMAR).also { it.setWords(true) }
                val min = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                ar = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
                if (ar.state != android.media.AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic busy")
                ar.startRecording()
                main.post { note() }
                val buf = ShortArray(1600)   // 100 ms
                var silentMs = 0L
                var warned = false
                // Sound gate: Vosk only runs while there's voice-level sound, so a quiet room costs
                // almost nothing. A short pre-roll keeps the start of "hey" when the gate opens.
                val gate = Settings.bool("wake_gate", true)
                val pre = ArrayDeque<ShortArray>()
                var floor = 200.0
                var openMs = 0
                while (listening && !paused && !sleeping) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) { Thread.sleep(50); continue }
                    var peak = 0
                    for (i in 0 until n) { val v = kotlin.math.abs(buf[i].toInt()); if (v > peak) peak = v }
                    if (peak == 0) { silentMs += n / 16 } else { silentMs = 0; if (warned) { warned = false; micBlocked = false; main.post { note() }; com.blaze.assistant.notify.Notifier.clearWakeBlocked() } }
                    if (silentMs > 6000 && !warned) { warned = true; micBlocked = true; main.post { note() }; com.blaze.assistant.notify.Notifier.wakeBlocked() }
                    if (gate) {
                        var sum = 0.0
                        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                        val rms = kotlin.math.sqrt(sum / n)
                        if (openMs <= 0) floor = floor * 0.95 + rms * 0.05   // track background noise only while closed
                        val loud = rms > maxOf(floor * 2.5, 350.0)
                        if (loud) {
                            if (openMs <= 0) { while (pre.isNotEmpty()) { val p = pre.removeFirst(); rec.acceptWaveForm(p, p.size) } }
                            openMs = 1500
                        } else if (openMs > 0) {
                            openMs -= n / 16
                            if (openMs <= 0) {   // sound ended: settle what was heard, then go idle
                                rec.acceptWaveForm(buf, n)
                                if (check(rec.finalResult)) break
                                rec.reset(); continue
                            }
                        }
                        if (openMs <= 0) { pre.addLast(buf.copyOf(n)); if (pre.size > 5) pre.removeFirst(); continue }
                    }
                    val done = rec.acceptWaveForm(buf, n)
                    // Only whole, finished phrases count; partial guesses fire on random speech.
                    if (done && check(rec.result)) { rec.reset(); break }
                }
            } catch (_: Exception) {
                Thread.sleep(1500)   // mic held by another app; the watchdog retries
            } finally {
                try { ar?.stop() } catch (_: Exception) { }
                ar?.release(); rec?.close()
                listening = false
                main.post { note() }
            }
        }.also { it.start() }
    }

    private val PHRASES = setOf("hey blaze", "okay blaze", "ok blaze", "wake up blaze")
    private val GRAMMAR = "[\"hey blaze\", \"okay blaze\", \"ok blaze\", \"wake up blaze\", " +
        "\"hey\", \"okay\", \"blaze\", \"play\", \"place\", \"plays\", \"base\", \"blade\", \"blessed\", \"lady\", \"baby\", \"maybe\", " +
        "\"hey babe\", \"hey they\", \"he played\", \"the\", \"a\", \"and\", \"is\", \"what\", \"yes\", \"no\", \"[unk]\"]"

    private fun stopListening() { listening = false; thread = null; main.post { note() } }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_PAUSE -> { paused = true; pausedAt = System.currentTimeMillis(); stopListening() }
            ACT_RESUME -> { paused = false; note(); main.postDelayed({ startListening() }, 350) }
            ACT_REFRESH -> { micBlocked = false; goForeground(); com.blaze.assistant.notify.Notifier.clearWakeBlocked()
                if (!paused) { stopListening(); main.postDelayed({ startListening() }, 300) } }
            else -> if (!paused) startListening()
        }
        return START_STICKY
    }

    /**
     * True when the wake phrase was heard (and Blaze was woken). The whole utterance must be the
     * phrase (not buried in other speech), and every word must be recognised confidently.
     */
    private fun check(hyp: String?): Boolean {
        val o = try { JSONObject(hyp ?: return false) } catch (_: Exception) { return false }
        val text = o.optString("text").trim()
        val heard = text in PHRASES
        if (!heard) return false
        val words = o.optJSONArray("result")
        val minConf = com.blaze.assistant.agent.Settings.str("wake_conf", "0.88").toDoubleOrNull() ?: 0.88
        if (words != null && words.length() > 0) {
            for (i in 0 until words.length()) if (words.getJSONObject(i).optDouble("conf", 0.0) < minConf) return false
            val dur = words.getJSONObject(words.length() - 1).optDouble("end") - words.getJSONObject(0).optDouble("start")
            if (dur < 0.35 || dur > 2.2) return false   // too clipped or too drawn-out to be "hey blaze"
        }
        if (!heard || System.currentTimeMillis() - lastFire < 3000) return false
        lastFire = System.currentTimeMillis()
        paused = true; pausedAt = System.currentTimeMillis(); listening = false
        // Give the mic a moment to be released before the full recogniser grabs it.
        main.postDelayed({ wake() }, 250)
        return true
    }

    private fun wake() {
        com.blaze.assistant.device.Sounds.wake()
        // Best path: as the default assistant, open Blaze's assistant session over any app.
        if (BlazeInteractionService.showFromWake()) return
        // Otherwise the overlay panel, if Blaze may draw over apps.
        if (com.blaze.assistant.ui.Overlay.canDrawOverApps(this)) {
            android.os.Handler(mainLooper).post { com.blaze.assistant.ui.Overlay.openPanel(this, listen = true) }; return
        }
        val i = com.blaze.assistant.IntentKey.sign(this, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_LISTEN, true))
        try { startActivity(i) } catch (_: Exception) { com.blaze.assistant.notify.Notifier.wakeHeard() }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(watchdog)
        stopListening()
        try { unregisterReceiver(screenRx) } catch (_: Exception) { }
        setProx(false)
        model?.close(); model = null
        super.onDestroy()
    }
}
