package com.blaze.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blaze.assistant.desktop.DesktopAgent
import com.blaze.assistant.desktop.DesktopLive
import kotlinx.coroutines.flow.MutableStateFlow

/** Whether the chat is scrolled up away from the newest message (the computer card shrinks to its pill then). */
object ChatScrollState { val away = MutableStateFlow(false) }

/**
 * v0.39.0 "Blaze is using the computer": a watch-only live view of the Linux desktop (frames only, no input goes
 * through), the "Watching · you can't touch the screen" badge, a plain step line with "Step n of m", and Watch full
 * screen / Pause / Stop. Scrolled away (or minimised) it shrinks to a small "Working on the computer…" pill.
 */
@Composable
fun DesktopTaskCard(onOpen: () -> Unit) {
    val run by DesktopAgent.state.collectAsState()
    val live by DesktopLive.state.collectAsState()
    val away by ChatScrollState.away.collectAsState()
    var small by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    val c = LocalBlaze.current
    val shape = RoundedCornerShape(24.dp)
    if (small || away) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(26.dp))
            .glass(RoundedCornerShape(26.dp), GlassIntensity.High).clickable { small = false; ChatScrollState.away.value = false }
            .semantics { contentDescription = "Blaze is working on the computer. Tap to watch." }
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(DuoColors.blazeBlue))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Working on the computer\u2026", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.ink))
                Text("${run.goal} \u00b7 ${run.progress.lowercase()}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
            Text("Open", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.ink))
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(shape).glass(shape, GlassIntensity.High).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WatchBadge(Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            GlassIconButton(Icons.Rounded.UnfoldLess, "Make it small", size = 32.dp, onClick = { small = true })
        }
        Spacer(Modifier.height(8.dp))
        // Watch-only: the view has no click or key handlers; nothing the user does reaches the desktop.
        if (!full) MiniDesktop(live, false, Modifier.fillMaxWidth().height(200.dp))
        else Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Showing full screen", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Glass.sub))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(DuoColors.blazeBlue))
            Spacer(Modifier.width(6.dp))
            Text("Blaze", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
        Text(run.goal, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.ink))
        Text(run.progress + (if (run.line.isNotBlank()) " \u00b7 " + run.line else "") + if (run.paused) " \u00b7 paused" else "",
            maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SimpleChip("Watch full screen", modifier = Modifier.weight(1.4f)) { full = true }
            SimpleChip(if (run.paused) "Resume" else "Pause", modifier = Modifier.weight(1f)) { if (run.paused) DesktopAgent.resume() else DesktopAgent.pause() }
            SimpleChip("Stop", primary = true, modifier = Modifier.weight(1f)) { DesktopAgent.stop(); DesktopLive.stop() }
        }
    }
    if (full) Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            MiniDesktop(live, false, Modifier.fillMaxSize().padding(top = 64.dp, bottom = 24.dp))
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                WatchBadge(Modifier)
                Spacer(Modifier.weight(1f))
                GlassIconButton(Icons.Rounded.Close, "Close full screen", size = 40.dp, onClick = { full = false })
            }
            Text(run.progress + " \u00b7 " + run.line, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = Color.White))
        }
    }
}

@Composable
private fun WatchBadge(modifier: Modifier) {
    Row(modifier.clip(Glass.pill).background(Color(0xCC0E0F12)).border(1.dp, Glass.border, Glass.pill).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFF5A5A)))
        Spacer(Modifier.width(6.dp))
        Text("Watching \u00b7 you can't touch the screen", maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Color.White))
    }
}
