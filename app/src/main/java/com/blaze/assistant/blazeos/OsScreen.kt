package com.blaze.assistant.blazeos

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.MascotMark
import com.blaze.assistant.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * v0.33.2 BlazeOS screen: Off / boot / the running phone (wallpaper, home screen or the app in front, status row,
 * home indicator, control centre, power menu, app switcher, BlazeOS permission prompts, banners and the volume bar).
 * The look follows design B: an orange-to-blue wallpaper, frosted widgets, squircle icons and a frosted dock.
 */

/** BlazeOS's colours for this frame, from its own Light / Dark, glass and accent settings. */
data class OsLook(val dark: Boolean, val ink: Color, val sub: Color, val fill: Color, val rimTop: Color, val rimBottom: Color,
                  val accent: Color, val panel: Color, val fontScale: Float, val anim: AnimLevel, val blur: Boolean) {
    fun sp(v: Float): TextUnit = (v * fontScale).sp
}

val LocalOsLook = staticCompositionLocalOf { lookOf(OsPrefs(), BlazeOs.budget.value) }

fun lookOf(p: OsPrefs, b: OsBudget): OsLook {
    val k = when (p.glass) { "low" -> 0.7f; "high" -> 1.4f; else -> 1f }
    val accent = OsIcons.parseColor(p.accent)?.let { Color(it.toInt()) } ?: Color(0xFFFF9A3C)
    return if (p.dark) OsLook(true, Color.White, Color.White.copy(alpha = 0.66f), Color(0xFF15161C).copy(alpha = (0.30f * k).coerceAtMost(0.62f)),
        Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.05f), accent, Color(0xFF1C1C22), p.fontScale, b.anim, b.blur)
    else OsLook(false, Color(0xFF111114), Color(0xFF111114).copy(alpha = 0.62f), Color.White.copy(alpha = (0.52f * k).coerceAtMost(0.9f)),
        Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.35f), accent, Color(0xFFF2F2F7), p.fontScale, b.anim, b.blur)
}

/** Frosted BlazeOS glass: a translucent fill and a light rim (the real blur is only the control centre's, on API 31+). */
fun Modifier.osGlass(look: OsLook, shape: Shape = RoundedCornerShape(24.dp)): Modifier =
    this.clip(shape).background(look.fill, shape).border(1.dp, Brush.verticalGradient(listOf(look.rimTop, look.rimBottom)), shape)

/** Ephemeral UI state shared by the activity (volume keys) and the screen. */
object OsUiState {
    val volumeAt = MutableStateFlow(0L)
    fun showVolume() { volumeAt.value = System.currentTimeMillis() }
    val control = MutableStateFlow(false)
    val powerMenu = MutableStateFlow(false)
    val switcher = MutableStateFlow(false)
}

@Composable
fun osText(look: OsLook, size: Float, weight: FontWeight = FontWeight.Normal, color: Color = look.ink) =
    TextStyle(fontFamily = Type.Sans, fontSize = look.sp(size), fontWeight = weight, color = color)

@Composable
fun BlazeOsScreen(onExit: () -> Unit) {
    val power by BlazeOs.power.collectAsState()
    val prefs by BlazeOs.prefs.collectAsState()
    val budget by BlazeOs.budget.collectAsState()
    val look = remember(prefs, budget) { lookOf(prefs, budget) }
    CompositionLocalProvider(LocalOsLook provides look) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (power) {
                Power.OFF -> OffScreen(onPowerOn = { BlazeOs.open() }, onExit = onExit)
                Power.BOOTING -> BootScreen()
                Power.ON, Power.PAUSED -> Running(onExit)
            }
        }
    }
}

@Composable
private fun OffScreen(onPowerOn: () -> Unit, onExit: () -> Unit) {
    val look = LocalOsLook.current
    BackHandler { onExit() }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        MascotMark(96.dp, modifier = Modifier.graphicsLayer { alpha = 0.55f })
        Spacer(Modifier.height(18.dp))
        Text("BlazeOS is off", style = osText(look, 20f, FontWeight.SemiBold, Color.White))
        Spacer(Modifier.height(6.dp))
        Text("Blaze's own virtual phone. Nothing is signed in.", style = osText(look, 14f, color = Color.White.copy(alpha = 0.6f)), textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        OsPillButton("Power on", Icons.Rounded.PowerSettingsNew, primary = true, onClick = onPowerOn)
        Spacer(Modifier.height(12.dp))
        OsPillButton("Exit to Blaze", Icons.Rounded.ExitToApp, onClick = onExit)
    }
}

