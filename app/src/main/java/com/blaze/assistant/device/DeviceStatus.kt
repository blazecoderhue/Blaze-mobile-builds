package com.blaze.assistant.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** What state the phone is in: battery, heat, storage, memory, network, sound. Cheap to read. */
object DeviceStatus {
    data class Snap(val battery: Int, val charging: Boolean, val tempC: Float, val freeStorageGb: Float, val totalStorageGb: Float,
                    val freeRamMb: Long, val totalRamMb: Long, val lowRam: Boolean, val network: String, val powerSave: Boolean,
                    val ringer: String, val uptimeH: Float, val health: String,
                    val internal: StorageMath.Vol? = null, val sdCards: List<StorageMath.Vol> = emptyList())

    fun snap(ctx: Context): Snap {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l < 0) -1 else l * 100 / s } ?: -1
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val health = when (b?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"; BatteryManager.BATTERY_HEALTH_COLD -> "cold"; else -> "unknown" }
        // v0.30.4: the same numbers as Settings › Storage (StorageStatsManager), not StatFs(/data) (see StorageMath).
        val internal = internalStorage(ctx)
        val sd = sdCards(ctx)
        val free = internal?.let { it.free / 1e9f } ?: -1f
        val total = internal?.let { it.total / 1e9f } ?: -1f
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = try { cm?.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null }
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "mobile data" else "mobile data (metered)"
            else -> "online"
        } + if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) " (no internet)" else ""
        val pm = ctx.getSystemService(PowerManager::class.java)
        val audio = ctx.getSystemService(AudioManager::class.java)
        val ringer = when (audio?.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }
        return Snap(level, charging, temp, free, total, mi.availMem / 1_048_576, mi.totalMem / 1_048_576, mi.lowMemory, net,
            pm?.isPowerSaveMode == true, ringer, SystemClock.elapsedRealtime() / 3_600_000f, health, internal, sd)
    }

    /**
     * Internal storage as Settings shows it: StorageStatsManager for the default volume (total rounded to the advertised
     * size, free including what Android can clear on demand). Fallback: StatFs on /data, total rounded the same way.
     */
    fun internalStorage(ctx: Context): StorageMath.Vol? {
        try {
            val ssm = ctx.getSystemService(android.app.usage.StorageStatsManager::class.java)
            if (ssm != null) {
                val uuid = android.os.storage.StorageManager.UUID_DEFAULT
                val total = ssm.getTotalBytes(uuid); val free = ssm.getFreeBytes(uuid)
                if (total > 0 && free in 0..total) return StorageMath.Vol("Internal storage", total, free)
            }
        } catch (_: Exception) { }
        return try {
            val st = StatFs(Environment.getDataDirectory().path)
            val total = StorageMath.advertised(st.totalBytes)
            StorageMath.Vol("Internal storage", total, st.availableBytes.coerceAtMost(total))
        } catch (_: Exception) { null }
    }

    /** Removable SD cards that are mounted (their app-specific dir sits on the card; StatFs reads the whole volume). */
    fun sdCards(ctx: Context): List<StorageMath.Vol> = try {
        ctx.getExternalFilesDirs(null).drop(1).filterNotNull().filter {
            try { Environment.isExternalStorageRemovable(it) && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED } catch (_: Exception) { false }
        }.mapIndexedNotNull { i, d ->
            try { val st = StatFs(d.path); StorageMath.Vol(if (i == 0) "SD card" else "SD card ${i + 1}", st.totalBytes, st.availableBytes) } catch (_: Exception) { null }
        }
    } catch (_: Exception) { emptyList() }

    /** Things worth a heads-up, empty when all is fine. */
    fun warnings(s: Snap): List<String> = buildList {
        if (s.battery in 0..15 && !s.charging) add("Battery is at ${s.battery}%")
        if (s.tempC >= 42f) add("Phone is hot (%.0f °C)".format(s.tempC))
        if (s.freeStorageGb in 0f..1.5f) add("Storage almost full (%.1f GB free)".format(s.freeStorageGb))
        if (s.lowRam) add("Memory is low")
        if (s.network.startsWith("offline")) add("No connection")
    }

    fun describe(ctx: Context): String {
        val s = snap(ctx)
        return buildString {
            append("Time: ${java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}.\n")
            append("Battery ${s.battery}%${if (s.charging) " (charging)" else ""}, ${"%.0f".format(s.tempC)} °C, health ${s.health}")
            if (s.powerSave) append(", battery saver on")
            append(".\nStorage (decimal GB, as in Settings): ").append(StorageMath.describe(s.internal, s.sdCards))
            append("\nMemory: ${s.freeRamMb} MB free of ${s.totalRamMb} MB${if (s.lowRam) " (LOW)" else ""}.")
            append("\nNetwork: ${s.network}. Ringer: ${s.ringer}. Up ${"%.1f".format(s.uptimeH)} h. ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}.")
            val w = warnings(s); if (w.isNotEmpty()) append("\nAttention: ").append(w.joinToString("; ")).append('.')
        }
    }

    private fun prop(name: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
    } catch (_: Exception) { "" }

    /** Who this phone is, worked out once: make, model, Android and skin version, screen, RAM, region. */
    @Volatile private var idCache = ""
    fun identity(ctx: Context): String {
        if (idCache.isNotEmpty()) return idCache
        val make = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val skin = when {
            make.equals("samsung", true) -> prop("ro.build.version.oneui").toIntOrNull()?.let { v -> " (One UI ${v / 10000}.${(v / 100) % 100})" } ?: " (One UI)"
            make.equals("xiaomi", true) || make.equals("redmi", true) || make.equals("poco", true) -> prop("ro.miui.ui.version.name").takeIf { it.isNotBlank() }?.let { " (MIUI/HyperOS $it)" } ?: ""
            make.equals("oneplus", true) || make.equals("oppo", true) || make.equals("realme", true) -> prop("ro.build.version.oplusrom").takeIf { it.isNotBlank() }?.let { " (ColorOS/OxygenOS $it)" } ?: ""
            make.equals("huawei", true) || make.equals("honor", true) -> prop("ro.build.version.emui").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            make.equals("tecno", true) || make.equals("infinix", true) || make.equals("itel", true) -> prop("ro.os_product.version").takeIf { it.isNotBlank() }?.let { " (HiOS/XOS $it)" } ?: ""
            else -> ""
        }
        val dm = ctx.resources.displayMetrics
        val ram = try { val mi = android.app.ActivityManager.MemoryInfo(); ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi); "%.0f GB RAM".format(mi.totalMem / 1e9) } catch (_: Exception) { "" }
        val country = try { ctx.getSystemService(android.telephony.TelephonyManager::class.java)?.simCountryIso?.uppercase().orEmpty() } catch (_: Exception) { "" }
        idCache = "This phone: $make ${Build.MODEL} (device ${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}$skin, " +
            "${dm.widthPixels}x${dm.heightPixels} screen, $ram, ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}" +
            (if (country.isNotBlank()) ", SIM country $country" else "") + ", language ${java.util.Locale.getDefault().toLanguageTag()}."
        return idCache
    }

    /** One line for the system prompt so Blaze is aware without spending a tool call. */
    fun promptLine(ctx: Context): String = try {
        val s = snap(ctx)
        val w = warnings(s)
        "\n" + identity(ctx) + "\nPhone now: battery ${s.battery}%${if (s.charging) " charging" else ""}, ${s.network}, ringer ${s.ringer}." +
            (if (w.isNotEmpty()) " Note: ${w.joinToString("; ")}." else "")
    } catch (_: Exception) { "" }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "device_status")
        .put("description", "Full phone health: battery, temperature, storage, memory, network, ringer, uptime, model. Use for questions about the phone's state or before heavy tasks.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray())))
}
