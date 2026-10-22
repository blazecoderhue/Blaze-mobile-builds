package com.blaze.assistant.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Where the phone is: last known fix from any provider, plus a street address when the geocoder has one. */
object Location {
    const val NAME = "get_location"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", NAME)
        .put("description", "The phone's current location (latitude, longitude, accuracy, nearest address). Use instead of asking the user where they are.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray())))

    @Suppress("MissingPermission", "DEPRECATION")
    suspend fun run(ctx: Context): String {
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!coarse && !com.blaze.assistant.device.Perms.ensure(ctx, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            && ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return com.blaze.assistant.device.Perms.denied("location")
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null || !(try { lm.isLocationEnabled } catch (_: Exception) { true })) return "Location is switched off on the phone (Quick Settings → Location)."
        val best = lm.getProviders(true).mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time } ?: return "No location fix yet. Open Maps once so the phone gets a fix, then try again."
        val ageMin = (System.currentTimeMillis() - best.time) / 60_000
        val addr = try {
            Geocoder(ctx, Locale.getDefault()).getFromLocation(best.latitude, best.longitude, 1)?.firstOrNull()?.let { a ->
                (0..a.maxAddressLineIndex).joinToString(", ") { a.getAddressLine(it) }
            }
        } catch (_: Exception) { null }
        return "Location: ${"%.5f".format(best.latitude)}, ${"%.5f".format(best.longitude)} (±${best.accuracy.toInt()} m, ${if (ageMin < 1) "just now" else "$ageMin min ago"})" +
            (addr?.let { "\nNear: $it" } ?: "")
    }
}
