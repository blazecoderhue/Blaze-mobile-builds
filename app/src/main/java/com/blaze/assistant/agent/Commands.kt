package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Slash commands. [action] ones change the app (new chat, switch model); [prompt] ones are
 * reusable prompt "skills" where {input} is replaced by whatever you type after the command.
 */
data class Command(val name: String, val args: String, val desc: String,
                   val prompt: String? = null, val custom: Boolean = false)

object Commands {
    val actions = listOf(
        Command("clear", "", "Clear the screen (nothing is forgotten; undo for a few seconds)"),
        Command("new", "", "Fresh start: a new context window (in a project: a new thread)"),
        Command("persona", "<style>", "Change Blaze's personality"),
        Command("settings", "", "Open settings"),
        Command("retry", "", "Regenerate the last answer"),
        Command("stop", "", "Stop what Blaze is doing"),
        Command("update", "", "Update Blaze to the newest build"),
        Command("plan", "[goal]", "Plan mode: read and look only, then a plan"),
        Command("build", "", "Build mode: full access (default)"),
        Command("autopilot", "", "Autopilot: work on without asking (deletes, money and messages still ask)"),
        Command("auto", "", "Auto: ask before risky actions (default)"),
        Command("agent", "<task>", "Hand a task to a background agent"),
        Command("agents", "", "List background agents"),
        Command("remember", "<fact>", "Save something to memory"),
        Command("forget", "<fact>", "Remove something from memory"),
        Command("memory", "", "See what Blaze remembers"),
        Command("routines", "", "See and edit routines"),
        Command("usage", "", "Tokens and cost so far"),
        Command("think", "", "Turn the \u201cThinking it through\u201d line on/off"),
        Command("voice", "", "Start listening"),
        Command("orb", "", "Pin the floating orb"),
        Command("bank", "[on|off]", "Bank mode: Blaze steps back so banking apps work"),
        Command("copy", "", "Copy this whole chat"),
        Command("views", "", "Open any view: memory, jobs, design, accounts…"),
        Command("search", "", "Search views and past conversations"),
        Command("projects", "", "Your projects"),
        Command("design", "", "Design: live preview"),
        Command("jobs", "", "Jobs and the job log"),
        Command("terminal", "", "Open Blaze Terminal (Alpine or phone shell)"),
        Command("music", "", "Open Blaze Music (player, library, store)"),
        Command("video", "", "Open Blaze Video (player, library, store)"),
        Command("schedule", "", "Routines and timed tasks"),
        Command("accounts", "", "Connected apps"),
        Command("doctor", "", "Phone health and self-check"),
        Command("router", "", "BlazeRouter: models and keys"),
        Command("skills", "", "Skill library"),
        Command("gallery", "", "Things Blaze made"),
        Command("browser", "", "Blaze's browser"),
        Command("logs", "[on|off|pause|status]", "Life logs: transcribe what the mic hears into memory"),
        Command("brainstorm", "", "Think out loud; get ideas, decisions and next steps when you stop"),
        Command("home", "", "Show Home: weather, news, heads-ups"),
        Command("doing", "", "Things Blaze is doing: routines, reminders, today's diary"),
        Command("look", "", "Look & feel: living backgrounds, theme, accent"),
        Command("sync", "", "Cloud sync: chats, memory, settings and designs on all your phones (end-to-end encrypted)"),
        Command("linux", "", "Open the Linux desktop"),
        Command("simple", "", "Simple mode: hide technical tools (nothing is removed)"),
        Command("advanced", "", "Advanced mode: show every tool and setting"),
        Command("help", "", "List all commands")
    )

    private val builtInPrompts = listOf(
        Command("summarize", "[text]", "Summarise this chat or some text",
            "Summarise {input} in a few tight bullet points. If nothing is given, summarise our conversation so far."),
        Command("tldr", "<text>", "One-line gist", "Give me a one-sentence TL;DR of: {input}"),
        Command("explain", "<topic>", "Explain simply", "Explain this in plain words with a small example: {input}"),
        Command("eli5", "<topic>", "Explain like I'm five", "Explain this like I'm five: {input}"),
        Command("translate", "<lang> <text>", "Translate text", "Translate the following. The first word is the target language: {input}"),
        Command("fix", "<text>", "Fix grammar and spelling", "Fix the grammar and spelling, keep my tone, and return only the corrected text: {input}"),
        Command("rewrite", "<text>", "Rewrite clearer", "Rewrite this to be clearer and more natural. Return only the rewrite: {input}"),
        Command("email", "<what about>", "Draft an email", "Draft a short email with a subject line about: {input}"),
        Command("reply", "<message>", "Draft a reply", "Draft a friendly, natural reply to this message: {input}"),
        Command("brainstorm", "<topic>", "10 quick ideas", "Brainstorm 10 varied, specific ideas for: {input}"),
        Command("code", "<task>", "Write code", "Write clean, working code for this, with a one-line explanation: {input}"),
        Command("screen", "[question]", "Look at your screen", "Look at my screen (use look_at_screen) and help: {input}"),
        Command("brief", "", "Today's briefing", "Give me a short briefing: today's calendar, any unread texts or missed calls, and anything I asked you to remember for today. {input}")
    )

    fun custom(): List<Command> = try {
        val a = JSONArray(Settings.userCommands)
        (0 until a.length()).map { val o = a.getJSONObject(it)
            Command(o.getString("name"), "[input]", o.optString("desc").ifBlank { "Your command" }, o.getString("prompt"), custom = true) }
    } catch (_: Exception) { emptyList() }

    fun saveCustom(name: String, desc: String, prompt: String) {
        val n = name.trim().removePrefix("/").lowercase().replace(Regex("[^a-z0-9_-]"), "")
        if (n.isEmpty() || prompt.isBlank()) return
        val list = custom().filterNot { it.name == n } + Command(n, "", desc, prompt, true)
        Settings.userCommands = JSONArray(list.map { JSONObject().put("name", it.name).put("desc", it.desc).put("prompt", it.prompt) }).toString()
    }

    fun deleteCustom(name: String) {
        Settings.userCommands = JSONArray(custom().filterNot { it.name == name }
            .map { JSONObject().put("name", it.name).put("desc", it.desc).put("prompt", it.prompt) }).toString()
    }

    fun all(): List<Command> = custom() + actions + builtInPrompts

    /** Commands matching what's typed after "/". */
    fun matching(typed: String): List<Command> {
        val q = typed.removePrefix("/").substringBefore(' ').lowercase()
        return all().filter { it.name.startsWith(q) }.ifEmpty { all().filter { it.name.contains(q) } }
    }

    /** The prompt to send for a prompt command, with {input} filled in. */
    fun expand(c: Command, input: String): String {
        val p = c.prompt ?: return input
        return if (p.contains("{input}")) p.replace("{input}", input.trim()).trim() else (p + "\n\n" + input).trim()
    }
}
