package com.blaze.assistant.ui

import android.app.WallpaperManager
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.blaze.assistant.media.AlbumStore
import com.blaze.assistant.media.GalleryModel
import com.blaze.assistant.media.GalleryModel.Entry
import com.blaze.assistant.media.GalleryRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.30.0 Gallery: All / Albums / Images / Music / Videos, automatic and user albums, search, sort, multi-select, and a
 * full-screen viewer (swipe, pinch zoom, play audio and video, share, rename, info, wallpaper, delete). Pure rules in
 * media/GalleryModel.kt (GalleryModelTest); storage in media/GalleryRepo.kt.
 */

/** One audio player for the Gallery so only one song plays at a time. */
private object GalleryAudio {
    private var mp: MediaPlayer? = null
    val playingId = MutableStateFlow("")
    val isPlaying = MutableStateFlow(false)
    fun position(): Long = try { mp?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }
    fun length(): Long = try { mp?.duration?.toLong()?.takeIf { it > 0 } ?: 0L } catch (_: Exception) { 0L }

    fun toggle(ctx: android.content.Context, e: Entry) {
        val p = mp
        if (playingId.value == e.id && p != null) {
            try { if (p.isPlaying) { p.pause(); isPlaying.value = false } else { p.start(); isPlaying.value = true } } catch (_: Exception) { stop() }
            return
        }
        stop()
        try {
            mp = MediaPlayer().apply {
                setDataSource(ctx, GalleryRepo.playUri(e))
                setOnCompletionListener { GalleryAudio.isPlaying.value = false; try { seekTo(0) } catch (_: Exception) { } }
                prepare(); start()
            }
            playingId.value = e.id; isPlaying.value = true
        } catch (_: Exception) {
            stop(); Toast.makeText(ctx, "Couldn't play this file.", Toast.LENGTH_SHORT).show()
        }
    }

    fun seek(ms: Long) { try { mp?.seekTo(ms.toInt()) } catch (_: Exception) { } }
    fun stop() { try { mp?.release() } catch (_: Exception) { }; mp = null; playingId.value = ""; isPlaying.value = false }
}

@Composable
private fun rememberEntryThumb(e: Entry, px: Int): ImageBitmap? {
    val v by produceState(GalleryRepo.cachedThumb(e, px)?.asImageBitmap(), e.id, px) {
        if (value == null) value = GalleryRepo.thumb(e, px)?.asImageBitmap()
    }
    return v
}

private sealed class GDialog {
    data class Delete(val items: List<Entry>, val fromViewer: Boolean) : GDialog()
    data class Rename(val item: Entry) : GDialog()
    data class Info(val item: Entry) : GDialog()
    data class Wallpaper(val item: Entry) : GDialog()
    data class PickAlbum(val items: List<Entry>, val moveFrom: String?) : GDialog()
    object NewAlbum : GDialog()
    data class RenameAlbum(val album: AlbumStore.Album) : GDialog()
    data class DeleteAlbum(val album: AlbumStore.Album) : GDialog()
}

