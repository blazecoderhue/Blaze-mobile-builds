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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.voice.myvoice.AudioClip
import com.blaze.assistant.voice.myvoice.MyVoice
import com.blaze.assistant.voice.myvoice.MyVoiceSetup
import com.blaze.assistant.voice.gemini.GeminiTts
import com.blaze.assistant.voice.gemini.GeminiVoice
import com.blaze.assistant.voice.myvoice.SpeedWatch
import com.blaze.assistant.voice.myvoice.VoicePack
import com.blaze.assistant.voice.myvoice.VoiceRecorder
import kotlinx.coroutines.launch

/** What the user reads aloud: varied sounds, ~15–20 s at a relaxed pace. */
const val MY_VOICE_SCRIPT = "Hi, this is my voice for Blaze. I'm reading this at my normal pace, the way I'd talk to a friend. " +
    "The quick brown fox jumps over the lazy dog. What's the weather like tomorrow? Remind me to call Mum at six, " +
    "and play something relaxing while I cook dinner."

const val MY_VOICE_TEST = "Hey, it's me. This is how Blaze sounds with my voice. Pretty cool, right?"

/** Where the setup is. Pure state, so the screenshot test can draw each step. */
data class MyVoiceUi(
    val chosen: Boolean,
    val pack: VoicePack.State,
    val metered: Boolean,
    val downloadMb: Int,
    val recording: Boolean = false,
    val seconds: Float = 0f,
    val level: Float = 0f,
    val recorded: AudioClip.Prepared? = null,
    val hasVoice: Boolean = false,
    val testing: Boolean = false,
    val test: MyVoice.TestResult? = null,
    val micDenied: Boolean = false,
    val message: String = "",
    /** A good recording saved on the phone, not confirmed with "Use this voice" yet. */
    val candidateSaved: Boolean = false,
    /** v0.41.0: the Gemini voice is the one Blaze speaks with. */
    val geminiChosen: Boolean = false,
)

/** Settings → Voice → "Blaze voice": System voice / My voice, the setup steps, and managing it. */
@Composable
fun MyVoiceSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pack by MyVoice.pack.collectAsState()
    val recorder = remember { VoiceRecorder(maxSec = 20) }
    val level by recorder.level.collectAsState()
    val seconds by recorder.seconds.collectAsState()
    var chosen by remember { mutableStateOf(MyVoice.chosen()) }
    var setupOpen by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recorded by remember { mutableStateOf<AudioClip.Prepared?>(null) }
    var hasVoice by remember { mutableStateOf(MyVoice.voiceReady()) }
    var testing by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<MyVoice.TestResult?>(null) }
    var micDenied by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(MyVoice.quality()) }
    var keepSlow by remember { mutableStateOf(MyVoice.keepWhenSlow()) }
    var candidateSaved by remember { mutableStateOf(MyVoice.candidateReady()) }
    var geminiChosen by remember { mutableStateOf(GeminiVoice.chosen()) }
    var geminiOpen by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (recording) recorder.cancel() } }

    fun finishRecording() {
        if (!recording) return
        recording = false
        val raw = recorder.stop()
        val p = AudioClip.prepareReference(raw, recorder.sampleRate)
        recorded = p; test = null
        message = AudioClip.verdictText(p.verdict)
        if (p.verdict == AudioClip.Verdict.OK) { MyVoice.saveCandidate(p); candidateSaved = true }
    }
    fun startRecording() {
        message = ""; recorded = null; test = null
        MyVoice.stop()
        val err = recorder.start { finishRecording() }
        if (err != null) message = err else recording = true
    }
    val micPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        micDenied = !ok
        if (ok) startRecording()
    }
    fun record() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) startRecording()
        else micPerm.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    val ui = MyVoiceUi(chosen, pack, MyVoice.onMeteredNetwork(), VoicePack.downloadMb(MyVoice.runtimeSpec().abi), recording, seconds, level,
        recorded, hasVoice, testing, test, micDenied, message, candidateSaved, geminiChosen)

    MyVoiceCard(
        ui = ui,
        setupOpen = !geminiOpen && (setupOpen || (chosen && !(MyVoice.packReady() && hasVoice))),
        geminiOpen = geminiOpen || geminiChosen,
        onPickGemini = {
            geminiOpen = true; setupOpen = false; message = ""
            if (GeminiVoice.hasKey()) { MyVoice.stop(); GeminiVoice.choose(); GeminiVoice.resetFailure(); geminiChosen = true; chosen = false }
        },
        onGeminiReady = { MyVoice.stop(); GeminiVoice.choose(); GeminiVoice.resetFailure(); geminiChosen = true; chosen = false },
        quality = quality, keepSlow = keepSlow,
        onPickSystem = { chosen = false; setupOpen = false; geminiOpen = false; geminiChosen = false; GeminiVoice.stop(); MyVoice.choose(false) },
        onPickMine = {
            geminiOpen = false
            if (MyVoice.packReady() && hasVoice) { chosen = true; geminiChosen = false; GeminiVoice.stop(); MyVoice.choose(true) } else setupOpen = true
        },
        onDownload = { scope.launch { MyVoice.download() } },
        onCancelDownload = { MyVoice.cancelDownload() },
        onRecord = { record() },
        onStop = { finishRecording() },
        onPlayRecording = { recorded?.let { MyVoice.playClip(it.samples, it.sampleRate) } },
        onTest = {
            testing = true; test = null; message = ""
            scope.launch { test = MyVoice.test(MY_VOICE_TEST); testing = false }
        },
        onUse = {
            if (MyVoice.useCandidate()) { hasVoice = true; candidateSaved = false; chosen = true; geminiChosen = false; geminiOpen = false; setupOpen = false; recorded = null; test = null
                message = "Done. Blaze speaks with your voice now." }
            else message = "Couldn't save your voice. Try recording again."
        },
        onRerecord = { setupOpen = true; recorded = null; test = null; message = "" },
        onDeleteVoice = { MyVoice.deleteVoice(); hasVoice = false; candidateSaved = false; chosen = false; recorded = null; test = null; message = "Your recording is deleted." },
        onDeletePack = { MyVoice.deletePack(); chosen = MyVoice.chosen(); message = "Voice model deleted. Blaze uses the phone voice." },
        onQuality = { quality = it; Settings.setStr("myvoice_quality", it) },
        onKeepSlow = { keepSlow = !keepSlow; Settings.setBool("myvoice_keep_slow", keepSlow); if (keepSlow) Settings.setStr("myvoice_slow_at", "0") },
        onOpenAppSettings = {
            try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        },
    )
}

