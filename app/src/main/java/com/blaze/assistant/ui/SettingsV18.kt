package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.IntentCache
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.device.CrashLog
import com.blaze.assistant.device.DeviceStatus
import com.blaze.assistant.device.SelfCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

@Composable
internal fun V18Note(text: String, top: Int = 0) {
    val c = LocalBlaze.current.mono
    Text(text, modifier = Modifier.padding(top = top.dp, bottom = 10.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
}

@Composable
internal fun V18Button(text: String, primary: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(modifier.height(44.dp).then(if (primary) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) { ButtonText(text, color = if (primary) c.paper else c.ink, size = 13.sp) }
}

@Composable
internal fun V18Section(title: String) { Spacer(Modifier.height(24.dp)); Label(title, modifier = Modifier.padding(bottom = 8.dp)) }

@Composable
internal fun Mono(text: String) {
    val c = LocalBlaze.current.mono
    Text(text, modifier = Modifier.fillMaxWidth().hairline(c.hair).padding(12.dp),
        style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, lineHeight = 18.sp, color = c.ink))
}

/** Settings → Device & diagnostics. */
@Composable
fun DevicePage() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { status = DeviceStatus.describe(ctx); delay(5000) } }

    V18Section("Phone health")
    Mono(status.ifBlank { "Reading…" })
    V18Note("Blaze sees a one-line version of this on every request, so it knows when the battery is low or you're offline.", top = 8)

    V18Section("Self-check")
    var checking by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SelfCheck.Item>>(emptyList()) }
    V18Button(if (checking) "Checking…" else "Run self-check") {
        if (!checking) { checking = true; scope.launch { results = SelfCheck.run(ctx); checking = false } }
    }
    if (results.isNotEmpty()) Label("${results.count { it.ok }} of ${results.size} passed", color = if (results.all { it.ok }) c.accent else c.ink, modifier = Modifier.padding(top = 10.dp))
    if (results.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 10.dp).hairline(c.hair)) {
        results.forEach { r ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(7.dp).background(if (r.ok) c.accent else c.ink, BlazeShapes.pill))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                    Text(r.detail, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                }
                if (!r.ok && r.fix.isNotBlank()) Label("Fix ›", color = c.accent, size = 10.sp,
                    modifier = Modifier.clickable { com.blaze.assistant.agent.SelfSetup.open(ctx, r.fix) }.padding(4.dp))
                else Label(if (r.ok) "Pass" else "Fix", color = if (r.ok) c.accent else c.ink, size = 10.sp)
            }
        }
    }

    V18Section("Speed")
    var route by remember { mutableStateOf(Settings.showRoute) }
    ToggleRow("Show speed badge", "⚡ instant / on-device or ☁ model · seconds under replies", route) { route = !route; Settings.showRoute = route }
    // v0.45.2: the old verification path (every check waits in line) for anyone who prefers it.
    var strictV by remember { mutableStateOf(com.blaze.assistant.agent.VerifyPolicy.strict()) }
    ToggleRow("Strict checks", "Off: actions are checked on the phone while the reply comes in, and the receipt under it updates when the check ends. On: every check finishes before Blaze goes on (slower)", strictV) {
        strictV = !strictV; Settings.setBool(com.blaze.assistant.agent.VerifyPolicy.PREF_STRICT, strictV) }
    var cached by remember { mutableStateOf(IntentCache.size()) }
    Spacer(Modifier.height(10.dp))
    V18Note("Instant commands: $cached. Commands that worked once on the phone run again with no model at all.")
    if (cached > 0) V18Button("Clear instant commands", primary = false) { IntentCache.clear(); cached = 0 }

    V18Section("Feel & accessibility")
    var card by remember { mutableStateOf(Settings.taskCard) }
    ToggleRow("Live task card", "Slim card with the current step and Stop over other apps", card) { card = !card; Settings.taskCard = card }
    var hap by remember { mutableStateOf(Settings.haptics) }
    ToggleRow("Haptic ticks", "A light tick when Blaze taps or types, a double tick when done", hap) { hap = !hap; Settings.haptics = hap }
    var scale by remember { mutableStateOf(Settings.textScale) }
    Spacer(Modifier.height(12.dp))
    Label("Chat text size", modifier = Modifier.padding(bottom = 8.dp))
    Row(Modifier.fillMaxWidth()) {
        listOf(0.9f to "S", 1f to "M", 1.15f to "L", 1.3f to "XL").forEach { (v, l) ->
            Box(Modifier.weight(1f).height(40.dp).then(if (scale == v) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f)).clickable { scale = v; Settings.textScale = v },
                contentAlignment = Alignment.Center) { ButtonText(l, color = if (scale == v) c.paper else c.ink, size = 13.sp) }
        }
    }

    V18Section("Crash & lag report")
    var log by remember { mutableStateOf(CrashLog.read()) }
    V18Note(if (log.isBlank()) "Nothing recorded. Crashes, errors and very slow turns are written here, on the phone only." 
        else "${log.lines().count { it.isNotBlank() }} lines · ${CrashLog.crashes()} crash(es). Send it to whoever is fixing Blaze.")
    Row(Modifier.fillMaxWidth()) {
        V18Button("Send report", modifier = Modifier.weight(1f)) { CrashLog.shareIntent(ctx)?.let { ctx.startActivity(it) } }
        Spacer(Modifier.width(8.dp))
        V18Button("Clear", primary = false, modifier = Modifier.weight(1f)) { CrashLog.clear(); log = "" }
    }
    if (log.isNotBlank()) { Spacer(Modifier.height(10.dp)); Mono(log.lines().filter { it.isNotBlank() }.takeLast(12).joinToString("\n").take(2500)) }
}

