package com.blaze.assistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.desktop.DesktopLive
import com.blaze.assistant.desktop.DesktopNarration
import com.blaze.assistant.desktop.DesktopPlan
import com.blaze.assistant.desktop.DesktopPlan.Phase
import com.blaze.assistant.desktop.LinuxDesktop
import com.blaze.assistant.desktop.Rfb
import kotlinx.coroutines.delay

/**
 * v0.25.5 live desktop card: while Blaze works in the Linux desktop, a dark Glass card above the composer shows a
 * live mini view of the desktop (its own read-only VNC connection, or the latest screenshot until that connects),
 * a ring where Blaze last clicked, a solid-blue status pill ("Clicking Save", "Typing into Terminal", "Taking a
 * look") and an X that stops Blaze's desktop work. Tap the card for the full Desktop view. It hides a few seconds
 * after the work ends. [preview] draws a fixed state for screenshots.
 */
@Composable
fun DesktopLiveCard(onOpen: () -> Unit, preview: DesktopNarration.Live? = null) {
    val live by DesktopLive.state.collectAsState()
    val st = preview ?: live
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.active) { while (st.active && preview == null) { now = System.currentTimeMillis(); delay(400) } }
    val show = preview != null || st.show(now)
    AnimatedVisibility(show, enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 },
        exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 3 }) {
        val pill = if (preview != null) st.status else st.pill(now)
        val shape = Glass.card
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
            .shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape).background(Glass.surface).border(1.dp, Glass.border, shape)
            .clickable(onClickLabel = "Open the Linux desktop", onClick = onOpen)
            .semantics { contentDescription = "Blaze is using the Linux desktop: $pill. Tap to open it." }
            .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniDesktop(st, preview != null, Modifier.width(104.dp).height(59.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("Blaze \u00b7 Linux desktop", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Glass.blue))
                Spacer(Modifier.height(5.dp))
                StatusPill(pill, working = st.acting || preview != null)
            }
            Spacer(Modifier.width(4.dp))
            GlassIconButton(Icons.Rounded.Close, "Stop Blaze's desktop work", size = 30.dp, onClick = { DesktopLive.stop() })
        }
    }
}

/**
 * 0.45.19 contrast: the pill is light (Glass.blue is a light grey since v0.31.0), so its words and dot are dark ink.
 * They were white on #E6E6EA ("Reading Blaze Phone's page" was unreadable, ratio 1.2:1). ChatFixes19Test checks AA.
 */
object StatusPillColors {
    val bg: Color get() = Glass.blue
    val fg = Color(0xFF111114)
    val dot = Color(0xFF111114)
}

/** The solid accent pill with a soft pulsing dot while an action runs. */
@Composable
fun StatusPill(text: String, working: Boolean, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "pill")
    val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    Row(modifier.clip(Glass.pill).background(StatusPillColors.bg).padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).alpha(if (working) a else 0.6f).clip(CircleShape).background(StatusPillColors.dot))
        Spacer(Modifier.width(5.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = StatusPillColors.fg))
    }
}

/**
 * The desktop in miniature: a second, view-only RFB connection to the local Xvnc (`-AlwaysShared`) copied into a
 * bitmap at most ~5 times a second, falling back to the last agent screenshot. Never sends input.
 */
@Composable
internal fun MiniDesktop(st: DesktopNarration.Live, preview: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val phase by LinuxDesktop.state.collectAsState()
    val canLive = !preview && phase.phase == Phase.RUNNING && LinuxDesktop.password.isNotBlank()
    var frame by remember { mutableIntStateOf(0) }
    val bmp = remember { mutableStateOf<Bitmap?>(null) }
    var drawn by remember { mutableIntStateOf(0) }
    val client = remember(canLive) { if (canLive) Rfb.Client(LinuxDesktop.password, onFrame = { frame++ }, onClosed = { }) else null }
    DisposableEffect(client) {
        client?.connect()
        onDispose { client?.close(); bmp.value?.recycle(); bmp.value = null }
    }
    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        var copied = -1
        while (true) {
            delay(200)
            if (frame == copied) continue
            copied = frame
            val w = c.width; val h = c.height
            if (w <= 0 || h <= 0) continue
            val b = bmp.value?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also { bmp.value?.recycle(); bmp.value = it }
            try { b.copyPixelsFromBuffer(java.nio.ShortBuffer.wrap(c.fb)) } catch (_: Exception) { }
            bmp.value = b; drawn++
        }
    }
    val shotV by DesktopLive.shotVersion.collectAsState()
    val shot = remember(shotV) { DesktopLive.lastShot?.let { try { BitmapFactory.decodeByteArray(it, 0, it.size) } catch (_: Throwable) { null } } }
    Box(modifier.clip(shape).background(Color.Black).border(1.dp, Glass.border, shape)) {
        Canvas(Modifier.fillMaxSize()) {
            if (drawn < 0) return@Canvas   // reading it redraws after every copy
            val pic = bmp.value ?: shot
            val pw = pic?.width ?: DesktopPlan.WIDTH; val ph = pic?.height ?: DesktopPlan.HEIGHT
            val s = minOf(size.width / pw, size.height / ph)
            val ox = (size.width - pw * s) / 2f; val oy = (size.height - ph * s) / 2f
            if (pic != null) drawImage(pic.asImageBitmap(), dstOffset = IntOffset(ox.toInt(), oy.toInt()),
                dstSize = IntSize((pw * s).toInt().coerceAtLeast(1), (ph * s).toInt().coerceAtLeast(1)))
            // Where Blaze last clicked (desktop pixels; screenshots are the same size as the desktop).
            if (pic != null && st.x >= 0 && st.y >= 0) {
                val sx = DesktopPlan.WIDTH.toFloat().let { if (pic != null) pw / it else 1f }
                val c = Offset(ox + st.x * sx * s, oy + st.y * sx * s)
                drawCircle(Glass.blue.copy(alpha = 0.25f), radius = 7.dp.toPx(), center = c)
                drawCircle(Glass.blue, radius = 7.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
            }
        }
        if (bmp.value == null && shot == null) Text("Connecting\u2026", Modifier.align(Alignment.Center),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 10.sp, color = Glass.sub))
        // LIVE tag while the stream is connected.
        if (bmp.value != null) Row(Modifier.align(Alignment.TopStart).padding(5.dp).clip(Glass.pill).background(Color(0xB30E0F12))
            .padding(horizontal = 5.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(Glass.blue))
            Spacer(Modifier.width(3.dp))
            Text("LIVE", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 8.sp, color = Color.White))
        }
    }
}
