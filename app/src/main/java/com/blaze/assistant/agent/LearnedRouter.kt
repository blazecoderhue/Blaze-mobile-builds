package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * v0.33.1 learned tool router (idea #16). [ToolRouter.guess] picks tool groups by fixed word lists; when a request's
 * words miss them, the model has to spend a whole round trip on find_tool / load_tools first. This learns, on the phone
 * and from your own turns only, which words go with which groups ("timetable" → files, "momo" → messages…), and
 * preloads a group when the evidence is strong. It only ever ADDS groups to the regex guess (nothing becomes
 * unreachable) and adds at most [MAX_ADD] per turn, so a wrong guess costs a few schemas, never a capability.
 *
 * Model: per word, how many turns it appeared in and in how many of those each group was used. A word predicts a group
 * when it was seen in at least [MIN_SEEN] turns and the group was used in at least [MIN_P] of them (Laplace-smoothed).
 * Pure (LearnedRouterTest); persisted as JSON by the caller.
 */
class LearnedRouter(private val maxWords: Int = 3000) {
    companion object {
        const val MIN_SEEN = 3
        const val MIN_P = 0.6
        const val MAX_ADD = 2
        private val stop = setOf("the", "and", "for", "you", "your", "with", "that", "this", "please", "can", "could", "would", "will",
            "what", "how", "when", "where", "who", "why", "are", "was", "were", "have", "has", "had", "not", "but", "from", "about",
            "into", "out", "just", "now", "then", "them", "they", "there", "here", "its", "it's", "my", "me", "him", "her", "our",
            "also", "some", "any", "all", "get", "got", "make", "let", "lets", "want", "need", "like", "know", "tell", "show", "give",
            "blaze", "halo", "hey", "okay", "yes", "no", "thanks", "thank", "too", "very", "really", "one", "two", "time", "do", "did", "done")

        /** Content words of a request: lower-case, ≥ 3 letters, stop words out, a light stem (plurals, -ing, -ed). */
        fun tokens(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }
            .filter { it.length >= 3 && it !in stop && !it.all(Char::isDigit) }
            .map { stem(it) }.toSet()

        fun stem(w: String): String = when {
            w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
            w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
            w.length > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("zes")) -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }
    }

    private class Word(var seen: Int = 0, var last: Long = 0, val groups: MutableMap<String, Int> = HashMap())
    private val words = HashMap<String, Word>()
    var turns = 0; private set

    /** One finished turn: its words and the tool groups it actually used (may be empty: that's evidence too). */
    @Synchronized fun learn(text: String, used: Set<String>, now: Long = System.currentTimeMillis()) {
        val toks = tokens(text)
        if (toks.isEmpty()) return
        turns++
        for (t in toks) {
            val w = words.getOrPut(t) { Word() }
            w.seen++; w.last = now
            for (g in used) w.groups[g] = (w.groups[g] ?: 0) + 1
        }
        if (words.size > maxWords) {
            // Forget the rarest, oldest words first.
            words.entries.sortedWith(compareBy({ it.value.seen }, { it.value.last })).take(words.size - maxWords).map { it.key }.forEach { words.remove(it) }
        }
    }

    /**
     * v0.45.0 reactions: takes back one [learn] of [text] with [used] (a reply the user gave 👎), so a bad turn doesn't
     * keep steering which tools load. Counts never go below zero; a word nothing is left of is forgotten.
     */
    @Synchronized fun unlearn(text: String, used: Set<String>) {
        val toks = tokens(text)
        if (toks.isEmpty()) return
        if (turns > 0) turns--
        for (t in toks) {
            val w = words[t] ?: continue
            if (w.seen > 0) w.seen--
            for (g in used) { val c = (w.groups[g] ?: 0) - 1; if (c > 0) w.groups[g] = c else w.groups.remove(g) }
            if (w.seen == 0) words.remove(t)
        }
    }

    /** Groups to preload for [text], strongest first, at most [MAX_ADD], minus what [already] has. */
    @Synchronized fun predict(text: String, already: Set<String> = emptySet()): List<String> {
        val score = HashMap<String, Double>()
        for (t in tokens(text)) {
            val w = words[t] ?: continue
            if (w.seen < MIN_SEEN) continue
            for ((g, c) in w.groups) {
                val p = c / (w.seen + 1.0)
                if (p >= MIN_P) score[g] = maxOf(score[g] ?: 0.0, p)
            }
        }
        return score.entries.filter { it.key !in already }.sortedByDescending { it.value }.take(MAX_ADD).map { it.key }
    }

    @Synchronized fun size() = words.size

    @Synchronized fun toJson(): JSONObject = JSONObject().put("turns", turns).put("w", JSONObject().apply {
        words.forEach { (k, w) -> put(k, JSONObject().put("s", w.seen).put("l", w.last).put("g", JSONObject(w.groups as Map<*, *>))) }
    })

    @Synchronized fun load(o: JSONObject) {
        words.clear(); turns = o.optInt("turns")
        val w = o.optJSONObject("w") ?: return
        for (k in w.keys()) {
            val e = w.optJSONObject(k) ?: continue
            val g = e.optJSONObject("g") ?: JSONObject()
            words[k] = Word(e.optInt("s"), e.optLong("l"), g.keys().asSequence().associateWith { g.optInt(it) }.toMutableMap())
        }
    }

    @Synchronized fun clear() { words.clear(); turns = 0 }
}

/** The phone's one learned router, stored in Settings ("learned_router"). */
object LearnedRoutes {
    private val model = LearnedRouter()
    @Volatile private var loaded = false
    val enabled get() = try { Settings.bool("learned_router", true) } catch (_: Throwable) { true }

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try { model.load(JSONObject(Settings.str("learned_router_v1", "{}"))) } catch (_: Exception) { }
            loaded = true
        }
    }

    fun predict(text: String, already: Set<String>): List<String> = if (!enabled) emptyList() else try { ensure(); model.predict(text, already) } catch (_: Exception) { emptyList() }

    fun learn(text: String, used: Set<String>) {
        if (!enabled) return
        try { ensure(); model.learn(text, used); Settings.setStr("learned_router_v1", model.toJson().toString()) } catch (_: Exception) { }
    }

    fun unlearn(text: String, used: Set<String>) {
        try { ensure(); model.unlearn(text, used); Settings.setStr("learned_router_v1", model.toJson().toString()) } catch (_: Exception) { }
    }

    fun stats(): String = try { ensure(); "Learned tool router: ${model.size()} words from ${model.turns} turns" + (if (!enabled) " (off)" else "") } catch (_: Exception) { "Learned tool router: not loaded" }

    fun clear() { model.clear(); try { Settings.setStr("learned_router_v1", "{}") } catch (_: Exception) { } }
}
