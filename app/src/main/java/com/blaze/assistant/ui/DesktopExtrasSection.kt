package com.blaze.assistant.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.blaze.assistant.desktop.DesktopExtras
import com.blaze.assistant.desktop.DesktopPlan.Phase
import com.blaze.assistant.desktop.LinuxDesktop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.34.0 Desktop page, once the desktop is installed: the app drawer, the curated app store, the screen size,
 * restart-by-itself and snapshots. The rules live in [DesktopExtras] (DesktopExtrasTest); the work in [LinuxDesktop].
 */
@Composable
internal fun DesktopExtrasSection(phase: Phase) {
    val ctx = LocalContext.current
    val c = LocalBlaze.current.mono
    val scope = rememberCoroutineScope()
    val running = phase == Phase.RUNNING
    val busy = phase == Phase.INSTALLING || phase == Phase.STARTING || phase == Phase.STOPPING
    var note by remember { mutableStateOf("") }
    val small = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub)

    // ---------------- app drawer ----------------
    var apps by remember { mutableStateOf<List<DesktopExtras.AppEntry>?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val reload: () -> Unit = { if (!loading) { loading = true; scope.launch { try { apps = LinuxDesktop.apps(ctx) } finally { loading = false } } } }
    LaunchedEffect(running) { if (running && apps == null) reload() }
    GroupHead("Apps", if (running) "Tap one to open it in the desktop." else "Start the desktop to open its apps.") {
        if (running) KitButton("Refresh", Btn.GHOST, enabled = !loading) { reload() }
    }
    if (running) {
        val list = apps
        when {
            list == null -> Text(if (loading) "Looking for apps\u2026" else "", style = small)
            list.isEmpty() -> Text("No apps found.", style = small)
            else -> {
                val shown = if (showAll) list else list.take(10)
                Rows {
                    shown.forEachIndexed { i, e ->
                        KitRow(e.name, sub = e.categories.split(';').firstOrNull { it.isNotBlank() && it != "GTK" && it != "Qt" }.orEmpty(), first = i == 0,
                            onClick = { scope.launch { note = LinuxDesktop.launch(e) } })
                    }
                }
                if (list.size > 10) KitButton(if (showAll) "Show fewer" else "Show all ${list.size}", Btn.GHOST) { showAll = !showAll }
            }
        }
    }

    // ---------------- curated store ----------------
    Spacer(Modifier.height(18.dp))
    val d = remember(phase) { LinuxDesktop.distro() }
    val storeBusy by LinuxDesktop.storeBusy.collectAsState()
    val storeLine by LinuxDesktop.storeLine.collectAsState()
    GroupHead("App store", "Picked apps for ${d.name}, installed with ${d.pm.tool}. Free and open source, from ${d.name}'s own repositories.")
    storeBusy?.let { b ->
        Text("$b \u00b7 ${storeLine.ifBlank { "Installing\u2026" }}", style = small)
        Spacer(Modifier.height(6.dp))
        BrandProgress(50)
        Spacer(Modifier.height(8.dp))
    }
    Rows {
        DesktopExtras.STORE.forEachIndexed { i, a ->
            val ok = DesktopExtras.packagesFor(a, d) != null
            KitRow(a.name, sub = if (ok) a.about else DesktopExtras.whyNot(a, d), first = i == 0, dim = !ok, trailing = {
                if (ok) {
                    if (running) KitButton("Open", Btn.GHOST, enabled = storeBusy == null) { scope.launch { note = LinuxDesktop.launchStore(a) } }
                    KitButton("Install", Btn.LINE, enabled = storeBusy == null && !busy) {
                        scope.launch { note = LinuxDesktop.installApp(a); if (running) reload() }
                    }
                }
            })
        }
    }

    // ---------------- display ----------------
    Spacer(Modifier.height(18.dp))
    var disp by remember { mutableStateOf(LinuxDesktop.display()) }
    GroupHead("Screen", if (running) "Used from the next start (stop and start the desktop)." else "Used from the next start.")
    Rows {
        DesktopExtras.DISPLAYS.forEachIndexed { i, x ->
            val big = x.w * x.h > 1280 * 720 || x.depth == 24
            KitRow(x.label, sub = "About ${DesktopExtras.displayMb(x)} MB of memory" + (if (x.h > x.w) " \u00b7 portrait" else "") +
                (if (i == 0) " \u00b7 lightest, best on a 3 GB phone" else if (big) " \u00b7 heavier on a 3 GB phone" else ""),
                first = i == 0, pill = if (x == disp) "Picked" else "", pillOn = true,
                onClick = { LinuxDesktop.setDisplay(x); disp = x })
        }
    }

    // ---------------- auto-restart ----------------
    Spacer(Modifier.height(18.dp))
    var auto by remember { mutableStateOf(LinuxDesktop.autoRestart()) }
    Rows {
        KitRow("Restart by itself", sub = "If Android or a crash closes the desktop, Blaze starts it again (at most 3 times in 10 minutes).", first = true,
            trailing = { KitSwitch(auto) { auto = !auto; LinuxDesktop.setAutoRestart(auto) } })
    }

    // ---------------- snapshots ----------------
    Spacer(Modifier.height(18.dp))
    val snapBusy by LinuxDesktop.snapBusy.collectAsState()
    var snaps by remember { mutableStateOf(emptyList<DesktopExtras.Snapshot>()) }
    var armed by remember { mutableStateOf("") }
    LaunchedEffect(snapBusy, phase, note) { snaps = withContext(Dispatchers.IO) { LinuxDesktop.snapshots(ctx) } }
    GroupHead("Snapshots", "Save the Linux as it is now (apps, files, settings); Restore puts it all back. Stop the desktop to restore.")
    BlazeButton(if (snapBusy) "Working\u2026" else "Save a snapshot", Modifier.fillMaxWidth(), kind = BtnKind.SECONDARY, enabled = !snapBusy && !busy) {
        note = "Saving a snapshot\u2026 this can take a few minutes."
        scope.launch { note = LinuxDesktop.createSnapshot(ctx) }
    }
    if (snaps.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
        Rows {
            snaps.forEachIndexed { i, s ->
                val other = s.distro != d.id
                KitRow(fmt.format(java.util.Date(s.at)), sub = com.blaze.assistant.desktop.DesktopDistro.of(s.distro).name + " \u00b7 %.0f MB".format(java.util.Locale.US, s.bytes / 1_000_000.0),
                    first = i == 0, dim = other, trailing = {
                        KitButton(if (armed == s.file) "Tap to confirm" else "Restore", if (armed == s.file) Btn.ACCENT else Btn.LINE,
                            enabled = !snapBusy && !running && !busy && !other) {
                            if (armed != s.file) armed = s.file
                            else { armed = ""; note = "Restoring\u2026"; scope.launch { note = LinuxDesktop.restoreSnapshot(ctx, s) } }
                        }
                        KitButton("Delete", Btn.GHOST, enabled = !snapBusy) { LinuxDesktop.deleteSnapshot(ctx, s); snaps = LinuxDesktop.snapshots(ctx) }
                    })
            }
        }
    }
    if (note.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(note, style = small)
    }
}
