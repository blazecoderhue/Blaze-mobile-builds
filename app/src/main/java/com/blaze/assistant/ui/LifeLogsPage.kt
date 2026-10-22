package com.blaze.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Mind
import com.blaze.assistant.agent.MindLogs
import com.blaze.assistant.life.Diarize
import com.blaze.assistant.life.LifeAudio
import com.blaze.assistant.life.LifeLogWorker
import com.blaze.assistant.life.LifeLogs
import com.blaze.assistant.notify.Conversations
import com.blaze.assistant.notify.NotificationBrain
import com.blaze.assistant.voice.WakeModel
import kotlinx.coroutines.launch

/**
 * Settings › Life logs (v0.19.0): status, time logged today, start / pause / stop / brainstorm, the offline model,
 * delete logs, and the one-time explainer before the first start. [preview] fixes the state for screenshots.
 */
@Composable
internal fun LifeLogsPage(preview: LifeLogs.State? = null, previewToday: Long = -1, previewChunks: Int = -1, previewModel: Boolean? = null) {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by LifeLogs.state.collectAsState()
    val s = preview ?: live
    val dl by WakeModel.progress.collectAsState()
    val modelReady = previewModel ?: remember(dl) { WakeModel.ready(ctx) }
    var explain by remember { mutableStateOf(false) }
    var pendingBrainstorm by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    var recordMode by remember { mutableStateOf(LifeLogs.recordMode) }
    var keepAudio by remember { mutableStateOf(LifeLogWorker.keepAudio) }
    var voices by remember { mutableStateOf(Diarize.knownFromJson(com.blaze.assistant.agent.Settings.str(LifeLogWorker.PREF_VOICES, "[]")).map { it.name }) }
    val chunks = if (previewChunks >= 0) previewChunks else remember(refresh, s.on) { try { Mind.db()?.let { MindLogs.count(it, MindLogs.LIFE) } ?: 0 } catch (_: Exception) { 0 } }
    val today = if (previewToday >= 0) previewToday else remember(refresh, s.on, s.last) { LifeLogs.todayMs() }

    fun begin(bs: Boolean) {
        if (!LifeLogs.acknowledged) { pendingBrainstorm = bs; explain = true; return }
        note = LifeLogs.start(ctx, bs)
    }

    Spacer(Modifier.height(18.dp))
    Text(if (recordMode) "Say \u201cstart logs\u201d (or /logs) and Blaze records the moments people speak. When you stop, it transcribes them on the phone, " +
        "tells the voices apart, and Blaze and Halo go over it together: where you were, who was there, and the memories worth keeping (people, plans, promises), each marked as inferred."
        else "Say \u201cstart logs\u201d (or /logs) and Blaze transcribes what your phone hears, on the phone and for free. Only text is saved, never audio; " +
        "while your phone is idle Blaze turns it into memories: people, plans, ideas and things you promised, each marked as inferred.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 21.sp, color = c.body))

    V18Section("Status")
    val status = when {
        !s.on -> "Off"
        s.paused.isNotBlank() -> "Paused: ${s.paused}"
        s.brainstorm -> "On: brainstorm"
        else -> "On: listening"
    }
    Mono("$status\n" +
        (if (s.on) "Engine: ${s.engine.ifBlank { "starting" }}\n" else "") +
        "Logged today: ${LifeLogs.hours(today)}\n" +
        "Saved log chunks: $chunks" +
        (if (s.on && s.last.isNotBlank()) "\nLast heard: \u201c${s.last.take(80)}\u201d" else ""))

    if (explain) {
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().hairline(c.accent).padding(14.dp)) {
            Label("Before you start", color = c.accent, size = 10.sp)
            Text(LifeLogs.EXPLAINER, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.ink))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                V18Button("I understand, start", modifier = Modifier.weight(1f)) {
                    LifeLogs.acknowledged = true; explain = false; note = LifeLogs.start(ctx, pendingBrainstorm) }
                V18Button("Not now", primary = false, modifier = Modifier.weight(1f)) { explain = false }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    if (!s.on) {
        V18Button("Start life logs") { begin(false) }
        Spacer(Modifier.height(8.dp))
        V18Button("Brainstorm with me", primary = false) { begin(true) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            V18Button(if (s.paused.isNotBlank()) "Resume" else "Pause", primary = false, modifier = Modifier.weight(1f)) {
                note = if (s.paused.isNotBlank()) LifeLogs.resume(ctx) else LifeLogs.pause(ctx) }
            V18Button(if (s.brainstorm) "Stop and summarise" else "Stop", modifier = Modifier.weight(1f)) { note = LifeLogs.stop(ctx); refresh++ }
        }
    }
    if (note.isNotBlank()) V18Note(note, top = 8)

    // v0.33.0 Life Log 2
    V18Section("Record, then review")
    ToggleRow("Record, then transcribe", "Keeps only the moments people speak; after Stop: transcript, voices told apart, Blaze + Halo review. Off = live text only, no audio", recordMode) {
        recordMode = !recordMode; LifeLogs.recordMode = recordMode }
    if (recordMode) {
        ToggleRow("Keep recordings", "Off: the audio is deleted once the review is done. On: kept on the phone (about 115 MB per hour of talking)", keepAudio) {
            keepAudio = !keepAudio; LifeLogWorker.keepAudio = keepAudio }
        val used = remember(refresh, s.on) { LifeAudio.usedBytes(ctx) }
        if (used > 0) V18Note("Recordings on the phone: ${"%.1f".format(used / 1e6)} MB", top = 6)
        Spacer(Modifier.height(8.dp))
        V18Button("Review my last recording again", primary = false) { note = LifeLogs.reviewLast(ctx) }
        V18Note(if (voices.isEmpty()) "Voices Blaze knows: none yet. After each review, voices Blaze and Halo could name (\u201cYou\u201d, a friend's name) are remembered, so they're recognised next time."
            else "Voices Blaze knows: ${voices.joinToString(", ")}", top = 8)
        if (voices.isNotEmpty()) V18Button("Forget voices", primary = false) { com.blaze.assistant.agent.Settings.setStr(LifeLogWorker.PREF_VOICES, "[]"); voices = emptyList() }
    }

    V18Section("On-device speech")
    if (modelReady) V18Note("Offline English model ready (about ${WakeModel.SIZE_MB} MB, shared with \u201cHey Blaze\u201d). It stays across updates.")
    else {
        V18Note(if (dl in 0..99) "Downloading the offline model: $dl%" else
            "The offline model (about ${WakeModel.SIZE_MB} MB) downloads once, the first time you start logs. Until then Android's own speech recogniser is used.")
        if (dl !in 0..99) V18Button("Download it now", primary = false) { scope.launch { WakeModel.download(ctx.applicationContext) } }
    }

    V18Section("Stays out of the way")
    V18Note("Pauses by itself during phone calls, while another app uses the mic, while Blaze listens or speaks, and when the battery is below ${LifeLogs.LOW_BATTERY}% and not charging. " +
        "The notification (with Pause and Stop) and Android's mic dot show the whole time it listens. Say \u201cstop logs\u201d to end it hands-free.")

    V18Section("Your logs")
    if (!confirmDelete) V18Button("Delete all life logs", primary = false) { confirmDelete = true }
    else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        V18Button("Delete $chunks chunks", modifier = Modifier.weight(1f)) { val n = LifeLogs.deleteAll(); note = "Deleted $n log chunks."; confirmDelete = false; refresh++ }
        V18Button("Keep", primary = false, modifier = Modifier.weight(1f)) { confirmDelete = false }
    }
    V18Note("Deleting removes the transcripts. Memories Blaze already learned from them stay in Memory, where you can delete them too.", top = 8)
}

