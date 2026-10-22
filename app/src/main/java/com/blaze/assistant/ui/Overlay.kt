package com.blaze.assistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.blaze.assistant.MainActivity
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Role
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.assist.BlazeInteractionService
import com.blaze.assistant.phone.BlazeAccessibilityService
import com.blaze.assistant.voice.Voice
import androidx.compose.foundation.shape.RoundedCornerShape

/** Lets a ComposeView live outside an Activity (assistant session window, floating orb). */
class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val reg = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun start() { saved.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
    fun destroy() { reg.currentState = Lifecycle.State.DESTROYED; store.clear() }

    fun attach(v: View) {
        v.setViewTreeLifecycleOwner(this)
        v.setViewTreeSavedStateRegistryOwner(this)
        v.setViewTreeViewModelStoreOwner(this)
    }
}

fun composeView(ctx: Context, owner: OverlayOwner, content: @androidx.compose.runtime.Composable () -> Unit): ComposeView =
    ComposeView(ctx).apply { owner.attach(this); setContent { BlazeTheme { content() } } }

/** The floating orb (and, without the assistant role, the overlay chat panel). */
object Overlay {
    private var orbView: View? = null
    private var orbOwner: OverlayOwner? = null
    private var panelView: View? = null
    private var panelOwner: OverlayOwner? = null

    fun canDrawOverApps(ctx: Context) = AndroidSettings.canDrawOverlays(ctx)

    /** A context + window type that may draw over other apps, or null. */
    private fun host(ctx: Context): Pair<Context, Int>? {
        BlazeAccessibilityService.instance?.let { return it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
        if (canDrawOverApps(ctx)) return ctx.applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return null
    }

    val orbShowing get() = orbView != null

    // Drag-to-bin state, read by the orb and bin composables.
    private var restoreOrb: (() -> Unit)? = null
    private val binHot = mutableStateOf(false)
    private val binShown = mutableStateOf(false)
    private val orbDying = mutableStateOf(false)
    private var binView: View? = null
    private var binOwner: OverlayOwner? = null

    private fun screen(wm: WindowManager, ctx: Context): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else ctx.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    fun showOrb(ctx: Context): Boolean {
        // v0.43.0: nothing floats over other apps while Bank mode is on.
        if (com.blaze.assistant.bank.BankMode.active) return false
        if (orbView != null) return true
        val (hc, type) = host(ctx) ?: return false
        val wm = hc.getSystemService(WindowManager::class.java)
        val d = hc.resources.displayMetrics.density
        val orbPx = (64 * d).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (w, h) = screen(wm, hc)
            x = Settings.orbX.takeIf { it >= 0 } ?: (w - (72 * d).toInt())
            y = Settings.orbY.takeIf { it >= 0 } ?: (h / 2)
        }
        var rawX = lp.x.toFloat(); var rawY = lp.y.toFloat()
        orbDying.value = false
        val owner = OverlayOwner().also { it.start() }
        val v = composeView(hc, owner) {
            Orb(
                dying = orbDying.value,
                onDragStart = { rawX = lp.x.toFloat(); rawY = lp.y.toFloat(); showBin(hc, type) },
                onDrag = { dx, dy ->
                    val (w, h) = screen(wm, hc)
                    rawX = (rawX + dx).coerceIn(0f, (w - orbPx).toFloat())
                    rawY = (rawY + dy).coerceIn(0f, (h - orbPx).toFloat())
                    // The bin sits centred near the bottom; within reach, the orb snaps into it.
                    val bx = w / 2f; val by = h - 110 * d
                    val hot = Math.hypot((rawX + orbPx / 2f - bx).toDouble(), (rawY + orbPx / 2f - by).toDouble()) < 110 * d
                    binHot.value = hot
                    if (hot) { lp.x = (bx - orbPx / 2f).toInt(); lp.y = (by - orbPx / 2f).toInt() }
                    else { lp.x = rawX.toInt(); lp.y = rawY.toInt() }
                    orbView?.let { runCatching { wm.updateViewLayout(it, lp) } }
                },
                onDragEnd = {
                    if (binHot.value) {
                        orbDying.value = true   // scatter, then the orb removes itself
                    } else {
                        Settings.orbX = lp.x; Settings.orbY = lp.y
                        hideBin()
                    }
                },
                onGone = { hideBin(); hideOrb(ctx) },
                onTap = { openPanel(ctx, listen = false) },
                onLongPress = { openPanel(ctx, listen = true) }
            )
        }
        return try {
            wm.addView(v, lp); orbView = v; orbOwner = owner; Settings.orbPinned = true; Agent.overlayOpen = true; true
        } catch (_: Exception) { owner.destroy(); false }
    }

