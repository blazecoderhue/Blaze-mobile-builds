package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool diet (speed plan step 1, the "mixture of experts" idea): instead of sending all ~95 tool
 * schemas on every turn, send a small core set plus only the groups this request looks like it
 * needs. Fewer input tokens means a faster first word on every provider. The model can always
 * pull in more with load_tools, and any tool that isn't in a group is always sent, so nothing
 * becomes unreachable.
 */
object ToolRouter {

    /** Always sent. */
    private val core = setOf(
        "save_file", "remember", "forget", "memory_search", "memory_save", "memory_update", "memory_delete", "open_view", "design", "about_blaze", "blaze_now", "jarvis_mode", "bank_mode", "follow_ups", "blaze_docs_search", "blaze_docs_read", "blazerouter", "processes", "process", "recall_memory", "device_status", "get_weather", "list_apps", "blaze_camera", "blaze_self", "ask_user", "self_test",
        "web_search", "fetch_page", "load_tools", "find_tool", "goal", "use_skill", "find_skill", "search_past_chats", "run_skill",
        // Long chats (v0.15.0): always on hand so nothing older is ever out of reach.
        "search_chat", "read_turn", "read_artifact", "update_brief", "project_docs_search", "project_docs_read",
        // Basic phone control stays in core: it's the most common thing people ask for.
        "run_plan", "open_app", "search_in_app", "read_screen",
        // Calls: "loudspeaker", "mute", "hang up" must work mid-call without guessing a group.
        "call_control",
        // Background agents: always on hand so long work can be handed off from any request.
        "start_agent", "list_agents", "check_agent", "message_agent", "cancel_agent"
    )

