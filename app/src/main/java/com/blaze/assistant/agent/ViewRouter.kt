package com.blaze.assistant.agent

/**
 * Views first (v0.23.0). Blazeinchue's rule: Blaze always uses its views. Output that fits a view (a deck of
 * flashcards, a page, a reminder, a picture, a memory…) is made THERE, the view is opened or linked,
 * and the chat gets a short answer, never the whole thing dumped as text.
 *
 *  - [PROMPT]: the always-present VIEWS block of the system prompt (each view, what belongs there,
 *    the tool that writes to it, and the hard rule).
 *  - [route]: a cheap keyword/intent classifier (no model call) that picks the view a request needs.
 *    The agent loop turns it into a required-view hint ([hint]) and switches on that view's tools ([Spec.groups]).
 *  - [cardsFrom] / [deckName] / [shouldConvert]: the safety net for Learn. When the model still writes
 *    flashcards or a quiz as text without calling the flashcards tool, the agent turns that text into
 *    a Learn deck (or re-prompts once when nothing can be parsed).
 * Pure Kotlin (no Android), unit-tested in ViewRouterTest.
 */
object ViewRouter {

    /** Design cues that are clearly visual work on their own (v0.23.2: Design is for anything visual, not only pages). */
    const val DESIGN_STRONG = """\b(3-?d|three\.?js|webgl|render(?:ing)?|mock-?ups?|wire-?frames?|prototypes?|diagrams?|flow ?charts?|charts?|graphs? (?:of|for|showing)|""" +
        """infographics?|floor ?plans?|blueprints?|3d scenes?|scene of|ui|ux|app screens?|user interface|website|web ?site|web ?page|landing page|html|css|poster|flyer|portfolio|""" +
        """slides?|slide deck|presentation|(?:make|build|create) (?:a |an |me a )?model of|design (?:a|an|me|my|the|this))\b"""

    /** A thing to make or show ("make a rocket", "show me a car"): Design, unless picture words make it Gallery. */
    const val DESIGN_OBJECT = """\b(?:make|build|create|design|draw|model|sketch|visuali[sz]e|show me)\b.{0,40}\b(?:rockets?|spaceships?|space ?shuttles?|cars?|trucks?|bikes?|motorbikes?|""" +
        """planes?|aeroplanes?|airplanes?|jets?|drones?|ships?|boats?|buildings?|houses?|towers?|bridges?|rooms?|kitchens?|bedrooms?|offices?|robots?|chairs?|tables?|desks?|""" +
        """products?|games?|engines?|machines?)\b"""