/** Everything Blaze made or downloaded, newest first. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(onDismiss: () -> Unit) {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    GalleryRepo.init(ctx)
    LaunchedEffect(Unit) { GalleryRepo.refresh() }
    DisposableEffect(Unit) {
        val stop = GalleryRepo.watch()
        onDispose { stop(); GalleryAudio.stop() }
    }
    val blaze by GalleryRepo.blaze.collectAsState()
    val phone by GalleryRepo.phone.collectAsState()
    val albums by GalleryRepo.albums.collectAsState()
    val loading by GalleryRepo.loading.collectAsState()
    val all = remember(blaze, phone) { GalleryModel.merge(blaze, phone) }
    val autos = remember(all) { GalleryModel.autoAlbums(all) }

    var tab by remember { mutableStateOf("all") }
    var albumId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(GalleryModel.Sort.DATE) }
    var asc by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var autoplay by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<GDialog?>(null) }
    var canPhone by remember { mutableStateOf(GalleryRepo.canReadPhone()) }
    // v0.34.0: clean up, trash, backup plan, editor, pinch columns, location stripping on share
    var extra by remember { mutableStateOf("") }   // clean | trash | backup
    var editing by remember { mutableStateOf<Entry?>(null) }
    var cols by remember { androidx.compose.runtime.mutableIntStateOf(3) }
    var strip by remember { mutableStateOf(com.blaze.assistant.mediaapps.MediaPrefs.bool(ctx, com.blaze.assistant.mediaapps.MediaPrefs.STRIP_GPS, true)) }
    var moreMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.blaze.assistant.media.GalleryClean.purge(ctx) } }
    fun share(list: List<Entry>) {
        scope.launch {
            val l = if (strip) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.blaze.assistant.media.GalleryClean.withoutLocation(ctx, list) } else list
            try { ctx.startActivity(GalleryRepo.shareIntent(l)) } catch (_: Exception) { }
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { GalleryRepo.refresh() }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        canPhone = GalleryRepo.canReadPhone(); GalleryRepo.refresh()
    }

    val userAlbum = albumId?.let { id -> albums.firstOrNull { it.id == id } }
    val autoAlbum = albumId?.let { id -> autos.firstOrNull { it.id == id } }
    if (albumId != null && userAlbum == null && autoAlbum == null && albumId?.startsWith("album:") == true) albumId = null

    val base: List<Entry> = when {
        userAlbum != null -> { val ids = userAlbum.items.toSet(); all.filter { it.id in ids } }
        autoAlbum != null -> autoAlbum.items
        tab == "albums" -> emptyList()
        else -> GalleryModel.forTab(all, tab)
    }
    val shown = remember(base, query, sort, asc) { GalleryModel.sort(GalleryModel.search(base, query), sort, asc) }
    val byId = remember(all) { all.associateBy { it.id } }
    val selectedItems = selected.mapNotNull { byId[it] }
    val now = remember(all) { System.currentTimeMillis() }
    val cells = remember(shown, sort, now) { GalleryModel.timeline(shown, sort, now) }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val tileRects = remember { HashMap<String, androidx.compose.ui.geometry.Rect>() }
    fun dayIds(h: GalleryModel.Cell.Header): List<String> {
        val i = cells.indexOf(h); if (i < 0) return emptyList()
        return cells.subList(i + 1, (i + 1 + h.count).coerceAtMost(cells.size)).mapNotNull { (it as? GalleryModel.Cell.Item)?.entry?.id }
    }
    val inGrid = tab != "albums" || albumId != null

    fun openViewer(e: Entry) {
        val ids = shown.map { it.id }
        viewer = ids to ids.indexOf(e.id).coerceAtLeast(0)
        autoplay = if (e.kind != "image") e.id else ""
    }

    /** v0.34.0: Delete moves to the 30-day Trash (Android's own trash for phone photos on Android 11+). */
    fun doDelete(list: List<Entry>) {
        scope.launch {
            val (moved, sender) = com.blaze.assistant.media.GalleryClean.trash(ctx, list)
            selected = emptySet()
            if (sender != null) try { deleteLauncher.launch(IntentSenderRequest.Builder(sender).build()) } catch (_: Exception) { }
            GalleryRepo.refresh()
            Toast.makeText(ctx, if (sender != null) "Confirm to move the phone's photos to the Trash." else if (moved == 1 || list.size == 1) "Moved to Trash (30 days)." else "Moved ${list.size} items to Trash (30 days).", Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(enabled = viewer == null) {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            searching -> { searching = false; query = "" }
            albumId != null -> albumId = null
            else -> onDismiss()
        }
    }

    // v0.45.8: follows the picked theme (draws its scene; Classic keeps the glass page).
    ThemedScreen(Modifier.fillMaxSize(), own = { pageBackdrop(c) }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // ---------------- header
            if (selected.isNotEmpty()) {
                BackTitle("${selected.size} selected", onBack = { selected = emptySet() }, modifier = Modifier.hairlineBottom(c.hair).padding(vertical = 2.dp)) {
                    HeaderIcon(Icons.Rounded.SelectAll, "Select all") { selected = shown.map { it.id }.toSet() }
                    HeaderIcon(Icons.Rounded.Close, "Clear selection") { selected = emptySet() }
                }
            } else {
                val title = userAlbum?.name ?: autoAlbum?.name ?: "Gallery"
                BackTitle(title, onBack = { if (albumId != null) albumId = null else onDismiss() },
                    modifier = Modifier.hairlineBottom(c.hair).padding(vertical = 2.dp)) {
                    if (inGrid) Label("${shown.size}", color = c.weak, size = 10.sp, modifier = Modifier.padding(end = 4.dp))
                    if (inGrid) HeaderIcon(Icons.Rounded.Search, "Search") { searching = !searching; if (!searching) query = "" }
                    if (inGrid) Box {
                        HeaderIcon(Icons.Rounded.SwapVert, "Sort") { sortMenu = true }
                        BlazeMenu(sortMenu, onDismiss = { sortMenu = false }, items = listOf(
                            "Newest first" to { sort = GalleryModel.Sort.DATE; asc = false },
                            "Oldest first" to { sort = GalleryModel.Sort.DATE; asc = true },
                            "Name A–Z" to { sort = GalleryModel.Sort.NAME; asc = true },
                            "Name Z–A" to { sort = GalleryModel.Sort.NAME; asc = false },
                            "Largest first" to { sort = GalleryModel.Sort.SIZE; asc = false },
                            "Smallest first" to { sort = GalleryModel.Sort.SIZE; asc = true },
                        ))
                    }
                    if (albumId == null) HeaderIcon(Icons.Rounded.AutoFixHigh, "Clean up") { extra = "clean" }
                    if (albumId == null) Box {
                        HeaderIcon(Icons.Rounded.MoreVert, "More") { moreMenu = true }
                        BlazeMenu(moreMenu, onDismiss = { moreMenu = false }, items = listOf(
                            "Trash" to { extra = "trash" },
                            "Backup plan" to { extra = "backup" },
                            (if (strip) "Remove location when sharing: on" else "Remove location when sharing: off") to {
                                strip = !strip; com.blaze.assistant.mediaapps.MediaPrefs.setBool(ctx, com.blaze.assistant.mediaapps.MediaPrefs.STRIP_GPS, strip) },
                        ))
                    }
                    if (userAlbum != null) Box {
                        var menu by remember { mutableStateOf(false) }
                        HeaderIcon(Icons.Rounded.Edit, "Album options") { menu = true }
                        BlazeMenu(menu, onDismiss = { menu = false }, items = listOf(
                            "Rename album" to { dialog = GDialog.RenameAlbum(userAlbum) },
                            "Delete album" to { dialog = GDialog.DeleteAlbum(userAlbum) },
                        ))
                    }
                }
            }
            // ---------------- pills + search
            if (albumId == null && selected.isEmpty()) GalleryTabs(tab) { tab = it; selected = emptySet() }
            if (searching && inGrid) KitSearch(query, { query = it }, "Search by name", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))

            // ---------------- body
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!inGrid) {
                    AlbumsGrid(autos = autos, user = albums, all = byId, canPhone = canPhone,
                        onOpen = { albumId = it }, onNew = { dialog = GDialog.NewAlbum },
                        onAlbumMenu = { a, action -> dialog = if (action == "rename") GDialog.RenameAlbum(a) else GDialog.DeleteAlbum(a) },
                        onAllowPhone = { try { permLauncher.launch(GalleryRepo.phonePermissions()) } catch (_: Exception) { } })
                } else if (shown.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Rows {
                            when {
                                query.isNotBlank() -> EmptyState("Nothing called \u201c$query\u201d", "Try another word, or clear the search.")
                                userAlbum != null -> EmptyState("This album is empty", "Long-press anything in the Gallery, then tap Album to put it here.")
                                loading -> EmptyState("Loading\u2026", "Looking through Blaze's pictures, music and videos.")
                                tab == "music" -> EmptyState("No music yet", "Songs Blaze downloads (Music/Blaze) and beats it makes show up here.")
                                tab == "videos" -> EmptyState("No videos yet", "Videos Blaze downloads (Movies/Blaze) show up here.")
                                else -> EmptyState("Nothing here yet", "Ask Blaze to \u201cdraw a lion in sunglasses\u201d or \u201cmake me an afro beat\u201d. Everything it makes or downloads lands here.")
                            }
                        }
                    }
                } else {
                    val otd = remember(all, now) { if (tab == "all") com.blaze.assistant.media.GalleryTools.onThisDay(all, now) else emptyList() }
                    LazyVerticalGrid(GridCells.Fixed(cols), Modifier.fillMaxSize().padding(horizontal = 2.dp)
                        .pointerInput(Unit) {
                            // v0.34.0 pinch the grid: two fingers apart = bigger tiles, together = more columns (2 to 6).
                            awaitEachGesture {
                                var zoom = 1f
                                do {
                                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    if (ev.changes.size >= 2) {
                                        zoom *= ev.calculateZoom()
                                        ev.changes.forEach { it.consume() }
                                        val n = com.blaze.assistant.media.GalleryTools.columnsAfterPinch(cols, zoom)
                                        if (n != cols) { cols = n; zoom = 1f }
                                    }
                                } while (ev.changes.any { it.pressed })
                            }
                        }, state = gridState) {
                        if (query.isBlank() && otd.isNotEmpty() && albumId == null) item(key = "onthisday", span = { GridItemSpan(maxLineSpan) }) {
                            OnThisDayCard(otd, now) { openViewer(it) }
                        }
                        items(cells, key = { it.key }, span = { cell -> GridItemSpan(if (cell is GalleryModel.Cell.Header) maxLineSpan else 1) },
                            contentType = { if (it is GalleryModel.Cell.Header) 0 else 1 }) { cell ->
                            when (cell) {
                                is GalleryModel.Cell.Header -> DayHeader(cell, selecting = selected.isNotEmpty(),
                                    allOn = selected.isNotEmpty() && dayIds(cell).all { it in selected }) {
                                    val ids = dayIds(cell)
                                    selected = if (ids.all { it in selected }) selected - ids.toSet() else selected + ids
                                }
                                is GalleryModel.Cell.Item -> {
                                    val e = cell.entry
                                    GalleryTile(e, selecting = selected.isNotEmpty(), isSelected = e.id in selected,
                                        onPlaced = { tileRects[e.id] = it },
                                        onClick = {
                                            if (selected.isNotEmpty()) selected = if (e.id in selected) selected - e.id else selected + e.id
                                            else openViewer(e)
                                        },
                                        onLongClick = { selected = selected + e.id })
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- selection actions
            if (selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().hairlineTop(c.hair).padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BarAction(Icons.Rounded.Share, "Share") { share(selectedItems) }
                    BarAction(Icons.Rounded.CreateNewFolder, "Album") { dialog = GDialog.PickAlbum(selectedItems, null) }
                    if (userAlbum != null) {
                        BarAction(Icons.Rounded.FolderOpen, "Move") { dialog = GDialog.PickAlbum(selectedItems, userAlbum.id) }
                        BarAction(Icons.Rounded.RemoveCircleOutline, "Remove") {
                            GalleryRepo.removeFromAlbum(userAlbum.id, selected.toList()); selected = emptySet()
                        }
                    }
                    BarAction(Icons.Rounded.Delete, "Trash") { dialog = GDialog.Delete(selectedItems, fromViewer = false) }
                }
            }
        }

        // ---------------- viewer
        viewer?.let { (ids, start) ->
            val list = ids.mapNotNull { byId[it] }
            if (list.isEmpty()) { viewer = null; return@let }
            GalleryViewer(list, start.coerceIn(0, list.size - 1), autoplay, originOf = { id -> tileRects[id] },
                onClose = { lastId ->
                    viewer = null; GalleryAudio.stop()
                    val idx = cells.indexOfFirst { it.key == lastId }
                    val vis = gridState.layoutInfo.visibleItemsInfo.map { it.index }
                    if (idx >= 0 && (vis.isEmpty() || idx < vis.first() || idx > vis.last())) scope.launch { gridState.scrollToItem(idx) }
                },
                onAction = { action, e ->
                    when (action) {
                        "share" -> share(listOf(e))
                        "edit" -> editing = e
                        "rename" -> dialog = GDialog.Rename(e)
                        "info" -> dialog = GDialog.Info(e)
                        "wallpaper" -> dialog = GDialog.Wallpaper(e)
                        "album" -> dialog = GDialog.PickAlbum(listOf(e), null)
                        "delete" -> dialog = GDialog.Delete(listOf(e), fromViewer = true)
                    }
                })
        }
    }

    // ---------------- v0.34.0 extras
    when (extra) {
        "clean" -> CleanUpDialog(all, onTrash = { l -> extra = ""; doDelete(l) }, onDismiss = { extra = "" })
        "trash" -> TrashDialog(onRestorePhone = { snd -> try { deleteLauncher.launch(IntentSenderRequest.Builder(snd).build()) } catch (_: Exception) { } }, onDismiss = { extra = ""; GalleryRepo.refresh() })
        "backup" -> BackupPlanDialog(all) { extra = "" }
    }
    editing?.let { e -> PhotoEditor(e) { editing = null } }

    // ---------------- dialogs
    when (val d = dialog) {
        null -> {}
        is GDialog.Delete -> BlazeDialog(
            title = if (d.items.size == 1) "Move \u201c${d.items[0].title.take(40)}\u201d to Trash?" else "Move ${d.items.size} items to Trash?",
            body = "They stay in the Trash for 30 days (Gallery \u203a \u22ee \u203a Trash), then are deleted for good.",
            confirm = "Move to Trash", warm = true, dismiss = "Cancel", onDismiss = { dialog = null },
            onConfirm = { dialog = null; GalleryAudio.stop(); doDelete(d.items) })
        is GDialog.Rename -> TextDialog("Rename", d.item.name.substringBeforeLast('.'), "New name", "Rename", onDismiss = { dialog = null }) { t ->
            dialog = null
            scope.launch { GalleryRepo.rename(d.item, t)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }
        }
        is GDialog.Info -> InfoDialog(d.item) { dialog = null }
        is GDialog.Wallpaper -> BlazeDialog(title = "Set as wallpaper", body = "Where should this picture go?", confirm = "Both", dismiss = "Cancel",
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; setWallpaper(ctx, scope, d.item, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Home screen", primary = false) { dialog = null; setWallpaper(ctx, scope, d.item, WallpaperManager.FLAG_SYSTEM) }
                PillButton("Lock screen", primary = false) { dialog = null; setWallpaper(ctx, scope, d.item, WallpaperManager.FLAG_LOCK) }
            }
        }
        is GDialog.PickAlbum -> AlbumPicker(d, albums.filter { it.id != d.moveFrom }, onDismiss = { dialog = null }) { target, newName ->
            dialog = null
            val ids = d.items.map { it.id }
            val dest = target ?: GalleryRepo.createAlbum(newName)?.id
            if (dest != null) {
                if (d.moveFrom != null) GalleryRepo.moveToAlbum(d.moveFrom, dest, ids) else GalleryRepo.addToAlbum(dest, ids)
                val name = albums.firstOrNull { it.id == dest }?.name ?: newName.trim()
                Toast.makeText(ctx, (if (d.moveFrom != null) "Moved to " else "Added to ") + name, Toast.LENGTH_SHORT).show()
            }
            selected = emptySet()
        }
        GDialog.NewAlbum -> TextDialog("New album", "", "Album name", "Create", onDismiss = { dialog = null }) { t ->
            dialog = null
            GalleryRepo.createAlbum(t)?.let { albumId = it.id }
        }
        is GDialog.RenameAlbum -> TextDialog("Rename album", d.album.name, "Album name", "Save", onDismiss = { dialog = null }) { t ->
            dialog = null; GalleryRepo.renameAlbum(d.album.id, t)
        }
        is GDialog.DeleteAlbum -> BlazeDialog(title = "Delete \u201c${d.album.name}\u201d?",
            body = "Only the album goes. Its pictures, songs and videos stay in the Gallery.", confirm = "Delete album", warm = true,
            dismiss = "Cancel", onDismiss = { dialog = null },
            onConfirm = { dialog = null; GalleryRepo.deleteAlbum(d.album.id); if (albumId == d.album.id) albumId = null })
    }
}

private fun setWallpaper(ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, e: Entry, which: Int) {
    Toast.makeText(ctx, "Setting wallpaper\u2026", Toast.LENGTH_SHORT).show()
    scope.launch { Toast.makeText(ctx, GalleryRepo.setWallpaper(e, which) ?: "Wallpaper set.", Toast.LENGTH_SHORT).show() }
}

// ---------------------------------------------------------------- pieces

/** All | Albums | Images | Music | Videos: the same pill as Chat | Projects | Home. Scrolls sideways on narrow phones. */
@Composable
private fun GalleryTabs(tab: String, onTab: (String) -> Unit) {
    val c = LocalBlaze.current.mono
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.pageGlass(c, RoundedCornerShape(20.dp), alpha = 0.06f).padding(3.dp)) {
            GalleryModel.tabs.forEach { (id, label) ->
                val on = tab == id
                val fill by androidx.compose.animation.animateColorAsState(if (on) c.ink else Color.Transparent, MotionTokens.medium(), label = "gtab")
                val ink by androidx.compose.animation.animateColorAsState(if (on) c.paper else c.sub, MotionTokens.medium(), label = "gtabText")
                Box(Modifier.clip(RoundedCornerShape(17.dp)).background(fill).clickable(onClickLabel = "Show $label") { onTab(id) }
                    .semantics { selected = on }.heightIn(min = 34.dp).padding(horizontal = 13.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                    Text(label, maxLines = 1, softWrap = false, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = ink))
                }
            }
        }
    }
}

