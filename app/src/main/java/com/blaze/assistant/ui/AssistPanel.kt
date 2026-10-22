package com.blaze.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Attachment
import com.blaze.assistant.agent.Attachments
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Role
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.voice.Voice
import kotlinx.coroutines.launch

/** Header buttons of the assistant panel, in order (pure, tested). Compact shows Magic Hand, Show more and Close (v0.24.7: the hand). */
object PanelButtons {
    enum class B(val desc: String) { HAND("Magic Hand: point at anything on screen"), NEW("New chat"), PIN("Pin the floating orb"), OPEN("Open Blaze"), MORE("Show more"), LESS("Show less"), CLOSE("Close") }
    fun of(compact: Boolean): List<B> = if (compact) listOf(B.HAND, B.MORE, B.CLOSE) else listOf(B.HAND, B.NEW, B.PIN, B.OPEN, B.LESS, B.CLOSE)
}

/**
 * Compact chat over any app (v0.24.6 look): a fully rounded dark Blaze card, the mascot with a listening pulse,
 * round icon buttons (described for TalkBack) instead of NEW / PIN / OPEN / LESS / ×, a rounded input pill that
 * also takes pictures from the keyboard, round mic / stop and send buttons, and Blaze's particles running along the
 * screen edges while it's summoned (they stop when it's dismissed).
 * [preview*] parameters are for screenshots only.
 */
@Composable
fun AssistPanel(autoListen: Boolean, onClose: () -> Unit, onPin: () -> Unit, onExpand: () -> Unit,
                previewMessages: List<ChatMessage>? = null, previewListening: Boolean = false, previewCompact: Boolean? = null) {
    CompositionLocalProvider(LocalBlaze provides DarkColors) {
        AssistPanelBody(autoListen, onClose, onPin, onExpand, previewMessages, previewListening, previewCompact)
    }
}

