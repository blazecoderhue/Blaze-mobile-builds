package com.blaze.assistant.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Exact-command cache for the fast lane. "open whatsapp" that worked once maps straight to
 * open_app(WhatsApp) next time: no model, no network, a few milliseconds.
 */
object IntentCache {
    private lateinit var file: File
    private val map = LinkedHashMap<String, Pair<String, JSONObject>>(64, 0.75f, true)
    private const val MAX = 300

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "intent_cache.json")
        try {
            val o = JSONObject(file.readText())
            for (k in o.keys()) o.getJSONObject(k).let { map[k] = it.getString("t") to it.getJSONObject("a") }
        } catch (_: Exception) { }
    }

    private val filler = Regex("^(hey |ok |okay )?(blaze[, ]+)?(please |can you |could you )?|( please| now| for me)$")
    fun norm(text: String) = text.lowercase().replace(Regex("[^a-z0-9 :%]"), " ").replace(Regex("\\s+"), " ").trim()
        .replace(filler, "").trim()

    @Synchronized fun get(text: String): Pair<String, JSONObject>? = if (!::file.isInitialized) null else map[norm(text)]
    @Synchronized fun put(text: String, tool: String, args: JSONObject) {
        if (!::file.isInitialized) return
        val k = norm(text); if (k.length < 3) return
        map[k] = tool to args
        while (map.size > MAX) map.remove(map.keys.first())
        save()
    }
    @Synchronized fun drop(text: String) { if (map.remove(norm(text)) != null) save() }
    @Synchronized fun clear() { map.clear(); save() }
    fun size() = map.size
    fun entries(): List<Pair<String, String>> = synchronized(this) { map.entries.reversed().map { it.key to it.value.first } }

    private fun save() {
        val o = JSONObject(); map.forEach { (k, v) -> o.put(k, JSONObject().put("t", v.first).put("a", v.second)) }
        try { file.writeText(o.toString()) } catch (_: Exception) { }
    }
}