/** "Today · 12", "Mon, 12 Oct"…: a day in the timeline. While selecting, tapping it selects the whole day. */
@Composable
private fun DayHeader(h: GalleryModel.Cell.Header, selecting: Boolean, allOn: Boolean, onToggleDay: () -> Unit) {
    val c = LocalBlaze.current.mono
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(h.label, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.ink))
        if (selecting) Box(Modifier.size(32.dp).clip(BlazeShapes.pill).clickable(onClickLabel = "Select this day", onClick = onToggleDay), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = if (allOn) c.accent else c.weak, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.size(40.dp).clip(BlazeShapes.pill).clickable(onClickLabel = desc, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(21.dp)) }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Column(Modifier.clip(BlazeShapes.small).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = tint ?: c.ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = TextStyle(fontFamily = Type.Sans, fontSize = 11.sp, color = tint ?: c.sub))
    }
}

/** A song with no cover art: a soft accent gradient with a note, so music never looks like a grey box. */
@Composable
private fun ArtFallback(e: Entry, big: Boolean = false) {
    val hue = (e.title.hashCode() and 0x7fffffff) % 360
    val a = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.85f)))
    val b = Color(android.graphics.Color.HSVToColor(floatArrayOf(((hue + 40) % 360).toFloat(), 0.65f, 0.45f)))
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            Icon(if (e.kind == "video") Icons.Rounded.PlayArrow else Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(if (big) 72.dp else 28.dp))
            if (!big) Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = Color.White))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryTile(e: Entry, selecting: Boolean, isSelected: Boolean, onPlaced: (androidx.compose.ui.geometry.Rect) -> Unit,
                        onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    val bmp = rememberEntryThumb(e, 256)
    // Thumbnails fade in instead of popping; a picture already in memory shows at once.
    val shownAlpha by androidx.compose.animation.core.animateFloatAsState(if (bmp != null) 1f else 0f,
        androidx.compose.animation.core.tween(if (GalleryRepo.cachedThumb(e, 256) != null) 0 else 180), label = "thumb")
    val inset by androidx.compose.animation.core.animateDpAsState(if (isSelected) 10.dp else 0.dp, label = "sel")
    Box(Modifier.padding(1.5.dp).aspectRatio(1f).onGloballyPositioned { onPlaced(it.boundsInRoot()) }.padding(inset)
        .clip(if (isSelected) BlazeShapes.small else BlazeShapes.xs).background(c.hair.copy(alpha = 0.3f))
        .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
        .semantics { contentDescription = e.title; selected = isSelected }) {
        if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shownAlpha }, contentScale = ContentScale.Crop)
        else if (e.kind != "image") ArtFallback(e)
        if (e.kind == "video") Box(Modifier.align(Alignment.Center).size(30.dp).clip(BlazeShapes.pill).background(Color(0x88000000)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        if (e.kind == "audio" && bmp != null) Box(Modifier.align(Alignment.TopStart).padding(5.dp).size(20.dp).clip(BlazeShapes.pill).background(Color(0x88000000)),
            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp)) }
        val dur = GalleryModel.duration(e.durationMs)
        if (dur.isNotEmpty()) Text(dur, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(BlazeShapes.xs).background(Color(0x99000000))
            .padding(horizontal = 5.dp, vertical = 1.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 10.sp, color = Color.White))
        if (selecting) {
            if (isSelected) Box(Modifier.matchParentSize().background(c.accent.copy(alpha = 0.25f)))
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).clip(BlazeShapes.pill)
                .background(if (isSelected) Color.White else Color(0x55000000)).border(1.5.dp, Color.White, BlazeShapes.pill), contentAlignment = Alignment.Center) {
                if (isSelected) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumsGrid(autos: List<GalleryModel.AutoAlbum>, user: List<AlbumStore.Album>, all: Map<String, Entry>, canPhone: Boolean,
                       onOpen: (String) -> Unit, onNew: () -> Unit, onAlbumMenu: (AlbumStore.Album, String) -> Unit, onAllowPhone: () -> Unit) {
    val c = LocalBlaze.current.mono
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        item(span = { GridItemSpan(2) }) { Label("Your albums", color = c.sub, size = 11.sp, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp)) }
        item {
            Column(Modifier.padding(6.dp).clip(BlazeShapes.medium).clickable(onClickLabel = "New album", onClick = onNew)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).glassInset(c).border(1.dp, c.hair, BlazeShapes.medium),
                    contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, contentDescription = null, tint = c.ink, modifier = Modifier.size(32.dp)) }
                AlbumCaption("New album", "")
            }
        }
        items(user, key = { it.id }) { a ->
            var menu by remember { mutableStateOf(false) }
            val items = a.items.mapNotNull { all[it] }
            Box {
                AlbumCard(a.name, items.size, items.maxByOrNull { it.time }, onClick = { onOpen(a.id) }, onLongClick = { menu = true })
                BlazeMenu(menu, onDismiss = { menu = false }, items = listOf("Rename" to { onAlbumMenu(a, "rename") }, "Delete album" to { onAlbumMenu(a, "delete") }))
            }
        }
        item(span = { GridItemSpan(2) }) { Label("On this phone", color = c.sub, size = 11.sp, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp)) }
        items(autos, key = { it.id }) { a -> AlbumCard(a.name, a.items.size, a.items.firstOrNull(), onClick = { onOpen(a.id) }, onLongClick = null) }
        if (autos.isEmpty()) item(span = { GridItemSpan(2) }) {
            Label("Pictures, songs and videos Blaze makes or downloads get their own albums here.", color = c.weak, size = 11.sp, modifier = Modifier.padding(6.dp))
        }
        if (!canPhone) item(span = { GridItemSpan(2) }) {
            Row(Modifier.padding(6.dp).fillMaxWidth().glassInset(c).clickable(onClick = onAllowPhone).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Show Screenshots and Camera", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
                    Text("Lets Blaze read your phone's photos and videos. Blaze's own files show without it.",
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                }
            }
        }
        item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(name: String, count: Int, cover: Entry?, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val c = LocalBlaze.current.mono
    Column(Modifier.padding(6.dp).clip(BlazeShapes.medium).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(BlazeShapes.medium).background(c.hair.copy(alpha = 0.3f))) {
            if (cover != null) {
                val bmp = rememberEntryThumb(cover, 256)
                if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else if (cover.kind != "image") ArtFallback(cover)
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = c.weak, modifier = Modifier.size(36.dp))
            }
        }
        AlbumCaption(name, if (count == 1) "1 item" else "$count items")
    }
}

