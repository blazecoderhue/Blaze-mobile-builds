package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The chat lane: greetings, thanks, small talk and plain knowledge questions go out as ONE call
 * with a short prompt and no tools (roughly a tenth of a full agent turn), which keeps free
 * quotas for real work. If the model sees it actually needs live data or an action, it answers
 * with [ESCAPE] and the turn reruns on the full tool lane.
 */
object ChatLane {
    const val ESCAPE = "[[TOOLS]]"

    private val smalltalk = Regex("""^(hi|hey|hello|yo|sup|hiya|howdy|good (morning|afternoon|evening|night)|gm|gn|morning|night|thanks?( you| u)?|thx|ty|cheers|ok(ay)?|cool|nice|great|awesome|lol|haha+|bye|see (you|ya)|later|sweet dreams|love (you|it)|you'?re (the best|awesome|great|amazing)|how are (you|u)|how'?s it going|what'?s up|wassup|who are you|what are you|what'?s your name)\b""", RegexOption.IGNORE_CASE)

    // Anything that smells of live data, the user's own stuff or an action goes to the tool lane.
    private val needsTools = Regex("""\b(weather|news|latest|current|currently|today|tonight|tomorrow|yesterday|now|this week|price|prices|cost|exchange rate|score|scores|search|google|look ?up|find|near|nearby|open|close|send|call|text|sms|whatsapp|remind|reminder|alarm|timer|set|turn|switch|enable|disable|play|pause|stop|start|check|my|mine|our|download|install|book|order|buy|schedule|calendar|email|mail|inbox|message|messages|screen|phone|app|apps|file|files|photo|battery|wifi|bluetooth|volume|time|date|run|code|shell|terminal|git|repo|website|url|link|http|again|same|do it|go ahead|continue|retry|remember|forget|delete|cancel|image|images|picture|pic|draw|paint|generate|create|make|design|logo|wallpaper|beat|beats|music|song|audio|gallery|python|script)\b|https?://|\.(com|org|zm|net)\b|\d{3,}""", RegexOption.IGNORE_CASE)

    private val knowledge = Regex("""^(what|who|why|how|when|where|which|explain|define|tell me|describe|write|compose|give me|translate|summari[sz]e|compare|is|are|can|could|should|would|do|does|did)\b""", RegexOption.IGNORE_CASE)

    /** True when [text] can be answered by plain chat. [lastAssistant] = Blaze's previous message. */
    fun fits(text: String, lastAssistant: String): Boolean {
        if (!Settings.bool("chat_lane", true)) return false
        val t = text.trim()
        if (t.isEmpty() || t.length > 300 || t.startsWith("[") || t.startsWith("/")) return false
        if (ToolRouter.guess(t).isNotEmpty()) return false
        // v0.45.9 (ToolTruth): this lane sends no tools, so anything tool-shaped goes to the full lane.
        if (ToolTruth.toolShaped(t) || SaveFile.wantsSave(t)) return false
        // v0.45.9: "ok" / "yes" to a calendar or reminder offer needs the schedule tools, not the plain chat lane.
        if (TurnContext.scheduleFollowUp(t, lastAssistant)) return false
        // v0.27.x: "how do I get / install / enable X" goes to the tool lane, so Blaze can offer to do it (AnswerStyle).
        if (AnswerStyle.offerVerb(t) != null) return false
        val small = smalltalk.containsMatchIn(t)
        // A short reply to a question Blaze just asked is probably part of a task ("yes", "the second one").
        if (!small && lastAssistant.trim().endsWith("?") && t.split(Regex("\\s+")).size < 8) return false
        if (needsTools.containsMatchIn(t) && !(small && t.length < 40)) return false
        return small || knowledge.containsMatchIn(t)
    }

    /** Short system prompt + the recent conversation as plain text turns. */
    fun messages(full: JSONArray, userText: String, persona: String, name: String?, context: String = ""): JSONArray {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val sys = buildString {
            append(persona).append(' ')
            if (name != null) append("The user's name is $name. ")
            // v0.45.7: the reply language (Auto = match the user), same line as the full prompt.
            append(ReplyLanguage.line(ReplyLanguage.current()).trim()).append(' ')
            append("Reply like a sharp friend texting back: short, warm, sure of yourself, the answer first, one or two sentences unless they ask for more. No filler like \"let me know if\". ")
            append("It is $now. ")
            // v0.42.0: a 2–3 line Awareness summary (what is playing / just happened), never the full block.
            append(Awareness.summaryBlock().trim()).append(' ')
            // v0.31.1: same formatting rule as the full prompt (tables never in code fences, HTML tags allowed).
            append(AnswerStyle.FORMAT.trimStart()).append(' ')
            append("You are Blaze: you CAN generate images, make beats and music, run Python in your own Linux terminal, browse, and use the user's phone, just not in this short reply. ")
            append("You run on the user's phone: never give desktop or text-editor advice and never say you can't save, run, open or check something. ")
            append("You have NO tools in this reply. If answering properly needs live or current information (news, weather, prices, scores, anything about today), ")
            append("anything on the user's phone, apps, messages, files, calendar or settings, or doing or making anything (an image, a beat, a file, any action), or if they ask whether you can do something, reply with exactly $ESCAPE and nothing else. ")
            if (Settings.followups) append("At the very end add one line exactly like <<follow-ups: first | second | third>> with three short next requests (max 6 words each) written as the user would say them. ")
            append(Memory.promptBlock(userText).take(1500))
            // v0.45.9: the assistant rules and the [context] block, last (Site persona v1.0.1 `context_block`).
            append(Duo.ASSISTANT_RULES)
            append(GreetName.RULE)
            if (context.isNotBlank()) { append(TurnContext.RULE); append('\n').append(context) }
        }
        val out = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        val flat = ToolCalls.flatten(full)
        val turns = (0 until flat.length()).map { flat.getJSONObject(it) }
            .filter { it.optString("role") != "system" && it.opt("content") is String }
            // v0.30.6: no [m7] message ids here (this short prompt has no rule about them).
            .map { m -> JSONObject().put("role", m.optString("role")).put("content", m.optString("content").let { c -> if (m.optString("role") == "user") RapidMessages.untag(c) else c }.let { c -> if (c.length > 1500) c.take(1500) + "…" else c }) }
            .takeLast(8).dropWhile { it.optString("role") != "user" }
        turns.forEach { out.put(it) }
        return out
    }

    fun escaped(text: String) = text.contains("[[TOOLS", true) || text.trim().equals("TOOLS", true)
}
