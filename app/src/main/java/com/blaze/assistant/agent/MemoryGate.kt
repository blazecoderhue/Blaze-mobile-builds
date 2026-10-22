package com.blaze.assistant.agent

/**
 * v0.45.9: memory is for durable facts only. A greeting, a thank-you, an "ok", a mood ("doing good thanks!") or a
 * read-only question (what's on today, the weather, the time, "how are you feeling??") never writes memory: not
 * through memory_save / memory_update / remember, and not through the after-turn auto-extract (MindReview.afterTurn).
 * So the "Memory updated" chip only shows when a real lasting fact was saved.
 *
 * Seen on a phone (0.45.8, 21 Oct 2026): "how are you feeling??", "doing good thanks!", "anything on for today?" and
 * "so what's on for today specifically?" each got "Memory updated".
 *
 * Pure (no Android), so MemoryGateTest covers it. A message that also tells something lasting ("thanks! I'm vegetarian
 * by the way", "remember my class is on Tuesdays") is allowed.
 */
object MemoryGate {
    /** The tools that write memory. memory_delete / forget stay allowed: they act on a user's explicit ask. */
    val WRITE_TOOLS = setOf("memory_save", "memory_update", "remember")

    const val REFUSED = "Not saved: this turn is small talk or a read-only question, and memory keeps only durable facts " +
        "(names, preferences, plans, decisions). Don't mention memory to the user; just answer."

    private val norm = Regex("[^\\p{L}\\p{N}' ]+")

    // Small-talk pieces: greetings, thanks, acks, moods, how-are-you and goodbyes (a few local words too).
    private const val PIECE = "(?:" +
        "hi+|hey+|hello+|hiya|yo|sup|howdy|heya|morning|good (?:morning|afternoon|evening|night|day)|gm|gn|night|there|" +
        "muli bwanji|mwaswera bwanji|mwauka bwanji|shani|habari|jambo|bonjour|salut|hola|" +
        "thanks?(?: (?:a lot|so much|you|u|man|bro|again))*|thank you(?: so much| very much)?|thx|ty|tysm|cheers|zikomo|natotela|asante|merci|" +
        "ok(?:ay)?|k|kk|okey|alright|all right|cool|nice|great|awesome|perfect|fine|good|got it|noted|understood|" +
        "lol|lmao|haha+|hehe+|wow|oh|ah|hmm+|" +
        "bye|goodbye|see (?:you|ya)(?: later| soon)?|later|ttyl|take care|sweet dreams|" +
        "how are (?:you|u)(?: doing| feeling| today)?|how(?:'re| are) (?:you|u) (?:doing|feeling)|how do you feel|" +
        "how'?s it going|how have you been|what'?s up|wassup|whats good|you good|you ok(?:ay)?|and you|you|" +
        "(?:i'?m |im )?(?:doing |feeling )?(?:good|great|fine|well|ok(?:ay)?|alright|not bad|pretty good|so so|better)(?: too| as well)?|" +
        "same|me too|you too|no worries|np|all good|love (?:you|it)|you'?re (?:the best|awesome|great|amazing)|" +
        "blaze|halo|all|buddy|mate|friend|man|bro" +
        ")"

    // A whole message made only of small-talk pieces ("ok cool thanks", "doing good thanks", "@halo hi").
    private val smallTalk = Regex("^$PIECE(?: $PIECE)*$")

    // Read-only questions: schedule / time / date / weather / "what's on". Nothing to remember in the asking.
    private val readOnly = Regex(
        "^(?:(?:halo|blaze|all) )?(?:so |and |ok |okay |well |hey |hi |blaze |halo |yea |yeah |ya |yes |yep |but |no |nah |alright |right |cool |um |uh |hmm |then |also )*(?:" +
            "(?:is there )?anything on|what'?s on|whats on|what is on|what do i have|do i have anything|have i got anything|what'?s happening|" +
            "what'?s (?:my )?(?:schedule|plan|agenda)|what are my (?:plans|reminders|alarms)|any (?:reminders|alarms|plans|meetings|classes)|" +
            "what time|what'?s the time|what day|what'?s the date|what is the date|what date|which day|" +
            "what'?s the weather|how'?s the weather|what is the weather|weather|is it (?:going to|gonna) rain|will it rain|how hot|how cold|" +
            "when is|when'?s|" +
            // v0.45.13 (phone 17:11-17:12, 0.45.9): "anything else to do today?" and "okay yea but anything other than those
            // that we setup together?" still got "Memory updated".
            "anything else|is there anything|are there any|anything other than|anything left|anything more|anything new|what else|" +
            "what'?s left|whats left|what'?s next|whats next|what do i have|what have i got|do i have|have i got|am i free|am i busy|" +
            "what are (?:my|the) (?:plans|reminders|alarms|events|classes|meetings)|which (?:reminders|alarms|events|classes)|any (?:more|other|new)" +
            ")\\b")