    private fun showBin(hc: Context, type: Int) {
        binHot.value = false
        if (binView != null) { binShown.value = true; return }
        val wm = hc.getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, (220 * hc.resources.displayMetrics.density).toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        val owner = OverlayOwner().also { it.start() }
        val v = composeView(hc, owner) { ParticleBin(binShown.value, binHot.value) }
        try { wm.addView(v, lp); binView = v; binOwner = owner; binShown.value = true } catch (_: Exception) { owner.destroy() }
    }

    private fun hideBin() {
        binShown.value = false
        val v = binView ?: return
        val o = binOwner
        binView = null; binOwner = null
        // Let the bin fade out before removing its window.
        v.postDelayed({
            runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
            o?.destroy()
        }, 260)
    }

    fun hideOrb(ctx: Context, unpin: Boolean = true) {
        orbView?.let { v -> runCatching { (v.context.getSystemService(WindowManager::class.java)).removeView(v) } }
        orbOwner?.destroy()
        orbView = null; orbOwner = null
        orbDying.value = false
        Agent.overlayOpen = panelView != null
        if (unpin) Settings.orbPinned = false
    }

    // ---------- Live task card ----------

    private var cardView: View? = null
    private var cardOwner: OverlayOwner? = null
    private var cardWatch: kotlinx.coroutines.Job? = null
    /** The banner shows until this time; a swipe sets it to 0 (minimised into the notification). */
    @Volatile private var cardUntil = 0L

