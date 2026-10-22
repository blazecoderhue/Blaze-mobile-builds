package com.blaze.assistant.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.FastLane
import com.blaze.assistant.agent.FreeRouter
import com.blaze.assistant.agent.Learning
import com.blaze.assistant.agent.Providers
import com.blaze.assistant.agent.ScreenMemory
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.connect.Connectors
import com.blaze.assistant.connect.Mcp
import com.blaze.assistant.connect.McpConfig
import com.blaze.assistant.notify.NotificationBrain
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip

@Composable
private fun Note(text: String, top: Int = 0) {
    val c = LocalBlaze.current.mono
    Text(text, modifier = Modifier.padding(top = top.dp, bottom = 10.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
}

@Composable
private fun Button(text: String, primary: Boolean = true, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.fillMaxWidth().height(44.dp).then(if (primary) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) { ButtonText(text, color = if (primary) c.paper else c.ink, size = 13.sp) }
}

@Composable
private fun Section(title: String) { Spacer(Modifier.height(24.dp)); Label(title, modifier = Modifier.padding(bottom = 8.dp)) }


// ---------------- Fast lane ----------------

@Composable
fun FastLaneSection() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prog by FastLane.progress.collectAsState()
    var on by remember { mutableStateOf(Settings.fastLane) }
    var ready by remember(prog) { mutableStateOf(FastLane.ready(ctx)) }
    var err by remember { mutableStateOf("") }
    Section("Fast lane (on-device)")
    if (!FastLane.supported(ctx)) { Note("This phone's processor isn't supported by the on-device model."); return }
    Note("Needle, a tiny on-phone model, handles direct commands like \u201copen WhatsApp\u201d, \u201cflashlight on\u201d or \u201ctimer 5 minutes\u201d in under a second, even offline. Anything else goes to your AI model.")
    if (!ready) {
        Button(if (prog in 0..99) "Downloading… $prog%" else "Download model (${FastLane.SIZE_MB} MB)") {
            if (prog !in 0..99) scope.launch { err = FastLane.download(ctx).exceptionOrNull()?.message.orEmpty(); ready = FastLane.ready(ctx) }
        }
        if (err.isNotBlank()) Note("Download failed: $err", top = 6)
    } else {
        ToggleRow("Use the fast lane", "Model downloaded · runs on this phone", on) { on = !on; Settings.fastLane = on; if (!on) FastLane.stop() }
        ActionText("Remove model", color = c.accent, size = 10.sp, modifier = Modifier.padding(top = 4.dp).clickable { FastLane.delete(ctx); ready = false })
    }
}

// ---------------- Steps model ----------------

@Composable
fun StepsModelSection() {
    var pid by remember { mutableStateOf(Settings.stepsProvider) }
    var model by remember { mutableStateOf(Settings.stepsModel) }
    val c = LocalBlaze.current.mono
    Section("Model for phone steps")
    Note("Optional: a quicker model for the in-between turns while Blaze taps through an app (e.g. a Flash-Lite model). Leave on \u201cSame as main\u201d to use your main model.")
    val options = listOf("" to "Same as main") + Providers.all.filter { it.id == Providers.AUTO || Settings.keyFor(it.id).isNotBlank() }.map { it.id to it.name }
    Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        options.forEach { (id, name) ->
            Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).background(if (id == pid) PageGlass.fill(c, 0.10f) else androidx.compose.ui.graphics.Color.Transparent).hairlineBottom(c.hair)
                .clickable { pid = id; Settings.stepsProvider = id }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (id == pid) c.accent else c.paper, BlazeShapes.pill)); Spacer(Modifier.width(10.dp))
                Text(name, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink))
            }
        }
    }
    if (pid.isNotBlank() && pid != Providers.AUTO) { Spacer(Modifier.height(10.dp)); Field("Steps model id", model, { model = it; Settings.stepsModel = it }, Settings.modelFor(pid).ifBlank { "model id" }) }
}

// ---------------- Connections ----------------

/**
 * Settings › Connected accounts (v0.19.0 look, Desktop's Accounts view): page header, one raised row per
 * app with a status tag, tap for Connect / Disconnect. [connected0] + [online0] are for screenshots (no network).
 */
