package com.blaze.assistant.mediaapps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blaze.assistant.mediaapps.store.Choice
import com.blaze.assistant.mediaapps.store.JamendoSource
import com.blaze.assistant.mediaapps.store.LinkParse
import com.blaze.assistant.mediaapps.store.LinkSource
import com.blaze.assistant.mediaapps.store.StoreDetail
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.StoreSource
import com.blaze.assistant.mediaapps.store.StoreSources
import com.blaze.assistant.ui.BlazeBrush
import com.blaze.assistant.ui.BlazeDialog
import com.blaze.assistant.ui.BlazeShapes
import com.blaze.assistant.ui.BlazeTextField
import com.blaze.assistant.ui.DialogColors
import com.blaze.assistant.ui.EmptyState
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MotionTokens
import com.blaze.assistant.ui.PillButton
import com.blaze.assistant.ui.Type
import com.blaze.assistant.ui.rememberHomeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.30.1: pieces shared by Blaze Music and Blaze Video: the Library | Store pill (the same look as the
 * Chat | Projects | Home pill), cover art, rows, and the Store (search any StoreSource as a plain thumbnail grid with
 * licences, preview, quality choice, download queue). Never a web view. v0.34.1: the player is no longer a tab; it opens
 * as a sheet over the Library ([PlayerSheet]) and collapses into a mini-player ([MiniBar]).
 */

/** The same pill as Chat | Projects | Home (ui/Shell.kt): faint track, the chosen tab filled with ink, fading across. */
@Composable
fun AppPill(tabs: List<Pair<String, String>>, value: String, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val c = LocalBlaze.current
    Row(modifier.clip(RoundedCornerShape(20.dp)).background(c.faint).padding(3.dp)) {
        tabs.forEach { (id, label) ->
            val on = value == id
            val fill by animateColorAsState(if (on) c.ink else Color.Transparent, MotionTokens.medium(), label = "tab")
            val ink by animateColorAsState(if (on) c.paper else c.sub, MotionTokens.medium(), label = "tabText")
            Box(Modifier.clip(RoundedCornerShape(17.dp)).background(fill).clickable(onClickLabel = "Switch to $label") { onPick(id) }
                .semantics { selected = on }.heightIn(min = 34.dp).padding(horizontal = 14.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                Text(label, maxLines = 1, softWrap = false, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = ink))
            }
        }
    }
}

@Composable
fun MText(text: String, size: TextUnit = 14.sp, color: Color = LocalBlaze.current.ink, weight: FontWeight = FontWeight.Normal,
          maxLines: Int = 1, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Type.Sans, fontWeight = weight, fontSize = size, lineHeight = size * 1.3f, color = color))
}

/** A round icon button (40 dp in a 44 dp target). */
@Composable
fun RoundIcon(icon: ImageVector, desc: String, modifier: Modifier = Modifier, size: Int = 44, tint: Color = LocalBlaze.current.ink,
              fill: Color = Color.Transparent, onClick: () -> Unit) {
    Box(modifier.size(size.dp).clip(BlazeShapes.pill).background(fill, BlazeShapes.pill).clickable(onClickLabel = desc, onClick = onClick)
        .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.52f).dp))
    }
}

// ---------------- cover art ----------------

object ArtCache {
    private val mem = object : android.util.LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private fun sample(w: Int, target: Int): Int { var s = 1; while (w / (s * 2) >= target) s *= 2; return s }

    /** A local cover: the saved thumbnail, the song's embedded picture or a video frame. Null when there's none. */
    fun load(ctx: Context, art: String, uri: String, kind: String, target: Int = 320): ImageBitmap? {
        val key = "$art|$uri|$target"
        mem.get(key)?.let { return it }
        val bmp: Bitmap? = try {
            when {
                art.isNotBlank() && !art.startsWith("http") && java.io.File(art).isFile -> {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(art, o)
                    BitmapFactory.decodeFile(art, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, target) })
                }
                uri.isNotBlank() -> {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(ctx, Uri.parse(uri))
                        if (kind == "video") {
                            if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, target, target * 9 / 16)
                            else r.getFrameAtTime(2_000_000)
                        } else r.embeddedPicture?.let { b ->
                            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(b, 0, b.size, o)
                            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, target) })
                        }
                    } finally { try { r.release() } catch (_: Exception) { } }
                }
                else -> null
            }
        } catch (_: Throwable) { null }
        return bmp?.asImageBitmap()?.also { mem.put(key, it) }
    }
}

@Composable
fun rememberArt(t: Track?, target: Int = 320): ImageBitmap? {
    if (t == null) return null
    if (t.art.startsWith("http")) return rememberHomeImage(t.art)
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, t.key, t.art) { value = withContext(Dispatchers.IO) { ArtCache.load(ctx, t.art, t.uri, t.kind, target) } }
    return img
}

/** Cover art, or a soft gradient with a note / film mark when there's none. */
@Composable
fun Art(t: Track?, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = BlazeShapes.small, target: Int = 320) {
    val img = rememberArt(t, target)
    val c = LocalBlaze.current
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(BlazeBrush.blue.copy(alpha = 0.55f), BlazeBrush.orange.copy(alpha = 0.45f)))),
        contentAlignment = Alignment.Center) {
        if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(if (t?.kind == "video") Icons.Rounded.Movie else Icons.Rounded.MusicNote, contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.fillMaxSize(0.42f))
        if (c.dark && img != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.06f)))
    }
}

@Composable
fun NetThumb(url: String, modifier: Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val img = rememberHomeImage(url)
    val c = LocalBlaze.current
    Box(modifier.background(c.faint)) {
        if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        content()
    }
}

/** The small dark duration badge in a thumbnail's corner. */
@Composable
fun BoxScope.DurationBadge(text: String) {
    if (text.isBlank()) return
    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(BlazeShapes.xs).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = Color.White))
    }
}

// ---------------- Store ----------------

