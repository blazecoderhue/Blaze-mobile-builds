package com.blaze.assistant.ui

import androidx.compose.ui.graphics.drawscope.clipRect

import androidx.compose.ui.draw.drawWithContent

import androidx.compose.foundation.layout.statusBarsPadding

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.blaze.assistant.home.Ago
import com.blaze.assistant.home.HomeBoard
import com.blaze.assistant.home.Interests
import com.blaze.assistant.home.StackState
import com.blaze.assistant.home.Stacks
import com.blaze.assistant.home.Widget
import com.blaze.assistant.home.WidgetData
import com.blaze.assistant.home.WidgetIntent
import com.blaze.assistant.home.WidgetLine
import com.blaze.assistant.home.WidgetSize
import com.blaze.assistant.home.WidgetStack
import com.blaze.assistant.home.WidgetTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * v0.29.0 Home board. [HomePane] is the stateful screen (reads HomeBoard's flows); everything under it is stateless so the
 * screenshot tests draw it with sample data. Kit-rounded surfaces only (NoSquareTest), TalkBack labels on every control,
 * start / end paddings (RTL safe), no fixed text heights (big fonts), no remote images (low-RAM phones).
 */

// ---------------------------------------------------------------- looks

/** Accent themes for the board. */
object HomeLook {
    data class Accent(val id: String, val name: String, val light: Color, val dark: Color)
    val accents = listOf(
        Accent("", "Blaze", Color(0xFF1A1A1C), GlassTokens.accent),   // v0.31: monochrome (glass) default
        Accent("orange", "Orange", Color(0xFFD9480F), Color(0xFFFF9A3C)),
        Accent("green", "Green", Color(0xFF2B8A3E), Color(0xFF69DB7C)),
        Accent("purple", "Purple", Color(0xFF6741D9), Color(0xFFB197FC)),
        Accent("pink", "Pink", Color(0xFFC2255C), Color(0xFFF783AC)),
        Accent("teal", "Teal", Color(0xFF0B7285), Color(0xFF66D9E8)),
    )
    fun accent(id: String, dark: Boolean): Color? = accents.firstOrNull { it.id == id && id.isNotBlank() }?.let { if (dark) it.dark else it.light }

    /** Solid / gradient swatches: (light, dark) pairs; index 0 = the app's own paper. */
    val solids = listOf(Color.Unspecified to Color.Unspecified, Color(0xFFF1F3F8) to Color(0xFF1E2230), Color(0xFFF6F1EA) to Color(0xFF2A241F),
        Color(0xFFEDF5EF) to Color(0xFF1C2620), Color(0xFFF4EEF8) to Color(0xFF261F2C))
    val gradients = listOf(listOf(Color(0xFFE8EEFF), Color(0xFFFFF1E6)) to listOf(Color(0xFF1B2140), Color(0xFF2E2230)),
        listOf(Color(0xFFE6FCF5), Color(0xFFE7F5FF)) to listOf(Color(0xFF14302A), Color(0xFF172A3A)),
        listOf(Color(0xFFFFF0F6), Color(0xFFF3F0FF)) to listOf(Color(0xFF34182A), Color(0xFF211B3A)),
        listOf(Color(0xFFFFF9DB), Color(0xFFFFE8CC)) to listOf(Color(0xFF332B10), Color(0xFF3A2414)))
}

/**
 * v0.29.3 glass look. Over the sky (the default) or a photo, cards turn to frosted glass: a translucent fill that is
 * brighter at the top, a bright hairline edge, and dark ink so text reads on a light sky in any app theme.
 */
data class HomeGlassStyle(val fill: Brush, val edge: Color, val chip: Color)
val LocalGlass = staticCompositionLocalOf<HomeGlassStyle?> { null }

object HomeGlass {
    /** v0.31: only a photo keeps the photo-glass style; the default "glass" look uses GlassKit's [Modifier.glass]. */
    fun on(kind: String) = kind == "image"
    /** The default look (and the retired sky / Solid-default values that map to it). */
    fun isGlass(kind: String, idx: Int = -1) = kind == "glass" || kind == "sky" || kind.isBlank() || (kind == "plain" && idx == 0)
    val light = HomeGlassStyle(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.66f), Color.White.copy(alpha = 0.40f))),
        Color.White.copy(alpha = 0.80f), Color.White.copy(alpha = 0.55f))
    val dark = HomeGlassStyle(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.06f))),
        Color.White.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.45f))
    /** The sky is light whatever the app theme, so the board uses light ink on it; a photo follows the theme. */
    fun lightInk(kind: String, dark: Boolean) = kind == "image" && !dark
    val skyTop = Color(0xFF6CB8F2); val skyMid = Color(0xFFA9D8FB); val skyLow = Color(0xFFDDF0FF); val skyHorizon = Color(0xFFF4FAFF)
}

/**
 * A card / button surface. v0.31: GlassKit frosted glass by default (static sheen + rim, no live blur, so it is cheap in
 * the scrolling board); over a user photo the photo-glass style keeps text readable.
 */
fun Modifier.homeSurface(@Suppress("UNUSED_PARAMETER") c: BlazeColors, g: HomeGlassStyle?, shape: androidx.compose.ui.graphics.Shape,
                         intensity: GlassIntensity = GlassIntensity.Medium, shadow: Boolean = true): Modifier =
    if (g != null) clip(shape).background(g.fill).border(1.dp, g.edge, shape) else glass(shape, intensity, shadow = shadow)

