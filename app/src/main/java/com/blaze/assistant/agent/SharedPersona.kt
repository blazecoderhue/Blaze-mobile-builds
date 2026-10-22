package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * v0.45.5 (#10): the persona every Blaze app shares (Site: https://blazeinchue.pages.dev/persona.json, v1.0.0).
 * Its `system` text is the FIRST system message, byte-identical every turn (provider prompt caches hit, TTFT drops);
 * the app's own instructions (tools, phone, the user's first name, local time in natural words) follow in a SECOND
 * system message. Fetched on start, refetched at most once a day, cached; the bundled v1 (assets/persona-v1.json) is the
 * offline fallback. Used for the default Blaze personality; a personality the user picked keeps its own words in the
 * second message. Pure parts (parse, due, pick) in this object; SharedPersonaTest runs the persona's evals as
 * regressions on the request shape.
 */
object SharedPersona {
    const val URL = "https://blazeinchue.pages.dev/persona.json"
    const val ASSET = "persona-v1.json"
    const val PREF = "persona_json"
    const val PREF_AT = "persona_at"
    const val DAY_MS = 86_400_000L
    /** gpt-oss and other reasoning models need room to reason and still answer (persona contract). */
    const val MIN_MAX_TOKENS = 400

    data class Persona(val version: String, val system: String, val evals: List<String>)

    fun parse(json: String?): Persona? = try {
        val o = JSONObject(json ?: "")
        val sys = o.optString("system")
        if (sys.isBlank() || sys.length > 8000) null
        else Persona(o.optString("version").ifBlank { "1" }, sys,
            o.optJSONArray("evals")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") } } ?: emptyList())
    } catch (_: Exception) { null }

    /** A refetch is due when the last good fetch is a day old (or never happened). */
    fun due(lastAt: Long, now: Long): Boolean = lastAt <= 0L || now - lastAt >= DAY_MS || now < lastAt

    /**
     * The newer of the cached copy and the bundled one (v0.45.9: a phone that cached 1.0.0 the day before updating to an
     * app that bundles 1.0.1 sends 1.0.1 at once, so the gateway never has to add the assistant rules itself).
     */
    fun pick(cached: String?, bundled: String?): Persona? {
        val c = parse(cached); val b = parse(bundled)
        if (c == null) return b
        if (b == null) return c
        return if (VersionCmp.compare(b.version, c.version) > 0) b else c
    }

    /** Dotted-number compare ("1.0.10" > "1.0.9"); non-numbers count as 0. */
    object VersionCmp {
        fun compare(a: String, b: String): Int {
            val x = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val y = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) { val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 }); if (d != 0) return d }
            return 0
        }
    }

    /** True when the shared persona leads the prompt: the default Blaze personality (a picked one keeps its own words). */
    fun applies(personaId: String): Boolean = personaId == "blaze"

    /** The second system message's opening: the user's first name and the local time in natural words. */
    fun contextLine(firstName: String?, natural: String): String = buildString {
        append("App context for Blaze on the user's Android phone. ")
        if (!firstName.isNullOrBlank()) append("User's first name: ").append(firstName.trim().substringBefore(' ')).append(". ")
        if (natural.isNotBlank()) append("Local time: ").append(natural).append(". ")
    }

    /** "6:51 PM on Tuesday, 20 October" in the phone's own words; never a zone id. */
    fun natural(now: java.util.Date = java.util.Date(), locale: java.util.Locale = java.util.Locale.getDefault()): String =
        java.text.SimpleDateFormat("h:mm a 'on' EEEE, d MMMM yyyy", locale).format(now)

    // ---------------- state ----------------

    @Volatile private var current: Persona? = null
    @Volatile private var bundled: String? = null
    @Volatile private var fetching = false

    /** The persona to send now (cached, else bundled), or null when neither is there. */
    fun get(): Persona? = current ?: try { pick(Settings.str(PREF), bundled).also { current = it } } catch (_: Throwable) { parse(bundled) }

    /** [messages] with the persona as its first system message (nothing changes when there is none). */
    fun prepend(messages: org.json.JSONArray): org.json.JSONArray {
        val p = get() ?: return messages
        val out = org.json.JSONArray().put(JSONObject().put("role", "system").put("content", p.system))
        for (i in 0 until messages.length()) out.put(messages.get(i))
        return out
    }

    /** On start: read the bundled copy, then refresh in the background when a day has passed. */
    fun init(ctx: android.content.Context) {
        if (bundled == null) bundled = try { ctx.assets.open(ASSET).bufferedReader().use { it.readText() } } catch (_: Throwable) { null }
        refreshIfDue()
    }

    fun refreshIfDue(now: Long = System.currentTimeMillis()) {
        val at = try { Settings.str(PREF_AT).toLongOrNull() ?: 0L } catch (_: Throwable) { return }
        if (!due(at, now) || fetching) return
        fetching = true
        Thread {
            try {
                val c = com.blaze.assistant.connect.UrlSafety.open(URL)
                c.connectTimeout = 8000; c.readTimeout = 8000; c.useCaches = false
                c.setRequestProperty("Cache-Control", "no-cache")
                val body = if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
                val p = parse(body)
                if (p != null && body != null) { Settings.setStr(PREF, body); Settings.setStr(PREF_AT, System.currentTimeMillis().toString()); current = p }
            } catch (_: Throwable) { } finally { fetching = false }
        }.start()
    }
}
