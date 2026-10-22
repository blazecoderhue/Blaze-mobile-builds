package com.blaze.assistant.ui

import android.content.Context
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloat

/**
 * v0.27.0: one set of motion specs for the whole app, so every animation has the same feel: three durations
 * (150 / 220 / 320 ms), the standard easing, and a spring for anything that follows a finger.
 *
 * With the phone's "Remove animations" on (Settings.Global.ANIMATOR_DURATION_SCALE == 0) every spec here becomes an
 * instant snap and every enter / exit transition becomes None, so nothing moves. [refresh] re-reads the setting
 * (app start, each resume, Magic Hand start).
 */
object MotionTokens {
    const val SHORT = 150
    const val MEDIUM = 220
    const val LONG = 320

    /** Standard: things that move on screen. Decelerate: things coming in. Accelerate: things going out. */
    val Standard: Easing = FastOutSlowInEasing
    val Decelerate: Easing = LinearOutSlowInEasing
    val Accelerate: Easing = FastOutLinearInEasing

    /** Press feedback: buttons shrink to this while held. */
    const val PRESS_SCALE = 0.96f

    /** False when the phone's animator duration scale is 0 ("Remove animations"). */
    @Volatile var enabled: Boolean = true
        private set

    /** Pure rule (MotionTokensTest): motion is on unless the scale is exactly 0 (or not a number). */
    fun motionOn(animatorScale: Float): Boolean = !animatorScale.isNaN() && animatorScale > 0f

    fun refresh(ctx: Context) {
        enabled = try {
            motionOn(android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
        } catch (_: Throwable) { true }
    }

    /** For tests and previews. */
    fun setEnabledForTests(on: Boolean) { enabled = on }

    /** A duration in ms for View animations (0 when motion is off). */
    fun ms(duration: Int): Long = if (enabled) duration.toLong() else 0L

    fun <T> fast(easing: Easing = Standard): FiniteAnimationSpec<T> = if (enabled) tween(SHORT, easing = easing) else snap()
    fun <T> medium(easing: Easing = Standard): FiniteAnimationSpec<T> = if (enabled) tween(MEDIUM, easing = easing) else snap()
    fun <T> slow(easing: Easing = Standard): FiniteAnimationSpec<T> = if (enabled) tween(LONG, easing = easing) else snap()
    /** Drags, snaps back, things that follow a finger. */
    fun <T> drag(): FiniteAnimationSpec<T> = if (enabled) spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow) else snap()

    /** A message, a card or a chip coming in: fade with a slight rise. */
    fun enterUp(): EnterTransition = if (enabled) fadeIn(tween(MEDIUM, easing = Decelerate)) + slideInVertically(tween(MEDIUM, easing = Decelerate)) { it / 6 } else EnterTransition.None
    fun exitDown(): ExitTransition = if (enabled) fadeOut(tween(SHORT, easing = Accelerate)) + slideOutVertically(tween(SHORT, easing = Accelerate)) { it / 8 } else ExitTransition.None
    /** A section opening / closing in place (the work card, a panel). */
    fun expand(): EnterTransition = if (enabled) fadeIn(tween(MEDIUM)) + expandVertically(tween(MEDIUM, easing = Standard)) else EnterTransition.None
    fun collapse(): ExitTransition = if (enabled) fadeOut(tween(SHORT)) + shrinkVertically(tween(MEDIUM, easing = Standard)) else ExitTransition.None
    fun fade(): EnterTransition = if (enabled) fadeIn(tween(MEDIUM)) else EnterTransition.None
    fun fadeAway(): ExitTransition = if (enabled) fadeOut(tween(SHORT)) else ExitTransition.None
}

/** v0.27.0: shrink slightly while pressed (pass the same [interaction] to clickable). No motion when animations are off. */
fun Modifier.pressScale(interaction: InteractionSource, scale: Float = MotionTokens.PRESS_SCALE): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && MotionTokens.enabled) scale else 1f, MotionTokens.fast(), label = "press")
    if (s == 1f) Modifier else Modifier.graphicsLayer { scaleX = s; scaleY = s }
}

/**
 * v0.27.0: fade in with a slight rise the first time this is composed, when [animate] is true (a new message, a
 * sheet's scrim). Nothing at all when [animate] is false or animations are off, so scrolling back to old items never
 * replays it.
 */
fun Modifier.appearIn(animate: Boolean, riseDp: Float = 10f, delayMs: Int = 0): Modifier = if (!animate || !MotionTokens.enabled) this else composed {
    val p = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs.toLong())
        p.animateTo(1f, MotionTokens.medium(MotionTokens.Decelerate))
    }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * riseDp * density }
}

/**
 * v0.27.0: which list items may play their entrance: only ones that arrive after the list first showed (not the
 * history it opened with), and only once each. Pure (MotionTokensTest).
 */
class AppearOnce(initial: Collection<Any>) {
    private val seen = HashSet<Any>(initial)
    /** True the first time [key] is seen after the list opened. */
    fun fresh(key: Any): Boolean = seen.add(key)
}

/**
 * v0.27.0: a loading placeholder: [base] with a soft light band sweeping across (MotionTokens.LONG × 4 per pass).
 * A plain [base] fill when animations are off. Used instead of spinners where a placeholder shape is cheap.
 */
fun Modifier.shimmer(base: androidx.compose.ui.graphics.Color, highlight: androidx.compose.ui.graphics.Color,
                     shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)): Modifier = composed {
    val clipped = Modifier.clip(shape).background(base, shape)
    if (!MotionTokens.enabled) clipped else {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
        val x by t.animateFloat(-0.4f, 1.4f, androidx.compose.animation.core.infiniteRepeatable(
            tween(MotionTokens.LONG * 4, easing = androidx.compose.animation.core.LinearEasing)), label = "shimmerX")
        clipped.drawWithContent {
            drawContent()
            val cx = size.width * x; val band = size.width * 0.35f
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(androidx.compose.ui.graphics.Color.Transparent, highlight, androidx.compose.ui.graphics.Color.Transparent),
                startX = cx - band, endX = cx + band))
        }
    }
}