@Composable
fun ConnectionsPage(connected0: Set<String>? = null, online0: Boolean? = null, open0: String = "") {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Connectors.state.collectAsState()
    var open by remember { mutableStateOf(open0) }
    var tick by remember { mutableStateOf(0) }
    val acct0 by com.blaze.assistant.connect.BlazeAccount.view.collectAsState()
    val acct = if (online0 != null) acct0.copy(online = online0, connectors = Connectors.all.associate { it.id to online0 }) else acct0
    if (connected0 == null) LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.blaze.assistant.connect.BlazeAccount.refreshProviders(true) } }
    }
    PageHead("Accounts", "Connected accounts", "Let Blaze use your apps: tap Connect, approve in your browser, and you're back. Tokens stay encrypted on this phone; sending or posting always asks you first.")
    if (!acct.online) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).glassInset(c).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(c.sub, BlazeShapes.pill)); Spacer(Modifier.width(10.dp))
        Text("Can't reach Blaze's sign-in service right now. Check your connection.", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.body))
    }
    val states = Connectors.all.map { conn -> conn to (connected0?.contains(conn.id) ?: remember(tick, st) { runCatching { Connectors.connected(conn.id) }.getOrDefault(false) }) }
    GroupHead("Apps \u00b7 %02d".format(states.size), right = { Label("${states.count { it.second }} connected", size = 9.sp, color = c.weak) })
    Rows {
        states.forEachIndexed { i, (conn, on) ->
            val one = acct.connectors[conn.id] == true
            val tag = when { on -> "Connected"; one -> "Connect"; acct.online -> "Soon"; else -> "Offline" }
            KitRow(conn.name, st[conn.id]?.takeIf { it.isNotBlank() } ?: conn.blurb, first = i == 0, pill = tag, pillOn = on,
                onClick = { open = if (open == conn.id) "" else conn.id }, lead = { Box(Modifier.size(7.dp).background(if (on) c.accent else c.hair2, BlazeShapes.pill)) })
            if (open == conn.id) Column(Modifier.fillMaxWidth().glassInset(c).hairlineTop(c.hair).padding(start = 33.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
                when {
                    on -> KitButton("Disconnect", Btn.LINE) { Connectors.disconnect(conn.id); tick++ }
                    one -> {
                        Note("Blaze opens ${conn.name} in your browser. Approve, and you're back here connected.")
                        KitButton("Connect ${conn.name}", Btn.ACCENT) { scope.launch { Connectors.connect(ctx, conn.id); tick++ } }
                    }
                    acct.online -> Note("One-tap Connect for ${conn.name} is coming soon.")
                    else -> Note("Connect needs Blaze's sign-in service, which can't be reached right now.")
                }
            }
        }
    }
    // v0.39.0: MCP servers are a technical tool, shown in Advanced mode only (saved servers keep working).
    if (connected0 == null && com.blaze.assistant.agent.SimpleMode.showMcp(com.blaze.assistant.agent.SimpleMode.simple)) McpSection()
}

