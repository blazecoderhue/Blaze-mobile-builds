package com.blaze.assistant.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Blaze's visible pointer for phone control (v0.21.0).
 *
 * Before Blaze touches the screen it glides a pointer to the target, re-checks that the target is still there (the
 * orient step), shows a short ripple, then acts, so you see it coming.
 *
 * v0.45.14 (user, live 0.45.13 21:38: "remove that obstruction completely and cleanly"): the pointer is a picture
 * only. Its window is FLAG_NOT_TOUCHABLE, so it never takes a touch, never covers the target and never pauses a run:
 * Blaze's own taps, screen changes and the user's touches all pass straight through. The old paused mode (the bar
 * over the top of the screen, drag / double-tap to click, Resume) is gone. The only way to stop a run from the screen
 * is the small Stop chip at the bottom-left edge, which hides itself while Blaze's own gesture is in flight.
 *
 * Light on purpose (low-end phone): plain Views in accessibility overlay windows, no Compose, and it only animates
 * while gliding or rippling, and is a still picture otherwise.
 */
object Pointer {
    const val ACCENT = 0xFF1F4BD8.toInt()

    /**
     * v0.24.4 (Luke): the pointer is always shown while Blaze drives the phone; agent taps can't hide it. The old
     * `show_pointer` pref is kept (backward compatible) but no longer read for agent gestures.
     */
    const val enabled: Boolean = true

    /** The pointer window, in dp (v0.24.4: 64 dp). */
    const val TOUCH_DP = 64
    /** Blaze's mascot drawn inside it (v0.24.5), tip to bottom, in dp. */
    const val MASCOT_DP = 52
    /** Mascot units (body radius) in dp. */
    val UNIT_DP: Float get() = MASCOT_DP / Mascot.HEIGHT
    // v0.32.0: the pointer is the Blaze icon (glass drop in its gold halo); its centre is the click point.
    const val TIP_Y_DP = TOUCH_DP / 2f
    val TIP_X_DP: Float get() = TOUCH_DP / 2f

    /** The pointer's window: drawn only, never touchable or focusable, so every touch goes to the app under it. */
    const val DOT_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    /** The Stop chip's window: only the chip itself takes a touch; everything outside it goes to the app. */
    const val CHIP_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    /** The Stop chip sits at the bottom-left edge, this far in and up (dp), above the navigation bar. */
    const val CHIP_EDGE_DP = 8
    const val CHIP_UP_DP = 88

    /** Light "I'm moving" pulse: 1.0 → 1.07 and back every 600 ms while gliding. */
    fun pulseScale(ms: Long): Float = 1f + 0.035f * (1f - kotlin.math.cos(ms % 600 / 600f * 2f * Math.PI.toFloat()))
    /** Squish on click over t = 0..1: wide and short, then back to round (scaleX, scaleY). */
    fun squish(t: Float): Pair<Float, Float> {
        val k = if (t !in 0f..1f) 0f else kotlin.math.sin(t * Math.PI.toFloat())
        return (1f + 0.16f * k) to (1f - 0.18f * k)
    }

    private val riskyRe = Regex("""\b(pay|payment|send|delete|remove|erase|buy|purchase|order|checkout|check out|transfer|withdraw|confirm|uninstall|post|publish|sign out|log ?out|subscribe|unsubscribe|reset|format|block|report|call)\b""", RegexOption.IGNORE_CASE)
    /** A label that spends money, sends, deletes or can't be undone (the in-app browser asks before clicking one). */
    fun risky(label: String?) = !label.isNullOrBlank() && riskyRe.containsMatchIn(label)

    // ---------- pure rules (PointerTest) ----------
    /** Glide time: quick for short hops, never slow (120–420 ms), by distance in dp. */
    fun glideMs(dxPx: Float, dyPx: Float, density: Float): Long {
        val dp = kotlin.math.sqrt(dxPx * dxPx + dyPx * dyPx) / density.coerceAtLeast(0.5f)
        return (120 + dp * 0.6f).toLong().coerceIn(120, 420)
    }
    /** Ripple before acting: short for a tap, held for a long press. */
    fun rippleMs(long: Boolean) = if (long) 520L else 240L
    /** A target that moved more than this (dp) after the glide is aimed at again. */
    fun moved(x0: Float, y0: Float, x1: Float, y1: Float, density: Float) =
        kotlin.math.hypot(x1 - x0, y1 - y0) / density.coerceAtLeast(0.5f) > 24f
    /** While Blaze's own gesture is in flight (until [ownUntil]) the Stop chip is hidden, so it can't catch that touch. */
    fun chipHidden(now: Long, ownUntil: Long) = now <= ownUntil

