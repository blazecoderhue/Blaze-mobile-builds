package com.blaze.assistant.blazeos

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.blaze.assistant.ui.MascotMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/*
 * v0.33.2 BlazeOS home screen (design B): pages of frosted widgets then squircle icons, a last page that is the App
 * Library, page dots and a frosted dock. Long-press anything to edit: drag icons to rearrange, − to take an icon off
 * the home screen (it stays in the App Library) or a widget away, + Widget to add one back, Done to finish.
 */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OsHomeScreen(onExit: () -> Unit) {
    val look = LocalOsLook.current
    val prefs by BlazeOs.prefs.collectAsState()
    val reg by BlazeOs.registry.collectAsState()
    val apps = remember(reg) { BlazeOs.apps(reg) }
    val byId = remember(apps) { apps.associateBy { it.id } }
    val ids = remember(apps) { apps.map { it.id }.toHashSet() }
    val dock = remember(prefs.dock, prefs.dockOn, apps) { if (!prefs.dockOn) emptyList() else prefs.dock?.let { OsDock.normalize(it, ids) } ?: OsDock.defaults(apps) }
    val order = remember(prefs.order, prefs.removed, apps, dock) { OsHome.order(prefs.order, apps, prefs.removed, dock) }
    val pages = remember(prefs.widgets, order, prefs.grid) { OsPages.pack(OsWidgets.rows(prefs.widgets), order, prefs.grid) }
    val pager = rememberPagerState { pages.size + 1 }
    var editing by remember { mutableStateOf(false) }
    var addWidget by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    fun open(a: OsApp) { if (!editing) scope.launch { OsLaunch.open(ctx, a) } }
    androidx.activity.compose.BackHandler(enabled = editing || pager.currentPage != 0) {
        if (editing) editing = false else scope.launch { pager.animateScrollToPage(0) }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        Box(Modifier.fillMaxWidth().height(40.dp)) {
            if (editing) Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                EditChip("+ Widget") { addWidget = true }
                Spacer(Modifier.weight(1f))
                EditChip("Done", primary = true) { editing = false }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 0, key = { it }) { i ->
            if (i < pages.size) HomePage(pages[i], i, pages, order, byId, editing, onEdit = { editing = true }, onOpen = ::open)
            else AppLibrary(apps, dock, onOpen = ::open)
        }
        // Page dots (the last one is the App Library).
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
            for (i in 0..pages.size) Box(Modifier.padding(horizontal = 4.dp).size(if (i == pages.size) 7.dp else 7.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = if (i == pager.currentPage) 0.95f else 0.38f)))
        }
        if (dock.isNotEmpty() || editing) Dock(dock, byId, editing, onOpen = ::open)
        Spacer(Modifier.height(30.dp))
    }
    if (addWidget) AddWidgetSheet(prefs.widgets, onClose = { addWidget = false })
    @Suppress("UNUSED_VARIABLE") val unused = onExit
}