@Composable
private fun McpSection() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by Mcp.servers.collectAsState()
    val status by Mcp.status.collectAsState()
    val toolLists by Mcp.toolLists.collectAsState()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var tok by remember { mutableStateOf("") }
    var header by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(McpConfig.AUTO) }
    var open by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    Section("MCP servers")
    Note("Remote MCP servers (Streamable HTTP or SSE). Their tools become Blaze tools named mcp__server__tool. Read-only tools run; others ask first (Action tools pass in Autopilot, destructive ones always ask). Tap a server to set each tool to Ask, Always or Off. The list uses the same mcp.json shape as Blaze CLI and Desktop and syncs with your Blaze account. Local (stdio) servers need a PC.")
    if (servers.isNotEmpty()) Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        servers.forEach { s ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).clickable { open = if (open == s.name) "" else s.name }
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (s.enabled) c.ink else c.weak))
                    Text(status[s.name] ?: if (s.enabled) "Not checked yet" else "Off", maxLines = 1,
                        style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = if (status[s.name]?.startsWith("Error") == true) c.accent else c.sub))
                    Text(s.url.ifBlank { "stdio" } + when (s.transport) { McpConfig.SSE -> " \u00b7 SSE"; McpConfig.HTTP -> " \u00b7 HTTP"; else -> "" },
                        maxLines = 1, style = TextStyle(fontFamily = Type.Mono, fontSize = 10.sp, color = c.weak))
                }
                if (s.remote) Box(Modifier.clip(BlazeShapes.pill).clickable { scope.launch { Mcp.refresh(s) } }.padding(8.dp)) { ActionText("Test", size = 10.sp) }
                Box(Modifier.clip(BlazeShapes.pill).clickable { Mcp.toggle(s.name) }.padding(8.dp)) { ActionText(if (s.enabled) "On" else "Off", color = if (s.enabled) c.accent else c.weak, size = 10.sp) }
                Box(Modifier.clip(BlazeShapes.pill).clickable { Mcp.remove(s.name) }.padding(10.dp)) { ActionText("\u00d7", size = 13.sp) }
            }
            if (open == s.name) {
                val tools = toolLists[s.name]
                if (tools == null) Text(if (s.remote) "Tap Test to list its tools." else "Runs on Blaze Desktop or CLI only.", modifier = Modifier.padding(12.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                else tools.forEach { t ->
                    val mode = McpConfig.mode(s.toolModes[t.name], t.risk)
                    Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(start = 24.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, color = if (mode == McpConfig.OFF) c.weak else c.ink))
                            Text(when (t.risk) { McpConfig.Risk.READ -> "reads only"; McpConfig.Risk.WRITE -> "can delete or change data"; else -> "acts" }, style = TextStyle(fontFamily = Type.Sans, fontSize = 10.sp, color = c.weak))
                        }
                        listOf(McpConfig.ASK to "Ask", McpConfig.ALWAYS to "Always", McpConfig.OFF to "Off").forEach { (m, label) ->
                            Box(Modifier.clip(BlazeShapes.pill).clickable { Mcp.setToolMode(s.name, t.name, if (m == McpConfig.defaultMode(t.risk)) "" else m) }.padding(6.dp)) {
                                Label(label, size = 10.sp, color = if (mode == m) c.accent else c.weak)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    Field("Name", name, { name = it }, "e.g. github")
    Field("Server URL", url, { url = it }, "https://\u2026/mcp")
    Field("Bearer token or OAuth access token (optional)", tok, { tok = it }, "Optional", secret = true)
    Field("Extra header (optional)", header, { header = it }, "X-Api-Key: \u2026")
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("Transport", modifier = Modifier.weight(1f))
        listOf(McpConfig.AUTO to "Auto", McpConfig.HTTP to "HTTP", McpConfig.SSE to "SSE").forEach { (t, label) ->
            Box(Modifier.clip(BlazeShapes.pill).clickable { transport = t }.padding(8.dp)) { ActionText(label, size = 11.sp, color = if (transport == t) c.accent else c.weak) }
        }
    }
    Button("Add server") {
        val err = Mcp.add(name, url, tok, header, transport)
        if (err.isNotEmpty()) msg = err else {
            val s = Mcp.servers.value.firstOrNull { it.name == name.trim() }
            name = ""; url = ""; tok = ""; header = ""; transport = McpConfig.AUTO; msg = ""
            if (s != null) scope.launch { Mcp.refresh(s) }
        }
    }
    Spacer(Modifier.height(16.dp))
    Field("Paste an mcp.json", paste, { paste = it }, "{\"mcpServers\": {\u2026}}")
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) { Button("Import") { if (paste.isNotBlank()) { msg = Mcp.importJson(paste); paste = "" ; scope.launch { Mcp.servers.value.filter { it.enabled && it.remote }.forEach { Mcp.refresh(it) } } } } }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { Button("Copy mcp.json", primary = false) {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("mcp.json", Mcp.exportJson()))
            msg = "Copied. It includes tokens: paste it only somewhere private."
        } }
    }
    if (msg.isNotEmpty()) Note(msg, top = 8)
}

// ---------------- Learning ----------------