    data class Group(val id: String, val about: String, val match: Regex, val names: (String) -> Boolean)

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    val groups = listOf(
        Group("screen", "fine-grained screen control: tap, type, scroll, buttons, screenshots, do_actions",
            r("""\b(tap|click|press|scroll|swipe|type|screen|button|screenshot|look at|watch|watcher|whenever|pops? up|sign ?in|log ?in)\b"""),
            { it in setOf("tap", "type_text", "scroll", "press_button", "do_actions", "look_at_screen", "wait", "viewport", "open_url", "navigate_to", "share_to_app", "watch") }),
        Group("messages", "texts, calls, contacts, WhatsApp/Telegram, notifications, USSD",
            r("""\b(groups?|group chat|catch me up|unread|who owes|text|sms|message|whatsapp|telegram|reply|call|ring|dial|phone|contact|number|notification|missed|unread|airtime|bundle|balance|ussd|mtn|airtel|zamtel|mobile money|momo)\b|\*\d"""),
            { it in setOf("send_sms", "compose_sms", "read_messages", "call_contact", "call_number", "dial", "find_contact", "recent_calls",
                "message_contact", "send_text", "reply_to_notification", "recent_notifications", "run_ussd", "add_contact", "edit_contact",
                "notif_conversations", "notif_read", "notif_reply", "group_brain") }),
        Group("persona", "Halo Persona Studio: tune how Halo talks, language mix, boundaries, saved personas",
            r("""\b(persona\w*|personality|halo (?:be|talk|speak|sound)|be (?:funnier|nicer|more \w+)|speak (?:bemba|nyanja|english)|bemba|nyanja|stop calling me)\b"""),
            { it == "halo_persona" }),
        // v0.33.0 Agent Marketplace, Duo Workspaces, Call Copilot.
        Group("market", "Agent Marketplace: browse, review, install or share community skill packs",
            r("""\b(marketplace|skill packs?|community skills?|install (?:a |the )?(?:skill|pack)|share (?:my |this )?skill|blazepack)\b"""),
            { it == "skill_market" }),
        Group("workspace", "Duo Workspaces: long projects over days with a board, shared folder and daily standup",
            r("""\b(workspaces?|long project|business plan|thesis|standup|stand-up|progress board|kanban|over (?:the next )?(?:few )?days)\b"""),
            { it == "duo_workspace" }),
        Group("callcopilot", "Call Copilot: live captions on calls, suggested answers, call summaries",
            r("""\b(call copilot|captions?|during (?:my |the )?calls?|summari[sz]e (?:my |the )?calls?|after (?:the |my )?call)\b"""),
            { it == "call_copilot" }),
        // v0.33.0 Safe Walk, Phone Health Check, Time Capsule.
        Group("safety", "Safe Walk: text a trusted contact your location if you don't check in on time",
            r("""\b(safe ?walk|walk(?:ing)? (?:me )?home|check on me|get home safe|taxi|uber|yango|share my location|i'?m safe|check.?in)\b"""),
            { it == "safe_walk" }),
        Group("health", "Phone Health Check: why the phone is slow / hot / dying, unused apps, cleanup",
            r("""\b(slow|lag\w*|hot|heat\w*|overheat\w*|dying|drain\w*|storage|space|clean ?up|unused apps?|phone health|health check|junk|memory full)\b"""),
            { it == "phone_health" }),
        Group("capsule", "Time Capsule: sealed notes that open on a future date",
            r("""\b(time capsule|future me|future self|letter to (?:my ?self|me)|open (?:it|this) (?:in|on|next))\b"""),
            { it == "time_capsule" }),
        // v0.33.0 Money Radar (receipts, bills, bundles).
        Group("money", "Money Radar: spending summary, receipts from mobile-money / bank SMS, bills due, data bundles",
            r("""\b(spen[dt]\w*|money|receipts?|transactions?|bills?|due|zesco|rent|budget|bundles?|data left|airtime|momo|mobile money|balance|paid|sent me|how much)\b"""),
            { it == "money" }),
        Group("logs", "life logs: record and transcribe what the mic hears (start, pause, stop, brainstorm, status, delete)",
            r("""\b(logs?|logging|live logs?|life logs?|brainstorm\w*|transcri\w*|record\w*|listen(?:ing)? in)\b"""),
            { it == "life_logs" }),
        Group("device", "alarms, timers, volume, settings toggles, battery, media",
            r("""\b(alarm|timer|volume|mute|loud|wifi|wi-fi|bluetooth|torch|flash|brightness|dnd|disturb|airplane|battery|setting|pause|play|next|skip|music|song|clipboard|copy|paste|notif\w*|share|ringer|silent|vibrate|rotat\w*|hotspot|nfc|location|split|power|screenshot|lock|usage|uninstall|camera|qr|photo|installed|apps?|secure folder|private space|phone model|android version|where am i|near me|nearby|gps|my location|address)\b"""),
            { it in setOf("set_alarm", "set_timer", "set_volume", "toggle_setting", "device_setting", "battery_level", "media_control", "get_location") || it in com.blaze.assistant.phone.AndroidControl.names }),
        Group("schedule", "calendar, routines, triggers (when X do Y)",
            // v0.45.9: plurals and "what's on" questions too ("View your upcoming meetings" got no calendar tool and Blaze said it couldn't see the calendar).
            r("""\b(calendars?|events?|meetings?|schedules?|remind|reminders?|alarms?|agenda|appointments?|classes|class|lectures?|upcoming|every|daily|tomorrow|tonight|morning|routines?|when|whenever|anything on|what'?s on|whats on|what is on|on for today|what do i have|do i have anything|am i (?:busy|free))\b"""),
            { it in setOf("calendar_events", "add_calendar_event", "gcal_events", "gcal_create", "create_routine", "list_routines", "delete_routine", "schedule_command",
                "create_trigger", "list_triggers", "delete_trigger", "move_event", "delete_event") }),
        Group("google", "Gmail, Drive, Google contacts, Google Classroom",
            r("""\b(e-?mail|gmail|inbox|mail|drive|doc|document|file|classroom|assignments?|coursework|homework|due)\b"""),
            { it.startsWith("gmail_") || it == "drive_search" || it == "gcontacts_search" || it.startsWith("classroom_") }),
        Group("seller", "brand kit for the user's store or business (posts, ads, listings, designs)",
            r("""\b(brand|store|shop|shopify|dropship\w*|listing|product|ads?|advert\w*|caption|posts?|content calendar|tiktok|instagram|facebook|marketing|customers?|orders?|digest|competitors?|homeglow)\b"""),
            { it == "brand_kit" }),
        // 0.45.19: App Store (free open-source apps from their own releases, installed with Android's installer).
        Group("appstore", "App Store: free open-source Android apps from their developers' releases, installed on this phone with Android's installer",
            r("""\b(app ?store|apps?|install|f-?droid|open[- ]source|foss|apk|get (?:me )?(?:the |an? )?app)\b"""),
            { it.startsWith("store_") }),
        Group("study", "flashcards (spaced repetition in the Learn view), study tools",
            r("""\b(flash ?cards?|quiz|revise|revision|study|studying|exam|test me|memori[sz]e|lecture|tutor)\b"""),
            { it == "flashcards" }),
        Group("browser", "Blaze's own background browser: open sites, click, fill forms, log in, private tabs, browser VPN by country",
            r("""\b(site|website|web ?page|browser|log ?in|sign ?in|form|book|order|buy|account|portal|vpn|incognito|private tab|from (?:the )?(?:us|uk|usa|another country)|\.com|\.org|\.zm|https?://)"""),
            { it.startsWith("browser_") }),
        Group("terminal", "Linux shell, code, files, Termux",
            r("""\b(shell|terminal|linux|command|script|code|coding|python|node|js|javascript|npm|pip|git|github|repo|clone|commit|push|pull|branch|api|json|csv|http|curl|download|install|file|files|folder|termux|run|compile|build|debug|server|program|calculate|convert)\b"""),
            { it in setOf("run_shell", "linux_install", "terminal_jobs", "terminal_stop", "termux_run", "git", "schedule_command", "preview_server") || it in com.blaze.assistant.terminal.DevTools.names }),
        Group("apps", "GitHub, Notion, Slack, Spotify connectors",
            r("""\b(github|repo|issue|pull request|pr|notion|slack|spotify|playlist|song|music)\b"""),
            { it.startsWith("github_") || it.startsWith("notion_") || it.startsWith("slack_") || it.startsWith("spotify_") }),
        Group("media", "play Blaze Music (music_ops: play, pause, next, playlists; never the terminal or screen control), make images (generate_image), beats and other media, the Gallery",
            r("""\b(aperture|shutter|iso|depth of field|hyperfocal|nd filter|receipt|prices?|subtitles?|captions?|recap|episodes?|series|videos?|autoplay|playlists?|equali[sz]er|crossfade|lyrics|karaoke|wrapped|daily mix\w*|dj|duplicates?|blurry|trash|on this day|declutter|download\w*|songs?|albums?|hum\w*|beat lab|reels?|clip studio|edit (?:my |a |the )?videos?|tiktok|status video|montage|cover art|covers?|thumbnails?|album art|image|images|picture|pictures|pic|photo|draw|drawing|paint|art|artwork|logo|icon|wallpaper|poster|illustration|generate|create|design|beat|beats|instrumental|music|song|melody|audio|sound|wav|mp3|gallery|meme|sticker|avatar)\b"""),
            { it in setOf("generate_image", "gallery", "run_code", "file_list", "file_read", "file_write", "run_shell", "terminal_status", "cover_art", "beat_lab", "clip_studio", "media_download", "video_ops", "music_ops", "gallery_ops", "camera_math") }),
        Group("read", "read aloud: articles, files, chats in the user's own cloned voice or Blaze's",
            r("""\b(read (?:it |this |that )?(?:out|aloud|to me)|read me|listen to (?:this|the|an?)|my voice|audiobook|text to speech|narrat\w*)\b"""),
            { it == "read_aloud" }),
        // v0.45.2: Blaze's Linux desktop on this phone (desktop_task = a whole job with a live view in chat; desktop = start/stop/click).
        // Before, both fell into "more" (sent only on request), so "open the browser in it" went to run_shell. DesktopRoute adds
        // this group for "it / there" requests while the desktop runs; the cue below covers requests that name it.
        Group(DesktopRoute.GROUP, "Blaze's Linux desktop on this phone: desktop_task does a whole job in it (open apps, browse, click, type) while the user watches a live view in chat; desktop starts, stops or shows it",
            r("""\b(linux desktop|desktop linux|linux browser|browser in (?:the )?linux|xfce|vnc|(?:on|in|inside) (?:the |my )?(?:linux )?desktop|the desktop)\b"""),
            { it in DesktopRoute.TOOLS }),
        Group("pc", "the user's paired computer running Blaze Desktop (ask_pc)",
            r("""\b(pc|computer|laptop|desktop|my mac|macbook|on windows)\b"""),
            { it == "ask_pc" }),
        // v0.29.0 Home board widgets.
        Group("home", "the Home board: add, change, remove and list widgets (weather, news, creators, sports scores, prices, calendar, routines, deadlines, GitHub, most-used apps, custom cards)",
            r("""\b(widgets?|home ?board|home tab|on (?:my )?home|board|dashboard|track(?:ing)?|follow(?:ing)?|keep an eye|scores?|fixtures|new videos|uploads|crypto|bitcoin|exchange rate|stock price)\b"""),
            { it.startsWith("home_widget_") }),
        Group("more", "everything else: watchers, triggers, goals, standing permissions, skill packs and other rarely used tools",
            Regex("(?!)"), { false }),
        Group("extras", "learned skills, helpers working in parallel, screen memory",
            r("""\b(skill|learn|delegate|parallel|at once|earlier on screen|saw|workflow)\b"""),
            { it in setOf("skill_manage", "delegate", "create_specialist", "search_screen_memory", "install_skill", "write_skill", "save_workflow", "skill_file") }),
    )