@Composable
private fun AlbumCaption(name: String, sub: String) {
    val c = LocalBlaze.current.mono
    Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
        if (sub.isNotEmpty()) Text(sub, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
    }
}

// ---------------------------------------------------------------- viewer

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryViewer(list: List<Entry>, start: Int, autoplay: String, originOf: (String) -> androidx.compose.ui.geometry.Rect?,
                          onClose: (String) -> Unit, onAction: (String, Entry) -> Unit) {
    val pager = rememberPagerState(initialPage = start) { list.size }
    val cur = list.getOrNull(pager.currentPage.coerceIn(0, list.size - 1)) ?: return
    var chrome by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(cur.id) { if (GalleryAudio.playingId.value.isNotEmpty() && GalleryAudio.playingId.value != cur.id) GalleryAudio.stop() }

    // Shared-element style open/close: the item grows out of its tile in the grid and shrinks back into the tile of
    // whatever item is showing when you close (or fades if that tile is off screen). graphicsLayer only: no relayout.
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(originOf(list[start].id)) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { anim.animateTo(1f, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    fun close() {
        if (closing) return
        closing = true
        val id = cur.id
        origin = originOf(id)
        scope.launch {
            anim.animateTo(0f, androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            onClose(id)
        }
    }
    BackHandler { close() }
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = anim.value }.background(Color.Black))
        HorizontalPager(pager, Modifier.fillMaxSize().graphicsLayer {
            val p = anim.value
            val o = origin
            if (o != null && size.width > 0 && size.height > 0 && p < 1f) {
                val s0 = (o.width / size.width).coerceIn(0.05f, 1f)
                val sc = s0 + (1f - s0) * p
                scaleX = sc; scaleY = sc
                translationX = (o.center.x - size.width / 2f) * (1f - p)
                translationY = (o.center.y - size.height / 2f) * (1f - p)
                alpha = (p * 3f).coerceAtMost(1f)
            } else if (p < 1f) {
                val sc = 0.9f + 0.1f * p; scaleX = sc; scaleY = sc; alpha = p
            }
        }, key = { list[it].id }, beyondViewportPageCount = 1, userScrollEnabled = !closing) { page ->
            val e = list[page]
            val current = page == pager.currentPage
            when (e.kind) {
                "image" -> ZoomImage(e, current, onTap = { chrome = !chrome })
                "video" -> VideoPage(e, current, autoplay == e.id)
                else -> AudioPage(e, current, autoplay == e.id)
            }
        }
        if (chrome && !closing) {
            Row(Modifier.fillMaxWidth().graphicsLayer { alpha = anim.value }.background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent))).statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(BlazeShapes.pill).clickable(onClickLabel = "Close") { close() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(cur.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Color.White))
                    Text("${pager.currentPage + 1} of ${list.size} \u00b7 " + dateLabel(cur.time), maxLines = 1,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Color(0xCCFFFFFF)))
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer { alpha = anim.value }.background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                .navigationBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                BarAction(Icons.Rounded.Share, "Share", Color.White) { onAction("share", cur) }
                if (cur.kind == "image") BarAction(Icons.Rounded.Tune, "Edit", Color.White) { onAction("edit", cur) }
                BarAction(Icons.Rounded.Edit, "Rename", Color.White) { onAction("rename", cur) }
                BarAction(Icons.Rounded.Info, "Info", Color.White) { onAction("info", cur) }
                if (cur.kind == "image") BarAction(Icons.Rounded.Wallpaper, "Wallpaper", Color.White) { onAction("wallpaper", cur) }
                BarAction(Icons.Rounded.CreateNewFolder, "Album", Color.White) { onAction("album", cur) }
                BarAction(Icons.Rounded.Delete, "Trash", Color.White) { onAction("delete", cur) }
            }
        }
    }
}

