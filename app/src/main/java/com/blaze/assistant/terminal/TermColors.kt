package com.blaze.assistant.terminal

import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle

/**
 * v0.45.8: puts the theme's terminal palette ([com.blaze.assistant.ui.TermPalette]) into the emulator. The scheme's
 * defaults are what every new tab (and a `reset`) starts from; open tabs are reset to them so the switch shows at once.
 */
object TermColors {
    fun apply(p: com.blaze.assistant.ui.TermPalette.Colors) {
        try {
            val d = TerminalColors.COLOR_SCHEME.mDefaultColors
            for (i in 0 until 16) d[i] = p.ansi[i]
            d[TextStyle.COLOR_INDEX_FOREGROUND] = p.fg
            d[TextStyle.COLOR_INDEX_BACKGROUND] = p.bg
            d[TextStyle.COLOR_INDEX_CURSOR] = p.cursor
            val tabs = synchronized(TermSessions.tabs) { TermSessions.tabs.toList() }
            for (t in tabs) try { t.session.emulator?.mColors?.reset() } catch (_: Throwable) { }
        } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("TermColors.apply", t) }
    }
}
