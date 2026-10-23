package com.blaze.assistant.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 0.45.19 contrast: the pill is light (Glass.blue is a light grey since v0.31.0), so its words and dot are dark ink.
 * They were white on #E6E6EA ("Reading Blaze Phone's page" was unreadable, ratio 1.2:1). ChatFixes19Test checks AA.
 */
object StatusPillColors {
    val bg: Color get() = Glass.blue
    val fg = Color(0xFF111114)
    val dot = Color(0xFF111114)
}

/**
 * The solid accent pill with a soft pulsing dot while an action runs. Used by Blaze Phone (PhoneLiveCard,
 * PhoneBrowserActivity). It lived in the Linux desktop card until wave B 12 removed that; kept here for Blaze Phone.
 */
@Composable
fun StatusPill(text: String, working: Boolean, modifier: Modifier = Modifier,
               chip: Color = Color(PhoneColors.STATUS_CHIP), onChip: Color = Color(PhoneColors.STATUS_ON_CHIP)) {
    // 0.45.19 fix 3: was white text on the light Glass.blue fill (1.2:1, unreadable); now dark ink on it (AA), or the
    // caller's palette pair (PhoneColors: the Blaze Phone tile and screen, every theme x light / dark).
    val pulse = rememberInfiniteTransition(label = "pill")
    val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    Row(modifier.clip(Glass.pill).background(chip).padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).alpha(if (working) a else 0.6f).clip(CircleShape).background(onChip))
        Spacer(Modifier.width(5.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = onChip))
    }
}
