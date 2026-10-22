package com.blaze.assistant.mediaapps

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.blaze.assistant.mediaapps.store.StoreDetail
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.StoreSource
import com.blaze.assistant.ui.BackButton
import com.blaze.assistant.ui.BlazeDialog
import com.blaze.assistant.ui.BlazeMenu
import com.blaze.assistant.ui.BlazeShapes
import com.blaze.assistant.ui.BlazeTextField
import com.blaze.assistant.ui.BlazeTheme
import com.blaze.assistant.ui.DialogColors
import com.blaze.assistant.ui.EmptyState
import com.blaze.assistant.ui.KitSearch
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MotionTokens
import com.blaze.assistant.ui.PillButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.30.1 Blaze Music: its own full-screen app inside Blaze (like Blaze Browser and Terminal), with the
 * Library | Store pill. Playback runs in [MusicService] (keeps playing with Blaze closed); this screen is a
 * MediaController onto it. v0.34.1: Player and Library are one tab: the full now-playing opens as a sheet over the
 * Library (tab "player" = the sheet is open) and swipes down into the mini-player; playback never stops for it.
 */
class MusicActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TAB = "blaze_media_tab"
        fun open(ctx: Context, tab: String = "") = ctx.startActivity(Intent(ctx, MusicActivity::class.java)
            .putExtra(EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private val controller = mutableStateOf<MediaController?>(null)
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val tab = mutableStateOf("library")
    private val storagePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.getStringExtra(EXTRA_TAB)?.takeIf { it.isNotBlank() }?.let { tab.value = it }
        com.blaze.assistant.mediaapps.store.JamendoSource.init(this)
        lifecycleScope.launch(Dispatchers.IO) { MediaLib.ensure(this@MusicActivity); Downloads.resumePending(this@MusicActivity) }
        StoragePermission.asker = {
            if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { BlazeTheme { MusicApp(controller.value, tab.value, { tab.value = it }, onClose = { BackToChat.go(this) }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.takeIf { it.isNotBlank() }?.let { tab.value = it }
    }

    override fun onStart() {
        super.onStart()
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, MusicService::class.java))).buildAsync()
        future = f
        f.addListener({ try { controller.value = f.get() } catch (_: Exception) { } }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller.value = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }
}

/** What the screen shows of the player, refreshed from the controller's events (and the position 4× a second). */
@Stable
class NowPlaying {
    var item by mutableStateOf<MediaItem?>(null)
    var index by mutableIntStateOf(0)
    var count by mutableIntStateOf(0)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var pos by mutableLongStateOf(0L)
    var dur by mutableLongStateOf(0L)
    var shuffle by mutableStateOf(false)
    var repeat by mutableStateOf(Repeat.OFF)
    var speed by mutableFloatStateOf(1f)
    var error by mutableStateOf("")
    var queue by mutableStateOf<List<MediaItem>>(emptyList())
    var upNext by mutableStateOf<List<Int>>(emptyList())

    fun read(p: Player) {
        item = p.currentMediaItem; index = p.currentMediaItemIndex; count = p.mediaItemCount
        playing = p.isPlaying; buffering = p.playbackState == Player.STATE_BUFFERING
        pos = p.currentPosition.coerceAtLeast(0); dur = p.duration.takeIf { it > 0 } ?: 0
        shuffle = p.shuffleModeEnabled
        repeat = when (p.repeatMode) { Player.REPEAT_MODE_ALL -> Repeat.ALL; Player.REPEAT_MODE_ONE -> Repeat.ONE; else -> Repeat.OFF }
        speed = p.playbackParameters.speed
        error = p.playerError?.let { friendly(it) }.orEmpty()
        queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
        // Up next in the order it will actually play (follows shuffle and repeat-all).
        val tl = p.currentTimeline
        val order = ArrayList<Int>()
        if (!tl.isEmpty && index >= 0) {
            var i = index
            val rep = if (p.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else p.repeatMode
            while (order.size < 100) {
                i = tl.getNextWindowIndex(i, rep, p.shuffleModeEnabled)
                if (i == androidx.media3.common.C.INDEX_UNSET || i == index) break
                order += i
            }
        }
        upNext = order
    }

    private fun friendly(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection. This song needs the internet; downloaded songs play offline."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This file was deleted or moved."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The stream link expired or was refused. Play it again from the Store."
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "This phone can't play this file's format."
        else -> "Couldn't play this: " + (e.message ?: e.errorCodeName)
    }
}

/** The Track behind a queue item: the library's when we have it, else built from the item's metadata (streams). */
fun trackOf(m: MediaItem?): Track? {
    if (m == null) return null
    MediaLib.find(m.mediaId)?.let { return it }
    val md = m.mediaMetadata
    return Track(m.mediaId, m.localConfiguration?.uri?.toString().orEmpty(), md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(),
        md.albumTitle?.toString().orEmpty(), 0, md.artworkUri?.let { if (it.scheme == "file") it.path.orEmpty() else it.toString() }.orEmpty(), "stream")
}

@Composable
fun MusicApp(ctl: MediaController?, tab: String, onTab: (String) -> Unit, onClose: () -> Unit) {
    val c = LocalBlaze.current
    val np = remember { NowPlaying() }
    val store = remember { StoreState("audio") }
    DisposableEffect(ctl) {
        val l = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { np.read(player) } }
        ctl?.addListener(l); ctl?.let { np.read(it) }
        onDispose { ctl?.removeListener(l) }
    }
    LaunchedEffect(ctl, np.playing) {
        while (ctl != null && np.playing) { np.pos = ctl.currentPosition.coerceAtLeast(0); np.dur = ctl.duration.coerceAtLeast(0); delay(250) }
    }
    // v0.34.1: Back collapses the player sheet into the mini-player (it keeps playing).
    BackHandler(tab == "player") { onTab("library") }
    // Wave A item 26: Back from the library or the store goes to the agent chat; the music keeps playing in its service.
    BackHandler(BackToChat.step(false, tab) == BackToChat.Step.TO_CHAT) { onClose() }

    fun playList(list: List<Track>, start: Int, shuffle: Boolean = false) {
        val p = ctl ?: return
        p.setMediaItems(list.map { MusicService.item(it) }, start.coerceIn(0, (list.size - 1).coerceAtLeast(0)), 0)
        p.shuffleModeEnabled = shuffle
        p.prepare(); p.play()
    }

    // v0.45.8: Music shows the picked theme (its scene behind everything) and has the shared theme switcher.
    com.blaze.assistant.ui.ThemedScreen(Modifier.fillMaxSize(), own = { background(c.paper) }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onClose, desc = "Close Music")
                Spacer(Modifier.weight(1f))
                // v0.34.1: one Library entry (the player is a sheet over it now, not a tab).
                AppPill(listOf("library" to "Library", "store" to "Store"), if (tab == "store") "store" else "library", onPick = onTab)
                Spacer(Modifier.weight(1f))
                com.blaze.assistant.ui.ThemeSwitch(Modifier.width(48.dp))
            }
            AnimatedContent(if (tab == "store") "store" else "library", Modifier.weight(1f), transitionSpec = {
                if (MotionTokens.enabled) fadeIn(tween(MotionTokens.MEDIUM)) togetherWith fadeOut(tween(MotionTokens.SHORT)) else fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            }, label = "musicTab") { t ->
                when (t) {
                    "store" -> StoreView("audio", store) { item, d, src -> playStream(ctl, item, d, src); onTab("player") }
                    else -> LibraryPage(np, ::playList)
                }
            }
        }
        // Mini-player: on Library and Store while the player sheet is collapsed; tap re-opens the full player.
        AnimatedVisibility(tab != "player" && np.item != null, Modifier.align(Alignment.BottomCenter),
            enter = MotionTokens.enterUp(), exit = MotionTokens.exitDown()) {
            MiniPlayer(ctl, np) { onTab("player") }
        }
        // The full player: a sheet over the Library with a grab handle; swipe it down to collapse.
        PlayerSheet(tab == "player", onCollapse = { onTab("library") }) { PlayerPage(ctl, np, ::playList) }
    }
}

