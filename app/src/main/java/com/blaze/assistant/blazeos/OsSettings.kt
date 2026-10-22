package com.blaze.assistant.blazeos

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * v0.33.2 BlazeOS Settings: the look (wallpaper, icons, grid, labels, dock, glass, accent, light / dark, font size), each
 * app's BlazeOS permissions, Storage (disk used against the quota), Memory (the boot budget and the host right now),
 * About BlazeOS and Factory reset (the BlazeOS disk only, after a confirm). Every change is saved at once (bos_* prefs).
 */

@Composable
fun OsSettingsApp() {
    val jump by OsSettingsNav.page.collectAsState()
    var page by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(jump) { if (jump.isNotEmpty()) { page = jump; OsSettingsNav.page.value = "" } }
    if (page.isNotEmpty()) BackHandler { page = if (page.startsWith("app:")) "apps" else "" }
    val back = { page = if (page.startsWith("app:")) "apps" else "" }
    Column(Modifier.fillMaxSize().background(osBg()).verticalScroll(rememberScrollState())) {
        when {
            page == "" -> SettingsRoot { page = it }
            page == "wallpaper" -> WallpaperPage(back)
            page == "home" -> HomeLookPage(back)
            page == "dock" -> DockPage(back)
            page == "display" -> DisplayPage(back)
            page == "apps" -> AppsPage(back) { page = "app:$it" }
            page.startsWith("app:") -> AppPermsPage(page.removePrefix("app:"), back)
            page == "storage" -> StoragePage(back)
            page == "memory" -> MemoryPage(back)
            page == "about" -> AboutPage(back)
            page == "reset" -> ResetPage(back)
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun SettingsRoot(go: (String) -> Unit) {
    val look = LocalOsLook.current
    OsAppHeader("Settings")
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (look.dark) Color(0xFF1C1C1E) else Color.White).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        com.blaze.assistant.ui.MascotMark(46.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("BlazeOS", style = osText(look, 17f, FontWeight.SemiBold))
            Text("A clean phone. No accounts signed in.", style = osText(look, 13f, color = look.sub))
        }
    }
    OsGroup {
        OsRow("Wallpaper", onClick = { go("wallpaper") })
        OsRow("Home screen", "Icons, grid, labels", onClick = { go("home") })
        OsRow("Dock", onClick = { go("dock") })
        OsRow("Display & text", onClick = { go("display") })
    }
    OsGroup {
        OsRow("Apps", "Permissions", onClick = { go("apps") })
        OsRow("Storage", onClick = { go("storage") })
        OsRow("Memory", onClick = { go("memory") })
    }
    OsGroup {
        OsRow("About BlazeOS", onClick = { go("about") })
        OsRow("Factory reset", danger = true, onClick = { go("reset") })
    }
}

@Composable
private fun Section(title: String) {
    val look = LocalOsLook.current
    Text(title.uppercase(), style = osText(look, 12f, FontWeight.SemiBold, look.sub), modifier = Modifier.padding(start = 32.dp, top = 14.dp, bottom = 2.dp))
}

@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((v, label) in options) OsChip(label, v == selected) { onPick(v) }
    }
}

@Composable
private fun Swatches(colors: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val look = LocalOsLook.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((hex, label) in colors) {
            val c = OsIcons.parseColor(hex)?.let { Color(it.toInt()) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(c ?: look.ink.copy(alpha = 0.12f))
                    .border(if (hex == selected) 3.dp else 0.dp, look.accent, CircleShape).clickable { onPick(hex) })
                Text(label, style = osText(look, 11f, color = look.sub))
            }
        }
    }
}

@Composable
private fun Toggle(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val look = LocalOsLook.current
    OsRow(title, onClick = { onChange(!on) }, trailing = {
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF30D158), checkedThumbColor = Color.White))
    })
    @Suppress("UNUSED_VARIABLE") val l = look
}