/** A soft daytime sky: blue to pale horizon, a warm sun glow, and a few cloud puffs. Drawn, no image to load. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSky() {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(0f to HomeGlass.skyTop, 0.35f to HomeGlass.skyMid, 0.75f to HomeGlass.skyLow, 1f to HomeGlass.skyHorizon))
    drawCircle(Brush.radialGradient(listOf(Color(0x66FFF6D6), Color(0x00FFF6D6)), center = Offset(w * 0.85f, h * 0.06f), radius = w * 0.75f),
        radius = w * 0.75f, center = Offset(w * 0.85f, h * 0.06f))
    fun cloud(cx: Float, cy: Float, r: Float, a: Float) {
        listOf(Triple(0f, 0f, 1f), Triple(-0.9f, 0.25f, 0.7f), Triple(0.95f, 0.2f, 0.75f), Triple(-0.35f, -0.35f, 0.75f), Triple(0.4f, -0.3f, 0.8f)).forEach { (dx, dy, k) ->
            val c = Offset(cx + dx * r, cy + dy * r); val rr = r * k * 1.25f
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = a), Color.White.copy(alpha = 0f)), center = c, radius = rr), radius = rr, center = c)
        }
    }
    cloud(w * 0.22f, h * 0.16f, w * 0.13f, 0.75f)
    cloud(w * 0.78f, h * 0.30f, w * 0.10f, 0.6f)
    cloud(w * 0.35f, h * 0.52f, w * 0.16f, 0.5f)
    cloud(w * 0.85f, h * 0.70f, w * 0.12f, 0.45f)
    cloud(w * 0.15f, h * 0.86f, w * 0.14f, 0.55f)
}

// ---------------------------------------------------------------- the screen

/**
 * The Home tab. [onAsk] hands a plain-words request to Blaze (the main chat runs it with the home_widget_* tools).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePane(onAsk: (String) -> Unit, onQuote: (String) -> Unit = {},
             /**
              * v0.45.8: Home's ground reaches the screen edges (under the status bar) and [bottom] (Swipe mode's "Swipe up to
              * chat" strip) sits ON it, so no other scene shows through behind the strip.
              */
             edgeToEdge: Boolean = false, bottom: (@Composable () -> Unit)? = null) {
    val base = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val widgets by HomeBoard.widgets.collectAsState()
    val data by HomeBoard.data.collectAsState()
    val loading by HomeBoard.loading.collectAsState()
    val offline by HomeBoard.offline.collectAsState()
    val refreshing by HomeBoard.refreshing.collectAsState()
    // 0.45.23: no interest picker on the first look at Home (it was a first-run step). The board starts with the starters,
    // which include the one-time "Tell me what you're into" card; tapping it opens the picker.
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!HomeBoard.onboarded) HomeBoard.finishOnboarding(emptySet(), emptySet(), "", skipped = true) }
    var stackState by remember { mutableStateOf(StackState()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var customise by rememberSaveable { mutableStateOf(false) }
    var create by rememberSaveable { mutableStateOf(false) }
    var cityFor by remember { mutableStateOf<String?>(null) }
    var glassUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var askChange by remember { mutableStateOf<Widget?>(null) }
    var toast by remember { mutableStateOf("") }
    var look by remember { mutableIntStateOf(0) }   // bumps when the look changes
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    // v0.30.5: looking at Home marks what's on it as seen (Home heads-ups only tell about things you haven't seen).
    LaunchedEffect(Unit) { com.blaze.assistant.home.HomeBoard.markSeen() }
    LaunchedEffect(toast) { if (toast.isNotBlank()) { delay(3_500); toast = "" } }
    // v0.30.5: Blaze's browser at work → the live preview window.
    val busyChats by com.blaze.assistant.agent.Agent.busyChats.collectAsState()
    val liveStep by com.blaze.assistant.browser.BrowserLive.step.collectAsState()
    var liveNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var liveHidden by remember { mutableStateOf(false) }
    val working = busyChats.isNotEmpty()
    LaunchedEffect(working, liveStep) {
        liveNow = System.currentTimeMillis()
        if (!working) liveHidden = false   // the next task shows it again
        while (working) { delay(5_000); liveNow = System.currentTimeMillis() }
    }
    val liveShowing = com.blaze.assistant.browser.BrowserLive.showing(liveStep, working, liveNow) && com.blaze.assistant.browser.BrowserEngine.tabs.isNotEmpty()

    // Coming back from a permission / Usage access screen: retry the cards that asked for it.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) HomeBoard.data.value.forEach { (id, d) -> if (d.action in setOf("usage", "location", "calendar")) HomeBoard.refresh(id) }
        }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val kind = HomeBoard.background
    val lightInk = HomeGlass.lightInk(kind, base.dark)
    // v0.45.8 one theme everywhere: with a living theme picked, Home shows the theme's scene (drawn behind it by the chat
    // screen) in the theme's palette, instead of its own Home look (the navy gradient that ignored a desert theme).
    val themed = themeOn()
    val ink0 = when {
        themed -> base
        HomeGlass.on(kind) -> if (lightInk) LightColors else DarkColors
        HomeGlass.isGlass(kind, HomeBoard.bgColor) -> DarkColors.mono   // v0.31: the default Home is dark glass whatever the app theme
        else -> base
    }
    val accent = remember(look, ink0.dark) { HomeLook.accent(HomeBoard.accent, ink0.dark) }
    val c = if (accent != null) ink0.copy(accent = accent) else ink0
    val glass = if (HomeGlass.on(kind)) (if (c.dark) HomeGlass.dark else HomeGlass.light) else null
    val bgImage = rememberHomeBackground(look).takeIf { !themed }

    fun open(url: String, w: Widget?) {
        if (w != null) { HomeBoard.tapped(w.id); if (w.type == WidgetTypes.CREATORS) HomeBoard.markSeen(w.id) }
        // v0.29.3: web links open in the glass window on Home; app, calendar and other links go where they always did.
        if (url.startsWith("https://") || url.startsWith("http://")) glassUrl = url else openHomeLink(ctx, url)
    }

    fun action(w: Widget, a: String) {
        when {
            a == "location" -> scope.launch {
                com.blaze.assistant.device.Perms.ensure(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                HomeBoard.refresh(w.id)
            }
            a == "calendar" -> scope.launch { com.blaze.assistant.device.Perms.ensure(ctx, android.Manifest.permission.READ_CALENDAR); HomeBoard.refresh(w.id) }
            a == "city" -> cityFor = w.id
            a == "usage" -> openUsageAccess(ctx)
            a == "interests" -> { picking = true }
            a.startsWith("ask:") -> { onAsk(a.removePrefix("ask:")); toast = "Asked Blaze. The answer is in Chat." }
        }
    }

    CompositionLocalProvider(LocalBlaze provides c, LocalGlass provides glass) {
        Box(Modifier.fillMaxSize().then(if (themed) Modifier else Modifier.homeBackground(c, HomeBoard.background, HomeBoard.bgColor, look))) {
            if (bgImage != null && HomeBoard.background == "image") {
                // v0.29.3: the photo shows in full now; the glass cards keep text readable over it.
                Image(bgImage, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (c.dark) 0.9f else 1f })
            }
            Column(Modifier.fillMaxSize().then(if (edgeToEdge) Modifier.statusBarsPadding() else Modifier)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
            if (picking) {
                InterestPicker(onDone = { chosen, subs, typed ->
                    HomeBoard.finishOnboarding(chosen, subs, typed, skipped = false); picking = false
                }, onSkip = { picking = false })
            } else {
                val stacks = remember(widgets) { Stacks.build(widgets) }
                LaunchedEffect(stacks) { stackState = stackState.prune(stacks) }
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = { HomeBoard.pullToRefresh() }, modifier = Modifier.fillMaxSize()) {
                    HomeBoardView(stacks, data, loading, offline, now, stackState,
                        stamp = Ago.of(maxOf(HomeBoard.lastRefresh, data.values.maxOfOrNull { it.fetched } ?: 0L), now),
                        onToggle = { stackState = stackState.toggle(it) },
                        onMenu = { menuFor = it.id },
                        onLine = { w, l -> open(l.url, w) },
                        onOpen = { w, d -> if (d.link.isNotBlank()) open(d.link, w) },
                        onAction = { w, a -> action(w, a) },
                        onVisible = { HomeBoard.visible(it) },
                        onReorder = { ids -> HomeBoard.reorder(ids) },
                        onMove = { id, d -> HomeBoard.move(id, d) },
                        onRemove = { HomeBoard.remove(it) },
                        onCustomise = { customise = true },
                        onCreate = { create = true })
                }
            }
            }
            bottom?.invoke()
            }
            if (toast.isNotBlank()) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).navigationBarsPadding()) { HomeToast(toast) }
            glassUrl?.let { u -> GlassWindow(u, onClose = { glassUrl = null }, onOpenOutside = { openHomeLink(ctx, it) },
                // v0.29.9: the selection pill on the page: Ask / Translate / Define go to Blaze, Quote goes into the chat box.
                onAsk = { t -> onAsk(t); toast = "Asked Blaze. The answer is in Chat." },
                onQuote = { t -> onQuote(t) }) }
            // v0.30.5: while Blaze works in its browser, a fixed glass window previews the page (× hides it until the next task).
            if (glassUrl == null && !picking && liveShowing && !liveHidden) BrowserLivePreview(onHide = { liveHidden = true })

            menuFor?.let { id ->
                val w = widgets.firstOrNull { it.id == id }
                if (w == null) menuFor = null else {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { menuFor = null }) {
                        WidgetMenuCard(w, onPick = { act ->
                            menuFor = null
                            when (act) {
                                "up" -> HomeBoard.move(w.id, -1)
                                "down" -> HomeBoard.move(w.id, 1)
                                "S", "M", "L" -> HomeBoard.resize(w.id, act)
                                "hide" -> { HomeBoard.setHidden(w.id, true); toast = "Hidden. Show it again in Customise." }
                                "remove" -> { HomeBoard.remove(w.id); toast = "Removed \u201c${w.title}\u201d." }
                                "refresh" -> HomeBoard.refresh(w.id)
                                "ask" -> askChange = w
                            }
                        })
                    }
                }
            }
            if (customise) {
                SheetScrim { customise = false }
                Box(Modifier.align(Alignment.BottomCenter)) {
                    CustomiseSheet(widgets, HomeBoard.background, HomeBoard.bgColor, HomeBoard.accent, HomeBoard.wifiOnly, HomeBoard.refreshHours,
                        onBackground = { HomeBoard.background = it; look++ }, onBgColor = { HomeBoard.bgColor = it; look++ },
                        onPickImage = { /* set below */ }, onAccent = { HomeBoard.accent = it; look++ },
                        onWifiOnly = { HomeBoard.wifiOnly = it; look++ }, onHours = { HomeBoard.refreshHours = it; look++ },
                        onSize = { id, s -> HomeBoard.resize(id, s) }, onHidden = { id, h -> HomeBoard.setHidden(id, h) },
                        onMove = { id, d -> HomeBoard.move(id, d) }, onClose = { customise = false },
                        imagePicker = { BackgroundPicker { look++ } })
                }
                androidx.activity.compose.BackHandler { customise = false }
            }
            if (create) {
                SheetScrim { create = false }
                Box(Modifier.align(Alignment.BottomCenter)) {
                    CreateWidgetSheet(onAdd = { text ->
                        create = false
                        val spec = WidgetIntent.parse(text)
                        if (spec != null) { HomeBoard.add(spec); toast = "Added \u201c${spec.title}\u201d." }
                        else { onAsk("Add a widget to my Home board: $text"); toast = "Asked Blaze. It appears here when it's ready." }
                    }, onClose = { create = false })
                }
                androidx.activity.compose.BackHandler { create = false }
            }
            cityFor?.let { id ->
                var city by remember { mutableStateOf("") }
                androidx.compose.ui.window.Dialog(onDismissRequest = { cityFor = null }) {
                    BlazeDialogCard("Which city?", "I'll show the weather there.", confirm = "Show weather", onConfirm = {
                        if (city.isNotBlank()) HomeBoard.setConfig(id, mapOf("city" to city.trim(), "use_location" to false))
                        cityFor = null
                    }, dismiss = "Cancel", onDismiss = { cityFor = null }) {
                        BlazeTextField(city, { city = it }, "e.g. Lagos, Lima, Manila")
                    }
                }
            }
            askChange?.let { w ->
                var t by remember { mutableStateOf("") }
                androidx.compose.ui.window.Dialog(onDismissRequest = { askChange = null }) {
                    BlazeDialogCard("Change \u201c${w.title}\u201d", "Say it in plain words, e.g. \u201cshow Chelsea instead\u201d or \u201cmake it euros\u201d.", confirm = "Ask Blaze", onConfirm = {
                        if (t.isNotBlank()) { onAsk("Change my Home widget \u201c${w.title}\u201d (id ${w.id}): ${t.trim()}"); toast = "Asked Blaze. The answer is in Chat." }
                        askChange = null
                    }, dismiss = "Cancel", onDismiss = { askChange = null }) {
                        BlazeTextField(t, { t = it }, "What should change?", singleLine = false)
                    }
                }
            }
        }
    }
}

