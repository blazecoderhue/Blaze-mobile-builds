package com.blaze.assistant.agent

/**
 * v0.45.9: a suggestion chip only offers something this phone can actually do right now. Seen on a phone (0.45.8):
 * "hi Blaze" came back with "View your upcoming meetings" while calendar access was off, and tapping it got "I don't
 * have the ability to access your calendar". Every chip source goes through [allowed]: the model's <<follow-ups: …>>
 * (ReplyParts.split) and the composer / reply chips built in the app.
 *
 * A chip that needs a capability the phone doesn't have is dropped. Chips with no capability cue (a question, "tell me
 * more") always stay. [caps] is refreshed at the start of each turn (Agent); its default allows everything, so code that
 * never sets it behaves as before. Pure (ChipGateTest).
 */
object ChipGate {
    data class Caps(
        /** Device calendar readable (READ_CALENDAR granted) or Google Calendar connected. */
        val calendar: Boolean = true,
        /** Weather tool usable (signed in to Blaze Cloud, or the Home weather card has a reading). */
        val weather: Boolean = true,
        /** Something to play or replay (Blaze Music queue / library / last list, or Spotify connected). */
        val music: Boolean = true,
        val sms: Boolean = true,
        val calls: Boolean = true,
        val contacts: Boolean = true,
        val email: Boolean = true,
        val location: Boolean = true,
        /** Phone control (accessibility) on: opening and driving other apps. */
        val phoneControl: Boolean = true,
    )

    @Volatile var caps: Caps = Caps()

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val rules: List<Pair<Regex, (Caps) -> Boolean>> = listOf(
        r("\\b(calendars?|meetings?|events?|agenda|appointments?)\\b") to { c -> c.calendar },
        r("\\b(weather|forecast|rain|temperature|sunny|umbrella)\\b") to { c -> c.weather },
        r("\\b(songs?|music|playlists?|tracks?|album|spotify|replay|play (?:something|some|my|the last))\\b") to { c -> c.music },
        r("\\b(text|sms|message (?:\\w+ )?(?:back|on)|reply to)\\b") to { c -> c.sms || c.phoneControl },
        r("\\b(call|ring|dial)\\b") to { c -> c.calls },
        r("\\b(contacts?|phone number)\\b") to { c -> c.contacts },
        r("\\b(e-?mails?|inbox|gmail)\\b") to { c -> c.email },
        r("\\b(near me|nearby|directions|where am i|my location)\\b") to { c -> c.location },
        r("\\b(open (?:whatsapp|instagram|tiktok|youtube|settings|the app)|tap|on (?:my|the) screen)\\b") to { c -> c.phoneControl },
    )

    /**
     * 0.45.16 (design suite chat-hard-switch 4/5): after "ok totally different thing…" the answer was clean but a chip
     * said "check Livingstone weather". When the ask is an explicit topic switch, names from the earlier exchange
     * (capitalised words not in the ask) are stale for this turn's chips. Set per turn by Agent; empty = no filter.
     */
    @Volatile var stale: Set<String> = emptySet()
    private val switchCue = r("\\b(?:totally|completely|something|a)?\\s*different (?:thing|topic|question|subject)|\\bnew (?:topic|question|subject)\\b|\\bchang(?:e|ing) (?:of |the )?(?:subject|topic)\\b|\\bunrelated\\b|\\bswitch(?:ing)? (?:gears|topics?)\\b|\\b(?:forget|never ?mind) (?:that|the) (?:trip|plan|last|other)\\b|\\bon another note\\b|\\bmoving on\\b")
    private val nameWord = Regex("\\b\\p{Lu}[\\p{L}'-]{3,}\\b")

    /** Names in [earlier] (the turns before the ask) that are stale when [ask] switches topic explicitly. Pure. */
    fun staleTerms(ask: String, earlier: List<String>, earlierAsks: List<String> = emptyList()): Set<String> {
        if (!switchCue.containsMatchIn(ask)) return emptySet()
        val inAsk = ask.lowercase()
        val names = earlier.flatMap { t -> nameWord.findAll(t).map { it.value.lowercase() }.toList() }
        return (names + topicWords(earlierAsks)).filter { it !in inAsk && it !in COMMON }.toSet()
    }
    /** Content words (4+ letters) of the user's earlier asks: "plan a 3 day trip" → trip (chip "set trip reminder"). */
    private fun topicWords(asks: List<String>): List<String> =
        asks.flatMap { a -> Regex("[\\p{L}'-]{4,}").findAll(a.lowercase()).map { it.value }.toList() }
    private val COMMON = setOf("help", "make", "show", "tell", "give", "find", "need", "like", "just", "about", "could", "would", "should", "some", "more", "have", "know", "explain", "work", "works", "want", "what", "this", "that", "your", "with", "here", "sure", "okay", "done", "blaze", "today", "tomorrow", "then", "also", "next", "first", "please", "thanks", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "day")

    /** True when [chip] needs nothing this phone lacks (and names no stale topic, see [stale]). */
    fun allowed(chip: String, c: Caps = caps): Boolean = rules.all { (re, ok) -> !re.containsMatchIn(chip) || ok(c) } &&
        (stale.isEmpty() || Regex("[\\p{L}'-]+").findAll(chip.lowercase()).none { it.value in stale })

    fun filter(chips: List<String>, c: Caps = caps): List<String> = chips.filter { allowed(it, c) }
}
