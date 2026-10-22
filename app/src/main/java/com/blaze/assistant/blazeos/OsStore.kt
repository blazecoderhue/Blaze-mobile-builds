package com.blaze.assistant.blazeos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * v0.33.2 BlazeOS App Store: the catalog (bundled assets/blazeos/catalog.json today; a remote catalog url can be named in
 * it later), Get / Update / Open, an app page with its permissions and size, and Uninstall (frees the space). Also the
 * screen of an installed Linux app (it runs in Blaze's Linux desktop, through proot).
 */

@Composable
fun OsStoreApp() {
    val look = LocalOsLook.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val reg by BlazeOs.registry.collectAsState()
    val used by BlazeOs.diskUsed.collectAsState()
    val budget by BlazeOs.budget.collectAsState()
    var catalog by remember { mutableStateOf<OsCatalog?>(null) }
    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var detail by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { catalog = BlazeOs.catalog() }
    fun get(l: OsListing) {
        if (busy.isNotEmpty()) return
        busy = l.manifest.id; error = ""
        scope.launch { val why = BlazeOs.install(l); busy = ""; if (why != null) error = why else BlazeOs.notify("App Store", "${l.manifest.name} is installed", "Find it on your home screen.") }
    }
    fun open(l: OsListing) { scope.launch { OsLaunch.open(ctx, OsApp(OsApps.pkgId(l.manifest.id), l.manifest.name, l.manifest.entry.type, true)) } }
    val shown = catalog?.apps?.firstOrNull { it.manifest.id == detail }
    if (shown != null) BackHandler { detail = "" }
    Column(Modifier.fillMaxSize().background(osBg()).verticalScroll(rememberScrollState())) {
        if (shown == null) {
            OsAppHeader("App Store")
            Text("Apps for BlazeOS: web apps, Linux apps and Blaze's own. Free, no account.", style = osText(look, 14f, color = look.sub),
                modifier = Modifier.padding(horizontal = 16.dp))
            Text("BlazeOS disk: ${OsResources.size(used)} of ${OsResources.size(budget.diskQuota)} used", style = osText(look, 12f, color = look.sub),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (error.isNotEmpty()) Text(error, style = osText(look, 14f, color = Color(0xFFFF453A)), modifier = Modifier.padding(16.dp))
            val c = catalog
            if (c == null) Text("Loading…", style = osText(look, 14f, color = look.sub), modifier = Modifier.padding(16.dp))
            else for ((cat, list) in c.apps.groupBy { it.manifest.category.ifEmpty { "Apps" } }) {
                Text(cat.uppercase(), style = osText(look, 12f, FontWeight.SemiBold, look.sub), modifier = Modifier.padding(start = 32.dp, top = 14.dp, bottom = 2.dp))
                OsGroup { for (l in list) StoreRow(l, reg, busy, onGet = { get(l) }, onOpen = { open(l) }, onDetail = { detail = l.manifest.id }) }
            }
            Spacer(Modifier.height(24.dp))
        } else StorePage(shown, reg, busy, onBack = { detail = "" }, onGet = { get(shown) }, onOpen = { open(shown) },
            onUninstall = { OsLaunch.uninstall(shown.manifest.id) })
    }
    UninstallConfirm()
}

private fun action(l: OsListing, reg: OsRegistry, busy: String): String = when {
    busy == l.manifest.id -> "…"
    else -> when (reg.kindOf(l.manifest)) { OsRegistry.INSTALL -> "Get"; OsRegistry.UPDATE -> "Update"; else -> "Open" }
}

@Composable
private fun StoreRow(l: OsListing, reg: OsRegistry, busy: String, onGet: () -> Unit, onOpen: () -> Unit, onDetail: () -> Unit) {
    val look = LocalOsLook.current
    val m = l.manifest
    Row(Modifier.fillMaxWidth().clickable(onClick = onDetail).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        StoreIcon(l)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, style = osText(look, 16f, FontWeight.SemiBold), maxLines = 1)
            Text(m.description, style = osText(look, 12f, color = look.sub), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        val a = action(l, reg, busy)
        GetButton(a) { if (a == "Open") onOpen() else onGet() }
    }
}

@Composable
private fun GetButton(text: String, onClick: () -> Unit) {
    val look = LocalOsLook.current
    Box(Modifier.clip(CircleShape).background(look.ink.copy(alpha = 0.1f)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 7.dp)) {
        Text(text.uppercase(), style = osText(look, 13f, FontWeight.SemiBold, look.accent))
    }
}

/** A catalog app's icon before it is installed (same drawing as an installed one). */
@Composable
private fun StoreIcon(l: OsListing) {
    OsStoreIconFace(l.manifest)
}

@Composable
fun OsStoreIconFace(m: OsManifestData) {
    val inst = BlazeOs.registry.collectAsState().value
    val id = OsApps.pkgId(m.id)
    // Not installed yet: show it through a temporary registry entry so OsAppIcon draws its glyph and colours.
    if (inst.has(m.id)) OsAppIcon(OsApp(id, m.name, m.entry.type, true), 52.dp)
    else OsGlyphIcon(m.icon, 52.dp)
}

@Composable
private fun StorePage(l: OsListing, reg: OsRegistry, busy: String, onBack: () -> Unit, onGet: () -> Unit, onOpen: () -> Unit, onUninstall: () -> Unit) {
    val look = LocalOsLook.current
    val m = l.manifest
    OsAppHeader("", onBack = onBack)
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        OsGlyphIcon(m.icon, 96.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, style = osText(look, 24f, FontWeight.SemiBold))
            Text(listOf(m.author, m.category).filter { it.isNotBlank() }.joinToString(" · "), style = osText(look, 13f, color = look.sub))
            Spacer(Modifier.height(10.dp))
            val a = action(l, reg, busy)
            GetButton(a) { if (a == "Open") onOpen() else onGet() }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(m.description, style = osText(look, 15f), modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(12.dp))
    OsGroup {
        OsRow("Version", m.version)
        OsRow("Kind", when (m.entry.type) { OsManifest.WEB -> if (m.entry.url.isNotEmpty()) "Web app (online)" else "Web app"; OsManifest.LINUX -> "Linux app (runs in the Linux desktop)"; OsManifest.WASM -> "Native app (WebAssembly)"; else -> "BlazeOS app" })
        OsRow("Size", if (l.size > 0) OsResources.size(l.size) else "Tiny")
        OsRow("Asks for", if (m.permissions.isEmpty()) "Nothing" else m.permissions.joinToString(", ") { OsPerms.label(it) })
    }
    if (reg.has(m.id)) OsGroup { OsRow("Uninstall", danger = true, onClick = onUninstall) }
    Spacer(Modifier.height(24.dp))
}

// ---------------- Linux apps ----------------

@Composable
fun OsLinuxApp(inst: OsInstalled) {
    val look = LocalOsLook.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = inst.manifest
    var step by remember { mutableStateOf("") }
    val installed = remember { com.blaze.assistant.desktop.LinuxDesktop.installed() }
    Column(Modifier.fillMaxSize().background(osBg()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        OsGlyphIcon(m.icon, 96.dp)
        Spacer(Modifier.height(14.dp))
        Text(m.name, style = osText(look, 24f, FontWeight.SemiBold))
        Text("A Linux app. It opens in Blaze's Linux desktop (${com.blaze.assistant.desktop.LinuxDesktop.distro().name}, ${m.entry.command}).", style = osText(look, 14f, color = look.sub))
        Spacer(Modifier.height(24.dp))
        if (!installed) {
            Text("The Linux desktop isn't set up yet. Set it up once in Desktop, then come back.", style = osText(look, 14f))
            Spacer(Modifier.height(12.dp))
            OsPillButton("Open Desktop", primary = true) { BlazeOs.launch("blaze:linux") }
        } else if (step.isNotEmpty()) Text(step, style = osText(look, 15f, color = look.sub))
        else OsPillButton("Open", primary = true) {
            scope.launch {
                try {
                    val d = com.blaze.assistant.desktop.LinuxDesktop
                    if (!d.alive()) { step = "Starting the Linux desktop…"; d.start(ctx) }
                    // v0.33.5: installed with the chosen Linux's own package manager (apk / apt / pacman / dnf), in that Linux.
                    val dist = d.distro()
                    val pkgs = com.blaze.assistant.desktop.DesktopDistro.mapPackages(dist, m.entry.packages, m.entry.distroPackages)
                    if (pkgs.isNotEmpty()) {
                        step = "Getting ${pkgs.joinToString(", ")} (${dist.pm.tool})…"
                        com.blaze.assistant.terminal.Terminal.run(com.blaze.assistant.desktop.DesktopDistro.appInstallScript(dist, pkgs), timeoutSec = 600, linux = true, distro = dist.id)
                    }
                    step = "Opening…"
                    com.blaze.assistant.terminal.Terminal.run(com.blaze.assistant.desktop.DesktopPlan.runCmd(m.entry.command), timeoutSec = 15, linux = true, distro = dist.id)
                    d.viewerRequest.value = true
                    step = ""
                    BlazeOs.launch("blaze:linux")
                } catch (e: Exception) { step = "Couldn't open it: " + (e.message ?: "unknown error") }
            }
        }
    }
}
