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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.connect.BlazeAccount
import com.blaze.assistant.connect.CloudSync
import com.blaze.assistant.setup.FirstRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 0.45.23: "Bring back your chats and memory", in the chat, after sign-in on a phone where cloud sync is off and the
 * account keeps an encrypted copy (FirstRun.offerRestore). The passphrase opens it (the key never leaves the phone); the
 * first round pulls chats, memory, settings and designs, and the chat list fills in. "Not now" hides it for this account;
 * Settings › Account › Cloud sync still turns it on. Offline: it checks again when the connection is back.
 */
@Composable
fun RestoreCard() {
    val c = LocalBlaze.current.mono
    val scope = rememberCoroutineScope()
    val account by BlazeAccount.view.collectAsState()
    val sync by CloudSync.view.collectAsState()
    val uid = account.user?.id.orEmpty()
    var hasCopy by remember(uid) { mutableStateOf<Boolean?>(null) }
    var dismissed by remember(uid) { mutableStateOf(Settings.str(FirstRun.PREF_RESTORE_DISMISSED, "")) }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val signedIn = account.user != null
    LaunchedEffect(uid, sync.on) {
        var wait = 5_000L
        while (FirstRun.shouldCheckCopy(signedIn, sync.on, dismissed, uid) && hasCopy == null) {
            hasCopy = CloudSync.accountHasCopy()   // null = offline / unreachable: ask again a bit later
            if (hasCopy == null) { delay(wait); wait = (wait * 2).coerceAtMost(60_000L) }
        }
    }
    if (!FirstRun.offerRestore(signedIn, sync.on, hasCopy, dismissed, uid)) return

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).pageGlass(c, PageGlass.row).padding(16.dp)
        .semantics { testTag = "restore_card" }) {
        Text("Bring back your chats and memory", style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
        Spacer(Modifier.height(4.dp))
        Text("Your Blaze account keeps an encrypted copy. Enter the sync passphrase you chose on your other device.",
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
        Spacer(Modifier.height(10.dp))
        Field("Sync passphrase", pass, { pass = it; err = "" }, "The passphrase from your other device", secret = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(44.dp).background(c.ink, PageGlass.row).clickable {
                if (busy) return@clickable
                busy = true; err = ""
                scope.launch {
                    err = CloudSync.enable(pass)   // checks the passphrase against the account's copy, then the first round pulls
                    busy = false
                    if (err.isEmpty()) pass = ""
                }
            }, contentAlignment = Alignment.Center) { ButtonText(if (busy) "Restoring\u2026" else "Restore", color = c.paper, size = 13.sp) }
            Box(Modifier.weight(1f).height(44.dp).pageGlass(c, PageGlass.row, alpha = 0.06f).clickable {
                Settings.setStr(FirstRun.PREF_RESTORE_DISMISSED, uid); dismissed = uid
            }, contentAlignment = Alignment.Center) { ButtonText("Not now", color = c.ink, size = 13.sp) }
        }
        if (err.isNotEmpty()) Text(err, modifier = Modifier.padding(top = 8.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = PageGlass.error))
    }
}