@Composable
private fun EditChip(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val look = LocalOsLook.current
    Box(Modifier.clip(CircleShape).background(if (primary) look.accent else Color.White.copy(alpha = 0.22f)).clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 7.dp)) { Text(text, style = osText(look, 14f, FontWeight.SemiBold, Color.White)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomePage(page: OsPage, pageIndex: Int, pages: List<OsPage>, order: List<String>, byId: Map<String, OsApp>, editing: Boolean,
                     onEdit: () -> Unit, onOpen: (OsApp) -> Unit) {
    val prefs by BlazeOs.prefs.collectAsState()
    val grid = prefs.grid
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        val cellW = maxWidth / grid.cols
        val cellH = maxHeight / grid.rows
        val density = LocalDensity.current
        val cwPx = with(density) { cellW.toPx() }; val chPx = with(density) { cellH.toPx() }
        Column(Modifier.fillMaxSize()) {
            for (row in page.widgetRows) {
                Row(Modifier.fillMaxWidth().height(cellH * OsWidgets.ROW_CELLS).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (k in row) OsWidgetView(k, Modifier.weight(1f).fillMaxSize(), editing, onEdit)
                }
            }
            val rows = page.apps.chunked(grid.cols)
            for ((r, line) in rows.withIndex()) {
                Row(Modifier.fillMaxWidth().height(cellH)) {
                    for ((c, id) in line.withIndex()) {
                        val a = byId[id] ?: continue
                        val slot = r * grid.cols + c
                        HomeIcon(a, Modifier.width(cellW).height(cellH), editing, onEdit, onOpen,
                            onDrop = { dx, dy ->
                                val target = OsPages.flatIndex(pages, pageIndex, OsPages.dropSlot(slot, (dx / cwPx).roundToInt(), (dy / chPx).roundToInt(), grid.cols, page.apps.size))
                                val moved = OsHome.move(order, id, target)
                                BlazeOs.update { it.copy(order = moved) }
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeIcon(a: OsApp, modifier: Modifier, editing: Boolean, onEdit: () -> Unit, onOpen: (OsApp) -> Unit, onDrop: (Float, Float) -> Unit) {
    val look = LocalOsLook.current
    val prefs by BlazeOs.prefs.collectAsState()
    var drag by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val wiggle = if (editing && look.anim == AnimLevel.FULL && !dragging) {
        val t = rememberInfiniteTransition(label = "wiggle")
        t.animateFloat(-2.2f, 2.2f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "r").value
    } else 0f
    BoxWithConstraints(modifier
        .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
        .graphicsLayer { rotationZ = wiggle; if (dragging) { scaleX = 1.12f; scaleY = 1.12f } }
        .pointerInput(a.id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { onEdit(); dragging = true },
                onDrag = { ch, d -> ch.consume(); drag += d },
                onDragEnd = { val d = drag; drag = Offset.Zero; dragging = false; onDrop(d.x, d.y) },
                onDragCancel = { drag = Offset.Zero; dragging = false })
        }
        .clickable(enabled = !editing) { onOpen(a) }, contentAlignment = Alignment.TopCenter) {
        val size = minOf(maxWidth * 0.66f, maxHeight * 0.62f, 64.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 6.dp)) {
            Box {
                OsAppIcon(a, size)
                if (editing) MinusBadge(Modifier.align(Alignment.TopStart).offset((-6).dp, (-6).dp)) {
                    val (o, r) = OsHome.remove(BlazeOs.prefs.value.order, BlazeOs.prefs.value.removed, a.id)
                    BlazeOs.update { it.copy(order = o, removed = r) }
                }
            }
            if (prefs.labels) {
                Spacer(Modifier.height(5.dp))
                Text(a.label, style = osText(look, 11.5f, FontWeight.Medium, Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun MinusBadge(modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.size(22.dp).clip(CircleShape).background(Color(0xFF3A3A3C)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Remove, "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Dock(dock: List<String>, byId: Map<String, OsApp>, editing: Boolean, onOpen: (OsApp) -> Unit) {
    val look = LocalOsLook.current
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(88.dp).osGlass(look, RoundedCornerShape(32.dp)).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        if (dock.isEmpty()) Text("Dock is empty · add apps in the App Library", style = osText(look, 12f, color = look.sub))
        for (id in dock) {
            val a = byId[id] ?: continue
            Box {
                Box(Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = !editing) { onOpen(a) }) { OsAppIcon(a, 60.dp) }
                if (editing) MinusBadge(Modifier.align(Alignment.TopStart).offset((-6).dp, (-6).dp)) {
                    BlazeOs.update { p -> p.copy(dock = OsDock.toggle(dock, id)) }
                }
            }
        }
    }
}

// ---------------- App Library ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppLibrary(apps: List<OsApp>, dock: List<String>, onOpen: (OsApp) -> Unit) {
    val look = LocalOsLook.current
    var q by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<OsApp?>(null) }
    val prefs by BlazeOs.prefs.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().osGlass(look, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = look.sub, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("App Library", style = osText(look, 15f, color = look.sub))
                BasicTextField(q, { q = it }, singleLine = true, textStyle = osText(look, 15f), cursorBrush = SolidColor(look.accent), modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(10.dp))
        val list = remember(apps, q) { if (q.isBlank()) OsApps.groups(apps) else listOf("" to OsApps.search(apps, q)) }
        LazyColumn(Modifier.weight(1f)) {
            for ((letter, group) in list) {
                if (letter.isNotEmpty()) item(key = "h$letter") { Text(letter, style = osText(look, 13f, FontWeight.SemiBold, Color.White.copy(alpha = 0.8f)), modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp)) }
                items(group, key = { "a" + letter + it.id }) { a ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = { onOpen(a) }, onLongClick = { menu = a })
                        .padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OsAppIcon(a, 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, style = osText(look, 15f, FontWeight.Medium, Color.White))
                            val where = listOfNotNull(if (a.id in dock) "In the dock" else null, if (a.id in prefs.removed) "Not on the home screen" else null,
                                when (a.kind) { OsManifest.WEB -> "Web app"; OsManifest.WASM -> "Native app"; else -> null })
                            if (where.isNotEmpty()) Text(where.joinToString(" · "), style = osText(look, 12f, color = Color.White.copy(alpha = 0.6f)))
                        }
                    }
                }
            }
        }
    }
    menu?.let { a ->
        OsSheet(a.label, onClose = { menu = null }) {
            SheetRow(if (a.id in dock) "Remove from dock" else "Add to dock", enabled = a.id in dock || dock.size < OsDock.MAX) {
                BlazeOs.update { p -> p.copy(dock = OsDock.toggle(dock, a.id)) }; menu = null
            }
            if (a.id in prefs.removed) SheetRow("Add to home screen") {
                val (o, r) = OsHome.restore(BlazeOs.prefs.value.order, BlazeOs.prefs.value.removed, a.id)
                BlazeOs.update { it.copy(order = o, removed = r) }; menu = null
            }
            if (a.removable) SheetRow("Uninstall", danger = true) {
                val id = a.key
                menu = null
                OsLaunch.uninstall(id)
            }
        }
    }
}

// ---------------- sheets ----------------

@Composable
fun OsSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val look = LocalOsLook.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { onClose() }, contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.padding(10.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(look.panel).clickable(enabled = false) { }
            .padding(vertical = 10.dp)) {
            Text(title, style = osText(look, 13f, FontWeight.SemiBold, look.sub), modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            content()
            SheetRow("Cancel") { onClose() }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun SheetRow(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val look = LocalOsLook.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick).padding(14.dp)) {
        Text(text, style = osText(look, 16f, FontWeight.Medium, when { !enabled -> look.sub; danger -> Color(0xFFFF453A); else -> look.ink }))
    }
}

@Composable
private fun AddWidgetSheet(current: List<String>, onClose: () -> Unit) {
    val missing = OsWidgets.all.filter { it !in current }
    OsSheet(if (missing.isEmpty()) "Every widget is on the home screen" else "Add a widget", onClose) {
        for (k in missing) SheetRow(OsWidgets.label(k) + " · " + OsWidgets.describe(k)) { BlazeOs.update { it.copy(widgets = OsWidgets.add(it.widgets, k)) }; onClose() }
    }
}

// ---------------- icons ----------------

/** The icon's shape from BlazeOS Settings: squircle (n = 5 superellipse), circle or rounded square. No square corners. */
fun osIconShape(kind: String): Shape = when (kind) {
    OsIcons.CIRCLE -> CircleShape
    OsIcons.ROUNDED -> RoundedCornerShape(24)
    else -> SquircleShape
}

private val squirclePts = OsIcons.squircle(72)
val SquircleShape: Shape = GenericShape { size, _ ->
    squirclePts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) }
    close()
}

/** A built-in's look: gradient + glyph (null glyph = drawn specially). */
private class Look(val from: Long, val to: Long, val glyph: ImageVector?, val glyphTint: Long = 0xFFFFFFFF)

private fun builtinLook(key: String): Look = when (key) {
    "blaze" -> Look(0xFF1E3A8A, 0xFF0B1230, null)
    "camera" -> Look(0xFF9A9AA0, 0xFF3A3A3C, Icons.Rounded.PhotoCamera)
    "photos" -> Look(0xFFFFB340, 0xFFFF375F, Icons.Rounded.PhotoLibrary)
    "music" -> Look(0xFFFF5A72, 0xFFE0234A, Icons.Rounded.MusicNote)
    "video" -> Look(0xFF6E6CF0, 0xFF2C2AA8, Icons.Rounded.Movie)
    "browser" -> Look(0xFF64D2FF, 0xFF0A84FF, Icons.Rounded.Explore)
    "calendar" -> Look(0xFFFFFFFF, 0xFFF2F2F7, null)
    "clock" -> Look(0xFF2C2C2E, 0xFF000000, Icons.Rounded.Schedule)
    "weather" -> Look(0xFF5AC8FA, 0xFF0A60C2, Icons.Rounded.WbSunny, 0xFFFFD60A)
    "files" -> Look(0xFF3D9BFF, 0xFF0040DD, Icons.Rounded.Folder)
    "terminal" -> Look(0xFF2C2C2E, 0xFF000000, Icons.Rounded.Terminal, 0xFF30D158)
    "store" -> Look(0xFF2FB4FF, 0xFF0A6CFF, Icons.Rounded.Storefront)
    "settings" -> Look(0xFF9A9AA0, 0xFF48484A, Icons.Rounded.Settings)
    else -> Look(0xFF8E8E93, 0xFF3A3A3C, Icons.Rounded.Apps)
}

private fun glyphOf(g: String): ImageVector = when (g) {
    "calculator" -> Icons.Rounded.Calculate; "notes" -> Icons.Rounded.EditNote; "snake", "game" -> Icons.Rounded.SportsEsports
    "eyes" -> Icons.Rounded.Visibility; "terminal" -> Icons.Rounded.Terminal; "music" -> Icons.Rounded.MusicNote
    "camera" -> Icons.Rounded.PhotoCamera; "weather" -> Icons.Rounded.WbSunny; else -> Icons.Rounded.Apps
}

@Composable
fun OsAppIcon(a: OsApp, size: Dp) {
    val prefs by BlazeOs.prefs.collectAsState()
    val reg by BlazeOs.registry.collectAsState()
    val budget by BlazeOs.budget.collectAsState()
    val ctx = LocalContext.current
    val shape = osIconShape(prefs.iconShape)
    val inst = if (a.builtin) null else reg.get(a.key)
    val tint = OsIcons.parseColor(prefs.iconTint)
    val tintMod = if (tint == null) Modifier else {
        val r = ((tint shr 16) and 0xFF) / 255f; val g = ((tint shr 8) and 0xFF) / 255f; val b = (tint and 0xFF) / 255f
        val cm = remember(tint) { ColorMatrix(OsIcons.tintMatrix(r, g, b)) }
        Modifier.drawWithCache {
            val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(cm) }
            onDrawWithContent { drawIntoCanvas { c -> c.saveLayer(Rect(Offset.Zero, this.size), paint); drawContent(); c.restore() } }
        }
    }
    // An icon file from the app's package, decoded off the main thread and downscaled.
    var bmp by remember(a.id) { mutableStateOf<ImageBitmap?>(null) }
    val iconFile = inst?.manifest?.icon?.file.orEmpty()
    if (iconFile.isNotEmpty()) LaunchedEffect(a.id, iconFile, budget.iconPx) {
        bmp = withContext(Dispatchers.IO) { OsImages.load(File(OsDisk.appDir(ctx, a.key), iconFile).absolutePath, budget.iconPx)?.asImageBitmap() }
    }
    Box(Modifier.size(size).then(tintMod).clip(shape), contentAlignment = Alignment.Center) {
        when {
            bmp != null -> Image(bmp!!, a.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            a.builtin && a.key == "calendar" -> CalendarIconFace(size)
            a.builtin && a.key == "blaze" -> {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFF0B1230)))))
                MascotMark(size * 0.9f)
            }
            else -> {
                val l = if (a.builtin) builtinLook(a.key) else {
                    val ic = inst?.manifest?.icon
                    val from = OsIcons.parseColor(ic?.from) ?: 0xFF8E8E93; val to = OsIcons.parseColor(ic?.to) ?: 0xFF3A3A3C
                    Look(from, to, glyphOf(ic?.glyph.orEmpty()))
                }
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(l.from.toInt()), Color(l.to.toInt())))))
                l.glyph?.let { Icon(it, a.label, tint = Color(l.glyphTint.toInt()), modifier = Modifier.size(size * 0.54f)) }
            }
        }
    }
}

