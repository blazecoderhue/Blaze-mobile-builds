package com.blaze.assistant.ui

import android.content.Context
import android.widget.Toast
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Command
import com.blaze.assistant.agent.Commands
import com.blaze.assistant.agent.Memory
import com.blaze.assistant.agent.ModelCatalog
import com.blaze.assistant.agent.Personas
import com.blaze.assistant.agent.Providers
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.agent.Usage

/** A suggestion shown above the input while typing a /command. [fill] replaces the input text. */
data class Suggestion(val title: String, val sub: String, val fill: String, val runNow: Boolean)

object CommandRunner {
    /** Suggestions for what's typed, or empty when it isn't a command. */
    fun suggest(text: String): List<Suggestion> {
        if (!text.startsWith("/")) return emptyList()
        val name = text.removePrefix("/").substringBefore(' ').lowercase()
        val hasSpace = text.contains(' ')
        val arg = text.substringAfter(' ', "").trim().lowercase()
        if (hasSpace) {
            return when (name) {
                "persona" -> Personas.all.filter { it.id != Personas.CUSTOM && (arg.isEmpty() || it.name.lowercase().contains(arg) || it.id.contains(arg)) }
                    .map { Suggestion(it.name, it.blurb, "/persona ${it.id}", true) }
                else -> emptyList()
            }
        }
        return Commands.matching(text).take(30).map { c ->
            val needsArg = c.args.startsWith("<")
            Suggestion("/" + c.name + if (c.args.isNotEmpty()) " " + c.args else "", c.desc,
                "/" + c.name + if (c.args.isNotEmpty()) " " else "", runNow = c.args.isEmpty())
        }
    }

