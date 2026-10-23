package com.blaze.assistant.agent

/**
 * v0.45.2 faster verification that stays real. The rules (VerifyPolicyTest):
 *
 *  (a) A plain answer turn (no action tool ran, nothing claims an action) needs no verification step at all: only the
 *      cheap local final-claim rule (ClaimCheck: no "done" claim without a done receipt) runs. No re-prompt, no model.
 *  (b) An action is checked on the phone first ([ToolVerify.check]: app in front, setting read back, file there, next
 *      alarm, desktop port / process). A conclusive local result (verified or failed) is final: the model is told so and
 *      takes no screenshot or extra look. Only an inconclusive one leaves room for one model-side check.
 *  (c) Fast path: the local check gets [QUICK_MS]; if it hasn't finished, the turn goes on with the action marked
 *      "still checking" (never done), the check finishes in the background, and the receipt under the reply flips to
 *      done / failed / couldn't confirm when it ends, with the reason in "What I did".
 *  (d) A model-based check (the specialist self-review) runs on the quick route with [MODEL_CHECK_MS]; running out of
 *      time or failing gives "not checked" (said plainly), never a silent pass.
 *  (e) Settings: "Strict checks" ([PREF_STRICT]) brings back the old path: every check waits in line, as before.
 *
 * Risky one-shot actions (sending, calling, paying, deleting, installing; [OneShot.risky] and the send tools) always
 * wait for a conclusive check before the turn goes on, in both modes.
 */
object VerifyPolicy {
    const val PREF_STRICT = "verify_strict"
    /** The fast path's wait for the local check before the turn moves on. */
    const val QUICK_MS = 400L
    /** The old path's per-check limit (also the blocking limit for risky actions). */
    const val BLOCKING_MS = 6_000L
    /** A background check gives up after this and the action stays "couldn't confirm". */
    const val BACKGROUND_MS = 8_000L
    /** A model-based self-check gets this long on the quick route. */
    const val MODEL_CHECK_MS = 12_000L

    fun strict(): Boolean = try { Settings.bool(PREF_STRICT, false) } catch (_: Throwable) { false }

    enum class Mode {
        /** Nothing outside Blaze to read back: the result's own words decide (no wait). */
        WORDS,
        /** Wait for the local check (old path; risky actions; strict mode). */
        BLOCKING,
        /** Wait [QUICK_MS], then finish in the background. */
        QUICK_THEN_BACKGROUND,
    }

    /** True for actions that can't be undone and must have a conclusive check before the turn goes on. */
    fun risky(name: String): Boolean = OneShot.risky(name) || name in ToolVerify.SEND_TOOLS

    /** True when [ToolVerify.check] has a phone-side probe for this tool. */
    fun probed(name: String): Boolean = name in ToolVerify.PROBED || name == "device_setting" || name == "toggle_setting"

    fun mode(name: String, strict: Boolean): Mode = when {
        !probed(name) && !risky(name) -> Mode.WORDS
        strict || risky(name) -> Mode.BLOCKING
        else -> Mode.QUICK_THEN_BACKGROUND
    }

    /** (b) A verdict the phone settled (verified or failed): no model check after it. */
    fun conclusive(v: ToolVerify.Verdict): Boolean = v.status != ToolVerify.Status.UNVERIFIED

    /** (b) The model may spend a step on a check (screenshot, read_screen, status) only when the phone couldn't tell. */
    fun modelCheckAllowed(v: ToolVerify.Verdict, strict: Boolean): Boolean = if (strict) v.status != ToolVerify.Status.VERIFIED else !conclusive(v)

    /**
     * (a) A plain answer turn: no action tool ran this turn (lookups don't count). Its only check is the local claim rule
     * (ClaimCheck / ActionProof on the final text); the re-prompt there fires only when the text claims an action.
     */
    fun plainTurn(called: Collection<String>): Boolean = called.none { Receipt.isAction(it) && it !in setOf("find_tool", "load_tools") }

