package com.blaze.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * WhatsApp-style reply: grab a message and slide it right. Past the threshold the phone ticks,
 * and on release the message is quoted in the composer; the message springs back either way.
 * Only rightward drags count, and inner horizontal scrollers (tables, code) still get theirs first.
 */
@Composable
fun SwipeToQuote(enabled: Boolean, onQuote: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    val max = with(density) { 96.dp.toPx() }
    val x = remember { Animatable(0f) }
    val armed = remember { booleanArrayOf(false) }
    val quote by rememberUpdatedState(onQuote)
    val on by rememberUpdatedState(enabled)
    Box(Modifier.fillMaxWidth()) {
        // The reply arrow that appears from the left as the message slides.
        Text("\u21A9", modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
            .alpha((x.value / threshold).coerceIn(0f, 1f)),
            style = TextStyle(fontSize = 18.sp, color = if (x.value >= threshold) c.accent else c.sub))
        Box(Modifier.fillMaxWidth()
            .offset { IntOffset(x.value.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { armed[0] = false },
                    onDragEnd = {
                        if (armed[0] && on) quote()
                        armed[0] = false
                        scope.launch { x.animateTo(0f, spring()) }
                    },
                    onDragCancel = { armed[0] = false; scope.launch { x.animateTo(0f, spring()) } },
                ) { change, drag ->
                    if (!on) return@detectHorizontalDragGestures
                    val next = (x.value + drag * 0.6f).coerceIn(0f, max)   // a little resistance, like WhatsApp
                    if (next == x.value) return@detectHorizontalDragGestures
                    change.consume()
                    if (!armed[0] && next >= threshold) { armed[0] = true; com.blaze.assistant.device.Haptics.tick(ctx) }
                    else if (armed[0] && next < threshold) armed[0] = false
                    scope.launch { x.snapTo(next) }
                }
            }) { content() }
    }
}
