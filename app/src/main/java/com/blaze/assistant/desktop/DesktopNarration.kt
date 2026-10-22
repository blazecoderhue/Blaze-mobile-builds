package com.blaze.assistant.desktop

import org.json.JSONObject

/**
 * v0.25.5 live desktop card, pure part (DesktopNarrationTest): the short plain-language pill for each `desktop`
 * tool action ("Clicking Save", "Typing into Terminal", "Opening Files", "Taking a look"), and when the card shows.
 *
 * The card follows one stretch of desktop work: it appears when the agent starts acting on the desktop, stays up
 * between actions while the agent decides what to do next, and hides [HIDE_AFTER_MS] after the agent's run ends
 * (or after [IDLE_HIDE_MS] with no desktop action, when the agent moved on to something else). The X stops the
 * agent's desktop work for the rest of that run.
 */
object DesktopNarration {
    /** Hide this long after the desktop work finishes. */
    const val HIDE_AFTER_MS = 4_000L
    /** No desktop action for this long while the agent is still busy: it moved on, so the card goes. */
    const val IDLE_HIDE_MS = 45_000L
    /** Between actions, after this long the pill says the agent is deciding the next step. */
    const val THINKING_AFTER_MS = 1_500L
    const val THINKING = "Deciding what to do next"
    private const val MAX = 34

    /** The pill for one tool call, or null for calls that don't touch the desktop (status, open). */
    fun status(args: JSONObject, installed: Boolean = true): String? {
        val label = clean(args.optString("label"))
        return when (args.optString("action")) {
            "screenshot" -> "Taking a look"
            "click" -> {
                val verb = when {
                    args.optBoolean("double") -> "Double-clicking"
                    args.optInt("button", 1) == 3 -> "Right-clicking"
                    else -> "Clicking"
                }
                if (label.isNotEmpty()) "$verb $label" else verb
            }
            "type" -> if (label.isNotEmpty()) "Typing into $label" else "Typing"
            "key" -> "Pressing " + keys(args.optString("keys")).ifEmpty { "a key" }
            "run" -> "Opening " + appName(args.optString("app"))
            "start" -> if (installed) "Starting the desktop" else "Setting up the desktop"
            "stop" -> "Stopping the desktop"
            else -> null
        }?.let { cap(it) }
    }

    /** Friendly app names for `run` ("files" → Files, "netsurf" → the browser, anything else → its first word). */
    fun appName(app: String): String = when (app.trim().lowercase()) {
        "terminal", "xfce4-terminal" -> "Terminal"
        "files", "file manager", "thunar" -> "Files"
        "browser", "web", "netsurf", "netsurf-gtk3", "dillo" -> "the browser"
        "editor", "text editor", "mousepad" -> "Text Editor"
        "" -> "an app"
        else -> app.trim().substringBefore(' ').substringAfterLast('/').take(24).ifEmpty { "an app" }
    }

    private val keyNames = mapOf("return" to "Enter", "kp_enter" to "Enter", "escape" to "Esc", "backspace" to "Backspace",
        "ctrl" to "Ctrl", "control" to "Ctrl", "alt" to "Alt", "shift" to "Shift", "super" to "Super", "tab" to "Tab",
        "delete" to "Delete", "space" to "Space", "up" to "Up", "down" to "Down", "left" to "Left", "right" to "Right",
        "home" to "Home", "end" to "End", "page_up" to "Page Up", "page_down" to "Page Down", "prior" to "Page Up", "next" to "Page Down")

    /** xdotool names to what's printed on the keys: "ctrl+s Return" → "Ctrl+S, Enter" (only plain names, like [DesktopPlan.keyCmd]). */
    fun keys(k: String): String = k.substringBefore(';').substringBefore('&').substringBefore('|').split(' ', ',')
        .filter { it.matches(Regex("[A-Za-z0-9_+]{1,40}")) }.take(3)
        .joinToString(", ") { combo -> combo.split('+').filter { it.isNotEmpty() }.joinToString("+") { p ->
            keyNames[p.lowercase()] ?: if (p.length == 1) p.uppercase() else p.replaceFirstChar { it.uppercase() }
        } }

    /** A short label from the agent ("the Save button" → "Save"), plain letters only. */
    fun clean(s: String): String {
        var t = s.lineSequence().firstOrNull().orEmpty().trim().trim('"', '\'', '\u201c', '\u201d', '.')
        t = t.replace(Regex("(?i)^(the|a|an)\\s+"), "").replace(Regex("(?i)\\s+(button|icon|menu item|field|box|window)$"), "")
        t = t.filter { it.isLetterOrDigit() || it in " -_.:/'&+()" }.trim()
        return if (t.length > 22) t.take(21).trimEnd() + "\u2026" else t
    }

    private fun cap(s: String) = (if (s.length > MAX) s.take(MAX - 1).trimEnd() + "\u2026" else s).replaceFirstChar { it.uppercase() }

    // ---------- when the card shows ----------

    /**
     * One stretch of desktop work. [acting] while a tool call runs; [lastAt] when the last action started or ended;
     * [runEndedAt] when the agent's run finished (0 while it's still going); [stopped] after the user tapped X.
     * [x]/[y] are the last click in desktop pixels (-1 when none), for the pointer ring on the mini view.
     */
    data class Live(
        val active: Boolean = false, val acting: Boolean = false, val status: String = "", val lastAt: Long = 0L,
        val runEndedAt: Long = 0L, val stopped: Boolean = false, val x: Int = -1, val y: Int = -1,
    ) {
        fun begin(status: String, now: Long, x: Int = -1, y: Int = -1): Live =
            if (stopped) this else copy(active = true, acting = true, status = status, lastAt = now, runEndedAt = 0L,
                x = if (x >= 0) x else this.x, y = if (y >= 0) y else this.y)
        fun end(now: Long): Live = if (!active) this else copy(acting = false, lastAt = now)
        /** The agent's run finished: the card hides [HIDE_AFTER_MS] later, and a stop applies only to that run. */
        fun runEnded(now: Long): Live = copy(acting = false, runEndedAt = if (active && runEndedAt == 0L) now else runEndedAt, stopped = false)
        fun stop(): Live = Live(stopped = true)

        fun show(now: Long): Boolean = active && !stopped && when {
            acting -> true
            runEndedAt > 0L -> now - runEndedAt < HIDE_AFTER_MS
            else -> now - lastAt < IDLE_HIDE_MS
        }

        /** The words on the pill right now. */
        fun pill(now: Long): String = when {
            acting -> status
            runEndedAt > 0L -> "Done"
            now - lastAt >= THINKING_AFTER_MS -> THINKING
            else -> status
        }

        /** Fully gone (so the state can reset). */
        fun expired(now: Long): Boolean = active && !show(now) && !acting
    }
}
