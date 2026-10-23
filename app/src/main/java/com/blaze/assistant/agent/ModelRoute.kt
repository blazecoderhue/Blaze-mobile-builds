package com.blaze.assistant.agent

/**
 * v0.44.0 model routing: turns that DO things go to the strongest lineup Blaze Cloud has, plain chat stays fast.
 *
 * The phone never names a model: Blaze Cloud (gateway 0.6.0) offers task lineups (`blaze_task` chat / quick / code /
 * vision / long / agent, each an ordered list of free models set in its admin) and takes `blaze_avoid`, a list of
 * "provider/model" routes to try last. So routing here is: which lineup, and which weak routes go to the back.
 *
 *  - An action turn ([heavy]: phone control, the PC, coding, files, the browser, messages,
 *    settings, alarms, several steps in a row, or a tool chain already under way) always asks for the `agent`
 *    lineup, even when the conversation is long or looks like code (those used to go to `long` / `code`, whose
 *    fallbacks include Flash-Lite and 8B models that write tool calls as text). Small models go last
 *    ([WEAK_FOR_TOOLS]); they are still the fallback chain, never dropped.
 *  - Pictures keep the `vision` lineup (the agent lineup can't read them).
 *  - Chat-lane and plain turns keep the task Cloud.task picked (`quick` / `chat`): fast and cheap.
 *
 * Pure Kotlin; ModelRouteTest.
 */
object ModelRoute {
    /** The strong lineup for tool turns (gateway defaults.ts: Gemini Flash → gpt-oss-120b (Groq, Cerebras) → Kimi K2 → Mistral Medium → Workers AI). */
    const val STRONG_TASK = "agent"

    /**
     * Routes that are fine for a quick reply but weak at multi-step tool use (seen writing calls as text, or losing
     * the thread). On an action turn they are sent as blaze_avoid, so Blaze Cloud tries them only after the rest.
     * The gateway keeps at most 8 avoid entries; the ones a failed try just named come first ([avoid]).
     */
    val WEAK_FOR_TOOLS = listOf(
        "groq/llama-3.1-8b-instant",
        "cerebras/llama3.1-8b",
        "mistral/ministral-8b-latest",
        "gemini/gemini-flash-lite-latest",
        "workers-ai/@cf/meta/llama-3.1-8b-instruct-fast",
        "mistral/mistral-small-latest",
    )

    /** Gateway limit for blaze_avoid (chat.ts avoidSet). */
    const val AVOID_MAX = 8

    /** Past this much text even the agent lineup's smallest context (128k tokens) gets tight: let `long` take it. */
    const val AGENT_MAX_CHARS = 360_000

    /** Tool groups whose tools act on the phone, the desktop or the outside world. */
    val ACTION_GROUPS = setOf("screen", "device", "messages", "terminal", "browser", "pc", "apps", "schedule", "google", "media", "safety", "home")

    private val ic = setOf(RegexOption.IGNORE_CASE)
    private val actionWords = Regex("""\b(open|launch|start|stop|restart|run|install|uninstall|set ?up|setup|turn (?:on|off)|switch (?:on|off)|enable|disable|send|text|message|reply|call|dial|book|order|buy|pay|fill|sign ?in|log ?in|click|tap|type|scroll|download|upload|save|delete|remove|move|rename|create|make|build|fix|deploy|commit|push|clone|code|script|debug|compile|desktop|linux|vnc|terminal|shell|server|alarm|timer|remind|schedule|post|share|install|record|play|pause)\b""", ic)
    private val multiStep = Regex("""\b(and then|then|after that|afterwards|next|also|step by step|first\b.*\bthen|until|for each|every one|all of (?:them|my))\b""", ic)
    private val questionOnly = Regex("""^\s*(what|who|why|when|where|which|how (?:much|many|old|far|long|come)|is|are|was|were|does|did|define|explain|tell me about)\b""", ic)

    /**
     * Is this an action turn? [groupsOn] = the tool groups on this turn, [toolsSoFar] = tools already called this turn,
     * [bulk] = a routine / specialist / background agent run (always work).
     */
    fun heavy(userText: String, groupsOn: Set<String> = emptySet(), toolsSoFar: Int = 0, bulk: Boolean = false): Boolean {
        if (bulk) return true
        if (toolsSoFar > 0) return true
        val t = userText.trim()
        if (t.isEmpty()) return false
        val acts = actionWords.containsMatchIn(t)
        if (acts && multiStep.containsMatchIn(t)) return true
        if (groupsOn.any { it in ACTION_GROUPS } && acts) return true
        // "open WhatsApp", "start the desktop", "set an alarm": an imperative that acts, even with no group guessed.
        if (acts && !questionOnly.containsMatchIn(t)) return true
        return false
    }

    data class Pick(val task: String, val strong: Boolean)

    /** The lineup for one request: [base] = Cloud.task's pick, [heavy] = this is an action turn, [chars] = text size. */
    fun task(base: String, heavy: Boolean, hasTools: Boolean, chars: Int = 0): Pick = when {
        base == "vision" -> Pick("vision", false)
        heavy && hasTools && chars <= AGENT_MAX_CHARS -> Pick(STRONG_TASK, true)
        else -> Pick(base, false)
    }

    /** blaze_avoid for one try: the routes a failed try just named first, then (strong turns only) the weak ones. */
    fun avoid(failed: List<String>, strong: Boolean): List<String> =
        (failed + if (strong) WEAK_FOR_TOOLS else emptyList()).distinct().take(AVOID_MAX)

    /** The route chip suffix, so "which lane answered" shows in Advanced details. */
    fun label(p: Pick): String = if (p.strong) " · strong" else ""
}
