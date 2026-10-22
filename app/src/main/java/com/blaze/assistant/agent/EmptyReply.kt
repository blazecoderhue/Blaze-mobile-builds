package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.26.2 root-cause fix for the "Done." replies (Jack, 2026-10-14: "what sound profile is my phone set to?" →
 * "Done.", reasoning pane "The user asks:", work card "1 step"). The path: Blaze Cloud's free upstream streamed
 * only reasoning and then ended the stream; LlmClient.readStream returned it as a normal message with empty
 * content and no tool calls; the agent loop took that as the final answer; addReply turned the empty text into
 * "Done.".
 *
 * Now an empty model reply is a failed attempt ([isEmpty]): Cloud.drive asks again once on the same route without
 * streaming and with [NUDGE], then once more on another model (blaze_avoid), then fails with [FAILED]. The agent
 * loop never shows an empty answer: [finalText] gives a specific line only when the user asked for an action and
 * a tool really succeeded ("Turned on Do Not Disturb."); otherwise it nudges once and then shows an error.
 * Pure (EmptyReplyTest).
 */
object EmptyReply {
    const val NUDGE = "[Blaze] Your last reply came back empty (no answer text). Answer the user now in one or two sentences, " +
        "from what you already know, the 'Phone now' line and the tool results above. Don't call a tool unless the answer truly needs one."
    const val FAILED = "The AI came back without an answer, a few times in a row. Tap Retry and I'll ask again."
    /** LlmException code for "the model answered with nothing". */
    const val CODE = -2
    /** Empty replies one request tolerates before it fails (same route without streaming, then another model). */
    const val TRIES = 3

    private val think = Regex("<(think|thought|thinking)>[\\s\\S]*?(</\\1>|$)", RegexOption.IGNORE_CASE)
    private val follow = Regex("<{1,2}/?follow[- ]?ups?:[^>]*>{0,2}", RegexOption.IGNORE_CASE)

    /** The words a person would see from a model message's content (reasoning tags and follow-up chips removed). */
    fun visible(content: Any?): String {
        val raw = when (content) {
            is String -> content
            is JSONArray -> (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text").orEmpty() }
            else -> ""
        }
        return raw.replace(think, "").replace(follow, "").trim()
    }

    /** No visible text and no tool calls: a reasoning-only or blank reply. It never counts as an answer. */
    fun isEmpty(msg: JSONObject): Boolean =
        (msg.optJSONArray("tool_calls")?.length() ?: 0) == 0 && visible(if (msg.isNull("content")) null else msg.opt("content")).isEmpty()

    /** [messages] plus the nudge (as a user turn), for the retry after an empty reply. */
    fun withNudge(messages: JSONArray): JSONArray = JSONArray(messages.toString()).put(JSONObject().put("role", "user").put("content", NUDGE))

    // ---------------- the agent loop's last line of defence ----------------

    private val question = Regex("^(what|what's|whats|which|who|whom|whose|when|where|why|how|is|am|are|was|were|do|does|did|can|could|will|would|should|have|has|tell me|show me what|any)\\b", RegexOption.IGNORE_CASE)
    private val action = Regex("^(?:please |can you |could you |blaze,? )?(turn|switch|set|put|open|close|send|text|message|call|ring|dial|play|pause|stop|start|mute|unmute|silence|enable|disable|toggle|make|create|add|remove|delete|schedule|remind|book|save|share|install|clear|lock|raise|lower|increase|decrease)\\b", RegexOption.IGNORE_CASE)

    /** An instruction to do something (not a question about something). */
    fun isAction(userText: String): Boolean {
        val t = userText.trim().lowercase().replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.endsWith("?")) return false
        if (question.containsMatchIn(t) && !t.startsWith("can you") && !t.startsWith("could you")) return false
        return action.containsMatchIn(t)
    }

    private val past = mapOf(
        "turning" to "Turned", "switching" to "Switched", "setting" to "Set", "putting" to "Put", "opening" to "Opened", "closing" to "Closed",
        "sending" to "Sent", "texting" to "Texted", "messaging" to "Messaged", "calling" to "Called", "dialling" to "Dialled", "dialing" to "Dialed",
        "playing" to "Played", "pausing" to "Paused", "stopping" to "Stopped", "starting" to "Started", "muting" to "Muted", "unmuting" to "Unmuted",
        "enabling" to "Enabled", "disabling" to "Disabled", "making" to "Made", "creating" to "Created", "adding" to "Added", "removing" to "Removed",
        "deleting" to "Deleted", "scheduling" to "Scheduled", "saving" to "Saved", "sharing" to "Shared", "installing" to "Installed",
        "clearing" to "Cleared", "locking" to "Locked", "raising" to "Raised", "lowering" to "Lowered", "booking" to "Booked", "silencing" to "Silenced",
        "toggling" to "Toggled", "changing" to "Changed", "updating" to "Updated", "writing" to "Wrote", "copying" to "Copied",
    )

    /** "Turning on Do Not Disturb" → "Turned on Do Not Disturb." A step line that isn't "<verb>ing …" becomes "Done: <step>.". */
    fun doneLine(step: String): String {
        val s = step.trim().trimEnd('.', '…').trim()
        if (s.isEmpty()) return ""
        val first = s.substringBefore(' ')
        val rest = s.substringAfter(' ', "")
        val p = past[first.lowercase()]
        return if (p != null) (if (rest.isNotEmpty()) "$p $rest." else "$p.") else "Done: ${s.replaceFirstChar { it.lowercase() }}."
    }

    /**
     * What to show when the model's final answer is empty: a specific "Turned on …" line when [userText] was an
     * action and a tool succeeded this turn ([okStep] = that tool's step line), else null (the caller nudges once,
     * then shows an error). Never a bare "Done.".
     */
    fun finalText(userText: String, okStep: String?): String? =
        if (isAction(userText) && !okStep.isNullOrBlank()) doneLine(okStep).ifEmpty { null } else null
}
