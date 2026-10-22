package com.blaze.assistant.blazeos

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/*
 * v0.36.0 BlazeOS native apps on screen. [OsWasm] keeps one [OsWasmTask] per open native app (the task list decides
 * which are open; pause / resume / close follow BlazeOS like web apps do). Each task runs its app on its own thread
 * at up to 60 frames a second; the screen draws the last finished frame on a Compose Canvas and feeds touches,
 * swipes, keys and Back back to the app.
 */

/** The Android side of [OsWasmHost]: the app's store on the BlazeOS disk, BlazeOS banners and prompts, the theme. */
class AndroidWasmHost(private val ctx: Context, private val inst: OsInstalled) : OsWasmHost {
    override val appId: String get() = inst.appId
    override val label: String get() = inst.manifest.name
    override val declared: List<String> get() = inst.manifest.permissions
    private val store get() = File(OsDisk.dataDir(ctx, appId), "storage.json")
    private val dir get() = OsDisk.appDir(ctx, inst.manifest.id)

    override fun decide(perm: String) = BlazeOs.grants.value.decide(appId, perm)
    override fun readStore(): String? = OsDisk.readText(store)
    override fun writeStore(json: String) = OsDisk.writeText(store, json)
    override fun notify(title: String, body: String) { MainScope().launch { BlazeOs.notify(label, title, body) } }
    override fun ask(perm: String, then: (Boolean) -> Unit) { MainScope().launch { then(BlazeOs.request(appId, listOf(perm))) } }
    override fun dark() = BlazeOs.prefs.value.dark
    override fun accent(): Int = (OsIcons.parseColor(BlazeOs.prefs.value.accent.ifEmpty { "#FF9A3C" }) ?: 0xFFFF9A3CL).toInt() or 0xFF000000.toInt()
    override fun fontScale() = BlazeOs.prefs.value.fontScale
    override fun hasImage(path: String): Boolean {
        if (!OsManifest.safePath(path) || path.substringAfterLast('.').lowercase() !in setOf("png", "jpg", "jpeg", "webp")) return false
        val f = File(dir, path)
        return f.isFile && f.canonicalPath.startsWith(dir.canonicalPath + File.separator)
    }
    override fun log(msg: String) { android.util.Log.d("BlazeOS/${inst.manifest.id}", msg.take(500)) }
    fun moduleFile(): File = File(dir, inst.manifest.entry.path)
    fun imageFile(path: String): File = File(dir, path)
}

/** One running native app: its runtime and its thread. */
class OsWasmTask(val appId: String, private val host: AndroidWasmHost, maxPages: Int) {
    val rt = OsWasmRuntime(host, maxPages)
    /** Bumped when there's a new frame or a new state (the screen recomposes on it). */
    val tick = MutableStateFlow(0L)
    @Volatile var paused = false; private set
    @Volatile private var thread: Thread? = null
    @Volatile var loading = true; private set
    private val images = ConcurrentHashMap<String, android.graphics.Bitmap?>()

    fun start(w: Float, h: Float) {
        if (thread != null) { rt.input.add(OsInput.Resize(w, h)); return }
        thread = Thread({ loop(w, h) }, "blazeos-app-$appId").apply { isDaemon = true; priority = Thread.NORM_PRIORITY; start() }
    }

    private fun loop(w: Float, h: Float) {
        val bytes = try { host.moduleFile().readBytes() } catch (_: Exception) { null }
        val ok = bytes != null && rt.load(bytes, w, h)
        loading = false
        tick.value++
        if (!ok) return
        val frameNs = 16_666_667L
        try {
            while (!Thread.currentThread().isInterrupted && rt.running) {
                val t0 = System.nanoTime()
                drain()
                if (paused) { Thread.sleep(80); continue }
                rt.deliver { handled -> if (!handled) MainScope().launch { BlazeOs.home() } }
                rt.step(t0 / 1e6)
                tick.value = rt.frameNo
                val left = frameNs - (System.nanoTime() - t0)
                if (left > 0) Thread.sleep(left / 1_000_000, (left % 1_000_000).toInt())
            }
        } catch (_: InterruptedException) { }
        tick.value++
    }

    fun pause() { if (!paused) { paused = true; rt.input.clear(); runOnApp { rt.pause() } } }
    fun resume() { if (paused) { paused = false; runOnApp { rt.resume() } } }

    /** pause / resume hooks must run on the app's thread; they're queued as a tiny job before the next frame. */
    private fun runOnApp(job: () -> Unit) { synchronized(this) { pending += job } }
    private val pending = ArrayList<() -> Unit>()
    private fun drain() { val jobs = synchronized(this) { pending.toList().also { pending.clear() } }; jobs.forEach { it() } }

    fun kill() {
        thread?.interrupt(); thread = null
        rt.close(); images.clear()
    }

    fun bitmap(path: String): android.graphics.Bitmap? = images.getOrPut(path) {
        try { BitmapFactory.decodeFile(host.imageFile(path).path, BitmapFactory.Options().apply { inSampleSize = 1 }) } catch (_: Throwable) { null }
    }
}

