package com.blaze.assistant.mediaapps

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.blaze.assistant.mediaapps.store.StoreDetail
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.StoreSource
import com.blaze.assistant.mediaapps.store.StoreSubtitle
import com.blaze.assistant.mediaapps.store.LinkSource
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.exoplayer.source.MediaSource
import android.widget.Toast
import com.blaze.assistant.ui.BackButton
import com.blaze.assistant.ui.BlazeBrush
import com.blaze.assistant.ui.BlazeDialog
import com.blaze.assistant.ui.BlazeMenu
import com.blaze.assistant.ui.BlazeShapes
import com.blaze.assistant.ui.BlazeTheme
import com.blaze.assistant.ui.DialogColors
import com.blaze.assistant.ui.EmptyState
import com.blaze.assistant.ui.KitSearch
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MotionTokens
import com.blaze.assistant.ui.PillButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.30.1 Blaze Video: its own full-screen app inside Blaze with the Library | Store pill (v0.34.1: the player is a
 * sheet over the Library, tab "player" = the sheet is open; swiping it down leaves a mini-player and keeps playing). ExoPlayer with
 * gestures (left half up/down = brightness, right half = volume, double-tap ±10 s; no swipe-seek since wave A),
 * fullscreen with rotation, picture-in-picture, speed, subtitle and audio-track choice, and resume where you left off.
 */
