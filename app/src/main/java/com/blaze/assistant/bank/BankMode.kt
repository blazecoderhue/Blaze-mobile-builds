package com.blaze.assistant.bank

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blaze.assistant.R
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.bank.BankModeLogic.Action
import com.blaze.assistant.bank.BankModeLogic.Event
import com.blaze.assistant.phone.BlazeAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * v0.43.0 Bank mode on the phone: runs [BankModeLogic]'s actions. On: Blaze's accessibility service switches itself
 * off ([android.accessibilityservice.AccessibilityService.disableSelf]), the floating orb, Magic Hand and the pointer
 * go away, notification reading pauses, Jarvis stops glancing at the screen or through the camera, and a plain
 * notification says so with "Turn Blaze back on". Off: what was on comes back; phone control comes back by itself when
 * Blaze holds WRITE_SECURE_SETTINGS (adb grant), otherwise the user is sent straight to Blaze's switch.
 *
 * Blaze has no VPN service and doesn't record the screen (its own browser's VPN is a proxy inside Blaze only, and
 * screen glances go through the accessibility service), so there is nothing else for a bank to see.
 */
object BankMode {
    private const val PREF_STATE = "bank_mode_state"
    private const val PREF_AUTO = "bank_mode_auto"
    private const val PREF_ADDED = "bank_apps_added"
    private const val PREF_REMOVED = "bank_apps_removed"
    private const val PREF_SEEDED = "bank_apps_seeded"
    const val CHANNEL = "blaze_bank_mode"
    const val NOTICE_ID = 83
    const val RESTORE_ID = 84
    const val ACTION_OFF = "com.blaze.assistant.BANK_MODE_OFF"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(BankModeLogic.State())
    val state: StateFlow<BankModeLogic.State> = _state
    @Volatile private var app: Context? = null

    /** True while Blaze is stepping back. Overlays, glances and the agent's screen tools check this. */
    val active: Boolean get() = _state.value.on

    fun init(ctx: Context) {
        app = ctx.applicationContext
        _state.value = load()
        if (_state.value.on) BankModeService.start(ctx.applicationContext)
    }

    var autoDetect: Boolean
        get() = try { Settings.bool(PREF_AUTO, true) } catch (_: Throwable) { true }
        set(v) = Settings.setBool(PREF_AUTO, v)

    // ---------- the bank list ----------
    private fun set(key: String): Set<String> = try { Settings.str(key, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() } catch (_: Throwable) { emptySet() }
    private fun put(key: String, v: Set<String>) = Settings.setStr(key, v.sorted().joinToString(","))
    fun added(): Set<String> = set(PREF_ADDED)
    fun removed(): Set<String> = set(PREF_REMOVED)

    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { "" }
    }

    /** Is [pkg] on the user's bank list (their own picks first, then what looks like a bank)? */
    fun isBank(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val ctx = app ?: return BankApps.looksLikeBank(pkg)
        return BankApps.isBank(pkg, label(ctx, pkg), added(), removed(), ctx.packageName)
    }

    /** Launchable apps on the phone (package to name). */
    fun installed(ctx: Context): Map<String, String> = try {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(i, 0).associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
    } catch (_: Throwable) { emptyMap() }

    /** The list the settings page shows: installed bank-looking apps plus the user's own picks, with their switch state. */
    fun listFor(ctx: Context): List<Triple<String, String, Boolean>> {
        val inst = installed(ctx)
        val seeded = BankApps.preseed(inst, ctx.packageName).toSet()
        val all = (seeded + added() + removed()).filter { it in inst }
        return all.map { Triple(it, inst[it] ?: it, isBank(it)) }.sortedBy { it.second.lowercase() }
    }

    /** Every launchable app, for "Add an app". */
    fun others(ctx: Context): List<Pair<String, String>> = installed(ctx).filter { (p, _) -> p != ctx.packageName && !isBank(p) }.toList().sortedBy { it.second.lowercase() }

    fun setBank(pkg: String, bank: Boolean) {
        val a = added().toMutableSet(); val r = removed().toMutableSet()
        if (bank) { a += pkg; r -= pkg } else { r += pkg; a -= pkg }
        put(PREF_ADDED, a); put(PREF_REMOVED, r)
    }

    // ---------- on / off ----------
    private fun now(ctx: Context): BankModeLogic.Saved = BankModeLogic.Saved(
        accessibility = BlazeAccessibilityService.instance != null,
        orb = try { Settings.orbPinned } catch (_: Throwable) { false },
        notifications = com.blaze.assistant.notify.NotificationBrain.instance != null,
        screenGlances = try { com.blaze.assistant.proactive.Jarvis.screenGlances } catch (_: Throwable) { false },
        cameraLooks = try { com.blaze.assistant.proactive.Jarvis.cameraLooks } catch (_: Throwable) { false },
    )

    @Synchronized private fun step(ctx: Context, e: Event) {
        val (next, actions) = BankModeLogic.reduce(_state.value, e, autoDetect)
        if (next == _state.value && actions.isEmpty()) return
        _state.value = next
        save(next)
        if (actions.isNotEmpty()) scope.launch { run(ctx.applicationContext, actions) }
    }