/** The boot animation: the mascot breathing over the wallpaper's colours, then the progress bar fills. */
@Composable
private fun BootScreen() {
    val look = LocalOsLook.current
    val progress by BlazeOs.bootProgress.collectAsState()
    val scale = if (look.anim == AnimLevel.OFF) 1f else {
        val t = rememberInfiniteTransition(label = "boot")
        t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(if (look.anim == AnimLevel.FULL) 700 else 900), RepeatMode.Reverse), label = "s").value
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF07080C), Color(0xFF0B1026)))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MascotMark(120.dp, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
            Spacer(Modifier.height(22.dp))
            Text("BlazeOS", style = osText(look, 26f, FontWeight.SemiBold, Color.White))
            Spacer(Modifier.height(26.dp))
            Box(Modifier.width(140.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.05f, 1f)).height(5.dp).clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFFA24A), Color(0xFF3D8BFF)))))
            }
        }
    }
}

@Composable
private fun Running(onExit: () -> Unit) {
    val look = LocalOsLook.current
    val power by BlazeOs.power.collectAsState()
    val tasks by BlazeOs.tasks.collectAsState()
    val control by OsUiState.control.collectAsState()
    val powerMenu by OsUiState.powerMenu.collectAsState()
    val switcher by OsUiState.switcher.collectAsState()
    val ask by BlazeOs.permAsk.collectAsState()
    // Lowest priority: Back on the BlazeOS home screen stays in BlazeOS (Exit is in the power menu).
    BackHandler { }
    val holder = rememberSaveableStateHolder()
    // Closed apps lose their saved state; open ones keep it while they are in the background.
    LaunchedEffect(tasks.open) { OsAppStates.retain(tasks.open) { holder.removeState(it); OsWeb.release(it); OsWasm.release(it) } }
    // Back: overlays first, then the app (its own back), then the home screen does nothing (like a phone).
    BackHandler(enabled = control || powerMenu || switcher) { OsUiState.control.value = false; OsUiState.powerMenu.value = false; OsUiState.switcher.value = false }
    val blurBehind = look.blur && (control || powerMenu || switcher)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (blurBehind) Modifier.blur(28.dp) else Modifier)) {
            OsWallpaperLayer()
            val front = tasks.front
            if (front.isEmpty()) OsHomeScreen(onExit = onExit)
            else Box(Modifier.fillMaxSize().background(if (look.dark) Color.Black else Color(0xFFF2F2F7))) {
                holder.SaveableStateProvider(front) { OsAppHost(front) }
            }
        }
        StatusRow(Modifier.align(Alignment.TopCenter), onAppScreen = tasks.front.isNotEmpty())
        HomeIndicator(Modifier.align(Alignment.BottomCenter))
        if (control || powerMenu || switcher) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (look.blur) 0.25f else 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                OsUiState.control.value = false; OsUiState.powerMenu.value = false; OsUiState.switcher.value = false })
        AnimatedVisibility(control, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) { ControlCentre() }
        if (powerMenu) PowerMenu(onExit)
        if (switcher) AppSwitcher()
        UninstallConfirm()
        ask?.let { PermissionPrompt(it) }
        BannerLayer(Modifier.align(Alignment.TopCenter))
        VolumeHud(Modifier.align(Alignment.CenterEnd))
        if (power == Power.PAUSED) PausedCover()
    }
}

/** Shown only for a moment if the window draws while BlazeOS is paused (it resumes on start). */
@Composable
private fun PausedCover() {
    val look = LocalOsLook.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable { BlazeOs.resume() }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MascotMark(72.dp)
            Spacer(Modifier.height(12.dp))
            Text("Paused · tap to resume", style = osText(look, 15f, color = Color.White))
        }
    }
}

