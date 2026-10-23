package com.blaze.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Role
import com.blaze.assistant.voice.Voice
import kotlinx.coroutines.delay

/**
 * Call Blaze: a full-screen, hands-free voice call. You talk, Blaze answers out loud, and the
 * mic reopens on its own. Tap anywhere while Blaze is talking to cut in. "Bye" or the red
 * button ends the call. Questions and confirmations still show as cards you can tap.
 */
/** v0.31: the call screen is always the dark glass look (whatever the app theme). */
@Composable
fun CallScreen(onHangUp: () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalBlaze provides DarkColors.mono) { CallScreenBody(onHangUp) }
}

@Composable
private fun CallScreenBody(onHangUp: () -> Unit) {
    val c = LocalBlaze.current
    val view = LocalView.current
    val mood by Agent.mood.collectAsState()
    val voice by Voice.state.collectAsState()
    val level by Voice.level.collectAsState()
    val partial by Voice.partial.collectAsState()
    val messages by Agent.messages.collectAsState()
    val question by Agent.pendingQuestion.collectAsState()
    val ended by Voice.callEnded.collectAsState()
    val muted by Voice.callMute.collectAsState()
    val speakerOn by Voice.callSpeaker.collectAsState()
    val earpiece = !speakerOn
    remember { Voice.callMute.value = false; Voice.callSpeaker.value = !com.blaze.assistant.agent.Settings.bool("call_earpiece", false); 0 }
    val greet = remember { com.blaze.assistant.agent.Settings.bool("call_greet", true) }
    var seconds by remember { mutableStateOf(0) }
    val startEnded = remember { Voice.callEnded.value }

    fun hangUp() {
        Voice.inCall = false; Voice.callMuted = false; Voice.callMute.value = false
        Voice.cancelListening(); Voice.stopSpeaking()
        Voice.setEarpiece(false); Voice.hushBeeps(false)
        view.keepScreenOn = false
        Voice.lastTurnByVoice = false
        onHangUp()
    }

    DisposableEffect(Unit) {
        Voice.inCall = true; Voice.callMuted = false
        view.keepScreenOn = true
        Voice.hushBeeps(true)
        if (greet) Voice.speak(listOf("Hey, I'm here. What's up?", "Hi! What can I do?", "Hey, go ahead.").random())
        com.blaze.assistant.device.Haptics.tick(view.context)
        onDispose { if (Voice.inCall) { Voice.inCall = false; Voice.cancelListening(); Voice.stopSpeaking(); Voice.setEarpiece(false); Voice.hushBeeps(false); view.keepScreenOn = false } }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); seconds++ } }
    // Speaker and mute can change from the buttons or from Blaze itself (call_control).
    LaunchedEffect(speakerOn) { Voice.setEarpiece(!speakerOn) }
    LaunchedEffect(muted) { Voice.callMuted = muted; if (muted) Voice.cancelListening() }
    LaunchedEffect(ended) { if (ended != startEnded) hangUp() }
    BackHandler { hangUp() }

    // Watchdog: whenever nothing is happening (no thinking, no talking, no listening), reopen the mic.
    // This also starts the call and covers replies that weren't spoken.
    LaunchedEffect(mood, voice, muted, question) {
        if (muted || mood != Mood.IDLE || voice != Voice.State.IDLE) return@LaunchedEffect
        delay(if (seconds == 0) (if (greet) 1800L else 300L) else 900L)
        if (Voice.inCall && !Voice.callMuted && Agent.mood.value == Mood.IDLE && Voice.state.value == Voice.State.IDLE) Voice.conversationListen()
    }

    val lastReply = remember(messages) { messages.lastOrNull { it.role == Role.BLAZE || it.role == Role.ERROR }?.text.orEmpty() }
    val agentStatus by Agent.status.collectAsState()
    val step = remember(messages) { messages.lastOrNull()?.takeIf { it.role == Role.STEP }?.text.orEmpty() }
    val status = when {
        muted -> "Muted"
        voice == Voice.State.LISTENING -> "Listening"
        voice == Voice.State.SPEAKING -> "Blaze is talking · tap to cut in"
        mood != Mood.IDLE -> agentStatus.ifBlank { com.blaze.assistant.agent.Friendly.between(null) }
        else -> "Connecting"
    }
    val caption = when {
        voice == Voice.State.LISTENING && partial.isNotBlank() -> partial
        voice == Voice.State.LISTENING -> "Go ahead, I'm listening."
        mood != Mood.IDLE && step.isNotBlank() -> step
        else -> Md.plain(lastReply).ifBlank { "Say anything. Say \"bye\" to hang up." }
    }

    Box(Modifier.fillMaxSize().background(GlassTokens.bg).glassBackground(true)
        .clickable(remember { MutableInteractionSource() }, indication = null) {
            // Barge in: stop Blaze talking and listen right away.
            if (voice == Voice.State.SPEAKING && !muted) { Voice.stopSpeaking(); Voice.conversationListen() }
            else if (voice == Voice.State.IDLE && mood == Mood.IDLE && !muted) Voice.conversationListen()
        }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(28.dp))
            Label("CALL", color = c.ink.copy(alpha = 0.55f), size = 10.sp)
            Spacer(Modifier.height(6.dp))
            Text("Blaze", style = TextStyle(fontFamily = Type.Display, fontSize = 30.sp, color = c.ink))
            Spacer(Modifier.height(4.dp))
            Text("%d:%02d".format(seconds / 60, seconds % 60),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink.copy(alpha = 0.6f)))

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CallOrb(240.dp, mood, voice, level, muted)
            }

            Label(status.uppercase(), color = c.accent, size = 10.sp)
            Spacer(Modifier.height(10.dp))
            Text(caption, maxLines = 5, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 17.sp, lineHeight = 24.sp, color = c.ink))
            Spacer(Modifier.height(14.dp))

            // Questions and "are you sure?" still need a tap; show them right here.
            Box(Modifier.fillMaxWidth()) { Column { ConfirmCard(); QuestionCard() } }

            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 26.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                CallButton(if (muted) "Unmute" else "Mute", on = muted, onClick = {
                    Voice.callMute.value = !muted
                }) { col -> MicSlash(col, muted) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(76.dp).clip(CircleShape).background(Color(0xFFB4554F)).border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape).clickable { hangUp() },
                        contentAlignment = Alignment.Center) { PhoneGlyph(Color.White, 30.dp, hangUp = true) }
                    Spacer(Modifier.height(8.dp))
                    Label("End", color = c.ink.copy(alpha = 0.7f), size = 9.sp)
                }
                CallButton(if (earpiece) "Earpiece" else "Speaker", on = !earpiece, onClick = {
                    Voice.callSpeaker.value = earpiece
                }) { col -> SpeakerGlyph(col, !earpiece) }
            }
        }
    }
}

