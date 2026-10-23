package com.blaze.assistant.agent

/**
 * 0.45.20 (feature batch item 9, the duo rule of 2026-10-21): @all no longer gets two full answers at once ("both
 * yapping"). Blaze is the doer and answers first (the work, the tools when the turn needs them). Halo then sees Blaze's
 * answer and either adds what Blaze missed or got wrong in a sentence or two, or stays quiet. A Halo line that repeats
 * Blaze is dropped ([haloLine]). Pure; DuoAllTest.
 */
object DuoAll {
    /** Halo's quiet tag: nothing worth adding. */
    const val QUIET = "[[quiet]]"
    /** Halo's add-on is short: at most this many sentences and characters. */
    const val HALO_SENTENCES = 2
    const val HALO_CHARS = 280
    /** Word overlap with Blaze's answer at or above this = a repeat (dropped). */
    const val SAME = 0.6

    fun blazeSystem(base: String): String = base +
        "\nThe user wrote to you and Halo together (@all). You are the doer: answer the user fully yourself, in your normal " +
        "length (short for small talk). Halo reads your answer afterwards and only adds what you missed, so don't mention Halo, " +
        "don't hand anything to Halo and don't wait for Halo. End with exactly one control tag on its own at the very end: " +
        "[[to: user]] if you asked the user something, else [[done]]."

    fun haloSystem(base: String, blazeAnswer: String): String = base +
        "\nThe user wrote to you and Blaze together (@all). Blaze has ALREADY answered; here is Blaze's answer:\n\"\"\"\n" +
        blazeAnswer.take(4000) + "\n\"\"\"\n" +
        "Your job: be brief and accurate. If Blaze's answer is right and complete, reply with exactly $QUIET and nothing else. " +
        "Otherwise add ONLY what Blaze missed or got wrong, in one or two short sentences. Never repeat, rephrase or praise " +
        "Blaze's answer, never greet again if Blaze already greeted, and never use tools for this. No control tags other than $QUIET."

    private val tag = Regex("""\[\[[^\]]*\]\]""")
    private val words = Regex("[\\p{L}\\p{N}']+")

    private fun bag(t: String): Set<String> = words.findAll(t.lowercase()).map { it.value }.filter { it.length > 2 }.toSet()

    /** Share of Halo's words that Blaze's answer already has (0..1). */
    fun overlap(halo: String, blaze: String): Double {
        val h = bag(halo); if (h.isEmpty()) return 1.0
        val b = bag(blaze)
        return h.count { it in b }.toDouble() / h.size
    }

    /** What Halo's raw reply shows: "" for quiet, empty, or a repeat of Blaze; else at most two sentences / 280 chars. */
    fun haloLine(raw: String, blazeAnswer: String): String {
        if (raw.contains(QUIET, ignoreCase = true)) return ""
        var t = tag.replace(raw, "").trim()
        if (t.isEmpty()) return ""
        if (blazeAnswer.isNotBlank() && overlap(t, blazeAnswer) >= SAME) return ""
        val sentences = Regex("(?<=[.!?])\\s+").split(t).filter { it.isNotBlank() }
        if (sentences.size > HALO_SENTENCES) t = sentences.take(HALO_SENTENCES).joinToString(" ")
        if (t.length > HALO_CHARS) t = t.take(HALO_CHARS - 1).substringBeforeLast(' ').trimEnd(',', ';', ':') + "\u2026"
        return t
    }
}

/**
 * 0.45.20 (feature batch item 10): voice chat speaks no filler ("On it.", "Sure thing!", "Got it, one sec.") for a
 * greeting or simple chat. A filler line is only allowed when a real tool task started this turn. A greeting gets a
 * direct greeting. Pure; DuoAllTest.
 */
object VoiceFiller {
    /** One filler clause, whole (after its end punctuation is taken off). */
    private val filler = Regex(
        "(?i)(okay|ok|alright|all right|sure|sure thing|got it|got you|on it|no problem|will do|understood|absolutely|certainly|" +
            "one sec|one second|one moment|just a sec|just a moment|give me a (sec|second|moment)|hang on|hold on|" +
            "let me (check|see|look|look into it|look into that|check on that|take a look|get on it|handle that|do that)( for you)?|" +
            "i'?ll (check|look into it|look into that|get on it|take a look|handle that|do that)( for you)?( now)?|working on it)")
    private val split = Regex("(?<=[.!?,…])\\s+")

    /** True when [text] is nothing but filler ("On it.", "Sure, one sec."). */
    fun onlyFiller(text: String): Boolean {
        val parts = split.split(text.trim()).map { it.trim().trimEnd('.', '!', '?', ',', '\u2026', ' ') }.filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.all { filler.matches(it) }
    }

    /** True when [text] opens with a filler line. */
    fun hasFiller(text: String): Boolean = split.split(text.trim()).firstOrNull()?.let { filler.matches(it.trim().trimEnd('.', '!', '?', ',', '…', ' ')) } == true

    /**
     * The words to speak: [reply] without its opening filler clauses when no tool ran this turn ([toolsRan] = false).
     * Kept as it is when filler is all it says, or when a real tool task ran.
     */
    fun spoken(reply: String, toolsRan: Boolean): String {
        if (toolsRan) return reply
        val parts = split.split(reply.trim()).toMutableList()
        var dropped = 0
        while (parts.size > 1 && dropped < 3 && filler.matches(parts[0].trim().trimEnd('.', '!', '?', ',', '…', ' '))) { parts.removeAt(0); dropped++ }
        if (dropped == 0) return reply
        return parts.joinToString(" ").trim().replaceFirstChar { it.uppercase() }
    }

    /** The voice rule for the model's prompt (voice turns). */
    const val RULE = "\nVOICE TURNS (when the user spoke to you): you're heard, not read. For a greeting, thanks or simple chat reply directly (a greeting gets a greeting back), " +
        "with no filler like \"On it\", \"Sure thing\", \"Let me check\" or \"One sec\". Only say a short line like that when you are " +
        "really starting a task with tools."
}
