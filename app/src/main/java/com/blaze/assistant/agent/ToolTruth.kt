package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.45.9 (user, 12:14: "the agent in chat must see and use its full tool set again"). Root cause, found in the code:
 * since v0.42/v0.44 every agent request carried only a guessed subset of the tools (ToolRouter's groups, then ToolPick's
 * 36-tool cap), the chat lane and the @all duo chat carried none, and gateway 0.8.2 now drops any tool call whose name
 * isn't in the request's own tools list (and answers with short text instead). So a request whose words missed a group
 * ("save this file", "open example.com in the Linux browser") reached a model that had no such tool, and it said it
 * couldn't. Now:
 *  - every agent request carries the full registered list ([full] on; Plan mode, private chats and background agents keep
 *    their deliberate filters);
 *  - lanes that send no tools (chat lane, duo chat) hand anything tool-shaped ([toolShaped]) to the full lane;
 *  - the list sent (count + names) is logged with each request ([line]) in What happened and the speed log;
 *  - [RULE]: never claim not to have a registered tool's ability; [refusal] catches the claim and the turn is nudged once.
 * Pure Kotlin; ToolTruthTest.
 */
object ToolTruth {
    /** On by default; "tool_full" = false brings back the old guessed subsets (a debugging switch only). */
    val full: Boolean get() = try { Settings.bool("tool_full", true) } catch (_: Throwable) { true }

    const val RULE = "\nYOUR TOOLS ARE REAL: every tool in your list works on this phone. Never say \"I don't have the capability\", \"I can't access\", " +
        "\"I'm unable to\" or \"as an AI I can't\" about anything a tool in your list does (terminal, Linux desktop and browser, opening views and screens, " +
        "files and save_file, calendar, reminders, web search, memory). Call the tool instead. If a tool fails, say what it returned. After a tool works, say in one short line what it did (for save_file, the saved path). "

    /** Requests that need a tool (any lane that sends no tools hands these to the full lane). */
    private val shaped = Regex("""(?i)\b(run|execute|terminal|shell|command|ls|cd|pwd|cat|python|script|linux|desktop|browser|browse|open|launch|show me|go to|navigate|settings|design view|view|screen|tab|page|file|files|folder|save|download|export|upload|calendar|schedule|agenda|event|meeting|remind|reminder|alarm|timer|routine|search|look ?up|google|web|fetch|memory|remember|forget|recall|what do you know|weather|send|text|call|message|email|install|make|create|build|generate|draw|play|set|turn (?:on|off)|enable|disable)\b|https?://|\b[\w-]+\.(com|org|net|io|dev|zm|app)\b""")

    fun toolShaped(text: String): Boolean = shaped.containsMatchIn(text)

    /** Plain talk that happens to contain a cue word ("what do you think, should I make tea?") stays with the talker. */
    private val chatty = Regex("""(?i)^\s*(?:@\w+\s*)?(?:what do you think|how do you feel|do you like|tell me a joke|joke)\b""")
    fun chatOnly(text: String): Boolean = chatty.containsMatchIn(text)

    /** A reply that claims an ability is missing. */
    private val refusalRe = Regex("""(?i)\b(i (?:don'?t|do not) have (?:the )?(?:capability|ability|access|tools?|a way)|i(?: can'?t| cannot| am unable to|'m unable to| am not able to|'m not able to) (?:save|create|write|download|access|open|run|execute|browse|view|see|check|set|use|read|reach)|as an ai,? i (?:can'?t|cannot|don'?t)|(?:isn'?t|is not) something i can do|beyond my capabilities|outside (?:of )?my capabilities|open a text editor on your (?:computer|pc|laptop)|on your (?:computer|pc|laptop),? open)""")

    fun refusal(text: String): Boolean = refusalRe.containsMatchIn(text)

    const val NUDGE = "[Check: you just said you can't do that, but your tool list has a tool for it. Don't refuse and don't give desktop advice: call the tool now (save_file saves the last code block; run_shell runs commands; browser_open / desktop_task for the Linux browser; open_view for app views and settings; calendar_events / create_routine for the calendar and reminders; web_search; memory_search).]"

    private fun nameOf(t: Any?): String = (t as? JSONObject)?.optJSONObject("function")?.optString("name").orEmpty()

    fun names(tools: JSONArray?): List<String> = if (tools == null) emptyList() else (0 until tools.length()).map { nameOf(tools.opt(it)) }.filter { it.isNotBlank() }