/** An app icon from a manifest's glyph and colours (App Store listings before install). */
@Composable
fun OsGlyphIcon(ic: OsIcon, size: Dp) {
    val prefs by BlazeOs.prefs.collectAsState()
    val from = OsIcons.parseColor(ic.from) ?: 0xFF8E8E93; val to = OsIcons.parseColor(ic.to) ?: 0xFF3A3A3C
    Box(Modifier.size(size).clip(osIconShape(prefs.iconShape)).background(Brush.linearGradient(listOf(Color(from.toInt()), Color(to.toInt())))),
        contentAlignment = Alignment.Center) { Icon(glyphOf(ic.glyph), null, tint = Color.White, modifier = Modifier.size(size * 0.54f)) }
}

@Composable
private fun CalendarIconFace(size: Dp) {
    val look = LocalOsLook.current
    val now = remember { java.util.Calendar.getInstance() }
    Column(Modifier.fillMaxSize().background(Color.White), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(now.time)
        androidx.compose.material3.Text(day, style = osText(look, (size.value * 0.18f), FontWeight.SemiBold, Color(0xFFFF3B30)))
        androidx.compose.material3.Text(now.get(java.util.Calendar.DAY_OF_MONTH).toString(), style = osText(look, (size.value * 0.42f), FontWeight.Normal, Color(0xFF111114)))
    }
}

