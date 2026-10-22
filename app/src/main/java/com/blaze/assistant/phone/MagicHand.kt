package com.blaze.assistant.phone

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Attachment
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Role
import com.blaze.assistant.browser.PointerActions
import com.blaze.assistant.phone.MagicHandLogic.Action
import com.blaze.assistant.phone.MagicHandLogic.Gesture
import com.blaze.assistant.phone.MagicHandLogic.Intent as HandIntent
import com.blaze.assistant.phone.MagicHandState.DockButton
import com.blaze.assistant.phone.MagicHandState.GrabAction
import com.blaze.assistant.phone.MagicHandState.Panel
import com.blaze.assistant.ui.DarkColors
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MascotArt
import com.blaze.assistant.ui.OverlayOwner
import com.blaze.assistant.ui.composeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Magic Hand (v0.24.7): Blaze's mascot as a pointer the user moves, summoned from the hand button in the quick
 * assistant panel (hold Home). Three accessibility overlay windows: a non-touchable highlight layer, the hand (drag it;
 * the flame tip points), and the dock (status, menu, answer, confirm, grab, teach, Close).
 *
 * Rules: dragging only highlights; nothing is ever tapped or typed without a yes on the confirm card; closing the
 * dock stops everything (windows, the question in flight, carrying, teach recording); in Private
 * ([MagicHandGuard]) nothing is captured, sent or saved.
 */
object MagicHand {
    private val _ui = MutableStateFlow(MagicHandState.Ui())
    val ui: StateFlow<MagicHandState.Ui> = _ui
    val on: Boolean get() = _ui.value.on

    private val main = Handler(Looper.getMainLooper())
    private var svc: BlazeAccessibilityService? = null
    private var wm: WindowManager? = null
    private var scope: CoroutineScope? = null
    private var hand: HandFrame? = null
    private var handLp: WindowManager.LayoutParams? = null
    private var handOwner: OverlayOwner? = null
    private var hi: HighlightView? = null
    private var dock: View? = null
    private var dockLp: WindowManager.LayoutParams? = null
    private var dockOwner: OverlayOwner? = null
    // v0.26.0: the movable / resizable grab box (its own small window, added under the dock and the hand).
    private var boxView: BoxFrame? = null
    private var boxLp: WindowManager.LayoutParams? = null
    private var grabBox: MagicHandLogic.Box? = null
    private var regrabJob: Job? = null
    private var grabGen = 0
    // v0.27.0: Grab mode's drawing layer (circle or drag with a finger anywhere) and the box's Undo history.
    private var drawView: DrawLayer? = null
    private var drawLp: WindowManager.LayoutParams? = null
    private val drawPts = ArrayList<MagicHandLogic.P>()
    private val boxHistory = MagicHandBox.History()
    private val lit = mutableStateOf(false)
    private val carryingState = mutableStateOf(false)

