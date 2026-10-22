package com.blaze.assistant.phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.blaze.assistant.R
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.voice.WakeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * v0.33.0 Call Copilot (opt-in, pref call_copilot): when a call starts, live captions run on the phone (offline Vosk) in
 * a notification; "Halo, help" asks Halo for a suggested answer (and a translation if the other side switched language)
 * from what was said so far; after you hang up, Blaze writes a summary with the to-dos and offers reminders. Android
 * gives apps the microphone during a call, so on speaker both sides are heard; on the earpiece mostly your side.
 * Nothing is recorded as audio; only the caption text, and it's dropped after the summary unless you keep it.
 * Pure prompt / caption helpers in [Pure] (CallCopilotTest).
 */
object CallCopilot {
    const val PREF = "call_copilot"
    var on: Boolean
        get() = try { Settings.bool(PREF, false) } catch (_: Throwable) { false }
        set(v) = Settings.setBool(PREF, v)

    object Pure {
        fun tail(lines: List<String>, chars: Int = 220): String {
            val sb = StringBuilder()
            for (l in lines.asReversed()) { if (sb.length + l.length + 3 > chars) break; sb.insert(0, if (sb.isEmpty()) l else "$l \u00b7 ") }
            return sb.toString().ifBlank { "Listening\u2026" }
        }
        fun suggestPrompt(lines: List<String>): String = "@halo Call Copilot: I'm on a phone call right now. Live captions so far (both voices mixed, may have errors):\n" +
            lines.takeLast(40).joinToString("\n") + "\n\nIn at most 2 short lines: what should I say next? If the caller switched to Bemba, Nyanja or another language, add a one-line translation of their last words."
        fun summaryPrompt(lines: List<String>, mins: Long): String = "Call Copilot: summarise the phone call that just ended ($mins min). Captions (both voices mixed, may have errors):\n" +
            lines.takeLast(400).joinToString("\n") + "\n\nReply with: one-line gist, decisions, to-dos with who/when, and any dates or amounts. Offer to set reminders or calendar events for the to-dos (ask first)."
        fun worth(lines: List<String>) = lines.sumOf { it.split(' ').size } >= 12
    }

    const val TOOL = "call_copilot"
    fun tool(): org.json.JSONObject = org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject().put("name", TOOL)
        .put("description", "Call Copilot: live captions during phone calls, Halo's suggested answers, and a summary with to-dos after hanging up. enable / disable / status.")
        .put("parameters", org.json.JSONObject().put("type", "object").put("properties", org.json.JSONObject()
            .put("action", org.json.JSONObject().put("type", "string").put("enum", org.json.JSONArray(listOf("enable", "disable", "status"))))).put("required", org.json.JSONArray(listOf("action")))))
    fun run(ctx: Context, a: org.json.JSONObject): String = when (a.optString("action")) {
        "enable" -> { on = true; init(ctx)
            if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED || ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                "Call Copilot is on, but it needs the Phone and Microphone permissions (Settings › Apps › Blaze › Permissions)."
            else "Call Copilot is on: captions start with your next call (best on speaker), and I'll summarise it when you hang up." }
        "disable" -> { on = false; "Call Copilot is off." }
        else -> if (on) "Call Copilot is on." else "Call Copilot is off."
    }

    private var started = false
    private var tm: TelephonyManager? = null

    fun init(ctx: Context) {
        if (started || !on) return
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        started = true
        val app = ctx.applicationContext
        tm = app.getSystemService(TelephonyManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                tm?.registerTelephonyCallback(app.mainExecutor, object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onState(app, state) })
            } else @Suppress("DEPRECATION") tm?.listen(object : PhoneStateListener() {
                @Deprecated("old api") override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(app, state) }, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (_: Throwable) { started = false }
    }

    private fun onState(ctx: Context, state: Int) {
        if (!on) return
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> start(ctx)
            TelephonyManager.CALL_STATE_IDLE -> if (CallCopilotService.live) ctx.startService(Intent(ctx, CallCopilotService::class.java).setAction(CallCopilotService.STOP))
        }
    }

    fun start(ctx: Context) {
        if (CallCopilotService.live) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, CallCopilotService::class.java)) }
        catch (_: Throwable) {
            // Android may refuse a mic service started from the background: offer a one-tap start instead.
            try { val nm = ctx.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CallCopilotService.CH, "Call Copilot", NotificationManager.IMPORTANCE_DEFAULT))
                nm.notify(CallCopilotService.NID + 1, NotificationCompat.Builder(ctx, CallCopilotService.CH).setSmallIcon(R.drawable.ic_stat_blaze)
                    .setContentTitle("Call Copilot").setContentText("Tap for live captions and a summary of this call").setAutoCancel(true)
                    .setContentIntent(PendingIntent.getForegroundService(ctx, 51, Intent(ctx, CallCopilotService::class.java), PendingIntent.FLAG_IMMUTABLE)).build()) } catch (_: Throwable) { }
        }
    }
}