/** One Store's state, kept while the app is open (switching tabs doesn't lose results). */
class StoreState(kind: String) {
    /** Wave A 27: "" = the All chip (every source), the default. */
    var source by mutableStateOf(ForYou.ALL)
    /** Wave A 27-28: the channel (video) / genre (music) chip, "" = All. */
    var chip by mutableStateOf(ForYou.ALL)
    /** The source/query the current page came from, for "load more" (only when one search filled the grid). */
    var pageSource by mutableStateOf("")
    var pageQuery by mutableStateOf("")
    /** Wave A 17: the search in flight (a newer one cancels it) and the query+chip it was for. */
    var job: kotlinx.coroutines.Job? = null
    var fired by mutableStateOf("\u0000")
    var query by mutableStateOf("")
    var results by mutableStateOf<List<StoreItem>>(emptyList())
    var next by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")
    var preview by mutableStateOf<StoreItem?>(null)
    /** v0.32.1: the source of [preview] when it isn't the selected chip's (a pasted link). */
    var previewSource by mutableStateOf<StoreSource?>(null)
    var linkOpen by mutableStateOf(false)
    var searched by mutableStateOf(false)
    var gen by mutableStateOf(0)
    /** v0.30.5: the idea the results came from when the box is empty ("For you" picks), "" for a typed search. */
    var feed by mutableStateOf("")
    /** 0.45.19: sources still answering the search on screen (a skeleton row shows for them). */
    var pending by mutableStateOf(0)
    /** 0.45.19: recent searches (newest first), loaded on first show. */
    var recent by mutableStateOf<List<String>?>(null)
    /** 0.45.20: the last search failed outright (error card with Retry), the source to name, and a saved-results note. */
    var failed by mutableStateOf(false)
    /** 0.45.20: a key that will search was typed: skeletons show at once (spec: under 100 ms), not after the debounce. */
    var typing by mutableStateOf(false)
    var failedSource by mutableStateOf<String?>(null)
    var savedNote by mutableStateOf("")
}

/**
 * The Store tab: pick a source (Jamendo, Internet Archive, public-domain films, Blender open movies, and YouTube when
 * the plug-in is in this build), search, see results as a neat grid (picture, title, author, length, licence), the
 * download queue on top. Tapping a result opens [PreviewSheet]. [onPlay] streams it right away.
 */