class VideoActivity : ComponentActivity(), com.blaze.assistant.ui.BackRule.ToChat {
    companion object {
        fun open(ctx: Context, tab: String = "") = ctx.startActivity(Intent(ctx, VideoActivity::class.java)
            .putExtra(MusicActivity.EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        const val EXTRA_PLAY = "blaze_video_play"
        /** v0.34.0: plays a library video (agent tool video_ops play). */
        fun openAndPlay(ctx: Context, key: String) = ctx.startActivity(Intent(ctx, VideoActivity::class.java)
            .putExtra(EXTRA_PLAY, key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        /** The open Video app (same process), for the agent's video_ops tool. */
        @Volatile var live: VideoActivity? = null
            private set
    }

    // ---------------------------------------------------------------- v0.34.0 smart video
    /** The next episode waiting on the "Up next" countdown (null = none). */
    val upNext = mutableStateOf<Track?>(null)
    var upNextAt = 0L
    /** Recap / translation progress and results for the player screen. */
    val recapText = mutableStateOf<String?>(null)
    val work = mutableStateOf("")
    private var intros: MutableMap<String, Long> = HashMap()

    fun seriesKey(t: Track?): String? = t?.let { VideoSmart.episode(it.title)?.key }
    fun introEnd(t: Track?): Long = seriesKey(t)?.let { intros[it] } ?: 0L

    fun markIntroEnd() {
        val k = seriesKey(current.value) ?: return
        val at = player.currentPosition
        intros[k] = at; MediaPrefs.setIntro(this, k, at)
        Toast.makeText(this, "Skip intro will jump to ${TimeText.clock(at)} in every episode.", Toast.LENGTH_SHORT).show()
    }

    fun skipIntro() { val e = introEnd(current.value); if (e > 0) player.seekTo(e) }

    private fun onEnded() {
        val t = current.value ?: return
        if (t.source != "stream") MediaPrefs.markFinished(this, t.key, System.currentTimeMillis())
        if (!MediaPrefs.autoplay(this) || t.source == "stream") return
        val next = VideoSmart.next(t, MediaLib.videos.value) ?: return
        upNextAt = System.currentTimeMillis(); upNext.value = next
    }

    fun cancelUpNext() { upNext.value = null }

    /** The subtitle track that's on now (or the first loaded one) with its cues. */
    private fun activeCues(): Pair<StoreSubtitle, List<Cue>>? {
        val on = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.let { g ->
            (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val t = subsLoaded.firstOrNull { s -> on != null && (on.id == s.id || on.id?.endsWith(":" + s.id) == true || on.label == Subs.label(s)) }
            ?: subsLoaded.firstOrNull() ?: return null
        val cues = SubsLoader.cues(t)
        return if (cues.isEmpty()) null else t to cues
    }

    /** A first subtitle track to work from when none is loaded: the viewer's language, else English, else any. */
    private suspend fun ensureSomeSubs(): Pair<StoreSubtitle, List<Cue>>? {
        activeCues()?.let { return it }
        val t = current.value ?: return null
        val cand = Subs.ordered(subsAvailable.value, java.util.Locale.getDefault().language).firstOrNull() ?: return null
        val local = if (cand.url.startsWith("file:")) cand else {
            val cues = SubsLoader.fetchYouTube(cand); if (cues.isEmpty()) return null
            withContext(Dispatchers.IO) { SubsLoader.save(this@VideoActivity, t.key, cand, cues) }
        }
        val cues = SubsLoader.cues(local)
        return if (cues.isEmpty()) null else local to cues
    }

    /** "Recap so far": what was said up to here, summed up by Blaze's model. Null result = it couldn't. */
    suspend fun recap(): String {
        val t = current.value ?: return "Nothing is playing."
        work.value = "Writing a recap…"
        try {
            val (_, cues) = ensureSomeSubs() ?: return "This video has no subtitles, so there's nothing to recap from. Load a .srt or .vtt file first."
            val text = VideoSmart.transcript(cues, player.currentPosition)
            if (text.isBlank()) return "Nothing has been said yet at this point."
            val msgs = org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", VideoSmart.recapPrompt(t.title, text)))
            val out = com.blaze.assistant.agent.LlmClient.complete(msgs, null, null, null, com.blaze.assistant.agent.LlmClient.Lane.BACKGROUND)
                .optString("content").substringAfterLast("</think>").trim()
            return out.ifBlank { "Blaze couldn't write a recap right now. Try again in a moment." }
        } catch (e: Exception) {
            return "Blaze couldn't write a recap: " + (e.message ?: "no connection")
        } finally { work.value = "" }
    }

    /**
     * AI subtitle translation: the subtitles that are on (or the first available) translated into [language] by Blaze's
     * model in batches, kept with the video as a new track "<language> (AI translation)" and turned on.
     */
    suspend fun translate(language: String): String {
        val t = current.value ?: return "Nothing is playing."
        val lang = language.trim().ifBlank { java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH) }
        val (src, cues) = ensureSomeSubs() ?: return "This video has no subtitles to translate. Load a .srt or .vtt file first."
        val batches = VideoSmart.batches(cues)
        val out = ArrayList<Cue>()
        try {
            batches.forEachIndexed { i, b ->
                work.value = "Translating subtitles to $lang · ${i + 1} of ${batches.size}"
                val msgs = org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", VideoSmart.translatePrompt(b, lang)))
                val reply = try {
                    com.blaze.assistant.agent.LlmClient.complete(msgs, null, null, null, com.blaze.assistant.agent.LlmClient.Lane.BACKGROUND)
                        .optString("content").substringAfterLast("</think>")
                } catch (_: Exception) { "" }
                out += VideoSmart.applyTranslation(b, reply)
            }
        } finally { work.value = "" }
        if (VideoSmart.translatedShare(cues, out) < 0.3) return "The translation didn't come back. Check the connection and try again."
        val code = langCode(lang)
        val track = StoreSubtitle("ai-" + lang.lowercase().replace(Regex("[^a-z]"), "") + "-" + src.id.take(24), "", code, "$lang (AI translation)", auto = true)
        val local = withContext(Dispatchers.IO) { SubsLoader.save(this@VideoActivity, t.key, track, out) }
        if (current.value?.key == t.key) attach(local)
        return "Subtitles translated to $lang and turned on (${out.size} lines)."
    }

    private fun langCode(name: String): String {
        val n = name.trim().lowercase()
        if (n.length in 2..3 && n.all { it.isLetter() }) return n
        return java.util.Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(java.util.Locale.ENGLISH).equals(name.trim(), true) }?.language.orEmpty()
    }

    /**
     * Wave A 25: "Translate to" [code]. A YouTube caption track is machine-translated by YouTube (fast, free, kept with
     * the video); anything else goes through Blaze's AI translation. The language is remembered for the next video.
     */
    fun translateTo(code: String, scope: kotlinx.coroutines.CoroutineScope) {
        SubsLoader.setPref(this, code)
        val base = Subs.translationBase(subsAvailable.value)
        if (base != null) {
            val tr = Subs.translated(base, code)
            mergeAvailable(listOf(tr))
            chooseSubtitle(Subs.MenuItem(tr.id, Subs.label(tr), tr.lang, subsLoaded.any { it.id == tr.id }, false))
        } else scope.launch {
            val r = translate(Subs.translateName(code))
            Toast.makeText(this@VideoActivity, r, Toast.LENGTH_LONG).show()
        }
    }

    /** Agent: turn subtitles on in [language] ("off" turns them off). */
    fun subtitlesTo(language: String): String {
        if (language.equals("off", true)) { subtitlesOff(); return "Subtitles off." }
        val want = language.trim()
        val match = { s: StoreSubtitle -> Subs.sameLang(s.lang, want) || Subs.label(s).contains(want, true) }
        val tr = subsAvailable.value.filter(match).sortedBy { it.auto }.firstOrNull()
            ?: Subs.translateTo.firstOrNull { (k, n) -> k.equals(want, true) || n.equals(want, true) || n.startsWith(want, true) }
                ?.let { (k, _) -> Subs.translationBase(subsAvailable.value)?.let { b -> Subs.translated(b, k).also { mergeAvailable(listOf(it)) } } }
            ?: return "No $want subtitles for this video. I can translate the ones it has (video_ops translate_subtitles)."
        SubsLoader.setPref(this, tr.lang)
        chooseSubtitle(Subs.MenuItem(tr.id, Subs.label(tr), tr.lang, subsLoaded.any { it.id == tr.id }, false))
        return "Turning on ${Subs.label(tr)} subtitles."
    }

    /** Smart downloads: watched episodes are cleared a day after they finished. */
    private fun smartCleanup() {
        if (!MediaPrefs.smartDownloads(this)) return
        val gone = VideoSmart.toClear(MediaLib.videos.value, MediaPrefs.finished(this), System.currentTimeMillis())
        gone.forEach { MediaLib.deleteDownload(this, it.key) }
        if (gone.isNotEmpty()) lifecycleScope.launch { Toast.makeText(this@VideoActivity, "Smart downloads cleared ${gone.size} watched episode" + (if (gone.size == 1) "" else "s") + ".", Toast.LENGTH_SHORT).show() }
    }

    private fun handlePlay(i: Intent?) {
        val key = i?.getStringExtra(EXTRA_PLAY)?.takeIf { it.isNotBlank() } ?: return
        i.removeExtra(EXTRA_PLAY)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { MediaLib.ensure(this@VideoActivity) }
            MediaLib.find(key)?.let { play(it) }
        }
    }

    lateinit var player: ExoPlayer
    private val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
    val tab = mutableStateOf("library")
    val pip = mutableStateOf(false)
    val fullscreen = mutableStateOf(false)
    /** The video loaded now (library track, or a Store stream). */
    val current = mutableStateOf<Track?>(null)

    // ---------------------------------------------------------------- v0.34.0 subtitles
    /** The player's tracks, updated live in onTracksChanged (the menu used to read them once and never again). */
    val tracks = mutableStateOf(Tracks.EMPTY)
    /** Subtitle tracks this video offers that the player may not have yet (YouTube captions, saved files). */
    val subsAvailable = mutableStateOf<List<StoreSubtitle>>(emptyList())
    /** Id of the track being fetched for the menu ("" = none). */
    val subsBusy = mutableStateOf("")
    val subsError = mutableStateOf("")
    /** Tracks side-loaded into the player now (local .vtt files). */
    private val subsLoaded = ArrayList<StoreSubtitle>()
    /** What [current] plays from, so it can be rebuilt with more subtitle tracks at the same spot. */
    private var playUris: List<String> = emptyList()
    /** A subtitle id to select as soon as the player reports it. */
    private var pendingSub: String? = null
    private val pickSubFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadSubtitleFile(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.getStringExtra(MusicActivity.EXTRA_TAB)?.takeIf { it.isNotBlank() }?.let { tab.value = it }
        com.blaze.assistant.mediaapps.store.JamendoSource.init(this)
        lifecycleScope.launch(Dispatchers.IO) { MediaLib.ensure(this@VideoActivity); Downloads.resumePending(this@VideoActivity); smartCleanup() }
        intros = MediaPrefs.intros(this).toMutableMap()
        live = this
        handlePlay(intent)
        player = ExoPlayer.Builder(this)
            // Wave A 16: streams through the disk cache, buffered well ahead.
            .setMediaSourceFactory(DefaultMediaSourceFactory(StreamCache.factory(this, http)).setLoadErrorHandlingPolicy(StreamCache.dropPolicy()))
            .setLoadControl(StreamCache.loadControl(this))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                com.blaze.assistant.agent.AwarenessHooks.video(player, current.value)
                if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); savePos() }
                if (Build.VERSION.SDK_INT >= 26) runCatching { setPictureInPictureParams(pipParams()) }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { current.value?.let { MediaLib.savePosition(it.key, 0, 1) }; onEnded() }
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                // v0.34.0 skip intro: an early jump forward in an episode teaches where the series' intro ends.
                if (reason != Player.DISCONTINUITY_REASON_SEEK || old.mediaItemIndex != new.mediaItemIndex) return
                val k = seriesKey(current.value) ?: return
                val known = intros[k] ?: 0L
                val learned = VideoSmart.learnIntro(known, old.positionMs, new.positionMs)
                if (learned != known) { intros[k] = learned; MediaPrefs.setIntro(this@VideoActivity, k, learned) }
            }
            override fun onPlayerError(error: PlaybackException) { retryStream(error) }
            override fun onTracksChanged(t: Tracks) {
                tracks.value = t
                pendingSub?.let { id -> if (selectSubtitle(id)) pendingSub = null }
            }
        })
        StoragePermission.asker = null
        setContent { BlazeTheme { VideoApp(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(MusicActivity.EXTRA_TAB)?.takeIf { it.isNotBlank() }?.let { tab.value = it }
        handlePlay(intent)
    }

    fun savePos() {
        val t = current.value ?: return
        if (t.source == "stream") return
        MediaLib.savePosition(t.key, player.currentPosition, player.duration.coerceAtLeast(0))
        com.blaze.assistant.agent.AwarenessHooks.video(player, t)
    }

    /** Plays a library video from where it was left. */
    fun play(t: Track) {
        savePos()
        current.value?.takeIf { it.key != t.key }?.let { com.blaze.assistant.agent.AwarenessHooks.videoLeft(player, it, clear = false) }
        upNext.value = null
        current.value = t
        MediaLib.played(t)
        playUris = listOf(t.uri)
        resetSubs(SubsLoader.saved(this, t.key))
        val at = ResumeLogic.resumeAt(MediaLib.position(t.key), t.durationMs)
        // Saved subtitles (a file loaded before, captions kept with a download) come back by themselves; a downloaded
        // YouTube video also gets its YouTube caption list when online.
        Subs.pick(subsAvailable.value, SubsLoader.pref(this))?.let { subsLoaded += it; pendingSub = it.id }
        subsLoaded.addAll(subsAvailable.value.filter { s -> subsLoaded.none { it.id == s.id } })
        val noSaved = pendingSub == null
        load(at, true)
        tab.value = "player"
        com.blaze.assistant.agent.AwarenessHooks.video(player, t)
        val vid = t.videoId.removePrefix("yt:")
        if ((t.storeSource == "youtube" || t.videoId.startsWith("yt:")) && Regex("^[A-Za-z0-9_-]{11}$").matches(vid)) lifecycleScope.launch {
            val yt = try { LinkSource.ytCaptions(vid) } catch (_: Exception) { emptyList() }
            if (current.value?.key == t.key) mergeAvailable(yt)
            // Wave A 25: the remembered caption language comes on by itself, as a YouTube translation when the video lacks it.
            if (noSaved && current.value?.key == t.key) Subs.pickOrTranslate(subsAvailable.value, SubsLoader.pref(this@VideoActivity))?.let { want ->
                mergeAvailable(listOf(want))
                chooseSubtitle(Subs.MenuItem(want.id, Subs.label(want), want.lang, false, false))
            }
        }
    }

    private fun resetSubs(saved: List<StoreSubtitle>) {
        subsLoaded.clear(); pendingSub = null; subsBusy.value = ""; subsError.value = ""
        subsAvailable.value = saved
    }

    private fun mergeAvailable(more: List<StoreSubtitle>) {
        val have = subsAvailable.value
        subsAvailable.value = have + more.filter { m -> have.none { it.id == m.id } }
    }

    /** (Re)loads [current] from [playUris] with every side-loaded subtitle in [subsLoaded], at [at]. */
    private fun load(at: Long, play: Boolean) {
        val t = current.value ?: return
        val subs = subsLoaded.map { SubsLoader.config(it, it.id == pendingSub) }
        val stream = t.source == "stream"
        val first = MediaItem.Builder().setMediaId(t.key).setUri(playUris.first()).setCustomCacheKey(if (stream) StreamCache.key(t.key) else null)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).build()).setSubtitleConfigurations(subs).build()
        if (playUris.size == 1) player.setMediaItem(first, at)
        else {
            val msf = DefaultMediaSourceFactory(StreamCache.factory(this, http)).setLoadErrorHandlingPolicy(StreamCache.dropPolicy())
            val parts = ArrayList<MediaSource>()
            parts += msf.createMediaSource(first)
            playUris.drop(1).forEachIndexed { i, u -> parts += ProgressiveMediaSource.Factory(StreamCache.factory(this, http)).setLoadErrorHandlingPolicy(StreamCache.dropPolicy())
                .createMediaSource(MediaItem.Builder().setUri(u).setCustomCacheKey(if (stream) StreamCache.key(t.key, i + 1) else null).build()) }
            player.setMediaSource(MergingMediaSource(*parts.toTypedArray()), at)
        }
        if (pendingSub != null) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        player.prepare(); player.playWhenReady = play
    }

    /** Turns on the player's text track with this id (side-loaded tracks keep their SubtitleConfiguration id). */
    fun selectSubtitle(id: String): Boolean {
        val label = (subsLoaded + subsAvailable.value).firstOrNull { it.id == id }?.let { Subs.label(it) }
        for (g in player.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                if (f.id == id || f.id?.endsWith(":$id") == true || (label != null && f.label == label)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    return true
                }
            }
        }
        return false
    }

    fun subtitlesOff() {
        pendingSub = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        SubsLoader.setPref(this, "off")
    }

    /** The user picked a subtitle in the menu: select it, or fetch it first (YouTube) and add it to the player. */
    fun chooseSubtitle(item: Subs.MenuItem) {
        if (item.lang.isNotBlank()) SubsLoader.setPref(this, item.lang)
        if (item.loaded || subsLoaded.any { it.id == item.id }) {
            if (!selectSubtitle(item.id)) pendingSub = item.id
            return
        }
        val t = current.value ?: return
        val track = subsAvailable.value.firstOrNull { it.id == item.id } ?: return
        subsBusy.value = track.id; subsError.value = ""
        lifecycleScope.launch {
            val local = if (track.url.startsWith("file:")) track else {
                val cues = try { SubsLoader.fetchYouTube(track) } catch (_: Exception) { emptyList() }
                if (cues.isEmpty()) null else withContext(Dispatchers.IO) { SubsLoader.save(this@VideoActivity, t.key, track, cues) }
            }
            subsBusy.value = ""
            if (current.value?.key != t.key) return@launch
            if (local == null) { subsError.value = "YouTube didn't send the ${Subs.label(track)} subtitles. Try again, or load a file."; return@launch }
            attach(local)
        }
    }

    /** Adds a saved track to the player (rebuilding the source at the same spot) and selects it. */
    private fun attach(local: StoreSubtitle) {
        subsLoaded.removeAll { it.id == local.id }; subsLoaded += local
        subsAvailable.value = subsAvailable.value.map { if (it.id == local.id) local else it }.let { l -> if (l.any { it.id == local.id }) l else l + local }
        pendingSub = local.id
        load(player.currentPosition, player.playWhenReady)
    }

    fun openSubtitleFile() {
        runCatching { pickSubFile.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream", "*/*")) }
    }

    /** A .srt / .vtt from the phone: parsed, kept with this video, added and turned on. */
    fun loadSubtitleFile(uri: Uri) {
        val t = current.value ?: return
        lifecycleScope.launch {
            try {
                val (name, cues) = SubsLoader.readFile(this@VideoActivity, uri)
                val id = "file-" + Subs.folderFor(uri.toString()).take(8)
                val local = withContext(Dispatchers.IO) { SubsLoader.save(this@VideoActivity, t.key, StoreSubtitle(id, "", "", name.substringBeforeLast('.'), false), cues) }
                if (current.value?.key == t.key) attach(local)
            } catch (e: Exception) {
                Toast.makeText(this@VideoActivity, e.message ?: "Couldn't read that subtitle file.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private var lastWatch: Pair<StoreItem, StoreSource>? = null
    private var retriedKey = ""

    /** v0.30.5: a refused stream (403 / 410) is asked for again once and resumes where it stopped (see MusicService). */
    private fun retryStream(e: PlaybackException) {
        if (e.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return
        val t = current.value ?: return
        val (item, src) = lastWatch ?: return
        if (t.source != "stream" || retriedKey == t.key) return
        retriedKey = t.key
        val pos = player.currentPosition
        lifecycleScope.launch {
            val d = try { src.refresh(item.id, "video") } catch (_: Exception) { null }
            if (d == null) {
                // Wave A 16: no network to re-ask the source: play on from what the disk cache holds.
                val urls = StreamCache.recall(this@VideoActivity, t.key)
                if (current.value?.key == t.key && urls.isNotEmpty() && StreamCache.hasStart(this@VideoActivity, t.key)) { playUris = urls; load(pos, true) }
                return@launch
            }
            if (current.value?.key == t.key) watch(item, d, src, pos)
        }
    }

    /** Watch a Store result without downloading: one progressive file, or video + audio merged while playing. */
    fun watch(item: StoreItem, d: StoreDetail, src: StoreSource, at: Long = 0) {
        // v0.34.0 data saver: on mobile data the smallest picture (240p or more).
        val picks = VideoSmart.streamFor("video", d, src, MediaPrefs.saving(this))
        if (picks.isEmpty()) return
        upNext.value = null
        lastWatch = item to src
        if (at == 0L) retriedKey = ""
        savePos()
        http.setUserAgent(src.userAgent)
        Taste.note(this, "video", d.title.ifBlank { item.title }, d.author.ifBlank { item.author }, Taste.PLAY)
        val t = Track("stream:${item.source}:${item.id}", picks[0].url, d.title.ifBlank { item.title }, d.author.ifBlank { item.author }, "",
            d.lengthSec * 1000L, item.thumb, "stream", 0, item.id, "video", d.licence.ifBlank { item.licence }, item.source)
        val same = current.value?.key == t.key
        current.value = t
        playUris = picks.map { it.url }
        StreamCache.remember(this, t.key, playUris)      // wave A 16: offline replay from disk
        val keep = if (same) subsLoaded.toList() else emptyList()
        if (!same) resetSubs(SubsLoader.saved(this, t.key))
        mergeAvailable(d.subtitles)
        subsLoaded.clear(); subsLoaded.addAll(keep)
        tab.value = "player"
        // The viewer's subtitle language (if they turned subtitles on before) is fetched first, ~4 s at most, so the
        // video starts with it on; any other track loads when picked in the menu.
        val want = if (keep.isEmpty()) Subs.pickOrTranslate(subsAvailable.value, SubsLoader.pref(this))?.also { w ->
            if (subsAvailable.value.none { it.id == w.id }) mergeAvailable(listOf(w)) } else null
        if (want == null) { load(at, true); return }
        // 0.45.19 step 3: the first chunk of the stream is fetched while the subtitle is; the video starts after
        // MediaNet.SUBS_WAIT_MS at most (0.45.18 waited up to 4 s) and a subtitle that lands later is added then.
        StreamCache.key(t.key)?.let { StreamCache.warm(this, playUris.first(), it, http) }
        lifecycleScope.launch {
            val fetch = async {
                if (want.url.startsWith("file:")) want else kotlinx.coroutines.withTimeoutOrNull(MediaNet.SUBS_TOTAL_MS) {
                    val cues = SubsLoader.fetchYouTube(want)
                    if (cues.isEmpty()) null else withContext(Dispatchers.IO) { SubsLoader.save(this@VideoActivity, t.key, want, cues) }
                }
            }
            val local = kotlinx.coroutines.withTimeoutOrNull(MediaNet.SUBS_WAIT_MS) { fetch.await() }
            if (current.value?.key != t.key) { fetch.cancel(); return@launch }
            if (local != null) { subsLoaded += local; subsAvailable.value = subsAvailable.value.map { if (it.id == local.id) local else it }; pendingSub = local.id }
            load(at, true)
            if (local == null) fetch.await()?.let { late -> if (current.value?.key == t.key && subsLoaded.none { it.id == late.id }) attach(late) }
        }
    }

    fun pipParams(): PictureInPictureParams {
        val vs = player.videoSize
        val r = if (vs.width > 0 && vs.height > 0) Rational(vs.width, vs.height).let { if (it.toFloat() > 2.39f) Rational(239, 100) else if (it.toFloat() < 0.42f) Rational(42, 100) else it } else Rational(16, 9)
        val b = PictureInPictureParams.Builder().setAspectRatio(r)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(player.isPlaying && tab.value == "player").setSeamlessResizeEnabled(true)
        return b.build()
    }

    /** Wave A item 26: Back from any level of Video lands on the agent chat; a playing video stays on as PiP. */
    /** 0.45.19 fix 7 (BackRule): Back from any Video screen goes to the chat; a playing video stays in picture-in-picture. */
    override fun backToChat() = leaveToChat()

    fun leaveToChat() {
        val pipOk = Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        if (BackToChat.keepVideo(player.isPlaying && current.value != null, pipOk)) {
            enterPip(); BackToChat.go(this, finish = false)
        } else BackToChat.go(this)
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
            runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 8–11: go to picture-in-picture by hand when leaving a playing video (12+ does it via auto-enter).
        if (Build.VERSION.SDK_INT in 26..30 && player.isPlaying && tab.value == "player") enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.value = isInPictureInPictureMode
    }

    fun setFullscreen(on: Boolean) {
        fullscreen.value = on
        val w = window
        val ctl = WindowCompat.getInsetsController(w, w.decorView)
        if (on) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            val vs = player.videoSize
            requestedOrientation = if (vs.height > vs.width) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onStop() {
        super.onStop()
        savePos()
        // Leaving the app (not into picture-in-picture): pause, like any video app.
        if (!pip.value) player.pause()
    }

    /** v0.34.1: the mini-player's close: stops the video and forgets it (the next play starts fresh). */
    fun closePlayer() {
        savePos()
        com.blaze.assistant.agent.AwarenessHooks.videoLeft(player, current.value, clear = true)
        upNext.value = null
        player.stop()
        player.clearMediaItems()
        current.value = null
        if (tab.value == "player") tab.value = "library"
    }

    override fun onDestroy() {
        if (live === this) live = null
        savePos()
        if (current.value != null) com.blaze.assistant.agent.AwarenessHooks.videoLeft(player, current.value, clear = true)
        player.release()
        super.onDestroy()
    }
}

@Composable
private fun VideoApp(a: VideoActivity) {
    val c = LocalBlaze.current
    val tab by a.tab
    val pip by a.pip
    val full by a.fullscreen
    val store = remember { StoreState("video") }
    BackHandler(full) { a.setFullscreen(false) }
    // v0.34.1: Back collapses the player sheet; the video keeps playing in the mini-player.
    BackHandler(!full && tab == "player") { a.tab.value = "library" }
    // Wave A item 26: Back from the library or the store goes to the agent chat (not the launcher).
    BackHandler(BackToChat.step(full, tab) == BackToChat.Step.TO_CHAT) { a.leaveToChat() }

    if (pip) { PlayerSurface(a, controls = false); return }
    if (full) { Box(Modifier.fillMaxSize().background(Color.Black)) { PlayerSurface(a, controls = true) }; return }

    val cur by a.current
    // v0.45.8: Video shows the picked theme and has the shared theme switcher.
    com.blaze.assistant.ui.ThemedScreen(Modifier.fillMaxSize(), own = { background(c.paper) }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton({ a.leaveToChat() }, desc = "Back to chat")
                Spacer(Modifier.weight(1f))
                // v0.34.1: one Library entry (the player is a sheet over it now, not a tab).
                AppPill(listOf("library" to "Library", "store" to "Store"), if (tab == "store") "store" else "library", onPick = { a.tab.value = it })
                Spacer(Modifier.weight(1f)); com.blaze.assistant.ui.ThemeSwitch(Modifier.width(48.dp))
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    "store" -> StoreView("video", store) { item, d, src -> a.watch(item, d, src) }
                    else -> VideoLibrary(a)
                }
            }
        }
        // Mini-player while the sheet is collapsed: tap re-opens the player, play / pause, close.
        AnimatedVisibility(tab != "player" && cur != null, Modifier.align(Alignment.BottomCenter), enter = MotionTokens.enterUp(), exit = MotionTokens.exitDown()) {
            VideoMini(a)
        }
        PlayerSheet(tab == "player", onCollapse = { a.tab.value = "library" }) { PlayerTab(a) }
    }
}

/** v0.34.1: the collapsed player: the video's art and title, play / pause and close; playback goes on underneath. */
@Composable
private fun VideoMini(a: VideoActivity) {
    val p = a.player
    val t by a.current
    var playing by remember { mutableStateOf(p.isPlaying) }
    var frac by remember { mutableFloatStateOf(0f) }
    fun read() { playing = p.isPlaying; val d = p.duration; frac = if (d > 0) p.currentPosition.toFloat() / d else 0f }
    DisposableEffect(p) {
        val l = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { read() } }
        p.addListener(l); read()
        onDispose { p.removeListener(l) }
    }
    LaunchedEffect(playing) { while (playing) { read(); delay(500) } }
    MiniBar(t, listOf(t?.artist.orEmpty(), t?.licence.orEmpty()).filter { it.isNotBlank() }.joinToString(" \u00b7 "), playing, frac,
        onOpen = { a.tab.value = "player" },
        onPlayPause = { if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() } },
        onClose = { a.closePlayer() }, artRatio = 16f / 9f)
}

@Composable
private fun PlayerTab(a: VideoActivity) {
    val c = LocalBlaze.current
    val t by a.current
    if (t == null) { EmptyState("Nothing playing", "Pick a video in Library, or find one in the Store."); return }
    // v0.34.1: under the video, the rest of your library (one tap switches), so the bottom is never blank.
    val videos by MediaLib.videos.collectAsState()
    val others = remember(videos, t!!.key) { videos.filter { it.key != t!!.key } }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) { PlayerSurface(a, controls = true) }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(horizontal = 2.dp)) {
                    MText(t!!.title, 18.sp, c.ink, FontWeight.SemiBold, maxLines = 3)
                    Spacer(Modifier.height(4.dp))
                    MText(listOf(t!!.artist, t!!.licence).filter { it.isNotBlank() }.joinToString(" \u00b7 "), 13.sp, c.sub)
                }
            }
            if (others.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                MText("More from your library \u00b7 ${others.size}", 15.sp, c.ink, FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            }
            items(others, key = { "more-" + it.key }) { v ->
                Column(Modifier.clip(BlazeShapes.small).clickable(onClickLabel = "Play ${v.title}") { a.play(v) }) {
                    Box {
                        Art(v, Modifier.fillMaxWidth().aspectRatio(16f / 9f), target = 320)
                        Box(Modifier.align(Alignment.BottomEnd)) { DurationBadgeText(if (v.durationMs > 0) TimeText.clock(v.durationMs) else "") }
                    }
                    MText(v.title, 12.5.sp, c.ink, FontWeight.Medium, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** The video, its gesture layer and (with [controls]) the overlay controls. */
@Composable
private fun PlayerSurface(a: VideoActivity, controls: Boolean) {
    val ctx = LocalContext.current
    val p = a.player
    var playing by remember { mutableStateOf(p.isPlaying) }
    var pos by remember { mutableLongStateOf(p.currentPosition) }
    var dur by remember { mutableLongStateOf(p.duration.coerceAtLeast(0)) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf("") }
    var speedMenu by remember { mutableStateOf(false) }
    var tracksOpen by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var autoplayOn by remember { mutableStateOf(MediaPrefs.autoplay(ctx)) }
    var smartOn by remember { mutableStateOf(MediaPrefs.smartDownloads(ctx)) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val cur by a.current
    val nextUp by a.upNext
    val busyWork by a.work
    val recap by a.recapText
    var speed by remember { mutableFloatStateOf(p.playbackParameters.speed) }
    DisposableEffect(p) {
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playing = player.isPlaying; dur = player.duration.coerceAtLeast(0); pos = player.currentPosition
                buffering = player.playbackState == Player.STATE_BUFFERING; speed = player.playbackParameters.speed
                error = player.playerError?.let { e ->
                    when (e.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection. Downloaded videos play offline."
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The stream link expired or was refused. Open it again from the Store."
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This file was deleted or moved."
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This phone can't decode this video. Try a lower quality."
                        else -> "Couldn't play this: " + (e.message ?: e.errorCodeName)
                    }
                }.orEmpty()
            }
        }
        p.addListener(l)
        onDispose { p.removeListener(l) }
    }
    LaunchedEffect(playing) { while (playing) { pos = p.currentPosition; delay(250) } }
    LaunchedEffect(show, playing) { if (show && playing) { delay(3500); show = false } }
    LaunchedEffect(hint) { if (hint.isNotEmpty()) { delay(900); hint = "" } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { cx ->
            PlayerView(cx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                // Wave A 25: captions sit clear of the seek bar and the gesture bar (caption safe area).
                subtitleView?.setBottomPaddingFraction(0.12f)
                player = p
            }
        }, update = { it.player = p }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())

        if (controls) {
            // Gestures: tap toggles controls, double-tap left/right skips 10 s, vertical drags change brightness (left) / volume (right).
            // Wave A item 4: no swipe-to-seek; sideways swipes are left to Android's gesture navigation. Seek with the bar or the 10 s buttons.
            Box(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { show = !show }, onDoubleTap = { o ->
                        if (o.x < size.width / 2) { p.seekBack(); hint = "\u2212 10 s" } else { p.seekForward(); hint = "+ 10 s" }
                    })
                }
                .pointerInput(Unit) {
                    var mode = 0   // 2 brightness, 3 volume, -1 ignored (started at a screen edge, where Back lives)
                    var acc = 0f
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val edge = 28.dp.toPx()
                    detectVerticalDragGestures(
                        onDragStart = { o -> acc = 0f; mode = if (o.x < edge || o.x > size.width - edge) -1 else if (o.x < size.width / 2) 2 else 3 },
                        onVerticalDrag = { ch, dy ->
                            if (mode > 0) ch.consume()
                            when (mode) {
                                2 -> {
                                    val lp = a.window.attributes
                                    val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                                    lp.screenBrightness = (cur - dy / size.height).coerceIn(0.02f, 1f)
                                    a.window.attributes = lp
                                    hint = "Brightness ${(lp.screenBrightness * 100).toInt()}%"
                                }
                                3 -> {
                                    acc -= dy
                                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val stepPx = size.height / (max * 1.2f)
                                    if (kotlin.math.abs(acc) >= stepPx) {
                                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (acc > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                                        acc = 0f
                                    }
                                    hint = "Volume ${am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max.coerceAtLeast(1)}%"
                                }
                            }
                        })
                })
        }
        if (buffering) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp), color = BlazeBrush.blue, trackColor = Color.Transparent)
        if (hint.isNotEmpty()) Box(Modifier.align(Alignment.Center).clip(BlazeShapes.medium).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 10.dp)) {
            MText(hint, 15.sp, Color.White, FontWeight.Medium)
        }
        if (error.isNotBlank()) Column(Modifier.align(Alignment.Center).padding(24.dp).clip(BlazeShapes.medium).background(Color.Black.copy(alpha = 0.75f)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            MText(error, 13.5.sp, Color.White, maxLines = 4)
            Spacer(Modifier.height(10.dp))
            PillButton("Try again", primary = true) { p.prepare(); p.play() }
        }
        // v0.34.0: Skip intro (learned per series), Up next countdown, recap / translation progress.
        val introAt = a.introEnd(cur)
        if (controls && VideoSmart.showSkip(pos, introAt)) Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 72.dp)) {
            PillButton("Skip intro", primary = true) { a.skipIntro() }
        }
        nextUp?.let { nx ->
            var left by remember(nx.key) { mutableStateOf(5) }
            LaunchedEffect(nx.key) {
                while (true) { left = VideoSmart.countdown(a.upNextAt, System.currentTimeMillis()); if (left < 0) break; delay(250) }
                a.play(nx)
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(16.dp).clip(BlazeShapes.medium).background(Color.Black.copy(alpha = 0.8f)).padding(14.dp)) {
                MText("Up next in ${left.coerceAtLeast(0)}", 12.sp, Color.White.copy(alpha = 0.8f))
                MText(nx.title, 14.sp, Color.White, FontWeight.SemiBold, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Cancel", primary = false) { a.cancelUpNext() }
                    PillButton("Play now", primary = true) { a.play(nx) }
                }
            }
        }
        if (busyWork.isNotBlank()) Box(Modifier.align(Alignment.TopStart).padding(10.dp).clip(BlazeShapes.small).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
            MText(busyWork, 12.sp, Color.White)
        }
        if (controls) AnimatedVisibility(show && error.isBlank(), Modifier.fillMaxSize(), enter = MotionTokens.fade(), exit = MotionTokens.fadeAway()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    Box {
                        RoundIcon(Icons.Rounded.Speed, "Speed ${Speeds.label(speed)}", tint = Color.White) { speedMenu = true }
                        BlazeMenu(speedMenu, { speedMenu = false }, Speeds.all.map { s -> (Speeds.label(s) + if (s == speed) "  \u2713" else "") to { p.setPlaybackSpeed(s) } })
                    }
                    RoundIcon(Icons.Rounded.ClosedCaption, "Subtitles and audio", tint = Color.White) { tracksOpen = true }
                    if (Build.VERSION.SDK_INT >= 26) RoundIcon(Icons.Rounded.PictureInPictureAlt, "Picture in picture", tint = Color.White) { a.enterPip() }
                    Box {
                        RoundIcon(Icons.Rounded.MoreVert, "More", tint = Color.White) { moreMenu = true }
                        val lang = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH)
                        BlazeMenu(moreMenu, { moreMenu = false }, buildList<Pair<String, () -> Unit>> {
                            add("Recap so far" to { scope.launch { a.recapText.value = a.recap() } })
                            add("Translate subtitles to $lang (AI)" to { scope.launch { val r = a.translate(lang); android.widget.Toast.makeText(ctx, r, android.widget.Toast.LENGTH_LONG).show() } })
                            if (a.seriesKey(a.current.value) != null) add("Mark the end of the intro here" to { a.markIntroEnd() })
                            add((if (autoplayOn) "Autoplay next episode: on" else "Autoplay next episode: off") to {
                                autoplayOn = !autoplayOn; MediaPrefs.setBool(ctx, MediaPrefs.AUTOPLAY, autoplayOn) })
                            add((if (smartOn) "Smart downloads: on" else "Smart downloads: off") to {
                                smartOn = !smartOn; MediaPrefs.setBool(ctx, MediaPrefs.SMART_DL, smartOn)
                                android.widget.Toast.makeText(ctx, if (smartOn) "Watched episodes you downloaded are cleared a day after you finish them." else "Smart downloads off.", android.widget.Toast.LENGTH_SHORT).show() })
                        })
                    }
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(Icons.Rounded.Replay10, "Back 10 seconds", size = 52, tint = Color.White) { p.seekBack() }
                    RoundIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", size = 68, tint = Color.White,
                        fill = Color.White.copy(alpha = 0.16f)) {
                        if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
                    }
                    RoundIcon(Icons.Rounded.Forward10, "Forward 10 seconds", size = 52, tint = Color.White) { p.seekForward() }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp)) {
                    var drag by remember { mutableStateOf<Float?>(null) }
                    val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MText(TimeText.clock(if (drag != null) (frac * dur).toLong() else pos) + " / " + TimeText.clock(dur), 12.sp, Color.White, modifier = Modifier.padding(start = 6.dp))
                        Spacer(Modifier.weight(1f))
                        val full by a.fullscreen
                        RoundIcon(if (full) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (full) "Exit full screen" else "Full screen", tint = Color.White) { a.setFullscreen(!full) }
                    }
                    ScrubBar(frac, { drag = it; show = true }, { drag?.let { f -> p.seekTo((f * dur).toLong()) }; drag = null },
                        active = BlazeBrush.blue, rest = Color.White.copy(alpha = 0.3f), knob = Color.White, label = "Seek video")
                }
            }
        }
    }
    if (tracksOpen) TracksDialog(a) { tracksOpen = false }
    recap?.let { r -> BlazeDialog("Recap so far", r, confirm = "Keep watching", onConfirm = { a.recapText.value = null }, onDismiss = { a.recapText.value = null }) }
}

