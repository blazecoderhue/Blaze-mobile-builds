package com.blaze.assistant.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.DoingLogic
import com.blaze.assistant.agent.SimpleMode
import java.util.Calendar

/** Settings: the Simple / Advanced switch at the top of the list, and what Simple keeps out of sight. */
@Composable
fun SimpleAdvancedSwitch() {
    val c = LocalBlaze.current.mono
    val simple by SimpleMode.simpleFlow.collectAsState()
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).glass(RoundedCornerShape(26.dp), GlassIntensity.Low, shadow = false).padding(4.dp)) {
        listOf(true to "Simple", false to "Advanced").forEach { (v, label) ->
            val on = simple == v
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).let { if (on) it.background(c.ink) else it }
                .clickable { SimpleMode.set(v) }.semantics { contentDescription = "$label mode" }, contentAlignment = Alignment.Center) {
                Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (on) c.paper else c.ink))
            }
        }
    }
    Text(if (simple) "Hidden in Advanced: ${SimpleMode.HIDDEN_LINE}. Everything still works, and Blaze can still open any of it."
        else "Everything is showing, including ${SimpleMode.HIDDEN_LINE}.",
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
    NavigationChoice()
}

/** Settings › Navigation: Swipe (Home first, swipe up for Chat; default) or Classic tabs. Independent of Simple / Advanced. */
@Composable
fun NavigationChoice() {
    val c = LocalBlaze.current.mono
    val swipe by com.blaze.assistant.agent.SwipeNav.swipeFlow.collectAsState()
    Spacer(Modifier.height(8.dp))
    Label("Navigation", modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).glass(RoundedCornerShape(26.dp), GlassIntensity.Low, shadow = false).padding(4.dp)) {
        listOf(true to "Swipe", false to "Classic tabs").forEach { (v, label) ->
            val on = swipe == v
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).let { if (on) it.background(c.ink) else it }
                .clickable { com.blaze.assistant.agent.SwipeNav.setSwipe(v) }.semantics { contentDescription = "Navigation: $label" }, contentAlignment = Alignment.Center) {
                Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (on) c.paper else c.ink))
            }
        }
    }
    Text(if (swipe) "Blaze opens on Home. Swipe up from the handle for Chat; pull down from the top for Home."
        else "The Chat | Projects | Home tabs, opening on Chat.",
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
}