    /**
     * While Blaze works and you're in another app, a slim card pops up at the top for a few seconds
     * (step + Stop), then hides; the notification shade keeps the progress. Tap it to open the panel.
     */
    fun watchTasks(ctx: Context) {
        if (cardWatch != null) return
        val app = ctx.applicationContext
        cardWatch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + com.blaze.assistant.device.CrashLog.guard).launch {
            var wasBusy = false; var wasFg = Agent.inForeground
            while (true) {
                val busy = Agent.busyChats.value.isNotEmpty()
                val now = System.currentTimeMillis()
                // Pop up briefly when a task starts (or you leave Blaze mid-task), then get out of the way.
                // The ongoing notification keeps showing progress and Stop.
                if (busy && (!wasBusy || (wasFg && !Agent.inForeground))) {
                    cardUntil = now + 3500
                    if (!Agent.inForeground) com.blaze.assistant.notify.Notifier.working(Agent.lastStep())
                }
                if (!busy && wasBusy) cardUntil = 0L
                wasBusy = busy; wasFg = Agent.inForeground
                val want = busy && Settings.taskCard && !Agent.inForeground && panelView == null && canDrawOverApps(app) && now < cardUntil
                if (want && cardView == null) showCard(app) else if (!want && cardView != null) hideCard()
                kotlinx.coroutines.delay(400)
            }
        }
    }

    private fun showCard(ctx: Context) {
        val wm = ctx.getSystemService(WindowManager::class.java)
        // v0.24.5: the window is only as wide as the card (12 dp margins), so touches beside it reach the app below.
        val dm = ctx.resources.displayMetrics
        val lp = WindowManager.LayoutParams((dm.widthPixels - 24 * dm.density).toInt().coerceAtMost((520 * dm.density).toInt()), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (36 * dm.density).toInt() }
        val owner = OverlayOwner().also { it.start() }
        val v = composeView(ctx, owner) {
            TaskCard(onOpen = { cardUntil = 0L; hideCard(); openApp(ctx, false) }, onStop = { Agent.stopAll() },
                onMinimise = { cardUntil = 0L; hideCard(); com.blaze.assistant.notify.Notifier.working(Agent.lastStep()) })
        }
        try { wm.addView(v, lp); cardView = v; cardOwner = owner } catch (_: Exception) { owner.destroy() }
    }

    private fun hideCard() {
        cardView?.let { v -> runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) } }
        cardOwner?.destroy(); cardView = null; cardOwner = null
    }

    /** Open the chat panel over the current app: the assistant session if Blaze is the assistant, else an overlay. */
    fun openPanel(ctx: Context, listen: Boolean) {
        com.blaze.assistant.agent.LlmClient.prewarm()
        if (BlazeInteractionService.showPanel(listen)) return
        if (panelView != null) return
        val h = host(ctx)
        // Typing needs a window the keyboard can reach; the app-overlay type supports that.
        val type = if (canDrawOverApps(ctx)) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else h?.second
        val hc = if (canDrawOverApps(ctx)) ctx.applicationContext else h?.first
        if (type == null || hc == null) { openApp(ctx, listen); return }
        val wm = hc.getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.25f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val owner = OverlayOwner().also { it.start() }
        val v = composeView(hc, owner) {
            AssistPanel(
                autoListen = listen,
                onClose = { closePanel() },
                onPin = { closePanel(); showOrb(ctx) },
                onExpand = { closePanel(); openApp(ctx, false) }
            )
        }
        // Tuck the orb away while the panel is open so it doesn't sit on top of it.
        val hadOrb = orbView != null
        if (hadOrb) hideOrb(ctx, unpin = false)
        restoreOrb = if (hadOrb) ({ showOrb(ctx) }) else null
        try { wm.addView(v, lp); panelView = v; panelOwner = owner; Agent.overlayOpen = true }
        catch (_: Exception) { owner.destroy(); openApp(ctx, listen) }
    }

    fun closePanel() {
        panelView?.let { v -> runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) } }
        panelOwner?.destroy()
        panelView = null; panelOwner = null
        Agent.overlayOpen = orbView != null
        restoreOrb?.let { r -> restoreOrb = null; if (Settings.orbPinned) r() }
    }

    fun openApp(ctx: Context, listen: Boolean) {
        ctx.startActivity(com.blaze.assistant.IntentKey.sign(ctx, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_LISTEN, listen)))
    }

    fun requestOverlayPermission(ctx: Context) {
        ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ---------------- UI ----------------

/** The round Blaze orb: pixel B in the middle, a ring that breathes while listening or working. */
@Composable
fun OrbFace(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    val mood by Agent.mood.collectAsState()
    val voice by Voice.state.collectAsState()
    val level by Voice.level.collectAsState()
    val active = mood != Mood.IDLE || voice != Voice.State.IDLE
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // Idle orb is a still picture: no animation frames while it just sits on screen.
        if (active) OrbRing(size, level) else Canvas(Modifier.size(size)) {
            drawCircle(c.accent.copy(alpha = 0.10f), radius = this.size.minDimension / 2f * 0.92f)
        }
        Box(Modifier.size(size * 0.66f).clip(CircleShape).background(c.ink), contentAlignment = Alignment.Center) {
            BrandMark(mood, size * 0.44f)
        }
    }
}

@Composable
private fun OrbRing(size: androidx.compose.ui.unit.Dp, level: Float) {
    val c = LocalBlaze.current
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0.9f, 1.05f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "b")
    val lv by animateFloatAsState(level, label = "lv")
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(c.accent.copy(alpha = 0.18f + lv * 0.3f), radius = r * breathe)
        drawArc(c.accent, spin, 110f, false, style = Stroke(width = r * 0.08f),
            topLeft = Offset(r * 0.1f, r * 0.1f), size = androidx.compose.ui.geometry.Size(r * 1.8f, r * 1.8f))
    }
}

@Composable
private fun Orb(
    dying: Boolean, onDragStart: () -> Unit, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit,
    onGone: () -> Unit, onTap: () -> Unit, onLongPress: () -> Unit
) {
    if (dying) { Scatter(64.dp, onGone); return }
    OrbFace(64.dp, Modifier
        .pointerInput(Unit) {
            detectDragGestures(onDragStart = { onDragStart() }, onDragEnd = onDragEnd, onDragCancel = onDragEnd) { ch, d -> ch.consume(); onDrag(d.x, d.y) }
        }
        .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() }) })
}

