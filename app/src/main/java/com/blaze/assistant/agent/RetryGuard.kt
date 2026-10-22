package com.blaze.assistant.agent

/**
 * v0.27.x: the agent's self-checks (FakeCall, ClaimGuard, refusal and verify nudges) stay on the user's request.
 *
 * Joey asked "show me your memories". The memory lookup ran, the answer said "I checked…", FakeCall thought nothing
 * had run (lookups didn't count) and re-prompted ("Doing it properly this time"); the next answer mentioned an
 * old diagram, ClaimGuard re-prompted again and handed the model the stale current design ("Doing it for real in
 * Design"), and the model opened a solar-panel diagram from an older turn: 1m40s for the wrong thing.
 *
 * Now:
 *  - every corrective re-prompt carries the user's latest message and says to stay on it ([anchor]);
 *  - a turn gets at most [MAX] corrective re-prompts in total, across every kind;
 *  - a claim about a view the user's request has nothing to do with is never "fixed" by doing work in that view
 *    ([ClaimGuard.relevant]): the stray sentence is dropped instead;
 *  - the step labels read like a person ([label]), not "Doing it properly this time".
 * Pure Kotlin, unit-tested in IntentHonestyTest.
 */
object RetryGuard {
    /** Corrective re-prompts per turn, all kinds together. */
    /** Re-prompts per turn, all kinds together (claim, proof, goal, leak, refusal, empty, echo, view, desktop): review 22 Oct #4. */
    const val MAX = 2

    enum class Kind { FAKE_CALL, CLAIM, REFUSAL, VERIFY, OFF_TOPIC }

    /** The user's latest request, appended to every corrective re-prompt so a retry never wanders off. */
    fun anchor(nudge: String, userText: String): String {
        val u = userText.trim().replace(Regex("\\s+"), " ").take(400)
        if (u.isEmpty()) return nudge
        return nudge + "\n[The user's latest message: \u201c$u\u201d. Stay on exactly that request. Don't switch to a different task, " +
            "don't reopen or show work from earlier turns (designs, files, pages) unless this message asks for it, and don't open a view it " +
            "didn't ask for. If a plain text answer is the right output, give that.]"
    }

    /** The re-prompt when the whole answer was a claim about something unrelated to the request. */
    const val OFF_TOPIC = "[Blaze check] Your reply talked about something the user didn't ask for this turn, and nothing was done there. " +
        "Answer the user's actual request now, in plain language, using a tool only if the request needs one."

    /** Friendly step labels for the work card. */
    fun label(kind: Kind, view: String = ""): String = when (kind) {
        Kind.FAKE_CALL -> "Double-checking my answer"
        Kind.CLAIM -> if (view.isNotBlank()) "Making sure it's really in $view" else "Double-checking my answer"
        Kind.REFUSAL -> "Trying another way"
        Kind.VERIFY -> "Trying another way"
        Kind.OFF_TOPIC -> "Getting back to your question"
    }

    /** Labels that must never show again (tests keep them out). */
    val RETIRED = listOf("Doing it properly this time", "Doing it for real")
}
