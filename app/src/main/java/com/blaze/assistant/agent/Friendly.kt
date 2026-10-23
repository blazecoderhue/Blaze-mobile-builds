package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * Plain-English line for a tool call ("Searching the web for “cheap flights”"), shown instead of
 * tool names while Blaze works. Same phrases as Blaze Desktop's friendly.go, plus phone tools.
 */
object Friendly {
    private val plainRe = Regex("^[A-Za-z][A-Za-z0-9 ,.'()]*$")
    private val installRe = Regex("""(?i)\b(npm|pnpm|yarn|bun) (i|install|add)\b|\bpip3? install\b|\b(apt|apt-get|apk|pkg|pacman|cargo|go) (install|add|-S|get)\b""")

    /** Tools that write their own step line, so no second one is added. */
    val selfNarrating = setOf("find_tool", "load_tools", "remember", "forget", "generate_image", "ask_user", "design")

    fun say(name: String, a: JSONObject): String {
        fun v(k: String) = a.optString(k).lineSequence().firstOrNull().orEmpty().trim()
        fun q(s: String) = "\u201c" + s.take(40) + "\u201d"
        fun file() = v("path").substringAfterLast('/').ifBlank { "a file" }
        fun host(s: String) = try { java.net.URI(s).host?.removePrefix("www.") ?: s } catch (_: Exception) { s }.ifBlank { "a page" }
        fun or(vararg s: String) = s.firstOrNull { it.isNotBlank() }.orEmpty()
        val s = when (name) {
            "web_search" -> "Searching the web for " + q(v("query"))
            "fetch_page", "browser_read" -> "Reading " + host(or(v("url"), "a web page"))
            "browser_open", "open_url" -> "Opening " + host(or(v("url"), v("target")))
            "browser_click", "browser_type", "browser_scroll", "browser_back", "browser_tabs", "browser_show" -> "Using the browser"
            "browser_vpn" -> when (v("action")) { "connect" -> "Switching the browser to " + or(v("country"), "another country"); "disconnect" -> "Turning the browser VPN off"; else -> "Checking the browser VPN" }
            "http_request" -> "Calling " + host(v("url"))
            "download_file" -> "Downloading " + host(v("url"))
            "file_read" -> "Reading " + file()
            "file_write" -> "Writing " + file()
            "file_edit" -> "Editing " + file()
            "file_delete" -> "Deleting " + file()
            "file_list" -> "Looking through your files"
            "file_search" -> "Searching your files for " + q(or(v("pattern"), v("query")))
            "export_file" -> "Saving " + file() + " to your phone"
            "run_shell", "termux_run" -> {
                val cmd = v("command"); val sum = v("summary")
                when {
                    installRe.containsMatchIn(cmd) -> "Installing packages"
                    sum.isNotBlank() && sum.length <= 60 && plainRe.matches(sum) -> sum.replaceFirstChar { it.uppercase() }
                    else -> "Running a command"
                }
            }
            "run_code" -> "Running " + or(v("language"), "some") + " code"
            "git" -> "Checking the project history"
            "linux_install" -> "Setting up the Linux terminal"
            "blaze_command" -> "Opening /" + v("command").removePrefix("/").substringBefore(' ')
            "terminal_jobs", "terminal_status", "job_input", "job_log" -> "Checking a background job"
            "terminal_stop" -> "Stopping a background job"
            "preview_server" -> "Opening the preview"
            "read_screen", "look_at_screen" -> "Looking at your screen"
            "open_app" -> "Opening " + or(v("app"), v("name"), v("package"), "an app")
            "tap" -> "Tapping " + or(v("text"), "the screen")
            "type_text" -> "Typing"
            "scroll" -> "Scrolling"
            "press_button", "system_action" -> "Pressing " + or(v("button"), v("action"), "a button")
            "search_in_app" -> "Searching " + or(v("app"), "the app") + " for " + q(v("query"))
            "wait" -> "Waiting for the screen"
            "list_apps" -> "Checking your apps"
            "send_sms", "compose_sms", "send_text" -> "Writing a message to " + or(v("to"), v("name"), v("contact"), "your contact")
            "read_messages" -> "Reading your messages"
            "call_contact", "call_number", "dial" -> "Calling " + or(v("name"), v("number"), "someone")
            "recent_calls" -> "Checking your calls"
            "find_contact", "gcontacts_search" -> "Looking up " + or(v("name"), v("query"), "a contact")
            "add_contact", "edit_contact" -> "Updating your contacts"
            "calendar_events", "gcal_events" -> "Checking your calendar"
            "add_calendar_event", "gcal_create", "move_event", "delete_event" -> "Updating your calendar"
            "set_alarm" -> "Setting an alarm"
            "set_timer" -> "Setting a timer"
            "set_volume" -> "Changing the volume"
            "device_setting", "open_settings_page", "phone_mode" -> "Changing a setting"
            "open_settings" -> "Opening " + (if (v("app").isNotBlank()) v("app") + "\u2019s " else "") + when (v("page")) {
                "open_by_default" -> "link settings"; "app_details" -> "App info"; "" -> "settings"
                else -> v("page").removePrefix("app_").replace('_', ' ') + " settings" }
            "battery_level" -> "Checking your battery"
            "app_usage" -> "Checking your screen time"
            "navigate_to" -> "Getting directions to " + or(v("destination"), v("place"), "there")
            "media_control", "spotify_control", "spotify_play" -> "Controlling your music"
            "spotify_now_playing" -> "Checking what's playing"
            "open_camera" -> "Opening the camera"
            "clipboard" -> "Using the clipboard"
            "share_text", "share_to_app" -> "Sharing"
            "post_notification" -> "Sending a notification"
            "gallery" -> "Looking in your Gallery"
            "gmail_search", "gmail_read" -> "Checking your email"
            "gmail_send" -> "Sending an email"
            "drive_search" -> "Searching your Drive"
            "github_my_repos", "github_issues", "github_my_prs", "github_notifications", "github_search_repos" -> "Checking GitHub"
            "github_create_issue" -> "Opening a GitHub issue"
            "github_create_repo" -> "Creating the GitHub repo"
            "github_ci" -> "Waiting for the app to build"
            "github_install_apk" -> "Installing the app"
            "notion_search", "notion_add_page" -> "Working in Notion"
            "slack_channels", "slack_read", "slack_post" -> "Working in Slack"
            "use_skill", "run_skill", "skill_file" -> "Following a saved skill"
            "write_skill", "skill_manage", "install_skill" -> "Saving a skill"
            "save_workflow" -> "Saving a workflow"
            "search_past_chats" -> "Looking through past chats"
            "search_chat", "read_turn" -> "Looking back through your conversations"
            "memory_search" -> "Checking memory"
            "memory_save", "memory_update" -> "Saving to memory"
            "read_artifact" -> "Reading the saved output"
            "flashcards" -> "Working on your flashcards"
            "brand_kit" -> "Checking your brand kit"
            "update_brief" -> "Updating the project brief"
            "project_docs_search", "project_docs_read" -> "Checking the project's notes"
            "uninstall_app" -> "Uninstalling an app"
            "update_blaze" -> "Checking for a Blaze update"
            "ask_pc" -> "Asking your PC"
            "processes", "goal" -> "Keeping track of the task"
            "run_plan", "do_actions" -> "Working on your phone"
            else -> when {
                name.startsWith("mcp__") || name.startsWith("mcp_") -> "Using " + name.removePrefix("mcp__").removePrefix("mcp_").substringBefore("__").replace('_', ' ').ifBlank { "a connected service" }
                name.startsWith("github_") -> "Working in GitHub"
                name.startsWith("gmail_") -> "Working in your email"
                name.startsWith("browser_") -> "Using the browser"
                else -> "Working on it"
            }
        }
        return if (s.length > 60) s.take(59) + "…" else s
    }

