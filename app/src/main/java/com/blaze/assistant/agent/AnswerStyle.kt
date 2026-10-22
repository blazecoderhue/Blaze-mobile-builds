package com.blaze.assistant.agent

/**
 * v0.27.x: Blaze answers like it has hands.
 *
 * Joey asked how to get a lightweight TTS model and got a walkthrough that ended "would you like me to walk you
 * through…?" instead of "Want me to install it for you?". When the user asks how to get, install, set up, enable,
 * open or download something Blaze can do itself, the reply leads with that and ends with ONE concrete offer to do
 * it, with the action as the first follow-up chip ([turnHint]). A clear command is simply done.
 *
 * Joey's constraint: no generic "want me to help?" tails. An offer only when there's a concrete, directly related
 * action Blaze can take next. Facts, explanations, greetings and finished tasks end without a question ([RULE]).
 * Pure Kotlin, unit-tested in IntentHonestyTest.
 */
object AnswerStyle {

    /** The always-on rule (system prompt, HOW YOU TALK). */
    const val RULE = "\nHANDS-ON: you can act, not just explain. When the user asks how to get, install, set up, enable, turn on, open or download " +
        "something you can do yourself with your tools (install an app, APK or model, change a setting, open an app, download a file), lead with " +
        "that in one line, keep any steps short, and end with ONE concrete offer to do it, e.g. \u201cWant me to install it for you?\u201d, with that " +
        "action as the first follow-up chip (\u201cYes, install it\u201d). If the message is a clear command (\u201cinstall X\u201d, \u201cturn on Y\u201d), just do it. " +
        "Offer only when there is a concrete next action you can really take that is directly related to the request. Never tack on generic " +
        "questions like \u201cwant me to help with anything else?\u201d or \u201cwould you like me to walk you through it?\u201d. Plain facts, explanations, " +
        "greetings and finished tasks end without a question: sometimes no question is best."

    /**
     * v0.31.1 formatting rule (always on, static so the provider's cached prefix holds): Markdown or light HTML, tables as
     * real tables (never inside a code fence), and what the chat turns into tappable glass windows.
     */
    const val FORMAT = "\nFORMATTING: write Markdown. You may also use these HTML tags and they are drawn formatted (never shown raw): " +
        "<b>/<strong>, <i>/<em>, <u>, <s>/<del>, <code>, <pre>, <a href=\"https://…\">, <br>, <p>, <h1>-<h4>, <ul>/<ol>/<li>, <blockquote>, <hr>, " +
        "<sub>/<sup>, <mark>, <span style=\"color:…\"> (colours are toned down to the app's monochrome look), <table> and <img src=\"https://…\" alt=\"…\">. " +
        "No scripts, styles, forms or iframes (they are removed). TABLES: write a table as a plain Markdown pipe table (| a | b | with a |---|---| line under the header) " +
        "or as an HTML <table>, NEVER inside a ``` code fence and never as ASCII art; code fences are only for real code. Tables, diagrams (```mermaid), charts, " +
        "comparisons, mini tools and pictures are shown automatically as a compact preview card the user taps to open in a floating window " +
        "(zoom, pan, scroll), so don't describe how to view them and don't repeat their content in the text."

    private val ic = setOf(RegexOption.IGNORE_CASE)

    // "how do I install …", "how can I get …", "how to set up …", "where can I download …", "I want to enable …", "is there a way to turn on …"
    private val howTo = Regex(
        """\b(?:how (?:do|can|could|would|should) (?:i|we|you)|how to|where (?:do|can|could) i|is there a way to|what'?s the (?:best |easiest )?way to|""" +
            """i (?:want|need|would like) to|i'd like to|i wanna|help me|can i)\s+(get|install|set ?up|setup|enable|turn on|switch on|activate|download|open|launch|add)\b""", ic)

    private val verbOf = linkedMapOf(
        "install" to Regex("""\b(install|get|add)\b""", ic),
        "download" to Regex("""\bdownload\b""", ic),
        "set up" to Regex("""\b(?:set ?up|setup)\b""", ic),
        "turn on" to Regex("""\b(enable|turn on|switch on|activate)\b""", ic),
        "open" to Regex("""\b(open|launch)\b""", ic),
    )

    // Things that aren't phone actions Blaze can take for the user ("how do I get a job", "how can I get fit").
    private val notDoable = Regex("""\b(job|visa|passport|girlfriend|boyfriend|fit|rich|money|loan|degree|promotion|better at|over it|to sleep|pregnant|citizenship|license|licence)\b""", ic)

    /** The action Blaze could offer for a how-to-get request ("install", "turn on"…), or null (facts, chat, commands). */
    fun offerVerb(userText: String): String? {
        val t = userText.trim()
        if (t.isEmpty() || t.startsWith("/") || t.length > 300) return null
        val m = howTo.find(t) ?: return null
        if (notDoable.containsMatchIn(t)) return null
        val word = m.groupValues[1]
        return verbOf.entries.firstOrNull { it.value.containsMatchIn(word) }?.key
    }

    /** The offer line for [verb]: "Want me to install it for you?". */
    fun offerLine(verb: String): String = "Want me to $verb it for you?"

    /** Per-turn hint (empty when the turn isn't a how-to-get request). */
    fun turnHint(userText: String): String {
        val v = offerVerb(userText) ?: return ""
        return "\nHANDS-ON THIS TURN: the user asked how to $v something. If you can $v it yourself, say so first in one line, keep any steps short, " +
            "and end with exactly \u201c${offerLine(v)}\u201d (adapted to the thing). Make the first follow-up chip \u201cYes, $v it\u201d. " +
            "If you truly can't do it from the phone, just answer, with no offer."
    }
}