    private fun nameOf(t: Any?): String = (t as? JSONObject)?.optJSONObject("function")?.optString("name").orEmpty()

    /** A tool's group; tools no group claims (and that aren't core) fall into "more", sent only on request. */
    fun groupOf(name: String): String? = groups.firstOrNull { it.names(name) }?.id
        ?: if (name in core || name == "load_tools" || name.isBlank() || !Settings.bool("tool_lean", true)) null else "more"

    /** Tool groups the text looks like it needs. */
    /** v0.45.0 (#19): the groups that hold [names]. */
    fun groupsFor(names: Collection<String>): Set<String> = names.mapNotNull { groupOf(it) }.toSet()

    fun guess(text: String): Set<String> = groups.filter { it.match.containsMatchIn(text) }.map { it.id }.toSet()

    /** v0.45.0 (#20): the action groups a request in any language gets when the English cues found nothing. */
    val CORE_ACTION = setOf("device", "messages", "screen")

    private val englishWords = setOf("the", "a", "an", "to", "my", "me", "is", "are", "what", "how", "why", "who", "when", "where", "please",
        "can", "could", "you", "i", "it", "of", "for", "and", "this", "that", "with", "do", "does", "your", "tell", "about", "in", "on", "hi", "hey",
        "hello", "thanks", "thank", "ok", "okay", "yes", "no", "good", "best", "explain", "write")

