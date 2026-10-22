package com.blaze.assistant.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opt-in screen memory: when you switch apps, keeps a short text snapshot of what was on screen,
 * only on this phone (filesDir/screen_memory.json, last 300). Screens with password fields are skipped.
 * Blaze can search it ("what was that address I saw in Chrome?").
 */
object ScreenMemory {
    private const val MAX = 300
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.blaze.assistant.device.CrashLog.guard)
    private val skipPkgs = listOf("com.android.systemui", "launcher", "keyguard", "password", "bitwarden", "1password", "lastpass", "authenticator")
    private val list = ArrayList<JSONObject>()
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "screen_memory.json")

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try { val a = JSONArray(file(ctx).readText()); for (i in 0 until a.length()) list += a.getJSONObject(i) } catch (_: Exception) { }
    }

    fun onWindow(svc: AccessibilityService, pkg: String) {
        if (!Settings.screenMemory || skipPkgs.any { pkg.contains(it) }) return
        scope.launch {
            delay(900)
            val root = svc.rootInActiveWindow ?: return@launch
            if (root.packageName?.toString() != pkg) return@launch
            val sb = StringBuilder(); var secret = false
            fun walk(n: AccessibilityNodeInfo?, d: Int) {
                if (n == null || d > 30 || sb.length > 1500) return
                if (n.isPassword) { secret = true; return }
                val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (t.length in 2..200) sb.append(t).append(" · ")
                for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
            }
            walk(root, 0)
            if (secret || sb.length < 20) return@launch
            val label = try { svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            val entry = JSONObject().put("t", System.currentTimeMillis()).put("app", label).put("text", sb.toString().take(1200))
            launch(Dispatchers.IO) {
                synchronized(list) {
                    load(svc); list += entry
                    while (list.size > MAX) list.removeAt(0)
                    file(svc).writeText(JSONArray(list).toString())
                }
            }
        }
    }

    fun count(ctx: Context): Int = synchronized(list) { load(ctx); list.size }

    fun clear(ctx: Context) = synchronized(list) { list.clear(); file(ctx).delete(); Unit }

    fun search(ctx: Context, query: String, app: String?): String = synchronized(list) {
        load(ctx)
        if (list.isEmpty()) return if (Settings.screenMemory) "Screen memory is empty so far." else "Screen memory is off (Settings → Memory)."
        val words = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
        val hits = list.asReversed().filter { app.isNullOrBlank() || it.optString("app").contains(app, true) }
            .map { it to words.count { w -> it.optString("text").lowercase().contains(w) } }
            .filter { words.isEmpty() || it.second > 0 }.sortedByDescending { it.second }.take(5)
        if (hits.isEmpty()) return "Nothing matching \"$query\" in screen memory."
        hits.joinToString("\n\n") { (o, _) -> "${fmt.format(Date(o.optLong("t")))} in ${o.optString("app")}: ${o.optString("text").take(500)}" }
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "search_screen_memory")
        .put("description", "Search text the user saw on their screen recently (kept only on the phone, if they turned screen memory on). Use for 'what was that … I saw earlier'.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("description", "Words to look for"))
            .put("app", JSONObject().put("type", "string").put("description", "Optional app name filter")))
            .put("required", JSONArray(listOf("query")))))
}
