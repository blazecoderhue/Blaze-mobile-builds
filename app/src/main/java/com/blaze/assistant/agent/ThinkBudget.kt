package com.blaze.assistant.agent

/**
 * v0.33.1 adaptive thinking budget (idea #9). Until now a turn was "quick" (reasoning models told to think briefly,
 * the gateway's fast "quick" lineup for plain chat) whenever the message was under 80 characters. That got both
 * directions wrong: "prove √2 is irrational" (short, hard) thought briefly, and a long pasted text with "fix the typos"
 * (long, easy) thought at length. This is a tiny on-phone classifier from cheap features (length, hard / easy cue
 * words, maths, code, questions in a row); no model call, a few microseconds. Pure (ThinkBudgetTest).
 */
object ThinkBudget {
    enum class Level { QUICK, NORMAL, DEEP }

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val deepCue = r("""\b(why|explain|prove|proof|derive|calculate|compute|solve|integrate|differentiate|probability|optimi[sz]e|compare|trade-?offs?|pros and cons|plan|strategy|design|architect\w*|debug|analy[sz]e|evaluate|critique|review|step[- ]by[- ]step|think (hard|carefully|it through)|reason|figure out|what if|should i|best way|recommend|diagnos\w*|algorithm|complexity|essay|research)\b""")
    private val easyCue = r("""^\s*(hi|hello|hey|yo|thanks|thank you|ok|okay|cool|nice|good (morning|night|evening|afternoon)|bye|lol|haha|yes|no|sure)\b|\b(fix (the )?typos|translate|summari[sz]e|tl;?dr|shorten|rephrase|rewrite|make it (shorter|longer|formal|casual)|open|turn (on|off)|call|text|play|pause|stop|set (an? )?(alarm|timer|reminder)|remind me|what time|what's the time|weather)\b""")
    private val maths = Regex("""(\d\s*[-+*/^=<>]\s*\d)|[∫∑√π≤≥≠]|\b(sin|cos|tan|log|ln|lim|dx|dy)\b""")
    private val code = Regex("""```|\b(fun|def|class|function|return|import|const|SELECT|stack ?trace|traceback|exception)\b""")

    /** How hard to think about this message. */
    fun level(text: String, attachments: Int = 0): Level {
        val t = text.trim()
        if (t.isEmpty()) return if (attachments > 0) Level.NORMAL else Level.QUICK
        val deep = deepCue.containsMatchIn(t) || maths.containsMatchIn(t) || code.containsMatchIn(t) || t.count { it == '?' } >= 3
        if (deep) return Level.DEEP
        val easy = easyCue.containsMatchIn(t)
        return when {
            attachments > 0 -> Level.NORMAL
            easy -> Level.QUICK
            t.length < 80 -> Level.QUICK
            else -> Level.NORMAL
        }
    }

    /** The quick flag LlmClient reads: think briefly, use the fast lineup. */
    fun quick(text: String, attachments: Int = 0): Boolean =
        if (try { Settings.bool("adaptive_think", true) } catch (_: Throwable) { true }) level(text, attachments) == Level.QUICK
        else attachments == 0 && text.length < 80
}
