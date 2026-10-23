package com.blaze.assistant.mediaapps

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import android.os.Bundle
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * v0.30.1 Blaze Music's player: media3 ExoPlayer in a MediaSessionService, so music keeps playing with Blaze closed,
 * with the media notification and lock-screen / Bluetooth / headset controls. Audio focus is handled (a call or another
 * app's sound pauses or ducks it) and unplugging headphones pauses it. Sleep timer and the shuffle order live here.
 */
class MusicService : MediaLibraryService() {
    companion object {
        /** The running service (same process), for the sleep timer. */
        @Volatile var current: MusicService? = null
            private set
        /** 0.45.20: a quiet chip when an item was skipped offline ("Not saved for offline"); "" = none. */
        val offlineNote = MutableStateFlow("")
        /** When the sleep timer stops playback (epoch ms, 0 = off). -1 = at the end of the current song. */
        val sleepAt = MutableStateFlow(0L)

        fun item(t: Track): MediaItem = MediaItem.Builder().setMediaId(t.key).setUri(t.uri)
            // Wave A 16: streams are cached under the stable Track key (the signed url expires).
            .setCustomCacheKey(if (StreamCache.cacheableUrl(t.uri)) StreamCache.key(t.key) else null)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist.ifBlank { null })
                .setAlbumTitle(t.album.ifBlank { null })
                .setArtworkUri(t.art.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) Uri.parse(it) else Uri.fromFile(java.io.File(it)) })
                .setIsPlayable(true).build()).build()

        /** v0.34.0: songs (library keys) to play as soon as the service starts (agent music_ops while it's stopped). */
        @Volatile var pendingPlay: Pair<List<String>, Boolean>? = null
        /** v0.45.6: resume the saved queue as soon as the service starts ("play music" while it's stopped). */
        @Volatile var pendingResume = false

        /** A Store result played without downloading (url from the source; expires, so never saved). */
        fun streamItem(t: Track, url: String): MediaItem = item(t.copy(uri = url))

        // v0.33.0: shuffle and loop buttons in the media notification, lock screen and quick-settings player.
        const val CMD_SHUFFLE = "blaze.music.SHUFFLE"
        const val CMD_REPEAT = "blaze.music.REPEAT"
        /** Off -> all -> one -> off (same order as the in-app button). */
        fun nextRepeat(mode: Int): Int = when (mode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private var session: MediaLibrarySession? = null
    /** Network streams (Store "Play"): the user-agent follows the source (see [streamUa]). */
    private val http = androidx.media3.datasource.DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)

    fun streamUa(ua: String) { http.setUserAgent(ua) }

    /** Wave A 16: the start of the next song is fetched into the disk cache, so the change is instant on a weak network. */
    private fun prefetchNext() {
        val n = player.nextMediaItemIndex
        if (n == C.INDEX_UNSET) return
        val cfg = player.getMediaItemAt(n).localConfiguration ?: return
        val key = cfg.customCacheKey ?: return
        StreamCache.prefetch(this, cfg.uri.toString(), key, http)
    }
    /** 0.45.20: local files always play offline; a stream only when its start is in the disk cache. */
    private fun playableOffline(i: Int): Boolean {
        val cfg = player.getMediaItemAt(i).localConfiguration ?: return false
        val key = cfg.customCacheKey ?: return true
        return StreamCache.hasStart(this, key)
    }

    /**
     * 0.45.20 offline queue (spec 1, offline fallback): with no network, an item that can't play from the phone is
     * skipped to the next one that can, in play order, with a quiet "Not saved for offline" chip. [ended] = the current
     * item failed (ran past its cached part), so it is skipped too. Nothing left = pause, never an endless spinner.
     */
    private fun offlineSkip(ended: Boolean) {
        if (NetWatch.now(this)) return
        if (!ended && !player.playWhenReady) return       // a queue restored at start-up is left alone until Play
        val i = player.currentMediaItemIndex
        if (i == C.INDEX_UNSET || i >= player.mediaItemCount) return
        if (!ended && playableOffline(i)) return
        val order = ArrayList<Int>().apply { add(i) }
        val tl = player.currentTimeline
        if (!tl.isEmpty) {
            var k = i
            val rep = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode
            while (order.size < 200) {
                k = tl.getNextWindowIndex(k, rep, player.shuffleModeEnabled)
                if (k == C.INDEX_UNSET || k == i) break
                order += k
            }
        }
        val n = NetHonesty.offlineNext(order, i) { playableOffline(it) }
        offlineNote.value = NetHonesty.NOT_SAVED
        main.removeCallbacks(clearNote); main.postDelayed(clearNote, 4_000)
        if (n >= 0) { player.seekTo(n, 0); player.prepare(); player.play() } else player.pause()
    }
    private val clearNote = Runnable { offlineNote.value = "" }
    private val offlineStall = Runnable {
        val stream = player.currentMediaItem?.mediaId?.startsWith("stream:") == true
        if (player.playbackState == Player.STATE_BUFFERING && NetHonesty.offlineSkipNow(NetHonesty.OFFLINE_SKIP_MS, NetWatch.now(this), stream)) offlineSkip(true)
    }

    private lateinit var player: ExoPlayer
    /** v0.33.2 BlazeOS: the player for the now-playing widget, BlazeOS's own volume and Pause (same process; null before onCreate). */
    val livePlayer: androidx.media3.common.Player? get() = if (::player.isInitialized) player else null
    private val main = Handler(Looper.getMainLooper())
    // ---------------------------------------------------------------- v0.34.0
    /** Play log: what's playing since when (flushed into MediaLib's play log on pause / song change). */
    private var playKey: String? = null
    private var playFrom = 0L
    private fun flushPlay() {
        val k = playKey ?: return
        val ms = System.currentTimeMillis() - playFrom
        playKey = null
        MediaLib.logPlay(k, playFrom, ms)
    }
    private fun startPlay() { flushPlay(); if (player.isPlaying) { playKey = player.currentMediaItem?.mediaId; playFrom = System.currentTimeMillis() } }

    private var eq: android.media.audiofx.Equalizer? = null
    /** Applies (and saves) an equalizer preset ([MusicSmart.presets]); "Flat" turns the equalizer off. */
    fun applyEq(name: String) { MediaPrefs.setStr(this, MediaPrefs.EQ, name); setupEq() }
    private fun setupEq() {
        val name = MediaPrefs.str(this, MediaPrefs.EQ, "Flat")
        try {
            if (name == "Flat") { eq?.enabled = false; return }
            val sid = player.audioSessionId
            if (sid == C.AUDIO_SESSION_ID_UNSET) return
            val e = eq ?: android.media.audiofx.Equalizer(0, sid).also { eq = it }
            val n = e.numberOfBands.toInt()
            val range = e.bandLevelRange
            val centers = (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 }
            MusicSmart.bandLevels(name, centers, range[0].toInt(), range[1].toInt()).forEachIndexed { i, l -> e.setBandLevel(i.toShort(), l.toShort()) }
            e.enabled = true
        } catch (_: Exception) { }
    }

    /** Crossfade-style fades between songs and the sleep timer's fade-out, 5 times a second. */
    private val fadeTick = object : Runnable {
        override fun run() {
            try {
                val cf = MediaPrefs.int(this@MusicService, MediaPrefs.CROSSFADE, 0) * 1000L
                var v = if (cf > 0 && player.isPlaying) MusicSmart.fadeVolume(player.currentPosition, player.duration, cf) else 1f
                val at = sleepAt.value
                if (at > 0 && MediaPrefs.bool(this@MusicService, MediaPrefs.SLEEP_FADE, true)) v *= MusicSmart.sleepVolume(at - System.currentTimeMillis())
                if (kotlin.math.abs(player.volume - v) > 0.01f) player.volume = v
            } catch (_: Exception) { }
            main.postDelayed(this, 200)
        }
    }

    /** Blaze DJ: keeps the music going with songs you play most (and liked ones), never the last 20 again. */
    fun startDj(on: Boolean = true) {
        MediaPrefs.setBool(this, MediaPrefs.DJ, on)
        if (!on) return
        if (player.mediaItemCount == 0) {
            val picks = MusicSmart.djPicks(MediaLib.songs.value, MediaLib.plays.value, MediaLib.liked.value.toSet(), MediaLib.recent.value, 10, System.nanoTime())
            if (picks.isEmpty()) return
            player.setMediaItems(picks.map { item(it) }); player.prepare(); player.play()
        } else { djTopUp(force = true); if (!player.isPlaying) { player.prepare(); player.play() } }
    }

    private fun djTopUp(force: Boolean = false) {
        if (!MediaPrefs.bool(this, MediaPrefs.DJ, false) && !force) return
        val left = player.mediaItemCount - 1 - player.currentMediaItemIndex
        if (left >= 2 && !force) return
        val have = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        val picks = MusicSmart.djPicks(MediaLib.songs.value.filter { it.key !in have }, MediaLib.plays.value, MediaLib.liked.value.toSet(), MediaLib.recent.value, 8, System.nanoTime())
        if (picks.isNotEmpty()) player.addMediaItems(picks.map { item(it) })
    }

    private val sleepTick = object : Runnable {
        override fun run() {
            val at = sleepAt.value
            if (at > 0 && System.currentTimeMillis() >= at) { player.pause(); sleepAt.value = 0 }
            main.postDelayed(this, 1000)
        }
    }

    private val saver = object : Runnable {
        override fun run() { if (player.isPlaying) saveQueue(); main.postDelayed(this, 15_000) }
    }

    /** The queue as library tracks (streams are left out: their urls expire). */
    fun saveQueue() {
        val n = player.mediaItemCount
        if (n == 0) return
        val tracks = (0 until n).mapNotNull { MediaLib.find(player.getMediaItemAt(it).mediaId) }
        val cur = player.currentMediaItem?.mediaId
        val idx = tracks.indexOfFirst { it.key == cur }.coerceAtLeast(0)
        if (tracks.isNotEmpty()) MediaLib.saveQueue(tracks, idx, if (tracks.getOrNull(idx)?.key == cur) player.currentPosition else 0)
    }

    override fun onCreate() {
        super.onCreate()
        MediaLib.ensure(this)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(StreamCache.factory(this, http))
                .setLoadErrorHandlingPolicy(StreamCache.dropPolicy()))   // 0.45.19: drops play on from buffer + cache
            .setLoadControl(StreamCache.loadControl(this))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(on: Boolean) { if (on) reshuffle(); session?.setCustomLayout(layout()) }
            override fun onRepeatModeChanged(mode: Int) { session?.setCustomLayout(layout()) }
            override fun onIsPlayingChanged(playing: Boolean) { if (!playing) { saveQueue(); flushPlay() } else startPlay(); com.blaze.assistant.agent.AwarenessHooks.music(player) }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                item?.mediaId?.let { id -> MediaLib.find(id)?.let { MediaLib.played(it) } }
                startPlay()
                djTopUp()
                prefetchNext()
                offlineSkip(false)
                com.blaze.assistant.agent.AwarenessHooks.music(player, songChanged = true)
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                try { eq?.release() } catch (_: Exception) { }
                eq = null; setupEq()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 0.45.20: offline, a stream that ran past what's on disk moves on to the next item that plays offline.
                if (!NetWatch.now(this@MusicService)) offlineSkip(true) else retryStream(error)
            }
            override fun onPlaybackStateChanged(state: Int) {
                // 0.45.20: offline and stalled on a stream = past its cached part; check again in 1.5 s, then skip.
                main.removeCallbacks(offlineStall)
                if (state == Player.STATE_BUFFERING && player.playWhenReady && !NetWatch.now(this@MusicService)) main.postDelayed(offlineStall, NetHonesty.OFFLINE_SKIP_MS)
                if (state == Player.STATE_ENDED && sleepAt.value == -1L) sleepAt.value = 0
                // Blaze DJ: the queue ran out, so it adds more and carries on.
                if (state == Player.STATE_ENDED && sleepAt.value == 0L && MediaPrefs.bool(this@MusicService, MediaPrefs.DJ, false)) {
                    val before = player.mediaItemCount
                    djTopUp(force = true)
                    if (player.mediaItemCount > before) { player.seekTo(before, 0); player.play() }
                }
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                com.blaze.assistant.agent.AwarenessHooks.music(player)
                // "End of song" sleep timer: stop when the song changes on its own.
                if (sleepAt.value == -1L && reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) { player.pause(); sleepAt.value = 0 }
            }
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, player, controls).setSessionActivity(open)
            .setCustomLayout(layout()).build()
        current = this
        main.post(sleepTick)
        main.post(fadeTick)
        setupEq()
        // Resume where the user left off: the last queue, song and position (paused, ready to play).
        MediaLib.loadQueue()?.let { (items, index, pos) ->
            player.setMediaItems(items.map { item(it) }, index, pos)
            player.prepare()
        }
        main.postDelayed(saver, 15_000)
        pendingPlay?.let { (keys, sh) -> pendingPlay = null; playKeys(keys, sh) }
        if (pendingResume) { pendingResume = false; if (player.mediaItemCount > 0) player.play() }
    }

    /** v0.45.6: the transport for music_ops / the in-app fast path. Main thread. */
    fun isPlayingNow(): Boolean = player.isPlaying
    fun queueSize(): Int = player.mediaItemCount
    fun nowTitle(): String = player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
    fun transport(op: String) {
        when (op) {
            "play" -> { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
            "pause" -> player.pause()
            "next" -> if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekToNext()
            "previous" -> player.seekToPrevious()
        }
    }

    /** v0.34.0: plays library songs by key (mixes, smart playlists, agent). Main thread. */
    fun playKeys(keys: List<String>, shuffle: Boolean = false): Int {
        val items = keys.mapNotNull { MediaLib.find(it) }.map { item(it) }
        if (items.isEmpty()) return 0
        player.setMediaItems(items, if (shuffle) items.indices.random() else 0, 0)
        player.shuffleModeEnabled = shuffle
        player.prepare(); player.play()
        return items.size
    }

    private val scope = kotlinx.coroutines.MainScope()
    private val retried = HashSet<String>()

    /**
     * v0.30.5 (Blazeinchue: "The stream link expired or was refused"): a Store stream the server refuses (403 / 410) is asked
     * for again once (the YouTube plug-in then tries its next client) and picks up where it stopped, instead of
     * leaving the error on screen.
     */
    private fun retryStream(e: androidx.media3.common.PlaybackException) {
        if (e.errorCode != androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        if (!id.startsWith("stream:") || !retried.add(id)) return
        val parts = id.split(":", limit = 3)
        if (parts.size < 3) return
        val src = com.blaze.assistant.mediaapps.store.StoreSources.get(parts[1]) ?: return
        val index = player.currentMediaItemIndex
        val pos = player.currentPosition
        scope.launch {
            val d = try { src.refresh(parts[2], "audio") } catch (_: Exception) { null } ?: return@launch
            val f = src.streamPick("audio", d).firstOrNull() ?: return@launch
            streamUa(src.userAgent)
            if (index !in 0 until player.mediaItemCount || player.getMediaItemAt(index).mediaId != id) return@launch
            player.replaceMediaItem(index, item.buildUpon().setUri(f.url).build())
            player.seekTo(index, pos.coerceAtLeast(0))
            player.prepare(); player.play()
        }
    }

    /** Shuffle with the song that's playing first (QueueLogic, unit tested), the rest random. */
    fun reshuffle() {
        val n = player.mediaItemCount
        if (n < 2) return
        val order = QueueLogic.shuffleOrder(n, player.currentMediaItemIndex.coerceAtLeast(0), System.nanoTime())
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
    }

    /** v0.33.0 The two extra buttons; their icon shows the current state (dim = off, "1" = repeat one). */
    private fun layout(): ImmutableList<CommandButton> {
        val sh = ::player.isInitialized && player.shuffleModeEnabled
        val rep = if (::player.isInitialized) player.repeatMode else Player.REPEAT_MODE_OFF
        val shuffle = CommandButton.Builder().setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
            .setDisplayName(if (sh) "Shuffle on" else "Shuffle off")
            .setIconResId(if (sh) com.blaze.assistant.R.drawable.ic_n_shuffle_on else com.blaze.assistant.R.drawable.ic_n_shuffle_off).build()
        val repeat = CommandButton.Builder().setSessionCommand(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
            .setDisplayName(when (rep) { Player.REPEAT_MODE_ALL -> "Loop all"; Player.REPEAT_MODE_ONE -> "Loop one"; else -> "Loop off" })
            .setIconResId(when (rep) {
                Player.REPEAT_MODE_ALL -> com.blaze.assistant.R.drawable.ic_n_repeat_all
                Player.REPEAT_MODE_ONE -> com.blaze.assistant.R.drawable.ic_n_repeat_one
                else -> com.blaze.assistant.R.drawable.ic_n_repeat_off
            }).build()
        return ImmutableList.of(shuffle, repeat)
    }

    // ---------------------------------------------------------------- v0.34.0 Android Auto (media browser)
    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()

    /** The browse tree Android Auto (and any media browser) shows: Songs, Liked, For you (mixes + smart playlists), Playlists. */
    fun children(parent: String): List<MediaItem> {
        val songs = MediaLib.songs.value
        val byKey = songs.associateBy { it.key }
        fun tracks(keys: List<String>) = keys.mapNotNull { byKey[it] }.map { item(it) }
        val now = System.currentTimeMillis()
        return when {
            parent == AutoTree.ROOT -> listOf(folder(AutoTree.SONGS, "Songs"), folder(AutoTree.LIKED, "Liked"), folder(AutoTree.FOR_YOU, "For you"), folder(AutoTree.PLAYLISTS, "Playlists"))
            parent == AutoTree.SONGS -> songs.map { item(it) }
            parent == AutoTree.LIKED -> tracks(MediaLib.liked.value)
            parent == AutoTree.PLAYLISTS -> MediaLib.playlists.value.map { folder("pl:" + it.id, it.name) }
            parent == AutoTree.FOR_YOU -> (MusicSmart.dailyMixes(songs, MediaLib.plays.value, now / 86_400_000L) +
                MusicSmart.smartLists(songs, MediaLib.plays.value, MediaLib.liked.value, now)).map { folder("sl:" + it.id, it.name) }
            parent.startsWith("pl:") -> tracks(MediaLib.playlists.value.firstOrNull { it.id == parent.removePrefix("pl:") }?.keys.orEmpty())
            parent.startsWith("sl:") -> tracks((MusicSmart.dailyMixes(songs, MediaLib.plays.value, now / 86_400_000L) +
                MusicSmart.smartLists(songs, MediaLib.plays.value, MediaLib.liked.value, now)).firstOrNull { it.id == parent.removePrefix("sl:") }?.keys.orEmpty())
            else -> emptyList()
        }
    }

    private val controls = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(AutoTree.ROOT, "Blaze Music"), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int,
                                   params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all = children(parentId)
            val from = (page.coerceAtLeast(0).toLong() * pageSize.coerceAtLeast(1)).coerceAtMost(all.size.toLong()).toInt()
            val to = (from + pageSize.coerceAtLeast(1)).coerceAtMost(all.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params))
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val t = MediaLib.find(mediaId)
            return Futures.immediateFuture(if (t != null) LibraryResult.ofItem(item(t), null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        /** Items picked in Android Auto arrive without their file uri: look them up in the library. */
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { m -> if (m.localConfiguration != null) m else MediaLib.find(m.mediaId)?.let { item(it) } ?: m }.toMutableList())

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY)).add(SessionCommand(CMD_REPEAT, Bundle.EMPTY)).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(cmds).build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                                     command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (command.customAction) {
                CMD_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                CMD_REPEAT -> player.repeatMode = nextRepeat(player.repeatMode)
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            session.setCustomLayout(layout())
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        com.blaze.assistant.agent.AwarenessHooks.musicStopped()
        saveQueue(); flushPlay()
        main.removeCallbacks(sleepTick); main.removeCallbacks(saver); main.removeCallbacks(fadeTick)
        try { eq?.release() } catch (_: Exception) { }
        current = null
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/** v0.34.0 media browser ids (Android Auto). */
object AutoTree {
    const val ROOT = "blaze:root"
    const val SONGS = "blaze:songs"
    const val LIKED = "blaze:liked"
    const val FOR_YOU = "blaze:foryou"
    const val PLAYLISTS = "blaze:playlists"
}