// ---------------- Terminal ----------------

@Composable
fun TerminalPage() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val T = com.blaze.assistant.terminal.Terminal
    val sessions by T.sessions.collectAsState()
    val sess by T.active.collectAsState()
    var showBlaze by remember { mutableStateOf(false) }
    val out by sess.out.collectAsState()
    val agentOut by T.agentLog.collectAsState()
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var histPos by remember { mutableStateOf(-1) }
    var ctrl by remember { mutableStateOf(false) }
    val scroll = androidx.compose.foundation.rememberScrollState()
    val mode by sess.mode.collectAsState()
    val L = com.blaze.assistant.terminal.Linux
    val lstatus by L.status.collectAsState()
    val installing by L.installing.collectAsState()
    var linuxReady by remember { mutableStateOf(L.installed()) }
    val tscope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(sess) { T.startShell(sess) }
    LaunchedEffect(out.length, agentOut.length, showBlaze) { scroll.animateScrollTo(scroll.maxValue) }
    fun setLine(t: String) { field = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(t.length)) }
    fun send() { val l = field.text; if (l.isNotBlank()) { T.input(l); setLine(""); histPos = -1 } }
    fun insert(t: String) {
        val v = field
        val nt = v.text.substring(0, v.selection.start) + t + v.text.substring(v.selection.end)
        field = androidx.compose.ui.text.input.TextFieldValue(nt, androidx.compose.ui.text.TextRange(v.selection.start + t.length))
    }

    // Tabs: one per shell session, plus a read-only "Blaze" tab with every command Blaze runs.
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        sessions.forEach { s ->
            val on = !showBlaze && s == sess
            Row(Modifier.padding(end = 6.dp).height(34.dp).then(if (on) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f))
                .clickable { showBlaze = false; T.select(s) }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ButtonText(s.title, color = if (on) c.paper else c.ink, size = 13.sp)
                if (sessions.size > 1) ActionText("  ×", color = if (on) c.paper else c.weak, size = 10.sp, modifier = Modifier.clickable { T.closeSession(s) })
            }
        }
        Box(Modifier.padding(end = 6.dp).height(34.dp).hairline(c.hair).clickable { showBlaze = false; T.newSession(mode) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Label("+", color = c.accent, size = 12.sp) }
        Box(Modifier.height(34.dp).then(if (showBlaze) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f)).clickable { showBlaze = true }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            ButtonText("Blaze", color = if (showBlaze) c.paper else c.accent, size = 13.sp) }
    }
    if (!showBlaze) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        listOf("phone" to "Phone shell", "linux" to "Linux (Alpine)").forEach { (id, label) ->
            Box(Modifier.weight(1f).height(40.dp).then(if (mode == id) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f))
                .clickable { if (id == "phone" || linuxReady) T.switchMode(id) }, contentAlignment = Alignment.Center) {
                ButtonText(label, color = if (mode == id) c.paper else if (id == "linux" && !linuxReady) c.weak else c.ink, size = 13.sp) }
        }
    }
    if (!linuxReady) {
        Column(Modifier.fillMaxWidth().hairline(c.accent).padding(12.dp)) {
            Text("Get a real Linux", style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
            Text(lstatus.ifEmpty { "Alpine Linux, about 3.5 MB to download, no root. Then install anything with apk: python3, nodejs, git, gcc, openjdk17…" },
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
            Spacer(Modifier.height(8.dp))
            V18Button(if (installing) "Installing…" else "Install Linux") {
                if (!installing) tscope.launch { L.install(); linuxReady = L.installed(); if (linuxReady) T.switchMode("linux") }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    V18Note(when {
        showBlaze -> "Every command Blaze runs for you, live, with its output. Read-only."
        mode == "linux" -> com.blaze.assistant.terminal.Terminal.defaultDistro().let { d -> if (d == "alpine") "Alpine Linux. Try: apk update && apk add python3, then python3. Your phone's Blaze files are in ~/phone."
            else "${com.blaze.assistant.terminal.TermLogic.distro(d)?.name ?: d} (your chosen Linux). Install software with ${com.blaze.assistant.terminal.Terminal.installHint(d)}. Your phone's Blaze files are in ~/phone." }
        else -> "Android sh plus ~300 BusyBox tools (vi, wget, tar, grep, awk, nc, httpd…). Tab completes file names; ↑ ↓ walk your history." })
    Box(Modifier.fillMaxWidth().height(380.dp).clip(BlazeShapes.medium).background(androidx.compose.ui.graphics.Color(0xFF111110)).padding(12.dp)) {
        Text(if (showBlaze) agentOut.ifEmpty { "Nothing yet. When Blaze runs a command, it shows up here." } else out.ifEmpty { "Starting the shell…" },
            modifier = Modifier.verticalScroll(scroll),
            style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, lineHeight = 17.sp, color = androidx.compose.ui.graphics.Color(0xFFF0F0EE)))
    }
    if (!showBlaze) {
        // Extra keys, like Termux: modifiers, navigation, and the symbols phone keyboards hide.
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(BlazeShapes.pill).background(androidx.compose.ui.graphics.Color(0xFF1C1C1A)).horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 4.dp)) {
            val keys = listOf("ESC", "CTRL", "TAB", "↑", "↓", "←", "→", "|", "~", "/", "-", ">", "&", ";", "$", "*", "\"")
            keys.forEach { k ->
                Box(Modifier.height(40.dp).width(46.dp).clip(BlazeShapes.pill).background(if (k == "CTRL" && ctrl) c.accent else androidx.compose.ui.graphics.Color.Transparent).clickable {
                    when (k) {
                        "ESC" -> { setLine(""); histPos = -1 }
                        "CTRL" -> ctrl = !ctrl
                        "TAB" -> setLine(T.complete(field.text))
                        "↑" -> { val h = sess.history; if (h.isNotEmpty()) { histPos = if (histPos < 0) h.size - 1 else (histPos - 1).coerceAtLeast(0); setLine(h[histPos]) } }
                        "↓" -> { val h = sess.history; if (histPos >= 0) { histPos++; if (histPos >= h.size) { histPos = -1; setLine("") } else setLine(h[histPos]) } }
                        "←" -> field = field.copy(selection = androidx.compose.ui.text.TextRange((field.selection.start - 1).coerceAtLeast(0)))
                        "→" -> field = field.copy(selection = androidx.compose.ui.text.TextRange((field.selection.end + 1).coerceAtMost(field.text.length)))
                        else -> insert(k)
                    }
                }, contentAlignment = Alignment.Center) {
                    Text(k, style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFF0F0EE)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().hairline(c.ink), verticalAlignment = Alignment.CenterVertically) {
            Label(if (ctrl) "^" else "$", color = c.accent, size = 12.sp, modifier = Modifier.padding(start = 12.dp))
            Box(Modifier.weight(1f).padding(12.dp)) {
                if (field.text.isEmpty()) Text(if (ctrl) "ctrl + letter (c, d, l, z)" else "command", style = TextStyle(fontFamily = Type.Mono, fontSize = 14.sp, color = c.weak))
                androidx.compose.foundation.text.BasicTextField(field, { v ->
                    if (ctrl && v.text.length == field.text.length + 1) {
                        // CTRL is armed: the next letter is a control key, not text.
                        when (v.text[v.selection.start.coerceAtLeast(1) - 1].lowercaseChar()) {
                            'c' -> T.interrupt()
                            'd' -> T.eof()
                            'l' -> T.clearScreen()
                            'z' -> T.sendRaw("\u001A")
                            'u' -> setLine("")
                            else -> {}
                        }
                        ctrl = false
                    } else field = v
                }, singleLine = true,
                    textStyle = TextStyle(fontFamily = Type.Mono, fontSize = 14.sp, color = c.ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send, autoCorrectEnabled = false),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.height(46.dp).width(64.dp).clip(BlazeShapes.pill).glassButton(c).clickable { send() },
                contentAlignment = Alignment.Center) { ButtonText("Run", color = Color.White) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            V18Button("Ctrl-C", primary = false, modifier = Modifier.weight(1f)) { T.interrupt() }
            Spacer(Modifier.width(8.dp))
            V18Button("Clear", primary = false, modifier = Modifier.weight(1f)) { T.clearScreen() }
        }
    } else {
        Spacer(Modifier.height(8.dp))
        V18Button("Clear Blaze log", primary = false) { T.agentLog.value = "" }
    }
    V18Section("Jobs")
    var jobs by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { jobs = com.blaze.assistant.terminal.Terminal.listJobs(); delay(3000) } }
    Mono(jobs)
    var tools by remember { mutableStateOf(Settings.bool("terminal_tools", true)) }
    Spacer(Modifier.height(10.dp))
    ToggleRow("Let Blaze use the terminal", "Risky commands (rm -rf, dd…) still ask first", tools) { tools = !tools; Settings.setBool("terminal_tools", tools) }
    if (linuxReady) {
        V18Section("Linux")
        Mono("Alpine · ${L.sizeMb()} MB on disk")
        Spacer(Modifier.height(8.dp))
        V18Button("Remove Linux", primary = false) { L.uninstall(); linuxReady = false; T.switchMode("phone") }
    }
    V18Section("Termux (optional)")
    val termux = com.blaze.assistant.terminal.Terminal.termuxInstalled(ctx)
    V18Note(if (termux) "Termux is installed: Blaze can run commands there too (python, node, git, openjdk, gradle — even building Android apps). In Termux run: echo allow-external-apps=true >> ~/.termux/termux.properties, then allow Blaze under App info → Permissions → Additional permissions."
        else "For python, node, git or building Android apps on the phone, install Termux from F-Droid. Blaze can then send commands to it. Android doesn't let regular apps run downloaded programs, which is why Termux is needed for that part.")
    if (!termux) V18Button("Get Termux (F-Droid)", primary = false) {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://f-droid.org/packages/com.termux/")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ---------------- Dashboard ----------------

@Composable
fun DashboardPage() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val running by com.blaze.assistant.dashboard.Dashboard.running.collectAsState()
    var lan by remember { mutableStateOf(com.blaze.assistant.dashboard.Dashboard.lan) }
    var auto by remember { mutableStateOf(Settings.bool("dash_auto", false)) }
    V18Note("A web page served by Blaze on your phone: chat with Blaze (it can still use your phone), see tokens and cost per day, phone health, memories, skills and jobs.")
    V18Button(if (running) "Open dashboard" else "Start & open dashboard") { com.blaze.assistant.dashboard.Dashboard.open(ctx) }
    Spacer(Modifier.height(8.dp))
    if (running) V18Button("Stop server", primary = false) { com.blaze.assistant.dashboard.Dashboard.stop() }
    Spacer(Modifier.height(10.dp))
    ToggleRow("Share on Wi-Fi", "Open it from a laptop on the same network (link has a secret token)", lan) {
        lan = !lan; com.blaze.assistant.dashboard.Dashboard.lan = lan; if (running) com.blaze.assistant.dashboard.Dashboard.restart(ctx) }
    ToggleRow("Start with Blaze", "Keep the dashboard running in the background", auto) { auto = !auto; Settings.setBool("dash_auto", auto) }
    var gen by remember { mutableStateOf(0) }
    if (running) {
        V18Section("Links")
        key(gen) { Mono(com.blaze.assistant.dashboard.Dashboard.localUrl() + (if (lan) "\n" + (com.blaze.assistant.dashboard.Dashboard.lanUrl() ?: "(not on Wi-Fi)") else "")) }
        Spacer(Modifier.height(8.dp))
        // v0.45.0: old links (and any copy seen on the network) stop working at once.
        V18Button("Make a new link", primary = false) { com.blaze.assistant.dashboard.Dashboard.rotate(); gen++ }
        V18Note("Wi-Fi links expire when the server stops or sharing is switched off.")
        if (lan) com.blaze.assistant.dashboard.Dashboard.lanUrl()?.let { u ->
            Spacer(Modifier.height(8.dp))
            V18Button("Copy Wi-Fi link", primary = false) {
                ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Blaze dashboard", u)) }
            // v0.33.0 Desktop Bridge: scan with the laptop / tablet camera to open Blaze on the big screen.
            val qr = remember(u) { com.blaze.assistant.dashboard.Bridge.qr(u)?.let { it.asImageBitmap() } }
            if (qr != null) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.Image(qr, "QR code for the Desktop Bridge link", modifier = Modifier.size(200.dp)
                    .background(androidx.compose.ui.graphics.Color.White).padding(8.dp))
                V18Note("Desktop Bridge: on your laptop, open this link (or scan the code). You get your chats, the phone's clipboard both ways, Blaze's files and uploads into Download/Blaze.", top = 6)
            }
        }
    }
}

// ---------------- Proactive ----------------

@Composable
fun ProactivePage() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val P = com.blaze.assistant.proactive.Proactive
    var on by remember { mutableStateOf(P.enabled) }
    var speak by remember { mutableStateOf(P.speak) }
    var every by remember { mutableStateOf(P.everyMin) }
    V18Note("Blaze watches what's new (notifications, calendar, phone health) and speaks up on its own when something matters: an urgent message, a meeting soon, a phone problem. Most checks stay silent. Urgent-looking messages are checked right away.")
    ToggleRow("Speak up on my own", "Heads-ups with a one-tap action", on) { on = !on; P.enabled = on }
    ToggleRow("Say it out loud", "Read heads-ups aloud when the ringer is on (urgent ones always)", speak) { speak = !speak; P.speak = speak }
    Spacer(Modifier.height(12.dp))
    Label("Check every", modifier = Modifier.padding(bottom = 8.dp))
    Row(Modifier.fillMaxWidth()) {
        listOf(15, 30, 45, 90, 180).forEach { m ->
            Box(Modifier.weight(1f).height(40.dp).then(if (every == m) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f)).clickable { every = m; P.everyMin = m },
                contentAlignment = Alignment.Center) { ButtonText(if (m < 60) "${m}m" else "${m / 60}h", color = if (every == m) c.paper else c.ink, size = 13.sp) }
        }
    }
    var qf by remember { mutableStateOf(P.quietFrom) }; var qt by remember { mutableStateOf(P.quietTo) }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label("Quiet hours", modifier = Modifier.weight(1f))
        V18Button("%02d:00".format(qf), primary = false, modifier = Modifier.width(84.dp)) { qf = (qf + 1) % 24; P.quietFrom = qf }
        Label("  to  ", size = 10.sp)
        V18Button("%02d:00".format(qt), primary = false, modifier = Modifier.width(84.dp)) { qt = (qt + 1) % 24; P.quietTo = qt }
    }
    V18Note("Only urgent things get through in quiet hours. Tap a time to change it.", top = 6)
    if (!com.blaze.assistant.notify.NotificationBrain.enabled(ctx)) { Spacer(Modifier.height(6.dp)); NotificationAccessRow() }
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) != true) {
        Spacer(Modifier.height(8.dp))
        V18Note("Samsung and others put apps to sleep. Let Blaze run in the background so it can speak up and keep the wake word alive.")
        V18Button("Allow background running", primary = false) {
            try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }

    V18Section("Jarvis mode")
    val J = com.blaze.assistant.proactive.Jarvis
    var lvl by remember { mutableStateOf(J.level) }
    var glances by remember { mutableStateOf(J.screenGlances) }
    var looks by remember { mutableStateOf(J.cameraLooks) }
    V18Note("Blaze reaches out on its own: a question now and then to get to know you (reply right in the notification), help offered in the moment, and routines it sets up for itself. Jarvis sets them up without asking; the others offer first.")
    Row(Modifier.fillMaxWidth()) {
        com.blaze.assistant.proactive.JarvisLogic.Level.values().forEach { l ->
            Box(Modifier.weight(1f).height(40.dp).then(if (lvl == l) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f)).clickable { lvl = l; J.level = l },
                contentAlignment = Alignment.Center) { ButtonText(l.label, color = if (lvl == l) c.paper else c.ink, size = 13.sp) }
        }
    }
    Spacer(Modifier.height(8.dp))
    ToggleRow("Glance at my screen", "Now and then, as text. Never banks, wallets, passwords or codes. Needs phone control", glances) { glances = !glances; J.screenGlances = glances }
    ToggleRow("Look through the camera", "One front-camera frame while Blaze is open; never saved. Android shows its camera dot", looks) { looks = !looks; J.cameraLooks = looks }
    if (looks && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
        V18Note("Camera looks need the camera permission: open Blaze Camera once and allow it.", top = 4)
    if (!com.blaze.assistant.home.HomeSources.usageAllowed(ctx)) {
        V18Note("Allow Usage access so Blaze can see which apps you use and when, so it can time its help.", top = 6)
        V18Button("Allow usage access", primary = false) {
            try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }

    V18Section("Triggers")
    val triggers by com.blaze.assistant.proactive.Triggers.all.collectAsState()
    V18Note("\u201cWhen X happens, do Y.\u201d Ask Blaze in chat, e.g. \u201cwhen Mary texts, read it to me\u201d or \u201cwhen battery drops below 20, turn on saver\u201d.")
    if (triggers.isNotEmpty()) Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        triggers.forEach { t ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { com.blaze.assistant.proactive.Triggers.toggle(t.id) }) {
                    Text(t.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (t.enabled) c.ink else c.weak))
                    Text("When ${t.event.replace('_', ' ')}${if (t.match.isNotBlank()) " · ${t.match}" else ""} → ${t.prompt}", maxLines = 2,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                }
                ActionText("×", color = c.sub, size = 14.sp, modifier = Modifier.clickable { com.blaze.assistant.proactive.Triggers.remove(t.id) }.padding(8.dp))
            }
        }
    }

    V18Section("Recent heads-ups")
    val recent by P.recent.collectAsState()
    if (recent.isEmpty()) V18Note("Nothing yet.")
    else Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        val f = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
        recent.asReversed().take(10).forEach { h ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(12.dp)) {
                Label(f.format(java.util.Date(h.time)), color = if (h.urgent) c.accent else c.weak, size = 9.sp, modifier = Modifier.width(70.dp))
                Text(h.text, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.ink))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    V18Button("Check now", primary = false) { P.tick(force = true) }
}

