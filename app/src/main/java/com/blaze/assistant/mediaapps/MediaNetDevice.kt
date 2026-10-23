package com.blaze.assistant.mediaapps

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.blaze.assistant.connect.Entitlements
import com.blaze.assistant.mediaapps.MediaNet.Link
import java.time.YearMonth
import java.time.ZoneOffset

/** 0.45.19: what [MediaNet]'s pure rules need from the phone and the plan (GET /v1/entitlements). */
object MediaNetDevice {
    /** The active network as a [Link]; an unreadable answer counts as weak (no prefetch, normal playback). */
    fun link(ctx: Context): Link = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val n = cm?.activeNetwork
        val caps = n?.let { cm.getNetworkCapabilities(it) }
        if (cm == null) Link.WEAK
        else if (caps == null) Link.NONE
        else MediaNet.classify(true, caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), caps.linkDownstreamBandwidthKbps)
    } catch (_: Exception) { Link.WEAK }

    /** Data Saver applies now: Blaze's own (on, on a metered network) or Android's system Data Saver on a metered network. */
    fun saving(ctx: Context): Boolean = MediaPrefs.saving(ctx) || try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm != null && cm.isActiveNetworkMetered && cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    } catch (_: Exception) { false }

    /** Downloads left this month for [kind] ("song"/"video"): null = the server didn't say, Int.MAX_VALUE = no cap. */
    fun left(s: Entitlements.Snapshot?, kind: String): Int? = (if (kind == "video") s?.video else s?.song)?.let { Entitlements.left(it) }

    fun cacheCap(deviceBudget: Long, s: Entitlements.Snapshot?): Long = MediaNet.cacheCap(deviceBudget, left(s, "song"), left(s, "video"))

    /** The month prefetches count against: the server's ("2026-10"), else this UTC month. */
    fun month(s: Entitlements.Snapshot?, nowMs: Long): String = s?.month?.takeIf { it.isNotBlank() }
        ?: YearMonth.from(java.time.Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC)).toString()

    private const val PREFS = "blaze_media_net"
    private fun ledger(ctx: Context): Map<String, Int> = ctx.applicationContext.getSharedPreferences(PREFS, 0).all
        .mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()

    /** Decides and records one prefetch of [kind]; returns the bytes to fetch, or 0 when it isn't allowed now. */
    fun takePrefetch(ctx: Context, kind: String): Long {
        val s = Entitlements.cached(ctx)
        val link = link(ctx)
        val month = month(s, System.currentTimeMillis())
        val l = ledger(ctx)
        if (MediaNet.noPrefetch(link, saving(ctx), left(s, kind), MediaNet.count(l, month, kind)) != null) return 0
        val next = MediaNet.bump(l, month, kind)
        ctx.applicationContext.getSharedPreferences(PREFS, 0).edit().clear().apply { next.forEach { (k, v) -> putInt(k, v) } }.apply()
        return MediaNet.prefetchBytes(link, kind)
    }
}
