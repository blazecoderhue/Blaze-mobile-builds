package com.blaze.assistant.blazeos

import android.content.ComponentName
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Role
import com.blaze.assistant.ui.MascotMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * v0.33.2 BlazeOS apps: launching (BlazeOS permission first, then the app in the BlazeOS frame, or a Blaze screen for
 * Camera / Video / Terminal), the app frame, and the small built-ins (Blaze, Calendar, Clock, Weather, Files) plus the
 * wrappers of Blaze's existing screens (Music, Photos, Desktop). Apps are composed only while open (lazy): nothing of an
 * app loads before its first launch.
 */

object OsLaunch {
    /** Built-ins that are Blaze screens with their own window (camera, full video player, terminal). */
    val handoffKeys = setOf("camera", "video", "terminal")

    /** Waiting for a yes before an app (and its data) is deleted. */
    val confirmUninstall = MutableStateFlow<OsApp?>(null)

    suspend fun open(ctx: Context, a: OsApp) {
        val perms = if (a.builtin) OsPerms.forBuiltin(a.key) else emptyList()
        if (!BlazeOs.request(a.id, perms)) return
        if (a.builtin && a.key in handoffKeys) { handoff(ctx, a.key); return }
        BlazeOs.launch(a.id)
    }

    private fun handoff(ctx: Context, key: String) {
        BlazeOs.handoff = true
        val ok = try {
            when (key) {
                "camera" -> com.blaze.assistant.device.BlazeCamera.open(ctx, null, com.blaze.assistant.device.CameraModes.Origin.STANDALONE)
                "video" -> { com.blaze.assistant.mediaapps.VideoActivity.open(ctx); true }
                "terminal" -> { com.blaze.assistant.terminal.BlazeTerminalActivity.open(ctx); true }
                else -> false
            }
        } catch (_: Exception) { false }
        if (!ok) { BlazeOs.handoff = false; BlazeOs.notify(BlazeOs.label("blaze:$key"), "Couldn't open", "This phone refused to open it.") }
    }

    fun uninstall(manifestId: String) {
        confirmUninstall.value = BlazeOs.apps().firstOrNull { it.id == OsApps.pkgId(manifestId) }
    }

    fun confirmed(a: OsApp) { confirmUninstall.value = null; MainScope().launch { BlazeOs.uninstall(a.key) } }
}

/** Which Settings page to open next (the battery widget opens Storage). */
object OsSettingsNav { val page = MutableStateFlow("") }

@Composable
fun UninstallConfirm() {
    val a by OsLaunch.confirmUninstall.collectAsState()
    val app = a ?: return
    OsSheet("Uninstall ${app.label}? Its data on BlazeOS is deleted too.", onClose = { OsLaunch.confirmUninstall.value = null }) {
        SheetRow("Uninstall", danger = true) { OsLaunch.confirmed(app) }
    }
}

/** The frame an app runs in: below the status row, above the home indicator. Back goes home unless the app uses it. */
@Composable
fun OsAppHost(id: String) {
    BackHandler { BlazeOs.home() }
    val reg by BlazeOs.registry.collectAsState()
    // An installed site's theme_color fills the status row above it, like an app's own bar.
    val theme = if (id.startsWith(OsApps.PKG)) reg.get(id.removePrefix(OsApps.PKG))?.manifest?.themeColor.orEmpty() else ""
    if (theme.isNotEmpty()) Box(Modifier.fillMaxSize().background(Color(parseHex(theme, 0))))
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(top = 36.dp, bottom = 26.dp)) {
        if (id.startsWith(OsApps.BUILTIN)) when (id.removePrefix(OsApps.BUILTIN)) {
            "blaze" -> OsBlazeApp()
            "photos" -> com.blaze.assistant.ui.GalleryScreen(onDismiss = { BlazeOs.home() })
            "music" -> OsMusicApp()
            "browser" -> OsBrowserApp()
            "calendar" -> OsCalendarApp()
            "clock" -> OsClockApp()
            "weather" -> OsWeatherApp()
            "files" -> OsFilesApp()
            "linux" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) { com.blaze.assistant.ui.LinuxDesktopPage() }
            "store" -> OsStoreApp()
            "settings" -> OsSettingsApp()
            else -> OsMissing(id)
        } else {
            val inst = reg.get(id.removePrefix(OsApps.PKG))
            when {
                inst == null -> OsMissing(id)
                inst.manifest.entry.type == OsManifest.LINUX -> OsLinuxApp(inst)
                inst.manifest.entry.type == OsManifest.WASM -> OsWasmApp(inst)
                inst.manifest.entry.type == OsManifest.BUILTIN -> OsMissing("a BlazeOS app: open ${inst.manifest.entry.key} from the home screen")
                else -> OsWebApp(inst)
            }
        }
    }
}

