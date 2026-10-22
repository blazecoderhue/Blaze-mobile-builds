package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.voice.Voice

/** Blaze's voice: which voice, how fast, how high, which language it listens in, and call options. */
@Composable
fun VoiceTuning() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    var voiceName by remember { mutableStateOf(Settings.str("tts_voice", "")) }
    var rate by remember { mutableStateOf(Settings.str("tts_rate", "1.0")) }
    var pitch by remember { mutableStateOf(Settings.str("tts_pitch", "1.0")) }
    var lang by remember { mutableStateOf(Settings.str("stt_lang", "")) }
    var showAll by remember { mutableStateOf(false) }
    var voices by remember { mutableStateOf(Voice.voices()) }
    LaunchedEffect(Unit) { var n = 0; while (voices.isEmpty() && n++ < 10) { kotlinx.coroutines.delay(500); voices = Voice.voices() } }

    Spacer(Modifier.height(22.dp))
    Label("Blaze's voice", modifier = Modifier.padding(bottom = 6.dp))
    Row(Modifier.fillMaxWidth().hairline(c.hair).clickable { Voice.preview() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("▶  Hear it", modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink))
        Label(voices.firstOrNull { it.name == voiceName }?.let { nice(it) } ?: "Phone default", size = 10.sp)
    }

    Label("Speed", modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    Chips(listOf("0.8" to "Slow", "1.0" to "Normal", "1.2" to "Quick", "1.5" to "Fast"), rate) { rate = it; Settings.setStr("tts_rate", it); Voice.preview() }
    Label("Pitch", modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    Chips(listOf("0.8" to "Deeper", "1.0" to "Normal", "1.2" to "Higher"), pitch) { pitch = it; Settings.setStr("tts_pitch", it); Voice.preview() }

    Label("Voice", modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    val phoneLang = java.util.Locale.getDefault().language
    val list = if (showAll) voices else voices.filter { it.locale.language == phoneLang }.take(12)
    VoiceRow("Phone default", "Whatever your phone's speech engine uses", voiceName.isBlank()) {
        voiceName = ""; Settings.setStr("tts_voice", ""); Voice.preview()
    }
    list.forEach { v ->
        VoiceRow(nice(v), if (v.isNetworkConnectionRequired) "Needs data" else "Works offline", voiceName == v.name) {
            voiceName = v.name; Settings.setStr("tts_voice", v.name); Voice.preview()
        }
    }
    if (voices.isEmpty()) Label("Your phone's speech engine is still loading voices.", size = 10.sp, modifier = Modifier.padding(vertical = 8.dp))
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (!showAll && voices.size > list.size) Label("Show all ${voices.size} voices", color = c.accent, size = 10.sp,
            modifier = Modifier.clickable { showAll = true }.padding(vertical = 10.dp).padding(end = 18.dp))
        ActionText("Get more voices", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
            try { ctx.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { android.widget.Toast.makeText(ctx, "Couldn't open speech settings.", android.widget.Toast.LENGTH_SHORT).show() }
        }.padding(vertical = 10.dp))
    }

    Label("Listen in", modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    Chips(listOf("" to "Phone", "en-US" to "English US", "en-GB" to "English UK", "en-ZA" to "English ZA"), lang) { lang = it; Settings.setStr("stt_lang", it) }

    Spacer(Modifier.height(22.dp))
    Label("Calls", modifier = Modifier.padding(bottom = 4.dp))
    var greet by remember { mutableStateOf(Settings.bool("call_greet", true)) }
    var ear by remember { mutableStateOf(Settings.bool("call_earpiece", false)) }
    var bye by remember { mutableStateOf(Settings.bool("call_bye", true)) }
    ToggleRow("Say hello when a call starts", "Blaze picks up with a short greeting", greet) { greet = !greet; Settings.setBool("call_greet", greet) }
    ToggleRow("Start on earpiece", "Hold the phone to your ear like a normal call. Off: loudspeaker", ear) { ear = !ear; Settings.setBool("call_earpiece", ear) }
    ToggleRow("\u201cBye\u201d hangs up", "Saying bye, thanks or that's all ends the call", bye) { bye = !bye; Settings.setBool("call_bye", bye) }
}

private fun nice(v: android.speech.tts.Voice): String {
    val tag = v.name.substringAfterLast("-x-", "").substringBefore('-').ifBlank { v.name.takeLast(6) }.uppercase()
    return "${v.locale.displayName} · $tag"
}

@Composable
private fun VoiceRow(title: String, sub: String, picked: Boolean, onPick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).hairline(if (picked) c.accent else c.hair).clickable(onClick = onPick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink))
            Label(sub, size = 9.sp)
        }
        if (picked) Label("✓", color = c.accent, size = 13.sp)
    }
}

@Composable
private fun Chips(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    val c = LocalBlaze.current.mono
    Row(Modifier.fillMaxWidth()) {
        options.forEach { (v, l) ->
            Box(Modifier.weight(1f).padding(end = 6.dp).hairline(if (value == v) c.ink else c.hair)
                .background(if (value == v) c.ink else c.paper).clickable { onPick(v) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                ButtonText(l, color = if (value == v) c.paper else c.ink, size = 13.sp)
            }
        }
    }
}