/** Pinch to zoom, drag when zoomed, double-tap to zoom in/out. At 1× a sideways drag goes to the pager (next item). */
@Composable
private fun ZoomImage(e: Entry, current: Boolean, onTap: () -> Unit) {
    val full by produceState<ImageBitmap?>(GalleryRepo.cachedThumb(e, 256)?.asImageBitmap(), e.id) {
        val big = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { GalleryRepo.decode(e, 2048) }
        if (big != null) value = big.asImageBitmap()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(current) { if (!current) { scale = 1f; offset = Offset.Zero } }
    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1) / 2f; val my = box.height * (s - 1) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    Box(Modifier.fillMaxSize().onSizeChanged { box = it }
        .pointerInput(e.id) {
            detectTapGestures(onTap = { onTap() }, onDoubleTap = { p ->
                if (scale > 1.05f) { scale = 1f; offset = Offset.Zero }
                else { scale = 2.5f; offset = clamp(Offset((box.width / 2f - p.x) * 1.5f, (box.height / 2f - p.y) * 1.5f), 2.5f) }
            })
        }
        .pointerInput(e.id) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val ev = awaitPointerEvent()
                    val zoom = ev.calculateZoom()
                    val pan = ev.calculatePan()
                    if (ev.changes.size > 1 || scale > 1.01f) {
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale <= 1.01f) Offset.Zero else clamp(offset + pan, scale)
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
            }
        }, contentAlignment = Alignment.Center) {
        val b = full
        if (b != null) Image(b, contentDescription = e.title, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
        else Label("Loading picture\u2026", color = Color(0xCCFFFFFF), size = 11.sp)
    }
}

