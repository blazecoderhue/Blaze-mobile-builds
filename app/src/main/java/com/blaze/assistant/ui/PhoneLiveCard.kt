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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.toArgb
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
import com.blaze.assistant.browser.phone.PhoneUiRules
import kotlinx.coroutines.delay

/** The Blaze Phone palette for the current theme accent and light / dark (PhoneColors; every pair meets WCAG AA). */
@Composable
fun phonePalette(): PhoneColors.Palette {
    val c = LocalBlaze.current
    return remember(c.accent, c.dark) { PhoneColors.of(c.accent.toArgb().toLong() and 0xFFFFFFFFL, c.dark) }
}

/**
 * 0.45.19 Blaze Phone live tile: while Blaze works on its own phone (or waits for the user to take over), a compact
 * Glass card above the composer shows the newest frame, the site and the step. Tap it for the full screen (watch);
 * "Take control" gives the user the real page. Same card language as the old desktop card, sized for
 * 360 dp: a 54 dp phone-shaped frame, two text lines, one button.
 */
@Composable
fun PhoneLiveCard() {
    val live by PhoneBrowser.live.collectAsState()
    var hiddenAt by remember { mutableLongStateOf(-1L) }
    // 0.45.19 fix 9: the 2-minute "recent" window needs a clock; without one the tile never left after Blaze finished.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live.at, live.mode) {
        now = System.currentTimeMillis()
        while (live.mode == PhonePolicy.Mode.AGENT && now - live.at < PhoneUiRules.RECENT_MS) { delay(5_000); now = System.currentTimeMillis() }
    }
    val needsUser = live.mode == PhonePolicy.Mode.WAITING
    val show = PhoneUiRules.showTile(live.mode, live.at, now, hiddenAt)
    val ctx = LocalContext.current
    val pal = phonePalette()
    AnimatedVisibility(show, enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 },
        exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 3 }) {
        DisposableEffect(Unit) { PhoneBrowser.watch(true); onDispose { PhoneBrowser.watch(false) } }
        val shape = Glass.card
        val host = PhoneUiRules.host(live.url)
        val pill = PhoneUiRules.pill(live.mode, live.step, live.reason)
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
            .shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape).background(Color(pal.card)).border(1.dp, Color(pal.cardBorder), shape)
            .clickable(onClickLabel = "Open Blaze Phone") { PhoneBrowserActivity.open(ctx, control = false) }
            .semantics { contentDescription = "Blaze Phone: $pill on ${host.ifBlank { "a page" }}. Tap to watch." }
            .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PhoneFrame(live.frameNo, Modifier.width(54.dp).height(96.dp), pal)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("Blaze Phone" + if (host.isNotBlank()) " \u00b7 $host" else "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(pal.title)))
                Spacer(Modifier.height(5.dp))
                StatusPill(pill, working = live.mode == PhonePolicy.Mode.AGENT, chip = Color(pal.chip), onChip = Color(pal.onChip))
                if (live.mode != PhonePolicy.Mode.USER) {
                    Spacer(Modifier.height(6.dp))
                    Text(PhoneUiRules.TAKE_CONTROL, maxLines = 1,
                        modifier = Modifier.clip(Glass.pill).background(Color(if (needsUser) pal.strong else pal.button))
                            .clickable(onClickLabel = "Take control of Blaze Phone") { PhoneBrowserActivity.open(ctx, control = true) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Color(if (needsUser) pal.onStrong else pal.onButton)))
                }
            }
            if (!needsUser) {
                Spacer(Modifier.width(4.dp))
                GlassIconButton(Icons.Rounded.Close, "Hide the Blaze Phone card", size = 30.dp, tint = Color(pal.onButton), bg = Color(pal.button),
                    onClick = { hiddenAt = System.currentTimeMillis() })
            }
        }
    }
}

/** The newest frame of Blaze's screen, fitted; a quiet placeholder when this phone has no live view. */
@Composable
fun PhoneFrame(frameNo: Long, modifier: Modifier, pal: PhoneColors.Palette = phonePalette()) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).background(Color(pal.frame)).border(1.dp, Color(pal.cardBorder), shape)) {
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
        if (frameNo >= 0 && PhoneBrowser.frame == null) Text("\u2026", Modifier.align(Alignment.Center),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Color(pal.frameText)))
    }
}
