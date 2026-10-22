package com.blaze.assistant.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * v0.31.1 floating glass window: a centred card-like glass window over a dimmed (and, on Android 12+, blurred) scrim,
 * not a full-screen page. Rounded corners, the glass rim, a grab handle, a title row with a round close button.
 * Dismiss: the ×, Back, a tap on the scrim, or a swipe down on the handle / title row.
 *
 * [content] runs in a [BoxWithConstraintsScope] whose constraints are always BOUNDED: width = the window's inner width;
 * height = exactly the body height when [fillHeight] (zoomable diagrams / pictures), else at most that (content wraps).
 */
@Composable
fun GlassFloatingWindow(title: String, onDismiss: () -> Unit, subtitle: String = "", fillHeight: Boolean = false,
                        content: @Composable BoxWithConstraintsScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val c = LocalBlaze.current
        val view = LocalView.current
        LaunchedEffect(Unit) {
            (view.parent as? DialogWindowProvider)?.window?.let { w ->
                w.setDimAmount(if (c.dark) 0.55f else 0.35f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && GlassTokens.blurSupported(view.context)) try {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    w.attributes = w.attributes.apply { blurBehindRadius = 36 }
                } catch (_: Throwable) { }
            }
        }
        val motion = MotionTokens.enabled
        val enter = remember { Animatable(if (motion) 0f else 1f) }
        LaunchedEffect(Unit) { if (motion) enter.animateTo(1f, spring(0.8f, Spring.StiffnessMediumLow)) }
        var dragY by remember { mutableFloatStateOf(0f) }
        val d = LocalDensity.current
        val dismissPx = with(d) { 120.dp.toPx() }
        val rise = with(d) { 28.dp.toPx() }
        val shape = RoundedCornerShape(28.dp)
        BoxWithConstraints(Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }   // tap the scrim to close
            .safeDrawingPadding().padding(horizontal = 14.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
            val bodyMax = (maxHeight * 0.9f) - 64.dp
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .graphicsLayer {
                    val e = enter.value
                    translationY = dragY + (1f - e) * rise
                    alpha = (e * (1f - (dragY / (dismissPx * 3f)).coerceIn(0f, 0.6f))).coerceIn(0f, 1f)
                    val s = 0.96f + 0.04f * e
                    scaleX = s; scaleY = s
                }
                .glass(shape, GlassIntensity.High)
                .background(GlassTokens.bg.copy(alpha = if (c.dark) 0.72f else 0f), shape)
                .background(if (c.dark) Color.Transparent else Color.White.copy(alpha = 0.80f), shape)
                .pointerInput(Unit) { detectTapGestures { } }   // taps inside the window never reach the scrim
            ) {
                Column(Modifier.fillMaxWidth().draggable(rememberDraggableState { delta -> dragY = (dragY + delta).coerceAtLeast(0f) },
                    Orientation.Vertical,
                    onDragStopped = { v ->
                        if (dragY > dismissPx || v > 1800f) onDismiss()
                        else animate(dragY, 0f, animationSpec = spring(0.8f, Spring.StiffnessMedium)) { x, _ -> dragY = x }
                    })) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape)
                        .background(c.ink.copy(alpha = 0.28f)).semantics { contentDescription = "Swipe down to close" })
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink))
                            if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.5.sp, color = c.sub))
                        }
                        GlassIconButton(Icons.Rounded.Close, "Close", size = 40.dp, onClick = onDismiss)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().then(if (fillHeight) Modifier.height(bodyMax) else Modifier.heightIn(max = bodyMax))
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp).clip(RoundedCornerShape(20.dp)), content = content)
            }
        }
    }
}
