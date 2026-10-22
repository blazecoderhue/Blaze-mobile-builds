package com.blaze.assistant.agent

/**
 * Specialists on every turn (v0.24.3, core skill blaze-specialists). Before the first model call Blaze picks the
 * specialist(s) the request looks like (shopping, booking, coding, research, study, design, writing, planning,
 * inbox, phone) with a keyword score — no extra model call — and:
 *  - attaches that specialist's skill packs to the turn's skills (full text within SkillMatch's budget),
 *  - switches on the delegate tool (ToolRouter group "extras"),
 *  - adds a short hint: split big work into independent pieces for specialists, keep small work,
 *  - shows "Specialist: X" on the work card.
 * [match], [attach], [hint], [cardLine] and [brief] are pure (SpecialistMatchTest).
 */
object SpecialistMatch {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** Cue patterns per specialist id. Each distinct match counts 1. Custom specialists match on their name words. */
    val cues: Map<String, List<Regex>> = mapOf(
        "shopper" to listOf(r("""\b(buy|buying|purchase|shop|shopping|cheapest|best price|prices?|deals?|discount|on sale|in stock)\b"""),
            r("""\bcompare\b.{0,40}\b(shops?|stores?|sellers?|prices?|phones?|laptops?|products?)\b"""), r("""\b(amazon|jumia|takealot|aliexpress|temu|shein|ebay)\b"""),
            r("""\border (?:me |a |an |some )""")),
        "booker" to listOf(r("""\b(book|booking|reserve|reservation|appointment)\b"""), r("""\b(flights?|hotels?|bus tickets?|train tickets?|tickets? for|table for|airbnb|lodge)\b""")),
        "coder" to listOf(r("""\b(code|coding|bug|debug|script|function|class|repo|repository|compile|build error|stack ?trace|exception|refactor|unit tests?|pull request)\b"""),
            r("""\b(kotlin|python|javascript|typescript|java|react|node|gradle|android studio|sql|html|css)\b""")),
        "researcher" to listOf(r("""\b(research|find out|look into|investigate|sources?|citations?|report on|deep dive|what'?s the latest|fact-?check)\b"""),
            r("""\bcompare\b""")),
        "tutor" to listOf(r("""\b(study|studying|exam|homework|assignment|revise|revision|quiz me|test me|flash ?cards?|lecture|tutor|teach me|explain\b.{0,30}\bto me)\b"""),
            r("""\b(course|semester|syllabus|cit\d{3}|net\d{3}|module)\b""")),
        "designer" to listOf(r("""\b(design|mock-?up|wireframe|logo|poster|flyer|landing page|website|web ?page|3-?d|ui|ux|slides?|slide deck|infographic|diagram)\b""")),
        "writer" to listOf(r("""\b(write|draft|essay|email|e-mail|letter|caption|post|blog|rewrite|proofread|edit my|summari[sz]e|cover letter|cv|résumé|resume)\b""")),
        "planner" to listOf(r("""\b(plan|planning|itinerary|break (?:it |this )?down|roadmap|timetable|schedule my|steps to|to-?do list)\b""")),
        "inbox" to listOf(r("""\b(inbox|my e-?mails?|gmail|unread|my messages|notifications|reply to (?:all|my))\b""")),
        "operator" to listOf(r("""\b(on my phone|in the app|open (?:the )?\w+ app|in settings|tap through|turn on .{0,20} in)\b""")),
    )

    /** Bundled skill packs each specialist loads (missing ones are skipped). */
    val skills: Map<String, List<String>> = mapOf(
        "shopper" to listOf("deep-research", "product-listing"),
        "booker" to listOf("planning-task-breakdown"),
        "coder" to listOf("blaze-coding-workflow", "debugging", "code-review", "writing-tests", "git-pr-workflow", "refactoring"),
        "researcher" to listOf("deep-research", "research-brief"),
        "tutor" to listOf("study-flashcards", "tutor", "study-planner", "lecture-notes"),
        "designer" to listOf("blaze-views", "landing-page-builder", "three-d-design", "slide-deck", "diagrams", "print-design"),
        "writer" to listOf("document-writing", "email-drafting", "essay-coach"),
        "planner" to listOf("planning-task-breakdown", "study-planner"),
        "inbox" to listOf("email-drafting", "whatsapp-replies"),
        "operator" to listOf("blaze-task-workflow"),
    )

    const val MAX = 2

