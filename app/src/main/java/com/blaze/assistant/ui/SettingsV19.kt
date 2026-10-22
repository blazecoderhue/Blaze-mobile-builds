package com.blaze.assistant.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Providers
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.agent.Traces
import com.blaze.assistant.phone.BlazeAccessibilityService
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

/** Settings → Look & feel: the small things that make Blaze feel alive. */
@Composable
internal fun FeelPage() {
    var taps by remember { mutableStateOf(Settings.bool("show_taps", true)) }
    var sounds by remember { mutableStateOf(Settings.bool("sounds", true)) }
    var compact by remember { mutableStateOf(Settings.bool("compact_panel", true)) }
    var autoHide by remember { mutableStateOf(Settings.bool("auto_hide_panel", true)) }
    var traces by remember { mutableStateOf(Traces.count()) }
    ToggleRow("Show where I tap", "A soft blue ring flashes on whatever Blaze is about to tap", taps) { taps = !taps; Settings.setBool("show_taps", taps) }
    ToggleRow("Sounds", "A blip when Blaze wakes, a tick when a task is done", sounds) { sounds = !sounds; Settings.setBool("sounds", sounds) }
    ToggleRow("Compact panel", "Over other apps, show a slim bar with just the latest line. Tap More to expand", compact) { compact = !compact; Settings.setBool("compact_panel", compact) }
    ToggleRow("Fade away when done", "The compact panel closes itself a few seconds after a task finishes", autoHide) { autoHide = !autoHide; Settings.setBool("auto_hide_panel", autoHide) }
    var habits by remember { mutableStateOf(Settings.bool("habit_offers", true)) }
    var morning by remember { mutableStateOf(Settings.bool("morning_brief", true)) }
    ToggleRow("Morning brief", "A short good-morning card with your day when your quiet hours end", morning) { morning = !morning; Settings.setBool("morning_brief", morning) }
    // v0.33.2: Home is BlazeOS; the old Home board is still there as the Classic board.
    var classic by remember { mutableStateOf(com.blaze.assistant.blazeos.BlazeOs.classic) }
    ToggleRow("Classic board on Home", "Show the old Home board instead of BlazeOS, Blaze's virtual phone", classic) { classic = !classic; com.blaze.assistant.blazeos.BlazeOs.classic = classic }
    ToggleRow("Offer to automate habits", "If you open an app at the same time most days, Blaze offers once to do it for you", habits) { habits = !habits; Settings.setBool("habit_offers", habits) }
    V18Section("Learning from what I do")
    V18Note("Blaze keeps a private log of the steps it runs on screen ($traces so far). It never leaves your phone and will train a faster on-device model later.")
    Spacer(Modifier.height(8.dp))
    V18Button("Clear step log", primary = false) { Traces.clear(); traces = 0 }
}

/** First run: three short screens, then straight into something real. */
@Composable
fun Onboarding(page0: Int = 0, onDone: (firstPrompt: String?) -> Unit) {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    var page by remember { mutableStateOf(page0) }
    var a11y by remember { mutableStateOf(BlazeAccessibilityService.isEnabled(ctx)) }
    var brain by remember { mutableStateOf(if (Settings.ready) "ready" else "") }
    LaunchedEffect(page) { while (page == 1) { a11y = BlazeAccessibilityService.isEnabled(ctx); delay(1200) } }
    BackHandler(enabled = page > 0) { page-- }
    fun finish(prompt: String?) { Settings.setBool("onboarded", true); onDone(prompt) }

    Box(Modifier.fillMaxSize().pageBackdrop(c)) {
        ParticleField(com.blaze.assistant.agent.Mood.IDLE)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // v0.19.0: three progress segments, like Desktop's onboarding steps.
                (0 until 3).forEach { i ->
                    Box(Modifier.padding(end = 4.dp).width(22.dp).height(3.dp).clip(BlazeShapes.pill).background(if (i <= page) c.accent else c.hair2))
                }
                Spacer(Modifier.width(8.dp))
                Label("0${page + 1} / 03", color = c.weak, size = 10.sp)
                Spacer(Modifier.weight(1f))
                Box(Modifier.clip(BlazeShapes.pill).clickable { finish(null) }.padding(8.dp)) { ActionText("Skip", color = c.sub, size = 10.sp) }
            }
            Column {
                OrbFace(72.dp)
                Spacer(Modifier.height(28.dp))
                val (title, body) = when (page) {
                    0 -> "Hi, I'm Blaze." to "I live in your phone and get things done for you: open apps, play music, send texts, find answers. Sign in to your Blaze account and I'm ready."
                    1 -> "Let me use your phone." to "Turn on Blaze in Accessibility so I can tap and type for you. I'll always ask before sending, paying or deleting anything."
                    else -> "Just say it." to "Type or tap the mic and tell me what you want, like \u201cplay lofi on YouTube\u201d. Turn on \u201cHey Blaze\u201d in Settings to call me hands-free."
                }
                Text(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp, color = c.ink))
                Spacer(Modifier.height(14.dp))
                Text(body, style = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, lineHeight = 24.sp, color = c.sub))
                Spacer(Modifier.height(24.dp))
                when (page) {
                    0 -> {
                        Choice("Free \u2014 start now", "Blaze Cloud picks the best free model for every message.", true) { brain = "free" }
                    }
                    1 -> Choice(if (a11y) "Phone control is on \u2713" else "Open Accessibility settings", if (a11y) "Nice. I can see and tap the screen now." else "Find Blaze in the list and switch it on.", a11y) {
                        if (!a11y) ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            Box(Modifier.fillMaxWidth().height(54.dp).clip(BlazeShapes.pill).glassButton(c).clickable {
                when (page) {
                    0 -> { brain = "free"; page = 1 }
                    1 -> page = 2
                    else -> finish(if (false) "" else "Introduce yourself in one sentence, then tell me three things you can do on my phone.")
                }
            }, contentAlignment = Alignment.Center) {
                ButtonText(when (page) { 0 -> "Continue"; 1 -> if (a11y) "Continue" else "Later"; else -> "Let's go" }, color = Color.White)
            }
        }
    }
}

@Composable
private fun Choice(title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Row(Modifier.fillMaxWidth().pageGlassSelectable(c, on).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = if (on) c.accent else c.ink))
            Spacer(Modifier.height(2.dp))
            Text(sub, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(20.dp).then(if (on) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f)), contentAlignment = Alignment.Center) {
            if (on) Text("\u2713", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.onAccent))
        }
    }
}