/** The open native apps. */
object OsWasm {
    private val live = ConcurrentHashMap<String, OsWasmTask>()

    /** Memory each app may use: 16 MiB, 8 MiB on a low tier phone. */
    fun maxPages(): Int = if (BlazeOs.budget.value.tier == "low") 128 else OsWasmRuntime.DEFAULT_MAX_PAGES

    fun obtain(ctx: Context, inst: OsInstalled): OsWasmTask =
        live.getOrPut(inst.appId) { OsWasmTask(inst.appId, AndroidWasmHost(ctx.applicationContext, inst), maxPages()) }

    fun pauseAll() { live.values.forEach { it.pause() } }
    fun resumeAll() { /* the app in front resumes itself when it shows again */ }
    fun release(appId: String) { live.remove(appId)?.kill() }
    fun releaseAll() { live.values.forEach { it.kill() }; live.clear() }
    /** Memory in use by native apps, bytes (Settings › Memory). */
    fun memoryBytes(): Long = live.values.sumOf { it.rt.pages().toLong() * 65536 }
}

@Composable
fun OsWasmApp(inst: OsInstalled) {
    val ctx = LocalContext.current
    val look = LocalOsLook.current
    val task = remember(inst.appId) { OsWasm.obtain(ctx, inst) }
    val tick by task.tick.collectAsState()
    val dens = LocalDensity.current
    val focus = remember { FocusRequester() }
    BackHandler { if (task.rt.running) task.rt.input.add(OsInput.Back) else BlazeOs.home() }
    DisposableEffect(task) { task.resume(); onDispose { task.pause() } }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }

    BoxWithConstraints(Modifier.fillMaxSize().background(if (look.dark) Color.Black else Color(0xFFF2F2F7))) {
        val wDp = maxWidth.value; val hDp = maxHeight.value
        LaunchedEffect(wDp, hDp) { task.start(wDp, hDp) }
        val err = task.rt.error
        if (err != null && !task.loading) {
            Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${inst.manifest.name} can't run", style = osText(look, 17f, color = look.ink))
                Text(err, style = osText(look, 13f, color = look.sub), modifier = Modifier.padding(top = 6.dp))
            }
            return@BoxWithConstraints
        }
        val paints = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) } }
        Canvas(Modifier.fillMaxSize()
            .semantics { contentDescription = inst.manifest.name }
            .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                val code = OsSyscalls.keyCode(e.nativeKeyEvent.keyCode)
                if (code == 0) false else { task.rt.input.add(OsInput.Key(code, e.type == KeyEventType.KeyDown)); true }
            }
            .pointerInput(task) {
                awaitPointerEventScope {
                    var sx = 0f; var sy = 0f
                    while (true) {
                        val ev = awaitPointerEvent()
                        for (c in ev.changes) {
                            val x = c.position.x / density; val y = c.position.y / density
                            val kind = when (ev.type) { PointerEventType.Press -> OsInput.DOWN; PointerEventType.Release -> OsInput.UP; PointerEventType.Move -> OsInput.MOVE; else -> -1 }
                            if (kind < 0) continue
                            if (kind == OsInput.DOWN) { sx = x; sy = y }
                            task.rt.input.add(OsInput.Touch(kind, x, y, c.id.value.toInt()))
                            if (kind == OsInput.UP) OsSyscalls.swipe(x - sx, y - sy)?.let { (dx, dy) -> task.rt.input.add(OsInput.Gesture(OsInput.SWIPE, dx, dy)) }
                            c.consume()
                        }
                    }
                }
            }) {
            if (tick < 0) return@Canvas   // reading tick redraws on every new frame
            val k = dens.density
            for (c in task.rt.frame) when (c) {
                is DrawCmd.Clear -> drawRect(Color(c.argb))
                is DrawCmd.Rect -> drawRoundRect(Color(c.argb), Offset(c.x * k, c.y * k), Size(c.w * k, c.h * k), CornerRadius(c.r * k, c.r * k))
                is DrawCmd.Circle -> drawCircle(Color(c.argb), c.r * k, Offset(c.cx * k, c.cy * k))
                is DrawCmd.Line -> drawLine(Color(c.argb), Offset(c.x1 * k, c.y1 * k), Offset(c.x2 * k, c.y2 * k), c.width * k)
                is DrawCmd.Text -> drawIntoCanvas { cv ->
                    paints.color = c.argb
                    paints.textSize = c.size * k * BlazeOs.prefs.value.fontScale.coerceIn(0.8f, 1.6f)
                    paints.textAlign = when (c.align) { 1 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
                    cv.nativeCanvas.drawText(c.text, c.x * k, c.y * k, paints)
                }
                is DrawCmd.Image -> task.bitmap(c.path)?.let { b ->
                    drawImage(b.asImageBitmap(), dstOffset = IntOffset((c.x * k).toInt(), (c.y * k).toInt()), dstSize = IntSize((c.w * k).toInt(), (c.h * k).toInt()))
                }
            }
        }
        if (task.loading) Box(Modifier.align(Alignment.Center)) { Text("Starting ${inst.manifest.name}\u2026", style = osText(look, 14f, color = look.sub)) }
    }
}