    // v0.45.13: short replies that only acknowledge or close a topic ("that was in the morning so we're good",
    // "No, that's all", "never mind", "not now"). Nothing lasting in them.
    private val ack = Regex(
        "\\b(?:(?:so )?we'?re (?:good|fine|ok(?:ay)?|done|all set)|that'?s (?:all|it|fine|ok(?:ay)?|good|enough|everything)|" +
            "never ?mind|nvm|not now|maybe later|no thanks|no thank you|no need|all done|i'?m done|" +
            "that was (?:in the morning|this morning|earlier|yesterday|already|before)|already (?:done|did it|passed|finished|over|happened)|" +
            "(?:it'?s|its) (?:fine|ok(?:ay)?|good|over|done) (?:now|then)|forget it|doesn'?t matter|no worries)\\b")

    // Words that mean the user is telling something lasting (allowed even inside a short message).
    private val durable = Regex(
        "\\b(?:remember|don'?t forget|note that|save (?:this|that)|my name is|call me|i(?: am|'m|\u2019m) (?:a |an )?(?:vegetarian|vegan|allergic|student|teacher|nurse|doctor|engineer|developer|from|based in|living in|moving)|" +
            "i live|i work|i study|my (?:wife|husband|partner|mum|mom|dad|son|daughter|brother|sister|friend|boss|birthday|class|school|job|address|favourite|favorite)|" +
            "i (?:like|love|hate|prefer|always|never|usually)\\b)", RegexOption.IGNORE_CASE)

    /** Lower-case words only, no emoji or punctuation, single spaces. */
    fun normalize(text: String): String =
        norm.replace(text.lowercase().replace('\u2019', '\''), " ").trim().replace(Regex("\\s+"), " ")

    fun isSmallTalk(text: String): Boolean {
        val t = normalize(text)
        if (t.isEmpty()) return true
        // "hi! thanks" / "ok cool thanks": every chunk is small talk.
        val chunks = text.split(Regex("[!.?,;\\n]+")).map { normalize(it) }.filter { it.isNotEmpty() }
        if (chunks.isNotEmpty() && chunks.all { smallTalk.matches(it) }) return true
        return smallTalk.matches(t)
    }

    fun isReadOnlyQuestion(text: String): Boolean {
        val t = normalize(text)
        if (t.isEmpty()) return false
        if (!readOnly.containsMatchIn(t)) return false
        // A question that also asks to change something isn't read-only ("what time is it, and set an alarm for 7").
        return !Regex("\\b(set|add|create|remind me|schedule|book|delete|cancel|move|change|turn|send|text|call)\\b").containsMatchIn(t)
    }

    /** v0.45.13: a short acknowledgement / topic closer (≤ 14 words) with nothing lasting in it. */
    fun isAck(text: String): Boolean {
        val t = normalize(text)
        if (t.isEmpty() || t.split(' ').size > 14) return false
        return ack.containsMatchIn(t)
    }

    /** True when this user message may lead to a memory write. */
    fun allows(userText: String): Boolean {
        if (durable.containsMatchIn(userText)) return true
        return !isSmallTalk(userText) && !isReadOnlyQuestion(userText) && !isAck(userText)
    }

    /** The tool result when a write tool is called on a turn that may not write; null = go ahead. */
    fun refusal(tool: String, userText: String): String? = if (tool in WRITE_TOOLS && !allows(userText)) REFUSED else null
}
