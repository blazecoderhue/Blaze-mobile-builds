package com.blaze.assistant.proactive

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.blaze.assistant.agent.Awareness
import com.blaze.assistant.agent.LlmClient
import com.blaze.assistant.agent.Memory
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.proactive.JarvisLogic.Level
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * v0.38.0 Jarvis mode: Blaze reaching out on its own, every few minutes while the phone is in use.
 *  - Questions: a real getting-to-know-you question as a heads-up ("Quick question: what do you do for fun?"); the
 *    reply lands in the Heads-ups chat, where Blaze saves what it learns with remember.
 *  - Screen glances (switch, off by default): the screen as text through phone control, never banks, wallets,
 *    password managers, sign-ins or codes; Blaze offers help only when it really fits, and keeps a one-line note of
 *    what the user was doing (Awareness), nothing else.
 *  - Camera looks (switch, off by default): one small front-camera frame, only while Blaze is open on the Chat
 *    screen (Android shows its camera dot); never saved.
 *  - Usage: today's top apps (Usage access) go into its thinking.
 *  - Its own routines: every few days it may propose one; at the Jarvis level it sets it up itself, named
 *    "Blaze · …", and says so with a tap to remove.
 * Everything respects quiet hours, calls and the per-level limits in [JarvisLogic].
 */
