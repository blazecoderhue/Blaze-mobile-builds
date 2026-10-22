package com.blaze.assistant.agent

import org.json.JSONArray

/**
 * Automatic compaction (v0.26.0), pure part (AutoCompactTest). Replaces the v0.15.0 "This chat is getting long ·
 * Fresh chat →" banner: a long chat stays the same chat, and nothing is asked of the user.
 *
 *  - Everything stays on screen. Only what is SENT to the model is compacted: the rolling state note
 *    ([LongChat] summary, persisted with the chat as lc_summary / lc_upto, so it is never redone each turn) plus the
 *    recent turns word for word.
 *  - Older turns fold into the note when the history is over the long-chat budget (as before), or when the whole
 *    request (system prompt + tools + history) nears the model's context window ([NEAR_PERCENT]).
 *  - When a provider still refuses the request as too long, the chat folds harder ([FORCED_KEEP] messages kept
 *    word for word) and retries once, instead of telling the user to start a new chat. If the summariser itself
 *    fails then, a clipped transcript of the older turns stands in for the note ([fallbackNote]).
 */
object AutoCompact {
    /** Compaction starts when the estimated request reaches this share of the context window. */
    const val NEAR_PERCENT = 75
    /** Messages kept word for word after a "too long" refusal (moved back to a user message). */
    const val FORCED_KEEP = 4

    /** The window the meter and this check assume: the model's own, or 32k when unknown. */
    fun window(contextWindow: Int): Int = LongChat.meterLimit(contextWindow)

    /** True when a request of [promptTokens] is near (≥ [NEAR_PERCENT]%) the model's context window. */
    fun nearLimit(promptTokens: Int, contextWindow: Int): Boolean {
        val w = window(contextWindow)
        return promptTokens.toLong() * 100 >= w.toLong() * NEAR_PERCENT
    }

    /** The first history index the rolling note does not cover yet (a stale index past the end resets to 0). */
    fun upto(h: JSONArray, st: LongChat.State): Int = if (st.upto > h.length()) 0 else st.upto

    /** Where the word-for-word tail starts for this fold: [LongChat.KEEP_TAIL] normally, [FORCED_KEEP] after a refusal. */
    fun keep(forced: Boolean): Int = if (forced) FORCED_KEEP else LongChat.KEEP_TAIL

    /**
     * Should older turns fold into the note before this model call?
     * [overheadTokens] = the system prompt and tool list of the last request; [forced] = the provider just said
     * the request is too long. Never true when there is nothing older than the tail to fold.
     */
    fun shouldCompact(h: JSONArray, st: LongChat.State, budget: Int, overheadTokens: Int, contextWindow: Int, forced: Boolean = false): Boolean {
        val from = upto(h, st)
        if (LongChat.tailStart(h, keep(forced)) <= from) return false
        if (forced) return true
        if (LongChat.needsCompaction(h, st, budget)) return true
        return nearLimit(overheadTokens + LongChat.tokens(h, LongChat.sendFrom(h, st)), contextWindow)
    }

    /** The history range [from, to) that this fold summarises: older turns only; the tail is always kept verbatim. */
    fun range(h: JSONArray, st: LongChat.State, forced: Boolean = false): IntRange {
        val from = upto(h, st)
        val to = LongChat.tailStart(h, keep(forced))
        return from until maxOf(from, to)
    }

    /** Should this request use the summary even though the history alone is under the budget? */
    fun pressure(overheadTokens: Int, historyTokens: Int, contextWindow: Int, squeezed: Boolean): Boolean =
        squeezed || nearLimit(overheadTokens + historyTokens, contextWindow)

    /**
     * The note to keep when the summariser gave nothing back on a forced fold: the previous note plus a clipped
     * transcript of the turns being folded, newest kept when it has to be cut, at most [LongChat.SUMMARY_MAX].
     */
    fun fallbackNote(prev: String, h: JSONArray, from: Int, to: Int): String {
        val t = LongChat.transcript(h, from, to).trim()
        val head = prev.trim().let { if (it.isBlank()) "" else "$it\n\n" } + "Older turns (clipped):\n"
        val room = (LongChat.SUMMARY_MAX - head.length).coerceAtLeast(200)
        val body = if (t.length <= room) t else "…" + t.takeLast(room - 1)
        return (head + body).take(LongChat.SUMMARY_MAX)
    }

    /** True when a provider error means the request was too long for the model's context. */
    fun isContextError(raw: String?, code: Int = 0): Boolean {
        if (code == 413) return true
        val low = raw.orEmpty().lowercase()
        if (listOf("context length", "context_length", "maximum context", "context window", "too many tokens", "reduce the length",
                "prompt is too long", "input is too long", "request too large", "too large for").any { it in low }) return true
        return "context" in low && ("length" in low || "too long" in low || "token" in low)
    }
}