/** The orb bursting into Blaze's pixel particles, then gone. */
@Composable
private fun Scatter(size: androidx.compose.ui.unit.Dp, onGone: () -> Unit) {
    val c = LocalBlaze.current
    val p = remember { androidx.compose.animation.core.Animatable(0f) }
    val bits = remember {
        val r = kotlin.random.Random(System.nanoTime())
        List(36) { Triple(r.nextFloat() * 6.283f, 0.35f + r.nextFloat() * 0.65f, r.nextInt(5)) }
    }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(520)); onGone() }
    Canvas(Modifier.size(size)) {
        val cx = this.size.width / 2f; val cy = this.size.height / 2f
        val t = p.value
        val px = this.size.width / 14f
        bits.forEach { (ang, sp, k) ->
            val dist = this.size.width * 0.9f * sp * t
            val col = if (k == 0) c.accent else c.ink
            drawCircle(col.copy(alpha = (1f - t).coerceIn(0f, 1f)), px * (1f - t * 0.5f) / 2f,
                Offset(cx + kotlin.math.cos(ang) * dist, cy + kotlin.math.sin(ang) * dist))
        }
        if (t < 0.25f) drawCircle(c.accent.copy(alpha = 0.3f * (1f - t * 4f)), radius = this.size.width / 2f * (1f + t))
    }
}

/**
 * The bin that rises while you drag the orb: a ring of drifting pixel particles with a pixel "×".
 * When the orb is over it, the particles pull in, turn blue and the ring swells.
 */
@Composable
private fun ParticleBin(shown: Boolean, hot: Boolean) {
    val c = LocalBlaze.current
    val vis by animateFloatAsState(if (shown) 1f else 0f, tween(240), label = "vis")
    val heat by animateFloatAsState(if (hot) 1f else 0f, tween(180), label = "heat")
    if (!shown && vis <= 0.001f) return   // nothing drawn, no frames, while you aren't dragging
    val t = rememberInfiniteTransition(label = "bin")
    val time by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "time")
    val dots = remember {
        val r = kotlin.random.Random(7)
        List(54) { Triple(r.nextFloat() * 6.283f, r.nextFloat(), r.nextFloat()) }
    }
    Canvas(Modifier.fillMaxSize()) {
        if (vis <= 0f) return@Canvas
        val w = size.width; val h = size.height
        // Soft fade from the bottom edge, like the app's paper.
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f * vis)), startY = 0f, endY = h))
        val cx = w / 2f
        val cy = h - 110.dp.toPx() + (1f - vis) * 80.dp.toPx()
        val ringR = (34.dp.toPx() + 10.dp.toPx() * heat)
        val px = 3.dp.toPx() + 1.dp.toPx() * heat
        // Drifting particle ring.
        dots.forEach { (a0, rr, ph) ->
            val a = a0 + time * (0.25f + ph * 0.3f) * (if (ph > 0.5f) 1 else -1)
            val spread = (1f - heat * 0.75f) * 18.dp.toPx()
            val rad = ringR + (rr - 0.5f) * spread + kotlin.math.sin(time * 2 + ph * 9) * 3.dp.toPx() * (1f - heat)
            val col = if (heat > 0.5f || ph > 0.86f) c.accent else Color.White
            drawCircle(col.copy(alpha = vis * (0.55f + 0.45f * heat)), px / 2f,
                Offset(cx + kotlin.math.cos(a) * rad, cy + kotlin.math.sin(a) * rad))
        }
        // Pixel "×" in the middle (hidden under the orb when it snaps in).
        val cell = 4.dp.toPx()
        for (i in -3..3) {
            for (sgn in listOf(1, -1)) {
                drawCircle((if (heat > 0.5f) c.accent else Color.White).copy(alpha = vis * 0.9f), cell * 0.45f,
                    Offset(cx + i * cell, cy + sgn * i * cell))
            }
        }
    }
}

// v0.24.6: AssistPanel moved to ui/AssistPanel.kt (rounded Blaze look, icon buttons, mascot, edge particles).

@Composable
private fun PanelButton(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(BlazeShapes.pill).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Label(label, color = if (label == "Pin orb") LocalBlaze.current.accent else LocalBlaze.current.sub, size = if (label == "×") 16.sp else 10.sp)
    }
}


/** Where a swipe on the work banner sends it (v0.24.5). Pure (OverlayBannerTest / PointerTest). */
enum class BannerSwipe { NONE, MINIMISE, OPEN }

