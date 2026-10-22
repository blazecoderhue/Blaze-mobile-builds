package com.blaze.assistant.notify

/**
 * v0.24.4: the one ongoing "what Blaze is doing" notification. Pure state → line so it can be tested
 * without Android. Priority: something that needs the user, then agent work, then an update download.
 * When agent work and a download run together the download rides along in the text.
 */
object StatusNote {
    data class Input(
        val busy: Boolean = false,
        val step: String = "",
        val needs: String = "",
        /** Update download percent (0..100), -1 = indeterminate (checking / installing), null = no update running. */
        val updatePct: Int? = null,
        val updateLabel: String = "",
    )

    data class Line(
        val title: String,
        val text: String,
        /** 0..100 determinate, -1 indeterminate, null = no progress bar. */
        val progress: Int?,
        /** A Stop button is shown (it cancels the agent work and any update download). */
        val stop: Boolean,
    )

    private val browse = Regex("\\b(brows\\w*|web|site|page|url|https?://|search\\w*|google|tab|open(ing)? [\\w.-]+\\.(com|org|net|io|co))\\b", RegexOption.IGNORE_CASE)
    private val download = Regex("\\b(download\\w*|install\\w*)\\b", RegexOption.IGNORE_CASE)

    /** The short verb for a step line: "Browsing…", "Downloading…" or "Thinking…". */
    fun verb(step: String): String = when {
        step.isBlank() -> "Thinking…"
        download.containsMatchIn(step) -> "Downloading…"
        browse.containsMatchIn(step) -> "Browsing…"
        else -> "Thinking…"
    }

    fun updateTitle(pct: Int?, label: String): String = when {
        pct == null -> ""
        pct >= 0 -> "Downloading update $pct%"
        label.isNotBlank() -> label
        else -> "Updating Blaze…"
    }

    /** Null = nothing is running: the notification goes away. */
    fun line(i: Input): Line? {
        val upd = updateTitle(i.updatePct, i.updateLabel)
        val step = i.step.trim()
        if (i.needs.isNotBlank()) {
            return Line("Needs you: " + i.needs.trim().take(80), listOf(if (i.busy) step else "", upd).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Tap to answer" }, null, stop = true)
        }
        if (i.busy) {
            val text = listOf(step, upd).filter { it.isNotBlank() }.joinToString(" · ")
            return Line(verb(step), text, -1, stop = true)
        }
        if (i.updatePct != null) return Line(upd, i.updateLabel.takeIf { i.updatePct >= 0 }.orEmpty(), i.updatePct, stop = true)
        return null
    }
}