@Composable
private fun CallButton(label: String, on: Boolean, onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    val c = LocalBlaze.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp).clip(CircleShape).then(if (on) Modifier.background(GlassTokens.accent, CircleShape) else Modifier.glass(CircleShape, GlassIntensity.Medium, shadow = false))
            .clickable(onClick = onClick), contentAlignment = Alignment.Center) { glyph(if (on) GlassTokens.onAccent else GlassTokens.ink) }
        Spacer(Modifier.height(8.dp))
        Label(label, color = c.ink.copy(alpha = 0.7f), size = 9.sp)
    }
}

/** The big orb: grows with your voice, breathes while Blaze talks, spins while it thinks. */
@Composable
private fun CallOrb(size: Dp, mood: Mood, voice: Voice.State, level: Float, muted: Boolean) {
    val c = LocalBlaze.current
    val t = rememberInfiniteTransition(label = "call")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "s")
    val lvl by animateFloatAsState(if (voice == Voice.State.LISTENING) level else 0f, tween(120), label = "l")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val base = r * 0.58f
            when {
                muted -> drawCircle(c.ink.copy(alpha = 0.06f), base * 1.2f)
                voice == Voice.State.LISTENING -> {
                    drawCircle(c.accent.copy(alpha = 0.12f), base * (1.15f + lvl * 0.55f))
                    drawCircle(c.accent.copy(alpha = 0.22f), base * (1.05f + lvl * 0.3f))
                }
                voice == Voice.State.SPEAKING -> {
                    drawCircle(c.accent.copy(alpha = 0.10f), base * (1.2f + breathe * 0.35f))
                    drawCircle(c.accent.copy(alpha = 0.20f), base * (1.08f + breathe * 0.15f))
                }
                mood != Mood.IDLE -> drawArc(c.accent, spin, 90f, false,
                    topLeft = Offset(center.x - base * 1.2f, center.y - base * 1.2f),
                    size = androidx.compose.ui.geometry.Size(base * 2.4f, base * 2.4f), style = Stroke(4.dp.toPx()))
                else -> drawCircle(c.ink.copy(alpha = 0.08f), base * 1.2f)
            }
        }
        Box(Modifier.size(size * 0.58f).glass(CircleShape, GlassIntensity.Medium, shadow = false), contentAlignment = Alignment.Center) {
            BrandMark(mood, size * 0.3f)
        }
    }
}