    fun skillsFor(s: Specialists.Spec): List<String> = s.skills.ifEmpty { skills[s.id].orEmpty() }

    fun score(text: String, s: Specialists.Spec): Int {
        val list = cues[s.id]
        if (list != null) return list.count { it.containsMatchIn(text) }
        // Custom specialist: its name words.
        val words = s.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }
        return if (words.any { Regex("""\b${Regex.escape(it)}""", RegexOption.IGNORE_CASE).containsMatchIn(text) }) 1 else 0
    }

    /** The specialists for [text], best first, at most [max]. Slash commands and routines get none. */
    fun match(text: String, specs: List<Specialists.Spec> = Specialists.builtIn, max: Int = MAX): List<Specialists.Spec> {
        val t = text.trim()
        if (t.length < 6 || t.startsWith("/") || t.startsWith("[Scheduled routine")) return emptyList()
        return specs.withIndex().map { (i, s) -> Triple(s, score(t, s), i) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<Specialists.Spec, Int, Int>> { it.second }.thenBy { it.third })
            .take(max).map { it.first }
    }

    /**
     * Adds the specialists' skill packs to the turn's [base] skills: ones already in full stay, then the packs go in
     * full while [SkillMatch.MAX_FULL] and the budget allow, the rest by name. Disabled skills are never attached.
     */
    fun attach(base: SkillMatch.Result, specs: List<Specialists.Spec>, cands: List<SkillMatch.Cand>,
               budget: Int = SkillMatch.BUDGET_CHARS): SkillMatch.Result {
        if (specs.isEmpty()) return base
        val byName = cands.filter { it.on }.associateBy { it.name.lowercase() }
        val wanted = specs.flatMap { skillsFor(it) }.distinct().mapNotNull { byName[it.lowercase()] }
        if (wanted.isEmpty()) return base
        val full = base.full.toMutableList()
        val others = base.others.toMutableList()
        var left = budget - full.sumOf { minOf(it.body.length, SkillMatch.PER_SKILL_CHARS) }
        for (c in wanted) {
            if (full.any { it.name.equals(c.name, true) }) continue
            val len = minOf(c.body.length, SkillMatch.PER_SKILL_CHARS)
            if (full.size < SkillMatch.MAX_FULL && len <= left) { full += c; left -= len; others.removeAll { it.equals(c.name, true) } }
            else if (others.none { it.equals(c.name, true) }) others += c.name
        }
        return SkillMatch.Result(full, others.take(8))
    }

    /** The per-turn hint ("" when no specialist matched). */
    fun hint(specs: List<Specialists.Spec>): String {
        if (specs.isEmpty()) return ""
        val names = specs.joinToString(", ") { "${it.name} (${it.id})" }
        return "\n\nSPECIALISTS FOR THIS REQUEST: $names. Their skills are attached above; follow blaze-specialists. " +
            "Delegate only when it helps: independent parts that can run in parallel, or big research. Small or step-by-step work: do it yourself. " +
            "Otherwise call delegate once with one task per target (comparing 3 shops = 3 tasks; never two specialists on the same target), each a tight brief " +
            "(goal, what to return, limits, stop and report after two failed tries), and scope: one line for the user (\"Sending Researcher and Shopper to compare…\"), which delegate shows in the chat. " +
            "delegate doesn't block: keep helping, and when each result lands write a short follow-up from it; merge when the last is in. " +
            "Steer one with message_agent, stop one with cancel_agent. If a part needs a field no specialist covers, create_specialist makes one (with a real field playbook)."
    }

    fun cardLine(specs: List<Specialists.Spec>): String = if (specs.isEmpty()) "" else "Specialist: " + specs.joinToString(", ") { it.name }

    /** Skill text a delegated specialist gets in its own prompt (first [max] packs, trimmed). */
    fun brief(s: Specialists.Spec, cands: List<SkillMatch.Cand>, max: Int = 2, per: Int = 1_800): String {
        val byName = cands.filter { it.on }.associateBy { it.name.lowercase() }
        val packs = skillsFor(s).mapNotNull { byName[it.lowercase()] }.take(max)
        if (packs.isEmpty()) return ""
        return "\nSKILLS (follow them):\n" + packs.joinToString("\n\n") { "### ${it.name}\n" + it.body.trim().take(per) } +
            "\nIf a step fails twice in a row, stop and report what you tried and what's left."
    }
}
