package com.blaze.assistant.agent

/**
 * v0.45.6 (phone 21:29: "play music" ended with the screen-control overlay paused and nothing in the chat): every turn
 * ends with a visible message. When a turn finishes (done, stopped, timed out, a tool failed) and nothing the
 * user can read was added after their message, the watchdog adds one plain line saying what happened. Pure; TurnEndTest.
 */
object TurnEnd {
    enum class Reason { DONE, STOPPED, TIMEOUT, TOOL_FAILED, ERROR }

    /** True when no reply (BLAZE or ERROR) follows the user's last message in [roles] (oldest first). */
    fun silent(roles: List<Role>): Boolean {
        val lastUser = roles.indexOfLast { it == Role.USER }
        if (lastUser < 0) return false
        return roles.drop(lastUser + 1).none { it == Role.BLAZE || it == Role.ERROR }
    }

    /** The closing line for a silent ending. [lastStep] = the last step shown ("Opening BlazeFX"), "" if none. */
    fun line(reason: Reason, lastStep: String = ""): String {
        val at = lastStep.trim().removeSuffix("\u2026").removeSuffix("...").take(80).let { if (it.isNotEmpty()) " (last step: $it)" else "" }
        return when (reason) {
            Reason.STOPPED -> "Stopped before finishing$at. Tell me if you want me to carry on."
            Reason.TIMEOUT -> "That took too long, so I stopped$at. Want me to try again?"
            Reason.TOOL_FAILED -> "A step failed, so I couldn't finish$at. Want me to try another way?"
            Reason.ERROR -> "Something went wrong before I could answer$at. Want me to try again?"
            Reason.DONE -> "I finished without a reply to show$at. Tell me if anything still needs doing."
        }
    }
}