// ---------------- Specialists ----------------

@Composable
fun SpecialistsPage() {
    val c = LocalBlaze.current.mono
    var on by remember { mutableStateOf(Settings.bool("specialists_on", true)) }
    var custom by remember { mutableStateOf(com.blaze.assistant.agent.Specialists.custom()) }
    V18Note("Blaze can hand parts of a job to focused specialists, each with its own instructions and only the tools it needs. Several run at the same time, so research + writing + planning finish faster.")
    ToggleRow("Use specialists", "Blaze decides when it's worth delegating", on) { on = !on; Settings.setBool("specialists_on", on) }
    V18Section("Team")
    // v0.26.2: every specialist has a field playbook; ones Blaze made say so, and custom ones can be edited or deleted.
    var editing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        (com.blaze.assistant.agent.Specialists.builtIn + custom).forEach { s ->
            Column(Modifier.fillMaxWidth().hairlineBottom(c.hair).clickable(enabled = s.custom) { editing = if (editing == s.id) null else s.id }.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                        Text(s.prompt, maxLines = 2, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                        val tags = listOfNotNull(
                            if (s.byBlaze) "MADE BY BLAZE" else null,
                            if (!s.custom || s.playbook.isNotBlank()) "PLAYBOOK" else "PLAYBOOK ON FIRST USE",
                            s.groups.joinToString(" · "))
                        Label(tags.joinToString(" · "), color = c.accent, size = 9.sp)
                    }
                    if (s.custom) ActionText("×", color = c.sub, size = 14.sp, modifier = Modifier.clickable {
                        custom = custom.filterNot { it.id == s.id }; com.blaze.assistant.agent.Specialists.saveCustom(custom); if (editing == s.id) editing = null }.padding(8.dp))
                }
                if (s.custom && editing == s.id) {
                    var ePrompt by remember(s.id) { mutableStateOf(s.prompt) }
                    var ePlay by remember(s.id) { mutableStateOf(s.playbook) }
                    var note by remember(s.id) { mutableStateOf("") }
                    Spacer(Modifier.height(8.dp))
                    V18Field("What it does and how", ePrompt) { ePrompt = it }
                    V18Field("Playbook (## Principles of the field, ## Quality bar, ## Best practices, ## Common mistakes to avoid, ## Self-check, ## Output format)", ePlay) { ePlay = it }
                    if (note.isNotBlank()) Text(note, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.accent))
                    V18Button("Save changes", primary = false) {
                        val check = if (ePlay.isBlank()) null else com.blaze.assistant.agent.Playbooks.validate(ePlay, "${s.name} ${s.id} $ePrompt")
                        custom = custom.map { if (it.id == s.id) it.copy(prompt = ePrompt.trim(), playbook = ePlay.trim()) else it }
                        com.blaze.assistant.agent.Specialists.saveCustom(custom)
                        note = when {
                            check == null -> "Saved. Blaze writes a playbook the first time it uses this specialist."
                            check.ok -> "Saved."
                            else -> "Saved, but the playbook needs work: " + check.problems.take(3).joinToString(" ")
                        }
                    }
                }
            }
        }
    }
    V18Section("Add your own")
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf(setOf("web", "memory")) }
    V18Field("Name, e.g. Shopify helper", name) { name = it }
    V18Field("What it does and how", prompt) { prompt = it }
    Label("Tools", modifier = Modifier.padding(vertical = 8.dp))
    com.blaze.assistant.agent.Specialists.groupNames.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { g ->
                val sel = g in groups
                Box(Modifier.weight(1f).height(38.dp).then(if (sel) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f))
                    .clickable { groups = if (sel) groups - g else groups + g }, contentAlignment = Alignment.Center) {
                    ButtonText(g, color = if (sel) c.paper else c.ink, size = 13.sp) }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Spacer(Modifier.height(10.dp))
    V18Button("Add specialist") {
        if (name.isNotBlank() && prompt.isNotBlank()) {
            custom = custom + com.blaze.assistant.agent.Specialists.Spec(name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'), name.trim(), prompt.trim(), groups, true,
                madeBy = com.blaze.assistant.agent.Specialists.USER)
            com.blaze.assistant.agent.Specialists.saveCustom(custom); name = ""; prompt = ""
        }
    }
}