    /**
     * Run a typed command. Returns the text to send to Blaze instead (for prompt commands),
     * "" when the command was handled here, or null when it isn't a command.
     * [openSettings] opens a settings page by id ("" = list). The chat screen handles /clear itself (with undo);
     * here it clears without one (the floating orb).
     */
    fun run(ctx: Context, text: String, openSettings: (String) -> Unit, listen: () -> Unit): String? {
        if (!text.startsWith("/") || text.startsWith("//")) return null
        val name = text.removePrefix("/").substringBefore(' ').lowercase()
        val arg = text.substringAfter(' ', "").trim()
        fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
        when (name) {
            // v0.45.13 hidden QA entry (not listed in /help): the strict phone-control self-test, 5 runs per case by default.
            // "/phoneqa" · "/phoneqa 1" · "/phoneqa 5 alarm-set,alarm-off" · "/phoneqa cases"
            "phoneqa" -> {
                val parts = arg.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (parts.firstOrNull() == "cases") Agent.note(com.blaze.assistant.agent.PhoneCases.ALL.joinToString("\n") { "${it.id}: ${it.prompt} \u2192 reads ${it.reads}" })
                else {
                    val n = parts.firstOrNull()?.toIntOrNull() ?: 5
                    val only = parts.drop(if (parts.firstOrNull()?.toIntOrNull() != null) 1 else 0).joinToString(",").split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    com.blaze.assistant.phone.PhoneQa.start(ctx, n, only)
                }
            }
            "new" -> Agent.clear()
            "clear" -> if (Agent.clearScreen() == com.blaze.assistant.agent.ClearScreen.Result.BUSY) toast("Blaze is working. Stop it first, then /clear.")
            // v0.25.0: no model or provider choice; old habits get one plain line.
            "model", "provider" -> Agent.note("Blaze picks the best free model for every message by itself. There's nothing to choose.")
            "persona" -> {
                val p = Personas.all.firstOrNull { it.id.equals(arg, true) || it.name.equals(arg, true) }
                if (p == null) openSettings("persona") else { Settings.personaId = p.id; Agent.note("Personality: ${p.name}") }
            }
            // v0.24.0: no chat list any more; one conversation, and Blaze looks older words up.
            "chats", "history", "archive" -> Agent.note("There's one conversation now, so there's no chat list. Ask me about anything you said before and I'll look it up.", asReply = true)
            "settings" -> openSettings("")
            "retry" -> Agent.retryLast()
            "update", "upgrade" -> com.blaze.assistant.update.Updater.start(ctx)
            "stop" -> Agent.stop()
            "plan" -> { com.blaze.assistant.agent.Modes.setWork("plan"); Agent.note("Plan mode: I'll only read and look, then give you a plan. /build to let me act.")
                if (arg.isNotEmpty()) return arg }
            "build" -> { com.blaze.assistant.agent.Modes.setWork("build"); Agent.note("Build mode: full access.") }
            // v0.39.0 Simple / Advanced (only what's listed changes; every command keeps working).
            "simple" -> { com.blaze.assistant.agent.SimpleMode.set(true); Agent.note("Simple mode: technical tools are tucked away in Settings \u203a Advanced. Nothing was removed.") }
            "advanced" -> { com.blaze.assistant.agent.SimpleMode.set(false); Agent.note("Advanced mode: every tool and setting is showing.") }
            "doing" -> openSettings("doing")
            // v0.43.0 Bank mode: /bank (on / off / toggle; plain /bank opens its page when it's off and nothing was said).
            "bank", "bankmode" -> when (arg.trim().lowercase()) {
                "on" -> Agent.note(com.blaze.assistant.bank.BankMode.turnOn(ctx))
                "off" -> Agent.note(com.blaze.assistant.bank.BankMode.turnOff(ctx))
                "" -> Agent.note(if (com.blaze.assistant.bank.BankMode.active) com.blaze.assistant.bank.BankMode.turnOff(ctx) else com.blaze.assistant.bank.BankMode.turnOn(ctx))
                else -> openSettings("bank")
            }
            "look", "theme" -> openSettings("look")
            "sync" -> openSettings("sync")
            "linux" -> openSettings("linux")
            "autopilot" -> { com.blaze.assistant.agent.Modes.setApproval("autopilot")
                Agent.note("Autopilot on: I keep going without asking, up to ${com.blaze.assistant.agent.Modes.apSteps} steps or ${com.blaze.assistant.agent.Modes.apMinutes} min. Deleting, paying and messaging people still need your tap.") }
            "auto" -> { com.blaze.assistant.agent.Modes.setApproval("auto"); Agent.note("Auto: I'll ask before risky actions.") }
            "agent" -> if (arg.isEmpty()) toast("Type the task after /agent") else Agent.startAgentFromUser(arg)
            "agents" -> Agent.note(com.blaze.assistant.agent.BgAgents.run("list_agents", org.json.JSONObject(), ""), asReply = true)
            "remember" -> if (arg.isNotEmpty()) { Memory.add(arg); Agent.note("Remembered: $arg") } else openSettings("memory")
            "forget" -> if (arg.isNotEmpty()) Agent.note(Memory.forget(arg)) else openSettings("memory")
            "memory" -> openSettings("memory")
            "routines" -> openSettings("routines")
            "usage", "cost" -> {
                val t = Usage.today(); val m = Usage.month()
                fun f(x: Usage.Totals) = "${x.input + x.output} tokens, ${x.calls} calls" + if (x.priced) ", about $%.3f".format(x.cost) else ""
                Agent.note("Today: ${f(t)}\nThis month: ${f(m)}", asReply = true)
            }
            "think", "reasoning" -> { Settings.showReasoning = !Settings.showReasoning; toast("Thinking line " + if (Settings.showReasoning) "shown" else "hidden") }
            "voice", "listen" -> listen()
            "orb" -> if (!Overlay.showOrb(ctx)) Overlay.requestOverlayPermission(ctx)
            "logs", "livelogs", "lifelogs" -> com.blaze.assistant.life.LifeLogs.toggleFromCommand(ctx, arg)
            "brainstorm" -> com.blaze.assistant.life.LifeLogs.toggleFromCommand(ctx, if (com.blaze.assistant.life.LifeLogs.active && arg.isBlank()) "stop" else "brainstorm")
            "copy" -> {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Blaze chat", Agent.transcript())); toast("Chat copied")
            }
            "help", "commands" -> Agent.note(Commands.all().joinToString("\n") { c ->
                "/" + c.name + (if (c.args.isNotEmpty()) " " + c.args else "") + " — " + c.desc }, asReply = true)
            else -> {
                val c: Command = Commands.all().firstOrNull { it.name == name && it.prompt != null } ?: return null
                return Commands.expand(c, arg)
            }
        }
        return ""
    }
}
