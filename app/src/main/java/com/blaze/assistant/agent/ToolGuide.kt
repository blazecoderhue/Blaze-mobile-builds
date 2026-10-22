package com.blaze.assistant.agent

import org.json.JSONArray

/**
 * One line per easily-confused tool saying when NOT to use it and what to use instead, appended
 * to the tool's description at send time (so every tool family stays in one place). Plus the
 * single place that turns a crashed or empty tool result into a plain error with a fix.
 */
object ToolGuide {
    private val notFor = mapOf(
        // Web
        "web_search" to "Not for a page you already have the URL of (fetch_page) or calling an API (http_request).",
        "fetch_page" to "Reads a web page as text. Not for JSON APIs or POST requests (http_request), nor sites that need a login or clicks (browser_open).",
        "http_request" to "For APIs (JSON, headers, POST). Not for reading articles (fetch_page) or logging in to a site (browser_open).",
        "browser_open" to "For sites needing login, clicks or forms. For just reading a public page, fetch_page is faster.",
        "open_url" to "Opens the URL in the phone's own browser for the user to see. To read it yourself use fetch_page or browser_open.",
        // Terminal & files
        "run_shell" to "Not for writing files (file_write) or reading them (file_read); not for Python/Node snippets (run_code).",
        "run_code" to "For short programs and calculations. For long-running servers use run_shell background=true.",
        "file_write" to "Not for files the user should open (write here, then export_file).",
        "termux_run" to "Only when Termux is needed; Blaze's own Linux (run_shell linux=true, run_code, git) needs no setup and returns output.",
        "linux_install" to "Rarely needed: run_code and git install Linux automatically.",
        "download_file" to "For files to keep. For API responses use http_request.",
        // Scheduling
        "create_routine" to "Runs a PROMPT through the AI at a time. For a plain command or script on a schedule use schedule_command; for 'when X happens' use create_trigger.",
        "schedule_command" to "Runs a SHELL command with no AI. For anything needing judgement use create_routine.",
        "create_trigger" to "For events (when a message/notification/app/battery change happens). For clock times use create_routine.",
        "set_alarm" to "A ringing alarm in the Clock app. For Blaze doing something at a time use create_routine.",
        "add_calendar_event" to "Phone calendar. For Google Calendar specifically use gcal_create.",
        "gcal_create" to "Google Calendar via the connected account. For the phone's own calendar use add_calendar_event.",
        // Messaging
        "send_sms" to "Plain SMS only. For WhatsApp/Telegram or pictures use message_contact.",
        "send_text" to "Plain SMS (quick lane). For WhatsApp/Telegram or pictures use message_contact.",
        "message_contact" to "WhatsApp/Telegram/SMS to a person, text and/or a picture (image=\"last\" after generate_image). Finds the contact itself; several matches come back as options to ask about. Only a 'Sent to' result means sent.",
        "compose_sms" to "Opens a filled-in SMS for the user to send themselves. To send directly use send_sms.",
        "find_contact" to "Phone contacts (the same ones WhatsApp uses; emoji names and small typos match). Search with the shortest part of the name. For Google contacts use gcontacts_search; to add or change one use add_contact/edit_contact.",
        // Screen
        "read_screen" to "Text of the current screen. Use look_at_screen only when text isn't enough (images, charts, games).",
        "look_at_screen" to "Costs a screenshot; prefer read_screen.",
        "tap" to "For several steps in a row use do_actions or run_plan.",
        "open_app" to "To open an app AND search inside it use search_in_app.",
        // Memory
        "remember" to "For lasting facts about the user, not one-off task details.",
        "recall_memory" to "Facts the user told you. For earlier conversations use search_past_chats.",
    )

    /** Append the hint to each tool's description, once. */
    fun apply(tools: JSONArray): JSONArray {
        for (i in 0 until tools.length()) {
            val f = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
            val hint = notFor[f.optString("name")] ?: continue
            val d = f.optString("description")
            if (!d.contains(hint)) f.put("description", "$d $hint")
        }
        return tools
    }

    /** A crash turned into something the model can act on. */
    fun crash(name: String, e: Throwable): String {
        val msg = e.message?.take(200) ?: e.javaClass.simpleName
        val fix = when (e) {
            is SecurityException -> "A permission is missing. Run self_test to see which, then tell the user how to allow it."
            is java.net.UnknownHostException, is java.net.ConnectException -> "No internet connection or the server is down. Check with self_test, then retry once."
            is java.net.SocketTimeoutException -> "The server was too slow. Retry once, then tell the user."
            is org.json.JSONException -> "A required argument was missing or the wrong type. Check the tool's parameters and call it again."
            is NumberFormatException -> "A number argument wasn't a number. Call it again with a plain number."
            is IllegalArgumentException, is IllegalStateException -> "The arguments didn't make sense for this tool. Re-read its description and try again."
            is OutOfMemoryError -> "The phone ran low on memory. Use a smaller input."
            else -> "Try a different approach or tool; if it keeps failing, run self_test."
        }
        return "Error in $name: $msg\nFix: $fix"
    }

    fun empty(name: String) = "$name finished but returned nothing. If you expected output, check the arguments; otherwise treat it as done."
}