@Composable
private fun VideoPage(e: Entry, current: Boolean, autoplay: Boolean) {
    var playing by remember { mutableStateOf(autoplay && current) }
    LaunchedEffect(current) { if (!current) playing = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing) {
            AndroidView(factory = { ctx ->
                VideoView(ctx).apply {
                    val mc = MediaController(ctx); mc.setAnchorView(this); setMediaController(mc)
                    setVideoURI(GalleryRepo.playUri(e))
                    setOnPreparedListener { start(); mc.show(2500) }
                    setOnErrorListener { _, _, _ -> Toast.makeText(ctx, "Couldn't play this video.", Toast.LENGTH_SHORT).show(); true }
                }
            }, modifier = Modifier.fillMaxWidth(), onRelease = { v -> try { v.stopPlayback() } catch (_: Exception) { } })
        } else {
            val bmp = rememberEntryThumb(e, 720)
            if (bmp != null) Image(bmp, contentDescription = e.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Box(Modifier.size(220.dp).clip(BlazeShapes.large)) { ArtFallback(e, big = true) }
            Box(Modifier.size(72.dp).clip(BlazeShapes.pill).background(Color(0x99000000)).clickable(onClickLabel = "Play") { playing = true },
                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(44.dp)) }
            val d = GalleryModel.duration(e.durationMs)
            if (d.isNotEmpty()) Text(d, modifier = Modifier.align(Alignment.Center).padding(top = 120.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = Color.White))
        }
    }
}

