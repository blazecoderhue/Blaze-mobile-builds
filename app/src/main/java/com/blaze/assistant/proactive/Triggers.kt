package com.blaze.assistant.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * "When X happens, do Y". Events: a notification (from an app / containing words), battery
 * dropping below a level, the charger plugged in or out, an app opened. Each fire runs the
 * trigger's prompt in its own background chat, with the event details attached.
 */
object Triggers {
    data class Trigger(val id: Long, val name: String, val event: String, val match: String, val prompt: String, val enabled: Boolean = true, val lastFired: Long = 0)

    private lateinit var app: Context
    val all = MutableStateFlow<List<Trigger>>(emptyList())
    private var lastBattery = -1

    fun init(ctx: Context) {
        app = ctx.applicationContext
        all.value = load()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / maxOf(1, i.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
                        if (lastBattery >= 0 && l < lastBattery) onBattery(l)
                        lastBattery = l
                    }
                    Intent.ACTION_POWER_CONNECTED -> fire("charging", "plugged in", "The charger was plugged in.")
                    Intent.ACTION_POWER_DISCONNECTED -> fire("charging", "unplugged", "The charger was unplugged.")
                }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }
        try { if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f) } catch (_: Exception) { }
        try { Autopilot.init(app) } catch (_: Throwable) { }   // v0.33.0 place / bluetooth / wifi events
    }

    private fun load(): List<Trigger> = try {
        val a = JSONArray(Settings.str("triggers", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Trigger(it.optLong("id"), it.optString("name"), it.optString("event"),
            it.optString("match"), it.optString("prompt"), it.optBoolean("enabled", true), it.optLong("last")) }
    } catch (_: Exception) { emptyList() }

    private fun save(l: List<Trigger>) {
        all.value = l
        Settings.setStr("triggers", JSONArray().apply { l.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("event", it.event)
            .put("match", it.match).put("prompt", it.prompt).put("enabled", it.enabled).put("last", it.lastFired)) } }.toString())
    }

    fun add(name: String, event: String, match: String, prompt: String): String {
        val ev = event.lowercase().trim()
        if (ev !in EVENTS) return "Error: event must be one of ${EVENTS.joinToString()}."
        // v0.33.0 Autopilot: a place is "Name@lat,lng[,radius m]"; "here" / "Name@here" = where the phone is now.
        var m = match.trim()
        if (ev.startsWith("place_")) {
            if (m.equals("here", true) || m.endsWith("@here", true)) m = Autopilot.hereMatch(app, m.substringBefore("@").let { if (it.equals("here", true)) name else it }, 150)
                ?: return "Error: no location fix for \"here\". Ask for location permission or the place's coordinates."
            if (Autopilot.Geo.parse(m) == null) return "Error: a place must be Name@lat,lng[,radius] or \"here\". Use get_location or web_search for coordinates."
        }
        val tid = System.currentTimeMillis()
        save(all.value + Trigger(tid, name.ifBlank { prompt.take(30) }, ev, m, prompt))
        if (ev.startsWith("place_")) Autopilot.schedulePlaces(app)
        return "Trigger \"$name\" saved: when $ev${if (match.isNotBlank()) " ($match)" else ""}, I'll: $prompt [ID ${com.blaze.assistant.agent.Processes.idFor("trigger", tid.toString())}]"
    }
    fun rename(id: Long, name: String) = save(all.value.map { if (it.id == id) it.copy(name = name.trim()) else it })
    fun remove(id: Long) = save(all.value.filterNot { it.id == id })
    fun toggle(id: Long) = save(all.value.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })

    // ---------- Events ----------

    fun onNotification(ctx: Context, appName: String, title: String, text: String) {
        if (!::app.isInitialized) return
        val hay = "$appName $title $text"
        all.value.filter { it.enabled && it.event == "notification" }.forEach { t ->
            val words = t.match.split(',', '|').map { it.trim() }.filter { it.isNotEmpty() }
            if (words.isEmpty() || words.any { hay.contains(it, true) }) fire(t, "Notification from $appName — $title: ${text.take(400)}")
        }
        Proactive.onNotification(appName, title, text)
    }

    fun onBattery(level: Int) {
        all.value.filter { it.enabled && it.event == "battery_below" }.forEach { t ->
            val n = t.match.filter { it.isDigit() }.toIntOrNull() ?: 20
            if (level == n || (level < n && level == lastBattery - 1 && level % 5 == 0 && level <= n - 5)) fire(t, "Battery is at $level%.")
        }
        if (level == 15 || level == 5) Proactive.say("Battery is at $level%. Want me to turn on battery saver?", "battery$level", urgent = level == 5)
    }

    fun onApp(pkg: String, label: String) {
        if (!::app.isInitialized) return
        all.value.filter { it.enabled && it.event == "app_opened" && (label.contains(it.match, true) || pkg.contains(it.match, true)) }
            .forEach { fire(it, "The user just opened $label.") }
    }

    val EVENTS = setOf("notification", "battery_below", "charging", "app_opened", "place_arrive", "place_leave", "bluetooth", "wifi")

    /** v0.33.0 bluetooth / wifi: fires triggers whose match is blank or part of [value] (device or network name). */
    fun fireEvent(event: String, value: String, detail: String) {
        if (!::app.isInitialized) return
        all.value.filter { it.enabled && it.event == event && (it.match.isBlank() || value.contains(it.match, true)) }.forEach { fire(it, detail) }
    }
    fun fireOne(t: Trigger, detail: String) = fire(t, detail)

    private fun fire(event: String, match: String, detail: String) =
        all.value.filter { it.enabled && it.event == event && (it.match.isBlank() || it.match.contains(match, true)) }.forEach { fire(it, detail) }

    private fun fire(t: Trigger, detail: String) {
        if (System.currentTimeMillis() - t.lastFired < 60_000) return   // no storms
        save(all.value.map { if (it.id == t.id) it.copy(lastFired = System.currentTimeMillis()) else it })
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Agent.runRoutine("⚡ ${t.name}", "[Trigger fired: $detail]\n${t.prompt}")
        }
    }

    // ---------- Tools ----------

    val names = setOf("create_trigger", "list_triggers", "delete_trigger")
    fun tools(): List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "create_trigger")
            .put("description", "Make Blaze react to events on its own: 'when Mary texts, read it to me', 'when battery drops below 20, turn on saver', 'when I plug in, summarise my day', 'when I open Instagram, remind me of my goal'. For time-based things use create_routine instead.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("name", JSONObject().put("type", "string"))
                .put("event", JSONObject().put("type", "string").put("enum", JSONArray(EVENTS.toList())))
                .put("match", JSONObject().put("type", "string").put("description", "notification: words/app/sender to match (comma-separated, blank = all); battery_below: the % ; charging: 'plugged in' or 'unplugged'; app_opened: app name; place_arrive/place_leave: Name@lat,lng[,radius m] or \"here\"; bluetooth: part of the device name (blank = any); wifi: part of the network name"))
                .put("prompt", JSONObject().put("type", "string").put("description", "What Blaze should do when it fires")))
                .put("required", JSONArray(listOf("name", "event", "prompt"))))),
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "list_triggers").put("description", "List event triggers.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))),
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "delete_trigger").put("description", "Delete a trigger by name.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("name", JSONObject().put("type", "string"))).put("required", JSONArray(listOf("name")))))
    )

    fun run(name: String, a: JSONObject): String = when (name) {
        "create_trigger" -> add(a.optString("name"), a.optString("event"), a.optString("match"), a.optString("prompt"))
        "list_triggers" -> if (all.value.isEmpty()) "No triggers." else all.value.joinToString("\n") { "- ${it.name}: when ${it.event}${if (it.match.isNotBlank()) " (${it.match})" else ""} → ${it.prompt}${if (!it.enabled) " [off]" else ""}" }
        "delete_trigger" -> all.value.firstOrNull { it.name.equals(a.optString("name"), true) }?.let { remove(it.id); "Deleted \"${it.name}\"." } ?: "No trigger called \"${a.optString("name")}\"."
        else -> "Unknown"
    }
}