    private var lastHit: MagicHandScreen.Hit? = null
    private var pendingHit: MagicHandScreen.Hit? = null
    private var recorder: MagicHandTeach.Recorder? = null
    private var askJob: Job? = null
    private var askedAgent = false
    private var ignoreClicksUntil = 0L
    private var density = 2f
    // v0.25.0 safety: watchdog beats, last touch, Back-twice, teach filter lists, label cache, off-main workers.
    @Volatile private var beatAt = 0L
    @Volatile private var touchAt = 0L
    private var watchdog: Thread? = null
    private val backTwice = MagicHandSafety.BackTwice()
    private var launchers: Set<String> = emptySet()
    private var imes: Set<String> = emptySet()
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "magic-hand").apply { isDaemon = true } }
    private var probeJob: Job? = null
    private var bottomInset = 0
    private var topInset = 0
    private const val NOTE_ID = 14

    const val HAND_DP = 64

    private fun set(f: (MagicHandState.Ui) -> MagicHandState.Ui) {
        val old = _ui.value
        val n = f(old); _ui.value = n
        // The grab box lives as long as the grabbed-text card.
        if (old.panel == Panel.GRAB && n.panel != Panel.GRAB) hideBox()
        if (old.grabMode != n.grabMode) drawActive(n.grabMode)
        carryingState.value = n.carrying
        updateDockFocus(n.panel == Panel.TEACH_REVIEW)
    }

    /** Private chat, or a private tab in Blaze's browser while it's in front. */
    fun isPrivate(): Boolean {
        if (Agent.privateChat.value) return true
        val s = svc ?: BlazeAccessibilityService.instance
        return try {
            com.blaze.assistant.browser.BrowserEngine.tab()?.isPrivate == true && s != null && s.currentPkg() == s.packageName
        } catch (_: Throwable) { false }
    }

    /** From the assistant panel's hand button. Returns a line to show when it can't start (phone control off), else null. */
    fun summon(ctx: Context): String? {
        val s = BlazeAccessibilityService.instance ?: return MagicHandState.NEEDS_A11Y
        main.postDelayed({ start(s) }, 250)
        return null
    }

    private fun start(s: BlazeAccessibilityService) {
        if (on) return
        svc = s
        val w = s.getSystemService(WindowManager::class.java) ?: return
        wm = w
        density = s.resources.displayMetrics.density
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        _ui.value = MagicHandState.start(isPrivate())
        insets(s, w)
        com.blaze.assistant.ui.MotionTokens.refresh(s)
        addHighlight(s, w)
        addDraw(s, w)
        addBox(s, w)
        addDock(s, w)
        addHand(s, w)
        touchAt = SystemClock.uptimeMillis(); beatAt = touchAt
        startWatchdog()
        keyFilter(s, true)
        // v0.28.1: load the OCR model now (off the main thread), so the first grab doesn't pay for it.
        if (MagicHandGuard.canGrab(isPrivate())) scope?.launch(Dispatchers.Default) { MagicHandScreen.warm() }
        showNote(s)
    }

    /** The navigation bar / gesture area and status bar heights, so nothing of ours covers them. */
    private fun insets(s: Context, w: WindowManager) {
        val res = s.resources
        fun dimen(name: String) = res.getIdentifier(name, "dimen", "android").takeIf { it != 0 }?.let { res.getDimensionPixelSize(it) } ?: 0
        bottomInset = dimen("navigation_bar_height"); topInset = dimen("status_bar_height")
        if (android.os.Build.VERSION.SDK_INT >= 30) try {
            val ins = w.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.systemGestures() or android.view.WindowInsets.Type.statusBars())
            bottomInset = maxOf(ins.bottom, if (bottomInset > 0 && ins.bottom == 0) bottomInset else 0)
            topInset = maxOf(ins.top, topInset)
        } catch (_: Throwable) { }
    }

    /** Main-thread heartbeat + a background watchdog: a stall over 3 s or 10 idle minutes ends Magic Hand. */
    private fun startWatchdog() {
        val beat = object : Runnable { override fun run() { beatAt = SystemClock.uptimeMillis(); if (on) main.postDelayed(this, 500) } }
        main.post(beat)
        watchdog?.interrupt()
        watchdog = Thread({
            try {
                while (on) {
                    Thread.sleep(1_000)
                    if (!on) break
                    when (MagicHandSafety.watchdog(SystemClock.uptimeMillis(), beatAt, touchAt)) {
                        MagicHandSafety.Verdict.OK -> Unit
                        MagicHandSafety.Verdict.IDLE -> { com.blaze.assistant.device.CrashLog.write(MagicHandSafety.IDLE_NOTE); stop(); break }
                        MagicHandSafety.Verdict.STALLED -> {
                            com.blaze.assistant.device.CrashLog.write(MagicHandSafety.STALLED_NOTE)
                            main.postAtFrontOfQueue { stopNow() }
                            Thread.sleep(1_500)
                            // Still stuck: the windows belong to the frozen UI thread, so the only sure way to give the
                            // phone back is to end Blaze's process (Android restarts the accessibility service by itself).
                            if (on && SystemClock.uptimeMillis() - beatAt > MagicHandSafety.STALL_MS) android.os.Process.killProcess(android.os.Process.myPid())
                            break
                        }
                    }
                }
            } catch (_: InterruptedException) { }
        }, "magic-hand-watchdog").apply { isDaemon = true; start() }
    }

    /** Observe (never consume) key presses while Magic Hand is on, so Back twice can close it. */
    private fun keyFilter(s: BlazeAccessibilityService, on: Boolean) {
        try {
            val info = s.serviceInfo ?: return
            val f = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            info.flags = if (on) info.flags or f else info.flags and f.inv()
            s.serviceInfo = info
        } catch (_: Throwable) { }
    }

    /** From BlazeAccessibilityService.onKeyEvent. Always returns false: Back, Home and Recents always reach Android. */
    fun onKey(e: android.view.KeyEvent): Boolean {
        if (!on) return false
        if (e.keyCode == android.view.KeyEvent.KEYCODE_BACK && e.action == android.view.KeyEvent.ACTION_UP) {
            touchAt = SystemClock.uptimeMillis()
            if (backTwice.press(e.eventTime)) stop()
            else if (dockFocusable) main.post { updateDockFocus(false) }
        }
        return false
    }

    private fun showNote(s: Context) {
        try {
            val nm = s.getSystemService(android.app.NotificationManager::class.java) ?: return
            val stopPi = android.app.PendingIntent.getBroadcast(s, 41,
                Intent(s, com.blaze.assistant.notify.ActionReceiver::class.java).setAction(com.blaze.assistant.notify.ActionReceiver.STOP_HAND),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(s, "blaze_work")
                .setSmallIcon(com.blaze.assistant.R.drawable.ic_stat_blaze)
                .setContentTitle("Magic Hand is on")
                .setContentText("Back twice, hold the dock, or tap Stop to close it.")
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .addAction(0, "Stop Magic Hand", stopPi)
                .setContentIntent(stopPi)
                .build()
            nm.notify(NOTE_ID, n)
        } catch (_: Throwable) { }
    }

    /** Closing the dock: everything stops and every window goes. */
    fun stop() { main.post { stopNow() } }

    private fun stopNow() {
        run {
            if (!on && hand == null && dock == null && hi == null && boxView == null && drawView == null) { _ui.value = MagicHandState.close(); return@run }
            watchdog?.interrupt(); watchdog = null
            probeJob?.cancel(); probeJob = null
            svc?.let { keyFilter(it, false) }
            try { (svc ?: BlazeAccessibilityService.instance)?.getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTE_ID) } catch (_: Throwable) { }
            dockFocusable = false
            if (askedAgent && askJob?.isActive == true && Agent.mood.value != Mood.IDLE) try { Agent.stop() } catch (_: Throwable) { }
            askJob?.cancel(); askJob = null; askedAgent = false
            regrabJob?.cancel(); regrabJob = null; grabBox = null; boxHistory.clear(); drawPts.clear()
            scope?.cancel(); scope = null
            worker.execute { MagicHandScreen.release() }
            recorder = null; lastHit = null; pendingHit = null
            val w = wm
            listOf(hand, hi, dock, boxView, drawView).forEach { v -> if (v != null) try { w?.removeViewImmediate(v) } catch (_: Exception) { } }
            hand = null; hi = null; dock = null; handLp = null; dockLp = null; boxView = null; boxLp = null; drawView = null; drawLp = null
            handOwner?.destroy(); handOwner = null
            dockOwner?.destroy(); dockOwner = null
            _ui.value = MagicHandState.close()
            carryingState.value = false; lit.value = false
            svc = null; wm = null
        }
    }

    // ---------- windows ----------
    private fun type() = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private fun screen(): Pair<Int, Int> {
        val s = svc ?: return android.content.res.Resources.getSystem().displayMetrics.let { it.widthPixels to it.heightPixels }
        val w = wm
        return if (android.os.Build.VERSION.SDK_INT >= 30 && w != null) w.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else s.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    private fun addHighlight(s: Context, w: WindowManager) {
        val v = HighlightView(s, density)
        // Never touchable, never focusable: every touch goes straight through to the app and the system bars.
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type(),
            MagicHandSafety.flags(MagicHandSafety.Win.HIGHLIGHT), PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        try { w.addView(v, lp); hi = v } catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("Magic Hand highlight", e) }
    }

    private fun addDock(s: Context, w: WindowManager) {
        val owner = OverlayOwner().also { it.start() }
        val calls = DockCalls(
            onButton = ::onDockButton, onMenu = ::onMenu, onConfirm = ::onConfirm, onGrab = ::onGrab,
            onBack = { set { MagicHandState.back(it) } },
            onTeachName = { n -> set { it.copy(teachName = n.take(60)) } },
            onTeachUndo = { recorder?.undo(); set { MagicHandState.teachSteps(it, teachLines()) } },
            onTeachSave = ::saveTeach,
            onTeachDiscard = { recorder?.clear(); recorder = null; set { MagicHandState.endTeach(it, "Discarded. Nothing was saved.") } },
            onTeachStart = { startTeach() },
            onGrabUndo = ::undoBox,
            onGrabAgain = { grabMode(true) },
        )
        val inner = composeView(s, owner) {
            val u by ui.collectAsState()
            CompositionLocalProvider(LocalBlaze provides DarkColors) { MagicHandDock(u, calls) }
        }
        // The dock's own frame: a long-press anywhere on it closes Magic Hand (not while typing a name), Back while it
        // has the keyboard gives the keyboard back, and a touch outside it never gets swallowed (NOT_TOUCH_MODAL).
        val v = DockFrame(s).apply {
            owner.attach(this)
            addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            // v0.26.0: not while the dock shows selectable text (grabbed text, an answer). Long-pressing that text to
            // select part of it used to hit this 650 ms timer and close Magic Hand, box and card included.
            onLongPress = { if (MagicHandState.longPressCloses(_ui.value)) stop() }
            onBack = { touchAt = SystemClock.uptimeMillis(); if (backTwice.press(SystemClock.uptimeMillis())) stop() else updateDockFocus(false) }
            onOutside = { if (dockFocusable) updateDockFocus(false) }
            onAnyTouch = { touchAt = SystemClock.uptimeMillis() }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type(),
            MagicHandSafety.flags(MagicHandSafety.Win.DOCK), PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM
            y = bottomInset   // sits above the navigation bar / gesture area, never on it
        }
        try { w.addView(v, lp); dock = v; dockLp = lp; dockOwner = owner }
        catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("Magic Hand dock", e); owner.destroy() }
    }

    @Volatile private var dockFocusable = false

    private fun startTeach() {
        val s = svc ?: return
        if (!MagicHandGuard.canTeach(isPrivate())) { set { MagicHandState.back(it.copy(private = true), MagicHandGuard.PRIVATE_NOTE) }; return }
        com.blaze.assistant.agent.Settings.setBool("mh_teach_intro_seen", true)
        recorder = MagicHandTeach.Recorder(s.packageName)
        // The phone's own home apps and keyboards are never part of a routine.
        worker.execute {
            launchers = try {
                s.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                    .mapNotNull { it.activityInfo?.packageName?.lowercase() }.toSet()
            } catch (_: Throwable) { emptySet() }
            imes = try {
                s.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.enabledInputMethodList
                    ?.map { it.packageName.lowercase() }?.toSet() ?: emptySet()
            } catch (_: Throwable) { emptySet() }
        }
        set { MagicHandState.teachSteps(MagicHandState.teach(it), teachLines()) }
    }

    /** The teach review has a name field, so only then may the dock take the keyboard. */
    private fun updateDockFocus(focusable: Boolean) {
        val v = dock ?: return; val lp = dockLp ?: return
        dockFocusable = focusable
        // v0.25.0: always NOT_TOUCH_MODAL. (v0.24.7 dropped NOT_FOCUSABLE here without adding NOT_TOUCH_MODAL, so on the
        // teach review the dock took every touch on the screen, the navigation bar's included.)
        val want = MagicHandSafety.flags(MagicHandSafety.Win.DOCK, typing = focusable)
        if (lp.flags == want) return
        lp.flags = want
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    private val tipX: Float get() { val box = (HAND_DP - 10) * density; return 5 * density + MascotArt.glyphTip(box).x }
    private val tipY: Float get() { val box = (HAND_DP - 10) * density; return 5 * density + MascotArt.glyphTip(box).y }

    @SuppressLint("ClickableViewAccessibility")
    private fun addHand(s: Context, w: WindowManager) {
        val size = (HAND_DP * density).toInt()
        val owner = OverlayOwner().also { it.start() }
        val frame = HandFrame(s, density)
        // v0.25.0: the window's root (this frame) carries the lifecycle owner too. In v0.24.7 only the inner ComposeView
        // had it, and Compose looks the owner up from the window's root view, so the mascot never got a composition.
        owner.attach(frame)
        val inner = composeView(s, owner) { MagicHandGlyph(HAND_DP, lit.value, carryingState.value) }
        frame.addView(inner, FrameLayout.LayoutParams(size, size))
        frame.contentDescription = "Magic Hand. Drag to point, double-tap for a menu."
        val (sw, sh) = screen()
        val (x0, y0) = MagicHandSafety.startSpot(size, sw, sh, bottomInset + (120 * density).toInt(), topInset)
        val lp = WindowManager.LayoutParams(size, size, type(), MagicHandSafety.flags(MagicHandSafety.Win.HAND),
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = x0; y = y0 }
        val points = ArrayList<MagicHandLogic.P>()
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var lastTapUp = 0L; var lastProbe = 0L
        frame.onTouch = { e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchAt = SystemClock.uptimeMillis()
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    // v0.25.4: the drawn line (and the circle it captures) follows the hand's centre, not the flame tip.
                    points.clear(); points += MagicHandLogic.trailPoint(lp.x, lp.y, size, e.eventTime)
                }
                MotionEvent.ACTION_MOVE -> {
                    touchAt = SystemClock.uptimeMillis()
                    val (sw2, sh2) = screen()
                    val (cx, cy) = MagicHandSafety.clamp(startX + (e.rawX - downX).toInt(), startY + (e.rawY - downY).toInt(), size, sw2, sh2, bottomInset, topInset)
                    lp.x = cx; lp.y = cy
                    try { w.updateViewLayout(frame, lp) } catch (_: Exception) { }
                    points += MagicHandLogic.trailPoint(lp.x, lp.y, size, e.eventTime)
                    hi?.trail(points)
                    // v0.27.0: once the drag is turning into a circle, show the box it will grab (never while just pointing).
                    val u = _ui.value
                    hi?.live(if (u.mode == MagicHandLogic.Mode.POINT && !u.private && MagicHandLogic.looping(points, density)) MagicHandBox.liveBox(points, dp(24)) else null)
                    // Pointing still reads what's under the flame tip.
                    if (e.eventTime - lastProbe > 90) { lastProbe = e.eventTime; val (tx, ty) = tip(); probe(tx, ty) }
                }
                MotionEvent.ACTION_UP -> {
                    points += MagicHandLogic.trailPoint(lp.x, lp.y, size, e.eventTime)
                    val g = MagicHandLogic.classify(points, density, lastTapUp)
                    lastTapUp = if (g == Gesture.TAP) e.eventTime else 0L
                    val pts = points.toList()
                    hi?.trail(emptyList()); hi?.live(null)
                    onGesture(g, pts)
                }
                MotionEvent.ACTION_CANCEL -> { hi?.trail(emptyList()); hi?.live(null) }
            }
            true
        }
        try { w.addView(frame, lp); hand = frame; handLp = lp; handOwner = owner }
        catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("Magic Hand pointer", e); owner.destroy() }
    }

    private fun tip(): Pair<Int, Int> { val lp = handLp ?: return 0 to 0; return (lp.x + tipX).toInt() to (lp.y + tipY).toInt() }

    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        try { val pm = (svc ?: BlazeAccessibilityService.instance)!!.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
        catch (_: Throwable) { pkg.substringAfterLast('.') }
    }

    /** What's under the tip: highlight it and say what it is. */
    /** What's under the tip, read off the UI thread (the accessibility tree is many binder calls), newest only, ≤ 800 ms. */
    private fun probe(x: Int, y: Int, then: (() -> Unit)? = null) {
        val s = svc ?: return
        probeJob?.cancel()
        probeJob = scope?.launch {
            val h = withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeoutOrNull(800) { try { MagicHandScreen.at(s, x, y) } catch (_: Exception) { null } }
            }
            val app = if (h != null) withContext(Dispatchers.Default) { appLabel(h.pkg) } else ""
            lastHit = h
            lit.value = h != null
            hi?.box(h?.box)
            if (h != null) set { MagicHandState.point(it, if (h.kind == MagicHandLogic.Kind.SECRET) "Password field" else h.element.label, h.kind, app) }
            then?.invoke()
        }
    }

    // ---------- gestures ----------
    private fun onGesture(g: Gesture, pts: List<MagicHandLogic.P>) {
        if (g == Gesture.NONE || g == Gesture.TAP) return
        val (x, y) = tip()
        probe(x, y) { act(g, pts) }
    }

    private fun act(g: Gesture, pts: List<MagicHandLogic.P>) {
        val h = lastHit
        val u0 = _ui.value
        set { it.copy(private = isPrivate()) }
        val u = _ui.value
        if (u0.panel == Panel.TEACH_REVIEW) return
        val kind = h?.kind ?: MagicHandLogic.Kind.EMPTY
        when (MagicHandLogic.intentFor(g, u.mode, u.carrying, kind)) {
            HandIntent.NONE, HandIntent.HIGHLIGHT -> Unit
            HandIntent.MENU -> if (h == null && u.private) set { MagicHandState.back(it, MagicHandGuard.PRIVATE_NOTE) }
                else set { MagicHandState.menu(it, h?.element?.label.orEmpty(), kind, h?.pkg?.let(::appLabel).orEmpty()) }
            HandIntent.DROP -> { pendingHit = h; set { MagicHandState.confirm(it, HandIntent.DROP, h?.element?.label.orEmpty().ifBlank { "this box" }) } }
            HandIntent.WATCH -> askWatch(h)
            HandIntent.GRAB -> grab(pts)
            HandIntent.TEACH_TAP -> { pendingHit = h; set { MagicHandState.confirm(it, HandIntent.TEACH_TAP, h?.element?.label.orEmpty()) } }
        }
    }

    private fun askWatch(h: MagicHandScreen.Hit?) {
        if (!MagicHandGuard.canWatch(isPrivate())) { set { MagicHandState.back(it, MagicHandGuard.PRIVATE_NOTE) }; return }
        if (h == null) { set { MagicHandState.back(it, MagicHandState.NOTHING) }; return }
        pendingHit = h
        set { MagicHandState.confirm(it, HandIntent.WATCH, h.element.label) }
    }

    // ---------- dock ----------
    private fun onDockButton(b: DockButton) {
        when (b) {
            DockButton.GRAB -> grabMode(!_ui.value.grabMode)
            DockButton.CLOSE -> stop()
            DockButton.TEACH -> {
                if (!MagicHandGuard.canTeach(isPrivate())) { set { MagicHandState.back(it.copy(private = true), MagicHandGuard.PRIVATE_NOTE) }; return }
                // v0.25.0: explain first; recording starts on Start.
                set { MagicHandState.intro(it, !com.blaze.assistant.agent.Settings.bool("mh_teach_intro_seen", false)) }
            }
            DockButton.DONE -> {
                val steps = recorder?.steps.orEmpty()
                if (steps.none { it.kind == MagicHandTeach.Kind.TAP || it.kind == MagicHandTeach.Kind.TYPE }) {
                    recorder = null; set { MagicHandState.endTeach(it, "Nothing to save: I didn't see any taps or typing.") }
                } else set { MagicHandState.review(it, teachLines(), MagicHandTeach.toWorkflow(steps, "", "").name) }
            }
            DockButton.DROP_CHAT -> dropInChat()
            DockButton.CAMERA -> {
                // The hand would sit over the shutter: close it, then open Blaze Camera (last mode used).
                val c = svc?.applicationContext
                stop()
                if (c != null) com.blaze.assistant.device.BlazeCamera.open(c, null, com.blaze.assistant.device.CameraModes.Origin.MAGIC_HAND)
            }
        }
    }

    private fun teachLines(): List<String> = recorder?.steps.orEmpty().map(MagicHandTeach::line)

    private fun saveTeach() {
        val rec = recorder ?: return
        if (isPrivate()) { recorder = null; set { MagicHandState.endTeach(it, MagicHandGuard.PRIVATE_NOTE) }; return }
        val d = MagicHandTeach.toWorkflow(rec.steps, _ui.value.teachName, "")
        scope?.launch {
            val r = withContext(Dispatchers.IO) {
                try { com.blaze.assistant.agent.SkillPacks.saveWorkflow(d.name, d.trigger, d.steps, d.checks, d.tags, tested = false) }
                catch (e: Exception) { "Couldn't save the workflow: ${e.message}" }
            }
            recorder = null
            set { MagicHandState.endTeach(it, if (r.startsWith("Workflow")) "Saved \u201c${d.name}\u201d. Blaze will follow it with you once before trusting it." else r) }
        }
    }

    private fun text(h: MagicHandScreen.Hit?): String = h?.element?.let { it.text.ifBlank { it.desc } }.orEmpty()

    private fun onMenu(a: Action) {
        val ctx = svc ?: return
        val h = lastHit
        val priv = isPrivate()
        if (!MagicHandGuard.allowed(a, priv)) { set { MagicHandState.back(it.copy(private = true), MagicHandGuard.PRIVATE_NOTE) }; return }
        val t = text(h)
        when (a) {
            Action.ASK, Action.EXPLAIN, Action.DESCRIBE, Action.TRANSLATE, Action.REMIND -> ask(a, h)
            Action.COPY -> { PointerActions.copy(ctx, "Text", t); set { MagicHandState.back(it, "Copied.") } }
            Action.SHARE -> { set { MagicHandState.back(it) }; PointerActions.share(ctx, t) }
            Action.SEARCH -> { set { MagicHandState.back(it) }; open(MagicHandState.searchUrl(t)) }
            Action.OPEN_LINK -> { set { MagicHandState.back(it) }; open(h?.element?.url?.ifBlank { t } ?: t) }
            Action.COPY_LINK -> { PointerActions.copy(ctx, "Link", h?.element?.url?.ifBlank { t } ?: t); set { MagicHandState.back(it, "Link copied.") } }
            Action.SAVE_IMAGE -> saveImage(h)
            Action.CARRY -> if (t.isBlank()) set { MagicHandState.back(it, MagicHandState.NOTHING) } else set { MagicHandState.carry(it, t) }
            Action.WATCH -> askWatch(h)
            Action.DROP -> { pendingHit = h; set { MagicHandState.confirm(it, HandIntent.DROP, h?.element?.label.orEmpty().ifBlank { "this box" }) } }
            Action.DROP_IN_CHAT -> dropInChat()
        }
    }

    private fun open(url: String) {
        val s = svc ?: return
        val u = url.trim().let { if (it.startsWith("www.")) "https://$it" else it }
        if (!u.startsWith("http")) return
        try { s.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    /**
     * Hides Magic Hand's windows, waits for the frame, runs [block], shows them again. v0.28.1: with [area] only the
     * windows over that area are hidden (the dock and the new grab card stay on screen while the text is read), and
     * the wait is two frames instead of 160 ms.
     */
    private suspend fun <T> hidden(area: MagicHandLogic.Box? = null, block: suspend () -> T): T {
        val views = listOfNotNull(hand, hi, dock, boxView).filter { v -> area == null || v === boxView || v === hi || overlaps(v, area) }
        val was = views.map { it.visibility }
        val any = views.any { it.visibility == View.VISIBLE }
        views.forEach { it.visibility = View.INVISIBLE }
        if (any) delay(if (area == null) 160 else 40)
        try { return block() } finally { views.forEachIndexed { i, v -> v.visibility = was[i] } }
    }

    private fun overlaps(v: View, a: MagicHandLogic.Box): Boolean {
        if (v.visibility != View.VISIBLE || v.width <= 0) return false
        val xy = IntArray(2); v.getLocationOnScreen(xy)
        return xy[0] < a.r && xy[0] + v.width > a.l && xy[1] < a.b && xy[1] + v.height > a.t
    }

    /** v0.28.1: a full screenshot for reading [area], hiding only what covers it. Null in Private / before Android 11. */
    private suspend fun shotFor(area: MagicHandLogic.Box): android.graphics.Bitmap? {
        val s = svc ?: return null
        if (!MagicHandGuard.canScreenshot(isPrivate())) return null
        val m = dp(MagicHandOcr.MARGIN_DP)
        return hidden(MagicHandLogic.Box(area.l - m, area.t - m, area.r + m, area.b + m)) { kotlinx.coroutines.withTimeoutOrNull(3_000) { MagicHandScreen.screenshot(s) } }
    }

    private suspend fun crop(b: MagicHandLogic.Box, around: Boolean): android.graphics.Bitmap? {
        val s = svc ?: return null
        if (!MagicHandGuard.canScreenshot(isPrivate())) return null
        val full = hidden { kotlinx.coroutines.withTimeoutOrNull(3_000) { MagicHandScreen.screenshot(s) } } ?: return null
        val (sw, sh) = full.width to full.height
        val box = if (around) MagicHandLogic.cropAround(b, sw, sh, (24 * density).toInt(), 1200) else b
        return withContext(Dispatchers.Default) { MagicHandScreen.crop(full, box) }
    }

    /** Point & ask: the element's words and a crop of the screen around it go to Blaze; the answer shows in the dock. */
    private fun ask(a: Action, h: MagicHandScreen.Hit?, custom: String? = null) {
        if (Agent.mood.value != Mood.IDLE) { set { MagicHandState.back(it, MagicHandState.BUSY) }; return }
        val app = h?.pkg?.let(::appLabel).orEmpty()
        val q = custom ?: MagicHandLogic.prompt(a, text(h), app)
        set { MagicHandState.asking(it, custom ?: text(h)) }
        askJob?.cancel()
        askJob = scope?.launch {
            val att = if (custom == null && h != null && a != Action.TRANSLATE && a != Action.REMIND) crop(h.box, around = true)?.let { bmp ->
                val url = withContext(Dispatchers.Default) { MagicHandScreen.dataUrl(bmp) }
                Attachment("magic-hand.jpg", listOf(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))), null)
            } else null
            if (!on || isPrivate()) { set { MagicHandState.back(it, MagicHandGuard.PRIVATE_NOTE) }; return@launch }
            val before = Agent.messages.value.lastOrNull()?.id ?: Long.MIN_VALUE
            askedAgent = true
            Agent.send(q, byVoice = false, attachments = listOfNotNull(att), untrusted = com.blaze.assistant.agent.ConfirmGate.MAGIC_HAND)
            val t0 = SystemClock.uptimeMillis()
            var seenBusy = false
            while (on && SystemClock.uptimeMillis() - t0 < 180_000) {
                delay(300)
                if (Agent.mood.value != Mood.IDLE) seenBusy = true
                val reply = Agent.messages.value.lastOrNull { it.id > before && (it.role == Role.BLAZE || it.role == Role.ERROR) }
                if (reply != null && reply.text.isNotBlank()) set { MagicHandState.answered(it, reply.text) }
                if ((seenBusy || SystemClock.uptimeMillis() - t0 > 3_000) && Agent.mood.value == Mood.IDLE) {
                    if (reply == null) set { MagicHandState.answered(it, "Blaze didn't answer. Open Blaze to see what happened.") }
                    break
                }
            }
            askedAgent = false
        }
    }

    private fun dropInChat() {
        val c = _ui.value.carried
        if (c.isBlank()) return
        if (!MagicHandGuard.allowed(Action.DROP_IN_CHAT, isPrivate())) { set { MagicHandState.back(it, MagicHandGuard.PRIVATE_NOTE) }; return }
        set { it.copy(carried = "") }
        ask(Action.ASK, null, custom = "Here's something I carried over from another app:\n\n\u201c${c.take(4000)}\u201d")
    }

    private fun saveImage(h: MagicHandScreen.Hit?) {
        val s = svc ?: return
        if (h == null) return
        scope?.launch {
            val bmp = crop(h.box, around = false)
            val ok = bmp != null && withContext(Dispatchers.IO) { saveToPictures(s, bmp) }
            set { MagicHandState.back(it, if (ok) "Saved to Pictures/Blaze." else "Couldn't save that picture.") }
        }
    }

    private fun saveToPictures(ctx: Context, bmp: android.graphics.Bitmap): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT < 29) false else {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "blaze-hand-${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Blaze")
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            uri != null && (ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } ?: false)
        }
    } catch (_: Exception) { false }

    // ---------- circle to grab ----------
    private fun grab(pts: List<MagicHandLogic.P>) {
        val (sw, sh) = screen()
        val b0 = MagicHandLogic.loopBounds(pts, sw, sh, (8 * density).toInt()) ?: return
        grabArea(b0)
    }

    /**
     * v0.27.0: grab what's in [b0] (a circle drawn with the hand, or a circle / drag in Grab mode). The box shows at
     * once (haptic tick), snaps to the text blocks under it (a second tick when it moves; Undo goes back to the drawn
     * shape), and the text is read for the snapped box.
     */
    private fun grabArea(b0: MagicHandLogic.Box) {
        val s = svc ?: return
        if (!MagicHandGuard.canGrab(isPrivate())) { set { MagicHandState.back(it.copy(grabMode = false), MagicHandGuard.PRIVATE_NOTE) }; return }
        val drawn = MagicHandBox.fit(b0, boxBounds(), dp(MagicHandBox.MIN_DP))
        regrabJob?.cancel()
        val gen = ++grabGen
        boxHistory.clear()
        showBox(drawn)
        tick(confirm = true)
        val tips = tipsShown()
        // v0.28.1: the card opens now (shimmer, actions ready); snapping, the screenshot and OCR run in parallel off
        // the main thread and fill it in. Was: "Reading what you grabbed…" until everything had finished.
        val src = grabSource(s)
        set { MagicHandState.reading(it, src).copy(coach = MagicHandBox.showTip(tips)) }
        regrabJob = scope?.launch {
            val around = MagicHandBox.window(drawn, maxOf(drawn.w, drawn.h) / 2)
            val nodesJob = async(Dispatchers.Default) {
                kotlinx.coroutines.withTimeoutOrNull(700) { try { MagicHandScreen.textBoxes(s, around) } catch (_: Exception) { emptyList() } }.orEmpty()
            }
            // The screenshot is taken once, around the drawn box plus room to snap; the snapped box is cropped from it.
            val full = shotFor(around)
            val nodes = nodesJob.await()
            if (gen != grabGen) return@launch
            val snapped = MagicHandBox.snap(drawn, nodes, dp(MagicHandBox.SNAP_PAD_DP), boxBounds(), dp(MagicHandBox.MIN_DP))
            val b = if (MagicHandBox.snapped(drawn, snapped, dp(4))) { boxHistory.push(drawn); showBox(snapped); tick(confirm = false); snapped } else drawn
            if (gen == grabGen) set { if (it.panel == Panel.GRAB) it.copy(canUndo = boxHistory.canUndo) else it }
            val text = readText(s, b, full)
            if (gen != grabGen || grabBox != b) return@launch
            set {
                val g = MagicHandState.grabbed(it, text, src)
                if (g.panel == Panel.GRAB) g.copy(canUndo = boxHistory.canUndo) else g
            }
            if (_ui.value.panel != Panel.GRAB) hideBox() else { bumpTips(tips); runQueued() }
        }
    }

    /** v0.28.1: an action tapped while the text was being read runs as soon as the text is there. */
    private fun runQueued() {
        val q = _ui.value.queued ?: return
        if (_ui.value.panel != Panel.GRAB || _ui.value.grabBusy || _ui.value.grabbed.isBlank()) return
        set { it.copy(queued = null, note = "") }
        onGrab(q)
    }

    /** Grab mode on / off (the dock's Grab button, "Grab again"). The hint is full for the first few times. */
    private fun grabMode(on: Boolean) {
        if (on) {
            val n = hintsShown()
            set { MagicHandState.grabMode(it.copy(private = isPrivate()), true, MagicHandBox.showTip(n)) }
            if (_ui.value.grabMode) com.blaze.assistant.agent.Settings.setStr("mh_grab_hints", (n + 1).coerceAtMost(99).toString())
        } else set { MagicHandState.grabMode(it, false) }
    }

    private fun hintsShown(): Int = com.blaze.assistant.agent.Settings.str("mh_grab_hints", "0").toIntOrNull() ?: 0
    private fun tipsShown(): Int = com.blaze.assistant.agent.Settings.str("mh_grab_tips", "0").toIntOrNull() ?: 0
    private fun bumpTips(n: Int) { if (n <= MagicHandBox.TIPS_TIMES) com.blaze.assistant.agent.Settings.setStr("mh_grab_tips", (n + 1).toString()) }

    /** A light haptic: a confirm on grab, a tick when the box snaps or goes back. */
    private fun tick(confirm: Boolean) {
        val v: View = boxView ?: hand ?: return
        try {
            v.performHapticFeedback(if (confirm && android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
                else android.view.HapticFeedbackConstants.CLOCK_TICK)
        } catch (_: Throwable) { }
    }

    /** Undo: the box goes back to its previous shape (the drawn one before a snap, or before a move / resize). */
    private fun undoBox() {
        if (_ui.value.panel != Panel.GRAB) return
        val prev = boxHistory.undo() ?: return
        showBox(prev)
        tick(confirm = false)
        set { it.copy(canUndo = boxHistory.canUndo) }
        reread(prev)
    }

    // ---------- Grab mode's drawing layer (v0.27.0) ----------
    /** Added once at start, under the box, the dock and the hand; takes touches only while Grab mode is on. */
    private fun addDraw(s: Context, w: WindowManager) {
        val v = DrawLayer(s)
        v.visibility = View.GONE
        v.onTouch = ::onDrawTouch
        v.contentDescription = MagicHandState.GRAB_MODE_HINT
        val lp = WindowManager.LayoutParams(1, 1, type(), MagicHandSafety.flags(MagicHandSafety.Win.DRAW, active = false),
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        try { w.addView(v, lp); drawView = v; drawLp = lp } catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("Magic Hand grab layer", e) }
    }

    /** The layer covers the screen above the navigation bar while Grab is on (never the bar itself). */
    private fun drawActive(on: Boolean) {
        val v = drawView ?: return; val lp = drawLp ?: return
        drawPts.clear(); hi?.trail(emptyList()); hi?.live(null)
        if (on) { val (sw, sh) = screen(); lp.width = sw; lp.height = (sh - bottomInset).coerceAtLeast(1) } else { lp.width = 1; lp.height = 1 }
        lp.flags = MagicHandSafety.flags(MagicHandSafety.Win.DRAW, active = on)
        v.visibility = if (on) View.VISIBLE else View.GONE
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    /** Grab mode: draw a circle or drag a rectangle with a finger; a live box follows; lift to grab. A tap turns Grab off. */
    private fun onDrawTouch(e: MotionEvent): Boolean {
        if (!_ui.value.grabMode) return false
        touchAt = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { drawPts.clear(); drawPts += MagicHandLogic.P(e.rawX, e.rawY, e.eventTime) }
            MotionEvent.ACTION_MOVE -> {
                drawPts += MagicHandLogic.P(e.rawX, e.rawY, e.eventTime)
                hi?.trail(drawPts)
                hi?.live(MagicHandBox.liveBox(drawPts, dp(24)))
            }
            MotionEvent.ACTION_UP -> {
                drawPts += MagicHandLogic.P(e.rawX, e.rawY, e.eventTime)
                val pts = drawPts.toList(); drawPts.clear()
                hi?.trail(emptyList()); hi?.live(null)
                val (sw, sh) = screen()
                val b0 = MagicHandLogic.loopBounds(pts, sw, sh, dp(4), minPx = dp(24))
                if (b0 == null) set { MagicHandState.grabMode(it, false).copy(note = "Grab is off. Tap Grab to try again.") }
                else grabArea(b0)
            }
            MotionEvent.ACTION_CANCEL -> { drawPts.clear(); hi?.trail(emptyList()); hi?.live(null) }
        }
        return true
    }

    private fun grabSource(s: BlazeAccessibilityService): String = lastHit?.pkg?.let(::appLabel) ?: s.currentPkg().let(::appLabel)

    /**
     * The words inside [b]: OCR of a screenshot (Magic Hand's windows over it hidden), else the accessibility text.
     * v0.28.1: the crop has a margin and lines mostly outside [b] are dropped; the OCR runs on Dispatchers.Default
     * on the warm recogniser, downscaled. [full] reuses a screenshot already taken for this grab.
     */
    private suspend fun readText(s: BlazeAccessibilityService, b: MagicHandLogic.Box, full: android.graphics.Bitmap? = null): String {
        val shot = full ?: shotFor(b)
        val ocr = if (shot != null) withContext(Dispatchers.Default) {
            kotlinx.coroutines.withTimeoutOrNull(8_000) { MagicHandScreen.readRegion(shot, b, dp(MagicHandOcr.MARGIN_DP)) }.orEmpty()
        } else ""
        return ocr.ifBlank { withContext(Dispatchers.Default) { kotlinx.coroutines.withTimeoutOrNull(2_000) { try { MagicHandScreen.textIn(s, b) } catch (_: Exception) { "" } }.orEmpty() } }
    }

    // ---------- the grab box: move it, resize it, read again (v0.26.0) ----------
    private fun dp(n: Int): Int = (n * density).toInt()
    private fun boxBounds(): MagicHandBox.Bounds { val (sw, sh) = screen(); return MagicHandBox.bounds(sw, sh, topInset, bottomInset) }

    /** Added once at start, under the dock and the hand (so both stay on top), taking no touches until a grab. */
    private fun addBox(s: Context, w: WindowManager) {
        val v = BoxFrame(s, density)
        v.visibility = View.GONE
        v.onTouch = ::onBoxTouch
        val lp = WindowManager.LayoutParams(1, 1, type(), MagicHandSafety.flags(MagicHandSafety.Win.BOX, active = false),
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        try { w.addView(v, lp); boxView = v; boxLp = lp } catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("Magic Hand grab box", e) }
    }

    private fun showBox(b: MagicHandLogic.Box) {
        grabBox = b
        val v = boxView; val lp = boxLp
        if (v == null || lp == null) { hi?.box(b); return }   // no box window: the plain outline, as before
        hi?.box(null)
        placeBox(b, active = true)
        v.box(b)
        if (v.visibility != View.VISIBLE) {
            // v0.27.0: the box fades in (instant with Remove animations on).
            v.alpha = 0f; v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(com.blaze.assistant.ui.MotionTokens.ms(com.blaze.assistant.ui.MotionTokens.SHORT)).start()
        }
    }

    private fun placeBox(b: MagicHandLogic.Box, active: Boolean) {
        val v = boxView ?: return; val lp = boxLp ?: return
        val win = MagicHandBox.window(b, dp(MagicHandBox.PAD_DP))
        lp.x = win.l; lp.y = win.t; lp.width = win.w; lp.height = win.h
        lp.flags = MagicHandSafety.flags(MagicHandSafety.Win.BOX, active = active)
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    private fun hideBox() {
        regrabJob?.cancel(); regrabJob = null; grabGen++
        grabBox = null; boxHistory.clear()
        val v = boxView ?: return; val lp = boxLp ?: return
        v.animate().cancel(); v.box(null); v.visibility = View.GONE
        lp.width = 1; lp.height = 1
        lp.flags = MagicHandSafety.flags(MagicHandSafety.Win.BOX, active = false)
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    private var boxStart: MagicHandLogic.Box? = null
    private var boxHandle = MagicHandBox.Handle.NONE
    private var boxDownX = 0f
    private var boxDownY = 0f
    private var boxCancelledRead = false

    /** Drag inside to move, a corner or an edge to resize; the text is read again a moment after the finger lifts. */
    private fun onBoxTouch(e: MotionEvent): Boolean {
        val b = grabBox ?: return false
        touchAt = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                boxHandle = MagicHandBox.hit(b, e.rawX.toInt(), e.rawY.toInt(), dp(MagicHandBox.REACH_DP))
                if (boxHandle == MagicHandBox.Handle.NONE) return false
                boxStart = b; boxDownX = e.rawX; boxDownY = e.rawY
                // A new drag cancels a read still waiting or running for the old rectangle (read again on release).
                boxCancelledRead = regrabJob?.isActive == true
                regrabJob?.cancel(); grabGen++
                boxView?.dragging(boxHandle)
            }
            MotionEvent.ACTION_MOVE -> {
                val st = boxStart ?: return false
                val nb = MagicHandBox.drag(st, boxHandle, (e.rawX - boxDownX).toInt(), (e.rawY - boxDownY).toInt(), boxBounds(), dp(MagicHandBox.MIN_DP))
                if (nb != grabBox) { grabBox = nb; placeBox(nb, active = true); boxView?.box(nb) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val st = boxStart; boxStart = null
                boxView?.dragging(MagicHandBox.Handle.NONE)
                val now = grabBox
                if (st != null && now != null && MagicHandBox.changed(st, now, dp(3))) {
                    boxHistory.push(st)
                    set { it.copy(canUndo = true, coach = false) }
                }
                if (st != null && now != null && (boxCancelledRead || MagicHandBox.changed(st, now, dp(3)))) reread(now)
                boxCancelledRead = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> Unit
        }
        return true
    }

    /** Debounced: read the text in [b] [MagicHandBox.REREAD_DELAY_MS] after the finger lifts, newest box only. */
    private fun reread(b: MagicHandLogic.Box) {
        val s = svc ?: return
        if (!MagicHandGuard.canGrab(isPrivate())) { set { MagicHandState.back(it, MagicHandGuard.PRIVATE_NOTE) }; return }
        regrabJob?.cancel()
        val gen = ++grabGen
        set { MagicHandState.regrabbing(it) }
        regrabJob = scope?.launch {
            delay(MagicHandBox.REREAD_DELAY_MS)
            val text = readText(s, b)
            if (gen != grabGen || grabBox != b) return@launch
            val src = grabSource(s)
            // Still on the card: update it. A drag before the first read finished: this is the first read.
            set { if (it.panel == Panel.GRAB) MagicHandState.regrabbed(it, text, src) else MagicHandState.grabbed(it.copy(note = ""), text, src) }
            if (_ui.value.panel != Panel.GRAB) hideBox() else runQueued()
        }
    }

    private fun onGrab(g0: GrabAction) {
        val s = svc ?: return
        // v0.28.1: tapped while reading = queued, run by runQueued() when the text lands.
        var now: GrabAction? = null
        set { val (n, g) = MagicHandState.tapGrab(it, g0); now = g; n }
        val g = now ?: return
        val t = _ui.value.grabbed
        if (t.isBlank()) return
        when (g) {
            // v0.26.0: Copy keeps the card and the box (fix the box, copy again); X closes.
            GrabAction.COPY -> { PointerActions.copy(s, "Text", t); set { it.copy(note = "Copied.") } }
            GrabAction.TRANSLATE -> ask(Action.TRANSLATE, null, custom = MagicHandLogic.prompt(Action.TRANSLATE, t, _ui.value.grabSource))
            GrabAction.SEARCH -> { set { MagicHandState.back(it) }; open(MagicHandState.searchUrl(t)) }
            GrabAction.SAVE -> {
                if (isPrivate()) { set { MagicHandState.back(it, MagicHandGuard.PRIVATE_NOTE) }; return }
                scope?.launch {
                    val src = _ui.value.grabSource
                    val r = withContext(Dispatchers.IO) { try { com.blaze.assistant.agent.Memory.add((if (src.isNotBlank()) "Saved from $src: " else "Saved: ") + t.take(1500)) } catch (e: Exception) { "Couldn't save: ${e.message}" } }
                    set { MagicHandState.back(it, if (r.startsWith("Not saved") || r.startsWith("Couldn")) r else "Saved to memory.") }
                }
            }
            GrabAction.CARRY -> set { MagicHandState.carry(it, t) }
        }
    }

    // ---------- confirm ----------
    private fun onConfirm(yes: Boolean) {
        val c = _ui.value.confirm ?: return
        val h = pendingHit; pendingHit = null
        if (!yes || h == null) { set { MagicHandState.back(it, "Cancelled. Nothing was done.") }; return }
        when (c.intent) {
            HandIntent.DROP -> set { if (drop(h.node, it.carried)) MagicHandState.dropped(it) else MagicHandState.back(it, "That box didn't take the text. It's on the clipboard; long-press the box and Paste.") }
            HandIntent.WATCH -> {
                val s = svc ?: return
                val value = text(h)
                if (value.isBlank() || isPrivate()) { set { MagicHandState.back(it, MagicHandState.NOTHING) }; return }
                MagicHandWatches.add(s, MagicHandWatch.Watch(java.util.UUID.randomUUID().toString(), h.pkg, appLabel(h.pkg),
                    h.element.desc.ifBlank { h.element.viewId.substringAfterLast('/').replace('_', ' ') }.ifBlank { value }.take(60),
                    h.path, h.element.viewId, h.element.className, value, System.currentTimeMillis()))
                set { MagicHandState.back(it, "Watching \u201c${MagicHandLogic.preview(value)}\u201d. I'll tell you when it changes.") }
            }
            HandIntent.TEACH_TAP -> {
                val s = svc ?: return
                recorder?.click(h.pkg, appLabel(h.pkg), h.element.label, h.element.viewId, h.element.className, SystemClock.uptimeMillis())
                ignoreClicksUntil = SystemClock.uptimeMillis() + 800
                set { MagicHandState.teachSteps(MagicHandState.back(it), teachLines()) }
                scope?.launch { try { s.tapNode(h.node) } catch (_: Exception) { } }
            }
            else -> set { MagicHandState.back(it) }
        }
    }

    /** Puts [carried] into an editable node (only after the user's yes): SET_TEXT, else the clipboard and Paste. */
    private fun drop(node: AccessibilityNodeInfo, carried: String): Boolean {
        if (carried.isBlank() || node.isPassword) return false
        try { node.refresh() } catch (_: Exception) { }
        val hint = android.os.Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
        val existing = if (hint) "" else node.text?.toString().orEmpty()
        val next = if (existing.isBlank()) carried else existing.trimEnd() + " " + carried
        val ok = try { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) }) } catch (_: Exception) { false }
        if (ok) return true
        val s = svc ?: return false
        return try {
            s.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Blaze", carried))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) { false }
    }

    // ---------- accessibility events ----------
    /** From BlazeAccessibilityService: watches re-read when their app comes to the front; teach records the user's own steps. */
    fun onEvent(s: BlazeAccessibilityService, e: AccessibilityEvent) {
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == s.packageName) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            main.removeCallbacks(watchCheck)
            watchPkg = pkg
            main.postDelayed(watchCheck, 1500)
        }
        val rec = recorder ?: return
        if (!on || _ui.value.mode != MagicHandLogic.Mode.TEACH || _ui.value.panel == Panel.TEACH_REVIEW) return
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_VIEW_CLICKED && type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        // v0.25.0: System UI, Android, launchers, screenshot tools, keyboards and Blaze are never part of a routine.
        if (MagicHandSafety.ignoreForTeach(pkg, s.packageName, launchers, imes)) return
        if (isPrivate()) { recorder = null; set { MagicHandState.endTeach(it, MagicHandGuard.PRIVATE_NOTE) }; return }
        val now = SystemClock.uptimeMillis()
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && now < ignoreClicksUntil) return
        // Copy what's cheap now (the event is recycled after this call); anything that needs binder calls runs on the worker.
        val text = e.text?.joinToString(" ")?.trim().orEmpty()
        val desc = e.contentDescription?.toString().orEmpty()
        val cls = e.className?.toString().orEmpty()
        val pw = e.isPassword
        @Suppress("DEPRECATION")
        val copy = if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) null else try { AccessibilityEvent.obtain(e) } catch (_: Throwable) { null }
        worker.execute {
            try {
                val app = appLabel(pkg)
                val src = try { copy?.source } catch (_: Exception) { null }
                when (type) {
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> rec.window(pkg, app, text.take(60), now)
                    AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                        val label = MagicHandSafety.humanLabel(text).ifBlank { MagicHandSafety.humanLabel(desc) }
                            .ifBlank { src?.let { MagicHandSafety.humanLabel(MagicHandScreen.elementOf(it).label) }.orEmpty() }
                        rec.click(pkg, app, label, src?.viewIdResourceName.orEmpty(), cls, now)
                    }
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                        val field = (if (android.os.Build.VERSION.SDK_INT >= 26) src?.hintText?.toString() else null).orEmpty()
                            .ifBlank { src?.contentDescription?.toString().orEmpty() }
                        rec.typed(pkg, app, MagicHandSafety.humanLabel(field), src?.viewIdResourceName.orEmpty(), text, pw || src?.isPassword == true, now)
                    }
                }
                val lines = rec.steps.map(MagicHandTeach::line)
                main.post { if (recorder === rec) set { MagicHandState.teachSteps(it, lines) } }
            } catch (_: Throwable) {
            } finally { recycle(copy) }
        }
    }

    @Suppress("DEPRECATION")
    private fun recycle(e: AccessibilityEvent?) { try { e?.recycle() } catch (_: Throwable) { } }

    private var watchPkg = ""
    private val watchCheck = Runnable {
        val s = BlazeAccessibilityService.instance ?: return@Runnable
        try { if (watchPkg.isNotBlank() && s.currentPkg() == watchPkg) MagicHandWatches.check(s, watchPkg) } catch (_: Exception) { }
    }

    // ---------- views ----------
    /** The hand's window: every touch goes to [onTouch] (the Compose glyph inside only draws). */
    private class HandFrame(ctx: Context, private val d: Float) : FrameLayout(ctx) {
        var onTouch: (MotionEvent) -> Boolean = { false }
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = onTouch(ev)
        // v0.25.0: a dark halo and a light rim drawn natively behind the mascot, so the pointer stands out on any
        // wallpaper (dark ones included) and is visible even before Compose draws its first frame.
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = 0xE6FFFFFF.toInt() }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = 0x994C7DFF.toInt() }
        init { setWillNotDraw(false) }
        override fun dispatchDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - 3f * d
            halo.shader = android.graphics.RadialGradient(cx, cy, r, intArrayOf(0xCC14161C.toInt(), 0x9914161C.toInt(), 0x0014161C), floatArrayOf(0f, 0.75f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, halo)
            c.drawCircle(cx, cy, r - 2f * d, glow)
            c.drawCircle(cx, cy, r - 4.5f * d, rim)
            super.dispatchDraw(c)
        }
    }

    /** The dock's window root: long-press closes Magic Hand, Back gives the keyboard back, outside touches pass through. */
    private class DockFrame(ctx: Context) : FrameLayout(ctx) {
        var onLongPress: () -> Unit = {}
        var onBack: () -> Unit = {}
        var onOutside: () -> Unit = {}
        var onAnyTouch: () -> Unit = {}
        private val longPress = Runnable { onLongPress() }
        private var downX = 0f; private var downY = 0f
        private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> { onOutside(); return false }
                MotionEvent.ACTION_DOWN -> { onAnyTouch(); downX = ev.x; downY = ev.y; postDelayed(longPress, 650) }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(ev.x - downX) > slop || kotlin.math.abs(ev.y - downY) > slop) removeCallbacks(longPress)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> removeCallbacks(longPress)
            }
            return super.dispatchTouchEvent(ev)
        }
        override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
            if (e.keyCode == android.view.KeyEvent.KEYCODE_BACK) { if (e.action == android.view.KeyEvent.ACTION_UP) onBack(); return true }
            return super.dispatchKeyEvent(e)
        }
    }

    /**
     * v0.26.0: the grab box's window (the box plus a margin for its handles). Draws the blue rectangle with corner and
     * edge handles; every touch goes to [onTouch] in screen coordinates (rawX / rawY), so moving the window while
     * dragging never makes the box jump.
     */
    private class BoxFrame(ctx: Context, private val d: Float) : View(ctx) {
        var onTouch: (MotionEvent) -> Boolean = { false }
        private var b: MagicHandLogic.Box? = null
        private var active = MagicHandBox.Handle.NONE
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = 0xFF4C7DFF.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x224C7DFF }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
        private val knobRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = 0xFF4C7DFF.toInt() }
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND; color = 0xFFFFFFFF.toInt() }
        init { contentDescription = "Grabbed area. Drag to move it, drag a corner to resize it." }
        fun box(n: MagicHandLogic.Box?) { b = n; invalidate() }
        fun dragging(h: MagicHandBox.Handle) { active = h; invalidate() }
        @SuppressLint("ClickableViewAccessibility")
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = onTouch(ev)
        override fun onDraw(c: Canvas) {
            val r = b ?: return
            val loc = IntArray(2); getLocationOnScreen(loc)
            c.save(); c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            val rf = RectF(r.l.toFloat(), r.t.toFloat(), r.r.toFloat(), r.b.toFloat())
            fill.color = if (active == MagicHandBox.Handle.MOVE) 0x334C7DFF else 0x224C7DFF
            c.drawRoundRect(rf, 12 * d, 12 * d, fill); c.drawRoundRect(rf, 12 * d, 12 * d, stroke)
            // Edge bars (drag an edge) and corner knobs (drag a corner).
            val half = minOf(10 * d, rf.width() / 6, rf.height() / 6)
            c.drawLine(rf.centerX() - half, rf.top, rf.centerX() + half, rf.top, bar)
            c.drawLine(rf.centerX() - half, rf.bottom, rf.centerX() + half, rf.bottom, bar)
            c.drawLine(rf.left, rf.centerY() - half, rf.left, rf.centerY() + half, bar)
            c.drawLine(rf.right, rf.centerY() - half, rf.right, rf.centerY() + half, bar)
            val kr = 9f * d   // v0.27.0: bigger corner knobs (the touch target around each is 48 dp)
            listOf(rf.left to rf.top, rf.right to rf.top, rf.left to rf.bottom, rf.right to rf.bottom).forEach { (x, y) ->
                c.drawCircle(x, y, kr, knob); c.drawCircle(x, y, kr, knobRim)
            }
            c.restore()
        }
    }

    /** v0.27.0: Grab mode's drawing layer. Draws nothing itself (the highlight shows the trail and the live box). */
    private class DrawLayer(ctx: Context) : View(ctx) {
        var onTouch: (MotionEvent) -> Boolean = { false }
        @SuppressLint("ClickableViewAccessibility")
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = onTouch(ev)
    }

    /** Full-screen, not touchable: a rounded outline around what's under the tip and the trail of a circle being drawn. */
    private class HighlightView(ctx: Context, private val d: Float) : View(ctx) {
        private var b: MagicHandLogic.Box? = null
        private val path = Path()
        private var trailOn = false
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = 0xFF4C7DFF.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x224C7DFF }
        private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = 0xCC4C7DFF.toInt(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        fun box(n: MagicHandLogic.Box?) { if (n != b) { b = n; invalidate() } }
        // v0.27.0: the live box while drawing (dashed, follows the drawn shape's bounds).
        private var liveBox: MagicHandLogic.Box? = null
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = 0xFF4C7DFF.toInt()
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * d, 6f * d), 0f) }
        private val liveFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x184C7DFF }
        fun live(n: MagicHandLogic.Box?) { if (n != liveBox) { liveBox = n; invalidate() } }
        fun trail(pts: List<MagicHandLogic.P>) {
            path.reset(); trailOn = pts.size > 2
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            invalidate()
        }
        override fun onDraw(c: Canvas) {
            val loc = IntArray(2); getLocationOnScreen(loc)
            c.save(); c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            b?.let { r -> val rf = RectF(r.l - 3 * d, r.t - 3 * d, r.r + 3 * d, r.b + 3 * d); c.drawRoundRect(rf, 12 * d, 12 * d, fill); c.drawRoundRect(rf, 12 * d, 12 * d, stroke) }
            liveBox?.let { r -> val rf = RectF(r.l.toFloat(), r.t.toFloat(), r.r.toFloat(), r.b.toFloat()); c.drawRoundRect(rf, 12 * d, 12 * d, liveFill); c.drawRoundRect(rf, 12 * d, 12 * d, dash) }
            if (trailOn) c.drawPath(path, trail)
            c.restore()
        }
    }
}
