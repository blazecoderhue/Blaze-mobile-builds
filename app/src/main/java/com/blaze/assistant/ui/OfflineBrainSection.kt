package com.blaze.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.blaze.assistant.agent.OfflineBrain
import kotlinx.coroutines.launch

/** v0.33.0 Offline Brain (Settings › Brain): download, switch, delete. */
@Composable
internal fun OfflineBrainSection() {
    val scope = rememberCoroutineScope()
    val prog by OfflineBrain.progress.collectAsState()
    var on by remember { mutableStateOf(OfflineBrain.on) }
    var ready by remember { mutableStateOf(OfflineBrain.ready()) }
    var note by remember { mutableStateOf("") }
    V18Section("Offline Brain")
    V18Note("A small AI model on the phone answers when there's no internet or your data runs out. About 550 MB, downloaded once (use Wi-Fi). Status: " +
        if (prog >= 0) "downloading $prog%" else OfflineBrain.status())
    ToggleRow("Use when offline", "Chat keeps working with no connection; tools wait until you're back online", on) { on = !on; OfflineBrain.on = on }
    if (!ready && prog < 0) V18Button("Download offline model") { scope.launch { val r = OfflineBrain.download(); ready = OfflineBrain.ready(); note = r.exceptionOrNull()?.let { "Download failed: ${it.message}. Tap again to resume." } ?: "Ready. Blaze answers offline from now." } }
    if (ready) V18Button("Delete offline model", primary = false) { OfflineBrain.delete(); ready = false; note = "Deleted." }
    if (note.isNotBlank()) V18Note(note, top = 6)
}
