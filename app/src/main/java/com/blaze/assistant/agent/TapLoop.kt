package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.30.7 faster phone actions: the pure rules behind cutting a phone task from one model round trip per tap
 * (v0.30.4 trace: 18 steps, 4 min 25 s, ≈ 7 s a step) to a plan the phone runs itself. Pure (TapLoopTest).
 *
 *  - [failed]: whether a screen tool's result means the step did not happen (for the plan recorder).
 *  - [wantOf] / [needsTap]: `want:"on"|"off"` on a tap: Blaze reads the switch and only taps when it isn't already
 *    that way, so a replayed or batched plan can never flip a setting back.
 *  - [settleAfter] / [waitMs]: run_plan waits for the NEXT step's target to show (LabelMatch on the new screen)
 *    instead of a fixed settle cap; apps that never go quiet (animations, video) no longer cost 1.2 s a step.
 *  - [compactNow]: no blocking summarise call in the middle of a phone task (a context error still folds).
 *  - [bareRow]: unlabelled clickable rows ("[12] item") are left out of screen readouts; their labelled children are
 *    listed and a tap on a child climbs to the clickable row anyway.
 *  - [Recorder] / [Plans]: the steps of a phone task that worked are kept per request ("plan memory"); the same
 *    request next time gets them as one run_plan to send at once (2 model calls instead of a call per tap).
 */
object TapLoop {

    // ---------------- results ----------------

    private val failStarts = listOf("Error", "Couldn't", "No element", "No app", "No installed app", "Unknown", "Not done yet",
        "Phone control", "The target", "Stopped", "Give an element", "That page needs")

    /** True when a screen tool's result says the step did not happen. */
    fun failed(result: String): Boolean {
        val r = result.trimStart()
        return r.isEmpty() || failStarts.any { r.startsWith(it) } || r.contains("did NOT approve") || r.contains("went away while")
    }

    // ---------------- switches: tap only when needed ----------------

    /** `want` on a tap step: true = on, false = off, null = not a switch step (or unreadable). */
    fun wantOf(want: String?): Boolean? = when (want?.trim()?.lowercase()) {
        "on", "true", "yes", "enable", "enabled", "1", "checked" -> true
        "off", "false", "no", "disable", "disabled", "0", "unchecked" -> false
        else -> null
    }

    /** Tap unless the switch is known to be in the wanted state already. Unknown state (not a switch) always taps. */
    fun needsTap(want: Boolean?, current: Boolean?): Boolean = want == null || current == null || current != want

    fun wantWord(state: Boolean): String = if (state) "on" else "off"

    // ---------------- run_plan settle ----------------

    private val targeted = setOf("tap", "long_press", "type")

    /**
     * Settle after a step (max, quiet ms). When the next step looks for a target by its words, a short settle is
     * enough: the next step's own wait polls the new screen for that target (on-device verification). Otherwise
     * the old caps (open_app 1.5 s, others 1.2 s).
     */
    fun settleAfter(doNow: String, next: JSONObject?): Pair<Long, Long> {
        val nextFinds = next != null && next.optString("do") in targeted && next.optString("target").isNotBlank()
        return when {
            doNow == "wait" -> 0L to 0L
            doNow == "open_app" || doNow == "open_settings" -> if (nextFinds) 600L to 150L else 1500L to 250L
            nextFinds -> 450L to 120L
            else -> 1200L to 180L
        }
    }

    /** How long a tap / type step waits for its target before scrolling for it; longer after a short settle. */
    fun waitMs(prevShortSettle: Boolean, field: Boolean = false): Long = when {
        prevShortSettle -> if (field) 2200L else 2000L
        field -> 1500L
        else -> 1200L
    }

    // ---------------- no mid-task compaction ----------------

    /** Compact before this model call? Only before the first one, or when the turn hasn't touched the screen. */
    fun compactNow(step: Int, screenActed: Boolean): Boolean = step == 0 || !screenActed

    // ---------------- compact screen readouts ----------------

    private val bare = Regex("^\\[\\d+] item( disabled)?$")

    /** A readout line with nothing in it: an unlabelled clickable row whose labelled children are listed after it. */
    fun bareRow(line: String): Boolean = bare.matches(line.trim())

