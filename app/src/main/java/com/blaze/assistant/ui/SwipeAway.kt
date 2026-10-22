package com.blaze.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * v0.25.0: swipe a snackbar / toast-style bar away. Drag it left, right or down past about a third of its width
 * (or flick it) and it slides out that way and fades; anything less springs back. Up is not a dismiss direction
 * (the bar sits at the bottom). [SwipeAway.decide] is the pure rule (SwipeAwayTest).
 */
object SwipeAway {
    enum class Dir { LEFT, RIGHT, DOWN }

    /** Where a drag of ([dx], [dy]) px with velocity ([vx], [vy]) px/s sends the bar, or null to spring back. */
    fun decide(dx: Float, dy: Float, widthPx: Float, heightPx: Float, vx: Float = 0f, vy: Float = 0f): Dir? {
        val horizontal = abs(dx) >= abs(dy)
        val fling = 1200f
        return if (horizontal) {
            when {
                dx > widthPx / 3f || vx > fling -> Dir.RIGHT
                dx < -widthPx / 3f || vx < -fling -> Dir.LEFT
                else -> null
            }
        } else if (dy > heightPx * 0.6f || vy > fling) Dir.DOWN else null
    }

    /** The resting offset for a dismissed bar (fully off its side). */
    fun target(d: Dir, widthPx: Float, heightPx: Float): Pair<Float, Float> = when (d) {
        Dir.LEFT -> -widthPx * 1.3f to 0f
        Dir.RIGHT -> widthPx * 1.3f to 0f
        Dir.DOWN -> 0f to heightPx * 2.5f
    }
}

fun Modifier.swipeAway(onDismiss: () -> Unit): Modifier = composed {
    val x = remember { Animatable(0f) }
    val y = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var w = 1f; var h = 1f
    this
        .graphicsLayer {
            w = size.width.coerceAtLeast(1f); h = size.height.coerceAtLeast(1f)
            translationX = x.value; translationY = y.value
            alpha = (1f - maxOf(abs(x.value) / w, y.value.coerceAtLeast(0f) / (h * 2f))).coerceIn(0f, 1f)
        }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = {
                    val d = SwipeAway.decide(x.value, y.value, w, h)
                    scope.launch {
                        if (d == null) { launch { x.animateTo(0f) }; y.animateTo(0f) }
                        else {
                            val (tx, ty) = SwipeAway.target(d, w, h)
                            launch { x.animateTo(tx, tween(180)) }
                            y.animateTo(ty, tween(180))
                            onDismiss()
                        }
                    }
                },
                onDragCancel = { scope.launch { launch { x.animateTo(0f) }; y.animateTo(0f) } },
            ) { change, drag ->
                change.consume()
                scope.launch {
                    x.snapTo(x.value + drag.x)
                    y.snapTo((y.value + drag.y).coerceAtLeast(-h * 0.15f))   // a little give upwards, no dismiss
                }
            }
        }
}