@Composable
fun MyVoiceCard(
    ui: MyVoiceUi,
    setupOpen: Boolean,
    geminiOpen: Boolean = false,
    onPickGemini: () -> Unit = {},
    onGeminiReady: () -> Unit = {},
    quality: String = "auto",
    keepSlow: Boolean = false,
    onPickSystem: () -> Unit = {}, onPickMine: () -> Unit = {},
    onDownload: () -> Unit = {}, onCancelDownload: () -> Unit = {},
    onRecord: () -> Unit = {}, onStop: () -> Unit = {}, onPlayRecording: () -> Unit = {},
    onTest: () -> Unit = {}, onUse: () -> Unit = {}, onRerecord: () -> Unit = {},
    onDeleteVoice: () -> Unit = {}, onDeletePack: () -> Unit = {},
    onQuality: (String) -> Unit = {}, onKeepSlow: () -> Unit = {}, onOpenAppSettings: () -> Unit = {},
) {
    val c = LocalBlaze.current.mono
    val packReady = ui.pack is VoicePack.State.Ready
    val mineActive = ui.chosen && packReady && ui.hasVoice
    val steps = MyVoiceSetup.Inputs(packReady, ui.pack is VoicePack.State.Working, ui.recording, ui.recorded?.verdict, ui.candidateSaved, ui.hasVoice)
    val step3 = MyVoiceSetup.step3(steps)

    Spacer(Modifier.height(22.dp))
    Label("Blaze voice", modifier = Modifier.padding(bottom = 6.dp))
    val geminiActive = ui.geminiChosen
    Choice("System voice", "Your phone's built-in speech voice", !mineActive && !setupOpen && !geminiActive && !geminiOpen, onPickSystem)
    Choice("My voice", when {
        mineActive -> "Blaze speaks with your voice, made on this phone"
        else -> "Record yourself for about 15 seconds and Blaze talks like you"
    }, (mineActive || setupOpen) && !geminiActive && !geminiOpen, onPickMine)
    Choice("Gemini voice", "Natural voices from Google, with your Gemini key", geminiActive || geminiOpen, onPickGemini)
    if (geminiOpen) GeminiVoicePanel(active = geminiActive, onReady = onGeminiReady)

    if (setupOpen) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).hairline(c.hair).padding(14.dp)) {
            // 1. Download
            StepTitle("1", "Download the voice model", packReady)
            when (val p = ui.pack) {
                is VoicePack.State.Ready -> Body("Downloaded. Works offline.")
                is VoicePack.State.Working -> {
                    Body(VoicePack.label(p))
                    Bar(VoicePack.percent(p) / 100f)
                    Row(Modifier.padding(top = 6.dp)) { ActionText("Pause", color = c.accent, size = 11.sp, modifier = Modifier.clickable(onClick = onCancelDownload).padding(vertical = 6.dp)) }
                }
                is VoicePack.State.Failed -> {
                    Body(p.message, color = c.accent)
                    PillButton("Download", primary = true, modifier = Modifier.padding(top = 8.dp), onClick = onDownload)
                }
                else -> {
                    Body("One-time ${ui.downloadMb} MB download (about 220 MB on the phone). Wi-Fi recommended." +
                        if (ui.metered) " You're on mobile data right now." else "")
                    PillButton("Download", primary = true, modifier = Modifier.padding(top = 8.dp), onClick = onDownload)
                }
            }

            // 2. Record
            Spacer(Modifier.height(16.dp))
            StepTitle("2", "Record your voice", MyVoiceSetup.step2Done(steps))
            Body("Somewhere quiet, phone about a hand away. Read this out loud at your normal pace:")
            Text("\u201C$MY_VOICE_SCRIPT\u201D", modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(c.weak.copy(alpha = 0.12f), BlazeShapes.small).padding(12.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 22.sp, color = c.ink))
            if (ui.recording) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(c.accent, BlazeShapes.pill))
                    Spacer(Modifier.width(8.dp))
                    Text("%d:%02d / 0:20".format(ui.seconds.toInt() / 60, ui.seconds.toInt() % 60),
                        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) { Bar(ui.level) }
                }
                PillButton("Stop", primary = true, warm = true, modifier = Modifier.padding(top = 10.dp), onClick = onStop)
            } else {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (ui.recorded != null) "Re-record" else "Record", primary = ui.recorded == null, onClick = onRecord)
                    if (ui.recorded != null && ui.recorded.samples.isNotEmpty()) PillButton("Play back", primary = false, onClick = onPlayRecording)
                }
            }
            if (ui.micDenied) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Body("Blaze needs the microphone to record you.", color = c.accent, modifier = Modifier.weight(1f))
                ActionText("Allow", color = c.accent, size = 11.sp, modifier = Modifier.clickable(onClick = onOpenAppSettings).padding(vertical = 6.dp))
            }

            // 3. Test
            val canTest = step3.canTest
            Spacer(Modifier.height(16.dp))
            StepTitle("3", "Hear it", ui.test?.ok == true)
            when {
                ui.testing -> { Body("Making a test sentence in your voice… the first time takes a little while."); Bar(-1f) }
                ui.test != null && !ui.test.ok -> Body(ui.test.error, color = c.accent)
                ui.test != null -> {
                    Body("Made %.1f s of speech in %.1f s. %s".format(ui.test.audioSec, ui.test.makeMs / 1000f, SpeedWatch.describe(ui.test.rtf)))
                    if (ui.test.rtf > 1f) Body("Turn on \u201CKeep my voice when it's slow\u201D below to always hear yourself (with pauses between sentences).", color = c.sub)
                }
                else -> Body(step3.hint)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canTest && !ui.testing) PillButton(if (ui.test == null) "Try it" else "Again", primary = ui.test == null, onClick = onTest)
                if (canTest && ui.test?.ok == true) PillButton("Use this voice", primary = true, onClick = onUse)
            }
        }
    }

    if (ui.message.isNotEmpty()) Body(ui.message, color = c.accent, modifier = Modifier.padding(top = 8.dp))

    if (packReady || ui.hasVoice) {
        Label("My voice quality", modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("auto" to "Auto", "fast" to "Fast", "balanced" to "Balanced", "best" to "Best").forEach { (v, l) ->
                Box(Modifier.weight(1f).padding(end = 6.dp).hairline(if (quality == v) c.ink else c.hair)
                    .background(if (quality == v) c.ink else c.paper).clickable { onQuality(v) }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center) { ButtonText(l, color = if (quality == v) c.paper else c.ink, size = 13.sp) }
            }
        }
        Body("Fast is slightly quicker; Best sounds a little smoother.", modifier = Modifier.padding(top = 4.dp))
        ToggleRow("Keep my voice when it's slow", "Off: if your phone can't keep up, Blaze switches to the phone voice and tells you", keepSlow, onKeepSlow)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (ui.hasVoice && !setupOpen) ActionText("Re-record", color = c.accent, size = 10.sp, modifier = Modifier.clickable(onClick = onRerecord).padding(vertical = 10.dp).padding(end = 14.dp))
            if (ui.hasVoice) ActionText("Delete my voice", color = c.accent, size = 10.sp, modifier = Modifier.clickable(onClick = onDeleteVoice).padding(vertical = 10.dp).padding(end = 14.dp))
            if (packReady) ActionText("Delete model", color = c.accent, size = 10.sp, modifier = Modifier.clickable(onClick = onDeletePack).padding(vertical = 10.dp))
        }
    }
    Body("Everything happens on this phone. Your recording is never uploaded.", modifier = Modifier.padding(top = 6.dp))
}

