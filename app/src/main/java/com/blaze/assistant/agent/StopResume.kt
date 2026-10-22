package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.45.0 (#38): a clean Stop and a real Resume.
 *
 * On Stop (or a failed turn) the history is tidied ([tidy]):
 *  - a tool call the model asked for that never got its result (Stop came mid-tool) gets one honest result: it was
 *    interrupted and may or may not have happened, so the next step checks before doing it again (never a blind redo
 *    of a send or a payment);
 *  - the screenshot the stopped step was about to read is dropped, so the resumed call is small.
 *
 * What the task was and what was already done is kept as a [Pending] (the pinned goal from GoalPin and the turn's
 * receipts), saved with the chat. "Continue where you stopped" (the chip under the Stopped line and under an error) or a
 * plain "continue / resume / carry on" picks the task up: the goal is pinned again and [note] tells the model what was
 * done, what was interrupted, and not to repeat finished actions. Anything else the user says starts something new.
 *
 * Pure (org.json only); StopResumeTest.
 */
object StopResume {
    const val CHIP = "Continue where you stopped"
    const val STOPPED_LINE = "Stopped. Tap \u201cContinue where you stopped\u201d to pick it up again."
    const val INTERRUPTED = "Interrupted before this finished (the user tapped Stop, or the turn failed), so it may or may not have happened. " +
        "Check the result (the screen, the app, the sent list) before doing it again; never repeat it blindly."

    data class Pending(val goal: GoalPin.Goal, val done: List<String>, val interrupted: List<String>, val at: Long) {
        fun toJson(): JSONObject = JSONObject().put("request", goal.request).put("constraints", JSONArray(goal.constraints))
            .put("done", JSONArray(done)).put("interrupted", JSONArray(interrupted)).put("at", at)
    }

    fun fromJson(o: JSONObject?): Pending? {
        if (o == null) return null
        val req = o.optString("request").ifBlank { return null }
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        return Pending(GoalPin.Goal(req, list("constraints")), list("done"), list("interrupted"), o.optLong("at"))
    }

    private val resumeRe = Regex(
        """^\s*(?:continue where you (?:stopped|left off)|pick (?:it )?up where you (?:stopped|left off)|resume(?: (?:it|the task|that))?|continue(?: (?:it|the task|that|please))?|carry on|keep going|go on|endelea|pitiliza|twalilila|continuer|reprends?)\s*[.!?]*\s*$""",
        RegexOption.IGNORE_CASE)

    /** True for "Continue where you stopped" and plain resume words (a short follow-up, never a new request). */
    fun isResume(text: String): Boolean = resumeRe.matches(text.trim())

    data class Tidied(val interrupted: List<String>, val droppedShot: Boolean)

    /**
     * Tidies [history] in place after a Stop: answers every unanswered tool call of the newest assistant step with
     * [INTERRUPTED] and drops a trailing screenshot message (user content holding an image_url) after it.
     * Returns the interrupted tool names.
     */
    fun tidy(history: JSONArray): Tidied {
        var dropped = false
        // 1) the trailing screenshot(s) the stopped step was about to read
        while (history.length() > 0) {
            val last = history.optJSONObject(history.length() - 1) ?: break
            if (last.optString("role") == "user" && hasImage(last)) { history.remove(history.length() - 1); dropped = true } else break
        }
        // 2) the newest assistant message with tool calls, and which of them got a result
        var ai = -1
        for (i in history.length() - 1 downTo 0) {
            val m = history.optJSONObject(i) ?: continue
            val role = m.optString("role")
            if (role == "assistant" && (m.optJSONArray("tool_calls")?.length() ?: 0) > 0) { ai = i; break }
            if (role == "user" || (role == "assistant")) break   // a newer plain turn: nothing dangling
        }
        if (ai < 0) return Tidied(emptyList(), dropped)
        val calls = history.getJSONObject(ai).getJSONArray("tool_calls")
        val answered = HashSet<String>()
        for (i in ai + 1 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optString("role") == "tool") answered += m.optString("tool_call_id")
        }
        val out = ArrayList<String>()
        for (i in 0 until calls.length()) {
            val c = calls.optJSONObject(i) ?: continue
            val id = c.optString("id")
            if (id.isEmpty() || id in answered) continue
            val name = c.optJSONObject("function")?.optString("name").orEmpty()
            history.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("name", name).put("content", INTERRUPTED))
            out += name
        }
        return Tidied(out, dropped)
    }

    private fun hasImage(m: JSONObject): Boolean {
        val c = m.opt("content") as? JSONArray ?: return false
        return (0 until c.length()).any { c.optJSONObject(it)?.optString("type") == "image_url" }
    }

    /** The note the resumed turn's message carries (the model reads it; the bubble shows only what the user tapped). */
    fun note(p: Pending): String = buildString {
        append("[Resuming the task the user stopped: \u201c").append(p.goal.request.take(600)).append("\u201d.")
        if (p.goal.constraints.isNotEmpty()) append(" Their constraints still hold: ").append(p.goal.constraints.joinToString(" | ") { "\u201c$it\u201d" }).append('.')
        if (p.done.isNotEmpty()) append(" Already done (don't redo): ").append(p.done.take(12).joinToString("; ")).append('.')
        if (p.interrupted.isNotEmpty()) append(" Interrupted mid-way (check whether it happened before repeating): ").append(p.interrupted.distinct().joinToString(", ")).append('.')
        append(" Continue from where it stopped.]")
    }

    /** The finished actions of a turn's receipts, as the model should read them ("Alarm 07:00: verified"). */
    fun doneLines(log: List<Receipt.Entry>): List<String> = log.filter { Receipt.isAction(it.tool) }.map { e ->
        val l = e.label.ifBlank { e.tool.replace('_', ' ') }
        "$l: " + when (e.status) { ToolVerify.Status.VERIFIED -> "done and checked"; ToolVerify.Status.UNVERIFIED -> "done, not confirmed"; ToolVerify.Status.FAILED -> "failed" }
    }.distinct().takeLast(12)

    /** A Pending for a stopped turn ([goal] null or blank = nothing to resume). */
    fun pending(goal: GoalPin.Goal?, done: List<String>, interrupted: List<String>, at: Long = System.currentTimeMillis()): Pending? =
        goal?.takeIf { it.request.isNotBlank() }?.let { Pending(it, done, interrupted, at) }
}
