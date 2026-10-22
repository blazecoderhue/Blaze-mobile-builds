package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.material3.Text
import com.blaze.assistant.agent.Reaction
import kotlin.math.abs

/**
 * v0.45.0 message reactions (R3). Double-tap a reply (👍) or tap "React" in its footer for the small picker.
 * Long-press (word selection, quote, chip merge) and horizontal swipe (quote) are left alone: the double-tap detector
 * only watches (Final pass, never consumes), ignores anything a child handled (links, chips) and any drag.
 */
object Reactions {
    const val DOUBLE_TAP = Reaction.UP

    /** Grouped for drawing: emoji → (count, the user's own). Order: first given first. */
    fun groups(list: List<Reaction>): List<Triple<String, Int, Boolean>> =
        list.groupBy { it.emoji }.map { (e, rs) -> Triple(e, rs.size, rs.any { it.by == "user" }) }

    /** TalkBack words for one chip. */
    fun label(emoji: String, count: Int, mine: Boolean): String {
        val name = when (emoji) { "👍" -> "thumbs up"; "👎" -> "thumbs down"; "❤️" -> "heart"; "😂" -> "laughing"; "😮" -> "surprised"; "🙏" -> "thanks"; else -> emoji }
        return (if (mine) "You reacted $name" else "Reacted $name") + (if (count > 1) ", $count" else "") + (if (mine) ". Double-tap to remove" else "")
    }
}

/** Calls [onDouble] for a quick second tap; never consumes, so taps, long-presses and swipes still reach the bubble. */
fun Modifier.doubleTapToReact(enabled: Boolean, onDouble: () -> Unit): Modifier = if (!enabled) this else pointerInput(Unit) {
    var lastUp = 0L
    var lastPos = androidx.compose.ui.geometry.Offset.Zero
    val slop = viewConfiguration.touchSlop
    val dbl = viewConfiguration.doubleTapTimeoutMillis
    val longPress = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        var moved = false
        var handledByChild = down.isConsumed
        var upAt = -1L
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Final)
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (abs(ch.position.x - down.position.x) > slop || abs(ch.position.y - down.position.y) > slop) moved = true
            if (ev.changes.size > 1) moved = true
            if (!ch.pressed) { if (ch.isConsumed) handledByChild = true; upAt = ch.uptimeMillis; break }
        }
        val held = if (upAt > 0) upAt - down.uptimeMillis else Long.MAX_VALUE
        if (upAt < 0 || moved || handledByChild || held >= longPress) { lastUp = 0L; return@awaitEachGesture }
        val near = abs(down.position.x - lastPos.x) < slop * 3 && abs(down.position.y - lastPos.y) < slop * 3
        if (lastUp > 0 && down.uptimeMillis - lastUp <= dbl && near) { lastUp = 0L; onDouble() }
        else { lastUp = upAt; lastPos = down.position }
    }
}

/** The reactions under a bubble: small quiet pills; tapping your own takes it back. */
@Composable
fun ReactionChips(list: List<Reaction>, mine: Boolean, onToggle: (String) -> Unit) {
    if (list.isEmpty()) return
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically) {
        for ((e, n, own) in Reactions.groups(list)) {
            Row(Modifier.padding(end = 4.dp).clip(RoundedCornerShape(12.dp))
                .background(if (own) c.accentSoft else GlassBubble.stripFill(c))
                .border(1.dp, if (own) c.accent.copy(alpha = 0.35f) else GlassBubble.stripEdge(c), RoundedCornerShape(12.dp))
                .clickable(enabled = own) { onToggle(e) }
                .semantics { contentDescription = Reactions.label(e, n, own) }
                .padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e, style = TextStyle(fontSize = 13.sp))
                if (n > 1) Text(" $n", style = TextStyle(fontFamily = Type.Mono, fontSize = 10.sp, color = c.sub))
            }
        }
    }
}

/** The small picker "React" opens: the fixed set, one tap each. */
@Composable
fun ReactionPicker(current: List<Reaction>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalBlaze.current
    Popup(alignment = Alignment.TopStart, onDismissRequest = onDismiss, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        Row(Modifier.clip(RoundedCornerShape(22.dp)).background(c.bubble).border(1.dp, GlassBubble.stripEdge(c), RoundedCornerShape(22.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (e in Reaction.PICKS) {
                val own = current.any { it.by == "user" && it.emoji == e }
                Text(e, style = TextStyle(fontSize = 22.sp),
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (own) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onPick(e) }.semantics { contentDescription = if (own) "Remove reaction $e" else "React with $e" }
                        .padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }
    }
}
