package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Photos for brains that can't see. If the model answering this turn isn't a vision model,
 * each picture is first described by one that is (Gemini, then Groq Llama 4, then an
 * OpenRouter free vision model), and the description replaces the picture. The user's own
 * model then answers as if it had seen it.
 */
object Vision {
    private val cache = object : LinkedHashMap<Int, String>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, String>?) = size > 40
    }
    private val sees = Regex("(?i)(gemini|gemma-3|gemma3|gpt-4o|gpt-4\\.1|gpt-5|o4|claude|llama-4|llama4|pixtral|-vl|vision|qwen2\\.5-vl|qwen3-vl|mistral-small-3|mistral-medium|grok-4|kimi-vl|glm-4\\.5v|phi-4-multimodal)")

    fun hasImages(messages: JSONArray): Boolean {
        for (i in 0 until messages.length()) {
            val c = messages.optJSONObject(i)?.opt("content") as? JSONArray ?: continue
            for (j in 0 until c.length()) if (c.optJSONObject(j)?.optString("type") == "image_url") return true
        }
        return false
    }

    fun canSee(model: String) = sees.containsMatchIn(model)

    /** Vision brains you have a key for, best first. */
    private fun seers(): List<LlmClient.Target> = buildList {
        if (Settings.keyFor("gemini").isNotBlank()) add(LlmClient.target("gemini", Settings.modelFor("gemini").takeIf { canSee(it) } ?: Settings.DEFAULT_MODEL))
        if (Settings.keyFor("groq").isNotBlank()) add(LlmClient.target("groq", "meta-llama/llama-4-scout-17b-16e-instruct"))
        if (Settings.keyFor("openrouter").isNotBlank()) add(LlmClient.target("openrouter", "google/gemma-3-27b-it:free"))
        if (Settings.keyFor("mistral").isNotBlank()) add(LlmClient.target("mistral", "pixtral-12b-2409"))
    }

    private const val ASK = "Describe this picture for an assistant that cannot see it. Copy ALL visible text word for word (keep line breaks). " +
        "If it's a screenshot, name the app/screen, buttons, switches and their on/off state, and any error or message. " +
        "Otherwise describe people, objects, colours, numbers, charts and anything notable. Be complete but plain; no opinions."

    /** Replaces every picture with a text description. Never throws: worst case the picture becomes a short note. */
    suspend fun describeAll(messages: JSONArray): JSONArray {
        val out = JSONArray()
        val who = seers()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val c = m.opt("content") as? JSONArray
            if (c == null) { out.put(m); continue }
            val parts = JSONArray(); var n = 0
            for (j in 0 until c.length()) {
                val p = c.getJSONObject(j)
                if (p.optString("type") != "image_url") { parts.put(p); continue }
                n++
                val url = p.optJSONObject("image_url")?.optString("url").orEmpty()
                val d = describe(url, who)
                parts.put(JSONObject().put("type", "text").put("text", "[Picture $n, described for you because your model can't see images]\n$d"))
            }
            out.put(JSONObject(m.toString()).put("content", parts))
        }
        return out
    }

    private suspend fun describe(url: String, who: List<LlmClient.Target>): String {
        val h = url.hashCode()
        synchronized(cache) { cache[h] }?.let { return it }
        if (who.isEmpty()) return "(A picture is attached, but no brain that can see pictures is connected. Tell the user in one line: connect the free Google brain in Settings \u2192 Brain so I can see photos.)"
        for (t in who) {
            try {
                val msg = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", ASK))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))))
                val r = LlmClient.call(t, msg, null, null, null, false, 60_000).optString("content").trim()
                if (r.isNotBlank() && r != "null") { synchronized(cache) { cache[h] = r }; return r }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) { }
        }
        return "(A picture is attached but the picture-reading brains are busy right now. Tell the user in one line and ask them to retry in a minute.)"
    }
}