@Composable
fun StoreView(kind: String, state: StoreState, onPlay: (StoreItem, StoreDetail, StoreSource) -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by Downloads.jobs.collectAsState()
    val mine = jobs.filter { it.kind == kind && it.status != DlStatus.DONE && it.status != DlStatus.CANCELED }
    val sources = remember(kind) { StoreSources.forKind(kind) }
    // Wave A 27: null = All sources. The setup notice only shows when one source is picked and it needs setup.
    val src = sources.firstOrNull { it.id == state.source }
    var setupTick by remember { mutableStateOf(0) }
    val setup = remember(src?.id, setupTick) { src?.setupNeeded() }
    val ready = remember(kind, setupTick) { sources.filter { it.setupNeeded() == null } }

    // v0.30.5 (Luke: "already scrollable ideas rather than a blank search in case the user isn't feeling inspired"):
    // ideas from what they play and download (Taste), then starters; an empty box shows the first one's results.
    // Wave A 27-28: channels (video) / genres (music) from what they play, "All" first and selected by default.
    val ideas = remember(kind) { Taste.chips(ctx, kind) }
    val seeds = remember(kind) { Taste.seeds(ctx, kind) }
    fun search(more: Boolean = false, idea: String? = null) {
        if (idea != null) state.chip = idea
        val typed = state.query.trim()
        // v0.32.2: a link typed or pasted into the search box goes straight to "Download by link".
        if (!more && idea == null && typed.contains("://") && LinkParse.firstUrl(typed) != null) {
            if (state.loading) return
            state.loading = true; state.error = ""
            scope.launch {
                try { val (item, ls) = LinkSource.resolve(typed, kind); state.previewSource = ls; state.preview = item }
                catch (e: Exception) { state.error = e.message ?: "Couldn't open that link." }
                finally { state.loading = false }
            }
            return
        }
        if (setup != null) return
        if (more && state.loading) return
        // Wave A 17: a new search cancels the one in flight (stale results never land: gen check).
        if (!more) { state.job?.cancel(); state.gen++ }
        val gen = state.gen
        if (more) {
            val s = StoreSources.get(state.pageSource) ?: return
            if (state.next.isBlank()) return
            state.loading = true
            scope.launch {
                try {
                    val p = s.search(kind, state.pageQuery, state.next)
                    if (gen != state.gen) return@launch
                    state.results = (state.results + p.items).distinctBy { it.source + it.id }; state.next = p.next
                } catch (e: Exception) { if (gen == state.gen) state.next = "" }
                finally { if (gen == state.gen) state.loading = false }
            }
            return
        }
        val mixMode = ForYou.isMix(typed, state.chip)
        state.feed = if (mixMode) "For you" else state.chip
        val queries = ForYou.queries(typed, state.chip, seeds)
        val use = (if (src != null) listOf(src) else ready)
        if (use.isEmpty()) return
        // One search per (source, query). In the For you mix a browsable catalogue (public-domain films, Blender)
        // lists its own picks instead of searching taste words it would never match.
        val pairs = use.flatMap { s -> if (mixMode && s.browsable) listOf(s to "") else queries.filter { it.isNotEmpty() || s.browsable }.map { q -> s to q } }
        if (pairs.isEmpty()) return
        state.fired = LiveSearch.key(typed, state.chip)
        state.error = ""; state.searched = true; state.failed = false; state.savedNote = ""
        state.failedSource = pairs.map { it.first.name }.distinct().singleOrNull()
        // 0.45.19 recent searches: a query+chip+source seen in the last 10 minutes shows its results at once.
        val cacheKey = RecentSearches.key(typed, state.chip, src?.id ?: ForYou.ALL)
        RecentSearches.results(kind).get(cacheKey)?.let { hit ->
            state.results = hit.items; state.next = hit.next; state.pageSource = hit.pageSource; state.pageQuery = hit.pageQuery
            state.loading = false; state.pending = 0
            return
        }
        // 0.45.20 offline: a search saved before shows at once with an offline note; a new one gets the "No internet"
        // card with Retry instead of skeletons waiting out a 15 s timeout.
        if (!NetWatch.now(ctx)) {
            state.job?.cancel(); state.pending = 0; state.loading = false; state.next = ""
            val saved = SavedSearchStore.find(ctx, kind, cacheKey)
            if (saved != null) { state.results = saved.items; state.savedNote = "Offline \u00b7 saved results" }
            else { state.results = emptyList(); state.failed = true; state.error = "Connect to search. Downloads still play offline." }
            return
        }
        state.loading = true
        state.results = emptyList(); state.next = ""      // skeleton cards show while it loads (wave A 17)
        state.pending = pairs.size
        state.job = scope.launch {
            // 0.45.19 first results fast: every source's answer lands as it arrives (cards on screen keep their place).
            val slots = arrayOfNulls<List<StoreItem>>(pairs.size)
            val got = pairs.mapIndexed { i, (s, q) ->
                async {
                    val r = runCatching { kotlinx.coroutines.withTimeout(15_000) { s.search(kind, q, "") } }
                    if (gen == state.gen && isActive) {
                        slots[i] = r.getOrNull()?.items.orEmpty()
                        state.results = LiveSearch.land(state.results, ForYou.mix(slots.map { it.orEmpty() }))
                        state.pending = (state.pending - 1).coerceAtLeast(0)
                    }
                    r
                }
            }.map { it.await() }
            if (gen != state.gen || !isActive) return@launch
            state.results = LiveSearch.land(state.results, ForYou.mix(got.map { r -> r.getOrNull()?.items.orEmpty() }))
            state.pending = 0
            // "Load more" pages on only when one search filled the grid.
            if (pairs.size == 1) { state.pageSource = pairs[0].first.id; state.pageQuery = pairs[0].second; state.next = got[0].getOrNull()?.next.orEmpty() }
            else { state.pageSource = ""; state.pageQuery = ""; state.next = "" }
            RecentSearches.results(kind).put(cacheKey, state.results, state.next, state.pageSource, state.pageQuery)
            if (state.results.isNotEmpty()) SavedSearchStore.put(ctx, kind, cacheKey, state.results)   // 0.45.20: offline copy
            state.failed = state.results.isEmpty() && got.all { it.isFailure }
            state.error = when {
                state.results.isNotEmpty() -> ""
                got.all { it.isFailure } -> got.first().exceptionOrNull()?.message ?: "Search didn't work. Check the connection."
                typed.isEmpty() && state.chip.isEmpty() -> "Nothing here right now."
                else -> "Nothing found for \u201c${queries.first()}\u201d."
            }
            state.loading = false
        }
    }
    // Wave A 17: results update as they type, 250 ms (0.45.19) after the last key; each keystroke restarts the wait.
    LaunchedEffect(state.query) {
        val t = state.query.trim()
        if (!state.searched || !LiveSearch.shouldFire(state.fired, t, state.chip)) { state.typing = false; return@LaunchedEffect }
        state.typing = true
        delay(LiveSearch.DEBOUNCE_MS)
        state.typing = false
        search()
    }
    // 0.45.19 step 3: once the results settle, the top ones' details are fetched in parallel (Wi-Fi 2, good signal 1,
    // never under Data Saver), so Preview -> Play doesn't wait on them.
    LaunchedEffect(state.results, state.loading) {
        if (state.loading || state.results.isEmpty()) return@LaunchedEffect
        val n = MediaNet.warmCount(MediaNetDevice.link(ctx), MediaNetDevice.saving(ctx), state.pending)
        if (n == 0) return@LaunchedEffect
        delay(400)
        state.results.take(n).forEach { r ->
            val s = StoreSources.get(r.source) ?: return@forEach
            val k = StreamCache.detailKey(s.id, r.id, kind)
            if (StreamCache.details.get(k) == null) launch {
                runCatching { kotlinx.coroutines.withTimeout(15_000) { s.detail(r, kind) } }.getOrNull()?.let { StreamCache.details.put(k, it) }
            }
        }
    }
    // 0.45.20: the saved (offline) searches are read once, off the main thread.
    LaunchedEffect(kind) { withContext(Dispatchers.IO) { SavedSearchStore.pages(ctx, kind) } }
    val online by remember { NetWatch.start(ctx) }.collectAsState()
    val waitedMs = rememberStallMs(state.loading && state.results.isEmpty())
    val searchNote = NetHonesty.search(state.loading, waitedMs, state.results.isNotEmpty(), state.failed, online)
    // Free sources list something before the first search (popular / the whole catalogue).
    // Wave A 27-28: open on the For you mix, nothing pre-selected.
    LaunchedEffect(src?.id, setup) {
        if (!state.searched && setup == null) search()
    }

    val square = state.results.isNotEmpty() && state.results.all { it.square }
    LazyVerticalGrid(GridCells.Fixed(if (square) 3 else 2), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (sources.size > 1) item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Wave A 27: "All" first and on by default; tapping a source again goes back to All.
                (listOf(ForYou.ALL to "All") + sources.map { it.id to it.name }).forEach { (id, name) ->
                    val on = id == state.source
                    Box(Modifier.clip(BlazeShapes.pill).background(if (on) c.ink else c.faint).clickable {
                        state.source = ForYou.toggle(state.source, id).let { if (on && id == ForYou.ALL) ForYou.ALL else it }
                        state.results = emptyList(); state.next = ""; state.error = ""; state.searched = false; state.loading = false; state.gen++
                    }.semantics { selected = on }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        MText(name, 12.5.sp, if (on) c.paper else c.sub, FontWeight.Medium)
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BlazeTextField(state.query, { state.query = it }, "Search " + (src?.name ?: if (kind == "video") "videos" else "music"),
                    Modifier.weight(1f), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { state.recent = RecentSearches.remember(ctx, kind, state.query); search() }))
                Spacer(Modifier.width(8.dp))
                RoundIcon(Icons.Rounded.Search, "Search", fill = c.faint) { state.recent = RecentSearches.remember(ctx, kind, state.query); search() }
            }
        }
        // 0.45.19 recent searches: shown while the box is empty; a tap searches it again (results cached 10 min).
        val recent = state.recent ?: RecentSearches.load(ctx, kind).also { state.recent = it }
        if (state.query.isBlank() && recent.isNotEmpty() && setup == null) item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MText("Recent", 12.sp, c.sub, FontWeight.Medium)
                recent.forEach { q ->
                    Box(Modifier.clip(BlazeShapes.pill).background(c.faint).clickable { state.query = q; state.recent = RecentSearches.remember(ctx, kind, q); search() }
                        .padding(horizontal = 12.dp, vertical = 7.dp)) { MText(q, 12.5.sp, c.ink) }
                }
                Box(Modifier.clip(BlazeShapes.pill).clickable { RecentSearches.forget(ctx, kind); state.recent = emptyList() }
                    .padding(horizontal = 8.dp, vertical = 7.dp)) { MText("Clear", 12.sp, c.sub) }
            }
        }
        // v0.32.1: "Download by link": a YouTube link or a direct file link, for when the Store's own search fails.
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.clip(BlazeShapes.pill).background(c.faint).clickable { state.linkOpen = true }
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Link, null, Modifier.size(16.dp), tint = c.accent)
                Spacer(Modifier.width(6.dp))
                MText("Download by link", 12.5.sp, c.ink, FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                MText("YouTube or a file link", 11.5.sp, c.sub)
            }
        }
        // Wave A 27-28: "All" then channels (video) / genres (music). A chip narrows the search (typed words kept);
        // tapping it again goes back to All.
        if (setup == null && (src == null || !src.browsable) && ideas.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(ForYou.ALL to "All") + ideas.map { it to it }).forEach { (id, label) ->
                    val on = state.chip == id
                    Box(Modifier.clip(BlazeShapes.pill).background(if (on) c.accent.copy(alpha = 0.22f) else c.faint)
                        .border(1.dp, if (on) c.accent.copy(alpha = 0.7f) else Color.Transparent, BlazeShapes.pill)
                        .clickable {
                            state.chip = ForYou.toggle(state.chip, id)
                            state.results = emptyList(); state.next = ""; state.error = ""; state.loading = false; state.gen++
                            search()
                        }.semantics { selected = on }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                        MText(label, 12.5.sp, if (on) c.ink else c.sub, FontWeight.Medium)
                    }
                }
            }
        }
        if (setup == null && ForYou.isMix(state.query, state.chip) && state.results.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            MText("For you", 15.sp, c.ink, FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
        }
        if (setup != null && src is JamendoSource) item(span = { GridItemSpan(maxLineSpan) }) { JamendoSetup(setup) { setupTick++ } }
        else if (setup != null) item(span = { GridItemSpan(maxLineSpan) }) { MText(setup, 13.sp, c.sub, maxLines = 4) }
        if (mine.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { DownloadsList(mine) }
        // 0.45.20: a search that failed gets an honest card (Retry, saved results when there are any), never a blank grid.
        if (searchNote == NetHonesty.Search.OFFLINE_ERROR || searchNote == NetHonesty.Search.SOURCE_ERROR) item(span = { GridItemSpan(maxLineSpan) }) {
            val savedKey = RecentSearches.key(state.query.trim(), state.chip, src?.id ?: ForYou.ALL)
            val saved = remember(savedKey, state.failed) { SavedSearchStore.find(ctx, kind, savedKey) }
            SearchErrorCard(NetHonesty.errorTitle(searchNote, state.failedSource), state.error, saved != null,
                onRetry = { search() },
                onSaved = { saved?.let { state.results = it.items; state.failed = false; state.error = ""; state.savedNote = "Saved results" } })
        } else if (state.error.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
            MText(state.error, 13.sp, c.sub, maxLines = 4, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (searchNote == NetHonesty.Search.SLOW) item(span = { GridItemSpan(maxLineSpan) }) {
            MText(NetHonesty.slowText(), 13.sp, c.sub, modifier = Modifier.padding(vertical = 4.dp))
        }
        if (state.savedNote.isNotBlank() && state.results.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            MText(state.savedNote, 12.5.sp, c.sub, FontWeight.Medium, modifier = Modifier.padding(vertical = 2.dp))
        }
        // Wave A 17: skeleton cards instead of a blank grid while results load (0.45.20: from the key, not the debounce).
        if ((state.loading && state.results.isEmpty()) || state.typing) items(LiveSearch.skeletons(kind != "video"), key = { "sk$it" }) { SkeletonTile(kind != "video") }
        if (state.results.isEmpty() && !state.loading && !state.typing && state.error.isBlank() && !state.failed && setup == null) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(if (kind == "video") "Find a video" else "Find music",
                "Search, then tap a result to see it, its licence and the qualities. Downloads stay private in Blaze's Library; " +
                    "export or share them from the ⋮ menu.")
        }
        if (!state.typing) items(state.results, key = { it.source + ":" + it.id }) { r ->
            ResultCard(r) {
                if (state.query.isNotBlank()) state.recent = RecentSearches.remember(ctx, kind, state.query)
                state.previewSource = StoreSources.get(r.source); state.preview = r
            }
        }
        // 0.45.19: a skeleton row for the sources still answering, under the results that already landed.
        if (state.results.isNotEmpty() && state.loading) items(LiveSearch.trailing(state.pending, square), key = { "skt$it" }) { SkeletonTile(square) }
        if (state.results.isNotEmpty() && (state.loading || state.next.isNotBlank())) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                if (state.loading) MText("Loading more\u2026", 13.sp, c.sub)
                else {
                    LaunchedEffect(state.results.size) { search(more = true) }   // reached the end: load the next page
                    MText("Loading more\u2026", 13.sp, c.sub)
                }
            }
        }
    }

    if (state.linkOpen) LinkDialog(kind, onDismiss = { state.linkOpen = false }) { item, source ->
        state.linkOpen = false; state.previewSource = source; state.preview = item
    }
    val s = state.previewSource ?: src
    state.preview?.let { item -> if (s != null) PreviewSheet(kind, item, s, onDismiss = { state.preview = null; state.previewSource = null }, onPlay = { d -> state.preview = null; state.previewSource = null; onPlay(item, d, s) },
        onDownload = { d, ch ->
            state.preview = null; state.previewSource = null
            // 0.45.20: downloads are private (no storage permission); the progress notification is asked for now, when it's needed.
            StoragePermission.askNotifications()
            Downloads.add(ctx, item, d, ch, kind)
            Toast.makeText(ctx, "Downloading \u201c${(ch.title.ifBlank { item.title }).take(40)}\u201d (${ch.label.take(30)})", Toast.LENGTH_SHORT).show()
        }) }
}