private fun playStream(ctl: MediaController?, item: StoreItem, d: StoreDetail, src: StoreSource) {
    val p = ctl ?: return
    // v0.34.0 data saver: the smallest stream on mobile data.
    val f = VideoSmart.streamFor("audio", d, src, MusicService.current?.let { MediaPrefs.saving(it) } ?: false).firstOrNull() ?: return
    MusicService.current?.streamUa(src.userAgent)
    Taste.note(MusicService.current, "audio", d.title.ifBlank { item.title }, d.author.ifBlank { item.author }, Taste.PLAY)
    val t = Track("stream:${item.source}:${item.id}", f.url, d.title.ifBlank { item.title }, d.author.ifBlank { item.author }, item.extra["album"].orEmpty(),
        d.lengthSec * 1000L, item.thumb, "stream", 0, item.id, "audio", d.licence.ifBlank { item.licence }, item.source)
    p.setMediaItem(MusicService.streamItem(t, f.url)); p.prepare(); p.play()
}

@Composable
private fun MiniPlayer(ctl: MediaController?, np: NowPlaying, onOpen: () -> Unit) {
    val t = trackOf(np.item)
    MiniBar(t, np.error.ifBlank { t?.artist.orEmpty() }, np.playing, if (np.dur > 0) np.pos.toFloat() / np.dur else 0f, onOpen,
        onPlayPause = { ctl?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } },
        // v0.34.1 close: stops the music and empties the queue (the mini-player goes away with it).
        onClose = { ctl?.let { it.stop(); it.clearMediaItems() } }) {
        RoundIcon(Icons.Rounded.SkipNext, "Next") { ctl?.seekToNext() }
    }
}

// ---------------- Player (now playing) ----------------

