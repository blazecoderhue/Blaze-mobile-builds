package com.blaze.assistant.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blaze.assistant.desktop.DesktopDistro
import com.blaze.assistant.desktop.DesktopFlow
import com.blaze.assistant.desktop.DesktopPlan
import com.blaze.assistant.desktop.DesktopPlan.Phase
import com.blaze.assistant.desktop.LinuxDesktop
import com.blaze.assistant.desktop.Rfb
import com.blaze.assistant.desktop.DesktopHealth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v0.25.1 Desktop view: one Start button for a real Linux desktop inside Blaze's Alpine, progress in plain words,
 * a Resume card if Android closed it, and a full-screen viewer (touch as mouse, keyboard, landscape, Stop).
 * [preview] draws a fixed state for screenshots.
 */
@Composable
fun LinuxDesktopPage(preview: LinuxDesktop.State? = null) {
    val ctx = LocalContext.current
    val c = LocalBlaze.current.mono
    val scope = rememberCoroutineScope()
    val live by LinuxDesktop.state.collectAsState()
    val st = preview ?: live
    var viewing by remember { mutableStateOf(false) }
    val supported = preview != null || remember { LinuxDesktop.cpuOk() }
    val killed = preview == null && remember(st.phase) { LinuxDesktop.wasKilled() } || st.phase == Phase.KILLED
    // v0.25.5: the live desktop card asked for the full-screen view.
    val wantViewer by LinuxDesktop.viewerRequest.collectAsState()
    LaunchedEffect(wantViewer, st.phase) {
        if (preview == null && wantViewer) { if (st.phase == Phase.RUNNING) viewing = true; LinuxDesktop.viewerRequest.value = false }
    }

    // v0.33.5: the chosen Linux (Alpine unless picked), and a paused download shown as paused after Blaze restarts.
    LaunchedEffect(Unit) { if (preview == null) LinuxDesktop.refresh() }
    val chosenFlow by LinuxDesktop.distroId.collectAsState()
    val initialDistro = remember { if (preview != null) DesktopDistro.DEFAULT else LinuxDesktop.distro().id }
    val distro = DesktopDistro.of(if (preview != null) DesktopDistro.DEFAULT else chosenFlow.ifBlank { initialDistro })
    var kept by remember { mutableStateOf(0L) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(st.phase, distro.id) {
        if (preview == null) kept = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LinuxDesktop.keptBytes() }
    }
    val paused = st.phase == Phase.PAUSED

    PageHead("Desktop", "Linux desktop", "A real Linux desktop (XFCE) running inside Blaze's own Linux on this phone. Nothing leaves the phone; Blaze can work in it too, always after asking.")
    Spacer(Modifier.height(14.dp))
    if (!supported || st.phase == Phase.UNSUPPORTED) {
        BlazeCard { Text(DesktopPlan.UNSUPPORTED, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 20.sp, color = c.body)) }
        return
    }
    BlazeCard(accent = st.phase == Phase.RUNNING) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(BlazeShapes.pill).background(when (st.phase) { Phase.RUNNING -> c.accent; Phase.PAUSED -> c.sub; Phase.ERROR, Phase.KILLED -> PageGlass.error; else -> c.weak }))
            Spacer(Modifier.width(10.dp))
            Text(when {
                killed -> "Closed by Android"
                paused -> st.line.ifBlank { DesktopFlow.PAUSED_LINE }
                st.phase == Phase.IDLE || st.phase == Phase.STOPPED -> if (preview == null && !LinuxDesktop.installed()) "Not installed yet" else "Ready"
                else -> st.line.ifBlank { st.phase.name.lowercase().sentence() }
            }, style = TextStyle(fontFamily = Type.Display, fontSize = 16.sp, color = c.ink))
        }
        if (st.phase == Phase.INSTALLING || st.phase == Phase.STARTING || (paused && st.pct != null)) {
            Spacer(Modifier.height(10.dp))
            BrandProgress(st.pct ?: 50)
        }
        Spacer(Modifier.height(8.dp))
        Text(when {
            killed -> com.blaze.assistant.desktop.KeepAlive.killedNote(com.blaze.assistant.desktop.KeepAlive.on())
            paused -> DesktopFlow.pausedLine(kept, distro.name)
            st.phase == Phase.ERROR -> st.error
            st.phase == Phase.RUNNING -> "${distro.name} \u00b7 ${LinuxDesktop.running.label}, only reachable from this phone."
            st.phase == Phase.INSTALLING -> "${distro.name} \u00b7 You can leave this page or turn the screen off. Stop download keeps what's downloaded."
            preview == null && LinuxDesktop.installed() -> "Light defaults for phones with 3\u20134 GB of memory. Stop it when you're done to free memory."
            else -> "First start installs ${distro.name}: " + distro.sizeLine()
        }, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    }
    Spacer(Modifier.height(14.dp))
    val busy = st.phase == Phase.INSTALLING || st.phase == Phase.STARTING || st.phase == Phase.STOPPING
    val stopping = paused && st.line != DesktopFlow.PAUSED_LINE   // "Stopping the download…" until the install has really let go
    val start = {
        // v0.25.5: runs in Blaze's own scope, so leaving this page doesn't cancel a download.
        note = ""
        val job = LinuxDesktop.startAsync(ctx)
        scope.launch { if (job.await().startsWith("The desktop is running")) viewing = true }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            st.phase == Phase.RUNNING -> {
                BlazeButton("Open full screen", Modifier.weight(1f)) { viewing = true }
                BlazeButton("Stop", kind = BtnKind.SECONDARY) { scope.launch { LinuxDesktop.stop() } }
            }
            // v0.33.5: the old greyed-out "Working…" is now a real Stop: it pauses the download and keeps what's downloaded.
            st.phase == Phase.INSTALLING -> BlazeButton(DesktopFlow.mainLabel(st.phase, false), Modifier.weight(1f), kind = BtnKind.SECONDARY) {
                LinuxDesktop.stopInstall(ctx)
            }
            st.phase == Phase.STARTING || st.phase == Phase.STOPPING -> BlazeButton("Working\u2026", Modifier.weight(1f), enabled = false) { }
            else -> BlazeButton(if (killed && com.blaze.assistant.desktop.KeepAlive.on()) "Restart" else DesktopFlow.mainLabel(st.phase, killed), Modifier.weight(1f), enabled = !stopping) { start() }
        }
        // v0.43.0: a start that failed (or a desktop Android closed) gets Repair: clears stale locks, re-links, starts again.
        if ((st.phase == Phase.ERROR || killed) && preview == null && LinuxDesktop.installed()) {
            BlazeButton("Repair", kind = BtnKind.SECONDARY) {
                note = ""
                val job = LinuxDesktop.repairAsync(ctx)
                scope.launch { if (DesktopHealth.saysRunning(job.await())) viewing = true }
            }
        }
    }
    // v0.45.2: Keep desktop running (foreground service + wake lock while it runs; restarts it if Android kills it).
    if (preview == null) { Spacer(Modifier.height(14.dp)); KeepDesktopRow() }
    // Advanced mode: the X server's last lines after a failed start.
    val simpleUi by com.blaze.assistant.agent.SimpleMode.simpleFlow.collectAsState()
    if (st.phase == Phase.ERROR && !simpleUi && LinuxDesktop.lastLog.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(LinuxDesktop.lastLog.joinToString("\n"), style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, lineHeight = 15.sp, color = c.sub))
    }
    val desktopInstalled = remember(st.phase, distro.id) { preview == null && LinuxDesktop.installed() }
    if (preview == null && DesktopFlow.canRemove(st.phase, kept, installed = desktopInstalled) && !stopping) {
        Spacer(Modifier.height(10.dp))
        BlazeButton("Remove downloaded files", Modifier.fillMaxWidth(), kind = BtnKind.GHOST) {
            scope.launch { note = LinuxDesktop.removeDownloads(); kept = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LinuxDesktop.keptBytes() } }
        }
    }
    if (note.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(note, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    }
    Spacer(Modifier.height(18.dp))
    // v0.33.5: which Linux the desktop runs in. Alpine is the default and the light choice for a low-end phone.
    val canSwitch = preview == null && DesktopFlow.canSwitch(st.phase)
    GroupHead("Linux", if (canSwitch) "The desktop, the Terminal and Linux apps use this one." else "Stop the desktop to switch.")
    val options = remember(preview == null) {
        if (preview != null) DesktopDistro.ALL
        else DesktopDistro.available(try { com.blaze.assistant.terminal.TermLogic.cpu(android.os.Build.SUPPORTED_ABIS?.firstOrNull()) } catch (_: Throwable) { null })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { d -> DistroCard(d, picked = d.id == distro.id, enabled = canSwitch) { if (LinuxDesktop.setDistro(d.id)) note = "" } }
    }
    // v0.34.0: apps, store, display, snapshots, auto-restart.
    if (preview == null && desktopInstalled) { Spacer(Modifier.height(18.dp)); DesktopExtrasSection(st.phase) }
    Spacer(Modifier.height(18.dp))
    GroupHead("How it works", "")
    Text("Tap to click, two fingers to right-click or scroll, pinch to zoom and drag to pan when zoomed. The keyboard button types; the arrow turns it sideways.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    if (viewing && st.phase == Phase.RUNNING && preview == null) DesktopViewer(onClose = { viewing = false })
}

/** v0.33.5: one Linux choice: name, package manager, size, and "Recommended for most phones" on Alpine. */
@Composable
private fun DistroCard(d: DesktopDistro.Info, picked: Boolean, enabled: Boolean, onPick: () -> Unit) {
    val c = LocalBlaze.current.mono
    BlazeCard(accent = picked, onClick = if (enabled && !picked) onPick else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(CircleShape).border(1.5.dp, if (picked) c.accent else c.weak, CircleShape), contentAlignment = Alignment.Center) {
                if (picked) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
            }
            Spacer(Modifier.width(10.dp))
            Text(d.name, style = TextStyle(fontFamily = Type.Display, fontSize = 16.sp, color = if (enabled || picked) c.ink else c.sub))
            Spacer(Modifier.width(8.dp))
            if (d.recommended) Tag("Recommended for most phones", on = true)
            Spacer(Modifier.weight(1f))
            Text(d.pm.tool, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        }
        Spacer(Modifier.height(6.dp))
        Text(d.blurb + " About ${d.downloadMb} MB to download.", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    }
}

/** Full-screen viewer. Closing it leaves the desktop running; Stop ends it. */
@Composable
fun DesktopViewer(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var frame by remember { mutableIntStateOf(0) }
    var clipNote by remember { mutableStateOf("") }
    val clipboard = remember { ctx.getSystemService(android.content.ClipboardManager::class.java) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var drawn by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    // v0.43.0: Retry makes a new connection (and Repair restarts the desktop first); while starting it retries by itself.
    var attempt by remember { mutableIntStateOf(0) }
    var retrying by remember { mutableIntStateOf(0) }
    var repairing by remember { mutableStateOf(false) }
    val client = remember(attempt) {
        Rfb.Client(LinuxDesktop.password, onFrame = { frame++ }, onClosed = { r -> error = r; if (r.isNotBlank()) LinuxDesktop.lost() },
            // v0.34.0 two-way clipboard: what's copied in the desktop lands on the phone's clipboard.
            onCutText = { t -> if (t.isNotEmpty()) main.post {
                try { clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Linux desktop", t)); clipNote = "Copied from the desktop" } catch (_: Exception) { }
            } })
    }
    val bmp = remember { mutableStateOf<Bitmap?>(null) }
    var landscape by remember { mutableStateOf(false) }
    var keyboard by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize(1, 1)) }
    DisposableEffect(attempt) {
        error = ""; retrying = 0
        client.connect(retryWindowMs = 30_000, onRetry = { n -> main.post { retrying = n } })
        onDispose { client.close() }
    }
    DisposableEffect(Unit) {
        val act = ctx as? Activity
        val before = act?.requestedOrientation
        onDispose {
            bmp.value?.recycle(); bmp.value = null
            if (act != null && before != null) act.requestedOrientation = before
        }
    }
    LaunchedEffect(landscape) {
        (ctx as? Activity)?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    // Copy the framebuffer into the bitmap after each update.
    LaunchedEffect(frame) {
        val w = client.width; val h = client.height
        if (w <= 0 || h <= 0) return@LaunchedEffect
        val b = bmp.value?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also { bmp.value?.recycle(); bmp.value = it }
        try { b.copyPixelsFromBuffer(java.nio.ShortBuffer.wrap(client.fb)) } catch (_: Exception) { }
        bmp.value = b
        drawn++
    }
    fun fit(): Float = if (client.width <= 0) 1f else minOf(box.width.toFloat() / client.width, box.height.toFloat() / client.height)
    fun toDesk(p: Offset): Pair<Int, Int> {
        val s = fit() * zoom
        val ox = (box.width - client.width * s) / 2f + pan.x; val oy = (box.height - client.height * s) / 2f + pan.y
        return ((p.x - ox) / s).toInt().coerceIn(0, (client.width - 1).coerceAtLeast(0)) to ((p.y - oy) / s).toInt().coerceIn(0, (client.height - 1).coerceAtLeast(0))
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Linux desktop" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val t0 = down.uptimeMillis
                        var maxPointers = 1; var moved = false; var dragging = false
                        var prevDist = 0f; var prevCentroid = down.position; var scrollAcc = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pts = ev.changes.filter { it.pressed }
                            if (pts.isEmpty()) {
                                val up = ev.changes.firstOrNull()
                                val quick = (up?.uptimeMillis ?: t0) - t0 < 350
                                if (dragging) { val (x, y) = toDesk(up?.position ?: down.position); client.pointer(0, x, y) }
                                else if (!moved && quick) {
                                    val (x, y) = toDesk(down.position)
                                    client.click(x, y, if (maxPointers >= 2) 3 else 1)
                                }
                                break
                            }
                            maxPointers = maxOf(maxPointers, pts.size)
                            if (pts.size >= 2) {
                                val a = pts[0].position; val b = pts[1].position
                                val dist = hypot(a.x - b.x, a.y - b.y); val cen = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                                if (prevDist > 0f) {
                                    val z = dist / prevDist
                                    if (abs(z - 1f) > 0.01f) { zoom = (zoom * z).coerceIn(1f, 4f); moved = true; if (zoom == 1f) pan = Offset.Zero }
                                    else {
                                        scrollAcc += cen.y - prevCentroid.y
                                        if (abs(scrollAcc) > 40f) { val (x, y) = toDesk(cen); client.scroll(x, y, up = scrollAcc > 0); scrollAcc = 0f; moved = true }
                                    }
                                }
                                prevDist = dist; prevCentroid = cen
                            } else if (maxPointers == 1) {
                                val ch = pts[0]
                                val d = ch.position - down.position
                                if (!moved && hypot(d.x, d.y) > viewConfiguration.touchSlop) moved = true
                                if (moved && ch.positionChanged()) {
                                    if (zoom > 1f) pan += ch.position - ch.previousPosition
                                    else { val (x, y) = toDesk(ch.position); client.pointer(1, x, y); dragging = true }
                                }
                            }
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }) {
                box = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                if (drawn < 0) return@Canvas   // reading it redraws after every copy
                val b = bmp.value ?: return@Canvas
                val s = fit() * zoom
                val ox = (size.width - b.width * s) / 2f + pan.x; val oy = (size.height - b.height * s) / 2f + pan.y
                drawContext.canvas.save()
                drawContext.canvas.translate(ox, oy); drawContext.canvas.scale(s, s)
                drawImage(b.asImageBitmap())
                drawContext.canvas.restore()
            }
            if (error.isNotBlank() || repairing) {
                // v0.43.0: plain words and a way forward instead of "failed to connect to /127.0.0.1 (port 5901) … ECONNREFUSED".
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (repairing) "Repairing the desktop\u2026 this can take a minute." else DesktopHealth.plainError(error), style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, color = Color.White))
                    if (!repairing) {
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BlazeButton("Retry") { attempt++ }
                            BlazeButton("Repair", kind = BtnKind.SECONDARY) {
                                repairing = true
                                scope.launch {
                                    val r = LinuxDesktop.repairAsync(ctx).await()
                                    repairing = false
                                    if (DesktopHealth.saysRunning(r)) attempt++ else onClose()
                                }
                            }
                        }
                    }
                }
            } else if (client.width == 0) Text(if (retrying > 0) "Waiting for the desktop to answer\u2026" else "Connecting\u2026",
                Modifier.align(Alignment.Center).padding(24.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = Color.White))
            // Controls: keyboard, rotate, close, stop.
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ViewerChip("\u2328", "Keyboard") { keyboard = !keyboard }
                // v0.34.0: the phone's clipboard into the desktop (then paste there with Ctrl+V, or Ctrl+Shift+V in the terminal).
                ViewerChip("\u2398", "Send the phone's clipboard to the desktop") {
                    val t = try { clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() } catch (_: Exception) { null }
                    if (t.isNullOrEmpty()) clipNote = "The phone's clipboard is empty"
                    else { client.cutText(t); clipNote = "Sent to the desktop's clipboard \u00b7 paste with Ctrl+V" }
                }
                ViewerChip("\u21bb", "Turn sideways") { landscape = !landscape }
                ViewerChip("\u2715", "Close the viewer (keeps the desktop running)") { onClose() }
                ViewerChip("\u25a0", "Stop the desktop and free memory") { scope.launch { LinuxDesktop.stop() }; onClose() }
            }
            if (keyboard) KeyInput(client, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            if (clipNote.isNotBlank()) {
                LaunchedEffect(clipNote) { delay(2500); clipNote = "" }
                Text(clipNote, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (keyboard) 96.dp else 24.dp)
                    .clip(BlazeShapes.pill).background(Color(0xCC141416)).padding(horizontal = 14.dp, vertical = 8.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Color.White))
            }
            // v0.25.5: what Blaze is doing, when it's working in the desktop you're watching.
            val live by com.blaze.assistant.desktop.DesktopLive.state.collectAsState()
            var tick by remember { mutableIntStateOf(0) }
            LaunchedEffect(live.active) { while (live.active) { delay(400); tick++ } }
            val t = remember(tick) { System.currentTimeMillis() }
            if (live.show(t)) StatusPill(live.pill(t), working = live.acting, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(14.dp))
        }
    }
}

