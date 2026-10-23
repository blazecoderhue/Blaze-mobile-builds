package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * v0.23.2: no fake claims about views. Blazeinchue asked for a 3D rocket and got "I'm on that tall modern 3D
 * rocket… view it in the design section", then "I've updated the design to show the engine" and "I've
 * opened the design view", with no design ever made.
 *
 * A final reply that says it created, updated or opened something in a view ([ViewRouter.specs]) is
 * checked against the tool calls that SUCCEEDED this turn. With no backing call the agent re-prompts once
 * ([nudge]); if it still isn't done, the claim sentences are replaced with an honest line ([honest]).
 * Pure Kotlin, unit-tested in ClaimGuardTest.
 */
object ClaimGuard {

    /** A tool call that succeeded this turn. */
    data class Call(val name: String, val args: JSONObject = JSONObject())

    private val ic = setOf(RegexOption.IGNORE_CASE)

    /** Did a tool result say it worked? (Same failure words the rest of the agent uses.) */
    fun succeeded(result: String): Boolean {
        val r = result.trimStart()
        return r.isNotEmpty() && !Regex("""^(Error|Couldn't|Could not|I couldn't|Can't|Cannot|Not |No |Need |Missing|Permission denied|The user didn't|Refused|Failed|No such|Unknown|Not in the|Nothing |There's no|The user did NOT|The user declined|The user chose|That MCP tool|That changed nothing)""", ic).containsMatchIn(r)
    }

    /** Words that name each view's things in a claim. Archive is read-only, so nothing there can be claimed. */
    private val nouns: Map<String, String> = mapOf(
        "design" to """designs?|preview|3-?d|mock-?ups?|3d scenes?|renders?|landing pages?|web ?pages?|websites?|posters?|flyers?|slides?|slide deck|diagrams?|charts?|floor ?plans?|wire-?frames?|prototypes?|layouts?|rockets?|artwork""",
        "learn" to """flash ?cards?|decks?|learn|quiz(?:zes)?""",
        "schedule" to """reminders?|routines?|alarms?|schedule|triggers?|calendar events?""",
        "gallery" to """images?|pictures?|photos?|gallery|logos?|wallpapers?|beats?|songs?|illustrations?""",
        "skills" to """skills?""",
        "projects" to """projects?""",
        "memory" to """memory|memories""",
        "browser" to """browser|tabs?""",
    )

    private const val VERBS = """created|made|built|generated|designed|drawn|drew|rendered|updated|changed|modified|edited|adjusted|tweaked|added|opened|saved|set up|scheduled|put|placed|switched|redesigned|rebuilt|shown|showing|displayed"""
    // "I've updated the design", "I have opened…", "the design has been updated", "it's now in…", "done, I added…"
    // v0.27.x: also a sentence that opens with the verb ("Opened the Solar Panel diagram in Design view.").
    private val pastClaim = Regex("""^\s*(?:$VERBS)\b|\b(?:i|i've|i have|i just|i've just|we've|it's|it is|it has been|has been|have been|is now|are now|done|all done)\b[^.!?\n]{0,30}?\b(?:$VERBS)\b""", ic)
    // "view it in the design section", "you can see it in Design", "it's in the Learn view", "open the design view to see"
    private fun whereClaim(n: String) = Regex("""\b(?:view|see|find|check|open|look at|explore|orbit|tap)\b[^.!?\n]{0,30}\b(?:in|on|from)\s+(?:the\s+|your\s+)?(?:$n)\b(?:\s+(?:section|view|tab|screen|page|panel))?|""" +
        """\b(?:it's|it is|they're|they are|now)\s+(?:now\s+)?(?:in|on)\s+(?:the\s+|your\s+)(?:$n)\s+(?:section|view|tab|screen)|""" +
        """\bhere(?:'s| is) (?:your|the) (?:new |updated )?(?:$n)\b""", ic)
    // Inside the "I … verb" part only, so "I've updated the design, let me know" still counts.
    private val negation = Regex("""\b(not|never|unable|failed|cannot|will|would|could|should|shall|can|going to|about to)\b|n't\b|'ll\b""", ic)
    private val pastTime = Regex("""\b(earlier|before|last time|previously|yesterday|already)\b""", ic)

