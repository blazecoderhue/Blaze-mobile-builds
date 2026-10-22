package com.blaze.assistant.agent

/**
 * v0.45.2 desktop live view routing. On a phone with the Linux desktop running, "open the browser in it" made Blaze
 * run `apk add firefox` with run_shell, and earlier it told the user to connect a VNC client to localhost:5901. Cause:
 * the `desktop` and `desktop_task` tools were in no ToolRouter group, so they fell into "more" (sent only on request)
 * while "browser" / "linux" / "install" turned on the browser and terminal groups. Nothing told the model the desktop
 * agent (with its live card in chat) existed for this turn.
 *
 * Now the "desktop" group holds both tools; a request about the Linux desktop (or a GUI verb aimed at "it / there"
 * while the desktop runs or was just talked about) turns it on, ToolPick keeps both past its cap, and [HINT] steers the
 * turn to desktop_task. Pure; DesktopRouteTest.
 */
object DesktopRoute {
    const val GROUP = "desktop"
    const val TASK = "desktop_task"
    const val TOOL = "desktop"
    val TOOLS = setOf(TASK, TOOL)

    private val ic = RegexOption.IGNORE_CASE
    /** Words that name the Linux desktop itself. */
    private val NAMES = Regex("""\b(linux desktop|desktop linux|linux (?:pc|computer|machine|box|vm)|xfce|vnc|the desktop|(?:on|in|inside|into) (?:the |my )?desktop|my desktop|desktop view|view (?:the )?desktop|alpine desktop|ubuntu desktop|debian desktop)\b""", ic)
    /** Things a person does on a computer screen. */
    private val GUI_VERB = Regex("""\b(open|launch|start|run|click|tap|type|browse|go to|visit|search|show|close|use|fill|install|download|play|watch|scroll|log ?in|sign ?in|write|edit|save)\b""", ic)
    /** "in it / there": the desktop the user just talked about. */
    private val THERE = Regex("""\b(in it|on it|inside it|into it|in there|on there|over there|there|in linux|on linux|inside linux|in the linux|on the linux)\b""", ic)
    /** The user clearly means the phone, not the desktop. */
    private val PHONE = Regex("""\b(on (?:my|the) phone|phone's|my android|android settings|blaze desktop|my (?:pc|laptop|mac|windows))\b""", ic)

    /** Recent chat lines mention the Linux desktop (so "it" can mean it). */
    fun mentioned(recent: String): Boolean = Regex("""\b(linux desktop|xfce|the desktop|desktop (?:is )?running|desktop_task)\b""", ic).containsMatchIn(recent)

    /**
     * True when this request should go to the desktop agent: it names the Linux desktop, or it's a GUI action aimed at
     * "it / there" while the desktop runs ([desktopRunning]) or was just discussed ([recentDesktop]).
     */
    fun wants(text: String, desktopRunning: Boolean, recentDesktop: Boolean): Boolean {
        if (text.isBlank() || PHONE.containsMatchIn(text)) return false
        if (NAMES.containsMatchIn(text)) return true
        return (desktopRunning || recentDesktop) && GUI_VERB.containsMatchIn(text) && THERE.containsMatchIn(text)
    }

    /** The turn hint when [wants] is true. */
    const val HINT = "\nLINUX DESKTOP: this request is about Blaze's Linux desktop on this phone. To do anything on it (open an app or the browser in it, " +
        "click, type, browse a site, fill in a form), call desktop_task with the goal in plain words: it starts the desktop if needed and shows a live, " +
        "watch-only view card in this chat while it works. Don't install or launch desktop apps with run_shell for this (desktop_task can install a " +
        "missing app inside the desktop itself). Never tell the user to use a VNC client, localhost or a port number: they watch it in the live card, " +
        "and open_view linux opens the full Desktop view (the \"View desktop\" button).\n"

    /** Words the model must never hand the user about the desktop (the app shows the view itself). */
    private val VNC_ADVICE = Regex("""(?i)(vnc (?:client|viewer|app)|connect (?:with|using|via) (?:a |an )?vnc|localhost:59\d\d|127\.0\.0\.1:59\d\d)""")

    /** True when a reply tells the user to connect a VNC client / port themselves. */
    fun vncAdvice(reply: String): Boolean = VNC_ADVICE.containsMatchIn(reply)

    /** The reply when the model still sent the user to a VNC client after the nudge. */
    const val NO_VNC_LINE = "I can show the Linux desktop right here in Blaze, no other app needed. Tap View desktop below to watch it, or tell me what to do in it and you'll see it live in this chat."

    /** The nudge when a reply did that (one retry, like the other claim nudges). */
    const val VNC_NUDGE = "[Blaze shows the Linux desktop itself: don't tell the user to use a VNC client, localhost or a port. " +
        "Use desktop_task to do it while they watch the live card, or open_view linux to show the Desktop view. Answer again.]"
}