// ---------------- widgets ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OsWidgetView(kind: String, modifier: Modifier, editing: Boolean, onEdit: () -> Unit) {
    val look = LocalOsLook.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Box(modifier) {
        Box(Modifier.fillMaxSize().osGlass(look, RoundedCornerShape(26.dp)).combinedClickable(enabled = !editing, onLongClick = onEdit, onClick = {
            val target = when (kind) { OsWidgets.CALENDAR -> "blaze:calendar"; OsWidgets.ASK -> "blaze:blaze"; OsWidgets.MUSIC -> "blaze:music"; else -> "blaze:settings" }
            if (kind == OsWidgets.BATTERY) OsSettingsNav.page.value = "storage"
            scope.launch { OsLaunch.open(ctx, OsApp(target, BlazeOs.label(target))) }
        })) {
            when (kind) {
                OsWidgets.CALENDAR -> CalendarWidget()
                OsWidgets.ASK -> AskWidget()
                OsWidgets.MUSIC -> MusicWidget()
                OsWidgets.BATTERY -> BatteryWidget()
            }
        }
        if (editing) MinusBadge(Modifier.align(Alignment.TopStart).offset((-4).dp, (-4).dp)) { BlazeOs.update { it.copy(widgets = OsWidgets.remove(it.widgets, kind)) } }
    }
}