object Jarvis {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.blaze.assistant.device.CrashLog.guard)
    private var loop: Job? = null
    private val lock = Mutex()

    var level: Level
        get() = Level.of(Settings.str("jarvis_level", Level.EAGER.id))
        set(v) { Settings.setStr("jarvis_level", v.id); if (::app.isInitialized) start(app) }
    var screenGlances: Boolean
        get() = Settings.bool("jarvis_screen", false)
        set(v) = Settings.setBool("jarvis_screen", v)
    var cameraLooks: Boolean
        get() = Settings.bool("jarvis_camera", false)
        set(v) = Settings.setBool("jarvis_camera", v)

    private fun num(k: String) = Settings.str(k, "0").toLongOrNull() ?: 0L
    private fun setNum(k: String, v: Long) = Settings.setStr(k, v.toString())

    fun start(ctx: Context) {
        app = ctx.applicationContext
        loop?.cancel(); loop = null
        if (level == Level.OFF) return
        loop = scope.launch { delay(120_000); while (isActive) { try { tick() } catch (_: Throwable) { }; delay(5 * 60_000L) } }
    }

    private fun inUse(): Boolean {
        val pm = app.getSystemService(PowerManager::class.java)
        val kg = app.getSystemService(KeyguardManager::class.java)
        return pm?.isInteractive == true && kg?.isKeyguardLocked != true
    }

    private fun busy(): Boolean = try {
        val am = app.getSystemService(android.media.AudioManager::class.java)
        am?.mode == android.media.AudioManager.MODE_IN_CALL || am?.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
    } catch (_: Throwable) { false }

    suspend fun tick() = lock.withLock {
        if (!::app.isInitialized || !Settings.ready) return@withLock
        val l = level
        if (l == Level.OFF || Proactive.quietNow() || busy() || !inUse()) return@withLock
        // v0.43.0: Bank mode on: no screen glances, camera looks or anything else until it's off.
        if (com.blaze.assistant.bank.BankMode.active) return@withLock
        val lim = JarvisLogic.limits(l)
        val now = System.currentTimeMillis()
        val blazeFront = Awareness.get("screen") != null
        // One reach-out per tick at most: camera (Blaze open), else a screen glance, else a question, else a routine.
        if (blazeFront && cameraLooks && JarvisLogic.everyDue(lim.cameraEveryMin, num("jarvis_cam_at"), now) && Awareness.get("screen")?.title == "Chat") {
            setNum("jarvis_cam_at", now); if (cameraLook()) return@withLock
        }
        if (!blazeFront && screenGlances && JarvisLogic.everyDue(lim.glanceEveryMin, num("jarvis_glance_at"), now)) {
            if (screenGlance(now)) return@withLock
        }
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val askedToday = if (Settings.str("jarvis_q_day", "") == day) Settings.str("jarvis_q_n", "0").toIntOrNull() ?: 0 else 0
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (JarvisLogic.questionDue(l, askedToday, num("jarvis_q_at"), now, hour, false)) {
            if (askQuestion(day, askedToday, now)) return@withLock
        }
        if (JarvisLogic.callDue(l, num("jarvis_call_at"), now, hour)) { setNum("jarvis_call_at", now); offerCall(); return@withLock }
        if (lim.routineEveryDays > 0 && now - num("jarvis_routine_at") >= lim.routineEveryDays * 86_400_000L && hour in 10..20) {
            setNum("jarvis_routine_at", now); selfRoutine(lim.selfRoutines)
        }
    }

    // ---------------- questions ----------------

    private suspend fun askQuestion(day: String, askedToday: Int, now: Long): Boolean {
        val asked = Settings.str("jarvis_asked", "").split(',').filter { it.isNotBlank() }.toSet()
        val mem = try { Memory.promptBlock("") } catch (_: Throwable) { "" }
        val q = JarvisLogic.pick(asked, mem, Settings.str("jarvis_last_topic", ""), (now / 86_400_000L).toInt()) ?: return false
        // Blaze may reword it to fit what's going on ("You've had a lot of jazz on today: who got you into it?").
        val own = try {
            val sys = "You are Blaze, the user's phone assistant, getting to know them like a friend would. Write ONE short, warm, specific question on the topic \"${q.topic}\" " +
                "(example: \"${q.text}\"), tied to what they're doing if that fits naturally. Never about passwords, money details or anything private. ${mem.take(800)}\n" +
                "Reply ONLY with JSON: {\"question\": \"...?\"}"
            val ctx = Awareness.promptBlock().ifBlank { "Nothing in particular is going on." }
            JarvisLogic.parseQuestion(llm(sys, ctx))
        } catch (_: Throwable) { "" }
        val text = own.ifBlank { q.text }
        Proactive.say(text, "jarvis-q:" + q.id)
        Settings.setStr("jarvis_asked", (asked + q.id).joinToString(","))
        Settings.setStr("jarvis_last_topic", q.topic)
        Settings.setStr("jarvis_q_day", day); Settings.setStr("jarvis_q_n", (askedToday + 1).toString())
        setNum("jarvis_q_at", now)
        return true
    }

    // ---------------- screen glances ----------------

    private fun launcher(pkg: String): Boolean = try {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        app.packageManager.queryIntentActivities(home, 0).any { it.activityInfo?.packageName == pkg }
    } catch (_: Throwable) { false }

    private suspend fun screenGlance(now: Long): Boolean {
        val svc = com.blaze.assistant.phone.BlazeAccessibilityService.instance ?: return false
        // Never while Blaze is working a task (it may be using the screen's element numbers).
        if (try { com.blaze.assistant.agent.Agent.busyChats.value.isNotEmpty() } catch (_: Throwable) { true }) return false
        val pkg = try { withContext(Dispatchers.Main) { svc.rootInActiveWindow?.packageName?.toString() } } catch (_: Throwable) { null } ?: return false
        if (!JarvisLogic.glanceable(pkg, app.packageName, launcher(pkg), Settings.str("jarvis_glance_pkg", ""), num("jarvis_glance_at"), now)) return false
        if (JarvisLogic.sensitive(pkg, "")) return false
        val screen = try { withContext(Dispatchers.Main) { svc.readScreen() } } catch (_: Throwable) { return false }
        setNum("jarvis_glance_at", now); Settings.setStr("jarvis_glance_pkg", pkg)
        if (JarvisLogic.sensitive(pkg, screen)) return false
        val label = try { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
        val compact = com.blaze.assistant.agent.Orient.compact(screen, maxLines = 30, maxChars = 1800)
        val sys = "You are Blaze, the user's phone assistant, glancing at their screen on your own to see if you can help right now. " +
            "Speak only when you can clearly save them time or effort with something concrete you can do (compare prices, add an event they're looking at, " +
            "translate, summarise a long article, find a better route, reply drafts, remind them later). Never comment on private messages or content, never judge. " +
            "Most glances say nothing. ${Memory.promptBlock(label).take(600)}\n" +
            "Reply ONLY with JSON: {\"say\": \"\" or \"one short friendly sentence offering help\", \"suggest\": \"\" or \"the request they'd tap, max 6 words\", " +
            "\"note\": \"what they're doing, max 12 words, no private details\"}"
        val g = JarvisLogic.parseGlance(llm(sys, "App: $label\nToday's usage: ${usageToday()}\n${Awareness.promptBlock()}\nScreen:\n$compact"))
        if (g.note.isNotBlank()) Awareness.did("glance", "in $label: ${g.note}")
        if (g.say.isNotBlank()) { Proactive.say(g.say, "jarvis-glance:" + g.say.lowercase().take(40), suggest = g.suggest); return true }
        return false
    }

    // ---------------- camera looks ----------------

    private class Owner : LifecycleOwner {
        val reg = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = reg
    }

    /** One small front-camera frame as a JPEG data URL, or null. Main thread; Blaze must be in front. */
    private suspend fun frontFrame(): String? = withContext(Dispatchers.Main) {
        if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return@withContext null
        val exec = ContextCompat.getMainExecutor(app)
        val provider = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<ProcessCameraProvider?> { cont ->
                val f = ProcessCameraProvider.getInstance(app)
                f.addListener({ if (cont.isActive) cont.resume(runCatching { f.get() }.getOrNull()) }, exec)
            }
        } ?: return@withContext null
        val front = CameraSelector.DEFAULT_FRONT_CAMERA
        if (!runCatching { provider.hasCamera(front) }.getOrDefault(false)) return@withContext null
        val owner = Owner().apply { reg.currentState = Lifecycle.State.STARTED }
        val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        try {
            provider.bindToLifecycle(owner, front, cap)
            delay(800)   // let exposure settle
            val bmp = withTimeoutOrNull(6_000) {
                suspendCancellableCoroutine<Bitmap?> { cont ->
                    cap.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val b = try {
                                val raw = image.toBitmap()
                                val k = minOf(1f, 512f / maxOf(raw.width, raw.height))
                                val m = android.graphics.Matrix().apply { postScale(k, k); postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                            } catch (_: Throwable) { null } finally { image.close() }
                            if (cont.isActive) cont.resume(b)
                        }
                        override fun onError(exception: ImageCaptureException) { if (cont.isActive) cont.resume(null) }
                    })
                }
            } ?: return@withContext null
            val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Throwable) { null } finally {
            runCatching { provider.unbind(cap) }
            runCatching { owner.reg.currentState = Lifecycle.State.DESTROYED }
        }
    }

    private suspend fun cameraLook(): Boolean {
        val url = frontFrame() ?: return false
        val sys = "You are Blaze, the user's phone assistant, taking a quick look through the front camera while they have you open. " +
            "Say something only if it genuinely helps or is kind and natural (they look tired late at night, they're somewhere new, they're holding something you could help with). " +
            "Never comment on looks, body or other people, never guess identity, age or emotion beyond the obvious. Most looks say nothing. " +
            "Reply ONLY with JSON: {\"say\": \"\" or \"one short friendly sentence\", \"suggest\": \"\" or \"a request they'd tap, max 6 words\", \"note\": \"\"}"
        val user = JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Time: " + SimpleDateFormat("EEEE HH:mm", Locale.getDefault()).format(Date()) + "\n" + Awareness.promptBlock()))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))))
        val raw = try {
            LlmClient.complete(JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(user), null, null, null, LlmClient.Lane.BACKGROUND).optString("content")
        } catch (_: Throwable) { return false }
        val g = JarvisLogic.parseGlance(raw)
        if (g.say.isBlank()) return false
        Proactive.say(g.say, "jarvis-cam:" + g.say.lowercase().take(40), suggest = g.suggest)
        return true
    }

    // ---------------- usage and its own routines ----------------

    /** Today's top apps by screen time ("" without Usage access). */
    fun usageToday(): String = try {
        if (!com.blaze.assistant.home.HomeSources.usageAllowed(app)) "" else {
            val usm = app.getSystemService(android.app.usage.UsageStatsManager::class.java)
            val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
            val stats = usm?.queryAndAggregateUsageStats(start, System.currentTimeMillis()) ?: emptyMap()
            val pm = app.packageManager
            JarvisLogic.usageLine(stats.values.filter { it.packageName != app.packageName }.map { s ->
                (try { pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString() } catch (_: Throwable) { s.packageName }) to s.totalTimeInForeground
            })
        }
    } catch (_: Throwable) { "" }

    private suspend fun selfRoutine(create: Boolean) {
        val existing = try { com.blaze.assistant.routine.Routines.all.value.joinToString("; ") { "${it.name} at %02d:%02d".format(it.hour, it.minute) } } catch (_: Throwable) { "" }
        val sys = "You are Blaze, the user's phone assistant. Propose at most ONE routine you would run for them on a schedule that would genuinely help, " +
            "based on their habits, usage and what you know (a morning brief before their usual first app, a wind-down nudge if late-night scrolling is high, " +
            "a weekly check-in on a goal). Never one that sends messages, spends money or deletes anything. Not a copy of an existing routine. ${Memory.promptBlock("routine habits").take(800)}\n" +
            "Reply ONLY with JSON: {\"name\": \"\" (empty = none), \"prompt\": \"what you will do each time, as an instruction to yourself\", \"hour\": 0-23, \"minute\": 0-59, " +
            "\"days\": bitmask Mon=1 Tue=2 Wed=4 Thu=8 Fri=16 Sat=32 Sun=64 (127 = every day), \"why\": \"one short sentence for the user\"}"
        val idea = JarvisLogic.parseRoutine(llm(sys, "Existing routines: ${existing.ifBlank { "none" }}\nToday's usage: ${usageToday().ifBlank { "unknown" }}\n${Awareness.promptBlock()}")) ?: return
        val time = "%02d:%02d".format(idea.hour, idea.minute)
        if (create) {
            val r = com.blaze.assistant.routine.Routines.add(JarvisLogic.SELF_PREFIX + idea.name, idea.prompt, idea.hour, idea.minute, idea.days)
            Awareness.did("routine", "set itself a routine: ${r.name} at $time")
            Proactive.say("I set myself a routine: ${idea.name}, ${r.daysLabel().lowercase()} at $time. ${idea.why}".trim(), "jarvis-routine:" + idea.name.lowercase(),
                suggest = "Remove routine ${r.name}")
        } else {
            Proactive.say("Want me to take care of something? ${idea.name}, at $time. ${idea.why}".trim(), "jarvis-routine:" + idea.name.lowercase(),
                suggest = "Yes, set up ${idea.name}".take(60))
        }
    }

    // ---------------- offering a call ----------------

    /** "Got a minute? I can catch you up out loud." with a Call me button (Blaze's voice call). */
    private fun offerCall() {
        val open = Awareness.nows().count { it.key.startsWith("followup:") }
        val text = JarvisLogic.callLine(open, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        try { com.blaze.assistant.agent.Agent.recordHeadsUp(text) } catch (_: Throwable) { }
        val ch = "blaze_calls"
        if (android.os.Build.VERSION.SDK_INT >= 26) app.getSystemService(android.app.NotificationManager::class.java)?.createNotificationChannel(
            android.app.NotificationChannel(ch, "Blaze check-ins", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Blaze offering a quick voice call" })
        val call = android.app.PendingIntent.getActivity(app, 7400, com.blaze.assistant.IntentKey.sign(app, Intent(app, com.blaze.assistant.MainActivity::class.java)
            .setAction(com.blaze.assistant.MainActivity.ACTION_CALL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val n = androidx.core.app.NotificationCompat.Builder(app, ch).setSmallIcon(com.blaze.assistant.R.drawable.ic_stat_blaze).setColor(0xFF1F4BD8.toInt())
            .setContentTitle("Blaze").setContentText(text).setAutoCancel(true).setContentIntent(call)
            .addAction(0, "Call me", call).build()
        try { androidx.core.app.NotificationManagerCompat.from(app).notify(7401, n) } catch (_: SecurityException) { }
    }

    // ---------------- chat control ----------------

    const val TOOL = "jarvis_mode"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Jarvis mode: how much Blaze reaches out on its own (questions to get to know the user, screen glances, camera looks while Blaze is open, its own routines). " +
            "Use when the user says 'be more / less proactive', 'stop asking me questions', 'look at my screen now and then', 'don't use the camera'. No arguments = show the current settings.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("level", JSONObject().put("type", "string").put("enum", JSONArray(Level.values().map { it.id })))
            .put("screen_glances", JSONObject().put("type", "boolean"))
            .put("camera_looks", JSONObject().put("type", "boolean")))))

    fun run(args: JSONObject): String {
        if (args.has("level")) level = Level.of(args.optString("level"))
        if (args.has("screen_glances")) screenGlances = args.optBoolean("screen_glances")
        if (args.has("camera_looks")) cameraLooks = args.optBoolean("camera_looks")
        val needs = buildList {
            if (screenGlances && com.blaze.assistant.phone.BlazeAccessibilityService.instance == null) add("screen glances need phone control (Settings › Phone control)")
            if (cameraLooks && ::app.isInitialized && ContextCompat.checkSelfPermission(app, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add("camera looks need the camera permission")
        }
        val lim = JarvisLogic.limits(level)
        return "Jarvis mode: ${level.label} (up to ${lim.questionsPerDay} questions a day; ${if (lim.selfRoutines) "sets up its own routines" else "offers routines"}). " +
            "Screen glances ${if (screenGlances) "on, every ${lim.glanceEveryMin} min" else "off"}. Camera looks ${if (cameraLooks && lim.cameraEveryMin > 0) "on while Blaze is open, every ${lim.cameraEveryMin} min" else "off"}." +
            (if (needs.isNotEmpty()) " Still needed: " + needs.joinToString("; ") + "." else "")
    }

    private suspend fun llm(sys: String, user: String): String = try {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", user))
        LlmClient.complete(msgs, null, null, null, LlmClient.Lane.BACKGROUND).optString("content")
    } catch (_: Throwable) { "" }
}