@Composable
private fun PlayerPage(ctl: MediaController?, np: NowPlaying, playList: (List<Track>, Int, Boolean) -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val t = trackOf(np.item)
    var view by remember { mutableStateOf("art") }   // art | queue | lyrics
    val liked by MediaLib.liked.collectAsState()
    var sleepMenu by remember { mutableStateOf(false) }
    val sleepAt by MusicService.sleepAt.collectAsState()
    var eqMenu by remember { mutableStateOf(false) }
    var fadeMenu by remember { mutableStateOf(false) }
    var eqName by remember { mutableStateOf(MediaPrefs.str(ctx, MediaPrefs.EQ, "Flat")) }
    var fadeS by remember { mutableIntStateOf(MediaPrefs.int(ctx, MediaPrefs.CROSSFADE, 0)) }
    var sleepFade by remember { mutableStateOf(MediaPrefs.bool(ctx, MediaPrefs.SLEEP_FADE, true)) }

    // Artwork-driven colour: the cover's dominant colour tints the page.
    val art = rememberArt(t, 320)
    val tint = remember(art) {
        art?.let {
            runCatching {
                val small = android.graphics.Bitmap.createScaledBitmap(it.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false), 16, 16, true)
                val px = IntArray(256); small.getPixels(px, 0, 16, 0, 0, 16, 16)
                Color(0xFF000000.toInt() or ArtColors.dominant(px))
            }.getOrNull()
        } ?: c.accent
    }
    val glow by animateColorAsState(tint, MotionTokens.slow(), label = "artTint")

    if (t == null) {
        EmptyState("Nothing playing", "Pick a song in Library, or find one in the Store.")
        return
    }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(glow.copy(alpha = if (c.dark) 0.45f else 0.30f), if (com.blaze.assistant.ui.themeOn()) Color.Transparent else c.paper)))
        .navigationBarsPadding().padding(horizontal = 22.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnimatedContent(view, label = "npView", transitionSpec = { MotionTokens.fade() togetherWith MotionTokens.fadeAway() }) { v ->
                when (v) {
                    "queue" -> QueueList(ctl, np)
                    "lyrics" -> LyricsView(t, np.pos)
                    "karaoke" -> LyricsView(t, np.pos, karaoke = true)
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Art(t, Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f), BlazeShapes.large, target = 720)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MText(t.title, 20.sp, c.ink, FontWeight.SemiBold, maxLines = 2)
                MText(listOf(t.artist, t.licence).filter { it.isNotBlank() }.joinToString(" \u00b7 "), 14.sp, c.sub)
            }
            val on = t.key in liked
            RoundIcon(if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (on) "Unlike" else "Like",
                tint = if (on) Color(0xFFE5484D) else c.ink) {
                if (t.source == "stream") Toast.makeText(ctx, "Download it first to keep it in Liked.", Toast.LENGTH_SHORT).show() else MediaLib.toggleLike(t.key)
            }
        }
        if (np.error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MText(np.error, 12.5.sp, com.blaze.assistant.ui.BlazeBrush.orange, maxLines = 3, modifier = Modifier.weight(1f))
                PillButton("Skip", primary = false) { ctl?.let { it.seekToNext(); it.prepare(); it.play() } }
            }
        }
        // Seek bar: drag shows the target time; the player jumps on release.
        var dragging by remember { mutableStateOf<Float?>(null) }
        val frac = dragging ?: if (np.dur > 0) (np.pos.toFloat() / np.dur).coerceIn(0f, 1f) else 0f
        ScrubBar(frac, { dragging = it }, { dragging?.let { f -> ctl?.seekTo((f * np.dur).toLong()) }; dragging = null },
            active = c.ink, rest = c.hair, knob = c.ink, label = "Seek song")
        Row {
            MText(TimeText.clock(if (dragging != null) (frac * np.dur).toLong() else np.pos), 12.sp, c.sub)
            Spacer(Modifier.weight(1f))
            MText(if (np.dur > 0) TimeText.clock(np.dur) else "", 12.sp, c.sub)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Rounded.Shuffle, if (np.shuffle) "Shuffle on" else "Shuffle off", tint = if (np.shuffle) c.accent else c.sub) { ctl?.shuffleModeEnabled = !np.shuffle }
            RoundIcon(Icons.Rounded.SkipPrevious, "Previous", size = 52) { ctl?.seekToPrevious() }
            Box(Modifier.size(70.dp).clip(BlazeShapes.pill).background(c.ink).clickable(onClickLabel = if (np.playing) "Pause" else "Play") {
                ctl?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); if (it.playbackState == Player.STATE_ENDED) it.seekTo(0, 0); it.play() } }
            }, contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(if (np.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = c.paper, modifier = Modifier.size(38.dp))
            }
            RoundIcon(Icons.Rounded.SkipNext, "Next", size = 52) { ctl?.seekToNext() }
            RoundIcon(if (np.repeat == Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                when (np.repeat) { Repeat.OFF -> "Repeat off"; Repeat.ALL -> "Repeat all"; Repeat.ONE -> "Repeat one" },
                tint = if (np.repeat == Repeat.OFF) c.sub else c.accent) {
                ctl?.repeatMode = when (np.repeat.next()) { Repeat.ALL -> Player.REPEAT_MODE_ALL; Repeat.ONE -> Player.REPEAT_MODE_ONE; Repeat.OFF -> Player.REPEAT_MODE_OFF }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Chip(Speeds.label(np.speed), Icons.Rounded.Speed, np.speed != 1f) { ctl?.setPlaybackSpeed(Speeds.next(np.speed)) }
            Box {
                val left = if (np.pos >= 0) SleepTimer.left(sleepAt, System.currentTimeMillis()) else 0L   // re-read as the position ticks
                Chip(when { sleepAt == -1L -> "End of song"; left > 0 -> TimeText.clock(left); else -> "Sleep" }, Icons.Rounded.Bedtime, sleepAt != 0L) { sleepMenu = true }
                BlazeMenu(sleepMenu, { sleepMenu = false }, buildList {
                    if (sleepAt != 0L) add("Turn off" to { MusicService.sleepAt.value = 0L })
                    SleepTimer.presetsMin.forEach { m -> add("$m minutes" to { MusicService.sleepAt.value = System.currentTimeMillis() + m * 60_000L }) }
                    add("End of this song" to { MusicService.sleepAt.value = -1L })
                    add((if (sleepFade) "Fade out at the end: on" else "Fade out at the end: off") to { sleepFade = !sleepFade; MediaPrefs.setBool(ctx, MediaPrefs.SLEEP_FADE, sleepFade) })
                })
            }
            Box {
                Chip(if (eqName == "Flat") "EQ" else eqName, Icons.Rounded.GraphicEq, eqName != "Flat") { eqMenu = true }
                BlazeMenu(eqMenu, { eqMenu = false }, MusicSmart.presets.keys.map { n ->
                    (n + if (n == eqName) "  \u2713" else "") to { eqName = n; MediaPrefs.setStr(ctx, MediaPrefs.EQ, n); MusicService.current?.applyEq(n); Unit } })
            }
            Box {
                Chip(if (fadeS == 0) "Fade" else "Fade ${fadeS} s", Icons.Rounded.BlurOn, fadeS > 0) { fadeMenu = true }
                BlazeMenu(fadeMenu, { fadeMenu = false }, listOf(0, 3, 6, 10).map { sec ->
                    ((if (sec == 0) "No fade between songs" else "Fade $sec s between songs") + if (sec == fadeS) "  \u2713" else "") to {
                        fadeS = sec; MediaPrefs.setInt(ctx, MediaPrefs.CROSSFADE, sec); Unit } })
            }
            Chip("Lyrics", Icons.Rounded.FormatQuote, view == "lyrics") { view = if (view == "lyrics") "art" else "lyrics" }
            Chip("Karaoke", Icons.Rounded.Mic, view == "karaoke") { view = if (view == "karaoke") "art" else "karaoke" }
            Chip("Queue", Icons.Rounded.KeyboardArrowDown, view == "queue") { view = if (view == "queue") "art" else "queue" }
        }
        // v0.34.1: the rest of the library under the controls, so the bottom is never blank and switching is one tap.
        MoreFromLibrary(np, playList)
    }
}