@Composable
private fun WallpaperPage(back: () -> Unit) {
    val look = LocalOsLook.current
    val prefs by BlazeOs.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { note = if (BlazeOs.setWallpaperPhoto(uri)) "" else "That picture couldn't be used." }
    }
    OsAppHeader("Wallpaper", onBack = back)
    Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((id, label) in OsWallpaper.presets) {
            val v = "preset:$id"
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(92.dp).height(170.dp).clip(RoundedCornerShape(18.dp)).border(if (prefs.wallpaper == v) 3.dp else 0.dp, look.accent, RoundedCornerShape(18.dp))
                    .clickable { BlazeOs.update { it.copy(wallpaper = v) } }) { PresetWallpaper(id, Modifier.fillMaxSize()) }
                Text(label, style = osText(look, 12f, color = look.sub))
            }
        }
    }
    Section("Colour")
    Swatches(OsWallpaper.solids.map { it to "" }, prefs.wallpaper.removePrefix("solid:")) { hex -> BlazeOs.update { it.copy(wallpaper = "solid:$hex") } }
    Section("Photo")
    OsGroup {
        OsRow(if (prefs.wallpaper == "photo") "Choose another photo" else "Choose a photo", onClick = {
            BlazeOs.handoff = true
            try { pick.launch("image/*") } catch (_: Exception) { BlazeOs.handoff = false }
        })
    }
    if (note.isNotEmpty()) Text(note, style = osText(look, 13f, color = Color(0xFFFF453A)), modifier = Modifier.padding(16.dp))
    Text("The photo is copied onto the BlazeOS disk (downscaled for this phone).", style = osText(look, 12f, color = look.sub), modifier = Modifier.padding(horizontal = 32.dp))
}

@Composable
private fun HomeLookPage(back: () -> Unit) {
    val prefs by BlazeOs.prefs.collectAsState()
    OsAppHeader("Home screen", onBack = back)
    Section("Icon shape")
    Chips(listOf(OsIcons.SQUIRCLE to "Squircle", OsIcons.CIRCLE to "Circle", OsIcons.ROUNDED to "Rounded"), prefs.iconShape) { v -> BlazeOs.update { it.copy(iconShape = v) } }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (k in listOf("blaze", "music", "browser", "settings")) OsAppIcon(OsApp(OsApps.BUILTIN + k, k), 52.dp)
    }
    Section("Icon tint")
    Swatches(OsPrefs.tints, prefs.iconTint) { v -> BlazeOs.update { it.copy(iconTint = v) } }
    Section("Grid")
    Chips(OsGrid.options.map { it.id to it.id.replace("x", " × ") }, prefs.grid.id) { v -> BlazeOs.update { it.copy(grid = OsGrid.parse(v)) } }
    Section("Labels")
    OsGroup { Toggle("Show app names", prefs.labels) { v -> BlazeOs.update { it.copy(labels = v) } } }
    Section("Widgets")
    OsGroup {
        for (k in OsWidgets.all) Toggle(OsWidgets.label(k), k in prefs.widgets) { on -> BlazeOs.update { it.copy(widgets = if (on) OsWidgets.add(it.widgets, k) else OsWidgets.remove(it.widgets, k)) } }
        OsRow("Reset home screen layout", onClick = { BlazeOs.update { it.copy(order = emptyList(), removed = emptySet(), widgets = OsWidgets.DEFAULT) } })
    }
    Section("Widget glass")
    Chips(OsPrefs.glassLevels.map { it to it.replaceFirstChar { c -> c.uppercase() } }, prefs.glass) { v -> BlazeOs.update { it.copy(glass = v) } }
    Section("Accent colour")
    Swatches(OsPrefs.accents, prefs.accent) { v -> BlazeOs.update { it.copy(accent = v) } }
}

@Composable
private fun DockPage(back: () -> Unit) {
    val look = LocalOsLook.current
    val prefs by BlazeOs.prefs.collectAsState()
    val reg by BlazeOs.registry.collectAsState()
    val apps = remember(reg) { BlazeOs.apps(reg) }
    val ids = remember(apps) { apps.map { it.id }.toHashSet() }
    val dock = prefs.dock?.let { OsDock.normalize(it, ids) } ?: OsDock.defaults(apps)
    OsAppHeader("Dock", onBack = back)
    OsGroup { Toggle("Show the dock", prefs.dockOn) { v -> BlazeOs.update { it.copy(dockOn = v) } } }
    Section("In the dock (${dock.size} of ${OsDock.MAX})")
    OsGroup {
        for (id in dock) {
            val a = apps.firstOrNull { it.id == id } ?: continue
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OsAppIcon(a, 36.dp); Spacer(Modifier.width(12.dp))
                Text(a.label, style = osText(look, 16f), modifier = Modifier.weight(1f))
                Text("↑", style = osText(look, 18f, color = look.accent), modifier = Modifier.clip(CircleShape).clickable { BlazeOs.update { it.copy(dock = OsDock.move(dock, id, -1)) } }.padding(8.dp))
                Text("↓", style = osText(look, 18f, color = look.accent), modifier = Modifier.clip(CircleShape).clickable { BlazeOs.update { it.copy(dock = OsDock.move(dock, id, 1)) } }.padding(8.dp))
                Text("Remove", style = osText(look, 14f, color = Color(0xFFFF453A)), modifier = Modifier.clip(CircleShape).clickable { BlazeOs.update { it.copy(dock = OsDock.toggle(dock, id)) } }.padding(8.dp))
            }
        }
    }
    Section("Add")
    OsGroup {
        for (a in apps.filter { it.id !in dock }) OsRow(a.label, if (OsDock.canAdd(dock, a.id)) "Add" else "Dock is full",
            onClick = { if (OsDock.canAdd(dock, a.id)) BlazeOs.update { it.copy(dock = OsDock.toggle(dock, a.id)) } })
    }
}

