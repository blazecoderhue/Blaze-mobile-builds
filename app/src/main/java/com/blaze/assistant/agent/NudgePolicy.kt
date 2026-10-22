package com.blaze.assistant.agent

/**
 * v0.45.0 (#19) re-prompts only when they can help. Each nudge is another full model call. For a claim with nothing
 * behind it ([ActionProof] / ClaimCheck Unbacked):
 *  - the proving tool wasn't even sent this turn → [Way.LOAD_AND_ASK]: its group is loaded and the model asked once;
 *  - nothing at all ran this turn (the model just said it) → [Way.ASK]: one re-prompt with the tool there;
 *  - the tool was there and other tools ran, but not that one → [Way.HONEST]: no extra call; the claim becomes the
 *    honest line and the reply gets a "Try again" chip ([TRY_AGAIN]).
 * Pure; NudgePolicyTest.
 */
object NudgePolicy {
    enum class Way { LOAD_AND_ASK, ASK, HONEST }

    const val TRY_AGAIN = "Try again"

    fun proof(a: ActionProof.Action, sent: Collection<String>, called: Collection<String>): Way = when {
        sent.none { it in a.strong } -> Way.LOAD_AND_ASK
        called.none { it != "find_tool" && it != "load_tools" } -> Way.ASK
        else -> Way.HONEST
    }
}