// ---------------- status row, home indicator ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusRow(modifier: Modifier, onAppScreen: Boolean) {
    val look = LocalOsLook.current
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableStateOf(OsHost.Battery(-1, false)) }
    var net by remember { mutableStateOf("none") }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            val (b, n) = withContext(Dispatchers.IO) { OsHost.battery(ctx) to OsHost.network(ctx) }
            battery = b; net = n
            delay(20_000)
        }
    }
    val ink = if (onAppScreen && !look.dark) Color(0xFF111114) else Color.White
    val time = remember(now) { java.text.SimpleDateFormat("H:mm", java.util.Locale.getDefault()).format(java.util.Date(now)) }
    Row(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout).height(36.dp)
        .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy > 12f) OsUiState.control.value = true } }
        .padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = osText(look, 15f, FontWeight.SemiBold, ink), modifier = Modifier.clickable { OsUiState.control.value = true })
        Spacer(Modifier.weight(1f))
        // The status pill: tap = control centre, long-press = the BlazeOS power menu (the real power button can't be caught).
        Row(Modifier.clip(CircleShape).combinedClickable(onClick = { OsUiState.control.value = true }, onLongClick = { OsUiState.powerMenu.value = true })
            .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SignalCellularAlt, null, tint = ink, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Icon(if (net == "none") Icons.Rounded.WifiOff else Icons.Rounded.Wifi, null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.clip(RoundedCornerShape(5.dp)).background(ink.copy(alpha = 0.9f)).padding(horizontal = 5.dp, vertical = 1.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (battery.charging) Icon(Icons.Rounded.Bolt, null, tint = if (ink == Color.White) Color.Black else Color.White, modifier = Modifier.size(10.dp))
                    Text(if (battery.pct >= 0) "${battery.pct}" else "–", style = osText(look, 11f, FontWeight.SemiBold, if (ink == Color.White) Color.Black else Color.White))
                }
            }
        }
    }
}

/** The home indicator: tap or swipe up = BlazeOS home; long-press = the app switcher. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeIndicator(modifier: Modifier) {
    val tasks by BlazeOs.tasks.collectAsState()
    val look = LocalOsLook.current
    val ink = if (tasks.front.isNotEmpty() && !look.dark) Color(0xFF111114) else Color.White
    Box(modifier.fillMaxWidth().height(26.dp)
        .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -10f) { OsUiState.switcher.value = false; BlazeOs.home() } } }
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = { BlazeOs.home() }, onLongClick = { OsUiState.switcher.value = true }),
        contentAlignment = Alignment.Center) {
        Box(Modifier.width(134.dp).height(5.dp).clip(CircleShape).background(ink.copy(alpha = 0.85f)))
    }
}

// ---------------- wallpaper ----------------

@Composable
fun OsWallpaperLayer() {
    val prefs by BlazeOs.prefs.collectAsState()
    val budget by BlazeOs.budget.collectAsState()
    val ctx = LocalContext.current
    val w = prefs.wallpaper
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(w, budget.imagePx) {
        photo = if (w == "photo") withContext(Dispatchers.IO) { OsImages.load(OsDisk.wallpaper(ctx).absolutePath, budget.imagePx)?.asImageBitmap() } else null
    }
    when {
        w == "photo" && photo != null -> Image(photo!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        w.startsWith("solid:") -> Box(Modifier.fillMaxSize().background(Color((OsIcons.parseColor(w.removePrefix("solid:")) ?: 0xFF14161CL).toInt())))
        else -> PresetWallpaper(OsWallpaper.preset(w).ifEmpty { "dusk" }, Modifier.fillMaxSize())
    }
}

/** The presets are drawn (no image files): soft radial glows over a gradient, cheap on any phone. */
@Composable
fun PresetWallpaper(id: String, modifier: Modifier) {
    val (base, glows) = when (id) {
        "ocean" -> listOf(Color(0xFF021B3A), Color(0xFF00305E)) to listOf(Color(0xFF00A2FF) to Offset(0.2f, 0.25f), Color(0xFF00E0C6) to Offset(0.85f, 0.7f), Color(0xFF1E5BFF) to Offset(0.5f, 1.0f))
        "ember" -> listOf(Color(0xFF2A0A04), Color(0xFF4A1206)) to listOf(Color(0xFFFF6A1A) to Offset(0.2f, 0.3f), Color(0xFFFFB347) to Offset(0.8f, 0.15f), Color(0xFFC2185B) to Offset(0.6f, 0.95f))
        "graphite" -> listOf(Color(0xFF0E0F13), Color(0xFF1C1E25)) to listOf(Color(0xFF4A4F5C) to Offset(0.25f, 0.2f), Color(0xFF2E3240) to Offset(0.8f, 0.8f))
        // "dusk", design B: warm orange top-left and bottom, deep blue between, bright blue lower right.
        else -> listOf(Color(0xFF0A1A4A), Color(0xFF06245E)) to listOf(Color(0xFFFF8A1F) to Offset(0.05f, 0.08f), Color(0xFFFFB54D) to Offset(0.45f, 0.0f),
            Color(0xFF0057E7) to Offset(0.85f, 0.42f), Color(0xFFFF6A00) to Offset(0.1f, 0.78f), Color(0xFF00A6FF) to Offset(0.95f, 0.95f))
    }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(base))
        val r = size.maxDimension * 0.62f
        for ((c, o) in glows) drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0f)), Offset(o.x * size.width, o.y * size.height), r),
            r, Offset(o.x * size.width, o.y * size.height))
    }
}