    /** The line the model sees on a result whose local check is still running (never "done"). */
    fun pendingTag(why: String): String = "[verified=pending: the phone is still checking (" + why.ifBlank { "reading it back" } +
        "). Don't say it's done; say you started it. The receipt under your reply updates when the check ends.]"

    /**
     * The status line for the model: [ToolVerify.tag], plus "checked on the phone" for a conclusive local result (no
     * screenshot or extra look needed) or the one-check allowance for an inconclusive one.
     */
    fun tag(result: String, v: ToolVerify.Verdict, fromProbe: Boolean, strict: Boolean): String {
        val base = ToolVerify.tag(result, v)
        if (strict || !fromProbe) return base
        return when (v.status) {
            ToolVerify.Status.VERIFIED -> base.removeSuffix("]") + " (checked on the phone; no screenshot or extra check needed)]"
            ToolVerify.Status.FAILED -> base
            ToolVerify.Status.UNVERIFIED -> base.removeSuffix("]") + " One quick check (read_screen or a screenshot) is allowed if it matters.]"
        }
    }

    /** (c) A background check that ended with nothing (timeout, error): honest "couldn't confirm". */
    fun timedOut(): ToolVerify.Verdict = ToolVerify.Verdict(ToolVerify.Status.UNVERIFIED, "the check didn't finish in time")

    /** (c) The receipt entry once its background check ended after the reply was shown. */
    fun flip(e: Receipt.Entry, v: ToolVerify.Verdict, afterReply: Boolean): Receipt.Entry =
        e.copy(status = v.status, pending = false,
            evidence = (v.why.ifBlank { if (v.status == ToolVerify.Status.VERIFIED) "read back on the phone" else "couldn't confirm it" }) +
                if (afterReply) " (checked after the reply)" else "")

    /** (d) The specialist review that couldn't run: said plainly under the result. */
    const val NOT_REVIEWED = "(Not self-checked: the review didn't finish in time, so this wasn't checked against the playbook.)"

    // ---------------- speed_stats ----------------

    /** Per-turn verification time: local checks (count, ms), model checks, background (pending) checks and flips. */
    class Stats {
        @Volatile var localN = 0; @Volatile var localMs = 0L
        @Volatile var modelN = 0; @Volatile var modelMs = 0L
        @Volatile var pending = 0; @Volatile var skipped = 0
        /** Wait the old path would have spent on the checks this turn made in the background (an estimate of time saved). */
        @Volatile var savedMs = 0L
        @Synchronized fun local(ms: Long) { localN++; localMs += ms }
        @Synchronized fun model(ms: Long) { modelN++; modelMs += ms }
        fun line(): String = if (localN + modelN + pending + skipped == 0) "" else
            "verify ${localMs + modelMs} ms (local $localN/${localMs} ms, model $modelN/${modelMs} ms" +
                (if (pending > 0) ", background $pending" else "") + (if (skipped > 0) ", plain-skip $skipped" else "") +
                (if (savedMs > 0) ", saved ~${savedMs} ms" else "") + ")"
    }
}

/**
 * v0.45.2: one turn's receipts and verification stats. Kept after the turn ends, so a check still running in the
 * background can flip the receipt of the reply it belongs to ([msgId]) instead of touching the next turn's.
 */
class VerifyBook {
    val log: MutableList<Receipt.Entry> = java.util.Collections.synchronizedList(mutableListOf())
    /** The reply bubble that carries this turn's receipt (-1 until the reply is shown). */
    @Volatile var msgId: Long = -1L
    val stats = VerifyPolicy.Stats()
    /** Actions whose last verdict was inconclusive (a following read_screen / screenshot counts as a model-side check). */
    @Volatile var openUnverified = 0

    companion object {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        /** Tools the model uses to check an action itself. */
        val CHECK_TOOLS = setOf("read_screen", "look_at_screen", "screenshot", "take_screenshot")
        fun isModelCheck(name: String, args: org.json.JSONObject): Boolean =
            name in CHECK_TOOLS
    }
}