@Composable
private fun AssistPanelBody(autoListen: Boolean, onClose: () -> Unit, onPin: () -> Unit, onExpand: () -> Unit,
                            previewMessages: List<ChatMessage>?, previewListening: Boolean, previewCompact: Boolean?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val live by Agent.messages.collectAsState()
    val messages = previewMessages ?: live
    val mood by Agent.mood.collectAsState()
    val voice0 by Voice.state.collectAsState()
    val voice = if (previewListening) Voice.State.LISTENING else voice0
    val partial by Voice.partial.collectAsState()
    val level by Voice.level.collectAsState()
    val agentStatus by Agent.status.collectAsState()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(listOf<Attachment>()) }
    var loading by remember { mutableStateOf(false) }
    var compact by remember { mutableStateOf(previewCompact ?: Settings.bool("compact_panel", true)) }
    val recentAll = messages.filter { it.role != Role.STEP || it == messages.lastOrNull() }.takeLast(8)
    val recent = if (compact) recentAll.filter { it.role != Role.USER }.takeLast(1) else recentAll
    val list = rememberLazyListState()
    var touched by remember { mutableStateOf(false) }
    var wasBusy by remember { mutableStateOf(false) }
    val busy = mood != Mood.IDLE
    val listening = voice == Voice.State.LISTENING
    LaunchedEffect(mood) {
        if (mood != Mood.IDLE) { wasBusy = true; return@LaunchedEffect }
        if (wasBusy && compact && !touched && Settings.bool("auto_hide_panel", true) && Agent.pendingQuestion.value == null) {
            kotlinx.coroutines.delay(7000)
            if (mood == Mood.IDLE && !touched && text.isEmpty() && Voice.state.value == Voice.State.IDLE && Agent.pendingQuestion.value == null) onClose()
        }
    }
    LaunchedEffect(recent.size, recent.lastOrNull()?.text?.length) { if (recent.isNotEmpty()) list.animateScrollToItem(recent.size - 1) }

    fun listen() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            note = "Open Blaze once and allow the microphone."; return
        }
        Voice.listen(onText = { if (it.isNotBlank()) Agent.send(it, byVoice = true) }, onError = { note = it })
    }
    LaunchedEffect(Unit) { if (autoListen && mood == Mood.IDLE && previewMessages == null) listen() }
    fun addImage(uri: android.net.Uri, mime: String) {
        touched = true; loading = true
        scope.launch { Attachments.load(ctx, uri, mime)?.let { pending = pending + it }; loading = false }
    }
    fun submit() {
        if (text.isBlank() && pending.isEmpty()) return
        val cmd = if (text.isBlank()) null else CommandRunner.run(ctx, text.trim(), openSettings = {
            ctx.startActivity(android.content.Intent(ctx, com.blaze.assistant.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }, listen = { })
        if (cmd == "") { text = ""; return }
        // v0.30.6 rapid messages: sending while Blaze works is fine (the message joins the turn).
        Agent.send(cmd ?: text, attachments = pending); text = ""; pending = emptyList()
    }

    val card = RoundedCornerShape(28.dp)
    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { Voice.cancelListening(); onClose() }) {
        if (Settings.particles) EdgeParticles(energy = if (busy || listening) 1f else 0.6f)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, null) { }
                .statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .shadow(20.dp, card, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(card).background(Glass.surface).border(1.dp, Glass.border, card)
        ) {
            // v0.33.0 Screen Doctor: "What's this?" over the app you summoned Blaze on.
            if (previewMessages != null && !busy && com.blaze.assistant.assist.ScreenDoctor.fresh()) {   // v0.39.0: the Duo strip has the chip now
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("What's this?", modifier = Modifier.clip(RoundedCornerShape(50)).background(Glass.raised)
                        .clickable { Voice.cancelListening(); touched = true
                            val sd = com.blaze.assistant.assist.ScreenDoctor
                            Agent.send(sd.prompt(sd.text, sd.app, text.trim()), attachments = sd.attachments()); sd.clear(); text = "" }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Glass.cyan))
                    Spacer(Modifier.width(8.dp))
                    Text("Explain, translate or fix what's on screen", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Glass.sub))
                }
            }
            // v0.39.0 home-button sheet: who's listening (Halo x Blaze), what Blaze sees, and one-tap chips.
            if (previewMessages == null) AssistDuoStrip(listening = listening, busy = busy, partial = partial,
                onChip = { prompt, withScreen ->
                    Voice.cancelListening(); touched = true
                    val sd = com.blaze.assistant.assist.ScreenDoctor
                    if (withScreen && sd.fresh()) { Agent.send(sd.prompt(sd.text, sd.app, prompt), attachments = sd.attachments()) } else Agent.send(prompt)
                },
                onType = { Voice.cancelListening(); touched = true })
            // Header: mascot, name + status, icon buttons.
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                MascotGlyph(if (compact) 34.dp else 40.dp, pulse = listening || busy, level = level)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    if (!compact) Text("Blaze", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Glass.text))
                    val status = when {
                        listening -> "Listening"
                        voice == Voice.State.SPEAKING -> "Speaking"
                        busy -> agentStatus.ifBlank { com.blaze.assistant.agent.Friendly.between(null) }
                        else -> "Ready"
                    }
                    Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = if (busy || voice != Voice.State.IDLE) Glass.cyan else Glass.sub))
                }
                PanelButtons.of(compact).forEach { b ->
                    Spacer(Modifier.width(4.dp))
                    GlassIconButton(when (b) {
                        PanelButtons.B.HAND -> Icons.Rounded.PanTool
                        PanelButtons.B.NEW -> Icons.Rounded.AddComment; PanelButtons.B.PIN -> Icons.Rounded.PushPin
                        PanelButtons.B.OPEN -> Icons.Rounded.OpenInFull; PanelButtons.B.MORE -> Icons.Rounded.UnfoldMore
                        PanelButtons.B.LESS -> Icons.Rounded.UnfoldLess; PanelButtons.B.CLOSE -> Icons.Rounded.Close
                    }, b.desc, size = 36.dp, bg = Glass.raised) {
                        when (b) {
                            PanelButtons.B.HAND -> {
                                // v0.24.7: summon Magic Hand over the app underneath; the panel gets out of the way.
                                val err = com.blaze.assistant.phone.MagicHand.summon(ctx)
                                if (err != null) note = err else { Voice.cancelListening(); onClose() }
                            }
                            PanelButtons.B.NEW -> { Voice.cancelListening(); Agent.clear(); text = "" }
                            PanelButtons.B.PIN -> onPin()
                            PanelButtons.B.OPEN -> onExpand()
                            PanelButtons.B.MORE, PanelButtons.B.LESS -> { compact = !compact; touched = true; Settings.setBool("compact_panel", compact) }
                            PanelButtons.B.CLOSE -> { Voice.cancelListening(); onClose() }
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = if (compact) 150.dp else 300.dp).padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Spacer(Modifier.height(2.dp)) }
                    items(recent, key = { it.id }) { m ->
                        when (m.role) {
                            Role.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                Text(m.text, modifier = Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(20.dp)).background(Glass.blue).padding(horizontal = 14.dp, vertical = 9.dp),
                                    style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = GlassTokens.onAccent))
                            }
                            Role.STEP -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(Glass.orange))
                                Spacer(Modifier.width(8.dp))
                                Text(m.text.lines().lastOrNull().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Glass.sub))
                            }
                            else -> SelectionContainer {
                                Text(if (m.role == Role.BLAZE || m.role == Role.USER) Md.render(m.text, Glass.cyan, Glass.raised2) else AnnotatedString(m.text),
                                    style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 22.sp,
                                        color = if (m.role == Role.ERROR) Glass.orange else Glass.text))
                            }
                        }
                    }
                    item { Spacer(Modifier.height(2.dp)) }
                }
            }
            ConfirmCard()
            QuestionCard()
            if (listening || note.isNotEmpty()) {
                Text(if (note.isNotEmpty()) note else partial.ifBlank { "Listening\u2026" },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = TextStyle(fontFamily = Type.Display, fontSize = 19.sp, color = if (note.isNotEmpty()) Glass.orange else Glass.text))
            }
            if (pending.isNotEmpty() || loading) AttachmentThumbs(pending, loading) { a -> pending = pending - a }
            // Input: rounded pill, round mic / stop and send.
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).clip(Glass.pill).background(Glass.raised).border(1.dp, Glass.border, Glass.pill)
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (text.isEmpty()) Text("Ask Blaze", style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = Glass.weak))
                    AcceptImages(onImage = { uri, mime -> addImage(uri, mime) }) {
                        BasicTextField(text, { text = it; touched = true },
                            textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = Glass.text),
                            cursorBrush = SolidColor(Glass.blue), maxLines = 3,
                            keyboardOptions = ComposerKeys.Message,   // v0.28.1: Enter = new line
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.width(8.dp))
                val micOn = voice != Voice.State.IDLE
                GlassIconButton(if (micOn) Icons.Rounded.Stop else Icons.Rounded.Mic, when (voice) {
                    Voice.State.LISTENING -> "Stop listening"; Voice.State.SPEAKING -> "Stop speaking"; else -> "Speak to Blaze" },
                    size = 46.dp, bg = if (micOn) Glass.blue else Glass.raised2) {
                    note = ""; touched = true
                    when (voice) {
                        Voice.State.LISTENING -> Voice.cancelListening()
                        Voice.State.SPEAKING -> Voice.stopSpeaking()
                        else -> listen()
                    }
                }
                Spacer(Modifier.width(6.dp))
                if (busy) GlassIconButton(Icons.Rounded.Stop, "Stop Blaze", size = 46.dp, bg = Glass.orange) { Agent.stop() }
                else GlassIconButton(Icons.Rounded.Send, "Send", size = 46.dp,
                    bg = if (text.isNotBlank() || pending.isNotEmpty()) Glass.blue else Glass.raised2) { submit() }
            }
        }
    }
}