/** Up, left or right past [thresholdPx] minimises into the notification; down past it opens Blaze; a short drag springs back. */
fun bannerSwipe(dx: Float, dy: Float, thresholdPx: Float): BannerSwipe = when {
    dy > thresholdPx && dy > kotlin.math.abs(dx) -> BannerSwipe.OPEN
    dy < -thresholdPx || kotlin.math.abs(dx) > thresholdPx * 1.4f -> BannerSwipe.MINIMISE
    else -> BannerSwipe.NONE
}

/**
 * The "Blaze is working" banner shown over other apps (v0.24.5 look): a fully rounded dark Blaze card with a subtle
 * border and shadow, the mascot inside a spinning ring, the status in brand colours, and Stop as a rounded pill.
 * Swipe up / left / right: it slides away and minimises into the ongoing notification. Swipe down or tap: opens Blaze.
 */
@Composable
fun TaskCard(onOpen: () -> Unit, onStop: () -> Unit, onMinimise: () -> Unit = {}, step0: String? = null, tasks0: Int? = null) {
    val msgs by Agent.messages.collectAsState()
    val busy by Agent.busyChats.collectAsState()
    val stepRaw = step0 ?: msgs.lastOrNull { it.role == com.blaze.assistant.agent.Role.STEP }?.text ?: "Working"
    val tasks = tasks0 ?: busy.size
    // A plan checklist shows its current line; "2/5" tells you how far along it is.
    val lines = stepRaw.lines()
    val step = if (lines.size > 1) {
        val done = lines.count { it.startsWith("\u2713") }
        (lines.firstOrNull { it.startsWith("\u2192") }?.removePrefix("\u2192 ") ?: lines.last().drop(2)) + "  \u00b7  $done/${lines.size}"
    } else stepRaw
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ox = remember { androidx.compose.animation.core.Animatable(0f) }
    val oy = remember { androidx.compose.animation.core.Animatable(0f) }
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val threshold = with(density) { 56.dp.toPx() }
    val away = with(density) { 420.dp.toPx() }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
    Row(Modifier.fillMaxWidth()
        .graphicsLayer { translationX = ox.value; translationY = oy.value; alpha = fade.value }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = {
                    when (bannerSwipe(ox.value, oy.value, threshold)) {
                        BannerSwipe.MINIMISE -> scope.launch {
                            val tx = if (kotlin.math.abs(ox.value) > -oy.value) kotlin.math.sign(ox.value) * away else ox.value
                            val ty = if (kotlin.math.abs(ox.value) > -oy.value) oy.value else -away / 2
                            launch { fade.animateTo(0f, androidx.compose.animation.core.tween(180)) }
                            launch { oy.animateTo(ty, androidx.compose.animation.core.tween(200)) }
                            ox.animateTo(tx, androidx.compose.animation.core.tween(200))
                            onMinimise()
                        }
                        BannerSwipe.OPEN -> scope.launch {
                            launch { fade.animateTo(0f, androidx.compose.animation.core.tween(160)) }
                            oy.animateTo(oy.value + threshold, androidx.compose.animation.core.tween(160))
                            onOpen()
                        }
                        BannerSwipe.NONE -> scope.launch {
                            launch { ox.animateTo(0f, androidx.compose.animation.core.spring()) }
                            oy.animateTo(0f, androidx.compose.animation.core.spring())
                        }
                    }
                },
                onDragCancel = { scope.launch { ox.snapTo(0f); oy.snapTo(0f) } }
            ) { ch, d -> ch.consume(); scope.launch { ox.snapTo(ox.value + d.x); oy.snapTo(oy.value + d.y) } }
        }
        .shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .background(DialogColors.surface, shape)
        .border(1.dp, DialogColors.border, shape)
        .clip(shape)
        .clickable(onClick = onOpen)
        .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        DuoEmblem(40.dp, animate = true) // v0.32.0: new Blaze icon
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (tasks > 1) "Blaze \u00b7 $tasks tasks" else "Blaze is working", maxLines = 1,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    color = GlassTokens.sub))
            Text(step, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, color = DialogColors.title))
        }
        Spacer(Modifier.width(8.dp))
        PillButton("Stop", primary = true, warm = true, onClick = onStop)
    }
}