/**
 * Settings › Notification replies (v0.19.0): notification access, the per-conversation chat log, auto-reply per
 * conversation (otherwise every send shows a card first), and deleting the chat logs.
 */
@Composable
internal fun RepliesPage(previewAccess: Boolean? = null) {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val access = previewAccess ?: remember { NotificationBrain.enabled(ctx) }
    var logOn by remember { mutableStateOf(Conversations.logOn) }
    var auto by remember { mutableStateOf(Conversations.auto()) }
    var note by remember { mutableStateOf("") }
    val convs = remember { Conversations.list().take(12) }

    Spacer(Modifier.height(18.dp))
    Text("Blaze reads your chat notifications (WhatsApp, SMS, Telegram, Messenger and more), keeps a log of each conversation, and can draft and send replies from the notification without opening the app.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 21.sp, color = c.body))
    V18Section("Notification access")
    if (access) V18Note("On. Ask \u201cwhat did Gloria say?\u201d or \u201creply to the family group\u201d.")
    else { V18Note("Off. Turn on notification access for Blaze to use replies."); V18Button("Open notification access") {
        try { ctx.startActivity(NotificationBrain.settingsIntent()) } catch (_: Exception) { } } }
    var shield by remember { mutableStateOf(com.blaze.assistant.notify.ScamShield.on) }
    ToggleRow("Scam Shield", "Warns you when an SMS or chat message looks like a mobile-money scam, a fake prize or a phishing link (checked on the phone)", shield) {
        shield = !shield; com.blaze.assistant.notify.ScamShield.on = shield }
    var money by remember { mutableStateOf(com.blaze.assistant.notify.MoneyRadar.on) }
    ToggleRow("Money Radar", "Keeps a receipt vault from mobile-money and bank SMS, spots bills you pay monthly and warns before data bundles run out or expire. Only amounts, payees and balances are kept, on the phone", money) {
        money = !money; com.blaze.assistant.notify.MoneyRadar.on = money; if (money) com.blaze.assistant.notify.MoneyRadar.schedule(ctx) }
    var copilot by remember { mutableStateOf(com.blaze.assistant.phone.CallCopilot.on) }
    ToggleRow("Call Copilot", "Live captions during phone calls (best on speaker), Halo's suggested answers, and a summary with to-dos when you hang up. Captions only, no audio is kept", copilot) {
        copilot = !copilot; com.blaze.assistant.phone.CallCopilot.on = copilot; if (copilot) com.blaze.assistant.phone.CallCopilot.init(ctx) }
    ToggleRow("Keep chat logs", "A rolling log per conversation in Blaze's memory, so replies have more context than the last few messages", logOn) {
        logOn = !logOn; Conversations.setLog(logOn) }
    V18Note("Codes, PINs and bank or mobile-money messages are never stored or read out.", top = 8)

    V18Section("Auto-reply")
    V18Note("Every reply shows a card with the text first. Switch on auto-reply only for a conversation where you want Blaze to send without asking.")
    if (convs.isEmpty()) V18Note("Conversations show up here after their next message arrives.")
    convs.forEach { cv ->
        ToggleRow(cv.title, "${cv.app}${if (cv.group) " · group" else ""} · ${if (cv.key in auto) "sends without asking" else "asks first"}", cv.key in auto) {
            val on = cv.key !in auto; Conversations.setAuto(cv.key, on); auto = Conversations.auto() }
    }

    V18Section("Chat logs")
    V18Button("Delete chat logs", primary = false) {
        val n = try { Mind.db()?.let { MindLogs.deleteKind(it, MindLogs.NOTIF) } ?: 0 } catch (_: Exception) { 0 }
        note = "Deleted $n logged messages."
    }
    if (note.isNotBlank()) V18Note(note, top = 8)
    Text("Tools: notif_conversations, notif_read, notif_reply.", modifier = Modifier.padding(top = 10.dp).clickable { },
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.weak))
}
