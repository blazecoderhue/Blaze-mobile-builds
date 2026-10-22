package com.blaze.assistant.device

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.33.0 Phone Health Check ("why is my phone slow / hot / dying fast?"): one report from the battery, temperature,
 * storage, memory, uptime and app usage. Lists apps you haven't opened in 30+ days with their size (uninstall candidates),
 * the apps you used most this week, and fixes in order of payoff. Read-only: uninstalling stays a separate, confirmed step.
 * Pure grading in [grade] (PhoneHealthTest).
 */
object PhoneHealth {
    const val TOOL = "phone_health"

    data class Input(val battery: Int, val charging: Boolean, val tempC: Float, val freeGb: Float, val totalGb: Float,
                     val freeRamMb: Long, val totalRamMb: Long, val uptimeH: Float, val powerSave: Boolean, val unusedApps: Int, val unusedMb: Long)

    data class Report(val score: Int, val grade: String, val fixes: List<String>)

    fun grade(i: Input): Report {
        var s = 100; val fix = ArrayList<Pair<Int, String>>()
        if (i.totalGb > 0) {
            val pct = i.freeGb / i.totalGb
            when { pct < 0.05f -> { s -= 30; fix += 30 to "Storage is almost full (%.1f GB free): apps slow down and updates fail. Clear WhatsApp media and old videos first.".format(i.freeGb) }
                   pct < 0.12f -> { s -= 15; fix += 15 to "Storage is getting tight (%.1f GB free). Aim for 15%% free.".format(i.freeGb) } }
        }
        if (i.tempC >= 42f) { s -= 20; fix += 20 to "The battery is hot (${i.tempC.toInt()}°C). Close games/camera, take the case off, don't charge in the sun." }
        else if (i.tempC >= 38f) { s -= 8; fix += 8 to "The battery is warm (${i.tempC.toInt()}°C)." }
        if (i.totalRamMb > 0 && i.freeRamMb * 100 / i.totalRamMb < 12) { s -= 12; fix += 12 to "Memory is nearly used up: restart the phone, then remove apps that run in the background." }
        if (i.uptimeH > 24 * 7) { s -= 10; fix += 10 to "The phone hasn't restarted in ${(i.uptimeH / 24).toInt()} days. A restart clears memory leaks." }
        if (i.unusedApps >= 10) { s -= 8; fix += 8 to "${i.unusedApps} apps unused for 30+ days take ${if (i.unusedMb > 1024) "%.1f GB".format(i.unusedMb / 1024f) else "${i.unusedMb} MB"}. Want me to list them for removal?" }
        if (!i.charging && i.battery in 0..15) { s -= 5; fix += 5 to "Battery is at ${i.battery}%." + if (!i.powerSave) " Turn on power saving." else "" }
        s = s.coerceIn(0, 100)
        val g = when { s >= 85 -> "healthy"; s >= 65 -> "okay"; s >= 45 -> "needs attention"; else -> "struggling" }
        return Report(s, g, fix.sortedByDescending { it.first }.map { it.second })
    }

    data class AppUse(val label: String, val pkg: String, val lastUsed: Long, val fgMs: Long, val sizeMb: Long)

    private fun apps(ctx: Context): List<AppUse> {
        val pm = ctx.packageManager
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val stats = try { usm?.queryAndAggregateUsageStats(now - 90L * 86_400_000L, now) } catch (_: Exception) { null } ?: emptyMap()
        val week = try { usm?.queryAndAggregateUsageStats(now - 7L * 86_400_000L, now) } catch (_: Exception) { null } ?: emptyMap()
        return pm.getInstalledApplications(0).filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName }.map { ai ->
            val size = try { File(ai.sourceDir).length() / 1_048_576 } catch (_: Exception) { 0L }
            AppUse(pm.getApplicationLabel(ai).toString(), ai.packageName, stats[ai.packageName]?.lastTimeUsed ?: 0L, week[ai.packageName]?.totalTimeInForeground ?: 0L, size)
        }
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Phone Health Check: score + fixes from battery, heat, storage, memory, uptime and app usage; unused apps with sizes; " +
            "most-used apps this week. Use for 'why is my phone slow/hot/dying', 'clean up my phone'. Read-only.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("detail", JSONObject().put("type", "string").put("enum", JSONArray(listOf("report", "unused_apps", "top_apps"))))).put("required", JSONArray())))

    fun run(ctx: Context, a: JSONObject): String {
        val now = System.currentTimeMillis()
        val usage = com.blaze.assistant.home.HomeSources.usageAllowed(ctx)
        val list = if (usage) apps(ctx) else emptyList()
        val unused = list.filter { it.lastUsed in 1 until now - 30L * 86_400_000L || it.lastUsed == 0L }.sortedByDescending { it.sizeMb }
        return when (a.optString("detail", "report")) {
            "unused_apps" -> if (!usage) "Needs Usage access (Settings › Apps › Special access › Usage access › Blaze)." else if (unused.isEmpty()) "Every app was used in the last 30 days."
                else "Unused for 30+ days (app size only; data and cache come on top):\n" + unused.take(30).joinToString("\n") { "${it.label} · ${it.sizeMb} MB · " +
                    if (it.lastUsed == 0L) "not opened in 90 days" else "last opened ${(now - it.lastUsed) / 86_400_000L} days ago" }
            "top_apps" -> if (!usage) "Needs Usage access." else list.filter { it.fgMs > 60_000 }.sortedByDescending { it.fgMs }.take(10)
                .joinToString("\n") { "${it.label}: ${it.fgMs / 3_600_000}h ${(it.fgMs / 60_000) % 60}m this week" }.ifBlank { "No app use recorded this week." }
            else -> {
                val s = DeviceStatus.snap(ctx)
                val r = grade(Input(s.battery, s.charging, s.tempC, s.freeStorageGb, s.totalStorageGb, s.freeRamMb, s.totalRamMb, s.uptimeH, s.powerSave,
                    unused.size, unused.sumOf { it.sizeMb }))
                "Phone health ${r.score}/100 (${r.grade}). Battery ${s.battery}%${if (s.charging) " charging" else ""}, ${s.tempC}°C, ${s.health}; storage " +
                    "%.1f of %.0f GB free; memory ${s.freeRamMb} of ${s.totalRamMb} MB free; up ${(s.uptimeH).toInt()} h.".format(s.freeStorageGb, s.totalStorageGb) +
                    (if (r.fixes.isEmpty()) "\nNothing to fix." else "\nFixes, biggest payoff first:\n" + r.fixes.mapIndexed { i, f -> "${i + 1}. $f" }.joinToString("\n")) +
                    if (!usage) "\n(Turn on Usage access for unused-app checks.)" else ""
            }
        }
    }
}