    /** True when [text] reads as English (any common English word in it). Language-independent tokens. */
    fun looksEnglish(text: String): Boolean =
        Regex("[\\p{L}']+").findAll(text.lowercase()).any { it.value in englishWords }

    /**
     * v0.45.0 (#20) route by language, not only English keywords: when the cues matched nothing and the request isn't
     * English (Bemba, Nyanja, French, Swahili…), the core action groups go along so the model can still act (and the
     * claim checks have the tools to check against). English requests that match nothing stay lean (#16).
     */
    fun fallback(text: String, guessed: Set<String>): Set<String> =
        if (guessed.isNotEmpty() || text.trim().length < 4 || looksEnglish(text)) emptySet() else CORE_ACTION

    /** Groups of every tool this chat has already used (so follow-ups keep working). */
    fun usedIn(history: JSONArray): Set<String> {
        val out = HashSet<String>()
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optString("role") == "tool") groupOf(m.optString("name"))?.let { out += it }
            val calls = m.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) groupOf(nameOf(calls.opt(j)))?.let { out += it }
        }
        return out
    }

    val enabled get() = Settings.bool("tool_diet", true)

    /** The tools to send this step. */
    fun select(all: JSONArray, groupsOn: Set<String>): JSONArray {
        if (!enabled) return all
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val t = all.opt(i)
            val n = nameOf(t)
            val g = groupOf(n)
            if (n in core || g == null || g in groupsOn) out.put(t)
        }
        return out
    }

    /** v0.44.0: [select] with the diet and lean prefs on, no Settings read (ToolPickSizeTest measures with it). */
    internal fun selectDefault(all: JSONArray, groupsOn: Set<String>): JSONArray {
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val t = all.opt(i)
            val n = nameOf(t)
            val g = groups.firstOrNull { it.names(n) }?.id ?: if (n in core || n == "load_tools" || n.isBlank()) null else "more"
            if (n in core || g == null || g in groupsOn) out.put(t)
        }
        return out
    }

    fun loadTool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "load_tools")
        .put("description", "Load more tools when the ones you have can't do the job. Groups: " +
            groups.joinToString("; ") { "${it.id} (${it.about})" } + ". They're available from your next step.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("groups", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")
                .put("enum", JSONArray(groups.map { it.id }))))).put("required", JSONArray(listOf("groups")))))
}