/** The Customise sheet's "Pick image" button with its own photo picker (only in the live app, not in screenshots). */
@Composable
private fun BackgroundPicker(onPicked: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { saveBackground(ctx, uri) }
            if (ok) { HomeBoard.background = "image"; onPicked() }
        }
    }
    KitButton("Pick image", Btn.LINE) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

private fun bgFile(ctx: Context) = java.io.File(ctx.filesDir, "home_bg.jpg")

/** Copies the picked picture into app storage, scaled down to ≤ 1080 px on its long side (small and fast on any phone). */
private fun saveBackground(ctx: Context, uri: Uri): Boolean = try {
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 1080) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
    if (bmp == null) false else {
        bgFile(ctx).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
        bmp.recycle(); true
    }
} catch (_: Throwable) { false }

@Composable
private fun rememberHomeBackground(look: Int): ImageBitmap? {
    val ctx = LocalContext.current
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(look) {
        img = if (HomeBoard.background != "image") null else withContext(Dispatchers.IO) {
            try {
                val f = bgFile(ctx)
                if (!f.isFile) null else android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 })?.asImageBitmap()
            } catch (_: Throwable) { null }
        }
    }
    return img
}

private fun Modifier.homeBackground(c: BlazeColors, kind: String, idx: Int, @Suppress("UNUSED_PARAMETER") look: Int): Modifier = when {
    // v0.31: the default look: the dark glass ground (gradient + two soft grey glows), static, drawn once.
    HomeGlass.isGlass(kind, idx) -> background(GlassTokens.bg).glassBackground(true)
    else -> homeCustomBackground(c, kind, idx)
}