@Composable
private fun OsMissing(id: String) {
    val look = LocalOsLook.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This app isn't installed ($id).", style = osText(look, 15f, color = look.sub)) }
}

/** A plain BlazeOS app header: optional back chevron, a large title, an optional action on the right. */
@Composable
fun OsAppHeader(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    val look = LocalOsLook.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) Row(Modifier.clip(CircleShape).clickable(onClick = onBack).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowBackIosNew, "Back", tint = look.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            action?.invoke()
        }
        Text(title, style = osText(look, 30f, FontWeight.SemiBold))
        Spacer(Modifier.height(8.dp))
    }
}

/** A grouped list card (iOS-style inset group). */
@Composable
fun OsGroup(content: @Composable () -> Unit) {
    val look = LocalOsLook.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(if (look.dark) Color(0xFF1C1C1E) else Color.White)) { content() }
}

@Composable
fun OsRow(title: String, value: String = "", danger: Boolean = false, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val look = LocalOsLook.current
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = osText(look, 16f, color = if (danger) Color(0xFFFF453A) else look.ink), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value.isNotEmpty()) Text(value, style = osText(look, 15f, color = look.sub), maxLines = 1)
        trailing?.invoke()
    }
}

@Composable
fun OsField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, single: Boolean = true) {
    val look = LocalOsLook.current
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(look.ink.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, style = osText(look, 16f, color = look.sub))
        BasicTextField(value, onChange, singleLine = single, textStyle = osText(look, 16f), cursorBrush = SolidColor(look.accent), modifier = Modifier.fillMaxWidth())
    }
}

/** An app's own page background (BlazeOS light / dark). */
@Composable
fun osBg(): Color = if (LocalOsLook.current.dark) Color.Black else Color(0xFFF2F2F7)

// ---------------- Blaze (the assistant app) ----------------

