package com.blaze.assistant.mediaapps

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/**
 * Wave A item 26: Back out of Blaze Video / Blaze Music always lands on the agent chat.
 * Both apps live in their own task, so a plain finish() used to drop the user on the launcher
 * or whatever task sat underneath. The player keeps going: Music in its service, a playing
 * video in picture-in-picture over the chat.
 */
object BackToChat {
    /** What one Back press does, from the innermost level out. Pure, so it is unit tested. */
    enum class Step { EXIT_FULLSCREEN, COLLAPSE_PLAYER, TO_CHAT }

    fun step(fullscreen: Boolean, tab: String): Step = when {
        fullscreen -> Step.EXIT_FULLSCREEN
        tab == "player" -> Step.COLLAPSE_PLAYER
        else -> Step.TO_CHAT      // library, store, any other tab: straight to the chat
    }

    /** Leaving with a video playing keeps it on screen as the mini player (PiP) instead of stopping it. */
    fun keepVideo(playing: Boolean, pipCapable: Boolean): Boolean = playing && pipCapable

    fun chatIntent(a: Activity): Intent =
        Intent(a, com.blaze.assistant.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Bring the chat forward. [finish] closes the media screen; false leaves it in PiP. */
    fun go(a: Activity, finish: Boolean = true) {
        runCatching { a.startActivity(chatIntent(a)) }
        if (finish) a.finish()
    }
}

/**
 * Wave A item 4: the seek bar. Replaces the Material 3 slider, whose tall bar thumb read as a
 * white line stuck across the video. Thin track, small round knob, tap or drag to seek; the
 * player only jumps when the finger lifts (onChange shows the target time while dragging).
 */
@Composable
fun ScrubBar(
    fraction: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    active: Color,
    rest: Color,
    knob: Color,
    modifier: Modifier = Modifier,
    label: String = "Seek",
) {
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    var pressed by remember { mutableStateOf(false) }
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().height(28.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
                setProgress { v -> change(v.coerceIn(0f, 1f)); done(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o -> change(Scrub.frac(o.x, size.width.toFloat())); done() })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> pressed = true; change(Scrub.frac(o.x, size.width.toFloat())) },
                    onDragEnd = { pressed = false; done() },
                    onDragCancel = { pressed = false; done() },
                    onHorizontalDrag = { ch, _ -> ch.consume(); change(Scrub.frac(ch.position.x, size.width.toFloat())) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val y = size.height / 2
            val r = (if (pressed) 8.dp else 6.dp).toPx()
            val w = size.width - 2 * r
            val x = r + w * f
            val stroke = 3.dp.toPx()
            drawLine(rest, Offset(r, y), Offset(size.width - r, y), stroke, StrokeCap.Round)
            drawLine(active, Offset(r, y), Offset(x, y), stroke, StrokeCap.Round)
            drawCircle(knob, r, Offset(x, y))
        }
    }
}

object Scrub {
    /** Finger x to a 0..1 position along the bar. */
    fun frac(x: Float, width: Float): Float = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)
}