@Composable
private fun AudioPage(e: Entry, current: Boolean, autoplay: Boolean) {
    val ctx = LocalContext.current
    val playingId by GalleryAudio.playingId.collectAsState()
    val isPlaying by GalleryAudio.isPlaying.collectAsState()
    val mine = playingId == e.id
    var pos by remember { mutableLongStateOf(0L) }
    var len by remember { mutableLongStateOf(e.durationMs) }
    LaunchedEffect(current) { if (current && autoplay && !mine) GalleryAudio.toggle(ctx, e) }
    LaunchedEffect(mine, isPlaying) {
        while (mine) { pos = GalleryAudio.position(); GalleryAudio.length().takeIf { it > 0 }?.let { len = it }; if (!isPlaying) break; delay(250) }
    }
    val art = rememberEntryThumb(e, 720)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 90.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).clip(BlazeShapes.large)) {
            if (art != null) Image(art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else ArtFallback(e, big = true)
        }
        Spacer(Modifier.height(22.dp))
        Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Color.White))
        Spacer(Modifier.height(18.dp))
        SeekBar(if (mine) pos else 0L, len, enabled = mine) { GalleryAudio.seek(it); pos = it }
        Row(Modifier.widthIn(max = 320.dp).fillMaxWidth().padding(top = 4.dp)) {
            Text(GalleryModel.duration(if (mine) pos else 0L).ifEmpty { "0:00" }, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Color(0xCCFFFFFF)))
            Spacer(Modifier.weight(1f))
            Text(GalleryModel.duration(len), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Color(0xCCFFFFFF)))
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.size(68.dp).clip(BlazeShapes.pill).background(Color.White).clickable(onClickLabel = if (mine && isPlaying) "Pause" else "Play") { GalleryAudio.toggle(ctx, e) },
            contentAlignment = Alignment.Center) {
            Icon(if (mine && isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(38.dp))
        }
    }
}

