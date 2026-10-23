package com.blaze.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.RapidMessages
import com.blaze.assistant.agent.Role
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v0.30.6 rapid messages (Blazeinchue: "blaze highlights the one it's responding to and such"). Kept out of ChatScreen.kt so the
 * bubble styling there can change freely: a quote chip above a reply that answers a message that isn't right above it
 * (or several), a soft glow on the user messages Blaze is working on, and a brief glow on a message a chip jumps to.
 * Which chips show is decided in RapidMessages.chipMap (pure, RapidMessagesTest).
 */
object ReplyFocus {
    /** The message to glow now, with a nonce so tapping the same chip twice glows twice. */
    val flashing = MutableStateFlow<Pair<Long, Long>?>(null)

    fun flash(id: Long) { flashing.value = id to System.nanoTime() }

    /** How long a jumped-to message glows (ms). */
    const val FLASH_MS = 1_200
}

/** What the list needs to draw quotes and glows: chips per reply id, the chat's messages by id, and what Blaze works on. */
class ReplyRefs(val chips: Map<Long, List<Long>>, val byId: Map<Long, ChatMessage>, val working: Set<Long>)

/** Computed once per message list (one pass), not per bubble. */
@Composable
fun rememberReplyRefs(messages: List<ChatMessage>): ReplyRefs {
    val working by Agent.focus.collectAsState()
    val chips = remember(messages) { RapidMessages.chipMap(messages) }
    val byId = remember(chips, messages) {
        if (chips.isEmpty()) emptyMap() else {
            val want = chips.values.flatten().toHashSet()
            messages.filter { it.id in want }.associateBy { it.id }
        }
    }
    return remember(chips, byId, working) { ReplyRefs(chips, byId, working) }
}

/**
 * Wraps one message of the thread: a user message gets the glow (working on it, or just jumped to); a Blaze reply that
 * quotes gets its chips above it. Anything else is drawn untouched.
 */
@Composable
fun ReplyThread(m: ChatMessage, refs: ReplyRefs, onJump: (Long) -> Unit, content: @Composable () -> Unit) {
    when {
        m.role == Role.USER -> {
            val c = LocalBlaze.current
            val a = glowAlpha(m.id, m.id in refs.working)
            Box(Modifier.fillMaxWidth().then(if (a > 0.004f) Modifier.background(c.accent.copy(alpha = a), RoundedCornerShape(18.dp)) else Modifier)) { content() }
        }
        m.role == Role.BLAZE && !refs.chips[m.id].isNullOrEmpty() -> Column(Modifier.fillMaxWidth()) {
            refs.chips[m.id].orEmpty().forEach { qid ->
                refs.byId[qid]?.let { q -> QuoteChip(RapidMessages.snippet(q.text)) { onJump(qid) } }
            }
            content()
        }
        else -> content()
    }
}

/** A texting-app reply quote: a thin accent bar, then one line of the quoted message. Tap = jump to it. */
@Composable
fun QuoteChip(text: String, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.padding(start = 2.dp, bottom = 4.dp).widthIn(max = 300.dp).height(IntrinsicSize.Min)
        .clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = 0.08f)).clickable(onClick = onClick)
        .semantics { contentDescription = "Replying to: $text. Tap to show it." }) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(c.accent))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 16.sp, color = c.sub))
    }
}

/** The soft accent wash behind a user message (alpha): faint while Blaze works on it, brighter for a moment after a jump. */
@Composable
private fun glowAlpha(id: Long, working: Boolean): Float {
    val flash by ReplyFocus.flashing.collectAsState()
    val pulse = remember(id) { Animatable(0f) }
    LaunchedEffect(flash) {
        val f = flash
        // Only a fresh jump glows (a bubble scrolled back into view later doesn't glow again).
        if (f != null && f.first == id && System.nanoTime() - f.second < ReplyFocus.FLASH_MS * 1_000_000L) {
            pulse.snapTo(1f)
            // With Remove animations on, the glow holds, then goes (no fade).
            if (MotionTokens.enabled) pulse.animateTo(0f, tween(ReplyFocus.FLASH_MS))
            else { kotlinx.coroutines.delay(ReplyFocus.FLASH_MS.toLong()); pulse.snapTo(0f) }
        }
    }
    val base by animateFloatAsState(if (working) 1f else 0f, tween(MotionTokens.LONG), label = "replyWorking")
    return maxOf(pulse.value * 0.22f, base * 0.07f)
}