    // ---------------- plan memory ----------------

    /** Steps from tools: what to put in a run_plan for a screen tool that just worked, or null when it can't be replayed. */
    fun stepOf(tool: String, args: JSONObject, label: String, toggled: Boolean?): JSONObject? = when (tool) {
        "open_app" -> args.optString("app_name").ifBlank { args.optString("app") }.takeIf { it.isNotBlank() }?.let { JSONObject().put("do", "open_app").put("app", it) }
        "open_settings" -> args.optString("page").takeIf { it.isNotBlank() }?.let { p ->
            JSONObject().put("do", "open_settings").put("page", p).apply { args.optString("app").takeIf { it.isNotBlank() }?.let { put("app", it) } } }
        "tap" -> args.optString("text").ifBlank { label }.trim().takeIf { it.isNotBlank() }?.let { t ->
            JSONObject().put("do", if (args.optBoolean("long_press")) "long_press" else "tap").put("target", t.take(80)).apply {
                val w = wantOf(args.optString("want")) ?: toggled
                if (w != null) put("want", wantWord(w))
            } }
        "type_text" -> JSONObject().put("do", "type").put("text", args.optString("text")).apply {
            if (label.isNotBlank() && label != args.optString("text")) put("target", label.take(80))
            if (args.optBoolean("submit")) put("submit", true) }
        "scroll" -> JSONObject().put("do", "scroll").put("direction", args.optString("direction").ifBlank { "down" })
        "press_button" -> when (args.optString("button").lowercase()) {
            "back" -> JSONObject().put("do", "back"); "home" -> JSONObject().put("do", "home"); "enter" -> JSONObject().put("do", "enter")
            else -> null
        }
        "search_in_app" -> JSONObject().put("do", "search_in_app").put("app", args.optString("app")).put("query", args.optString("query"))
            .apply { if (args.optBoolean("play")) put("play", true) }
        else -> null
    }

    /** Tools that only look (no step to record, nothing to break the recipe). */
    val lookOnly = setOf("read_screen", "look_at_screen", "wait")

    /** Same words as the risky-tap confirmation: a plan with one of these is never kept (it sends, pays or deletes). */
    private val risky = Regex("\\b(send|pay|buy|purchase|order|checkout|check out|delete|remove|post|publish|transfer|confirm|subscribe|place order|book|uninstall|force stop|clear)\\b", RegexOption.IGNORE_CASE)

    /**
     * The phone steps of one turn, in order. A step that failed makes the recipe "not ok" until a later step works;
     * a step that can't be replayed (a raw button, an unlabelled tap) spoils the recipe for this turn.
     */
    class Recorder {
        val steps = ArrayList<JSONObject>()
        var lastOk = true; private set
        var spoiled = false; private set
        fun reset() { steps.clear(); lastOk = true; spoiled = false }
        fun ok(step: JSONObject?) {
            if (step == null) { spoiled = true; return }
            if (steps.size >= MAX_STEPS) spoiled = true else steps += JSONObject(step.toString())
            lastOk = true
        }
        fun fail() { lastOk = false }
        /** The recipe to keep, or null (nothing, failed at the end, unreplayable or risky). */
        fun recipe(): List<JSONObject>? {
            if (steps.isEmpty() || !lastOk || spoiled) return null
            if (steps.any { s -> listOf("target", "text", "query").any { risky.containsMatchIn(s.optString(it)) } }) return null
            return steps.toList()
        }
    }

    const val MAX_STEPS = 15

    private val stop = setOf("the", "a", "an", "to", "my", "me", "for", "please", "pls", "can", "you", "could", "would", "will", "in", "of", "it",
        "its", "app", "blaze", "hey", "ok", "okay", "just", "now", "and", "from", "this", "that", "phone", "i", "want", "is", "be")
    /** Words that flip what a request means: two requests only match when these are the same. */
    private val polarity = setOf("on", "off", "enable", "disable", "allow", "block", "stop", "start", "mute", "unmute", "show", "hide",
        "open", "close", "turn", "dont", "not", "never", "always", "up", "down", "increase", "decrease", "lower", "raise", "connect", "disconnect",
        "grant", "revoke", "let", "prevent", "keep", "clear", "remove", "add")