    /** A view the agent writes to. [tools] = the tools that put things there; [groups] = ToolRouter groups to switch on. */
    data class Spec(val id: String, val name: String, val holds: String, val tools: List<String>, val groups: List<String>, val cue: Regex)

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** In priority order: the first that matches is the required view. */
    val specs = listOf(
        Spec("learn", "Learn", "flashcards, decks, quizzes and anything to study or memorise (spaced repetition)",
            listOf("flashcards", "open_view"), listOf("study"),
            r("""\b(flash ?cards?|quiz(?:zes)?|test me|(?<!case )study|studying|revise|revision|memori[sz]e|spaced repetition|exam prep|cards? (?:on|about|for)|(?<!slide )(?<!pitch )deck)\b""")),
        Spec("schedule", "Schedule", "reminders, routines, repeating tasks, timed jobs and triggers",
            listOf("create_routine", "schedule_command", "create_trigger", "set_alarm", "add_calendar_event", "open_view"), listOf("schedule", "device"),
            r("""\b(remind(?:er|ers)? ?(?:me)?|every (?:day|morning|evening|night|week|weekday|monday|tuesday|wednesday|thursday|friday|saturday|sunday|hour|\d+)|daily|weekly|routine|schedule (?:a|this|it|my)|wake me)\b""")),
        Spec("design", "Design", "anything visual or interactive, live preview: 3D scenes and models (three.js, orbit to look around), 2D art, websites and landing pages, " +
            "UI mockups and app screens, diagrams and charts as files to save or export, posters, flyers, slides, floor plans, product mockups, simple games",
            listOf(Designs.TOOL, "file_write", "open_view"), listOf("terminal", "media"),
            r(DESIGN_STRONG + "|" + DESIGN_OBJECT)),
        Spec("gallery", "Gallery", "images, pictures, logos, wallpapers, beats, music and other media Blaze makes",
            listOf("generate_image", "gallery"), listOf("media"),
            r("""\b(?:(?:make|generate|draw|create|paint|produce)\b.{0,40}\b(?:image|picture|pic|photo|logo|icon|wallpaper|art|artwork|illustration|beat|beats|song|instrumental|sticker|avatar)s?|my gallery|in (?:the|my) gallery)\b""")),
        Spec("skills", "Skills", "saved methods and workflows (skills), installing skill packs",
            listOf("save_workflow", "write_skill", "install_skill", "use_skill"), listOf("extras"),
            r("""\b((?:make|save|create|write|install|add)\b.{0,30}\b(?:skills?|workflows?)|skill library|my skills|my workflows|as a (?:skill|workflow))\b""")),
        Spec("projects", "Projects", "ongoing efforts with their own threads, brief and instructions",
            listOf("update_brief", "open_view"), emptyList(),
            r("""\b((?:new|start|create|open) (?:a )?project|my projects|project (?:for|about|called))\b""")),
        Spec("archive", "Archive", "past conversations, searched with search_chat (there is no chat list screen; answer in chat)",
            listOf("search_past_chats", "search_chat"), emptyList(),
            r("""\b((?:old|past|previous|earlier|archived) (?:chats?|conversations?)|chat history|what did (?:we|i) (?:say|talk|discuss)|we talked about|last time we)\b""")),
        Spec("memory", "Memory", "lasting facts about the user, their people, preferences and decisions",
            listOf("memory_save", "remember", "forget", "memory_search"), emptyList(),
            r("""\b(remember (?:that|this|my|me)|don'?t forget (?:that|my)|forget (?:that|my|about)|what do you (?:know|remember)|keep in mind|""" +
                """(?:show|list|see|view|read|display)\b.{0,20}\b(?:your |my |all |saved )?memories|(?:your|my) memories)\b""")),
        Spec("browser", "Browser", "websites Blaze opens, fills in or signs in to (background tabs; browser_show only when the user must step in)",
            listOf("browser_open", "browser_click", "browser_type", "browser_secure_input"), listOf("browser"),
            r("""(\b(?:open|go to|visit|log ?in to|sign ?in to|sign up (?:on|for)|fill (?:in|out))\b.{0,40}(?:\bsite\b|\bwebsite\b|\bportal\b|\.com\b|\.org\b|\.zm\b|https?://)|https?://)""")),
    )

    fun byId(id: String): Spec? = specs.firstOrNull { it.id == id }