    /** From the settings switch, the quick tile, /bank, or the agent's bank_mode tool. Returns a plain sentence. */
    fun turnOn(ctx: Context): String {
        app = ctx.applicationContext
        if (active) return "Bank mode is already on."
        step(ctx, Event.TurnOn(now(ctx)))
        return "Bank mode is on. Blaze has stepped back: phone control, the floating button and screen reading are off until you turn it off."
    }

    fun turnOff(ctx: Context): String {
        app = ctx.applicationContext
        if (!active) return "Bank mode is already off."
        val hadA11y = _state.value.saved.accessibility
        step(ctx, Event.TurnOff)
        return if (!hadA11y || canRestoreA11y(ctx)) "Bank mode is off. Blaze is back." else "Bank mode is off. " + BankModeLogic.RESTORE_ASK
    }

    /** The app in front changed (from the accessibility service while it's on, else the watcher's usage check). */
    fun onFront(ctx: Context, pkg: String) {
        if (pkg.isBlank() || pkg == ctx.packageName && !active) return
        app = ctx.applicationContext
        val bank = pkg != ctx.packageName && isBank(pkg)
        step(ctx, Event.Front(pkg, bank, System.currentTimeMillis(), if (_state.value.on) _state.value.saved else now(ctx)))
    }

    fun tick(ctx: Context) = step(ctx, Event.Tick(System.currentTimeMillis()))

    fun statusLine(): String = _state.value.let { s ->
        when {
            !s.on -> "Bank mode is off." + (if (autoDetect) " It turns on by itself when a banking app on your list opens." else "")
            s.auto -> "Bank mode is on (a banking app is open). It turns off a few seconds after you leave it."
            else -> "Bank mode is on until you turn it off."
        }
    }

    // ---------- actions ----------
    private suspend fun run(ctx: Context, actions: List<Action>) {
        for (a in actions) try { when (a) {
            Action.HIDE_OVERLAYS -> {
                com.blaze.assistant.ui.Overlay.hideOrb(ctx, unpin = false)
                try { com.blaze.assistant.ui.Overlay.closePanel() } catch (_: Throwable) { }
                try { com.blaze.assistant.phone.MagicHand.stop() } catch (_: Throwable) { }
                try { com.blaze.assistant.phone.Pointer.done() } catch (_: Throwable) { }
            }
            // Jarvis checks [active] before every glance or camera look; nothing to switch.
            Action.PAUSE_GLANCES, Action.PAUSE_CAMERA, Action.RESUME_GLANCES, Action.RESUME_CAMERA -> Unit
            Action.PAUSE_NOTIFICATIONS -> com.blaze.assistant.notify.NotificationBrain.instance?.let { if (Build.VERSION.SDK_INT >= 24) it.requestUnbind() }
            Action.RESUME_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 24) android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(ctx, com.blaze.assistant.notify.NotificationBrain::class.java))
            Action.DISABLE_ACCESSIBILITY -> {
                val svc = BlazeAccessibilityService.instance
                if (svc != null && Build.VERSION.SDK_INT >= 24) svc.disableSelf()
            }
            Action.RESTORE_ACCESSIBILITY -> if (!restoreA11y(ctx)) askRestore(ctx)
            Action.SHOW_ORB -> { delay(600); com.blaze.assistant.ui.Overlay.showOrb(ctx) }
            Action.SHOW_NOTICE -> BankModeService.start(ctx)
            Action.WATCH_FRONT -> Unit   // the service watches while it runs
            Action.CLEAR_NOTICE, Action.STOP_WATCH -> BankModeService.stop(ctx)
        } } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.error("bank mode $a", e) }
    }

    fun canRestoreA11y(ctx: Context): Boolean = ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Puts Blaze's service back in Android's list, other services untouched. Needs WRITE_SECURE_SETTINGS. */
    private fun restoreA11y(ctx: Context): Boolean {
        if (!canRestoreA11y(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            val cn = ComponentName(ctx, BlazeAccessibilityService::class.java).flattenToString()
            val cur = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, BankModeLogic.editServices(cur, cn, true))
            android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            true
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.error("bank mode restore", e); false }
    }

    /** v0.45.0: asks in a row that phone control didn't come back after (reset when it connects). */
    private const val PREF_IGNORED = "bank_restore_ignored"
    private const val PREF_ASKED = "bank_restore_pending"
    fun restoreIgnored(): Int = Settings.int(PREF_IGNORED, 0)
    /** Phone control connected again: the last ask worked (or the user did it anyway). */
    /** "Remind me again" on Bank mode's page. */
    fun resetRestoreReminders() { Settings.setInt(PREF_IGNORED, 0); Settings.setBool(PREF_ASKED, false) }
    fun a11yBack() { if (Settings.bool(PREF_ASKED, false) || restoreIgnored() != 0) { Settings.setBool(PREF_ASKED, false); Settings.setInt(PREF_IGNORED, 0) } }

    /** Without the permission: one tap straight to Blaze's switch. */
    private fun askRestore(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        channel(nm)
        // The previous ask is still open and phone control never came back: it was ignored.
        if (Settings.bool(PREF_ASKED, false)) Settings.setInt(PREF_IGNORED, restoreIgnored() + 1)
        val ask = BankModeLogic.restoreAsk(restoreIgnored(), com.blaze.assistant.agent.SimpleMode.simple) ?: run {
            com.blaze.assistant.device.CrashLog.write("Bank mode: restore reminder skipped (ignored ${restoreIgnored()} times)"); return }
        Settings.setBool(PREF_ASKED, true)
        val pi = PendingIntent.getActivity(ctx, 1, com.blaze.assistant.phone.PhoneControl.fixIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            nm.notify(RESTORE_ID, NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_blaze)
                .setContentTitle(ask.title).setContentText(ask.text).setStyle(NotificationCompat.BigTextStyle().bigText(ask.text))
                .setContentIntent(pi).setAutoCancel(true).build())
        } catch (_: SecurityException) { }
    }

    fun channel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bank mode", NotificationManager.IMPORTANCE_LOW))
    }

    // ---------- saved state (survives Blaze restarting while a bank app is open) ----------
    private fun save(s: BankModeLogic.State) = try {
        Settings.setStr(PREF_STATE, JSONObject().put("on", s.on).put("auto", s.auto).put("pkg", s.bankPkg).put("left", s.leftAt)
            .put("a11y", s.saved.accessibility).put("orb", s.saved.orb).put("notif", s.saved.notifications)
            .put("glance", s.saved.screenGlances).put("cam", s.saved.cameraLooks).toString())
    } catch (_: Throwable) { }

    private fun load(): BankModeLogic.State = try {
        val o = JSONObject(Settings.str(PREF_STATE, "{}"))
        BankModeLogic.State(o.optBoolean("on"), o.optBoolean("auto"), o.optString("pkg"),
            BankModeLogic.Saved(o.optBoolean("a11y"), o.optBoolean("orb"), o.optBoolean("notif"), o.optBoolean("glance"), o.optBoolean("cam")), o.optLong("left"))
    } catch (_: Throwable) { BankModeLogic.State() }

    // ---------- agent tool ----------
    const val TOOL = "bank_mode"
    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Bank mode: Blaze steps back so banking apps work (they refuse to run while Blaze's phone control, floating button or screen reading is on). " +
            "on / off / status. Turn it on when the user asks or wants to use a bank, payment or wallet app. While it's on you can't read or control the screen. " +
            "Never read, tap or type inside a banking app, with or without Bank mode.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", org.json.JSONArray(listOf("on", "off", "status")))))
            .put("required", org.json.JSONArray(listOf("action")))))

    fun runTool(ctx: Context, args: JSONObject): String = when (args.optString("action")) {
        "on" -> turnOn(ctx)
        "off" -> turnOff(ctx)
        else -> statusLine()
    }
}

