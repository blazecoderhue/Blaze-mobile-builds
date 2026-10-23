package com.blaze.assistant.mediaapps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.BlazeBrush
import com.blaze.assistant.ui.BlazeShapes
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.PillButton
import kotlinx.coroutines.delay

/** 0.45.20: how long [stalled] has been true, in ms (0 when not), ticking twice a second only while it is. */
@Composable
fun rememberStallMs(stalled: Boolean): Long {
    var ms by remember { mutableLongStateOf(0L) }
    LaunchedEffect(stalled) {
        if (!stalled) { ms = 0L; return@LaunchedEffect }
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (true) { ms = android.os.SystemClock.elapsedRealtime() - t0; delay(500) }
    }
    return ms
}

/**
 * 0.45.20: the player's honest line ([NetHonesty.play]): a small pill, one line, with Retry only when it is retrying.
 * [onDark] for the video surface. Never a spinner on its own.
 */
@Composable
fun NetNote(p: NetHonesty.Play, online: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier, onDark: Boolean = false) {
    if (p == NetHonesty.Play.NONE) return
    val c = LocalBlaze.current
    val bg = if (onDark) Color.Black.copy(alpha = 0.6f) else c.faint
    val fg = if (onDark) Color.White else if (p == NetHonesty.Play.RETRYING) BlazeBrush.orange else c.sub
    Row(modifier.clip(BlazeShapes.pill).background(bg).padding(start = 12.dp, end = if (NetHonesty.retryButton(p)) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MText(NetHonesty.playText(p, online), 12.5.sp, fg, FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
        if (NetHonesty.retryButton(p)) {
            Spacer(Modifier.width(8.dp))
            MText("Retry", 12.5.sp, if (onDark) Color.White else c.ink, FontWeight.SemiBold,
                modifier = Modifier.clip(BlazeShapes.pill).clickable(onClick = onRetry).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

/**
 * 0.45.20 Store error card ([NetHonesty.errorTitle]): what went wrong in one line, then Retry and, when this search
 * was saved before, Show saved results. Full width so nothing squishes at 360 dp.
 */
@Composable
fun SearchErrorCard(title: String, detail: String, canShowSaved: Boolean, onRetry: () -> Unit, onSaved: () -> Unit) {
    val c = LocalBlaze.current
    Column(Modifier.fillMaxWidth().clip(BlazeShapes.medium).background(c.faint).padding(16.dp)) {
        MText(title, 15.sp, c.ink, FontWeight.SemiBold)
        if (detail.isNotBlank()) { Spacer(Modifier.height(4.dp)); MText(detail, 13.sp, c.sub, maxLines = 3) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("Retry", primary = true, onClick = onRetry)
            if (canShowSaved) PillButton("Show saved results", primary = false, onClick = onSaved)
        }
    }
}
