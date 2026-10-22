package com.blaze.assistant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay

/**
 * v0.45.2 message actions as compact icons (Copy, Quote, Retry, What I did, React) instead of words.
 * Each icon keeps a 40 dp touch target, a content description for TalkBack and a long-press label.
 */
object MessageActionIcons {
    enum class Kind(val label: String, val spoken: String) {
        COPY("Copy", "Copy message"), QUOTE("Quote", "Quote message"), RETRY("Retry", "Retry"),
        RECEIPT("What I did", "What I did"), REACT("React", "React"),
        /** v0.45.5: model, route, tools, timings, errors and the request id of this reply. */
        TRACE("What happened", "What happened")
    }

    /** The icon for an action label, or null (unknown labels stay as words). */
    fun kindOf(label: String): Kind? = Kind.values().firstOrNull { it.label.equals(label, ignoreCase = true) }

    /** Minimum touch target and the drawn glyph size (visually small). */
    const val TOUCH_DP = 40
    const val GLYPH_DP = 16

    fun icon(k: Kind): ImageVector = when (k) {
        Kind.COPY -> Icons.Rounded.ContentCopy
        Kind.QUOTE -> Icons.Rounded.FormatQuote
        Kind.RETRY -> Icons.Rounded.Refresh
        Kind.RECEIPT -> Icons.AutoMirrored.Rounded.ReceiptLong
        Kind.REACT -> Icons.Rounded.SentimentSatisfied
        Kind.TRACE -> Icons.Rounded.Info
    }
}

/** One icon action: tap runs it, long-press shows its name for a moment (and TalkBack reads [Kind.spoken]). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageActionIcon(kind: MessageActionIcons.Kind, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current
    val haptic = LocalHapticFeedback.current
    var tip by remember { mutableStateOf(false) }
    LaunchedEffect(tip) { if (tip) { delay(1400); tip = false } }
    Box(Modifier.size(MessageActionIcons.TOUCH_DP.dp).clip(RoundedCornerShape(20.dp))
        .combinedClickable(enabled = true, onClickLabel = kind.spoken, role = Role.Button,
            onLongClickLabel = "Show name",
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); tip = true },
            onClick = { if (enabled) onClick() })
        .semantics { contentDescription = kind.spoken + if (enabled) "" else " (not available now)"; role = Role.Button },
        contentAlignment = Alignment.Center) {
        Icon(MessageActionIcons.icon(kind), contentDescription = null, tint = if (enabled) c.sub else c.weak,
            modifier = Modifier.size(MessageActionIcons.GLYPH_DP.dp))
        if (tip) Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -84)) {
            Text(kind.label, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.paper),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.ink).padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}
