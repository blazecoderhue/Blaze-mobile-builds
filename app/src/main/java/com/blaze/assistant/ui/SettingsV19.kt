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

