package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v0.39.0 Simple / Advanced. Simple (the default, new installs and upgrades alike) only HIDES technical things:
 * Terminal and Jobs, MCP servers, BlazeRouter and the brain / model pickers, and developer pages (usage meters,
 * dashboard, prompt commands, skill library, sub-agents). Nothing is removed: every view still opens from its
 * /command and Blaze can still open it with open_view; Advanced shows everything as before.
 *
 * Pref `ui_mode` = "simple" | "advanced". The rules are pure (SimpleModeTest).
 */
object SimpleMode {
    const val PREF = "ui_mode"
    const val SIMPLE = "simple"
    const val ADVANCED = "advanced"

    /** Views (ui/Views ids) that only show in Advanced lists. */
    val TECH_VIEWS = setOf("terminal", "jobs", "router", "brain", "dashboard", "usage", "commands", "skills", "agents")

    /** Settings pages (SettingsSheet ids) that only show in Advanced lists. */
    val TECH_PAGES = setOf("ai", "terminal", "dashboard", "usage", "commands", "skills", "specialists")

    /** + menu entries that only show in Advanced (Plan / Build is a developer switch; Autopilot stays visible). */
    val TECH_PLUS = setOf("plan", "atfile")

    /** The line under the switch in Settings: what Simple keeps out of sight. */
    const val HIDDEN_LINE = "Terminal, MCP, BlazeRouter, AI models and developer settings"

    /** What a saved value means: Simple unless "advanced" was chosen (pure). */
    fun of(saved: String?): String = if (saved?.trim()?.lowercase() == ADVANCED) ADVANCED else SIMPLE

    fun viewVisible(id: String, simple: Boolean): Boolean = !simple || id !in TECH_VIEWS
    fun pageVisible(id: String, simple: Boolean): Boolean = !simple || id !in TECH_PAGES
    fun plusVisible(id: String, simple: Boolean): Boolean = !simple || id !in TECH_PLUS

    /** MCP servers live on the Connected accounts page; Simple shows the accounts and hides the MCP section. */
    fun showMcp(simple: Boolean): Boolean = !simple

    /** Slash commands always work in both modes (the text field runs every one); only lists are filtered. */
    fun commandRuns(@Suppress("UNUSED_PARAMETER") name: String, @Suppress("UNUSED_PARAMETER") simple: Boolean): Boolean = true

    /** v0.41.0: how a turn's work steps show in the chat. */
    enum class Work { CARD, HINT, NONE }

    /**
     * Simple mode keeps the chat clean: while Blaze works, one small quiet line; when it's done, nothing extra (the
     * answer is the point). Advanced, or Settings › Show every step, keeps the full step card with Details.
     */
    fun work(simple: Boolean, showSteps: Boolean, live: Boolean): Work = when {
        !simple || showSteps -> Work.CARD
        live -> Work.HINT
        else -> Work.NONE
    }

    /** The quiet line while working: the plain step, short, or "Working on it". */
    fun hint(status: String, lastStep: String): String {
        val t = status.ifBlank { lastStep }.lineSequence().firstOrNull().orEmpty().trim().trimEnd('.', '\u2026', ':')
        val short = if (t.length > 48) t.take(48).substringBeforeLast(' ').ifBlank { t.take(48) } else t
        return short.ifBlank { "Working on it" } + "\u2026"
    }

    private val _simple = MutableStateFlow(true)
    val simpleFlow: StateFlow<Boolean> = _simple

    /** Reads the saved mode (call after Settings.init). */
    fun load() { _simple.value = try { of(Settings.str(PREF, SIMPLE)) == SIMPLE } catch (_: Throwable) { true } }

    val simple: Boolean get() = _simple.value

    fun set(simple: Boolean) {
        try { Settings.setStr(PREF, if (simple) SIMPLE else ADVANCED) } catch (_: Throwable) { }
        _simple.value = simple
    }
}