/** A thin progress line; tap or drag to seek. */
@Composable
private fun SeekBar(pos: Long, len: Long, enabled: Boolean, onSeek: (Long) -> Unit) {
    var w by remember { mutableStateOf(1) }
    val frac = if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f
    Box(Modifier.widthIn(max = 320.dp).fillMaxWidth().height(28.dp).onSizeChanged { w = it.width.coerceAtLeast(1) }
        .pointerInput(enabled, len) {
            if (!enabled || len <= 0) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                onSeek((down.position.x / w * len).toLong().coerceIn(0, len))
                do {
                    val ev = awaitPointerEvent()
                    ev.changes.firstOrNull()?.let { ch -> if (ch.pressed) { onSeek((ch.position.x / w * len).toLong().coerceIn(0, len)); ch.consume() } }
                } while (ev.changes.any { it.pressed })
            }
        }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(BlazeShapes.pill).background(Color(0x44FFFFFF)))
        Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(BlazeShapes.pill).background(Color.White))
    }
}

private fun dateLabel(ms: Long): String = if (ms <= 0) "" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

// ---------------------------------------------------------------- dialogs

@Composable
private fun TextDialog(title: String, initial: String, hint: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    BlazeDialog(title = title, confirm = confirm, onConfirm = { if (t.isNotBlank()) onDone(t.trim()) }, dismiss = "Cancel", onDismiss = onDismiss) {
        BlazeTextField(t, { t = it.take(120) }, hint, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (t.isNotBlank()) onDone(t.trim()) }))
    }
}

@Composable
private fun InfoDialog(e: Entry, onDismiss: () -> Unit) {
    val rows = buildList {
        add("Name" to e.name)
        add("Type" to when (e.kind) { "image" -> "Picture"; "audio" -> "Audio"; "video" -> "Video"; else -> e.kind } + " \u00b7 " + GalleryRepo.mime(e))
        add("Size" to GalleryModel.size(e.size))
        add("Date" to dateLabel(e.time))
        if (e.width > 0 && e.height > 0) add("Resolution" to "${e.width} \u00d7 ${e.height}")
        if (e.durationMs > 0) add("Length" to GalleryModel.duration(e.durationMs))
        add("Where" to (if (e.isFile) "Blaze's gallery (only Blaze can see it)" else e.folder.trimEnd('/').ifBlank { "Phone storage" }))
    }
    BlazeDialog(title = "Info", confirm = "Done", onConfirm = onDismiss, onDismiss = onDismiss) {
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(k, modifier = Modifier.width(92.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = DialogColors.body))
                Text(v, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = DialogColors.title))
            }
        }
    }
}

@Composable
private fun AlbumPicker(d: GDialog.PickAlbum, albums: List<AlbumStore.Album>, onDismiss: () -> Unit, onPick: (String?, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val what = if (d.items.size == 1) "it" else "${d.items.size} items"
    BlazeDialog(title = if (d.moveFrom != null) "Move $what to\u2026" else "Add $what to\u2026", confirm = "Create album",
        onConfirm = { if (name.isNotBlank()) onPick(null, name) }, dismiss = "Cancel", onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            albums.forEach { a ->
                Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).clickable { onPick(a.id, a.name) }.padding(horizontal = 6.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = DialogColors.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(a.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = DialogColors.title))
                    Text("${a.items.size}", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = DialogColors.body))
                }
            }
        }
        if (albums.isNotEmpty()) Spacer(Modifier.height(8.dp))
        BlazeTextField(name, { name = it.take(60) }, "New album name", modifier = Modifier.fillMaxWidth())
    }
}