/** v0.34.1: your other songs under the player; a tap plays that song with the library as the queue. */
@Composable
private fun MoreFromLibrary(np: NowPlaying, playList: (List<Track>, Int, Boolean) -> Unit) {
    val c = LocalBlaze.current
    val songs by MediaLib.songs.collectAsState()
    val playingKey = np.item?.mediaId
    val others = remember(songs, playingKey) { songs.withIndex().filter { it.value.key != playingKey }.take(60) }
    if (others.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        MText("From your library", 13.sp, c.sub, FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(others, key = { it.value.key }) { iv ->
                val t = iv.value
                Column(Modifier.width(88.dp).clip(BlazeShapes.small).clickable(onClickLabel = "Play ${t.title}") { playList(songs, iv.index, false) }) {
                    Art(t, Modifier.size(88.dp), target = 200)
                    Spacer(Modifier.height(4.dp))
                    MText(t.title, 12.sp, c.ink, FontWeight.Medium)
                    MText(t.artist, 11.sp, c.sub)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.clip(BlazeShapes.pill).background(if (on) c.ink else c.faint).clickable(onClickLabel = text, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = if (on) c.paper else c.ink, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        MText(text, 12.5.sp, if (on) c.paper else c.ink, FontWeight.Medium)
    }
}

/** Up next, in play order. Long-press the handle and drag to move a song (when shuffle is off); × removes it. */
@Composable
private fun QueueList(ctl: MediaController?, np: NowPlaying) {
    val c = LocalBlaze.current
    val rowH = 60.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val order = np.upNext
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { MText("Now playing", 12.sp, c.sub, FontWeight.Medium, modifier = Modifier.padding(4.dp)) }
        item { trackOf(np.item)?.let { TrackRow(it, true, onClick = {}) } }
        item {
            MText(if (order.isEmpty()) "Nothing up next" else "Up next" + if (np.shuffle) " \u00b7 shuffled" else "", 12.sp, c.sub, FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp))
        }
        itemsIndexed(order, key = { _, i -> np.queue.getOrNull(i)?.mediaId + "#" + i }) { pos, idx ->
            val t = trackOf(np.queue.getOrNull(idx)) ?: return@itemsIndexed
            val me = dragFrom == pos
            Row(Modifier.fillMaxWidth().height(rowH).graphicsLayer { if (me) { translationY = dragDy; shadowElevation = 12f } }
                .clip(BlazeShapes.small).background(if (me) c.faint else Color.Transparent)
                .clickable { ctl?.seekToDefaultPosition(idx); ctl?.play() }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TrackRow(t, false, onClick = { ctl?.seekToDefaultPosition(idx); ctl?.play() }) }
                RoundIcon(Icons.Rounded.Close, "Remove from queue", size = 38, tint = c.sub) { ctl?.removeMediaItem(idx) }
                if (!np.shuffle) Box(Modifier.size(44.dp).pointerInput(idx) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragFrom = pos; dragDy = 0f },
                        onDrag = { ch, d -> ch.consume(); dragDy += d.y },
                        onDragEnd = {
                            val steps = Math.round(dragDy / rowPx)
                            val toPos = (pos + steps).coerceIn(0, order.size - 1)
                            if (toPos != pos) ctl?.moveMediaItem(idx, order[toPos])
                            dragFrom = -1; dragDy = 0f
                        },
                        onDragCancel = { dragFrom = -1; dragDy = 0f })
                }, contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(Icons.Rounded.DragHandle, contentDescription = "Drag to reorder", tint = c.sub)
                }
            }
        }
    }
}