    fun words(text: String): List<String> = text.lowercase().replace("'", "").replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
        .split(Regex("\\s+")).filter { it.isNotEmpty() && it !in stop }.distinct()

    /** The request's key: its content words, sorted (word order doesn't change the task). */
    fun key(text: String): String = words(text).sorted().joinToString(" ")

    /** Same task? Same words, or (3+ words) same flip-words, same numbers and at most one other word different. */
    fun sameTask(a: String, b: String): Boolean {
        val x = a.split(' ').filter { it.isNotEmpty() }.toSet(); val y = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        if (x.size < 3 || y.size < 3) return false
        if (x.filter { it in polarity }.toSet() != y.filter { it in polarity }.toSet()) return false
        if (x.filter { it.any(Char::isDigit) }.toSet() != y.filter { it.any(Char::isDigit) }.toSet()) return false
        val inter = x.intersect(y).size
        return x.size + y.size - 2 * inter <= 1 && inter * 5 >= maxOf(x.size, y.size) * 4
    }

    data class Recipe(val key: String, val steps: JSONArray, val uses: Int, val at: Long)

    /** The hint sent with the first model call when this request worked before. */
    fun hint(r: Recipe): String =
        "[Done before] This same request worked last time with these steps. Send them now as ONE run_plan call " +
            "(it checks each step on the phone, and a tap with want skips a switch that's already that way): " +
            JSONObject().put("steps", r.steps).toString() +
            "\nIf the run stops, fix only the failed step. Don't read the screen first."

    /** Saved recipes, newest last; at most [MAX] kept (filesDir/plan_memory.json on the phone). */
    object Plans {
        const val MAX = 120
        private var file: File? = null
        private val map = LinkedHashMap<String, Recipe>()

        @Synchronized fun init(f: File) {
            file = f; map.clear()
            try {
                val o = JSONObject(f.readText())
                for (k in o.keys()) o.optJSONObject(k)?.let { r -> r.optJSONArray("steps")?.let { s -> map[k] = Recipe(k, s, r.optInt("uses"), r.optLong("at")) } }
            } catch (_: Exception) { }
        }

        @Synchronized fun find(text: String): Recipe? {
            val k = key(text); if (k.isEmpty()) return null
            return map[k] ?: map.values.lastOrNull { sameTask(it.key, k) }
        }

        @Synchronized fun put(text: String, steps: List<JSONObject>, now: Long = System.currentTimeMillis()) {
            val k = key(text); if (k.isEmpty() || steps.isEmpty()) return
            val old = map.remove(k)
            map[k] = Recipe(k, JSONArray().apply { steps.forEach { put(it) } }, (old?.uses ?: 0) + 1, now)
            while (map.size > MAX) map.remove(map.keys.first())
            save()
        }

        @Synchronized fun drop(text: String) { if (map.remove(key(text)) != null) save() }
        /** v0.45.0 reactions: removes and returns the plan for [text] (👎), so taking the 👎 back can [restore] it. */
        @Synchronized fun take(text: String): Recipe? = map.remove(key(text))?.also { save() }
        @Synchronized fun restore(r: Recipe) { if (r.key.isEmpty() || map.containsKey(r.key)) return; map[r.key] = r; while (map.size > MAX) map.remove(map.keys.first()); save() }
        /** v0.45.0 reactions: 👍 counts as another good use of the plan for [text] ([by] = -1 takes it back). */
        @Synchronized fun reinforce(text: String, by: Int = 1): Boolean {
            val k = key(text); val r = map[k] ?: return false
            map[k] = r.copy(uses = maxOf(0, r.uses + by)); save(); return true
        }
        @Synchronized fun get(text: String): Recipe? = map[key(text)]
        @Synchronized fun clear() { map.clear(); save() }
        @Synchronized fun size(): Int = map.size

        private fun save() {
            val f = file ?: return
            val o = JSONObject(); map.forEach { (k, r) -> o.put(k, JSONObject().put("steps", r.steps).put("uses", r.uses).put("at", r.at)) }
            try { f.writeText(o.toString()) } catch (_: Exception) { }
        }
    }
}