/**
 * v0.32.1 "Download by link": paste (prefilled from the clipboard when it holds a link) a YouTube link or a music /
 * video file link; Get resolves it ([LinkSource.resolve]) and opens the usual preview sheet with its qualities.
 */
@Composable
fun LinkDialog(kind: String, onDismiss: () -> Unit, onReady: (StoreItem, StoreSource) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember {
        val clip = try { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty() } catch (_: Exception) { "" }
        mutableStateOf(if (LinkParse.firstUrl(clip) != null) clip.trim() else "")
    }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    fun go() {
        if (busy || text.isBlank()) return
        busy = true; err = ""
        scope.launch {
            try { val (item, src) = LinkSource.resolve(text, kind); onReady(item, src) }
            catch (e: Exception) { err = e.message ?: "Couldn't open that link." }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(24.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(DialogColors.surface, shape).border(1.dp, DialogColors.border, shape).padding(18.dp)) {
            MText("Download by link", 17.sp, DialogColors.title, FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            MText("Paste a YouTube link (video, Shorts, YouTube Music) or a link to a " + (if (kind == "video") "video" else "music") + " file.",
                13.sp, DialogColors.body, maxLines = 3)
            Spacer(Modifier.height(12.dp))
            BlazeTextField(text, { text = it; err = "" }, "https://youtu.be/\u2026", Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }))
            if (err.isNotBlank()) { Spacer(Modifier.height(8.dp)); MText(err, 13.sp, BlazeBrush.orange, maxLines = 5) }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Cancel", primary = false, onClick = onDismiss)
                PillButton(if (busy) "Getting\u2026" else "Get", primary = true) { go() }
            }
        }
    }
}

