package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.44.0 fewer tools per turn. ToolRouter already sends the groups a request needs, but its always-on core had grown
 * to ~50 tools (background agents, long-chat lookups, Blaze's own docs and settings, skills, projects…), so a plain
 * "open WhatsApp" still carried every one of their schemas. Smaller models pick worse from a long list, and every
 * schema is input tokens on every step.
 *
 * Here the core is split: [ESSENTIAL] always goes; the rest is in [CUES], each sent only when the request's words
 * point at it, a tool from it was used in this chat, or the model loaded it (load_tools / find_tool, which always stay).
 * Then the turn is capped at [MAX_TOOLS]: tools the request names or this chat used come first, the rest of a guessed
 * group fills what's left. Nothing becomes unreachable: find_tool searches every tool and loads its cue or group.
 *
 * Pure Kotlin; ToolPickTest. Prompt size before/after is logged per turn (BlazeSpeed / speed log: "tools a/b, c→d chars").
 */
object ToolPick {
    /** Upper bound of tool schemas per model call (load_tools / find_tool always on top). */
    const val MAX_TOOLS = 36

    /** Always sent, whatever the request. */
    val ESSENTIAL = setOf("load_tools", "find_tool", "ask_user", "memory_search", "memory_save", "remember", "web_search", "fetch_page",
        "open_app", "run_plan", "read_screen", "search_in_app", "device_status", "blaze_now", "call_control", "open_view", "get_weather", "save_file")

    data class Cue(val id: String, val about: String, val names: Set<String>, val match: Regex)

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    val CUES = listOf(
        Cue("agents", "background agents: start, check, steer or stop long work",
            setOf("start_agent", "list_agents", "check_agent", "message_agent", "cancel_agent"),
            r("""\b(agents?|background|in parallel|meanwhile|while (?:you|i)|long (?:task|job|research)|hand (?:it )?off|keep (?:working|going)|research)\b""")),
        Cue("history", "this chat's older turns and files, past chats, the project's brief and notes",
            setOf("search_chat", "read_turn", "read_artifact", "search_past_chats", "update_brief", "project_docs_search", "project_docs_read", "recall_memory"),
            r("""\b(earlier|before|last time|you said|i said|we (?:talked|discussed|said|did)|previous\w*|that (?:file|doc|table|list|code|report)|above|chats?|conversation|project|brief|notes|what did|remind me what|recall|again)\b""")),
        Cue("self", "Blaze itself: its docs, settings and self-test, BlazeRouter, Jarvis, follow-ups, running processes (alarms, routines, jobs by ID)",
            setOf("about_blaze", "blaze_self", "self_test", "blaze_docs_search", "blaze_docs_read", "blazerouter", "jarvis_mode", "follow_ups", "processes", "process"),
            r("""\b(blaze|yourself|your (?:settings|docs|repo|version|features?)|update|what can you|can you|self.?test|router|jarvis|follow.?ups?|reach out|process(?:es)?|running|stop|cancel|delete|alarms?|timers?|routines?|jobs?|r\d+|version|halo)\b""")),
        Cue("skills", "skills, goals and saved workflows",
            setOf("use_skill", "find_skill", "run_skill", "goal"),
            r("""\b(skills?|workflows?|goals?|plan|step by step|checklist|recipe|how do i|guide)\b""")),
        Cue("make", "designs and views, apps list, the camera, forgetting and editing memories",
            setOf("design", "list_apps", "blaze_camera", "forget", "memory_update", "memory_delete"),
            r("""\b(design|3d|mock-?up|page|website|landing|poster|diagram|apps?|installed|camera|photo|picture|scan|forget|wrong|update|change|correct|clean|duplicates?|delete|remove)\b""")),
    )

    private val cueOf: Map<String, String> = CUES.flatMap { c -> c.names.map { it to c.id } }.toMap()

    fun cueOf(name: String): String? = cueOf[name]

    private fun nameOf(t: Any?): String = (t as? JSONObject)?.optJSONObject("function")?.optString("name").orEmpty()

    /** Every tool name this chat has already called (so follow-ups keep their tools). */
    fun usedNames(history: JSONArray): Set<String> {
        val out = HashSet<String>()
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optString("role") == "tool") m.optString("name").takeIf { it.isNotBlank() }?.let { out += it }
            val calls = m.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) nameOf(calls.opt(j)).takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out
    }

    /** v0.45.0: tool calls in [reply] whose names weren't in the [sent] tool list (a real tool outside the subset, or none at all). */
    fun missing(reply: JSONObject, sent: JSONArray): Set<String> {
        val calls = reply.optJSONArray("tool_calls") ?: return emptySet()
        val have = HashSet<String>().apply { for (i in 0 until sent.length()) add(nameOf(sent.opt(i))) }
        if (have.isEmpty()) return emptySet()
        return (0 until calls.length()).map { nameOf(calls.opt(it)) }.filter { it.isNotBlank() && it !in have }.toSet()
    }

    /** The cues this request turns on. */
    fun cues(userText: String, used: Set<String>, groupsOn: Set<String>): Set<String> =
        CUES.filter { c -> c.id in groupsOn || c.match.containsMatchIn(userText) || c.names.any { it in used } }.map { it.id }.toSet()

    /** Cues of the tool names mentioned in [text] (find_tool's answer), so a found core tool is sent next step. */
    fun cuesIn(text: String): Set<String> = cueOf.filterKeys { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(text) }.values.toSet()

    private val word = Regex("[a-z0-9]{3,}")
    private val stop = setOf("the", "and", "for", "you", "your", "with", "this", "that", "what", "can", "please", "from", "into", "about", "have", "are")

    /**
     * The tools to send: [selected] (ToolRouter's pick) minus cue tools that aren't on, capped at [max].
     * [keepAll] = a routine / specialist run (it gets everything it was given).
     */
    fun pick(selected: JSONArray, userText: String, used: Set<String>, groupsOn: Set<String>, keepAll: Boolean = false, max: Int = MAX_TOOLS,
             /** v0.45.2: tools this turn must keep past the cap (the desktop agent on a desktop request). */
             must: Set<String> = emptySet()): JSONArray {
        if (keepAll) return selected
        val on = cues(userText, used, groupsOn)
        val kept = ArrayList<Pair<Int, Any?>>()
        for (i in 0 until selected.length()) {
            val t = selected.opt(i)
            val n = nameOf(t)
            val c = cueOf[n]
            if (n in ESSENTIAL || c == null || c in on) kept += i to t
        }
        if (kept.size <= max) return JSONArray().apply { kept.forEach { put(it.second) } }
        // Over the cap: essentials, tools this chat used and tools the request names keep their place; the rest is
        // ranked by how many of the request's words its name and description share.
        val words = word.findAll(userText.lowercase()).map { it.value }.filter { it !in stop }.toSet()
        fun score(t: Any?): Int {
            val n = nameOf(t)
            if (n in ESSENTIAL) return 10_000
            if (n in must) return 9_500
            if (n in used) return 9_000
            if (cueOf[n] != null) return 8_000
            val nameWords = n.lowercase().split('_').filter { it.length >= 3 }
            val direct = nameWords.count { w -> words.any { it.startsWith(w) || w.startsWith(it) } }
            val desc = (t as? JSONObject)?.optJSONObject("function")?.optString("description").orEmpty().lowercase()
            val inDesc = words.count { desc.contains(it) }
            return direct * 100 + inDesc
        }
        val chosen = kept.sortedWith(compareByDescending<Pair<Int, Any?>> { score(it.second) }.thenBy { it.first }).take(max).map { it.first }.toSet()
        return JSONArray().apply { kept.filter { it.first in chosen }.forEach { put(it.second) } }
    }

    /**
     * v0.45.1 prompt caching: the same tools as [now], but those already sent earlier in this turn ([prev], by name) keep
     * their earlier order and new ones go after them. Providers cache the request's longest identical prefix (the tool
     * list is part of it), so a tool joining mid-turn no longer moves every tool behind it. Nothing is added or dropped.
     */
    fun keepOrder(prev: List<String>, now: JSONArray): JSONArray {
        if (prev.isEmpty()) return now
        val pos = HashMap<String, Int>().apply { prev.forEachIndexed { i, n -> putIfAbsent(n, i) } }
        val items = (0 until now.length()).map { now.opt(it) }
        val sorted = items.withIndex().sortedWith(compareBy({ pos[nameOf(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
        return JSONArray().apply { sorted.forEach { put(it) } }
    }

    fun names(a: JSONArray): List<String> = (0 until a.length()).map { nameOf(a.opt(it)) }

    /** load_tools with ToolRouter's groups plus the cues (so the model can ask for any of them by name). */
    fun loadTool(): JSONObject {
        val t = ToolRouter.loadTool()
        val f = t.getJSONObject("function")
        f.put("description", f.optString("description").removeSuffix(" They're available from your next step.") +
            "; " + CUES.joinToString("; ") { "${it.id} (${it.about})" } + ". They're available from your next step.")
        f.optJSONObject("parameters")?.optJSONObject("properties")?.optJSONObject("groups")?.optJSONObject("items")?.optJSONArray("enum")
            ?.let { e -> CUES.forEach { e.put(it.id) } }
        return t
    }

    /** Characters of tool schemas (what they cost in the request). */
    fun chars(tools: JSONArray): Int = tools.toString().length

    /** "tools 24/141 · schemas 61.2k→14.9k chars" for the speed line. */
    fun sizeLine(sent: JSONArray, all: JSONArray, before: JSONArray): String =
        "tools ${sent.length()}/${all.length()} · schemas %.1fk→%.1fk chars".format(chars(before) / 1000.0, chars(sent) / 1000.0)
}
