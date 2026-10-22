package com.blaze.assistant.agent

import java.io.File

/**
 * Skills on every turn (v0.23.0). Luke's rule: before each turn's first model call, Blaze looks up the
 * skills related to the request. No extra model call: a keyword score over each skill's name,
 * description and keywords (front-matter `tags:` / `keywords:`), across bundled skills (assets/skills),
 * the user's own (~/skills) and learned ones (Learning, skills.json). Disabled skills never match.
 *
 * The top 1-3 go into the prompt in full within a character budget; the other matches by name only.
 * The agent shows "Skills: …" on the work card. [match] and [block] are pure (SkillMatchTest).
 */
object SkillMatch {
    data class Cand(val name: String, val description: String, val keywords: List<String>, val body: String,
                    val source: String, val on: Boolean = true)
    data class Hit(val cand: Cand, val score: Int)
    data class Result(val full: List<Cand>, val others: List<String>) {
        val names: List<String> get() = full.map { it.name } + others
        val isEmpty: Boolean get() = full.isEmpty() && others.isEmpty()
        companion object { val NONE = Result(emptyList(), emptyList()) }
    }

    const val MAX_FULL = 3
    /** ≈ 1,500 tokens for all full skills together. */
    const val BUDGET_CHARS = 6_000
    const val PER_SKILL_CHARS = 3_200
    const val MIN_SCORE = 3

    private val stop = ("the and for with that this you your me my our are was were can could would should will just please " +
        "what when where which who how why its it's about from into have has had get got make made want need let lets " +
        "some any all one two also very really then than them they their there here now today help hey blaze okay ok thanks").split(" ").toSet()

    private val genericName = setOf("phone", "task", "app", "skill", "flow", "speed", "general")

    /** Lowercase word stems: "flashcards" → "flashcard", "studying" → "study", "quizzes" → "quiz". */
    fun stem(w: String): String {
        var s = w
        if (s.length > 5 && s.endsWith("ying")) s = s.dropLast(3)          // studying → stud(y)
        else if (s.length > 5 && s.endsWith("ing")) s = s.dropLast(3)
        if (s.length > 4 && s.endsWith("zzes")) s = s.dropLast(3)          // quizzes → quiz
        else if (s.length > 4 && s.endsWith("ies")) s = s.dropLast(3) + "y"
        else if (s.length > 4 && s.endsWith("es") && (s.endsWith("ches") || s.endsWith("shes") || s.endsWith("xes"))) s = s.dropLast(2)
        else if (s.length > 3 && s.endsWith("s") && !s.endsWith("ss")) s = s.dropLast(1)
        return s
    }