/** Blaze inside BlazeOS: the same Blaze brain, in a phone-app layout. */
@Composable
private fun OsBlazeApp() {
    val look = LocalOsLook.current
    val msgs by Agent.messages.collectAsState()
    val mood by Agent.mood.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    val shown = remember(msgs) { msgs.filter { (it.role == Role.USER || it.role == Role.BLAZE) && it.text.isNotBlank() }.takeLast(40) }
    val list = rememberLazyListState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) list.scrollToItem(shown.size - 1) }
    Column(Modifier.fillMaxSize().background(osBg()).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MascotMark(36.dp, spin = mood != com.blaze.assistant.agent.Mood.IDLE)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Blaze", style = osText(look, 20f, FontWeight.SemiBold))
                Text(if (mood != com.blaze.assistant.agent.Mood.IDLE) "Working on it…" else "Your assistant, on BlazeOS", style = osText(look, 12f, color = look.sub))
            }
        }
        if (shown.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            MascotMark(96.dp)
            Spacer(Modifier.height(12.dp))
            Text("Ask Blaze anything", style = osText(look, 20f, FontWeight.SemiBold))
            Text("Same Blaze as your chat. BlazeOS itself has no accounts signed in.", style = osText(look, 13f, color = look.sub), textAlign = TextAlign.Center)
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { m ->
                val mine = m.role == Role.USER
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Text(m.text.take(4000), style = osText(look, 15f, color = if (mine) Color.White else look.ink),
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (mine) look.accent else look.ink.copy(alpha = 0.09f))
                            .padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(if (mine) 0.8f else 0.92f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OsField(text, { text = it }, "Message Blaze", Modifier.weight(1f), single = false)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (text.isBlank()) look.ink.copy(alpha = 0.15f) else look.accent)
                .clickable(enabled = text.isNotBlank()) { Agent.send(text.trim()); text = "" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ---------------- Music (Blaze Music inside the frame) ----------------

@Composable
private fun OsMusicApp() {
    val ctx = LocalContext.current
    var ctl by remember { mutableStateOf<androidx.media3.session.MediaController?>(null) }
    var tab by rememberSaveable { mutableStateOf("library") }
    DisposableEffect(Unit) {
        val f = androidx.media3.session.MediaController.Builder(ctx, androidx.media3.session.SessionToken(ctx,
            ComponentName(ctx, com.blaze.assistant.mediaapps.MusicService::class.java))).buildAsync()
        f.addListener({ try { ctl = f.get(); BlazeOs.applyVolume() } catch (_: Exception) { } }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
        onDispose { ctl = null; androidx.media3.session.MediaController.releaseFuture(f) }
    }
    com.blaze.assistant.mediaapps.MusicApp(ctl, tab, onTab = { tab = it }, onClose = { BlazeOs.home() })
}

// ---------------- Calendar ----------------

data class OsEvent(val id: Long, val title: String, val start: Long, val minutes: Int = 60)

/** BlazeOS's own calendar: events in data/blaze_calendar/events.json (no account; nothing synced). */
object OsCalendarStore {
    private fun file(ctx: Context) = File(OsDisk.dataDir(ctx, "blaze:calendar"), "events.json")

    fun parse(text: String?): List<OsEvent> = try {
        val a = JSONArray(text ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            OsEvent(o.optLong("id"), o.optString("title"), o.optLong("start"), o.optInt("minutes", 60)).takeIf { it.title.isNotBlank() && it.start > 0 }
        } }.sortedBy { it.start }
    } catch (_: Exception) { emptyList() }

    fun json(list: List<OsEvent>): String = JSONArray().apply {
        for (e in list) put(JSONObject().put("id", e.id).put("title", e.title).put("start", e.start).put("minutes", e.minutes))
    }.toString()

    fun load(ctx: Context): List<OsEvent> = parse(OsDisk.readText(file(ctx)))
    fun save(ctx: Context, list: List<OsEvent>) = OsDisk.writeText(file(ctx), json(list))

    /** The next event that hasn't ended yet. */
    fun next(ctx: Context, now: Long): OsEvent? = load(ctx).firstOrNull { it.start + it.minutes * 60_000L > now }

    fun whenLabel(e: OsEvent, now: Long): String {
        val f = java.text.SimpleDateFormat("H:mm", java.util.Locale.getDefault())
        val day = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
        val sameDay = now / 86_400_000L == e.start / 86_400_000L
        return (if (sameDay) "Today " else day.format(java.util.Date(e.start)) + " · ") + f.format(java.util.Date(e.start)) + "–" + f.format(java.util.Date(e.start + e.minutes * 60_000L))
    }
}

@Composable
private fun OsCalendarApp() {
    val look = LocalOsLook.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<OsEvent>>(emptyList()) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var dayOffset by rememberSaveable { mutableStateOf(0) }
    var time by rememberSaveable { mutableStateOf("14:00") }
    LaunchedEffect(Unit) { events = withContext(Dispatchers.IO) { OsCalendarStore.load(ctx) } }
    fun persist(l: List<OsEvent>) { events = l; scope.launch(Dispatchers.IO) { OsCalendarStore.save(ctx, l) } }
    if (adding) BackHandler { adding = false }
    Column(Modifier.fillMaxSize().background(osBg())) {
        OsAppHeader(if (adding) "New event" else "Calendar", onBack = if (adding) ({ adding = false }) else null, action = {
            if (!adding) Text("Add", style = osText(look, 16f, FontWeight.Medium, look.accent), modifier = Modifier.clip(CircleShape).clickable { adding = true }.padding(8.dp))
        })
        if (adding) Column(Modifier.padding(16.dp)) {
            OsField(title, { title = it.take(80) }, "Title")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, d) in listOf("Today" to 0, "Tomorrow" to 1, "+2 days" to 2, "Next week" to 7)) OsChip(label, dayOffset == d) { dayOffset = d }
            }
            Spacer(Modifier.height(12.dp))
            OsField(time, { time = it.take(5) }, "Time (HH:MM)")
            Spacer(Modifier.height(16.dp))
            val hm = Regex("^(\\d{1,2}):(\\d{2})$").find(time.trim())
            val ok = title.isNotBlank() && hm != null && hm.groupValues[1].toInt() < 24 && hm.groupValues[2].toInt() < 60
            OsPillButton("Save", primary = ok) {
                if (ok && hm != null) {
                    val c = java.util.Calendar.getInstance()
                    c.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
                    c.set(java.util.Calendar.HOUR_OF_DAY, hm.groupValues[1].toInt()); c.set(java.util.Calendar.MINUTE, hm.groupValues[2].toInt())
                    c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
                    persist((events + OsEvent(System.currentTimeMillis(), title.trim(), c.timeInMillis)).sortedBy { it.start })
                    title = ""; adding = false
                }
            }
        } else {
            val now = System.currentTimeMillis()
            val upcoming = events.filter { it.start + it.minutes * 60_000L > now }
            if (upcoming.isEmpty()) Text("No events. BlazeOS's calendar is its own: nothing is synced from an account.", style = osText(look, 14f, color = look.sub),
                modifier = Modifier.padding(16.dp))
            else LazyColumn { items(upcoming, key = { it.id }) { e ->
                OsGroup {
                    OsRow(e.title, OsCalendarStore.whenLabel(e, now), trailing = {
                        Icon(Icons.Rounded.Delete, "Delete", tint = look.sub, modifier = Modifier.padding(start = 10.dp).size(20.dp).clickable { persist(events - e) })
                    })
                }
            } }
        }
    }
}

