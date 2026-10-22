package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.BgAgents
import com.blaze.assistant.agent.Modes
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip

/** A background agent's live card: status, current step, time, and Steer / Stop / Open. */
@Composable
fun AgentCard(id: String) {
    val c = LocalBlaze.current
    val all by BgAgents.all.collectAsState()
    val bg = all.firstOrNull { it.id == id }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(bg?.live) { while (bg?.live == true) { delay(1000); tick++ } }
    var steering by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    if (bg == null) {
        Label("Background agent $id (cleared)", size = 9.sp)
        return
    }
    val secs = bg.seconds.also { tick }
    val time = if (secs >= 60) "${secs / 60}m ${secs % 60}s" else "${secs}s"
    val head = when (bg.status) {
        BgAgents.QUEUED -> "Queued"
        BgAgents.RUNNING -> if (bg.waiting) "Waiting for you" else "Working"
        BgAgents.DONE -> "Done"
        BgAgents.FAILED -> "Couldn't finish"
        else -> "Stopped"
    }
    val bad = bg.status == BgAgents.FAILED
    Column(Modifier.widthIn(max = 340.dp).fillMaxWidth().glass(GlassTokens.cardShape, if (bg.waiting || bad) GlassIntensity.High else GlassIntensity.Medium, shadow = false).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // v0.26.2: a specialist's card says which one ("B3 · SHOPPER").
            Label("${bg.id} · " + BgAgents.cardRole(bg) + if (bg.plan) " · PLAN" else "", color = c.accent, size = 9.sp)
            Spacer(Modifier.weight(1f))
            Label("$time · ${bg.steps} steps", size = 9.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(Md.plain(bg.title), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, color = c.ink))
        Spacer(Modifier.height(6.dp))
        val line = when {
            bg.live -> Md.plain(bg.last).ifBlank { if (bg.status == BgAgents.QUEUED) "Starts when a slot is free" else "Getting started" }
            else -> Md.plain(bg.result).ifBlank { head }
        }
        if (bg.status == BgAgents.RUNNING && !bg.waiting) Shimmer("$head: ${line.take(80)}", Modifier.fillMaxWidth())
        else Text(if (bg.live) "$head: $line" else line, maxLines = if (expanded) 40 else 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(enabled = !bg.live) { expanded = !expanded },
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = if (bad) c.accent else c.body))
        if (steering) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().hairline(c.hair).padding(horizontal = 10.dp, vertical = 10.dp)) {
                if (text.isEmpty()) Text(if (bg.live) "Tell it something…" else "Follow-up…", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.weak))
                BasicTextField(text, { text = it }, textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink),
                    cursorBrush = SolidColor(c.accent), maxLines = 4,
                    keyboardOptions = ComposerKeys.Message,   // v0.28.1: Enter = new line; "Send" sends
                    modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (steering) {
                ActionText("Send", color = c.accent, size = 10.sp, modifier = Modifier.clickable { if (text.isNotBlank()) { BgAgents.steer(bg.id, text); text = ""; steering = false } })
                ActionText("Cancel", size = 10.sp, modifier = Modifier.clickable { steering = false })
            } else {
                ActionText(if (bg.live) "Steer" else "Follow up", color = c.accent, size = 10.sp, modifier = Modifier.clickable { steering = true })
                if (bg.live) ActionText("Stop", size = 10.sp, modifier = Modifier.clickable { BgAgents.cancel(bg.id) })
                ActionText("Open", size = 10.sp, modifier = Modifier.clickable { Agent.open(bg.chat) })
            }
        }
    }
}

/** Composer mode switches: Plan / Build and Auto / Autopilot, plus how many agents are working. */
@Composable
fun ModeBar() {
    val c = LocalBlaze.current
    val work by Modes.work.collectAsState()
    val appr by Modes.approval.collectAsState()
    val agents by BgAgents.all.collectAsState()
    val live = agents.count { it.live }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).glass(GlassTokens.pillShape, GlassIntensity.Low, shadow = false).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(if (work == "plan") "PLAN" else "BUILD", on = work == "plan") { Modes.toggleWork() }
        Chip(if (appr == "autopilot") "AUTOPILOT" else "AUTO", on = appr == "autopilot") { Modes.toggleApproval() }
        Spacer(Modifier.weight(1f))
        if (live > 0) Label("$live AGENT" + (if (live > 1) "S" else "") + " WORKING", color = c.accent, size = 9.sp)
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Box(Modifier.clip(BlazeShapes.pill).background(if (on) c.accent else c.paper, BlazeShapes.pill).hairline(if (on) c.accent else c.hair, BlazeShapes.pill).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 5.dp)) {
        ButtonText(text, color = if (on) c.paper else c.ink, size = 13.sp)
    }
}