/**
 * Subtitles and audio. v0.34.0: reads the player's tracks live ([VideoActivity.tracks], from onTracksChanged), lists
 * YouTube's caption tracks and saved files that aren't loaded yet (picking one fetches it and adds it), and loads a
 * .srt / .vtt from the phone. Off = no subtitles (remembered).
 */
@Composable
private fun TracksDialog(a: VideoActivity, onDismiss: () -> Unit) {
    val p = a.player
    val tracks by a.tracks
    val available by a.subsAvailable
    val busy by a.subsBusy
    val err by a.subsError
    val groups = tracks.groups
    val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
    val textOff = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    val audioTotal = audio.sumOf { it.length }
    fun name(g: Tracks.Group, i: Int, n: Int): String {
        val f = g.getTrackFormat(i)
        // Wave A 25: readable names, never "und".
        if (g.type == C.TRACK_TYPE_AUDIO) return Subs.audioName(f.label, f.language, n, audioTotal)
        return listOfNotNull(f.label?.takeIf { it.isNotBlank() }, f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { Subs.langName(it) }).firstOrNull()
            ?: ("Subtitles " + (n + 1))
    }
    var translating by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val inPlayer = ArrayList<Pair<Subs.MenuItem, () -> Unit>>()
    var n = 0
    groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }.forEach { g ->
        for (i in 0 until g.length) {
            val k = n++
            val f = g.getTrackFormat(i)
            val id = available.firstOrNull { s -> f.id == s.id || f.id?.endsWith(":" + s.id) == true || f.label == Subs.label(s) }?.id ?: (f.id ?: "track-$k")
            inPlayer += Subs.MenuItem(id, name(g, i, k), f.language.orEmpty(), true, !textOff && g.isTrackSelected(i)) to {
                f.language?.let { l -> SubsLoader.setPref(a, l) }
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
            }
        }
    }
    val menu = Subs.menu(inPlayer.map { it.first }, available, java.util.Locale.getDefault().language)
    val anyOn = menu.any { it.selected }
    if (translating) {
        // Wave A 25: "Translate to" any language; the viewer's own language first.
        val dev = java.util.Locale.getDefault().language
        val langs = Subs.translateTo.sortedBy { !Subs.sameLang(it.first, dev) }
        BlazeDialog("Translate subtitles to", confirm = "Back", onConfirm = { translating = false }, onDismiss = { translating = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (Subs.translationBase(available) == null)
                    MText("This video has no YouTube captions, so Blaze's AI translates the subtitles it has. That takes a minute.", 12.5.sp, DialogColors.body, maxLines = 3)
                langs.forEach { (code, label) -> Option(label, menu.any { it.selected && Subs.sameLang(it.lang, code) && it.label.contains("translat", true) }) {
                    translating = false; a.translateTo(code, scope) } }
            }
        }
        return
    }
    BlazeDialog("Subtitles and audio", confirm = "Done", onConfirm = onDismiss, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            MText("Subtitles", 12.sp, DialogColors.body, FontWeight.Medium)
            Option("Off", !anyOn) { a.subtitlesOff() }
            menu.forEach { m ->
                val action = inPlayer.firstOrNull { it.first.id == m.id }?.second
                Option(m.label + if (busy == m.id) "  · loading…" else "", m.selected) { if (action != null) action() else a.chooseSubtitle(m) }
            }
            if (menu.isEmpty()) MText("This video has no subtitles of its own. Load a .srt or .vtt file.", 12.5.sp, DialogColors.body, maxLines = 3)
            if (err.isNotBlank()) MText(err, 12.5.sp, Color(0xFFE5484D), maxLines = 3)
            if (menu.isNotEmpty() || Subs.translationBase(available) != null) Option("Translate to\u2026", false) { translating = true }
            Option("Load a subtitle file (.srt, .vtt)…", false) { a.openSubtitleFile() }
            Spacer(Modifier.height(10.dp))
            MText("Audio", 12.sp, DialogColors.body, FontWeight.Medium)
            var m2 = 0
            audio.forEach { g -> for (i in 0 until g.length) { val k = m2++; Option(name(g, i, k), g.isTrackSelected(i)) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
            } } }
        }
    }
}

