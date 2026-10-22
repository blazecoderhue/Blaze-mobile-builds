package com.blaze.assistant.mediaapps

import android.content.Context
import android.net.ConnectivityManager

/**
 * v0.34.0: Blaze Music / Video settings (autoplay, smart downloads, data saver, equalizer, crossfade, sleep fade)
 * and small per-video memories (where a series' intro ends, which downloads were watched to the end). One prefs file.
 */
object MediaPrefs {
    private const val FILE = "blaze_media_v34"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun bool(ctx: Context, key: String, def: Boolean) = try { p(ctx).getBoolean(key, def) }
        catch (_: ClassCastException) { com.blaze.assistant.agent.PrefCoerce.bool(p(ctx).all[key], def) }   // v0.45.5 hotfix
    fun setBool(ctx: Context, key: String, v: Boolean) = p(ctx).edit().putBoolean(key, v).apply()
    fun int(ctx: Context, key: String, def: Int) = p(ctx).getInt(key, def)
    fun setInt(ctx: Context, key: String, v: Int) = p(ctx).edit().putInt(key, v).apply()
    fun str(ctx: Context, key: String, def: String = "") = p(ctx).getString(key, def) ?: def
    fun setStr(ctx: Context, key: String, v: String) = p(ctx).edit().putString(key, v).apply()

    const val AUTOPLAY = "video_autoplay"            // default on
    const val SMART_DL = "video_smart_downloads"     // default off
    const val DATA_SAVER = "music_data_saver"        // default on: smallest stream on mobile data
    const val EQ = "music_eq_preset"                 // preset name, "Flat" = off
    const val CROSSFADE = "music_crossfade_s"        // 0 = off
    const val SLEEP_FADE = "music_sleep_fade"        // default on
    const val DJ = "music_dj"                        // Blaze DJ keeps the music going
    const val STRIP_GPS = "share_strip_location"     // default on

    fun autoplay(ctx: Context) = bool(ctx, AUTOPLAY, true)
    fun smartDownloads(ctx: Context) = bool(ctx, SMART_DL, false)
    fun dataSaver(ctx: Context) = bool(ctx, DATA_SAVER, true)

    /** True on mobile data (or any metered network). */
    fun metered(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) { false }

    /** Data saver applies now: on, and on a metered network. */
    fun saving(ctx: Context) = dataSaver(ctx) && metered(ctx)

    // ---- per-series intro ends ----
    fun intros(ctx: Context): Map<String, Long> = VideoSmart.introParse(str(ctx, "video_intros", "{}"))
    fun setIntro(ctx: Context, series: String, endMs: Long) {
        val m = intros(ctx).toMutableMap(); if (endMs > 0) m[series] = endMs else m.remove(series)
        setStr(ctx, "video_intros", VideoSmart.introJson(m))
    }

    // ---- videos watched to the end (smart downloads) ----
    fun finished(ctx: Context): Map<String, Long> = VideoSmart.introParse(str(ctx, "video_finished", "{}"))
    fun markFinished(ctx: Context, key: String, at: Long) {
        val m = finished(ctx).toMutableMap(); m[key] = at
        if (m.size > 400) m.keys.sortedBy { m[it] }.take(m.size - 400).forEach { m.remove(it) }
        setStr(ctx, "video_finished", VideoSmart.introJson(m))
    }
}
