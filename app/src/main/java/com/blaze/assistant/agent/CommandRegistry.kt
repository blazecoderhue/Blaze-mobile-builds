package com.blaze.assistant.agent

import com.blaze.assistant.ui.Views
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.39.0: Blaze knows every view and every / command and can run them itself (the `blaze_command` tool), in Simple
 * and Advanced alike. A command that names a view opens it (open_view rules); a prompt command gives Blaze its prompt
 * to follow; a few app commands run as if typed. Commands that would cut Blaze's own turn short (/stop, /new, /clear,
 * /retry) are left to the user. Pure except [tool] (CommandRegistryTest).
 */
object CommandRegistry {
    const val TOOL = "blaze_command"

    enum class Kind { VIEW, PROMPT, ACTION, USER_ONLY, UNKNOWN }

    /** Commands only the user runs: they would end or wipe the turn Blaze is in. */
    val USER_ONLY = setOf("stop", "new", "clear", "retry", "copy", "update", "search", "help")

    /** App commands Blaze may run as if typed (mode switches, voice, the orb, opening pages). */
    val ACTIONS = setOf("plan", "build", "autopilot", "auto", "think", "voice", "orb", "views", "settings", "routines", "memory", "usage",
        "persona", "agents", "remember", "forget", "logs", "brainstorm", "agent", "simple", "advanced", "bank")

    fun kind(name: String, all: List<Command> = Commands.all()): Kind {
        val n = name.trim().removePrefix("/").lowercase()
        val c = all.firstOrNull { it.name == n }
        return when {
            n in USER_ONLY -> Kind.USER_ONLY
            c?.prompt != null -> Kind.PROMPT
            Views.find(n) != null && n !in ACTIONS -> Kind.VIEW
            n in ACTIONS -> Kind.ACTION
            c == null && Views.find(n) != null -> Kind.VIEW
            else -> Kind.UNKNOWN
        }
    }

    /** Every / command and every view, one line each, for blaze_now and the prompt docs. */
    fun catalog(all: List<Command> = Commands.all()): String = buildString {
        append("## Slash commands (the user types them; you run them with ").append(TOOL).append(")\n")
        all.distinctBy { it.name }.forEach { c ->
            append("- /").append(c.name)
            if (c.args.isNotBlank()) append(' ').append(c.args)
            append(": ").append(c.desc)
            when (kind(c.name, all)) { Kind.USER_ONLY -> append(" (user only)"); Kind.VIEW -> append(" (opens a view)"); else -> {} }
            append('\n')
        }
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Run one of Blaze's own / commands as if the user typed it: a view (/music, /gallery, /schedule, /doing, /look …) opens it, " +
            "a prompt command (/summarize, /translate, /brief …) gives you its prompt to follow, an app command (/autopilot, /voice, /orb, /plan …) runs it. " +
            "/stop, /new, /clear and /retry are for the user only. The full list is in blaze_now.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("command", JSONObject().put("type", "string").put("description", "The command without or with the slash, e.g. music or /translate"))
            .put("input", JSONObject().put("type", "string").put("description", "What follows the command, if anything")))
            .put("required", JSONArray(listOf("command")))))
}