/** Handset glyph (tilted down for hang up). */
@Composable
fun PhoneGlyph(color: Color, size: Dp, hangUp: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        rotate(if (hangUp) 135f else 0f) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.22f, h * 0.08f); lineTo(w * 0.38f, h * 0.08f); lineTo(w * 0.44f, h * 0.30f); lineTo(w * 0.34f, h * 0.38f)
                quadraticBezierTo(w * 0.44f, h * 0.56f, w * 0.62f, h * 0.66f)
                lineTo(w * 0.70f, h * 0.56f); lineTo(w * 0.92f, h * 0.62f); lineTo(w * 0.92f, h * 0.78f)
                quadraticBezierTo(w * 0.90f, h * 0.94f, w * 0.74f, h * 0.92f)
                quadraticBezierTo(w * 0.10f, h * 0.84f, w * 0.08f, h * 0.24f)
                quadraticBezierTo(w * 0.08f, h * 0.10f, w * 0.22f, h * 0.08f); close()
            }
            drawPath(p, color)
        }
    }
}

@Composable
private fun MicSlash(color: Color, slashed: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height; val s = 2.dp.toPx()
        drawRoundRect(color, Offset(w * 0.36f, h * 0.06f), androidx.compose.ui.geometry.Size(w * 0.28f, h * 0.52f),
            androidx.compose.ui.geometry.CornerRadius(w * 0.14f))
        drawArc(color, 0f, 180f, false, Offset(w * 0.22f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.5f), style = Stroke(s))
        drawLine(color, Offset(w / 2, h * 0.72f), Offset(w / 2, h * 0.92f), s)
        if (slashed) drawLine(color, Offset(w * 0.12f, h * 0.1f), Offset(w * 0.88f, h * 0.9f), s * 1.3f)
    }
}

@Composable
private fun SpeakerGlyph(color: Color, loud: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height; val s = 2.dp.toPx()
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.1f, h * 0.38f); lineTo(w * 0.28f, h * 0.38f); lineTo(w * 0.5f, h * 0.16f)
            lineTo(w * 0.5f, h * 0.84f); lineTo(w * 0.28f, h * 0.62f); lineTo(w * 0.1f, h * 0.62f); close()
        }
        drawPath(p, color)
        drawArc(color, -45f, 90f, false, Offset(w * 0.38f, h * 0.3f), androidx.compose.ui.geometry.Size(w * 0.3f, h * 0.4f), style = Stroke(s))
        if (loud) drawArc(color, -50f, 100f, false, Offset(w * 0.32f, h * 0.14f), androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.72f), style = Stroke(s))
    }
}
