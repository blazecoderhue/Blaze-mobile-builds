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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Card
import com.blaze.assistant.agent.Flashcards
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.connect.Classroom
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

/**
 * Learn (student pack, v0.16.0): flashcard decks with spaced repetition (the tutor skill fills them),
 * plus the student switches: Google Classroom and low-data mode.
 */
@Composable
internal fun LearnPage(startDeck: String = "", reveal: Boolean = false, now: Long? = null) {
    val c = LocalBlaze.current.mono
    val all by Flashcards.cards.collectAsState()
    LaunchedEffect(Unit) { Flashcards.load() }
    var deck by remember { mutableStateOf(startDeck) }
    val clock = now ?: System.currentTimeMillis()

    Spacer(Modifier.height(18.dp))
    if (deck.isNotEmpty()) {
        val queue = remember(all, deck) { Flashcards.queue(deck, clock) }
        Review(deck, queue.firstOrNull(), queue.size, reveal, clock, onBack = { deck = "" })
        return
    }
    Text("Flashcards come back just before you'd forget them. Ask Blaze to make some from your notes, a PDF or slides (\u201cmake flashcards from my CIT102 notes\u201d), then study here.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 21.sp, color = c.body))
    Spacer(Modifier.height(16.dp))
    Label("Decks", modifier = Modifier.padding(bottom = 8.dp))
    val decks = remember(all) { Flashcards.decks(clock) }
    if (decks.isEmpty()) Rows { EmptyState("No cards yet", "Ask Blaze to \u201cmake flashcards from my notes\u201d, or study with the tutor skill. Decks show up here.") }
    decks.forEachIndexed { i, d ->
        Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).clickable { deck = d.name }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("%02d".format(i + 1), color = c.weak, size = 10.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
                Text("${d.total} cards", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
            if (d.due > 0) StatusChip("${d.due} due", c.accent, filled = true)
            if (d.fresh > 0) { Spacer(Modifier.width(6.dp)); StatusChip("${d.fresh} new", c.accent, filled = false) }
            if (d.due + d.fresh == 0) Label("Done", color = c.weak, size = 9.sp)
        }
    }
    Spacer(Modifier.height(22.dp))
    Label("Student tools", modifier = Modifier.padding(bottom = 4.dp))
    var classroom by remember { mutableStateOf(Classroom.enabled) }
    var low by remember { mutableStateOf(Settings.lowData) }
    ToggleRow("Google Classroom", "Read your courses and assignments, and turn each one into a reminder 24 hours before it's due. Reconnect Google once after switching this on", classroom) {
        classroom = !classroom; Classroom.enabled = classroom }
    ToggleRow("Low-data mode", "Text models only (photos are described once), no screenshots, no instant pictures, and each answer shows the MB it used", low) {
        low = !low; Settings.lowData = low }
    Text("Study skills: study-planner, tutor, lecture-notes, essay-coach, group-project and classroom-sync (Settings \u203a Skill library).",
        modifier = Modifier.padding(top = 10.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.weak))
}

@Composable
private fun Review(deck: String, card: Card?, left: Int, reveal0: Boolean, now: Long, onBack: () -> Unit) {
    val c = LocalBlaze.current.mono
    var reveal by remember(card?.id) { mutableStateOf(reveal0) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // v0.28.3: the shared round BackButton (was "← Decks" text).
        BackButton(onBack, modifier = Modifier.offset(x = (-4).dp), desc = "Back to decks")
        Text("Decks", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
        Spacer(Modifier.weight(1f))
        Label(if (card == null) deck else "$deck \u00b7 $left left", color = c.sub, size = 9.sp, maxLines = 1)
    }
    Spacer(Modifier.height(12.dp))
    if (card == null) {
        Box(Modifier.fillMaxWidth().hairline(c.hair).padding(24.dp), contentAlignment = Alignment.Center) {
            Text("All caught up on this deck. Next cards come back when they're due.", textAlign = TextAlign.Center,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.sub))
        }
        return
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp).hairline(c.ink.copy(alpha = 0.35f)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Label(if (card.reps == 0) "New" else "Review", color = c.accent, size = 9.sp)
        Text(card.front, style = TextStyle(fontFamily = Type.Display, fontSize = 20.sp, lineHeight = 27.sp, color = c.ink))
        if (reveal) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
            Text(card.back, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 22.sp, color = c.body))
        }
    }
    Spacer(Modifier.height(12.dp))
    if (!reveal) {
        Box(Modifier.fillMaxWidth().height(48.dp).clip(BlazeShapes.pill).glassButton(c).clickable { reveal = true }, contentAlignment = Alignment.Center) {
            ButtonText("Show answer", color = Color.White)
        }
    } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Flashcards.AGAIN to "Again", Flashcards.HARD to "Hard", Flashcards.GOOD to "Good", Flashcards.EASY to "Easy").forEach { (g, name) ->
            val main = g == Flashcards.GOOD
            Column(Modifier.weight(1f).height(56.dp).then(if (main) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f))
                .clickable { Flashcards.answer(card.id, g) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                ButtonText(name, color = if (main) c.paper else c.ink, size = 13.sp)
                Text(Flashcards.label(card, g, now), maxLines = 1, style = TextStyle(fontFamily = Type.Mono, fontSize = 10.sp, color = if (main) c.paper.copy(alpha = 0.7f) else c.sub))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    ActionText("Delete this card", color = c.weak, size = 9.sp, modifier = Modifier.clickable { Flashcards.deleteCard(card.id) }.padding(vertical = 8.dp))
}