@Composable
private fun DisplayPage(back: () -> Unit) {
    val prefs by BlazeOs.prefs.collectAsState()
    OsAppHeader("Display & text", onBack = back)
    Section("Appearance")
    Chips(listOf("dark" to "Dark", "light" to "Light"), prefs.theme) { v -> BlazeOs.update { it.copy(theme = v) } }
    Section("Text size")
    Chips(listOf("s" to "Small", "m" to "Default", "l" to "Large"), prefs.font) { v -> BlazeOs.update { it.copy(font = v) } }
    Section("Brightness (BlazeOS only)")
    OsGroup {
        androidx.compose.material3.Slider(prefs.brightness / 100f, { BlazeOs.setBrightness((it * 100).toInt()) }, Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun AppsPage(back: () -> Unit, open: (String) -> Unit) {
    val reg by BlazeOs.registry.collectAsState()
    val apps = remember(reg) { BlazeOs.apps(reg) }
    OsAppHeader("Apps", onBack = back)
    OsGroup { for (a in apps) OsRow(a.label, if (a.builtin) "Built in" else reg.get(a.key)?.let { OsResources.size(it.size) }.orEmpty(), onClick = { open(a.id) }) }
}

@Composable
private fun AppPermsPage(appId: String, back: () -> Unit) {
    val look = LocalOsLook.current
    val grants by BlazeOs.grants.collectAsState()
    val reg by BlazeOs.registry.collectAsState()
    val inst = if (appId.startsWith(OsApps.PKG)) reg.get(appId.removePrefix(OsApps.PKG)) else null
    OsAppHeader(BlazeOs.label(appId), onBack = back)
    Section("Permissions")
    OsGroup {
        for (p in OsPerms.all) {
            val d = grants.decide(appId, p)
            OsRow(OsPerms.label(p), when (d) { OsGrants.Decision.ALLOWED -> "Allowed"; OsGrants.Decision.DENIED -> "Not allowed"; else -> "Ask" }, onClick = {
                BlazeOs.setGrant(appId, p, when (d) { OsGrants.Decision.ASK -> true; OsGrants.Decision.ALLOWED -> false; else -> null })
            })
        }
    }
    Text("Tap to switch: Ask → Allowed → Not allowed → Ask.", style = osText(look, 12f, color = look.sub), modifier = Modifier.padding(horizontal = 32.dp))
    if (inst != null) {
        Section("App")
        OsGroup {
            OsRow("Version", inst.manifest.version)
            OsRow("Size", OsResources.size(inst.size))
            OsRow("Uninstall", danger = true, onClick = { OsLaunch.uninstall(inst.id) })
        }
    }
    UninstallConfirm()
}

@Composable
private fun StoragePage(back: () -> Unit) {
    val look = LocalOsLook.current
    val ctx = LocalContext.current
    val budget by BlazeOs.budget.collectAsState()
    val reg by BlazeOs.registry.collectAsState()
    var parts by remember { mutableStateOf(listOf<Pair<String, Long>>()) }
    LaunchedEffect(reg) {
        parts = withContext(Dispatchers.IO) {
            listOf("Apps" to OsDisk.size(OsDisk.apps(ctx)), "App data" to OsDisk.size(OsDisk.data(ctx)), "System" to OsDisk.size(OsDisk.system(ctx)),
                "Cache (not counted)" to OsDisk.size(OsDisk.cache(ctx)))
        }
        BlazeOs.diskUsed.value = parts.take(3).sumOf { it.second }
    }
    val used = parts.take(3).sumOf { it.second }
    OsAppHeader("Storage", onBack = back)
    Text("${OsResources.size(used)} of ${OsResources.size(budget.diskQuota)} used", style = osText(look, 20f, FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 16.dp))
    Box(Modifier.padding(16.dp).fillMaxWidth().height(10.dp).clip(CircleShape).background(look.ink.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth(if (budget.diskQuota > 0) (used.toFloat() / budget.diskQuota).coerceIn(0.01f, 1f) else 0f).height(10.dp).clip(CircleShape).background(look.accent))
    }
    OsGroup { for ((k, v) in parts) OsRow(k, OsResources.size(v)) }
    Text("The BlazeOS disk may use up to the smaller of 8 GB and a quarter of the phone's free space, set when BlazeOS starts. " +
        "Uninstalling an app frees its space.", style = osText(look, 12f, color = look.sub), modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
    Section("Installed apps")
    OsGroup { for (a in reg.apps) OsRow(a.manifest.name, OsResources.size(a.size)) }
}

@Composable
private fun MemoryPage(back: () -> Unit) {
    val look = LocalOsLook.current
    val ctx = LocalContext.current
    val budget by BlazeOs.budget.collectAsState()
    val tasks by BlazeOs.tasks.collectAsState()
    var avail by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(0L) }
    var heap by remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(Unit) { val m = withContext(Dispatchers.IO) { OsHost.memory(ctx) }; avail = m.first; total = m.second; heap = OsHost.heap() }
    OsAppHeader("Memory", onBack = back)
    Section("This phone right now")
    OsGroup {
        OsRow("Free memory", "${OsResources.size(avail)} of ${OsResources.size(total)}")
        OsRow("Blaze is using", "${OsResources.size(heap.first)} of ${OsResources.size(heap.second)}")
    }
    Section("What BlazeOS took at start")
    OsGroup {
        OsRow("Tier", budget.tier.replaceFirstChar { it.uppercase() })
        OsRow("Picture cache", OsResources.size(budget.imageCacheBytes))
        OsRow("Disk cache", OsResources.size(budget.diskCacheBytes))
        OsRow("Apps open at once", "${budget.maxLiveApps}")
        OsRow("Open now", "${tasks.open.size}")
        OsRow("Blur", if (budget.blur) "On" else "Off (drawn glass)")
        OsRow("Animations", budget.anim.name.lowercase().replaceFirstChar { it.uppercase() })
    }
    Text("Pause frees memory: pictures, background apps and music stop; the app on screen comes back on resume.", style = osText(look, 12f, color = look.sub),
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
}

@Composable
private fun AboutPage(back: () -> Unit) {
    val reg by BlazeOs.registry.collectAsState()
    val budget by BlazeOs.budget.collectAsState()
    OsAppHeader("About", onBack = back)
    OsGroup {
        OsRow("Name", "BlazeOS")
        OsRow("BlazeOS version", BlazeOs.VERSION)
        OsRow("Blaze", com.blaze.assistant.BuildConfig.VERSION_NAME)
        OsRow("Apps installed", "${reg.apps.size}")
        OsRow("Disk", OsResources.size(budget.diskQuota))
        OsRow("Accounts", "None signed in")
    }
}

@Composable
private fun ResetPage(back: () -> Unit) {
    val look = LocalOsLook.current
    val scope = rememberCoroutineScope()
    var sure by remember { mutableStateOf(false) }
    OsAppHeader("Factory reset", onBack = back)
    Text("Erases the BlazeOS disk: every installed app and its data, BlazeOS Calendar, permissions and the wallpaper photo, and puts BlazeOS's " +
        "settings back to new. Nothing else of Blaze's is touched (your chats, music, photos and files stay).", style = osText(look, 15f), modifier = Modifier.padding(16.dp))
    OsGroup {
        if (!sure) OsRow("Erase BlazeOS…", danger = true, onClick = { sure = true })
        else {
            OsRow("Yes, erase BlazeOS and restart", danger = true, onClick = { scope.launch { OsWeb.releaseAll(); OsWasm.releaseAll(); BlazeOs.factoryReset(); BlazeOs.open() } })
            OsRow("Cancel", onClick = { sure = false })
        }
    }
}
