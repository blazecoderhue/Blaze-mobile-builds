package com.blaze.assistant.proactive

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blaze.assistant.agent.Settings
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v0.33.0 Autopilot Routines: more trigger events, so one sentence ("when I get to campus, go silent, open my timetable
 * and turn on Wi-Fi") becomes a trigger whose prompt Blaze runs on its own:
 * - place_arrive / place_leave: a saved place ("Campus@-12.80,28.21,200"; "here" = where the phone is now). Checked every
 *   15 minutes from the last location fix (no extra battery drain from GPS polling).
 * - bluetooth: a Bluetooth device connects (match = part of its name; blank = any).
 * - wifi: the phone joins a Wi-Fi network (match = part of the network name; blank = any).
 * Pure geometry and enter/leave logic in [Geo] (AutopilotTest).
 */
object Autopilot {
    object Geo {
        data class Place(val name: String, val lat: Double, val lng: Double, val radiusM: Double)

        fun meters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
            val r = 6_371_000.0; val dLat = Math.toRadians(bLat - aLat); val dLng = Math.toRadians(bLng - aLng)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
            return 2 * r * atan2(sqrt(h), sqrt(1 - h))
        }

        /** "Campus@-12.80,28.21,200" / "Campus@-12.80,28.21" (150 m). */
        fun parse(match: String): Place? {
            val m = Regex("""^\s*(.*?)\s*@\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)(?:\s*,\s*(\d+(?:\.\d+)?))?\s*$""").find(match) ?: return null
            val lat = m.groupValues[2].toDouble(); val lng = m.groupValues[3].toDouble()
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            return Place(m.groupValues[1].ifBlank { "the place" }, lat, lng, m.groupValues[4].toDoubleOrNull()?.coerceIn(50.0, 5000.0) ?: 150.0)
        }

        /**
         * Inside / outside with hysteresis (enter under r, leave over r + 50 m + accuracy) so a jittery fix doesn't flap.
         * Returns the new state; [was] null = unknown (first reading never fires).
         */
        fun inside(p: Place, lat: Double, lng: Double, accM: Double, was: Boolean?): Boolean {
            val d = meters(p.lat, p.lng, lat, lng)
            return when (was) { true -> d <= p.radiusM + 50 + accM.coerceAtMost(300.0); else -> d <= p.radiusM + accM.coerceAtMost(100.0) / 2 }
        }
    }

    private var started = false

    @SuppressLint("MissingPermission")
    fun init(ctx: Context) {
        if (started) return; started = true
        val app = ctx.applicationContext
        val bt = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
                val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val name = try { if (Build.VERSION.SDK_INT < 31 || app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) dev?.name else null } catch (_: Exception) { null }
                Triggers.fireEvent("bluetooth", name.orEmpty(), "Bluetooth device connected: ${name ?: "a device"}.")
            }
        }
        try { if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(bt, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED), Context.RECEIVER_EXPORTED)
              else app.registerReceiver(bt, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)) } catch (_: Exception) { }
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            var lastSsid = ""
            cm?.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val ssid = try { @Suppress("DEPRECATION") app.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.trim('"')
                        ?.takeIf { it != "<unknown ssid>" } } catch (_: Exception) { null }.orEmpty()
                    if (ssid.isNotEmpty() && ssid == lastSsid) return
                    lastSsid = ssid
                    Triggers.fireEvent("wifi", ssid, "Joined Wi-Fi ${ssid.ifBlank { "(name hidden: needs location permission)" }}.")
                }
                override fun onLost(network: Network) { lastSsid = "" }
            })
        } catch (_: Exception) { }
        schedulePlaces(app)
    }

    fun schedulePlaces(ctx: Context) {
        val any = Triggers.all.value.any { it.enabled && it.event.startsWith("place_") }
        try {
            val wm = WorkManager.getInstance(ctx)
            if (any) wm.enqueueUniquePeriodicWork("autopilot_places", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<AutopilotWorker>(15, TimeUnit.MINUTES).build())
            else wm.cancelUniqueWork("autopilot_places")
        } catch (_: Throwable) { }
    }

    @SuppressLint("MissingPermission")
    fun checkPlaces(ctx: Context) {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val fix = lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }.maxByOrNull { it.time } ?: return
        if (System.currentTimeMillis() - fix.time > 30 * 60_000L) return
        Triggers.all.value.filter { it.enabled && it.event.startsWith("place_") }.forEach { t ->
            val p = Geo.parse(t.match) ?: return@forEach
            val key = "ap_in_${t.id}"
            val was = Settings.str(key).let { if (it.isEmpty()) null else it == "1" }
            val now = Geo.inside(p, fix.latitude, fix.longitude, fix.accuracy.toDouble(), was)
            Settings.setStr(key, if (now) "1" else "0")
            if (was != null && was != now) {
                if (now && t.event == "place_arrive") Triggers.fireOne(t, "The user just arrived at ${p.name}.")
                if (!now && t.event == "place_leave") Triggers.fireOne(t, "The user just left ${p.name}.")
            }
        }
    }

    /** "here" → the current fix as "Name@lat,lng,radius". */
    @SuppressLint("MissingPermission")
    fun hereMatch(ctx: Context, name: String, radius: Int): String? {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val f = lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }.maxByOrNull { it.time } ?: return null
        return "%s@%.5f,%.5f,%d".format(java.util.Locale.US, name.ifBlank { "here" }, f.latitude, f.longitude, radius)
    }
}

class AutopilotWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result { try { Autopilot.checkPlaces(applicationContext) } catch (_: Throwable) { }; return Result.success() }
}