@Composable
private fun LyricsView(t: Track, pos: Long, karaoke: Boolean = false) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    var res by remember(t.key) { mutableStateOf<Lyrics.Result?>(null) }
    var err by remember(t.key) { mutableStateOf("") }
    var tries by remember { mutableIntStateOf(0) }
    LaunchedEffect(t.key, tries) {
        err = ""
        try { res = withContext(Dispatchers.IO) { Lyrics.get(ctx.filesDir, t) } } catch (e: Exception) { err = "Couldn't load lyrics (${e.message ?: "no connection"})." }
    }
    val r = res
    when {
        err.isNotBlank() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MText(err, 13.sp, c.sub, maxLines = 3); Spacer(Modifier.height(8.dp)); PillButton("Try again", primary = false) { tries++ }
        }
        r == null -> MText("Looking for lyrics\u2026", 14.sp, c.sub)
        r.instrumental -> MText("Instrumental", 16.sp, c.sub)
        r.empty -> MText("No lyrics found for this song.", 14.sp, c.sub)
        karaoke && r.synced.isEmpty() -> MText("Karaoke needs synced lyrics, and this song only has plain ones.", 14.sp, c.sub, maxLines = 3)
        karaoke -> KaraokeView(r.synced, pos)
        r.synced.isNotEmpty() -> {
            val cur = Lrc.current(r.synced, pos)
            val list = rememberLazyListState()
            LaunchedEffect(cur) { if (cur >= 0) { if (MotionTokens.enabled) list.animateScrollToItem((cur - 2).coerceAtLeast(0)) else list.scrollToItem((cur - 2).coerceAtLeast(0)) } }
            LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(vertical = 40.dp)) {
                itemsIndexed(r.synced) { i, l ->
                    val on = i == cur
                    val col by animateColorAsState(if (on) c.ink else c.weak, MotionTokens.medium(), label = "lyric")
                    MText(l.text.ifBlank { "\u266A" }, if (on) 21.sp else 19.sp, col, FontWeight.SemiBold, maxLines = 4, modifier = Modifier.padding(vertical = 7.dp))
                }
                item { MText("Lyrics: LRCLIB", 11.sp, c.weak, modifier = Modifier.padding(top = 16.dp)) }
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 20.dp)) {
            item { androidx.compose.material3.Text(r.plain, style = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 26.sp, color = c.ink)) }
            item { MText("Lyrics: LRCLIB", 11.sp, c.weak, modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}

// ---------------- Library ----------------

@Composable
private fun LibraryPage(np: NowPlaying, playList: (List<Track>, Int, Boolean) -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val songs by MediaLib.songs.collectAsState()
    val liked by MediaLib.liked.collectAsState()
    val recent by MediaLib.recent.collectAsState()
    val lists by MediaLib.playlists.collectAsState()
    var section by remember { mutableStateOf("foryou") }
    var q by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    var openList by remember { mutableStateOf<String?>(null) }
    var addTo by remember { mutableStateOf<List<String>?>(null) }
    var naming by remember { mutableStateOf<Pair<String?, String>?>(null) }   // (playlist id or null = new, name)
    var confirmDel by remember { mutableStateOf<Track?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { MediaLib.refresh() } }
    val playingKey = np.item?.mediaId
    val byKey = remember(songs) { songs.associateBy { it.key } }
    // Wave B item 20: press-and-hold multi-select (keys of the picked songs; empty = not selecting).
    var sel by remember { mutableStateOf(LibrarySelection.NONE) }
    var confirmMany by remember { mutableStateOf<List<Track>?>(null) }
    val scope = rememberCoroutineScope()
    val selecting = LibrarySelection.active(sel)
    val found = remember(songs, q) { LibraryGroups.search(songs, q) }
    // What Select all picks: the songs on screen in the current section.
    val shown: List<Track> = remember(songs, liked, lists, section, q, group, openList, found) {
        when {
            q.isNotBlank() -> found
            section == "songs" -> songs
            section == "liked" -> liked.mapNotNull { byKey[it] }
            section == "artists" || section == "albums" -> group?.let { g ->
                (if (section == "artists") LibraryGroups.artists(songs) else LibraryGroups.albums(songs)).firstOrNull { it.first == g }?.second } ?: emptyList()
            section == "playlists" -> lists.firstOrNull { it.id == openList }?.keys?.mapNotNull { byKey[it] }?.distinctBy { it.key } ?: emptyList()
            else -> emptyList()
        }
    }
    val shownKeys = remember(shown) { shown.map { it.key } }
    // Songs deleted (or a section change hiding them) drop out of the selection.
    LaunchedEffect(shownKeys) { sel = LibrarySelection.prune(sel, shownKeys) }
    val exportTree = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val picked = LibrarySelection.picked(sel, shown)
        if (tree != null && picked.isNotEmpty()) scope.launch {
            Toast.makeText(ctx, "Exporting ${picked.size}\u2026", Toast.LENGTH_SHORT).show()
            val n = withContext(Dispatchers.IO) { MediaExport.exportAll(ctx, picked, tree) }
            Toast.makeText(ctx, if (n == picked.size) "Exported $n" else "Exported $n of ${picked.size}", Toast.LENGTH_SHORT).show()
            sel = LibrarySelection.clear()
        }
    }

    BackHandler(group != null || openList != null) { group = null; openList = null }
    // Registered after the group handler, so Back leaves selection mode first.
    BackHandler(LibrarySelection.backClears(sel)) { sel = LibrarySelection.clear() }

    @Composable
    fun SongRow(t: Track, list: List<Track>, i: Int, playlist: Playlist? = null, pIndex: Int = -1) {
        var menu by remember { mutableStateOf(false) }
        TrackRow(t, t.key == playingKey && !selecting,
            onClick = { if (selecting) sel = LibrarySelection.toggle(sel, t.key) else playList(list, i, false) },
            onLongClick = { sel = if (selecting) LibrarySelection.toggle(sel, t.key) else LibrarySelection.start(t.key) },
            selected = if (selecting) t.key in sel else null) {
            Box {
                RoundIcon(Icons.Rounded.MoreVert, "More", size = 40, tint = c.sub) { menu = true }
                BlazeMenu(menu, { menu = false }, buildList {
                    add((if (t.key in liked) "Remove from Liked" else "Like") to { MediaLib.toggleLike(t.key) })
                    add("Add to playlist\u2026" to { addTo = listOf(t.key) })
                    if (playlist != null) {
                        add("Remove from this playlist" to { MediaLib.setPlaylists(PlaylistOps.removeAt(lists, playlist.id, pIndex, System.currentTimeMillis())) })
                        if (pIndex > 0) add("Move up" to { MediaLib.setPlaylists(PlaylistOps.move(lists, playlist.id, pIndex, pIndex - 1, System.currentTimeMillis())) })
                        if (pIndex < playlist.keys.size - 1) add("Move down" to { MediaLib.setPlaylists(PlaylistOps.move(lists, playlist.id, pIndex, pIndex + 1, System.currentTimeMillis())) })
                    }
                    if (t.source == "download") add("Delete from phone" to { confirmDel = t })
                })
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        if (selecting) SelectionBar(LibrarySelection.label(sel), LibrarySelection.allSelected(sel, shownKeys),
            onClose = { sel = LibrarySelection.clear() }, onSelectAll = { sel = LibrarySelection.selectAll(sel, shownKeys) },
            onShare = {
                val i = MediaExport.shareIntent(ctx, LibrarySelection.picked(sel, shown))
                if (i == null) Toast.makeText(ctx, "These can't be shared", Toast.LENGTH_SHORT).show() else runCatching { ctx.startActivity(i) }
            },
            onExport = { runCatching { exportTree.launch(null) }.onFailure { Toast.makeText(ctx, "No file picker on this phone", Toast.LENGTH_SHORT).show() } },
            onDelete = {
                val p = LibrarySelection.picked(sel, shown)
                if (LibrarySelection.deletable(p).isEmpty()) Toast.makeText(ctx, "Gallery songs are deleted in Gallery", Toast.LENGTH_SHORT).show() else confirmMany = p
            },
            onAddToPlaylist = { addTo = LibrarySelection.picked(sel, shown).map { it.key } })
        else KitSearch(q, { q = it; group = null; openList = null }, "Search your music")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("foryou" to "For you", "songs" to "Songs", "liked" to "Liked", "artists" to "Artists", "albums" to "Albums", "playlists" to "Playlists").forEach { (id, label) ->
                val on = section == id
                Box(Modifier.clip(BlazeShapes.pill).background(if (on) c.ink else c.faint).clickable { section = id; group = null; openList = null; sel = LibrarySelection.clear() }
                    .padding(horizontal = 14.dp, vertical = 7.dp)) { MText(label, 13.sp, if (on) c.paper else c.sub, FontWeight.Medium) }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 110.dp)) {
            when {
                q.isNotBlank() -> {
                    if (found.isEmpty()) item { EmptyState("No match", "Nothing in your library matches \u201c$q\u201d. Try the Store.") }
                    itemsIndexed(found, key = { _, t -> "q" + t.key }) { i, t -> SongRow(t, found, i) }
                }
                section == "foryou" -> item { ForYou(songs, liked, playList) }
                section == "songs" -> {
                    val rec = recent.mapNotNull { byKey[it] }.take(15)
                    if (rec.isNotEmpty()) {
                        item { MText("Recently played", 15.sp, c.ink, FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp)) }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(rec, key = { _, t -> "r" + t.key }) { i, t ->
                                    Column(Modifier.width(118.dp).clip(BlazeShapes.small).clickable { playList(rec, i, false) }) {
                                        Art(t, Modifier.size(118.dp), target = 240)
                                        Spacer(Modifier.height(4.dp))
                                        MText(t.title, 12.5.sp, c.ink, FontWeight.Medium)
                                        MText(t.artist, 11.5.sp, c.sub)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            MText("All songs \u00b7 ${songs.size}", 15.sp, c.ink, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (songs.isNotEmpty()) { PillButton("Shuffle", primary = false) { playList(songs, (songs.indices).random(), true) } }
                        }
                    }
                    if (songs.isEmpty()) item {
                        EmptyState("No songs yet", "Songs you download in the Store, and music in Blaze's Gallery, show up here and play offline.")
                    }
                    itemsIndexed(songs, key = { _, t -> t.key }) { i, t -> SongRow(t, songs, i) }
                }
                section == "liked" -> {
                    val l = liked.mapNotNull { byKey[it] }
                    if (l.isEmpty()) item { EmptyState("No liked songs", "Tap the heart on the player to keep a song here.") }
                    else item { Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Play", primary = true) { playList(l, 0, false) }; PillButton("Shuffle", primary = false) { playList(l, l.indices.random(), true) } } }
                    itemsIndexed(l, key = { _, t -> "l" + t.key }) { i, t -> SongRow(t, l, i) }
                }
                section == "artists" || section == "albums" -> {
                    val groups = if (section == "artists") LibraryGroups.artists(songs) else LibraryGroups.albums(songs)
                    val g = group
                    if (g == null) {
                        if (groups.isEmpty()) item { EmptyState("Nothing here yet", "Download some music in the Store.") }
                        items(groups, key = { section + it.first }) { (name, list) ->
                            Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).clickable { group = name }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Art(list.first(), Modifier.size(52.dp), if (section == "artists") BlazeShapes.pill else BlazeShapes.small, target = 140)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    MText(name, 14.5.sp, c.ink, FontWeight.Medium)
                                    MText("${list.size} song" + if (list.size == 1) "" else "s", 12.sp, c.sub)
                                }
                            }
                        }
                    } else {
                        val list = groups.firstOrNull { it.first == g }?.second.orEmpty()
                        item {
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                BackButton({ group = null }); MText(g, 17.sp, c.ink, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                PillButton("Play", primary = true) { playList(list, 0, false) }
                            }
                        }
                        itemsIndexed(list, key = { _, t -> "g" + t.key }) { i, t -> SongRow(t, list, i) }
                    }
                }
                else -> {   // playlists
                    val open = lists.firstOrNull { it.id == openList }
                    if (open == null) {
                        item {
                            Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).clickable { naming = null to "" }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(52.dp).clip(BlazeShapes.small).background(c.faint), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.Icon(Icons.Rounded.Add, contentDescription = null, tint = c.ink)
                                }
                                Spacer(Modifier.width(12.dp)); MText("New playlist", 14.5.sp, c.ink, FontWeight.Medium)
                            }
                        }
                        items(lists, key = { "p" + it.id }) { p ->
                            Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).clickable { openList = p.id }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Art(p.keys.firstNotNullOfOrNull { byKey[it] }, Modifier.size(52.dp), target = 140)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    MText(p.name, 14.5.sp, c.ink, FontWeight.Medium)
                                    MText("${p.keys.size} song" + if (p.keys.size == 1) "" else "s", 12.sp, c.sub)
                                }
                            }
                        }
                    } else {
                        val tracks = open.keys.mapNotNull { byKey[it] }
                        item {
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                BackButton({ openList = null })
                                MText(open.name, 17.sp, c.ink, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                RoundIcon(Icons.Rounded.Edit, "Rename", size = 40) { naming = open.id to open.name }
                                RoundIcon(Icons.Rounded.Delete, "Delete playlist", size = 40) { MediaLib.setPlaylists(PlaylistOps.delete(lists, open.id)); openList = null }
                            }
                        }
                        if (tracks.isEmpty()) item { EmptyState("Empty playlist", "Use \u22ee \u2192 Add to playlist on any song.") }
                        else item { Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("Play", primary = true) { playList(tracks, 0, false) }; PillButton("Shuffle", primary = false) { playList(tracks, tracks.indices.random(), true) } } }
                        // Indices into open.keys (a missing song keeps its slot so moves stay right).
                        open.keys.forEachIndexed { pi, k ->
                            val t = byKey[k] ?: return@forEachIndexed
                            item(key = "pl$pi$k") { SongRow(t, tracks, tracks.indexOf(t), open, pi) }
                        }
                    }
                }
            }
        }
    }

    addTo?.let { keys ->
        BlazeDialog("Add to playlist", confirm = "New playlist", onConfirm = { addTo = null; naming = null to ""; pendingAdd = keys; sel = LibrarySelection.clear() }, dismiss = "Cancel", onDismiss = { addTo = null }) {
            Column(Modifier.fillMaxWidth()) {
                if (lists.isEmpty()) MText("No playlists yet.", 13.sp, DialogColors.body)
                lists.forEach { p ->
                    Box(Modifier.fillMaxWidth().clip(BlazeShapes.small).clickable {
                        MediaLib.setPlaylists(PlaylistOps.add(lists, p.id, keys, System.currentTimeMillis())); addTo = null; sel = LibrarySelection.clear()
                        Toast.makeText(ctx, "Added to ${p.name}", Toast.LENGTH_SHORT).show()
                    }.padding(horizontal = 8.dp, vertical = 12.dp)) { MText(p.name, 15.sp, DialogColors.title) }
                }
            }
        }
    }
    naming?.let { (id, start) ->
        var name by remember(id) { mutableStateOf(start) }
        BlazeDialog(if (id == null) "New playlist" else "Rename playlist", confirm = "Save", onConfirm = {
            val now = System.currentTimeMillis()
            if (id == null) {
                val nid = MediaLib.newPlaylistId()
                var l = PlaylistOps.create(lists, name, nid, now)
                pendingAdd?.let { l = PlaylistOps.add(l, nid, it, now) }
                MediaLib.setPlaylists(l)
            } else MediaLib.setPlaylists(PlaylistOps.rename(lists, id, name, now))
            pendingAdd = null; naming = null
        }, dismiss = "Cancel", onDismiss = { naming = null; pendingAdd = null }) {
            BlazeTextField(name, { name = it }, "Playlist name", Modifier.fillMaxWidth())
        }
    }
    confirmDel?.let { t ->
        BlazeDialog("Delete \u201c${t.title.take(40)}\u201d?", "It's removed from your phone and from every playlist.", confirm = "Delete", warm = true,
            onConfirm = { MediaLib.deleteDownload(ctx, t.key); confirmDel = null }, dismiss = "Cancel", onDismiss = { confirmDel = null })
    }
    confirmMany?.let { picked ->
        val (title, body) = LibrarySelection.deleteText(picked, "audio")
        BlazeDialog(title, body, confirm = "Delete", warm = true, onConfirm = {
            val keys = LibrarySelection.deletable(picked).map { it.key }
            confirmMany = null; sel = LibrarySelection.clear()
            scope.launch { withContext(Dispatchers.IO) { MediaLib.deleteDownloads(ctx, keys) } }
        }, dismiss = "Cancel", onDismiss = { confirmMany = null })
    }
}