    // ---------- stop ----------
    @Volatile private var stopAsked = false
    /** When the pointer was last on screen (wall clock ms); /phoneqa's overlay case checks it was shown. */
    @Volatile var shownAt = 0L
        private set

    /** The Stop chip was tapped: the next screen action ends the task, and a glide in progress stops at once. */
    fun stop() { stopAsked = true }
    /** True once after Stop was tapped (the agent ends the task). */
    fun takeStop(): Boolean = stopAsked.also { stopAsked = false }
    val stopped: Boolean get() = stopAsked

    // ---------- windows ----------
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var wm: WindowManager? = null
    private var dot: Dot? = null
    private var dotLp: WindowManager.LayoutParams? = null
    private var chip: View? = null
    private var trail: Trail? = null
    @Volatile private var ownUntil = 0L
    @Volatile private var x = 0f
    @Volatile private var y = 0f
    private var density = 2f

    private fun type() = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private fun ensure(ctx: Context) {
        if (dot != null) return
        val w = ctx.getSystemService(WindowManager::class.java) ?: return
        wm = w
        density = ctx.resources.displayMetrics.density
        val size = (TOUCH_DP * density).toInt()
        val v = Dot(ctx, density)
        val lp = WindowManager.LayoutParams(size, size, type(), DOT_FLAGS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        try { w.addView(v, lp); dot = v; dotLp = lp } catch (_: Exception) { }
        showChip(ctx, w)
    }

    private fun showChip(ctx: Context, w: WindowManager) {
        if (chip != null) return
        val d = density
        val c = TextView(ctx).apply {
            text = "Stop"; textSize = 12f; isSingleLine = true; setTextColor(Color.WHITE)
            setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setColor(Color.argb(200, 29, 26, 22)) }
            contentDescription = "Stop Blaze"
            setOnClickListener { stop(); visibility = View.GONE }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type(),
            CHIP_FLAGS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.START; x = (CHIP_EDGE_DP * d).toInt(); y = (CHIP_UP_DP * d).toInt() }
        try { w.addView(c, lp); chip = c } catch (_: Exception) { }
    }

    private fun place(px: Float, py: Float) {
        val v = dot ?: return; val lp = dotLp ?: return
        x = px; y = py
        lp.x = (px - v.tipX).toInt(); lp.y = (py - v.tipY).toInt()
        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    /** Glide to (tx, ty). False only when the user tapped Stop (the caller must not act). */
    suspend fun aim(ctx: Context, tx: Float, ty: Float): Boolean {
        if (stopAsked) return false
        withContext(Dispatchers.Main) {
            ensure(ctx)
            dot?.visibility = View.VISIBLE
            dot?.moving(true)
        }
        shownAt = System.currentTimeMillis()
        val fx = x.takeIf { it > 0 } ?: tx; val fy = y.takeIf { it > 0 } ?: (ty - 200 * density)
        val ms = glideMs(tx - fx, ty - fy, density)
        val start = SystemClock.uptimeMillis()
        try {
            while (true) {
                if (stopAsked) return false
                val t = ((SystemClock.uptimeMillis() - start).toFloat() / ms).coerceIn(0f, 1f)
                val e = 1 - (1 - t) * (1 - t) * (1 - t)   // ease out
                withContext(Dispatchers.Main) { place(fx + (tx - fx) * e, fy + (ty - fy) * e) }
                if (t >= 1f) break
                kotlinx.coroutines.delay(16)
            }
        } finally { main.post { dot?.moving(false) } }
        return !stopAsked
    }

    /** The short "here it comes" ripple. False only when the user tapped Stop during it. */
    suspend fun ripple(long: Boolean): Boolean {
        val v = dot ?: return !stopAsked
        val ms = rippleMs(long)
        withContext(Dispatchers.Main) { v.ripple(ms) }
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) { if (stopAsked) return false; kotlinx.coroutines.delay(30) }
        return !stopAsked
    }

    /** Blaze's own touch is about to happen: the Stop chip steps aside for it (the pointer never takes touches). */
    fun ownGesture(ms: Long) {
        ownUntil = SystemClock.uptimeMillis() + ms + 120
        main.post {
            val c = chip ?: return@post
            c.visibility = View.GONE
            main.postDelayed({ if (!chipHidden(SystemClock.uptimeMillis(), ownUntil) && !stopAsked) chip?.visibility = View.VISIBLE }, ms + 150)
        }
    }

    /** Puts the pointer at (px, py) at once (end of a swipe), if it's on screen. */
    fun jump(px: Float, py: Float) { main.post { if (dot != null) place(px, py) } }