// ---------------- control centre, power menu, switcher ----------------

@Composable
private fun ControlCentre() {
    val look = LocalOsLook.current
    val ctx = LocalContext.current
    val prefs by BlazeOs.prefs.collectAsState()
    val notices by BlazeOs.notices.collectAsState()
    var net by remember { mutableStateOf("none") }
    LaunchedEffect(Unit) { net = withContext(Dispatchers.IO) { OsHost.network(ctx) } }
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout).padding(top = 40.dp, start = 14.dp, end = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CcTile(Modifier.weight(1f), if (net == "none") Icons.Rounded.WifiOff else Icons.Rounded.Wifi, OsHost.networkLabel(net),
                "From the phone (read-only)", on = net != "none") { }
            CcTile(Modifier.weight(1f), if (prefs.dark) Icons.Rounded.DarkMode else Icons.Rounded.LightMode, if (prefs.dark) "Dark" else "Light",
                "BlazeOS look", on = prefs.dark) { BlazeOs.update { it.copy(theme = if (it.dark) "light" else "dark") } }
        }
        Spacer(Modifier.height(12.dp))
        CcSlider(Icons.Rounded.BrightnessMedium, "Brightness", prefs.brightness) { BlazeOs.setBrightness(it) }
        Spacer(Modifier.height(10.dp))
        CcSlider(if (prefs.volume == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "Volume", prefs.volume) { BlazeOs.setVolume(it) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().osGlass(look, RoundedCornerShape(22.dp)).clickable { OsUiState.control.value = false; OsUiState.powerMenu.value = true }
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PowerSettingsNew, null, tint = look.ink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text("Power", style = osText(look, 15f, FontWeight.SemiBold))
            Spacer(Modifier.weight(1f))
            Text("Pause · Restart · Off", style = osText(look, 13f, color = look.sub))
        }
        if (notices.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().osGlass(look, RoundedCornerShape(22.dp)).padding(14.dp)) {
                Text("Notifications", style = osText(look, 13f, FontWeight.SemiBold, look.sub))
                for (n in notices.take(4)) {
                    Spacer(Modifier.height(8.dp))
                    Text(n.app + " · " + n.title, style = osText(look, 14f, FontWeight.Medium), maxLines = 1)
                    if (n.body.isNotBlank()) Text(n.body, style = osText(look, 13f, color = look.sub), maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun CcTile(modifier: Modifier, icon: ImageVector, title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    val look = LocalOsLook.current
    Row(modifier.osGlass(look, RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(if (on) look.accent else look.ink.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (on) Color.White else look.ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = osText(look, 14f, FontWeight.SemiBold), maxLines = 1)
            Text(sub, style = osText(look, 11f, color = look.sub), maxLines = 1)
        }
    }
}

@Composable
private fun CcSlider(icon: ImageVector, label: String, value: Int, onChange: (Int) -> Unit) {
    val look = LocalOsLook.current
    Row(Modifier.fillMaxWidth().osGlass(look, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, tint = look.ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Slider(value = value / 100f, onValueChange = { onChange((it * 100).toInt()) }, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = look.accent, inactiveTrackColor = look.ink.copy(alpha = 0.2f)))
        Spacer(Modifier.width(8.dp))
        Text("$value", style = osText(look, 12f, color = look.sub), modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun PowerMenu(onExit: () -> Unit) {
    val look = LocalOsLook.current
    fun close() { OsUiState.powerMenu.value = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(look.panel.copy(alpha = 0.94f)).padding(10.dp)) {
            Text("BlazeOS", style = osText(look, 13f, FontWeight.SemiBold, look.sub), modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 6.dp))
            PowerRow(Icons.Rounded.Pause, "Pause & leave", "Frees memory; your app is kept") { close(); onExit() }
            PowerRow(Icons.Rounded.RestartAlt, "Restart", "Closes every app and boots again") { close(); BlazeOs.restart() }
            PowerRow(Icons.Rounded.PowerSettingsNew, "Power off", "Closes everything; tap Home to start again") { close(); BlazeOs.powerOff() }
            PowerRow(Icons.Rounded.ExitToApp, "Exit to Blaze", "Back to the chat; BlazeOS pauses") { close(); onExit() }
            PowerRow(Icons.Rounded.Close, "Cancel", "") { close() }
        }
    }
}

@Composable
private fun PowerRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val look = LocalOsLook.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(look.ink.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = look.ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = osText(look, 16f, FontWeight.SemiBold))
            if (sub.isNotEmpty()) Text(sub, style = osText(look, 12f, color = look.sub))
        }
    }
}

@Composable
private fun AppSwitcher() {
    val look = LocalOsLook.current
    val tasks by BlazeOs.tasks.collectAsState()
    val apps = remember(tasks) { BlazeOs.apps() }
    Column(Modifier.fillMaxSize().padding(vertical = 90.dp), verticalArrangement = Arrangement.Center) {
        if (tasks.recents.isEmpty()) Text("No open apps", style = osText(look, 16f, color = Color.White), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 32.dp)) {
            items(tasks.recents, key = { it }) { id ->
                val a = apps.firstOrNull { it.id == id } ?: OsApp(id, id)
                Column(Modifier.width(180.dp).height(320.dp).clip(RoundedCornerShape(28.dp)).background(look.panel)
                    .clickable { OsUiState.switcher.value = false; BlazeOs.launch(id) }.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.label, style = osText(look, 14f, FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.Rounded.Close, "Close ${a.label}", tint = look.sub, modifier = Modifier.size(22.dp).clip(CircleShape).clickable { BlazeOs.close(id) })
                    }
                    Spacer(Modifier.weight(1f))
                    OsAppIcon(a, 72.dp)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PermissionPrompt(a: BlazeOs.PermAsk) {
    val look = LocalOsLook.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(36.dp).clip(RoundedCornerShape(26.dp)).background(look.panel).padding(top = 22.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(OsPerms.question(a.appLabel, a.perm), style = osText(look, 16f, FontWeight.SemiBold), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Text("BlazeOS asks for each app. You can change it in Settings › Apps.", style = osText(look, 13f, color = look.sub), textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(14.dp))
            for ((label, v) in listOf("Allow" to "allow", "Only this time" to "once", "Don't allow" to "deny")) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(16.dp)).clickable { a.answer.complete(v) }.padding(14.dp),
                    contentAlignment = Alignment.Center) {
                    Text(label, style = osText(look, 16f, FontWeight.Medium, if (v == "deny") look.sub else look.accent))
                }
            }
        }
    }
}

@Composable
private fun BannerLayer(modifier: Modifier) {
    val look = LocalOsLook.current
    val b by BlazeOs.banner.collectAsState()
    AnimatedVisibility(b != null, modifier = modifier, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        val n = b ?: return@AnimatedVisibility
        Column(Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(top = 40.dp, start = 12.dp, end = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(22.dp)).background(look.panel.copy(alpha = 0.96f)).clickable { BlazeOs.banner.value = null }.padding(14.dp)) {
            Text(n.app.uppercase(), style = osText(look, 11f, FontWeight.SemiBold, look.sub))
            Spacer(Modifier.height(2.dp))
            Text(n.title, style = osText(look, 15f, FontWeight.SemiBold), maxLines = 1)
            if (n.body.isNotBlank()) Text(n.body, style = osText(look, 13f, color = look.sub), maxLines = 2)
        }
    }
}

@Composable
private fun VolumeHud(modifier: Modifier) {
    val look = LocalOsLook.current
    val at by OsUiState.volumeAt.collectAsState()
    val prefs by BlazeOs.prefs.collectAsState()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(at) { if (at > 0) { show = true; delay(1600); show = false } }
    AnimatedVisibility(show, modifier = modifier.padding(end = 10.dp), enter = fadeIn(), exit = fadeOut()) {
        Column(Modifier.width(44.dp).height(170.dp).clip(RoundedCornerShape(22.dp)).background(look.panel.copy(alpha = 0.92f)).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).width(32.dp).clip(RoundedCornerShape(16.dp)).background(look.ink.copy(alpha = 0.15f)), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight((prefs.volume / 100f).coerceIn(0.02f, 1f)).clip(RoundedCornerShape(16.dp)).background(look.ink))
            }
            Spacer(Modifier.height(6.dp))
            Icon(if (prefs.volume == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "BlazeOS volume ${prefs.volume}", tint = look.ink, modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------- shared bits ----------------

@Composable
fun OsPillButton(text: String, icon: ImageVector? = null, primary: Boolean = false, onClick: () -> Unit) {
    val look = LocalOsLook.current
    Row(Modifier.clip(CircleShape).background(if (primary) look.accent else Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick)
        .padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = osText(look, 15f, FontWeight.SemiBold, Color.White))
    }
}

/** Keeps the saved state of open apps only (closed apps are forgotten). */
object OsAppStates {
    private val known = HashSet<String>()
    fun retain(open: List<String>, drop: (String) -> Unit) {
        val gone = known - open.toSet()
        for (g in gone) drop(g)
        known.clear(); known.addAll(open)
    }
}

// ---------------- Blaze's Home tab ----------------

/**
 * Blaze's Home tab: switching to it opens BlazeOS fullscreen (BlazeOsActivity). Coming back from BlazeOS shows this
 * card: a small BlazeOS screen with its state, Open, and the way to the Classic board.
 */
@Composable
fun BlazeOsCard() {
    val ctx = LocalContext.current
    val power by BlazeOs.power.collectAsState()
    val prefs by BlazeOs.prefs.collectAsState()
    val budget by BlazeOs.budget.collectAsState()
    val look = remember(prefs, budget) { lookOf(prefs, budget) }
    LaunchedEffect(Unit) { BlazeOsActivity.open(ctx) }
    CompositionLocalProvider(LocalOsLook provides look) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.width(200.dp).height(380.dp).clip(RoundedCornerShape(40.dp)).border(6.dp, Color(0xFF1C1C1E), RoundedCornerShape(40.dp))
                .clickable { BlazeOsActivity.open(ctx) }, contentAlignment = Alignment.Center) {
                OsWallpaperLayer()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MascotMark(72.dp, modifier = Modifier.graphicsLayer { alpha = if (power == Power.OFF) 0.5f else 1f })
                    Spacer(Modifier.height(10.dp))
                    Text("BlazeOS", style = osText(look, 18f, FontWeight.SemiBold, Color.White))
                    Text(OsMachine.label(power), style = osText(look, 13f, color = Color.White.copy(alpha = 0.75f)))
                }
            }
            Spacer(Modifier.height(20.dp))
            OsPillButton(if (power == Power.PAUSED) "Resume BlazeOS" else "Open BlazeOS", Icons.Rounded.PowerSettingsNew, primary = true) { BlazeOsActivity.open(ctx) }
            Spacer(Modifier.height(10.dp))
            Text("Blaze's own virtual phone · nothing signed in", style = osText(look, 13f, color = com.blaze.assistant.ui.LocalBlaze.current.sub), textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("Use the Classic board", style = osText(look, 13f, FontWeight.Medium, com.blaze.assistant.ui.LocalBlaze.current.ink),
                modifier = Modifier.clip(CircleShape).clickable { BlazeOs.classic = true }.padding(8.dp))
        }
    }
}