@Composable
internal fun V18Field(hint: String, value: String, onChange: (String) -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp).hairline(c.hair).padding(12.dp)) {
        if (value.isEmpty()) Text(hint, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.weak))
        androidx.compose.foundation.text.BasicTextField(value, onChange, textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun VoiceExtras() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var loop by remember { mutableStateOf(Settings.bool("voice_loop", true)) }
    V18Section("Hands-free")
    var conf by remember { mutableStateOf(Settings.str("wake_conf", "0.88")) }
    Label("\u201cHey Blaze\u201d sensitivity", modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        listOf("0.94" to "Strict", "0.88" to "Normal", "0.78" to "Easy").forEach { (v, l) ->
            Box(Modifier.weight(1f).height(40.dp).background(if (conf == v) LocalBlaze.current.ink else LocalBlaze.current.paper, BlazeShapes.medium).hairline(LocalBlaze.current.hair)
                .clickable { conf = v; Settings.setStr("wake_conf", v) }, contentAlignment = Alignment.Center) {
                Label(l, color = if (conf == v) LocalBlaze.current.paper else LocalBlaze.current.ink, size = 10.sp) }
        }
    }
    V18Note("Strict wakes only on a clear \u201cHey Blaze\u201d. Use Easy if it misses you.")
    var gate by remember { mutableStateOf(Settings.bool("wake_gate", true)) }
    var off by remember { mutableStateOf(Settings.bool("wake_screen_off", false)) }
    var chg by remember { mutableStateOf(Settings.bool("wake_charging", true)) }
    var pk by remember { mutableStateOf(Settings.bool("wake_pocket", true)) }
    V18Section("Battery saver")
    ToggleRow("Sound gate", "Only process audio when someone's speaking. Saves battery in quiet rooms", gate) { gate = !gate; Settings.setBool("wake_gate", gate); com.blaze.assistant.voice.WakeWordService.start(ctx) }
    ToggleRow("Listen with screen off", "Off: the mic switches off when the screen does", off) { off = !off; Settings.setBool("wake_screen_off", off); com.blaze.assistant.voice.WakeWordService.start(ctx) }
    if (!off) ToggleRow("\u2026but listen while charging", "Keep listening with the screen off while plugged in", chg) { chg = !chg; Settings.setBool("wake_charging", chg); com.blaze.assistant.voice.WakeWordService.start(ctx) }
    ToggleRow("Pause in pocket", "Mic off while the phone is face down or in a pocket", pk) { pk = !pk; Settings.setBool("wake_pocket", pk); com.blaze.assistant.voice.WakeWordService.start(ctx) }
    ToggleRow("Conversation mode", "After Blaze answers out loud, it listens again so you can keep talking", loop) { loop = !loop; Settings.setBool("voice_loop", loop) }
}

@Composable
fun CostCap() {
    var cap by remember { mutableStateOf(Settings.str("daily_cap", "")) }
    V18Section("Daily spend cap")
    V18Note("When today's estimated cost passes this (in US dollars), Blaze switches to the free Auto chain until tomorrow. Leave blank for no cap.")
    V18Field("e.g. 0.50", cap) { v -> cap = v.filter { it.isDigit() || it == '.' }; Settings.setStr("daily_cap", cap) }
}