private fun Modifier.homeCustomBackground(c: BlazeColors, kind: String, idx: Int): Modifier = when (kind) {
    "image" -> background(GlassTokens.bg).glassBackground(true)   // the photo draws over the glass ground while it loads
    "gradient" -> HomeLook.gradients.getOrNull(idx)?.let { (l, d) -> background(Brush.verticalGradient(if (c.dark) d else l)) } ?: pageBackdrop(c)
    else -> HomeLook.solids.getOrNull(idx)?.let { (l, d) -> val col = if (c.dark) d else l; if (col == Color.Unspecified) background(GlassTokens.bg).glassBackground(true) else background(col) } ?: pageBackdrop(c)
}

fun openHomeLink(ctx: Context, url: String) {
    try {
        when {
            url.startsWith("app:") -> ctx.packageManager.getLaunchIntentForPackage(url.removePrefix("app:"))?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            url.startsWith("calendar:") -> {
                val t = url.removePrefix("calendar:").toLongOrNull() ?: return
                val u = android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { android.content.ContentUris.appendId(it, t) }.build()
                ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            url.startsWith("https://") || url.startsWith("http://") -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } catch (_: Exception) { }
}

private fun openUsageAccess(ctx: Context) {
    try { ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) { try { ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } }
}

// ---------------------------------------------------------------- the board (stateless)

private const val PAGE = 12

/**
 * The scrolling board: header (title, "Updated … ago", Customise, Create), then one item per category stack. Items are
 * lazy (only cards on screen are composed and fetched) and paged in [PAGE] at a time as you scroll (infinite scroll).
 * Drag a card's grip to reorder whole stacks; the long-press menu moves single widgets.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeBoardView(
    stacks: List<WidgetStack>, data: Map<String, WidgetData>, loading: Set<String>, offline: Boolean, now: Long, stackState: StackState,
    stamp: String, onToggle: (String) -> Unit, onMenu: (Widget) -> Unit, onLine: (Widget, WidgetLine) -> Unit, onOpen: (Widget, WidgetData) -> Unit,
    onAction: (Widget, String) -> Unit, onVisible: (String) -> Unit, onReorder: (List<String>) -> Unit, onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit, onCustomise: () -> Unit, onCreate: () -> Unit, listState: LazyListState = rememberLazyListState(),
) {
    val c = LocalBlaze.current.mono
    val order = remember { mutableStateListOf<WidgetStack>() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(stacks) { if (dragKey == null) { order.clear(); order.addAll(stacks) } }
    if (order.isEmpty() && stacks.isNotEmpty() && dragKey == null) { order.addAll(stacks) }
    var shown by remember { mutableIntStateOf(PAGE) }
    LaunchedEffect(listState, order.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last -> if (last >= shown - 1 && shown < order.size) shown += PAGE }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "head") { BoardHeader(stamp, offline, onCustomise, onCreate) }
        if (order.isEmpty()) item(key = "empty") {
            EmptyState("Your board is empty", "Add a widget in plain words, like \u201ctrack Bitcoin\u201d or \u201cArsenal scores\u201d.", action = "Create a widget", onAction = onCreate)
        }
        itemsIndexed(order.take(shown), key = { _, s -> s.key }) { i, s ->
            val dragging = dragKey == s.key
            Box(Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
                translationY = if (dragging) dragY else 0f
                if (dragging) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12f }
            }.let { if (dragging || !MotionTokens.enabled) it else it.animateItemPlacement(MotionTokens.drag()) }) {
                val grip = Modifier.pointerInput(s.key) {
                    detectVerticalDragGestures(
                        onDragStart = { dragKey = s.key; dragY = 0f },
                        onVerticalDrag = { ch, dy ->
                            ch.consume(); dragY += dy
                            val info = listState.layoutInfo.visibleItemsInfo
                            val me = info.firstOrNull { it.key == s.key } ?: return@detectVerticalDragGestures
                            val mid = me.offset + dragY + me.size / 2f
                            val target = info.firstOrNull { it.key != s.key && it.key != "head" && it.key != "empty" && mid.toInt() in it.offset..(it.offset + it.size) }
                            if (target != null) {
                                val from = order.indexOfFirst { it.key == s.key }; val to = order.indexOfFirst { it.key == target.key }
                                if (from >= 0 && to >= 0) { order.add(to, order.removeAt(from)); dragY += (me.offset - target.offset).toFloat() }
                            }
                        },
                        onDragEnd = { dragKey = null; dragY = 0f; onReorder(order.flatMap { st -> st.widgets.map { it.id } }) },
                        onDragCancel = { dragKey = null; dragY = 0f })
                }
                StackItem(s, stackState.isOpen(s.key), data, loading, offline, now, grip,
                    onToggle = { onToggle(s.key) }, onMenu = onMenu, onLine = onLine, onOpen = onOpen, onAction = onAction, onVisible = onVisible,
                    canUp = i > 0, canDown = i < order.size - 1, onMove = onMove, onRemove = onRemove)
            }
        }
        if (shown < order.size) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Label("Loading more\u2026", size = 11.sp) }
        }
    }
}

@Composable
private fun BoardHeader(stamp: String, offline: Boolean, onCustomise: () -> Unit, onCreate: () -> Unit) {
    val c = LocalBlaze.current.mono
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Home", modifier = Modifier.semantics { heading() },
                    style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = c.ink))
                Text(if (offline) "Offline \u00b7 showing saved cards" else stamp, maxLines = 2,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, color = c.sub))
            }
            // v0.45.8: the shared theme switcher (the same options as every screen).
            ThemeSwitch(Modifier.size(48.dp))
            RoundIcon(Icons.Rounded.Tune, "Customise board", onCustomise)
            Spacer(Modifier.width(6.dp))
            RoundIcon(Icons.Rounded.Add, "Create a widget", onCreate, filled = true)
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit, filled: Boolean = false, size: Int = 40) {
    val c = LocalBlaze.current.mono
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = desc, role = Role.Button, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(size.dp).homeSurface(c, LocalGlass.current, CircleShape, if (filled) GlassIntensity.High else GlassIntensity.Low, shadow = filled),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun HomeToast(text: String) {
    Box(Modifier.widthIn(max = 340.dp).clip(GlassTokens.pillShape).background(DialogColors.surface, GlassTokens.pillShape)
        .glass(GlassTokens.pillShape, GlassIntensity.High).padding(horizontal = 18.dp, vertical = 11.dp)) {
        Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = DialogColors.title))
    }
}

/** Spring for the stack (none with Remove animations). */
private fun <T> stackSpring() = if (MotionTokens.enabled) spring<T>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow) else androidx.compose.animation.core.snap()

/**
 * One category stack. Collapsed: the main card with up to two cards peeking underneath (offset, scaled, dimmed) and a
 * "+N" chip; a tap expands it. Expanded: the other cards slide out below; tapping the main card or "Show less" folds it.
 */
@Composable
fun StackItem(
    s: WidgetStack, open: Boolean, data: Map<String, WidgetData>, loading: Set<String>, offline: Boolean, now: Long, grip: Modifier,
    onToggle: () -> Unit, onMenu: (Widget) -> Unit, onLine: (Widget, WidgetLine) -> Unit, onOpen: (Widget, WidgetData) -> Unit,
    onAction: (Widget, String) -> Unit, onVisible: (String) -> Unit, canUp: Boolean = true, canDown: Boolean = true,
    onMove: (String, Int) -> Unit = { _, _ -> }, onRemove: (String) -> Unit = {},
) {
    val c = LocalBlaze.current.mono
    LaunchedEffect(s.main.id) { onVisible(s.main.id) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            if (s.stacked && !open) {
                // v0.45.8: each ghost card is clipped to the strip that shows below the card in front of it, so its edges
                // no longer show through the see-through main card (the doubled outlines on the news card).
                val peeks = StackState.peeks(s.widgets.size)
                val strips = StackState.strips(s.widgets.size)
                for (i in peeks.indices.reversed()) {
                    val p = peeks[i]; val st = strips[i]
                    Box(Modifier.matchParentSize().graphicsLayer {
                        translationY = st.shiftDp.dp.toPx(); scaleX = p.scale; alpha = p.alpha
                    }.drawWithContent {
                        clipRect(top = (size.height - st.visibleDp.dp.toPx()).coerceAtLeast(0f)) { this@drawWithContent.drawContent() }
                    }.homeSurface(c, LocalGlass.current, GlassTokens.cardShape, GlassIntensity.Low, shadow = false))
                }
            }
            WidgetCard(s.main, data[s.main.id], s.main.id in loading, offline, now, grip,
                stackCount = if (s.stacked) s.widgets.size else 0, stackOpen = open,
                onClick = { if (s.stacked) onToggle() else data[s.main.id]?.let { onOpen(s.main, it) } },
                onMenu = { onMenu(s.main) }, onLine = { onLine(s.main, it) }, onAction = { onAction(s.main, it) },
                canUp = canUp, canDown = canDown, onMove = onMove, onRemove = onRemove)
        }
        if (s.stacked && !open) Spacer(Modifier.height(26.dp))
        AnimatedVisibility(s.stacked && open,
            enter = if (MotionTokens.enabled) expandVertically(stackSpring()) + fadeIn(stackSpring()) + slideInVertically(stackSpring()) { -it / 4 } else EnterTransition.None,
            exit = if (MotionTokens.enabled) shrinkVertically(stackSpring()) + fadeOut(stackSpring()) + slideOutVertically(stackSpring()) { -it / 4 } else ExitTransition.None) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                s.rest.forEach { w ->
                    LaunchedEffect(w.id) { onVisible(w.id) }
                    WidgetCard(w, data[w.id], w.id in loading, offline, now, Modifier, onClick = { data[w.id]?.let { onOpen(w, it) } },
                        onMenu = { onMenu(w) }, onLine = { onLine(w, it) }, onAction = { onAction(w, it) }, onMove = onMove, onRemove = onRemove)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.homeSurface(c, LocalGlass.current, GlassTokens.pillShape, GlassIntensity.Low, shadow = false)
                        .clickable(onClickLabel = "Fold the ${s.category} stack", onClick = onToggle).heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.KeyboardArrowUp, null, tint = c.ink, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Show less", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.ink))
                        }
                    }
                }
            }
        }
    }
}