/** Jamendo's "not configured" card: where to get a client_id, and a field to paste it. */
@Composable
fun JamendoSetup(text: String, onSaved: () -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var bad by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(BlazeShapes.medium).background(c.faint).padding(14.dp)) {
        MText("Set up Jamendo", 14.sp, c.ink, FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        MText(text, 12.5.sp, c.sub, maxLines = 5)
        Spacer(Modifier.height(10.dp))
        // v0.30.5 (Luke: "a simple button to take them where they have to go, or simply asking Blaze to get it"):
        // Blaze can do it (its browser + the jamendo_setup tool), or the dev portal opens in the browser.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Ask Blaze to set it up", primary = true) {
                com.blaze.assistant.agent.Agent.send("Set up the Jamendo Store in Blaze Music for me: get a free Jamendo API client_id on " +
                    JamendoSource.DEVPORTAL + " (ask me before creating an account) and save it with " + JamendoSource.TOOL + ".")
                runCatching { ctx.startActivity(android.content.Intent(ctx, com.blaze.assistant.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            }
            PillButton("Get a free ID", primary = false) {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(JamendoSource.DEVPORTAL))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BlazeTextField(id, { id = it }, "Jamendo client_id", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PillButton("Save", primary = true) {
                val v = id.trim()
                if (v.isNotBlank()) scope.launch {
                    checking = true
                    val ok = JamendoSource.works(v)
                    checking = false
                    if (ok) { JamendoSource.setClientId(v); onSaved() } else bad = true
                }
            }
        }
        if (checking || bad) MText(if (checking) "Checking with Jamendo\u2026" else "Jamendo didn't accept that ID.", 12.sp, if (bad) BlazeBrush.orange else c.sub,
            modifier = Modifier.padding(top = 6.dp))
    }
}

/** Android 8–9 need the storage permission to save into Music/ and Movies/ (Android 10+ don't). */
object StoragePermission {
    var asker: (() -> Unit)? = null
    fun ask(ctx: Context) { asker?.invoke() }
    /** 0.45.20 (item 15): the notification prompt, just in time (a download starting), set by the screen that can ask. */
    var notifAsker: (() -> Unit)? = null
    fun askNotifications() { notifAsker?.invoke() }
    /** Android 9 and older: shared Music/Movies/Downloads need the storage permission (Blaze's own storage doesn't). */
    fun needsForExport(ctx: Context): Boolean = Build.VERSION.SDK_INT < 29 &&
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
}

@Composable
fun DownloadsList(jobs: List<DlJob>) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(BlazeShapes.medium).background(c.faint).padding(vertical = 6.dp)) {
        MText("Downloads", 12.sp, c.sub, FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        // Wave B 18: a soft plan line only when the plan's cap is what keeps two or more waiting (no button, no nag).
        val lim by Downloads.limit.collectAsState()
        DlConcurrency.nudge(lim.first, lim.second, jobs.count { it.status == DlStatus.QUEUED && it.attempts == 0 })?.let {
            MText(it, 11.5.sp, c.sub, maxLines = 2, modifier = Modifier.padding(horizontal = 14.dp))
        }
        // Wave B 3: this month's meters for the kinds in the list (server numbers; hidden on an unlimited plan).
        val ent by com.blaze.assistant.connect.Entitlements.snap.collectAsState()
        ent?.let { s ->
            val kinds = jobs.map { com.blaze.assistant.connect.Entitlements.kindOf(it.kind) }.distinct()
            kinds.forEach { k ->
                val ctr = (if (k == "video") s.video else s.song) ?: return@forEach
                val f = com.blaze.assistant.connect.Entitlements.fraction(ctr) ?: return@forEach
                val warn = com.blaze.assistant.connect.Entitlements.near(ctr)
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                    MText((if (k == "video") "Video downloads \u00b7 " else "Song downloads \u00b7 ") + com.blaze.assistant.connect.Entitlements.line(ctr), 11.5.sp,
                        if (warn) BlazeBrush.orange else c.sub)
                    Spacer(Modifier.height(3.dp))
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(BlazeShapes.pill),
                        color = if (warn) BlazeBrush.orange else c.accent, trackColor = c.hair)
                }
            }
        }
        jobs.forEach { j ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                NetThumb(j.thumb, Modifier.size(44.dp).clip(BlazeShapes.xs))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    MText(j.title, 13.sp, c.ink, FontWeight.Medium)
                    val line = DlVerify.statusLine(j) { TimeText.size(it) }
                    MText(line, 11.5.sp, if (j.status == DlStatus.FAILED) BlazeBrush.orange else c.sub)
                    if (j.active || j.status == DlStatus.PAUSED) {
                        Spacer(Modifier.height(4.dp))
                        // Wave B 19: verifying / saving show a moving bar, never a bar stuck at 100%.
                        if (j.phase.isNotEmpty()) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp).clip(BlazeShapes.pill),
                            color = c.accent, trackColor = c.hair)
                        else LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(BlazeShapes.pill),
                            color = c.accent, trackColor = c.hair)
                    }
                }
                when (j.status) {
                    DlStatus.RUNNING, DlStatus.QUEUED -> RoundIcon(Icons.Rounded.Pause, "Pause", size = 38) { Downloads.pause(ctx, j.id) }
                    DlStatus.PAUSED -> RoundIcon(Icons.Rounded.PlayArrow, "Resume", size = 38) { Downloads.resume(ctx, j.id) }
                    DlStatus.FAILED -> RoundIcon(Icons.Rounded.Refresh, "Try again", size = 38) { Downloads.resume(ctx, j.id) }
                    else -> {}
                }
                RoundIcon(Icons.Rounded.Close, if (j.active || j.status == DlStatus.PAUSED) "Cancel" else "Remove", size = 38) { Downloads.dismiss(ctx, j.id) }
            }
        }
    }
}