class CallCopilotService : Service() {
    companion object {
        const val CH = "blaze_callcopilot"; const val NID = 4600; const val STOP = "stop"; const val HELP = "help"
        @Volatile var live = false
    }
    private val lines = ArrayList<String>()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    private var startedAt = 0L
    private var hint = ""
    @Volatile private var partial = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { finish(); return START_NOT_STICKY }
            HELP -> { help(); return START_NOT_STICKY }
        }
        if (live) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Call Copilot", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NID, note())
        } catch (_: Throwable) { stopSelf(); return START_NOT_STICKY }
        live = true; running = true; startedAt = System.currentTimeMillis()
        if (!WakeModel.ready(this)) { hint = "Downloading the offline speech model; captions start when it's ready."; refresh(); scope.launch { if (WakeModel.download(applicationContext).isSuccess) listen() else { hint = "Couldn't get the speech model."; main.post { refresh() } } } }
        else scope.launch { listen() }
        return START_NOT_STICKY
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, CallCopilotService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun note() = NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat_blaze).setOngoing(true).setOnlyAlertOnce(true)
        .setContentTitle("Call Copilot · live captions").setContentText(hint.ifBlank { CallCopilot.Pure.tail(synchronized(lines) { lines.toList() } + listOf(partial).filter { it.isNotBlank() }) })
        .setStyle(NotificationCompat.BigTextStyle().bigText(hint.ifBlank { CallCopilot.Pure.tail(synchronized(lines) { lines.toList() } + listOf(partial).filter { it.isNotBlank() }, 420) }))
        .addAction(0, "Halo, help", pi(HELP, 61)).addAction(0, "Stop", pi(STOP, 62)).build()

    private fun refresh() { if (live) try { getSystemService(NotificationManager::class.java).notify(NID, note()) } catch (_: Throwable) { } }

    @Suppress("MissingPermission")
    private fun listen() {
        var rec: Recognizer? = null; var ar: android.media.AudioRecord? = null; var model: Model? = null
        try {
            model = Model(WakeModel.dir(this).absolutePath); rec = Recognizer(model, 16000f)
            val min = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            ar = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
            ar.startRecording(); hint = ""; main.post { refresh() }
            val buf = ShortArray(1600); var last = 0L
            while (running) {
                val n = ar.read(buf, 0, buf.size); if (n <= 0) { Thread.sleep(50); continue }
                if (rec.acceptWaveForm(buf, n)) add(JSONObject(rec.result).optString("text"))
                else if (System.currentTimeMillis() - last > 1200) { last = System.currentTimeMillis()
                    val p = JSONObject(rec.partialResult).optString("partial"); if (p.isNotBlank() && p != partial) { partial = p; main.post { refresh() } } }
            }
            add(JSONObject(rec.finalResult).optString("text"))
        } catch (_: Throwable) { hint = "The microphone isn't available during this call on this phone."; main.post { refresh() }
        } finally { try { ar?.stop(); ar?.release() } catch (_: Throwable) { }; try { rec?.close() } catch (_: Throwable) { }; try { model?.close() } catch (_: Throwable) { } }
    }

    private fun add(t: String) { val s = t.trim(); partial = ""; if (s.isEmpty()) return; synchronized(lines) { lines += s }; main.post { refresh() } }

    private fun help() {
        val snap = synchronized(lines) { lines.toList() }
        if (snap.isEmpty()) return
        scope.launch {
            val r = try { Agent.ask("Call Copilot", CallCopilot.Pure.suggestPrompt(snap)) } catch (_: Throwable) { "" }
            if (r.isNotBlank()) { hint = "Halo: " + r.take(300); main.post { refresh() }; main.postDelayed({ hint = ""; refresh() }, 25_000) }
        }
    }

    private fun finish() {
        running = false; live = false
        val snap = synchronized(lines) { lines.toList() }
        val mins = ((System.currentTimeMillis() - startedAt) / 60_000).coerceAtLeast(1)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) { }
        if (CallCopilot.Pure.worth(snap)) main.post { try { Agent.runRoutine("Call summary", CallCopilot.Pure.summaryPrompt(snap, mins)) } catch (_: Throwable) { } }
        stopSelf()
    }

    override fun onDestroy() { running = false; live = false; super.onDestroy() }
}
