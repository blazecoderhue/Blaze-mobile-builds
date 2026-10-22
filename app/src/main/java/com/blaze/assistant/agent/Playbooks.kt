package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.26.2 field playbooks. Every specialist (built-in, user-made and Blaze-made) works from a playbook: a short
 * field standard with six sections (principles of the field, quality bar, best practices, common mistakes to
 * avoid, self-check, output format). Built-in ones ship in assets/specialists/<id>.md; custom ones are stored with
 * the specialist in the `specialists` pref (optional `playbook` key). The playbook goes into the specialist's
 * system prompt, and after its draft one review pass checks the draft against the Self-check list (redo once).
 *
 * Everything here is pure (PlaybooksTest): [validate] is the gate a Blaze-made (or generated) playbook must pass
 * before it is saved.
 */
object Playbooks {
    data class Section(val key: String, val title: String, val match: Regex, val minChars: Int)

    val SECTIONS = listOf(
        Section("principles", "Principles of the field", Regex("principle", RegexOption.IGNORE_CASE), 200),
        Section("quality", "Quality bar", Regex("quality|what good looks like", RegexOption.IGNORE_CASE), 150),
        Section("practices", "Best practices", Regex("best practice|practices", RegexOption.IGNORE_CASE), 200),
        Section("mistakes", "Common mistakes to avoid", Regex("mistake|pitfall|avoid", RegexOption.IGNORE_CASE), 150),
        Section("selfcheck", "Self-check", Regex("self[- ]?check|checklist", RegexOption.IGNORE_CASE), 120),
        Section("output", "Output format", Regex("output", RegexOption.IGNORE_CASE), 80),
    )
    const val MIN_CHECKS = 4
    const val MAX_CHARS = 9_000
    /** How much of a playbook goes into a prompt. */
    const val INJECT_CHARS = 6_000
    /** create_specialist / generation: tries before giving up. */
    const val MAX_TRIES = 3
    /** A playbook sharing this much of its 4-word phrases with another one is a copy. */
    const val COPY_OVERLAP = 0.45

    data class Check(val ok: Boolean, val problems: List<String>) {
        /** What the model is told when its playbook is rejected. */
        fun feedback(): String = if (ok) "OK" else "The playbook was rejected. Fix every point and send the whole playbook again:\n" +
            problems.joinToString("\n") { "- $it" } +
            "\nUse exactly these six '## ' headings: ${SECTIONS.joinToString(", ") { it.title }}. Make every line specific to this field (its own terms, tools, standards and failure modes), not advice that fits any job."
    }

    // ---------------- built-in playbooks (assets) ----------------

    /** Reads assets/specialists/<id>.md (set by Agent.init; tests set it to read from disk). */
    @Volatile var loader: ((String) -> String?)? = null
    private val cache = ConcurrentHashMap<String, String>()

    fun builtIn(id: String): String = cache[id] ?: (try { loader?.invoke(id) } catch (_: Throwable) { null }).orEmpty().also { if (it.isNotBlank()) cache[id] = it }

    fun reset() = cache.clear()

    /** The playbook a specialist works from: its own, else the shipped one for its id ("" when neither exists). */
    fun forSpec(s: Specialists.Spec): String = s.playbook.ifBlank { if (!s.custom) builtIn(s.id) else "" }

    // ---------------- parsing ----------------

    /** "## Heading" → body, in order. Text before the first heading is ignored. */
    fun headings(md: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var head: String? = null; val body = StringBuilder()
        for (line in md.lines()) {
            val m = Regex("^#{2,3}\\s+(.+?)\\s*#*\\s*$").find(line)
            if (m != null) { head?.let { out += it to body.toString().trim() }; head = m.groupValues[1]; body.clear() }
            else if (head != null) body.append(line).append('\n')
        }
        head?.let { out += it to body.toString().trim() }
        return out
    }

    /** Section key → body for the six known sections. Each heading goes to the first section it matches that isn't taken yet. */
    fun sections(md: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((h, b) in headings(md)) SECTIONS.firstOrNull { it.key !in out && it.match.containsMatchIn(h) }?.let { out[it.key] = b }
        return out
    }

