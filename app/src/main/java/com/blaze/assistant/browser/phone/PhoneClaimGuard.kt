package com.blaze.assistant.browser.phone

/**
 * 0.45.19-speed (device test: mid-task, "what is Blaze Phone doing?" was answered "Booking confirmed!" with nothing
 * booked): while Blaze Phone is in play, a reply may say booked / ordered / paid / confirmed only when a phone_read in
 * this same turn saw a confirmation on the page (a confirmation number or confirmation text: its verdict line was
 * VERIFIED). Any other such sentence is replaced by the plain truth: not booked yet.
 *
 * Unlike ClaimCheck.BOOKED this doesn't depend on what the user asked (a status question counts too) and only a
 * phone_read backs it (a status line, a tap, or an earlier turn never do). Pure Kotlin; PhoneClaimGuardTest.
 */
object PhoneClaimGuard {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val claim = r(
        """^\W*(?:done[,!.:]?\s*|all set[,!.]?\s*|great(?: news)?[,!.]?\s*|success[,!.]?\s*)?(?:booked|confirmed|reserved|ordered|paid|purchased|done and booked|all booked)\b|""" +
            """\b(?:i|i've|i have|i just|i've just|we've|blaze has|blaze phone has|it has|it's)\s+(?:also\s+|now\s+|just\s+|successfully\s+|already\s+)?(?:booked|reserved|ordered|purchased|paid|placed (?:the|your|an?) order|completed (?:the|your) (?:booking|order|purchase|payment|reservation)|confirmed (?:the|your) (?:booking|reservation|order|table|room|appointment|payment))\b|""" +
            """\b(?:booking|reservation|order|table|room|ticket|tickets|appointment|purchase|payment|checkout)s?\s+(?:is\s+|are\s+|has been\s+|have been\s+|was\s+|were\s+)?(?:now\s+|all\s+|fully\s+)?(?:confirmed|booked|reserved|placed|complete|completed|successful|done|paid|went through)\b|""" +
            """\byou(?:'re| are)\s+(?:all\s+)?(?:booked|set for|confirmed|paid up)\b|""" +
            """\b(?:confirmation (?:number|code)|reference(?: number)?|booking ref(?:erence)?)\s*(?:is|:)\s*#?[A-Z0-9-]{4,}"""
    )
    private val negation = r(
        """\b(?:not|never|no|isn't|aren't|wasn't|weren't|hasn't|haven't|didn't|couldn't|can't|cannot|won't|unable|failed|yet to|until|before|once|if|when|whether|tried to|trying to|about to|going to|ready to|want me to|shall i|should i|do you want|waiting|pending|still)\b|\?\s*$"""
    )

    private fun sentences(text: String): List<String> =
        text.split(Regex("""(?<=[.!?\u2026])\s+|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }

    /** The sentences of [reply] that claim something was booked / ordered / paid / confirmed. */
    fun claims(reply: String): List<String> = sentences(reply).filter { claim.containsMatchIn(it) && !negation.containsMatchIn(it) }

    /**
     * Did a phone_read this turn see a confirmation? [phoneDone]: this turn's Blaze Phone calls in order, as
     * (tool name, verification why) with why from ToolVerify.phoneVerdict ("page verified: …" / "page not verified").
     * The newest phone_read decides (a later read that lost the confirmation, or shows an error, wins).
     */
    fun backed(phoneDone: List<Pair<String, String>>): Boolean =
        phoneDone.lastOrNull { it.first == "phone_read" }?.second?.startsWith("page verified") == true

    const val HONEST = "Not booked yet: Blaze Phone hasn't shown a confirmation page or number, so nothing is booked, ordered or paid as far as I can see."

    /**
     * The reply with any unbacked booked / paid / confirmed sentence replaced by [HONEST] (once, where the first was),
     * or null when nothing needs changing. [inPlay]: this turn used Blaze Phone or Blaze Phone has a task open.
     */
    fun review(reply: String, phoneDone: List<Pair<String, String>>, inPlay: Boolean): String? {
        if (!inPlay || reply.isBlank()) return null
        val bad = claims(reply)
        if (bad.isEmpty() || backed(phoneDone)) return null
        var t = reply
        var put = false
        for (s in bad.distinct()) {
            val i = t.indexOf(s); if (i < 0) continue
            t = t.substring(0, i) + (if (!put) HONEST else "") + t.substring(i + s.length)
            put = true
        }
        if (!put) t = HONEST + "\n\n" + t
        return t.replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }
}
