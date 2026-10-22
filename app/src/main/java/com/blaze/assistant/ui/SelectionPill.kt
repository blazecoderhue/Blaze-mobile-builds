package com.blaze.assistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * v0.27.2: Compose's text-selection menu, routed to Blaze's own pill. [onShow] gets the selection box and the
 * "copy" / "select all" callbacks Compose offers (copy also ends the selection).
 */
class BlazeTextToolbar(val onShow: (Rect, (() -> Unit)?, (() -> Unit)?) -> Unit, val onHide: () -> Unit) : TextToolbar {
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                          onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        status = TextToolbarStatus.Shown; onShow(rect, onCopyRequested, onSelectAllRequested)
    }
    override fun hide() { status = TextToolbarStatus.Hidden; onHide() }
}

/**
 * v0.27.2: the clipboard handed to selectable text. Normally it is the phone's clipboard; while [capture] runs, a
 * "copy" lands here instead, so Search / Ask / Share read the selected words without overwriting what you copied.
 */
class CaptureClipboard(private val real: ClipboardManager) : ClipboardManager by real {
    private var capturing = false
    private var captured: AnnotatedString? = null
    override fun setText(annotatedString: AnnotatedString) {
        if (capturing) captured = annotatedString else real.setText(annotatedString)
    }
    /** Runs [copy] (Compose's copy callback) and returns what it copied, leaving the phone's clipboard alone. */
    fun capture(copy: (() -> Unit)?): String {
        if (copy == null) return ""
        capturing = true; captured = null
        try { copy() } finally { capturing = false }
        return captured?.text.orEmpty().also { captured = null }
    }
}

/** The things the pill's actions do outside the chat (browser, share sheet, speech). */
object SelectionDo {
    /** Search or open a link in Blaze's own browser, in a new tab. */
    fun openInBlazeBrowser(ctx: Context, text: String) {
        val url = SelectionActions.browserUrl(text)
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url), ctx, com.blaze.assistant.browser.BrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (_: Exception) { toast(ctx, "Couldn't open Blaze's browser") }
    }

    fun share(ctx: Context, text: String) {
        try {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { toast(ctx, "Nothing to share with") }
    }

    fun readAloud(ctx: Context, text: String) {
        val t = SelectionActions.speakable(text)
        if (t.isBlank()) return
        try {
            if (!com.blaze.assistant.voice.Voice.ttsOk()) { toast(ctx, "Text-to-speech isn't ready yet"); return }
            com.blaze.assistant.voice.Voice.speak(t)
        } catch (_: Exception) { toast(ctx, "Text-to-speech isn't ready yet") }
    }

    /** The phone's language in English ("French"), for Translate. */
    fun phoneLanguage(): String = try { java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "English" } } catch (_: Exception) { "English" }

    fun toast(ctx: Context, s: String) { try { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show() } catch (_: Exception) { } }
}

/**
 * v0.27.2: the black selection pill (Copy · Quote · Select all · Search · Ask Blaze · Translate · Define · Read aloud ·
 * Share). It is as wide as its actions up to the screen minus 12 dp each side, then scrolls sideways with a snapping
 * fling and a haptic tick on each settle. A fade + chevron on an edge says more is there; the first time it opens it
 * peeks the next actions once (never with Remove animations on). Draw it in a full-screen box; it does not block
 * touches outside itself.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectionPill(rect: Rect, actions: List<SelectionActions.Action>, ink: Color, paper: Color, nudge: Boolean = true, onAction: (SelectionActions.Action) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val haptic = LocalHapticFeedback.current
        val edge = with(d) { 12.dp.toPx() }
        val screenW = with(d) { maxWidth.toPx() }
        var pillW by remember { mutableIntStateOf(0) }
        var pillH by remember { mutableIntStateOf(0) }
        val state = rememberLazyListState()
        val fling = rememberSnapFlingBehavior(state, SnapPosition.Start)
        var nudging by remember { mutableStateOf(false) }

        // Above the words; below them when there is no room on top.
        val above = rect.top - with(d) { 52.dp.toPx() }
        val y = if (above >= with(d) { 40.dp.toPx() }) above else rect.bottom + with(d) { 10.dp.toPx() }
        val x = rect.left.coerceAtMost(screenW - pillW - edge).coerceAtLeast(edge)

        // A tick when a swipe settles on a new action.
        LaunchedEffect(state) {
            var last = state.firstVisibleItemIndex
            snapshotFlow { state.isScrollInProgress }.collect { moving ->
                if (!moving) {
                    val now = state.firstVisibleItemIndex
                    if (now != last && !nudging) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    last = now
                }
            }
        }
        // First time only: peek the hidden actions, then come back.
        LaunchedEffect(actions.size) {
            snapshotFlow { state.layoutInfo.totalItemsCount > 0 && pillW > 0 }.first { it }
            val seen = try { Settings.bool(SelectionActions.NUDGE_KEY, false) } catch (_: Throwable) { true }
            if (!nudge || !SelectionActions.shouldNudge(seen, MotionTokens.enabled, state.canScrollForward)) return@LaunchedEffect
            delay(450)
            val px = with(d) { 64.dp.toPx() }
            nudging = true
            try {
                state.animateScrollBy(px, tween(MotionTokens.LONG, easing = MotionTokens.Decelerate))
                delay(160)
                state.animateScrollBy(-px, tween(MotionTokens.MEDIUM, easing = MotionTokens.Standard))
                try { Settings.setBool(SelectionActions.NUDGE_KEY, true) } catch (_: Throwable) { }
            } finally { nudging = false }
        }

        Box(Modifier.offset { IntOffset(x.toInt(), y.toInt()) }
            .widthIn(max = maxWidth - 24.dp)
            .alpha(if (pillW == 0) 0f else 1f)
            .onSizeChanged { pillW = it.width; pillH = it.height }
            .shadow(8.dp, BlazeShapes.pill).clip(BlazeShapes.pill).background(ink, BlazeShapes.pill)) {
            LazyRow(state = state, flingBehavior = fling, contentPadding = PaddingValues(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                items(actions, key = { it.name }) { a ->
                    Box(Modifier.clip(BlazeShapes.pill).clickable { onAction(a) }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        ActionText(a.label, color = paper, size = 11.sp)
                    }
                }
            }
            // Edge hints: a fade into the pill's colour and a small chevron where more actions are hidden.
            val h = with(d) { pillH.toDp() }
            if (state.canScrollBackward) EdgeHint(Modifier.align(Alignment.CenterStart), h, ink, paper, start = true)
            if (state.canScrollForward) EdgeHint(Modifier.align(Alignment.CenterEnd), h, ink, paper, start = false)
        }
    }
}

@Composable
private fun EdgeHint(modifier: Modifier, height: Dp, ink: Color, paper: Color, start: Boolean) {
    val fade = if (start) listOf(ink, ink.copy(alpha = 0.92f), ink.copy(alpha = 0f)) else listOf(ink.copy(alpha = 0f), ink.copy(alpha = 0.92f), ink)
    Box(modifier.width(44.dp).height(height).background(Brush.horizontalGradient(fade)),
        contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd) {
        Text(if (start) "\u2039" else "\u203a", modifier = Modifier.padding(horizontal = 10.dp),
            style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = paper.copy(alpha = 0.75f)))
    }
}
