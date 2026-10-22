package com.blaze.assistant.agent

import com.blaze.assistant.ui.DesignStatus

/**
 * v0.45.6: Design never says "Done" unless the artifact really rendered. The design tool's result carries a RENDER CHECK
 * line ([toolLine]) and the final reply is grounded ([ground]): with no render confirmed, a "Done / ready / is open"
 * opening is replaced by the plain state (still drawing, or what failed). Motion requests are told the truth: the Motion
 * timeline isn't built, the intro is a page on the Page tab. Pure; DesignTruthTest.
 */
object DesignTruth {
    const val MOTION_NOTE_MODEL = "the Motion timeline isn't built yet: make or change an animated page and say it's on the Page tab"

    fun motionStatus(is3d: Boolean): String = if (is3d) "The Motion timeline isn't built yet; your model is on the 3D tab."
        else "The Motion timeline isn't built yet; your intro is on the Page tab."

    /** The line appended to the design tool's result. [status] null = the Design view didn't report (not open, still loading). */
    fun toolLine(status: DesignStatus.Status?): String = when (status?.state) {
        DesignStatus.State.RENDERED -> "\nRENDER CHECK: rendered (${status.detail})."
        DesignStatus.State.FAILED -> "\nRENDER CHECK: FAILED: ${status.detail.take(200)}. Do NOT say done or ready: tell the user plainly what failed and offer to fix it."
        else -> "\nRENDER CHECK: not confirmed yet (the Design view hasn't shown it drawing). Do NOT say done or ready: say it's saved and opening in Design."
    }

    private val claim = Regex("""(?i)^\s*(?:all\s+)?(?:done|finished|ready|here(?:'s| is| you go)|your\b[^.!?\n]{0,80}\b(?:is|are)\s+(?:ready|open|done|live|up))\b[^.!?\n]*[.!?]?\s*""")
    private val readyAnywhere = Regex("""(?i)\b(?:is|are) (?:now )?(?:ready|open|live)\b(?: in (?:the )?design(?: view)?)?""")

    /**
     * The reply as the user may see it. [status] = what the Design view proved for this turn's design (null = nothing heard);
     * [motion] = the user asked in the Motion tab; [is3d] = the design is a 3D model.
     */
    fun ground(reply: String, status: DesignStatus.Status?, motion: Boolean = false, is3d: Boolean = false): String {
        var out = reply.trim()
        val state = status?.state
        if (state != DesignStatus.State.RENDERED) {
            val lead = when (state) {
                DesignStatus.State.FAILED -> "It didn't render: ${status.detail.trim().trimEnd('.').take(200)}."
                else -> "Saved; it's still opening in Design and hasn't shown on screen yet."
            }
            val m = claim.find(out)
            out = if (m != null) (lead + " " + out.substring(m.range.last + 1).replace(readyAnywhere, "is saved").trim()).trim()
                else if (readyAnywhere.containsMatchIn(out) || state == DesignStatus.State.FAILED) (lead + " " + out.replace(readyAnywhere, "is saved")).trim()
                else out
        }
        if (motion && !out.contains("Page tab", true) && !out.contains("3D tab", true)) out = (out + " " + motionStatus(is3d)).trim()
        return out
    }

    /** True when the user's message came from the Design studio's Motion tab. */
    fun isMotionAsk(userText: String): Boolean = userText.trimStart().startsWith("Motion design", ignoreCase = true)
}