    fun words(s: String): Set<String> = s.lowercase().replace("flash cards", "flashcards").replace("flash card", "flashcard")
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 && it !in stop }.map { stem(it) }.toSet()

    /** Name words ×3, keywords ×2 (a multi-word keyword phrase in the text ×3), description words ×1. */
    fun score(query: String, c: Cand): Int {
        val q = words(query); if (q.isEmpty()) return 0
        val low = " " + query.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ") + " "
        // v0.26.2: generic name words ("phone", "task", "app") count once, so "what's my phone's sound profile?" doesn't pull in phone-task-speed.
        val nameHits = words(c.name.replace('-', ' ')) intersect q
        var s = nameHits.fold(0) { acc, w -> acc + if (w in genericName) 1 else 3 }
        val kw = c.keywords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        s += 2 * (kw.filter { !it.contains(' ') }.flatMap { words(it) }.toSet() intersect q).size
        s += 3 * kw.count { it.contains(' ') && low.contains(" $it ") }
        s += (words(c.description) intersect q).size
        return s
    }

    /** The skills for [query]: disabled ones excluded, best first, top [maxFull] in full within [budget]. */
    fun match(query: String, cands: List<Cand>, maxFull: Int = MAX_FULL, budget: Int = BUDGET_CHARS, minScore: Int = MIN_SCORE,
              fullMin: Int = 0, fullWords: Int = 0, maxOthers: Int = 8): Result {
        if (query.isBlank()) return Result.NONE
        val hits = cands.asSequence().filter { it.on }.distinctBy { it.name.lowercase() }
            .map { Hit(it, score(query, it)) }.filter { it.score >= minScore }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.cand.name }).toList()
        val full = ArrayList<Cand>(); val others = ArrayList<String>()
        var left = budget
        for (h in hits) {
            val len = minOf(h.cand.body.length, PER_SKILL_CHARS)
            // v0.30.4: a body goes in full only on a strong match (score and distinct query words); weaker ones by name.
            val strong = h.score >= fullMin && (fullWords <= 0 || matchedWords(query, h.cand) >= fullWords)
            if (strong && full.size < maxFull && len <= left) { full += h.cand; left -= len } else others += h.cand.name
        }
        return Result(full, others.take(maxOthers))
    }

    /** Distinct query words the skill's name, keywords or description share with [query]. */
    fun matchedWords(query: String, c: Cand): Int {
        val q = words(query)
        val mine = words(c.name.replace('-', ' ')) + c.keywords.flatMap { words(it) } + words(c.description)
        return (q intersect mine).size
    }

    /** v0.30.4: per-turn bar for a skill body in full: score ≥ 5 from ≥ 2 distinct query words. */
    const val FULL_MIN = 5
    const val FULL_WORDS = 2
    /** v0.30.4: a learned (recorded) skill joins a plain device action only on this strong a match. */
    const val STRONG = 6

    /** For a plain device action: only the user's own learned skills on a strong match (they replay in seconds). */
    fun learnedOnly(query: String, cands: List<Cand> = cached()): Result = try {
        match(query, cands.filter { it.source == "learned" }, maxFull = 1, minScore = STRONG, fullMin = STRONG, fullWords = FULL_WORDS, maxOthers = 2)
    } catch (_: Throwable) { Result.NONE }

    /** The prompt block for a [Result] ("" when nothing matched). */
    fun block(r: Result): String {
        if (r.isEmpty) return ""
        return buildString {
            append("\n\nSKILLS FOR THIS REQUEST (looked up for you; follow them, they are the user's own way of doing it):\n")
            for (c in r.full) {
                append("### ${c.name} (${c.source})\n")
                val b = c.body.trim()
                append(if (b.length > PER_SKILL_CHARS) b.take(PER_SKILL_CHARS) + "\n…(more with use_skill ${c.name})" else b).append("\n\n")
            }
            if (r.others.isNotEmpty()) append("Other matching skills (read with use_skill if useful): ${r.others.joinToString(", ")}\n")
        }
    }

    /** The work-card line: "Skills: tutor, study-flashcards". */
    fun cardLine(r: Result): String = if (r.isEmpty) "" else "Skills: " + r.names.take(5).joinToString(", ")

    // ---------------- reading skills on the phone ----------------

    private fun frontMatter(md: String): Map<String, String> {
        val m = Regex("""^\s*---\s*\n(.*?)\n---""", RegexOption.DOT_MATCHES_ALL).find(md) ?: return emptyMap()
        return m.groupValues[1].lines().mapNotNull { l ->
            val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim().trim('"', '\'')
        }.toMap()
    }

    fun kwList(v: String?): List<String> = v.orEmpty().trim().removePrefix("[").removeSuffix("]").split(',')
        .map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }

    fun body(md: String): String = md.replace(Regex("""^\s*---\s*\n.*?\n---\s*\n?""", RegexOption.DOT_MATCHES_ALL), "").trim()

    /** One skill folder (with SKILL.md) as a candidate. */
    fun fromDir(dir: File, source: String, on: Boolean): Cand? {
        val f = File(dir, "SKILL.md").takeIf { it.isFile } ?: return null
        val md = try { f.readText() } catch (_: Exception) { return null }
        val fm = frontMatter(md)
        return Cand(fm["name"] ?: dir.name, fm["description"].orEmpty(), kwList(fm["tags"]) + kwList(fm["keywords"]), body(md), source, on)
    }

    /** Every skill on the phone: bundled + user packs in ~/skills, and learned skills. Disabled ones marked off. */
    fun candidates(): List<Cand> {
        val out = ArrayList<Cand>()
        try {
            val bundled = SkillLibrary.bundled().map { it.name }.toSet()
            for (p in SkillPacks.all()) fromDir(p.dir, if (p.dir.name in bundled) "bundled" else "user", SkillLibrary.isOn(p.dir.name))?.let { out += it }
        } catch (_: Throwable) { }
        try {
            for (s in Learning.skills.value) out += Cand(s.name, s.description, words(s.whenToUse).toList(),
                (s.description + "\n" + (if (s.whenToUse.isNotBlank()) "When: ${s.whenToUse}\n" else "") + s.steps +
                    (if (s.replay != null) "\n(run_skill ${s.name} replays the recorded phone steps)" else "")).trim(), "learned", true)
        } catch (_: Throwable) { }
        return out
    }

    /** The cached candidate list (re-read at most every 20 s, files can change under us). */
    @Volatile private var cache: Pair<Long, List<Cand>>? = null
    fun cached(): List<Cand> = try {
        val now = System.currentTimeMillis()
        cache?.takeIf { now - it.first < 20_000 }?.second ?: candidates().also { cache = now to it }
    } catch (_: Throwable) { emptyList() }

    // v0.26.1: an in-chat diagram / chart doesn't pull in the file-route skills (VisualRoute); it's drawn in the reply.
    fun forTurn(query: String): Result = try {
        // v0.33.3: "list my apps" is about the phone (list_apps), never a skill whose words overlap (product-listing).
        if (com.blaze.assistant.phone.AppFinder.isAppsAsk(query)) Result.NONE else
        match(query, VisualRoute.filter(query, cached()).filter { SkillGuard.strongHit(query, it) }, fullMin = FULL_MIN, fullWords = FULL_WORDS, maxOthers = 4)
    } catch (_: Throwable) { Result.NONE }

    fun invalidate() { cache = null; index = null }

    // ---------------- v0.26.2: skills by lookup (SkillIndex, Desktop's bm25 ranking) ----------------

    /** A candidate as an index row: name, description, when-to-use (keywords + the learned "When:" line). */
    fun doc(c: Cand): SkillIndex.Doc = SkillIndex.Doc(c.name, listOf(c.name, c.description,
        (c.keywords + listOfNotNull(Regex("(?m)^When: (.+)$").find(c.body)?.groupValues?.get(1))).joinToString(" ")),
        note = if (c.source == "learned" && c.body.contains("run_skill")) " [replayable with run_skill]" else "")

    /** The index for these candidates (enabled ones only; VisualRoute's skips applied by the caller). Pure. */
    fun indexOf(cands: List<Cand>): SkillIndex.Index = SkillIndex.skills(cands.filter { it.on }.distinctBy { it.name.lowercase() }.map { doc(it) })

    /** Rebuilt whenever the skill set changes: keyed on a signature of the candidates (install, toggle, learn, delete). */
    @Volatile private var index: Pair<Int, SkillIndex.Index>? = null
    fun index(cands: List<Cand> = cached()): SkillIndex.Index {
        val sig = cands.joinToString("\n") { "${it.name}\t${it.description}\t${it.on}\t${it.keywords}" }.hashCode()
        index?.takeIf { it.first == sig }?.let { return it.second }
        return indexOf(cands).also { index = sig to it }
    }

    /** The per-turn skills block: the 2-3 best matches with short descriptions and one line about find_skill. No name list. */
    fun lookupBlock(query: String, skip: Set<String> = emptySet()): String = if (com.blaze.assistant.phone.AppFinder.isAppsAsk(query)) "" else try {
        // v0.30.5: skills that only share a stray word with the request are never suggested (SkillGuard.strongHit).
        val cands = VisualRoute.filter(query, cached())
        val weak = cands.filter { !SkillGuard.strongHit(query, it) }.map { it.name }.toSet()
        SkillIndex.promptBlock(index(cands), query, skip + weak)
    } catch (_: Throwable) { "" }

    /** find_skill: search every enabled skill. */
    fun find(query: String): String = try { SkillIndex.findLine(index(), query) } catch (e: Throwable) { "Error: ${e.message}" }
}