    // ---------- Natural status lines (never commands) ----------

    private val fence = Regex("```[\\s\\S]*?(```|$)")
    private val inline = Regex("`[^`]*`")
    private val promptLine = Regex("(?m)^\\s*(\\$|>>>|PS>|#|>)\\s.*$")
    private val snake = Regex("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b(\\s*\\([^)]*\\))?")
    private val cmdish = Regex("""(?i)\b(sudo|pip3?|npm|npx|pnpm|yarn|apk|apt(-get)?|git|python3?|node|bash|sh|curl|wget|chmod|ls|cat|echo|gradlew?|mkdir|rm|cd|kotlinc|java)\s+(install|add|run|clone|push|commit|-\S+|\S*[./=]\w\S*)""")
    private val symbols = Regex("""&&|\|\||[{}<>]|=>|==|:=|;\s|\$\(""")
    private val url = Regex("""https?://\S+""")

    /**
     * The model's narration as one plain sentence for the status line: code, commands, tool
     * names and links are removed; a sentence that is mostly a command is dropped. "" when
     * nothing natural is left.
     */
    fun plain(text: String): String {
        var t = fence.replace(text, " ")
        t = promptLine.replace(t, " ")
        t = inline.replace(t, " ")
        t = url.replace(t, " ")
        val sentences = t.replace(Regex("\\s+"), " ").trim().split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !cmdish.containsMatchIn(it) && !symbols.containsMatchIn(it) }
            .map { snake.replace(it, "").replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.!?:])"), "$1").trim() }
            .filter { it.count { c -> c.isLetter() } >= 4 }
        val first = sentences.firstOrNull() ?: return ""
        val s = first.trimEnd(':', ',', ' ', ';', '.', '…').replace(Regex("\\s+(with|using|via|by|in|to|on|from)$", RegexOption.IGNORE_CASE), "")
            .trimEnd(':', ',', ' ', ';', '.').replaceFirstChar { it.uppercase() }
        if (s.count { it.isLetter() } < 4) return ""
        return if (s.length > 80) s.take(79).trimEnd() + "…" else s
    }

    /** What Blaze is doing while the model reads the result of [lastTool] (null = the user's message). */
    fun between(lastTool: String?): String = when {
        lastTool == null -> "Reading your message"
        lastTool == "web_search" -> "Going through the search results"
        lastTool in setOf("fetch_page", "browser_read") || lastTool.startsWith("browser_") -> "Reading the page"
        lastTool.startsWith("file_") -> "Going through the files"
        lastTool in setOf("run_shell", "termux_run", "run_code") -> "Checking how that went"
        lastTool in setOf("read_screen", "look_at_screen", "tap", "type_text", "scroll", "run_plan", "do_actions", "open_app") -> "Looking at the screen"
        lastTool.startsWith("gmail_") -> "Going through your email"
        lastTool.contains("calendar") || lastTool.startsWith("gcal_") -> "Checking your schedule"
        lastTool == "github_ci" -> "Reading the build result"
        lastTool.startsWith("github_") || lastTool == "git" -> "Going through the project"
        else -> "Working out the next step"
    }

    /** The exact call, for the expanded view. */
    fun exact(name: String, a: JSONObject): String {
        val cmd = a.optString("command")
        if (cmd.isNotBlank() && name in setOf("run_shell", "termux_run")) return "$ " + cmd.take(400)
        val body = a.keys().asSequence().joinToString(", ") { k -> "$k=" + a.opt(k).toString().replace("\n", " ").take(120) }
        return "$name($body)".take(500)
    }
}
