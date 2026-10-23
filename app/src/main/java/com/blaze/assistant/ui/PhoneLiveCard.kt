package com.blaze.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.browser.phone.PhoneBrowser
import com.blaze.assistant.browser.phone.PhoneBrowserActivity
import com.blaze.assistant.browser.phone.PhonePolicy

/**
 * 0.45.19 Blaze Phone live tile: while Blaze works on its own phone (or waits for the user to take over), a compact
 * Glass card above the composer shows the newest frame, the site and the step. Tap it for the full screen (watch);
 * "Take control" gives the user the real page. Same card language as the desktop card (DesktopLiveCard), sized for
 * 360 dp: a 54 dp phone-shaped frame, two text lines, one button.
 */
@Composable
fun PhoneLiveCard() {
    val live by PhoneBrowser.live.collectAsState()
    var hiddenAt by remember { mutableLongStateOf(-1L) }
    val needsUser = live.mode == PhonePolicy.Mode.WAITING
    // Shown while Blaze works there (a step in the last 2 minutes), and always while it waits for the user.
    val recent = live.mode == PhonePolicy.Mode.AGENT && System.currentTimeMillis() - live.at < 120_000
    val show = (needsUser || recent || live.mode == PhonePolicy.Mode.USER) && (needsUser || hiddenAt < live.at)
    val ctx = LocalContext.current
    AnimatedVisibility(show, enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 },
        exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 3 }) {
        DisposableEffect(Unit) { PhoneBrowser.watch(true); onDispose { PhoneBrowser.watch(false) } }
        val shape = Glass.card
        val host = live.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
        val pill = when (live.mode) {
            PhonePolicy.Mode.WAITING -> "Blaze needs you: " + (live.reason ?: "take control")
            PhonePolicy.Mode.USER -> "You're in control"
            else -> live.step.ifBlank { "Browsing" }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
            .shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape).background(Glass.surface).border(1.dp, Glass.border, shape)
            .clickable(onClickLabel = "Open Blaze Phone") { PhoneBrowserActivity.open(ctx, control = false) }
            .semantics { contentDescription = "Blaze Phone: $pill on ${host.ifBlank { "a page" }}. Tap to watch." }
            .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PhoneFrame(live.frameNo, Modifier.width(54.dp).height(96.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("Blaze Phone" + if (host.isNotBlank()) " \u00b7 $host" else "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Glass.blue))
                Spacer(Modifier.height(5.dp))
                StatusPill(pill, working = live.mode == PhonePolicy.Mode.AGENT)
                if (live.mode != PhonePolicy.Mode.USER) {
                    Spacer(Modifier.height(6.dp))
                    Text("Take control", maxLines = 1,
                        modifier = Modifier.clip(Glass.pill).background(if (needsUser) Color.White else Glass.raised2)
                            .clickable(onClickLabel = "Take control of Blaze Phone") { PhoneBrowserActivity.open(ctx, control = true) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = if (needsUser) Color.Black else Glass.text))
                }
            }
            if (!needsUser) {
                Spacer(Modifier.width(4.dp))
                GlassIconButton(Icons.Rounded.Close, "Hide the Blaze Phone card", size = 30.dp, onClick = { hiddenAt = System.currentTimeMillis() })
            }
        }
    }
}

/** The newest frame of Blaze's screen, fitted; a quiet placeholder when this phone has no live view. */
@Composable
fun PhoneFrame(frameNo: Long, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).background(Color.White).border(1.dp, Glass.border, shape)) {
        Canvas(Modifier.fillMaxSize()) {
            if (frameNo < 0) return@Canvas   // read so each new frame redraws
            val pic = PhoneBrowser.frame ?: return@Canvas
            if (pic.isRecycled) return@Canvas
            val pw = PhoneBrowser.frameW.takeIf { it > 0 } ?: pic.width
            val ph = PhoneBrowser.frameH.takeIf { it > 0 } ?: pic.height
            val s = minOf(size.width / pw, size.height / ph)
            val ox = (size.width - pw * s) / 2f; val oy = (size.height - ph * s) / 2f
            drawImage(pic.asImageBitmap(), srcOffset = IntOffset.Zero, srcSize = IntSize(pw.coerceAtMost(pic.width), ph.coerceAtMost(pic.height)),
                dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize((pw * s).toInt().coerceAtLeast(1), (ph * s).toInt().coerceAtLeast(1)))
        }
        if (PhoneBrowser.frame == null) Text("\u2026", Modifier.align(Alignment.Center),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = LightColors.sub))
    }
}