    /**
     * The view a request needs, or null (plain chat). Slash commands and routines are left alone.
     * [followOn] = the last turn touched a design (v0.23.2): a short edit ("show engine", "add fins",
     * "make it taller", "open it so I can see") then goes to Design to change that same design.
     */
    fun route(text: String, followOn: Boolean = false): Spec? {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("/") || t.startsWith("[Scheduled routine")) return null
        var first = specs.firstOrNull { it.cue.containsMatchIn(t) }
        // v0.26.1: a diagram / chart asked for in chat is drawn inline in the reply (VisualRoute), not in Design,
        // unless something else in the ask is clearly Design work (a page, a poster, 3D…) or it asks for a file.
        if (first?.id == "design" && VisualRoute.inChat(t) && !designStrong.containsMatchIn(VisualRoute.withoutVisualWords(t))) first = null
        // "make a picture of a car": a thing to draw, with picture words, is Gallery unless it's clearly a design (3D, mockup, page…).
        if (first?.id == "design" && !designStrong.containsMatchIn(t) && byId("gallery")!!.cue.containsMatchIn(t)) first = byId("gallery")
        if (followOn && (first == null || first.id == "design") && isFollowOnEdit(t)) return byId("design")
        return first
    }

    private val designStrong = r(DESIGN_STRONG)
    private val editLead = r("""^(?:(?:ok(?:ay)?|now|and|also|please|pls|then|great|nice|cool|good|perfect|can you|could you|would you|will you|just|yeah|yes|hey blaze|blaze)[,!.]?\s+)*""" +
        """(?:show|hide|add|remove|delete|drop|make|change|turn|rotate|spin|move|put|give|use|swap|replace|resize|scale|zoom|colou?r|paint|fix|undo|redo|open|let me see|see|view|""" +
        """bigger|smaller|taller|shorter|wider|narrower|thinner|thicker|longer|more|less|darker|lighter|brighter|try|flip|mirror|cent(?:er|re)|align|increase|decrease|raise|lower|lift|""" +
        """extend|shrink|enlarge|attach|include|without|with|no|switch|set|label|animate|place)\b""")
    private val notDesign = r("""\b(calendar|e-?mails?|inbox|messages?|whatsapp|sms|texts?|calls?|alarms?|timers?|weather|news|battery|notifications?|music|songs?|playlist|volume|wi-?fi|bluetooth|reminders?|flash ?cards?|memory|memories|remember|know about me|contacts?|torch|brightness)\b""")

    /** A short change to what was just designed ("show engine", "make it taller", "add fins", "open it for me so I can see"). */
    fun isFollowOnEdit(text: String): Boolean {
        val t = text.trim().trimEnd('.', '!', '?')
        if (t.isEmpty() || t.startsWith("/") || t.split(Regex("\\s+")).size > 14) return false
        return editLead.containsMatchIn(t) && !notDesign.containsMatchIn(t)
    }

    /** The hint for a follow-on edit of the current design. */
    fun followOnHint(title: String, kind: String, path: String): String =
        "\nREQUIRED VIEW THIS TURN: Design, a follow-on edit of the current design \u201c" + title + "\u201d (" + kind + ", " + path + "). Change THAT design with design action=update " +
            (if (kind == "3d") "(set_parts = the parts to add or change, by id, only the keys that change; remove_parts = ids to drop; read it first with action=read to see the part ids) "
            else "(edits=[{find, replace}] or a new html; read it first with action=read) ") +
            "and keep everything else as it was. For \"open it\" / \"show me\" use design action=open. Don't start a new design, and don't just describe the change."

    /** All views a request touches, first = required. */
    fun routeAll(text: String): List<Spec> = if (route(text) == null) emptyList() else specs.filter { it.cue.containsMatchIn(text) }

    val PROMPT: String = buildString {
        append("\n\nVIEWS (always on; you use them without being asked). Blaze has views, screens the user can open from /views. ")
        append("HARD RULE: output that fits a view is CREATED in that view with its tool, then the view is opened (open_view) or named so the user can tap it, ")
        append("and the chat reply is one or two short lines (what you made, where). Never dump it as text in chat instead. ")
        append("You don't need the user to say \"use the view\": every turn, pick the view that fits.\n")
        for (s in specs) append("- ${s.name} (/${s.id}): ${s.holds}. Write with ${s.tools.filter { it != "open_view" }.ifEmpty { listOf("open_view") }.joinToString(" / ")}.\n")
        append("- Flashcards, \"quiz me\", \"test me\", study or revision → Learn: flashcards action=add (deck, cards=[{front, back}]), then open_view view=learn. Never write Q: / A: lists in chat.\n")
        append("- Anything visual or interactive → Design (it is not only for art and sites): 3D models and scenes (design action=create kind=3d parts=[…], three.js with orbit controls), " +
            "2D art (svg), sites, UI mockups, diagrams, charts, posters, slides, floor plans, product mockups, games (html). The Design view opens by itself. " +
            "EXCEPT a diagram, flowchart or chart asked for in chat: draw it inline in the reply (```mermaid / ```chart), no design tool, files or preview; Design only when they ask to save, export, edit or make a file. " +
            "Follow-ons (\"show the engine\", \"make it taller\", \"add fins\") change the CURRENT design with design action=update, never a new one or a text answer.\n")
        append("- Never say you created, updated or opened something in a view (\"I've updated the design\", \"view it in the design section\") unless that view's tool succeeded this turn; Blaze checks.\n")
        append("- \"Remind me\" or anything repeating → Schedule (create_routine / schedule_command / create_trigger). Pictures and beats → Gallery. A method or multi-step task to reuse → Skills (save_workflow with trigger, steps, checks).\n")
        append("- Other views (Jobs, Accounts, Agents, Usage, Settings…): open with open_view ask=true unless the user asked for that view.")
    }

    /** The per-turn required-view hint, injected before the first model call. */
    fun hint(s: Spec): String = when (s.id) {
        "learn" -> "\nREQUIRED VIEW THIS TURN: Learn. Put the cards in a Learn deck with flashcards action=add (deck = topic or course code + topic, " +
            "cards = [{front, back}], 10-25 cards, one fact each), then open_view view=learn, then reply in one or two lines (deck name, card count). " +
            "Do NOT write the questions and answers in chat. To quiz from an existing deck: flashcards action=due and ask one card at a time."
        "design" -> "\nREQUIRED VIEW THIS TURN: Design. Make it for real with the design tool: 3D (a rocket, car, building, product…) → design action=create kind=3d " +
            "title=… parts=[…] (8-40 parts, real proportions, y up, parts that should touch do); pages, UI mockups, posters, slides, charts, diagrams, floor plans, games → " +
            "design action=create html=<one complete document>; 2D art → svg. The Design view opens by itself. Then reply in one or two lines describing only what the tool result says " +
            "you made (a 3D model only if it says 3D; orbit/zoom only for 3D). " +
            "Don't paste the code in chat, and never say it's made unless the tool succeeded."
        "schedule" -> "\nREQUIRED VIEW THIS TURN: Schedule. Create it with create_routine / schedule_command / create_trigger (or set_alarm / add_calendar_event for one-off times) and say when it will run in one line."
        "gallery" -> "\nREQUIRED VIEW THIS TURN: Gallery. Make it with generate_image (art/photos) or run_code (beats, charts) saved under /root/phone/ so it lands in the Gallery; reply briefly."
        "skills" -> "\nREQUIRED VIEW THIS TURN: Skills. Save a workflow with save_workflow (name, trigger, steps, checks), or a skill with write_skill / install_skill; say its name in one line."
        "projects" -> "\nREQUIRED VIEW THIS TURN: Projects. Use open_view view=projects so the user can create or open the project there; keep the reply short."
        "archive" -> "\nREQUIRED VIEW THIS TURN: Archive. Look it up with search_chat / search_past_chats and answer in chat from what you find (quote the exact words when it helps). There is no chat list screen to open."
        "memory" -> "\nREQUIRED VIEW THIS TURN: Memory. Save, update or forget with memory_save / remember / forget (or look up with memory_search); confirm in one line. " +
            "To show what you remember (\"show me your memories\", \"what do you know about me\"), call memory_search query=all and list the lines in chat grouped plainly " +
            "(about you, preferences, people, projects…), then one line on how to change or delete one. Never open Design or another view for this."
        "browser" -> "\nREQUIRED VIEW THIS TURN: Browser. Do it in Blaze's background browser (browser_open, browser_click, browser_type, browser_secure_input); browser_show only if the user must step in."
        else -> "\nREQUIRED VIEW THIS TURN: ${s.name}. Create the result there with ${s.tools.joinToString(" / ")}, open or link it, and reply briefly."
    }

    // ---------------- Learn safety net ----------------

    /** The user asked for cards themselves (not just "help me study"), so a reply with no deck gets one re-prompt. */
    private val wantsCards = r("""\b(flash ?cards?|cards? (?:on|about|for|from)|(?:a |new )?deck|quiz(?:zes)?|test me)\b""")
    fun asksForCards(userText: String) = wantsCards.containsMatchIn(userText)

    private val strip = Regex("""^\s*(?:[-*•>]\s+|\d{1,3}[.)]\s+|#{1,6}\s*)*""")
    private fun clean(s: String) = s.replace("**", "").replace("__", "").replace(Regex("""(?<!\w)\*(?!\s)|(?<!\s)\*(?!\w)"""), "").replace("`", "").trim().trim('|').trim()

    private val qRe = Regex("""^(?:q(?:uestion)?|front|term|card\s*\d+\s*(?:front)?)\s*\d*\s*[:.\-–—]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val aRe = Regex("""^(?:a(?:nswer|ns)?|back|definition|def)\s*\d*\s*[:.\-–—]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val dashRe = Regex("""^(.{2,80}?)\s+[–—-]\s+(.{2,})$""")

    /**
     * Flashcard / quiz pairs written as text: "Q: … / A: …" (also Question/Answer, Front/Back, Term/Definition,
     * numbered and bold), two-column markdown tables, and "**Term** – definition" lines. Empty when it isn't cards.
     */
    fun cardsFrom(text: String): List<Pair<String, String>> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Pair<String, String>>()
        // 1) Q / A pairs (the answer may be on the same line after the question: "Q: x A: y").
        var pendingQ: String? = null
        for (raw in lines) {
            val l = clean(raw.replace(strip, ""))
            val inline = Regex("""^(?:q(?:uestion)?|front)\s*\d*\s*[:.]\s*(.+?)\s+(?:a(?:nswer)?|back)\s*[:.]\s*(.+)$""", RegexOption.IGNORE_CASE).find(l)
            if (inline != null) { out += inline.groupValues[1].trim() to inline.groupValues[2].trim(); pendingQ = null; continue }
            val q = qRe.find(l); val a = aRe.find(l)
            when {
                q != null && a == null -> pendingQ = q.groupValues[1].trim()
                a != null && pendingQ != null -> { out += pendingQ!! to a.groupValues[1].trim(); pendingQ = null }
            }
        }
        if (out.size >= 2) return out.filter { it.first.isNotBlank() && it.second.isNotBlank() }
        out.clear()
        // 2) A two-column table: | front | back |
        val rows = lines.filter { it.startsWith("|") && it.count { c -> c == '|' } == 3 }
        if (rows.size >= 3) {
            val body = rows.filterNot { it.replace(Regex("[|:\\-\\s]"), "").isEmpty() }
            val cells = body.map { it.trim('|').split('|').map { c -> clean(c) } }
            val data = if (cells.isNotEmpty() && cells[0].any { it.lowercase() in setOf("front", "question", "term", "q", "back", "answer", "definition", "a") }) cells.drop(1) else cells
            data.filter { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }.forEach { out += it[0] to it[1] }
            if (out.size >= 2) return out
            out.clear()
        }
        // 3) "**Term** – definition" (only bold terms, so ordinary sentences with dashes don't count)
        for (raw in lines) {
            val l = raw.replace(strip, "")
            if (!l.startsWith("**")) continue
            val m = dashRe.find(l) ?: Regex("""^\*\*(.{2,80}?)\*\*\s*[:–—-]\s*(.{2,})$""").find(l) ?: continue
            out += clean(m.groupValues[1]).trimEnd(':') to clean(m.groupValues[2])
        }
        return if (out.size >= 3) out else emptyList()
    }

    /** "make flashcards on the French revolution" → "The French revolution". */
    fun deckName(userText: String): String {
        val m = Regex("""\b(?:on|about|for|from|of|covering)\s+(?:the\s+topic\s+)?(.{2,60}?)(?:[.?!,]|\s+(?:please|for me|so|and quiz|then)\b|$)""", RegexOption.IGNORE_CASE)
            .find(userText.substringAfter(Regex("""(?i)flash ?cards?|quiz(?:zes)?|deck|test me|cards""").find(userText)?.value ?: "", userText))
        val raw = m?.groupValues?.get(1)?.trim()?.trim('"', '\'', '“', '”')?.removePrefix("my ")?.trim().orEmpty()
        return raw.replaceFirstChar { it.uppercase() }.take(40).ifBlank { "Cards" }
    }

    /** What the agent does with a final text reply on a Learn turn that never called the flashcards tool. */
    enum class Fix { NONE, CONVERT, REPROMPT }

    fun shouldConvert(userText: String, reply: String, calledFlashcards: Boolean, alreadyNudged: Boolean): Fix {
        if (calledFlashcards) return Fix.NONE
        if (route(userText)?.id != "learn") return Fix.NONE
        val cards = cardsFrom(reply)
        if (cards.size >= 2) return Fix.CONVERT
        // One quiz question at a time (interactive) is fine; a refusal or plain explanation of "make cards" is not.
        if (asksForCards(userText) && !alreadyNudged && !Regex("""(?i)\bquiz me\b|\btest me\b""").containsMatchIn(userText)) return Fix.REPROMPT
        return Fix.NONE
    }

    const val NUDGE = "You answered in chat, but flashcards belong in the Learn view (the user's standing rule). " +
        "Call flashcards action=add now with deck and cards=[{front, back}], then open_view view=learn, then reply in one or two lines. Don't list the cards in chat."

    fun converted(deck: String, added: Int, total: Int): String =
        "Made the **$deck** deck in Learn: $added card${if (added == 1) "" else "s"}" +
            (if (added < total) " (${total - added} were already there)" else "") +
            ". It's open now; cards come back right before you'd forget them (/learn)."
}
