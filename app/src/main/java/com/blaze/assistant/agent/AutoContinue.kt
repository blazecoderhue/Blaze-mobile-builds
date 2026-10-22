package com.blaze.assistant.agent

/**
 * v0.45.3: when Autopilot may send another turn by itself after a reply that called no tools.
 *
 * Before 0.45.3 any reply that ended in a question got an automatic "decide yourself" turn, so a finished answer
 * ("…Want me to run the unused-apps check early or set a reminder?") was followed by a second, unasked turn
 * ("Sending…"). Now Autopilot continues only when the task is really unfinished:
 *
 *  - the model said so with the hidden [CONTINUE] marker (and the reply doesn't end by asking the user),
 *  - the reply states a multi-step plan with steps left ("Step 2 of 5", "(2/5)"),
 *  - or the last action this turn failed its check and is worth one retry.
 *
 * It stops when the reply is a complete answer, asks the user anything or offers options, is chit-chat or
 * information, or carries the [DONE] marker. A reply that ends in a question is never followed by an automatic turn.
 * At most [MAX] automatic turns per user message. Approval rules (deletes, money, messages, calls) are unchanged:
 * they live in ConfirmGate. Pure Kotlin; AutoContinueTest.
 */
object AutoContinue {
    /** Hidden markers the model may end a reply with (never shown; ReplyParts strips them). */
    const val DONE = "<<done>>"
    const val CONTINUE = "<<continue>>"

    /** Automatic continues per user message. */
    const val MAX = 3

    /** The line sent as the user's turn when Autopilot continues. */
    const val NUDGE = "[Autopilot] Continue with the next unfinished step of the task. Don't repeat what is already done. " +
        "When everything is done and checked, give the short summary and end with $DONE."

    enum class Why { DONE_MARK, ASKS_USER, OFFERS, CAP, ANSWER, CONTINUE_MARK, PLAN_LEFT, FAILED_CHECK }

    data class Decision(val go: Boolean, val why: Why)

    private val ic = setOf(RegexOption.IGNORE_CASE)
    private val doneRe = Regex("""<<\s*done\s*>>""", ic)
    private val contRe = Regex("""<<\s*continue\s*>>""", ic)
    /** Both markers anywhere, plus (while streaming) a half-typed one at the very end. */
    private val markRe = Regex("""[ \t]*<<\s*(?:done|continue)\s*>>[ \t]*""", ic)
    private val partialRe = Regex("""<{1,2}\s*(?:d(?:o(?:n(?:e)?)?)?|c(?:o(?:n(?:t(?:i(?:n(?:u(?:e)?)?)?)?)?)?)?)?\s*>?$""", ic)

    fun hasDone(text: String) = doneRe.containsMatchIn(text)
    fun hasContinue(text: String) = contRe.containsMatchIn(text)

    /** [text] without the markers (while streaming, without a half-typed one at the end either). */
    fun strip(text: String, final: Boolean = true): String {
        if (!text.contains('<')) return text
        var t = markRe.replace(text, "")
        if (!final) partialRe.find(t)?.let { m -> if (t.length - m.range.first <= 12 && (m.value.length >= 2 || t.endsWith("<"))) t = t.substring(0, m.range.first) }
        return t
    }

    // A question or an offer in the last lines: the user decides next, so Autopilot never answers for them.
    private val offerRe = Regex("""\b(want me to|would you like|do you want|should i|shall i|let me know|if you(?:'d)? like|happy to|i can also|or i can|which (?:one|option|do you)|your call|up to you|tell me (?:if|which|when)|ready when you are)\b""", ic)
    private val questionRe = Regex("(?i)(\\?\\s*(\\*\\*)?\\s*$|\\b(would you like|do you want|should i|shall i|let me know|which (one|option)|can you (confirm|tell|provide)|could you (confirm|tell|provide)|please (confirm|choose|provide|tell))\\b)")
    private val optionsRe = Regex("""(?m)^\s*(?:option\s+)?[1-3a-c][.)]\s+\S""", ic)

    /** True when the reply ends by asking the user something or offering options. */
    fun asksUser(text: String): Boolean {
        val t = strip(text).trim().trimEnd('*', '_', ' ', '\n')
        if (t.isEmpty()) return false
        if (t.endsWith("?")) return true
        val tail = t.takeLast(400).lines().takeLast(3).joinToString("\n")
        return questionRe.containsMatchIn(tail) || offerRe.containsMatchIn(tail)
    }

    private fun offers(text: String): Boolean {
        val tail = strip(text).trim().takeLast(600)
        return optionsRe.findAll(tail).count() >= 2 && offerRe.containsMatchIn(tail)
    }

    private val stepOf = Regex("""\bstep\s+(\d{1,2})\s*(?:of|/)\s*(\d{1,2})\b""", ic)
    private val paren = Regex("""\((\d{1,2})\s*/\s*(\d{1,2})\)""")

    /** Steps left in a plan the reply itself states ("Step 2 of 5 done", "(3/4)"); 0 when it states none. */
    fun planStepsLeft(text: String): Int {
        val m = (stepOf.findAll(text) + paren.findAll(text)).lastOrNull() ?: return 0
        val cur = m.groupValues[1].toIntOrNull() ?: return 0
        val all = m.groupValues[2].toIntOrNull() ?: return 0
        return if (all in 2..30 && cur in 1..all) all - cur else 0
    }

    /**
     * [text] = the reply (no tool calls), [autoSoFar] = automatic continues already sent for this message,
     * [toolsThisTurn] = tools that ran this turn, [failedCheck] = the last action's check failed.
     */
    fun decide(text: String, autoSoFar: Int, toolsThisTurn: Int = 0, failedCheck: Boolean = false): Decision {
        if (hasDone(text)) return Decision(false, Why.DONE_MARK)
        if (asksUser(text)) return Decision(false, Why.ASKS_USER)
        if (offers(text)) return Decision(false, Why.OFFERS)
        if (autoSoFar >= MAX) return Decision(false, Why.CAP)
        if (hasContinue(text)) return Decision(true, Why.CONTINUE_MARK)
        if (planStepsLeft(text) > 0) return Decision(true, Why.PLAN_LEFT)
        if (failedCheck && toolsThisTurn > 0) return Decision(true, Why.FAILED_CHECK)
        return Decision(false, Why.ANSWER)
    }

    /** The Autopilot prompt rule (with the markers). */
    const val PROMPT = "When a reply ends but the task still has steps you will do next by yourself, end it with $CONTINUE. " +
        "When the task is finished, or you are only answering, informing or chatting, end with $DONE. Never write a marker after a question to the user. "
}
