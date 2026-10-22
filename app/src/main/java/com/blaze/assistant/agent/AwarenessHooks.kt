package com.blaze.assistant.agent

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player

/**
 * v0.37.0: the Android side of [Awareness]. Installed once from BlazeApp:
 *  - the Blaze screen in front (activity callbacks) and the view open over the chat (Views.showing, Design's title);
 *  - other apps' media (Spotify, YouTube, podcasts…) from their media sessions, read through the notification
 *    listener Blaze already has (no new permission; nothing when notification access is off);
 *  - the BlazeOS app in front;
 *  - Blaze Music and Blaze Video report their own player ([music], [video], [videoClosed]).
 * All readers are cheap and never throw.
 */
object AwarenessHooks {
    @Volatile private var installed = false
    @Volatile private var started = 0
    private val friendly = mapOf(
        "MainActivity" to "Chat", "VideoActivity" to "Blaze Video", "MusicActivity" to "Blaze Music",
        "BlazeOsActivity" to "BlazeOS", "BrowserActivity" to "Browser", "SiteAppActivity" to "a site app",
        "BlazeTerminalActivity" to "Terminal",
    )

    fun screenName(a: Activity): String = friendly[a.javaClass.simpleName]
        ?: a.javaClass.simpleName.removeSuffix("Activity").replace(Regex("([a-z])([A-Z])"), "$1 $2").ifBlank { "Blaze" }

    fun install(app: Application) {
        if (installed) return
        installed = true
        try { Awareness.init(app.filesDir) } catch (_: Throwable) { }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                val name = screenName(a)
                Awareness.set(Awareness.Now("screen", "On screen", name, "Blaze"))
                if (name != "Chat") Awareness.did("opened", "opened $name")
            }
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) { started = (started - 1).coerceAtLeast(0); if (started == 0) Awareness.clear("screen") }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        Awareness.readers += { viewNow() }
        Awareness.readers += { osNow() }
        Awareness.readers += { phoneMedia(app) }
    }

    /** The view over the chat (Design, Learn…), with the design's title when it's Design. */
    private fun viewNow(): List<Awareness.Now> {
        val v = com.blaze.assistant.ui.Views.showing.value
        if (v.isBlank() || Awareness.get("screen")?.title != "Chat") return emptyList()
        val name = com.blaze.assistant.ui.Views.find(v)?.name ?: v
        val title = if (v == "design") (try { Designs.current()?.title } catch (_: Throwable) { null })?.ifBlank { null } else null
        return listOf(Awareness.Now("view", "Open", title ?: name, if (title != null) "the Design view" else "Blaze"))
    }

    private fun osNow(): List<Awareness.Now> {
        val os = com.blaze.assistant.blazeos.BlazeOs
        if (!os.ready || os.power.value != com.blaze.assistant.blazeos.Power.ON) return emptyList()
        val front = os.tasks.value.front
        val title = if (front.isBlank()) "Home screen" else try { os.label(front) } catch (_: Throwable) { front }
        val open = os.tasks.value.open.size
        return listOf(Awareness.Now("blazeos", "Open", title, "BlazeOS", if (open > 1) "($open apps open)" else ""))
    }

    /** Other apps' media sessions (Blaze's own are reported by the players themselves). */
    private fun phoneMedia(ctx: Context): List<Awareness.Now> {
        if (com.blaze.assistant.notify.NotificationBrain.instance == null) return emptyList()
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        val list: List<MediaController> = try {
            msm.getActiveSessions(ComponentName(ctx, com.blaze.assistant.notify.NotificationBrain::class.java))
        } catch (_: Throwable) { return emptyList() }
        return list.filter { it.packageName != ctx.packageName }.mapNotNull { c -> session(ctx, c) }.take(3)
    }

    private fun session(ctx: Context, c: MediaController): Awareness.Now? {
        val md = c.metadata ?: return null
        val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val dur = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        val st = c.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING
        if (st != null && st.state in setOf(PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR)) return null
        // PlaybackState's position is as of lastPositionUpdateTime (elapsedRealtime); move it to wall-clock time.
        val now = System.currentTimeMillis()
        val pos = st?.let { s -> if (s.position < 0) -1L else s.position } ?: -1L
        val at = st?.let { s -> now - (SystemClock.elapsedRealtime() - s.lastPositionUpdateTime).coerceAtLeast(0) } ?: now
        val app = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString() } catch (_: Throwable) { c.packageName }
        return Awareness.Now("media:" + c.packageName, if (playing) "Playing" else "Paused", title, app,
            if (artist.isNotBlank()) "by $artist" else "", pos, if (dur > 0) dur else -1, playing, at)
    }

    // ---------------- Blaze's own players (called on the player's thread) ----------------

    /** Blaze Music's player changed (song, play / pause, seek). [songChanged] logs the new song as played. */
    fun music(p: Player, songChanged: Boolean = false) {
        try {
            val item = p.currentMediaItem
            if (item == null) { Awareness.clear("music"); return }
            val t = com.blaze.assistant.mediaapps.MediaLib.find(item.mediaId)
            val title = t?.title ?: item.mediaMetadata.title?.toString() ?: return
            val artist = t?.artist?.ifBlank { null } ?: item.mediaMetadata.artist?.toString() ?: ""
            val dur = p.duration.takeIf { it > 0 } ?: t?.durationMs?.takeIf { it > 0 } ?: -1
            Awareness.set(Awareness.Now("music", if (p.isPlaying) "Playing" else "Paused", title, "Blaze Music",
                if (artist.isNotBlank()) "by $artist" else "", p.currentPosition, dur, p.isPlaying))
            if (songChanged || p.isPlaying) Awareness.did("listened", "played \"$title\"" + (if (artist.isNotBlank()) " by $artist" else "") + " in Blaze Music")
        } catch (_: Throwable) { }
    }

    fun musicStopped() = Awareness.clear("music")

    /** Blaze Video's player: the video loaded, played, paused or moved. */
    fun video(p: Player, t: com.blaze.assistant.mediaapps.Track?) {
        try {
            if (t == null) { Awareness.clear("video"); return }
            val dur = p.duration.takeIf { it > 0 } ?: t.durationMs.takeIf { it > 0 } ?: -1
            Awareness.set(Awareness.Now("video", if (p.isPlaying) "Watching" else "Paused", t.title, "Blaze Video",
                "", p.currentPosition, dur, p.isPlaying))
        } catch (_: Throwable) { }
    }

    /** The video was closed or replaced: remember where it was left (what "the movie I was just watching" means). */
    fun videoLeft(p: Player, t: com.blaze.assistant.mediaapps.Track?, clear: Boolean) {
        try {
            if (t == null) return
            val dur = p.duration.takeIf { it > 0 } ?: t.durationMs
            val pos = p.currentPosition
            val done = dur > 0 && pos >= dur - 30_000
            Awareness.did("watched", "watched \"${t.title}\" in Blaze Video, " +
                if (done) "finished it" else "stopped at ${Awareness.clock(pos)}" + (if (dur > 0) " of ${Awareness.clock(dur)}" else ""))
            if (clear) Awareness.clear("video")
        } catch (_: Throwable) { }
    }
}
