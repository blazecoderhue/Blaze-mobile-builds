package com.blaze.assistant.browser.phone

/**
 * 0.45.19 Blaze Phone UI fixes, the pure part (PhoneUiRulesTest, PhonePipTest): when the chat tile shows, what its chip
 * says, which buttons the full screen offers, and when the picture-in-picture window follows the user out of Blaze.
 */
object PhoneUiRules {
    /** The tile stays this long after Blaze's last step on Blaze Phone. */
    const val RECENT_MS = 120_000L

    /** Shown while Blaze works there (a step in the last [RECENT_MS]), while the user drives, and always while it waits. */
    fun showTile(mode: PhonePolicy.Mode, at: Long, now: Long, hiddenAt: Long): Boolean {
        val needsUser = mode == PhonePolicy.Mode.WAITING
        val recent = mode == PhonePolicy.Mode.AGENT && now - at < RECENT_MS
        return (needsUser || recent || mode == PhonePolicy.Mode.USER) && (needsUser || hiddenAt < at)
    }

    fun host(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')

    fun pill(mode: PhonePolicy.Mode, step: String, reason: String?): String = when (mode) {
        PhonePolicy.Mode.WAITING -> "Blaze needs you: " + (reason ?: "take control")
        PhonePolicy.Mode.USER -> "You're in control"
        PhonePolicy.Mode.OFF -> step.ifBlank { "Idle" }
        else -> step.ifBlank { "Browsing" }
    }

    /**
     * Fix 6: the full screen's bottom-right button. Watching: "Take control". In control: only "Hand back" (the old
     * "Done" is gone); the user can also just close the screen (back / the top-left arrow), which hands back too.
     */
    fun buttons(control: Boolean): List<String> = if (control) listOf(HAND_BACK) else listOf(TAKE_CONTROL)
    const val HAND_BACK = "Hand back"
    const val TAKE_CONTROL = "Take control"

    /** The bar text while the user drives. */
    fun controlLine(agentWaiting: Boolean): String = if (agentWaiting) "Blaze paused, you're in control" else "You're in control"
}

/**
 * 0.45.19 fix 4: Blaze Phone's picture-in-picture. When the user leaves Blaze while Blaze works on Blaze Phone, the live
 * view follows as a floating window (like a video) with Stop and Open Blaze; tapping it (expand) returns to Blaze.
 */
object PhonePip {
    const val ACTION_STOP = "com.blaze.assistant.phone.PIP_STOP"
    const val ACTION_OPEN = "com.blaze.assistant.phone.PIP_OPEN"
    const val REQ_STOP = 7101
    const val REQ_OPEN = 7102

    /** Blaze is working on Blaze Phone right now: a turn that used phone tools is still running (or waits for the user). */
    fun working(mode: PhonePolicy.Mode, ownerChat: String?, busyChats: Set<String>): Boolean =
        (mode == PhonePolicy.Mode.AGENT || mode == PhonePolicy.Mode.WAITING) && ownerChat != null && ownerChat in busyChats

    /** Whether leaving Blaze should open the picture-in-picture window at all (never over a locked Blaze). */
    fun wanted(working: Boolean, pipFeature: Boolean, sdk: Int, locked: Boolean): Boolean =
        working && pipFeature && sdk >= 26 && !locked

    /** API 31+: the system enters it by itself (setAutoEnterEnabled); below, Blaze enters it on onUserLeaveHint. */
    fun autoEnter(sdk: Int): Boolean = sdk >= 31
    fun enterOnLeaveHint(sdk: Int, wanted: Boolean, launchingOwnScreen: Boolean): Boolean = sdk in 26..30 && wanted && !launchingOwnScreen

    /**
     * Leaving because Blaze opened one of its own screens (the Blaze Phone screen, the browser, settings pages in other
     * activities) is not "leaving Blaze": those starts carry FLAG_ACTIVITY_NO_USER_ACTION so neither path fires.
     */
    fun ownScreen(targetPackage: String?, ourPackage: String): Boolean = targetPackage != null && targetPackage == ourPackage

    /** Android's allowed range is 1:2.39 to 2.39:1; a phone page is taller, so it is clamped. */
    fun aspect(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 9 to 16
        val r = w.toDouble() / h
        val min = 1 / 2.39; val max = 2.39
        return when {
            r < min -> 100 to 239
            r > max -> 239 to 100
            else -> w to h
        }
    }

    /** The window's buttons, in order: Stop while a turn runs, and Open Blaze always. */
    fun actions(working: Boolean): List<String> = if (working) listOf(ACTION_STOP, ACTION_OPEN) else listOf(ACTION_OPEN)
    fun title(action: String): String = if (action == ACTION_STOP) "Stop" else "Open Blaze"

    /** The one line drawn over the live view in the window (null = just the page). */
    fun label(working: Boolean, mode: PhonePolicy.Mode, step: String): String? = when {
        mode == PhonePolicy.Mode.WAITING -> "Blaze needs you"
        step == "Stopped" -> "Stopped"
        !working -> "Done"
        else -> null
    }
}