/** A type's small mark colour (on the accent). */
/** v0.29.3: real rounded icons (the 0.29.0 glyphs looked like keyboard emoji). */
fun typeIcon(type: String): androidx.compose.ui.graphics.vector.ImageVector = when (type) {
    WidgetTypes.WEATHER -> Icons.Rounded.WbSunny; WidgetTypes.NEWS -> Icons.Rounded.Newspaper; WidgetTypes.CREATORS -> Icons.Rounded.SmartDisplay
    WidgetTypes.SPORTS -> Icons.Rounded.SportsSoccer; WidgetTypes.PRICES -> Icons.Rounded.ShowChart; WidgetTypes.CALENDAR -> Icons.Rounded.Event
    WidgetTypes.ROUTINES -> Icons.Rounded.Autorenew; WidgetTypes.DEADLINES -> Icons.Rounded.Flag; WidgetTypes.GITHUB -> Icons.Rounded.Code
    WidgetTypes.APPS -> Icons.Rounded.Apps; WidgetTypes.INTO -> Icons.Rounded.AutoAwesome; else -> Icons.Rounded.Edit
}

/** The icon tile at the top of a card: a small rounded square in a soft accent gradient with a crisp white icon. */
@Composable
fun TypeTile(type: String, size: Int = 28) {
    val c = LocalBlaze.current.mono
    Box(Modifier.size(size.dp).glass(RoundedCornerShape((size * 0.36f).dp), GlassIntensity.High, shadow = false), contentAlignment = Alignment.Center) {
        Icon(typeIcon(type), contentDescription = null, tint = c.ink, modifier = Modifier.size((size * 0.58f).dp))
    }
}