@Composable
fun LearningSection() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val skills by Learning.skills.collectAsState()
    val pending by Learning.pending.collectAsState()
    var auto by remember { mutableStateOf(Settings.autoLearn) }
    var ask by remember { mutableStateOf(Settings.learnApproval) }
    var screen by remember { mutableStateOf(Settings.screenMemory) }
    var shown by remember { mutableStateOf("") }
    Section("Learning")
    var titles by remember { mutableStateOf(Settings.autoTitles) }
    ToggleRow("Learn from chats", "Blaze saves lasting facts and preferences as you go, replaces ones that changed, and saves tasks you teach it or repeat as skills. Small talk costs nothing: it only checks when something worth keeping was said", auto) { auto = !auto; Settings.autoLearn = auto }
    var long by remember { mutableStateOf(Settings.longChat) }
    ToggleRow("Long chats", "Older turns fold into a running summary, Blaze searches the chat instead of re-reading it, long tool output is saved aside, and the work card shows tokens in and out", long) { long = !long; Settings.longChat = long }
    var review by remember { mutableStateOf(com.blaze.assistant.agent.MindReview.enabled) }
    ToggleRow("Review past conversations when idle", "When the phone is idle and charging, or idle on Wi-Fi, Blaze reads past turns (old chats too) a little at a time with the cheapest free model and saves what's worth remembering", review) {
        review = !review; com.blaze.assistant.agent.MindReview.enabled = review
        try { com.blaze.assistant.agent.MindReview.schedule(ctx) } catch (_: Throwable) { }
    }
    val waiting = remember { try { com.blaze.assistant.agent.Mind.db()?.unreviewedCount() ?: 0 } catch (_: Exception) { 0 } }
    if (review && waiting > 0) Label("$waiting turns waiting for review", size = 9.sp, modifier = Modifier.padding(bottom = 6.dp))
    ToggleRow("Name chats by topic", "Chats are titled by what most of the conversation is about, not the first message. Chats you rename stay as you named them", titles) { titles = !titles; Settings.autoTitles = titles }
    ToggleRow("Ask before saving", "Changes wait here for your OK instead of saving straight away", ask) { ask = !ask; Settings.learnApproval = ask }
    if (pending.isNotEmpty()) {
        Section("Waiting for your OK")
        Column(Modifier.fillMaxWidth().hairline(c.accent)) {
            pending.forEach { p ->
                Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body))
                    Box(Modifier.clip(BlazeShapes.pill).clickable { Learning.approve(p.id) }.padding(10.dp)) { ActionText("Keep", color = c.accent, size = 10.sp) }
                    Box(Modifier.clip(BlazeShapes.pill).clickable { Learning.reject(p.id) }.padding(10.dp)) { ActionText("×", size = 13.sp) }
                }
            }
        }
    }
    Section("Skills Blaze learned")
    if (skills.isEmpty()) Note("None yet. When Blaze works out a multi-step task (like ordering your usual ride), it saves the steps so next time is faster. Recorded phone steps can be replayed without the AI.")
    else Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        skills.sortedByDescending { it.uses }.forEach { s ->
            Column(Modifier.fillMaxWidth().hairlineBottom(c.hair).clickable { shown = if (shown == s.name) "" else s.name }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name + if (s.replay != null) "  ▶" else "", style = TextStyle(fontFamily = Type.Mono, fontSize = 13.sp, color = c.ink))
                        Text(s.description + " · used ${s.uses}×", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                    }
                    Box(Modifier.clip(BlazeShapes.pill).clickable { com.blaze.assistant.agent.Agent.send("Run my skill \"${s.name}\"") }.padding(10.dp)) { ActionText("Run", color = c.accent, size = 10.sp) }
                    Box(Modifier.clip(BlazeShapes.pill).clickable { Learning.deleteSkill(s.name) }.padding(10.dp)) { ActionText("×", size = 13.sp) }
                }
                if (shown == s.name) Text(s.steps, modifier = Modifier.padding(top = 6.dp, end = 12.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.body))
            }
        }
    }
    Section("Screen memory")
    ToggleRow("Remember what I see", "Keeps short text snapshots of screens when you switch apps, only on this phone (needs phone control). Password screens are skipped.", screen) { screen = !screen; Settings.screenMemory = screen }
    var count by remember(screen) { mutableStateOf(ScreenMemory.count(ctx)) }
    if (count > 0) ActionText("Clear $count snapshots", color = c.accent, size = 10.sp, modifier = Modifier.padding(top = 4.dp).clickable { ScreenMemory.clear(ctx); count = 0 })
}

// ---------------- Notification access ----------------

@Composable
fun NotificationAccessRow() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(NotificationBrain.enabled(ctx)) }
    LaunchedEffect(Unit) { on = NotificationBrain.enabled(ctx) }
    Section("Notification brain")
    Row(Modifier.fillMaxWidth().hairline(if (on) c.hair else c.accent).clickable { ctx.startActivity(NotificationBrain.settingsIntent()) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(if (on) c.accent else c.weak, BlazeShapes.pill)); Spacer(Modifier.width(10.dp))
        Text(if (on) "Notification access is on" else "Let Blaze read notifications", modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
        Label("Open →", color = c.accent)
    }
    Note("Ask \u201cwhat did I miss?\u201d or \u201creply to Mary on WhatsApp\u201d. Kept in memory only, never saved; codes are hidden.", top = 8)
}