@Composable
private fun ViewerChip(mark: String, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xCC141416)).border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape).clickable(onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) {
        Text(mark, style = TextStyle(fontFamily = Type.Sans, fontSize = 17.sp, color = Color.White))
    }
}

/** A hidden text field that turns typing into key presses (Backspace when text shrinks, Enter on the action key). */
@Composable
private fun KeyInput(client: Rfb.Client, modifier: Modifier) {
    var text by remember { mutableStateOf(" ") }
    val fr = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { fr.requestFocus(); kb?.show() }
    Column(modifier.fillMaxWidth().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Esc" to Rfb.XK_ESCAPE, "Tab" to Rfb.XK_TAB, "\u2190" to Rfb.XK_LEFT, "\u2191" to Rfb.XK_UP, "\u2193" to Rfb.XK_DOWN, "\u2192" to Rfb.XK_RIGHT, "Del" to Rfb.XK_DELETE).forEach { (l, k) ->
                Box(Modifier.clip(BlazeShapes.pill).background(Color(0xCC141416)).border(1.dp, Color.White.copy(alpha = 0.22f), BlazeShapes.pill).clickable { client.key(k) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(l, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Color.White))
                }
            }
        }
        BasicTextField(text, { n ->
            when {
                n.length < text.length -> repeat(text.length - n.length) { client.key(Rfb.XK_BACKSPACE) }
                n.length > text.length -> client.type(n.substring(text.length))
            }
            text = if (n.isEmpty() || n.length > 200) " " else n
        }, Modifier.size(1.dp).focusRequester(fr),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrect = false),
            keyboardActions = KeyboardActions(onSend = { client.key(Rfb.XK_RETURN) }))
    }
}