    /** "tools sent 142: a, b, c…" (names in the order sent; [max] names, then "+N more"). */
    fun line(tools: JSONArray?, max: Int = 400): String {
        val n = names(tools)
        if (n.isEmpty()) return "tools sent 0 (no tools in this request)"
        val shown = n.take(max)
        return "tools sent ${n.size}: " + shown.joinToString(", ") + if (n.size > shown.size) " +${n.size - shown.size} more" else ""
    }

    /** The tools one agent request carries: the whole registered list, or (switch off) the old guessed [subset]. */
    fun forRequest(all: JSONArray, subset: () -> JSONArray): JSONArray = if (full) all else subset()

    private val validName = Regex("^[a-zA-Z0-9_-]{1,64}$")

    /**
     * The list as it goes on the wire: the first schema for each name kept, invalid names dropped, and every schema given
     * object parameters (OpenAI shape; gateway 0.8.2 validates calls against the request's own list).
     */
    fun clean(tools: JSONArray): JSONArray {
        val seen = HashSet<String>()
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val f = t.optJSONObject("function") ?: continue
            val n = f.optString("name")
            if (!validName.matches(n) || !seen.add(n)) continue
            val p = f.optJSONObject("parameters")
            if (p == null) f.put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))
            else { if (p.optString("type").isBlank()) p.put("type", "object"); if (p.optJSONObject("properties") == null) p.put("properties", JSONObject()) }
            if (t.optString("type").isBlank()) t.put("type", "function")
            out.put(t)
        }
        return out
    }

    /** The tools the strict cases need; every lane that sends tools must have these (ToolTruthTest). */
    val MUST = setOf("run_shell", "browser_open", "desktop_task", "open_view", SaveFile.TOOL, "calendar_events", "create_routine", "web_search", "memory_search", "remember")

    /**
     * v0.45.9 replay fix: on the first step of a turn whose ask names exactly one tool, the request names that tool in
     * tool_choice, so a slow or empty model step can't answer it with text. "save this file" after a code reply →
     * save_file (live 0.8.7: auto once came back empty after 32 s; named, 3 of 3 in ~1 s); "View calendar" (or a yes to a
     * calendar offer) → calendar_events, which shows Android's one-tap prompt when access is off. Null = "auto".
     */
    private val calView = Regex("""(?i)^\s*(?:@\w+\s+)?(?:please\s+)?(?:view|show|open|check|see)(?:\s+me)?(?:\s+(?:my|the))?\s+(?:google\s+)?calendar\b[\s.!?]*$""")
    private val calOffer = Regex("""(?i)\b(?:check|look at|open|view|see)\b[^.?!\n]{0,30}\bcalendar\b""")

    // Live 0.8.7, 5 runs: "open example.com in the Linux browser" went to browser_open (Blaze's built-in browser) 5 of 5,
    // and "open Settings" was refused 2 of 5 ("I don't have the capability to open settings").
    private val linux = Regex("""(?i)\b(?:linux|xfce|alpine)\s+(?:browser|desktop)\b|\bin\s+(?:the\s+)?linux\b|\bfirefox\b""")
    private val viewAsk = Regex("""(?i)^\s*(?:@\w+\s+)?(?:please\s+)?(?:open|show|go to|switch to)\s+(?:me\s+)?(?:the\s+|my\s+)?(?:blaze\s+)?(?:settings|design|terminal|schedule|memory|projects|home)(?:\s+(?:view|page|screen|tab))?[\s.!]*$""")

    // v0.45.13 round 2 (rpS terminal-ls 3/5: codestral wrote ```sh ls``` as text with no run_shell call): "run X in the
    // terminal", "run ls", a bare shell command ("ls", "pwd", "git status") names the terminal tool on the first step.
    private const val CMDS = "ls|pwd|whoami|uname|df|du|ps|htop|neofetch|ifconfig|uptime|git|python3?|pip3?|npm|npx|node|apt(?:-get)?|pkg|mkdir|chmod|grep|curl|wget"
    private val inTerminal = Regex("""(?i)^\s*(?:@\w+\s+)?(?:please\s+|can you\s+|could you\s+)?(?:run|execute|exec|type)\s+\S.{0,80}?\s+(?:in|on|from)\s+(?:the\s+|my\s+|a\s+)?(?:blaze\s+)?(?:terminal|shell|console|command line)\b[\s.!?]*$""")
    private val runCmd = Regex("""(?i)^\s*(?:@\w+\s+)?(?:please\s+)?(?:run|execute)\s+(?:`[^`\n]+`|(?:$CMDS)(?:\s+[-\w./~=:@"']+){0,6})[\s.!?]*$""")
    private val bareCmd = Regex("""^\s*(?:@\w+\s+)?(?:$CMDS)(?:\s+[-\w./~=:@"']+){0,6}\s*$""")

    /** "run ls in the terminal", "run `df -h`", "ls", "git status": a shell command for run_shell. */
    internal fun shellAsk(u: String): Boolean = inTerminal.matches(u) || runCmd.matches(u) || bareCmd.matches(u)

    // v0.45.9 gate (HQ 15:37), live 0.8.7, 5 runs each on auto: "remind me to call mum at 18:00 today" went to
    // create_trigger or no tool 3 of 5 ("Reminder set" with nothing set); "what reminders do I have set?" called no tool
    // 5 of 5 ("nothing scheduled today" 4 of 5); "remember that my NET exam is on 3 November" said "Got it, I'll remember"
    // with no write 5 of 5; "what do you remember about my exams?" said "I don't have any memories" with no search 5 of 5.
    private val remindAsk = Regex("""(?i)^\s*(?:@\w+\s+)?(?:please\s+|can you\s+|could you\s+)?(?:remind me\b|set (?:me )?an? reminder\b|add an? reminder\b)""")
    private val remindList = Regex("""(?i)\b(?:what|which|show|list|any)\b[^.?!\n]{0,25}\b(?:reminders|routines)\b""")
    private val memSave = Regex("""(?i)^\s*(?:@\w+\s+)?(?:please\s+)?(?:remember|note|save to memory)\s+(?:that\s+)?(?!when\b|what\b|if\b)\S""")
    private val memRecall = Regex("""(?i)\b(?:what do you (?:remember|know) about|do you remember|what have you (?:saved|remembered)|what(?:'s| is) in (?:your|my) memory)\b""")

    // v0.45.13 phone-control sweep 0 (replay 8/60: codestral answered most phone asks with text and no tool, or
    // stopped after opening a page). Each phone ask names its tool on the first step; the alarm-off and settings-value
    // flows also name the second step ([follow]). Real names from PhoneTools / Skills / Processes.
    private val alarmOff = Regex("""(?i)\b(?:turn|switch|shut)\s+off\b[^.?!\n]{0,40}\balarms?\b|\b(?:remove|delete|cancel|disable|dismiss|stop|kill|clear)\b[^.?!\n]{0,30}\balarms?\b|\balarms?\b[^.?!\n]{0,30}\b(?:off|remove|delete|cancel)\b""")
    private val alarmSet = Regex("""(?i)\b(?:set|add|create|make|put)\s+(?:me\s+)?(?:an?\s+|my\s+)?alarm\b|\bwake me (?:up )?at\b""")
    private val draftAsk = Regex("""(?i)\bdraft\b[^.?!\n]{0,30}\b(?:text|sms|message)\b|\b(?:text|sms|message)\b[^.?!\n]{0,60}\b(?:don'?t|do not|never)\s+send\b""")
    private val timerAsk = Regex("""(?i)\b(?:set|start|put|make)\s+(?:me\s+)?(?:an?\s+)?(?:\d+[\s-]*(?:min(?:ute)?s?|sec(?:ond)?s?|hours?)\s+)?(?:countdown\s+)?timer\b|\btimer for\b""")
    private val contactAsk = Regex("""(?i)\b(?:add|save|create|new)\s+(?:a\s+|the\s+|this\s+)?(?:new\s+)?contact\b|\bsave\b[^.?!\n]{0,40}\bas a contact\b|\badd\b[^.?!\n]{0,40}\bto (?:my )?contacts\b""")
    private val mapsAsk = Regex("""(?i)\b(?:directions?|navigate|navigation|route)\s+(?:me\s+)?(?:to|for)\b|\bhow do i get to\b|\btake me to\b[^.?!\n]{0,40}\bmaps?\b""")
    /** 0.45.16 (design suite tool-media-play 1/5): "play X in Blaze Music" went to run_plan search_in_app on our own app. */
    private val blazeMusicAsk = Regex("""(?i)\b(?:play|put on|start|queue|shuffle)\b[^.?!\n]{0,80}\b(?:on|in|from|with)\s+(?:blaze\s+music|my\s+(?:music\s+)?library|my\s+(?:songs|downloads))\b""")
    private val youtubeAsk = Regex("""(?i)\b(?:play|put on|watch|start)\b[^.?!\n]{0,80}\b(?:on|in|from)\s+(?:youtube(?:\s+music)?|yt)\b""")
    private val toggleAsk = Regex("""(?i)\b(?:turn|switch|put)\s+(?:on|off)\b[^.?!\n]{0,40}\b(?:wi-?fi|bluetooth|do not disturb|dnd|flashlight|torch|airplane|aeroplane|flight mode|mobile data)\b|\b(?:wi-?fi|bluetooth|do not disturb|dnd|flashlight|torch|airplane mode|mobile data)\b[^.?!\n]{0,40}\b(?:on|off)\b|\b(?:enable|disable)\b[^.?!\n]{0,20}\b(?:wi-?fi|bluetooth|do not disturb|dnd|flashlight|airplane mode|mobile data)\b""")
    private val settingsValue = Regex("""(?i)\b(?:open|go to)\b[^.?!\n]{0,20}\bsettings\b[^.?!\n]{0,40}\b(?:turn|switch|set|enable|disable|change)\b|\b(?:turn|switch|set)\s+(?:on\s+|off\s+)?(?:the\s+)?(?:dark (?:mode|theme)|night (?:mode|light)|eye comfort|auto[- ]?rotate|always on display)\b|\b(?:dark (?:mode|theme)|night mode)\s+(?:on|off)\b""")
    private val eventAsk = Regex("""(?i)\b(?:add|create|put|schedule|book|make)\s+(?:a\s+|an\s+|the\s+)?(?:new\s+)?(?:calendar\s+)?(?:event|meeting|appointment)\b|\b(?:add|put)\b[^.?!\n]{0,60}\b(?:to|on|in) (?:my |the )?calendar\b""")

    /** 0.45.15: a phone ask that a direct tool does (the app names it in tool_choice), so no saved tap plan is sent. */
    fun directPhone(userText: String): Boolean = phoneChoice(userText.trim().replace('\u2019', '\''), PHONE_CORE) != null

    /** The phone ask's first-step tool, or null. [toolNames] must hold it. */
    internal fun phoneChoice(u: String, toolNames: Collection<String>): String? {
        fun pick(vararg n: String) = n.firstOrNull { it in toolNames }
        return when {
            alarmOff.containsMatchIn(u) -> pick("processes", "process")
            blazeMusicAsk.containsMatchIn(u) -> pick("music_ops")
            draftAsk.containsMatchIn(u) -> pick("compose_sms")
            timerAsk.containsMatchIn(u) -> pick("set_timer")
            alarmSet.containsMatchIn(u) -> pick("set_alarm")
            contactAsk.containsMatchIn(u) -> pick("add_contact")
            mapsAsk.containsMatchIn(u) -> pick("navigate_to", "open_url")
            youtubeAsk.containsMatchIn(u) -> pick("search_in_app")
            // 0.45.15: open_settings (SettingsJump) first: it returns the page's screen, so the tap that follows can aim.
            settingsValue.containsMatchIn(u) -> pick("open_settings", "open_settings_page")
            toggleAsk.containsMatchIn(u) -> pick("device_setting")
            eventAsk.containsMatchIn(u) -> pick("add_calendar_event", "gcal_create")
            else -> null
        }
    }

    /**
     * v0.45.13: the second step of a two-step phone flow, named once: after `processes` on an alarm-off ask → `process`
     * (the list gives the ID); after `open_settings_page` on a settings-value ask → `tap` (with want). Keyed by the tool
     * the previous step called (exactly one tool). Also written into the replay fixture (tool_choice_follow) so
     * tools/halo_replay.py mirrors it.
     */
    fun follow(userText: String): Map<String, String> {
        val u = userText.trim().replace('\u2019', '\'')
        return when {
            alarmOff.containsMatchIn(u) -> mapOf("processes" to "process")
            settingsValue.containsMatchIn(u) && !draftAsk.containsMatchIn(u) -> mapOf("open_settings" to "tap", "open_settings_page" to "tap")
            else -> emptyMap()
        }
    }

    fun choiceFor(userText: String, assistantTexts: List<String>, toolNames: Collection<String>): String? {
        val u = userText.trim().replace('\u2019', '\'')
        if ("desktop_task" in toolNames && linux.containsMatchIn(u) && Regex("(?i)\\b(open|go to|browse|visit|load)\\b").containsMatchIn(u)) return "desktop_task"
        if ("open_view" in toolNames && viewAsk.matches(u)) return "open_view"
        if ("run_shell" in toolNames && shellAsk(u)) return "run_shell"
        if (SaveFile.TOOL in toolNames && SaveFile.wantsSave(u) && SaveFile.lastCode(assistantTexts) != null) return SaveFile.TOOL
        if ("calendar_events" in toolNames && (calView.matches(u) ||
                TurnContext.scheduleFollowUp(u, assistantTexts.lastOrNull().orEmpty()) && calOffer.containsMatchIn(assistantTexts.lastOrNull().orEmpty()))) return "calendar_events"
        if ("create_routine" in toolNames && remindAsk.containsMatchIn(u)) return "create_routine"
        if ("list_routines" in toolNames && remindList.containsMatchIn(u)) return "list_routines"
        if (memRecall.containsMatchIn(u)) return listOf("memory_search", "recall_memory").firstOrNull { it in toolNames }
        if (memSave.containsMatchIn(u) && MemoryGate.allows(u)) return listOf("memory_save", "remember").firstOrNull { it in toolNames }
        return phoneChoice(u, toolNames)
    }

    /**
     * 0.45.15 (device /phoneqa 21 Oct 22:40, 10/55): the app sends notes of its own as user messages after the ask: the
     * saved-plan hint ("[Done before] … Send them now as ONE run_plan call") and the orient screen. Read as "the user's
     * message", they hid the ask: no named tool_choice, blaze_tools auto, and codestral wrote run_plan JSON / tool names
     * as text. These are never the ask.
     */
    fun isAppNote(content: String): Boolean {
        val t = content.trimStart()
        return NOTE_HEADS.any { t.startsWith(it) }
    }
    val NOTE_HEADS = listOf("[Done before]", "[Where the phone is right now", "[Before acting]")

    /** Index of the user's own last message (app notes after it skipped), or null. */
    fun askIndex(messages: JSONArray): Int? = (messages.length() - 1 downTo 0).firstOrNull { i ->
        messages.optJSONObject(i)?.let { it.optString("role") == "user" && !isAppNote(it.optString("content")) } == true }

    /** The name [choice] would put in tool_choice for [messages] (null = auto). */
    fun choiceName(messages: JSONArray, toolNames: Collection<String>): String? {
        try {
            val n = messages.length(); if (n == 0) return null
            val last = messages.optJSONObject(n - 1) ?: return null
            val ui = askIndex(messages) ?: return null
            val user = messages.optJSONObject(ui)?.optString("content").orEmpty()
            // First step: nothing after the ask but the app's own notes.
            if ((ui + 1 until n).all { messages.optJSONObject(it)?.let { m -> m.optString("role") == "user" && isAppNote(m.optString("content")) } == true }) {
                val said = (0 until n - 1).mapNotNull { messages.optJSONObject(it) }.filter { it.optString("role") == "assistant" }.map { it.optString("content") }
                return choiceFor(user, said, toolNames)
            }
            // v0.45.13: a later step of this turn: the follow-up tool, once, after a step that called exactly the key tool.
            if (last.optString("role") != "tool") return null
            val fl = follow(user); if (fl.isEmpty()) return null
            // v0.45.13 round 2 (rpS settings-value #5): identical calls in one step (same name, same arguments, e.g.
            // open_settings_page{display} twice) count once, so the step still "called exactly the key tool".
            val calls = (ui + 1 until n).mapNotNull { messages.optJSONObject(it) }.filter { it.optString("role") == "assistant" }
                .map { a -> a.optJSONArray("tool_calls")?.let { c -> distinctCalls(c) } ?: emptyList() }
            val prev = calls.lastOrNull() ?: return null
            val next = prev.singleOrNull()?.let { fl[it] } ?: return null
            if (next !in toolNames || calls.any { next in it }) return null
            return next
        } catch (e: Throwable) { return null }
    }

    /**
     * v0.45.13 round 3 (rpT settings-value 0/5): the model split one switch tap into tap{text:"Dark mode"} and
     * tap{want:"on"}, so the tap that ran had no want and the switch was never read back. In one step, a tap that has
     * only want (no text, no element) is folded into the nearest earlier tap of that step that names its target and has
     * no want (else the nearest later one), and dropped. Identical taps then count once. Runs on the phone, no extra
     * model step. Returns how many calls were folded.
     */
    fun mergeTaps(reply: JSONObject): Int {
        val calls = reply.optJSONArray("tool_calls") ?: return 0
        if (calls.length() < 2) return 0
        fun argsOf(c: JSONObject): JSONObject? = c.optJSONObject("function")?.let { f ->
            when (val a = f.opt("arguments")) { is JSONObject -> a; is String -> try { JSONObject(a.ifBlank { "{}" }) } catch (e: Exception) { null }; else -> JSONObject() } }
        val list = (0 until calls.length()).mapNotNull { calls.optJSONObject(it) }
        val isTap = list.map { nameOf(it) == "tap" }
        val args = list.map { argsOf(it) }
        fun named(a: JSONObject?) = a != null && (a.optString("text").isNotBlank() || a.has("element"))
        fun hasWant(a: JSONObject?) = a != null && a.optString("want").isNotBlank()
        val drop = mutableSetOf<Int>()
        for (i in list.indices) {
            if (!isTap[i] || named(args[i]) || !hasWant(args[i])) continue
            val target = ((i - 1 downTo 0) + (i + 1 until list.size)).firstOrNull { j -> j !in drop && isTap[j] && named(args[j]) && !hasWant(args[j]) } ?: continue
            val a = args[target]!!.put("want", args[i]!!.optString("want"))
            list[target].optJSONObject("function")?.put("arguments", a.toString())
            drop += i
        }
        if (drop.isEmpty()) return 0
        val out = JSONArray(); list.forEachIndexed { k, c -> if (k !in drop) out.put(c) }
        reply.put("tool_calls", out)
        return drop.size
    }

    /** Canonical arguments: JSON objects with their keys sorted (so {"a":1,"b":2} == {"b":2,"a":1}); else the trimmed text. */
    internal fun canonArgs(raw: Any?): String {
        fun canon(v: Any?): Any? = when (v) {
            is JSONObject -> JSONObject().also { o -> v.keys().asSequence().sorted().forEach { k -> o.put(k, canon(v.opt(k))) } }
            is JSONArray -> JSONArray().also { a -> (0 until v.length()).forEach { a.put(canon(v.opt(it))) } }
            else -> v
        }
        val t = when (raw) { is JSONObject -> raw; is String -> raw.trim(); null -> ""; else -> raw.toString().trim() }
        if (t is JSONObject) return sortedJson(canon(t))
        val str = t as String
        if (str.isEmpty() || str == "{}") return "{}"
        return try { sortedJson(canon(JSONObject(str))) } catch (e: Exception) { str }
    }

    private fun sortedJson(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + sortedJson(v.opt(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { sortedJson(v.opt(it)) }
        is String -> JSONObject.quote(v)
        null, JSONObject.NULL -> "null"
        else -> v.toString()
    }

    /** The tool names of one step's [toolCalls], identical calls (same name and arguments) counted once, order kept. */
    internal fun distinctCalls(toolCalls: JSONArray): List<String> = (0 until toolCalls.length()).mapNotNull { toolCalls.opt(it) as? JSONObject }
        .map { c -> val f = c.optJSONObject("function"); nameOf(c) to canonArgs(f?.opt("arguments")) }
        .filter { it.first.isNotBlank() }.distinct().map { it.first }

    /** The tool_choice value for a request: a named function when [choiceName] picks one, else "auto". */
    fun choice(messages: JSONArray, tools: JSONArray?): Any {
        if (tools == null || tools.length() == 0) return "auto"
        val name = choiceName(messages, names(tools)) ?: return "auto"
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name))
    }

    // ---------------- v0.45.13: at most MAX_TOOLS per request ----------------

    /**
     * Some gateway routes refuse more than 128 tools ("'tools': maximum number of items is 128"; replay 0.8.12 sent 136
     * once the phone tools joined). [cap] keeps every tool that matters to this turn and drops the least relevant ones
     * deterministically (same request → same list, original order kept for prompt caching):
     *  - always kept: the named-choice tool and its follow-up, [MUST], [PHONE_CORE], tools already called in this chat;
     *  - then by relevance to the last user message: in a ToolRouter group the text cues (+4), name in the text (+3),
     *    description words in the text (+1 each, max 3);
     *  - ties: [LOW] tools go first, then later tools before earlier ones.
     */
    const val MAX_TOOLS = 128

    val PHONE_CORE = setOf("open_app", "read_screen", "tap", "type_text", "scroll", "press_button", "wait", "search_in_app", "open_url",
        "navigate_to", "compose_sms", "set_alarm", "set_timer", "process", "processes", "add_calendar_event", "calendar_events", "add_contact",
        "find_contact", "device_setting", "phone_mode", "open_settings_page", "open_settings", "list_routines", "memory_save", "get_weather", "send_sms",
        "message_contact", "call_number", "dial", "music_ops", "video_ops", "blaze_now", "load_tools", "find_tool")

    /** Rarely needed on a phone turn: dropped first when the list is over the cap. */
    val LOW = listOf("jamendo_setup", "install_skill", "skill_file", "write_skill", "skill_manage", "brand_kit", "blazerouter", "update_brief",
        "project_docs_read", "project_docs_search", "flashcards", "life_logs", "browser_vpn", "uninstall_app", "home_widget_update",
        "home_widget_remove", "home_widget_list", "gallery_ops", "jarvis_mode", "standing_permission", "preview_server", "terminal_stop",
        "job_input", "linux_install", "read_artifact", "read_turn", "blaze_docs_read", "schedule_command", "delete_trigger", "list_triggers",
        "app_usage", "run_ussd", "save_workflow", "run_skill")

    private val word = Regex("[a-z][a-z0-9]{3,}")
    private val stop = setOf("this", "that", "with", "from", "your", "have", "what", "when", "where", "then", "them", "they", "into",
        "only", "also", "just", "like", "please", "some", "about", "there", "here", "phone", "blaze", "user", "tool", "tools", "using")

    fun cap(messages: JSONArray, tools: JSONArray, max: Int = MAX_TOOLS): JSONArray {
        if (tools.length() <= max) return tools
        val names = names(tools)
        val n = messages.length()
        val user = askIndex(messages)?.let { messages.optJSONObject(it)?.optString("content") }.orEmpty()
        val used = HashSet<String>()
        for (i in 0 until n) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "tool") m.optString("name").takeIf { it.isNotBlank() }?.let { used += it }
            m.optJSONArray("tool_calls")?.let { c -> for (j in 0 until c.length()) used += nameOf(c.opt(j)) }
        }
        val keep = HashSet<String>().apply {
            addAll(MUST); addAll(PHONE_CORE); addAll(used)
            choiceName(messages, names)?.let { add(it) }
            addAll(follow(user).values)
            // the first-step choice too on a later step, so the flow's own tool never drops mid-turn
            choiceFor(user, emptyList(), names)?.let { add(it) }
        }
        val text = user.lowercase()
        val words = word.findAll(text).map { it.value }.filter { it !in stop }.toSet()
        val cued = try { names(ToolRouter.selectDefault(tools, ToolRouter.guess(user))).toSet() } catch (_: Throwable) { emptySet() }
        val all = (0 until tools.length()).map { i ->
            val f = tools.optJSONObject(i)?.optJSONObject("function")
            val nm = f?.optString("name").orEmpty()
            val desc = f?.optString("description").orEmpty().lowercase()
            val nameHit = nm.split('_').any { it.length >= 3 && Regex("\\b" + Regex.escape(it)).containsMatchIn(text) }
            val descHits = words.count { Regex("\\b" + Regex.escape(it)).containsMatchIn(desc) }.coerceAtMost(3)
            val score = if (nm in keep) Int.MAX_VALUE else (if (nm in cued) 4 else 0) + (if (nameHit) 3 else 0) + descHits
            Triple(i, nm, score)
        }
        val drop = all.filter { it.third != Int.MAX_VALUE }
            .sortedWith(compareBy<Triple<Int, String, Int>> { it.third }
                .thenBy { LOW.indexOf(it.second).let { x -> if (x < 0) Int.MAX_VALUE else x } }
                .thenByDescending { it.first })
            .take((tools.length() - max).coerceAtLeast(0)).map { it.first }.toSet()
        val out = JSONArray()
        for (i in 0 until tools.length()) if (i !in drop) out.put(tools.get(i))
        return out
    }
}