    /** The Self-check items ("- [ ] …", "- …", "1. …"). */
    fun checklist(md: String): List<String> = (sections(md)["selfcheck"] ?: "").lines().map { it.trim() }
        .filter { Regex("^(?:[-*•]|\\d{1,2}[.)])\\s+").containsMatchIn(it) }
        .map { it.replace(Regex("^(?:[-*•]|\\d{1,2}[.)])\\s+(?:\\[[ xX]?]\\s*)?"), "").trim() }.filter { it.length >= 8 }

    // ---------------- validation ----------------

    private val stop = ("the and for with that this your you are was were will can could would should have has had from into about " +
        "what when where which who how why its them they their there here then than also very just only each every more most " +
        "make made does done doing want need used uses using user users task tasks work works good best right real thing things " +
        "specialist blaze help helps helping return returns result results answer answers keep give gives clear short").split(' ').toSet()

    fun stem(w: String): String {
        var s = w
        if (s.length > 5 && s.endsWith("ing")) s = s.dropLast(3)
        else if (s.length > 5 && s.endsWith("ers")) s = s.dropLast(3)
        else if (s.length > 4 && s.endsWith("er")) s = s.dropLast(2)
        if (s.length > 4 && s.endsWith("ies")) s = s.dropLast(3) + "y"
        else if (s.length > 4 && s.endsWith("es") && (s.endsWith("ches") || s.endsWith("shes") || s.endsWith("xes"))) s = s.dropLast(2)
        else if (s.length > 3 && s.endsWith("s") && !s.endsWith("ss")) s = s.dropLast(1)
        return s
    }

    fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 && it !in stop }.map { stem(it) }

    /** The words that name the field (from the specialist's name, id and prompt). */
    fun fieldTerms(field: String): Set<String> = words(field).toSet()

    private val generic = Regex("^(?:[-*•]|\\d{1,2}[.)])?\\s*(?:\\[[ xX]?]\\s*)?(?:always |be |make sure |ensure |try to |do your best|stay |keep it |don'?t forget|be careful|be professional|be accurate|be thorough|be helpful|do a good job|high[- ]quality|follow best practices|use common sense)[^.]{0,40}\\.?$", RegexOption.IGNORE_CASE)
    private val filler = Regex("lorem ipsum dolor|\\bTODO\\b|\\bTBD\\b|\\[insert|<insert|your text here|placeholder section", RegexOption.IGNORE_CASE)

    private val secretPatterns = listOf(
        "an API key" to Regex("\\bsk-[A-Za-z0-9_-]{16,}"),
        "a GitHub token" to Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}"),
        "an AWS key" to Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        "a Google API key" to Regex("\\bAIza[0-9A-Za-z_-]{30,}"),
        "a Slack token" to Regex("\\bxox[abpr]-[A-Za-z0-9-]{10,}"),
        "a private key" to Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        "a bearer token" to Regex("\\bBearer\\s+[A-Za-z0-9._~+/-]{20,}"),
        "a password or key value" to Regex("(?i)\\b(?:password|passwd|pwd|api[_ -]?key|secret|access[_ -]?token)\\s*[:=]\\s*['\"]?[^\\s'\"]{6,}"),
        "a long token" to Regex("\\b[A-Fa-f0-9]{40,}\\b|\\b[A-Za-z0-9+/]{48,}={0,2}"),
    )

    /** What looks like a credential in [md] (names of the kinds found). */
    fun secrets(md: String): List<String> = secretPatterns.filter { it.second.containsMatchIn(md) }.map { it.first }.distinct()

    private fun shingles(s: String): Set<String> {
        val w = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return if (w.size < 4) emptySet() else (0..w.size - 4).map { w.subList(it, it + 4).joinToString(" ") }.toSet()
    }

    /** Share of [a]'s 4-word phrases that also appear in [b] (0..1). */
    fun overlap(a: String, b: String): Double {
        val x = shingles(a); if (x.isEmpty()) return 0.0
        val y = shingles(b); if (y.isEmpty()) return 0.0
        return x.count { it in y }.toDouble() / x.size
    }

    /**
     * The gate for a playbook: all six sections, each long enough, at least [MIN_CHECKS] self-check items,
     * specific to the field ([field] = name + prompt), not generic boilerplate, not a copy of one of [others],
     * no credentials, not over [MAX_CHARS].
     */
    fun validate(md: String, field: String, others: List<String> = emptyList()): Check {
        val p = ArrayList<String>()
        val text = md.trim()
        if (text.length > MAX_CHARS) p += "Too long (${text.length} characters; keep it under $MAX_CHARS)."
        val secs = sections(text)
        for (s in SECTIONS) {
            val b = secs[s.key]
            if (b == null) p += "Missing section \"## ${s.title}\"."
            else if (b.length < s.minChars) p += "Section \"${s.title}\" is too thin (${b.length} characters; needs at least ${s.minChars} of real, field-specific content)."
        }
        if (secs["selfcheck"] != null) {
            val n = checklist(text).size
            if (n < MIN_CHECKS) p += "Self-check has $n checklist item(s); list at least $MIN_CHECKS concrete checks as \"- [ ] …\" lines, each one something you can verify in the draft."
        }
        if (filler.containsMatchIn(text)) p += "Remove placeholder text (lorem ipsum, TODO, [insert …])."
        // Field-specific: it uses the field's own words.
        val terms = fieldTerms(field)
        if (terms.isNotEmpty()) {
            val have = words(text).toSet()
            val hit = terms.count { it in have }
            val need = minOf(3, terms.size)
            if (hit < need) p += "Not specific to the field: it uses only $hit of the field's own terms (${terms.take(8).joinToString(", ")}). Write about this field's tools, standards and failure modes."
        }
        // Generic boilerplate: mostly one-size-fits-all lines, or too small a vocabulary.
        val items = text.lines().map { it.trim() }.filter { Regex("^(?:[-*•]|\\d{1,2}[.)])\\s+").containsMatchIn(it) }
        val gen = items.count { generic.matches(it) }
        if (items.size >= 4 && gen * 10 >= items.size * 3) p += "Too generic: $gen of ${items.size} lines are boilerplate (\"be accurate\", \"make sure…\", \"high quality\"). Replace them with concrete, checkable rules for this field."
        val vocab = words(text).toSet().size
        if (text.length >= 600 && vocab < 90) p += "Too repetitive or generic: only $vocab distinct content words. Add concrete detail (standards, numbers, tools, examples)."
        // Repeated sections (the same text pasted under several headings).
        val bodies = secs.values.map { it.lowercase().replace(Regex("\\s+"), " ") }.filter { it.length > 40 }
        if (bodies.size != bodies.distinct().size) p += "Two sections have the same text; each section needs its own content."
        // Copy of another playbook.
        for (o in others) if (o.isNotBlank() && overlap(text, o) >= COPY_OVERLAP) {
            val name = Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(o)?.groupValues?.get(1)?.trim()?.removeSuffix(" playbook")?.removeSuffix(" Playbook") ?: "another specialist's"
            p += "Copied from $name playbook (${(overlap(text, o) * 100).toInt()}% of its phrases). Write one for this field."; break
        }
        secrets(text).takeIf { it.isNotEmpty() }?.let { p += "Contains what looks like ${it.joinToString(" and ")}. Playbooks never hold credentials; remove it." }
        return Check(p.isEmpty(), p)
    }

    // ---------------- prompts ----------------

    /** The playbook block for a specialist's system prompt ("" when it has none). */
    fun inject(name: String, playbook: String): String {
        val b = playbook.trim(); if (b.isEmpty()) return ""
        return "\n\nPLAYBOOK — $name (your field's standard: follow it, and before you return run its Self-check; if any item fails, fix the work first):\n" +
            (if (b.length > INJECT_CHARS) b.take(INJECT_CHARS) + "\n…" else b) + "\n"
    }

    /** The whole system prompt of a specialist run (delegate with wait, or a background agent running as a specialist). */
    fun specialistSystem(s: Specialists.Spec, playbook: String, skills: String, now: String, memory: String, agentId: String? = null): String = buildString {
        append("You are Blaze's ${s.name} specialist")
        if (agentId != null) append(", working as background agent $agentId while the user keeps chatting with the main Blaze; nobody watches this chat")
        append(". ").append(s.prompt.trim())
        append(inject(s.name, playbook))
        append(skills)
        append("\nIt is $now.")
        append(memory)
        append("\nYou work on one brief. Stay inside it, don't ask questions unless you're truly blocked, and if a step fails twice, stop and report what you tried and what's left.")
        append("\nWhen done, reply with the result only, in the playbook's Output format.")
    }

    // ---------------- self-check review ----------------

    /** v0.45.2: [checked] = false when the review call timed out or failed (said plainly, never a silent pass). */
    data class Review(val pass: Boolean, val fixes: List<String>, val checked: Boolean = true)

    /** One cheap review call: the Self-check list and Quality bar against the draft. */
    fun reviewMessages(name: String, playbook: String, task: String, draft: String): JSONArray {
        val secs = sections(playbook)
        val checks = checklist(playbook).ifEmpty { listOf("The draft does what the brief asked, completely and specifically.") }
        val sys = "You review a $name specialist's draft against its playbook. Be strict but fair: judge only what the draft shows. " +
            "Reply PASS on the first line if every check holds. Otherwise reply FAIL on the first line, then one line per failed check: \"- <check>: <what exactly to fix>\". No other text."
        val user = buildString {
            append("SELF-CHECK:\n").append(checks.joinToString("\n") { "- $it" })
            secs["quality"]?.let { append("\n\nQUALITY BAR:\n").append(it.take(900)) }
            append("\n\nBRIEF:\n").append(task.trim().take(1_500))
            append("\n\nDRAFT:\n").append(draft.trim().take(6_000))
        }
        return JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", user))
    }

    /** PASS / FAIL + fixes. Anything unreadable counts as a pass, so a confused reviewer never loops the work. */
    fun parseReview(text: String): Review {
        val t = text.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "").trim()
        val first = t.lineSequence().map { it.trim().trim('*', '#', ' ') }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (!first.uppercase().startsWith("FAIL")) return Review(true, emptyList())
        val fixes = t.lines().drop(1).map { it.trim() }.filter { Regex("^(?:[-*•]|\\d{1,2}[.)])\\s+").containsMatchIn(it) }
            .map { it.replace(Regex("^(?:[-*•]|\\d{1,2}[.)])\\s+"), "").trim() }.filter { it.isNotEmpty() }.take(6)
            .ifEmpty { listOf(first.substringAfter(':', "").trim().ifEmpty { "The draft doesn't meet the playbook's quality bar." }) }
        return Review(false, fixes)
    }

    /** What the specialist gets when its draft failed the review (one redo). */
    fun redoPrompt(fixes: List<String>): String = "[Self-check] Your draft failed these playbook checks:\n" + fixes.joinToString("\n") { "- $it" } +
        "\nFix them now (use your tools if a fix needs it), then reply with the complete corrected result only, in the playbook's Output format."

    // ---------------- generating a playbook ----------------

    /** Model messages that write a playbook for a custom specialist; on a retry, [problems] and the [previous] draft. */
    fun generateMessages(name: String, prompt: String, problems: List<String> = emptyList(), previous: String = ""): JSONArray {
        val sys = "You write field playbooks for AI specialists. A playbook is the working standard of one field, written for a capable but inexperienced practitioner. " +
            "Use exactly these markdown headings, in order: " + SECTIONS.joinToString(", ") { "## ${it.title}" } + ". " +
            "Principles: the field's core principles. Quality bar: what a good result in this field concretely looks like. Best practices: specific techniques, tools and standards of the field. " +
            "Common mistakes to avoid: the field's real failure modes. Self-check: at least 5 \"- [ ] …\" items that can each be verified in a draft. Output format: the exact shape of the result. " +
            "Every line must be specific to this field: its terms, numbers, tools and standards. No generic advice (\"be accurate\", \"make sure\"), no placeholders, no credentials. 2,000 to 4,000 characters. Reply with the playbook only, starting with \"# <Name> playbook\"."
        val user = buildString {
            append("Specialist: $name\nWhat it does: ${prompt.trim().take(1_200)}")
            if (problems.isNotEmpty()) {
                append("\n\nYour last version was rejected:\n").append(problems.joinToString("\n") { "- $it" })
                if (previous.isNotBlank()) append("\n\nLast version:\n").append(previous.take(5_000))
                append("\n\nWrite the whole playbook again with every point fixed.")
            }
        }
        return JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", user))
    }

    /** The model's reply as a playbook: code fences and think tags dropped. */
    fun cleanGenerated(raw: String): String = raw.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
        .trim().removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim()
}