/** v0.41.0 Settings › Voice › Gemini voice: pick one of Google's voices, hear it, and (when needed) add the key. */
@Composable
fun GeminiVoicePanel(active: Boolean, onReady: () -> Unit) {
    val c = LocalBlaze.current.mono
    var voice by remember { mutableStateOf(GeminiVoice.voice()) }
    var hasKey by remember { mutableStateOf(GeminiVoice.hasKey()) }
    var keyText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var noteBad by remember { mutableStateOf(false) }
    fun hear() {
        busy = true; note = ""
        GeminiVoice.preview("Hi, I'm Blaze. This is how I sound with this voice.", voice) { err ->
            busy = false
            if (err == null) { note = if (active) "That's the voice Blaze uses now." else "Blaze speaks with this voice now."; noteBad = false; onReady() }
            else { note = err; noteBad = true }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp).hairline(c.hair).padding(14.dp)) {
        if (!hasKey) {
            Body("Paste your Gemini API key. You can get one free at aistudio.google.com. It stays on this phone and in your Blaze account.")
            BlazeTextField(keyText, { keyText = it.trim() }, "Gemini API key", modifier = Modifier.fillMaxWidth().padding(top = 8.dp), mono = true)
            PillButton("Save key", primary = true, modifier = Modifier.padding(top = 8.dp), onClick = {
                if (keyText.length < 20) { note = "That doesn't look like a Gemini key."; noteBad = true }
                else if (GeminiVoice.saveKey(keyText)) { hasKey = true; keyText = ""; note = ""; hear() }
                else { note = "Couldn't save the key. Try again."; noteBad = true }
            })
        } else {
            Body("Pick a voice. Speech is made by Google over the internet, with your own Gemini key.")
            GeminiTts.VOICES.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    row.forEach { (v, word) ->
                        Column(Modifier.weight(1f).padding(end = 6.dp).hairline(if (voice == v) c.ink else c.hair)
                            .background(if (voice == v) c.ink else c.paper).clickable { voice = v; GeminiVoice.setVoice(v); hear() }.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            ButtonText(v, color = if (voice == v) c.paper else c.ink, size = 12.sp)
                            Label(word, color = if (voice == v) c.paper else c.sub, size = 8.sp)
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!busy) PillButton(if (active) "Hear it" else "Use this voice", primary = !active, onClick = { hear() })
            }
            if (busy) { Body("Making a sample\u2026", modifier = Modifier.padding(top = 6.dp)); Bar(-1f) }
        }
        if (note.isNotEmpty()) Body(note, color = if (noteBad) c.accent else c.sub, modifier = Modifier.padding(top = 8.dp))
        if (active) Body("If Gemini can't be reached, Blaze uses the phone voice and tells you.", modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Choice(title: String, sub: String, picked: Boolean, onPick: () -> Unit) {
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
private fun StepTitle(n: String, title: String, done: Boolean) {
    val c = LocalBlaze.current.mono
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Box(Modifier.size(22.dp).background(if (done) c.accent else c.weak.copy(alpha = 0.25f), BlazeShapes.pill), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else n, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (done) c.paper else c.ink))
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = c.ink))
    }
}

@Composable
private fun Body(text: String, color: androidx.compose.ui.graphics.Color = LocalBlaze.current.sub, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = color))
}

/** A thin progress / level bar. A negative fraction draws an indeterminate bar. */
@Composable
private fun Bar(fraction: Float) {
    val c = LocalBlaze.current.mono
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).background(c.weak.copy(alpha = 0.2f), BlazeShapes.pill)) {
        if (fraction < 0f) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxSize(), color = c.accent, trackColor = c.weak.copy(alpha = 0.2f))
        else Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent, BlazeShapes.pill))
    }
}