    private fun sentences(text: String): List<String> = text.split(Regex("""(?<=[.!?…])\s+|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }

    /** The claim sentences in [text] about [viewId] (empty when there are none). */
    fun claims(text: String, viewId: String): List<String> {
        val n = nouns[viewId] ?: return emptyList()
        val noun = Regex("""\b(?:$n)\b""", ic)
        val where = whereClaim(n)
        return sentences(text).filter { s ->
            if (pastTime.containsMatchIn(s)) return@filter false
            // "I've opened the website in the browser" is a Browser claim, not a Design one.
            if (viewId == "design" && Regex("""\bbrowser\b""", ic).containsMatchIn(s)) return@filter false
            val w = where.containsMatchIn(s) && !Regex("""\b(couldn't|can't|cannot|unable|failed|didn't|haven't|wasn't|isn't|not yet|once|when it's|after)\b""", ic).containsMatchIn(s)
            val p = noun.containsMatchIn(s) && pastClaim.findAll(s).any { m -> !negation.containsMatchIn(m.value) }
            w || p
        }
    }

    /** Does a succeeded call put something in [spec]'s view? */
    fun backs(spec: ViewRouter.Spec, c: Call): Boolean {
        if (c.name == "open_view") return c.args.optString("view").lowercase().let { it == spec.id || (spec.id == "archive" && it == "chats") }
        return when (spec.id) {
            "design" -> c.name == Designs.TOOL || c.name == "preview_server" ||
                (c.name in setOf("file_write", "file_edit") && Regex("""(?i)\.(html?|svg|css|js)$""").containsMatchIn(c.args.optString("path").trim()))
            "gallery" -> c.name in setOf("generate_image", "gallery", "run_code")
            // v0.27.x: showing / listing memories is backed by a lookup too.
            "memory" -> c.name in setOf("memory_save", "memory_update", "memory_delete", "remember", "forget", "memory_search", "recall_memory")
            "browser" -> c.name.startsWith("browser_") || c.name in setOf("open_url", "navigate_to")
            else -> c.name in spec.tools
        }
    }

    /** The first view the reply claims to have changed with no succeeded call backing it, or null. */
    fun unbacked(reply: String, ok: List<Call>): ViewRouter.Spec? {
        if (reply.isBlank()) return null
        for (s in ViewRouter.specs) {
            if (s.id == "archive") continue
            if (claims(reply, s.id).isEmpty()) continue
            if (ok.none { backs(s, it) }) return s
        }
        return null
    }

    /**
     * v0.27.x: is a claim about [spec]'s view something this request is about? Only then is the model re-prompted to
     * do the work there. "show me your memories" answered with "Opened the solar panel diagram in Design" is not a
     * reason to go and open Design; the stray claim is dropped instead ([drop]).
     */
    fun relevant(spec: ViewRouter.Spec, userText: String, turnView: String? = null): Boolean {
        if (turnView == spec.id) return true
        if (ViewRouter.route(userText)?.id == spec.id) return true
        val n = nouns[spec.id] ?: return false
        return Regex("""\b(?:$n)\b""", ic).containsMatchIn(userText)
    }

    /** [reply] with the claim sentences about [spec] taken out ("" when nothing else was said). */
    fun drop(reply: String, spec: ViewRouter.Spec): String {
        val bad = claims(reply, spec.id).toSet()
        val rest = sentences(reply).filterNot { it in bad }.joinToString(" ").trim()
        return if (rest.length < 12) "" else rest
    }

    /** The one re-prompt: do it for real now. */
    fun nudge(spec: ViewRouter.Spec, current: Designs.Current?): String = "[Blaze check] Your reply says you made, changed or opened something in ${spec.name}, " +
        "but no ${spec.name} tool succeeded this turn, so nothing is there. Do it for real now: " + when (spec.id) {
            "design" -> if (current != null) "the current design is \u201c${current.title}\u201d (${current.kind}, ${current.path}); change it with design action=update " +
                "(3D: set_parts/remove_parts; pages: edits or html), or design action=open to show it, or design action=create for something new. "
                else "design action=create (3D: kind=3d with parts=[…]; pages and everything 2D: html; art: svg). "
            "learn" -> "flashcards action=add (deck, cards=[{front, back}]), then open_view view=learn. "
            "schedule" -> "create_routine / schedule_command / create_trigger (set_alarm or add_calendar_event for one-off times). "
            "gallery" -> "generate_image (or run_code saving under /root/phone/). "
            "skills" -> "save_workflow, write_skill or install_skill. "
            "projects" -> "open_view view=projects (or update_brief). "
            "memory" -> "memory_save / remember. "
            "browser" -> "browser_open and the other browser_ tools. "
            else -> "${spec.tools.joinToString(" / ")}. "
        } + "Then reply in one or two lines about what the tool actually did (its real result: 2D or 3D, what it shows). If it can't be done, say so plainly."

    /** The claim sentences swapped for an honest line (the rest of the reply is kept). */
    fun honest(reply: String, spec: ViewRouter.Spec): String {
        val bad = claims(reply, spec.id).toSet()
        val rest = sentences(reply).filterNot { it in bad }.joinToString(" ").trim()
        val line = "I didn't manage to " + when (spec.id) {
            "design" -> "build or change it in Design this time, so nothing new is there yet"
            "learn" -> "make the deck in Learn this time, so there are no new cards yet"
            "schedule" -> "set that up in Schedule this time, so nothing is scheduled yet"
            "gallery" -> "make it in the Gallery this time, so nothing new is there yet"
            "memory" -> "save that to memory this time"
            else -> "do that in ${spec.name} this time, so nothing changed there yet"
        } + ". Say \u201ctry again\u201d and I'll have another go."
        return if (rest.length < 12) line else "$line\n\n$rest"
    }

    // ---------------- v0.28.7: sends ----------------

    /** Tools whose success means a message really left the phone. */
    val SEND_TOOLS = setOf("message_contact", "send_sms", "send_text", "reply_to_notification", "gmail_send", "send_email")

    private val sendClaimRe = Regex("""\b(?:message|text|picture|image|photo|it)\s+(?:was\s+|has been\s+)?sent\b|^\s*sent\b|\b(?:i've|i have|i just|i)\s+(?:sent|messaged|texted|delivered|forwarded)\b|\bsuccessfully sent\b""", ic)

    /** Is this request about sending something to someone (not a question about an earlier send)? */
    fun asksToSend(userText: String): Boolean =
        Regex("""\b(send|message|text|whatsapp|telegram|sms|forward|tell|reply)\b""", ic).containsMatchIn(userText) &&
            !Regex("""^\s*(did|have|has|was|were)\b""", ic).containsMatchIn(userText)

    /** Sentences in [reply] that say a message went out. */
    fun sendClaims(reply: String): List<String> = sentences(reply).filter { s ->
        sendClaimRe.containsMatchIn(s) && !Regex("""\b(not|never|couldn't|didn't|wasn't|haven't|unable|failed|once|when|after|if)\b""", ic).containsMatchIn(s)
    }

    /** A reply claiming a send with no send tool succeeding this turn → the honest version, else null. */
    fun unbackedSend(reply: String, ok: List<Call>): String? {
        val bad = sendClaims(reply)
        if (bad.isEmpty() || ok.any { it.name in SEND_TOOLS }) return null
        val rest = sentences(reply).filterNot { it in bad }.joinToString(" ").trim()
        val line = "I haven't actually sent it: no message left the phone this time. Say \u201ctry again\u201d and I'll send it properly."
        return if (rest.length < 12) line else "$line\n\n$rest"
    }
}