@Composable
private fun Option(text: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).background(if (on) DialogColors.primary.copy(alpha = 0.18f) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        MText(text, 14.5.sp, DialogColors.title, if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (on) MText("\u2713", 14.sp, DialogColors.primary)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VideoLibrary(a: VideoActivity) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val videos by MediaLib.videos.collectAsState()
    val recent by MediaLib.recentVideos.collectAsState()
    var q by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf<Track?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { MediaLib.refresh() } }
    val byKey = remember(videos) { videos.associateBy { it.key } }
    // Continue watching: recently played with a saved position.
    val cont = recent.mapNotNull { byKey[it] }.filter { MediaLib.position(it.key) > 0 }.take(12)
    val shown = remember(videos, q) { LibraryGroups.search(videos, q) }
    // Wave B item 20: press-and-hold multi-select on the All videos grid.
    var sel by remember { mutableStateOf(LibrarySelection.NONE) }
    var confirmMany by remember { mutableStateOf<List<Track>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val selecting = LibrarySelection.active(sel)
    val shownKeys = remember(shown) { shown.map { it.key } }
    LaunchedEffect(shownKeys) { sel = LibrarySelection.prune(sel, shownKeys) }
    BackHandler(LibrarySelection.backClears(sel)) { sel = LibrarySelection.clear() }
    val exportTree = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val picked = LibrarySelection.picked(sel, shown)
        if (tree != null && picked.isNotEmpty()) scope.launch {
            Toast.makeText(ctx, "Exporting ${picked.size}\u2026", Toast.LENGTH_SHORT).show()
            val n = withContext(Dispatchers.IO) { MediaExport.exportAll(ctx, picked, tree) }
            Toast.makeText(ctx, if (n == picked.size) "Exported $n" else "Exported $n of ${picked.size}", Toast.LENGTH_SHORT).show()
            sel = LibrarySelection.clear()
        }
    }

    Column(Modifier.fillMaxSize()) {
    if (selecting) SelectionBar(LibrarySelection.label(sel), LibrarySelection.allSelected(sel, shownKeys),
        onClose = { sel = LibrarySelection.clear() }, onSelectAll = { sel = LibrarySelection.selectAll(sel, shownKeys) },
        onShare = {
            val i = MediaExport.shareIntent(ctx, LibrarySelection.picked(sel, shown))
            if (i == null) Toast.makeText(ctx, "These can't be shared", Toast.LENGTH_SHORT).show() else runCatching { ctx.startActivity(i) }
        },
        onExport = { runCatching { exportTree.launch(null) }.onFailure { Toast.makeText(ctx, "No file picker on this phone", Toast.LENGTH_SHORT).show() } },
        onDelete = {
            val p = LibrarySelection.picked(sel, shown)
            if (LibrarySelection.deletable(p).isEmpty()) Toast.makeText(ctx, "Gallery videos are deleted in Gallery", Toast.LENGTH_SHORT).show() else confirmMany = p
        },
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
    // v0.34.1: bottom room for the mini-player.
    LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 110.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!selecting) item(span = { GridItemSpan(maxLineSpan) }) { KitSearch(q, { q = it }, "Search your videos") }
        if (q.isBlank() && cont.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { MText("Continue watching", 15.sp, c.ink, FontWeight.SemiBold) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cont, key = { "c" + it.key }) { t ->
                        Column(Modifier.width(200.dp).clip(BlazeShapes.small).clickable { a.play(t) }) {
                            Box {
                                Art(t, Modifier.fillMaxWidth().aspectRatio(16f / 9f), target = 400)
                                val d = t.durationMs.takeIf { it > 0 }
                                if (d != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
                                    Box(Modifier.fillMaxWidth((MediaLib.position(t.key).toFloat() / d).coerceIn(0f, 1f)).height(3.dp).background(Color(0xFFE5484D)))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            MText(t.title, 12.5.sp, c.ink, FontWeight.Medium)
                            MText("${TimeText.clock(MediaLib.position(t.key))} watched", 11.5.sp, c.sub)
                        }
                    }
                }
            }
        }
        // v0.34.0 episode grouping: series (two or more episodes) in order, with what's next to watch.
        val series = if (q.isBlank()) VideoSmart.series(videos) else emptyList()
        series.forEach { (name, eps) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "series-$name") {
                Column {
                    val upto = eps.indexOfLast { MediaLib.position(it.key) > 0 || it.key in recent }.coerceAtLeast(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MText("$name \u00b7 ${eps.size} episodes", 15.sp, c.ink, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        PillButton(if (upto > 0) "Continue" else "Play", primary = false) { a.play(eps[upto]) }
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(eps, key = { "s" + it.key }) { t ->
                            Column(Modifier.width(150.dp).clip(BlazeShapes.small).clickable { a.play(t) }) {
                                Art(t, Modifier.fillMaxWidth().aspectRatio(16f / 9f), target = 300)
                                val e = VideoSmart.episode(t.title)
                                MText(if (e != null) "S${e.season} \u00b7 E${e.episode}" else t.title, 12.sp, c.ink, FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { MText(if (q.isBlank()) "All videos \u00b7 ${videos.size}" else "Results", 15.sp, c.ink, FontWeight.SemiBold) }
        if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(if (q.isBlank()) "No videos yet" else "No match",
                if (q.isBlank()) "Films you download in the Store, and videos in Blaze's Gallery, show up here and play offline." else "Nothing matches \u201c$q\u201d.")
        }
        items(shown, key = { it.key }) { t ->
            var menu by remember { mutableStateOf(false) }
            val picked = selecting && t.key in sel
            Column(Modifier.clip(BlazeShapes.small).background(if (picked) c.faint else Color.Transparent).combinedClickable(
                onClick = { if (selecting) sel = LibrarySelection.toggle(sel, t.key) else a.play(t) },
                onLongClick = { sel = if (selecting) LibrarySelection.toggle(sel, t.key) else LibrarySelection.start(t.key) })) {
                Box {
                    Art(t, Modifier.fillMaxWidth().aspectRatio(16f / 9f), target = 400)
                    Box(Modifier.align(Alignment.BottomEnd)) { DurationBadgeText(if (t.durationMs > 0) TimeText.clock(t.durationMs) else "") }
                    if (selecting) SelectMark(picked, Modifier.align(Alignment.TopStart).padding(6.dp), onArt = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(top = 4.dp)) {
                        MText(t.title, 13.sp, c.ink, FontWeight.Medium, maxLines = 2)
                        MText(listOf(t.artist, t.licence).filter { it.isNotBlank() }.joinToString(" \u00b7 "), 11.5.sp, c.sub)
                    }
                    if (t.source == "download" && !selecting) Box {
                        RoundIcon(Icons.Rounded.MoreVert, "More", size = 36, tint = c.sub) { menu = true }
                        BlazeMenu(menu, { menu = false }, listOf("Delete from phone" to { confirmDel = t }))
                    }
                }
            }
        }
    }
    }
    confirmDel?.let { t ->
        BlazeDialog("Delete \u201c${t.title.take(40)}\u201d?", "It's removed from your phone.", confirm = "Delete", warm = true,
            onConfirm = { MediaLib.deleteDownload(ctx, t.key); confirmDel = null }, dismiss = "Cancel", onDismiss = { confirmDel = null })
    }
    confirmMany?.let { picked ->
        val (title, body) = LibrarySelection.deleteText(picked, "video")
        BlazeDialog(title, body, confirm = "Delete", warm = true, onConfirm = {
            val keys = LibrarySelection.deletable(picked).map { it.key }
            confirmMany = null; sel = LibrarySelection.clear()
            scope.launch { withContext(Dispatchers.IO) { MediaLib.deleteDownloads(ctx, keys) } }
        }, dismiss = "Cancel", onDismiss = { confirmMany = null })
    }
}

@Composable
private fun DurationBadgeText(text: String) {
    if (text.isBlank()) return
    Box(Modifier.padding(6.dp).clip(BlazeShapes.xs).background(Color.Black.copy(alpha = 0.78f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        MText(text, 11.sp, Color.White, FontWeight.Medium)
    }
}