/**
 * Keeps Bank mode's notification up and, with usage access, notices when the user leaves the bank app (Blaze's
 * accessibility service is off by then, so it can't tell).
 */
class BankModeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: kotlinx.coroutines.Job? = null

    companion object {
        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, BankModeService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.error("bank mode service", e) }
        }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, BankModeService::class.java)) } catch (_: Throwable) { } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == BankMode.ACTION_OFF) { BankMode.turnOff(this); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.let { BankMode.channel(it) }
        val off = PendingIntent.getService(this, 2, Intent(this, BankModeService::class.java).setAction(BankMode.ACTION_OFF), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, BankMode.CHANNEL).setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(BankModeLogic.NOTICE_TITLE).setContentText(BankModeLogic.NOTICE_TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(BankModeLogic.NOTICE_TEXT))
            .setOngoing(true).setOnlyAlertOnce(true).addAction(0, BankModeLogic.NOTICE_ACTION, off).setContentIntent(off).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(BankMode.NOTICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(BankMode.NOTICE_ID, n)
        } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.error("bank mode foreground", e); nm?.notify(BankMode.NOTICE_ID, n) }
        if (loop?.isActive != true) loop = scope.launch {
            // v0.45.0 (#15): 1.5 s right after the front app changed, then 3 s; each query reads only the new events (FrontPoll).
            val poll = FrontPoll()
            while (isActive) {
                delay(poll.interval(System.currentTimeMillis()))
                if (!BankMode.active) { stopSelf(); break }
                front(poll)?.let { BankMode.onFront(this@BankModeService, it) }
                BankMode.tick(this@BankModeService)
            }
        }
        return START_STICKY
    }

    /** The app that newly came to the front since the last query (needs usage access; null without it or when none). */
    private fun front(poll: FrontPoll): String? = try {
        val usm = getSystemService(android.app.usage.UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val ev = usm.queryEvents(poll.since(end), end)
        val e = android.app.usage.UsageEvents.Event()
        var last: String? = null; var lastAt = 0L
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) { last = e.packageName; lastAt = e.timeStamp } }
        poll.record(end, last, lastAt)
    } catch (_: Throwable) { null }

    override fun onDestroy() { loop?.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy() }
}
