package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * Plain-language rules for the everyday commands every assistant should nail, with no model at all:
 * alarms, timers, torch, Wi-Fi/Bluetooth, volume, media keys, opening an app. Works offline.
 * Returns a fast-lane tool call (name + arguments) or null.
 */
object QuickRules {
    /** Drops greetings and politeness that change nothing: "flashlight on, thanks bro" -> "flashlight on". */
    fun polite(s: String): String = s.trim()
        .replace(Regex("[,.!]*\\s*(thanks|thank you|thx|pls|plz|please|bro|mate|boss|man|asap|quickly|real quick)[.!]*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[,.!]*\\s*(thanks|thank you|thx|pls|plz|please|bro|mate|boss|man)[.!]*$", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun clean(s: String) = polite(s).lowercase()
        .replace(Regex("[?!.,]+$"), "").replace(Regex("\\s+"), " ")
        .replace(Regex("^(hey blaze|ok blaze|okay blaze|blaze)[, ]+"), "")
        .replace(Regex("^(please|can you|could you|would you|will you|i want you to|i need you to)[, ]+"), "")
        .replace(Regex("^(please)[, ]+"), "")
        .replace(Regex("( for me| please| now| blaze)+$"), "").trim()

    private val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "forty five" to 45, "sixty" to 60, "a" to 1, "an" to 1, "half an" to 0)

    /** "data" also appears in "data usage", "data balance", "bundle": those aren't a toggle. */
    private fun hits0(t: String) = Regex("\\b(usage|balance|bundle|left|remaining|how much|check)\\b").containsMatchIn(t)

    private fun num(s: String): Int? = s.toIntOrNull() ?: words[s]

    fun parse(text: String): Pair<String, JSONObject>? {
        val t = clean(text)
        if (t.isEmpty() || t.length > 70) return null

        // Life logs (v0.19.0): "start logs", "live logs", "activate life logs", "stop logs", "brainstorm with me".
        lifeLogs(t)?.let { return "life_logs" to JSONObject().put("action", it) }

        // Timer: "set a timer for 5 minutes", "timer 10 min", "5 minute timer", "half an hour timer"
        Regex("""^(?:set |start )?(?:a |an )?(?:timer|countdown)(?: for)? (.+)$|^(.+?) (?:timer|countdown)$""").matchEntire(t)?.let { m ->
            val d = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            duration(d)?.let { s -> return "set_timer" to JSONObject().put("minutes", s / 60).put("seconds", s % 60) }
        }

        // Alarm: "set an alarm for 6:30 am", "wake me up at 7", "alarm 5:45 pm"
        Regex("""^(?:set |make |create )?(?:an |a |my )?(?:alarm|wake ?up)(?: call)?(?: for| at)? (.+)$|^wake me(?: up)?(?: at| by)? (.+)$""").matchEntire(t)?.let { m ->
            val d = m.groupValues[1].ifBlank { m.groupValues[2] }.trim().removeSuffix(" tomorrow").removeSuffix(" tomorrow morning")
            clock(d)?.let { (h, mi) -> return "set_alarm" to JSONObject().put("hour", h).put("minute", mi) }
        }

        // Torch / Wi-Fi / Bluetooth / DND / airplane
        val settings = listOf("flashlight" to "flashlight|torch|flash ?light|light", "wifi" to "wi-?fi|wireless",
            "bluetooth" to "bluetooth", "do_not_disturb" to "do not disturb|dnd", "airplane" to "airplane mode|flight mode|aeroplane mode",
            "mobile_data" to "mobile data|data")
        for ((id, pat) in settings) {
            Regex("""^(?:turn|switch|put) (on|off) (?:the |my )?(?:$pat)$|^(?:turn|switch|put) (?:the |my )?(?:$pat) (on|off)$|^(?:the |my )?(?:$pat) (on|off)$|^(enable|disable|activate|deactivate) (?:the |my )?(?:$pat)$""")
                .matchEntire(t)?.let { m ->
                    val v = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@let
                    val on = v in setOf("on", "enable", "activate")
                    return "toggle_setting" to JSONObject().put("setting", id).put("on", on)
                }
        }

        // Looser form for short commands: exactly one setting word plus an on/off word anywhere,
        // e.g. "can u put the torch on", "kill the wifi", "i need the flashlight", "torch".
        if (t.split(' ').size <= 9 && !Regex("\\b(and|then|if|when|whenever|every|at|after|don't|dont|not|never|settings?|password|connect to|name)\\b").containsMatchIn(t)
            && !Regex("^(is|are|was|what|why|how|does|do|did|which|who|should|where)\\b").containsMatchIn(t)) {
            val hits = settings.filter { (_, pat) -> Regex("\\b(?:$pat)\\b").containsMatchIn(t) }
                .filterNot { it.first == "flashlight" && Regex("\\b(brightness|screen|night light|dark mode)\\b").containsMatchIn(t) }
                .filterNot { it.first == "mobile_data" && hits0(t) }
            if (hits.size == 1) {
                val off = Regex("\\b(off|disable|deactivate|kill|stop|close|shut|no more|cut)\\b").containsMatchIn(t)
                val on = Regex("\\b(on|enable|activate|start|open|need|want|give|light up)\\b").containsMatchIn(t) || t.split(' ').size <= 2
                if (off != on) return "toggle_setting" to JSONObject().put("setting", hits[0].first).put("on", on)
            }
        }

        // Speed readout and the process registry work offline too.
        if (Regex("^(?:show |give me |what are |check )?(?:my |your |the )?(?:speed|latency) ?(?:stats|statistics|report|numbers|test)?$|^how fast (?:are you|is blaze)$").matches(t))
            return "speed_stats" to JSONObject()
        if (Regex("^(?:show |give me |check )?(?:my |your |the )?tool ?(?:call )?(?:stats|statistics|report|errors|health)$").matches(t))
            return "tool_stats" to JSONObject()
        if (Regex("^(?:what'?s|what is|show|list|show me)(?: me)? (?:running|active|tracked|my processes|processes|everything you (?:set|made|created))$|^(?:my )?processes$").matches(t))
            return "list_processes" to JSONObject()

        // Volume
        when {
            Regex("""^(?:turn |put )?(?:the )?(?:volume|sound) up$|^(?:louder|increase (?:the )?volume|raise (?:the )?volume)$""").matches(t) -> return "set_volume" to JSONObject().put("level", "up")
            Regex("""^(?:turn |put )?(?:the )?(?:volume|sound) down$|^(?:quieter|softer|lower (?:the )?volume|decrease (?:the )?volume|reduce (?:the )?volume)$""").matches(t) -> return "set_volume" to JSONObject().put("level", "down")
            Regex("""^(?:mute|silence)(?: the)?(?: phone| volume| sound| my phone)?$|^(?:turn )?(?:the )?(?:volume|sound) off$""").matches(t) -> return "set_volume" to JSONObject().put("level", "mute")
            Regex("""^(?:max|maximum|full) volume$|^(?:turn |put )?(?:the )?volume (?:to )?(?:max|maximum|full)$""").matches(t) -> return "set_volume" to JSONObject().put("level", "max")
        }

        // Media keys
        when (t) {
            "pause", "pause the music", "pause music", "pause it", "stop the music", "stop music" -> return "media_control" to JSONObject().put("action", "pause")
            "resume", "resume music", "resume the music", "play", "continue playing", "unpause" -> return "media_control" to JSONObject().put("action", "play")
            "next", "next song", "next track", "skip", "skip this song", "skip song", "skip track" -> return "media_control" to JSONObject().put("action", "next")
            "previous", "previous song", "previous track", "last song", "go back a song" -> return "media_control" to JSONObject().put("action", "previous")
        }

        // Blaze's own settings: "open your voice settings", "blaze brain settings", "voice settings again".
        Regex("""^(?:(?:please |can you |could you )?(?:open|show|go to|take me to|bring up)?\s*)?(?:the |my )?(your |blaze'?s? )?([a-z &]{2,20}?) (?:settings|setting|page)(?: again| please)?$""").matchEntire(t)?.let { m ->
            val own = m.groupValues[1].isNotBlank()
            val what = m.groupValues[2].trim()
            val blazeOnly = setOf("voice", "wake word", "brain", "ai", "terminal", "persona", "personality", "memory", "routines", "orb", "call", "calls", "speech")
            val page = settingsPageFor(what)
            if (page.isNotEmpty() && (own || what in blazeOnly)) return "blaze_self" to JSONObject().put("action", "open").put("target", page)
        }

        // "wifi settings", "battery settings" → that Android page. "whatsapp settings" (another app's own settings)
        // is multi-step (open the app, find its menu), so the full agent handles it instead of opening Android Settings.
        Regex("""^(?:(?:please |can you |could you )?(?:open|show|go to|take me to|bring up)?(?: me)?\s*)?(?:the |my )?(?:phone'?s? )?([a-z0-9 .&'-]{2,28}?) (?:settings|setting|preferences|options|menu)(?: page| again| please)?$""").matchEntire(t)?.let { m ->
            val what = m.groupValues[1].trim()
            val sys = mapOf("wifi" to "wifi", "wi-fi" to "wifi", "wi fi" to "wifi", "internet" to "wifi", "bluetooth" to "bluetooth", "hotspot" to "hotspot",
                "location" to "location", "gps" to "location", "battery" to "battery", "display" to "display", "screen" to "display", "brightness" to "display",
                "sound" to "sound", "sounds" to "sound", "volume" to "sound", "ringtone" to "sound", "apps" to "apps", "app" to "apps", "storage" to "storage",
                "notification" to "notifications", "notifications" to "notifications", "data" to "data_usage", "data usage" to "data_usage",
                "mobile data" to "data_usage", "mobile network" to "mobile_network", "network" to "mobile_network", "sim" to "mobile_network",
                "language" to "language", "keyboard" to "language", "date and time" to "date_time", "date & time" to "date_time", "time" to "date_time",
                "security" to "security", "lock screen" to "security", "developer" to "developer", "developer options" to "developer", "vpn" to "vpn",
                "nfc" to "nfc", "accessibility" to "accessibility", "default apps" to "default_apps", "airplane" to "airplane", "dnd" to "dnd", "do not disturb" to "dnd")
            sys[what]?.let { return "open_settings_page" to JSONObject().put("page", it) }
            val verb = Regex("^(open|show|show me|go to|take me to|bring up|the|my|open the|open my)$").matches(what)
            if (!verb && what !in setOf("phone", "android", "system", "device")) return null
        }

        // Open an app: "open whatsapp", "launch the camera", "go to settings". Single target only.
        Regex("""^(?:open|launch|start|go to|take me to|bring up|show me|run) (?:the |my |up )?([a-z0-9][a-z0-9 .+&'-]{1,28}?)(?: app)?$""").matchEntire(t)?.let { m ->
            val app = m.groupValues[1].trim()
            if (com.blaze.assistant.phone.AppMatch.clean(app).isNotEmpty() && app.split(' ').size <= 3 && !Regex("\\b(and|then|search|play|on|in|with|to|for|about)\\b").containsMatchIn(app)
                && !Regex(".+ (settings?|preferences|options|menu|chats?|status|profile|page)$").matches(app))
                return "open_app" to JSONObject().put("name", app)
        }
        return null
    }

    /** "5 minutes", "1 hour 30 minutes", "90 seconds", "half an hour", "an hour and a half". */
    /** The life_logs action a short spoken or typed command asks for, or null. */
    fun lifeLogs(t0: String): String? {
        val t = t0.lowercase().trim().replace(Regex("[?!.,]+$"), "")
        val logs = "(?:the |my )?(?:live |life )?(?:logs?|logging)"
        return when {
            Regex("^(?:start|begin|activate|turn on|switch on|enable|resume|go) $logs$|^(?:live|life) logs?(?: on)?$|^$logs on$|^start (?:live |life )?logging$").matches(t) ->
                if (t.startsWith("resume")) "resume" else "start"
            Regex("^(?:stop|end|deactivate|turn off|switch off|disable|kill) $logs$|^$logs off$").matches(t) -> "stop"
            Regex("^(?:pause|hold) $logs$").matches(t) -> "pause"
            Regex("^(?:is|are) $logs (?:on|running)$|^$logs status$").matches(t) -> "status"
            Regex("^(?:let'?s |lets |i want to |can we )?brainstorm(?: with me| together)?$|^start (?:a )?brainstorm(?:ing)?(?: session)?$").matches(t) -> "brainstorm"
            Regex("^(?:stop|end|finish) (?:the )?brainstorm(?:ing)?(?: session)?$").matches(t) -> "stop"
            else -> null
        }
    }

    fun duration(d: String): Int? {
        val s = d.trim().removePrefix("for ")
        if (Regex("^half an? hour$").matches(s)) return 1800
        if (Regex("^an? hour and a half$|^one and a half hours?$|^1\\.5 hours?$").matches(s)) return 5400
        var total = 0; var any = false
        Regex("""(\d+(?:\.\d+)?|[a-z]+(?: [a-z]+)?) ?(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""").findAll(s).forEach { m ->
            val raw = m.groupValues[1]
            val n = raw.toDoubleOrNull() ?: num(raw)?.toDouble() ?: num(raw.substringAfterLast(' '))?.toDouble() ?: return@forEach
            val u = m.groupValues[2]
            total += when { u.startsWith("h") -> (n * 3600).toInt(); u.startsWith("m") -> (n * 60).toInt(); else -> n.toInt() }
            any = true
        }
        return if (any && total in 1..86_400) total else null
    }

    /** "6", "6:30", "6.30", "6 30 am", "18:45", "7 pm", "noon", "midnight", "half past 6". */
    fun clock(d: String): Pair<Int, Int>? {
        val s = d.trim().replace("a.m.", "am").replace("p.m.", "pm").replace(" o'clock", "").replace(" oclock", "")
        if (s == "noon" || s == "midday") return 12 to 0
        if (s == "midnight") return 0 to 0
        Regex("""^half past (\d{1,2})(?: ?(am|pm))?(?: in the (morning|evening|afternoon|night))?$""").matchEntire(s)?.let { m ->
            return fix(m.groupValues[1].toInt(), 30, m.groupValues[2], m.groupValues[3]) }
        Regex("""^(\d{1,2})(?:[:. ](\d{2}))? ?(am|pm)?(?: in the (morning|evening|afternoon|night)| tonight)?$""").matchEntire(s)?.let { m ->
            val pm = m.groupValues[3].ifBlank { if (s.endsWith("tonight")) "pm" else "" }
            return fix(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, pm, m.groupValues[4]) }
        return null
    }

    private fun fix(h0: Int, m: Int, ampm: String, part: String): Pair<Int, Int>? {
        var h = h0
        if (h > 23 || m > 59) return null
        val pm = ampm == "pm" || part in setOf("evening", "afternoon", "night")
        val am = ampm == "am" || part == "morning"
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        return h to m
    }
}
