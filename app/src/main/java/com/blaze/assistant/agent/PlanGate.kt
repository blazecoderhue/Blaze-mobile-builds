package com.blaze.assistant.agent

/**
 * v0.45.0 (#18) phone plans and learned routes only from turns that really worked. TapLoop.Plans replays "the steps
 * that worked last time"; before, any turn without a nudge was saved, even one that did the wrong thing. Now:
 *  - [keep]: no claim / proof nudge, no rewrite of the reply, at least one action verified and nothing failed or left
 *    unconfirmed among the turn's actions;
 *  - [correction]: the next message says it was wrong ("no", "that's wrong", "didn't work", "undo", 👎, and the same in
 *    French, Swahili, Nyanja and Bemba) → the plan saved for the previous request is dropped.
 * Pure Kotlin; PlanGateTest.
 */
object PlanGate {
    fun keep(log: List<Receipt.Entry>, nudged: Boolean, rewrote: Boolean): Boolean {
        if (nudged || rewrote) return false
        val acts = log.filter { Receipt.isAction(it.tool) }
        if (acts.isEmpty()) return false
        if (acts.any { it.status != ToolVerify.Status.VERIFIED }) return false
        return true
    }

    /** Learned routes may learn from a turn unless it failed outright or had to be corrected by a nudge. */
    fun learn(log: List<Receipt.Entry>, nudged: Boolean): Boolean = !nudged && log.none { it.status == ToolVerify.Status.FAILED }

    private val wrong = Regex(
        """^\s*(?:no+\b|nope\b|wrong\b|not (?:that|this|what)\b|that'?s (?:not|wrong)\b|that (?:didn'?t|did not|doesn'?t) work|it (?:didn'?t|did not|doesn'?t) work|""" +
            """didn'?t work|you didn'?t|undo\b|stop\b|that'?s not it|incorrect\b|non\b|pas (?:ça|ca)\b|ce n'est pas|ça (?:n'a|n'a pas) marché|hapana\b|si hivyo|""" +
            """haijafanya|ayi\b|iyayi\b|sichoncho|awe\b|tefyo|tacabomba|\uD83D\uDC4E)""", RegexOption.IGNORE_CASE)

    fun correction(text: String): Boolean = wrong.containsMatchIn(text.trim())
}