/** Tap a result: picture, title, author, licence, then the qualities / files on offer, Download and Play / Watch. */
@Composable
fun PreviewSheet(kind: String, item: StoreItem, source: StoreSource, onDismiss: () -> Unit, onPlay: (StoreDetail) -> Unit, onDownload: (StoreDetail, Choice) -> Unit) {
    val ctx = LocalContext.current
    // 0.45.19 step 3: details fetched ahead for the top results open at once (StreamCache.details, 10 min).
    val detailKey = StreamCache.detailKey(source.id, item.id, kind)
    var detail by remember(item.id) { mutableStateOf<StoreDetail?>(StreamCache.details.get(detailKey)) }
    var err by remember(item.id) { mutableStateOf("") }
    var pick by rememberSaveable(item.id) { mutableStateOf("") }
    var tries by remember { mutableStateOf(0) }
    LaunchedEffect(item.id, tries) {
        err = ""
        if (tries == 0 && detail != null) return@LaunchedEffect
        try { detail = source.detail(item, kind).also { StreamCache.details.put(detailKey, it) } } catch (e: Exception) { err = e.message ?: "Couldn't load this item." }
    }
    val d = detail
    val choices = remember(d) { if (d == null) emptyList() else source.choices(kind, d) }
    LaunchedEffect(choices) { if (pick.isEmpty() || choices.none { it.id == pick }) pick = source.defaultChoice(kind, choices)?.id.orEmpty() }
    val licence = d?.licence?.ifBlank { null } ?: item.licence
    val licenceUrl = d?.licenceUrl?.ifBlank { null } ?: item.licenceUrl

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(24.dp)
        Column(Modifier.padding(16.dp).widthIn(max = 460.dp).fillMaxWidth().clip(shape).background(DialogColors.surface, shape)
            .border(1.dp, DialogColors.border, shape).verticalScroll(rememberScrollState())) {
            NetThumb(item.thumb.replace("mqdefault", "hqdefault"), Modifier.fillMaxWidth().aspectRatio(if (item.square) 1.6f else 16f / 9f)) { DurationBadge(item.durationText) }
            Column(Modifier.padding(18.dp)) {
                MText(item.title, 17.sp, DialogColors.title, FontWeight.SemiBold, maxLines = 3)
                Spacer(Modifier.height(4.dp))
                MText(listOf(item.author, item.extra["album"].orEmpty(), item.views).filter { it.isNotBlank() }.joinToString(" \u00b7 "), 13.sp, DialogColors.body, maxLines = 2)
                if (licence.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MText("Licence: $licence", 12.5.sp, DialogColors.primary, FontWeight.Medium,
                            modifier = Modifier.let { m -> if (licenceUrl.startsWith("http")) m.clickable {
                                try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(licenceUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                            } else m })
                        Spacer(Modifier.width(6.dp))
                        MText("\u00b7 ${source.name}", 12.5.sp, DialogColors.body)
                    }
                }
                Spacer(Modifier.height(14.dp))
                when {
                    err.isNotBlank() -> {
                        MText(err, 13.sp, BlazeBrush.orange, maxLines = 5)
                        Spacer(Modifier.height(10.dp))
                        PillButton("Try again", primary = false) { tries++ }
                    }
                    d == null -> MText("Getting qualities\u2026", 13.sp, DialogColors.body)
                    choices.isEmpty() -> MText("No downloadable " + (if (kind == "video") "video" else "audio") + " file is offered for this one.", 13.sp, DialogColors.body, maxLines = 3)
                    else -> {
                        MText(if (kind == "video") "Video quality" else if (choices.any { it.title.isNotBlank() }) "Track and format" else "Audio quality", 12.sp, DialogColors.body, FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        choices.forEach { ch ->
                            val on = ch.id == pick
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(BlazeShapes.small)
                                .background(if (on) DialogColors.primary.copy(alpha = 0.18f) else Color.Transparent)
                                .border(1.dp, if (on) DialogColors.primary else DialogColors.border, BlazeShapes.small)
                                .clickable { pick = ch.id }.semantics { selected = on }.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                MText(ch.label, 14.sp, DialogColors.title, FontWeight.Medium, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                MText(ch.detail, 12.sp, DialogColors.body)
                            }
                        }
                    }
                }
                // v0.34.0: what it costs in data, before it plays or downloads.
                val metered = remember(item.id) { MediaPrefs.metered(ctx) }
                val saving = remember(item.id) { MediaPrefs.saving(ctx) }
                val picked = choices.firstOrNull { it.id == pick }
                val streamChoice = if (saving) VideoSmart.saverChoice(kind, choices) else source.defaultChoice(kind, choices)
                val streamBytes = streamChoice?.bytes ?: 0L
                var confirmWatch by remember(item.id) { mutableStateOf(false) }
                if (d != null && choices.isNotEmpty()) {
                    val dl = VideoSmart.costLine(picked?.bytes ?: 0L, metered, kind).replace(if (kind == "video") "to watch" else "to listen", "to download")
                    val st = VideoSmart.costLine(streamBytes, metered, kind)
                    if (dl.isNotBlank() || st.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        if (st.isNotBlank()) MText((if (kind == "video") "Watch: " else "Play: ") + st + if (saving) " · data saver (${streamChoice?.label.orEmpty().take(24)})" else "",
                            12.sp, if (metered) BlazeBrush.orange else DialogColors.body, maxLines = 2)
                        if (dl.isNotBlank()) MText("Download: " + dl.substringBefore(" · "), 12.sp, DialogColors.body)
                    }
                }
                // Wave B 3: what this month's plan has left, from the server (GET /v1/entitlements), never a hardcoded number.
                val ent by com.blaze.assistant.connect.Entitlements.snap.collectAsState()
                LaunchedEffect(Unit) { if (ent == null) withContext(Dispatchers.IO) { runCatching { com.blaze.assistant.connect.Entitlements.cached(ctx) } } }
                val leftLine = com.blaze.assistant.connect.Entitlements.sheetLine(ent, if (kind == "video") "video" else "song")
                if (d != null && choices.isNotEmpty() && leftLine.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    val none = leftLine.startsWith("No ")
                    MText(leftLine, 12.sp, if (none) BlazeBrush.orange else DialogColors.body, maxLines = 2)
                }
                if (confirmWatch && d != null) BlazeDialog("Watch on mobile data?", "This stream uses about ${TimeText.size(streamBytes)} of your data. Downloaded videos play offline for free.",
                    confirm = "Watch", onConfirm = { confirmWatch = false; onPlay(d) }, dismiss = "Not now", onDismiss = { confirmWatch = false })
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Close", primary = false, onClick = onDismiss)
                    if (d != null && choices.isNotEmpty()) {
                        PillButton(if (kind == "video") "Watch" else "Play", primary = false) { if (VideoSmart.warnFirst(streamBytes, metered)) confirmWatch = true else onPlay(d) }
                        PillButton("Download", primary = true) { choices.firstOrNull { it.id == pick }?.let { onDownload(d, it) } }
                    }
                }
            }
        }
    }
}

