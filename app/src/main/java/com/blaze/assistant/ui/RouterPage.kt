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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.BlazeRouter
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

/** Settings › BlazeRouter: run the router (Alpine, Termux or directly), use it as the brain, open its dashboard. */
@Composable
internal fun RouterPage(onClassic: () -> Unit) {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by BlazeRouter.state.collectAsState()
    var up by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var runner by remember { mutableStateOf(BlazeRouter.runner) }
    var auto by remember { mutableStateOf(BlazeRouter.autostart) }
    var using by remember { mutableStateOf(Settings.providerId == BlazeRouter.ID) }
    LaunchedEffect(st, busy) { up = BlazeRouter.healthy() }

    fun act(f: suspend () -> String) { busy = true; scope.launch { msg = f(); busy = false; up = BlazeRouter.healthy(); using = Settings.providerId == BlazeRouter.ID } }

    Spacer(Modifier.height(22.dp))
    Text("Blaze Desktop's model router, running on this phone. It ranks free models by task and tier, fans out to several providers when one is slow, and remembers the last model that worked. Every OmniRoute auto/* preset is here, including auto/best-free.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 21.sp, color = c.body))
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth().hairline(c.hair).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (up) c.accent else c.weak, BlazeShapes.pill))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (up) "Running" + (if (st.where.isNotBlank()) " in " + BlazeRouter.label(st.where) else "") else "Stopped",
                style = TextStyle(fontFamily = Type.Display, fontSize = 17.sp, color = c.ink))
            Text(if (up) BlazeRouter.BASE + "/v1" else st.note.ifBlank { "Start it to use the presets and the dashboard." }, maxLines = 3,
                style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.sub))
        }
    }
    Spacer(Modifier.height(16.dp))
    Label("Run it in", modifier = Modifier.padding(bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BlazeRouter.RUNNERS.forEach { r ->
            val on = runner == r
            Box(Modifier.then(if (on) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f)).clickable { runner = r; BlazeRouter.runner = r }
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
                ButtonText(when (r) { "auto" -> "Auto"; "alpine" -> "Alpine"; "termux" -> "Termux"; else -> "Direct" }, color = if (on) c.paper else c.ink, size = 13.sp)
            }
        }
    }
    Text("Auto tries Blaze's Alpine Linux first, then Termux, then runs it straight from Blaze.", modifier = Modifier.padding(top = 6.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(46.dp).clip(BlazeShapes.pill).glassButton(c).clickable(enabled = !busy) { act { if (up) BlazeRouter.stop(ctx) else BlazeRouter.start(ctx) } },
            contentAlignment = Alignment.Center) { ButtonText(if (busy) "…" else if (up) "Stop" else "Start", color = Color.White) }
        Box(Modifier.weight(1f).height(46.dp).hairline(c.hair).clickable(enabled = !busy) { act { BlazeRouter.openDashboard(ctx); "Dashboard opened." } },
            contentAlignment = Alignment.Center) { Label("Open dashboard", color = c.ink, size = 11.sp) }
    }
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().height(46.dp).then(if (using) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f))
        .clickable(enabled = !busy) { act { if (using) { Settings.providerId = com.blaze.assistant.agent.Providers.AUTO; "Back to Auto." } else BlazeRouter.run(ctx, org.json.JSONObject().put("action", "use")) } },
        contentAlignment = Alignment.Center) {
        ButtonText(if (using) "Blaze answers through BlazeRouter · ${Settings.model}" else "Use as Blaze's brain (${BlazeRouter.DEFAULT_MODEL})", color = if (using) c.paper else c.ink, size = 13.sp)
    }
    if (msg.isNotBlank()) Text(msg, modifier = Modifier.padding(top = 10.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
    Spacer(Modifier.height(8.dp))
    ToggleRow("Start with Blaze", "Run the router whenever Blaze opens", auto) { auto = !auto; BlazeRouter.autostart = auto }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClassic).padding(vertical = 16.dp)) {
        Label("Classic editor: keys and models  →", color = c.accent, size = 10.sp)
    }
    Text("BlazeRouter is a Go port of OmniRoute (MIT). Your keys stay on this phone in ~/.blaze.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.weak))
}
