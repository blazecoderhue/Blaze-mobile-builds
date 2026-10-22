package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.connect.BlazeAccount
import com.blaze.assistant.connect.CloudSync
import com.blaze.assistant.connect.SyncKinds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v0.40.0 Settings › Account › Cloud sync (`/sync`): optional, end-to-end encrypted live sync with your other phones.
 * Off by default. Plain words in Simple and Advanced alike; Advanced also lists items that were too big to sync.
 */
@Composable
fun CloudSyncPage(onSignIn: () -> Unit = {}) {
    val c = LocalBlaze.current.mono
    val scope = rememberCoroutineScope()
    val acct by BlazeAccount.view.collectAsState()
    val v by CloudSync.view.collectAsState()
    val simple by com.blaze.assistant.agent.SimpleMode.simpleFlow.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }

    Spacer(Modifier.height(10.dp))
    Text("Keep your chats, memory, settings and designs the same on every phone signed in to your Blaze account.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, color = c.sub))
    Spacer(Modifier.height(14.dp))

    if (acct.user == null) {
        SyncNote("You're signed out. Sign in to your Blaze account to use cloud sync.")
        SyncButton("Sign in") { onSignIn() }
        return
    }

    // ---------- the switch ----------
    var setup by remember { mutableStateOf(false) }
    // v0.45.7: the switch matches the label (off until "Turn on cloud sync" succeeds).
    val sw = CloudSyncSwitch.view(v.on, setup, v.needsPassphrase, if (v.on) CloudSync.statusLine(v, now) else "")
    ToggleRow("Cloud sync", sw.label, sw.checked) {
        if (v.on) { CloudSync.turnOff() } else setup = !setup
    }

    if (!v.on || v.needsPassphrase) {
        if (setup || v.needsPassphrase) PassphraseSetup(needsExisting = v.needsPassphrase) { setup = false }
        else SyncNote("Locked with a passphrase only you know. Blaze's service stores a scrambled copy it can't read.", top = 12)
        return
    }

    // ---------- on ----------
    Spacer(Modifier.height(16.dp))
    Label("What syncs")
    Spacer(Modifier.height(4.dp))
    for (k in SyncKinds.ALL) {
        val on = k !in v.off
        ToggleRow(SyncKinds.label(k), SyncKinds.detail(k), on) { CloudSync.setKind(k, !on) }
    }
    SyncNote("Never synced: your Blaze sign-in, connected accounts, API keys, PC pairing and phone permissions.", top = 12)
    if (!simple && v.skipped.isNotEmpty()) SyncNote("Too big to sync, kept on this phone: " + v.skipped.take(8).joinToString(", ") +
        (if (v.skipped.size > 8) " and ${v.skipped.size - 8} more" else "") + ".")

    Spacer(Modifier.height(10.dp))
    SyncButton(if (v.busy) "Syncing…" else "Sync now", primary = false) {
        if (!v.busy) scope.launch { CloudSync.syncNow() }
    }
    Spacer(Modifier.height(8.dp))
    SyncButton("Turn off", primary = false) { CloudSync.turnOff() }
    SyncNote("Turning off keeps everything on this phone and leaves the cloud copy for your other phones.", top = 8)

    Spacer(Modifier.height(18.dp))
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    if (!confirm) ActionText("Delete my cloud copy", color = c.accent, size = 10.sp, modifier = Modifier.clickable { confirm = true }.padding(vertical = 8.dp))
    else {
        SyncNote("This deletes the synced copy from Blaze's service and stops sync on every phone. Chats, memory and designs on your phones stay.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { SyncButton("Keep it", primary = false) { confirm = false } }
            Box(Modifier.weight(1f)) {
                SyncButton(if (busy) "Deleting…" else "Delete it") {
                    if (busy) return@SyncButton
                    busy = true
                    scope.launch { err = CloudSync.deleteCloudCopy(); busy = false; if (err.isEmpty()) confirm = false }
                }
            }
        }
    }
    if (err.isNotEmpty()) SyncNote(err, top = 8)
}

/** Pick a new passphrase (first phone) or enter the existing one (another phone, or after a change elsewhere). */
@Composable
private fun PassphraseSetup(needsExisting: Boolean, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var hasCopy by remember { mutableStateOf<Boolean?>(if (needsExisting) true else null) }
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (hasCopy == null) hasCopy = CloudSync.accountHasCopy() ?: false }

    Spacer(Modifier.height(14.dp))
    val existing = hasCopy == true
    if (hasCopy == null) { SyncNote("Checking your account…"); return }
    SyncNote(if (existing) "Your account already syncs from another phone. Enter the sync passphrase you picked there."
        else "Pick a sync passphrase. You'll type it once on each phone. Blaze can't recover it, so keep it somewhere safe.")
    Field("Sync passphrase", pass, { pass = it; err = "" }, "At least 8 characters", secret = true)
    if (!existing) Field("Type it again", again, { again = it; err = "" }, "The same passphrase", secret = true)
    SyncButton(if (busy) "Turning on…" else "Turn on cloud sync") {
        if (busy) return@SyncButton
        if (!existing && pass != again) { err = "The two passphrases don't match."; return@SyncButton }
        busy = true; err = ""
        scope.launch {
            err = CloudSync.enable(pass)
            busy = false
            if (err.isEmpty()) { pass = ""; again = ""; onDone() }
        }
    }
    if (err.isNotEmpty()) SyncNote(err, top = 8)
}

@Composable
private fun SyncNote(text: String, top: Int = 0) {
    val c = LocalBlaze.current.mono
    Text(text, modifier = Modifier.padding(top = top.dp, bottom = 10.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
}

@Composable
private fun SyncButton(text: String, primary: Boolean = true, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.fillMaxWidth().height(44.dp).then(if (primary) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) { ButtonText(text, color = if (primary) c.paper else c.ink, size = 13.sp) }
}

/** The row on Settings › Account that opens Cloud sync. */
@Composable
fun CloudSyncRow(onOpen: () -> Unit) {
    val c = LocalBlaze.current.mono
    val v by CloudSync.view.collectAsState()
    Row(Modifier.fillMaxWidth().hairline(c.hair).clickable(onClick = onOpen).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text("Cloud sync", style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
            Text(CloudSync.statusLine(v), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
        Text("\u203a", style = TextStyle(fontFamily = Type.Sans, fontSize = 20.sp, color = c.sub))
    }
}
