package com.blaze.assistant.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Delivery
import com.blaze.assistant.agent.DeliveryState

/**
 * 0.45.20 delivery label (user spec B, contract §1): under your LATEST bubble only, right-aligned, small and muted but
 * readable on every theme (a soft capsule, AA over any ground: [BubblePalette.capsule]). Animated dots + "Sending",
 * then "Sent", then "Read". "Not sent, tap to retry" when the gateway refused it. No ticks. TalkBack hears each change
 * politely (a polite live region with the spoken words from [Delivery.spoken]).
 */
object DeliveryView {
    const val TAG = "delivery_label"

    /**
     * What the label shows for the latest user bubble. [tracked] = the tracker's state (null: never tracked, e.g. older
     * chats or a local-only answer); [answered] = a real Blaze reply (not the ack) came after it; [busy] = a turn is
     * running in this chat now. Read only ever comes from a real event or a real reply, never from time passing.
     */
    fun effective(tracked: DeliveryState?, answered: Boolean, busy: Boolean): DeliveryState = when {
        answered -> DeliveryState.READ
        tracked == DeliveryState.NOT_SENT -> DeliveryState.NOT_SENT
        // A send left at Sending with nothing running (the app was closed meanwhile, or Stop): honestly not sent.
        tracked == DeliveryState.SENDING && !busy -> DeliveryState.NOT_SENT
        tracked != null -> tracked
        busy -> DeliveryState.SENDING
        else -> DeliveryState.SENT
    }
}

@Composable
fun DeliveryLabel(state: DeliveryState, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val c = LocalBlaze.current
    val ink = BubblePalette.capsuleInk(c)
    val failed = state == DeliveryState.NOT_SENT
    Row(modifier.fillMaxWidth().padding(top = 3.dp, end = 4.dp), horizontalArrangement = Arrangement.End) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(BubblePalette.capsule(c), RoundedCornerShape(10.dp))
            .let { m -> if (failed && onRetry != null) m.clickable(role = Role.Button, onClickLabel = "Retry sending") { onRetry() } else m }
            .heightIn(min = if (failed) 32.dp else 0.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; contentDescription = Delivery.spoken(state) }
            .padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                DeliveryState.SENDING -> SendingDots(ink)
                // A short dotted lead like the reference ("•••• Read"), still, quiet.
                DeliveryState.SENT, DeliveryState.READ -> StillDots(ink, if (state == DeliveryState.READ) 4 else 2)
                DeliveryState.NOT_SENT -> Unit
            }
            if (state != DeliveryState.NOT_SENT) Spacer(Modifier.width(5.dp))
            Text(Delivery.label(state), style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, lineHeight = 13.sp,
                fontWeight = if (failed) FontWeight.Medium else FontWeight.Normal, color = if (failed) c.ink else ink))
        }
    }
}

@Composable
private fun StillDots(ink: androidx.compose.ui.graphics.Color, n: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(n) { Box(Modifier.size(2.5.dp).clip(CircleShape).background(ink)) }
    }
}

/** Three dots that pulse in turn while sending (still with Remove animations on). */
@Composable
private fun SendingDots(ink: androidx.compose.ui.graphics.Color) {
    val tr = rememberInfiniteTransition(label = "sending")
    val phase = tr.animateFloat(0f, 3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "sendingDots")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val on = !MotionTokens.enabled || phase.value.toInt() == i
            Box(Modifier.size(3.dp).alpha(if (on) 1f else 0.35f).clip(CircleShape).background(ink))
        }
    }
}
