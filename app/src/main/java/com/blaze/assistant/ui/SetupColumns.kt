package com.blaze.assistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.setup.SetupGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color

/** v0.24.4: a setup step's mark: a still ring (to do), a spinning ring (working on it), a checkmark (done). */
enum class Mark { TODO, WORKING, DONE }

@Composable
fun StepMark(mark: Mark, size: Dp = 26.dp) {
    val c = LocalBlaze.current.mono
    val done by animateFloatAsState(if (mark == Mark.DONE) 1f else 0f, tween(420), label = "done")
    val spin = if (mark == Mark.WORKING) {
        val t = rememberInfiniteTransition(label = "ring")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin").value
    } else 0f
    Canvas(Modifier.size(size).semantics { contentDescription = when (mark) { Mark.DONE -> "Done"; Mark.WORKING -> "Working"; Mark.TODO -> "To do" } }) {
        val w = this.size.width; val sw = w * 0.09f
        val inset = sw / 2f
        val box = androidx.compose.ui.geometry.Size(w - sw, w - sw)
        // Ring: hairline track, accent arc while working; it closes and fills as it turns into a check.
        drawCircle(c.hair2, radius = (w - sw) / 2f, style = Stroke(sw))
        if (mark == Mark.WORKING) drawArc(c.accent, spin, 100f, false, Offset(inset, inset), box, style = Stroke(sw, cap = StrokeCap.Round))
        if (done > 0f) {
            drawArc(c.accent, -90f, 360f * done, false, Offset(inset, inset), box, style = Stroke(sw, cap = StrokeCap.Round))
            drawCircle(c.accent.copy(alpha = done), radius = (w - sw) / 2f * done)
            // The check draws itself in the second half.
            val p = ((done - 0.4f) / 0.6f).coerceIn(0f, 1f)
            if (p > 0f) {
                val a = Offset(w * 0.30f, w * 0.52f); val b = Offset(w * 0.45f, w * 0.66f); val e = Offset(w * 0.71f, w * 0.38f)
                val path = Path().apply {
                    moveTo(a.x, a.y)
                    if (p < 0.4f) { val k = p / 0.4f; lineTo(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k) }
                    else { lineTo(b.x, b.y); val k = (p - 0.4f) / 0.6f; lineTo(b.x + (e.x - b.x) * k, b.y + (e.y - b.y) * k) }
                }
                drawPath(path, c.onAccent, style = Stroke(sw * 1.2f, cap = StrokeCap.Round))
            }
        }
    }
}

/** One column row of the setup list: mark, title, why, and a button (or Later for optional steps). */
@Composable
fun SetupColumnRow(title: String, why: String, mark: Mark, required: Boolean, status: String = "",
                   onDo: (() -> Unit)?, onLater: (() -> Unit)?) {
    val c = LocalBlaze.current.mono
    Row(Modifier.fillMaxWidth().pageGlassSelectable(c, mark == Mark.DONE)
        .let { if (onDo != null && mark == Mark.TODO) it.clickable(onClick = onDo) else it }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        StepMark(mark)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = if (mark == Mark.DONE) c.accent else c.ink))
                if (!required && mark != Mark.DONE) { Spacer(Modifier.width(8.dp)); Tag("Optional") }
            }
            Spacer(Modifier.height(2.dp))
            Text(status.ifBlank { why }, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
            if (mark == Mark.TODO && (onDo != null || onLater != null)) Row(Modifier.padding(top = 8.dp)) {
                if (onDo != null) KitButton(if (required) "Set up" else "Turn on", Btn.INK, onClick = onDo)
                if (onLater != null) { Spacer(Modifier.width(8.dp)); KitButton("Later", Btn.GHOST, onClick = onLater) }
            }
        }
    }
}

/** Starts the fix for a dependency: a permission prompt, a system settings page, an install, or the Desktop page. */
fun setupFix(ctx: Context, id: String, ask: (String) -> Unit, openPage: (String) -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    val pkg = Uri.parse("package:" + ctx.packageName)
    fun go(i: Intent) = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    when (id) {
        "notifications" -> if (Build.VERSION.SDK_INT >= 33) ask(Manifest.permission.POST_NOTIFICATIONS)
            else go(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        "mic" -> ask(Manifest.permission.RECORD_AUDIO)
        "linux" -> scope.launch { try { com.blaze.assistant.terminal.Linux.install() } catch (_: Throwable) { } }
        "accessibility" -> {
            com.blaze.assistant.agent.Settings.phoneControl = true
            ComeBack.watch(ctx) { com.blaze.assistant.phone.BlazeAccessibilityService.isEnabled(ctx) }
            go(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        "overlay" -> { ComeBack.watch(ctx) { android.provider.Settings.canDrawOverlays(ctx) }; go(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)) }
        "battery" -> try {
            @Suppress("BatteryLife")
            ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { go(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        "desktop" -> openPage("desktop")
    }
}

/** Settings › Permissions (v0.24.4): every dependency with its mark; tap to fix. */
@Composable
fun PermissionsPage(onOpenPage: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val have = remember(tick) { SetupGate.probe(ctx) }
    val linuxBusy by com.blaze.assistant.terminal.Linux.installing.collectAsState()
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    Spacer(Modifier.height(10.dp))
    GroupHead("Permissions & setup", "What Blaze can use on this phone. Tap one that's off to turn it on.")
    SetupGate.deps.forEach { d ->
        val ok = have[d.id] == true
        SetupColumnRow(d.title, d.why, if (ok) Mark.DONE else if (d.id == "linux" && linuxBusy) Mark.WORKING else Mark.TODO, d.required,
            onDo = if (ok) null else ({ setupFix(ctx, d.id, { perm.launch(it) }, onOpenPage, scope) }), onLater = null)
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(14.dp))
}

/** Settings › Updates (v0.24.4): the one update card plus the check button. Updates run in the background. */
@Composable
fun UpdatesPage() {
    val ctx = LocalContext.current
    Spacer(Modifier.height(10.dp))
    GroupHead("Updates", "Blaze checks once a day. Updates download in the background with progress in the notification (and a Stop button); Android asks you to confirm the install.")
    UpdateCard()
    Spacer(Modifier.height(12.dp))
    Row {
        KitButton("Check now", Btn.INK) { com.blaze.assistant.update.Updater.retry(ctx) }
        Spacer(Modifier.width(8.dp))
        KitButton("Stop", Btn.GHOST) { com.blaze.assistant.update.Updater.stop() }
    }
}

/** Settings › About (v0.24.4). */
@Composable
fun AboutPage() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("")
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OrbFace(48.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Blaze", style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = c.ink))
            Label("Version $v", color = c.weak, size = 10.sp)
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("Your chats, memories, keys and saved logins stay on this phone (keys are synced only when you sign in to a Blaze account). Private chats are never saved.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 21.sp, color = c.body))
    Spacer(Modifier.height(14.dp))
    Label("blazeinchue.pages.dev", color = c.sub, size = 10.sp)
}