/** One widget card. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WidgetCard(
    w: Widget, d: WidgetData?, loading: Boolean, offline: Boolean, now: Long, grip: Modifier,
    stackCount: Int = 0, stackOpen: Boolean = false,
    onClick: () -> Unit, onMenu: () -> Unit, onLine: (WidgetLine) -> Unit, onAction: (String) -> Unit,
    canUp: Boolean = true, canDown: Boolean = true, onMove: (String, Int) -> Unit = { _, _ -> }, onRemove: (String) -> Unit = {},
) {
    val c = LocalBlaze.current.mono
    val size = WidgetSize.norm(w.size)
    val stale = d != null && Ago.stale(d.fetched, now, offline, d.error)
    val summary = buildString {
        append(w.title); append(", "); append(WidgetTypes.label(w.type)); append(" widget")
        if (d != null) { if (d.value.isNotBlank()) append(", ").append(d.value); if (d.change.isNotBlank()) append(", ").append(d.change); if (d.error.isNotBlank()) append(". ").append(d.error) }
        if (stackCount > 1) append(". Stack of $stackCount, ").append(if (stackOpen) "expanded" else "collapsed")
    }
    Column(Modifier.fillMaxWidth().homeSurface(c, LocalGlass.current, GlassTokens.cardShape)
        .combinedClickable(onClickLabel = if (stackCount > 1) (if (stackOpen) "Fold the stack" else "Expand the stack") else "Open",
            onLongClickLabel = "Widget options", onClick = onClick, onLongClick = onMenu)
        .semantics {
            contentDescription = summary
            customActions = listOfNotNull(
                CustomAccessibilityAction("Widget options") { onMenu(); true },
                if (canUp) CustomAccessibilityAction("Move up") { onMove(w.id, -1); true } else null,
                if (canDown) CustomAccessibilityAction("Move down") { onMove(w.id, 1); true } else null,
                CustomAccessibilityAction("Remove") { onRemove(w.id); true })
        }
        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeTile(w.type)
            Spacer(Modifier.width(10.dp))
            Text(w.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = c.ink))
            if (stackCount > 1 && !stackOpen) { Tag("+${stackCount - 1}", on = true); Spacer(Modifier.width(2.dp)) }
            if (stale) { Tag("Stale"); Spacer(Modifier.width(2.dp)) }
            Box(grip.size(36.dp).clip(CircleShape).semantics { contentDescription = "Drag to reorder" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DragIndicator, null, tint = c.weak, modifier = Modifier.size(18.dp))
            }
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = "Widget options", onClick = onMenu).semantics { contentDescription = "Options for ${w.title}" },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreHoriz, null, tint = c.sub, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.padding(end = 8.dp, top = 4.dp)) {
            when {
                d == null || (d.empty && d.error.isBlank() && loading) -> {
                    Box(Modifier.fillMaxWidth(0.55f).height(22.dp).shimmer(c.faint, c.hair))
                    if (size != WidgetSize.S) { Spacer(Modifier.height(8.dp)); Box(Modifier.fillMaxWidth(0.85f).height(12.dp).shimmer(c.faint, c.hair)) }
                    if (d == null && !loading) Label("Not updated yet", size = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
                else -> WidgetBody(w, d, size, onLine)
            }
            if (d != null && d.error.isNotBlank()) {
                Text(d.error, modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.sub))
            }
            if (d != null && d.action.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                val label = when {
                    d.action == "location" -> "Allow location"
                    d.action == "city" -> "Type a city"
                    d.action == "calendar" -> "Allow calendar"
                    d.action == "usage" -> "Open Usage access"
                    d.action == "interests" -> "Pick my interests"
                    d.action.startsWith("ask:") -> "Ask Blaze"
                    else -> ""
                }
                if (label.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KitButton(label, Btn.LINE) { onAction(d.action) }
                    if (d.action == "location") KitButton("Type a city", Btn.GHOST) { onAction("city") }
                }
            }
            if (d != null && d.fetched > 0L && size != WidgetSize.S) {
                Text(if (loading) "Updating\u2026" else Ago.of(d.fetched, now), modifier = Modifier.padding(top = 8.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.weak))
            } else if (loading && d != null && !d.empty) Text("Updating\u2026", modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = c.weak))
        }
    }
}

@Composable
private fun WidgetBody(w: Widget, d: WidgetData, size: String, onLine: (WidgetLine) -> Unit) {
    val c = LocalBlaze.current.mono
    if (d.value.isNotBlank() || d.headline.isNotBlank()) {
        if (d.value.isNotBlank()) {
            Text(d.value, maxLines = if (size == WidgetSize.S) 1 else 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = if (w.type == WidgetTypes.WEATHER || w.type == WidgetTypes.PRICES) 30.sp else 20.sp,
                    lineHeight = if (w.type == WidgetTypes.WEATHER || w.type == WidgetTypes.PRICES) 34.sp else 25.sp, color = c.ink))
            if (d.headline.isNotBlank() && d.headline != w.title) Text(d.headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, color = c.sub))
        } else if (d.headline != w.title && size != WidgetSize.S) {
            Text(d.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
        }
    }
    if (d.change.isNotBlank()) {
        val up = d.change.startsWith("\u25B2"); val down = d.change.startsWith("\u25BC")
        Text(d.change, maxLines = if (size == WidgetSize.L) 6 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 17.sp,
                color = if (up) (if (c.dark) GlassTokens.success else Color(0xFF4F6B57)) else if (down) (if (c.dark) GlassTokens.error else Color(0xFF8A5252)) else c.sub))
    }
    val n = WidgetSize.lines(size).let { if (size == WidgetSize.S) 0 else it }
    if (n > 0 && d.lines.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        val hero = d.lines.firstOrNull()?.takeIf { it.image.isNotBlank() && size != WidgetSize.S }
        if (hero != null) {
            val img = rememberHomeImage(hero.image)
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).glass(GlassTokens.tileShape, GlassIntensity.Low, shadow = false)
                .clickable(onClickLabel = "Preview ${hero.title}") { onLine(hero) }) {
                if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().glassSheen(c))
            }
            Spacer(Modifier.height(4.dp))
        }
        d.lines.take(n).forEachIndexed { i, l ->
            Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).let { if (l.url.isNotBlank()) it.clickable(onClickLabel = "Open ${l.title}") { onLine(l) } else it }
                .let { if (i > 0) it.hairlineTopInset(c.hair, 0.dp) else it }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.5.sp, lineHeight = 18.sp, color = c.ink))
                    if (l.sub.isNotBlank()) Text(l.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.5.sp, color = c.sub))
                }
                if (l.image.isNotBlank() && l !== hero) {
                    Spacer(Modifier.width(10.dp))
                    val img = rememberHomeImage(l.image)
                    Box(Modifier.size(width = 68.dp, height = 50.dp).glass(RoundedCornerShape(12.dp), GlassIntensity.Low, shadow = false)) {
                        if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                if (l.note.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    if (l.note == "New" || l.note == "Next") Tag(l.note, on = true)
                    else Text(l.note, maxLines = 2, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, color = c.body))
                }
            }
        }
        if (d.lines.size > n) Label("+${d.lines.size - n} more", size = 10.5.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

// ---------------------------------------------------------------- long-press menu

/** Move up / down, Resize S M L, Hide, Remove, Refresh, Ask Blaze to change. */
@Composable
fun WidgetMenuCard(w: Widget, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.widthIn(min = 260.dp, max = 340.dp).clip(shape).background(DialogColors.surface, shape).glass(shape, GlassIntensity.High).padding(vertical = 8.dp)) {
        Text(w.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 6.dp).semantics { heading() },
            style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = DialogColors.title))
        @Composable fun item(label: String, id: String, warm: Boolean = false) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(BlazeShapes.small).clickable(onClickLabel = label) { onPick(id) }
                .heightIn(min = 46.dp).padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                Text(label, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.5.sp, color = if (warm) DialogColors.primaryWarm else DialogColors.title))
            }
        }
        item("Move up", "up"); item("Move down", "down")
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Resize", modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.5.sp, color = DialogColors.title))
            listOf("S", "M", "L").forEach { s ->
                val on = WidgetSize.norm(w.size) == s
                Box(Modifier.padding(start = 6.dp).size(40.dp).glass(CircleShape, if (on) GlassIntensity.High else GlassIntensity.Low, shadow = false)
                    .then(if (on) Modifier.background(GlassTokens.accent, CircleShape) else Modifier).clickable(onClickLabel = WidgetSize.label(s)) { onPick(s) }
                    .semantics { contentDescription = WidgetSize.label(s); selected = on }, contentAlignment = Alignment.Center) {
                    Text(s, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (on) GlassTokens.onAccent else DialogColors.title))
                }
            }
        }
        item("Refresh", "refresh"); item("Ask Blaze to change", "ask"); item("Hide", "hide"); item("Remove", "remove", warm = true)
    }
}