/** Songs waiting to go into a playlist that's being created from "Add to playlist → New playlist". */
private var pendingAdd: List<String>? = null

// ---------------- v0.34.0 karaoke + For you ----------------

/** Karaoke: the line being sung, big, filling left to right as it goes; the next line under it. */
@Composable
private fun KaraokeView(lines: List<LyricLine>, pos: Long) {
    val c = LocalBlaze.current
    val cur = Lrc.current(lines, pos)
    val line = lines.getOrNull(cur)
    val next = lines.getOrNull(cur + 1)
    val start = line?.ms ?: 0L
    val end = next?.ms ?: (start + 4_000)
    val frac = if (line == null || end <= start) 0f else ((pos - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxSize().padding(vertical = 24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val text = line?.text?.ifBlank { "\u266A" } ?: "\u266A"
        Box {
            MText(text, 30.sp, c.weak, FontWeight.Bold, maxLines = 4)
            MText(text, 30.sp, c.accent, FontWeight.Bold, maxLines = 4, modifier = Modifier.drawWithContent {
                clipRect(right = size.width * frac) { this@drawWithContent.drawContent() } })
        }
        Spacer(Modifier.height(22.dp))
        MText(next?.text.orEmpty(), 19.sp, c.sub, FontWeight.Medium, maxLines = 3)
        Spacer(Modifier.height(30.dp))
        MText("Karaoke \u00b7 lyrics: LRCLIB", 11.sp, c.weak)
    }
}

/** For you: Blaze DJ, daily mixes, smart playlists, Wrapped, playlist import and the tag fixer. */
@Composable
private fun ForYou(songs: List<Track>, liked: List<String>, playList: (List<Track>, Int, Boolean) -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val plays by MediaLib.plays.collectAsState()
    val byKey = remember(songs) { songs.associateBy { it.key } }
    val now = remember(plays.size) { System.currentTimeMillis() }
    val mixes = remember(songs, plays.size) { MusicSmart.dailyMixes(songs, plays, now / 86_400_000L) }
    val smart = remember(songs, plays.size, liked) { MusicSmart.smartLists(songs, plays, liked, now) }
    var wrapped by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Blaze DJ", primary = true) {
                val svc = MusicService.current
                if (svc == null) android.widget.Toast.makeText(ctx, "Play any song first, then start Blaze DJ.", android.widget.Toast.LENGTH_SHORT).show()
                else { svc.startDj(true); android.widget.Toast.makeText(ctx, "Blaze DJ is on: it keeps the music going with songs you love.", android.widget.Toast.LENGTH_SHORT).show() }
            }
            PillButton("Wrapped", primary = false) { wrapped = MusicExtras.wrappedText(MusicExtras.wrapped("year")) }
            PillButton("Import playlist", primary = false) { importing = true }
            PillButton(if (busy.isNotBlank()) busy else "Fix tags & covers", primary = false) {
                if (busy.isNotBlank()) return@PillButton
                busy = "Fixing\u2026"
                scope.launch {
                    val r = MusicExtras.fixTags(ctx) { i, n -> busy = "Fixing $i of $n" }
                    busy = ""
                    android.widget.Toast.makeText(ctx, "Checked ${r.checked} songs: ${r.renamed} renamed, ${r.covers} new covers.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        if (songs.isEmpty()) { Spacer(Modifier.height(16.dp)); EmptyState("Nothing to mix yet", "Download songs in the Store or import a playlist; mixes and smart playlists build from what you play.") }
        @Composable
        fun ListRow(l: MusicSmart.SmartList) {
            val tracks = l.keys.mapNotNull { byKey[it] }
            if (tracks.isEmpty()) return
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(BlazeShapes.small).clickable { playList(tracks, 0, false) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Art(tracks.first(), Modifier.size(52.dp), BlazeShapes.small, target = 140)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    MText(l.name, 14.5.sp, c.ink, FontWeight.SemiBold)
                    MText("${l.about} \u00b7 ${tracks.size} songs", 12.sp, c.sub)
                }
                RoundIcon(Icons.Rounded.Shuffle, "Shuffle ${l.name}", size = 40, tint = c.sub) { playList(tracks, tracks.indices.random(), true) }
            }
        }
        if (mixes.isNotEmpty()) { Spacer(Modifier.height(12.dp)); MText("Daily mixes", 15.sp, c.ink, FontWeight.SemiBold); mixes.forEach { ListRow(it) } }
        if (smart.isNotEmpty()) { Spacer(Modifier.height(12.dp)); MText("Smart playlists", 15.sp, c.ink, FontWeight.SemiBold); smart.forEach { ListRow(it) } }
    }
    wrapped?.let { w -> BlazeDialog("Your Wrapped", w, confirm = "Nice", onConfirm = { wrapped = null }, onDismiss = { wrapped = null }) }
    if (importing) ImportDialog { importing = false }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    BlazeDialog("Import a playlist", "Paste a YouTube or Spotify playlist link (Spotify albums work too). Blaze finds each song, downloads it and builds the playlist.",
        confirm = if (busy) "Importing\u2026" else if (msg.isNotBlank()) "Done" else "Import", onConfirm = {
            if (msg.isNotBlank()) { onDismiss(); return@BlazeDialog }
            if (busy || text.isBlank()) return@BlazeDialog
            busy = true
            scope.launch {
                msg = try { MusicExtras.importPlaylist(ctx, text.trim()) } catch (e: Exception) { "Couldn't import: ${e.message ?: "no connection"}" }
                busy = false
            }
        }, dismiss = "Cancel", onDismiss = onDismiss) {
        BlazeTextField(text, { text = it; msg = "" }, "https://open.spotify.com/playlist/\u2026", Modifier.fillMaxWidth())
        if (msg.isNotBlank()) { Spacer(Modifier.height(8.dp)); MText(msg, 13.sp, DialogColors.body, maxLines = 8) }
    }
}
