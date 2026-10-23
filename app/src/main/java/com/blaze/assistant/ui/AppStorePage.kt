package com.blaze.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.appstore.AppStore
import com.blaze.assistant.appstore.Catalog
import com.blaze.assistant.appstore.InstalledInfo
import com.blaze.assistant.appstore.StoreApp
import com.blaze.assistant.appstore.StoreCategory
import com.blaze.assistant.appstore.StorePolicy
import com.blaze.assistant.appstore.StoreStep
import com.blaze.assistant.appstore.StoreVersions
import com.blaze.assistant.appstore.storeSize
import kotlinx.coroutines.delay

/**
 * 0.45.19 Blaze App Store (Settings page "appstore", open_view "apps"): open-source apps from their developers' own
 * releases, installed on this phone with Android's installer. Free, no ads, no gates. Every listing shows its license,
 * a Source repo button and [StorePolicy.NOTICE]. Get = confirm card (disclaimer) → download → SHA-256 + signer +
 * VirusTotal → Android's own install dialog. Installed apps show Open, or Update when a newer release is known.
 */
@Composable
internal fun AppStorePage() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    remember { AppStore.init(ctx); true }
    LaunchedEffect(Unit) { AppStore.refresh(); AppStore.scanInstalled(); AppStore.checkUpdates() }
    val cat by AppStore.catalog.collectAsState()
    val jobs by AppStore.jobs.collectAsState()
    val installed by AppStore.installed.collectAsState()
    val updates by AppStore.updates.collectAsState()
    var q by remember { mutableStateOf("") }
    var chip by remember { mutableStateOf(Catalog.ALL) }
    var open by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableIntStateOf(PAGE) }
    var allowed by remember { mutableStateOf(true) }
    var vt by remember { mutableStateOf(AppStore.vtKey().orEmpty()) }
    // The user comes back from Android's "install unknown apps" screen: notice the grant without a restart.
    LaunchedEffect(Unit) { while (true) { allowed = AppStore.canInstall(); delay(1500) } }
    val list = remember(cat, q, chip) { AppStore.search(q, chip) }
    val upList = remember(cat, updates) { updates.keys.mapNotNull { cat.byId(it) }.sortedBy { it.name.lowercase() } }

    Spacer(Modifier.height(18.dp))
    Text(StorePolicy.FREE, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 21.sp, color = c.body))
    Text(StorePolicy.NOTICE, modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.weak))
    if (!allowed) Column(Modifier.fillMaxWidth().padding(top = 14.dp).hairline(c.hair).padding(14.dp)) {
        Text(StorePolicy.PERMISSION, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
        PrimaryPill("Allow installs", Modifier.padding(top = 10.dp)) { AppStore.requestInstallPermission(ctx) }
    }
    Spacer(Modifier.height(14.dp))
    if (upList.isNotEmpty()) {
        Label("Updates (${upList.size})", modifier = Modifier.padding(bottom = 8.dp))
        upList.forEach { a -> AppRow(a, open == a.id, jobs[a.id], installed[a.pkg], updates[a.id], onToggle = { open = if (open == a.id) null else a.id }) }
        Spacer(Modifier.height(8.dp))
    }
    Field("Search ${cat.apps.size} open-source apps", q, { q = it; shown = PAGE }, "password manager, maps, email")
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf(StoreCategory(Catalog.ALL, "All")) + cat.shelf()).forEach { k ->
            val on = k.id == chip
            Box(Modifier.clip(BlazeShapes.pill).hairline(if (on) c.accent else c.hair, BlazeShapes.pill).clickable { chip = k.id; shown = PAGE }
                .padding(horizontal = 12.dp, vertical = 7.dp)) { Label(k.name, color = if (on) c.accent else c.sub, size = 11.sp) }
        }
    }
    if (list.isEmpty()) Label(if (cat.apps.isEmpty()) "The catalog couldn't load. Check the connection and open the Store again." else "No app matches.", modifier = Modifier.padding(vertical = 12.dp))
    list.take(shown).forEach { a -> AppRow(a, open == a.id, jobs[a.id], installed[a.pkg], updates[a.id], onToggle = { open = if (open == a.id) null else a.id }) }
    if (list.size > shown) Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(BlazeShapes.pill).glassButton(c).clickable { shown += PAGE }
        .padding(vertical = 12.dp), contentAlignment = Alignment.Center) { ButtonText("Show more (${list.size - shown})", color = Color.White) }
    Spacer(Modifier.height(14.dp))
    Field("VirusTotal key (optional, your own)", vt, { vt = it; AppStore.setVtKey(it) }, "Without one, installs say \"not scanned\"", secret = true)
    Text("Apps come straight from each project's own GitHub or GitLab releases and land on this phone through Android's installer. " +
        "Uninstall them any time from Android's settings. Thanks to every maintainer: star their repos.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 17.sp, color = c.weak))
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun AppRow(a: StoreApp, expanded: Boolean, job: AppStore.Job?, have: InstalledInfo?, newer: String?, onToggle: () -> Unit) {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).hairline(c.hair).clickable(onClick = onToggle).padding(14.dp)
        .semantics { contentDescription = "${a.name}, ${a.license.ifBlank { "license not stated" }}. ${StorePolicy.NOTICE}" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink))
            Label(a.license.ifBlank { "License not stated" }, size = 10.sp)
        }
        Text(a.summary, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
        Text(StorePolicy.NOTICE, modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.weak))
        if (have != null) Label("Installed ${have.versionName}" + (newer?.let { " · update ${StoreVersions.display(it)}" } ?: ""), size = 10.sp, modifier = Modifier.padding(top = 6.dp))
        if (job != null) Label(job.message + (if (job.step == StoreStep.DOWNLOADING && job.total > 0) " · ${storeSize(job.done)} of ${storeSize(job.total)}" else ""),
            color = if (job.step == StoreStep.BLOCKED || job.step == StoreStep.FAILED) c.accent else c.sub, size = 10.sp, modifier = Modifier.padding(top = 6.dp))
        if (expanded) {
            val busy = job != null && !job.step.terminal
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinePill(StorePolicy.SOURCE_BUTTON) { AppStore.openSource(ctx, a) }
                when {
                    busy && job?.step == StoreStep.WAITING_USER -> PrimaryPill("Open installer") { AppStore.reopenInstaller(ctx, a) }
                    busy && job?.step == StoreStep.DOWNLOADING -> OutlinePill("Cancel") { AppStore.cancel(a.id) }
                    busy -> PrimaryPill("Working…", enabled = false) { }
                    have != null && newer == null -> PrimaryPill("Open") { AppStore.openApp(ctx, a.pkg) }
                    else -> PrimaryPill(if (have != null) "Update" else "Get") {
                        AppStore.start(a, update = have != null) { t, d -> Agent.confirm(t, d, if (have != null) "Update ${a.name}" else "Get ${a.name}", always = true) }
                    }
                }
            }
            Text("Release page", modifier = Modifier.padding(top = 8.dp).clip(BlazeShapes.pill).clickable { AppStore.openReleases(ctx, a) }.padding(vertical = 4.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.weak))
        }
    }
}

@Composable
private fun PrimaryPill(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(modifier.height(40.dp).clip(BlazeShapes.pill).glassButton(c).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) { ButtonText(text, color = Color.White) }
}

@Composable
private fun OutlinePill(text: String, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.height(40.dp).clip(BlazeShapes.pill).hairline(c.hair, BlazeShapes.pill).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) { ButtonText(text, color = c.ink) }
}

private const val PAGE = 40