/** A row in the music library: cover, title, artist · length, and a trailing slot. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun TrackRow(t: Track, playing: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, selected: Boolean? = null,
             trailing: @Composable () -> Unit = {}) {
    val c = LocalBlaze.current
    // Wave B item 20: press-and-hold starts multi-select; [selected] non-null = selection mode (pick mark instead of ⋮).
    val tap = if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier.clickable(onClick = onClick)
    Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).background(if (playing || selected == true) c.faint else Color.Transparent).then(tap)
        .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(t, Modifier.size(46.dp), target = 140)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            MText(t.title, 14.sp, if (playing) c.accent else c.ink, FontWeight.Medium)
            MText(listOf(t.artist, if (t.durationMs > 0) TimeText.clock(t.durationMs) else "").filter { it.isNotBlank() }.joinToString(" \u00b7 "), 12.sp, c.sub)
        }
        if (selected != null) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { SelectMark(selected) } else trailing()
    }
}

/** Download icon used by the empty library. */
val DownloadIcon: ImageVector get() = Icons.Rounded.Download

// ---------------- v0.34.1: one Library tab; the player as a sheet over it, the mini-player under it ----------------

/**
 * v0.34.1: the full player as a sheet over the Library, with a slim grab handle on top. Dragging the handle down past
 * 140 dp (or flinging it), or tapping it, calls [onCollapse]; the caller then shows [MiniBar] and playback carries on,
 * since nothing here owns the player (the sheet only shows and hides its [content]). Touches never fall through to the
 * Library underneath while it is open.
 */
@Composable
fun PlayerSheet(expanded: Boolean, onCollapse: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalBlaze.current
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val gone = with(LocalDensity.current) { 140.dp.toPx() }
    LaunchedEffect(expanded) { if (expanded) drag.snapTo(0f) }
    AnimatedVisibility(expanded, modifier.fillMaxSize(),
        enter = if (MotionTokens.enabled) slideInVertically(tween(MotionTokens.LONG, easing = MotionTokens.Decelerate)) { it } else EnterTransition.None,
        exit = if (MotionTokens.enabled) slideOutVertically(tween(MotionTokens.MEDIUM, easing = MotionTokens.Accelerate)) { it } else ExitTransition.None) {
        // Registered after the Library's own Back handlers (it is composed above them), so Back collapses the sheet first.
        BackHandler(expanded) { onCollapse() }
        // v0.45.8: the player sheet shows the theme's scene too (opaque over the Library).
        Box(Modifier.fillMaxSize().offset { IntOffset(0, drag.value.roundToInt()) }.background(c.paper)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        if (com.blaze.assistant.ui.themeOn()) com.blaze.assistant.ui.LivingBackground()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(28.dp)
                .draggable(rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } }, Orientation.Vertical,
                    onDragStopped = { v -> if (drag.value > gone || v > 1800f) onCollapse() else drag.animateTo(0f, MotionTokens.drag()) })
                .clickable(onClickLabel = "Collapse the player", onClick = onCollapse)
                .semantics { contentDescription = "Player. Drag down to go back to the Library; it keeps playing." },
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 40.dp, height = 5.dp).clip(BlazeShapes.pill).background(c.weak.copy(alpha = 0.55f)))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
        }
    }
}

/**
 * v0.34.1: the mini-player docked at the bottom while the player sheet is collapsed: art, title, play / pause, close,
 * an optional [extra] button and a thin progress line. Tapping it re-opens the player.
 */
@Composable
fun MiniBar(t: Track?, subtitle: String, playing: Boolean, progress: Float, onOpen: () -> Unit, onPlayPause: () -> Unit, onClose: () -> Unit,
            artRatio: Float = 1f, extra: @Composable () -> Unit = {}) {
    val c = LocalBlaze.current
    Column(Modifier.navigationBarsPadding().padding(10.dp).widthIn(max = 560.dp).fillMaxWidth().clip(BlazeShapes.medium)
        .background(if (c.dark) Color(0xFF1C1C20) else Color(0xFFF1F1F3)).clickable(onClickLabel = "Open player", onClick = onOpen)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(t, Modifier.height(44.dp).aspectRatio(artRatio), target = 160)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                MText(t?.title.orEmpty(), 13.5.sp, c.ink, FontWeight.Medium)
                MText(subtitle, 12.sp, c.sub)
            }
            RoundIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", onClick = onPlayPause)
            extra()
            RoundIcon(Icons.Rounded.Close, "Close the player", tint = c.sub, onClick = onClose)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.hair)) { Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(c.accent)) }
    }
}


/** Wave A 17: live search timing and skeleton counts (pure, LiveSearchTest). 0.45.19: 250 ms, see MediaNetSearchTest. */
object LiveSearch {
    const val DEBOUNCE_MS = 250L
    const val MIN_CHARS = 2

    fun key(typed: String, chip: String): String = typed.trim().lowercase() + "\u0001" + chip

    /** Fire a live search when the words changed, are long enough (or cleared, back to For you), and aren't a link (Enter opens links). */
    fun shouldFire(lastKey: String, typed: String, chip: String): Boolean {
        val t = typed.trim()
        if ("://" in t) return false
        if (t.isNotEmpty() && t.length < MIN_CHARS) return false
        return key(t, chip) != lastKey
    }

    /** How many skeleton cards fill the loading grid: two rows of 16:9 or three rows of squares. */
    fun skeletons(square: Boolean): List<Int> = (0 until if (square) 9 else 6).toList()

    /** 0.45.19: one row of skeleton cards after the results while [pending] sources are still answering. */
    fun trailing(pending: Int, square: Boolean): List<Int> = if (pending <= 0) emptyList() else (0 until if (square) 3 else 2).toList()

    /**
     * 0.45.19 first results fast: each source's answer lands as it comes. Cards already on screen keep their place;
     * the rest of the full mix ([ForYou.mix] of every answer so far) follows, so nothing jumps under the user's thumb.
     */
    fun land(shown: List<StoreItem>, full: List<StoreItem>): List<StoreItem> {
        val have = shown.mapTo(HashSet()) { it.source + ":" + it.id }
        return shown + full.filter { have.add(it.source + ":" + it.id) }
    }

    enum class Card { SKELETON, PARTIAL, READY }

    /**
     * 0.45.19 per-card loading state: no title yet = a skeleton card; a title with its thumbnail still on the way =
     * text with a pulsing picture; a thumbnail that failed (or none) stops pulsing.
     */
    fun card(title: String, thumbUrl: String, thumbLoading: Boolean): Card = when {
        title.isBlank() -> Card.SKELETON
        thumbUrl.isNotBlank() && thumbLoading -> Card.PARTIAL
        else -> Card.READY
    }
}

/**
 * 0.45.19 recent searches: the last [MAX] words searched per Store (newest first, case-insensitive dedupe, links never
 * kept), and the last results per query+chip+source in memory for [TTL_MS], so typing a recent search shows its
 * results at once instead of skeletons.
 */
object RecentSearches {
    const val MAX = 8
    const val TTL_MS = 10 * 60_000L
    const val RESULTS_MAX = 12