// ---------------------------------------------------------------- customise sheet

@Composable
fun CustomiseSheet(
    widgets: List<Widget>, background: String, bgColor: Int, accent: String, wifiOnly: Boolean, hours: Int,
    onBackground: (String) -> Unit, onBgColor: (Int) -> Unit, onPickImage: () -> Unit, onAccent: (String) -> Unit,
    onWifiOnly: (Boolean) -> Unit, onHours: (Int) -> Unit, onSize: (String, String) -> Unit, onHidden: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit, onClose: () -> Unit, imagePicker: (@Composable () -> Unit)? = null,
) {
    val c = LocalBlaze.current.mono
    var bg by remember(background) { mutableStateOf(background) }
    var bgc by remember(bgColor) { mutableIntStateOf(bgColor) }
    var acc by remember(accent) { mutableStateOf(accent) }
    var wifi by remember(wifiOnly) { mutableStateOf(wifiOnly) }
    var hrs by remember(hours) { mutableIntStateOf(hours) }
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).homeSheet(c)
        .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Customise", modifier = Modifier.weight(1f).semantics { heading() }, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = c.ink))
            KitButton("Done", Btn.LINE, onClick = onClose)
        }
        GroupHead("Background")
        Segmented(listOf("glass" to "Glass", "plain" to "Solid", "gradient" to "Gradient", "image" to "Image"), if (bg == "sky" || bg.isBlank()) "glass" else bg) { bg = it; onBackground(it) }
        Spacer(Modifier.height(10.dp))
        when (bg) {
            "glass", "sky", "" -> Label("Dark frosted glass, black and white. Pick Image to use your own photo.", size = 11.sp)
            "image" -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (imagePicker != null) imagePicker() else KitButton("Pick image", Btn.LINE, onClick = onPickImage)
                Spacer(Modifier.width(10.dp)); Label("Your photo behind glass cards.", size = 11.sp)
            }
            "gradient" -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeLook.gradients.forEachIndexed { i, (l, d) -> Swatch(Brush.linearGradient(if (c.dark) d else l), bgc == i, "Gradient ${i + 1}") { bgc = i; onBgColor(i) } }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeLook.solids.forEachIndexed { i, (l, d) -> val col = if (c.dark) d else l
                    Swatch(androidx.compose.ui.graphics.SolidColor(if (col == Color.Unspecified) c.paper else col), bgc == i, if (i == 0) "Default" else "Colour $i") { bgc = i; onBgColor(i) } }
            }
        }
        GroupHead("Accent", modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeLook.accents.forEach { a -> Swatch(androidx.compose.ui.graphics.SolidColor(if (c.dark) a.dark else a.light), acc == a.id, a.name) { acc = a.id; onAccent(a.id) } }
        }
        GroupHead("Refresh", "Every few hours in the background, never more than once an hour.", modifier = Modifier.padding(top = 12.dp))
        Segmented(listOf("1" to "1 h", "3" to "3 h", "6" to "6 h", "12" to "12 h"), hrs.toString()) { hrs = it.toInt(); onHours(it.toInt()) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi only", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                Text("Saves mobile data. Pull down on the board to refresh anytime.", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
            Box(Modifier.semantics { contentDescription = "Wi-Fi only"; stateDescription = if (wifi) "On" else "Off" }) { KitSwitch(wifi) { wifi = !wifi; onWifiOnly(wifi) } }
        }
        GroupHead("Widgets", "Order, size and what's shown.", modifier = Modifier.padding(top = 12.dp))
        if (widgets.isEmpty()) Label("No widgets yet.", size = 12.sp)
        widgets.forEachIndexed { i, w ->
            Row(Modifier.fillMaxWidth().let { if (i > 0) it.hairlineTopInset(c.hair, 0.dp) else it }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (w.hidden) c.weak else c.ink))
                    Text(w.category.ifBlank { WidgetTypes.label(w.type) }, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.5.sp, color = c.sub))
                }
                SizeChips(w.size) { onSize(w.id, it) }
                SmallIcon(Icons.Rounded.KeyboardArrowUp, "Move ${w.title} up", enabled = i > 0) { onMove(w.id, -1) }
                SmallIcon(Icons.Rounded.KeyboardArrowDown, "Move ${w.title} down", enabled = i < widgets.size - 1) { onMove(w.id, 1) }
                Box(Modifier.semantics { contentDescription = "Show ${w.title}"; stateDescription = if (w.hidden) "Hidden" else "Shown" }) { KitSwitch(!w.hidden) { onHidden(w.id, !w.hidden) } }
            }
        }
    }
}

