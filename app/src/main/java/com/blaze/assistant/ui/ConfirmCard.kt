package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Agent
import androidx.compose.ui.graphics.Color

/** Blaze's mid-task question: tap an option, or type your own answer. */
@Composable
fun QuestionCard() {
    val p by Agent.pendingQuestion.collectAsState()
    val q = p ?: return
    QuestionView(q.question, q.options, q.chat, key = q) { Agent.answerQuestion(it) }
}

/**
 * The ask_user card itself (also used by screenshots). v0.24.0: it reads as Blaze's newest message: the question
 * in Blaze's bubble, the answers as rounded pills under it, and your own answer in a small pill field.
 */
@Composable
fun QuestionView(question: String, options: List<String>, chat: String = "", key: Any? = question, onAnswer: (String) -> Unit = {}) {
    val c = LocalBlaze.current
    var typing by remember(key) { mutableStateOf(false) }
    var text by remember(key) { mutableStateOf("") }
    // v0.31.0: a glass card (GlassCard) instead of a bubble.
    GlassCard(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp).appearIn(true), intensity = GlassIntensity.High) {
        Column {
            Label(if (chat.isNotBlank()) "Question · ${chat.take(30)}" else "Blaze is asking", color = c.accent, size = 9.sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(question, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp, color = c.ink))
        }
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, o -> SoftPill(o, lead = "${i + 1}") { onAnswer(o) } }
            if (!typing) SoftPill("Type my own answer…", lead = "${options.size + 1}", fill = c.bubble, ink = c.sub) { typing = true }
            else Row(Modifier.widthIn(max = 340.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp), GlassIntensity.Low, shadow = false).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (text.isEmpty()) Text("Your answer", style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
                    BasicTextField(text, { text = it }, textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (text.isBlank()) c.hair else c.accent)
                    .clickable { if (text.isNotBlank()) onAnswer(text.trim()) }.semantics { contentDescription = "Send answer" }, contentAlignment = Alignment.Center) {
                    SendMark(if (text.isBlank()) c.paper else c.onAccent, stop = false)
                }
            }
        }
    }
}

/** "Send a text to Mary?" with Allow / Don't. Shown wherever Blaze is on screen. */
@Composable
fun ConfirmCard() {
    val c = LocalBlaze.current
    val p by Agent.pendingConfirm.collectAsState()
    val pc = p ?: return
    // v0.39.0: a message waiting for the user's tap reads as a draft: "Draft · not sent yet" with Not now / Send.
    val draft = com.blaze.assistant.agent.SimpleHome.isDraft(pc.title, pc.action)
    GlassCard(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp).appearIn(true), intensity = GlassIntensity.High) {
        Column {
            Label(if (draft) "Draft \u00b7 not sent yet" else "Needs your OK", color = c.accent, size = 9.sp)
            Spacer(Modifier.height(4.dp))
            Text(pc.title, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, color = c.ink))
            if (pc.detail.isNotBlank()) Text(pc.detail, modifier = Modifier.padding(top = 4.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.body))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth()) {
            Box(Modifier.weight(1f).height(46.dp).glass(RoundedCornerShape(23.dp), GlassIntensity.Low, shadow = false).clickable { Agent.answerConfirm(false) },
                contentAlignment = Alignment.Center) { Label(if (draft) "Not now" else "Don't", color = c.ink, size = 11.sp) }
            Box(Modifier.weight(1f).height(46.dp).glassPrimary(BlazeShapes.pill).clickable { Agent.answerConfirm(true) },
                contentAlignment = Alignment.Center) { ButtonText(if (draft) "Send" else pc.action, color = GlassTokens.onPrimary(c.dark)) }
        }
        // v0.45.3 Normal mode commands: allow this kind of command for the rest of the chat, or always (never for risky ones).
        if (pc.grants) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp).widthIn(max = 340.dp).fillMaxWidth()) {
            Box(Modifier.weight(1f).height(40.dp).glass(RoundedCornerShape(20.dp), GlassIntensity.Low, shadow = false)
                .clickable { Agent.answerConfirm(true, com.blaze.assistant.agent.CommandPolicy.Grant.CHAT) }, contentAlignment = Alignment.Center) {
                Label("Allow for this chat", color = c.ink, size = 10.sp) }
            Box(Modifier.weight(1f).height(40.dp).glass(RoundedCornerShape(20.dp), GlassIntensity.Low, shadow = false)
                .clickable { Agent.answerConfirm(true, com.blaze.assistant.agent.CommandPolicy.Grant.ALWAYS) }, contentAlignment = Alignment.Center) {
                Label("Always allow this command", color = c.ink, size = 10.sp) }
        }
    }
}