/** "Things Blaze is doing": routines and reminders with friendly names and an on/off switch, then "Today I…". */
@Composable
fun DoingPage() {
    val c = LocalBlaze.current.mono
    val routines by com.blaze.assistant.routine.Routines.all.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    Spacer(Modifier.height(10.dp))
    Text("Turn any of these off with one tap.", style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.sub))
    Spacer(Modifier.height(14.dp))
    Label("Coming up")
    Spacer(Modifier.height(6.dp))
    if (routines.isEmpty()) Text("Nothing scheduled yet. Ask me, for example \u201cevery weekday at 7, read me the morning brief\u201d.",
        modifier = Modifier.padding(vertical = 10.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
    routines.sortedByDescending { it.enabled }.forEach { r ->
        ToggleRow(DoingLogic.friendlyName(r.name), DoingLogic.whenLine(r.days, r.hour, r.minute, r.at, r.everyMinutes), r.enabled) {
            com.blaze.assistant.routine.Routines.setEnabled(r.id, !r.enabled)
        }
    }
    Spacer(Modifier.height(22.dp))
    val lines = remember { DoingLogic.today(com.blaze.assistant.agent.Awareness.recent(60)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("Today I\u2026", modifier = Modifier.weight(1f))
        if (lines.size > 6) ActionText(if (showAll) "Show less" else "See all", modifier = Modifier.clickable { showAll = !showAll }.padding(8.dp))
    }
    Text(DoingLogic.summary(lines), modifier = Modifier.padding(vertical = 8.dp),
        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, color = c.ink))
    (if (showAll) lines.reversed() else lines.takeLast(6).reversed()).forEach { l ->
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
            Text(l.time, modifier = Modifier.width(56.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
            Text(l.text, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, color = c.body))
        }
    }
}

/** Settings › Look & feel: living backgrounds, Light / Dark / Auto, accent, Move with music, Save battery. */
@Composable
fun LookPage() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val bg by Look.bg.collectAsState()
    val accent by Look.accent.collectAsState()
    val music by Look.music.collectAsState()
    val still by Look.still.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            Look.setPhoto(uri.toString()); Look.setBg(LivingLogic.Bg.PHOTO)
        }
    }
    Spacer(Modifier.height(10.dp))
    // v0.45.2: the Living Particles and Weather themes (the same picker as the profile menu's Themes).
    val themeId by Look.theme.collectAsState()
    BlazeButton("Themes · " + LivingThemes.of(themeId).name, Modifier.fillMaxWidth(), kind = BtnKind.SECONDARY) { ThemePickerState.open.value = true }
    Spacer(Modifier.height(12.dp))
    Text("Choose a background. Most of them move, gently." + if (themeId.isNotBlank()) " Picking one switches the theme back to Classic." else "",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.sub))
    Spacer(Modifier.height(12.dp))
    LivingLogic.Bg.values().toList().chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { b ->
                val on = b == bg
                Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).glass(RoundedCornerShape(20.dp), if (on) GlassIntensity.High else GlassIntensity.Low, shadow = false)
                    .border(if (on) 2.dp else 0.dp, if (on) c.ink else Color.Transparent, RoundedCornerShape(20.dp))
                    .clickable { if (b == LivingLogic.Bg.PHOTO && Look.photo.value.isBlank()) pick.launch(arrayOf("image/*")) else { Look.setBg(b); if (Look.theme.value.isNotBlank()) Look.setTheme(LivingThemes.CLASSIC) } }
                    .semantics { contentDescription = "${b.title}: ${b.sub}" }.padding(10.dp)) {
                    Swatch(b, Modifier.fillMaxWidth().height(64.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(b.title, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink))
                    Text(b.sub, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                }
            }
        }
    }
    if (bg == LivingLogic.Bg.PHOTO) ActionText("Pick another photo", modifier = Modifier.clickable { pick.launch(arrayOf("image/*")) }.padding(vertical = 10.dp))
    Spacer(Modifier.height(18.dp))
    Label("Theme")
    Spacer(Modifier.height(8.dp))
    val mode = ThemeMode.mode.value
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("light" to "Light", "dark" to "Dark", "auto" to "Auto").forEach { (id, label) ->
            val on = mode == id || (id == "dark" && mode == "system")
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).let { if (on) it.background(c.ink) else it.glass(RoundedCornerShape(22.dp), GlassIntensity.Low, shadow = false) }
                .clickable { ThemeMode.set(id) }, contentAlignment = Alignment.Center) {
                Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = if (on) c.paper else c.ink))
            }
        }
    }
    Text("Auto turns dark after sunset and light in the morning.", modifier = Modifier.padding(vertical = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
    // v0.45.7: how strongly a theme's picture shows behind the chat. Text keeps AA contrast at every position.
    Spacer(Modifier.height(14.dp))
    Label("Background strength")
    val strength by Look.strength.collectAsState()
    androidx.compose.material3.Slider(strength, { Look.setStrength(it) }, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth()
        .semantics { contentDescription = "Background strength ${(strength * 100).toInt()} percent" })
    Text("Fainter keeps the picture calm behind your messages; stronger shows more of it. Text stays readable either way.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
    Spacer(Modifier.height(14.dp))
    Label("Accent")
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LivingLogic.ACCENTS.forEach { (hex, name) ->
            val col = LivingLogic.parseAccent(hex)?.let { Color(it) }
            Box(Modifier.size(38.dp).clip(CircleShape).background(col ?: c.accent)
                .border(if (accent == hex) 3.dp else 1.dp, if (accent == hex) c.ink else c.hair, CircleShape)
                .clickable { Look.setAccent(hex) }.semantics { contentDescription = "Accent: $name" })
        }
    }
    Spacer(Modifier.height(14.dp))
    ToggleRow("Move with music", "Pulses gently with what's playing in Blaze Music", music) { Look.setMusic(!music) }
    ToggleRow("Save battery: keep it still", "Also pauses by itself on low battery or in power saver", still) { Look.setStill(!still) }
}

/** A still preview of a background for its card. */
@Composable
private fun Swatch(b: LivingLogic.Bg, modifier: Modifier) {
    val cal = remember { Calendar.getInstance() }
    val cols = when (b) {
        LivingLogic.Bg.SKY -> LivingLogic.sky(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)).map { Color(it) }
        LivingLogic.Bg.AURORA -> listOf(Color(0xFF07101F), Color(0xFF2EE6A6), Color(0xFF3AA0FF), Color(0xFFB45CFF))
        LivingLogic.Bg.OCEAN -> listOf(Color(0xFF0B3A5B), Color(0xFF0E5A7E), Color(0xFF0A2E48))
        LivingLogic.Bg.PAPER -> listOf(LocalBlaze.current.paper, LocalBlaze.current.paper)
        LivingLogic.Bg.MOSAIC -> listOf(Color(0xFF3B4C7A), Color(0xFF5A3B6E), Color(0xFF2F6061))
        LivingLogic.Bg.PHOTO -> listOf(Color(0xFF2A2D36), Color(0xFF505664))
    }
    Canvas(modifier.clip(RoundedCornerShape(14.dp))) {
        drawRect(Brush.linearGradient(cols))
        if (b == LivingLogic.Bg.PAPER) drawRect(Color.White.copy(alpha = 0.04f))
    }
}