    fun add(list: List<String>, q: String, max: Int = MAX): List<String> {
        val t = q.trim().replace(Regex("\\s+"), " ")
        if (t.length < LiveSearch.MIN_CHARS || "://" in t) return list
        return (listOf(t) + list.filterNot { it.equals(t, ignoreCase = true) }).take(max)
    }

    fun encode(list: List<String>): String = list.joinToString("\n")
    fun decode(s: String): List<String> = s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.take(MAX)

    private fun prefKey(kind: String) = "store_recent_" + kind
    fun load(ctx: android.content.Context, kind: String): List<String> = decode(MediaPrefs.str(ctx, prefKey(kind)))
    fun remember(ctx: android.content.Context, kind: String, q: String): List<String> =
        add(load(ctx, kind), q).also { MediaPrefs.setStr(ctx, prefKey(kind), encode(it)) }
    fun forget(ctx: android.content.Context, kind: String) = MediaPrefs.setStr(ctx, prefKey(kind), "")

    /** An LRU of recent result pages; [now] is injected so tests run on a fake clock. */
    class Results(private val max: Int = RESULTS_MAX, private val ttlMs: Long = TTL_MS, private val now: () -> Long = System::currentTimeMillis) {
        private class Hit(val items: List<StoreItem>, val next: String, val pageSource: String, val pageQuery: String, val at: Long)
        private val map = object : LinkedHashMap<String, Hit>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Hit>?) = size > max
        }
        data class Page(val items: List<StoreItem>, val next: String, val pageSource: String, val pageQuery: String)
        @Synchronized fun put(key: String, items: List<StoreItem>, next: String = "", pageSource: String = "", pageQuery: String = "") {
            if (items.isNotEmpty()) map[key] = Hit(items, next, pageSource, pageQuery, now())
        }
        @Synchronized fun get(key: String): Page? {
            val h = map[key] ?: return null
            if (now() - h.at > ttlMs) { map.remove(key); return null }
            return Page(h.items, h.next, h.pageSource, h.pageQuery)
        }
        @Synchronized fun size() = map.size
    }

    /** One results cache per Store kind while the app process lives. */
    val music = Results()
    val video = Results()
    fun results(kind: String) = if (kind == "video") video else music
    fun key(typed: String, chip: String, source: String) = LiveSearch.key(typed, chip) + "\u0001" + source
}

/**
 * 0.45.20: the last [SavedSearches.MAX] result pages per Store in a small file, for searching again offline. Read once
 * (preloaded off the main thread by StoreView), written on a background thread.
 */
object SavedSearchStore {
    private val mem = java.util.concurrent.ConcurrentHashMap<String, List<SavedSearches.Page>>()
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "blaze-saved-search").apply { isDaemon = true } }
    private fun file(ctx: android.content.Context, kind: String) = java.io.File(ctx.applicationContext.filesDir, "store_saved_$kind.json")
    fun pages(ctx: android.content.Context, kind: String): List<SavedSearches.Page> = mem[kind]
        ?: SavedSearches.decode(runCatching { file(ctx, kind).takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()).also { mem[kind] = it }
    fun find(ctx: android.content.Context, kind: String, key: String): SavedSearches.Page? = SavedSearches.find(pages(ctx, kind), key)
    fun put(ctx: android.content.Context, kind: String, key: String, items: List<StoreItem>) {
        val app = ctx.applicationContext
        io.execute {
            val next = SavedSearches.put(pages(app, kind), key, items, System.currentTimeMillis())
            mem[kind] = next
            runCatching {
                val f = file(app, kind); val tmp = java.io.File(f.path + ".tmp")
                tmp.writeText(SavedSearches.encode(next)); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }
}

/** 0.45.19: the thumbnail as it loads: null while on the way, with [loading] false once it landed or failed. */
data class ThumbState(val img: ImageBitmap?, val loading: Boolean)

@Composable
fun rememberThumb(url: String): ThumbState {
    val ctx = LocalContext.current
    return produceState(ThumbState(null, url.isNotBlank()), url) {
        value = if (url.isBlank()) ThumbState(null, false)
        else ThumbState(runCatching { com.blaze.assistant.ui.HomeImages.load(ctx, url) }.getOrNull(), false)
    }.value
}

/**
 * 0.45.19 a Store result card that shows its own loading state ([LiveSearch.card]): a skeleton until it has a title,
 * then the text with a pulsing picture until the thumbnail lands.
 */
@Composable
fun ResultCard(r: StoreItem, onClick: () -> Unit) {
    val c = LocalBlaze.current
    val thumb = rememberThumb(r.thumb)
    when (LiveSearch.card(r.title, r.thumb, thumb.loading)) {
        LiveSearch.Card.SKELETON -> SkeletonTile(r.square)
        else -> Column(Modifier.clip(BlazeShapes.small).clickable(onClick = onClick)) {
            val shape = Modifier.fillMaxWidth().aspectRatio(if (r.square) 1f else 16f / 9f).clip(BlazeShapes.small)
            Box(shape.background(c.faint.copy(alpha = if (thumb.loading) pulse() else 1f))) {
                thumb.img?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                DurationBadge(r.durationText)
            }
            Spacer(Modifier.height(6.dp))
            MText(r.title, 13.sp, c.ink, FontWeight.Medium, maxLines = 2)
            if (r.author.isNotBlank()) MText(r.author, 12.sp, c.sub)
            if (r.licence.isNotBlank()) MText(r.licence, 11.sp, c.accent, FontWeight.Medium)
        }
    }
}

/** The skeleton pulse (0.35-0.75), held still on battery saver. */
@Composable
private fun pulse(): Float {
    val ctx = LocalContext.current
    val still = remember { (ctx.getSystemService(android.os.PowerManager::class.java))?.isPowerSaveMode == true }
    if (still) return 0.55f
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "pl")
    return t.animateFloat(0.35f, 0.75f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "p").value
}

/** A loading placeholder shaped like a result card; it pulses softly, and holds still on battery saver. */
@Composable
fun SkeletonTile(square: Boolean) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val still = remember { (ctx.getSystemService(android.os.PowerManager::class.java))?.isPowerSaveMode == true }
    val a = if (still) 0.55f else {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "sk")
        t.animateFloat(0.35f, 0.75f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "a").value
    }
    Column(Modifier.semantics { contentDescription = "Loading" }) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (square) 1f else 16f / 9f).clip(BlazeShapes.small).background(c.faint.copy(alpha = a)))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.9f).height(11.dp).clip(BlazeShapes.pill).background(c.faint.copy(alpha = a)))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.55f).height(10.dp).clip(BlazeShapes.pill).background(c.faint.copy(alpha = a * 0.8f)))
    }
}