@Composable
private fun CalendarWidget() {
    val look = LocalOsLook.current
    val ctx = LocalContext.current
    val now = remember { java.util.Calendar.getInstance() }
    var next by remember { mutableStateOf<OsEvent?>(null) }
    LaunchedEffect(Unit) { next = withContext(Dispatchers.IO) { OsCalendarStore.next(ctx, System.currentTimeMillis()) } }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(now.time).uppercase(), style = osText(look, 10f, FontWeight.SemiBold, Color(0xFFFF3B30)))
                Text(now.get(java.util.Calendar.DAY_OF_MONTH).toString(), style = osText(look, 22f, FontWeight.Medium, Color(0xFF111114)))
            }
            Spacer(Modifier.width(10.dp))
            Text(java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(now.time), style = osText(look, 14f, FontWeight.Medium, look.sub), maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        val e = next
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(30.dp).clip(CircleShape).background(look.accent))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(e?.title ?: "No events", style = osText(look, 14f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e?.let { OsCalendarStore.whenLabel(it, System.currentTimeMillis()) } ?: "Add one in Calendar", style = osText(look, 12f, color = look.sub), maxLines = 1)
            }
        }
    }
}

@Composable
private fun AskWidget() {
    val look = LocalOsLook.current
    Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        MascotMark(72.dp)
        Spacer(Modifier.height(4.dp))
        Text("Ask Blaze", style = osText(look, 17f, FontWeight.SemiBold))
    }
}