    /** Swipes are drawn as a path for a moment. */
    fun showPath(ctx: Context, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        main.post {
            val w = ctx.getSystemService(WindowManager::class.java) ?: return@post
            trail?.let { try { w.removeView(it) } catch (_: Exception) { } }
            val t = Trail(ctx, x1, y1, x2, y2, ctx.resources.displayMetrics.density)
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
            try { w.addView(t, lp); trail = t } catch (_: Exception) { }
            main.postDelayed({ try { w.removeView(t) } catch (_: Exception) { }; if (trail === t) trail = null }, ms + 450)
        }
    }

    /** When Blaze finishes or stops, the pointer and the Stop chip leave the screen. */
    fun done() {
        stopAsked = false
        main.post {
            chip?.let { c -> try { wm?.removeView(c) } catch (_: Exception) { } }; chip = null
            dot?.let { d -> try { wm?.removeView(d) } catch (_: Exception) { } }; dot = null; dotLp = null
            x = 0f; y = 0f
        }
    }

    /**
     * The pointer (v0.24.5): Blaze's mascot (phone/Mascot.kt): the flame body with its orange → blue gradient and cyan
     * middle, two dark eyes, a soft orange / blue glow, a thin white edge and a soft shadow so it reads on any app.
     * The flame tip is the click point (a tiny white dot marks it). It pulses lightly while gliding, squishes on click
     * (around the tip, so the click point never moves).
     * Stays on screen until the task ends (Pointer.done).
     */
    private class Dot(ctx: Context, val d: Float) : View(ctx) {
        val tipX = TIP_X_DP * d; val tipY = TIP_Y_DP * d
        private val k = UNIT_DP * d
        private var rippleStart = 0L; private var rippleMs = 0L
        private var moveStart = 0L
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0); style = Paint.Style.FILL
            setShadowLayer(3.5f * d, 0f, 1.5f * d, Color.argb(120, 0, 0, 0)) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Mascot.EYE; style = Paint.Style.FILL }
        private val emblemScope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
        private val tipDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL
            setShadowLayer(1.2f * d, 0f, 0f, Color.argb(160, 0, 0, 0)) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
        private val bodyPath = Path()
        private val rect = android.graphics.RectF()

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // shadow layers on paths; the view is only 64 dp

        fun moving(on: Boolean) { moveStart = if (on) SystemClock.uptimeMillis() else 0L; invalidate() }
        fun ripple(ms: Long) { rippleStart = SystemClock.uptimeMillis(); rippleMs = ms; invalidate() }

        private fun radial(at: FloatArray, r: Float, color: Int, sx: Float, sy: Float): android.graphics.RadialGradient {
            val c = Mascot.at(at, tipX, tipY, k, sx, sy)
            return android.graphics.RadialGradient(c[0], c[1], r * k * maxOf(sx, sy), color, color and 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        }

        override fun onDraw(c: Canvas) {
            val now = SystemClock.uptimeMillis()
            val t = if (rippleMs > 0) (now - rippleStart).toFloat() / rippleMs else 2f
            var (sx, sy) = squish(t)
            if (moveStart > 0) { val p = pulseScale(now - moveStart); sx *= p; sy *= p }
            // v0.32.0: draw the new Blaze icon (shared Compose drawing) centred on the click point; squish/pulse scale it.
            val sc = (sx + sy) / 2f
            val wPx = MASCOT_DP * d * 1.15f * sc
            val lpx = (tipX * 2).coerceAtLeast(1f); val lpy = (tipY * 2).coerceAtLeast(1f)
            emblemScope.draw(androidx.compose.ui.unit.Density(d), androidx.compose.ui.unit.LayoutDirection.Ltr,
                androidx.compose.ui.graphics.Canvas(c), androidx.compose.ui.geometry.Size(lpx, lpy)) {
                with(com.blaze.assistant.ui.DuoGlowAccess) { emblem(androidx.compose.ui.geometry.Offset(tipX, tipY), wPx, ((now % 100_000L) / 1000f)) }
            }
            c.drawCircle(tipX, tipY, 1.6f * d, tipDot)
            if (t <= 1f) {
                ring.alpha = ((1 - t) * 255).toInt()
                c.drawCircle(tipX, tipY, (4 + 12 * t) * d, ring)
            } else rippleMs = 0
            postInvalidateOnAnimation() // v0.32.0: the drop wobbles and the halo breathes
        }
    }

    /** A swipe drawn as a fading line with a dot at the start. */
    private class Trail(ctx: Context, val x1: Float, val y1: Float, val x2: Float, val y2: Float, d: Float) : View(ctx) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; alpha = 170; style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND }
        private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; alpha = 200 }
        private val r = 6 * d
        override fun onDraw(c: Canvas) { c.drawLine(x1, y1, x2, y2, line); c.drawCircle(x1, y1, r, dotP) }
    }
}