@Composable
fun OsChip(text: String, on: Boolean, onClick: () -> Unit) {
    val look = LocalOsLook.current
    Box(Modifier.clip(CircleShape).background(if (on) look.accent else look.ink.copy(alpha = 0.1f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, style = osText(look, 14f, FontWeight.Medium, if (on) Color.White else look.ink))
    }
}

// ---------------- Clock ----------------

/** The clock's timer and stopwatch keep running while the Clock app is in the background. */
object OsClock {
    val timerEnd = MutableStateFlow(0L)
    val stopStart = MutableStateFlow(0L)
    val stopAcc = MutableStateFlow(0L)
    private var timerJob: kotlinx.coroutines.Job? = null

    fun startTimer(minutes: Int) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        timerEnd.value = end
        timerJob?.cancel()
        timerJob = MainScope().launch {
            while (System.currentTimeMillis() < end && timerEnd.value == end) delay(500)
            if (timerEnd.value == end) { timerEnd.value = 0L; BlazeOs.notify("Clock", "Timer done", "$minutes min") }
        }
    }
    fun cancelTimer() { timerEnd.value = 0L; timerJob?.cancel() }
}

@Composable
private fun OsClockApp() {
    val look = LocalOsLook.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val end by OsClock.timerEnd.collectAsState()
    val sStart by OsClock.stopStart.collectAsState()
    val sAcc by OsClock.stopAcc.collectAsState()
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(if (sStart > 0) 50 else 250) } }
    Column(Modifier.fillMaxSize().background(osBg()).verticalScroll(rememberScrollState())) {
        OsAppHeader("Clock")
        Text(java.text.SimpleDateFormat("H:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now)), style = osText(look, 56f, FontWeight.Normal),
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault()).format(java.util.Date(now)) + " · " + java.util.TimeZone.getDefault().id,
            style = osText(look, 14f, color = look.sub), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Text("TIMER", style = osText(look, 12f, FontWeight.SemiBold, look.sub), modifier = Modifier.padding(horizontal = 32.dp))
        OsGroup {
            if (end > 0) {
                val left = ((end - now) / 1000).coerceAtLeast(0)
                OsRow("%d:%02d".format(left / 60, left % 60), "Cancel", onClick = { OsClock.cancelTimer() })
            } else Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in listOf(1, 3, 5, 10, 25)) OsChip("$m min", false) { OsClock.startTimer(m) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("STOPWATCH", style = osText(look, 12f, FontWeight.SemiBold, look.sub), modifier = Modifier.padding(horizontal = 32.dp))
        OsGroup {
            val ms = sAcc + if (sStart > 0) now - sStart else 0L
            OsRow("%d:%02d.%02d".format(ms / 60_000, (ms / 1000) % 60, (ms / 10) % 100), if (sStart > 0) "Stop" else "Start", onClick = {
                if (sStart > 0) { OsClock.stopAcc.value = sAcc + (System.currentTimeMillis() - sStart); OsClock.stopStart.value = 0L }
                else OsClock.stopStart.value = System.currentTimeMillis()
            })
            if (sStart == 0L && sAcc > 0) OsRow("Reset", onClick = { OsClock.stopAcc.value = 0L })
        }
    }
}

// ---------------- Weather (Open-Meteo, free, no key) ----------------

@Composable
private fun OsWeatherApp() {
    val look = LocalOsLook.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember { File(OsDisk.dataDir(ctx, "blaze:weather"), "city.json") }
    var city by remember { mutableStateOf<JSONObject?>(null) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    suspend fun fetch(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 10000
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) { null }
    }
    suspend fun load(c: JSONObject) {
        status = "Loading…"
        data = fetch("https://api.open-meteo.com/v1/forecast?latitude=${c.optDouble("latitude")}&longitude=${c.optDouble("longitude")}" +
            "&current=temperature_2m,weather_code,wind_speed_10m,relative_humidity_2m&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto")
        status = if (data == null) "No connection. Try again when you're online." else ""
    }
    LaunchedEffect(Unit) {
        city = withContext(Dispatchers.IO) { OsDisk.readText(file)?.let { try { JSONObject(it) } catch (_: Exception) { null } } }
        city?.let { load(it) }
    }
    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF2F80ED), Color(0xFF0B3D91)))).verticalScroll(rememberScrollState())) {
        val c = city
        if (c == null || q.isNotEmpty() || results.isNotEmpty()) {
            OsAppHeader("Weather")
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OsField(q, { q = it }, "Search a city", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OsChip("Search", true) { scope.launch {
                    status = "Searching…"
                    val r = fetch("https://geocoding-api.open-meteo.com/v1/search?count=6&name=" + java.net.URLEncoder.encode(q.trim(), "UTF-8"))
                    results = r?.optJSONArray("results")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
                    status = if (r == null) "No connection." else if (results.isEmpty()) "No city found." else ""
                } }
            }
            for (r in results) OsGroup { OsRow(r.optString("name"), listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "), onClick = {
                city = r; results = emptyList(); q = ""
                scope.launch { withContext(Dispatchers.IO) { OsDisk.writeText(file, r.toString()) }; load(r) }
            }) }
            if (c == null && results.isEmpty()) Text("BlazeOS doesn't use your location. Pick a city; it's kept on the BlazeOS disk.", style = osText(look, 14f, color = Color.White.copy(alpha = 0.8f)),
                modifier = Modifier.padding(16.dp))
        } else {
            val cur = data?.optJSONObject("current")
            Spacer(Modifier.height(30.dp))
            Text(c.optString("name"), style = osText(look, 30f, FontWeight.Medium, Color.White), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(cur?.let { "${it.optDouble("temperature_2m").toInt()}°" } ?: "–", style = osText(look, 84f, FontWeight.Normal, Color.White), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(cur?.let { wmo(it.optInt("weather_code")) + " · wind ${it.optDouble("wind_speed_10m").toInt()} km/h · ${it.optInt("relative_humidity_2m")}%" } ?: status,
                style = osText(look, 15f, color = Color.White.copy(alpha = 0.85f)), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            val d = data?.optJSONObject("daily")
            if (d != null) Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.16f)).padding(12.dp)) {
                val days = d.optJSONArray("time"); val hi = d.optJSONArray("temperature_2m_max"); val lo = d.optJSONArray("temperature_2m_min"); val code = d.optJSONArray("weather_code")
                for (i in 0 until minOf(7, days?.length() ?: 0)) Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    val label = try { java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(days!!.optString(i))!!) } catch (_: Exception) { "" }
                    Text(if (i == 0) "Today" else label, style = osText(look, 15f, FontWeight.Medium, Color.White), modifier = Modifier.width(70.dp))
                    Text(wmo(code?.optInt(i) ?: 0), style = osText(look, 14f, color = Color.White.copy(alpha = 0.85f)), modifier = Modifier.weight(1f))
                    Text("${lo?.optDouble(i)?.toInt()}° / ${hi?.optDouble(i)?.toInt()}°", style = osText(look, 15f, color = Color.White))
                }
            }
            Text("Change city", style = osText(look, 15f, FontWeight.Medium, Color.White), modifier = Modifier.padding(16.dp).clip(CircleShape)
                .clickable { city = null }.padding(8.dp))
            Text("Data: Open-Meteo.com (free, CC BY 4.0)", style = osText(look, 11f, color = Color.White.copy(alpha = 0.6f)), modifier = Modifier.padding(horizontal = 24.dp))
        }
        if (status.isNotEmpty() && city == null) Text(status, style = osText(look, 14f, color = Color.White), modifier = Modifier.padding(16.dp))
    }
}