@Composable
private fun SizeChips(size: String, onPick: (String) -> Unit) {
    val c = LocalBlaze.current.mono
    Row {
        listOf("S", "M", "L").forEach { s ->
            val on = WidgetSize.norm(size) == s
            Box(Modifier.size(32.dp).clip(CircleShape).then(if (on) Modifier.background(c.ink, CircleShape) else Modifier.glass(CircleShape, GlassIntensity.Low, shadow = false)).clickable(onClickLabel = WidgetSize.label(s)) { onPick(s) }
                .semantics { contentDescription = WidgetSize.label(s); selected = on }, contentAlignment = Alignment.Center) {
                Text(s, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = if (on) c.paper else c.sub))
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClickLabel = desc, onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) c.ink else c.weak, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Swatch(brush: Brush, on: Boolean, desc: String, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.size(40.dp).clip(CircleShape).border(if (on) 2.dp else 1.dp, if (on) c.ink else c.hair2, CircleShape).padding(4.dp).clip(CircleShape).background(brush)
        .clickable(onClickLabel = desc, onClick = onClick).semantics { contentDescription = desc; selected = on })
}

// ---------------------------------------------------------------- create a widget

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateWidgetSheet(onAdd: (String) -> Unit, onClose: () -> Unit, initial: String = "") {
    val c = LocalBlaze.current.mono
    var t by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().homeSheet(c).navigationBarsPadding()
        .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp)) {
        Text("Create a widget", modifier = Modifier.semantics { heading() }, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = c.ink))
        Text("Say what you want to see, in plain words.", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        Spacer(Modifier.height(12.dp))
        BlazeTextField(t, { t = it }, "e.g. show me Arsenal scores")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Show me Arsenal scores", "Track Bitcoin price", "New videos from MKBHD", "Weather in Tokyo", "USD to EUR", "Tech news").forEach { ex ->
                HomeChip(ex, false) { t = ex }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            KitButton("Cancel", Btn.GHOST, onClick = onClose)
            KitButton("Add to Home", Btn.ACCENT, enabled = t.isNotBlank()) { onAdd(t.trim()) }
        }
    }
}

@Composable
fun HomeChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.then(if (on) Modifier.clip(GlassTokens.pillShape).background(c.ink).border(1.dp, c.ink, GlassTokens.pillShape) else Modifier.homeSurface(c, LocalGlass.current, GlassTokens.pillShape, GlassIntensity.Low, shadow = false))
        .clickable(onClickLabel = label, role = Role.Checkbox, onClick = onClick).semantics { selected = on }
        .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, color = if (on) c.paper else c.ink))
    }
}

// ---------------------------------------------------------------- interest picker (first open)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterestPicker(onDone: (Set<String>, Set<String>, String) -> Unit, onSkip: () -> Unit, initialChosen: Set<String> = emptySet(), initialSubs: Set<String> = emptySet()) {
    val c = LocalBlaze.current.mono
    var chosen by remember { mutableStateOf(initialChosen) }
    var subs by remember { mutableStateOf(initialSubs) }
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            PageHead("Your Home board", "What are you into?", "Pick a few and I'll fill your board with live cards. You can change everything later.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Interests.catalogue.forEach { ch ->
                    HomeChip(ch.label, ch.id in chosen) {
                        chosen = if (ch.id in chosen) chosen - ch.id else chosen + ch.id
                        if (ch.id !in chosen) subs = subs - ch.subs.toSet()
                    }
                }
            }
            Interests.catalogue.filter { it.id in chosen && it.subs.isNotEmpty() }.forEach { ch ->
                GroupHead(ch.label, modifier = Modifier.padding(top = 14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ch.subs.forEach { s -> HomeChip(s, s in subs) { subs = if (s in subs) subs - s else subs + s } }
                }
            }
            GroupHead("Anything else?", "Optional. A team, a creator, a coin, a topic\u2026", modifier = Modifier.padding(top = 14.dp))
            BlazeTextField(typed, { typed = it }, "e.g. Kaizer Chiefs, Veritasium, Formula 1")
            Spacer(Modifier.height(16.dp))
        }
        Row(Modifier.fillMaxWidth().then(LocalGlass.current?.let { Modifier.background(it.fill) } ?: Modifier.glass(GlassTokens.sheetShape, GlassIntensity.High, shadow = false)).padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            KitButton("Skip", Btn.GHOST, onClick = onSkip)
            Spacer(Modifier.weight(1f))
            KitButton("Set up my board", Btn.ACCENT, enabled = chosen.isNotEmpty() || subs.isNotEmpty() || typed.isNotBlank()) { onDone(chosen, subs, typed) }
        }
    }
}

/** v0.31: Home's bottom sheets: a near-opaque dark base (readable over the board) under GlassKit's high frost and a grab-free rim. */
internal fun Modifier.homeSheet(c: BlazeColors): Modifier =
    clip(GlassTokens.sheetShape).background(if (c.dark) DialogColors.surface else Color(0xF7FAFAFA), GlassTokens.sheetShape)
        .glass(GlassTokens.sheetShape, GlassIntensity.High, shadow = false)