/** Blaze Music's player, read in-process (no service binding just for the widget); polled while the widget shows. */
@Composable
private fun MusicWidget() {
    val look = LocalOsLook.current
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        while (true) {
            val p = try { com.blaze.assistant.mediaapps.MusicService.current?.livePlayer } catch (_: Throwable) { null }
            title = p?.mediaMetadata?.title?.toString().orEmpty(); artist = p?.mediaMetadata?.artist?.toString().orEmpty()
            playing = p?.isPlaying == true; pos = p?.currentPosition ?: 0L; dur = (p?.duration ?: 0L).coerceAtLeast(0L)
            delay(if (playing) 1000 else 3000)
        }
    }
    fun act(f: (androidx.media3.common.Player) -> Unit) {
        try { com.blaze.assistant.mediaapps.MusicService.current?.livePlayer?.let(f) } catch (_: Throwable) { }
        tick++
    }
    Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFFFF5A72), Color(0xFF5E5CE6)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title.ifEmpty { "Not playing" }, style = osText(look, 15f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (title.isEmpty()) "Tap to open Music" else artist, style = osText(look, 12f, color = look.sub), maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(look.ink.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f).height(4.dp).clip(CircleShape).background(look.ink))
            }
        }
        Spacer(Modifier.width(8.dp))
        if (title.isNotEmpty()) {
            Icon(Icons.Rounded.FastRewind, "Previous", tint = look.ink, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { act { it.seekToPrevious() } })
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = look.ink,
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable { act { if (it.isPlaying) it.pause() else { BlazeOs.applyVolume(); it.play() } } })
            Icon(Icons.Rounded.FastForward, "Next", tint = look.ink, modifier = Modifier.size(28.dp).clip(CircleShape).clickable { act { it.seekToNext() } })
        }
    }
}

@Composable
private fun BatteryWidget() {
    val look = LocalOsLook.current
    val ctx = LocalContext.current
    val used by BlazeOs.diskUsed.collectAsState()
    val budget by BlazeOs.budget.collectAsState()
    var battery by remember { mutableStateOf(OsHost.Battery(-1, false)) }
    var mem by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            val (b, m) = withContext(Dispatchers.IO) { OsHost.battery(ctx) to OsHost.memory(ctx) }
            battery = b; mem = if (m.second > 0) 1f - m.first.toFloat() / m.second else 0f
            delay(30_000)
        }
    }
    val disk = if (budget.diskQuota > 0) (used.toFloat() / budget.diskQuota).coerceIn(0f, 1f) else 0f
    Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Ring("Battery", battery.pct.coerceAtLeast(0) / 100f, if (battery.pct >= 0) "${battery.pct}%" else "–", Color(0xFF30D158))
        Ring("BlazeOS disk", disk, OsResources.size(used), Color(0xFF0A84FF))
        Ring("Memory", mem, "${(mem * 100).toInt()}%", look.accent)
    }
}

@Composable
private fun Ring(label: String, f: Float, value: String, color: Color) {
    val look = LocalOsLook.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 6.dp.toPx()
                drawCircle(look.ink.copy(alpha = 0.15f), radius = size.minDimension / 2 - w / 2, style = Stroke(w))
                drawArc(color, -90f, 360f * f.coerceIn(0f, 1f), false, topLeft = Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            }
            Text(value, style = osText(look, 11f, FontWeight.SemiBold), maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = osText(look, 11f, color = look.sub), maxLines = 1)
    }
}

@Suppress("unused") private val keepAdd = Icons.Rounded.Add