/**
 * v0.39.0 the home-button sheet's top strip: "Blaze & Halo" with the Duo dots and a plain status, "Sees: <app>"
 * (from the assist structure the system hands Blaze, or Awareness), the live words while listening, and chips:
 * What is this? / Reply for me / @halo / Type instead. Replies come back as drafts with a Send button (ConfirmCard).
 */
@Composable
private fun AssistDuoStrip(listening: Boolean, busy: Boolean, partial: String, onChip: (String, Boolean) -> Unit, onType: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sd = com.blaze.assistant.assist.ScreenDoctor
    val label = remember(sd.app) {
        val pkg = sd.app
        if (pkg.isBlank()) com.blaze.assistant.agent.Awareness.get("screen")?.title.orEmpty()
        else try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg.substringAfterLast('.') }
    }
    val sees = com.blaze.assistant.agent.SimpleHome.seesLine(label)
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(DuoColors.blazeBlue))
            Spacer(Modifier.width(3.dp))
            Box(Modifier.size(9.dp).clip(CircleShape).background(DuoColors.haloGold))
            Spacer(Modifier.width(8.dp))
            Text("Blaze & Halo", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Glass.text))
            Spacer(Modifier.width(8.dp))
            Text(when { listening -> "Listening\u2026 let go to finish"; busy -> "Working"; label.isNotBlank() -> "Done \u00b7 still in $label"; else -> "Ready" },
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Glass.sub))
        }
        if (sees.isNotBlank()) Text(sees, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Glass.cyan))
        if (listening && partial.isNotBlank()) Text("\u201c$partial\u201d", modifier = Modifier.padding(top = 6.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, color = Glass.text))
        if (!busy) Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("What is this?" to ("Explain what's on my screen in plain words." to true),
                "Reply for me" to ("Write a reply for me to what's on my screen. Show it as a draft; don't send until I tap Send." to true),
                "@halo" to ("@halo what do you think about what's on my screen?" to true)).forEach { (l, v) ->
                Text(l, modifier = Modifier.clip(RoundedCornerShape(50)).background(Glass.raised).clickable { onChip(v.first, v.second) }
                    .padding(horizontal = 12.dp, vertical = 7.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Glass.text))
            }
            Text("Type instead", modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onType() }.padding(horizontal = 12.dp, vertical = 7.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Glass.sub))
        }
    }
}
