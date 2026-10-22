package com.blaze.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Duo
import com.blaze.assistant.agent.Role

/*
 * v0.32.0 the Meeting room. When Blaze and Halo take on a task they work it out here first: proposals, critiques, code,
 * which tools they're using (and the searches they run), until they both agree. The verdict lands in the main chat.
 * You can read along and drop in at any time; when they need you, they ask in the main chat and the room waits.
 */

/** Label for a room's state. */
private fun roomHead(meta: String): String = when (Duo.cardState(meta)) {
    Duo.LIVE -> "In session"
    Duo.WAITING -> "Waiting for you"
    Duo.DONE -> Duo.cardDeliverer(meta)?.let { "Agreed · ${it.display} gave the verdict" } ?: "Agreed"
    else -> "Paused"
}

/** The room's card in the main chat: what they're working on, who's talking, how far along, and Open. */
@Composable
fun RoomCard(m: ChatMessage) {
    val c = LocalBlaze.current
    val rooms by Agent.rooms.collectAsState()
    val status by Agent.status.collectAsState()
    val list = rooms[m.id].orEmpty()
    val talk = list.count { it.role == Role.BLAZE }
    val state = Duo.cardState(m.meta)
    val live = state == Duo.LIVE
    val last = list.lastOrNull { it.role == Role.BLAZE }
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()
        .glass(GlassTokens.cardShape, if (state == Duo.WAITING) GlassIntensity.High else GlassIntensity.Medium, shadow = false)
        .clickable { Agent.roomOpen.value = m.id }.padding(14.dp)
        .semantics { contentDescription = "Meeting room. ${roomHead(m.meta)}. Tap to open." }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DuoMark()
            Spacer(Modifier.width(8.dp))
            Label("MEETING ROOM · BLAZE × HALO", color = c.accent, size = 9.sp)
            Spacer(Modifier.weight(1f))
            Label("$talk msg" + if (talk == 1) "" else "s", size = 9.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(Md.plain(m.text), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, color = c.ink))
        Spacer(Modifier.height(6.dp))
        if (live) Shimmer(status.ifBlank { "Blaze and Halo are talking it through" }, Modifier.fillMaxWidth())
        else {
            val line = if (last != null && state != Duo.DONE) "${Duo.Who.of(last.speaker).display}: ${Md.plain(last.text).replace('\n', ' ').take(110)}" else roomHead(m.meta)
            Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.body))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionText(if (live) "Watch" else "Open", color = c.accent, size = 10.sp)
            if (state == Duo.WAITING) Label("They asked you something", size = 9.sp)
        }
    }
}

/** The blue drop and the gold ring, side by side (the duo's little mark). */
@Composable
private fun DuoMark() {
    Canvas(Modifier.size(width = 20.dp, height = 10.dp)) {
        val r = size.height / 2f
        drawCircle(DuoColors.blazeBlue, radius = r * 0.8f, center = androidx.compose.ui.geometry.Offset(r, r))
        drawCircle(DuoColors.haloGold, radius = r * 0.8f, center = androidx.compose.ui.geometry.Offset(size.width - r, r), style = Stroke(1.4.dp.toPx()))
    }
}

/** The full-screen room: the transcript (with tool steps), and a box to drop in. */
@Composable
fun MeetingRoomScreen(roomId: Long, onDismiss: () -> Unit) {
    val c = LocalBlaze.current
    val rooms by Agent.rooms.collectAsState()
    val messages by Agent.messages.collectAsState()
    val duoLive by Agent.duoLive.collectAsState()
    val status by Agent.status.collectAsState()
    val card = messages.firstOrNull { it.id == roomId }
    val list = rooms[roomId].orEmpty()
    val state = card?.meta?.let { Duo.cardState(it) } ?: Duo.STOPPED
    val state0 = rememberLazyListState()
    val lastLen = list.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(list.size, lastLen) { if (list.isNotEmpty()) state0.scrollToItem(list.size - 1, 100_000) }
    Box(Modifier.fillMaxSize().background(c.paper)
        // swallow taps so nothing under the room reacts
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DuoMark(); Spacer(Modifier.width(8.dp))
                        Text("Meeting room", style = TextStyle(fontFamily = Type.Sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
                    }
                    Text(card?.text?.let { Md.plain(it) }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                }
                ActionText("×", size = 18.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss)
                    .semantics { contentDescription = "Close the meeting room" }.padding(14.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
            LazyColumn(state = state0, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { m -> RoomEntry(m, live = m.id in duoLive) }
                if (state == Duo.LIVE) item(key = "live") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp)) {
                        ThinkingOrb(status.ifBlank { "They're thinking" }, size = 12.dp)
                        Spacer(Modifier.width(6.dp))
                        Shimmer(status.ifBlank { "Thinking…" })
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
            RoomFooter(state, card?.meta.orEmpty())
        }
    }
}

@Composable
private fun RoomEntry(m: ChatMessage, live: Boolean) {
    val c = LocalBlaze.current
    when (m.role) {
        Role.USER -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Label("YOU", size = 9.sp, modifier = Modifier.padding(end = 4.dp, bottom = 2.dp))
            Text(m.text, modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).background(c.faint).padding(horizontal = 12.dp, vertical = 8.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.ink))
        }
        Role.STEP -> {
            val who = Duo.Who.of(m.speaker)
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(DuoColors.main(who).copy(alpha = 0.8f)))
                Spacer(Modifier.width(8.dp))
                Text("${who.display} · ${m.text}", maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.sub))
            }
        }
        else -> {
            val who = Duo.Who.of(m.speaker)
            Column(Modifier.fillMaxWidth()) {
                SpeakerName(who)
                CompositionLocalProvider(LocalSpeaker provides who) { BlazeReply(m.id, m.text, GroupPos.SINGLE, live = live) }
            }
        }
    }
}

/** Drop in (live or waiting), or the closed state. A message here goes to the chat and straight into the room. */
@Composable
private fun RoomFooter(state: String, meta: String) {
    val c = LocalBlaze.current
    if (state == Duo.DONE || state == Duo.STOPPED) {
        Text(roomHead(meta) + if (state == Duo.DONE) ". The verdict is in the chat." else ". Mention @all to start a new one.",
            modifier = Modifier.fillMaxWidth().padding(16.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, color = c.sub))
        return
    }
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).hairline(c.hair).padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (text.isEmpty()) Text(if (state == Duo.WAITING) "Answer them…" else "Drop in with a thought…",
                style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.weak))
            BasicTextField(text, { text = it }, textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink),
                cursorBrush = SolidColor(c.accent), maxLines = 5, keyboardOptions = ComposerKeys.Message, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(8.dp))
        ActionText("Send", color = c.accent, size = 12.sp, modifier = Modifier.clip(CircleShape)
            .clickable(enabled = text.isNotBlank()) { Agent.send(text.trim()); text = "" }.padding(12.dp))
    }
}