/** WMO weather codes (Open-Meteo) in words. */
fun wmo(c: Int): String = when (c) {
    0 -> "Clear"; 1, 2 -> "Partly cloudy"; 3 -> "Cloudy"; 45, 48 -> "Fog"; in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"
    in 80..82 -> "Showers"; 85, 86 -> "Snow showers"; in 95..99 -> "Thunderstorm"; else -> "—"
}

// ---------------- Files (the BlazeOS disk only) ----------------

@Composable
private fun OsFilesApp() {
    val look = LocalOsLook.current
    val ctx = LocalContext.current
    val root = remember { OsDisk.root(ctx) }
    var path by rememberSaveable { mutableStateOf("") }
    val dir = remember(path) { File(root, path) }
    var entries by remember { mutableStateOf<List<Pair<File, Long>>>(emptyList()) }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(path) {
        entries = withContext(Dispatchers.IO) {
            (dir.listFiles()?.toList() ?: emptyList()).filter { !it.name.endsWith(".tmp") }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { it to OsDisk.size(it) }
        }
    }
    val up: (() -> Unit)? = if (preview != null) ({ preview = null }) else if (path.isNotEmpty()) ({ path = path.substringBeforeLast('/', "") }) else null
    if (up != null) BackHandler { up() }
    Column(Modifier.fillMaxSize().background(osBg())) {
        OsAppHeader(preview?.first ?: if (path.isEmpty()) "Files" else path.substringAfterLast('/'), onBack = up)
        val p = preview
        if (p != null) Text(p.second, style = osText(look, 13f), modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()))
        else {
            Text(if (path.isEmpty()) "Everything on the BlazeOS disk. Nothing of the phone's own storage is here." else "BlazeOS / $path",
                style = osText(look, 13f, color = look.sub), modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(Modifier.fillMaxSize()) { items(entries, key = { it.first.name }) { (f, size) ->
                OsGroup {
                    Row(Modifier.fillMaxWidth().clickable {
                        if (f.isDirectory) path = if (path.isEmpty()) f.name else "$path/${f.name}"
                        else if (f.length() < 256 * 1024 && f.extension.lowercase() in setOf("json", "txt", "html", "js", "css", "md")) preview = f.name to (OsDisk.readText(f) ?: "")
                    }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (f.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description, null, tint = if (f.isDirectory) Color(0xFF0A84FF) else look.sub, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(f.name, style = osText(look, 15f), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(OsResources.size(size), style = osText(look, 13f, color = look.sub))
                    }
                }
            } }
        }
    }
}
